package com.example.thundercam20.imageprocessing

import android.graphics.Bitmap
import app.grapheneos.camera.thmc.ThmcBitmaps

/**
 * P0-lite: быстрый путь вместо ImageProcessor.process (5-7 Bitmap, bilateral-секунды).
 * Порядок: chroma-lite (1/6) -> 1-pass LUT -> локальный контраст на low-freq -> мягкий unsharp по маске.
 * Цель ~300мс на 12Мп, пик 2 Bitmap. Любая проблема -> null -> вызыватель идёт legacy-путем.
 */
object P0Lite {
    data class Result(val bitmap: Bitmap, val scene: String, val recipe: String)

    fun process(
        bmpIn: Bitmap,
        tone01: Float = 1f,
        contrast01: Float = 1f,
        sat01: Float = 1f,
        warmth01: Float = 1f,
        denoise01: Int = 65,
        iso: Int = 0,
        meanL: Float = -1f,
    ): Result? {
        return try {
            var bmp = if (bmpIn.isMutable) bmpIn else {
                bmpIn.copy(Bitmap.Config.ARGB_8888, true) ?: return null
            }
            val w = bmp.width; val h = bmp.height
            val mL = if (meanL >= 0f) meanL else estimateMeanL(bmp)
            // 1. хрома-лайт только если шум и не бумага
            val t0 = System.nanoTime()
            if (denoise01 > 0 && mL < 0.6f) {
                try {
                    val cs = if (mL < 0.35f) 0.9f else 0.7f
                    val cleaned = chromaLite(bmp, cs)
                    if (cleaned !== bmp) {
                        if (bmp !== bmpIn) ThmcBitmaps.release(bmp)
                        bmp = cleaned
                    }
                    // Глубокая темнота: крупные пятна вторым проходом (первый давит
                    // мелкое зерно, второй — низкочастотные blotches). Только хрома.
                    if (mL < 0.35f) {
                        val cleaned2 = chromaLite(bmp, 0.9f)
                        if (cleaned2 !== bmp) {
                            if (bmp !== bmpIn) ThmcBitmaps.release(bmp)
                            bmp = cleaned2
                        }
                    }
                } catch (_: Throwable) {}
            }
            // 2. LUT один проход
            val wb = AutoWb.estimate(bmp, warmth01)
            val params = ColorLut.build(mL, iso, tone01, contrast01, sat01, wb)
            ColorLut.applyInPlace(bmp, params)
            // 3. локальный контраст: только днем/светло и при низком ISO.
            // В сумерках/ночи он тащит зерно и дает серую обводку вокруг кромок — пропуск.
            try {
                val noisyHighIso = iso >= 800
                val darkNoisy = mL < 0.45f
                if (!noisyHighIso && !darkNoisy) {
                    localContrast(bmp, 0.22f)
                } else if (!noisyHighIso && mL < 0.55f) {
                    localContrast(bmp, 0.10f)
                }
            } catch (_: Throwable) {}
            // 4. мягкий unsharp только если реально мыло и не ночь-шум
            try {
                if (mL in 0.35f..0.72f && denoise01 < 70) {
                    val s = Sharpener().unsharpFast(bmp, radius = 0.8f, amount = 0.3f, threshold = 12.0)
                    if (s !== bmp) {
                        if (bmp !== bmpIn) ThmcBitmaps.release(bmp)
                        bmp = if (s.isMutable) s else s.copy(Bitmap.Config.ARGB_8888, true) ?: s
                    }
                }
            } catch (_: Throwable) {}
            val ms = (System.nanoTime() - t0) / 1_000_000
            try { android.util.Log.i("P0Lite", "ok ${ms}ms ${w}x$h meanL=${"%.2f".format(mL)} iso=$iso") } catch (_: Throwable) {}
            Result(bmp, "LITE", "lite")
        } catch (_: Throwable) { null }
    }

