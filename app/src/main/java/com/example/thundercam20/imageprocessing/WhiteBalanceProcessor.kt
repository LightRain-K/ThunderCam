package com.example.thundercam20.imageprocessing

import android.graphics.Bitmap
import android.graphics.Color

/**
 * P0 White Balance — холодный сдвиг TECNO.
 * Исследование (robust n=31):
 *   Lab a -1.67 [-4.91,-0.29] p=0.018
 *   Lab b -4.79 [-7.84,-0.47] p=0.003
 *   mean_B +28.8 [+16,+50] p=1.5e-05
 * Фикс restore: Lab a+1.67 b+4.79 или RGB: R/0.98 G/1.02 B/1.06 -0.025
 * Референс: photo_converter.py restore_cv2 lab[:,:,1]+=1.67 lab[:,:,2]+=4.79
 *           и RGB инверс bad[:,2]-0.025 /1.06
 */
class WhiteBalanceProcessor {

    /**
     * Быстрый RGB-фикс (без конвертации Lab) — инверс degrade:
     * degrade: R*0.98, G*1.02, B*1.06+0.025
     * restore: (B-0.025)/1.06, G/1.02, R/0.98
     * @param warmth 0..1 — сила (1 = полный фикс)
     */
    fun restoreRGB(bitmap: Bitmap, warmth: Float = 1f): Bitmap {
        if (warmth <= 0.001f) return bitmap
        val w = bitmap.width; val h = bitmap.height; val n = w*h
        val pixels = IntArray(n); bitmap.getPixels(pixels,0,w,0,0,w,h)
        val out = IntArray(n)
        // Интерполируем между identity и полным фиксом
        val rGain = 1f/0.98f // 1.0204
        val gGain = 1f/1.02f // 0.9804
        val bGain = 1f/1.06f // 0.9434
        val rG = 1f + (rGain-1f)*warmth
        val gG = 1f + (gGain-1f)*warmth
        val bG = 1f + (bGain-1f)*warmth
        val bBias = -0.025f * 255f * warmth / bGain // компенсируем bias частично
        for (i in pixels.indices) {
            val p = pixels[i]
            var r = (Color.red(p) * rG).toInt().coerceIn(0,255)
            var g = (Color.green(p) * gG).toInt().coerceIn(0,255)
            var b = ((Color.blue(p) + bBias) * bG).toInt().coerceIn(0,255)
            // Альтернатива: точный инверс
            // b = ((Color.blue(p)/255f -0.025f*warmth)/ (0.06f*warmth+1f) *255).toInt()
            out[i] = Color.rgb(r,g,b)
        }
        val res = Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        res.setPixels(out,0,w,0,0,w,h)
        return res
    }

    /**
     * Точнее через Lab — но медленнее. Для Android делаем упрощённый RGB,
     * Lab-вариант оставлен для справки (можно включить флагом).
     * Здесь делаем тот же RGB но по строкам как в CV2.
     */
    fun restore(bitmap: Bitmap, warmth: Float = 1f): Bitmap = restoreRGB(bitmap, warmth)

    /** Точный пер-пиксельный инверс degrade (совпадает с photo_converter.py) */
    fun restoreExact(bitmap: Bitmap): Bitmap {
        val w = bitmap.width; val h = bitmap.height; val n = w*h
        val pixels = IntArray(n); bitmap.getPixels(pixels,0,w,0,0,w,h)
        val out = IntArray(n)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (Color.red(p) / 0.98f).toInt().coerceIn(0,255)
            val g = (Color.green(p) / 1.02f).toInt().coerceIn(0,255)
            val bF = Color.blue(p)/255f - 0.025f
            val b = (bF.coerceIn(0f,1f)/1.06f*255f).toInt().coerceIn(0,255)
            out[i] = Color.rgb(r,g,b)
        }
        val res = Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        res.setPixels(out,0,w,0,0,w,h); return res
    }
}
