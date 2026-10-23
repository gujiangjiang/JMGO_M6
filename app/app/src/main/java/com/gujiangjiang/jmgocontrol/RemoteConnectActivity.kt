package com.gujiangjiang.jmgocontrol

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gujiangjiang.jmgocontrol.core.AppPrefs
import com.gujiangjiang.jmgocontrol.databinding.ActivityRemoteConnectBinding
import com.gujiangjiang.jmgocontrol.databinding.ItemRemoteDeviceBinding
import com.gujiangjiang.jmgocontrol.remote.AnymoteClient
import com.gujiangjiang.jmgocontrol.remote.RemoteDiscovery

class RemoteConnectActivity : AppCompatActivity() {

    private lateinit var b: ActivityRemoteConnectBinding
    private lateinit var prefs: AppPrefs
    private var discovery: RemoteDiscovery? = null
    private val devices = ArrayList<RemoteDiscovery.RemoteDevice>()
    private lateinit var adapter: DeviceAdapter
    private var checking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityRemoteConnectBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = AppPrefs(this)

        adapter = DeviceAdapter(devices) { openPad(it) }
        b.deviceList.layoutManager = LinearLayoutManager(this)
        b.deviceList.adapter = adapter
        refreshStatus()

        b.btnScan.setOnClickListener {
            devices.clear()
            adapter.notifyDataSetChanged()
            refreshStatus()
            restartDiscovery()
        }
        b.btnReconnect.setOnClickListener { manualConnect() }
        b.btnLast.setOnClickListener { lastConnect() }

        if (prefs.lastRemoteIp.isNotEmpty()) {
            b.ipInput.setText(prefs.lastRemoteIp)
        }
    }

    override fun onResume() {
        super.onResume()
        restartDiscovery()
    }

    override fun onPause() {
        super.onPause()
        discovery?.stop()
        discovery = null
    }

    private fun restartDiscovery() {
        discovery?.stop()
        b.scanStatus.text = getString(R.string.connect_scanning)
        discovery = RemoteDiscovery(this) { dev ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (devices.none { it.ip == dev.ip }) {
                    devices.add(dev)
                    adapter.notifyItemInserted(devices.size - 1)
                    refreshStatus()
                }
            }
        }
        discovery?.start()
    }

    private fun refreshStatus() {
        b.scanStatus.text = if (devices.isEmpty()) {
            getString(R.string.connect_none)
        } else {
            getString(R.string.connect_found, devices.size)
        }
    }

    private fun manualConnect() {
        val ip = b.ipInput.text.toString().trim()
        if (!isValidIp(ip)) {
            Toast.makeText(this, R.string.connect_bad_ip, Toast.LENGTH_SHORT).show()
            return
        }
        openPad(RemoteDiscovery.RemoteDevice(ip = ip, model = ip, mac = "", sn = ""))
    }

    private fun lastConnect() {
        val ip = prefs.lastRemoteIp
        if (ip.isEmpty()) {
            Toast.makeText(this, R.string.connect_last_none, Toast.LENGTH_SHORT).show()
            return
        }
        val name = if (prefs.lastRemoteName.isNotEmpty()) prefs.lastRemoteName else ip
        openPad(RemoteDiscovery.RemoteDevice(ip = ip, model = name, mac = "", sn = ""))
    }

    /**
     * 连接设备（前置条件流程）：连通性测试通过后保存设备并返回主菜单，
     * 由主菜单展示设备名与 IP，用户再选择「遥控面板 / 工具箱」。
     */
    private fun openPad(dev: RemoteDiscovery.RemoteDevice) {
        val name = if (dev.model.isNotEmpty()) dev.model else dev.ip
        b.scanStatus.text = getString(R.string.connect_testing, name)
        if (checking) return
        checking = true
        Thread {
            val ok = AnymoteClient.test(dev.ip)
            runOnUiThread {
                checking = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (ok) {
                    prefs.lastRemoteIp = dev.ip
                    prefs.lastRemoteName = name
                    Toast.makeText(
                        this,
                        getString(R.string.connect_ok, name, dev.ip),
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                } else {
                    b.scanStatus.text = getString(R.string.connect_test_fail)
                    Toast.makeText(this, R.string.connect_test_fail, Toast.LENGTH_LONG).show()
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun isValidIp(ip: String): Boolean {
        if (ip.isEmpty() || ip.length > 15) return false
        val parts = ip.split(".")
        if (parts.size != 4) return false
        return parts.all { p ->
            p.isNotEmpty() && p.length <= 3 && p.all { it in '0'..'9' } &&
                (p.toIntOrNull() ?: 999) in 0..255
        }
    }

    private class DeviceAdapter(
        private val items: List<RemoteDiscovery.RemoteDevice>,
        private val onClick: (RemoteDiscovery.RemoteDevice) -> Unit
    ) : RecyclerView.Adapter<DeviceAdapter.VH>() {

        class VH(val b: ItemRemoteDeviceBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(ItemRemoteDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val d = items[position]
            holder.b.deviceModel.text = d.model
            val info = buildString {
                append(d.ip)
                if (d.sn.isNotEmpty()) {
                    append(" · SN ")
                    append(d.sn)
                }
            }
            holder.b.deviceInfo2.text = info
            holder.b.root.setOnClickListener { onClick(d) }
        }
    }
}
