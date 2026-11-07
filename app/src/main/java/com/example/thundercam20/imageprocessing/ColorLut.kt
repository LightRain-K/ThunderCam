package com.example.thundercam20.imageprocessing

import android.graphics.Bitmap
import kotlin.math.pow

/**
 * P0-lite LUT: весь тон/контраст/сат/WB запекается в один проход.
 * ACES-lite (Narkowicz) + S-contrast + highlight-desat + WB gains.
 * Один getPixels/setPixels полосами, ноль лишних Bitmap.
 */
object ColorLut {
    data class Params(
        val shadowLift: Float,
        val hiPull: Float,
        val contrast: Float,
        val sat: Float,
        val rGain: Float,
        val gGain: Float,
        val bGain: Float,
        val meanR: Float,
        val meanG: Float,
        val meanB: Float,
        // Потолок gain лифта: сумерки 1.6 (не вытаскивать шум из черноты),
        // иначе 2.2. Превью HAL выглядит лучше именно потому что не лифтит.
        val gainCap: Float = 2.2f,
    )

    fun build(
        meanL: Float,
        iso: Int,
        tone01: Float,
        contrast01: Float,
        sat01: Float,
        wb: AutoWb.Gains,
    ): Params {
        val dark = (0.45f - meanL).coerceIn(0f, 0.45f) / 0.45f // 0..1
        val isoG = when {
            iso >= 2500 -> 1f
            iso >= 1600 -> 0.6f
            iso >= 800 -> 0.3f
            else -> 0f
        }
        // Формула из ресерча: ночь поднимает, ISO душит.
        // Сумеречный гард (0.18-0.48): HAL-кадр захвата обычно ЯРЧЕ превью
        // (другая AE-точка полноразмера), а превью без лифта выглядит чище итога.
        // Поэтому лифт режем до 0.25 и потолок gain 1.30 — не вытаскивать шум
        // из черноты + пятна и серая обводка. Было 0.45/1.6 — выжигало и шумело.
        val twilit = meanL in 0.18f..0.48f
        val twK = if (twilit) 0.25f else 1f
        val gainCap = if (twilit) 1.30f else 2.2f
        var shadowLift = (0.08f + 0.28f * dark) * tone01 * (1f - 0.55f * isoG) * twK
        // Лампы/фонари: pull сильнее (было 0.05 — точечный свет выжигал в белое пятно).
        val hiPull = (0.08f + 0.06f * (1f - dark)) * tone01
        val contrast = 1f + (0.06f + 0.06f * (1f - dark) - 0.04f * isoG) * contrast01 * 2f
        // День сочнее (было тускло vs legacy 1.428): база 1.25 вместо 1.18.
        // Сумерки/высокое ISO насыщенность душим сильнее.
        var satBase = 1.25f + 0.12f * (1f - dark) - 0.45f * isoG
        if (twilit) satBase = 1f + (satBase - 1f) * 0.7f
        var sat = satBase
        sat = 1f + (sat - 1f) * sat01 * 2f
        if (iso >= 2500) sat = sat.coerceAtMost(1.1f)
        return Params(
            shadowLift = shadowLift.coerceIn(0f, 0.38f),
            hiPull = hiPull.coerceIn(0f, 0.15f),
            contrast = contrast.coerceIn(0.95f, 1.25f),
            sat = sat.coerceIn(0.8f, 1.6f),
            rGain = wb.r, gGain = wb.g, bGain = wb.b,
            meanR = 128f, meanG = 128f, meanB = 128f,
            gainCap = gainCap,
        )
    }

    private fun aces(x: Float): Float {
        val a = x * (2.51f * x + 0.03f)
        val b = x * (x * 2.43f + 0.59f) + 0.14f
        return (a / b).coerceIn(0f, 1f)
    }