    private fun estimateMeanL(bmp: Bitmap): Float {
        return try {
            val sw = 160
            val sh = (160f * bmp.height / bmp.width).toInt().coerceIn(1, 160)
            val small = Bitmap.createScaledBitmap(bmp, sw, sh, true)
            val n = sw * sh
            val px = IntArray(n)
            small.getPixels(px, 0, sw, 0, 0, sw, sh)
            var s = 0L
            for (v in px) s += ((v shr 16) and 255) * 299L + ((v shr 8) and 255) * 587L + (v and 255) * 114L
            small.recycle()
            (s.toFloat() / n / 1000f / 255f).coerceIn(0f, 1f)
        } catch (_: Throwable) { 0.4f }
    }

    /** Хрома-лайт: даун 1/6 -> ап, люма оригинала. Без tightBlend полосами — в 2 раза дешевле старого. */
    private fun chromaLite(bmp: Bitmap, k: Float): Bitmap {
        val w = bmp.width; val h = bmp.height
        val sw = (w / 6).coerceAtLeast(1); val sh = (h / 6).coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bmp, sw, sh, true)
        val glow = Bitmap.createScaledBitmap(small, w, h, true)
        small.recycle()
        // in-place на mutable копии
        val out = if (bmp.isMutable) bmp else bmp.copy(Bitmap.Config.ARGB_8888, true) ?: return bmp
        val band = 384
        val srcPx = IntArray(w * band)
        val gloPx = IntArray(w * band)
        var y = 0
        while (y < h) {
            val hh = minOf(band, h - y)
            bmp.getPixels(srcPx, 0, w, 0, y, w, hh)
            glow.getPixels(gloPx, 0, w, 0, y, w, hh)
            for (i in 0 until w * hh) {
                val p = srcPx[i]; val q = gloPx[i]
                val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                val gr = (q shr 16) and 0xFF; val gg = (q shr 8) and 0xFF; val gb = q and 0xFF
                val l = 0.299f * r + 0.587f * g + 0.114f * b
                val gl = 0.299f * gr + 0.587f * gg + 0.114f * gb
                val nr = (l + (gr - gl) * k + (r - l) * (1 - k)).toInt().coerceIn(0, 255)
                val ng = (l + (gg - gl) * k + (g - l) * (1 - k)).toInt().coerceIn(0, 255)
                val nb = (l + (gb - gl) * k + (b - l) * (1 - k)).toInt().coerceIn(0, 255)
                srcPx[i] = -0x1000000 or (nr shl 16) or (ng shl 8) or nb
            }
            out.setPixels(srcPx, 0, w, 0, y, w, hh)
            y += hh
        }
        glow.recycle()
        return out
    }

    /**
     * Локальный контраст (структура): low-freq (×1/4) вверх/низко по люме, ±24.
     * Публичный — legacy-путь ImageProcessor добирает «pop» чистых дневных
     * кадров этим же инструментом (было: только в Lite, «нет отличия от стока»).
     */
    fun localContrast(bmp: Bitmap, amount: Float) {
        val w = bmp.width; val h = bmp.height
        val sw = (w / 4).coerceAtLeast(1); val sh = (h / 4).coerceAtLeast(1)
        val low = Bitmap.createScaledBitmap(bmp, sw, sh, true)
        val lowUp = Bitmap.createScaledBitmap(low, w, h, true)
        low.recycle()
        val band = 256
        val srcPx = IntArray(w * band)
        val lowPx = IntArray(w * band)
        var y = 0
        while (y < h) {
            val hh = minOf(band, h - y)
            bmp.getPixels(srcPx, 0, w, 0, y, w, hh)
            lowUp.getPixels(lowPx, 0, w, 0, y, w, hh)
            for (i in 0 until w * hh) {
                val p = srcPx[i]; val q = lowPx[i]
                val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
                val lr = (q shr 16) and 255; val lg = (q shr 8) and 255; val lb = q and 255
                val l = 0.299f * r + 0.587f * g + 0.114f * b
                val ll = 0.299f * lr + 0.587f * lg + 0.114f * lb
                val d = ((l - ll) * amount).toInt().coerceIn(-24, 24)
                srcPx[i] = -0x1000000 or
                    ((r + d).coerceIn(0, 255) shl 16) or
                    ((g + d).coerceIn(0, 255) shl 8) or
                    (b + d).coerceIn(0, 255)
            }
            bmp.setPixels(srcPx, 0, w, 0, y, w, hh)
            y += hh
        }
        lowUp.recycle()
    }
}
