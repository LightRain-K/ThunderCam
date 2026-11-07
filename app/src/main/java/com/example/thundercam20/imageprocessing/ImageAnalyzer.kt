package com.example.thundercam20.imageprocessing

import android.graphics.Bitmap
import com.example.thundercam20.analysis.FastGray
import kotlin.math.min

/**
 * Анализатор изображения для вычислительной фотографии
 * Проверяет: шум, темноту, размытие.
 * Та же математика и пороги, но на FastGray (один getPixels + даунскейл 480)
 * вместо тысяч JNI getPixel — было минутами, стало миллисекундами.
 */
class ImageAnalyzer {

    /**
     * Шум в основном в плоских зонах. Контраст картины ≠ шум.
     * Лапласиан по зонам с низким градиентом. Порт из thundercam20 (thr 4.5).
     */
    fun hasHighNoise(bitmap: Bitmap, threshold: Float = 4.5f): Boolean {
        return try {
            val g = FastGray.of(bitmap, 320)
            val y = g.y
            if (y.isEmpty()) return false
            var flatCount = 0
            var flatLapSum = 0.0
            val w = g.w; val h = g.h
            for (yy in 2 until h - 2) {
                for (xx in 2 until w - 2) {
                    val idx = yy * w + xx
                    val v = y[idx].toInt() and 0xFF
                    val gx = (y[idx + 1].toInt() and 0xFF) - (y[idx - 1].toInt() and 0xFF)
                    val gy = (y[idx + w].toInt() and 0xFF) - (y[idx - w].toInt() and 0xFF)
                    val grad = kotlin.math.abs(gx) + kotlin.math.abs(gy)
                    if (grad < 8) {
                        val lap = Math.abs(-4 * v + (y[idx - 1].toInt() and 0xFF) + (y[idx + 1].toInt() and 0xFF) + (y[idx - w].toInt() and 0xFF) + (y[idx + w].toInt() and 0xFF))
                        flatLapSum += lap.toDouble()
                        flatCount++
                        if (flatCount > 2000) break
                    }
                }
                if (flatCount > 2000) break
            }
            if (flatCount == 0) return false
            (flatLapSum / flatCount) > threshold
        } catch (_: Throwable) {
            try {
                val g = FastGray.of(bitmap)
                FastGray.meanContrast(g) > 24f
            } catch (_: Throwable) { false }
        }
    }

    /**
     * Измерение σ люминального шума ПОЛНОГО разрешения: сетка по кадру с шагом
     * 4, плоские зоны (|grad| < 24 — кромки отсекаются; широкий порог, т.к.
     * само зерно σ=6 даёт |grad|~17 и узкий гейт выбросил бы ровно шумные
     * зоны), гистограмма |лапласиана|, МЕДИАНА (робастна к просочившимся
     * рёбрам) с пересчётом в масштаб mean|lap| (med≈0.845·mean для полунормали
     * → ×1.18) и формулой NoiseReducer: σ = 1.2533·value/6 — ЕДИНИЦЫ СОГЛАСОВАНЫ
     * с sigmaFromIso. hasHighNoise меряет на 320px-даунскейле, где зерно
     * успокательно умирает; здесь зерно честно в нативных 0..255 уровнях.
     * Цена: ~0.75·h строк getPixels + ~800K отсчётов — миллисекунды.
     * Возвращает -1 при любой проблеме (вызыватель тогда живёт по старым гейтам).
     */
    fun measureNoiseSigma(bitmap: Bitmap): Double {
        return try {
            val w = bitmap.width; val h = bitmap.height
            if (w < 64 || h < 64) return -1.0
            val stride = 4
            val raw = IntArray(w)
            val lumaUp = FloatArray(w)
            val lumaCur = FloatArray(w)
            val lumaDn = FloatArray(w)
            fun loadLuma(dst: FloatArray, yy: Int) {
                bitmap.getPixels(raw, 0, w, 0, yy, w, 1)
                for (i in 0 until w) {
                    val p = raw[i]
                    dst[i] = 0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
                }
            }
            val bins = IntArray(33) // |lap| шагом по 4 уровня, 128+ — в последний бин
            var cnt = 0L
            var y = stride
            while (y < h - stride) {
                loadLuma(lumaUp, y - 1)
                loadLuma(lumaCur, y)
                loadLuma(lumaDn, y + 1)
                var x = stride
                while (x < w - stride) {
                    val grad = Math.abs(lumaCur[x + 1] - lumaCur[x - 1]) +
                        Math.abs(lumaDn[x] - lumaUp[x])
                    if (grad < 24.0) {
                        val lap = Math.abs(
                            -4.0 * lumaCur[x] + lumaCur[x - 1] + lumaCur[x + 1] +
                                lumaUp[x] + lumaDn[x]
                        )
                        bins[(lap / 4.0).toInt().coerceIn(0, 32)]++
                        cnt++
                    }
                    x += stride
                }
                y += stride
            }
            if (cnt < 400) -1.0
            else {
                val med = DenoiseCommon.medianFromHist(bins, cnt.toInt())
                // бин = 4 уровня (центр +2), медиана→среднее ×1.18, σ-формула NoiseReducer
                ((med * 4.0 + 2.0) * 1.18 * 1.2533 / 6.0).coerceIn(0.0, 30.0)
            }
        } catch (_: Throwable) { -1.0 }
    }

