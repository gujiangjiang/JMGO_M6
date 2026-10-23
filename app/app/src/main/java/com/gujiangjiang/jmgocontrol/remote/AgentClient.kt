package com.gujiangjiang.jmgocontrol.remote

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

/** 手机端 → 投影仪远程助手（Agent）的 HTTP 客户端 */
class AgentClient(
    private val ip: String,
    private val port: Int = DEFAULT_PORT,
    private val pin: String = ""
) {

    class Result(val ok: Boolean, val code: Int, val body: ByteArray, val error: String?) {
        fun text(): String = String(body, Charsets.UTF_8)

        fun json(): JSONObject? = try {
            JSONObject(text())
        } catch (t: Throwable) {
            null
        }
    }

    fun get(path: String, timeoutMs: Int = 10000): Result = request("GET", path, null, timeoutMs)

    fun post(path: String, body: ByteArray, timeoutMs: Int = 300000): Result =
        request("POST", path, body, timeoutMs)

    private fun request(method: String, path: String, body: ByteArray?, timeoutMs: Int): Result {
        var conn: HttpURLConnection? = null
        try {
            val url = URL("http://" + ip + ":" + port + path)
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 5000
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("X-Pin", pin)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/octet-stream")
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.let { readAll(it) } ?: ByteArray(0)
            return Result(code in 200..299, code, bytes, null)
        } catch (t: Throwable) {
            return Result(false, -1, ByteArray(0), t.message ?: t.javaClass.simpleName)
        } finally {
            try {
                conn?.disconnect()
            } catch (t: Throwable) {
            }
        }
    }

    private fun readAll(ins: InputStream): ByteArray {
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val r = ins.read(buf)
            if (r < 0) break
            bos.write(buf, 0, r)
        }
        try {
            ins.close()
        } catch (t: Throwable) {
        }
        return bos.toByteArray()
    }

    companion object {
        const val DEFAULT_PORT = 9399
        const val DISCOVERY_MAGIC = "JMGOAGENT1"
        const val DISCOVERY_PORT = 9188

        /** UDP 广播查找局域网内的 Agent，返回发现的 IP 列表 */
        fun discover(timeoutMs: Int = 1500): List<String> {
            val found = ArrayList<String>()
            try {
                val sock = DatagramSocket()
                sock.broadcast = true
                sock.soTimeout = 400
                val payload = DISCOVERY_MAGIC.toByteArray(Charsets.UTF_8)
                val addr = InetAddress.getByName("255.255.255.255")
                val deadline = System.currentTimeMillis() + timeoutMs
                var lastSend = 0L
                val buf = ByteArray(512)
                while (System.currentTimeMillis() < deadline) {
                    val now = System.currentTimeMillis()
                    if (now - lastSend > 500) {
                        try {
                            sock.send(DatagramPacket(payload, payload.size, addr, DISCOVERY_PORT))
                        } catch (t: Throwable) {
                        }
                        lastSend = now
                    }
                    try {
                        val pkt = DatagramPacket(buf, buf.size)
                        sock.receive(pkt)
                        val txt = String(pkt.data, 0, pkt.length)
                        if (txt.startsWith(DISCOVERY_MAGIC)) {
                            val ip = pkt.address?.hostAddress ?: continue
                            if (!found.contains(ip)) found.add(ip)
                        }
                    } catch (t: SocketTimeoutException) {
                    }
                }
                try {
                    sock.close()
                } catch (t: Throwable) {
                }
            } catch (t: Throwable) {
            }
            return found
        }
    }
}
