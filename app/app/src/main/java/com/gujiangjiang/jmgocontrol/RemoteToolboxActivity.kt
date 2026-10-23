package com.gujiangjiang.jmgocontrol

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.gujiangjiang.jmgocontrol.core.AppPrefs
import com.gujiangjiang.jmgocontrol.databinding.ActivityRemoteToolboxBinding
import com.gujiangjiang.jmgocontrol.remote.AgentClient
import java.io.File
import java.net.URLEncoder

class RemoteToolboxActivity : AppCompatActivity() {

    private lateinit var b: ActivityRemoteToolboxBinding
    private lateinit var prefs: AppPrefs
    private var ip = ""
    private var name = ""
    private var maxBright = 100
    private var floorBright = 8
    private var pinAsked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityRemoteToolboxBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = AppPrefs(this)

        ip = intent.getStringExtra(EXTRA_IP) ?: prefs.lastRemoteIp
        name = intent.getStringExtra(EXTRA_NAME) ?: prefs.lastRemoteName
        if (name.isEmpty()) name = ip
        b.tbDevice.text = getString(R.string.tb_device_line, name, ip)
        b.tbState.text = getString(R.string.tb_connecting)

        b.rowShot.setOnClickListener { doScreenshot() }
        b.rowInstall.setOnClickListener { pickApk() }
        b.rowPkgReset.setOnClickListener { confirmPkgReset() }
        b.rowFiles.setOnClickListener { openFiles() }

        b.tbBright.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) b.tbBrightValue.text = (progress + floorBright).toString()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val v = (seekBar?.progress ?: 0) + floorBright
                setBrightness(v)
            }
        })

        refreshInfo()
    }

    private fun client(): AgentClient =
        AgentClient(ip, AgentClient.DEFAULT_PORT, prefs.agentClientPin)

    private fun ui(block: () -> Unit) = runOnUiThread {
        if (!isFinishing) block()
    }

    private fun status(s: String) {
        ui { b.tbStatus.text = s }
    }

    private fun refreshInfo() {
        status(getString(R.string.tb_connecting))
        Thread {
            val ping = AgentClient(ip, AgentClient.DEFAULT_PORT, "").get("/ping", 6000)
            if (!ping.ok) {
                ui { b.tbState.text = getString(R.string.tb_offline) }
                status(getString(R.string.tb_offline_hint))
                return@Thread
            }
            val info = client().get("/info")
            if (info.code == 403) {
                ui { b.tbState.text = getString(R.string.tb_need_pin) }
                askPin()
                return@Thread
            }
            if (!info.ok) {
                ui { b.tbState.text = getString(R.string.tb_offline) }
                status(info.error ?: info.text())
                return@Thread
            }
            val j = info.json() ?: return@Thread
            maxBright = j.optInt("brightnessMax", 100).coerceAtLeast(1)
            floorBright = j.optInt("brightnessFloor", 8).coerceIn(0, maxBright / 2)
            val cur = j.optInt("brightness", floorBright)
            ui {
                b.tbState.text = getString(R.string.tb_ready)
                b.tbBright.max = (maxBright - floorBright).coerceAtLeast(1)
                b.tbBright.progress = (cur - floorBright).coerceIn(0, b.tbBright.max)
                b.tbBrightValue.text = cur.toString()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun askPin() {
        if (pinAsked) return
        pinAsked = true
        val pad = (16 * resources.displayMetrics.density).toInt()
        val wrapper = FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0) }
        val et = EditText(this).apply {
            hint = getString(R.string.tb_pin_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(prefs.agentClientPin)
        }
        wrapper.addView(et)
        AlertDialog.Builder(this)
            .setTitle(R.string.tb_pin_title)
            .setMessage(R.string.tb_pin_msg)
            .setView(wrapper)
            .setCancelable(false)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                prefs.agentClientPin = et.text.toString().trim()
                pinAsked = false
                refreshInfo()
            }
            .setNegativeButton(R.string.dialog_cancel) { _, _ -> pinAsked = false }
            .show()
    }

    private fun setBrightness(v: Int) {
        b.tbBrightValue.text = v.toString()
        Thread {
            val r = client().get(
                "/settings/set?service=0&cmd=100&value=" + v
            )
            status(
                if (r.ok) getString(R.string.tb_brightness_set, v)
                else getString(R.string.tb_fail, r.error ?: r.text())
            )
        }.apply { isDaemon = true }.start()
    }

    private fun doScreenshot() {
        status(getString(R.string.tb_shot_working))
        Thread {
            val r = client().get("/screenshot", 20000)
            if (!r.ok || r.body.isEmpty()) {
                status(getString(R.string.tb_fail, r.error ?: r.text().take(120)))
                return@Thread
            }
            val dir = getExternalFilesDir("screenshots") ?: filesDir
            val f = File(dir, "shot-" + System.currentTimeMillis() + ".png")
            f.writeBytes(r.body)
            val bmp = BitmapFactory.decodeByteArray(r.body, 0, r.body.size)
            ui {
                status(getString(R.string.tb_shot_saved, f.absolutePath))
                if (bmp != null) {
                    val iv = ImageView(this)
                    iv.setImageBitmap(bmp)
                    AlertDialog.Builder(this)
                        .setTitle(R.string.tb_shot_preview)
                        .setView(iv)
                        .setPositiveButton(R.string.dialog_ok, null)
                        .show()
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun pickApk() {
        try {
            val i = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "application/vnd.android.package-archive"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(i, REQ_APK)
        } catch (t: Throwable) {
            Toast.makeText(this, R.string.tb_pick_fail, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmPkgReset() {
        AlertDialog.Builder(this)
            .setTitle(R.string.tb_pkgreset)
            .setMessage(R.string.tb_pkgreset_msg)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                status(getString(R.string.tb_working))
                Thread {
                    val r = client().post("/pkgreset", ByteArray(0), 30000)
                    val j = r.json()
                    val ok = j?.optBoolean("ok") ?: false
                    status(getString(if (ok) R.string.tb_pkgreset_ok else R.string.tb_fail, j?.optString("message") ?: (r.error ?: "")))
                }.apply { isDaemon = true }.start()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun openFiles() {
        startActivity(
            Intent(this, RemoteFilesActivity::class.java)
                .putExtra(RemoteFilesActivity.EXTRA_IP, ip)
                .putExtra(RemoteFilesActivity.EXTRA_NAME, name)
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_APK || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        status(getString(R.string.tb_install_working))
        Thread {
            try {
                val resolver = contentResolver
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                var fileName = "push.apk"
                resolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) fileName = c.getString(idx) ?: fileName
                }
                val enc = URLEncoder.encode(fileName, "UTF-8")
                val r = client().post("/install?name=" + enc, bytes)
                val j = r.json()
                val ok = j?.optBoolean("ok") ?: false
                val msg = j?.optString("message") ?: (r.error ?: "")
                status(getString(if (ok) R.string.tb_install_ok else R.string.tb_fail, msg.take(300)))
            } catch (t: Throwable) {
                status(getString(R.string.tb_fail, t.message ?: t.javaClass.simpleName))
            }
        }.apply { isDaemon = true }.start()
    }

    companion object {
        const val EXTRA_IP = "ip"
        const val EXTRA_NAME = "name"
        private const val REQ_APK = 2101
    }
}
