package com.example.thundercam20.imageprocessing

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ИИ-апскейл зума: Real-ESRGAN-General-x4v3 (Qualcomm AI Hub, BSD-3),
 * assets/sr_resrgan_w8a8.tflite, 1.3МБ, вход/выход UINT8 128→512.
 * Проверена стендом: резкость как у ESRGAN, в 6 раз быстрее (0.56с vs 3.6с десктоп).
 * Схема: кроп → ТАЙЛЫ 128×128 → SR (×4 → 512) → ститч → бленд → сглаживание → ресайз → шарп.
 * Один тайл на весь кроп размазывал детали (128 на 4000px) — теперь сетка до 3×3.
 * Нет модели / ошибка / мёртвый выход — тихо отдаём null,
 * вызыватель идёт в Lanczos/билинейный. Только для фото-зума, не для превью.
 */
object SuperResZoom {
    private const val ASSET_FAST = "sr_resrgan_w8a8.tflite"
    private const val ASSET_PRO_FP16 = "sr_pro_fp16.tflite"
    private const val ASSET_PRO_F32 = "sr_pro_float32.tflite"
    @Volatile private var interpreter: Interpreter? = null
    @Volatile private var inSize: Int = 128
    @Volatile private var outSize: Int = 0
    @Volatile private var useUint8 = false
    @Volatile private var outScale = 1f
    @Volatile private var outZp = 0
    @Volatile private var broken = false
    // Pro (мощная юзера): выход NCHW [1,3,os,os], вход float [0,1]
    @Volatile private var outNchw = false
    private var loadedKey = ""

    private val initLock = Any()
    private val inferLock = Any()

    @Volatile private var backend = "?"

    fun available(context: Context): Boolean {
        val (src, file) = selectionOf(context)
        val wantKey = "$src|${file?.absolutePath ?: ""}|pwr=$powerful"
        if (interpreter != null && wantKey == loadedKey && !broken) return true
        synchronized(initLock) {
            if (interpreter != null && wantKey == loadedKey && !broken) return true
            // Смена модели: закрываем старую, сбрасываем флаг смерти
            try { interpreter?.close() } catch (_: Throwable) {}
            interpreter = null
            broken = false
            loadedKey = ""
            return try {
                val ok = if (file != null && loadFile(context, file)) true
                else if (src == "fast") loadFast(context)
                else if (src == "pro") loadPowerful(context)
                else if (powerful) loadPowerful(context)
                else loadFast(context)
                // Скачанный битый (loadFile false) — уже упали на вшитые выше.
                if (ok) loadedKey = wantKey
                ok
            } catch (t: Throwable) {
                try { android.util.Log.e("SuperResZoom", "init failed: ${t.javaClass.simpleName}: ${t.message}") } catch (_: Throwable) {}
                broken = true
                false
            }
        }
    }

    /**
     * Что грузить: скачанный файл (выбор x4plus/custom + файл цел) или вшитая сборка.
     * Ключ prefs дублирует ThmcSettings.Keys.SR_MODEL строкой (без зависимости наружу).
     */
    private fun selectionOf(context: Context): Pair<String, java.io.File?> {
        return try {
            val sel = context.applicationContext
                .getSharedPreferences("commons", Context.MODE_PRIVATE)
                .getString("thmc_sr_model", "auto")?.trim()?.lowercase() ?: "auto"
            val f = if (app.grapheneos.camera.thmc.ThmcSrModels.isDownloadedId(sel)) {
                val file = java.io.File(context.applicationContext.filesDir, "sr_models/$sel.tflite")
                if (file.exists() && file.length() > 100 * 1024) file else null
            } else null
            val src = if (f != null) "file"
            else if (sel == "pro") "pro"
            else if (sel == "fast") "fast"
            else if (powerful) "pro"
            else "fast"
            src to f
        } catch (_: Throwable) {
            (if (powerful) "pro" else "fast") to null
        }
    }

