package app.grapheneos.camera.thmc

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import app.grapheneos.camera.capturer.ImageCapturer
import kotlin.math.abs
import kotlin.math.min
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Программная стабилизация на зуме: субпиксельный стек (Super Res Zoom style).
 *
 * Проблема: на цифровом зуме дрожь множится вместе с кропом, а серии на большом
 * зуме запрещены — сдвиг выходит за окно равнялки. Одиночный кадр часто смазан.
 *
 * Решение — не «выбрать самый резкий», а сложить серию:
 *   1) 4 кадра подряд (декод «на лету»);
 *   2) QC по резкости (лапласиан) + ГИРОСКОП: кадры, снятые в рывок,
 *      выкидываются по интегралу |ω| вокруг спуска — смаз даёт «псевдорезкость»;
 *   3) целочисленное равнение (ThmcAlignV2) общим пересечением;
 *   4) СУБПИКСЕЛЬНАЯ доводка остатка ±2 px (билинейный SAD на сером 160)
 *      и варп кадров на этот остаток;
 *   5) робастный мерж Винера (variance-weighting + reject выбросов) — шум падает
 *      как √N, детали остаются от опоры, а субпиксельная разница кадров реально
 *      добавляет разрешение, а не «угадывается».
 *
 * Это честный стек: движение и выбросы отсекаются, а не «повезёт/не повезёт».
 * Любая проблема → одиночный кадр штатным путём (как раньше).
 */
