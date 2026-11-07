package com.example.thundercam20.imageprocessing

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Класс для повышения резкости изображения с использованием Unsharp Mask
 */
class Sharpener {
    
    /**
     * Применяет Unsharp Mask к изображению
     * 
     * Алгоритм:
     * 1. Создать Gaussian Blur копию изображения
     * 2. Вычесть размытую версию из оригинала (получим детали)
     * 3. Усилить детали и прибавить к оригиналу
     * 
     * @param bitmap входное изображение
     * @param radius радиус размытия (рекомендуется 1-3)
     * @param amount степень усиления (рекомендуется 0.5-2.0)
     * @return резкое изображение
     */
    /**
     * Быстрый Unsharp через OpenCV (миллисекунды на 12МП вместо минут Kotlin-цикла).
     * Та же математика: оригинал×(1+amount) − blur×amount.
     */
    /**
     * Быстрый Unsharp через OpenCV по каналу яркости (Y в YCrCb) с порогом шума:
     * 1. Шарпим только яркость (Y) — нет цветных ореолов и усиления цветового шума.
     * 2. Порог (threshold) отсекает мелкий шум в плоских тенях/небе, усиливая только реальные грани.
     */
    fun unsharpFast(bitmap: Bitmap, radius: Float = 1.2f, amount: Float = 0.75f, threshold: Double = 6.0): Bitmap {
        if (!com.example.thundercam20.utils.OpencvManager.available) return bitmap
        try {
            val mat = org.opencv.core.Mat()
            org.opencv.android.Utils.bitmapToMat(bitmap, mat)
            val rgb = org.opencv.core.Mat()
            org.opencv.imgproc.Imgproc.cvtColor(mat, rgb, org.opencv.imgproc.Imgproc.COLOR_RGBA2RGB)
            mat.release()

            val ycrcb = org.opencv.core.Mat()
            org.opencv.imgproc.Imgproc.cvtColor(rgb, ycrcb, org.opencv.imgproc.Imgproc.COLOR_RGB2YCrCb)
            rgb.release()

            val ch = java.util.ArrayList<org.opencv.core.Mat>(3)
            org.opencv.core.Core.split(ycrcb, ch)
            ycrcb.release()

            val yMat = ch[0]
            val blurred = org.opencv.core.Mat()
            org.opencv.imgproc.Imgproc.GaussianBlur(yMat, blurred, org.opencv.core.Size(0.0, 0.0), radius.toDouble())

            // Высокочастотная деталь с порогом: pos = max(0, (Y - blur) - thr), neg = max(0, (blur - Y) - thr)
            val pos = org.opencv.core.Mat()
            val neg = org.opencv.core.Mat()
            org.opencv.core.Core.subtract(yMat, blurred, pos)
            org.opencv.core.Core.subtract(blurred, yMat, neg)
            blurred.release()

            val thrScalar = org.opencv.core.Scalar(threshold)
            org.opencv.core.Core.subtract(pos, thrScalar, pos)
            org.opencv.core.Core.subtract(neg, thrScalar, neg)

            val ySharp = org.opencv.core.Mat()
            org.opencv.core.Core.addWeighted(yMat, 1.0, pos, amount.toDouble(), 0.0, ySharp)
            org.opencv.core.Core.addWeighted(ySharp, 1.0, neg, -amount.toDouble(), 0.0, ySharp)
            yMat.release(); pos.release(); neg.release()
            ch[0] = ySharp

            val merged = org.opencv.core.Mat()
            org.opencv.core.Core.merge(ch, merged)
            ch.forEach { it.release() }

            val rgbOut = org.opencv.core.Mat()
            org.opencv.imgproc.Imgproc.cvtColor(merged, rgbOut, org.opencv.imgproc.Imgproc.COLOR_YCrCb2RGB)
            merged.release()

            val out = org.opencv.core.Mat()
            org.opencv.imgproc.Imgproc.cvtColor(rgbOut, out, org.opencv.imgproc.Imgproc.COLOR_RGB2RGBA)
            rgbOut.release()

            val result = Bitmap.createBitmap(out.cols(), out.rows(), Bitmap.Config.ARGB_8888)
            org.opencv.android.Utils.matToBitmap(out, result)
            out.release()
            return result
        } catch (t: Throwable) {
            return bitmap
        }
    }

    fun applyUnsharpMask(
        bitmap: Bitmap,
        radius: Float = 2f,
        amount: Float = 1.5f
    ): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        
        // Шаг 1: Создать размытую копию (Gaussian Blur)
        val blurred = applyGaussianBlur(bitmap, radius)
        
        // Шаг 2: Применить Unsharp Mask
        val result = Bitmap.createBitmap(width, height, bitmap.config ?: Bitmap.Config.ARGB_8888)
        
