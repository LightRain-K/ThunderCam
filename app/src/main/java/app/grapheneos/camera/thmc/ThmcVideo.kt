package app.grapheneos.camera.thmc

/**
 * Видео-улучшение: тот же рецепт P0, что для фото (тон/контраст/насыщенность/тепло),
 * но применяется GPU-транскодом (шейдер), а не попиксельным CPU — иначе минуты на ролик.
 * Чистая математика картирования, тестируется на JVM.
 */
object ThmcVideo {

    /** Имя выходного файла рядом с исходником: IMG_..._THMC.mp4. */
    fun outName(srcName: String): String {
        val base = srcName.substringBeforeLast('.', srcName.ifEmpty { "video" })
        val clean = if (base.isBlank()) "video" else base
        return "${clean}_THMC.mp4"
    }

    /**
     * Параметры P0-лайт шейдера из слайдеров 0-100 и силы 0-1.
     * Та же математика, что нативный processDirect (фикс. рабочая яркость 0.35):
     * lift/pull — подъём теней/компресс светов, contrast/sat — множители,
     * гейны WB + bias синего, strength — бленд с оригиналом.
     */
    data class Grade(
        val lift: Float,
        val pull: Float,
        val contrast: Float,
        val sat: Float,
        val rGain: Float,
        val gGain: Float,
        val bGain: Float,
        val bBias: Float,
        val strength: Float,
    )

    fun gradeFor(strength01: Float, tone: Int, contrast: Int, sat: Int, warmth: Int): Grade {
        val t = tone.coerceIn(0, 100) / 100f
        val c = contrast.coerceIn(0, 100) / 100f
        val s = sat.coerceIn(0, 100) / 100f
        val w = warmth.coerceIn(0, 100) / 100f
        val darkness = (0.45f - 0.35f) / 0.45f // фикс. рабочая точка видео
        return Grade(
            lift = (0.12f + 0.26f * darkness) * t,
            pull = 0.08f * t,
            contrast = 1f + (1.087f - 1f) * c,
            sat = 1f + (1.428f - 1f) * s,
            rGain = 1f + ((1f / 0.98f) - 1f) * w,
            gGain = 1f + ((1f / 1.02f) - 1f) * w,
            bGain = 1f + ((1f / 1.06f) - 1f) * w,
            bBias = -0.025f * w,
            strength = strength01.coerceIn(0f, 1f),
        )
    }
}
