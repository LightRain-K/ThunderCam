package app.grapheneos.camera.thmc

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import app.grapheneos.camera.capturer.DEFAULT_MEDIA_STORE_CAPTURE_PATH
import java.io.ByteArrayOutputStream

/**
 * Галерейное «Улучшить ThunderCam»: общее улучшение любого JPEG (не апскейл —
 * он ждёт фазу зума). Enhance-рецепт поверх: умеренный P0 + CLAHE + шарп + макс-шумодав.
 * Оригинал не трогаем — сохраняем копией. EXIF пересаживаем 1:1.
 */
object ThmcEnhance {
    private const val TAG = "ThmcEnhance"

    /**
     * Галерейный enhance = ТОЛЬКО шумодав (пользователь: P0-цвет второй раз пережигает).
     * Схема: denoiseMax считается один раз → слайдер живьём блендит (orig↔denoised).
     * Работает и на уже обработанных кадрах, ничего не пережирает по построению.
     */
    fun denoiseMax(orig: Bitmap): Bitmap? {
        return try {
            val nr = com.example.thundercam20.imageprocessing.NoiseReducer()
            if (com.example.thundercam20.utils.OpencvManager.available) {
                nr.bilateralFilter(orig, diameter = 9, sigmaColor = 50f, sigmaSpace = 50f)
            } else {
                nr.softKotlin(orig, radius = 3, blend = 0.55f)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "denoise failed", t)
            null
        }
    }

    /** ИИ-свет (Zero-DCE) на битмапе. null при проблеме — вызыватель идёт без ИИ. */
    fun aiLight(context: Context, bmp: Bitmap): Bitmap? {
        return try { ThmcZeroDce.enhance(context, bmp) } catch (_: Throwable) { null }
    }

    /**
     * Полная цепочка с раздельными силами: ИИ-свет (aiS, null = без ИИ) → шумодав (denS).
     * Точный пересчёт для сохранения (превью ниже — приближённый, но мгновенный).
     */
    fun enhanceFullChain(context: Context, jpeg: ByteArray, quality: Int, aiS: Float?, denS: Float): ByteArray? {
        return try {
            var bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
            if (bmp.config != Bitmap.Config.ARGB_8888) {
                val c = bmp.copy(Bitmap.Config.ARGB_8888, false) ?: return null
                try { bmp.recycle() } catch (_: Exception) {}
                bmp = c
            }
            // ИИ-свет с его силой
            var base = bmp
            if (aiS != null) {
                val ai = aiLight(context, bmp)
                if (ai != null) {
                    val c = ai.copy(Bitmap.Config.ARGB_8888, false)
                    if (c != null) {
                        // Бленд возвращает результат (immutable-вход не мутируется!)
                        val bl = ThmcP0.blendWithStock(bmp, c, aiS.coerceIn(0f, 1f))
                        if (bl !== c) try { c.recycle() } catch (_: Exception) {}
                        base = bl ?: c
                    } else base = ai
                    if (ai !== base) try { ai.recycle() } catch (_: Exception) {}
                }
            }
            val d = denoiseMax(base) ?: return null
            val out = if (denS < 0.999f) ThmcP0.blendWithStock(base, d, denS.coerceIn(0f, 1f)) ?: d else d
            val baos = ByteArrayOutputStream(jpeg.size)
            try { com.example.thundercam20.imageprocessing.NativeProcessor.dither(out) } catch (_: Throwable) {}
            out.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            if (base !== bmp) try { bmp.recycle() } catch (_: Exception) {}
            if (base !== out) try { base.recycle() } catch (_: Exception) {}
            if (d !== out) try { d.recycle() } catch (_: Exception) {}
            try { out.recycle() } catch (_: Exception) {}
            val clean = baos.toByteArray()
            val exif = androidxc.camera.core.impl.utils.Exif.createFromInputStream(
                java.io.ByteArrayInputStream(jpeg)
            )
            val finalBaos = ByteArrayOutputStream(clean.size + 100 * 1024)
            exif.exifInterface.saveAttributes(java.io.ByteArrayInputStream(clean), finalBaos)
            finalBaos.toByteArray()
        } catch (t: Throwable) {
            Log.w(TAG, "enhanceFullChain failed", t)
            null
        }
    }

