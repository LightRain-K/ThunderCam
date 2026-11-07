package app.grapheneos.camera.ui.seekbar

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatSeekBar
import androidx.camera.core.ExposureState
import androidx.transition.Fade
import androidx.transition.Transition
import androidx.transition.TransitionManager
import app.grapheneos.camera.R
import app.grapheneos.camera.ui.activities.MainActivity

class ExposureBar : AppCompatSeekBar {
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

    private lateinit var mainActivity: MainActivity

    fun setMainActivity(mainActivity: MainActivity) {
        this.mainActivity = mainActivity
        try {
            val label = mainActivity.exposureBarPanel.findViewById<android.widget.TextView>(R.id.exposure_value)
            label.setOnClickListener { reset() }
        } catch (_: Throwable) {}
    }

    /** Текущий EV из индекса компенсации и шага. */
    fun currentEv(): Float {
        return try {
            val st = mainActivity.camConfig.camera?.cameraInfo?.exposureState ?: return 0f
            val step = st.exposureCompensationStep
            val stepF = step.numerator.toFloat() / step.denominator.coerceAtLeast(1).toFloat()
            progress * stepF
        } catch (_: Throwable) { 0f }
    }

    private fun updateLabel() {
        try {
            val label = mainActivity.exposureBarPanel.findViewById<android.widget.TextView>(R.id.exposure_value)
            val ev = currentEv()
            label.text = String.format(java.util.Locale.US, "%+.1f EV", ev)
        } catch (_: Throwable) {}
    }

    /** Тап по значению = сброс в 0. */
    fun reset() {
        try {
            progress = 0
            mainActivity.camConfig.camera?.cameraControl?.setExposureCompensationIndex(0)
            updateLabel()
            showPanel()
        } catch (_: Throwable) {}
    }

    fun setExposureConfig(exposureState: ExposureState) {
        max = exposureState.exposureCompensationRange.upper
        min = exposureState.exposureCompensationRange.lower

        // Прогресс маппится 1:1 на индекс компенсации (было: целочисленное
        // деление шага давало 0 при шаге <1EV — ползунок всегда уезжал в 0).
        progress = exposureState.exposureCompensationIndex.coerceIn(min, max)

        Log.i("TAG", "Setting progress from setExposureConfig")
        updateLabel()

        onSizeChanged(width, height, 0, 0)
    }

    fun showPanel() {
        togglePanel(View.VISIBLE)
        closePanelHandler.removeCallbacks(closePanelRunnable)
        closePanelHandler.postDelayed(closePanelRunnable, PANEL_VISIBILITY_DURATION)
    }

    fun hidePanel() {
        togglePanel(View.GONE)
    }

    private fun togglePanel(visibility: Int) {
        val transition: Transition = Fade()
        if (visibility == View.GONE) {
            transition.duration = 300
        } else {
            transition.duration = 0
        }
        transition.addTarget(R.id.exposure_bar)

        TransitionManager.beginDelayedTransition(
            mainActivity.window.decorView.rootView as ViewGroup, transition
        )

        mainActivity.exposureBarPanel.visibility = visibility
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(h, w, oldh, oldw)
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
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                progress = max - (max * event.y / (height / 2)).toInt()

                Log.i("progress", progress.toString())
                Log.i("max", max.toString())

                mainActivity.camConfig.camera?.cameraControl
                    ?.setExposureCompensationIndex(progress)

                updateLabel()
                showPanel()

                onSizeChanged(width, height, 0, 0)
            }
            MotionEvent.ACTION_CANCEL -> {
            }
        }
        return true
    }
}
