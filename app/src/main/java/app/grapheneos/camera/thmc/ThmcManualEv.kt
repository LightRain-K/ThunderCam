package app.grapheneos.camera.thmc

import android.hardware.camera2.CameraCharacteristics
import android.util.Log
import androidx.camera.core.Camera

/**
 * ThunderCam ручная экспозиция (shutter priority своими силами).
 *
 * Проблема: HAL в помещении ставит 1/30 + ISO 4000 — шум. ZSL на этом телефоне
 * нет, напрямую приказать «длиннее выдержку» нечем. Поэтому ведём свою AE-петлю:
 * калибровка (выдержка×ISO → яркость) снимается с EXIF каждого кадра, следующий
 * кадр считаем от неё: та же яркость, но сплит с упором на выдержку.
 *
 * Первый кадр / смена сцены = HAL (он же и калибрует), дальше — manual:
 * AE_MODE_OFF + SENSOR_EXPOSURE_TIME + SENSOR_SENSITIVITY одним махом через
 * Camera2 addCaptureRequestOptions, возврат — clear в onCaptureSuccess.
 */
object ThmcManualEv {
    private const val TAG = "ThmcManualEv"
    private const val PREFS = "commons"

    // Цель по яркости + учёт ручной EV пользователя (экспобар).
    // 0.47: 18% серый в sRGB ≈ 0.46. Старый 0.38 = гарантированная недодержка
    // 0.5–1.3 EV против того, что видит глаз/превью → жалоба «днём темнее,
    // в сумерки шумно» (тёмный JPEG потом лифтится — зерно вылезает).
    private const val BASE_TARGET = 0.47f

    // Потолки выдержки: с рук длиннее — смаз. Фронт без стаба — короче.
    private const val SHUTTER_MAX_BACK_NS = 66_000_000L
    private const val SHUTTER_MAX_FRONT_NS = 40_000_000L

    private const val ISO_MIN = 100
    private const val ISO_MAX = 3200

    private val isoLadder = intArrayOf(
        100, 125, 160, 200, 250, 320, 400, 500, 640, 800,
        1000, 1250, 1600, 2000, 2500, 3200
    )

    data class Plan(val shutterNs: Long, val iso: Int, val confident: Boolean = true) {
        val product: Long get() = shutterNs * iso
    }

    /** Калибровка с прошлого кадра. null = снимать HAL'ом (он же откалибрует). */
    fun plan(
        camera: Camera?,
        previewL: Float,
        isFront: Boolean,
        extraZoom: Float,
        prefs: android.content.SharedPreferences,
    ): Plan? {
        val r = planInner(camera, previewL, isFront, extraZoom, prefs)
        if (r == null) {
            try {
                val age = (System.currentTimeMillis() - prefs.getLong("thmc_cal_ts", 0L)) / 1000
                Log.i(TAG, "null L=${"%.2f".format(previewL)} calP=${prefs.getLong("thmc_cal_p", 0L)} " +
                    "calB=${"%.2f".format(prefs.getFloat("thmc_cal_b", 0f))} " +
                    "dPl=${"%.2f".format(kotlin.math.abs(previewL - prefs.getFloat("thmc_cal_pl", -1f)))} age=${age}s")
            } catch (_: Throwable) {}
        }
        return r
    }