        for (y in 0 until height) {
            for (x in 0 until width) {
                val originalPixel = bitmap.getPixel(x, y)
                val blurredPixel = blurred.getPixel(x, y)
                
                val origR = Color.red(originalPixel)
                val origG = Color.green(originalPixel)
                val origB = Color.blue(originalPixel)
                
                val blurR = Color.red(blurredPixel)
                val blurG = Color.green(blurredPixel)
                val blurB = Color.blue(blurredPixel)
                
                // Вычесть размытую версию (получим детали)
                val detailR = origR - blurR
                val detailG = origG - blurG
                val detailB = origB - blurB
                
                // Усилить детали и прибавить к оригиналу
                val newR = (origR + detailR * amount).toInt().coerceIn(0, 255)
                val newG = (origG + detailG * amount).toInt().coerceIn(0, 255)
                val newB = (origB + detailB * amount).toInt().coerceIn(0, 255)
                
                result.setPixel(x, y, Color.rgb(newR, newG, newB))
            }
        }
        
        // Освободить память
        blurred.recycle()
        
        return result
    }
    
    /**
     * Применяет Gaussian Blur к изображению
     */
    private fun applyGaussianBlur(bitmap: Bitmap, radius: Float): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        
        // Создать копию изображения
        val result = Bitmap.createBitmap(width, height, bitmap.config ?: Bitmap.Config.ARGB_8888)
        
        // Вычислить размер ядра (должен быть нечетным)
        val kernelSize = (radius * 2 + 1).toInt().coerceAtLeast(3)
        val halfKernel = kernelSize / 2
        
        // Создать Gaussian kernel
        val kernel = FloatArray(kernelSize * kernelSize)
        val sigma = radius / 2
        val sigma2 = sigma * sigma
        
        var sum = 0f
        for (y in 0 until kernelSize) {
            for (x in 0 until kernelSize) {
                val dx = x - halfKernel
                val dy = y - halfKernel
                kernel[y * kernelSize + x] = kotlin.math.exp(-(dx * dx + dy * dy) / (2 * sigma2)).toFloat()
                sum += kernel[y * kernelSize + x]
            }
        }
        
        // Нормализовать kernel
        for (i in kernel.indices) {
            kernel[i] /= sum
        }
        
        // Применить kernel
        for (y in halfKernel until height - halfKernel) {
            for (x in halfKernel until width - halfKernel) {
                var r = 0f
                var g = 0f
                var b = 0f
                
                for (ky in 0 until kernelSize) {
                    for (kx in 0 until kernelSize) {
                        val pixel = bitmap.getPixel(x + kx - halfKernel, y + ky - halfKernel)
                        val kernelValue = kernel[ky * kernelSize + kx]
                        
                        r += Color.red(pixel) * kernelValue
                        g += Color.green(pixel) * kernelValue
                        b += Color.blue(pixel) * kernelValue
                    }
                }
                
                result.setPixel(x, y, Color.rgb(r.toInt(), g.toInt(), b.toInt()))
            }
        }
        
        return result
    }
    
    /**
     * Альтернативный метод: sharpening kernel (простой и быстрый)
     */
    fun applySimpleSharpening(
        bitmap: Bitmap,
        strength: Float = 1.5f
    ): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        
        val result = Bitmap.createBitmap(width, height, bitmap.config ?: Bitmap.Config.ARGB_8888)
        
        // Ядро для резкости
        val kernel = listOf(
            listOf(-1f, -1f, -1f),
            listOf(-1f, 9f, -1f),
            listOf(-1f, -1f, -1f)
        )
        
        val halfKernel = 1
        
        for (y in halfKernel until height - halfKernel) {
            for (x in halfKernel until width - halfKernel) {
                var r = 0f
                var g = 0f
                var b = 0f
                
                for (ky in -halfKernel..halfKernel) {
                    for (kx in -halfKernel..halfKernel) {
                        val pixel = bitmap.getPixel(x + kx, y + ky)
                        val kernelValue = kernel[ky + halfKernel][kx + halfKernel]
                        
                        r += Color.red(pixel) * kernelValue
                        g += Color.green(pixel) * kernelValue
                        b += Color.blue(pixel) * kernelValue
                    }
                }
                
                // Применить силу эффекта
                val origPixel = bitmap.getPixel(x, y)
                val origR = Color.red(origPixel).toFloat()
                val origG = Color.green(origPixel).toFloat()
                val origB = Color.blue(origPixel).toFloat()
                
                val finalR = min(255f, max(0f, origR + (r - origR) * strength))
                val finalG = min(255f, max(0f, origG + (g - origG) * strength))
                val finalB = min(255f, max(0f, origB + (b - origB) * strength))
                
                result.setPixel(x, y, Color.rgb(finalR.toInt(), finalG.toInt(), finalB.toInt()))
            }
        }
        
        return result
    }
    
    private fun min(a: Float, b: Float): Float = if (a < b) a else b
    private fun max(a: Float, b: Float): Float = if (a > b) a else b
}
