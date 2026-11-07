package app.grapheneos.camera.capturer

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import app.grapheneos.camera.App
import app.grapheneos.camera.CapturedItem
import app.grapheneos.camera.R
import app.grapheneos.camera.ui.activities.MainActivity
import app.grapheneos.camera.ui.activities.SecureMainActivity
import app.grapheneos.camera.ui.showIgnoringShortEdgeMode
import app.grapheneos.camera.util.printStackTraceToString
import com.google.android.material.dialog.MaterialAlertDialogBuilder

private const val imageFileFormat = ".jpg"

class ImageCapturer(val mActivity: MainActivity) {
    val camConfig = mActivity.camConfig

    // ThunderCam очередь без блокировки затвора: кадры обрабатываются фоном
    // (imageWriterExecutor), затвор всегда живой. Кап — защита памяти бюджетника:
    // два полных P0-пайплайна параллельно это предел, третий тап ждёт с тостом.
    private val pending = java.util.concurrent.atomic.AtomicInteger(0)
    private val seqGen = java.util.concurrent.atomic.AtomicLong(0)
    private val activeSavers =
        java.util.Collections.synchronizedSet(HashSet<ImageSaver>())
    @Volatile private var lastThumbSeq = 0L
    @Volatile private var queuedTap = false

    val isTakingPicture: Boolean
        get() = activeSavers.isNotEmpty()

    /** Порядковый номер кадра (для миниатюр по порядку очереди). */
    fun nextSeq(): Long = seqGen.incrementAndGet()

    /** Кадр забран в работу: счётчик + бейдж. Только с любого потока. */
    fun onShotDispatched() {
        pending.incrementAndGet()
        refreshBadge()
    }

    private fun onShotDone() {
        pending.updateAndGet { v -> maxOf(0, v - 1) }
        refreshBadge()
    }

    /** Кадр умер без сохранения (отмена HDR, фолбэк): только счётчик. */
    fun onShotAborted() = onShotDone()

    private fun refreshBadge() {
        val n = pending.get()
        try {
            mActivity.runOnUiThread { mActivity.updateProcessingBadge(n) }
        } catch (_: Throwable) {}
    }

    // ThunderCam HDR: обход ветки HDR для стокового фолбэка (ThmcHdr достреливает одиночным)
    var hdrBypass = false

    // ThunderCam авто-EV: фон для bias-settle, флаг дебаунса, индекс для возврата.
    private val evExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var evSettleInFlight = false
    @Volatile private var evRestoreIndex: Int? = null
    @Volatile private var manualActive = false

    private fun restoreAutoEv() {
        val prev = evRestoreIndex
        evRestoreIndex = null
        if (prev != null) {
            try { app.grapheneos.camera.thmc.ThmcAutoEv.restore(camConfig.camera, prev) } catch (_: Throwable) {}
        }
        if (manualActive) {
            manualActive = false
            try { app.grapheneos.camera.thmc.ThmcManualEv.clear(camConfig.camera) } catch (_: Throwable) {}
        }
    }

    private fun fadeCaptureButton() {
        mActivity.captureButton.isEnabled = false

        val animation: Animation = AlphaAnimation(mActivity.captureButton.alpha, 0.6f)
        animation.duration = 200
        animation.interpolator = LinearInterpolator()
        animation.fillAfter = true

        mActivity.captureButton.startAnimation(animation)
    }

    private fun unfadeCaptureButton() {
        mActivity.captureButton.isEnabled = true

        val animation: Animation = AlphaAnimation(mActivity.captureButton.alpha, 1f)
        animation.duration = 200
        animation.interpolator = LinearInterpolator()
        animation.fillAfter = true

        mActivity.captureButton.startAnimation(animation)
    }

