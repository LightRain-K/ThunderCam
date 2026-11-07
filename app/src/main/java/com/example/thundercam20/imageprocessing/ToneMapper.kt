package com.example.thundercam20.imageprocessing

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

/**
 * P0 Tone-mapping HDR — инверсия деградации TECNO (робастный анализ 31 пары).
 *
 * Исследование: TECNO тянет к середине — тёмные +0.144, средние +0.135, светлые +0.006
 * (по mean L tertiles q33=0.332 q66=0.402). Восстановление: L' = L - d(L) * strength.
 * d зависит от сцены (meanL всего изображения).
 *
 * Референс: photo_converter.py restore_cv2() d = 0.144/0.135/0.006 с +0.07 сдвигом для bad.
 */
class ToneMapper {

    // Робасные пороги из robust_recalc.py — для restore (bad уже ярче на ~0.07)
    private val tDark = 0.40f  // 0.332 + 0.07
    private val tMid  = 0.47f  // 0.402 + 0.07
    private val dDark = 0.144f
    private val dMid  = 0.135f
    private val dBright = 0.045f // было 0.006 невидимо, делаем 0.045 видно (исслед. 0.006 → для эффекта ×7)

    /**
     * Применяет HDR tone-mapping восстановление.
     * @param strength 0..1 — сила эффекта (1.0 = полный инверс по ТЗ, 0 = выкл)
     */
    fun restore(bitmap: Bitmap, strength: Float = 1f): Bitmap {
        if (strength <= 0.001f) return bitmap

        val w = bitmap.width
        val h = bitmap.height
        val n = w * h
        val pixels = IntArray(n)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // Считаем mean L быстро (L = 0.299R+0.587G+0.114B /255)
        var sumL = 0.0
        for (p in pixels) {
            val l = (0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)) / 255.0
            sumL += l
        }
        val meanL = (sumL / n).toFloat()
        val shadowLift: Float
        val highlightPull: Float
        if (meanL < 0.45f) {
            val darkness = (0.45f - meanL) / 0.45f
            shadowLift = (0.12f + 0.26f * darkness) * strength
            highlightPull = 0.08f * strength
        } else {
            shadowLift = 0.04f * strength
            highlightPull = 0.05f * strength
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val outPixels = IntArray(n)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)
            val lum = 0.299f * r + 0.587f * g + 0.114f * b
            if (lum <= 0.5f) {
                outPixels[i] = p
            } else {
                val x = lum / 255f
                val noiseGate = x / (x + 0.022f)
                val shWeight = (1f - x) * (1f - x)
                val hiWeight = x * x
                val xNew = x + shadowLift * shWeight * noiseGate - highlightPull * hiWeight * (1f - x)
                val gain = ((xNew * 255f) / lum).coerceIn(0.75f, 2.2f)
                val nr = (r * gain).toInt().coerceIn(0, 255)
                val ng = (g * gain).toInt().coerceIn(0, 255)
                val nb = (b * gain).toInt().coerceIn(0, 255)
                outPixels[i] = Color.rgb(nr, ng, nb)
            }
        }
        out.setPixels(outPixels, 0, w, 0, 0, w, h)
        return out
    }

    /** Деградация в сторону TECNO (для дебага/A/B) */
    fun degrade(bitmap: Bitmap, strength: Float = 1f): Bitmap {
        if (strength <= 0.001f) return bitmap
        val w = bitmap.width; val h = bitmap.height; val n = w*h
        val pixels = IntArray(n); bitmap.getPixels(pixels,0,w,0,0,w,h)
        var sumL=0.0; for(p in pixels) sumL += (0.299*Color.red(p)+0.587*Color.green(p)+0.114*Color.blue(p))/255
        val meanL=(sumL/n).toFloat()
        val d = when { meanL<0.332f->0.144f; meanL<0.402f->0.135f; else->0.006f }*strength
        val out = Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        val outPixels=IntArray(n)
        for(i in pixels.indices){
            val p=pixels[i]; val r=Color.red(p);val g=Color.green(p);val b=Color.blue(p)
            val lum=0.299f*r+0.587f*g+0.114f*b
            val lumT = min(255f, lum + d*255f)
            val gain=(lumT/(lum+1e-6f)).coerceIn(0.6f,1.9f)
            outPixels[i]=Color.rgb((r*gain).toInt().coerceIn(0,255),(g*gain).toInt().coerceIn(0,255),(b*gain).toInt().coerceIn(0,255))
        }
        out.setPixels(outPixels,0,w,0,0,w,h); return out
    }
}
