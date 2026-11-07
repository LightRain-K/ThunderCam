package app.grapheneos.camera.thmc

import android.content.ContentValues
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import app.grapheneos.camera.CamConfig
import app.grapheneos.camera.R
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RAW-серия (DNG): 3 кадра −1/0/+1 для стекинга на десктопе (Lightroom/Photoshop).
 *
 * Почему отдельным трактом: CameraX не отдаёт RAW_SENSOR вообще, а HAL держит
 * камеру только для одного владельца — поэтому на время серии отвязываем CameraX
 * (unbindAll), снимаем напрямую через Camera2 и перепривязываем обратно.
 * Pova 5 сюда не попадёт (нет RAW capability — строка в настройках скрыта),
 * S25U отдаёт биннированный RAW.
 *
 * Внутри — только JPEG-домен нам не нужен: демозаик/стек отданы десктопу,
 * телефон пишет 3 честных DNG. Демозаик в приложении — следующим этапом.
 * Только с фонового потока; UI-колбэки — через runOnUiThread вызывателя.
 */
object ThmcRawBurst {
    private const val TAG = "ThmcRaw"

    /** Видимость строки в настройках: телефон реально отдаёт RAW. */
    fun isSupported(activity: AppCompatActivity): Boolean {
        return try {
            ThmcRawProbe.probeManager(activity) != null
        } catch (_: Throwable) { false }
    }

