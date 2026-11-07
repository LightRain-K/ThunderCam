package com.example.thundercam20.camera

import android.graphics.Bitmap
import android.graphics.PointF
import kotlin.math.abs

/**
 * Лёгкий трекинг без OpenCV (чтобы не крашить на TECNO без native lib).
 * Работает на Bitmap 160×120, ищет патч 24×24 вокруг точки методом SAD в окне ±12px.
 * Достаточно для удержания фокуса на слабых, 10fps, без Mat.
 */
class FocusTracker {

    private var norm: PointF? = null // 0-1
    private var viewW = 1f
    private var viewH = 1f
    private var enabled = false
    private var prevPatch: ByteArray? = null
    private var prevPatchW = 0
    private var prevPatchH = 0
    private var lostCount = 0
    // EMA-сглаживание выхода (убирает плавание) + скорость для coast при срывах
    private var lastVdx = 0f
    private var lastVdy = 0f
    private var smX = 0f
    private var smY = 0f
    private var smInit = false

    fun start(x: Float, y: Float, w: Float, h: Float) {
        viewW = w; viewH = h
        norm = PointF((x / w).coerceIn(0f, 1f), (y / h).coerceIn(0f, 1f))
        prevPatch = null
        enabled = true
        lostCount = 0
        lastVdx = 0f; lastVdy = 0f; smInit = false
    }

    fun stop() { enabled = false; norm = null; prevPatch = null; lostCount = 0; smInit = false }

    fun isTracking(): Boolean = enabled && norm != null

    fun getPoint(): PointF? {
        val n = norm ?: return null
        // Сглаженный выход (EMA 0.7) — кольцо не дёргается на джиттере поиска
        return if (smInit) PointF(smX * viewW, smY * viewH)
        else PointF(n.x * viewW, n.y * viewH)
    }
    fun getNorm(): PointF? = norm

    /** Принятие хода: скорость для coast + EMA. Сырое norm — для поиска, наружу — гладкое. */
    private fun acceptMove(nx: Float, ny: Float) {
        val pn = norm
        val cx = nx.coerceIn(0f, 1f); val cy = ny.coerceIn(0f, 1f)
        if (pn != null) {
            lastVdx = cx - pn.x; lastVdy = cy - pn.y
        }
        norm = PointF(cx, cy)
        if (smInit) {
            smX = smX * 0.3f + cx * 0.7f
            smY = smY * 0.3f + cy * 0.7f
        } else {
            smX = cx; smY = cy; smInit = true
        }
    }

    // Телеметрия для диагностики (ThmcTrackAnalyzer показывает в тосте)
    var lastDx = 0
    var lastDy = 0
    var lastScore = -1
    var lastError = ""
    var framesSeen = 0
    fun getLost(): Int = lostCount
    fun isEnabled(): Boolean = enabled
    fun debug(): String = "en=$enabled n=$norm f=$framesSeen dx=$lastDx dy=$lastDy score=$lastScore lost=$lostCount err=$lastError"

