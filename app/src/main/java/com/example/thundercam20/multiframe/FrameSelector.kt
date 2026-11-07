package com.example.thundercam20.multiframe

import android.graphics.Bitmap
import com.example.thundercam20.analysis.FastGray

/**
 * Класс для выбора лучших кадров из серии
 * Удаляет кадры с сильным движением, размытием или плохим фокусом.
 * Та же логика и пороги, но на FastGray — миллисекунды вместо минут.
 */
class FrameSelector {

    private var previousGray: FastGray.Gray? = null
    private val motionThreshold = 50f
    private val blurThreshold = 100f

    /**
     * Проверяет, является ли кадр хорошим
     */
    fun isGoodFrame(bitmap: Bitmap, previousBitmap: Bitmap? = null): Boolean {
        val g = FastGray.of(bitmap)
        val prev = previousBitmap?.let { FastGray.of(it) } ?: previousGray
        if (prev != null && FastGray.motionFraction(g, prev, motionThreshold) > 0.1f) return false
        if (FastGray.meanAbsLaplacian(g) < blurThreshold) return false
        if (FastGray.meanContrast(g) <= 20) return false
        previousGray = g
        return true
    }

    /**
     * Резчайший кадр серии (lucky imaging для зума: мерж на дрожи даёт кашу,
     * один резкий — нет). Та же метрика резкости, что в отбраковке.
     */
    fun selectSharpest(frames: List<Bitmap>): Bitmap {
        if (frames.size <= 1) return frames.first()
        var best = frames[0]
        var bestScore = -1.0
        for (f in frames) {
            try {
                val s = FastGray.meanAbsLaplacian(FastGray.of(f, 320))
                if (s > bestScore) { bestScore = s; best = f }
            } catch (_: Exception) {}
        }
        try { android.util.Log.i("LuckyZoom", "sharpest=$bestScore") } catch (_: Throwable) {}
        return best
    }

    /**
     * Выбирает лучшие кадры из списка
     */
    fun selectBestFrames(frames: List<Bitmap>, maxFrames: Int = 5): List<Bitmap> {
        val goodFrames = mutableListOf<Bitmap>()
        var prev: Bitmap? = null
        for (f in frames) {
            if (isGoodFrame(f, prev)) {
                goodFrames.add(f)
                if (goodFrames.size >= maxFrames) break
            }
            prev = f
        }
        return goodFrames
    }
}
