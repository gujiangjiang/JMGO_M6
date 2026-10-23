package com.gujiangjiang.jmgocontrol.remote

import java.io.InputStream

/**
 * Anymote 协议编解码（手工实现，无 protobuf 依赖）。
 * 帧格式：varint 长度前缀 + RemoteMessage。
 * 字段号来源见仓库 docs/remote-control.md。
 */
object Anymote {

    // ---------- 编码 ----------

    fun varint(n: Int): ByteArray {
        var v = n
        val out = ArrayList<Byte>(5)
        while (true) {
            val b = v and 0x7f
            v = v ushr 7
            if (v != 0) {
                out.add((b or 0x80).toByte())
            } else {
                out.add(b.toByte())
                break
            }
        }
        return out.toByteArray()
    }

    private fun vi(field: Int, value: Int): ByteArray =
        varint((field shl 3) or 0) + varint(value)

    private fun ld(field: Int, bytes: ByteArray): ByteArray =
        varint((field shl 3) or 2) + varint(bytes.size) + bytes

    private fun ld(field: Int, s: String): ByteArray = ld(field, s.toByteArray(Charsets.UTF_8))

    private fun frame(payload: ByteArray): ByteArray = varint(payload.size) + payload

    /** KeyEvent：action 0=UP / 1=DOWN，keycode 为标准 Android 键码 */
    fun keyMessage(keycode: Int, down: Boolean): ByteArray {
        val keyEvent = vi(1, if (down) 1 else 0) + vi(2, keycode)
        val request = ld(1, keyEvent)              // RequestMessage.key_event_message
        return frame(ld(2, request))               // RemoteMessage.request_message
    }

    /** Data 消息：文本输入 type="input_method"（服务端直接 commitText） */
    fun dataMessage(type: String, data: String): ByteArray {
        val d = ld(1, type) + ld(2, data)
        val request = ld(4, d)                     // RequestMessage.data_message
        return frame(ld(2, request))
    }

    /** 空 Fling：官方 App 用作心跳 */
    fun pingMessage(): ByteArray {
        val fling = ld(1, "")
        val request = ld(6, fling)                 // RequestMessage.fling_message
        return frame(ld(2, request))
    }

    // ---------- 解码 ----------

    class FrameReader(private val input: InputStream) {
        fun readFrame(): ByteArray? {
            var len = 0
            var shift = 0
            while (true) {
                val b = input.read()
                if (b < 0) return null
                len = len or ((b and 0x7f) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
                if (shift > 28) return null
            }
            if (len <= 0 || len > 4 * 1024 * 1024) return null
            val buf = ByteArray(len)
            var off = 0
            while (off < len) {
                val r = input.read(buf, off, len - off)
                if (r < 0) return null
                off += r
            }
            return buf
        }
    }

    /** 只解析顶层 varint / length-delimited 字段 */
    fun fields(buf: ByteArray): Map<Int, Any> {
        val out = HashMap<Int, Any>()
        var i = 0
        while (i < buf.size) {
            var key = 0
            var shift = 0
            while (true) {
                if (i >= buf.size) return out
                val b = buf[i].toInt() and 0xff
                i++
                key = key or ((b and 0x7f) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
                if (shift > 28) return out
            }
            val num = key ushr 3
            when (key and 7) {
                0 -> {
                    var v = 0L
                    var s = 0
                    while (true) {
                        if (i >= buf.size) return out
                        val b = buf[i].toInt() and 0xff
                        i++
                        v = v or ((b and 0x7f).toLong() shl s)
                        if (b and 0x80 == 0) break
                        s += 7
                        if (s > 63) return out
                    }
                    out[num] = v
                }
                2 -> {
                    var ln = 0
                    var s = 0
                    while (true) {
                        if (i >= buf.size) return out
                        val b = buf[i].toInt() and 0xff
                        i++
                        ln = ln or ((b and 0x7f) shl s)
                        if (b and 0x80 == 0) break
                        s += 7
                        if (s > 28) return out
                    }
                    if (i + ln > buf.size) return out
                    out[num] = buf.copyOfRange(i, i + ln)
                    i += ln
                }
                5 -> {
                    if (i + 4 > buf.size) return out
                    i += 4
                }
                1 -> {
                    if (i + 8 > buf.size) return out
                    i += 8
                }
                else -> return out
            }
        }
        return out
    }

    fun bytesField(m: Map<Int, Any>, f: Int): ByteArray? = m[f] as? ByteArray

    fun stringField(m: Map<Int, Any>, f: Int): String? =
        (m[f] as? ByteArray)?.toString(Charsets.UTF_8)

    /**
     * 解析设备推送：RemoteMessage.response_message.data_message{type,data}
     * @return Pair(type, data) 或 null
     */
    fun parseDataPush(frame: ByteArray): Pair<String, String>? {
        val rm = fields(frame)
        val resp = bytesField(rm, 3)?.let { fields(it) } ?: return null
        val data = bytesField(resp, 1)?.let { fields(it) } ?: return null
        val type = stringField(data, 1) ?: return null
        val value = stringField(data, 2) ?: ""
        return type to value
    }
}