    @SuppressLint("RestrictedApi")
    fun takePicture() {
        if (camConfig.camera == null) {
            return
        }

        if (!camConfig.canTakePicture) {
            mActivity.showMessage(R.string.unsupported_taking_picture_while_recording)
            return
        }

        // Тап во время EV-отстоя не теряем, а ставим в одразуместную очередь:
        // человеческий темп тапов она покрывает, затвор не блокируется.
        if (evSettleInFlight) {
            queuedTap = true
            return
        }

        // Переполнение очереди — честный тост вместо молчаливого игнора.
        if (pending.get() >= MAX_IN_FLIGHT) {
            mActivity.showMessage(R.string.thmc_queue_busy)
            return
        }

        // ThunderCam гейт серий по зуму: на большом зуме сдвиг от дрожи больше окна
        // поиска — кадры не сводятся. Потолок зависит от телефона (OIS 12×, иначе 6×).
        // Запрет = честный одиночный с тостом, а не каша из мержа.
        val totalZoom =
            (camConfig.zoomState?.zoomRatio ?: 1f) * camConfig.thmcExtraZoom
        // Мастер Thunder: выкл = сток без вычислений (все серии и P0 пропускаются ниже).
        val thunderOn = try { camConfig.thmc.thunder } catch (_: Throwable) { true }
        val seriesMode = thunderOn && !hdrBypass && !camConfig.isVideoMode && !camConfig.isQRMode &&
            camConfig.flashMode == ImageCapture.FLASH_MODE_OFF
        val nightWanted = seriesMode && camConfig.thmcNightAuto &&
            app.grapheneos.camera.thmc.ThmcNight.shouldRunNight(app.grapheneos.camera.thmc.ThmcTrackState.lastPreviewL)
        val hdrWanted = seriesMode && camConfig.thmcHdr
        val zoomOk = app.grapheneos.camera.thmc.ThmcStab.allowSeries(totalZoom, camConfig.camera)
        if ((nightWanted || hdrWanted) && !zoomOk) {
            try {
                val label = "×%.0f".format(totalZoom)
                mActivity.runOnUiThread {
                    android.widget.Toast.makeText(
                        mActivity,
                        mActivity.getString(R.string.thmc_series_zoom_single, label),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (_: Throwable) {}
        }

        // ThunderCam ночь-авто: тёмная сцена снимает серией коротких (стек давит шум
        // и шевеленку). Приоритет над HDR: брекетинг в темноте даёт смаз. База не тронута.
        if (nightWanted && zoomOk) {
            if (!app.grapheneos.camera.thmc.ThmcNight.isRunning()) {
                if (app.grapheneos.camera.thmc.ThmcNight.capture(this)) {
                    onShotDispatched()
                    return
                }
            }
            // Серия уже идёт — дальше сток (HDR-ветка ниже сама отскочит от running).
        }

        // ThunderCam HDR (тумблер): серия с брекетингом вместо одиночного. Вспышка/видео/QR — сток.
        // Серия уже идёт — повторный тап НЕ отменяет её (потеря кадра), а снимает одиночным.
        if (hdrWanted && zoomOk &&
            !app.grapheneos.camera.thmc.ThmcNight.isRunning()
        ) {
            if (!app.grapheneos.camera.thmc.ThmcHdr.isRunning()) {
                if (app.grapheneos.camera.thmc.ThmcHdr.capture(this)) {
                    onShotDispatched()
                    return
                }
            } else {
                try {
                    mActivity.runOnUiThread {
                        android.widget.Toast.makeText(
                            mActivity, R.string.thmc_hdr_busy_stock, android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                } catch (_: Throwable) {}
            }
        }
        // HDR включён, но вспышка его молча отключала — говорим прямо.
        if (!hdrBypass && camConfig.thmcHdr && !camConfig.isVideoMode && !camConfig.isQRMode &&
            camConfig.flashMode != ImageCapture.FLASH_MODE_OFF
        ) {
            try {
                mActivity.runOnUiThread {
                    android.widget.Toast.makeText(
                        mActivity, "Вспышка: HDR выкл, сток-кадр", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (_: Throwable) {}
        }

        val preview = mActivity.imagePreview

        val imageCapture = camConfig.imageCapture!!

        // ThunderCam авто-EV: давим пересвет HAL ДО кадра (только вниз, с возвратом).
        // Bias на повторяющемся запросе + отстой, иначе ISO 4000 при среднем свете.
        // HDR и вспышка управляют EV сами — тут только одиночный сток без вспышки.
        val wantAutoEv = camConfig.thmcAutoEv && !camConfig.isVideoMode && !camConfig.isQRMode &&
            camConfig.flashMode == ImageCapture.FLASH_MODE_OFF &&
            (hdrBypass || !camConfig.thmcHdr)
        // Ручная выдержка вместо HAL AE: пробуем первой, EV-bias — запасной.
        // Условия те же + вспышка выкл (ей нужен AE) и зум ≤1.5 (длинная выдержка).
        val wantManual = camConfig.thmcManualEv && !camConfig.isVideoMode && !camConfig.isQRMode &&
            camConfig.flashMode == ImageCapture.FLASH_MODE_OFF &&
            (hdrBypass || !camConfig.thmcHdr)
        val previewL = app.grapheneos.camera.thmc.ThmcTrackState.lastPreviewL
        val brightFrac = try { app.grapheneos.camera.thmc.ThmcTrackState.lastBrightFrac } catch (_: Throwable) { 0f }
        // Превью момента кадра — для спаривания калибровки (P0 видит уже другое).
        try {
            mActivity.getSharedPreferences("commons", android.content.Context.MODE_PRIVATE).edit()
                .putFloat("thmc_cap_pl", previewL)
                .putLong("thmc_cap_ts", System.currentTimeMillis())
                .apply()
        } catch (_: Throwable) {}
        val needWork = (wantAutoEv && app.grapheneos.camera.thmc.ThmcAutoEv.biasSteps(previewL, brightFrac) != 0) ||
            (wantManual && previewL in 0.05f..0.60f) // bright day = HAL, без settle-лагов
        if (needWork) {
            evSettleInFlight = true
            fadeCaptureButton()
            evExecutor.execute {
                try {
                    val prefs = app.grapheneos.camera.thmc.ThmcManualEv.prefsOf(mActivity)
                    val extra = try { prefs.getInt("thmc_ev_extra", 0) } catch (_: Throwable) { 0 }
                    var manualOk = false
                    var appliedAny = false
                    if (wantManual) {
                        try {
                            val isFront = camConfig.lensFacing == CameraSelector.LENS_FACING_FRONT
                            val p = app.grapheneos.camera.thmc.ThmcManualEv.plan(
                                camConfig.camera, previewL, isFront, camConfig.thmcExtraZoom, prefs
                            )
                            try {
                                android.util.Log.i("ThmcExp", "L=${"%.2f".format(previewL)} extra=$extra plan=${p?.let { "${it.shutterNs / 1_000_000}ms/${it.iso}${if (!it.confident) "?" else ""}" } ?: "null"}")
                            } catch (_: Throwable) {}
                            if (p != null) {
                                manualOk = app.grapheneos.camera.thmc.ThmcManualEv.applyPlan(camConfig.camera, p)
                                appliedAny = manualOk
                                // Неуверенный план (прыжок калибровки): свой замер из
                                // превью-потока и точная докрутка. Не сошёлся — HAL.
                                if (manualOk && !p.confident) {
                                    val c = app.grapheneos.camera.thmc.ThmcManualEv.meterCorrect(camConfig.camera, p, isFront)
                                    if (c != null) {
                                        manualOk = app.grapheneos.camera.thmc.ThmcManualEv.applyPlan(camConfig.camera, c, 250L)
                                    } else {
                                        manualOk = false
                                    }
                                }
                            }
                        } catch (_: Throwable) { manualOk = false }
                    }
                    manualActive = manualOk
                    if (!manualOk) {
                        // Грубая ручная осталась висеть — снять перед HAL/EV кадром.
                        if (appliedAny) {
                            try { app.grapheneos.camera.thmc.ThmcManualEv.clear(camConfig.camera) } catch (_: Throwable) {}
                            appliedAny = false
                        }
                        val prev = if (wantAutoEv) app.grapheneos.camera.thmc.ThmcAutoEv.apply(camConfig.camera, previewL, extra, brightFrac) else null
                        evRestoreIndex = prev
                        // Запоминаем суммарный bias для серво (0 = без bias).
                        try {
                            var totalBias = 0
                            if (wantAutoEv && prev != null) {
                                val st = camConfig.camera?.cameraInfo?.exposureState
                                if (st != null) totalBias = st.exposureCompensationIndex - prev
                            }
                            prefs.edit().putInt("thmc_last_bias", totalBias).apply()
                        } catch (_: Throwable) {}
                    } else {
                        evRestoreIndex = null
                        try { prefs.edit().putInt("thmc_last_bias", 0).apply() } catch (_: Throwable) {}
                    }
                    mActivity.runOnUiThread {
                        evSettleInFlight = false
                        if (isTakingPicture) {
                            restoreAutoEv()
                            return@runOnUiThread
                        }
                        try {
                            dispatchStockCapture(imageCapture, preview, skipFade = true)
                        } catch (_: Throwable) {
                            restoreAutoEv()
                            unfadeCaptureButton()
                        }
                        // Отложенный тап (пришёл во время отстоя) — достреливаем сразу.
                        if (queuedTap) {
                            queuedTap = false
                            try { takePicture() } catch (_: Throwable) {}
                        }
                    }
                } catch (_: Throwable) {
                    try { restoreAutoEv() } catch (_: Throwable) {}
                    evRestoreIndex = null
                    mActivity.runOnUiThread {
                        evSettleInFlight = false
                        unfadeCaptureButton()
                    }
                }
            }
            return
        }

        try {
            android.util.Log.i("ThmcExp", "direct L=${"%.2f".format(previewL)} autoEv=$wantAutoEv manual=$wantManual")
        } catch (_: Throwable) {}
        try {
            mActivity.getSharedPreferences("commons", android.content.Context.MODE_PRIVATE).edit()
                .putInt("thmc_last_bias", 0).apply()
        } catch (_: Throwable) {}
        dispatchStockCapture(imageCapture, preview, skipFade = false)
    }

    /** Собственно одиночный стоковый кадр (после авто-EV или без него). Только с main. */
    private fun dispatchStockCapture(
        imageCapture: ImageCapture,
        preview: android.view.View,
        skipFade: Boolean,
    ) {
        val imageMetadata = ImageCapture.Metadata()
        imageMetadata.isReversedHorizontal = camConfig.lensFacing == CameraSelector.LENS_FACING_FRONT
                && camConfig.saveImageAsPreviewed

        if (camConfig.requireLocation) {
            val location = (mActivity.applicationContext as App).getLocation()
            if (location == null) {
                mActivity.showMessage(R.string.location_unavailable)
            } else {
                imageMetadata.location = location
            }
        }

        val imageSaver = ImageSaver(
            this,
            mActivity.applicationContext,
            imageCapture.jpegQuality,
            camConfig.storageLocation,
            imageFileFormat,
            imageMetadata,
            camConfig.removeExifAfterCapture,
            targetThumbnailWidth = preview.width,
            targetThumbnailHeight = preview.height,
            seq = nextSeq(),
        )

        activeSavers.add(imageSaver)
        onShotDispatched()

        imageCapture.takePicture(ImageSaver.imageCaptureCallbackExecutor, imageSaver)
        if (!skipFade) fadeCaptureButton()
    }

    fun cancelPendingCaptureRequest() {
        restoreAutoEv()
        queuedTap = false
        try { app.grapheneos.camera.thmc.ThmcHdr.cancel() } catch (_: Throwable) {}
        try { app.grapheneos.camera.thmc.ThmcNight.cancel() } catch (_: Throwable) {}
        if (isTakingPicture) {
            val copy: List<ImageSaver>
            synchronized(activeSavers) {
                copy = activeSavers.toList()
                activeSavers.clear()
            }
            for (s in copy) {
                try { s.cancelCaptureRequest() } catch (_: Throwable) {}
            }
            pending.set(0)
            refreshBadge()
            unfadeCaptureButton()
        }
    }

    fun onCaptureSuccess(saver: ImageSaver? = null) {
        if (saver != null) activeSavers.remove(saver)
        restoreAutoEv()
        unfadeCaptureButton()

        camConfig.mPlayer.playShutterSound()
        camConfig.snapPreview()

        mActivity.previewLoader.visibility = View.VISIBLE
        if (camConfig.selfIlluminate) {

            val animation: Animation = AlphaAnimation(0.8f, 0f)
            animation.duration = 200
            animation.interpolator = LinearInterpolator()
            animation.fillAfter = true

            mActivity.mainOverlay.setImageResource(android.R.color.white)

            animation.setAnimationListener(
                object : Animation.AnimationListener {
                    override fun onAnimationStart(p0: Animation?) {
                        mActivity.mainOverlay.visibility = View.VISIBLE
                    }

                    override fun onAnimationEnd(p0: Animation?) {
                        mActivity.mainOverlay.visibility = View.INVISIBLE
                        mActivity.mainOverlay.setImageResource(android.R.color.transparent)
                    }

                    override fun onAnimationRepeat(p0: Animation?) {}

                }
            )

            mActivity.mainOverlay.startAnimation(animation)
        }
    }

    fun onCaptureError(exception: ImageCaptureException, saver: ImageSaver? = null) {
        Log.e(TAG, "onCaptureError", exception)
        if (saver != null) activeSavers.remove(saver)
        onShotDone()
        restoreAutoEv()
        unfadeCaptureButton()
        mActivity.previewLoader.visibility = View.GONE

        if (mActivity.isStarted) {
            val msg = mActivity.getString(R.string.unable_to_capture_image_verbose, exception.imageCaptureError)
            showErrorDialog(msg, exception)
        }
    }

    fun onImageSaverSuccess(item: CapturedItem) {
        onShotDone()
        // Счетчик кадров для онбординга: калибровка/бенч только после 5-го фото, не на старте.
        try {
            val prefs = mActivity.getSharedPreferences("commons", android.content.Context.MODE_PRIVATE)
            prefs.edit().putInt("thmc_shots_taken", prefs.getInt("thmc_shots_taken", 0) + 1).apply()
        } catch (_: Throwable) {}
        camConfig.updateLastCapturedItem(item)

        if (mActivity is SecureMainActivity) {
            mActivity.capturedItems.add(item)
        }
        // Бейдж: показать что определила авто-сцена на этом кадре.
        try { mActivity.refreshThmcModeBadge() } catch (_: Throwable) {}

        // ThunderCam: тихий вход в «Улучшить» — снакбар с кнопкой прямо с видоискателя.
        // Иначе Enhance не найти (только через ⋮ в галерее). Макс 5 раз навсегда — дальше не ноем.
        if (mActivity.javaClass == MainActivity::class.java && item.type == app.grapheneos.camera.ITEM_TYPE_IMAGE) {
            try {
                val prefs = mActivity.getSharedPreferences("commons", android.content.Context.MODE_PRIVATE)
                val shown = prefs.getInt("thmc_enhance_hint_count", 0)
                if (shown < 5) {
                    prefs.edit().putInt("thmc_enhance_hint_count", shown + 1).apply()
                    mActivity.runOnUiThread {
                        try {
                            com.google.android.material.snackbar.Snackbar.make(
                                mActivity.rootView,
                                mActivity.getString(app.grapheneos.camera.R.string.thmc_enhance_snack),
                                com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                            ).setAction(mActivity.getString(app.grapheneos.camera.R.string.thmc_enhance_open)) {
                                try {
                                    app.grapheneos.camera.ui.activities.ThmcEnhanceActivity.open(mActivity, item)
                                } catch (_: Throwable) {}
                            }.show()
                        } catch (_: Throwable) {}
                    }
                }
            } catch (_: Throwable) {}
        }

        // ThunderCam авто-апскейл в облаке: если URL задан и тумблер вкл — отправляем
        // только что сохранённый кадр, показываем прогресс, по ответу перезаписываем оригинал.
        val url = camConfig.thmcCloudUrl
        if (url.isNotBlank() && camConfig.thmcCloudAuto && item.type == app.grapheneos.camera.ITEM_TYPE_IMAGE) {
            val bytes = app.grapheneos.camera.thmc.ThmcEnhance.readBytes(mActivity, item.uri)
            if (bytes != null) {
                mActivity.runOnUiThread {
                    try {
                        mActivity.showMessage(mActivity.getString(R.string.thmc_cloud_working))
                    } catch (_: Throwable) {}
                }
                Thread {
                    val result = app.grapheneos.camera.thmc.ThmcCloud.enhance(mActivity, url, bytes) { pct ->
                        mActivity.runOnUiThread {
                            try {
                                mActivity.showMessage("${mActivity.getString(R.string.thmc_cloud_working)} $pct%")
                            } catch (_: Throwable) {}
                        }
                    }
                    if (result != null) {
                        val ok = app.grapheneos.camera.thmc.ThmcCloud.overwrite(mActivity, item.uri, result)
                        if (ok) {
                            mActivity.runOnUiThread {
                                try { mActivity.showMessage(mActivity.getString(R.string.thmc_cloud_done)) } catch (_: Throwable) {}
                            }
                        } else {
                            mActivity.runOnUiThread {
                                try { mActivity.showMessage(mActivity.getString(R.string.thmc_cloud_fail)) } catch (_: Throwable) {}
                            }
                        }
                    } else {
                        mActivity.runOnUiThread {
                            try { mActivity.showMessage(mActivity.getString(R.string.thmc_cloud_fail)) } catch (_: Throwable) {}
                        }
                    }
                }.start()
            }
        }
    }

    fun onStorageLocationNotFound() {
        camConfig.onStorageLocationNotFound()
    }

    fun onImageSaverError(exception: ImageSaverException, skipErrorDialog: Boolean) {
        Log.e(TAG, "onImageSaverError", exception)
        onShotDone()
        if (pending.get() == 0) mActivity.previewLoader.visibility = View.GONE

        if (!mActivity.isStarted) {
            val channelId = "image_saver_error"
            val channel = NotificationChannel(channelId, mActivity.getString(R.string.unable_to_save_image),
                NotificationManager.IMPORTANCE_HIGH)

            val notif = Notification.Builder(mActivity, channelId).apply {
                setSmallIcon(R.drawable.info)
                setContentTitle(mActivity.getString(R.string.unable_to_save_image))
            }.build()

            mActivity.getSystemService(NotificationManager::class.java).let {
                it.createNotificationChannel(channel)
                it.notify(1, notif)
            }
            return
        }

        if (skipErrorDialog) {
            mActivity.showMessage(R.string.unable_to_save_image)
        } else {
            val msg = mActivity.getString(R.string.unable_to_save_image_verbose, exception.place.name)
            showErrorDialog(msg, exception)
        }
    }

    private fun showErrorDialog(message: String, exception: Throwable) {
        val ctx = mActivity

        MaterialAlertDialogBuilder(ctx).apply {
            setMessage(message)
            setPositiveButton(R.string.show_details) { _, _ ->
                val pkgName = ctx.packageName
                val pkgVersion = ctx.packageManager.getPackageInfo(pkgName, 0).longVersionCode
                val text = "osVersion: ${Build.FINGERPRINT}" +
                        "\npackage: $pkgName:$pkgVersion" +
                        "\n\n${exception.printStackTraceToString()}"

                MaterialAlertDialogBuilder(ctx).apply {
                    setItems(text.lines().toTypedArray(), null)
                    setNeutralButton(R.string.copy_to_clipboard) { _, _ ->
                        val clipData = ClipData.newPlainText(exception.javaClass.name, text)
                        val cm = mActivity.getSystemService(ClipboardManager::class.java)
                        cm.setPrimaryClip(clipData)
                        ctx.showMessage(R.string.copied_text_to_clipboard)
                    }
                    showIgnoringShortEdgeMode()
                }
            }
            showIgnoringShortEdgeMode()
        }
    }

    fun onThumbnailGenerated(thumbnail: Bitmap, seq: Long = 0) {
        // Миниатюры из очереди — только вперёд: старый кадр не перебивает новый.
        // Спиннер гаснет, когда очередь пуста (а не на первой миниатюре).
        if (seq >= lastThumbSeq) {
            lastThumbSeq = seq
            mActivity.imagePreview.setImageBitmap(thumbnail)
        }
        if (pending.get() == 0) mActivity.previewLoader.visibility = View.GONE
    }

    companion object {
        private const val TAG = "ImageCapturer"

        // ThunderCam очередь: 4 слота — 3-й тап больше не дропается в тост.
        const val MAX_IN_FLIGHT = 4
    }
}