    /**
     * Проверка уровня освещенности
     * Возвращает среднюю яркость и флаг, если изображение слишком темное
     */
    fun analyzeBrightness(bitmap: Bitmap): BrightnessResult {
        val g = FastGray.of(bitmap)
        val avg = g.mean().toDouble()
        return BrightnessResult(avg, avg < 80) // Порог темноты как был
    }

    /**
     * Проверка уровня размытия
     * Использует дисперсию Лапласиана
     * Возвращает true, если изображение размыто
     */
    fun hasBlur(bitmap: Bitmap, threshold: Float = 95f): Boolean {
        return try {
            val g = FastGray.of(bitmap, 480)
            FastGray.varianceLaplacian(g) < threshold
        } catch (_: Throwable) {
            try { val g = FastGray.of(bitmap); FastGray.varianceLaplacian(g) < threshold } catch (_: Throwable) { false }
        }
    }

    /**
     * Комплексный анализ изображения
     */
    fun analyze(bitmap: Bitmap): ImageAnalysisResult {
        val g = try { FastGray.of(bitmap, 480) } catch (_: Throwable) { null }
        val noiseResult = hasHighNoise(bitmap)
        val brightnessResult = analyzeBrightness(bitmap)
        val blurResult = hasBlur(bitmap)
        val contrastEst = if (g != null) {
            try {
                var sum = 0.0; var cnt = 0
                for (i in 1 until g.y.size) { sum += Math.abs((g.y[i].toInt() and 0xFF) - (g.y[i-1].toInt() and 0xFF)); cnt++ }
                if (cnt == 0) 0.5 else (sum / cnt / 128.0).coerceIn(0.0, 1.0)
            } catch (_: Throwable) { 0.5 }
        } else 0.5
        val satEst = avgSaturation(bitmap)

        return ImageAnalysisResult(
            hasHighNoise = noiseResult,
            avgBrightness = brightnessResult.avgBrightness,
            isTooDark = brightnessResult.isTooDark,
            hasBlur = blurResult,
            avgSaturation = satEst,
            contrastEst = contrastEst
        )
    }

    /**
     * Средняя насыщенность 0..1 (для бумажного гарда: белая бумага ~0.05-0.15).
     * Даунскейл 160 + один getPixels — миллисекунды.
     */
    fun avgSaturation(bitmap: Bitmap): Double {
        return try {
            val s = 160f / maxOf(bitmap.width, bitmap.height).coerceAtLeast(1)
            val w = (bitmap.width * s).toInt().coerceAtLeast(1)
            val h = (bitmap.height * s).toInt().coerceAtLeast(1)
            val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
            val px = IntArray(w * h)
            small.getPixels(px, 0, w, 0, 0, w, h)
            if (small !== bitmap) small.recycle()
            var sum = 0.0
            for (p in px) {
                val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
                if (mx > 0) sum += (mx - mn).toDouble() / mx
            }
            sum / px.size
        } catch (_: Throwable) { 0.5 }
    }
    
    /**
     * Проверка наличия движения (motion blur)
     * Сравнивает два последовательных кадра
     * 
     * @param currentBitmap текущий кадр
     * @param previousBitmap предыдущий кадр
     * @param threshold порог для определения движения (по умолчанию 50)
     * @return true, если обнаружено движение
     */
    fun hasMotion(
        currentBitmap: Bitmap,
        previousBitmap: Bitmap,
        threshold: Float = 50f
    ): Boolean {
        val a = FastGray.of(currentBitmap)
        val b = FastGray.of(previousBitmap)
        // Если более 10% пикселей изменились - это движение (порог как был)
        return FastGray.motionFraction(a, b, threshold) > 0.1f
    }

    /**
     * Результат анализа яркости
     */
    data class BrightnessResult(
        val avgBrightness: Double,
        val isTooDark: Boolean
    )

    /**
     * Результат комплексного анализа
     */
    data class ImageAnalysisResult(
        val hasHighNoise: Boolean,
        val avgBrightness: Double,
        val isTooDark: Boolean,
        val hasBlur: Boolean,
        val avgSaturation: Double = 0.5,
        val contrastEst: Double = 0.5
    )

}
