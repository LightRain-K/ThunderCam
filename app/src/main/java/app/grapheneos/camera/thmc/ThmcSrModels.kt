package app.grapheneos.camera.thmc

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * ThunderCam каталог SR-моделей: вшитые + скачиваемые.
 *
 * Честная лестница (только проверенное):
 * - fast: вшитая Real-ESRGAN-General-x4v3 w8a8 1.3МБ — быстро везде, качество базовое.
 * - x4plus: настоящий Real-ESRGAN-x4plus float32 67МБ (Qualcomm AI Hub, BSD-3,
 *   вход 128×128 — как наш конвейер) — макс. качество, только сильным (зверь),
 *   считает минутами. Только HTTPS, валидация тензоров после скачивания.
 * - custom: своя ссылка на .tflite (продвинутые).
 *
 * Вшитая Pro (fp16/f32) остаётся под старым тумблером thmcSrPowerful и работает,
 * когда выбрано auto/fast/pro. Скачанная модель при выборе бьёт вшитые.
 */
object ThmcSrModels {
    private const val TAG = "ThmcSrModels"

    const val SEL_AUTO = "auto"
    const val SEL_FAST = "fast"
    const val SEL_PRO = "pro"
    const val SEL_X4PLUS = "x4plus"
    const val SEL_X4PLUS_W8A8 = "x4plus_w8a8"
    const val SEL_CUSTOM = "custom"

    const val MAX_DOWNLOAD_BYTES = 150L * 1024 * 1024

    /** ID со скачиваемым файлом (vs вшитые). Единая правда для резолва. */
    fun isDownloadedId(sel: String): Boolean =
        sel == SEL_X4PLUS || sel == SEL_X4PLUS_W8A8 || sel == SEL_CUSTOM

    data class CatalogEntry(
        val id: String,
        val titleRu: String,
        val titleEn: String,
        val descRu: String,
        val descEn: String,
        /** Прямая ссылка на .tflite. Пусто = вшитая, качать нечего. */
        val url: String,
        val sizeMb: Int,
    )

    fun catalog(): List<CatalogEntry> = listOf(
        CatalogEntry(
            SEL_FAST, "Вшитая быстрая", "Bundled fast",
            "1.3 МБ, везде быстро. Базовое качество.", "1.3 MB, fast everywhere. Basic quality.",
            "", 0
        ),
        CatalogEntry(
            SEL_PRO, "Вшитая Pro", "Bundled Pro",
            "8–16 МБ, заметно резче быстрой. Середина и выше.", "8–16 MB, sharper than fast. Mid and up.",
            "", 0
        ),
        CatalogEntry(
            SEL_X4PLUS, "x4plus с Qualcomm AI Hub", "x4plus from Qualcomm AI Hub",
            "Настоящий Real-ESRGAN-x4plus float32, 67 МБ (BSD-3). Макс. качество, только зверь, считает минутами, качать по Wi-Fi.",
            "Genuine Real-ESRGAN-x4plus float32, 67 MB (BSD-3). Best quality, beasts only, minutes per photo, use Wi-Fi.",
            "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/real_esrgan_x4plus/releases/v0.65.0/real_esrgan_x4plus-tflite-float.zip",
            67
        ),
        CatalogEntry(
            SEL_X4PLUS_W8A8, "x4plus w8a8 (легче)", "x4plus w8a8 (lighter)",
            "Тот же x4plus, квантованный w8a8, 17 МБ (тот же релиз). Резче вшитых, быстрее float, потянет середина.",
            "Same x4plus, w8a8 quantized, 17 MB (same release). Sharper than bundled, faster than float, mid and up.",
            "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/real_esrgan_x4plus/releases/v0.65.0/real_esrgan_x4plus-tflite-w8a8.zip",
            17
        ),
        CatalogEntry(
            SEL_CUSTOM, "Своя ссылка", "Custom URL",
            "Любой .tflite по HTTPS (4D вход/выход, апскейл ×2–×4). Проверяем тензоры после скачивания.",
            "Any .tflite over HTTPS (4D in/out, ×2–×4 upscale). Tensor shapes validated after download.",
            "", 0
        ),
    )

    fun modelsDir(context: Context): File {
        val d = File(context.applicationContext.filesDir, "sr_models")
        try { d.mkdirs() } catch (_: Throwable) {}
        return d
    }

    fun modelFile(context: Context, id: String): File =
        File(modelsDir(context), "$id.tflite")

    fun isDownloaded(context: Context, id: String): Boolean {
        return try {
            val f = modelFile(context, id)
            f.exists() && f.length() > 100 * 1024
        } catch (_: Throwable) { false }
    }

