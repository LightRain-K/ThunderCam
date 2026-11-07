package app.grapheneos.camera.thmc

import android.content.SharedPreferences

/**
 * ThunderCam-настройки поверх общих prefs.
 *
 * Зачем: CamConfig — god-object (камера + prefs + UI), его нельзя покрыть тестами.
 * Здесь только персистентность THMC_* + чистая логика в companion (без Android —
 * тестируется обычными JVM-тестами через MapStore).
 * CamConfig делегирует сюда, публичный API camConfig.thmc* не меняется.
 *
 * Ключи/дефолты продублированы из CamConfig.SettingValues осознанно: prefs-ключи —
 * стабильное хранилище (менять строки нельзя), а чтение SettingValues.Default на JVM
 * дёргает инициализацию всего объекта (там Quality.HIGHEST и т.п.). Значения обязаны
 * совпадать — это проверяет KeysParityTest.
 */
class ThmcSettings(private val store: KvStore) {

    object Keys {
        const val THUNDER = "thmc_thunder"
        const val P0 = "thmc_p0"
        const val P0_STRENGTH = "thmc_p0_strength"
        const val HDR = "thmc_hdr"
        const val TRACK = "thmc_track"
        const val AUTO_SCENE = "thmc_auto_scene"
        const val AUTO_EV = "thmc_auto_ev"
        const val MANUAL_EV = "thmc_manual_ev"
        const val CALIBRATED = "thmc_calibrated"
        const val ZOOM = "thmc_zoom"
        const val SR_POWERFUL = "thmc_sr_powerful"
        const val SR_MODEL = "thmc_sr_model"
        const val SR_BLEND = "thmc_sr_blend"
        const val CLOUD_URL = "thmc_cloud_url"
        const val CLOUD_AUTO = "thmc_cloud_auto"
        const val NIGHT_AUTO = "thmc_night_auto"
        const val BENCH_TIER = "thmc_bench_tier"
        const val BENCH_DONE = "thmc_bench_done"
        const val BENCH_SCORE = "thmc_bench_score"
        const val TONE = "thmc_tone"
        const val CONTRAST = "thmc_contrast"
        const val SAT = "thmc_sat"
        const val WARMTH = "thmc_warmth"
        const val DENOISE = "thmc_denoise"
        const val PRESET = "thmc_preset"
        const val GLOBAL_REFINE = "thmc_global_refine"
        const val PHOTO_RESOLUTION = "photo_resolution"
    }

    object Defaults {
        const val THUNDER = true
        const val P0 = true
        const val P0_STRENGTH = 100
        const val TONE = 66
        const val CONTRAST = 66
        const val SAT = 68
        const val WARMTH = 50
        const val DENOISE = 60
        const val PRESET = "standard"
        const val GLOBAL_REFINE = true
        const val HDR = false
        const val TRACK = true
        const val AUTO_SCENE = true
        const val AUTO_EV = true
        const val MANUAL_EV = true
        const val CALIBRATED = false
        const val SR_POWERFUL = false
        const val SR_MODEL = "auto"
        const val SR_BLEND = 70
        const val CLOUD_URL = ""
        const val CLOUD_AUTO = false
        const val NIGHT_AUTO = true
        const val BENCH_TIER = "auto"
        const val BENCH_DONE = false
        const val BENCH_SCORE = 0f
        const val PHOTO_RESOLUTION = "auto"
    }

    // --- Мастер Thunder: один тумблер вместо 4 (HDR + ночь-авто + P0 + авто-сцена).
    // Выкл = стоковая съемка GrapheneOS без вычислений. Сила — отдельный слайдер p0Strength.
    var thunder: Boolean
        get() = store.getBoolean(Keys.THUNDER, Defaults.THUNDER)
        set(value) {
            store.putBoolean(Keys.THUNDER, value)
            if (!value) {
                store.putBoolean(Keys.HDR, false)
                store.putBoolean(Keys.NIGHT_AUTO, false)
                store.putBoolean(Keys.P0, false)
                store.putBoolean(Keys.AUTO_SCENE, false)
            } else {
                store.putBoolean(Keys.P0, true)
                store.putBoolean(Keys.NIGHT_AUTO, true)
                store.putBoolean(Keys.AUTO_SCENE, true)
                // HDR оставляем как был (по дефолту выкл — дневной fixed-burst включается отдельно).
            }
        }

    // --- P0 ---
    var p0: Boolean
        get() = store.getBoolean(Keys.P0, Defaults.P0)
        set(value) = store.putBoolean(Keys.P0, value)