    /** Запуск серии с прогресс-диалогом. Всё внутри, вызывателю — один вызов. */
    fun capture(activity: AppCompatActivity, camConfig: CamConfig) {
        val cancelled = AtomicBoolean(false)
        val dialog = try {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.thmc_raw_title)
                .setMessage(activity.getString(R.string.thmc_raw_working, 0))
                .setNegativeButton(android.R.string.cancel) { _, _ -> cancelled.set(true) }
                .setCancelable(false)
                .show()
        } catch (_: Throwable) { null }
        fun progress(fmt: Int, arg: Any) {
            try {
                activity.runOnUiThread {
                    try {
                        dialog?.setMessage(activity.getString(fmt, arg))
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        }
        fun done(toast: Int, arg: Any? = null) {
            try {
                activity.runOnUiThread {
                    try { dialog?.dismiss() } catch (_: Throwable) {}
                    try {
                        android.widget.Toast.makeText(
                            activity,
                            if (arg != null) activity.getString(toast, arg) else activity.getString(toast),
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        }
        Thread {
            var count = 0
            var err: Int? = null
            try {
                count = runBurst(activity, camConfig, cancelled) { pct ->
                    progress(R.string.thmc_raw_working, pct)
                }
                if (count <= 0 && !cancelled.get()) err = R.string.thmc_raw_fail
            } catch (t: Throwable) {
                Log.w(TAG, "raw burst failed", t)
                if (!cancelled.get()) err = R.string.thmc_raw_fail
            } finally {
                // Камеру возвращаем всегда, иначе видоискатель мёртв.
                try {
                    activity.runOnUiThread {
                        try { camConfig.startCamera(true) } catch (_: Throwable) {
                            try { camConfig.initializeCamera(true) } catch (_: Throwable) {}
                        }
                    }
                } catch (_: Throwable) {}
            }
            if (cancelled.get()) {
                done(R.string.thmc_raw_cancelled)
            } else if (err != null) {
                done(err)
            } else {
                done(R.string.thmc_raw_done, count)
            }
        }.start()
    }

    /** Серия −1/0/+1 в DNG. Возвращает число сохранённых. Только с фона. */
    fun runBurst(
        activity: AppCompatActivity,
        camConfig: CamConfig,
        cancelled: AtomicBoolean,
        onPct: (Int) -> Unit,
    ): Int {
        val probe = ThmcRawProbe.probeManager(activity) ?: throw Exception("no raw")
        // Отвязываем CameraX — HAL один на всех.
        try { camConfig.cameraProvider?.unbindAll() } catch (t: Throwable) {
            throw Exception("unbind", t)
        }
        val manager = activity.applicationContext
            .getSystemService(android.content.Context.CAMERA_SERVICE) as CameraManager
        val chars = manager.getCameraCharacteristics(probe.cameraId)
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var reader: ImageReader? = null
        var camThread: HandlerThread? = null
        try {
            camThread = HandlerThread("ThmcRaw").apply { start() }
            val bg = Handler(camThread.looper)
            // Открытие камеры.
            val openLatch = CountDownLatch(1)
            var openErr: Exception? = null
            manager.openCamera(probe.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    device = d
                    openLatch.countDown()
                }

                override fun onDisconnected(d: CameraDevice) {
                    try { d.close() } catch (_: Throwable) {}
                    openLatch.countDown()
                }

                override fun onError(d: CameraDevice, error: Int) {
                    try { d.close() } catch (_: Throwable) {}
                    openErr = Exception("open $error")
                    openLatch.countDown()
                }
            }, bg)
            if (!openLatch.await(6, TimeUnit.SECONDS) || device == null) {
                throw openErr ?: Exception("open timeout")
            }
            val dev = device!!

            // RAW-выход.
            reader = ImageReader.newInstance(probe.bestW, probe.bestH, android.graphics.ImageFormat.RAW_SENSOR, 3)
            val images = LinkedBlockingQueue<Image>(4)
            reader.setOnImageAvailableListener({ r ->
                try {
                    val img = r.acquireNextImage() ?: return@setOnImageAvailableListener
                    if (!images.offer(img)) try { img.close() } catch (_: Throwable) {}
                } catch (_: Throwable) {}
            }, bg)

            // Сессия с одним выходом.
            val sessLatch = CountDownLatch(1)
            var sessErr: Exception? = null
            var sess: CameraCaptureSession? = null
            dev.createCaptureSession(
                listOf(reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        sess = s
                        sessLatch.countDown()
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        sessErr = Exception("session")
                        sessLatch.countDown()
                    }
                },
                bg
            )
            if (!sessLatch.await(6, TimeUnit.SECONDS) || sess == null) {
                throw sessErr ?: Exception("session timeout")
            }
            session = sess!!

            // Быстрый AF-лок (не блокируем серию насмерть при промахе).
            try {
                val af = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                }.build()
                val afLatch = CountDownLatch(1)
                session.capture(af, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        s: CameraCaptureSession, req: CaptureRequest, res: TotalCaptureResult,
                    ) {
                        afLatch.countDown()
                    }
                }, bg)
                afLatch.await(1500, TimeUnit.MILLISECONDS)
                val idle = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                }.build()
                try { session.capture(idle, null, bg) } catch (_: Throwable) {}
            } catch (_: Throwable) {}
            // Сливаем AF-кадр из очереди, чтобы не спутать с серией.
            try {
                while (true) {
                    val stray = images.poll(300, TimeUnit.MILLISECONDS) ?: break
                    try { stray.close() } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}

            // Брекетинг −1/0/+1: время кадра + оседание AE.
            val brackets = intArrayOf(-1, 0, 1)
            var saved = 0
            for ((idx, ev) in brackets.withIndex()) {
                if (cancelled.get()) break
                onPct(idx * 100 / brackets.size)
                val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev)
                }.build()
                val resLatch = CountDownLatch(1)
                var result: TotalCaptureResult? = null
                var failed = false
                session.capture(req, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult,
                    ) {
                        result = res
                        resLatch.countDown()
                    }

                    override fun onCaptureFailed(
                        s: CameraCaptureSession, r: CaptureRequest, failure: android.hardware.camera2.CaptureFailure,
                    ) {
                        failed = true
                        resLatch.countDown()
                    }
                }, bg)
                if (!resLatch.await(8, TimeUnit.SECONDS) || failed || result == null) {
                    try { images.poll(100, TimeUnit.MILLISECONDS)?.close() } catch (_: Throwable) {}
                    continue
                }
                val img = images.poll(8, TimeUnit.SECONDS)
                if (img == null) continue
                try {
                    // Даём AE осесть до следующего плеча.
                    Thread.sleep(350)
                    val dng = DngCreator(chars, result!!).let { creator ->
                        try {
                            val baos = ByteArrayOutputStream(img.width * img.height * 2 + 1024 * 1024)
                            creator.writeImage(baos, img)
                            baos.toByteArray()
                        } finally {
                            try { creator.close() } catch (_: Throwable) {}
                        }
                    }
                    if (saveDng(activity, dng, idx)) saved++
                } finally {
                    try { img.close() } catch (_: Throwable) {}
                }
            }
            onPct(100)
            return saved
        } finally {
            try { session?.close() } catch (_: Throwable) {}
            try { device?.close() } catch (_: Throwable) {}
            try { reader?.close() } catch (_: Throwable) {}
            try { camThread?.quitSafely() } catch (_: Throwable) {}
        }
    }

    /** Сохранение DNG в MediaStore рядом с фото. */
    fun saveDng(activity: AppCompatActivity, bytes: ByteArray, idx: Int): Boolean {
        return try {
            val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val fileName = app.grapheneos.camera.IMAGE_NAME_PREFIX + dateStr + "_RAW$idx.dng"
            val rel = app.grapheneos.camera.capturer.DEFAULT_MEDIA_STORE_CAPTURE_PATH
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
                put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val coll = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val cr = activity.applicationContext.contentResolver
            val uri = cr.insert(coll, cv) ?: return false
            try {
                cr.openOutputStream(uri)!!.use { out -> out.write(bytes) }
                cv.clear()
                cv.put(MediaStore.MediaColumns.IS_PENDING, 0)
                cr.update(uri, cv, null, null)
            } catch (e: Exception) {
                try { cr.delete(uri, null, null) } catch (_: Throwable) {}
                return false
            }
            try {
                val item = app.grapheneos.camera.CapturedItem(app.grapheneos.camera.ITEM_TYPE_IMAGE, dateStr, uri)
                val main = activity as? app.grapheneos.camera.ui.activities.MainActivity
                main?.camConfig?.updateLastCapturedItem(item)
            } catch (_: Throwable) {}
            true
        } catch (_: Throwable) { false }
    }
}