    /**
     * Скачивание с прогрессом. Кидает Exception с понятным текстом.
     * Только HTTPS, кап 150МБ. .zip (релиз Qualcomm) распаковываем — берём первый
     * .tflite внутри. Докачки нет (простота), tmp→validate→rename.
     * onProgress(pct, doneBytes, totalBytes): pct=-1 когда сервер скрыл длину —
     * тогда показываем мегабайты, а не проценты.
     */
    fun download(
        context: Context,
        id: String,
        url: String,
        onProgress: (Int, Long, Long) -> Unit,
    ) {
        val clean = url.trim()
        require(clean.startsWith("https://")) { "need https" }
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(clean).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 60000
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "ThunderCam")
            }
            if (conn.responseCode != 200) throw Exception("http ${conn.responseCode}")
            val total = conn.contentLengthLong
            if (total > MAX_DOWNLOAD_BYTES) throw Exception("too big")
            if (total > 0 && total < 100 * 1024) throw Exception("too small")
            val raw = File(modelsDir(context), "$id.bin")
            var done = 0L
            var lastPct = -2
            var lastMb10 = -1L
            fun emit() {
                if (total > 0) {
                    val pct = ((done.toDouble() / total * 0.9) * 100).toInt().coerceIn(0, 100)
                    if (pct != lastPct) {
                        lastPct = pct
                        try { onProgress(pct, done, total) } catch (_: Throwable) {}
                    }
                } else {
                    // Длину скрыли — тикаем мегабайтами, чтобы не было тишины.
                    val mb10 = done / (1024 * 1024 / 10)
                    if (mb10 != lastMb10) {
                        lastMb10 = mb10
                        try { onProgress(-1, done, total) } catch (_: Throwable) {}
                    }
                }
            }
            conn.inputStream.use { inp ->
                FileOutputStream(raw).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val r = inp.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        done += r
                        if (done > MAX_DOWNLOAD_BYTES) throw Exception("too big")
                        emit()
                    }
                }
            }
            if (done < 100 * 1024) throw Exception("too small")
            val tmp = File(modelsDir(context), "$id.tmp")
            try { tmp.delete() } catch (_: Throwable) {}
            if (clean.endsWith(".zip", ignoreCase = true)) {
                try { onProgress(92, done, total) } catch (_: Throwable) {}
                extractTfliteFromZip(raw, tmp)
                try { raw.delete() } catch (_: Throwable) {}
            } else {
                if (!raw.renameTo(tmp)) throw Exception("save failed")
            }
            try { onProgress(96, done, total) } catch (_: Throwable) {}
            val shape = validateModel(tmp)
                ?: throw Exception("bad model (need 4D in/out, ×2–×4)")
            Log.i(TAG, "downloaded $id ${tmp.length() / 1024}KB $shape")
            val dst = modelFile(context, id)
            try { dst.delete() } catch (_: Throwable) {}
            if (!tmp.renameTo(dst)) throw Exception("save failed")
            try { onProgress(100, done, total) } catch (_: Throwable) {}
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    /** Первый .tflite из zip (релиз Qualcomm). Кап по распакованному. */
    private fun extractTfliteFromZip(zipFile: File, outTmp: File) {
        var found = false
        java.util.zip.ZipInputStream(zipFile.inputStream().buffered()).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                try {
                    if (!e.isDirectory && e.name.endsWith(".tflite", ignoreCase = true)) {
                        found = true
                        FileOutputStream(outTmp).use { out ->
                            val buf = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val r = zin.read(buf)
                                if (r < 0) break
                                out.write(buf, 0, r)
                                total += r
                                if (total > MAX_DOWNLOAD_BYTES) throw Exception("too big")
                            }
                            if (total < 100 * 1024) throw Exception("too small")
                        }
                        break
                    }
                } finally {
                    try { zin.closeEntry() } catch (_: Throwable) {}
                }
                e = zin.nextEntry
            }
        }
        if (!found) throw Exception("no tflite in zip")
    }

    /** Проверка файла: открывается интерпретером, 4D вход/выход, масштаб ×2–×4. */
    fun validateModel(file: File): String? {
        return try {
            if (!file.exists() || file.length() < 100 * 1024) return null
            val ch = file.inputStream().channel
            val buf = ch.map(
                java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, file.length()
            ).order(java.nio.ByteOrder.nativeOrder())
            try { ch.close() } catch (_: Throwable) {}
            val it = org.tensorflow.lite.Interpreter(buf)
            try {
                val inS = it.getInputTensor(0).shape()
                val outS = it.getOutputTensor(0).shape()
                if (inS.size != 4 || outS.size != 4) return null
                val nchw = outS.size == 4 && outS[1] == 3
                val os = if (nchw) outS[2] else outS[1]
                val scale = os.toFloat() / inS[1].toFloat()
                if (scale < 1.9f || scale > 4.1f) return null
                "${inS.joinToString("x")}→${outS.joinToString("x")}"
            } finally {
                try { it.close() } catch (_: Throwable) {}
            }
        } catch (t: Throwable) {
            Log.w(TAG, "validate failed", t)
            null
        }
    }

    /**
     * Что реально использовать: выбранный скачанный файл или вшитая сборка.
     * Чистая логика (файл проверяется вызывателем): возвращает "file" | "pro" | "fast".
     */
    fun resolveSource(selectedId: String, downloadedExists: Boolean, powerful: Boolean): String {
        if (isDownloadedId(selectedId) && downloadedExists) return "file"
        if (selectedId == SEL_PRO) return "pro"
        if (selectedId == SEL_FAST) return "fast"
        return if (powerful) "pro" else "fast" // auto: старый тумблер
    }
}
