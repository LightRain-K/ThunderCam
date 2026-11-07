package app.grapheneos.camera.thmc

import androidx.annotation.StringRes
import app.grapheneos.camera.R

/**
 * Именованные рецепты P0 — один тап вместо шести слайдеров.
 *
 * Значения сверены с мобильной практикой (Lightroom/Snapseed/VSCO-рецепты):
 * - Еда: тёпло и сочно, но без пересвета (vibrance-логика: насыщенность умеренно,
 *   тепло чуть выше нейтрали, шумодав средний чтобы не убить фактуру);
 * - Портрет: мягко — контраст и насыщенность вниз (кожа не горит), шумодав выше;
 * - Ночь: цвет сдержан (зерно не красится), шумодав в максимум;
 * - Документ: почти ч/б, контраст вверх, шумодав в минимум (не мылить текст);
 * - Пейзаж: глубина — контраст и насыщенность вверх.
 * Шкала 0-100 совпадает со слайдерами. Чистая логика, тестируется на JVM.
 */
data class RecipePreset(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int,
    val tone: Int,
    val contrast: Int,
    val sat: Int,
    val warmth: Int,
    val denoise: Int,
)

object ThmcPresets {
    const val ID_STANDARD = "standard"
    const val ID_FOOD = "food"
    const val ID_PORTRAIT = "portrait"
    const val ID_NIGHT = "night"
    const val ID_DOCUMENT = "document"
    const val ID_LANDSCAPE = "landscape"
    const val ID_CUSTOM = "custom"

    val ALL: List<RecipePreset> = listOf(
        RecipePreset(ID_STANDARD, R.string.thmc_preset_standard, R.string.thmc_preset_standard_desc, 66, 66, 68, 50, 60),
        RecipePreset(ID_FOOD, R.string.thmc_preset_food, R.string.thmc_preset_food_desc, 65, 65, 78, 62, 50),
        RecipePreset(ID_PORTRAIT, R.string.thmc_preset_portrait, R.string.thmc_preset_portrait_desc, 55, 45, 45, 55, 70),
        RecipePreset(ID_NIGHT, R.string.thmc_preset_night, R.string.thmc_preset_night_desc, 65, 55, 40, 45, 100),
        RecipePreset(ID_DOCUMENT, R.string.thmc_preset_document, R.string.thmc_preset_document_desc, 50, 85, 10, 50, 15),
        RecipePreset(ID_LANDSCAPE, R.string.thmc_preset_landscape, R.string.thmc_preset_landscape_desc, 65, 75, 80, 55, 50),
    )

    /** Неизвестный id → стандарт. Чистая. */
    fun byId(id: String?): RecipePreset =
        ALL.find { it.id == (id ?: ID_STANDARD) } ?: ALL[0]

    /**
     * Точное совпадение слайдеров с пресетом → его id, иначе custom.
     * Чистая, тестируется на JVM.
     */
    fun matchId(tone: Int, contrast: Int, sat: Int, warmth: Int, denoise: Int): String =
        ALL.find { it.tone == tone && it.contrast == contrast && it.sat == sat && it.warmth == warmth && it.denoise == denoise }?.id
            ?: ID_CUSTOM

    /** Применить пресет к настройкам (слайдеры + id). */
    fun applyTo(settings: ThmcSettings, id: String) {
        val p = byId(id)
        settings.tone = p.tone
        settings.contrast = p.contrast
        settings.sat = p.sat
        settings.warmth = p.warmth
        settings.denoise = p.denoise
        settings.preset = p.id
    }

    /**
     * Сцена → пресет. null = сцена нейтральная (офис/день-индор): остаётся ручной рецепт.
     * Чистая, тестируется на JVM.
     */
    fun scenePreset(scene: com.example.thundercam20.analysis.SceneAnalyzer.Scene): String? = when (scene) {
        com.example.thundercam20.analysis.SceneAnalyzer.Scene.NIGHT -> ID_NIGHT
        com.example.thundercam20.analysis.SceneAnalyzer.Scene.DARK_INDOOR -> ID_NIGHT
        com.example.thundercam20.analysis.SceneAnalyzer.Scene.PORTRAIT -> ID_PORTRAIT
        com.example.thundercam20.analysis.SceneAnalyzer.Scene.TEXT -> ID_DOCUMENT
        com.example.thundercam20.analysis.SceneAnalyzer.Scene.FOOD -> ID_FOOD
        com.example.thundercam20.analysis.SceneAnalyzer.Scene.LANDSCAPE -> ID_LANDSCAPE
        com.example.thundercam20.analysis.SceneAnalyzer.Scene.DAY_OUTDOOR -> ID_LANDSCAPE
        com.example.thundercam20.analysis.SceneAnalyzer.Scene.DAY_INDOOR -> null
    }

    /**
     * Пресет → конфиг конвейера (та же математика, что ThmcP0.Params.toRestoreConfig:
     * слайдеры 0-100 в доли эффекта). Чистая, тестируется на JVM.
     */
    fun restoreConfigOf(p: RecipePreset): com.example.thundercam20.imageprocessing.RestoreConfig {
        val t = p.tone.coerceIn(0, 100) / 100f
        val c = p.contrast.coerceIn(0, 100) / 100f
        val s = p.sat.coerceIn(0, 100) / 100f
        val w = p.warmth.coerceIn(0, 100) / 100f
        return com.example.thundercam20.imageprocessing.RestoreConfig(
            toneStrength = t,
            contrastFactor = 1f + (com.example.thundercam20.imageprocessing.ContrastProcessor.RESTORE_FACTOR - 1f) * c,
            saturationFactor = 1f + (com.example.thundercam20.imageprocessing.SaturationProcessor.RESTORE_FACTOR - 1f) * s,
            warmth = w,
            aperture = 16f,
            enableDenoise = p.denoise > 0,
            denoiseMode = ThmcP0.denoiseMode(p.denoise),
            globalUnify = true,
        )
    }
}
