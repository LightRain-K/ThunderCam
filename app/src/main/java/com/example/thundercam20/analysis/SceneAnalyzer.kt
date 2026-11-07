package com.example.thundercam20.analysis

import android.graphics.Bitmap

/**
 * Адаптив по сценам — расширяет per-brightness tertiles DARK/MID/BRIGHT (robust_recalc q33 0.332 q66 0.402)
 * + indoor/outdoor/портрет/пейзаж/еда/текст эвристика. Позже можно заменить на ML Kit/TFLite,
 * сейчас эвристика на meanL/contrast/saturation/теплоте + соотношение сторон.
 */
object SceneAnalyzer {

    enum class Scene { NIGHT, DARK_INDOOR, DAY_INDOOR, DAY_OUTDOOR, PORTRAIT, LANDSCAPE, TEXT, FOOD }

    data class SceneResult(val scene: Scene, val meanL: Float, val confidence: Float)

    /**
     * Доля тёплых пикселей (r>g+12 и r>b+12) по даунскейлу. Чистая, тестируется на JVM.
     * Еда/закат тёплые (0.55+), пасмурный день/офис холодные.
     */
    fun warmRatio(px: IntArray): Float {
        if (px.isEmpty()) return 0f
        var n = 0
        for (p in px) {
            val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
            if (r > g + 12 && r > b + 12) n++
        }
        return n.toFloat() / px.size
    }

    /** Даунскейл кадра для цветовой статистики (дешёвый, ~25К px). */
    private fun samplePixels(bitmap: Bitmap, maxSide: Int = 160): IntArray {
        return try {
            val w = bitmap.width; val h = bitmap.height
            if (w <= 0 || h <= 0) return IntArray(0)
            val s = maxOf(w, h) / maxSide.coerceAtLeast(1)
            val sw = maxOf(1, w / maxOf(1, s)); val sh = maxOf(1, h / maxOf(1, s))
            val small = Bitmap.createScaledBitmap(bitmap, sw, sh, true)
            val out = IntArray(sw * sh)
            small.getPixels(out, 0, sw, 0, 0, sw, sh)
            if (small !== bitmap) try { small.recycle() } catch (_: Throwable) {}
            out
        } catch (_: Throwable) { IntArray(0) }
    }

    fun analyze(bitmap: Bitmap, meanL: Float, contrast: Float, saturation: Float): SceneResult {
        val w = bitmap.width; val h = bitmap.height
        val isPortrait = h > w
        val aspect = w.toFloat() / h
        // ночь по meanL
        if (meanL < 0.28f) return SceneResult(Scene.NIGHT, meanL, 0.9f)
        if (meanL < 0.33f) {
            // тёмный indoor vs night
            return if (saturation < 0.4f) SceneResult(Scene.DARK_INDOOR, meanL, 0.7f) else SceneResult(Scene.NIGHT, meanL, 0.6f)
        }
        // бумага/документ: ярко и почти ч/б — текст, а не пересвет (раньше TEXT был недостижим).
        if (meanL > 0.55f && saturation < 0.22f) return SceneResult(Scene.TEXT, meanL, 0.75f)
        if (meanL > 0.50f) {
            // яркий день outdoor
            return if (aspect > 1.6f) SceneResult(Scene.LANDSCAPE, meanL, 0.6f) else SceneResult(Scene.DAY_OUTDOOR, meanL, 0.7f)
        }
        // средний свет: сначала люди, потом еда, потом геометрия.
        val warm = try { warmRatio(samplePixels(bitmap)) } catch (_: Throwable) { 0f }
        if (isPortrait && saturation > 0.5f) return SceneResult(Scene.PORTRAIT, meanL, 0.6f)
        // еда: сочно + тепло + не панорама (закатные пейзажи широкие — они мимо).
        if (saturation > 0.45f && warm > 0.55f && aspect < 1.5f && meanL in 0.30f..0.75f) {
            return SceneResult(Scene.FOOD, meanL, 0.65f)
        }
        return when {
            !isPortrait && aspect > 1.4f -> SceneResult(Scene.LANDSCAPE, meanL, 0.6f)
            contrast < 0.4f -> SceneResult(Scene.DAY_INDOOR, meanL, 0.5f)
            else -> SceneResult(Scene.DAY_OUTDOOR, meanL, 0.5f)
        }
    }

    fun perSceneTweak(scene: Scene, meanL: Float): com.example.thundercam20.imageprocessing.RestoreConfig {
        val base = com.example.thundercam20.imageprocessing.RestoreConfig.Default
        // Усилил дневные — было 0.5/0.55 слабо, теперь 0.8/0.85 видно как в начале
        // Ночь/сумерки: насыщенность урезана (было 1.55/1.45 — докрашивала зерно
        // в цветные крапинки). Тон держим, цвет — нет: зерно давится, потом не цветёт.
        return when (scene) {
            Scene.NIGHT -> base.copy(toneStrength = 1f, contrastFactor = 1.14f, saturationFactor = 1.30f, warmth = 1f)
            Scene.DARK_INDOOR -> base.copy(toneStrength = 0.95f, contrastFactor = 1.12f, saturationFactor = 1.28f, warmth = 0.95f)
            Scene.DAY_INDOOR -> base.copy(toneStrength = 0.85f, contrastFactor = 1.10f, saturationFactor = 1.40f, warmth = 0.9f)
            Scene.DAY_OUTDOOR -> base.copy(toneStrength = 0.85f, contrastFactor = 1.12f, saturationFactor = 1.50f, warmth = 1f)
            Scene.PORTRAIT -> base.copy(toneStrength = 0.80f, contrastFactor = 1.08f, saturationFactor = 1.35f, warmth = 0.95f)
            Scene.LANDSCAPE -> base.copy(toneStrength = 0.85f, contrastFactor = 1.15f, saturationFactor = 1.55f, warmth = 1f)
            Scene.TEXT -> base.copy(toneStrength = 0.75f, contrastFactor = 1.18f, saturationFactor = 1.20f, warmth = 0.85f)
            Scene.FOOD -> base.copy(toneStrength = 0.85f, contrastFactor = 1.10f, saturationFactor = 1.45f, warmth = 1f)
        }.adaptForBrightness(meanL)
    }
}
