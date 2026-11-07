package com.example.thundercam20.imageprocessing

import android.graphics.Bitmap

/**
 * Авто-WB вместо хардкода R/0.98 G/1.02 B/1.06.
 * gray-world на 240px + кламп, лицо как референс (если будет бокс — дотяжка).
 */
object AutoWb {
    data class Gains(val r: Float, val g: Float, val b: Float)

    fun estimate(bmp: Bitmap, warmth01: Float = 1f): Gains {
        if (warmth01 <= 0.01f) return Gains(1f, 1f, 1f)
        return try {
            val sw = 240
            val sh = (240f * bmp.height / bmp.width).toInt().coerceIn(1, 240)
            val small = Bitmap.createScaledBitmap(bmp, sw, sh, true)
            val n = sw * sh
            val px = IntArray(n)
            small.getPixels(px, 0, sw, 0, 0, sw, sh)
            var sr = 0L; var sg = 0L; var sb = 0L
            for (v in px) { sr += (v shr 16) and 255; sg += (v shr 8) and 255; sb += v and 255 }
            small.recycle()
            val mR = sr.toFloat() / n + 1f
            val mG = sg.toFloat() / n + 1f
            val mB = sb.toFloat() / n + 1f
            // Красный гард: сцена с доминантным красным (закат, красная стена, кожа крупно)
            // — это замысел, а не ошибка WB. Давим коррекцию вдвое, красный канал не роняем ниже 0.95.
            val redDom = mR > mG * 1.12f && mR > mB * 1.12f
            var rG = (mG / mR).coerceIn(0.85f, 1.25f)
            var bG = (mG / mB).coerceIn(0.85f, 1.25f)
            if (redDom) {
                rG = 1f + (rG - 1f) * 0.5f
                bG = 1f + (bG - 1f) * 0.7f
            }
            // Красный канал самый болезненный (кожа/красные объекты сереют) — пол ниже.
            rG = rG.coerceIn(0.95f, 1.18f)
            bG = bG.coerceIn(0.88f, 1.22f)
            // warmth01 миксует identity <-> полный фикс (слайдер остался рабочим)
            rG = 1f + (rG - 1f) * warmth01.coerceIn(0f, 1f)
            bG = 1f + (bG - 1f) * warmth01.coerceIn(0f, 1f)
            Gains(rG, 1f, bG)
        } catch (_: Throwable) {
            Gains(1f + 0.02f * warmth01, 1f - 0.01f * warmth01, 1f - 0.03f * warmth01)
        }
    }
}
