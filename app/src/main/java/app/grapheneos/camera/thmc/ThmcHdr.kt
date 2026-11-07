package app.grapheneos.camera.thmc

import android.annotation.SuppressLint
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.internal.utils.ImageUtil
import androidxc.camera.core.impl.utils.Exif
import app.grapheneos.camera.CapturedItem
import app.grapheneos.camera.IMAGE_NAME_PREFIX
import app.grapheneos.camera.ITEM_TYPE_IMAGE
import app.grapheneos.camera.capturer.DEFAULT_MEDIA_STORE_CAPTURE_PATH
import app.grapheneos.camera.capturer.ImageCapturer
import app.grapheneos.camera.util.ImageResizer
import app.grapheneos.camera.util.getTreeDocumentUri
import app.grapheneos.camera.util.removePendingFlagFromUri
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ThunderCam HDR: брекетинг через компенсацию экспозиции (AE остаётся включён — быстро),
 * один AF-лок на серию, выравнивание + мерж + P0. Под тумблером thmcHdr, база не тронута.
 *
 * Скорость: фокус ~0.4с + 3×(комп.+выстрел ~0.4-0.7с) + мерж/P0. Цель 2.5-4с против 10с старого пайплайна.
 * Безопасность: любая проблема → стоковый одиночный кадр через takePictureStock().
 */
object ThmcHdr {
    private const val TAG = "ThmcHdr"

    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    // Capture-пул общий (2 потока): следующая серия может снимать пока предыдущая
    // считается в process-пуле. Было: свой single — кадры ждали мерж.

    fun isRunning(): Boolean = running.get()

    /** Отмена серии пользователем (повторный тап). true = отмена принята. */
    fun cancel(): Boolean {
        if (!running.get()) return false
        cancelled.set(true)
        return true
    }

