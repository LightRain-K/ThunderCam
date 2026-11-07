package app.grapheneos.camera.thmc

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidxc.camera.core.impl.utils.Exif
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * ThunderCam P0 поверх стокового захвата GrapheneOS.
 * Точка врезки — ImageSaver.saveImageInner, ПОСЛЕ processExif (EXIF уже полный).
 * Декод → P0 (тон/контраст/насыщенность/WB/шумодав) → код → пересадка EXIF 1:1.
 * Правило безопасности: любая проблема → null → сохраняется стоковый кадр, база не страдает.
 */
object ThmcP0 {
    private const val TAG = "ThmcP0"
    @Volatile private var lastCtx: android.content.Context? = null

    /** Рецепт P0 из ползунков (0-100 → доля эффекта стадии). */
    data class Params(
        val tone: Int = 100,
        val contrast: Int = 100,
        val sat: Int = 100,
        val warmth: Int = 100,
        val denoise: Int = 65,
        // Фронталка: мелкий сенсор + фикс-фокус → шумодав обязателен всегда (минимум авто).
        // Мастер-тумблер P0 главнее: выкл = сток без обработки вообще.
        val frontCamera: Boolean = false,
        // Для галерейного Enhance: CLAHE и шарп поверх (на захвате выкл по умолчанию).
        val clahe: Boolean = false,
        val sharpen: Boolean = false,
        // Авто-сцена: анализатор сцен сам выбирает пресет (ночь/портрет/текст/еда/сумерки)
        val autoScene: Boolean = false,
        // Глобальная доводка текстуры: отдельная стадия после шумодава (можно отключить)
        val globalRefine: Boolean = true,
        // Кадр уже усреднён серией (ночь/HDR стек): шум подавлен √N до P0,
        // лифт теней душится сильно слабее — иначе сумеречный стек выходит тёмным.
        val stacked: Boolean = false,
    ) {
        fun toRestoreConfig(): com.example.thundercam20.imageprocessing.RestoreConfig {
            val t = tone.coerceIn(0, 100) / 100f
            val c = contrast.coerceIn(0, 100) / 100f
            val s = sat.coerceIn(0, 100) / 100f
            val w = warmth.coerceIn(0, 100) / 100f
            val dm = maxOf(denoiseMode(denoise), if (frontCamera) 1 else 0)
            return com.example.thundercam20.imageprocessing.RestoreConfig(
                toneStrength = t,
                contrastFactor = 1f + (com.example.thundercam20.imageprocessing.ContrastProcessor.RESTORE_FACTOR - 1f) * c,
                saturationFactor = 1f + (com.example.thundercam20.imageprocessing.SaturationProcessor.RESTORE_FACTOR - 1f) * s,
                warmth = w,
                aperture = 16f,
                enableClahe = clahe,
                enableSharpen = sharpen,
                denoiseMode = dm,
                globalUnify = globalRefine,
                stacked = stacked,
            )
        }
    }

    /** Шумодав: 0 = выкл, 1-69 = авто (сильнее ночью), 70-100 = макс. */
    fun denoiseMode(v: Int): Int = when {
        v <= 0 -> 0
        v < 70 -> 1
        else -> 2
    }