    var p0Strength: Int
        get() = store.getInt(Keys.P0_STRENGTH, Defaults.P0_STRENGTH).coerceIn(1, 100)
        set(value) = store.putInt(Keys.P0_STRENGTH, value.coerceIn(0, 100))

    var hdr: Boolean
        get() = store.getBoolean(Keys.HDR, Defaults.HDR)
        set(value) = store.putBoolean(Keys.HDR, value)

    var track: Boolean
        get() = store.getBoolean(Keys.TRACK, Defaults.TRACK)
        set(value) = store.putBoolean(Keys.TRACK, value)

    var autoScene: Boolean
        get() = store.getBoolean(Keys.AUTO_SCENE, Defaults.AUTO_SCENE)
        set(value) = store.putBoolean(Keys.AUTO_SCENE, value)

    var globalRefine: Boolean
        get() = store.getBoolean(Keys.GLOBAL_REFINE, Defaults.GLOBAL_REFINE)
        set(value) = store.putBoolean(Keys.GLOBAL_REFINE, value)

    var autoEv: Boolean
        get() = store.getBoolean(Keys.AUTO_EV, Defaults.AUTO_EV)
        set(value) = store.putBoolean(Keys.AUTO_EV, value)

    var manualEv: Boolean
        get() = store.getBoolean(Keys.MANUAL_EV, Defaults.MANUAL_EV)
        set(value) = store.putBoolean(Keys.MANUAL_EV, value)

    var calibrated: Boolean
        get() = store.getBoolean(Keys.CALIBRATED, Defaults.CALIBRATED)
        set(value) = store.putBoolean(Keys.CALIBRATED, value)

    // --- SR / облако ---
    var srBlend: Int
        get() = store.getInt(Keys.SR_BLEND, Defaults.SR_BLEND).coerceIn(0, 100)
        set(value) {
            val v = value.coerceIn(0, 100)
            store.putInt(Keys.SR_BLEND, v)
            com.example.thundercam20.imageprocessing.SuperResZoom.blendPercent = v
        }

    var srPowerful: Boolean
        get() = store.getBoolean(Keys.SR_POWERFUL, Defaults.SR_POWERFUL)
        set(value) {
            store.putBoolean(Keys.SR_POWERFUL, value)
            com.example.thundercam20.imageprocessing.SuperResZoom.powerful = value
        }

    /** Выбор SR-модели: auto/fast/pro/x4plus/custom (скачанные бьют вшитые). */
    var srModel: String
        get() = (store.getString(Keys.SR_MODEL, Defaults.SR_MODEL) ?: Defaults.SR_MODEL).trim().lowercase()
        set(value) = store.putString(Keys.SR_MODEL, value.trim().lowercase())

    var cloudUrl: String
        get() = store.getString(Keys.CLOUD_URL, Defaults.CLOUD_URL) ?: ""
        set(value) = store.putString(Keys.CLOUD_URL, value.trim())

    var cloudAuto: Boolean
        get() = store.getBoolean(Keys.CLOUD_AUTO, Defaults.CLOUD_AUTO)
        set(value) = store.putBoolean(Keys.CLOUD_AUTO, value)

    // Ночь-авто: тёмная сцена сама снимает серией (короткие кадры + стек).
    var nightAuto: Boolean
        get() = store.getBoolean(Keys.NIGHT_AUTO, Defaults.NIGHT_AUTO)
        set(value) = store.putBoolean(Keys.NIGHT_AUTO, value)

    // Бенчмарк скорости: замеренный тир / флаг прохождения / MПикс-с замера.
    var benchTier: String
        get() = store.getString(Keys.BENCH_TIER, Defaults.BENCH_TIER) ?: Defaults.BENCH_TIER
        set(value) = store.putString(Keys.BENCH_TIER, value)

    var benchDone: Boolean
        get() = store.getBoolean(Keys.BENCH_DONE, Defaults.BENCH_DONE)
        set(value) = store.putBoolean(Keys.BENCH_DONE, value)

    var benchScore: Float
        get() = store.getFloat(Keys.BENCH_SCORE, Defaults.BENCH_SCORE)
        set(value) = store.putFloat(Keys.BENCH_SCORE, value)

    // --- Рецепт P0 по стадиям ---
    private fun stage(key: String, def: Int): Int =
        store.getInt(key, def).coerceIn(0, 100)

    private fun setStage(key: String, value: Int) =
        store.putInt(key, value.coerceIn(0, 100))

    var tone: Int
        get() = stage(Keys.TONE, Defaults.TONE)
        set(value) = setStage(Keys.TONE, value)