    private fun loadAssetBytes(context: Context, asset: String): ByteBuffer {
        val bytes = context.assets.open(asset).use { it.readBytes() }
        val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        buf.put(bytes); buf.rewind()
        return buf
    }

    /**
     * GPU-делегат только если класс есть И устройство в allowlist.
     * Иначе null (без NoClassDefFoundError-шума — часть сборок без gpu-классов).
     */
    private fun newGpuDelegate(): org.tensorflow.lite.gpu.GpuDelegate? {
        return try {
            if (org.tensorflow.lite.gpu.CompatibilityList().isDelegateSupportedOnThisDevice) {
                org.tensorflow.lite.gpu.GpuDelegate()
            } else null
        } catch (_: Throwable) { null }
    }

    // Бленд сеть/реал (0-100% сети). 70 = анти-галлюцинации по умолчанию.
    @Volatile var blendPercent = 70

    private fun cpuOptions(): Interpreter.Options {
        // int8 на GPU-делигате почти никогда не едет — падает на CPU.
        // Поэтому CPU-путь: NNAPI + XNNPACK + threads=cores-2.
        val cores = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
        return Interpreter.Options().apply {
            numThreads = cores
            try { setUseNNAPI(true) } catch (_: Throwable) {}
            try { setUseXNNPACK(true) } catch (_: Throwable) {}
        }
    }