    /** Бэкенды Pro-апскейла: настоящий x4plus в облаке vs Pro-модель на телефоне. */
    const val BACKEND_CLOUD = "cloud"
    const val BACKEND_DEVICE = "device"

    data class ProResult(val bytes: ByteArray, val backend: String, val ms: Long)

    /** Причина последнего отказа Pro (диагностика/тосты). */
    @Volatile var lastProFail = ""
        private set

    private fun cloudUrlOf(context: Context): String {
        return try {
            context.applicationContext
                .getSharedPreferences("commons", Context.MODE_PRIVATE)
                .getString(ThmcSettings.Keys.CLOUD_URL, "")?.trim() ?: ""
        } catch (_: Throwable) { "" }
    }

    /** Какой бэкенд возьмёт Pro (для подписи в UI до запуска). Чистый. */
    fun proBackend(context: Context): String =
        if (cloudUrlOf(context).isNotBlank()) BACKEND_CLOUD else BACKEND_DEVICE

    /**
     * Цель он-девайс Pro: ×2, но не больше 4096 по стороне. null = фото уже большое
     * (масштаб <1.2×): на телефоне это даст крохи за минуты — честный отказ,
     * таким только облако. Чистая, тестируется.
     */
    fun proTarget(w: Int, h: Int): Pair<Int, Int>? {
        if (w < 16 || h < 16) return null
        val scale = minOf(2.0, 4096.0 / maxOf(w, h))
        if (scale < 1.2) return null
        return ((w * scale).toInt().coerceAtLeast(w)) to ((h * scale).toInt().coerceAtLeast(h))
    }

