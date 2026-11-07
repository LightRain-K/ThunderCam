package app.grapheneos.camera.thmc

import android.graphics.Bitmap
import android.util.Log
import com.example.thundercam20.analysis.FastGray

/**
 * Быстрое равнение дрожи рук: только сдвиг (translation), без поворота.
 * SAD с нормировкой среднего (устойчив к разной яркости плеч) на 2 уровнях
 * пирамиды + применение сдвига кропом (O(1), без варпа). Цель <0.5с на серию.
 * Поворот игнорируем: при ручной дроже сдвиг доминирует, остаток прячут мерж и шумодав.
 */
object ThmcAlign {
    private const val TAG = "ThmcAlign"

    data class Shift(val dx: Int, val dy: Int, val angle: Float)

    /**
     * Равняет кадры на опорный refIdx. Возвращает кропы общего пересечения (один размер).
     * @param coarseRadiusFor радиус грубого поиска (ед. ~80px) для кадра i.
     * Гиро-приор: спокойные кадры ищут в узком окне (быстрее), трясущиеся — в полном.
     * Знак приора не используется (оси телефона vs сенсора), только модуль — регресса нет.
     */
    fun align(frames: List<Bitmap>, refIdx: Int, coarseRadiusFor: ((Int) -> Int)? = null): List<Bitmap> {
        if (frames.size <= 1) return frames
        return try {
            val t0 = System.nanoTime()
            val ref = refIdx.coerceIn(frames.indices)
            // Серые превью 320 для поиска сдвига+доворота
            val grays = frames.map { FastGray.of(it, 320) }
            val shifts = frames.indices.map { i ->
                if (i == ref) Shift(0, 0, 0f)
                else findShift(grays[ref], grays[i], coarseRadiusFor?.invoke(i)?.coerceIn(4, 16) ?: 16)
            }
            val sx = 320f / frames[ref].width
            // Доворот нативом (Matrix, миллисекунды), затем кроп-сдвиг
            val derotated = frames.indices.map { i ->
                val a = shifts[i].angle
                if (a == 0f) frames[i]
                else try {
                    val m = android.graphics.Matrix()
                    m.postRotate(a, frames[i].width / 2f, frames[i].height / 2f)
                    Bitmap.createBitmap(frames[i], 0, 0, frames[i].width, frames[i].height, m, true)
                } catch (_: Throwable) { frames[i] }
            }
            // Сдвиги в пикселях рабочих кадров
            val full = shifts.map { s -> ((s.dx / sx).toInt()) to ((s.dy / sx).toInt()) }
            // Все сдвиги — относительно опоры. Пиксель опоры (x,y) = пиксель кадра i (x+dx, y+dy).
            // Общее пересечение в координатах опоры, затем кроп каждого кадра. Размеры равны по построению.
            var x0 = 0; var y0 = 0
            var x1 = frames[ref].width; var y1 = frames[ref].height
            for (i in frames.indices) {
                val (dx, dy) = full[i]
                x0 = maxOf(x0, -dx); y0 = maxOf(y0, -dy)
                x1 = minOf(x1, frames[i].width - dx); y1 = minOf(y1, frames[i].height - dy)
            }
            val cw = (x1 - x0) and 0xFFFFFFFE.toInt()
            val ch = (y1 - y0) and 0xFFFFFFFE.toInt()
            // Дрожь больше кадра — честный фолбэк, а не каша
            if (cw < 100 || ch < 100) throw Exception("drift too large: ${cw}x${ch}")
            // Кроп не больше кадра (защита от вырождения при дикой дрожи)
            val res = frames.indices.map { i ->
                val (dx, dy) = full[i]
                val src = derotated[i]
                val w = minOf(cw, src.width - 2); val h = minOf(ch, src.height - 2)
                val ox = (x0 + dx).coerceIn(0, (src.width - w).coerceAtLeast(0))
                val oy = (y0 + dy).coerceIn(0, (src.height - h).coerceAtLeast(0))
                Bitmap.createBitmap(src, ox, oy, w.coerceAtLeast(2), h.coerceAtLeast(2))
            }
            // Промежуточные довёрнутые чистим (оригиналы чистит вызыватель)
            for (i in derotated.indices) {
                if (derotated[i] !== frames[i]) try { derotated[i].recycle() } catch (_: Throwable) {}
            }
            Log.i(TAG, "align ok ${(System.nanoTime() - t0) / 1_000_000}ms shifts=$full")
            res
        } catch (t: Throwable) {
            Log.w(TAG, "align fallback: as-is", t)
            frames
        }
    }

