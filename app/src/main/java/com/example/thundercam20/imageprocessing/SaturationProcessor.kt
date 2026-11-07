package com.example.thundercam20.imageprocessing

import android.graphics.Bitmap
import android.graphics.Color

/**
 * P0 Saturation — компенсация бледности TECNO.
 * Исследование: TECNO S = 0.70 × Canon (median ratio 0.70 [0.65-0.80] p=0.0001).
 * Восстановление: S *= 1/0.70 = 1.428 (диапазон 1.25-1.54).
 * Референс: photo_converter.py restore_cv2 hsv[:,:,1]*=1.428
 */
class SaturationProcessor {

    companion object {
        const val TECNO_RATIO = 0.70f
        const val RESTORE_FACTOR = 1.428f // 1/0.70
        const val RESTORE_MIN = 1.25f
        const val RESTORE_MAX = 1.54f
    }

    /**
     * @param factor 1.0 = выкл, 1.428 = по ТЗ, диапазон 1.0..1.6
     * Реализовано через HSV — быстро и без потери оттенка.
     */
    fun restore(bitmap: Bitmap, factor: Float = RESTORE_FACTOR): Bitmap {
        if (factor <= 1.001f) return bitmap
        val w = bitmap.width; val h = bitmap.height; val n = w*h
        val pixels = IntArray(n); bitmap.getPixels(pixels,0,w,0,0,w,h)
        val out = IntArray(n)
        val hsv = FloatArray(3)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
            Color.RGBToHSV(r,g,b,hsv)
            hsv[1] = (hsv[1] * factor).coerceIn(0f,1f)
            out[i] = Color.HSVToColor(hsv)
        }
        val res = Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        res.setPixels(out,0,w,0,0,w,h)
        return res
    }

    /** Деградация для A/B теста: S *=0.70 */
    fun degrade(bitmap: Bitmap, factor: Float = TECNO_RATIO): Bitmap = restore(bitmap, factor)
}