    var contrast: Int
        get() = stage(Keys.CONTRAST, Defaults.CONTRAST)
        set(value) = setStage(Keys.CONTRAST, value)

    var sat: Int
        get() = stage(Keys.SAT, Defaults.SAT)
        set(value) = setStage(Keys.SAT, value)

    var warmth: Int
        get() = stage(Keys.WARMTH, Defaults.WARMTH)
        set(value) = setStage(Keys.WARMTH, value)

    var denoise: Int
        get() = stage(Keys.DENOISE, Defaults.DENOISE)
        set(value) = setStage(Keys.DENOISE, value)

    /** Именованный пресет рецепта; ручное движение слайдеров ставит custom. */
    var preset: String
        get() = (store.getString(Keys.PRESET, Defaults.PRESET) ?: Defaults.PRESET).trim().lowercase()
        set(value) = store.putString(Keys.PRESET, value.trim().lowercase().ifEmpty { Defaults.PRESET })

    /** Сброс рецепта P0 к начальным (слайдеры). Тумблеры не трогает. */
    fun resetRecipe() {
        store.putInt(Keys.P0_STRENGTH, Defaults.P0_STRENGTH)
        store.putInt(Keys.TONE, Defaults.TONE)
        store.putInt(Keys.CONTRAST, Defaults.CONTRAST)
        store.putInt(Keys.SAT, Defaults.SAT)
        store.putInt(Keys.WARMTH, Defaults.WARMTH)
        store.putInt(Keys.DENOISE, Defaults.DENOISE)
    }

    // --- Разрешение фото ---
    var photoResolution: String
        get() = normalizePhotoResolution(store.getString(Keys.PHOTO_RESOLUTION, Defaults.PHOTO_RESOLUTION))
        set(value) = store.putString(Keys.PHOTO_RESOLUTION, normalizePhotoResolution(value))

    companion object {
        /** auto/max/WxH, всё остальное → auto. Чистая, тестируется на JVM. */
        fun normalizePhotoResolution(v: String?): String {
            val s = v?.trim()?.lowercase() ?: "auto"
            return if (s == "auto" || s == "max" || s.matches(Regex("\\d+x\\d+"))) s else "auto"
        }

        /**
         * Разбор WxH в (w, h), null если auto/max/мусор/меньше 320×240.
         * Чистая, тестируется на JVM (без android.util.Size).
         */
        fun parseWxH(v: String?): Pair<Int, Int>? {
            return try {
                val s = normalizePhotoResolution(v)
                if (!s.matches(Regex("\\d+x\\d+"))) return null
                val (w, h) = s.split("x").map { it.toInt() }
                if (w < 320 || h < 240) null else w to h
            } catch (_: Throwable) { null }
        }
    }
}

/** Минимальный KV-фасад: SharedPreferences в проде, MapStore в тестах. */
interface KvStore {
    fun getBoolean(key: String, def: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getInt(key: String, def: Int): Int
    fun putInt(key: String, value: Int)
    fun getFloat(key: String, def: Float): Float
    fun putFloat(key: String, value: Float)
    fun getString(key: String, def: String?): String?
    fun putString(key: String, value: String?)
}

class SharedPrefsStore(private val prefs: SharedPreferences) : KvStore {
    override fun getBoolean(key: String, def: Boolean) = prefs.getBoolean(key, def)
    override fun putBoolean(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
    override fun getInt(key: String, def: Int) = prefs.getInt(key, def)
    override fun putInt(key: String, value: Int) { prefs.edit().putInt(key, value).apply() }
    override fun getFloat(key: String, def: Float) = prefs.getFloat(key, def)
    override fun putFloat(key: String, value: Float) { prefs.edit().putFloat(key, value).apply() }
    override fun getString(key: String, def: String?) = prefs.getString(key, def)
    override fun putString(key: String, value: String?) { prefs.edit().putString(key, value).apply() }
}

/** In-memory фасад для юнит-тестов (дефолтов не знает — их даёт ThmcSettings). */
class MapStore : KvStore {
    private val map = HashMap<String, Any?>()
    override fun getBoolean(key: String, def: Boolean) = map[key] as? Boolean ?: def
    override fun putBoolean(key: String, value: Boolean) { map[key] = value }
    override fun getInt(key: String, def: Int) = map[key] as? Int ?: def
    override fun putInt(key: String, value: Int) { map[key] = value }
    override fun getFloat(key: String, def: Float) = map[key] as? Float ?: def
    override fun putFloat(key: String, value: Float) { map[key] = value }
    override fun getString(key: String, def: String?) = map[key] as? String ?: def
    override fun putString(key: String, value: String?) { map[key] = value }
}
