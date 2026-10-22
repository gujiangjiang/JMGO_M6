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
import androidx.appcompat.app.AppCompatActivity
import com.gujiangjiang.jmgocontrol.core.AppPrefs
import com.gujiangjiang.jmgocontrol.databinding.ActivityAppSettingsBinding

class AppSettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivityAppSettingsBinding
    private lateinit var prefs: AppPrefs

    private val seq = intArrayOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER
    )
    private var seqPos = 0
    private var seqAt = 0L
    private var labTaps = 0
    private var labTapAt = 0L

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
            render()
        }
        b.rowRepo.setOnClickListener { openRepo() }
        render()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val now = System.currentTimeMillis()
            if (now - seqAt > 4000) seqPos = 0
            seqAt = now
            if (event.keyCode == seq[seqPos]) {
                seqPos++
                if (seqPos == seq.size) {
                    seqPos = 0
                    if (!prefs.labUnlocked) unlockLab()
                    return true
                }
            } else {
                seqPos = if (event.keyCode == seq[0]) 1 else 0
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
        b.versionValue.text = getString(
            R.string.build_info,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE
        )
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
        val now = System.currentTimeMillis()
        if (now - labTapAt > 2000) labTaps = 0
        labTapAt = now
        labTaps++
        if (labTaps >= 7) {
            labTaps = 0
            unlockLab()
        }
    }

    private fun unlockLab() {
        prefs.labUnlocked = true
        render()
        Toast.makeText(this, R.string.lab_unlocked_toast, Toast.LENGTH_SHORT).show()
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
