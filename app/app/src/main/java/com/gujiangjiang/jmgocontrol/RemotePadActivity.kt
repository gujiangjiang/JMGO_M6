package com.gujiangjiang.jmgocontrol

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.gujiangjiang.jmgocontrol.core.BaseActivity
import com.gujiangjiang.jmgocontrol.core.AppPrefs
import com.gujiangjiang.jmgocontrol.databinding.ActivityRemotePadBinding
import com.gujiangjiang.jmgocontrol.databinding.ItemKeyBinding
import com.gujiangjiang.jmgocontrol.remote.AnymoteClient
import kotlin.math.abs

class RemotePadActivity : BaseActivity() {

    private lateinit var b: ActivityRemotePadBinding
    private lateinit var prefs: AppPrefs
    private var client: AnymoteClient? = null
    private var ip: String = ""
    private var name: String = ""

    private var downX = 0f
    private var downY = 0f
    private var moved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityRemotePadBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = AppPrefs(this)

        ip = intent.getStringExtra(EXTRA_IP) ?: prefs.lastRemoteIp
        name = intent.getStringExtra(EXTRA_NAME) ?: prefs.lastRemoteName
        if (name.isEmpty()) name = ip

        b.deviceName.text = name
        b.connState.text = getString(R.string.pad_connecting, ip)
        b.btnReconnect.setOnClickListener { connect() }

        bindDpad()
        bindTouchpad()
        buildKeyRows()
        connect()
    }

    override fun onDestroy() {
        super.onDestroy()
        client?.disconnect()
        client = null
    }

    private fun connect() {
        if (ip.isEmpty()) {
            b.connState.text = getString(R.string.pad_closed, "未指定设备")
            return
        }
        client?.disconnect()
        b.connState.text = getString(R.string.pad_connecting, ip)
        client = AnymoteClient(
            onReady = {
                runOnUiThread {
                    if (!isFinishing) b.connState.text = getString(R.string.pad_ready)
                }
            },
            onData = { type, data ->
                if (type == "volume") {
                    runOnUiThread {
                        if (!isFinishing) b.connState.text = getString(R.string.pad_volume, data)
                    }
                }
            },
            onClosed = { reason ->
                runOnUiThread {
                    if (!isFinishing) b.connState.text = getString(R.string.pad_closed, reason)
                }
            }
        ).also { it.connect(ip) }
    }

    private fun sendKey(keycode: Int) {
        client?.sendKey(keycode)
    }

    private fun bindDpad() {
        b.keyUp.setOnClickListener { sendKey(KEYCODE_DPAD_UP) }
        b.keyDown.setOnClickListener { sendKey(KEYCODE_DPAD_DOWN) }
        b.keyLeft.setOnClickListener { sendKey(KEYCODE_DPAD_LEFT) }
        b.keyRight.setOnClickListener { sendKey(KEYCODE_DPAD_RIGHT) }
        b.keyOk.setOnClickListener { sendKey(KEYCODE_DPAD_CENTER) }
    }

    private fun bindTouchpad() {
        val threshold = 30 * resources.displayMetrics.density
        b.touchpad.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x
                    downY = ev.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(ev.x - downX) > threshold || abs(ev.y - downY) > threshold) {
                        moved = true
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (!moved) {
                        sendKey(KEYCODE_DPAD_CENTER)
                    } else if (abs(dx) > abs(dy)) {
                        sendKey(if (dx > 0) KEYCODE_DPAD_RIGHT else KEYCODE_DPAD_LEFT)
                    } else {
                        sendKey(if (dy > 0) KEYCODE_DPAD_DOWN else KEYCODE_DPAD_UP)
                    }
                }
            }
            true
        }
    }

    private class KeyDef(
        val icon: Int,
        val label: String,
        val rotation: Float = 0f,
        val action: () -> Unit
    )

    private fun buildKeyRows() {
        val rows = listOf(
            listOf(
                KeyDef(R.drawable.ic_arrow_big, getString(R.string.key_back), 180f) { sendKey(4) },
                KeyDef(R.drawable.ic_home, getString(R.string.key_home)) { sendKey(3) },
                KeyDef(R.drawable.ic_menu, getString(R.string.key_menu)) { sendKey(82) }
            ),
            listOf(
                KeyDef(R.drawable.ic_vol_down, getString(R.string.key_vol_down)) { sendKey(25) },
                KeyDef(R.drawable.ic_mute, getString(R.string.key_mute)) { sendKey(164) },
                KeyDef(R.drawable.ic_vol_up, getString(R.string.key_vol_up)) { sendKey(24) }
            ),
            listOf(
                KeyDef(R.drawable.ic_keyboard, getString(R.string.key_keyboard)) { showTextDialog() },
                KeyDef(R.drawable.ic_focus, getString(R.string.key_autofocus)) { sendKey(235) },
                KeyDef(R.drawable.ic_cast, getString(R.string.key_cast)) { sendKey(238) }
            ),
            listOf(
                KeyDef(R.drawable.ic_focus_minus, getString(R.string.key_focus_minus)) { sendKey(226) },
                KeyDef(R.drawable.ic_focus_plus, getString(R.string.key_focus_plus)) { sendKey(227) },
                KeyDef(R.drawable.ic_app_logo, getString(R.string.key_jmgo)) { sendKey(224) },
                KeyDef(R.drawable.ic_power, getString(R.string.key_power)) { sendKey(26) }
            )
        )
        val margin = (3 * resources.displayMetrics.density).toInt()
        for (rowDef in rows) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = margin * 2 }
            }
            for (def in rowDef) {
                val item = ItemKeyBinding.inflate(LayoutInflater.from(this), row, false)
                item.keyIcon.setImageResource(def.icon)
                item.keyIcon.rotation = def.rotation
                item.keyLabel.text = def.label
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                lp.marginStart = margin
                lp.marginEnd = margin
                item.root.layoutParams = lp
                item.root.setOnClickListener { def.action() }
                row.addView(item.root)
            }
            b.keyRows.addView(row)
        }
    }

    private fun showTextDialog() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val wrapper = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
        }
        val et = EditText(this).apply {
            hint = getString(R.string.pad_text_hint)
            maxLines = 3
        }
        wrapper.addView(et)
        AlertDialog.Builder(this)
            .setTitle(R.string.pad_text_title)
            .setView(wrapper)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val text = et.text.toString()
                if (text.isNotEmpty()) {
                    val ok = client?.let {
                        it.sendText(text)
                        true
                    } ?: false
                    Toast.makeText(
                        this,
                        if (ok) R.string.pad_text_sent else R.string.pad_text_fail,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_IP = "ip"
        const val EXTRA_NAME = "name"

        private const val KEYCODE_HOME = 3
        private const val KEYCODE_BACK = 4
        private const val KEYCODE_DPAD_UP = 19
        private const val KEYCODE_DPAD_DOWN = 20
        private const val KEYCODE_DPAD_LEFT = 21
        private const val KEYCODE_DPAD_RIGHT = 22
        private const val KEYCODE_DPAD_CENTER = 23
        private const val KEYCODE_VOLUME_UP = 24
        private const val KEYCODE_VOLUME_DOWN = 25
        private const val KEYCODE_POWER = 26
        private const val KEYCODE_MENU = 82
        private const val KEYCODE_MUTE = 164
    }
}
