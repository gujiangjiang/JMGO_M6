package com.gujiangjiang.jmgocontrol.remote

import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Anymote TCP 客户端（端口 9005，无 TLS、无鉴权、无握手）。
 * 连接后设备会主动推 volume / sysconfig 两条 Data，收到 sysconfig 视为就绪。
 */
class AnymoteClient(
    private val onReady: () -> Unit,
    private val onData: (type: String, data: String) -> Unit,
    private val onClosed: (reason: String) -> Unit
) {

    @Volatile
    var connected = false
        private set

    @Volatile
    private var running = false
    private var socket: Socket? = null
    private var out: OutputStream? = null
    private var readyNotified = false

    fun connect(ip: String, port: Int = DEFAULT_PORT) {
        disconnect()
        running = true
        readyNotified = false
        Thread {
            var reason = "无法连接到设备"
            try {
                val s = Socket()
                s.connect(InetSocketAddress(ip, port), 5000)
                socket = s
                out = s.getOutputStream()
                connected = true
                reason = "连接已断开"

                Thread {
                    while (running && connected) {
                        try {
                            Thread.sleep(HEARTBEAT_MS)
                        } catch (t: InterruptedException) {
                            break
                        }
                        if (running && connected) send(Anymote.pingMessage())
                    }
                }.apply { isDaemon = true }.start()

                val reader = Anymote.FrameReader(s.getInputStream())
                while (running) {
                    val frame = reader.readFrame() ?: break
                    val push = Anymote.parseDataPush(frame) ?: continue
                    if (push.first == "sysconfig" && !readyNotified) {
                        readyNotified = true
                        onReady()
                    }
                    onData(push.first, push.second)
                }
            } catch (t: Throwable) {
                reason = "连接失败：" + (t.message ?: t.javaClass.simpleName)
            } finally {
                connected = false
                onClosed(reason)
            }
        }.apply { isDaemon = true }.start()
    }

    fun send(frame: ByteArray): Boolean {
        return try {
            val o = out ?: return false
            o.write(frame)
            o.flush()
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** 单击：DOWN + UP */
    fun sendKey(keycode: Int) {
        send(Anymote.keyMessage(keycode, true))
        try {
            Thread.sleep(30)
        } catch (t: InterruptedException) {
        }
        send(Anymote.keyMessage(keycode, false))
    }

    fun sendKeyDown(keycode: Int) {
        send(Anymote.keyMessage(keycode, true))
    }

    fun sendKeyUp(keycode: Int) {
        send(Anymote.keyMessage(keycode, false))
    }

    fun sendText(text: String) {
        send(Anymote.dataMessage("input_method", text))
    }

    fun disconnect() {
        running = false
        connected = false
        try {
            socket?.close()
        } catch (t: Throwable) {
        }
        socket = null
        out = null
    }

    companion object {
        const val DEFAULT_PORT = 9005
        private const val HEARTBEAT_MS = 30000L
    }
}
