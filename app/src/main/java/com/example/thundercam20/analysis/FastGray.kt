package com.example.thundercam20.analysis

import android.graphics.Bitmap

/**
 * Быстрая серая матрица для анализа: даунскейл до maxSide + один getPixels.
 * Заменяет тысячи JNI getPixel — те же метрики, в ~100-1000 раз быстрее.
 * Функционал не урезан: пороги вызывающих сторон не меняются.
 */
object FastGray {

    data class Gray(val w: Int, val h: Int, val y: FloatArray) {
        fun mean(): Float {
            var s = 0.0; for (v in y) s += v
            return if (y.isEmpty()) 0f else (s / y.size).toFloat()
        }
    }

    fun of(bitmap: Bitmap, maxSide: Int = 480): Gray {
        val longSide = maxOf(bitmap.width, bitmap.height)
        val small = if (longSide > maxSide) {
            val s = maxSide.toFloat() / longSide
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * s).toInt().coerceAtLeast(1), (bitmap.height * s).toInt().coerceAtLeast(1), true)
        } else bitmap
        val w = small.width; val h = small.height
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== bitmap) small.recycle()
        val y = FloatArray(w * h)
        for (i in px.indices) {
            val p = px[i]
            y[i] = 0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
        }
        return Gray(w, h, y)
    }

    /** Средний модуль лапласиана 4-соседа (метрика резкости, порог ~100 как было). */
    fun meanAbsLaplacian(g: Gray): Double {
        val w = g.w; val h = g.h; val y = g.y
        var sum = 0.0; var n = 0
        for (row in 1 until h - 1) {
            var i = row * w + 1
            val end = row * w + w - 1
            while (i < end) {
                val c = y[i]
                sum += kotlin.math.abs(4f * c - y[i - 1] - y[i + 1] - y[i - w] - y[i + w])
                n++; i++
            }
        }
        return if (n == 0) 0.0 else sum / n
    }

    /** Дисперсия лапласиана (как было в ImageAnalyzer.hasBlur, порог 100). */
    fun varianceLaplacian(g: Gray): Double {
        val w = g.w; val h = g.h; val y = g.y
        var sum = 0.0; var sum2 = 0.0; var n = 0
        for (row in 1 until h - 1) {
            var i = row * w + 1
            val end = row * w + w - 1
            while (i < end) {
                val l = (4f * y[i] - y[i - 1] - y[i + 1] - y[i - w] - y[i + w]).toDouble()
                sum += l; sum2 += l * l; n++; i++
            }
        }
        if (n == 0) return 0.0
        val mean = sum / n
        return sum2 / n - mean * mean
    }

    /** Средний локальный контраст 4-соседа (метрика фокуса, порог ~20 как было). */
    fun meanContrast(g: Gray): Double {
        val w = g.w; val h = g.h; val y = g.y
        var sum = 0.0; var n = 0
        for (row in 1 until h - 1) {
            var i = row * w + 1
            val end = row * w + w - 1
            while (i < end) {
                val c = y[i]
                sum += kotlin.math.abs(c - y[i - 1]) + kotlin.math.abs(c - y[i + 1]) + kotlin.math.abs(c - y[i - w]) + kotlin.math.abs(c - y[i + w])
                n++; i++
            }
        }
        return if (n == 0) 0.0 else sum / n
    }

    /** Доля пикселей с |разницей| > thr между двумя кадрами (метрика движения, порог 0.1 как было). */
    fun motionFraction(a: Gray, b: Gray, thr: Float = 50f): Float {
        val n = minOf(a.y.size, b.y.size)
        if (n == 0) return 0f
        var changed = 0
        for (i in 0 until n) if (kotlin.math.abs(a.y[i] - b.y[i]) > thr) changed++
        return changed.toFloat() / n
    }
}
