package com.example.thundercam20.imageprocessing

import android.graphics.Bitmap
import com.example.thundercam20.analysis.SceneAnalyzer

/**
 * Главный процессор — P0 цепочка TECNO -> Canon (робастный ТЗ).
 * Порядок из photo_converter.py restore_cv2():
 *  1. Tone-mapping (L - d)
 *  2. Contrast global ×1.087
 *  3. Saturation HSV ×1.428
 *  4. WB (Lab a+1.67 b+4.79 / RGB)
 * P2 (sharp/noise/jpeg) выкл по умолчанию — ненадёжно (CV>3 p>0.11)
 */
class ImageProcessor {

    private val toneMapper = ToneMapper()
    private val contrastProcessor = ContrastProcessor()
    private val saturationProcessor = SaturationProcessor()
    private val wbProcessor = WhiteBalanceProcessor()
    private val apertureProcessor = ApertureProcessor()
    private val analyzer = ImageAnalyzer()
    private val noiseReducer = NoiseReducer()
    private val sharpener = Sharpener()

    fun process(bitmap: Bitmap, config: RestoreConfig = RestoreConfig.Default, context: android.content.Context? = null, focusPointNorm: android.graphics.PointF? = null, autoScene: Boolean = false, iso: Int = 0): ProcessedImage {
        val tStart = System.nanoTime()
        val tm = com.example.thundercam20.utils.Timing("P0stages")
        val analysis = analyzer.analyze(bitmap)
        tm.mark("analyze")
        val meanL = (analysis.avgBrightness / 255.0).toFloat()
        val scene = SceneAnalyzer.analyze(bitmap, meanL, analysis.contrastEst.toFloat(), analysis.avgSaturation.toFloat())
        // Авто-пресет: сцена → именованный рецепт (еда/ночь/портрет/текст/пейзаж),
        // он ГЛАВНЫЙ, слайдеры юзера — поправка 35%. Публикуем что применили
        // (бейдж режима + EXIF): пользователь видит работу автопилота.
        val scenePresetId = if (autoScene) app.grapheneos.camera.thmc.ThmcPresets.scenePreset(scene.scene) else null
        try {
            app.grapheneos.camera.thmc.ThmcTrackState.lastScenePreset = scenePresetId ?: ""
            app.grapheneos.camera.thmc.ThmcTrackState.lastSceneTs = System.currentTimeMillis()
        } catch (_: Throwable) {}
        try { android.util.Log.i("ThmcScene", "${scene.scene.name} preset=${scenePresetId ?: "-"} meanL=${"%.2f".format(meanL)} noise=${analysis.hasHighNoise} auto=$autoScene iso=$iso") } catch (_: Throwable) {}
        val perSceneRaw = if (scenePresetId != null) {
            app.grapheneos.camera.thmc.ThmcPresets.restoreConfigOf(app.grapheneos.camera.thmc.ThmcPresets.byId(scenePresetId))
        } else {
            SceneAnalyzer.perSceneTweak(scene.scene, meanL)
        }
        // Авто-сцена вкл → микс: сцена задает базу, юзер правит.
        // Было 65% сцена / 35% юзер — ползунки почти не чувствовались (жалоба с поля).
        // Стало наоборот: ручной рецепт главный (65%), сцена подстраивает (35%).
        // Явный «выкл» шумодава юзера святой и тут.
        var adaptiveBase = if (autoScene) {
            if (config == RestoreConfig.Default) perSceneRaw.adaptForBrightness(meanL)
            else {
                val mixed = RestoreConfig(
                    toneStrength = (perSceneRaw.toneStrength * 0.35f + config.toneStrength * 0.65f).coerceIn(0f, 1.1f),
                    contrastFactor = 1f + ((perSceneRaw.contrastFactor - 1f) * 0.35f + (config.contrastFactor - 1f) * 0.65f).coerceIn(-0.1f, 0.25f),
                    saturationFactor = 1f + ((perSceneRaw.saturationFactor - 1f) * 0.35f + (config.saturationFactor - 1f) * 0.65f).coerceIn(-0.1f, 0.6f),
                    warmth = (perSceneRaw.warmth * 0.35f + config.warmth * 0.65f).coerceIn(0.8f, 1.2f),
                    aperture = config.aperture,
                    enableClahe = config.enableClahe,
                    enableUnreliable = config.enableUnreliable,
                    enableDenoise = config.enableDenoise,
                    enableSharpen = config.enableSharpen,
                    // Шумодав сцены сильнее ручного — но явный «выкл» юзера святой.
                    denoiseMode = if (config.denoiseMode == 0) 0 else maxOf(config.denoiseMode, perSceneRaw.denoiseMode),
                    globalUnify = config.globalUnify,
                    stacked = config.stacked
                )
                mixed.adaptForBrightness(meanL)
            }
        } else {
            if (config == RestoreConfig.Default) perSceneRaw.adaptForBrightness(meanL)
            else config.adaptForBrightness(meanL)
        }
        // Превью-матч: кадр захвата ярче превью (HAL AE полноразмера светлее
        // стрима ~2fps) — значит превью уже правильно экспонировано, а P0-лифт
        // поверх даст выжигание. Душим тон пропорционально дельте.
        // Именно это чинит «предпросмотр лучше фото».
        try {
            val prevL = app.grapheneos.camera.thmc.ThmcTrackState.lastPreviewL
            if (prevL in 0.05f..0.95f) {
                val delta = meanL - prevL
                if (delta > 0.07f) {
                    val k = (1f - (delta - 0.07f) * 2.0f).coerceIn(0.35f, 1f)
                    adaptiveBase = adaptiveBase.copy(
                        toneStrength = adaptiveBase.toneStrength * k,
                        contrastFactor = 1f + (adaptiveBase.contrastFactor - 1f) * k,
                        saturationFactor = 1f + (adaptiveBase.saturationFactor - 1f) * k
                    )
                }
            }
        } catch (_: Throwable) {}
        // Бумажный гард: яркое + почти ч/б (лист, документы) — мягкий контраст, без шарпа,
        // иначе faint-принт выжигается в чёрный
        val paperLike = meanL > 0.55f && analysis.avgSaturation < 0.18
        val shadowL = shadowMean(bitmap)
        // Сумерки или глубокие тени с точечным светом (фонарь ночью):
        val twilit = (meanL in 0.18f..0.48f) || (shadowL < 0.20f && meanL < 0.55f)
        // РАННЯЯ гипотеза шума — ДО лифта, одной цифрой решают и «душить ли
        // тон/насыщенность» (блок ниже) и «включать ли шумодав» (стадия dn).
        // hasHighNoise меряет по 320px (зерно умирает при даунскейле) и по
        // первым 2000 flat-пикселям сверху — в городе с малым небом false-negative.
        // measNoise — честная σ полного разрешения нативного кадра: главный
        // аргумент, когда EXIF врал/пуст. Порог 3.0 уровня ≈ видимое зерно в
        // поднятых тенях. -1 = измерение невозможно (кадр без плоских зон) —
        // тогда живём по старым гейтам.
        val measNoise = if (meanL < 0.62f && !paperLike) analyzer.measureNoiseSigma(bitmap) else -1.0
        // Низкий известный ISO доверяем больше измерения: плотная ТЕКСТУРА
        // (листва, кирпич) тоже даёт σ 3–6, а при ISO≤320 зерна физически нет.
        // 4.5, не 3.0: на тише σ 3–4 шумодав мылил чистые кадры (жалоба «мыльно,
        // отличия от стока нет»). Видимое зерно в 8-бит JPEG — от ~5.
        val measNoisy = when {
            measNoise < 0 -> false
            iso in 1..320 -> measNoise >= 7.0
            else -> measNoise >= 4.5
        }
        val noisyAssume = analysis.hasHighNoise || measNoisy ||
            (twilit && iso >= 640) || (iso == 0 && meanL < 0.50f && measNoise < 0)
        if (paperLike) {
            adaptiveBase = adaptiveBase.copy(
                contrastFactor = 1f + (adaptiveBase.contrastFactor - 1f) * 0.35f,
                toneStrength = adaptiveBase.toneStrength * 0.5f,
                saturationFactor = 1f + (adaptiveBase.saturationFactor - 1f) * 0.5f
            )
        }
        // Шумный полумрак: не докрашиваем зерно saturation в цветные крапинки
        // и не вытягиваем лифтом теней зерно наружу. На высоких ISO — почти не
        // лифтим вообще: кадр и так на пределе, любой лифт = яркость + зерно.
        // isoBand: 0 = норма, 1 = высокое (1600+), 2 = предельное (2500+).
        val isoBand = when {
            iso >= 2500 -> 2
            iso >= 1600 -> 1
            else -> 0
        }
        // Для ДУШЕНИЯ лифта отдельная оценка: измеренная σ (или темный кадр без
        // ISO и без измерений — консервативно «высокое ISO», Tecno HAL иногда
        // не пишет тег). Сигмы шумодава продолжают верить честному isoBand
        // плюс измерению (см. denoiseV2).
        val dampBand = when {
            isoBand >= 1 -> isoBand
            measNoisy -> 1                          // измерение говорит «зерно» (с поправкой на честный ISO)
            iso == 0 && meanL < 0.42f && measNoise < 0 -> 1 // темно, ISO нет, измерить нечем — перестраховка
            else -> 0
        }
        val isoMid = isoBand >= 1
        if (noisyAssume && meanL < 0.55f && !paperLike) {
            adaptiveBase = adaptiveBase.copy(
                // Ночная сцена любит saturation ×1.5+, который докрашивает зерно
                // в цветные крапинки: держим потолок, но не в ноль — ×0.45/1.1
                // убивали сам «эффект улучшения» (жалоба: «нет отличия от стока»).
                saturationFactor = if (adaptiveBase.saturationFactor > 1f)
                    minOf(
                        1f + (adaptiveBase.saturationFactor - 1f) * when {
                            adaptiveBase.stacked && dampBand < 2 -> 0.9f
                            dampBand == 2 -> 0.45f
                            dampBand == 1 -> 0.65f
                            else -> 0.8f
                        },
                        if (dampBand >= 2) 1.2f else 2f
                    ) else adaptiveBase.saturationFactor,
                // Лифт теней — главный усилитель зерна: шумодав стоит ПОСЛЕ
                // processDirect и не может вернуть недодержку обратно, а
                // bilateral по уже поднятому в 8 битах зерну бьёт мимо.
                // При высоком/неизвестном ISO лифт урезаем, но НЕ в ноль:
                // ×0.35/×0.5 на сумерках давали «нет отличия от стока, только
                // мыльно». Полевые значения: band2 ×0.6, band1 ×0.75, single ×0.85.
                toneStrength = if (meanL < 0.45f) adaptiveBase.toneStrength * when {
                    adaptiveBase.stacked -> when (dampBand) { 2 -> 0.95f; 1 -> 0.95f; else -> 1f }
                    else -> when (dampBand) { 2 -> 0.6f; 1 -> 0.75f; else -> 0.85f }
                } else adaptiveBase.toneStrength
            )
        }
        var out = bitmap
        // Порядок HDR+-стиля: ШУМОДАВ ДО ТОНА. Раньше лифт шёл первым и усиливал
        // зерно ×1.3–1.6, а шумодав потом воевал с уже поднятым — отсюда «грязь»
        // в сумерках. Теперь: чистка на исходных уровнях → тон/контраст/
        // насыщенность → возврат деталей (crisp) уже на тонированном кадре.
        var ownedTemp: Bitmap? = null
        // Решение о силе — та же логика, что раньше решала ПОСЛЕ тона:
        // mode 0 — выкл; mode 2 — сильный в темноте, иначе лёгкий; mode 1 —
        // сильный только при реальной нужде, иначе лёгкий при шуме.
        val dayLike = meanL > 0.48f && !paperLike && !(isoMid && noisyAssume)
        val darkScene = scene.scene == SceneAnalyzer.Scene.NIGHT || scene.scene == SceneAnalyzer.Scene.DARK_INDOOR
        val strongNeed = (adaptiveBase.enableDenoise || darkScene || meanL < 0.38f) && noisyAssume && !dayLike && !paperLike
        val isStrong = when (adaptiveBase.denoiseMode) {
            0 -> false
            2 -> (meanL < 0.38f || darkScene) && noisyAssume && !paperLike
            else -> strongNeed
        }
        val isLight = adaptiveBase.denoiseMode != 0 && !isStrong && noisyAssume && !paperLike
        var recipe = if (adaptiveBase.denoiseMode == 0) "dn0" else "clean"
        var preStrong = false
        if (isStrong || isLight) {
            // Шумодав освобождает вход, а bitmap принадлежит вызывателю
            // (нужен для blend при силе <100%) — работаем на копии-владении.
            val src = if (out === bitmap) {
                try { bitmap.copy(Bitmap.Config.ARGB_8888, false) } catch (_: Throwable) { null }
            } else out
            if (src != null) {
                val (r, tg) = if (isStrong) {
                    denoiseStrong(src, isoBand, adaptiveBase.globalUnify, iso, measNoise)
                } else {
                    denoiseLight(src, iso, measNoise)
                }
                out = r
                ownedTemp = if (r !== bitmap) r else null
                recipe = if (isStrong) "pre-strong-$tg" else "pre-$tg"
                preStrong = isStrong
                tm.mark("denoise")
            }
        }
        if (NativeProcessor.isAvailable()) {
            // CLAHE вместо global: при enableClahe в натив отдаём contrast=1.0,
            // иначе двойной контраст (global + CLAHE).
            val nativeConfig = if (adaptiveBase.enableClahe) adaptiveBase.copy(contrastFactor = 1f) else adaptiveBase
            val p0 = NativeProcessor.processBitmap(out, nativeConfig, meanL)
            if (p0 !== out) {
                if (ownedTemp != null) { try { out.recycle() } catch (_: Exception) {}; ownedTemp = null }
                out = p0
            }
            if (adaptiveBase.enableClahe) {
                val cl = contrastProcessor.applyClaheFast(out, clipLimit = 1.8f)
                if (cl !== out) { try { out.recycle() } catch (_: Exception) {} }
                out = cl
            }
        } else {
            val t = toneMapper.restore(out, adaptiveBase.toneStrength)
            if (t !== out) { val tmp = out; out = t; if (ownedTemp == tmp) ownedTemp = null; try { if (tmp !== bitmap && tmp !== out) tmp.recycle() } catch (_: Exception) {} }
            val c = if (adaptiveBase.enableClahe) contrastProcessor.applyClaheFast(out, clipLimit = 1.8f) else contrastProcessor.restoreGlobalContrast(out, adaptiveBase.contrastFactor)
            if (c !== out) { try { out.recycle() } catch (_: Exception) {} }
            out = c
            val s = saturationProcessor.restore(out, adaptiveBase.saturationFactor)
            if (s !== out) { try { out.recycle() } catch (_: Exception) {} }
            out = s
            val w = wbProcessor.restore(out, adaptiveBase.warmth)
            if (w !== out) { try { out.recycle() } catch (_: Exception) {} }
            out = w
        }
        // Kotlin-путь мог отработать in-place: свой temp не утек, чужое не тронуто
        if (ownedTemp != null && ownedTemp !== out) { try { ownedTemp?.recycle() } catch (_: Exception) {} }
        ownedTemp = null
        // ОДИН мягкий CLAHE в сумерках/контровом свете — ВЫКЛЮЧЕН как авто.
        // Причина: превью HAL без CLAHE выглядит правильно, а авто-CLAHE на capture
        // выжигающе светлит (clip 1.25 на уже ярком HAL-кадре) и тащит зерно наружу.
        // hasHighNoise-гейт ненадёжен (первые 2000 flat-пикселей, thr 4.5): на городе
        // с малым небом возвращает false -> CLAHE срабатывает именно на шумном.
        // Включается только явным enableClahe (галерея Enhance), на захвате — никогда.
        if (false && twilit && !paperLike && !adaptiveBase.enableClahe && !isoMid && !analysis.hasHighNoise) {
            val lifted = contrastProcessor.applyClaheFast(out, clipLimit = 1.25f)
            if (lifted !== out) { try { out.recycle() } catch (_: Exception) {} }
            out = lifted
        }
        if (adaptiveBase.aperture < 15.5f) {
            // FaceBokeh вместо эвристического depth (тот давал инвертированные круги)
            val withBokeh = FaceBokeh.apply(out, adaptiveBase.aperture, focusPointNorm)
            if (withBokeh !== out) { try { out.recycle() } catch (_: Exception) {} }
            out = withBokeh
        }
        // Портрет: кожа не горит — desat-маска по лицам (сцена или пресет с диафрагмой)
        if (scene.scene == SceneAnalyzer.Scene.PORTRAIT || adaptiveBase.aperture < 15.5f) {
            val soft = FaceProtect.protect(out)
            if (soft !== out) { try { out.recycle() } catch (_: Exception) {} }
            out = soft
        }

        tm.mark("p0core")
        // Шумодав уже отработал ДО тона (см. начало метода). Здесь — только
        // добивка крупной низкочастотной хроматики, которую оставил лёгкий
        // шумодав. Сильный пре-шумодав уже вымыл хрому — второй проход не нужен
        // (и это экономит один full-res проход: скорость).
        if (!preStrong && noisyAssume && meanL < 0.45f && !paperLike && adaptiveBase.denoiseMode != 0) {
            try {
                val clean = noiseReducer.chromaSmooth(out, 0.9f)
                if (clean !== out) { try { out.recycle() } catch (_: Exception) {} }
                out = clean
                recipe += "+chroma"
            } catch (_: Throwable) {}
        }
        // Дневная структура («pop»). Чистый спокойный кадр иначе почти не
        // отличается от стока: в яркой ветке тон-лифт это +2% — незаметно.
        // Локальный контраст ±24 по low-freq (тот же инструмент, что в Lite)
        // даёт объём и не поднимает зерно — стадия идёт только на измеренно
        // чистых кадрах (measNoise < 3, включая «не измерено днём»).
        if (!noisyAssume && measNoise < 3.0 && meanL > 0.45f && !paperLike &&
            adaptiveBase.contrastFactor > 1f && out.isMutable) {
            try {
                P0Lite.localContrast(out, 0.26f)
                // HAL шарпит ДО нас, а мы декодим и перекодируем JPEG — без
                // микроконтраста кадр выходит мыльнее стока (полевая жалоба).
                // Чистый кадр (σ<3): порог 6 не поднимает зерно.
                val sh = sharpener.unsharpFast(out, radius = 0.8f, amount = 0.30f, threshold = 6.0)
                if (sh !== out) {
                    if (out !== bitmap) try { out.recycle() } catch (_: Exception) {}
                    out = sh
                }
                recipe += "+loc"
            } catch (_: Throwable) {}
        }
        // V2 уже вернул детали + crisp внутри (валидированный пайп) — пост-крисп только legacy.
        val v2path = recipe.startsWith("v2")
        // Возврат деталей после жёсткого шумодава: мягкий unsharp только по яркости
        // (хрома не трогается — цветное зерно не поднимается). Порог 9 отсекает
        // остаточное зерно в плоском, реальные кромки усиливаются.
        var crisp = false
        if (!v2path && adaptiveBase.denoiseMode != 0 && noisyAssume && meanL < 0.45f && !paperLike) {
            try {
                // Порог 9 отсекает зерно; на 1600+ ISO зерно крупнее порога —
                // задирать 12, иначе crisp возвращает то, что шумодав подавил.
                val crispThr = if (isoBand >= 1) 12.0 else 9.0
                val c = sharpener.unsharpFast(out, radius = 0.8f, amount = 0.60f, threshold = crispThr)
                if (c !== out) { try { out.recycle() } catch (_: Exception) {} }
                out = c
                crisp = true
            } catch (_: Throwable) {}
        }
        // Резкость только при реальном блюре — иначе поднимает зерно (было: twilit/meanL триггерили шарп всегда).
        if ((adaptiveBase.enableSharpen) && analysis.hasBlur && !paperLike && meanL < 0.72f) {
            val sharp = sharpener.unsharpFast(out)
            if (sharp !== out) { try { out.recycle() } catch (_: Exception) {} }
            out = sharp
        }
        // Не трогать до штатива: jpeg/sharp/noise — помечено unreliable
        tm.mark("sharpen")
        tm.report()
        if (crisp) recipe += "+crisp"
        // σ в рецепт — для диагностики БЕЗ логов: видно в «Подробностях»
        // галереи (EXIF ImageDescription). Разрешает спор «шумно или мыльно»:
        // s=2 это чисто, s=8 это зерно, а dn-тег покажет, что шумодав был.
        if (measNoise >= 0) recipe += " s=" + "%.1f".format(measNoise)
        // Время обработки в рецепт — видно в «Подробностях» без логов.
        try { recipe += " t=" + ((System.nanoTime() - tStart) / 1_000_000) + "ms" } catch (_: Throwable) {}
        val sceneName = if (scenePresetId != null) "${scene.scene.name}/${scenePresetId}" else scene.scene.name
        try { app.grapheneos.camera.thmc.ThmcTrackState.lastRecipe = "$sceneName $recipe".trim() } catch (_: Throwable) {}
        return ProcessedImage(out, analysis, sceneName, recipe)
    }

