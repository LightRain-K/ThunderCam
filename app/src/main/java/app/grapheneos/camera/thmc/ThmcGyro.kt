package app.grapheneos.camera.thmc

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.sqrt

/**
 * ThunderCam гироскоп: гейт дрожи для серий (ночь/HDR).
 *
 * V1 — только гейт спуска: кадр серии не снимаем, пока руки крутят
 * (угловая скорость выше порога), ждём успокоения с таймаутом.
 * Prior для выравнивания (сужение поиска по предсказанному сдвигу) — V1.5:
 * сюда добавится кольцевой буфер с timestamp и функция shiftPrior().
 *
 * Нюансы:
 * - Слушатель на MAIN-хендлере (воркер CameraX без Looper события не получит).
 * - Нет датчика / нет событий → гейт открыт (available=false), серия идёт как без гиро.
 * - Всё в try/catch: датчики на бюджетниках врут и отваливаются.
 */
object ThmcGyro {
    private const val TAG = "ThmcGyro"

    /** Порог спокойных рук, °/с. Выше — ждём (до timeout в awaitCalm). */
    const val CALM_DEG_PER_SEC = 1.2f

    @Volatile private var magnitudeDegPerSec = 0f
    @Volatile private var eventsSeen = 0
    @Volatile private var listening = false

    // Кольцевой буфер угловой скорости (tNs, wx, wz опущен, wy): для приора сдвига V1.5.
    // SensorEvent.timestamp — та же шкала, что SystemClock.elapsedRealtimeNanos().
    private val buf = ArrayDeque<Triple<Long, Float, Float>>(1200)
    private const val BUF_CAP = 1200

    private var sensorManager: SensorManager? = null
    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent?) {
            try {
                if (e == null || e.values.size < 3) return
                val m = sqrt(
                    (e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble()
                ).toFloat() * 57.2958f
                // Сглаживание: сырой MEMS шумит, резкие рывки всё равно пробьют.
                magnitudeDegPerSec = magnitudeDegPerSec * 0.6f + m * 0.4f
                eventsSeen++
                synchronized(buf) {
                    buf.addLast(Triple(e.timestamp, e.values[0], e.values[1]))
                    while (buf.size > BUF_CAP) buf.removeFirst()
                }
            } catch (_: Throwable) {}
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /** Подписаться. false = датчика нет — гейт всегда открыт. Идемпотентно. */
    @Synchronized
    fun start(context: Context): Boolean {
        if (listening) return eventsSeen > 0
        return try {
            val sm = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
                ?: return false
            val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: run {
                Log.i(TAG, "no gyroscope, gate open")
                return false
            }
            magnitudeDegPerSec = 0f
            eventsSeen = 0
            listening = sm.registerListener(
                listener, gyro, SensorManager.SENSOR_DELAY_FASTEST, Handler(Looper.getMainLooper())
            )
            sensorManager = sm
            if (!listening) Log.w(TAG, "register failed, gate open")
            listening
        } catch (t: Throwable) {
            Log.w(TAG, "start failed, gate open", t)
            false
        }
    }

    @Synchronized
    fun stop() {
        try {
            if (listening) sensorManager?.unregisterListener(listener)
        } catch (_: Throwable) {}
        listening = false
        sensorManager = null
    }

    /** true = датчик есть и руки спокойны (или датчика нет — гейт открыт). */
    fun isCalm(thresholdDegPerSec: Float = CALM_DEG_PER_SEC): Boolean {
        if (!listening || eventsSeen == 0) return true
        return magnitudeDegPerSec <= thresholdDegPerSec
    }

    /**
     * Ждать спокойных рук до timeoutMs (проверка каждые 60мс).
     * Всегда возвращается (не блокирует серию навсегда): false = сняли на тряске.
     */
    fun awaitCalm(timeoutMs: Long, thresholdDegPerSec: Float = CALM_DEG_PER_SEC): Boolean {
        if (!listening || eventsSeen == 0) return true
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            if (isCalm(thresholdDegPerSec)) return true
            try { Thread.sleep(60) } catch (_: Throwable) { return false }
        }
        return isCalm(thresholdDegPerSec)
    }

    /**
     * Приор сдвига между двумя моментами (нс, шкала elapsedRealtimeNanos):
     * |сдвиг| в пикселях при заданном фокусном (в тех же пикселях).
     * Чистое интегрирование pitch/yaw; знак осей телефона vs сенсора не учитываем —
     * вызыватель использует только МОДУЛЬ для сужения радиуса поиска (без риска неверного знака).
     * 0 = данных нет.
     */
    fun predictedShiftMag(t0Ns: Long, t1Ns: Long, focalPx: Float): Float {
        if (focalPx <= 0f) return 0f
        val snap: List<Triple<Long, Float, Float>> = synchronized(buf) { buf.toList() }
        if (snap.size < 2) return 0f
        val (dtx, dty) = integrateRotation(snap, t0Ns, t1Ns)
        val magRad = sqrt(dtx * dtx + dty * dty)
        if (magRad.isNaN()) return 0f
        return (magRad * focalPx).toFloat().coerceAtLeast(0f)
    }

    /**
     * Интегрирование угловой скорости (рад/с) по сэмплам (tNs, wx, wy) на [t0, t1].
     * Чистая, тестируется на JVM.
     */
    fun integrateRotation(
        samples: List<Triple<Long, Float, Float>>,
        t0: Long,
        t1: Long,
    ): Pair<Double, Double> {
        if (samples.size < 2 || t1 <= t0) return 0.0 to 0.0
        var ax = 0.0; var ay = 0.0
        var prevT = maxOf(samples[0].first, t0)
        var prevX = samples[0].second.toDouble()
        var prevY = samples[0].third.toDouble()
        for (i in 1 until samples.size) {
            val (t, x, y) = samples[i]
            if (t <= t0) {
                prevT = t; prevX = x.toDouble(); prevY = y.toDouble()
                continue
            }
            if (prevT >= t1) break
            val te = minOf(t, t1)
            val dt = ((te - maxOf(prevT, t0)) / 1_000_000_000.0).coerceAtLeast(0.0)
            // Трапеция по соседним сэмплам
            ax += (prevX + x) * 0.5 * dt
            ay += (prevY + y) * 0.5 * dt
            prevT = t; prevX = x.toDouble(); prevY = y.toDouble()
        }
        return ax to ay
    }
}
