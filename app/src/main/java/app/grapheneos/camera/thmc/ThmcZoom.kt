package app.grapheneos.camera.thmc

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import app.grapheneos.camera.capturer.ImageCapturer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Программная стабилизация на зуме (фото).
 *
 * Проблема: на цифровом зуме дрожь рук множится вместе с кропом, а серии
 * (ночь/HDR) на большом зуме запрещены — сдвиг выходит за окно равнялки.
 * В итоге на 5–10× одиночный кадр часто смазан.
 *
 * Решение — «lucky imaging»: быстро снимаем 3 кадра подряд, скорим резкость
 * по лапласиану и оставляем самый резкий. Никакого мержа разных кадров:
 * на зуме микросдвиг заметнее, чем выигрыш от усреднения, а шумодав и так
 * отработает в P0. Декод идёт «на лету» (ThmcBurst), пока снимается следующий.
 *
 * Сохранение — штатным одиночным путём (ThmcP0.apply + ThmcSave.saveFinal),
 * поэтому зум-кроп, SR-апскейл, рецепт и EXIF остаются теми же, что и обычно.
 * Любая проблема → обычный одиночный сток (как раньше).
 */
object ThmcZoom {
    private const val TAG = "ThmcZoom"
    private const val FRAMES = 3

    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    fun isRunning(): Boolean = running.get()

    fun cancel(): Boolean {
        if (!running.get()) return false
        cancelled.set(true)
        return true
    }

    /** true = кадр забран в работу. false = снимай обычным путём. */
    fun capture(imageCapturer: ImageCapturer, totalZoom: Float): Boolean {
        val camConfig = imageCapturer.camConfig
        val camera = camConfig.camera ?: return false
        if (camConfig.imageCapture == null) return false
        if (!running.compareAndSet(false, true)) return false
        cancelled.set(false)
        val seq = imageCapturer.nextSeq()
        try { imageCapturer.onCaptureSuccess(null) } catch (_: Throwable) {}
        ThmcExec.capture.execute { run(imageCapturer, camera, totalZoom, seq) }
        return true
    }

