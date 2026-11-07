package com.example.thundercam20.imageprocessing

/**
 * P0 конфиг восстановления TECNO -> Canon (робастные значения).
 * Слайдеры: 0..1 где 1 = полный фикс по ТЗ.
 * Диапазоны из robust_recalc.py 95% CI.
 */
data class RestoreConfig(
    val toneStrength: Float = 1f,
    val contrastFactor: Float = ContrastProcessor.RESTORE_FACTOR,
    val saturationFactor: Float = SaturationProcessor.RESTORE_FACTOR,
    val warmth: Float = 1f,
    val aperture: Float = 16f, // f/16 выкл, f/1.4 открыто — софт-диафрагма
    val enableClahe: Boolean = false,
    val enableUnreliable: Boolean = false,
    val enableDenoise: Boolean = false,
    val enableSharpen: Boolean = false,
    val denoiseMode: Int = 1, // 0 выкл, 1 авто (сильный в темноте + лёгкий всегда), 2 макс (сильный всегда)
    val globalUnify: Boolean = true, // глобальная доводка текстуры (отдельная стадия, тумблер в настройках)
    // Кадр уже усреднён мультикадром (ночь/HDR): шум снижен √N ДО тонмаппинга,
    // лифт теней душится мягче — на стеке старые ×0.5×0.45 давали сумеречно-тёмный.
    val stacked: Boolean = false,
) {
    fun adaptForBrightness(meanL: Float): RestoreConfig {
        if (toneStrength == 0f && contrastFactor == 1f && saturationFactor == 1f && warmth == 0f) return this
        // Сумерки 0.18–0.48 — главная ловушка: HAL-кадр захвата ярче превью
        // (другая AE-точка полноразмера), а P0-лифт поверх даёт выжигание.
        // Полный рецепт + shadowLift + CLAHE = выбеленное шумное мыло.
        // Режем тон и насыщенность, тепло почти не трогаем.
        // Было 0.65/0.70/0.60 — выжигало, стало 0.50/0.60/0.50.
        if (meanL in 0.18f..0.48f) {
            return copy(
                toneStrength = toneStrength * 0.50f,
                contrastFactor = 1f + (contrastFactor - 1f) * 0.60f,
                saturationFactor = 1f + (saturationFactor - 1f) * 0.50f,
                warmth = warmth * 0.90f
            )
        }
        return when {
            meanL < 0.33f -> this
            meanL < 0.40f -> this
            else -> copy( // BRIGHT: теперь 0.85/0.85/0.90/0.90 — почти полный, чтобы на максимуме было видно
                toneStrength = toneStrength * 0.85f,
                contrastFactor = 1f + (contrastFactor - 1f) * 0.85f,
                saturationFactor = 1f + (saturationFactor - 1f) * 0.90f,
                warmth = warmth * 0.90f
            )
        }
    }
    companion object {
        val Default = RestoreConfig()
        val Mild = RestoreConfig(toneStrength = 0.6f, contrastFactor = 1.05f, saturationFactor = 1.25f, warmth = 0.7f, aperture = 16f)
        val Strong = RestoreConfig(toneStrength = 1f, contrastFactor = 1.15f, saturationFactor = 1.54f, warmth = 1f, aperture = 16f)
        val Off = RestoreConfig(toneStrength = 0f, contrastFactor = 1f, saturationFactor = 1f, warmth = 0f, aperture = 16f, denoiseMode = 0)
        val Portrait = RestoreConfig(toneStrength = 0.8f, contrastFactor = 1.08f, saturationFactor = 1.35f, warmth = 0.95f, aperture = 2.0f)
    }
}
