package com.example.thundercam20.imageprocessing

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Защита кожи в портрете: лица не должны «гореть» от P0-насыщенности ×1.43.
 * Детектим лица ML Kit на 640-копии, строим мягкую маску и внутри неё
 * тянем пиксели к desat 45% (цвет ровный, детали целы).
 * Нет лиц / ошибка — возвращаем как было, без крашей.
 */
object FaceProtect {

    /** Один клиент на процесс: создание детектора (~200-500мс) не на каждый кадр. */
    @Volatile private var client: FaceDetector? = null

    private fun detector(): FaceDetector {
        client?.let { return it }
        val opts = FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).build()
        val c = FaceDetection.getClient(opts)
        client = c
        return c
    }

    fun protect(bitmap: Bitmap): Bitmap {
        val boxes = faceBoxes(bitmap)
        if (boxes.isEmpty()) return bitmap
        return try {
            val w = bitmap.width; val h = bitmap.height
            val px = IntArray(w * h)
            bitmap.getPixels(px, 0, w, 0, 0, w, h)
            for (i in px.indices) {
                val x = (i % w).toFloat(); val y = (i / w).toFloat()
                var best = 0f
                for (b in boxes) {
                    val f = faceFactor(x, y, b)
                    if (f > best) best = f
                    if (best >= 1f) break
                }
                if (best > 0.01f) {
                    val p = px[i]
                    val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                    val gray = (r + g + b) / 3
                    // desat 60% внутри маски — кожа не должна гореть
                    val sr = (r + (gray - r) * 0.6f).toInt()
                    val sg = (g + (gray - g) * 0.6f).toInt()
                    val sb = (b + (gray - b) * 0.6f).toInt()
                    val nr = (r + (sr - r) * best).toInt().coerceIn(0, 255)
                    val ng = (g + (sg - g) * best).toInt().coerceIn(0, 255)
                    val nb = (b + (sb - b) * best).toInt().coerceIn(0, 255)
                    px[i] = -0x1000000 or (nr shl 16) or (ng shl 8) or nb
                }
            }
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            out.setPixels(px, 0, w, 0, 0, w, h)
            out
        } catch (_: Throwable) { bitmap }
    }

    /** 1 внутри лица (70% бокса), спад до 0 к 130% — мягкие края без ступенек. */
    private fun faceFactor(x: Float, y: Float, b: RectF): Float {
        val cx = b.centerX(); val cy = b.centerY()
        val rx = b.width() / 2; val ry = b.height() / 2
        if (rx <= 0 || ry <= 0) return 0f
        val dx = kotlin.math.abs(x - cx) / rx
        val dy = kotlin.math.abs(y - cy) / ry
        val d = max(dx, dy)
        return when {
            d <= 0.7f -> 1f
            d >= 1.3f -> 0f
            else -> 1f - (d - 0.7f) / 0.6f
        }
    }

    /** Боксы лиц в координатах битмапа (для боке и масок). Пусто = нет лиц. */
    fun faceBoxes(bitmap: Bitmap): List<RectF> {
        return detectFaces(bitmap)
    }

    private fun detectFaces(bitmap: Bitmap): List<RectF> {
        return try {
            val longSide = max(bitmap.width, bitmap.height)
            val s = 640f / longSide
            val small = if (longSide > 640) Bitmap.createScaledBitmap(bitmap, (bitmap.width * s).toInt(), (bitmap.height * s).toInt(), true) else bitmap
            // Быстрый провал: лицо не нашли за 1.5с — отдаём кадр как был, съёмка не ждёт ML Kit.
            val faces = Tasks.await(detector().process(InputImage.fromBitmap(small, 0)), 1500, TimeUnit.MILLISECONDS)
            val sx = bitmap.width.toFloat() / small.width
            val sy = bitmap.height.toFloat() / small.height
            val out = faces.mapNotNull { f ->
                f.boundingBox.let { b -> RectF(b.left * sx, b.top * sy, b.right * sx, b.bottom * sy) }
            }
            if (small !== bitmap) small.recycle()
            out
        } catch (_: Throwable) { emptyList() }
    }
}
