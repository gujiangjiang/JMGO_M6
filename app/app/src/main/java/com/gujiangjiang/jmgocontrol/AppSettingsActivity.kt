package com.gujiangjiang.jmgocontrol

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.gujiangjiang.jmgocontrol.core.BaseActivity
import com.gujiangjiang.jmgocontrol.agent.AgentService
import com.gujiangjiang.jmgocontrol.core.AppPrefs
import com.gujiangjiang.jmgocontrol.databinding.ActivityAppSettingsBinding

class AppSettingsActivity : BaseActivity() {

    private lateinit var b: ActivityAppSettingsBinding
    private lateinit var prefs: AppPrefs
    private val labTracker = com.gujiangjiang.jmgocontrol.core.LabUnlockTracker()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityAppSettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = AppPrefs(this)

        b.rowLocalMode.setOnClickListener { switchMode(AppPrefs.MODE_LOCAL) }
        b.rowRemoteMode.setOnClickListener { switchMode(AppPrefs.MODE_REMOTE) }
        b.rowBriFloor.setOnClickListener { editBrightnessFloor() }
        b.rowLab.setOnClickListener { onLabRowClick() }
        b.rowAgent.setOnClickListener {
            prefs.agentEnabled = !prefs.agentEnabled
            if (prefs.agentEnabled) {
                AgentService.start(this)
            } else {
                AgentService.stop(this)
            }
            render()
        }
        b.rowAgent.setOnLongClickListener {
            showPinDialog()
            true
        }
        b.rowRepo.setOnClickListener { openRepo() }
        b.rowAbout.setOnClickListener { showAboutDialog() }
        b.rowLang.setOnClickListener { showLangDialog() }
        render()
    }

    private fun showLangDialog() {
        val values = arrayOf(AppPrefs.LANG_SYSTEM, AppPrefs.LANG_ZH, AppPrefs.LANG_EN)
        val labels = arrayOf(
            getString(R.string.lang_system),
            getString(R.string.lang_zh),
            getString(R.string.lang_en)
        )
        val pad = (8 * resources.displayMetrics.density).toInt()
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.as_lang)
            .setView(box)
            .setNegativeButton(R.string.dialog_cancel, null)
            .create()
        for (i in values.indices) {
            val tv = android.widget.TextView(this).apply {
                text = labels[i]
                textSize = 16f
                val selected = prefs.lang == values[i]
                setTextColor(getColor(if (selected) R.color.accent else R.color.text_primary))
                setPadding(pad * 2, pad * 2, pad * 2, pad * 2)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dialog.dismiss()
                    prefs.lang = values[i]
                    App.applyLang(values[i])
                    Toast.makeText(this@AppSettingsActivity, R.string.lang_switched, Toast.LENGTH_SHORT)
                        .show()
                    recreate()
                }
            }
            box.addView(tv)
        }
        dialog.show()
    }

    private fun showAboutDialog() {
        com.gujiangjiang.jmgocontrol.ui.About.show(this)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !prefs.labUnlocked) {
            if (labTracker.onKey(event.keyCode)) {
                unlockLab()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun render() {
        val local = prefs.mode == AppPrefs.MODE_LOCAL

        b.localModeTitle.setTextColor(getColor(if (local) R.color.accent else R.color.text_primary))
        b.localModeSub.text = if (local) getString(R.string.as_mode_current) else ""
        b.localModeSub.visibility = if (local) View.VISIBLE else View.GONE
        b.rowLocalMode.setBackgroundResource(
            if (local) R.drawable.bg_entry_active else R.drawable.bg_entry
        )

        b.remoteModeTitle.setTextColor(getColor(if (!local) R.color.accent else R.color.text_primary))
        b.remoteModeSub.text = if (!local) getString(R.string.as_mode_current) else ""
        b.remoteModeSub.visibility = if (!local) View.VISIBLE else View.GONE
        b.rowRemoteMode.setBackgroundResource(
            if (!local) R.drawable.bg_entry_active else R.drawable.bg_entry
        )

        b.briFloorValue.text = prefs.brightnessFloor.toString()
        b.labValue.text =
            getString(if (prefs.labUnlocked) R.string.as_lab_unlocked else R.string.as_lab_locked)
        b.agentValue.text = getString(if (prefs.agentEnabled) R.string.on else R.string.off)
        b.agentSub.text = if (prefs.agentEnabled) {
            getString(R.string.agent_sub_on, prefs.agentPin)
        } else {
            getString(R.string.as_agent_sub)
        }
        b.versionValue.text = getString(
            R.string.build_info,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE
        )
        val langLabel = when (prefs.lang) {
            AppPrefs.LANG_ZH -> getString(R.string.lang_zh)
            AppPrefs.LANG_EN -> getString(R.string.lang_en)
            else -> getString(R.string.lang_system)
        }
        val resolved = androidx.core.os.ConfigurationCompat
            .getLocales(resources.configuration).get(0)
        b.langValue.text =
            if (resolved == null) langLabel else "$langLabel · ${resolved.language}"
    }

    private fun switchMode(mode: String) {
        if (prefs.mode == mode) return
        prefs.mode = mode
        render()
        val name = getString(if (mode == AppPrefs.MODE_LOCAL) R.string.mode_local else R.string.mode_remote)
        Toast.makeText(this, getString(R.string.mode_switched, name), Toast.LENGTH_SHORT).show()
    }

    private fun editBrightnessFloor() {
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.brightnessFloor.toString())
            setSelection(text.length)
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val wrap = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(et)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.bri_floor_title)
            .setView(wrap)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val v = et.text.toString().toIntOrNull() ?: prefs.brightnessFloor
                prefs.brightnessFloor = v
                render()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun onLabRowClick() {
        if (prefs.labUnlocked) {
            AlertDialog.Builder(this)
                .setTitle(R.string.as_relock_title)
                .setMessage(R.string.as_relock_msg)
                .setPositiveButton(R.string.dialog_ok) { _, _ ->
                    prefs.labUnlocked = false
                    render()
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
            return
        }
        if (labTracker.onTap()) unlockLab()
    }

    private fun unlockLab() {
        prefs.labUnlocked = true
        render()
        Toast.makeText(this, R.string.lab_unlocked_toast, Toast.LENGTH_SHORT).show()
    }

    private fun showPinDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.agent_pin_title)
            .setMessage(getString(R.string.agent_pin_msg, prefs.agentPin))
            .setPositiveButton(R.string.agent_regen) { _, _ ->
                val newPin = prefs.regenerateAgentPin()
                if (prefs.agentEnabled) {
                    AgentService.stop(this)
                    AgentService.start(this)
                }
                Toast.makeText(
                    this,
                    getString(R.string.agent_pin_new, newPin),
                    Toast.LENGTH_LONG
                ).show()
                render()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun openRepo() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val REPO_URL = "https://github.com/gujiangjiang/JMGO_M6"
    }
}
