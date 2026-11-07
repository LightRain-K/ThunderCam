package com.example.thundercam20.imageprocessing

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

/**
 * P0 Contrast — компенсация мыла TECNO + CLAHE опционально.
 * Исследование: TECNO contrast -0.063 (×0.92) [0.83 был артефакт] => restore ×1.087 [1.05-1.15]
 * CLAHE оставлен как опциональный пресет, по умолчанию выкл (P0 приоритет — глобальный фактор).
 */
class ContrastProcessor {

    companion object {
        const val TECNO_FACTOR = 0.92f
        const val RESTORE_FACTOR = 1.087f // 1/0.92
        const val RESTORE_MIN = 1.05f
        const val RESTORE_MAX = 1.15f
    }

    private val clipLimit = 2.0f
    private val tileGridSize = 8

    /**
     * P0: Глобальный контраст restore (быстро, по ТЗ).
     * Формула: (rgb - mean) * factor + mean  (mean per channel)
     */
    fun restoreGlobalContrast(bitmap: Bitmap, factor: Float = RESTORE_FACTOR): Bitmap {
        if (factor <= 1.001f) return bitmap
        val w = bitmap.width; val h = bitmap.height; val n = w*h
        val pixels = IntArray(n); bitmap.getPixels(pixels,0,w,0,0,w,h)
        // mean per channel
        var sumR=0L; var sumG=0L; var sumB=0L
        for (p in pixels) { sumR+=Color.red(p); sumG+=Color.green(p); sumB+=Color.blue(p) }
        val meanR=sumR.toFloat()/n; val meanG=sumG.toFloat()/n; val meanB=sumB.toFloat()/n
        val out=IntArray(n)
        for (i in pixels.indices){
            val p=pixels[i]
            val nr = ((Color.red(p)-meanR)*factor+meanR).toInt().coerceIn(0,255)
            val ng = ((Color.green(p)-meanG)*factor+meanG).toInt().coerceIn(0,255)
            val nb = ((Color.blue(p)-meanB)*factor+meanB).toInt().coerceIn(0,255)
            out[i]=Color.rgb(nr,ng,nb)
        }
        val res=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        res.setPixels(out,0,w,0,0,w,h); return res
    }

    /** Деградация для A/B */
    fun degradeGlobalContrast(bitmap: Bitmap, factor: Float = TECNO_FACTOR): Bitmap = restoreGlobalContrast(bitmap, factor)
    
    /**
     * Быстрый CLAHE через OpenCV: Y-канал YCrCb + createCLAHE (миллисекунды).
     * Нет OpenCV — Kotlin-фолбэк ниже, без крашей.
     */
    fun applyClaheFast(bitmap: Bitmap, clipLimit: Float = this.clipLimit): Bitmap {
        if (!com.example.thundercam20.utils.OpencvManager.available) return applyClahe(bitmap, clipLimit)
        try {
            val mat = org.opencv.core.Mat()
            org.opencv.android.Utils.bitmapToMat(bitmap, mat)
            val rgb = org.opencv.core.Mat()
            org.opencv.imgproc.Imgproc.cvtColor(mat, rgb, org.opencv.imgproc.Imgproc.COLOR_RGBA2RGB)
            val lab = org.opencv.core.Mat()
            org.opencv.imgproc.Imgproc.cvtColor(rgb, lab, org.opencv.imgproc.Imgproc.COLOR_RGB2Lab)
            val ch = java.util.ArrayList<org.opencv.core.Mat>()
            org.opencv.core.Core.split(lab, ch)
            val clahe = org.opencv.imgproc.Imgproc.createCLAHE(clipLimit.toDouble(), org.opencv.core.Size(8.0, 8.0))
            val lEq = org.opencv.core.Mat()
            clahe.apply(ch[0], lEq)
            ch[0].release()
            ch[0] = lEq
            org.opencv.core.Core.merge(ch, lab)
            val rgbOut = org.opencv.core.Mat()
            org.opencv.imgproc.Imgproc.cvtColor(lab, rgbOut, org.opencv.imgproc.Imgproc.COLOR_Lab2RGB)
            val out = org.opencv.core.Mat()
            org.opencv.imgproc.Imgproc.cvtColor(rgbOut, out, org.opencv.imgproc.Imgproc.COLOR_RGB2RGBA)
            val result = Bitmap.createBitmap(out.cols(), out.rows(), Bitmap.Config.ARGB_8888)
            org.opencv.android.Utils.matToBitmap(out, result)
            mat.release(); rgb.release(); lab.release(); ch.forEach { it.release() }; rgbOut.release(); out.release()
            return result
        } catch (t: Throwable) {
            return applyClahe(bitmap, clipLimit)
        }
    }

    /**
     * Применяет CLAHE к изображению (Kotlin-фолбэк, медленно на больших)
     *
     * @param bitmap входное изображение
     * @param clipLimit ограничение контраста (по умолчанию 2.0)
     * @param tileGridSize размер сетки тайлов (по умолчанию 8x8)
     * @return изображение с улучшенным контрастом
     */
    fun applyClahe(bitmap: Bitmap, clipLimit: Float = this.clipLimit, tileGridSize: Int = this.tileGridSize): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        
        val result = Bitmap.createBitmap(width, height, bitmap.config ?: Bitmap.Config.ARGB_8888)
        
        // Размеры тайлов
        val tileSizeX = width / tileGridSize
        val tileSizeY = height / tileGridSize
        