    /**
     * Средний нижний квартиль яркости (0-1): темнота фона при ярком объекте (фонарь ночью).
     * Гистограмма 64 бина на FastGray 240 — дёшево, без сортировки.
     */
    private fun shadowMean(bmp: Bitmap): Float {
        return try {
            val g = com.example.thundercam20.analysis.FastGray.of(bmp, 240)
            if (g.y.isEmpty()) return 1f
            val hist = IntArray(64)
            for (v in g.y) hist[(v.coerceIn(0f, 255f) / 4f).toInt().coerceIn(0, 63)]++
            var need = g.y.size / 4
            var sum = 0L; var cnt = 0
            for (i in 0..63) {
                val c = hist[i]
                if (c == 0) continue
                val take = minOf(c, need)
                sum += take.toLong() * (i * 4 + 2)
                cnt += take
                need -= take
                if (need <= 0) break
            }
            if (cnt == 0) 1f else sum.toFloat() / cnt / 255f
        } catch (_: Throwable) { 1f }
    }

    /**
     * Сильный шумодав (ночь/сумерки): раздельный YCrCb — цветные пятна давятся
     * хромой на 85%, люма чистится умеренным bilateral (микро-контраст живёт —
     * умному rescue есть что возвращать). Детали возвращает когерентный
     * detailBlend v2: связная структура (фактура/кромки) — из оригинала,
     * изолированные всплески (зерно) — остаются давлеными, хрома всегда smooth.
     */
    /**
     * V2 из C:\Users\pc\denoiser (thundercam_pipe_v2.py): полный валидированный
     * пайп (dual-bilateral + shrinkage-возврат + crisp внутри). null — нельзя
     * (нет OpenCV / OOM / ошибка) → вызыватель идёт legacy-путем.
     * Сигма: ISO из EXIF (sigma_from_iso) когда известно, иначе автооценка.
     * globalRefine-тумблер на V2-пути не действует осознанно: v2 валидирован
     * как целое, лишние стадии поверх — только через новую валидацию.
     */
    private fun denoiseV2(out: Bitmap, iso: Int, strong: Boolean, meas: Double): Pair<Bitmap, String>? {
        if (!com.example.thundercam20.utils.OpencvManager.available) return null
        if (out.config != Bitmap.Config.ARGB_8888) return null
        return try {
            // Приор по ISO (a=1.8 — бюджетник в гамма-домене шумнее «эталона»)
            // и измерение полного разрешения: берём БОЛЬШЕЕ. EXIF соврал вниз —
            // измерение вытащит правду; текстура завысила измерение на низком
            // ISO — приор не даст пережечь (meas для низкого ISO гейтится выше).
            val prior = if (iso > 0) DenoiseCommon.sigmaFromIso(iso, a = 1.8) else null
            val measS = if (meas >= 2.5 && !(iso in 1..320)) meas.coerceAtMost(14.0) else -1.0
            val ov: DenoiseV2.Sig? = when {
                prior != null && measS > prior.first -> DenoiseV2.Sig(measS, (measS * 1.8).coerceIn(4.0, 30.0))
                // «ISO шумное, кадр тихий» (стек/хороший свет) — не пережимать:
                // null => V2 оценит σ сам по кадру (там честный полный размер).
                prior != null && measS > 0 && measS < prior.first -> null
                prior != null -> DenoiseV2.Sig(prior.first, prior.second)
                measS > 0 -> DenoiseV2.Sig(measS, (measS * 1.8).coerceIn(4.0, 30.0))
                else -> null
            }
            val tV = System.nanoTime()
            val (bmp, tag) = DenoiseV2.processBitmap(out, clean = strong, sigmaOverride = ov)
            try {
                android.util.Log.i(
                    "ThmcP0",
                    "denoiseV2 ${(System.nanoTime() - tV) / 1_000_000}ms tag=$tag iso=$iso"
                )
            } catch (_: Throwable) {}
            Pair(bmp, "v2$tag")
        } catch (e: OutOfMemoryError) {
            try { android.util.Log.w("ThmcP0", "denoiseV2 oom → legacy") } catch (_: Throwable) {}
            null
        } catch (t: Throwable) {
            try { android.util.Log.w("ThmcP0", "denoiseV2 fail → legacy", t) } catch (_: Throwable) {}
            null
        }
    }

