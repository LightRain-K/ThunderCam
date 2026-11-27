package app.grapheneos.camera.ui.activities

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.grapheneos.camera.CamConfig
import app.grapheneos.camera.CapturedItems
import app.grapheneos.camera.NumInputFilter
import app.grapheneos.camera.R
import app.grapheneos.camera.databinding.MoreSettingsBinding
import app.grapheneos.camera.util.storageLocationToUiString
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

open class MoreSettings : AppCompatActivity(), TextView.OnEditorActionListener {
    private lateinit var camConfig: CamConfig

    private lateinit var binding: MoreSettingsBinding
    private lateinit var snackBar: Snackbar

    private lateinit var sLField: EditText

    private lateinit var rSLocation: Button

    private lateinit var rootView: View

    private lateinit var pQField: EditText

    private val dirPickerHandler = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val intent = it.data
        val uri = intent?.data?.let {
            if (it.toString().contains(CapturedItems.SAF_TREE_SEPARATOR)) {
                null
            } else {
                it
            }
        }
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

            val uriString = uri.toString()
            camConfig.storageLocation = uriString

            val uiString = storageLocationToUiString(this, uriString)
            sLField.setText(uiString)

            showMessage(getString(R.string.storage_location_updated, uiString))

        } else {
            showMessage(getString(R.string.no_directory_selected))
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val camConfig = obtainCamConfig(intent)
        if (camConfig == null) {
            finish()
            return
        }
        this.camConfig = camConfig

        binding = MoreSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val showStorageSettings = this !is MoreSettingsSecure

        val sIAPToggle = binding.saveImageAsPreviewToggle

        sIAPToggle.isChecked = camConfig.saveImageAsPreviewed

        sIAPToggle.setOnClickListener {
            camConfig.saveImageAsPreviewed =
                sIAPToggle.isChecked
        }

        val sVAPToggle = binding.saveVideoAsPreviewToggle

        sVAPToggle.isChecked = camConfig.saveVideoAsPreviewed

        sVAPToggle.setOnClickListener {
            camConfig.saveVideoAsPreviewed = sVAPToggle.isChecked
        }

        rootView = binding.rootView

        sLField = binding.storageLocationField

        sLField.setText(storageLocationToUiString(this, camConfig.storageLocation))

        sLField.setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            dirPickerHandler.launch(Intent.createChooser(i, getString(R.string.choose_storage_location)))
        }

        snackBar = Snackbar.make(rootView, "", Snackbar.LENGTH_LONG)

        rSLocation = binding.refreshStorageLocation
        rSLocation.setOnClickListener {

            val dialog = MaterialAlertDialogBuilder(this)

            dialog.setTitle(R.string.are_you_sure)

            dialog.setMessage(R.string.revert_to_default_directory)

            dialog.setPositiveButton(R.string.yes) { _, _ ->
                val defaultLocation = CamConfig.SettingValues.Default.STORAGE_LOCATION

                if (camConfig.storageLocation != defaultLocation) {
                    showMessage(getString(R.string.reverted_to_default_directory))
                    camConfig.storageLocation = defaultLocation
                    sLField.setText(storageLocationToUiString(this, defaultLocation))
                } else {
                    showMessage(getString(R.string.already_using_default_directory))
                }
            }

            dialog.setNegativeButton(R.string.no, null)
            dialog.show()
        }

        pQField = binding.photoQuality

        pQField.setText(camConfig.photoQuality.toString())

        pQField.filters = arrayOf(NumInputFilter(this))
        pQField.setOnEditorActionListener(this)

        val exifToggle = binding.removeExifToggle
        val exifToggleSetting = binding.removeExifSetting

        exifToggleSetting.setOnClickListener {
            if (camConfig.isInCaptureMode) {
                showMessage(
                    getString(R.string.image_taken_in_this_mode_does_not_contain_extra_data)
                )
            } else {
                exifToggle.performClick()
            }
        }

        // Lock toggle in checked state in capture mode
        if (camConfig.isInCaptureMode) {
            exifToggle.isChecked = true
            exifToggle.isEnabled = false
        } else {
            exifToggle.isChecked = camConfig.removeExifAfterCapture
        }

        exifToggle.setOnClickListener {
            camConfig.removeExifAfterCapture = exifToggle.isChecked
        }

        val gSwitch = binding.gyroscopeSettingSwitch
        gSwitch.isChecked = camConfig.gSuggestions
        gSwitch.setOnClickListener {
            camConfig.gSuggestions = gSwitch.isChecked
        }

        val gSetting = binding.gyroscopeSetting
        gSetting.setOnClickListener {
            gSwitch.performClick()
        }

        val csSwitch = binding.cameraSoundsSwitch
        csSwitch.isChecked = camConfig.enableCameraSounds
        csSwitch.setOnClickListener {
            camConfig.enableCameraSounds = csSwitch.isChecked
        }

        val csSetting = binding.cameraSoundsSetting
        csSetting.setOnClickListener {
            csSwitch.performClick()
        }

        val sIAPSetting = binding.saveImageAsPreviewSetting
        sIAPSetting.setOnClickListener {
            sIAPToggle.performClick()
        }

        val sVAPSetting = binding.saveVideoAsPreviewSetting
        sVAPSetting.setOnClickListener {
            sVAPToggle.performClick()
        }

        val sLS = binding.storageLocationSetting
        sLS.setOnClickListener {
            sLField.performClick()
        }

        // Every other row here acts on the control it holds. This one held a 36dp field and did
        // nothing, while still announcing itself as activatable.
        val pQSetting = binding.photoQualitySetting
        pQSetting.setOnClickListener {
            pQField.requestFocus()
            pQField.setSelection(pQField.text.length)
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(pQField, 0)
        }

        val zslSetting = binding.zslSetting
        if (camConfig.isZslSupported) {
            zslSetting.visibility = View.VISIBLE

            val zslToggle = binding.zslSettingToggle
            zslToggle.isChecked = camConfig.enableZsl
            zslToggle.setOnClickListener {
                camConfig.enableZsl = !camConfig.enableZsl
            }

            zslSetting.setOnClickListener {
                zslToggle.performClick()
            }
        }

        // ThunderCam P0: обработка кадра (тон/контраст/шумодав). Выкл = чистая база.
        val thmcP0Setting = binding.thmcP0Setting
        val thmcP0Toggle = binding.thmcP0SettingToggle
        thmcP0Toggle.isChecked = camConfig.thmcP0
        thmcP0Toggle.setOnClickListener {
            camConfig.thmcP0 = thmcP0Toggle.isChecked
        }
        thmcP0Setting.setOnClickListener {
            thmcP0Toggle.performClick()
        }

        // ThunderCam мастер калибровки под свой телефон (2 тапа, тепло/насыщенность)
        binding.thmcCalibrateSetting.setOnClickListener {
            ThmcCalibrateActivity.open(this, camConfig)
        }

        // ThunderCam RAW-серия: видна, только если HAL отдаёт RAW (Pova 5 — нет).
        try {
            if (app.grapheneos.camera.thmc.ThmcRawBurst.isSupported(this)) {
                binding.thmcRawSetting.visibility = View.VISIBLE
                binding.thmcRawSetting.setOnClickListener {
                    app.grapheneos.camera.thmc.ThmcRawBurst.capture(this, camConfig)
                }
            } else {
                binding.thmcRawSetting.visibility = View.GONE
            }
        } catch (_: Throwable) {
            try { binding.thmcRawSetting.visibility = View.GONE } catch (_: Throwable) {}
        }

        // ThunderCam SR-модель: каталог + скачивание.
        binding.thmcSrmodelSetting.setOnClickListener {
            app.grapheneos.camera.thmc.showSrModelDialog(this, camConfig.thmc)
        }

        // ThunderCam бенчмарк: перезапуск замера скорости (тир для ночи/зума).
        binding.thmcBenchSetting.setOnClickListener {
            app.grapheneos.camera.thmc.runBenchFlow(this, camConfig.thmc)
        }

        // ThunderCam ночь-авто: тёмная сцена сама снимает серией. Выкл = ночью одиночный.
        val thmcNightSetting = binding.thmcNightSetting
        val thmcNightToggle = binding.thmcNightSettingToggle
        thmcNightToggle.isChecked = camConfig.thmcNightAuto
        thmcNightToggle.setOnClickListener {
            camConfig.thmcNightAuto = thmcNightToggle.isChecked
            refreshThmcSummary()
        }
        thmcNightSetting.setOnClickListener {
            thmcNightToggle.performClick()
        }

        // ThunderCam HDR: серия с брекетингом. Выкл = стоковый одиночный кадр.
        val thmcHdrSetting = binding.thmcHdrSetting
        val thmcHdrToggle = binding.thmcHdrSettingToggle
        thmcHdrToggle.isChecked = camConfig.thmcHdr
        thmcHdrToggle.setOnClickListener {
            camConfig.thmcHdr = thmcHdrToggle.isChecked
            refreshThmcSummary()
        }
        thmcHdrSetting.setOnClickListener {
            thmcHdrToggle.performClick()
        }

        // ThunderCam трекинг: ведение фокуса за объектом по долгому тапу (только фото).
        val thmcTrackSetting = binding.thmcTrackSetting
        val thmcTrackToggle = binding.thmcTrackSettingToggle
        thmcTrackToggle.isChecked = camConfig.thmcTrack
        thmcTrackToggle.setOnClickListener {
            camConfig.thmcTrack = thmcTrackToggle.isChecked
        }
        thmcTrackSetting.setOnClickListener {
            thmcTrackToggle.performClick()
        }

        // ThunderCam зум: цифра поверх оптики до 20×. Выкл = предел HAL.
        val thmcZoomSetting = binding.thmcZoomSetting
        val thmcZoomToggle = binding.thmcZoomSettingToggle
        thmcZoomToggle.isChecked = camConfig.thmcZoom
        thmcZoomToggle.setOnClickListener {
            camConfig.thmcZoom = thmcZoomToggle.isChecked
        }
        thmcZoomSetting.setOnClickListener {
            thmcZoomToggle.performClick()
        }

        // Апскейл-модель: быстрый w8a8 vs мощный B160 (смена файла на лету)
        val thmcSrSetting = binding.thmcSrSetting
        val thmcSrToggle = binding.thmcSrSettingToggle
        thmcSrToggle.isChecked = camConfig.thmcSrPowerful
        thmcSrToggle.setOnClickListener {
            camConfig.thmcSrPowerful = thmcSrToggle.isChecked
            refreshThmcProfileSubtitle()
        }
        thmcSrSetting.setOnClickListener {
            thmcSrToggle.performClick()
        }
        // Авто-сцена: анализатор сцен сам выбирает пресет. Выкл = только ручной рецепт.
        val thmcAutoSceneSetting = binding.thmcAutoSceneSetting
        val thmcAutoSceneToggle = binding.thmcAutoSceneToggle
        thmcAutoSceneToggle.isChecked = camConfig.thmcAutoScene
        thmcAutoSceneToggle.setOnClickListener {
            camConfig.thmcAutoScene = thmcAutoSceneToggle.isChecked
            updatePresetSubtitle()
            refreshThmcSummary()
        }
        thmcAutoSceneSetting.setOnClickListener {
            thmcAutoSceneToggle.performClick()
        }

        // Стабилизация на зуме: 3 быстрых кадра → самый резкий.
        val thmcZoomStabToggle = binding.thmcZoomStabToggle
        thmcZoomStabToggle.isChecked = camConfig.thmcZoomStab
        thmcZoomStabToggle.setOnClickListener {
            camConfig.thmcZoomStab = thmcZoomStabToggle.isChecked
        }
        binding.thmcZoomStabSetting.setOnClickListener {
            thmcZoomStabToggle.performClick()
        }

        // Стабилизация превью (EIS по гироскопу): только картинка, захват не трогаем.
        val thmcPreviewStabToggle = binding.thmcPreviewStabToggle
        thmcPreviewStabToggle.isChecked = camConfig.thmcPreviewStab
        thmcPreviewStabToggle.setOnClickListener {
            camConfig.thmcPreviewStab = thmcPreviewStabToggle.isChecked
        }
        binding.thmcPreviewStabSetting.setOnClickListener {
            thmcPreviewStabToggle.performClick()
        }
        // Глобальная доводка текстуры: твой алгоритм, отдельная стадия после шумодава.
        val thmcGlobalRefineSetting = binding.thmcGlobalRefineSetting
        val thmcGlobalRefineToggle = binding.thmcGlobalRefineToggle
        thmcGlobalRefineToggle.isChecked = camConfig.thmcGlobalRefine
        thmcGlobalRefineToggle.setOnClickListener {
            camConfig.thmcGlobalRefine = thmcGlobalRefineToggle.isChecked
        }
        thmcGlobalRefineSetting.setOnClickListener {
            thmcGlobalRefineToggle.performClick()
        }

        val thmcAutoEvSetting = binding.thmcAutoEvSetting
        val thmcAutoEvToggle = binding.thmcAutoEvToggle
        thmcAutoEvToggle.isChecked = camConfig.thmcAutoEv
        thmcAutoEvToggle.setOnClickListener {
            camConfig.thmcAutoEv = thmcAutoEvToggle.isChecked
        }
        thmcAutoEvSetting.setOnClickListener {
            thmcAutoEvToggle.performClick()
        }

        val thmcManualEvSetting = binding.thmcManualEvSetting
        val thmcManualEvToggle = binding.thmcManualEvToggle
        thmcManualEvToggle.isChecked = camConfig.thmcManualEv
        thmcManualEvToggle.setOnClickListener {
            camConfig.thmcManualEv = thmcManualEvToggle.isChecked
        }
        thmcManualEvSetting.setOnClickListener {
            thmcManualEvToggle.performClick()
        }

        // ThunderCam: рецепт P0 — 6 слайдеров. Значение применяется сразу, переживает перезапуск.
        // Подпись пресета обновляется в updatePresetSubtitle() (член класса — вызывается
        // и из слушателей выше по коду).
        fun refreshRecipeSliders() {
            fun pcts(v: Int) = "$v%"
            binding.thmcStrengthSlider.value = camConfig.thmcP0Strength.toFloat()
            binding.thmcStrengthValue.text = if (camConfig.thmcP0Strength <= 0) getString(R.string.thmc_denoise_off) else pcts(camConfig.thmcP0Strength)
            binding.thmcToneSlider.value = camConfig.thmcTone.toFloat()
            binding.thmcToneValue.text = pcts(camConfig.thmcTone)
            binding.thmcContrastSlider.value = camConfig.thmcContrast.toFloat()
            binding.thmcContrastValue.text = pcts(camConfig.thmcContrast)
            binding.thmcSatSlider.value = camConfig.thmcSat.toFloat()
            binding.thmcSatValue.text = pcts(camConfig.thmcSat)
            binding.thmcWarmthSlider.value = camConfig.thmcWarmth.toFloat()
            binding.thmcWarmthValue.text = pcts(camConfig.thmcWarmth)
            binding.thmcDenoiseSlider.value = camConfig.thmcDenoise.toFloat()
            binding.thmcDenoiseValue.text = when {
                camConfig.thmcDenoise <= 0 -> getString(R.string.thmc_denoise_off)
                camConfig.thmcDenoise < 70 -> getString(R.string.thmc_denoise_auto)
                else -> getString(R.string.thmc_denoise_max)
            }
        }
        fun bindThmcSlider(
            slider: com.google.android.material.slider.Slider,
            value: TextView,
            get: () -> Int,
            set: (Int) -> Unit,
            format: (Int) -> String,
        ) {
            val cur = get()
            value.text = format(cur)
            slider.value = cur.toFloat()
            slider.addOnChangeListener { _, v, _ ->
                val iv = v.toInt()
                value.text = format(iv)
                set(iv)
            }
        }
        val pct = { v: Int -> "$v%" }
        // Сила 0 = обработка выкл (как тумблер). Тумблер остаётся мастер-выключателем.
        bindThmcSlider(binding.thmcStrengthSlider, binding.thmcStrengthValue,
            { camConfig.thmcP0Strength }, { camConfig.thmcP0Strength = it; updatePresetSubtitle() }) { v ->
            if (v <= 0) getString(R.string.thmc_denoise_off) else "$v%"
        }
        bindThmcSlider(binding.thmcToneSlider, binding.thmcToneValue,
            { camConfig.thmcTone }, { camConfig.thmcTone = it; updatePresetSubtitle() }, pct)
        bindThmcSlider(binding.thmcContrastSlider, binding.thmcContrastValue,
            { camConfig.thmcContrast }, { camConfig.thmcContrast = it; updatePresetSubtitle() }, pct)
        bindThmcSlider(binding.thmcSatSlider, binding.thmcSatValue,
            { camConfig.thmcSat }, { camConfig.thmcSat = it; updatePresetSubtitle() }, pct)
        bindThmcSlider(binding.thmcWarmthSlider, binding.thmcWarmthValue,
            { camConfig.thmcWarmth }, { camConfig.thmcWarmth = it; updatePresetSubtitle() }, pct)
        bindThmcSlider(binding.thmcDenoiseSlider, binding.thmcDenoiseValue,
            { camConfig.thmcDenoise }, { camConfig.thmcDenoise = it; updatePresetSubtitle(); refreshThmcProfileSubtitle() }) { v ->
            when {
                v <= 0 -> getString(R.string.thmc_denoise_off)
                v < 70 -> getString(R.string.thmc_denoise_auto)
                else -> getString(R.string.thmc_denoise_max)
            }
        }

        // ThunderCam: сброс рецепта P0 к начальным + обновление ползунков на экране
        binding.thmcRecipeResetSetting.setOnClickListener {
            camConfig.resetThmcRecipe()
            refreshRecipeSliders()
            updatePresetSubtitle()
            showMessage(getString(R.string.thmc_recipe_reset_done))
        }

        // Бленд сеть/реал в апскейле (% нейросети)
        bindThmcSlider(binding.thmcSrBlendSlider, binding.thmcSrBlendValue,
            { camConfig.thmcSrBlend }, { camConfig.thmcSrBlend = it }) { v -> "$v%" }

        // VPS-апскейл: адрес сервера (пусто = выкл).
        // Сохраняем и по DONE, и при потере фокуса (иначе выход через Back теряет ввод).
        val cloudUrlField = binding.thmcCloudUrl
        cloudUrlField.setText(camConfig.thmcCloudUrl)
        val saveCloudUrl = {
            camConfig.thmcCloudUrl = cloudUrlField.text.toString()
        }
        cloudUrlField.setOnEditorActionListener { _, id, _ ->
            if (id == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                saveCloudUrl()
                showMessage(getString(R.string.thmc_cloud_saved))
                true
            } else false
        }
        cloudUrlField.onFocusChangeListener = android.view.View.OnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveCloudUrl()
        }
        binding.thmcCloudTest.setOnClickListener {
            saveCloudUrl()
            val url = cloudUrlField.text.toString()
            if (url.isBlank()) {
                showMessage(getString(R.string.thmc_cloud_no_url))
            } else {
                showMessage(getString(R.string.thmc_cloud_working))
                Thread {
                    val ok = app.grapheneos.camera.thmc.ThmcCloud.health(url)
                    runOnUiThread {
                        showMessage(getString(if (ok) R.string.thmc_cloud_ok else R.string.thmc_cloud_unreachable))
                    }
                }.start()
            }
        }
        binding.thmcCloudUrlSetting.setOnClickListener {
            cloudUrlField.requestFocus()
            cloudUrlField.setSelection(cloudUrlField.text.length)
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(cloudUrlField, 0)
        }

        // Авто-апскейл в облаке при каждой съёмке
        val thmcCloudAutoSetting = binding.thmcCloudAutoSetting
        val thmcCloudAutoToggle = binding.thmcCloudAutoToggle
        thmcCloudAutoToggle.isChecked = camConfig.thmcCloudAuto
        thmcCloudAutoToggle.setOnClickListener {
            camConfig.thmcCloudAuto = thmcCloudAutoToggle.isChecked
        }
        thmcCloudAutoSetting.setOnClickListener {
            thmcCloudAutoToggle.performClick()
        }

        // Пресет рецепта: диалог одного тапа. Слайдеры двигаются сами, подпись — имя пресета.
        updatePresetSubtitle()
        binding.thmcPresetSetting.setOnClickListener {
            val presets = app.grapheneos.camera.thmc.ThmcPresets.ALL
            val labels = presets.map { "${getString(it.titleRes)} — ${getString(it.descRes)}" }.toTypedArray()
            var checked = presets.indexOfFirst {
                it.id == app.grapheneos.camera.thmc.ThmcPresets.matchIdByLook(
                    camConfig.thmcTone, camConfig.thmcContrast, camConfig.thmcSat,
                    camConfig.thmcWarmth
                )
            }.coerceAtLeast(0)
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.thmc_preset_title)
                .setSingleChoiceItems(labels, checked) { _, which -> checked = which }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val p = presets[checked.coerceIn(presets.indices)]
                    camConfig.thmcTone = p.tone
                    camConfig.thmcContrast = p.contrast
                    camConfig.thmcSat = p.sat
                    camConfig.thmcWarmth = p.warmth
                    camConfig.thmcDenoise = p.denoise
                    camConfig.thmc.preset = p.id
                    refreshRecipeSliders()
                    updatePresetSubtitle()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        // Профиль качества: один тап вместо трёх ручек. Подпись показывает реальные
        // текущие значения (после ручной правки она честно меняется).
        refreshThmcProfileSubtitle()
        binding.thmcProfileSetting.setOnClickListener {
            val titles = intArrayOf(
                R.string.thmc_profile_fast, R.string.thmc_profile_balanced, R.string.thmc_profile_max
            )
            val descs = intArrayOf(
                R.string.thmc_profile_fast_desc, R.string.thmc_profile_balanced_desc, R.string.thmc_profile_max_desc
            )
            val labels = titles.indices.map {
                "${getString(titles[it])} — ${getString(descs[it])}"
            }.toTypedArray()
            var checked = when {
                camConfig.thmcSrPowerful || camConfig.thmcDenoise >= 70 -> 2
                camConfig.thmcDenoise >= 55 -> 1
                else -> 0
            }
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.thmc_profile_title)
                .setSingleChoiceItems(labels, checked) { _, which -> checked = which }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    when (checked) {
                        0 -> {
                            camConfig.thmcP0Strength = 90
                            camConfig.thmcDenoise = 45
                            camConfig.thmcSrPowerful = false
                            camConfig.thmcSrBlend = 60
                        }
                        2 -> {
                            camConfig.thmcP0Strength = 100
                            camConfig.thmcDenoise = 75
                            camConfig.thmcSrPowerful = true
                            camConfig.thmcSrBlend = 75
                        }
                        else -> {
                            camConfig.thmcP0Strength = 100
                            camConfig.thmcDenoise = 60
                            camConfig.thmcSrPowerful = false
                            camConfig.thmcSrBlend = 70
                        }
                    }
                    refreshRecipeSliders()
                    binding.thmcSrBlendSlider.value = camConfig.thmcSrBlend.toFloat()
                    binding.thmcSrSettingToggle.isChecked = camConfig.thmcSrPowerful
                    updatePresetSubtitle()
                    refreshThmcProfileSubtitle()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        // Справка простым языком: что делает каждый пункт.
        binding.thmcHelpSetting.setOnClickListener {
            try {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.thmc_help_title)
                    .setMessage(R.string.thmc_help_text)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            } catch (_: Throwable) {}
        }

        // Экспертный режим: «всё под капотом» одним тумблером. Простой вид по
        // умолчанию: стиль, шумодав, ночь, HDR, авто-сюжет и сброс — без ползунков.
        binding.thmcAdvancedHeader.setOnClickListener {
            val prefs = getSharedPreferences("commons", Context.MODE_PRIVATE)
            try {
                prefs.edit().putBoolean(
                    "thmc_advanced_open", !prefs.getBoolean("thmc_advanced_open", false)
                ).apply()
            } catch (_: Throwable) {}
            applyAdvancedVisibility()
        }

        // Мастер «Умное улучшение»: один выключатель вместо четырёх.
        binding.thmcSmartToggle.isChecked = camConfig.thmcThunder
        binding.thmcSmartToggle.setOnClickListener {
            camConfig.thmcThunder = binding.thmcSmartToggle.isChecked
            // Мастер меняет отдельные тумблеры — подтягиваем их состояние на экран.
            try { binding.thmcP0SettingToggle.isChecked = camConfig.thmcP0 } catch (_: Throwable) {}
            try { binding.thmcNightSettingToggle.isChecked = camConfig.thmcNightAuto } catch (_: Throwable) {}
            try { binding.thmcHdrSettingToggle.isChecked = camConfig.thmcHdr } catch (_: Throwable) {}
            try { binding.thmcAutoSceneToggle.isChecked = camConfig.thmcAutoScene } catch (_: Throwable) {}
            applySmartEnabledState()
            refreshThmcSummary()
        }
        binding.thmcSmartSetting.setOnClickListener { binding.thmcSmartToggle.performClick() }

        binding.thmcExpertToggle.isChecked = camConfig.thmcExpertUi
        binding.thmcExpertToggle.setOnClickListener {
            camConfig.thmcExpertUi = binding.thmcExpertToggle.isChecked
            applyThmcExpertUi()
        }
        binding.thmcExpertSetting.setOnClickListener { binding.thmcExpertToggle.performClick() }

        applyThmcExpertUi()
        applySmartEnabledState()
        refreshThmcSummary()

        // ThunderCam: селектор разрешения фото (авто/макс/точные размеры HAL).
        camConfig.migrateHighestResToSelector()
        val highResSetting = binding.highestResSetting
        fun resLabel(v: String): String = when {
            v == "max" -> getString(R.string.thmc_res_max)
            v.matches(Regex("\\d+x\\d+")) -> v.replace("x", "×")
            else -> getString(R.string.thmc_res_auto)
        }
        binding.highestResSettingValue.text = resLabel(camConfig.photoResolution)
        highResSetting.setOnClickListener {
            val sizes = try { camConfig.availablePhotoResolutions() } catch (_: Throwable) { emptyList() }
            val values = mutableListOf("auto", "max")
            val labels = mutableListOf(getString(R.string.thmc_res_auto), getString(R.string.thmc_res_max))
            for (s in sizes) {
                val v = "${s.width}x${s.height}"
                if (v !in values) {
                    values.add(v)
                    labels.add("${s.width}×${s.height}")
                }
            }
            val cur = camConfig.photoResolution
            var checked = values.indexOf(cur).coerceAtLeast(0)
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.highest_res_setting)
                .setSingleChoiceItems(labels.toTypedArray(), checked) { _, which -> checked = which }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    camConfig.photoResolution = values[checked]
                    binding.highestResSettingValue.text = resLabel(values[checked])
                    showMessage(getString(R.string.highest_res_setting_desc))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        if (!showStorageSettings) {
            binding.storageLocationSettings.visibility = View.GONE
        }

        binding.appBar.setNavigationOnClickListener {
            finish()
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.scrollView) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutouts = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            v.setPadding(cutouts.left, 0, cutouts.right, systemBars.bottom)
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.appBar) { v, insets ->
            val cutouts = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            v.setPadding(cutouts.left, 0, cutouts.right, 0)
            insets
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {

        if (event.action == MotionEvent.ACTION_UP) {
            val v: View? = currentFocus
            if (v is EditText) {
                val outRect = Rect()
                v.getGlobalVisibleRect(outRect)
                if (!outRect.contains(event.rawX.toInt(), event.rawY.toInt())) {
                    clearFocus()
                    dumpData()
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun clearFocus() {
        val view = currentFocus
        if (view != null) {
            view.clearFocus()
            val imm: InputMethodManager =
                getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    override fun onPause() {
        // dispatchTouchEvent and onEditorAction only fire when the user taps outside the field
        // or presses the IME action key. Leaving the screen any other way (Back, gesture back,
        // the up arrow, Home, task switch) used to drop whatever had been typed, silently.
        // Commit here so that every exit path persists; a snackbar would be pointless on a
        // screen that is going away, so the invalid-value complaint is suppressed.
        // onCreate() can bail out before the views exist (no CamConfig in the intent) and the
        // lifecycle still runs through onPause, hence the initialization check.
        if (this::pQField.isInitialized) {
            dumpData(notifyOnInvalidValue = false)
        }
        super.onPause()
    }

    private fun dumpData(notifyOnInvalidValue: Boolean = true) {

        // Dump state of photo quality
        val quality = pQField.text.toString().toIntOrNull()
        // NumInputFilter keeps out-of-range values from being typed, but it cannot stop them being
        // deleted into place: the empty replacement it returns to reject an edit is the very edit a
        // deletion asks for, so deleting the leading digit of "10" leaves "0" behind. Committing
        // that made ImageCapture reject the quality and crash the next bind.
        if (quality == null || quality !in NumInputFilter.min..NumInputFilter.max) {
            // Revert back to the original value if invalid number was found
            pQField.setText(camConfig.photoQuality.toString())
            if (notifyOnInvalidValue) {
                showMessage(getString(R.string.invalid_photo_quality_value))
            }
        } else {
            camConfig.photoQuality = quality
        }
    }

    override fun onEditorAction(p0: TextView?, id: Int, p2: KeyEvent?): Boolean {
        return if (id == EditorInfo.IME_ACTION_DONE) {
            clearFocus()
            dumpData()
            true
        } else false
    }

    fun showMessage(msg: String) {
        snackBar.setText(msg)
        snackBar.show()
    }

    /** Подпись стиля: точное совпадение слайдеров = имя пресета, иначе «Свой». */
    private fun updatePresetSubtitle() {
        try {
            // Авто-сцена включена — рецепт выбирает камера, подпись честно говорит об этом.
            if (camConfig.thmcAutoScene) {
                binding.thmcPresetSettingSubtitle.text = getString(R.string.thmc_preset_auto)
                refreshThmcSummary()
                return
            }
            val id = app.grapheneos.camera.thmc.ThmcPresets.matchIdByLook(
                camConfig.thmcTone, camConfig.thmcContrast, camConfig.thmcSat,
                camConfig.thmcWarmth
            )
            binding.thmcPresetSettingSubtitle.text =
                getString(app.grapheneos.camera.thmc.ThmcPresets.byId(id).titleRes)
            refreshThmcSummary()
        } catch (_: Throwable) {}
    }

    /**
     * Простой / экспертный вид настроек. Простой (по умолчанию) оставляет только
     * понятные решения: стиль, шумодав, ночь, HDR, авто-сюжет. Всё остальное —
     * под тумблером «Показать все настройки»; значения при этом не меняются.
     */
    private fun applyThmcExpertUi() {
        try {
            val expert = camConfig.thmcExpertUi
            binding.thmcExpertToggle.isChecked = expert
            val rows = listOf(
                binding.thmcP0Setting,
                binding.thmcStrengthSlider.parent as View,
                binding.thmcToneSlider.parent as View,
                binding.thmcContrastSlider.parent as View,
                binding.thmcSatSlider.parent as View,
                binding.thmcWarmthSlider.parent as View,
                binding.thmcSrmodelSetting,
                binding.thmcBenchSetting,
                binding.thmcCalibrateSetting,
                binding.thmcRawSetting,
                binding.thmcSubZoomHeader,
                binding.thmcZoomSetting,
                binding.thmcSrSetting,
                binding.saveImageAsPreviewSetting,
            )
            for (row in rows) row.visibility = if (expert) View.VISIBLE else View.GONE
            applyAdvancedVisibility()
        } catch (_: Throwable) {}
    }

    /** «Тонкая настройка движка» — вложенная группа внутри экспертного режима. */
    private fun applyAdvancedVisibility() {
        try {
            val expert = camConfig.thmcExpertUi
            val prefs = getSharedPreferences("commons", Context.MODE_PRIVATE)
            val open = try { prefs.getBoolean("thmc_advanced_open", false) } catch (_: Throwable) { false }
            val rows = listOf(
                binding.thmcGlobalRefineSetting,
                binding.thmcAutoEvSetting,
                binding.thmcManualEvSetting,
                binding.thmcSrBlendBlock,
                binding.thmcSubCloudHeader,
                binding.thmcCloudAutoSetting,
                binding.thmcCloudUrlSetting,
            )
            binding.thmcAdvancedHeader.visibility = if (expert) View.VISIBLE else View.GONE
            for (row in rows) row.visibility = if (expert && open) View.VISIBLE else View.GONE
            binding.thmcAdvancedHeader.text =
                "${getString(R.string.thmc_advanced_title)} ${if (open) "▴" else "▾"}"
        } catch (_: Throwable) {}
    }

    /** Мастер выключен — зависимые переключатели гаснут, чтобы не выглядели «включёнными». */
    private fun applySmartEnabledState() {
        try {
            val on = camConfig.thmcThunder
            val rows = listOf(
                binding.thmcNightSetting, binding.thmcHdrSetting, binding.thmcAutoSceneSetting,
                binding.thmcPresetSetting, binding.thmcP0Setting, binding.thmcZoomStabSetting,
            )
            for (r in rows) { r.alpha = if (on) 1f else 0.4f; r.isEnabled = on }
            binding.thmcNightSettingToggle.isEnabled = on
            binding.thmcHdrSettingToggle.isEnabled = on
            binding.thmcAutoSceneToggle.isEnabled = on
            binding.thmcP0SettingToggle.isEnabled = on
            binding.thmcZoomStabToggle.isEnabled = on
            binding.thmcDenoiseSlider.isEnabled = on
            binding.thmcDenoiseSlider.alpha = if (on) 1f else 0.4f
        } catch (_: Throwable) {}
    }

    /** Подпись профиля: реальные текущие значения, а не название последнего нажатия. */
    private fun refreshThmcProfileSubtitle() {
        try {
            binding.thmcProfileSubtitle.text = getString(
                R.string.thmc_profile_summary,
                camConfig.thmcDenoise,
                getString(
                    if (camConfig.thmcSrPowerful) R.string.thmc_profile_sr_on
                    else R.string.thmc_profile_sr_off
                )
            )
        } catch (_: Throwable) {}
    }

    /** Сводка «что сейчас включено» — простыми словами, без терминов. */
    private fun refreshThmcSummary() {
        try {
            if (!camConfig.thmcThunder) {
                binding.thmcSummary.text = getString(R.string.thmc_sum_off)
                return
            }
            val style = if (camConfig.thmcAutoScene) {
                getString(R.string.thmc_sum_style_auto)
            } else {
                val id = app.grapheneos.camera.thmc.ThmcPresets.matchIdByLook(
                    camConfig.thmcTone, camConfig.thmcContrast, camConfig.thmcSat,
                    camConfig.thmcWarmth
                )
                getString(
                    R.string.thmc_sum_style,
                    getString(app.grapheneos.camera.thmc.ThmcPresets.byId(id).titleRes)
                )
            }
            val night = getString(
                if (camConfig.thmcNightAuto) R.string.thmc_sum_night_on else R.string.thmc_sum_night_off
            )
            val hdr = getString(
                if (camConfig.thmcHdr) R.string.thmc_sum_hdr_on else R.string.thmc_sum_hdr_off
            )
            val scene = getString(
                if (camConfig.thmcAutoScene) R.string.thmc_sum_scene_on else R.string.thmc_sum_scene_off
            )
            binding.thmcSummary.text = getString(R.string.thmc_summary, style, night, hdr, scene)
        } catch (_: Throwable) {}
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        private var camConfigId = 0L
        private var staticCamConfig: CamConfig? = null

        private const val INTENT_EXTRA_CAM_CONFIG_ID = "camConfig_id"

        fun start(caller: MainActivity) {
            val flavor = if (caller is SecureActivity) MoreSettingsSecure::class else MoreSettings::class
            Intent(caller, flavor.java).let {
                camConfigId += 1
                it.putExtra(INTENT_EXTRA_CAM_CONFIG_ID, camConfigId)
                staticCamConfig = caller.camConfig

                caller.startActivity(it)
            }
        }

        private fun obtainCamConfig(intent: Intent): CamConfig? {
            val camConfig = staticCamConfig
            if (camConfigId != intent.getLongExtra(INTENT_EXTRA_CAM_CONFIG_ID, -1)) {
                return null
            }
            return camConfig
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        if (isFinishing) {
            staticCamConfig = null
        }
    }
}
