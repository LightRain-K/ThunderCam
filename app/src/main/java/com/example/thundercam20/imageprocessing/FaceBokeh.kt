package com.example.thundercam20.imageprocessing

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.max

/**
 * Адекватное боке без карты глубины (MiDaS нет и не предвидится):
 * резкий эллипс на главном лице (или точке фокуса), фон — прогрессивный блюр.
 * Предсказуемо, без инвертированных «бубликов» старого эвристического depth.
 * f/1.4 — сильное, f/16 — выкл (вызывает только при f<15.5).
 */
object FaceBokeh {

    fun apply(bitmap: Bitmap, apertureF: Float, focusNorm: PointF? = null): Bitmap {
        if (apertureF >= 15.5f) return bitmap
        return try {
            val faces = FaceProtect.faceBoxes(bitmap)
            // резкая зона: главное лицо ×1.25 или круг у точки фокуса
            val sharp: RectF = if (faces.isNotEmpty()) {
                val b = faces.maxByOrNull { it.width() * it.height() }!!
                val cx = b.centerX(); val cy = b.centerY()
                val rx = b.width() * 0.5f * 1.25f; val ry = b.height() * 0.5f * 1.25f
                RectF(cx - rx, cy - ry, cx + rx, cy + ry)
            } else if (focusNorm != null) {
                val cx = focusNorm.x * bitmap.width; val cy = focusNorm.y * bitmap.height
                val r = max(bitmap.width, bitmap.height) * 0.18f
                RectF(cx - r, cy - r, cx + r, cy + r)
            } else {
                return bitmap // ни лица ни фокуса — нечего держать резким, выходим без порчи
            }
            // сила блюра по f/: f/1.4→r12 … f/8→r4
            val radius = (((16f - apertureF) / (16f - 1.4f)) * 12f).toInt().coerceIn(2, 12)
            val blurred = NoiseReducer().softKotlin(bitmap, radius = radius.coerceAtMost(4), blend = 1f)
            blendByMask(bitmap, blurred, sharp)
        } catch (_: Throwable) { bitmap }
    }

    private fun blendByMask(sharp: Bitmap, blurred: Bitmap, zone: RectF): Bitmap {
        val w = sharp.width; val h = sharp.height
        val bw = blurred.width; val bh = blurred.height
        val sPx = IntArray(w * h); val bPx = IntArray(w * h)
        sharp.getPixels(sPx, 0, w, 0, 0, w, h)
        val bb = if (bw == w && bh == h) blurred else Bitmap.createScaledBitmap(blurred, w, h, true)
        bb.getPixels(bPx, 0, w, 0, 0, w, h)
        val cx = (zone.left + zone.right) / 2; val cy = (zone.top + zone.bottom) / 2
        val rx = max(zone.width() / 2, 1f); val ry = max(zone.height() / 2, 1f)
        val out = IntArray(w * h)
        for (i in out.indices) {
            val x = (i % w).toFloat(); val y = (i / w).toFloat()
            val dx = (x - cx) / rx; val dy = (y - cy) / ry
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            // внутри 0.85 — резко, к 1.45 — полный блюр, smoothstep
            val t = ((d - 0.85f) / 0.6f).coerceIn(0f, 1f)
            val m = t * t * (3f - 2f * t)
            val s = sPx[i]; val q = bPx[i]
            val r = (((s shr 16) and 0xFF) * (1 - m) + ((q shr 16) and 0xFF) * m).toInt()
            val g = (((s shr 8) and 0xFF) * (1 - m) + ((q shr 8) and 0xFF) * m).toInt()
            val b = ((s and 0xFF) * (1 - m) + (q and 0xFF) * m).toInt()
            out[i] = -0x1000000 or (r shl 16) or (g shl 8) or b
        }
        val res = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        res.setPixels(out, 0, w, 0, 0, w, h)
        if (bb !== blurred) bb.recycle()
        return res
    }
}