    /**
     * ISO из EXIF источника (HAL пишет реальное ISO кадра). 0 = неизвестно.
     * Нужно P0 чтобы душить тон/насыщенность на высоких ISO вместо докрашивания зерна.
     */
    /**
     * Выдержка из EXIF в нс (TAG_EXPOSURE_TIME: "1/60" или десятичная). 0 = неизвестно.
     */
    fun readShutterNs(exifSource: ByteArray?): Long {
        if (exifSource == null) return 0L
        return try {
            val ei = androidx.exifinterface.media.ExifInterface(java.io.ByteArrayInputStream(exifSource))
            val raw = ei.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_EXPOSURE_TIME) ?: return 0L
            val sec = if (raw.contains("/")) {
                val (n, d) = raw.split("/").map { it.trim().toDouble() }
                if (d == 0.0) return 0L
                n / d
            } else raw.toDouble()
            if (sec <= 0.0 || sec > 5.0) 0L else (sec * 1_000_000_000.0).toLong()
        } catch (_: Throwable) { 0L }
    }

    fun readIso(exifSource: ByteArray?): Int {
        if (exifSource == null) return 0
        return try {
            val ei = androidx.exifinterface.media.ExifInterface(java.io.ByteArrayInputStream(exifSource))
            val iso = ei.getAttributeInt(
                androidx.exifinterface.media.ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0
            )
            if (iso > 0) iso else ei.getAttributeInt(
                androidx.exifinterface.media.ExifInterface.TAG_ISO_SPEED_RATINGS, 0
            )
        } catch (_: Throwable) { 0 }
    }

    fun apply(
        jpegWithExif: ByteArray,
        quality: Int,
        strength01: Float = 1f,
        params: Params = Params(),
        exifSource: ByteArray? = null,
        context: android.content.Context? = null,
        zoom: Float = 1f,
    ): ByteArray? {
        if (strength01 <= 0f) return null
        try { com.example.thundercam20.utils.OpencvManager.init() } catch (_: Throwable) {}
        return try {
            var bmp = try {
                BitmapFactory.decodeByteArray(jpegWithExif, 0, jpegWithExif.size)
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "p0 decode oom, retry sample=2")
                try {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                    BitmapFactory.decodeByteArray(jpegWithExif, 0, jpegWithExif.size, opts)
                } catch (_: Throwable) { null }
            } ?: return null
            if (bmp.config != Bitmap.Config.ARGB_8888) {
                val c = try {
                    bmp.copy(Bitmap.Config.ARGB_8888, false)
                } catch (e: OutOfMemoryError) {
                    Log.w(TAG, "p0 copy oom, stock kept")
                    return null
                } ?: return null
                ThmcBitmaps.release(bmp)
                bmp = c
            }
            applyFromBitmap(
                bmpIn = bmp,
                quality = quality,
                strength01 = strength01,
                params = params,
                exifSource = exifSource ?: jpegWithExif,
                context = context,
                zoom = zoom
            )
        } catch (t: Throwable) {
            Log.w(TAG, "p0 fallback to stock", t)
            null
        }
    }

    /**
     * Прямой вход с готового Bitmap (используется в HDR без лишнего JPEG encode→decode).
     * Освобождает bmpIn внутри.
     */
    fun applyFromBitmap(
        bmpIn: Bitmap,
        quality: Int,
        strength01: Float = 1f,
        params: Params = Params(),
        exifSource: ByteArray,
        context: android.content.Context? = null,
        zoom: Float = 1f,
    ): ByteArray? {
        try { com.example.thundercam20.utils.OpencvManager.init() } catch (_: Throwable) {}
        return try {
            val t0 = System.nanoTime()
            var bmp = if (bmpIn.config == Bitmap.Config.ARGB_8888) bmpIn else {
                val c = bmpIn.copy(Bitmap.Config.ARGB_8888, false) ?: return null
                ThmcBitmaps.release(bmpIn)
                c
            }
            // ThunderCam зум: кроп центра + апскейл до исходного размера, затем P0 (без двойного шумодава).
            // Сеть — только с ×2: ниже оптика/теле HAL и так идеальны (S25U 5× теле),
            // гонять нейронку ради долей процента — минуты впустую.
            if (zoom > 1.01f) {
                try {
                    val bw = bmp.width; val bh = bmp.height
                    val cw = ((bw / zoom).toInt().coerceAtLeast(16) / 2) * 2
                    val ch = ((bh / zoom).toInt().coerceAtLeast(16) / 2) * 2
                    val cropped = Bitmap.createBitmap(bmp, (bw - cw) / 2, (bh - ch) / 2, cw, ch)
                    // Предчистка хромы ДО сети: иначе SR раздувает цветное зерно кропа
                    // в пятна, а P0 потом дожаривает их насыщенностью («цветастый шум»).
                    val cleaned = try {
                        com.example.thundercam20.imageprocessing.NoiseReducer()
                            .chromaSmooth(cropped, 0.85f)
                    } catch (_: Throwable) { cropped }
                    if (cleaned !== cropped) ThmcBitmaps.release(cropped)
                    val useNet = zoom >= 2f
                    var srOk = false
                    var srWhy = ""
                    val up = if (useNet) context?.let {
                        // ZoomRouter: flat-skip + ROI + кап 2 тайла (единая точка для всех).
                        val r = ZoomRouter.upscale(it, cleaned, bw, bh, zoom)
                        srOk = r != null && com.example.thundercam20.imageprocessing.SuperResZoom.lastUsedSr
                        if (!srOk) srWhy =
                            com.example.thundercam20.imageprocessing.SuperResZoom.lastFailReason
                        r
                    } ?: run {
                        if (context == null) srWhy = "no-context"
                        null
                    } else null
                    val final = up ?: Bitmap.createScaledBitmap(cleaned, bw, bh, true)
                    if (final !== cleaned) ThmcBitmaps.release(cleaned)
                    ThmcBitmaps.release(bmp)
                    bmp = final
                    try {
                        android.util.Log.i(TAG, "zoom x${"%.1f".format(zoom)} sr=$srOk why=$srWhy")
                    } catch (_: Throwable) {}
                    // Закрытый тест: видно, включилась ли сеть, и почему нет.
                    // Без сети (<×2) — молча билинейный, тостить нечего.
                    try {
                        val ctx = context
                        if (ctx != null && useNet) {
                            val msg = if (srOk) "Зум ×${"%.0f".format(zoom)} • ИИ"
                            else "Зум ×${"%.0f".format(zoom)} • сток ($srWhy)"
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                try {
                                    android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
                                } catch (_: Throwable) {}
                            }
                        }
                    } catch (_: Throwable) {}
                } catch (t: Throwable) {
                    android.util.Log.w(TAG, "zoom crop failed, stock frame", t)
                }
            }
            val w = bmp.width; val h = bmp.height
            val res = com.example.thundercam20.imageprocessing.ImageProcessor()
                .process(bmp, params.toRestoreConfig(), null, null, params.autoScene, readIso(exifSource))
            // Калибровка ручной экспозиции: (выдержка×ISO → яркость) с этого кадра.
            try {
                val ctx = context ?: lastCtx
                if (ctx != null) {
                    lastCtx = ctx.applicationContext
                    val prefs = ThmcManualEv.prefsOf(ctx)
                    val shotIso = readIso(exifSource)
                    val shotShutter = readShutterNs(exifSource)
                    val shotBright = (res.analysis.avgBrightness / 255.0).toFloat()
                    // Превью момента кадра (пишет ImageCapturer), фолбэк — текущее.
                    var shotPreviewL = ThmcTrackState.lastPreviewL
                    try {
                        val capPl = prefs.getFloat("thmc_cap_pl", -1f)
                        val capTs = prefs.getLong("thmc_cap_ts", 0L)
                        if (capPl in 0.05f..0.95f && System.currentTimeMillis() - capTs < 15_000L) {
                            shotPreviewL = capPl
                        }
                    } catch (_: Throwable) {}
                    ThmcManualEv.updateFromShot(
                        prefs,
                        shotShutter,
                        shotIso,
                        shotBright,
                        shotPreviewL,
                        params.frontCamera,
                    )
                    // Серво-привод EV: замыкаем по ОШИБКЕ ВЫХОДА, а не только по ISO.
                    // HAL упёрся в высокое ISO при среднем свете → следующий кадр давим
                    // глубже. Но если прошлый кадр с минусовым bias вышел ТЕМНЕЕ цели —
                    // это мы перестарались (смена сцены с накрученным extra): сброс.
                    // Ночь не трогаем, свои длинные ручные кадры — не в счёт.
                    // Цель совпадает с ThmcManualEv.BASE_TARGET (0.47): при 0.38
                    // серво считал нормальный день «пересветом» и долбил extra вниз.
                    if (shotIso > 0 && shotPreviewL in 0.30f..0.70f) {
                        val extra = prefs.getInt("thmc_ev_extra", 0)
                        val lastBias = try { prefs.getInt("thmc_last_bias", 0) } catch (_: Throwable) { 0 }
                        val err = shotBright - 0.47f
                        val next = when {
                            lastBias < 0 && err < -0.08f -> 0
                            shotShutter in 1L..39_999_999L && shotIso > 2000 -> (extra + 1).coerceAtMost(4)
                            shotIso < 500 -> 0
                            shotIso < 1000 -> (extra - 1).coerceAtLeast(0)
                            else -> extra
                        }
                        if (next != extra) {
                            prefs.edit().putInt("thmc_ev_extra", next).apply()
                            try { android.util.Log.i("ThmcExp", "servo iso=$shotIso err=${"%.2f".format(err)} bias=$lastBias extra $extra->$next") } catch (_: Throwable) {}
                        }
                    }
                }
            } catch (_: Throwable) {}
            var out = res.bitmap
            val s = strength01.coerceIn(0f, 1f)
            if (s < 0.999f) {
                val bl = blendWithStock(bmp, out, s)
                if (bl != null && bl !== out) ThmcBitmaps.release(out)
                out = bl ?: out
            }
            val baos = ByteArrayOutputStream(exifSource.size)
            // Dither ±1 LSB строго перед энкодом: последнее 8-битное округление
            // (после лифта/шумодава) иначе рисует полосы в тенях и в небе.
            try { com.example.thundercam20.imageprocessing.NativeProcessor.dither(out) } catch (_: Throwable) {}
            out.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            if (out !== bmp) ThmcBitmaps.release(out)
            ThmcBitmaps.release(bmp)
            val p0bytes = baos.toByteArray()
            val exif = Exif.createFromInputStream(ByteArrayInputStream(exifSource))
            try {
                val scene = res.sceneName
                if (scene.isNotEmpty()) {
                    exif.exifInterface.setAttribute(
                        androidx.exifinterface.media.ExifInterface.TAG_IMAGE_DESCRIPTION,
                        "ThunderCam P0 $scene ${res.recipe} iso${readIso(exifSource)}"
                    )
                }
            } catch (_: Throwable) {}
            val finalBaos = ByteArrayOutputStream(p0bytes.size + 100 * 1024)
            exif.exifInterface.saveAttributes(ByteArrayInputStream(p0bytes), finalBaos)
            val finalBytes = finalBaos.toByteArray()
            var descCheck = "?"
            try {
                val checkEi = Exif.createFromInputStream(ByteArrayInputStream(finalBytes)).exifInterface
                descCheck = checkEi.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_IMAGE_DESCRIPTION) ?: "null"
            } catch (_: Throwable) { descCheck = "read-fail" }
            Log.i(TAG, "p0 ok ${(System.nanoTime() - t0) / 1_000_000}ms ${w}x${h} -> ${finalBytes.size / 1024}KB zoom=$zoom desc=[$descCheck]")
            finalBytes
        } catch (t: Throwable) {
            Log.w(TAG, "p0 fallback to stock", t)
            null
        }
    }

    /**
     * Рецепт в EXIF ImageDescription (серийные пути: encodeWithExif пересаживает
     * базовый HAL-EXIF без описания — одиночный путь штампует сам). Нужен, чтобы
     * «Подробности» галереи показывали работу P0 без логов. Пустой lastRecipe =
     * нечего писать; любая проблема → исходные байты.
     */
    fun stampRecipe(jpeg: ByteArray, iso: Int): ByteArray {
        val recipe = try { ThmcTrackState.lastRecipe } catch (_: Throwable) { "" }
        if (recipe.isEmpty()) return jpeg
        return try {
            val exif = Exif.createFromInputStream(ByteArrayInputStream(jpeg))
            exif.exifInterface.setAttribute(
                androidx.exifinterface.media.ExifInterface.TAG_IMAGE_DESCRIPTION,
                "ThunderCam P0 $recipe iso$iso"
            )
            val out = ByteArrayOutputStream(jpeg.size + 64 * 1024)
            exif.exifInterface.saveAttributes(ByteArrayInputStream(jpeg), out)
            out.toByteArray()
        } catch (_: Throwable) { jpeg }
    }

    /**
     * Lerp P0→сток. Делегирует нативу (zero-copy) с чанкованным Kotlin-фолбэком —
     * пик ~2*w*256 int вместо 2*w*h (12Мп: ~2МБ вместо 96МБ).
     * Возвращает мутабельный результат или null при проблеме/OOM.
     */
    fun blendWithStock(stock: Bitmap, p0: Bitmap, s: Float): Bitmap? {
        return try {
            com.example.thundercam20.imageprocessing.NativeProcessor.blend(stock, p0, s)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "blend oom, stock kept")
            null
        } catch (t: Throwable) {
            Log.w(TAG, "blend fallback", t)
            null
        }
    }

    /**
     * Серийный путь «в лучшем виде»: P0 на РАБОЧЕМ разрешении (2048), без JPEG туда-обратно.
     * Входной bmpIn освобождается внутри. Возвращает Bitmap рабочего размера
     * (вызыватель сам делает upscaleTo + encodeWithExif). Зум-кроп НЕ делаем здесь —
     * его делает вызыватель/applyFromBitmap на финале, чтобы не давить шумодав дважды.
     */
    fun processWorkBitmap(
        bmpIn: Bitmap,
        params: Params,
        iso: Int,
        strength01: Float = 1f,
    ): Bitmap {
        // Fast path P0-lite: 1-pass LUT вместо 5-7 Bitmap + bilateral-секунды.
        // Любая проблема -> legacy ImageProcessor ниже.
        // Сила применяется масштабом стадий (было: игнор s — ночь 0.8 жарила как 1.0).
        // Ночь (denoise=max) идет legacy: ей нужен сильный luma-шумодав DenoiseV2,
        // в лайте только хрома — иначе зерно. День (auto) — лайт, быстро.
        try {
            val s = strength01.coerceIn(0f, 1f)
            val maxDenoise = denoiseMode(params.denoise) == 2
            if (!maxDenoise) {
                val lite = com.example.thundercam20.imageprocessing.P0Lite.process(
                    bmpIn,
                    tone01 = (params.tone / 100f) * s,
                    contrast01 = (params.contrast / 100f) * s,
                    sat01 = (params.sat / 100f) * s,
                    warmth01 = params.warmth / 100f,
                    denoise01 = params.denoise,
                    iso = iso,
                )
                if (lite != null) {
                    try { app.grapheneos.camera.thmc.ThmcTrackState.lastRecipe = "LITE iso$iso" } catch (_: Throwable) {}
                    return lite.bitmap
                }
            }
        } catch (_: Throwable) {}
        var bmp = if (bmpIn.config == Bitmap.Config.ARGB_8888) bmpIn else {
            val c = bmpIn.copy(Bitmap.Config.ARGB_8888, false) ?: return bmpIn
            ThmcBitmaps.release(bmpIn)
            c
        }
        val res = com.example.thundercam20.imageprocessing.ImageProcessor()
            .process(bmp, params.toRestoreConfig(), null, null, params.autoScene, iso)
        var out = res.bitmap
        val s = strength01.coerceIn(0f, 1f)
        if (s < 0.999f) {
            val bl = blendWithStock(bmp, out, s)
            if (bl != null && bl !== out) ThmcBitmaps.release(out)
            out = bl ?: out
        }
        if (out !== bmp) ThmcBitmaps.release(bmp)
        return out
    }
}