    fun onYFrame(yBytes: ByteArray, w: Int, h: Int, rotationDeg: Int = 0): PointF? {
        framesSeen++
        if (!enabled || norm == null) return null
        try {
            // поворот сенсора — оси перепутаны если 90/270
            val (cxRaw, cyRaw) = when (rotationDeg) {
                90 -> Pair((norm!!.y * w).toInt(), ((1f - norm!!.x) * h).toInt())
                270 -> Pair(((1f - norm!!.y) * w).toInt(), (norm!!.x * h).toInt())
                180 -> Pair(((1f - norm!!.x) * w).toInt(), ((1f - norm!!.y) * h).toInt())
                else -> Pair((norm!!.x * w).toInt(), (norm!!.y * h).toInt())
            }
            val cx = cxRaw.coerceIn(0, w - 1)
            val cy = cyRaw.coerceIn(0, h - 1)
            val patchSize = 24
            // Окно адаптивное: в норме ±20, при срыве (резкий рывок) ±36
            val search = if (lostCount > 0) 36 else 20

            if (prevPatch == null) {
                // проверка текстуры — если патч однородный (<12 std), расширяем
                val p = extractPatch(yBytes, w, h, cx, cy, patchSize)
                var sum = 0; var sum2 = 0
                for (b in p) { val v = b.toInt() and 0xFF; sum += v; sum2 += v * v }
                val mean = sum.toFloat() / p.size
                var vari = 0f; for (b in p) { val d = (b.toInt() and 0xFF) - mean; vari += d * d }
                vari /= p.size
                if (vari < 80) {
                    // однородный — увеличиваем патч до 32 для захвата краёв
                    prevPatch = extractPatch(yBytes, w, h, cx, cy, 32)
                    prevPatchW = 32; prevPatchH = 32
                } else {
                    prevPatch = p; prevPatchW = patchSize; prevPatchH = patchSize
                }
                return getPoint()
            }

            val prev = prevPatch ?: return getPoint()
            val curPatchSize = prevPatchW
            // двухпроходной поиск: coarse step 2 (точнее старт), затем refine step 1 вокруг лучшего
            var bestDx = 0; var bestDy = 0
            var bestScore = Int.MAX_VALUE
            // coarse
            for (dy in -search..search step 2) {
                for (dx in -search..search step 2) {
                    val nx = cx + dx; val ny = cy + dy
                    if (nx - curPatchSize/2 < 0 || nx + curPatchSize/2 >= w || ny - curPatchSize/2 < 0 || ny + curPatchSize/2 >= h) continue
                    val curPatch = extractPatch(yBytes, w, h, nx, ny, curPatchSize)
                    var sad = 0
                    // zero-mean SAD для устойчивости к яркости
                    var sumCur = 0; for (b in curPatch) sumCur += b.toInt() and 0xFF
                    val meanCur = sumCur / curPatch.size
                    var sumPrev = 0; for (b in prev) sumPrev += b.toInt() and 0xFF
                    val meanPrev = sumPrev / prev.size
                    for (i in curPatch.indices) {
                        sad += abs(((curPatch[i].toInt() and 0xFF) - meanCur) - ((prev[i].toInt() and 0xFF) - meanPrev))
                        if (sad >= bestScore) break
                    }
                    if (sad < bestScore) { bestScore = sad; bestDx = dx; bestDy = dy }
                }
            }
            // refine вокруг лучшего ±4 step1
            var bestDx2 = bestDx; var bestDy2 = bestDy; var bestScore2 = bestScore
            for (dy in (bestDy - 4)..(bestDy + 4)) {
                for (dx in (bestDx - 4)..(bestDx + 4)) {
                    if (dx == bestDx && dy == bestDy) continue
                    val nx = cx + dx; val ny = cy + dy
                    if (nx - curPatchSize/2 < 0 || nx + curPatchSize/2 >= w || ny - curPatchSize/2 < 0 || ny + curPatchSize/2 >= h) continue
                    val curPatch = extractPatch(yBytes, w, h, nx, ny, curPatchSize)
                    var sad = 0
                    var sumCur = 0; for (b in curPatch) sumCur += b.toInt() and 0xFF
                    val meanCur = sumCur / curPatch.size
                    var sumPrev = 0; for (b in prev) sumPrev += b.toInt() and 0xFF
                    val meanPrev = sumPrev / prev.size
                    for (i in curPatch.indices) {
                        sad += abs(((curPatch[i].toInt() and 0xFF) - meanCur) - ((prev[i].toInt() and 0xFF) - meanPrev))
                        if (sad >= bestScore2) break
                    }
                    if (sad < bestScore2) { bestScore2 = sad; bestDx2 = dx; bestDy2 = dy }
                }
            }
            bestDx = bestDx2; bestDy = bestDy2; bestScore = bestScore2
            lastDx = bestDx; lastDy = bestDy; lastScore = bestScore

            val threshold = curPatchSize * curPatchSize * 14 // zero-mean, порог ниже
            // Патч обновляем только на уверенных совпадениях — иначе дрейф в сторону
            val goodThreshold = threshold * 0.5
            if (bestScore > threshold) {
                lostCount++
                // Coast: пока ищем, едем по последней скорости (резкие рывки переживаем)
                val cn = norm
                if (cn != null && lostCount <= 20 && (lastVdx != 0f || lastVdy != 0f)) {
                    norm = PointF((cn.x + lastVdx).coerceIn(0f, 1f), (cn.y + lastVdy).coerceIn(0f, 1f))
                }
                if (lostCount > 20) { stop(); return null }
                // даже при потере — двигаем немного к лучшему, но не обновляем патч
                if (bestScore < threshold * 1.5) {
                    val nx = (cx + bestDx).coerceIn(0, w - 1)
                    val ny = (cy + bestDy).coerceIn(0, h - 1)
                    val (normX, normY) = when (rotationDeg) {
                        90 -> Pair(1f - ny.toFloat() / h, nx.toFloat() / w)
                        270 -> Pair(ny.toFloat() / h, 1f - nx.toFloat() / w)
                        180 -> Pair(1f - nx.toFloat() / w, 1f - ny.toFloat() / h)
                        else -> Pair(nx.toFloat() / w, ny.toFloat() / h)
                    }
                    acceptMove(normX, normY)
                }
            } else {
                lostCount = 0
                val nx = (cx + bestDx).coerceIn(0, w - 1)
                val ny = (cy + bestDy).coerceIn(0, h - 1)
                val (normX, normY) = when (rotationDeg) {
                    90 -> Pair(1f - ny.toFloat() / h, nx.toFloat() / w)
                    270 -> Pair(ny.toFloat() / h, 1f - nx.toFloat() / w)
                    180 -> Pair(1f - nx.toFloat() / w, 1f - ny.toFloat() / h)
                    else -> Pair(nx.toFloat() / w, ny.toFloat() / h)
                }
                acceptMove(normX, normY)
                // обновляем патч только при хорошем совпадении, медленно 0.15
                if (bestScore < goodThreshold) {
                    val newPatch = extractPatch(yBytes, w, h, nx, ny, curPatchSize)
                    for (i in prev.indices) {
                        val blended = ((prev[i].toInt() and 0xFF) * 85 + (newPatch[i].toInt() and 0xFF) * 15) / 100
                        prev[i] = blended.toByte()
                    }
                }
            }
            return getPoint()
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}:${e.message}"
            return getPoint()
        }
    }

    private fun extractPatch(yBytes: ByteArray, w: Int, h: Int, cx: Int, cy: Int, size: Int): ByteArray {
        val half = size / 2
        val patch = ByteArray(size * size)
        var idx = 0
        for (dy in -half until half) {
            for (dx in -half until half) {
                val x = (cx + dx).coerceIn(0, w - 1)
                val y = (cy + dy).coerceIn(0, h - 1)
                patch[idx++] = yBytes[y * w + x]
            }
        }
        return patch
    }

    fun onFrameFromBitmap(bmp: Bitmap): PointF? {
        // даунскейл до 160 для скорости
        val small = Bitmap.createScaledBitmap(bmp, 160, 120, true)
        val w = small.width; val h = small.height
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        // to Y
        val yBytes = ByteArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            yBytes[i] = (0.299 * r + 0.587 * g + 0.114 * b).toInt().toByte()
        }
        small.recycle()
        return onYFrame(yBytes, w, h)
    }
}
