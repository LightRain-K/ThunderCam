package app.grapheneos.camera.thmc

import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.camera.core.Camera

/**
 * Стабилизация превью движением окна кропа (EIS по-простому).
 *
 * Идея: зум — это кроп. Значит, чтобы картинка не тряслась, не надо двигать
 * пиксели — достаточно сдвигать само окно кропа в пределах запаса сенсора.
 * На превью это делается один-в-один: PreviewView рисуется чуть крупнее
 * (scale 1+MARGIN) и смещается на translationX/Y, компенсируя поворот телефона.
 *
 * Угол берём интегрированием гироскопа (рад), переводим в пиксели через
 * фокусное: shift = angle × focal × zoom. Медленный дрейф убираем high-pass'ом,
 * иначе окно уползёт к краю и там «прилипнет».
 *
 * Это только превью. Захват не трогаем: фото считается обычным путём
 * (зум-стек/ночь/HDR), поэтому WYSIWYG не ломается — кроме того, что
 * стабилизированный превью показывает на ~10% уже (плата за запас), как EIS
 * в любой камере.
 *
 * Выключено по умолчанию: знак осей (телефон vs кадр) может быть инвертирован
 * на конкретной модели — тогда картинка будет трястись сильнее. Проверяется
 * одним прогоном, при необходимости меняются SIGN_X/SIGN_Y.
 */
object ThmcPreviewStab {
    private const val MARGIN = 0.14f          // запас на сдвиг: 14% кадра
    private const val TAU = 0.6f              // high-pass: убираем медленный дрейф
    private const val MAX_SHIFT_FRAC = 0.6f   // не больше 60% запаса
    private const val GAIN = 1.35f            // чуть перекомпенсируем — эффект заметнее

    // Знак «экран против движения сцены». Если на устройстве окажется наоборот —
    // картинка будет усиливать тряску: тогда поставить +1.
    private const val SIGN_X = -1f
    private const val SIGN_Y = -1f

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var view: View? = null
    private var cameraProvider: (() -> Camera?)? = null
    private var zoomProvider: (() -> Float)? = null

    private var lastNs = 0L
    private var yaw = 0f
    private var pitch = 0f
    private var slowYaw = 0f
    private var slowPitch = 0f

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try { step() } catch (_: Throwable) {}
            handler.postDelayed(this, 16)
        }
    }

    fun isRunning(): Boolean = running

    fun start(v: View, camera: () -> Camera?, zoom: () -> Float) {
        if (running) return
        running = true
        view = v
        cameraProvider = camera
        zoomProvider = zoom
        lastNs = 0L
        yaw = 0f; pitch = 0f; slowYaw = 0f; slowPitch = 0f
        try {
            v.scaleX = 1f + MARGIN
            v.scaleY = 1f + MARGIN
            v.translationX = 0f
            v.translationY = 0f
        } catch (_: Throwable) {}
        handler.post(tick)
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(tick)
        try {
            view?.let {
                it.translationX = 0f
                it.translationY = 0f
                it.scaleX = 1f
                it.scaleY = 1f
            }
        } catch (_: Throwable) {}
        view = null
        cameraProvider = null
        zoomProvider = null
    }

    private fun step() {
        val v = view ?: return
        if (v.width <= 0) return
        val now = System.nanoTime()
        if (lastNs == 0L) { lastNs = now; return }
        val dt = ((now - lastNs) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
        lastNs = now

        val (wx, wy) = ThmcGyro.latestRates()
        // Интегрируем угол: yaw(ось y) → горизонталь, pitch(ось x) → вертикаль.
        yaw += wy * dt
        pitch += wx * dt
        // High-pass: медленный дрейф не должен уводить окно к краю.
        val a = (dt / TAU).coerceIn(0f, 1f)
        slowYaw += (yaw - slowYaw) * a
        slowPitch += (pitch - slowPitch) * a
        val hpYaw = yaw - slowYaw
        val hpPitch = pitch - slowPitch

        val cam = cameraProvider?.invoke() ?: return
        val zoom = (zoomProvider?.invoke() ?: 1f).coerceIn(1f, 20f)
        val focal = try {
            val f = ThmcAlign.focalPxGray(cam, v.width)
            // На части бюджетников LENS_INFO_* пустые — берём типичное 26 мм-экв.
            if (f > 0f) f else v.width * 0.8f
        } catch (_: Throwable) { v.width * 0.8f }

        val maxShift = MAX_SHIFT_FRAC * MARGIN * v.width
        var dx = hpYaw * focal * zoom * SIGN_X * GAIN
        var dy = hpPitch * focal * zoom * SIGN_Y * GAIN
        dx = dx.coerceIn(-maxShift, maxShift)
        dy = dy.coerceIn(-maxShift, maxShift)
        v.translationX = dx
        v.translationY = dy
    }
}