    private fun planInner(
        camera: Camera?,
        previewL: Float,
        isFront: Boolean,
        extraZoom: Float,
        prefs: android.content.SharedPreferences,
    ): Plan? {
        if (camera == null) return null
        // Верх 0.60, не 0.95: яркий день HAL экспонирует ВЕРНО (сцено-зависимый
        // замер + свой тонмаппинг), а ручная петля с целью 0.47 и ISO-потолком
        // 3200 только темнит и шумит. Ручной режим — для тьмы/сумерек, где
        // HAL раздувает ISO. Калибровка продолжает учиться на HAL-кадрах.
        if (previewL < 0.05f || previewL > 0.60f) return null
        if (extraZoom > 1.5f) return null
        return try {
            val calP = prefs.getLong("thmc_cal_p", 0L)
            val calB = prefs.getFloat("thmc_cal_b", 0f)
            val calPl = prefs.getFloat("thmc_cal_pl", -1f)
            val calTs = prefs.getLong("thmc_cal_ts", 0L)
            val calFront = prefs.getBoolean("thmc_cal_front", false)
            if (calP <= 0L || calB < 0.03f) return null
            if (calFront != isFront) return null
            if (System.currentTimeMillis() - calTs > 90_000L) return null
            // Сцена только что сменилась (скачок превью): первый кадр — HAL
            // с живым замером, а не manual по протухшей калибровке. HAL-кадр
            // тут же перекалибрует петлю под новую сцену.
            val spread = ThmcTrackState.sceneSpread()
            if (spread > 0.12f) {
                try { Log.i(TAG, "scene fresh dL=${"%.2f".format(spread)}, HAL first") } catch (_: Throwable) {}
                return null
            }
            if (kotlin.math.abs(previewL - calPl) > 0.20f) return null

            // Цель с учётом ручной EV пользователя.
            var target = BASE_TARGET
            try {
                val es = camera.cameraInfo.exposureState
                val step = es.exposureCompensationStep
                val stepEv = step.numerator.toDouble() / step.denominator.coerceAtLeast(1).toDouble()
                target = (BASE_TARGET * Math.pow(2.0, es.exposureCompensationIndex * stepEv)).toFloat()
                    .coerceIn(0.15f, 0.8f)
            } catch (_: Throwable) {}

            // Отношение в ЛИНЕЙНОМ свете: JPEG-mean гаммирован (~0.45) + HAL жмёт
            // света тон-кривой, линейный счёт по JPEG-mean даёт промах в разы.
            val linT = Math.pow(target.toDouble(), 2.2)
            val linB = Math.pow(calB.toDouble(), 2.2)
            if (linB <= 1e-6) return null
            val ratio = (linT / linB).coerceIn(0.05, 20.0)
            // Прыжок в разы = почти наверняка смена сцены с протухшей калибровкой.
            // Не стреляем вслепую: берём ограниченный план (не дальше 2.5×) и помечаем
            // неуверенным — вызыватель добьёт своим замером из превью-потока.
            val confident = ratio in 0.4..2.5
            val safeRatio = ratio.coerceIn(0.4, 2.5)
            val product = calP.toDouble() * safeRatio
            return split(camera, isFront, product, target, "calB=$calB", confident)
        } catch (t: Throwable) {
            Log.w(TAG, "plan failed", t)
            null
        }
    }

    /** Сплит произведения (выдержка×ISO) с упором на выдержку. */
    private fun split(
        camera: Camera?,
        isFront: Boolean,
        product: Double,
        target: Float,
        why: String,
        confident: Boolean,
    ): Plan? {
        if (camera == null) return null
        return try {
            val shutterRange = exposureTimeRange(camera)
            val isoRange = sensitivityRange(camera)
            val shutterMax = (if (isFront) SHUTTER_MAX_FRONT_NS else SHUTTER_MAX_BACK_NS)
                .coerceIn(shutterRange.lower, shutterRange.upper)
            val isoCap = ISO_MAX.coerceIn(isoRange.lower, isoRange.upper)
            val isoFloor = ISO_MIN.coerceIn(isoRange.lower, isoRange.upper)
            if (isoFloor > isoCap) return null

            // Сплит с упором на выдержку: тянем выдержку до потолка (НЕ выше —
            // иначе смаз с рук), остаток — ISO лесенкой ВВЕРХ. Было: округление
            // к ближайшему давало выдержку 172мс при потолке 66мс.
            var iso = ceilingIso((product / shutterMax).toInt(), isoFloor, isoCap)
            var shutter = (product / iso).toLong()
                .coerceIn(shutterRange.lower, minOf(shutterRange.upper, shutterMax))
            // Если даже потолок ISO не вывозит — недоэкспонируем (не шумим).
            // Если света через край — упираемся в минимум выдержки.
            Log.i(TAG, "plan shut=${shutter / 1_000_000}ms iso=$iso (t=$target $why conf=$confident)")
            Plan(shutter, iso, confident)
        } catch (t: Throwable) {
            Log.w(TAG, "plan failed", t)
            null
        }
    }

