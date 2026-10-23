package com.gujiangjiang.jmgocontrol

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.gujiangjiang.jmgocontrol.agent.AgentService
import com.gujiangjiang.jmgocontrol.core.AppPrefs
import com.gujiangjiang.jmgocontrol.core.LabUnlockTracker
import com.gujiangjiang.jmgocontrol.databinding.ActivityMainBinding
import com.gujiangjiang.jmgocontrol.ui.Entry
import com.gujiangjiang.jmgocontrol.ui.EntryAdapter

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: AppPrefs
    private val labTracker = LabUnlockTracker()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPrefs(this)
        if (prefs.mode == AppPrefs.MODE_UNSET) {
            startActivity(Intent(this, ModeSelectActivity::class.java))
            finish()
            return
        }
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.list.layoutManager = LinearLayoutManager(this)
        maybeStartAgent()
    }

    private fun maybeStartAgent() {
        if (prefs.mode == AppPrefs.MODE_LOCAL && prefs.agentEnabled) {
            try {
                AgentService.start(this)
            } catch (t: Throwable) {
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::b.isInitialized) return
        render()
        requestStorageIfNeeded()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !prefs.labUnlocked) {
            if (labTracker.onKey(event.keyCode)) {
                prefs.labUnlocked = true
                render()
                Toast.makeText(this, R.string.lab_unlocked_toast, Toast.LENGTH_SHORT).show()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun requestStorageIfNeeded() {
        if (prefs.mode != AppPrefs.MODE_LOCAL) return
        val granted = checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissions(
                arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                REQ_STORAGE
            )
        }
    }

    private fun render() {
        val local = prefs.mode == AppPrefs.MODE_LOCAL
        b.modeChip.text = getString(if (local) R.string.mode_local else R.string.mode_remote)
        b.deviceInfo.text = getString(R.string.device_line, Build.MODEL, Build.VERSION.RELEASE)

        val entries = mutableListOf<Entry>()
        if (local) {
            entries += Entry(
                "general",
                R.drawable.ic_general,
                getString(R.string.entry_general),
                getString(R.string.entry_general_sub)
            )
            entries += Entry(
                "advanced",
                R.drawable.ic_wrench,
                getString(R.string.entry_advanced),
                getString(R.string.entry_advanced_sub)
            )
            if (prefs.labUnlocked) {
                entries += Entry(
                    "lab",
                    R.drawable.ic_lab,
                    getString(R.string.entry_lab),
                    getString(R.string.entry_lab_sub_unlocked)
                )
            }
        } else {
            entries += Entry(
                "connect",
                R.drawable.ic_wifi,
                getString(R.string.entry_connect),
                getString(R.string.entry_connect_sub)
            )
            entries += Entry(
                "remote",
                R.drawable.ic_remote,
                getString(R.string.entry_remote_pad),
                getString(R.string.entry_remote_pad_sub)
            )
            entries += Entry(
                "toolbox",
                R.drawable.ic_toolbox,
                getString(R.string.entry_toolbox),
                getString(R.string.entry_toolbox_sub)
            )
        }
        entries += Entry(
            "appsettings",
            R.drawable.ic_settings,
            getString(R.string.entry_app_settings),
            null
        )
        b.list.adapter = EntryAdapter(entries) { onEntry(it) }
    }

    private fun onEntry(e: Entry) {
        when (e.id) {
            "appsettings" -> startActivity(Intent(this, AppSettingsActivity::class.java))
            "general" -> openLocalPage(LocalSettingsActivity.PAGE_GENERAL)
            "advanced" -> openLocalPage(LocalSettingsActivity.PAGE_ADVANCED)
            "lab" -> openLocalPage(LocalSettingsActivity.PAGE_LAB)
            "connect" -> startActivity(Intent(this, RemoteConnectActivity::class.java))
            "remote" -> {
                if (prefs.lastRemoteIp.isEmpty()) {
                    startActivity(Intent(this, RemoteConnectActivity::class.java))
                } else {
                    startActivity(Intent(this, RemotePadActivity::class.java))
                }
            }
            "toolbox" -> {
                if (prefs.lastRemoteIp.isEmpty()) {
                    startActivity(Intent(this, RemoteConnectActivity::class.java))
                } else {
                    startActivity(Intent(this, RemoteToolboxActivity::class.java))
                }
            }
            else -> Toast.makeText(this, R.string.wip, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openLocalPage(page: String) {
        startActivity(
            Intent(this, LocalSettingsActivity::class.java)
                .putExtra(LocalSettingsActivity.EXTRA_PAGE, page)
        )
    }

    companion object {
        private const val REQ_STORAGE = 1001
    }
}
