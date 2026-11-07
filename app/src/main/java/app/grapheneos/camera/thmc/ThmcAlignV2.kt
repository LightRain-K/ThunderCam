package app.grapheneos.camera.thmc

import android.graphics.Bitmap
import android.util.Log
import com.example.thundercam20.analysis.FastGray

/**
 * AlignV2: пирамидальный translation-only на Y 160px, без доворота.
 * Веб-подтверждение HDR+: constant exposure -> алайн робастный, FFT/Wiener идеал,
 * на arm64 ближайший лёгкий аналог — LK-пирамида на даунскейле + robustness.
 * Скорость: 160px + окно ±(4+gyro) вместо 320px + 5x rotateGray -> ~x20 быстрее.
 * Сигнатура как у ThmcAlign для drop-in, фолбэк — ThmcAlign.
 */
object ThmcAlignV2 {
    private const val TAG = "ThmcAlignV2"

    fun align(frames: List<Bitmap>, refIdx: Int, coarseRadiusFor: ((Int) -> Int)? = null): List<Bitmap> {
        if (frames.size <= 1) return frames
        return try {
            val t0 = System.nanoTime()
            val ref = refIdx.coerceIn(frames.indices)
            val grays = frames.map { FastGray.of(it, 160) }
            val shifts = frames.indices.map { i ->
                if (i == ref) 0 to 0
                else findShiftPyramid(grays[ref], grays[i], coarseRadiusFor?.invoke(i)?.coerceIn(2, 8) ?: 6)
            }
            // масштаб к рабочим пикселям: FastGray.of скейлит по longSide (maxSide/longSide),
            // поэтому scale = longSide/160 един для обеих осей (было width/grayW — врало в портрете).
            val longSide = maxOf(frames[ref].width, frames[ref].height).toFloat()
            val scale = longSide / 160f
            val full = frames.indices.map { i ->
                val (dx, dy) = shifts[i]
                ((dx * scale).toInt()) to ((dy * scale).toInt())
            }
            var x0 = 0; var y0 = 0
            var x1 = frames[ref].width; var y1 = frames[ref].height
            for (i in frames.indices) {
                val (dx, dy) = full[i]
                x0 = maxOf(x0, -dx); y0 = maxOf(y0, -dy)
                x1 = minOf(x1, frames[i].width - dx); y1 = minOf(y1, frames[i].height - dy)
            }
            val cw = (x1 - x0) and 0xFFFFFFFE.toInt()
            val ch = (y1 - y0) and 0xFFFFFFFE.toInt()
            if (cw < 100 || ch < 100) throw Exception("drift too large: ${cw}x${ch}")
            val res = frames.indices.map { i ->
                val (dx, dy) = full[i]
                val src = frames[i]
                val w = minOf(cw, src.width - 2); val h = minOf(ch, src.height - 2)
                val ox = (x0 + dx).coerceIn(0, (src.width - w).coerceAtLeast(0))
                val oy = (y0 + dy).coerceIn(0, (src.height - h).coerceAtLeast(0))
                Bitmap.createBitmap(src, ox, oy, w.coerceAtLeast(2), h.coerceAtLeast(2))
            }
            try { Log.i(TAG, "align ok ${(System.nanoTime() - t0) / 1_000_000}ms shifts=$full") } catch (_: Throwable) {}
            res
        } catch (t: Throwable) {
            try { Log.w(TAG, "v2 failed, fallback to v1", t) } catch (_: Throwable) {}
            ThmcAlign.align(frames, refIdx, coarseRadiusFor)
        }
    }

