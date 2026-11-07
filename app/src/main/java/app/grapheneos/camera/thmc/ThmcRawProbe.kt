package app.grapheneos.camera.thmc

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.util.Log
import androidx.camera.core.Camera

/**
 * Фаза 0 RAW: рантайм-проба возможностей сенсора вместо хардкода по моделям.
 *
 * Факты с поля: Tecno Pova 5 — RAW нет вообще, S25U — есть (размер в Probe
 * не указан), A17 — неизвестно. Вывод: ни одну модель в код не зашиваем,
 * спрашиваем HAL при старте серии и кэшируем в памяти.
 *
 * probe() — только диагностика (нужен живой Camera, дёргать с фона или из
 * серии — characteristics кэшированы фреймворком, дёшево). pickBest() — чистая,
 * тестируется на JVM: крупнейший RAW с длинной стороной <= капа тира
 * (полные 200Мп S25U в стек не тянем — OOM, берём биннированный).
 */
object ThmcRawProbe {
    private const val TAG = "ThmcRaw"

    data class RawInfo(
        val supported: Boolean,
        /** HARDWARE_LEVEL строкой для диагностики (FULL/LEVEL_3/…). */
        val level: String,
        /** Все RAW_SENSOR размеры HAL (w,h). */
        val sizes: List<Pair<Int, Int>>,
        /** Лучший под кап тира (null = нечего предложить). */
        val best: Pair<Int, Int>?,
    )

    private val cache = HashMap<Int, RawInfo>()

    /** Кап длинной стороны RAW под тир: мид — 12Мп класс (4080 S25U бин), зверь — до 24Мп. */
    fun maxSideForTier(tier: String): Int = when (tier) {
        ThmcBench.TIER_BEAST -> 6000
        ThmcBench.TIER_MID -> 4128 // S25U бин 4080x3060 проходит впритык (было 4000 — резало)
        else -> 0 // бюджетнику RAW-стек не предлагаем даже при флаге
    }

    /**
     * Крупнейший размер с longSide <= maxSide. Пусто/ноль → null.
     * Чистая, тестируется на JVM.
     */
    fun pickBest(sizes: List<Pair<Int, Int>>, maxSide: Int): Pair<Int, Int>? {
        if (maxSide <= 0 || sizes.isEmpty()) return null
        val fitting = sizes.filter { maxOf(it.first, it.second) in 1..maxSide }
        if (fitting.isEmpty()) return null
        return fitting.maxByOrNull { it.first * it.second }
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    fun probe(camera: Camera, maxSide: Int = 6000): RawInfo {
        val key = try { System.identityHashCode(camera) } catch (_: Throwable) { 0 }
        cache[key]?.let { return it }
        val info = try {
            probeOnce(camera, maxSide)
        } catch (t: Throwable) {
            Log.w(TAG, "probe failed, no raw", t)
            RawInfo(false, "unknown", emptyList(), null)
        }
        cache[key] = info
        return info
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun probeOnce(camera: Camera, maxSide: Int): RawInfo {
        val ci = androidx.camera.camera2.interop.Camera2CameraInfo.from(camera.cameraInfo)
        val caps = ci.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.toSet() ?: emptySet()
        val levelInt = ci.getCameraCharacteristic(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            ?: CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
        val level = when (levelInt) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "L$levelInt"
        }
        val hasRaw = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        if (!hasRaw) {
            Log.i(TAG, "no RAW capability (level=$level)")
            return RawInfo(false, level, emptyList(), null)
        }
        val map = ci.getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val rawSizes = try {
            map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.mapNotNull {
                if (it.width > 0 && it.height > 0) it.width to it.height else null
            } ?: emptyList()
        } catch (t: Throwable) {
            Log.w(TAG, "raw sizes failed", t)
            emptyList()
        }
        val best = pickBest(rawSizes, maxSide)
        Log.i(TAG, "RAW ok level=$level sizes=$rawSizes best=$best")
        return RawInfo(true, level, rawSizes, best)
    }

    /** Сброс кэша (смена камеры). */
    fun clear() = cache.clear()

    /**
     * Проба через CameraManager (без открытой камеры): ищет заднюю камеру с RAW.
     * Для экрана настроек (видимость RAW-строки) и RAW-серии. Чистый результат,
     * тестируется через pickBest (сама проба требует девайс).
     */
    data class RawCam(
        val cameraId: String,
        val bestW: Int,
        val bestH: Int,
        val level: String,
    )

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    fun probeManager(context: android.content.Context, maxSide: Int = 6000): RawCam? {
        return try {
            val manager = context.applicationContext
                .getSystemService(android.content.Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
                ?: return null
            var best: RawCam? = null
            var bestArea = 0
            var hasRawId: String? = null
            var hasRawLevel = "L?"
            fun consider(id: String, chars: android.hardware.camera2.CameraCharacteristics) {
                val caps = chars.get(android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?.toSet() ?: emptySet()
                if (!caps.contains(android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)) return
                if (hasRawId == null) {
                    hasRawId = id
                    hasRawLevel = when (chars.get(android.hardware.camera2.CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                        android.hardware.camera2.CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                        android.hardware.camera2.CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                        else -> "L?"
                    }
                }
                val map = chars.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val sizes = try {
                    map?.getOutputSizes(android.graphics.ImageFormat.RAW_SENSOR)?.mapNotNull {
                        if (it.width > 0 && it.height > 0) it.width to it.height else null
                    } ?: emptyList()
                } catch (_: Throwable) { emptyList() }
                if (sizes.isEmpty()) return
                val pick = pickBest(sizes, maxSide) ?: return
                val area = pick.first * pick.second
                if (area <= bestArea) return
                val levelInt = chars.get(android.hardware.camera2.CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                val level = when (levelInt) {
                    android.hardware.camera2.CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                    android.hardware.camera2.CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                    else -> "L$levelInt"
                }
                bestArea = area
                best = RawCam(id, pick.first, pick.second, level)
            }
            for (id in manager.cameraIdList) {
                val chars = try { manager.getCameraCharacteristics(id) } catch (_: Throwable) { continue }
                val facing = chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                // S25U: логическая задняя — LIMITED без RAW, RAW только на физических.
                // Поэтому смотрим все ID + физические внутри каждого.
                consider(id, chars)
                try {
                    val phys = chars.physicalCameraIds
                    for (pid in phys) {
                        try {
                            val pc = manager.getCameraCharacteristics(pid)
                            consider(pid, pc)
                        } catch (_: Throwable) {}
                    }
                } catch (_: Throwable) {}
                // BACK-приоритет: задние смотрим первыми, но фронталки тоже не игнорим полностью
                if (facing != android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) continue
            }
            try {
                android.util.Log.i(TAG, "probeManager raw=$best hasRawId=$hasRawId")
            } catch (_: Throwable) {}
            // S25U-кейс: capability RAW есть, а размеров RAW_SENSOR HAL не отдал.
            // Строку показываем с бин-заглушкой 4000x3000: серия попробует,
            // при отказе HAL будет честный тост, а не мертвая кнопка.
            if (best == null && hasRawId != null && maxSide >= 3000) {
                best = RawCam(hasRawId!!, 4000, 3000, hasRawLevel)
            }
            best
        } catch (t: Throwable) {
            try { android.util.Log.w(TAG, "probeManager failed", t) } catch (_: Throwable) {}
            null
        }
    }
}
