package com.gujiangjiang.jmgocontrol.remote

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * UDP 9101 发现：广播 "discover _anymote._tcp 9101\n"，
 * 设备应答 "_anymote._tcp <model> <port> <mac> <sn>\n"（单播回请求源 IP）。
 * 注意：应答中的端口字段部分固件写 9000，实际控制端口固定 9005。
 */
class RemoteDiscovery(
    context: Context,
    private val onDevice: (RemoteDevice) -> Unit
) {

    data class RemoteDevice(
        val ip: String,
        val model: String,
        val mac: String,
        val sn: String,
        val port: Int = AnymoteClient.DEFAULT_PORT
    )

    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private val seen = HashSet<String>()

    fun start() {
        stop()
        running = true
        seen.clear()
        multicastLock = try {
            wifi?.createMulticastLock("jmgo_discovery")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (t: Throwable) {
            null
        }
        val t = Thread {
            var sock: DatagramSocket? = null
            try {
                sock = DatagramSocket()
                sock.broadcast = true
                sock.soTimeout = 1000
                val addr = InetAddress.getByName("255.255.255.255")
                val payload = "discover _anymote._tcp 9101\n".toByteArray(Charsets.UTF_8)
                var lastSend = 0L
                val buf = ByteArray(1024)
                while (running) {
                    val now = System.currentTimeMillis()
                    if (now - lastSend > 3000) {
                        try {
                            sock.send(DatagramPacket(payload, payload.size, addr, DISCOVERY_PORT))
                        } catch (t: Throwable) {
                        }
                        lastSend = now
                    }
                    try {
                        val pkt = DatagramPacket(buf, buf.size)
                        sock.receive(pkt)
                        val text = String(pkt.data, 0, pkt.length).trim()
                        val tok = text.split(Regex("\\s+"))
                        if (tok.size >= 3 && tok[0] == "_anymote._tcp") {
                            val ip = pkt.address?.hostAddress ?: continue
                            if (seen.add(ip)) {
                                onDevice(
                                    RemoteDevice(
                                        ip = ip,
                                        model = tok[1],
                                        mac = tok.getOrElse(3) { "" },
                                        sn = tok.getOrElse(4) { "" }
                                    )
                                )
                            }
                        }
                    } catch (t: SocketTimeoutException) {
                    }
                }
            } catch (t: Throwable) {
            } finally {
                try {
                    sock?.close()
                } catch (t: Throwable) {
                }
            }
        }
        t.isDaemon = true
        t.start()
        thread = t
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        } catch (t: Throwable) {
        }
        multicastLock = null
    }

    companion object {
        const val DISCOVERY_PORT = 9101
    }
}
