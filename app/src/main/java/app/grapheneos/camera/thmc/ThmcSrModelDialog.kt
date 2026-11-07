package app.grapheneos.camera.thmc

import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import app.grapheneos.camera.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

/**
 * ThunderCam выбор SR-модели: вшитые + скачиваемые (x4plus с HF, своя ссылка).
 * Качество/скорость/мегабайты честно в подписях. Скачивание с прогрессом,
 * валидация тензоров, выбор сохраняется в srModel (скачанные бьют вшитые).
 */
fun showSrModelDialog(activity: AppCompatActivity, settings: ThmcSettings) {
    val ru = Locale.getDefault().language == "ru"
    val density = activity.resources.displayMetrics.density
    fun dp(v: Int) = (v * density).toInt()

    val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(8), dp(16), 0)
    }
    val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    root.addView(list)

    val progStatus = TextView(activity).apply {
        gravity = Gravity.CENTER
        textSize = 12f
        visibility = View.GONE
        setPadding(0, dp(8), 0, 0)
    }
    val progBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        visibility = View.GONE
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }
    root.addView(progStatus)
    root.addView(progBar)

    // Своя ссылка (только для custom).
    val urlField = EditText(activity).apply {
        hint = if (ru) "https://… .tflite" else "https://… .tflite"
        inputType = InputType.TYPE_TEXT_VARIATION_URI
        visibility = View.GONE
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }
    root.addView(urlField)

    var dlg: AlertDialog? = null
    var busy = false

    fun refresh() {
        list.removeAllViews()
        val sel = try { settings.srModel } catch (_: Throwable) { "auto" }
        for (e in ThmcSrModels.catalog()) {
            val downloadable = e.url.isNotBlank() || e.id == ThmcSrModels.SEL_CUSTOM
            val has = if (downloadable && e.id != ThmcSrModels.SEL_CUSTOM)
                ThmcSrModels.isDownloaded(activity, e.id) else false
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val texts = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val mark = if (sel == e.id) "• " else ""
            texts.addView(TextView(activity).apply {
                text = mark + if (ru) e.titleRu else e.titleEn
                textSize = 15f
            })
            var desc = if (ru) e.descRu else e.descEn
            if (e.id != ThmcSrModels.SEL_CUSTOM && downloadable) {
                desc += if (has) {
                    val mb = try { ThmcSrModels.modelFile(activity, e.id).length() / 1024 / 1024 } catch (_: Throwable) { 0 }
                    if (ru) " Скачана ($mb МБ)." else " Downloaded ($mb MB)."
                } else if (ru) " Не скачана." else " Not downloaded."
            }
            texts.addView(TextView(activity).apply {
                text = desc
                textSize = 12f
            })
            row.addView(texts)
            val btn = Button(activity).apply {
                text = when {
                    !downloadable -> if (ru) "Выбрать" else "Select"
                    e.id == ThmcSrModels.SEL_CUSTOM -> if (ru) "Качнуть" else "Fetch"
                    has -> if (ru) "Выбрать" else "Select"
                    else -> if (ru) "Скачать" else "Get"
                }
            }
            btn.setOnClickListener {
                if (busy) return@setOnClickListener
                if (!downloadable || (has && e.id != ThmcSrModels.SEL_CUSTOM)) {
                    try { settings.srModel = e.id } catch (_: Throwable) {}
                    try { dlg?.dismiss() } catch (_: Throwable) {}
                    return@setOnClickListener
                }
                if (e.id == ThmcSrModels.SEL_CUSTOM) {
                    if (urlField.visibility != View.VISIBLE) {
                        urlField.visibility = View.VISIBLE
                        return@setOnClickListener
                    }
                    val u = urlField.text.toString()
                    if (!u.trim().startsWith("https://")) {
                        toast(activity, if (ru) "Нужна https-ссылка" else "Need an https link")
                        return@setOnClickListener
                    }
                    startDownload(activity, settings, e.id, u, progStatus, progBar,
                        onBusy = { busy = it }, onDone = { refresh() })
                    return@setOnClickListener
                }
                // Скачать каталожную, затем выбрать.
                startDownload(activity, settings, e.id, e.url, progStatus, progBar,
                    onBusy = { busy = it },
                    onDone = {
                        try { settings.srModel = e.id } catch (_: Throwable) {}
                        refresh()
                    })
            }
            row.addView(btn)
            list.addView(row)
        }
        // auto cranked (по тумблеру Pro): отдельной строкой.
        val autoRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val autoTexts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val selNow = try { settings.srModel } catch (_: Throwable) { "auto" }
        autoTexts.addView(TextView(activity).apply {
            text = (if (selNow == "auto") "• " else "") +
                if (ru) "Авто (по тумблеру Pro)" else "Auto (Pro toggle)"
            textSize = 15f
        })
        autoTexts.addView(TextView(activity).apply {
            text = if (ru) "Как было: вшитая быстрая или Pro." else "As before: bundled fast or Pro."
            textSize = 12f
        })
        autoRow.addView(autoTexts)
        val autoBtn = Button(activity).apply { text = if (ru) "Выбрать" else "Select" }
        autoBtn.setOnClickListener {
            if (busy) return@setOnClickListener
            try { settings.srModel = ThmcSrModels.SEL_AUTO } catch (_: Throwable) {}
            try { dlg?.dismiss() } catch (_: Throwable) {}
        }
        autoRow.addView(autoBtn)
        list.addView(autoRow)
    }

    refresh()
    dlg = MaterialAlertDialogBuilder(activity)
        .setTitle(if (ru) "SR-модель" else "SR model")
        .setView(root)
        .setPositiveButton(android.R.string.ok, null)
        .show()
}

private fun startDownload(
    activity: AppCompatActivity,
    settings: ThmcSettings,
    id: String,
    url: String,
    status: TextView,
    bar: ProgressBar,
    onBusy: (Boolean) -> Unit,
    onDone: () -> Unit,
) {
    onBusy(true)
    status.visibility = View.VISIBLE
    bar.visibility = View.VISIBLE
    bar.progress = 0
    status.text = "0%"
    Thread {
        try {
            ThmcSrModels.download(activity, id, url) { p, done, total ->
                activity.runOnUiThread {
                    try {
                        if (p >= 0) {
                            bar.isIndeterminate = false
                            bar.progress = p
                            status.text = "$p%"
                        } else {
                            // Сервер скрыл длину — показываем мегабайты.
                            bar.isIndeterminate = true
                            val mb = done / (1024.0 * 1024.0)
                            status.text = "%.1f МБ".format(mb)
                        }
                    } catch (_: Throwable) {}
                }
            }
            activity.runOnUiThread {
                try {
                    status.visibility = View.GONE
                    bar.visibility = View.GONE
                    onBusy(false)
                    toast(activity, "OK")
                    onDone()
                } catch (_: Throwable) {}
            }
        } catch (t: Throwable) {
            activity.runOnUiThread {
                try {
                    status.visibility = View.GONE
                    bar.visibility = View.GONE
                    onBusy(false)
                    toast(activity, "${t.message ?: "fail"}")
                } catch (_: Throwable) {}
            }
        }
    }.start()
}

private fun toast(activity: AppCompatActivity, msg: String) {
    try {
        android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_SHORT).show()
    } catch (_: Throwable) {}
}
