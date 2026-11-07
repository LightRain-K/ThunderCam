package app.grapheneos.camera.thmc

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import app.grapheneos.camera.R
import app.grapheneos.camera.capturer.ImageCapturer
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ThunderCam ночь V1: серия ОДИНАКОВЫХ коротких недоэкспонированных кадров
 * (много коротких + усреднение вместо одного длинного со смазом),
 * гиро-гейт спуска, lucky-опора, выравнивание, робастный мерж, P0.
 *
 * Включается авто по тёмной сцене (см. isNight + thmcNightAuto), приоритетом над HDR:
 * брекетинг в темноте даёт смаз, стек — нет. База не тронута, фолбэк — одиночный.
 *
 * V1 осознанно в JPEG-домене (reuse ThmcAlign/Merge/denoise), работа на 2048 —
 * как HDR. V2 (Camera2 YUV + тайловый Винер) — отдельным этапом.
 */
object ThmcNight {
    private const val TAG = "ThmcNight"

    /**
     * Ниже — ночь (та же граница, что тёмный гейт HDR).
     * 0.35 → 0.48: сумерки (0.35–0.5) были МЁРТВОЙ ЗОНЕЙ — одиночный кадр на
     * ISO 800–3000, который P0 потом лифтит = «светлит и жутко шумит». Стек —
     * единственный инструмент, реально давящий шум (√N), он и получает сумерки.
     * Guards на месте: firstL>0.55 → одиночный, QC по годным кадрам, adaptiveFrames
     * по тряске, отстой гиро. Глубокая ночь ниже 0.35 — как жила.
     */
    const val NIGHT_PREVIEW_L = 0.48f

    /** Рабочая сторона стека: как HDR (память бюджетника). */
    const val NIGHT_WORK_SIDE = 2048

    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    // Capture-пул общий (см. ThmcHdr): серии не ждут чужой мерж.

    fun isRunning(): Boolean = running.get()

    fun cancel(): Boolean {
        if (!running.get()) return false
        cancelled.set(true)
        return true
    }

    /** Тёмная сцена (превью-яркость из трекера). Чистая, тестируется. */
    fun isNight(previewL: Float): Boolean = previewL in 0.02f..NIGHT_PREVIEW_L

    /**
     * Устойчивое решение о ночной серии: тьма подтверждена медианой окна + сцена не скачет.
     * Чинит ложные срабатывания на светлом (протухший lastPreviewL после выхода из тьмы
     * или один тёмный кадр превью). Глубокая тьма (<0.12) — сразу, без бюрократии.
     */
    fun shouldRunNight(previewL: Float): Boolean {
        if (!isNight(previewL)) return false
        if (previewL < 0.12f) return true
        val med = ThmcTrackState.medianPreviewL()
        if (med < 0f) return true // мало истории (старт) — доверяем кадру
        if (med > NIGHT_PREVIEW_L) return false // окно светлое — сэмпл протух
        if (ThmcTrackState.sceneChanged()) return false // сцена скачет — одиночным, без размазни
        return true
    }

    /** План серии: темнее → больше кадров (√N давит шум). Кап — профилем тира. Чистый. */
    data class Plan(val frames: Int)

    fun planNight(previewL: Float, maxFrames: Int = 6): Plan {
        val n = when {
            previewL < 0.12f -> 6
            previewL < 0.20f -> 5
            previewL < 0.35f -> 4
            else -> 3 // сумерки: 3 кадра = √3 шума за ~1с, больше — ждём тряску
        }
        return Plan(n.coerceAtMost(maxFrames.coerceAtLeast(2)))
    }