    /** Прогрев интерпретера холостым прогоном (убирает первый долгий инференс из замера юзера). */
    private fun warmup(it: Interpreter) {
        try {
            val inS = it.getInputTensor(0).shape()
            val inT = it.getInputTensor(0).dataType()
            val outS = it.getOutputTensor(0).shape()
            val outT = it.getOutputTensor(0).dataType()
            val inBuf = if (inT == org.tensorflow.lite.DataType.UINT8) {
                java.nio.ByteBuffer.allocateDirect(inS.reduce { a, b -> a * b }).order(java.nio.ByteOrder.nativeOrder())
            } else {
                java.nio.ByteBuffer.allocateDirect(inS.reduce { a, b -> a * b } * 4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
            }
            val outBuf = if (outT == org.tensorflow.lite.DataType.UINT8) {
                java.nio.ByteBuffer.allocateDirect(outS.reduce { a, b -> a * b }).order(java.nio.ByteOrder.nativeOrder())
            } else {
                java.nio.ByteBuffer.allocateDirect(outS.reduce { a, b -> a * b } * 4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
            }
            try { it.run(inBuf, outBuf) } catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }

    private fun finishInit(it: Interpreter, asset: String, backendName: String): Boolean {
        try { android.util.Log.i("SuperResZoom", "inputs=${it.inputTensorCount} outputs=${it.outputTensorCount}") } catch (_: Throwable) {}
        val inT = it.getInputTensor(0)
        val outT = it.getOutputTensor(0)
        val inShape = inT.shape()
        val outShape = outT.shape()
        val inType = inT.dataType()
        val outType = outT.dataType()
        try { android.util.Log.i("SuperResZoom", "backend=$backendName $asset in=${inShape.joinToString("x")} $inType out=${outShape.joinToString("x")} $outType") } catch (_: Throwable) {}
        val ioOk = (inType == org.tensorflow.lite.DataType.FLOAT32 && outType == org.tensorflow.lite.DataType.FLOAT32) ||
                (inType == org.tensorflow.lite.DataType.UINT8 && outType == org.tensorflow.lite.DataType.UINT8)
        if (!ioOk) {
            try { it.close() } catch (_: Throwable) {}
            broken = true
            return false
        }
        inSize = inShape[1]
        // Pro: выход NCHW [1,3,os,os] — просторный размер в [2], а не [1]! (у w8a8 NHWC)
        outNchw = outShape.size == 4 && outShape[1] == 3
        outSize = if (outNchw) outShape[2] else outShape[1]
        useUint8 = inType == org.tensorflow.lite.DataType.UINT8
        if (useUint8) {
            try {
                val qp = outT.quantizationParams()
                outScale = qp.scale
                outZp = qp.zeroPoint
            } catch (_: Throwable) {
                outScale = 0.004972266f
                outZp = 25
            }
            // вход uint8 сырые байты (scale 1/255 zp 0) — без преобразований
        }
        backend = backendName
        interpreter = it
        return true
    }

    private fun loadFast(context: Context): Boolean {
        try { android.util.Log.i("SuperResZoom", "loading model...") } catch (_: Throwable) {}
        val buf = loadAssetBytes(context, ASSET_FAST)
        // GPU first (в разы быстрее на Mali/Adreno), CPU фолбэк
        var it: Interpreter? = null
        var gpu: org.tensorflow.lite.gpu.GpuDelegate? = null
        var be = "gpu"
        try {
            gpu = newGpuDelegate() ?: throw Exception("gpu N/A")
            it = Interpreter(buf, Interpreter.Options().addDelegate(gpu))
        } catch (t: Throwable) {
            try { gpu?.close() } catch (_: Throwable) {}
            try { android.util.Log.i("SuperResZoom", "gpu N/A (${t.javaClass.simpleName}), cpu") } catch (_: Throwable) {}
            it = null
        }
        if (it == null) {
            buf.rewind()
            it = Interpreter(buf, cpuOptions())
            be = "nnapi-xnnpack"
        }
        val ok = finishInit(it, ASSET_FAST, be)
        if (ok) warmup(it)
        return ok
    }

    private fun loadPowerful(context: Context): Boolean {
        // Pro fp16 работает ТОЛЬКО с GPU (на CPU падает в conv) → пробуем GPU+fp16,
        // иначе f32+CPU (медленно, но едет везде)
        try {
            val buf = loadAssetBytes(context, ASSET_PRO_FP16)
            val gpu = newGpuDelegate() ?: throw Exception("gpu N/A")
            val it = Interpreter(buf, Interpreter.Options().addDelegate(gpu))
            return finishInit(it, ASSET_PRO_FP16, "gpu-fp16")
        } catch (t: Throwable) {
            try { android.util.Log.i("SuperResZoom", "pro fp16/gpu N/A (${t.javaClass.simpleName}), f32 cpu") } catch (_: Throwable) {}
        }
        val buf2 = loadAssetBytes(context, ASSET_PRO_F32)
        val it2 = Interpreter(buf2, cpuOptions())
        val ok2 = finishInit(it2, ASSET_PRO_F32, "nnapi-xnnpack-f32")
        if (ok2) warmup(it2)
        return ok2
    }

    /** Скачанная модель из файлов (каталог/ссылка). Та же схема GPU→CPU. */
    private fun loadFile(context: Context, file: java.io.File): Boolean {
        try { android.util.Log.i("SuperResZoom", "loading file ${file.name} ${file.length() / 1024}KB...") } catch (_: Throwable) {}
        fun mapBuf(): ByteBuffer {
            val ch = file.inputStream().channel
            try {
                return ch.map(
                    java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, file.length()
                ).order(ByteOrder.nativeOrder())
            } finally {
                try { ch.close() } catch (_: Throwable) {}
            }
        }
        var it: Interpreter? = null
        var gpu: org.tensorflow.lite.gpu.GpuDelegate? = null
        var be = "gpu"
        try {
            gpu = newGpuDelegate() ?: throw Exception("gpu N/A")
            it = Interpreter(mapBuf(), Interpreter.Options().addDelegate(gpu))
        } catch (t: Throwable) {
            try { gpu?.close() } catch (_: Throwable) {}
            try { android.util.Log.i("SuperResZoom", "file gpu N/A (${t.javaClass.simpleName}), cpu") } catch (_: Throwable) {}
            it = null
        }
        if (it == null) {
            it = Interpreter(mapBuf(), cpuOptions())
            be = "nnapi-xnnpack"
        }
        val ok = finishInit(it, "file:${file.name}", be)
        if (ok) warmup(it)
        return ok
    }

    /**
     * ИИ-апскейл кропа до targetW×targetH. Возвращает null если SR недоступен.
     */
    @Volatile var lastUsedSr = false
        private set

    /** Почему последний upscaleCrop отдал null (диагностика закрытого теста). */
    @Volatile var lastFailReason = ""
        private set

    private fun failNull(why: String): Bitmap? {
        lastFailReason = why
        return null
    }

    /** Мастер-выключатель из настроек (по умолчанию вкл). */
    @Volatile var userEnabled = true

    /** Мощная модель юзера (RealESR-Mobile Pro) вместо быстрой w8a8. Смена файла на лету. */
    @Volatile var powerful = false

    /**
     * Сетка тайлов под кроп: исходный тайл ~700px на инференс, 1..3 по каждой оси,
     * всего не больше maxTiles (профиль тира режет слабые: 4 вместо 9).
     * Чистая, тестируется на JVM. Мелкий кроп сильного зума = (1,1).
     */
    fun tileGrid(cropW: Int, cropH: Int, maxTiles: Int = 9): Pair<Int, Int> {
        if (cropW < 16 || cropH < 16) return 1 to 1
        var tx = (cropW / 700 + 1).coerceIn(1, 3)
        var ty = (cropH / 700 + 1).coerceIn(1, 3)
        while (tx * ty > maxTiles.coerceAtLeast(1) && (tx > 1 || ty > 1)) {
            if (tx >= ty) tx-- else ty--
        }
        return tx to ty
    }

    /** Кап тайлов из профиля бенчмарка (commons-префсы, без CamConfig). */
    private fun maxTilesFor(context: Context): Int {
        return try {
            val tier = context.applicationContext
                .getSharedPreferences("commons", Context.MODE_PRIVATE)
                .getString("thmc_bench_tier", "auto") ?: "auto"
            when (tier) {
                // Кап ставит только замеренный бюджетник; без замера и на сильных — как было (9).
                "budget" -> 4
                else -> 9
            }
        } catch (_: Throwable) { 4 }
    }

    fun upscaleCrop(
        context: Context,
        crop: Bitmap,
        targetW: Int,
        targetH: Int,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
        // Кап тайлов для пути захвата (без прогресса — каждый тайл это секунды ожидания):
        // серии и зум в кадре идут с 4, Enhance Pro со своим UI — с профилем (до 9).
        maxTiles: Int? = null,
    ): Bitmap? {
        lastUsedSr = false
        if (!userEnabled) return failNull("off")
        if (!available(context)) {
            try { android.util.Log.i("SuperResZoom", "upscaleCrop: model unavailable") } catch (_: Throwable) {}
            return failNull("model")
        }
        val it = interpreter ?: return failNull("model")
        val t0 = System.currentTimeMillis()
        return try {
            val cw = crop.width; val ch = crop.height
            if (cw < 16 || ch < 16 || targetW < 16 || targetH < 16) return failNull("size")
            val tile = inSize
            val os = outSize
            if (os <= 0) return failNull("model")
            // Сетка: исходный тайл ~700px на инференс, кап — профилем бенчмарка.
            // Мелкий кроп сильного зума = 1 тайл (как раньше), средний/широкий = тайлы.
            val (tilesX, tilesY) = tileGrid(cw, ch, maxTiles ?: maxTilesFor(context))
            val big: Bitmap = if (tilesX * tilesY == 1) {
                val small = Bitmap.createScaledBitmap(crop, tile, tile, true)
                try {
                    try { onProgress?.invoke(0, 1) } catch (_: Throwable) {}
                    (inferTile(it, small, tile, os) ?: return failNull("tile")).also {
                        try { onProgress?.invoke(1, 1) } catch (_: Throwable) {}
                    }
                } finally {
                    try { small.recycle() } catch (_: Throwable) {}
                }
            } else {
                stitchTiles(it, crop, tilesX, tilesY, tile, os, onProgress) ?: return failNull("tile")
            }
            finishSr(crop, big, targetW, targetH, t0)
        } catch (e: OutOfMemoryError) {
            try { android.util.Log.w("SuperResZoom", "oom, fallback") } catch (_: Throwable) {}
            return failNull("oom")
        } catch (t: Throwable) {
            broken = true
            try { it.close() } catch (_: Throwable) {}
            interpreter = null
            return failNull("error")
        }
    }

    /** Тайлы кропа → инференс каждого → ститч в (tilesX*os × tilesY*os). null = фолбэк. */
    private fun stitchTiles(
        it: Interpreter, crop: Bitmap, tilesX: Int, tilesY: Int, tile: Int, os: Int,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): Bitmap? {
        val cw = crop.width; val ch = crop.height
        val tiles = ArrayList<Bitmap>(tilesX * tilesY)
        val total = tilesX * tilesY
        try {
            for (ty in 0 until tilesY) {
                val y0 = ty * ch / tilesY
                val y1 = (ty + 1) * ch / tilesY
                for (tx in 0 until tilesX) {
                    val x0 = tx * cw / tilesX
                    val x1 = (tx + 1) * cw / tilesX
                    val src = try {
                        Bitmap.createBitmap(crop, x0, y0, x1 - x0, y1 - y0)
                    } catch (_: Throwable) { return null }
                    val small = try {
                        Bitmap.createScaledBitmap(src, tile, tile, true)
                    } finally {
                        try { src.recycle() } catch (_: Throwable) {}
                    }
                    try {
                        tiles.add(inferTile(it, small, tile, os) ?: return null)
                        try { onProgress?.invoke(tiles.size, total) } catch (_: Throwable) {}
                    } finally {
                        try { small.recycle() } catch (_: Throwable) {}
                    }
                }
            }
            val big = try {
                Bitmap.createBitmap(tilesX * os, tilesY * os, Bitmap.Config.ARGB_8888)
            } catch (e: OutOfMemoryError) { return null }
            try {
                val row = IntArray(os * os)
                for (i in tiles.indices) {
                    val t = tiles[i]
                    t.getPixels(row, 0, os, 0, 0, os, os)
                    val tx = i % tilesX; val ty = i / tilesX
                    big.setPixels(row, 0, os, tx * os, ty * os, os, os)
                }
            } catch (_: Throwable) {
                try { big.recycle() } catch (_: Throwable) {}
                return null
            }
            return big
        } finally {
            for (t in tiles) try { t.recycle() } catch (_: Throwable) {}
        }
    }

    /**
     * Один инференс тайла 128→os. Возвращает новый Bitmap os×os или null (мёртвый выход).
     * small не владеет — вызыватель ресайклит сам. Лок только на interp.run.
     */
    private fun inferTile(it: Interpreter, small: Bitmap, tile: Int, os: Int): Bitmap? {
        val outPx = IntArray(os * os)
        var outSum = 0L
        if (outNchw) {
            // Pro (мощная): вход float [0,1], выход NCHW [1,3,os,os] [0,1] → транспонируем
            val input = ByteBuffer.allocateDirect(1 * tile * tile * 3 * 4).order(ByteOrder.nativeOrder())
            val px = IntArray(tile * tile)
            small.getPixels(px, 0, tile, 0, 0, tile, tile)
            for (p in px) {
                input.putFloat(((p shr 16) and 0xFF) / 255f)
                input.putFloat(((p shr 8) and 0xFF) / 255f)
                input.putFloat((p and 0xFF) / 255f)
            }
            input.rewind()
            val nchw = 3 * os * os
            val output = ByteBuffer.allocateDirect(4 * nchw).order(ByteOrder.nativeOrder())
            synchronized(inferLock) { it.run(input, output) }
            output.rewind()
            val arr = FloatArray(nchw)
            output.asFloatBuffer().get(arr)
            var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
            for (v in arr) {
                if (v < mn) mn = v
                if (v > mx) mx = v
            }
            try { android.util.Log.i("SuperResZoom", "pro range [$mn,$mx] os=$os") } catch (_: Throwable) {}
            if (mn.isNaN() || mx.isNaN() || mx <= 0f || mx - mn < 0.01f) {
                try { android.util.Log.w("SuperResZoom", "bad output (flat), fallback") } catch (_: Throwable) {}
                return failNull("flat")
            }
            val chn = os * os
            for (y in 0 until os) {
                for (x in 0 until os) {
                    val i = y * os + x
                    val r = (arr[i] * 255f).toInt().coerceIn(0, 255)
                    val g = (arr[chn + i] * 255f).toInt().coerceIn(0, 255)
                    val b = (arr[2 * chn + i] * 255f).toInt().coerceIn(0, 255)
                    outSum += r + g + b
                    outPx[i] = -0x1000000 or (r shl 16) or (g shl 8) or b
                }
            }
        } else if (useUint8) {
            // uint8 путь: сырые байты in, декант (v-zp)*scale*255 out
            val input = ByteBuffer.allocateDirect(tile * tile * 3).order(ByteOrder.nativeOrder())
            val px = IntArray(tile * tile)
            small.getPixels(px, 0, tile, 0, 0, tile, tile)
            for (p in px) {
                input.put((((p shr 16) and 0xFF).toByte()))
                input.put((((p shr 8) and 0xFF).toByte()))
                input.put(((p and 0xFF).toByte()))
            }
            input.rewind()
            val output = ByteBuffer.allocateDirect(os * os * 3).order(ByteOrder.nativeOrder())
            synchronized(inferLock) { it.run(input, output) }
            output.rewind()
            var mn = 255; var mx = 0
            val raw = ByteArray(os * os * 3)
            output.get(raw)
            for (i in outPx.indices) {
                val r = (((raw[i * 3].toInt() and 0xFF) - outZp) * outScale * 255f).toInt().coerceIn(0, 255)
                val g = (((raw[i * 3 + 1].toInt() and 0xFF) - outZp) * outScale * 255f).toInt().coerceIn(0, 255)
                val b = (((raw[i * 3 + 2].toInt() and 0xFF) - outZp) * outScale * 255f).toInt().coerceIn(0, 255)
                if (r < mn) mn = r
                if (r > mx) mx = r
                outSum += r + g + b
                outPx[i] = -0x1000000 or (r shl 16) or (g shl 8) or b
            }
            try { android.util.Log.i("SuperResZoom", "u8 range [$mn,$mx] os=$os") } catch (_: Throwable) {}
            if (mx <= 0 || mx - mn < 3) {
                try { android.util.Log.w("SuperResZoom", "bad output (flat), fallback") } catch (_: Throwable) {}
                return failNull("flat")
            }
        } else {
            // float NHWC, две попытки нормировки входа (иначе насыщение → цветной мусор):
            // 1) стандарт [0,1] (float-экспорты ESRGAN-семьи, включая x4plus),
            // 2) legacy [0,255]. Ворота flat+mean отсекают чужую нормировку сами.
            fun attempt(divIn: Float, strict01Out: Boolean): Long {
                val input = ByteBuffer.allocateDirect(1 * tile * tile * 3 * 4).order(ByteOrder.nativeOrder())
                val px = IntArray(tile * tile)
                small.getPixels(px, 0, tile, 0, 0, tile, tile)
                for (p in px) {
                    input.putFloat(((p shr 16) and 255) / divIn)
                    input.putFloat(((p shr 8) and 255) / divIn)
                    input.putFloat((p and 255) / divIn)
                }
                input.rewind()
                val output = ByteBuffer.allocateDirect(1 * os * os * 3 * 4).order(ByteOrder.nativeOrder())
                synchronized(inferLock) { it.run(input, output) }
                output.rewind()
                val n = os * os * 3
                val arr = FloatArray(n)
                output.asFloatBuffer().get(arr)
                var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
                for (v in arr) {
                    if (v < mn) mn = v
                    if (v > mx) mx = v
                }
                try { android.util.Log.i("SuperResZoom", "f32 div=$divIn range [$mn,$mx] os=$os") } catch (_: Throwable) {}
                if (mn.isNaN() || mx.isNaN()) return -1
                val map: (Float) -> Int
                if (strict01Out) {
                    if (mx <= 0f || mx - mn < 0.01f) return -1
                    map = { v -> (v * 255f).toInt().coerceIn(0, 255) }
                } else {
                    if (mx <= 0f || mx - mn < 3f) return -1
                    val mode = when {
                        mx <= 1.0f && mn >= 0f -> 0 // [0,1]
                        mn >= -1.01f && mx <= 1.01f -> 1 // tanh
                        else -> 2 // [0,255]
                    }
                    map = { v ->
                        when (mode) {
                            0 -> (v * 255f).toInt()
                            1 -> ((v + 1f) * 0.5f * 255f).toInt()
                            else -> v.toInt()
                        }.coerceIn(0, 255)
                    }
                }
                var sum = 0L
                for (i in outPx.indices) {
                    val r = map(arr[i * 3]); val g = map(arr[i * 3 + 1]); val b = map(arr[i * 3 + 2])
                    sum += r + g + b
                    outPx[i] = -0x1000000 or (r shl 16) or (g shl 8) or b
                }
                return sum
            }
            var sum = attempt(255f, true)
            if (sum < 0) {
                try { android.util.Log.i("SuperResZoom", "f32 retry legacy [0,255]") } catch (_: Throwable) {}
                sum = attempt(1f, false)
            }
            if (sum < 0) return failNull("flat")
            outSum = sum
        }
        // Ворота №2: средняя яркость выхода должна походить на вход (мёртвая сеть даёт черноту)
        val inMean = inputMean(small)
        val outMean = outSum.toFloat() / (os * os * 3)
        try { android.util.Log.i("SuperResZoom", "inMean=$inMean outMean=$outMean") } catch (_: Throwable) {}
        if (inMean > 8f && (outMean < inMean * 0.25f || outMean > inMean * 4f)) {
            try { android.util.Log.w("SuperResZoom", "dead net (mean mismatch), fallback") } catch (_: Throwable) {}
            return failNull("dead")
        }
        return try {
            val sr = Bitmap.createBitmap(os, os, Bitmap.Config.ARGB_8888)
            sr.setPixels(outPx, 0, os, 0, 0, os, os)
            sr
        } catch (e: OutOfMemoryError) { null }
    }

    /** Бленд с билинейной базой + сглаживание + ресайз до цели + шарп. big владеет — ресайклится внутри. */
    private fun finishSr(
        crop: Bitmap, big: Bitmap,
        targetW: Int, targetH: Int, t0: Long,
    ): Bitmap? {
        val sr = big
        try {
            // Анти-галлюцинации: сеть врёт на текстурах и мелком тексте.
            // Бленд сеть/билинейный (доля сети из настроек, дефолт 70): детали остаются, выдумок нет.
            try {
                val bp = blendPercent.coerceIn(0, 100) / 100f
                val bw = sr.width; val bh = sr.height
                val basePx = IntArray(bw * bh)
                val base = Bitmap.createScaledBitmap(crop, bw, bh, true)
                base.getPixels(basePx, 0, bw, 0, 0, bw, bh)
                if (base !== crop) try { base.recycle() } catch (_: Throwable) {}
                val outPx = IntArray(bw * bh)
                sr.getPixels(outPx, 0, bw, 0, 0, bw, bh)
                for (i in outPx.indices) {
                    val s = outPx[i]; val q = basePx[i]
                    val r = (((s shr 16) and 0xFF) * bp + ((q shr 16) and 0xFF) * (1f - bp)).toInt()
                    val g = (((s shr 8) and 0xFF) * bp + ((q shr 8) and 0xFF) * (1f - bp)).toInt()
                    val b = ((s and 0xFF) * bp + (q and 0xFF) * (1f - bp)).toInt()
                    outPx[i] = -0x1000000 or (r shl 16) or (g shl 8) or b
                }
                sr.setPixels(outPx, 0, bw, 0, 0, bw, bh)
            } catch (_: Throwable) {}
            // Сгладить сетку до растяжки (иначе пиксельная структура едет в JPEG)
            try {
                val smoothed = NoiseReducer().softKotlin(sr, radius = 1, blend = 0.3f)
                if (smoothed !== sr) { try { sr.recycle() } catch (_: Throwable) {} }
                // до целевого размера обычным ресайзом — детали уже восстановлены сетью
                val res = if (smoothed.width == targetW && smoothed.height == targetH) smoothed
                else {
                    val r = Bitmap.createScaledBitmap(smoothed, targetW, targetH, true)
                    try { smoothed.recycle() } catch (_: Throwable) {}
                    r
                }
                // Подрезкость после растяжки (512→4000 даёт мыло): лёгкий аншарп.
                // Только с OpenCV — Kotlin-фолбэк на 3-4МП это минуты!
                val crisp = if (com.example.thundercam20.utils.OpencvManager.available) {
                    try { Sharpener().unsharpFast(res, radius = 1.2f, amount = 0.5f) } catch (_: Throwable) { res }
                } else res
                if (crisp !== res) try { res.recycle() } catch (_: Throwable) {}
                lastUsedSr = true
                try { android.util.Log.i("SuperResZoom", "SR OK tiled [$backend] in ${System.currentTimeMillis() - t0}ms") } catch (_: Throwable) {}
                return crisp
            } catch (_: Throwable) {
                // сглаживание не удалось — отдаём как было
                val res = if (sr.width == targetW && sr.height == targetH) sr
                else {
                    val r = Bitmap.createScaledBitmap(sr, targetW, targetH, true)
                    try { sr.recycle() } catch (_: Throwable) {}
                    r
                }
                lastUsedSr = true
                return res
            }
        } catch (_: Throwable) {
            try { sr.recycle() } catch (_: Throwable) {}
            return null
        }
    }

    private fun inputMean(bmp: Bitmap): Float {
        return try {
            val w = 32; val h = 32
            val s = Bitmap.createScaledBitmap(bmp, w, h, true)
            val px = IntArray(w * h)
            s.getPixels(px, 0, w, 0, 0, w, h)
            if (s !== bmp) try { s.recycle() } catch (_: Throwable) {}
            var sum = 0L
            for (p in px) sum += ((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)
            sum.toFloat() / (w * h * 3)
        } catch (_: Throwable) { 128f }
    }

    /**
     * Lanczos-апскейл + лёгкий шарп (когда сети нет): заметно лучше билинейного,
     * ноль риска. OpenCV нет — возвращаем null, вызыватель идёт в билинейный.
     */
    fun upscaleLanczos(crop: Bitmap, targetW: Int, targetH: Int): Bitmap? {
        if (!com.example.thundercam20.utils.OpencvManager.available) return null
        return try {
            val mat = org.opencv.core.Mat()
            org.opencv.android.Utils.bitmapToMat(crop, mat)
            val big = org.opencv.core.Mat()
            org.opencv.imgproc.Imgproc.resize(mat, big, org.opencv.core.Size(targetW.toDouble(), targetH.toDouble()), 0.0, 0.0, org.opencv.imgproc.Imgproc.INTER_LANCZOS4)
            val bmp = Bitmap.createBitmap(big.cols(), big.rows(), Bitmap.Config.ARGB_8888)
            org.opencv.android.Utils.matToBitmap(big, bmp)
            mat.release(); big.release()
            // лёгкий шарп поверх — подбираем мыло Lanczos
            try {
                val sharp = Sharpener().unsharpFast(bmp, radius = 1.2f, amount = 0.5f)
                if (sharp !== bmp) try { bmp.recycle() } catch (_: Throwable) {}
                sharp
            } catch (_: Throwable) { bmp }
        } catch (t: Throwable) { null }
    }
}
