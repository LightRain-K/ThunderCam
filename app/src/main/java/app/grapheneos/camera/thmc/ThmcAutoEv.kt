package app.grapheneos.camera.thmc

import android.util.Log
import androidx.camera.core.Camera
import java.util.concurrent.TimeUnit

/**
 * ThunderCam авто-EV: HAL в помещении задирает ISO (до 4000) и пересвечивает
 * относительно реальности. P0 постфактум ISO не понизит — давим ДО кадра.
 *
 * Только вниз (bias ≤ 0), поверх ручной компенсации пользователя, с возвратом.
 * Работает на повторяющемся запросе: set → ждём future → отстой 150мс → кадр.
 * Восстановление — в onCaptureSuccess (экспозиция кадра к тому моменту закрыта).
 */
object ThmcAutoEv {
    private const val TAG = "ThmcAutoEv"

    /** bias в шагах компенсации по превью-яркости + клиппингу светов. 0 = не трогать. Только вниз.
     * brightFrac — доля пикселей превью ярче 0.9 (лампа 5% кадра средний почти не
     * двигает, а света клиппит): давим по ней, иначе пережог не лечится никак.
     * Пороги ПОДНЯТЫ с 0.48/0.58: средняя яркость правильно экспонированного
     * дня — 0.55–0.70 (небо!), старые гейты давали −1/−2 EV почти на любом
     * солнечном кадре → «фото темнее превью» + лифт зерна постфактум.
     * Давим только реальный пересвет/клиппинг. */
    fun biasSteps(previewL: Float, brightFrac: Float = 0f): Int {
        if (previewL < 0.05f || previewL > 0.95f) return 0
        var b = when {
            previewL > 0.72f -> -2
            previewL > 0.62f -> -1
            else -> 0
        }
        if (brightFrac > 0.15f && previewL > 0.50f) b = minOf(b - 1, -2)
        else if (brightFrac > 0.05f && previewL > 0.55f) b = minOf(b, -1)
        return b
    }

    /**
     * Применяет отрицательный bias к текущему индексу. Возвращает индекс для
     * восстановления или null (нечего делать / камера недоступна).
     * extraSteps — серво-добавка по прошлому ISO (глубже при хронике высокого ISO).
     * Блокирует вызывающий поток — вызывать ТОЛЬКО с фона. Таймаут ужатый (600мс):
     * полный отстой 1.5с+150мс убивал серии, bias и так оседает на брекетинге.
     */
    fun apply(camera: Camera?, previewL: Float, extraSteps: Int = 0, brightFrac: Float = 0f): Int? {
        if (camera == null) return null
        val bias = biasSteps(previewL, brightFrac) - extraSteps.coerceIn(0, 4)
        if (bias == 0) return null
        return try {
            val st = camera.cameraInfo.exposureState
            val cur = st.exposureCompensationIndex
            val range = st.exposureCompensationRange
            val want = (cur + bias).coerceIn(range.lower, range.upper)
            if (want == cur) return null
            camera.cameraControl.setExposureCompensationIndex(want).get(600, TimeUnit.MILLISECONDS)
            try { Thread.sleep(40) } catch (_: Throwable) {}
            try {
                Log.i(TAG, "bias $bias (extra=$extraSteps L=${"%.2f".format(previewL)} idx $cur->$want)")
            } catch (_: Throwable) {}
            cur
        } catch (t: Throwable) {
            Log.w(TAG, "apply failed", t)
            null
        }
    }

    fun restore(camera: Camera?, prevIndex: Int?) {
        if (camera == null || prevIndex == null) return
        try {
            camera.cameraControl.setExposureCompensationIndex(prevIndex)
        } catch (_: Throwable) {}
    }
}