    /**
     * Мощный ИИ-апскейл (отдельная опция галереи).
     * В первую очередь — настоящий Real-ESRGAN x4plus в облаке (если настроен адрес):
     * полное разрешение, не колхоз. Иначе — Pro-модель на телефоне (до 4096).
     * Оригинал не трогаем, вызыватель сохраняет копией _SR. null = не получилось.
     * onProgress: 0..100 (облако — честные, телефон — по тайлам).
     */
    fun upscalePro(
        context: Context,
        jpeg: ByteArray,
        quality: Int,
        onProgress: (Int) -> Unit = {},
    ): ProResult? {
        lastProFail = ""
        val url = cloudUrlOf(context)
        if (url.isNotBlank()) {
            return try {
                val t0 = System.nanoTime()
                try { onProgress(0) } catch (_: Throwable) {}
                val res = ThmcCloud.enhance(context, url, jpeg) { p ->
                    try { onProgress(p.coerceIn(0, 100)) } catch (_: Throwable) {}
                } ?: run {
                    lastProFail = "cloud"
                    return null
                }
                try { onProgress(100) } catch (_: Throwable) {}
                Log.i(TAG, "pro cloud ok ${res.size / 1024}KB ${(System.nanoTime() - t0) / 1_000_000}ms")
                ProResult(res, BACKEND_CLOUD, (System.nanoTime() - t0) / 1_000_000)
            } catch (t: Throwable) {
                Log.w(TAG, "pro cloud failed", t)
                null
            }
        }
        return try {
            var bmp = try {
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            } catch (e: OutOfMemoryError) {
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                try { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) } catch (_: Throwable) { null }
            } ?: return null
            if (bmp.config != Bitmap.Config.ARGB_8888) {
                val c = try { bmp.copy(Bitmap.Config.ARGB_8888, false) } catch (_: OutOfMemoryError) { null }
                    ?: return null
                try { bmp.recycle() } catch (_: Exception) {}
                bmp = c
            }
            val sr = com.example.thundercam20.imageprocessing.SuperResZoom
            val prevPowerful = sr.powerful
            try {
                sr.powerful = true
                if (!sr.available(context)) {
                    Log.w(TAG, "pro model unavailable")
                    lastProFail = "model"
                    return null
                }
                val maxSide = maxOf(bmp.width, bmp.height)
                if (maxSide < 16) {
                    lastProFail = "size"
                    return null
                }
                val (tw, th) = proTarget(bmp.width, bmp.height) ?: run {
                    // Большое фото: ×1.03 за 24с — не услуга. Таким только облако.
                    Log.i(TAG, "pro device refused: too big ${bmp.width}x${bmp.height}")
                    lastProFail = "big"
                    return null
                }
                val t0 = System.nanoTime()
                try { onProgress(5) } catch (_: Throwable) {}
                val up = try {
                    sr.upscaleCrop(context, bmp, tw, th, onProgress = { done, total ->
                        try { onProgress((5 + 85 * done / total.coerceAtLeast(1)).coerceIn(0, 100)) } catch (_: Throwable) {}
                    })
                } catch (e: OutOfMemoryError) {
                    Log.w(TAG, "pro oom")
                    lastProFail = "oom"
                    null
                } ?: run {
                    if (lastProFail.isEmpty()) lastProFail = "error"
                    return null
                }
                Log.i(TAG, "pro device ok ${(System.nanoTime() - t0) / 1_000_000}ms ${bmp.width}x${bmp.height}->${up.width}x${up.height}")
                try { onProgress(92) } catch (_: Throwable) {}
                val baos = ByteArrayOutputStream(jpeg.size)
                up.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                try { up.recycle() } catch (_: Exception) {}
                val clean = baos.toByteArray()
                val exif = androidxc.camera.core.impl.utils.Exif.createFromInputStream(
                    java.io.ByteArrayInputStream(jpeg)
                )
                val finalBaos = ByteArrayOutputStream(clean.size + 100 * 1024)
                exif.exifInterface.saveAttributes(java.io.ByteArrayInputStream(clean), finalBaos)
                try { onProgress(100) } catch (_: Throwable) {}
                ProResult(finalBaos.toByteArray(), BACKEND_DEVICE, (System.nanoTime() - t0) / 1_000_000)
            } finally {
                try { sr.powerful = prevPowerful } catch (_: Throwable) {}
                try { bmp.recycle() } catch (_: Exception) {}
            }
        } catch (t: Throwable) {
            Log.w(TAG, "upscalePro failed", t)
            null
        }
    }
    /**
     * Перезапись файла по uri (MediaStore). false = нет доступа → вызыватель делает копию рядом.
     * EXIF уже внутри байтов.
     */
    fun overwrite(context: Context, uri: Uri, bytes: ByteArray): Boolean {
        return try {
            context.contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                ?: return false
            true
        } catch (_: Throwable) { false }
    }

    /** Чтение байтов по uri. null при проблеме. */
    fun readBytes(context: Context, uri: Uri): ByteArray? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (_: Throwable) { null }
    }

    /** Декод превью ~1024 для диалога. */
    fun decodePreview(bytes: ByteArray): Bitmap? {
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var s = 1
            while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= 1024) s *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = s
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (_: Throwable) { null }
    }

    /**
     * Сохранение копии в DCIM/Camera с суффиксом (по умолч. _ENH, Pro-апскейл — _SR).
     * Возвращает (uri, dateStr) для фокуса галереи. EXIF уже внутри байтов.
     */
    fun saveCopy(context: Context, bytes: ByteArray, suffix: String = "_ENH"): Pair<Uri, String>? {
        return try {
            val cr = context.contentResolver
            val dateStr = java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US)
                .format(java.util.Date())
            val fileName = IMAGE_NAME_PREFIX_THMC + dateStr + suffix + ".jpg"
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, DEFAULT_MEDIA_STORE_CAPTURE_PATH)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
            try {
                cr.openAssetFileDescriptor(uri, "w")!!.use {
                    val fd = it.fileDescriptor
                    var off = 0
                    while (off != bytes.size) off += android.system.Os.write(fd, bytes, off, bytes.size - off)
                    android.system.Os.fsync(fd)
                }
                app.grapheneos.camera.util.removePendingFlagFromUri(cr, uri)
            } catch (e: Exception) {
                try { cr.delete(uri, null, null) } catch (_: Throwable) {}
                throw e
            }
            uri to dateStr
        } catch (t: Throwable) {
            Log.w(TAG, "saveCopy failed", t)
            null
        }
    }

    private const val IMAGE_NAME_PREFIX_THMC = "ThunderCam_"
}
