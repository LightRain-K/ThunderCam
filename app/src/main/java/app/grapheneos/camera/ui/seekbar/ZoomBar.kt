package app.grapheneos.camera.ui.seekbar

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.AppCompatSeekBar
import androidx.camera.core.ZoomState
import androidx.camera.core.AspectRatio
import androidx.transition.Fade
import androidx.transition.Transition
import androidx.transition.TransitionManager
import app.grapheneos.camera.CamConfig
import app.grapheneos.camera.R
import app.grapheneos.camera.ui.activities.MainActivity
import kotlin.math.roundToInt

class ZoomBar : AppCompatSeekBar {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(
        context,
        attrs,
        defStyle
    )

    companion object {
        private const val PANEL_VISIBILITY_DURATION = 2000L
    }

    private val closePanelHandler: Handler = Handler(Looper.getMainLooper())

    private val closePanelRunnable = Runnable {
        hidePanel()
    }

    @SuppressLint("InflateParams")
    private var thumbView: View = LayoutInflater.from(context)
        .inflate(R.layout.zoom_bar_thumb, null, false)

    private lateinit var mainActivity: MainActivity
    private lateinit var camConfig: CamConfig

    fun setMainActivity(mainActivity: MainActivity) {
        this.mainActivity = mainActivity
        camConfig = mainActivity.camConfig
    }

    fun showPanel() {
        togglePanel(View.VISIBLE)
        closePanelHandler.removeCallbacks(closePanelRunnable)
        closePanelHandler.postDelayed(closePanelRunnable, PANEL_VISIBILITY_DURATION)
    }

    private fun hidePanel() {
        togglePanel(View.GONE)
    }

    private fun togglePanel(visibility: Int) {
        val transition: Transition = Fade()
        if (visibility == View.GONE) {
            transition.duration = 300
        } else {
            transition.duration = 0
        }
        transition.addTarget(R.id.zoom_bar_panel)

        TransitionManager.beginDelayedTransition(
            mainActivity.window.decorView.rootView as ViewGroup, transition
        )
        mainActivity.zoomBarPanel.visibility = visibility
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(h, w, oldh, oldw)
    }

    /** Ставит цифру превью (скейл) и запоминает фактор для захвата. Всегда парой. */
    private fun setExtra(extra: Float) {
        camConfig.thmcExtraZoom = extra
        try {
            val pv = mainActivity.previewView
            // WYSIWYG: превью-аспект уже равен аспекту захвата (applyPreviewRatio),
            // поэтому центр превью = центру кадра. Пивот строго в центр превью.
            // bottom_overlay (черная плашка под превью) при скейле накрывала бы низ
            // увеличенного превью — видимый центр уходил вверх, а кроп захвата берет
            // истинный центр → превью показывало выше чем сохранение. Поэтому на зуме
            // прячем плашку, превью едет под кнопки честно по центру.
            try {
                mainActivity.findViewById<View>(app.grapheneos.camera.R.id.bottom_overlay)?.visibility =
                    if (extra > 1.01f) View.GONE else View.VISIBLE
            } catch (_: Throwable) {}
            pv.pivotX = pv.width / 2f
            pv.pivotY = pv.height / 2f
            pv.scaleX = extra
            pv.scaleY = extra
        } catch (_: Throwable) {}
    }

    fun updateThumb(shouldShowPanel: Boolean = true) {
        val zoomState: ZoomState? = camConfig.zoomState

        if (shouldShowPanel) {
            showPanel()
        } else {
            hidePanel()
        }

        var zoomRatio = 1.0f
        var linearZoom = 0.0f

        if (zoomState != null) {
            zoomRatio = zoomState.zoomRatio
            linearZoom = zoomState.linearZoom
        }

        progress = (linearZoom * 100).roundToInt()

        // ThunderCam: пилюля показывает ТОТАЛ (оптика × цифра), а не только HAL
        val total = zoomRatio * camConfig.thmcExtraZoom
        val textView: TextView = thumbView.findViewById(R.id.progress) as TextView
        val text = String.format("%.1fx", total)

        textView.text = text

        thumbView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        val bitmap = Bitmap.createBitmap(
            thumbView.measuredWidth,
            thumbView.measuredHeight,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        thumbView.layout(0, 0, thumbView.measuredWidth, thumbView.measuredHeight)
        thumbView.draw(canvas)
        thumb = BitmapDrawable(resources, bitmap)
        onSizeChanged(width, height, 0, 0)
    }

    @Synchronized
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(heightMeasureSpec, widthMeasureSpec)
        setMeasuredDimension(measuredHeight, measuredWidth)
    }

    override fun onDraw(c: Canvas) {
        c.rotate(-90f)
        c.translate(-height.toFloat(), 0f)
        super.onDraw(c)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) {
            return false
        }
        when (event.action) {

            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_DOWN -> {

                var progress = max - (max * event.y / height).toInt()

                if (progress < 1) progress = 1
                if (progress > 100) progress = 100

                // ThunderCam зум: 1-75 = оптика HAL, 76-100 = цифра поверх.
                // Extra-зона есть ВСЕГДА (минимум ×2 поверх HAL): иначе при
                // totalCap <= devMax цифра умирает целиком и нейронка не включается.
                // Верхняя цель — тиром бенчмарка (бюджет ~10×, середина ~20×, зверь ~100×).
                // Превью тянется скейлом, захват — кропом с SR (один фактор = WYSIWYG).
                // В видео и при выкл. тумблере — стоковый маппинг на весь ход.
                val canExtra = camConfig.thmcZoom && !camConfig.isVideoMode
                val devMax = camConfig.zoomState?.maxZoomRatio?.coerceAtLeast(1f) ?: 1f
                val totalCap = try {
                    app.grapheneos.camera.thmc.ThmcBench.effectiveProfile(
                        mainActivity, camConfig.thmc
                    ).zoomCap
                } catch (_: Throwable) { 20f }
                val digiCap = maxOf(totalCap / devMax, 2f)
                try {
                    // Диагностика базы зума: какое стекло, какой HAL-макс (сверяем со стоком).
                    var focal = "?"
                    try {
                        val cam = camConfig.camera
                        if (cam != null) {
                            focal = androidx.camera.camera2.interop.Camera2CameraInfo.from(cam.cameraInfo)
                                .getCameraCharacteristic(
                                    android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
                                )?.joinToString() ?: "?"
                        }
                    } catch (_: Throwable) {}
                    android.util.Log.i(
                        "ThmcZoom",
                        "devMax=$devMax totalCap=$totalCap digiCap=$digiCap canExtra=$canExtra " +
                            "focal=[$focal] facing=${camConfig.lensFacing}"
                    )
                } catch (_: Throwable) {}
                if (!canExtra || digiCap <= 1.01f || progress <= 75) {
                    setExtra(1f)
                    val lin = if (canExtra && digiCap > 1.01f) progress / 75f else progress / 100f
                    camConfig.camera?.cameraControl?.setLinearZoom(lin.coerceIn(0f, 1f))
                } else {
                    camConfig.camera?.cameraControl?.setLinearZoom(1f)
                    val extra = (1f + (progress - 75) / 25f * (digiCap - 1f)).coerceIn(1f, digiCap)
                    setExtra(extra)
                }
                updateThumb()

            }
            MotionEvent.ACTION_CANCEL -> {
            }
        }
        return true
    }
}