    /** true = серия забрана в работу (затвор обработан, дальше сами). false = снимай стоком. */
    fun capture(imageCapturer: ImageCapturer): Boolean {
        val camConfig = imageCapturer.camConfig
        val camera = camConfig.camera ?: return false
        val imageCapture = camConfig.imageCapture ?: return false
        // Серия уже идёт — не отменяем её (потеря кадров), вызыватель снимет стоком.
        if (running.get()) return false
        if (!running.compareAndSet(false, true)) return false
        cancelled.set(false)
        val seq = imageCapturer.nextSeq()
        try {
            imageCapturer.onCaptureSuccess(null)
        } catch (_: Throwable) {}
        try {
            imageCapturer.mActivity.runOnUiThread {
                try {
                    imageCapturer.mActivity.showSeriesStatus(
                        "HDR 0/3 • " + try {
                            imageCapturer.mActivity.getString(app.grapheneos.camera.R.string.thmc_tap_to_shoot)
                        } catch (_: Throwable) { "" }
                    )
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        ThmcExec.capture.execute { runBrackets(imageCapturer, seq) }
        return true
    }

    private fun finish(imageCapturer: ImageCapturer, restore: () -> Unit) {
        try { restore() } catch (_: Throwable) {}
        try {
            imageCapturer.mActivity.runOnUiThread {
                try { imageCapturer.mActivity.hideSeriesStatus() } catch (_: Throwable) {}
                try { imageCapturer.mActivity.updateSelfTimerBadge() } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        running.set(false)
    }

    private fun progress(imageCapturer: ImageCapturer, text: String) {
        try {
            imageCapturer.mActivity.runOnUiThread {
                try {
                    imageCapturer.mActivity.showSeriesStatus(text)
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    /** Прогресс с подсказкой по дрожи (гиро уже слушает серию). */
    private fun hdrProgress(imageCapturer: ImageCapturer, idx: Int, total: Int): String {
        return try {
            val calm = ThmcGyro.isCalm()
            val hint = imageCapturer.mActivity.getString(
                if (calm) app.grapheneos.camera.R.string.thmc_night_hold
                else app.grapheneos.camera.R.string.thmc_night_freeze
            )
            "HDR ${idx + 1}/$total • $hint"
        } catch (_: Throwable) {
            "HDR ${idx + 1}/$total"
        }
    }

    private fun runBrackets(imageCapturer: ImageCapturer, seq: Long) {
        val camConfig = imageCapturer.camConfig
        val camera = camConfig.camera
        val imageCapture = camConfig.imageCapture
        if (camera == null || imageCapture == null) {
            running.set(false)
            return
        }
        // Авто-EV сложен В брекетинг (без отдельного apply+отстой 1.5с):
        // bias считается из превью и прибавляется к каждому плечу. База HAL не трогаем.
        var origIndexHolder = 0
        var origKnown = false
        val restore: () -> Unit = {
            try { camera.cameraControl.cancelFocusAndMetering() } catch (_: Throwable) {}
            try {
                if (origKnown) camera.cameraControl.setExposureCompensationIndex(origIndexHolder)
            } catch (_: Throwable) {}
            try { ThmcGyro.stop() } catch (_: Throwable) {}
        }
        try {
            ThmcGyro.start(imageCapturer.mActivity)
            val previewL = ThmcTrackState.lastPreviewL
            // Суммарный bias вниз (превью + клиппинг светов + серво по прошлому ISO).
            // Только вниз, иначе брекетинг уплывёт.
            val baseBias = if (camConfig.thmcAutoEv) {
                try {
                    val prefs = imageCapturer.mActivity.getSharedPreferences("commons", android.content.Context.MODE_PRIVATE)
                    val extra = try { prefs.getInt("thmc_ev_extra", 0) } catch (_: Throwable) { 0 }
                    val bf = try { ThmcTrackState.lastBrightFrac } catch (_: Throwable) { 0f }
                    (ThmcAutoEv.biasSteps(previewL, bf) - extra.coerceIn(0, 4)).coerceAtMost(0)
                } catch (_: Throwable) { 0 }
            } else 0
            try {
                imageCapturer.mActivity.getSharedPreferences("commons", android.content.Context.MODE_PRIVATE).edit()
                    .putInt("thmc_last_bias", baseBias).apply()
            } catch (_: Throwable) {}
            val expState = camera.cameraInfo.exposureState
            val origIndex = expState.exposureCompensationIndex
            origIndexHolder = origIndex; origKnown = true
            // Фаза 0 RAW: только диагностика в лог (на решение пока не влияет).
            // Pova — no raw, S25U — raw есть, A17 — увидим в логе без гаданий.
            try {
                val tier = ThmcBench.effectiveProfile(imageCapturer.mActivity, camConfig.thmc).tier
                val r = ThmcRawProbe.probe(camera, ThmcRawProbe.maxSideForTier(tier))
                Log.i(TAG, "raw supported=${r.supported} level=${r.level} best=${r.best}")
            } catch (_: Throwable) {}
            val range = expState.exposureCompensationRange
            // Шаг EV из реального шага компенсации (у большинства 1/3 EV).
            // Плечи считаем в EV, а не в «20% диапазона»: -0.8EV не спасало
            // света, а «плюс» не снималось вовсе — HDR-эффекта не было.
            val stepEv = try {
                val s = expState.exposureCompensationStep
                (s.numerator.toDouble() / s.denominator.coerceAtLeast(1).toDouble()).coerceIn(0.1, 1.0)
            } catch (_: Throwable) { 1.0 / 3.0 }
            val evToSteps = { ev: Double -> (ev / stepEv).toInt().coerceAtLeast(1) }
            // HDR = три плеча: −2EV спасает света, +1.5EV открывает тени, 0 — опора.
            // Днём плечи шире (света есть запас), в сумерках мягче (длинные
            // выдержки = смаз и шум, там и стек ночи поможет).
            val brightScene = previewL >= 0.45f
            val offDown = evToSteps(if (brightScene) 2.0 else 1.0)
            val offUp = evToSteps(if (brightScene) 1.5 else 0.75)
            val fixedBurst = false
            val brackets = intArrayOf(baseBias, baseBias - offDown, baseBias + offUp)

            // Один AF-лок на серию. Soft-ZSL: сцена стоит + AF был <3с назад —
            // пропускаем 600мс reconverge (проверено вебом: ZSL берет уже сошедшийся фокус).
            if (ThmcZsl.skipAf()) {
                try { Log.i(TAG, "af skipped (zsl)") } catch (_: Throwable) {}
            } else try {
                val pv = imageCapturer.mActivity.previewView
                if (pv.width > 0 && pv.height > 0) {
                    val factory = SurfaceOrientedMeteringPointFactory(pv.width.toFloat(), pv.height.toFloat())
                    val pt = factory.createPoint(pv.width / 2f, pv.height / 2f)
                    val action = FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF)
                        .disableAutoCancel().build()
                    camera.cameraControl.startFocusAndMetering(action).get(600, TimeUnit.MILLISECONDS)
                    ThmcZsl.markAf()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "af lock skipped", t)
            }

            val quality = imageCapture.jpegQuality
            val collected = mutableListOf<ByteArray>()
            val frameTimesNs = mutableListOf<Long>()
            val total = brackets.size
            for ((idx, b) in brackets.withIndex()) {
                if (cancelled.get()) {
                    Log.i(TAG, "hdr cancelled by user")
                    finish(imageCapturer, restore)
                    imageCapturer.onShotAborted()
                    try {
                        imageCapturer.mActivity.runOnUiThread {
                            android.widget.Toast.makeText(
                                imageCapturer.mActivity, "HDR отменён", android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    } catch (_: Throwable) {}
                    return
                }
                progress(imageCapturer, hdrProgress(imageCapturer, idx, total))
                try {
                    // Stage A: fixed burst — EC один раз до цикла, дальше кадры подряд без EC.
                    // Первый кадр ставит EC, остальные идут сразу (~250мс вместо 700+250).
                    if (idx == 0 || !fixedBurst) {
                        camera.cameraControl.setExposureCompensationIndex((origIndex + b).coerceIn(range.lower, range.upper))
                            .get(700, TimeUnit.MILLISECONDS)
                        Thread.sleep(25)
                    }
                    val tFrame = android.os.SystemClock.elapsedRealtimeNanos()
                    val bytes = takeOne(imageCapture, quality) ?: throw Exception("frame null")
                    collected.add(bytes)
                    frameTimesNs.add(tFrame)
                } catch (t: Throwable) {
                    Log.w(TAG, "bracket $b failed", t)
                    break
                }
            }
            if (collected.size < 2) throw Exception("hdr short: ${collected.size}")
            processAndSave(imageCapturer, camera, collected, frameTimesNs, quality, seq, hdrSide(imageCapturer), fixedBurst)
            finish(imageCapturer, restore)
        } catch (t: Throwable) {
            Log.w(TAG, "hdr fallback to stock", t)
            finish(imageCapturer, restore)
            imageCapturer.onShotAborted()
            try {
                imageCapturer.mActivity.runOnUiThread {
                    imageCapturer.hdrBypass = true
                    try { imageCapturer.takePicture() } finally { imageCapturer.hdrBypass = false }
                }
            } catch (_: Throwable) {}
        }
    }

    /** Один синхронный кадр в память (вызывается с фонового потока). */
    private fun takeOne(imageCapture: ImageCapture, quality: Int): ByteArray? =
        ImageSaverShared.takeOne(imageCapture, quality)

    /** Зеркало ImageSaver.extractJpegBytes (кроп + JPEG/YUV) — см. ImageSaverShared. */
    private fun extractBytes(image: ImageProxy, quality: Int): ByteArray =
        ImageSaverShared.extractBytes(image, quality)

    // Рабочее разрешение мержа: равнять/мержить 12Мп в Kotlin = ~100с. Как в стоках,
    // ночь считаем на даунскейле (бининг — индустриальный стандарт ночного режима).
    // Сторона тирирована (см. ThmcBench.Profile.hdrSide): бюджет 1792, мид 2304, зверь 3072.
    // Нативный мерж попиксельный — больших FloatArray больше нет, зверю можно выше.
    private const val HDR_WORK_SIDE_FALLBACK = 2048

    fun hdrSide(imageCapturer: ImageCapturer): Int {
        return try {
            ThmcBench.effectiveProfile(imageCapturer.mActivity, imageCapturer.camConfig.thmc).hdrSide
        } catch (_: Throwable) { HDR_WORK_SIDE_FALLBACK }
    }

    private fun sampleFor(bytes: ByteArray, cap: Int): Int {
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var s = 1
            while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= cap) s *= 2
            s
        } catch (_: Throwable) { 1 }
    }

    private fun processAndSave(
        imageCapturer: ImageCapturer,
        camera: androidx.camera.core.Camera,
        frames: List<ByteArray>,
        frameTimesNs: List<Long>,
        quality: Int,
        seq: Long,
        workSide: Int = HDR_WORK_SIDE_FALLBACK,
        fixedBurst: Boolean = true,
    ) {
        val camConfig = imageCapturer.camConfig
        val t0 = System.nanoTime()
        fun mark(m: String) {
            try { Log.i(TAG, "hdr stage $m ${(System.nanoTime() - t0) / 1_000_000}ms") } catch (_: Throwable) {}
        }
        // Декод сразу в рабочее разрешение (inSampleSize — без транзиентного 12Мп).
        // Параллельно в 2 потока: на 3-5 кадров экономит ~0.3-0.6с.
        val sample = sampleFor(frames[0], workSide)
        val (bmps, bmpTimesNs) = ImageSaverShared.decodeParallel(frames, frameTimesNs, sample)
        if (bmps.size < 2) throw Exception("decode short")
        mark("decode x${bmps.size} s=$sample")
        progress(imageCapturer, "HDR равнение")
        val selector = com.example.thundercam20.multiframe.FrameSelector()
        try {
            // QC относительный: абсолютный isGoodFrame для брекетинга вреден (гейт движения
            // срабатывает на разную яркость плеч, пороги резкости/контраста ночь не проходит).
            // Держим кадры не хуже 40% от лучшего — сдвиги дотягивает равнялка, мусор отсекаем.
            // Резких <2 → одиночный резчайший с P0 (быстро и гарантированно резко).
            fun sharpScore(b: Bitmap): Double {
                return try {
                    com.example.thundercam20.analysis.FastGray.meanAbsLaplacian(
                        com.example.thundercam20.analysis.FastGray.of(b, 320)
                    )
                } catch (_: Throwable) { 0.0 }
            }
            val scored = bmps.map { it to sharpScore(it) }
            val bestScore = scored.maxOfOrNull { it.second } ?: 0.0
            // Темнота для адаптивного гейта (средний кадр — frames[0])
            val darkNight = try {
                com.example.thundercam20.analysis.FastGray.of(bmps[0], 160).mean() / 255f < 0.35f
            } catch (_: Throwable) { false }
            // Ночью выдержки длинные → смаз внутри кадра, мерж его не чинит. Гейт строже (0.55):
            // тряска → одиночный резчайший с макс-шумодавом. Днём можно свободнее (0.4).
            val gate = if (darkNight) 0.55 else 0.4
            val goodIdx = scored.indices.filter { scored[it].second >= bestScore * gate }
            val good = goodIdx.map { bmps[it] }
            // Веса из тех же скоров (резчайший тяжелее), без пересчёта
            val weights = goodIdx.map { i ->
                if (bestScore <= 0.0) 1 else (1 + (4 * scored[i].second / bestScore).toInt()).coerceIn(1, 5)
            }
            mark("qc good=${good.size}/${bmps.size} gate=$gate")
            val sharpAll = selector.selectSharpest(bmps)
            mark("select")
            var dAlign = 0L; var dMerge = 0L
            // Выход — в габаритах исходника: серия считается на даунскейле,
            // иначе фото выходит мелким и EXIF врёт про размер.
            val midBytes = frames[0]
            val (fullW, fullH) = ThmcSave.fullSize(midBytes)
            var mergedBmp: Bitmap
            if (good.size >= 2) {
                val sharp = selector.selectSharpest(good)
                val baseIdx = good.indexOf(sharp).coerceAtLeast(0)
                // Гиро-приор как в ночи: модуль предсказанного сдвига сужает поиск
                // (на опоре dead-zone в ThmcAlign дотягивает остаток до точного нуля).
                val goodTimes = goodIdx.map { bmpTimesNs[it] }
                val refTime = goodTimes[baseIdx]
                val focal = try {
                    val gw = good[0].width; val gh = good[0].height
                    val grayW = if (gw >= gh) 320 else (320 * gw / gh.coerceAtLeast(1)).coerceAtLeast(1)
                    ThmcAlign.focalPxGray(camera, grayW)
                } catch (_: Throwable) { 0f }
                var t = System.nanoTime()
                val aligned = ThmcAlignV2.align(good, baseIdx) { i ->
                    try {
                        val mag = ThmcGyro.predictedShiftMag(refTime, goodTimes[i], focal)
                        if (mag <= 0f) 16 else ((mag / 4f + 4f).toInt()).coerceIn(4, 16)
                    } catch (_: Throwable) { 16 }
                }
                dAlign = (System.nanoTime() - t) / 1_000_000
                mark("align")
                t = System.nanoTime()
                // Дневной 3-плечевой HDR — Мертенс (экспозиционная фьюжн: света из
                // тёмного, тени из светлого). Только на спокойной сцене: при дрожи
                // пирамида размазывает (остаток алайна). Дрожь/2 кадра — взвешенный
                // lucky-мерж: резчайший как опора + reject 20. fixed+Винер — ночь.
                val isoHdr = ThmcP0.readIso(midBytes)
                val calm = try { ThmcGyro.isCalm() } catch (_: Throwable) { true }
                val merged = if (fixedBurst) {
                    ThmcMerge.mergeWiener(aligned, baseIdx, noise = ThmcMerge.noiseForIso(isoHdr), rejectThr = 20)
                } else if (aligned.size == 2 || !calm) {
                    ThmcMerge.mergeWeighted(aligned, weights, aligned[baseIdx], 20)
                } else {
                    com.example.thundercam20.imageprocessing.NativeProcessor.mergeMertens(aligned, baseIdx)
                        ?: if (aligned.size >= 3) {
                            val byLuma = aligned.sortedBy {
                                try { com.example.thundercam20.analysis.FastGray.of(it, 120).mean() } catch (_: Throwable) { 128f }
                            }
                            com.example.thundercam20.imageprocessing.NativeProcessor.mergeHdr(
                                dark = byLuma[0],
                                mid = byLuma[byLuma.size / 2],
                                bright = byLuma.last()
                            ) ?: ThmcMerge.mergeWeighted(aligned, weights)
                        } else {
                            ThmcMerge.mergeWeighted(aligned, weights)
                        }
                }
                dMerge = (System.nanoTime() - t) / 1_000_000
                mark("merge")
                for (b in aligned) if (b !== merged && b !in bmps) ThmcBitmaps.release(b)
                mergedBmp = merged
            } else {
                mergedBmp = sharpAll.copy(Bitmap.Config.ARGB_8888, false) ?: sharpAll
                mark("single")
            }
            // P0 на РАБОЧЕМ разрешении, один апскейл ПОСЛЕ (было два upscale + P0 на 12Мп).
            val cc = camConfig
            val nightSingle = darkNight && good.size < 2
            val front = try {
                cc.lensFacing == androidx.camera.core.CameraSelector.LENS_FACING_FRONT
            } catch (_: Throwable) { false }
            val params = ThmcP0.Params(
                // HDR: тон урезан ×0.85 (был ×0.7 — «сила слабовата, почти сток»).
                // Света теперь спасает тёмное плечо брекетинга, лифт — только тени.
                tone = (cc.thmcTone * 0.85f).toInt(),
                contrast = cc.thmcContrast, sat = cc.thmcSat,
                warmth = cc.thmcWarmth, denoise = if (nightSingle) 100 else cc.thmcDenoise,
                frontCamera = front,
                autoScene = cc.thmcAutoScene,
                globalRefine = cc.thmcGlobalRefine,
                stacked = true,
            )
            val tP0s = System.nanoTime()
            progress(imageCapturer, "HDR P0")
            var workBmp: Bitmap = mergedBmp
            if (cc.thmcP0 && cc.thmcP0Strength > 0) {
                com.example.thundercam20.imageprocessing.SuperResZoom.powerful = cc.thmcSrPowerful
                com.example.thundercam20.imageprocessing.SuperResZoom.blendPercent = cc.thmcSrBlend
                workBmp = ThmcP0.processWorkBitmap(mergedBmp, params, ThmcP0.readIso(midBytes), cc.thmcP0Strength / 100f)
                mark("p0work")
            }
            var fullBmp: Bitmap = ThmcSave.upscaleTo(workBmp, fullW, fullH)
            mark("upscale")
            val zoom = cc.thmcExtraZoom
            if (zoom > 1.01f) {
                try {
                    val bw = fullBmp.width; val bh = fullBmp.height
                    val cw = ((bw / zoom).toInt().coerceAtLeast(16) / 2) * 2
                    val ch = ((bh / zoom).toInt().coerceAtLeast(16) / 2) * 2
                    val cropped = Bitmap.createBitmap(fullBmp, (bw - cw) / 2, (bh - ch) / 2, cw, ch)
                    var up: Bitmap? = null
                    if (zoom >= 2f) {
                        try {
                            // ZoomRouter: flat-skip + ROI-центр + кап 2 тайла (было 4 в лоб).
                            up = ZoomRouter.upscale(
                                imageCapturer.mActivity, cropped, bw, bh, zoom
                            )
                            if (up != null && !com.example.thundercam20.imageprocessing.SuperResZoom.lastUsedSr) {
                                ThmcBitmaps.release(up)
                                up = null
                            }
                        } catch (_: Throwable) { up = null }
                    }
                    val zf = up ?: Bitmap.createScaledBitmap(cropped, bw, bh, true)
                    if (zf !== cropped) ThmcBitmaps.release(cropped)
                    if (fullBmp !== zf) ThmcBitmaps.release(fullBmp)
                    fullBmp = zf
                    mark("zoom")
                } catch (t: Throwable) { Log.w(TAG, "zoom crop failed", t) }
            } else {
                try {
                    // Пост-шарп компенсирует билинейный апскейл workSide→full (1.7x на бюджетнике).
                    // Было 0.35/9 — мыло после апскейла. 0.55/6 возвращает кромки, порог держит плоское.
                    val sh = com.example.thundercam20.imageprocessing.Sharpener()
                        .unsharpFast(fullBmp, radius = 0.8f, amount = 0.55f, threshold = 6.0)
                    if (sh !== fullBmp) ThmcBitmaps.release(fullBmp)
                    fullBmp = sh
                    mark("postsharp")
                } catch (_: Throwable) {}
            }
            var finalBytes: ByteArray = ThmcSave.encodeWithExif(fullBmp, midBytes, quality)
            finalBytes = ThmcP0.stampRecipe(finalBytes, try { ThmcP0.readIso(midBytes) } catch (_: Throwable) { 0 })
            val dP0 = (System.nanoTime() - tP0s) / 1_000_000

            mark("p0")
            if (camConfig.removeExifAfterCapture) {
                // Приватность уважаем: EXIF долой (как стоковый removeExif)
                finalBytes = ThmcSave.stripExif(finalBytes)
            }
            ThmcSave.saveFinal(imageCapturer, finalBytes, seq)
            mark("save")
            val total = (System.nanoTime() - t0) / 1_000_000
            Log.i(TAG, "hdr ok ${total}ms")
            // Цифры на экран: тотал + кто жрёт (align/merge/p0), без логов
            try {
                val msg = if (good.size >= 2)
                    "HDR ${(total / 1000.0)}с: кадров ${good.size}/${bmps.size}, равнение ${dAlign}мс, мерж ${dMerge}мс, P0 ${dP0}мс"
                else
                    "HDR ${(total / 1000.0)}с: одиночный (резких ${good.size}/${bmps.size}), P0 ${dP0}мс"
                imageCapturer.mActivity.runOnUiThread {
                    try {
                        android.widget.Toast.makeText(
                            imageCapturer.mActivity, msg, android.widget.Toast.LENGTH_LONG
                        ).show()
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        } finally {
            for (b in bmps) ThmcBitmaps.release(b)
        }
    }

}

/** Общий executor колбэков захвата (как ImageSaver.imageCaptureCallbackExecutor). */
object ImageSaverShared {
    val executor = Executors.newSingleThreadExecutor()

    /**
     * Параллельный декод серии: пока снимался кадр N+1, CPU простаивал — декодируем
     * в 2 потока вместо последовательного цикла. На 4-5 кадров экономит ~0.3-0.6с
     * на многоядерных бюджетниках. Порядок и соответствие временам сохраняется.
     * Каждому потоку — своя копия Options (out-поля BitmapFactory не потокобезопасны).
     * inBitmap из пула: декод идёт в готовый буфер без аллокации; несовпадение
     * размера — честный фолбэк на обычный декод, буфер возвращается в пул целым.
     */
    fun decodeParallel(
        frames: List<ByteArray>,
        frameTimesNs: List<Long>,
        sample: Int,
    ): Pair<MutableList<android.graphics.Bitmap>, MutableList<Long>> {
        val bmps = mutableListOf<android.graphics.Bitmap>()
        val times = mutableListOf<Long>()
        if (frames.isEmpty()) return bmps to times
        // Ожидаемые габариты для inBitmap (по первому кадру; брекетинг одного размера).
        var ew = 0; var eh = 0
        try {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(frames[0], 0, frames[0].size, bounds)
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                ew = maxOf(1, (bounds.outWidth + sample - 1) / sample.coerceAtLeast(1))
                eh = maxOf(1, (bounds.outHeight + sample - 1) / sample.coerceAtLeast(1))
            }
        } catch (_: Throwable) {}
        fun decodeOne(bytes: ByteArray): android.graphics.Bitmap? {
            if (ew > 0 && eh > 0) {
                try {
                    val pooled = app.grapheneos.camera.thmc.ThmcBitmaps.obtain(ew, eh)
                    if (pooled != null) {
                        val o = android.graphics.BitmapFactory.Options().apply {
                            inSampleSize = sample
                            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                            inBitmap = pooled
                            inMutable = true
                        }
                        try {
                            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)?.let { return it }
                        } catch (_: IllegalArgumentException) {
                            // Размер не сошёлся — буфер цел, вернуть в пул и декодить обычно.
                        }
                        app.grapheneos.camera.thmc.ThmcBitmaps.release(pooled)
                    }
                } catch (_: Throwable) {}
            }
            return try {
                val o = android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            } catch (_: Throwable) { null }
        }
        if (frames.size == 1) {
            decodeOne(frames[0])?.let {
                bmps.add(it); times.add(frameTimesNs.getOrElse(0) { 0L })
            }
            return bmps to times
        }
        // Общий decode-пул (не создавать на серию): экономия ~50мс на создании потоков.
        val pool = ThmcExec.decode
        try {
            val futs = frames.map { bytes ->
                pool.submit<android.graphics.Bitmap?> { decodeOne(bytes) }
            }
            for (i in futs.indices) {
                try {
                    val b = futs[i].get(10, TimeUnit.SECONDS)
                    if (b != null) { bmps.add(b); times.add(frameTimesNs.getOrElse(i) { 0L }) }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {
        }
        return bmps to times
    }

    /** Один синхронный кадр в память (вызывается с фонового потока). Общий для HDR и ночи. */
    @SuppressLint("RestrictedApi")
    fun takeOne(imageCapture: ImageCapture, quality: Int): ByteArray? {
        val lock = java.util.concurrent.CountDownLatch(1)
        var out: ByteArray? = null
        imageCapture.takePicture(
            executor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        out = extractBytes(image, quality)
                    } catch (_: Throwable) {
                    } finally {
                        try { image.close() } catch (_: Throwable) {}
                        lock.countDown()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    lock.countDown()
                }
            }
        )
        lock.await(3, TimeUnit.SECONDS)
        return out
    }

    /** Зеркало ImageSaver.extractJpegBytes (кроп + JPEG/YUV). */
    @SuppressLint("RestrictedApi")
    fun extractBytes(image: ImageProxy, quality: Int): ByteArray {
        var cropRect: Rect? = null
        var jpeg: ByteArray
        try {
            cropRect = if (ImageUtil.shouldCropImage(image)) image.cropRect else null
            jpeg = if (image.format == android.graphics.ImageFormat.JPEG) {
                ImageUtil.jpegImageToJpegByteArray(image)
            } else {
                ImageUtil.yuvImageToJpegByteArray(image, cropRect, quality, 0)
            }
        } finally {
            try { image.close() } catch (_: Throwable) {}
        }
        if (cropRect != null) {
            try {
                val m = ImageUtil::class.java.getDeclaredMethod(
                    "cropJpegByteArray", ByteArray::class.java, Rect::class.java, Int::class.javaPrimitiveType
                )
                m.isAccessible = true
                jpeg = m.invoke(null, jpeg, cropRect, quality) as ByteArray
            } catch (_: Throwable) {}
        }
        return jpeg
    }
}