    private fun denoiseStrong(out: Bitmap, isoBand: Int = 0, unify: Boolean = true, iso: Int = 0, meas: Double = -1.0): Pair<Bitmap, String> {
        denoiseV2(out, iso, strong = true, meas)?.let { (bmp, tag) ->
            if (bmp !== out) try { out.recycle() } catch (_: Exception) {}
            // Добивка цветных пятен на высоком ISO: V2 иногда оставляет крупные
            // хроматические blotches (видно на ночных сумерках) — хрома дешево, люму не трогаем.
            if (iso >= 1600) {
                try {
                    val clean = noiseReducer.chromaSmooth(bmp, 0.85f)
                    if (clean !== bmp) try { bmp.recycle() } catch (_: Exception) {}
                    return Pair(clean, tag + "+ch")
                } catch (_: Throwable) { return Pair(bmp, tag) }
            }
            return Pair(bmp, tag)
        }
        return denoiseStrongLegacy(out, isoBand, unify, iso)
    }

    private fun denoiseStrongLegacy(out: Bitmap, isoBand: Int = 0, unify: Boolean = true, iso: Int = 0): Pair<Bitmap, String> {
        val (denoised, sig) = noiseReducer.denoiseYCrCb(out, chromaBlend = 0.85f)
        // Пороги от σ (стенд: t0≈0.4σ, t1≈1.9·t0): шумно — строго, чисто — мягко.
        val t0b = maxOf(1.5f, (0.4f * sig).toFloat())
        val t1b = t0b * 1.9f
        val res = noiseReducer.detailBlend(out, denoised, t0b, t1b)
        // Глобальная доводка твоего алгоритма (отдельная стадия, тумблер globalUnify).
        // Читает out (вход шумодава) — вызывать ДО recycle out ниже.
        var tag = "s" + sig.toInt()
        var final = res
        // Доводка выключена на высоком ISO: Otsu+компоненты на зерне возвращают
        // шумовые комки как «фактуру» — детали плывут. Только чистое среднее/низкое ISO.
        if (unify && isoBand < 1) {
            try {
                val tG = System.nanoTime()
                val g = noiseReducer.globalTextureRefine(out, res, t0b)
                if (g !== res) {
                    try { res.recycle() } catch (_: Exception) {}
                    final = g
                    tag = "+G"
                }
                try { android.util.Log.i("ThmcP0", "globalRefine ${(System.nanoTime() - tG) / 1_000_000}ms tag=$tag") } catch (_: Throwable) {}
            } catch (_: Throwable) {}
        }
        if (res !== denoised) try { denoised.recycle() } catch (_: Exception) {}
        if (final !== out) try { out.recycle() } catch (_: Exception) {}
        // Высокое ISO: финальная добивка хромы (крупные цветные пятна), люма не тронута.
        if (iso >= 1600) {
            try {
                val clean = noiseReducer.chromaSmooth(final, 0.85f)
                if (clean !== final) try { final.recycle() } catch (_: Exception) {}
                return Pair(clean, tag + "+ch")
            } catch (_: Throwable) {}
        }
        return Pair(final, tag)
    }

