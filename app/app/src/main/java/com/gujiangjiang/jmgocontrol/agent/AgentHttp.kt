package com.gujiangjiang.jmgocontrol.agent

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Socket
import java.net.URLDecoder

class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: ByteArray
)

class HttpResponse {
    var code = 200
    var contentType = "application/json; charset=utf-8"
    var bytes: ByteArray = "{}".toByteArray(Charsets.UTF_8)

    fun json(s: String) {
        contentType = "application/json; charset=utf-8"
        bytes = s.toByteArray(Charsets.UTF_8)
    }
}

/** 极简 HTTP/1.1（单请求-响应，Connection: close），够用即可，避免引入依赖 */
object AgentHttp {

    fun readRequest(socket: Socket): HttpRequest? {
        val input = socket.getInputStream()
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val rawPath = parts[1]

        val qIdx = rawPath.indexOf('?')
        val path = if (qIdx >= 0) rawPath.substring(0, qIdx) else rawPath
        val query = HashMap<String, String>()
        if (qIdx >= 0) {
            for (kv in rawPath.substring(qIdx + 1).split("&")) {
                if (kv.isEmpty()) continue
                val eq = kv.indexOf('=')
                val k = if (eq >= 0) kv.substring(0, eq) else kv
                val v = if (eq >= 0) kv.substring(eq + 1) else ""
                query[dec(k)] = dec(v)
            }
        }

        val headers = HashMap<String, String>()
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            val c = h.indexOf(':')
            if (c > 0) {
                headers[h.substring(0, c).trim().lowercase()] = h.substring(c + 1).trim()
            }
        }

        val len = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (len > 0) readN(input, len) else ByteArray(0)
        return HttpRequest(method, path, query, headers, body)
    }

    private fun readN(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) break
            off += r
        }
        return if (off == n) buf else buf.copyOf(off)
    }

    private fun readLine(input: InputStream): String? {
        val bos = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) {
                return if (bos.size() == 0) null else bos.toString("UTF-8")
            }
            if (b == '\n'.code) break
            if (b == '\r'.code) continue
            bos.write(b)
            if (bos.size() > 8192) break
        }
        return bos.toString("UTF-8")
    }

    private fun dec(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (t: Throwable) {
        s
    }

    fun writeResponse(out: BufferedOutputStream, resp: HttpResponse) {
        val statusText = when (resp.code) {
            200 -> "OK"
            403 -> "Forbidden"
            404 -> "Not Found"
            500 -> "Internal Server Error"
            else -> "OK"
        }
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(resp.code).append(' ').append(statusText).append("\r\n")
        head.append("Content-Type: ").append(resp.contentType).append("\r\n")
        head.append("Content-Length: ").append(resp.bytes.size).append("\r\n")
        head.append("Connection: close\r\n")
        head.append("Cache-Control: no-store\r\n")
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.UTF_8))
        out.write(resp.bytes)
    }
}
