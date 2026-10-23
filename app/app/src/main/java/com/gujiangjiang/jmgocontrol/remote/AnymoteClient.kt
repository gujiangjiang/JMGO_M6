package com.gujiangjiang.jmgocontrol.remote

import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue

/**
 * Anymote TCP 客户端（端口 9005，无 TLS、无鉴权、无握手）。
 * 连接后设备会主动推 volume / sysconfig 两条 Data，收到 sysconfig 视为就绪。
 *
 * 注意：Android 禁止在主线程进行网络写操作（NetworkOnMainThreadException），
 * 因此所有发送都通过内部发送队列 + 后台写线程完成（与官方 App 的
 * DeviceManager.send() 队列一致）。
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
    private val sendQueue = LinkedBlockingQueue<ByteArray>(64)

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
                sendQueue.clear()

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

                Thread {
                    while (running && connected) {
                        val frame = try {
                            sendQueue.take()
                        } catch (t: InterruptedException) {
                            break
                        }
                        try {
                            val o = out ?: break
                            o.write(frame)
                            o.flush()
                        } catch (t: Throwable) {
                        }
                        try {
                            Thread.sleep(FRAME_GAP_MS)
                        } catch (t: InterruptedException) {
                            break
                        }
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

    /** 入队发送（主线程安全）；未连接时返回 false */
    fun send(frame: ByteArray): Boolean {
        if (!connected) return false
        sendQueue.offer(frame)
        return true
    }

    /** 单击：DOWN + UP（由写线程按 22ms 间隔发出） */
    fun sendKey(keycode: Int) {
        send(Anymote.keyMessage(keycode, true))
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
        sendQueue.clear()
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
        private const val FRAME_GAP_MS = 22L

        /** 快速连通性测试：能否连上并在超时内收到 sysconfig 推送 */
        fun test(ip: String, port: Int = DEFAULT_PORT, timeoutMs: Int = 2500): Boolean {
            var s: Socket? = null
            return try {
                s = Socket()
                s.connect(InetSocketAddress(ip, port), 2000)
                s.soTimeout = timeoutMs
                val reader = Anymote.FrameReader(s.getInputStream())
                val deadline = System.currentTimeMillis() + timeoutMs
                var ok = false
                while (System.currentTimeMillis() < deadline) {
                    val frame = try {
                        reader.readFrame()
                    } catch (t: Throwable) {
                        null
                    } ?: break
                    val push = Anymote.parseDataPush(frame) ?: continue
                    if (push.first == "sysconfig") {
                        ok = true
                        break
                    }
                }
                ok
            } catch (t: Throwable) {
                false
            } finally {
                try {
                    s?.close()
                } catch (t: Throwable) {
                }
            }
        }
    }
}
