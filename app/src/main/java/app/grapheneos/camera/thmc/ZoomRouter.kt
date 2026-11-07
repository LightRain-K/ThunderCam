package app.grapheneos.camera.thmc

import android.content.Context
import android.graphics.Bitmap
import com.example.thundercam20.analysis.FastGray

/**
 * ZoomRouter: куда вести зум-кроп.
 * Веб-подтверждение: burst > single NN > RAISR; NN только ROI 1-2 тайла 256 + overlap,
 * периферия Lanczos, flat/sky всегда skip. Удаление облака подтверждено.
 * V1: гейт по резкости + кап тайлов 2 + центр-ROI через существующий upscaleCrop.
 */
object ZoomRouter {
    data class Decision(val doSr: Boolean, val why: String)

    fun decide(crop: Bitmap, zoom: Float): Decision {
        if (zoom < 2f) return Decision(false, "zoom<2 lanczos")
        return try {
            val g = FastGray.of(crop, 240)
            val sharp = FastGray.meanAbsLaplacian(g)
            if (sharp < 6.0) return Decision(false, "flat skip sharp=$sharp")
            Decision(true, "sr sharp=$sharp")
        } catch (_: Throwable) { Decision(true, "sr-unknown") }
    }

    /**
     * Единая точка для всех вызывателей (ThmcP0/Hdr/Night/ImageSaver).
     * Возвращает upscaled Bitmap того же размера что targetW/H, или null -> вызыватель в билинейный.
     */
    fun upscale(
        context: Context,
        cropped: Bitmap,
        targetW: Int,
        targetH: Int,
        zoom: Float,
    ): Bitmap? {
        val d = decide(cropped, zoom)
        if (!d.doSr) return null
        return try {
            // Кап 2 тайла для скорости (было 4): ROI-центр уже дает вау, периферия Lanczos.
            // Центр 70% кропа — туда NN, остальное вызыватель дольет билинейным (через fallback null).
            val cw = cropped.width; val ch = cropped.height
            val roiW = (cw * 0.7f).toInt().coerceAtLeast(64)
            val roiH = (ch * 0.7f).toInt().coerceAtLeast(64)
            val ox = (cw - roiW) / 2; val oy = (ch - roiH) / 2
            // Если кроп маленький — целиком в сеть
            if (cw * ch < 700 * 700) {
                com.example.thundercam20.imageprocessing.SuperResZoom.upscaleCrop(
                    context, cropped, targetW, targetH, maxTiles = 2
                )
            } else {
                // ROI-путь: центр через сеть на промежуточный размер, затем общий ресайз
                val roi = try { Bitmap.createBitmap(cropped, ox, oy, roiW, roiH) } catch (_: Throwable) { cropped }
                val roiTW = (targetW * 0.7f).toInt().coerceAtLeast(64)
                val roiTH = (targetH * 0.7f).toInt().coerceAtLeast(64)
                val srRoi = try {
                    com.example.thundercam20.imageprocessing.SuperResZoom.upscaleCrop(
                        context, roi, roiTW, roiTH, maxTiles = 2
                    )
                } catch (_: Throwable) { null }
                if (roi !== cropped) try { ThmcBitmaps.release(roi) } catch (_: Throwable) {}
                if (srRoi == null) {
                    com.example.thundercam20.imageprocessing.SuperResZoom.upscaleCrop(
                        context, cropped, targetW, targetH, maxTiles = 2
                    )
                } else {
                    // Вшиваем ROI в билинейную основу чтобы без швов по краю ROI
                    val base = Bitmap.createScaledBitmap(cropped, targetW, targetH, true)
                    try {
                        val canvas = android.graphics.Canvas(base)
                        val dx = (targetW - srRoi.width) / 2; val dy = (targetH - srRoi.height) / 2
                        canvas.drawBitmap(srRoi, dx.toFloat(), dy.toFloat(), null)
                    } catch (_: Throwable) {}
                    try { ThmcBitmaps.release(srRoi) } catch (_: Throwable) {}
                    base
                }
            }
        } catch (_: Throwable) { null }
    }
}