object ThmcZoom {
    private const val TAG = "ThmcZoom"
    private const val FRAMES = 4
    /** Кадры хуже этой доли от лучшего по резкости в стек не берём. */
    private const val QC_SHARP = 0.60

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
        var merged: Bitmap? = null
        try {
            ThmcGyro.start(imageCapturer.mActivity)
            // Один короткий AF-лок на серию: дальше кадры идут подряд.
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
            val workSide = try {
                ThmcBench.effectiveProfile(imageCapturer.mActivity, camConfig.thmc).nightSide
            } catch (_: Throwable) { 2048 }
            val burst = ThmcBurst(workSide)
            val frames = ArrayList<ByteArray>(FRAMES)
            val frameTimes = ArrayList<Long>(FRAMES)
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
                    frameTimes.add(ts)
                    burst.submit(frames.size - 1, bytes, ts)
                } catch (t: Throwable) {
                    Log.w(TAG, "zoom frame $i failed", t)
                }
            }
            if (frames.isEmpty()) throw Exception("zoom short")

            val dec = burst.awaitAll()
            if (dec.bmps.isEmpty()) throw Exception("zoom decode short")
            progress("Зум: складываю ${dec.bmps.size} кадров")

            // --- QC: резкость по лапласиану, опора — самый резкий кадр ---
            val scores = dec.bmps.map { b ->
                try {
                    com.example.thundercam20.analysis.FastGray.meanAbsLaplacian(
                        com.example.thundercam20.analysis.FastGray.of(b, 320)
                    )
                } catch (_: Throwable) { 0.0 }
            }
            val bestScore = scores.maxOrNull() ?: 0.0
            val sharpKeep = dec.bmps.indices.filter { scores[it] >= bestScore * QC_SHARP }
            if (sharpKeep.size < 2) {
                Log.i(TAG, "zoom: only ${sharpKeep.size} sharp frame(s), single path")
                throw Exception("zoom few good frames")
            }
            // Гироскоп: кадр, снятый в рывок, смазан даже если лапласиан высокий
            // (контраст от смаза тоже даёт «резкость»). Считаем интеграл |ω| вокруг
            // момента спуска и выкидываем кадры, где мотало заметно сильнее лучшего.
            val motions = sharpKeep.map { i ->
                val t = frameTimes.getOrElse(dec.srcIdx.getOrElse(i) { 0 }) { 0L }
                if (t <= 0L) 0.0 else try {
                    ThmcGyro.motionBetween(t - 120_000_000L, t + 300_000_000L)
                } catch (_: Throwable) { 0.0 }
            }
            val minMotion = motions.minOrNull() ?: 0.0
            val calmLocal = sharpKeep.indices.filter { motions[it] <= minMotion * 2.5 + 0.02 }
            val useLocal = if (calmLocal.size >= 2) calmLocal else sharpKeep.indices.toList()
            val useIdx = useLocal.map { sharpKeep[it] }
            Log.i(
                TAG,
                "gyro keep ${useIdx.size}/${sharpKeep.size} " +
                    "motion=" + motions.joinToString(",") { "%.3f".format(it) }
            )
            val goodBmps = useIdx.map { dec.bmps[it] }
            val goodSrcIdx = useIdx.map { dec.srcIdx.getOrElse(it) { 0 } }
            val refLocal = useLocal.maxByOrNull { scores[useIdx[it]] } ?: 0
            val refSrcBytes = frames.getOrNull(goodSrcIdx.getOrNull(refLocal) ?: 0) ?: frames[0]

            // --- целочисленное равнение общим пересечением ---
            val aligned = ThmcAlignV2.alignEx(goodBmps, refLocal).frames.toMutableList()
            if (aligned.size < 2) throw Exception("zoom align short")

            // --- субпиксельная доводка + варп ---
            val refGray = gray160(aligned[refLocal])
            var warped = 0
            for (i in aligned.indices) {
                if (i == refLocal) continue
                try {
                    val (dx, dy) = refineShift(refGray, gray160(aligned[i]))
                    if (abs(dx) > 0.02f || abs(dy) > 0.02f) {
                        val w = warp(aligned[i], -dx, -dy)
                        if (w !== aligned[i]) {
                            ThmcBitmaps.recycleQuietly(aligned[i])
                            aligned[i] = w
                            warped++
                        }
                    }
                } catch (_: Throwable) {}
            }

            // --- робастный стек ---
            val iso = try { ThmcP0.readIso(refSrcBytes) } catch (_: Throwable) { 0 }
            val noise = ThmcMerge.noiseForIso(iso).coerceAtLeast(6f)
            val stacked = ThmcMerge.mergeWiener(aligned, refLocal, noise = noise, rejectThr = 24)
            merged = stacked
            for (b in aligned) if (b !== stacked) ThmcBitmaps.recycleQuietly(b)
            for (b in dec.bmps) if (b !in goodBmps && b !== stacked) ThmcBitmaps.recycleQuietly(b)

            // --- штатный финал: зум-кроп + SR + P0 + EXIF ---
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
                stacked = true,
            )
            com.example.thundercam20.imageprocessing.SuperResZoom.powerful = cc.thmcSrPowerful
            com.example.thundercam20.imageprocessing.SuperResZoom.blendPercent = cc.thmcSrBlend
            val out = try {
                ThmcP0.applyFromBitmap(
                    merged, quality, cc.thmcP0Strength / 100f, params,
                    refSrcBytes, imageCapturer.mActivity.applicationContext, cc.thmcExtraZoom
                )
            } catch (_: Throwable) { null }
            if (out != null) {
                merged = null // владение ушло в applyFromBitmap
                ThmcSave.saveFinal(imageCapturer, out, seq)
            } else {
                // P0 не смог — сохраняем опорный кадр как есть (стек вернём в finally).
                ThmcSave.saveFinal(imageCapturer, refSrcBytes, seq)
            }
            val sec = "%.1f".format((System.nanoTime() - t0) / 1_000_000_000.0)
            toast(
                imageCapturer,
                "Зум ×${"%.1f".format(totalZoom)}: стек ${aligned.size} кадров" +
                    (if (warped > 0) " (субпиксель)" else "") + " за $sec с"
            )
        } catch (t: Throwable) {
            Log.w(TAG, "zoom fallback to single", t)
            abort(imageCapturer)
        } finally {
            merged?.let { ThmcBitmaps.recycleQuietly(it) }
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

    // --- субпиксельное равнение -------------------------------------------------

    private fun gray160(b: Bitmap): com.example.thundercam20.analysis.FastGray.Gray =
        com.example.thundercam20.analysis.FastGray.of(b, 160)

    /**
     * Остаточный сдвиг cur относительно ref: cur[x+dx, y+dy] ≈ ref[x, y].
     * Двухпроходный поиск: ±2 px шагом 0.5, затем ±0.5 шагом 0.125.
     * Билинейная выборка + SAD по центральной части, чтобы края не влияли.
     */
    private fun refineShift(
        ref: com.example.thundercam20.analysis.FastGray.Gray,
        cur: com.example.thundercam20.analysis.FastGray.Gray,
    ): Pair<Float, Float> {
        val rm = mean(ref.y); val cm = mean(cur.y)
        fun sad(dx: Float, dy: Float): Double {
            var s = 0.0; var n = 0
            var y = (ref.h * 0.2f).toInt()
            val yEnd = (ref.h * 0.8f).toInt()
            while (y < yEnd) {
                var x = (ref.w * 0.2f).toInt()
                val xEnd = (ref.w * 0.8f).toInt()
                while (x < xEnd) {
                    val v = sample(cur, x + dx, y + dy) - cm
                    s += abs((ref.y[y * ref.w + x] - rm) - v).toDouble()
                    n++
                    x += 2
                }
                y += 2
            }
            return if (n == 0) Double.MAX_VALUE else s / n
        }
        var bx = 0f; var by = 0f
        var best = Double.MAX_VALUE
        var d = -2.0f
        while (d <= 2.0f) {
            var e = -2.0f
            while (e <= 2.0f) {
                val s = sad(d, e)
                if (s < best) { best = s; bx = d; by = e }
                e += 0.5f
            }
            d += 0.5f
        }
        val cxf = bx; val cyf = by
        d = cxf - 0.5f
        while (d <= cxf + 0.5f) {
            var e = cyf - 0.5f
            while (e <= cyf + 0.5f) {
                val s = sad(d, e)
                if (s < best) { best = s; bx = d; by = e }
                e += 0.125f
            }
            d += 0.125f
        }
        return bx to by
    }

    private fun sample(
        g: com.example.thundercam20.analysis.FastGray.Gray,
        x: Float, y: Float,
    ): Float {
        val x0 = kotlin.math.floor(x).toInt()
        val y0 = kotlin.math.floor(y).toInt()
        if (x0 < 0 || y0 < 0 || x0 + 1 >= g.w || y0 + 1 >= g.h) return 0f
        val fx = x - x0; val fy = y - y0
        val a = g.y[y0 * g.w + x0]; val b = g.y[y0 * g.w + x0 + 1]
        val c = g.y[(y0 + 1) * g.w + x0]; val dd = g.y[(y0 + 1) * g.w + x0 + 1]
        return a * (1 - fx) * (1 - fy) + b * fx * (1 - fy) + c * (1 - fx) * fy + dd * fx * fy
    }

    private fun mean(a: FloatArray): Float {
        var s = 0.0
        for (v in a) s += v
        return if (a.isEmpty()) 0f else (s / a.size).toFloat()
    }

    /** Сдвиг кадра на (dx,dy) билинейно. Возвращает новый битмап (или исходный). */
    private fun warp(src: Bitmap, dx: Float, dy: Float): Bitmap {
        return try {
            val m = Matrix()
            m.postTranslate(dx, dy)
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        } catch (_: Throwable) { src }
    }
}