    /** Сдвиг+доворот кадра относительно опоры (поиск коррекции: как довернуть cur к ref). */
    private fun findShift(ref: FastGray.Gray, cur: FastGray.Gray, coarseRange: Int = 16): Shift {
        // Уровень 1: грубый сдвиг на ~80px, диапазон ±coarseRange (= ±4× на 320)
        val rSmall = boxDown(ref, 4)
        val cSmall = boxDown(cur, 4)
        val (dx0, dy0) = searchLevel(rSmall, cSmall, coarseRange)
        // Уровень 2: доворот ±2° (шаг 1°) + точный сдвиг ±4 вокруг грубого×4.
        // Угол уже является коррекцией (вращаем cur и меряем) — применять как есть.
        var bestA = 0f; var bestDx = dx0 * 4; var bestDy = dy0 * 4
        var best = Double.MAX_VALUE
        for (ad in -2..2) {
            val rot = if (ad == 0) cur else rotateGray(cur, ad.toFloat())
            val (dx, dy) = searchLevel(ref, rot, 4, bestDx, bestDy)
            val s = sadAt(ref, rot, dx, dy)
            if (s < best) { best = s; bestA = ad.toFloat(); bestDx = dx; bestDy = dy }
        }
        // Dead-zone: микросдвиг ±2 без доворота, неотличимый от нуля (SAD в пределах 2%),
        // — это дрожь SAD на шуме, а не реальный сдвиг. На опоре края иначе «съезжают».
        if (bestA == 0f && kotlin.math.abs(bestDx) <= 2 && kotlin.math.abs(bestDy) <= 2) {
            val s0 = sadAt(ref, cur, 0, 0)
            if (s0 <= best * 1.02) return Shift(0, 0, 0f)
        }
        return Shift(bestDx, bestDy, bestA)
    }

    /**
     * Фокусное в пикселях серого превью (для гиро-приора сдвига).
     * 0 = неизвестно → вызыватель ищет полным диапазоном.
     */
    fun focalPxGray(camera: androidx.camera.core.Camera, grayW: Int): Float {
        return try {
            val info = androidx.camera.camera2.interop.Camera2CameraInfo.from(camera.cameraInfo)
            val f = info.getCameraCharacteristic(
                android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
            )?.firstOrNull() ?: return 0f
            val sensor = info.getCameraCharacteristic(
                android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE
            ) ?: return 0f
            if (sensor.width <= 0f || grayW <= 0) return 0f
            f.toFloat() / sensor.width * grayW
        } catch (_: Throwable) { 0f }
    }

    private fun sadAt(ref: FastGray.Gray, cur: FastGray.Gray, dx: Int, dy: Int): Double {
        return sad(ref, cur, mean(ref), mean(cur), dx, dy)
    }

    /** Поворот серого кадра на угол (билинейно, центр). Для поиска доворота на превью. */
    private fun rotateGray(g: FastGray.Gray, angleDeg: Float): FastGray.Gray {
        val w = g.w; val h = g.h
        val out = FloatArray(w * h)
        val rad = Math.toRadians(angleDeg.toDouble())
        val cos = Math.cos(rad); val sin = Math.sin(rad)
        val cx = (w - 1) / 2.0; val cy = (h - 1) / 2.0
        for (y in 0 until h) for (x in 0 until w) {
            // Обратное отображение: откуда взять пиксель
            val sx = cos * (x - cx) + sin * (y - cy) + cx
            val sy = -sin * (x - cx) + cos * (y - cy) + cy
            out[y * w + x] = sampleBilinear(g, sx, sy)
        }
        return FastGray.Gray(w, h, out)
    }

    private fun sampleBilinear(g: FastGray.Gray, x: Double, y: Double): Float {
        val x0 = x.toInt(); val y0 = y.toInt()
        if (x0 < 0 || y0 < 0 || x0 + 1 >= g.w || y0 + 1 >= g.h) return 0f
        val fx = (x - x0).toFloat(); val fy = (y - y0).toFloat()
        val a = g.y[y0 * g.w + x0]; val b = g.y[y0 * g.w + x0 + 1]
        val c = g.y[(y0 + 1) * g.w + x0]; val d = g.y[(y0 + 1) * g.w + x0 + 1]
        return a * (1 - fx) * (1 - fy) + b * fx * (1 - fy) + c * (1 - fx) * fy + d * fx * fy
    }

    private data class Gray(val w: Int, val h: Int, val y: FloatArray)