    /** Применить план: AE off + выдержка + ISO. Только с фона. true = снимать. */
    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    fun applyPlan(camera: Camera?, plan: Plan, settleMs: Long = 450L): Boolean {
        if (camera == null) return false
        return try {
            val b = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
                .setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE, android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE_OFF)
                .setCaptureRequestOption(android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME, plan.shutterNs)
                .setCaptureRequestOption(android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY, plan.iso)
            // Превью должно успевать за нашей выдержкой, иначе замер врёт в темноту
            // (превью упирается в свой fps-потолок, а still честно держит 66мс и
            // выходит светлее цели). Диапазон (10,30) разрешает длинные кадры превью.
            // Не примет HAL — уйдём в HAL-фолбэк, как раньше.
            try {
                b.setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    android.util.Range(10, 30)
                )
            } catch (_: Throwable) {}
            val opts = b.build()
            androidx.camera.camera2.interop.Camera2CameraControl.from(camera.cameraControl)
                .addCaptureRequestOptions(opts).get(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
            try { Thread.sleep(settleMs) } catch (_: Throwable) {}
            Log.i(TAG, "manual shut=${plan.shutterNs / 1_000_000}ms iso=${plan.iso}")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "apply failed", t)
            try { clear(camera) } catch (_: Throwable) {}
            false
        }
    }

    /**
     * Свой замер из превью-потока: анализатор меряет яркость при ЗАВЕДОМО
     * известном произведении (план уже применён), считаем точное и докручиваем.
     * Только с фона. Возвращает итоговый план или null (снимать HAL).
     */
    fun meterCorrect(camera: Camera?, rough: Plan, isFront: Boolean): Plan? {
        if (camera == null) return null
        return try {
            val roughProduct = rough.product
            if (roughProduct <= 0L) return null
            ThmcTrackState.meterL = -1f
            ThmcTrackState.meterProduct = roughProduct
            val t0 = System.currentTimeMillis()
            var m = -1f
            while (System.currentTimeMillis() - t0 < 800L) {
                try { Thread.sleep(50) } catch (_: Throwable) {}
                val v = ThmcTrackState.meterL
                if (v >= 0f) { m = v; break }
            }
            ThmcTrackState.meterProduct = 0L
            if (m < 0.03f || m > 0.97f) {
                Log.i(TAG, "meter clipped L=${"%.2f".format(m)}, HAL fallback")
                return null
            }
            // Цель с учётом ручной EV (как в плане).
            var target = BASE_TARGET
            try {
                val es = camera.cameraInfo.exposureState
                val step = es.exposureCompensationStep
                val stepEv = step.numerator.toDouble() / step.denominator.coerceAtLeast(1).toDouble()
                target = (BASE_TARGET * Math.pow(2.0, es.exposureCompensationIndex * stepEv)).toFloat()
                    .coerceIn(0.15f, 0.8f)
            } catch (_: Throwable) {}
            val ratio = (Math.pow(target.toDouble(), 2.2) / Math.pow(m.toDouble(), 2.2)).coerceIn(0.1, 10.0)
            val corrected = split(camera, isFront, roughProduct.toDouble() * ratio, target, "meterL=$m", true)
            Log.i(TAG, "meter L=${"%.2f".format(m)} ratio=${"%.2f".format(ratio)} -> ${corrected?.let { "${it.shutterNs / 1_000_000}ms/${it.iso}" } ?: "null"}")
            corrected
        } catch (t: Throwable) {
            Log.w(TAG, "meter failed", t)
            try { ThmcTrackState.meterProduct = 0L } catch (_: Throwable) {}
            null
        }
    }

    /** Возврат к HAL AE (fire-and-forget: следующий кадр всё равно перезапишет опции). */
    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    fun clear(camera: Camera?) {
        if (camera == null) return
        try {
            androidx.camera.camera2.interop.Camera2CameraControl.from(camera.cameraControl)
                .clearCaptureRequestOptions()
        } catch (_: Throwable) {}
    }

    /** Калибровка с отснятого кадра: вызывать из P0 (там есть EXIF + яркость). */
    fun updateFromShot(
        prefs: android.content.SharedPreferences,
        shutterNs: Long,
        iso: Int,
        brightness01: Float,
        previewL: Float,
        isFront: Boolean,
    ) {
        try {
            if (shutterNs <= 0L || iso <= 0) return
            if (previewL < 0.05f || previewL > 0.95f) return
            // Яркость климпим, а не отбрасываем: белый кадр (0.99) иначе вообще не
            // калибрует — петля протухает, manual мёртв, HAL повторяет пересвет.
            // Кап 0.95 даёт заведомо заниженную оценку сцены → следующий план темнее,
            // петля сама выезжает из клиппинга вниз.
            val b = brightness01.coerceIn(0.02f, 0.95f)
            prefs.edit()
                .putLong("thmc_cal_p", shutterNs * iso)
                .putFloat("thmc_cal_b", b)
                .putFloat("thmc_cal_pl", previewL)
                .putLong("thmc_cal_ts", System.currentTimeMillis())
                .putBoolean("thmc_cal_front", isFront)
                .apply()
        } catch (_: Throwable) {}
    }

    fun prefsOf(context: android.content.Context): android.content.SharedPreferences {
        return context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
    }

    /** Ближайшая ступень лесенки НЕ НИЖЕ нужного (округление вниз растит выдержку). */
    private fun ceilingIso(v: Int, floor: Int, cap: Int): Int {
        val c = isoLadder.filter { it >= v }.minOrNull() ?: isoLadder.last()
        return c.coerceIn(floor, cap)
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun exposureTimeRange(camera: Camera): android.util.Range<Long> {
        return try {
            val info = androidx.camera.camera2.interop.Camera2CameraInfo.from(camera.cameraInfo)
            info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                ?: android.util.Range(1_000_000L, 66_000_000L)
        } catch (_: Throwable) { android.util.Range(1_000_000L, 66_000_000L) }
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun sensitivityRange(camera: Camera): android.util.Range<Int> {
        return try {
            val info = androidx.camera.camera2.interop.Camera2CameraInfo.from(camera.cameraInfo)
            info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                ?: android.util.Range(100, 3200)
        } catch (_: Throwable) { android.util.Range(100, 3200) }
    }
}