    /** In-place на mutable Bitmap. Возвращает тот же Bitmap. */
    fun applyInPlace(bmp: Bitmap, p: Params): Bitmap {
        val w = bmp.width; val h = bmp.height
        val band = 256
        val px = IntArray(w * band)
        // средний цвет для контраста — один проход по даунскейлу 160
        var mr = 128f; var mg = 128f; var mb = 128f
        try {
            val sw = 160; val sh = (160f * h / w).toInt().coerceIn(1, 160)
            val small = Bitmap.createScaledBitmap(bmp, sw, sh, true)
            val n = sw * sh
            val tmp = IntArray(n)
            small.getPixels(tmp, 0, sw, 0, 0, sw, sh)
            var sr = 0L; var sg = 0L; var sb = 0L
            for (v in tmp) { sr += (v shr 16) and 255; sg += (v shr 8) and 255; sb += v and 255 }
            mr = sr.toFloat() / n; mg = sg.toFloat() / n; mb = sb.toFloat() / n
            small.recycle()
        } catch (_: Throwable) {}
        val cEffBase = p.contrast
        var y = 0
        while (y < h) {
            val hh = minOf(band, h - y)
            bmp.getPixels(px, 0, w, 0, y, w, hh)
            for (i in 0 until w * hh) {
                val pix = px[i]
                var r = ((pix shr 16) and 255).toFloat()
                var g = ((pix shr 8) and 255).toFloat()
                var b = (pix and 255).toFloat()
                // 1. lift/pull через gain по люме (как раньше, но без второго прохода)
                val lum = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                if (lum > 0.002f) {
                    val ng = lum / (lum + 0.022f)
                    val shW = (1 - lum) * (1 - lum)
                    val hiW = lum * lum
                    var xn = lum + p.shadowLift * shW * ng - p.hiPull * hiW * (1 - lum)
                    if (xn < 0.001f) xn = 0.001f
                    // ACES-lite только на пересветах чтобы не мылить середину
                    if (xn > 0.75f) xn = aces(xn)
                    var gain = xn / lum
                    if (gain < 0.75f) gain = 0.75f else if (gain > p.gainCap) gain = p.gainCap
                    r *= gain; g *= gain; b *= gain
                }
                // 2. contrast вокруг среднего (ослабляем в тенях)
                val lumA = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                val cW = if (lumA < 0.35f) 0.35f + 0.65f * (lumA / 0.35f) else 1f
                val cEff = 1f + (cEffBase - 1f) * cW
                r = (r - mr) * cEff + mr
                g = (g - mg) * cEff + mg
                b = (b - mb) * cEff + mb
                // 3. sat с highlight-desat (небо не горит). Красный щадим: десат слабее (0.25),
                // иначе кожа и красные объекты сереют — главная жалоба с поля.
                val gray = 0.299f * r + 0.587f * g + 0.114f * b
                val l01 = (gray / 255f).coerceIn(0f, 1f)
                var sEff = p.sat * (1f - 0.25f * ((l01 - 0.7f) / 0.3f).coerceIn(0f, 1f))
                // красный hue (R доминирует): не давим насыщенность ниже 0.9 от sEff
                val isRed = r > g * 1.15f && r > b * 1.15f
                if (isRed) sEff = maxOf(sEff, p.sat * 0.9f)
                // Кожа (умеренный оранжево-красный): приглушаем -12% как legacy FaceProtect,
                // иначе при новой дневной базе портреты морковные.
                val skinLike = r > g && g > b && r < 250f &&
                    (r - g) in 8f..70f && (g - b) in 2f..45f && l01 in 0.25f..0.9f
                if (skinLike) sEff *= 0.88f
                r = gray + (r - gray) * sEff
                g = gray + (g - gray) * sEff
                b = gray + (b - gray) * sEff
                // 4. WB
                r *= p.rGain; g *= p.gGain; b *= p.bGain
                // Filmic-плечо: мягкий переход вместо рваного клиппа (деталей в клиппе
                // нет — сенсор насыщен, но пятно будет плавным, без цветной каймы).
                run {
                    val mx0 = maxOf(r, g, b)
                    if (mx0 > 235f) {
                        var f = (mx0 - 235f) / 20f
                        if (f > 1.5f) f = 1.5f
                        val lumw = 0.299f * r + 0.587f * g + 0.114f * b
                        var d = f * 0.6f
                        if (d > 0.9f) d = 0.9f
                        r += (lumw - r) * d; g += (lumw - g) * d; b += (lumw - b) * d
                        val mx2 = maxOf(r, g, b)
                        if (mx2 > 235f) {
                            val k = (235f + (mx2 - 235f) * 0.5f) / mx2
                            r *= k; g *= k; b *= k
                        }
                    }
                }
                px[i] = -0x1000000 or
                    (r.toInt().coerceIn(0, 255) shl 16) or
                    (g.toInt().coerceIn(0, 255) shl 8) or
                    b.toInt().coerceIn(0, 255)
            }
            bmp.setPixels(px, 0, w, 0, y, w, hh)
            y += hh
        }
        return bmp
    }
}