    /** Лёгкий дневной шумодав: убирает цветной мусор матрицы и лёгкое зерно без мыла. */
    private fun denoiseLight(out: Bitmap, iso: Int = 0, meas: Double = -1.0): Pair<Bitmap, String> {
        denoiseV2(out, iso, strong = false, meas)?.let { (bmp, tag) ->
            if (bmp !== out) try { out.recycle() } catch (_: Exception) {}
            return Pair(bmp, tag)
        }
        // Сумерки на высоком ISO: хрома 0.85 вместо 0.55 — иначе цветные крапинки.
        // Порог 640 в тон ранней noisyAssume (бюджетник шумит уже с 640).
        val blend = if (iso >= 640) 0.85f else 0.55f
        val denoised = noiseReducer.denoiseYCrCb(out, chromaBlend = blend).first
        if (denoised !== out) try { out.recycle() } catch (_: Exception) {}
        return Pair(denoised, "light")
    }

    // Совместимость: старый вызов без конфига
    fun process(bitmap: Bitmap): ProcessedImage = process(bitmap, RestoreConfig.Default)

    data class ProcessedImage(
        val bitmap: Bitmap,
        val analysis: ImageAnalyzer.ImageAnalysisResult,
        val sceneName: String = "",
        val recipe: String = ""
    )
}