    private fun boxDown(g: FastGray.Gray, f: Int): Gray {
        val w = (g.w / f).coerceAtLeast(1); val h = (g.h / f).coerceAtLeast(1)
        val y = FloatArray(w * h)
        for (yy in 0 until h) for (xx in 0 until w) {
            var s = 0f; var n = 0
            for (dy in 0 until f) for (dx in 0 until f) {
                val sx = xx * f + dx; val sy = yy * f + dy
                if (sx < g.w && sy < g.h) { s += g.y[sy * g.w + sx]; n++ }
            }
            y[yy * w + xx] = if (n > 0) s / n else 0f
        }
        return Gray(w, h, y)
    }

    private fun mean(g: FastGray.Gray): Float {
        var s = 0.0; for (v in g.y) s += v
        return if (g.y.isEmpty()) 0f else (s / g.y.size).toFloat()
    }

    private fun mean(g: Gray): Float {
        var s = 0.0; for (v in g.y) s += v
        return if (g.y.isEmpty()) 0f else (s / g.y.size).toFloat()
    }

    /** Поиск минимума SAD в окне ±range вокруг (cx,cy). Возвращает абсолютный сдвиг. */
    private fun searchLevel(ref: FastGray.Gray, cur: FastGray.Gray, range: Int, cx: Int = 0, cy: Int = 0): Pair<Int, Int> {
        val rm = mean(ref); val cm = mean(cur)
        var bestDx = cx.coerceIn(-range * 4, range * 4); var bestDy = cy.coerceIn(-range * 4, range * 4)
        // Ограничиваем абсолютный поиск разумным диапазоном превью
        val maxAbs = (minOf(ref.w, ref.h) * 0.25f).toInt().coerceAtLeast(range)
        var best = Double.MAX_VALUE
        // Шаг 2 на грубом уровне для скорости, шаг 1 на точном
        val step = if (range > 4) 2 else 1
        var dy = (cy - range).coerceAtLeast(-maxAbs)
        while (dy <= (cy + range).coerceAtMost(maxAbs)) {
            var dx = (cx - range).coerceAtLeast(-maxAbs)
            while (dx <= (cx + range).coerceAtMost(maxAbs)) {
                val s = sad(ref, cur, rm, cm, dx, dy)
                if (s < best) { best = s; bestDx = dx; bestDy = dy }
                dx += step
            }
            dy += step
        }
        return bestDx to bestDy
    }

    private fun searchLevel(ref: Gray, cur: Gray, range: Int, cx: Int = 0, cy: Int = 0): Pair<Int, Int> {
        val rm = mean(ref); val cm = mean(cur)
        val maxAbs = (minOf(ref.w, ref.h) * 0.25f).toInt().coerceAtLeast(range)
        var bestDx = cx; var bestDy = cy
        var best = Double.MAX_VALUE
        val step = if (range > 4) 2 else 1
        var dy = (cy - range).coerceAtLeast(-maxAbs)
        while (dy <= (cy + range).coerceAtMost(maxAbs)) {
            var dx = (cx - range).coerceAtLeast(-maxAbs)
            while (dx <= (cx + range).coerceAtMost(maxAbs)) {
                val s = sad(ref, cur, rm, cm, dx, dy)
                if (s < best) { best = s; bestDx = dx; bestDy = dy }
                dx += step
            }
            dy += step
        }
        return bestDx to bestDy
    }

    private fun sad(ref: FastGray.Gray, cur: FastGray.Gray, rm: Float, cm: Float, dx: Int, dy: Int): Double {
        var s = 0.0; var n = 0
        val x0 = maxOf(0, -dx); val y0 = maxOf(0, -dy)
        val x1 = minOf(ref.w, cur.w - dx); val y1 = minOf(ref.h, cur.h - dy)
        for (y in y0 until y1) {
            val ro = y * ref.w; val co = (y + dy) * cur.w
            for (x in x0 until x1) {
                s += kotlin.math.abs((ref.y[ro + x] - rm) - (cur.y[co + x + dx] - cm))
                n++
            }
        }
        return if (n == 0) Double.MAX_VALUE else s / n
    }

    private fun sad(ref: Gray, cur: Gray, rm: Float, cm: Float, dx: Int, dy: Int): Double {
        var s = 0.0; var n = 0
        val x0 = maxOf(0, -dx); val y0 = maxOf(0, -dy)
        val x1 = minOf(ref.w, cur.w - dx); val y1 = minOf(ref.h, cur.h - dy)
        for (y in y0 until y1) {
            val ro = y * ref.w; val co = (y + dy) * cur.w
            for (x in x0 until x1) {
                s += kotlin.math.abs((ref.y[ro + x] - rm) - (cur.y[co + x + dx] - cm))
                n++
            }
        }
        return if (n == 0) Double.MAX_VALUE else s / n
    }
}