    private fun run(
        imageCapturer: ImageCapturer,
        camera: Camera,
        totalZoom: Float,
        seq: Long,
    ) {
        val camConfig = imageCapturer.camConfig
        val imageCapture: ImageCapture = camConfig.imageCapture ?: run {
            running.set(false)
            return
        }
        val t0 = System.nanoTime()
        fun progress(text: String) {
            try {
                imageCapturer.mActivity.runOnUiThread {
                    try { imageCapturer.mActivity.showSeriesStatus(text) } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        }
        try {
            ThmcGyro.start(imageCapturer.mActivity)
            // Один короткий AF-лок на всю тройку: дальше кадры идут подряд.
            try {
                val pv = imageCapturer.mActivity.previewView
                if (pv.width > 0 && pv.height > 0) {
                    val factory = SurfaceOrientedMeteringPointFactory(pv.width.toFloat(), pv.height.toFloat())
                    val pt = factory.createPoint(pv.width / 2f, pv.height / 2f)
                    val action = FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF)
                        .disableAutoCancel().build()
                    camera.cameraControl.startFocusAndMetering(action).get(450, TimeUnit.MILLISECONDS)
                }
            } catch (_: Throwable) {}

            val quality = imageCapture.jpegQuality
            val nightSide = try {
                ThmcBench.effectiveProfile(imageCapturer.mActivity, camConfig.thmc).nightSide
            } catch (_: Throwable) { 2048 }
            val burst = ThmcBurst(nightSide)
            val frames = ArrayList<ByteArray>(FRAMES)
            for (i in 0 until FRAMES) {
                if (cancelled.get()) {
                    Log.i(TAG, "zoom cancelled")
                    abort(imageCapturer)
                    return
                }
                val zoomLabel = "×" + "%.1f".format(totalZoom)
                progress("Зум $zoomLabel ${i + 1}/$FRAMES • держите ровно")
                try { ThmcGyro.awaitCalm(70) } catch (_: Throwable) {}
                try {
                    val ts = SystemClock.elapsedRealtimeNanos()
                    val bytes = ImageSaverShared.takeOne(imageCapture, quality)
                        ?: throw Exception("frame null")
                    frames.add(bytes)
                    burst.submit(frames.size - 1, bytes, ts)
                } catch (t: Throwable) {
                    Log.w(TAG, "zoom frame $i failed", t)
                }
            }
            if (frames.isEmpty()) throw Exception("zoom short")

            val dec = burst.awaitAll()
            if (dec.bmps.isEmpty()) throw Exception("zoom decode short")
            // Самый резкий кадр по лапласиану на сером 320 (как QC серий).
            var bestIdx = 0
            var bestScore = -1.0
            for (i in dec.bmps.indices) {
                val s = try {
                    com.example.thundercam20.analysis.FastGray.meanAbsLaplacian(
                        com.example.thundercam20.analysis.FastGray.of(dec.bmps[i], 320)
                    )
                } catch (_: Throwable) { 0.0 }
                if (s > bestScore) { bestScore = s; bestIdx = i }
            }
            val bestSrc = dec.srcIdx.getOrNull(bestIdx)?.let { frames.getOrNull(it) } ?: frames[0]
            for (b in dec.bmps) ThmcBitmaps.recycleQuietly(b)

            // Штатный одиночный путь: кроп зума + SR + P0 + EXIF.
            val cc = camConfig
            val front = try {
                cc.lensFacing == androidx.camera.core.CameraSelector.LENS_FACING_FRONT
            } catch (_: Throwable) { false }
            val params = ThmcP0.Params(
                tone = cc.thmcTone, contrast = cc.thmcContrast, sat = cc.thmcSat,
                warmth = cc.thmcWarmth, denoise = cc.thmcDenoise,
                frontCamera = front,
                autoScene = cc.thmcAutoScene,
                globalRefine = cc.thmcGlobalRefine,
            )
            com.example.thundercam20.imageprocessing.SuperResZoom.powerful = cc.thmcSrPowerful
            com.example.thundercam20.imageprocessing.SuperResZoom.blendPercent = cc.thmcSrBlend
            val out = try {
                ThmcP0.apply(
                    bestSrc, quality, cc.thmcP0Strength / 100f, params,
                    null, imageCapturer.mActivity.applicationContext, cc.thmcExtraZoom
                )
            } catch (_: Throwable) { null }
            ThmcSave.saveFinal(imageCapturer, out ?: bestSrc, seq)
            val sec = "%.1f".format((System.nanoTime() - t0) / 1_000_000_000.0)
            toast(imageCapturer, "Зум ×${"%.1f".format(totalZoom)}: резчайший из ${dec.bmps.size} за $sec с")
        } catch (t: Throwable) {
            Log.w(TAG, "zoom fallback to single", t)
            abort(imageCapturer)
        } finally {
            running.set(false)
            try { ThmcGyro.stop() } catch (_: Throwable) {}
            try {
                imageCapturer.mActivity.runOnUiThread {
                    try { imageCapturer.mActivity.hideSeriesStatus() } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        }
    }

    /** Отмена/сбой: обычный одиночный кадр, чтобы пользователь не остался без фото. */
    private fun abort(imageCapturer: ImageCapturer) {
        try {
            imageCapturer.mActivity.runOnUiThread {
                try {
                    imageCapturer.hdrBypass = true
                    try { imageCapturer.takePicture() } finally { imageCapturer.hdrBypass = false }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    private fun toast(imageCapturer: ImageCapturer, msg: String) {
        try {
            imageCapturer.mActivity.runOnUiThread {
                try {
                    android.widget.Toast.makeText(
                        imageCapturer.mActivity, msg, android.widget.Toast.LENGTH_SHORT
                    ).show()
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }
}