        // Массив для хранения гистограмм каждого тайла
        val tileHistograms = Array(tileGridSize) { Array(tileGridSize) { IntArray(256) } }
        
        // Шаг 1: Вычислить гистограммы для каждого тайла
        for (tileY in 0 until tileGridSize) {
            for (tileX in 0 until tileGridSize) {
                val startX = tileX * tileSizeX
                val startY = tileY * tileSizeY
                val endX = min(startX + tileSizeX, width)
                val endY = min(startY + tileSizeY, height)
                
                for (y in startY until endY) {
                    for (x in startX until endX) {
                        val pixel = bitmap.getPixel(x, y)
                        val brightness = getBrightness(pixel)
                        tileHistograms[tileY][tileX][brightness.toInt()]++
                    }
                }
            }
        }
        
        // Шаг 2: Ограничить гистограммы (clip histograms)
        for (tileY in 0 until tileGridSize) {
            for (tileX in 0 until tileGridSize) {
                clipHistogram(tileHistograms[tileY][tileX], clipLimit, tileGridSize * tileGridSize)
            }
        }
        
        // Шаг 3: Создать кумулятивные распределения (CDF)
        val tileCDFs = Array(tileGridSize) { Array(tileGridSize) { FloatArray(256) } }
        for (tileY in 0 until tileGridSize) {
            for (tileX in 0 until tileGridSize) {
                val totalPixels = computeTotalPixels(tileHistograms[tileY][tileX])
                createCDF(tileHistograms[tileY][tileX], tileCDFs[tileY][tileX], totalPixels)
            }
        }
        
        // Шаг 4: Применить интерполяцию для каждого пикселя
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = bitmap.getPixel(x, y)
                val brightness = getBrightness(pixel)
                
                // Найти координаты тайлов
                val tileX = min(x / tileSizeX, tileGridSize - 1)
                val tileY = min(y / tileSizeY, tileGridSize - 1)
                
                // Получить CDF значения для окружающих тайлов
                val cdfTL = tileCDFs[tileY][tileX][brightness.toInt()] // Top-Left
                val cdfTR = if (tileX < tileGridSize - 1) tileCDFs[tileY][tileX + 1][brightness.toInt()] else cdfTL // Top-Right
                val cdfBL = if (tileY < tileGridSize - 1) tileCDFs[tileY + 1][tileX][brightness.toInt()] else cdfTL // Bottom-Left
                val cdfBR = if (tileX < tileGridSize - 1 && tileY < tileGridSize - 1) 
                    tileCDFs[tileY + 1][tileX + 1][brightness.toInt()] 
                else 
                    (cdfTL + cdfTR + cdfBL) / 3 // Bottom-Right
                
                // Билинейная интерполяция
                val fx = (x % tileSizeX) / tileSizeX.toFloat()
                val fy = (y % tileSizeY) / tileSizeY.toFloat()
                
                val cdfValue = (1 - fx) * (1 - fy) * cdfTL +
                              fx * (1 - fy) * cdfTR +
                              (1 - fx) * fy * cdfBL +
                              fx * fy * cdfBR
                
                // Преобразовать CDF в новую яркость
                val newBrightness = (cdfValue * 255).toInt().coerceIn(0, 255)
                
                // Применить новую яркость с сохранением цвета
                val newPixel = applyBrightnessToPixel(pixel, newBrightness)
                result.setPixel(x, y, newPixel)
            }
        }
        
        return result
    }
    
    /**
     * Получить яркость пикселя
     */
    private fun getBrightness(pixel: Int): Float {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        return 0.299f * r + 0.587f * g + 0.114f * b
    }
    
    /**
     * Ограничить гистограмму (clip histogram)
     */
    private fun clipHistogram(histogram: IntArray, clipLimit: Float, totalPixels: Int) {
        val maxClipValue = (clipLimit * totalPixels).toInt()
        var excess = 0
        
        // Посчитать избыток
        for (i in 0 until 256) {
            if (histogram[i] > maxClipValue) {
                excess += histogram[i] - maxClipValue
                histogram[i] = maxClipValue
            }
        }
        
        // Распределить избыток равномерно
        val increment = excess / 256
        for (i in 0 until 256) {
            histogram[i] += increment
        }
        
        // Распределить остаток
        val remainder = excess % 256
        var index = 0
        for (i in 0 until remainder) {
            histogram[index++]++
        }
    }
    
    /**
     * Вычислить общее количество пикселей в гистограмме
     */
    private fun computeTotalPixels(histogram: IntArray): Int {
        return histogram.sum()
    }
    
    /**
     * Создать кумулятивное распределение (CDF)
     */
    private fun createCDF(histogram: IntArray, cdf: FloatArray, totalPixels: Int) {
        var sum = 0f
        for (i in 0 until 256) {
            sum += histogram[i]
            cdf[i] = sum / totalPixels
        }
    }
    
    /**
     * Применить новую яркость к пикселю с сохранением цвета
     */
    private fun applyBrightnessToPixel(pixel: Int, newBrightness: Int): Int {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        
        val currentBrightness = 0.299f * r + 0.587f * g + 0.114f * b
        
        if (currentBrightness == 0f) {
            return Color.rgb(newBrightness, newBrightness, newBrightness)
        }
        
        val factor = newBrightness / currentBrightness
        
        val newR = (r * factor).toInt().coerceIn(0, 255)
        val newG = (g * factor).toInt().coerceIn(0, 255)
        val newB = (b * factor).toInt().coerceIn(0, 255)
        
        return Color.rgb(newR, newG, newB)
    }
}
