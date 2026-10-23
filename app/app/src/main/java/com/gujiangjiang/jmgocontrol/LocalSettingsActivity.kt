package com.gujiangjiang.jmgocontrol

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.gujiangjiang.jmgocontrol.core.AppPrefs
import com.gujiangjiang.jmgocontrol.core.HolatekApi
import com.gujiangjiang.jmgocontrol.core.HolatekCmd
import com.gujiangjiang.jmgocontrol.databinding.ActivityLocalSettingsBinding
import com.gujiangjiang.jmgocontrol.databinding.ItemSettingBinding
import com.gujiangjiang.jmgocontrol.local.Kind
import com.gujiangjiang.jmgocontrol.local.SettingSpec
import com.gujiangjiang.jmgocontrol.local.SettingsPages

class LocalSettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivityLocalSettingsBinding
    private lateinit var prefs: AppPrefs
    private var api: HolatekApi? = null
    private lateinit var audio: AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private var specs: List<SettingSpec> = emptyList()
    private val rowMap = HashMap<String, ItemSettingBinding>()
    private val sliderListeners = HashMap<String, SeekBar.OnSeekBarChangeListener>()

    private val page: String
        get() = intent.getStringExtra(EXTRA_PAGE) ?: PAGE_GENERAL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLocalSettingsBinding.inflate(layoutInflater)
        setContentView(b.root)

        prefs = AppPrefs(this)
        api = HolatekApi.create(this)
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        if (page == PAGE_LAB && !prefs.labUnlocked) {
            Toast.makeText(this, R.string.lab_locked_toast, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        b.title.text = when (page) {
            PAGE_ADVANCED -> getString(R.string.entry_advanced)
            PAGE_LAB -> getString(R.string.entry_lab)
            else -> getString(R.string.entry_general)
        }
        if (api == null) {
            b.banner.visibility = View.VISIBLE
            b.banner.text = getString(R.string.holatek_unavailable)
        } else {
            b.banner.visibility = View.GONE
        }
        build()
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    private fun build() {
        specs = when (page) {
            PAGE_ADVANCED -> SettingsPages.advanced()
            PAGE_LAB -> SettingsPages.lab()
            else -> SettingsPages.general()
        }
        val inflater = LayoutInflater.from(this)
        for (spec in specs) {
            val row = ItemSettingBinding.inflate(inflater, b.container, false)
            row.title.text = spec.title
            if (spec.subtitle.isNullOrEmpty()) {
                row.subtitle.visibility = View.GONE
            } else {
                row.subtitle.visibility = View.VISIBLE
                row.subtitle.text = spec.subtitle
            }
            when (spec.kind) {
                Kind.SLIDER -> {
                    row.slider.visibility = View.VISIBLE
                    setUpSlider(spec, row)
                }
                Kind.INFO -> {
                    row.slider.visibility = View.GONE
                    row.root.isClickable = false
                    row.root.isFocusable = false
                }
                else -> {
                    row.slider.visibility = View.GONE
                    row.root.setOnClickListener { onRowClick(spec) }
                }
            }
            b.container.addView(row.root)
            rowMap[spec.id] = row
        }
    }

    // ---------- 读取 ----------

    private fun readInt(spec: SettingSpec): Int? = when {
        spec.isAudioVolume -> audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        spec.isAudioMute -> if (audio.isStreamMute(AudioManager.STREAM_MUSIC)) 1 else 0
        else -> api?.get(spec.service, spec.cmd)
    }

    private fun sliderRange(spec: SettingSpec): Pair<Int, Int> {
        if (spec.isAudioVolume) {
            return 0 to audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        }
        if (spec.id == "luminance") {
            val max = api?.max(spec.cmd)?.takeIf { it > 0 } ?: spec.max
            val floor = prefs.brightnessFloor.coerceIn(0, max)
            return floor to max.coerceAtLeast(floor + 1)
        }
        val mn = api?.min(spec.cmd) ?: spec.min
        var mx = api?.max(spec.cmd) ?: spec.max
        if (mx <= mn) mx = mn + 1
        return mn to mx
    }

    private fun refreshAll() {
        for (spec in specs) refreshRow(spec)
    }

    private fun refreshRow(spec: SettingSpec) {
        val row = rowMap[spec.id] ?: return
        val display: String = when {
            spec.isStringInfo -> api?.getString(spec.service, spec.cmd) ?: "--"
            else -> {
                val cur = readInt(spec)
                if (cur == null) "--"
                else spec.options.firstOrNull { it.value == cur }?.label ?: cur.toString()
            }
        }
        row.value.text = display

        if (spec.kind == Kind.SLIDER) {
            val (mn, mx) = sliderRange(spec)
            val v = readInt(spec) ?: mn
            row.slider.max = (mx - mn).coerceAtLeast(1)
            val listener = sliderListeners[spec.id]
            row.slider.setOnSeekBarChangeListener(null)
            row.slider.progress = (v - mn).coerceIn(0, row.slider.max)
            if (listener != null) row.slider.setOnSeekBarChangeListener(listener)
        }
    }

    // ---------- 应用 ----------

    private fun setUpSlider(spec: SettingSpec, row: ItemSettingBinding) {
        val (mn, mx) = sliderRange(spec)
        row.slider.max = (mx - mn).coerceAtLeast(1)
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) row.value.text = (mn + progress).toString()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                applySliderValue(spec, mn + (seekBar?.progress ?: 0))
            }
        }
        sliderListeners[spec.id] = listener
        row.slider.setOnSeekBarChangeListener(listener)
    }

    private fun applySliderValue(spec: SettingSpec, value: Int) {
        if (spec.isAudioVolume) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
            refreshRow(spec)
            return
        }
        var v = value
        if (spec.id == "luminance") {
            val floor = prefs.brightnessFloor
            if (v < floor) {
                v = floor
                Toast.makeText(this, getString(R.string.bri_floor_toast, floor), Toast.LENGTH_SHORT)
                    .show()
            }
            if (v <= 0) v = 1
        }
        if (spec.risky) {
            applyWithRevert(spec, v)
        } else {
            api?.set(spec.service, spec.cmd, v)
            refreshRow(spec)
        }
    }

    private fun applyValue(spec: SettingSpec, value: Int) {
        if (spec.isAudioMute) {
            audio.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (value == 1) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                0
            )
            refreshRow(spec)
            return
        }
        if (spec.risky) {
            applyWithRevert(spec, value)
        } else {
            api?.set(spec.service, spec.cmd, value)
            refreshRow(spec)
        }
    }

    /** 风险设置：应用后弹倒计时确认，超时自动恢复原值 */
    private fun applyWithRevert(spec: SettingSpec, newValue: Int) {
        val old = api?.get(spec.service, spec.cmd)
        api?.set(spec.service, spec.cmd, newValue)
        var confirmed = false
        var remain = REVERT_SECONDS

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.cd_title, spec.title))
            .setMessage(getString(R.string.cd_msg, remain))
            .setCancelable(false)
            .setPositiveButton(R.string.cd_keep) { _, _ -> confirmed = true }
            .create()

        val runnable = object : Runnable {
            override fun run() {
                if (confirmed || !dialog.isShowing) return
                remain--
                if (remain <= 0) {
                    if (old != null) api?.set(spec.service, spec.cmd, old)
                    dialog.dismiss()
                    Toast.makeText(
                        this@LocalSettingsActivity,
                        R.string.cd_reverted,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    dialog.findViewById<TextView>(android.R.id.message)?.text =
                        getString(R.string.cd_msg, remain)
                    handler.postDelayed(this, 1000)
                }
            }
        }
        dialog.setOnDismissListener {
            handler.removeCallbacks(runnable)
            refreshAll()
        }
        dialog.show()
        handler.postDelayed(runnable, 1000)
    }

    // ---------- 交互 ----------

    private fun onRowClick(spec: SettingSpec) {
        when (spec.kind) {
            Kind.LIST -> withLabConfirm(spec) { showListDialog(spec) }
            Kind.SWITCH -> withLabConfirm(spec) {
                val cur = readInt(spec) ?: 0
                val on = spec.options.firstOrNull { it.value != 0 }?.value ?: 1
                val off = spec.options.firstOrNull { it.value == 0 }?.value ?: 0
                applyValue(spec, if (cur == 0) on else off)
            }
            Kind.ACTION -> withLabConfirm(spec) { runAction(spec) }
            Kind.RGB -> withLabConfirm(spec) { showRgbDialog(spec) }
            else -> {}
        }
    }

    private fun withLabConfirm(spec: SettingSpec, block: () -> Unit) {
        if (!spec.doubleConfirm) {
            block()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.lab_warn_title)
            .setMessage(getString(R.string.lab_warn_msg, spec.title))
            .setPositiveButton(R.string.dialog_ok) { _, _ -> block() }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showListDialog(spec: SettingSpec) {
        val labels = spec.options.map { it.label }.toTypedArray()
        val cur = readInt(spec)
        val checked = spec.options.indexOfFirst { it.value == cur }
        AlertDialog.Builder(this)
            .setTitle(spec.title)
            .setSingleChoiceItems(labels, checked) { d, which ->
                d.dismiss()
                applyValue(spec, spec.options[which].value)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun runAction(spec: SettingSpec) {
        val action = spec.broadcastAction
        if (action != null) {
            try {
                sendBroadcast(Intent(action))
                Toast.makeText(this, R.string.action_done, Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                // ignore
            }
            return
        }
        val wv = spec.writeValue
        if (wv != null) {
            api?.set(spec.service, spec.cmd, wv)
            Toast.makeText(this, R.string.action_done, Toast.LENGTH_SHORT).show()
            refreshAll()
        }
    }

    private fun showRgbDialog(spec: SettingSpec) {
        val maxR = api?.max(HolatekCmd.COLOR_RGB)?.takeIf { it > 0 } ?: 100
        val maxG = api?.max(HolatekCmd.COLOR_G)?.takeIf { it > 0 } ?: maxR
        val maxB = api?.max(HolatekCmd.COLOR_B)?.takeIf { it > 0 } ?: maxR
        val cur = api?.getArray(HolatekCmd.SVC_DLP, HolatekCmd.COLOR_RGB) ?: intArrayOf(0, 0, 0)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val etR = rgbField(box, "R (0-$maxR)", cur.getOrElse(0) { 0 })
        val etG = rgbField(box, "G (0-$maxG)", cur.getOrElse(1) { 0 })
        val etB = rgbField(box, "B (0-$maxB)", cur.getOrElse(2) { 0 })

        AlertDialog.Builder(this)
            .setTitle(spec.title)
            .setView(box)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val r = (etR.text.toString().toIntOrNull() ?: 0).coerceIn(0, maxR)
                val g = (etG.text.toString().toIntOrNull() ?: 0).coerceIn(0, maxG)
                val bl = (etB.text.toString().toIntOrNull() ?: 0).coerceIn(0, maxB)
                api?.setArray(HolatekCmd.SVC_DLP, HolatekCmd.COLOR_RGB, intArrayOf(r, g, bl))
                refreshAll()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun rgbField(parent: LinearLayout, hint: String, value: Int): EditText {
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            this.hint = hint
            setText(value.toString())
        }
        parent.addView(et)
        return et
    }

    companion object {
        const val EXTRA_PAGE = "page"
        const val PAGE_GENERAL = "general"
        const val PAGE_ADVANCED = "advanced"
        const val PAGE_LAB = "lab"
        private const val REVERT_SECONDS = 15
    }
}