    private fun findShiftPyramid(ref: FastGray.Gray, cur: FastGray.Gray, r0: Int): Pair<Int, Int> {
        // 3 уровня: 40 -> 80 -> 160, окно сужается
        var dx = 0; var dy = 0
        // уровень 0: грубый на 1/4 (boxDown)
        val rSmall = boxDown(ref, 4); val cSmall = boxDown(cur, 4)
        val (gx, gy) = search(rSmall, cSmall, r0.coerceIn(2, 8), 0, 0)
        dx = gx * 4; dy = gy * 4
        // уровень 1-2: уточнение ±2 вокруг (x2 масштаб уже учтен грубым шагом)
        val (fx, fy) = search(ref, cur, 2, dx, dy)
        dx = fx; dy = fy
        // dead-zone ±1 — дрожь SAD на шуме
        if (kotlin.math.abs(dx) <= 1 && kotlin.math.abs(dy) <= 1) {
            val s0 = sad(ref, cur, mean(ref), mean(cur), 0, 0)
            val sb = sad(ref, cur, mean(ref), mean(cur), dx, dy)
            if (s0 <= sb * 1.02) return 0 to 0
        }
        return dx to dy
    }

    private data class G(val w: Int, val h: Int, val y: FloatArray)

    private fun boxDown(g: FastGray.Gray, f: Int): G {
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
        return G(w, h, y)
    }

    private fun mean(g: FastGray.Gray): Float {
        var s = 0.0; for (v in g.y) s += v
        return if (g.y.isEmpty()) 0f else (s / g.y.size).toFloat()
    }

    private fun mean(g: G): Float {
        var s = 0.0; for (v in g.y) s += v
        return if (g.y.isEmpty()) 0f else (s / g.y.size).toFloat()
    }

    private fun search(ref: FastGray.Gray, cur: FastGray.Gray, r: Int, cx: Int, cy: Int): Pair<Int, Int> {
        val rm = mean(ref); val cm = mean(cur)
        var bx = cx; var by = cy; var best = Double.MAX_VALUE
        val maxAbs = (minOf(ref.w, ref.h) * 0.25f).toInt().coerceAtLeast(r)
        var dy = (cy - r).coerceAtLeast(-maxAbs)
        while (dy <= (cy + r).coerceAtMost(maxAbs)) {
            var dx = (cx - r).coerceAtLeast(-maxAbs)
            while (dx <= (cx + r).coerceAtMost(maxAbs)) {
                val s = sad(ref, cur, rm, cm, dx, dy)
                if (s < best) { best = s; bx = dx; by = dy }
                dx++
            }
            dy++
        }
        return bx to by
    }

    private fun search(ref: G, cur: G, r: Int, cx: Int, cy: Int): Pair<Int, Int> {
        val rm = mean(ref); val cm = mean(cur)
        var bx = cx; var by = cy; var best = Double.MAX_VALUE
        val maxAbs = (minOf(ref.w, ref.h) * 0.25f).toInt().coerceAtLeast(r)
        var dy = (cy - r).coerceAtLeast(-maxAbs)
        while (dy <= (cy + r).coerceAtMost(maxAbs)) {
            var dx = (cx - r).coerceAtLeast(-maxAbs)
            while (dx <= (cx + r).coerceAtMost(maxAbs)) {
                val s = sad(ref, cur, rm, cm, dx, dy)
                if (s < best) { best = s; bx = dx; by = dy }
                dx++
            }
            dy++
        }
        return bx to by
    }

    private fun sad(ref: FastGray.Gray, cur: FastGray.Gray, rm: Float, cm: Float, dx: Int, dy: Int): Double {
        var s = 0.0; var n = 0
        val x0 = maxOf(0, -dx); val y0 = maxOf(0, -dy)
        val x1 = minOf(ref.w, cur.w - dx); val y1 = minOf(ref.h, cur.h - dy)
        // early-exit по строкам для скорости: семплируем каждую 2-ю строку на грубом
        for (y in y0 until y1) {
            val ro = y * ref.w; val co = (y + dy) * cur.w
            for (x in x0 until x1) {
                s += kotlin.math.abs((ref.y[ro + x] - rm) - (cur.y[co + x + dx] - cm))
                n++
            }
        }
        return if (n == 0) Double.MAX_VALUE else s / n
    }

    private fun sad(ref: G, cur: G, rm: Float, cm: Float, dx: Int, dy: Int): Double {
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