    /** true = серия забрана в работу. false = снимай стоком. */
    fun capture(imageCapturer: ImageCapturer): Boolean {
        val camConfig = imageCapturer.camConfig
        val camera = camConfig.camera ?: return false
        val imageCapture = camConfig.imageCapture ?: return false
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
                        "Ночь 0 • " + try {
                            imageCapturer.mActivity.getString(R.string.thmc_tap_to_shoot)
                        } catch (_: Throwable) { "" }
                    )
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        ThmcExec.capture.execute { runSeries(imageCapturer, seq) }
        return true
    }

    private fun finish(imageCapturer: ImageCapturer, restore: () -> Unit) {
        try { restore() } catch (_: Throwable) {}
        try { ThmcGyro.stop() } catch (_: Throwable) {}
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

    private fun runSeries(imageCapturer: ImageCapturer, seq: Long) {
        val camConfig = imageCapturer.camConfig
        val camera = camConfig.camera
        val imageCapture = camConfig.imageCapture
        if (camera == null || imageCapture == null) {
            running.set(false)
            return
        }
        val restore: () -> Unit = {
            try { camera.cameraControl.cancelFocusAndMetering() } catch (_: Throwable) {}
            try {
                val orig = camera.cameraInfo.exposureState.exposureCompensationIndex
                camera.cameraControl.setExposureCompensationIndex(orig)
            } catch (_: Throwable) {}
        }
        try {
            ThmcGyro.start(imageCapturer.mActivity)
            val expState = camera.cameraInfo.exposureState
            val origIndex = expState.exposureCompensationIndex
            val range = expState.exposureCompensationRange
            // Недоэкспозиция −1EV порядком: морозим света и шевеленку, тени поднимет стек.
            // СУМЕРКИ (>=0.35) не недодерживаем: там и так нормальный свет, недодержка
            // + лифт = ровно та жалоба «шумно и грезит». Стек сам по себе давит шум.
            val twilightish = ThmcTrackState.lastPreviewL >= 0.35f
            val off = if (twilightish) 0
                else ((range.upper - range.lower) * 0.2f).toInt().coerceAtLeast(1)
            val nightIndex = (origIndex - off).coerceIn(range.lower, range.upper)
            // Профиль тира: сколько кадров и на каком разрешении тянет телефон.
            // ZSL motion metering: тряска режет число кадров (как Night Sight).
            val prof = try {
                ThmcBench.effectiveProfile(imageCapturer.mActivity, camConfig.thmc)
            } catch (_: Throwable) { ThmcBench.profileOf(ThmcBench.TIER_MID) }
            val basePlan = planNight(ThmcTrackState.lastPreviewL, prof.nightMax)
            val plan = try {
                basePlan.copy(frames = ThmcTrackState.adaptiveFrames(basePlan.frames, 2, prof.nightMax))
            } catch (_: Throwable) { basePlan }

            // Один AF-лок на серию. Ночь длинная (1200мс) — тем ценнее пропуск при стоящей сцене.
            if (ThmcZsl.skipAf()) {
                try { Log.i(TAG, "af skipped (zsl)") } catch (_: Throwable) {}
            } else try {
                val pv = imageCapturer.mActivity.previewView
                if (pv.width > 0 && pv.height > 0) {
                    val factory = SurfaceOrientedMeteringPointFactory(pv.width.toFloat(), pv.height.toFloat())
                    val pt = factory.createPoint(pv.width / 2f, pv.height / 2f)
                    val action = FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF)
                        .disableAutoCancel().build()
                    camera.cameraControl.startFocusAndMetering(action).get(1200, TimeUnit.MILLISECONDS)
                    Thread.sleep(30)
                    ThmcZsl.markAf()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "af lock skipped", t)
            }

            camera.cameraControl.setExposureCompensationIndex(nightIndex).get(700, TimeUnit.MILLISECONDS)
            Thread.sleep(25)
            val quality = imageCapture.jpegQuality
            val collected = mutableListOf<ByteArray>()
            val frameTimesNs = mutableListOf<Long>()
            for (idx in 0 until plan.frames) {
                if (cancelled.get()) {
                    Log.i(TAG, "night cancelled")
                    finish(imageCapturer, restore)
                    imageCapturer.onShotAborted()
                    return
                }
                // Подсказка по дрожи: спокойно — «держите ровно», крутит — «замрите».
                val calm = try { ThmcGyro.isCalm() } catch (_: Throwable) { true }
                try {
                    val hint = imageCapturer.mActivity.getString(
                        if (calm) R.string.thmc_night_hold else R.string.thmc_night_freeze
                    )
                    progress(imageCapturer, "Ночь ${idx + 1}/${plan.frames} • $hint")
                } catch (_: Throwable) {
                    progress(imageCapturer, "Ночь ${idx + 1}/${plan.frames}")
                }
                // Гиро-гейт: не снимаем в момент рывка (до 150мс ждём, дальше — как есть).
                // Было 250мс × 6 кадров = до 1.5с чистого ожидания. Спокойные руки — 0мс и так.
                try { ThmcGyro.awaitCalm(150) } catch (_: Throwable) {}
                try {
                    val tFrame = SystemClock.elapsedRealtimeNanos()
                    val bytes = ImageSaverShared.takeOne(imageCapture, quality)
                        ?: throw Exception("frame null")
                    collected.add(bytes)
                    frameTimesNs.add(tFrame)
                } catch (t: Throwable) {
                    Log.w(TAG, "night frame $idx failed", t)
                }
            }
            if (collected.size < 2) throw Exception("night short: ${collected.size}")
            val nightSide = try {
                ThmcBench.effectiveProfile(imageCapturer.mActivity, camConfig.thmc).nightSide
            } catch (_: Throwable) { NIGHT_WORK_SIDE }
            processAndSave(imageCapturer, camera, collected, frameTimesNs, quality, seq, nightSide)
            finish(imageCapturer, restore)
        } catch (t: Throwable) {
            Log.w(TAG, "night fallback to stock", t)
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

    private fun sampleFor(bytes: ByteArray, cap: Int): Int {
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var s = 1
            while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= cap) s *= 2
            s
        } catch (_: Throwable) { 1 }
    }

    private fun sharpScore(b: Bitmap): Double {
        return try {
            com.example.thundercam20.analysis.FastGray.meanAbsLaplacian(
                com.example.thundercam20.analysis.FastGray.of(b, 320)
            )
        } catch (_: Throwable) { 0.0 }
    }

    /** Фокусное в пикселях серого превью — см. ThmcAlign (общее для серий). */
    private fun focalPxGray(camera: Camera, grayW: Int): Float =
        ThmcAlign.focalPxGray(camera, grayW)

    private fun processAndSave(
        imageCapturer: ImageCapturer,
        camera: Camera,
        frames: List<ByteArray>,
        frameTimesNs: List<Long>,
        quality: Int,
        seq: Long,
        nightSide: Int,
    ) {
        val camConfig = imageCapturer.camConfig
        val t0 = System.nanoTime()
        fun mark(m: String) {
            try { Log.i(TAG, "night stage $m ${(System.nanoTime() - t0) / 1_000_000}ms") } catch (_: Throwable) {}
        }
        // Параллельный декод в 2 потока (см. HDR): ~0.3-0.6с экономии на 4-6 кадров.
        val sample = sampleFor(frames[0], nightSide)
        val (bmps, bmpTimesNs) = ImageSaverShared.decodeParallel(frames, frameTimesNs, sample)
        if (bmps.size < 2) throw Exception("decode short")
        mark("decode x${bmps.size} s=$sample")
        // Страховка от протухшего триггера: первый кадр светлый — серия не нужна.
        // Одиночный с P0 быстрее и чище, чем стек из светлых (тот ещё и пересвечивает).
        val firstL = try {
            com.example.thundercam20.analysis.FastGray.of(bmps[0], 160).mean() / 255f
        } catch (_: Throwable) { 0f }
        if (firstL > 0.55f) {
            val sharpAll = com.example.thundercam20.multiframe.FrameSelector().selectSharpest(bmps)
            val single = sharpAll.copy(Bitmap.Config.ARGB_8888, false) ?: sharpAll
            mark("daylight-single")
            saveWithP0(imageCapturer, single, frames[frames.size / 2], quality, seq)
            mark("p0single")
            toast(imageCapturer, "Ночь ${(elapsedSec(t0))}с: светло, одиночный")
            return
        }
        progress(imageCapturer, "Ночь равнение")
        try {
            // QC относительный (как HDR): не хуже 55% от лучшего, веса из тех же скоров.
            val scored = bmps.map { it to sharpScore(it) }
            val bestScore = scored.maxOfOrNull { it.second } ?: 0.0
            val goodIdx = scored.indices.filter { scored[it].second >= bestScore * 0.55 }
            val good = goodIdx.map { bmps[it] }
            val weights = goodIdx.map { i ->
                if (bestScore <= 0.0) 1 else (1 + (4 * scored[i].second / bestScore).toInt()).coerceIn(1, 5)
            }
            mark("qc good=${good.size}/${bmps.size}")
            // Годных <2 → lucky-опора одиночным с макс-шумодавом (нечего стекать).
            // Лучший вид: P0 на рабочем размере, апскейл после (не наоборот).
            if (good.size < 2) {
                val sharpAll = com.example.thundercam20.multiframe.FrameSelector().selectSharpest(bmps)
                val single = sharpAll.copy(Bitmap.Config.ARGB_8888, false) ?: sharpAll
                mark("single")
                saveWithP0(imageCapturer, single, frames[frames.size / 2], quality, seq)
                mark("p0single")
                toast(imageCapturer, "Ночь ${(elapsedSec(t0))}с: одиночный (годных ${good.size}/${bmps.size})")
                return
            }
            val sharp = com.example.thundercam20.multiframe.FrameSelector().selectSharpest(good)
            val baseIdx = good.indexOf(sharp).coerceAtLeast(0)
            // Гиро-приор: предсказанный |сдвиг| сужает радиус грубого поиска
            // (спокойно → быстро, тряска → полный диапазон). Только модуль — без риска знака.
            val goodTimes = goodIdx.map { bmpTimesNs[it] }
            val refTime = goodTimes[baseIdx]
            val focal = try {
                val gw = good[0].width; val gh = good[0].height
                val grayW = if (gw >= gh) 320 else (320 * gw / gh.coerceAtLeast(1)).coerceAtLeast(1)
                focalPxGray(camera, grayW)
            } catch (_: Throwable) { 0f }
            var t = System.nanoTime()
            val aligned = ThmcAlignV2.align(good, baseIdx) { i ->
                try {
                    val mag = ThmcGyro.predictedShiftMag(refTime, goodTimes[i], focal)
                    if (mag <= 0f) 16 else ((mag / 4f + 4f).toInt()).coerceIn(4, 16)
                } catch (_: Throwable) { 16 }
            }
            val dAlign = (System.nanoTime() - t) / 1_000_000
            mark("align")
            t = System.nanoTime()
            // Винер для fixed-burst (HDR+ style): ночь — одинаковая недодержка, Мертенс вреден.
            // noise по ISO среднего кадра; ISO неизвестен (EXIF пуст) → 12 как ночью.
            val isoNight = try { ThmcP0.readIso(frames[frames.size / 2]) } catch (_: Throwable) { 0 }
            val noiseNight = if (isoNight > 0) ThmcMerge.noiseForIso(isoNight).coerceAtLeast(8f) else 12f
            val merged = ThmcMerge.mergeWiener(aligned, baseIdx, noise = noiseNight, rejectThr = 28)
            val dMerge = (System.nanoTime() - t) / 1_000_000
            mark("merge")
            for (b in aligned) if (b !== merged && b !in bmps) ThmcBitmaps.release(b)
            progress(imageCapturer, "Ночь P0")
            val tP0s = System.nanoTime()
            // Лучший вид: merged уже рабочего размера — P0 на нём, апскейл внутри saveWithP0.
            saveWithP0(imageCapturer, merged, frames[frames.size / 2], quality, seq)
            val dP0 = (System.nanoTime() - tP0s) / 1_000_000
            mark("p0")
            toast(imageCapturer, "Ночь ${elapsedSec(t0)}с: кадров ${good.size}/${bmps.size}, равнение ${dAlign}мс, мерж ${dMerge}мс, P0 ${dP0}мс")
        } finally {
            for (b in bmps) ThmcBitmaps.release(b)
        }
    }

    private fun elapsedSec(t0: Long) = "%.1f".format((System.nanoTime() - t0) / 1_000_000_000.0)

    private fun toast(imageCapturer: ImageCapturer, msg: String) {
        try {
            imageCapturer.mActivity.runOnUiThread {
                try {
                    android.widget.Toast.makeText(
                        imageCapturer.mActivity, msg, android.widget.Toast.LENGTH_LONG
                    ).show()
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    /** P0 на рабочем размере + апскейл + EXIF + сохранение. bmpIn освобождается внутри. */
    private fun saveWithP0(
        imageCapturer: ImageCapturer,
        bmpIn: Bitmap,
        exifSource: ByteArray,
        quality: Int,
        seq: Long,
    ) {
        val camConfig = imageCapturer.camConfig
        val cc = camConfig
        val front = try {
            cc.lensFacing == androidx.camera.core.CameraSelector.LENS_FACING_FRONT
        } catch (_: Throwable) { false }
        val params = ThmcP0.Params(
            // Ночь: тон ×0.85 (был ×0.65 — «сила слабовата»). Стек поднял тени
            // усреднением; лифт только добивает, фонари защищает highlightPull.
            tone = (cc.thmcTone * 0.85f).toInt(),
            contrast = cc.thmcContrast, sat = cc.thmcSat,
            warmth = cc.thmcWarmth,
            denoise = 100,
            frontCamera = front,
            autoScene = cc.thmcAutoScene,
            globalRefine = cc.thmcGlobalRefine,
            stacked = true,
        )
        com.example.thundercam20.imageprocessing.SuperResZoom.powerful = cc.thmcSrPowerful
        com.example.thundercam20.imageprocessing.SuperResZoom.blendPercent = cc.thmcSrBlend
        // 1. P0 на рабочем размере (шумодав/деталь дёшево), без JPEG туда-обратно.
        var work: Bitmap = bmpIn
        if (cc.thmcP0 && cc.thmcP0Strength > 0) {
            // Ночь: раньше сила ужималась до 80% — «эффект почти не виден».
            // Стек уже погасил шум √N, P0 может работать в полную (95% кап на страх).
            work = ThmcP0.processWorkBitmap(bmpIn, params, ThmcP0.readIso(exifSource), minOf(cc.thmcP0Strength / 100f, 0.95f))
        }
        // 2. Дотяжка до габаритов исходника (EXIF не врёт).
        val (fullW, fullH) = ThmcSave.fullSize(exifSource)
        var full: Bitmap = ThmcSave.upscaleTo(work, fullW, fullH)
        // 3. Зум-кроп на финале без второго шумодава.
        val zoom = cc.thmcExtraZoom
        if (zoom > 1.01f) {
            try {
                val bw = full.width; val bh = full.height
                val cw = ((bw / zoom).toInt().coerceAtLeast(16) / 2) * 2
                val ch = ((bh / zoom).toInt().coerceAtLeast(16) / 2) * 2
                val cropped = Bitmap.createBitmap(full, (bw - cw) / 2, (bh - ch) / 2, cw, ch)
                var up: Bitmap? = null
                if (zoom >= 2f) {
                    try {
                        // ZoomRouter: flat-skip + ROI-центр + кап 2 тайла (как в HDR).
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
                if (full !== zf) ThmcBitmaps.release(full)
                full = zf
            } catch (t: Throwable) { android.util.Log.w(TAG, "zoom crop failed", t) }
        } else {
            try {
                // Пост-шарп после билинейного апскейла 2048→full — как в HDR
                // (0.55/6). Старые 0.35/9 оставляли стек «мыльноватым».
                val sh = com.example.thundercam20.imageprocessing.Sharpener()
                    .unsharpFast(full, radius = 0.8f, amount = 0.55f, threshold = 6.0)
                if (sh !== full) ThmcBitmaps.release(full)
                full = sh
            } catch (_: Throwable) {}
        }
        var finalBytes: ByteArray = ThmcSave.encodeWithExif(full, exifSource, quality)
        finalBytes = ThmcP0.stampRecipe(finalBytes, try { ThmcP0.readIso(exifSource) } catch (_: Throwable) { 0 })
        if (camConfig.removeExifAfterCapture) {
            finalBytes = ThmcSave.stripExif(finalBytes)
        }
        ThmcSave.saveFinal(imageCapturer, finalBytes, seq)
    }
}
