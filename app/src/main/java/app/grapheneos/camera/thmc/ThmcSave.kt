package app.grapheneos.camera.thmc

import android.content.ContentValues
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
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

/**
 * ThunderCam общее сохранение серийных кадров (HDR, ночь):
 * зеркало ImageSaver.saveImageInner (MediaStore/SAF + миниатюра + галерея + EXIF).
 * Вынесено из ThmcHdr, чтобы HDR и ночь не разъезжались копипастой.
 */
object ThmcSave {
    /** Габариты JPEG без декода. (0,0) при проблеме. Чистый. */
    fun fullSize(bytes: ByteArray): Pair<Int, Int> {
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            if (o.outWidth < 16 || o.outHeight < 16) 0 to 0 else o.outWidth to o.outHeight
        } catch (_: Throwable) { 0 to 0 }
    }

    /**
     * Дотяжка результата серии до исходного размера кадра.
     * Lanczos4 (OpenCV) вместо билинейного: 2304→4160 билинейно давал «мыло»
     * именно в сериях (ночь/HDR), при этом зерно стека не возвращается.
     * Нет OpenCV/OOM — честный билинейный фолбэк.
     * Равен/больше/проблема — возвращаем как было.
     */
    fun upscaleTo(bmp: android.graphics.Bitmap, w: Int, h: Int): android.graphics.Bitmap {
        return try {
            if (w < 16 || h < 16) return bmp
            if (bmp.width >= w && bmp.height >= h) return bmp
            var up: android.graphics.Bitmap? = null
            try {
                com.example.thundercam20.utils.OpencvManager.init()
                if (com.example.thundercam20.utils.OpencvManager.available) {
                    val src = org.opencv.core.Mat()
                    org.opencv.android.Utils.bitmapToMat(bmp, src)
                    val dst = org.opencv.core.Mat()
                    org.opencv.imgproc.Imgproc.resize(
                        src, dst,
                        org.opencv.core.Size(w.toDouble(), h.toDouble()),
                        0.0, 0.0, org.opencv.imgproc.Imgproc.INTER_LANCZOS4
                    )
                    src.release()
                    val outBmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                    org.opencv.android.Utils.matToBitmap(dst, outBmp)
                    dst.release()
                    up = outBmp
                }
            } catch (_: Throwable) { up = null }
            val result = up ?: android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true)
            try {
                android.util.Log.i("ThmcSave", "upscale ${bmp.width}x${bmp.height}->${w}x$h lanczos=${up != null}")
            } catch (_: Throwable) {}
            ThmcBitmaps.release(bmp)
            result
        } catch (e: OutOfMemoryError) {
            try {
                android.util.Log.w("ThmcSave", "upscale oom, keep work size")
            } catch (_: Throwable) {}
            bmp
        } catch (_: Throwable) { bmp }
    }

    /** Пересадка EXIF 1:1 (тот же вызов, что в ImageSaver.processExif). */    fun transplantExif(pixels: ByteArray, exifSource: ByteArray): ByteArray {
        return try {
            val exif = Exif.createFromInputStream(ByteArrayInputStream(exifSource))
            val out = ByteArrayOutputStream(pixels.size + 100 * 1024)
            exif.exifInterface.saveAttributes(ByteArrayInputStream(pixels), out)
            out.toByteArray()
        } catch (_: Throwable) { pixels }
    }

    fun stripExif(jpeg: ByteArray): ByteArray {
        return try {
            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
            val out = ByteArrayOutputStream(jpeg.size)
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
            ThmcBitmaps.release(bmp)
            out.toByteArray()
        } catch (_: Throwable) { jpeg }
    }

    /**
     * Финал серии «в лучшем виде»: Bitmap (уже full-size после upscaleTo) → JPEG → EXIF 1:1.
     * bmp освобождается внутри. OOM → пробуем single-encode без EXIF-пересадки.
     */
    fun encodeWithExif(bmp: android.graphics.Bitmap, exifSource: ByteArray, quality: Int): ByteArray {
        return try {
            val baos = ByteArrayOutputStream(exifSource.size)
            // Dither перед энкодом (серии): ломает бандинг после лифта/апскейла.
            try { com.example.thundercam20.imageprocessing.NativeProcessor.dither(bmp) } catch (_: Throwable) {}
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, baos)
            ThmcBitmaps.release(bmp)
            transplantExif(baos.toByteArray(), exifSource)
        } catch (e: OutOfMemoryError) {
            ThmcBitmaps.release(bmp)
            throw e
        }
    }

    /** Сохранение зеркалом ImageSaver.saveImageInner (MediaStore/SAF + миниатюра + галерея). */
    fun saveFinal(imageCapturer: ImageCapturer, bytes: ByteArray, seq: Long) {
        val camConfig = imageCapturer.camConfig
        val appContext = imageCapturer.mActivity.applicationContext
        val cr = appContext.contentResolver
        val captureTime = Date()
        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(captureTime)
        val fileName = IMAGE_NAME_PREFIX + dateStr + ".jpg"
        val storageLocation = camConfig.storageLocation
        val toMediaStore = storageLocation == app.grapheneos.camera.CamConfig.SettingValues.Default.STORAGE_LOCATION
        val uri: Uri = if (toMediaStore) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, DEFAULT_MEDIA_STORE_CAPTURE_PATH)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            cr.insert(app.grapheneos.camera.CamConfig.imageCollectionUri, cv)
                ?: throw Exception("insert null")
        } else {
            val treeUri = Uri.parse(storageLocation)
            DocumentsContract.createDocument(cr, getTreeDocumentUri(treeUri), "image/jpeg", fileName)
                ?: throw Exception("saf null")
        }
        try {
            cr.openAssetFileDescriptor(uri, "w")!!.use {
                val fd = it.fileDescriptor
                var off = 0
                while (off != bytes.size) off += android.system.Os.write(fd, bytes, off, bytes.size - off)
                if (toMediaStore) android.system.Os.fsync(fd)
            }
            if (toMediaStore) removePendingFlagFromUri(cr, uri)
        } catch (e: Exception) {
            try { cr.delete(uri, null, null) } catch (_: Throwable) {}
            throw e
        }
        // Миниатюра + галерея теми же колбэками, что сток
        try {
            val pv = imageCapturer.mActivity.imagePreview
            val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
            val thumb = ImageDecoder.decodeBitmap(source, ImageResizer(pv.width, pv.height))
            imageCapturer.mActivity.runOnUiThread { imageCapturer.onThumbnailGenerated(thumb, seq) }
        } catch (_: Throwable) {}
        val item = CapturedItem(ITEM_TYPE_IMAGE, dateStr, uri)
        imageCapturer.mActivity.runOnUiThread { imageCapturer.onImageSaverSuccess(item) }
    }
}
