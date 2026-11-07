package app.grapheneos.camera.thmc

import android.graphics.Bitmap
import android.util.Log

/**
 * Быстрый взвешенный мерж (резкие кадры тяжелее):
 * плоские FloatArray-аккумуляторы + bulk getPixels/setPixels.
 * Веса приходят готовыми из QC (относительная резкость) — не пересчитываем.
 */
object ThmcMerge {
    private const val TAG = "ThmcMerge"

    /**
     * Винер для fixed-burst (HDR+ style): тайл 16x16, вес var/(var+k*noise^2).
     * frames уже выровнены, refIdx — опора (резчайший). null никогда — фолбэк внутрь.
     */
    fun noiseForIso(iso: Int): Float = when {
        iso <= 0 -> 6f // ISO неизвестен — середина
        iso <= 400 -> 4f
        iso <= 800 -> 6f
        iso <= 1600 -> 8f
        iso <= 3200 -> 11f
        else -> 14f
    }

    fun mergeWiener(frames: List<Bitmap>, refIdx: Int, noise: Float, rejectThr: Int = 28): Bitmap {        try {
            com.example.thundercam20.imageprocessing.NativeProcessor.mergeWiener(
                frames, refIdx, noise, rejectThr
            )?.let { return it }
        } catch (_: Throwable) {}
        // фолбэк: робастное среднее с опорой
        val w = frames.indices.map { 1 }
        val ref = frames.getOrNull(refIdx)
        return mergeWeighted(frames, w, ref, rejectThr)
    }

    fun mergeWeighted(frames: List<Bitmap>, weights: List<Int>): Bitmap =
        mergeWeighted(frames, weights, null, 30)

    /**
     * Взвешенное среднее + робастность: вклад пикселя кадра, отличающегося от опоры
     * больше rejectThr (по любому каналу), отбрасывается — прохожие/блики не мажутся.
     * ref = null → старое чистое среднее. Пиксель, отброшенный везде, берётся из опоры.
     */
    fun mergeWeighted(frames: List<Bitmap>, weights: List<Int>, ref: Bitmap?, rejectThr: Int): Bitmap {
        require(frames.isNotEmpty())
        if (frames.size == 1) return frames[0]
        // Лучший путь: натив без FloatArray-аккумуляторов (0 вместо 50-144МБ транзиента).
        try {
            com.example.thundercam20.imageprocessing.NativeProcessor.mergeWeightedN(frames, weights, ref, rejectThr)?.let { return it }
        } catch (_: Throwable) {}
        val t0 = System.nanoTime()
        val w = frames[0].width; val h = frames[0].height; val n = w * h
        val useRef = ref != null && ref.width == w && ref.height == h
        var refPx: IntArray? = null
        if (useRef) {
            val a = IntArray(n)
            ref!!.getPixels(a, 0, w, 0, 0, w, h)
            refPx = a
        }
        val wSum = weights.sum().coerceAtLeast(1).toFloat()
        val rAcc = FloatArray(n); val gAcc = FloatArray(n); val bAcc = FloatArray(n)
        val wAcc = if (useRef) FloatArray(n) else null
        val px = IntArray(n)
        var rejected = 0L
        for (fi in frames.indices) {
            val f = frames[fi]
            if (f.width != w || f.height != h) continue
            val wt = weights.getOrElse(fi) { 1 }.toFloat() / wSum
            if (wt <= 0f) continue
            f.getPixels(px, 0, w, 0, 0, w, h)
            if (!useRef) {
                for (i in 0 until n) {
                    val p = px[i]
                    rAcc[i] += ((p shr 16) and 255) * wt
                    gAcc[i] += ((p shr 8) and 255) * wt
                    bAcc[i] += (p and 255) * wt
                }
            } else {
                for (i in 0 until n) {
                    val p = px[i]
                    val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
                    val rp = refPx!![i]
                    val dr = r - ((rp shr 16) and 255)
                    val dg = g - ((rp shr 8) and 255)
                    val db = b - (rp and 255)
                    var m = if (dr < 0) -dr else dr
                    val ag = if (dg < 0) -dg else dg
                    val ab = if (db < 0) -db else db
                    if (ag > m) m = ag
                    if (ab > m) m = ab
                    if (m > rejectThr) {
                        rejected++
                        continue
                    }
                    rAcc[i] += r * wt
                    gAcc[i] += g * wt
                    bAcc[i] += b * wt
                    wAcc!![i] += wt
                }
            }
        }
        val out = IntArray(n)
        if (!useRef) {
            for (i in 0 until n) {
                val r = rAcc[i].toInt().coerceIn(0, 255)
                val g = gAcc[i].toInt().coerceIn(0, 255)
                val b = bAcc[i].toInt().coerceIn(0, 255)
                out[i] = -16777216 or (r shl 16) or (g shl 8) or b
            }
        } else {
            for (i in 0 until n) {
                val ws = wAcc!![i]
                if (ws > 1e-6f) {
                    val r = (rAcc[i] / ws).toInt().coerceIn(0, 255)
                    val g = (gAcc[i] / ws).toInt().coerceIn(0, 255)
                    val b = (bAcc[i] / ws).toInt().coerceIn(0, 255)
                    out[i] = -16777216 or (r shl 16) or (g shl 8) or b
                } else {
                    out[i] = refPx!![i]
                }
            }
        }
        val res = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        res.setPixels(out, 0, w, 0, 0, w, h)
        Log.i(TAG, "merge ok ${(System.nanoTime() - t0) / 1_000_000}ms ${w}x$h rej=$rejected")
        return res
    }
}
