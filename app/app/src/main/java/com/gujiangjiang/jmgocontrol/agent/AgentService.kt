package com.gujiangjiang.jmgocontrol.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.gujiangjiang.jmgocontrol.BuildConfig
import com.gujiangjiang.jmgocontrol.MainActivity
import com.gujiangjiang.jmgocontrol.R
import com.gujiangjiang.jmgocontrol.core.AppPrefs
import com.gujiangjiang.jmgocontrol.core.HolatekApi
import com.gujiangjiang.jmgocontrol.core.HolatekCmd
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * 投影仪端「远程助手」：为手机端提供高级遥控能力。
 * - HTTP 服务（端口 9399，X-Pin 鉴权）：亮度/设置读写、截屏、APK 推送安装、
 *   重置安装数量限制、文件浏览/下载/上传；
 * - UDP 9188 发现应答：手机可在局域网内自动找到 Agent。
 * 运行条件：本机模式 + 应用设置中「远程助手服务」开启（system 变体权限最完整）。
 */
class AgentService : Service() {

    private var serverSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    private var api: HolatekApi? = null

    @Volatile
    private var running = false

    override fun onCreate() {
        super.onCreate()
        api = HolatekApi.create(this)
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) start()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }

    private fun start() {
        running = true
        Thread { acceptLoop() }.apply { isDaemon = true }.start()
        Thread { udpLoop() }.apply { isDaemon = true }.start()
    }

    private fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (t: Throwable) {
        }
        try {
            udpSocket?.close()
        } catch (t: Throwable) {
        }
        serverSocket = null
        udpSocket = null
    }

    private fun acceptLoop() {
        try {
            val ss = ServerSocket(PORT)
            ss.reuseAddress = true
            serverSocket = ss
            while (running) {
                val s = try {
                    ss.accept()
                } catch (t: Throwable) {
                    if (running) continue else break
                }
                Thread { handle(s) }.apply { isDaemon = true }.start()
            }
        } catch (t: Throwable) {
        }
    }

    private fun udpLoop() {
        try {
            val ds = DatagramSocket(UDP_PORT)
            ds.soTimeout = 1000
            udpSocket = ds
            val buf = ByteArray(256)
            while (running) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    ds.receive(pkt)
                    val txt = String(pkt.data, 0, pkt.length).trim()
                    if (txt.startsWith(DISCOVERY_MAGIC)) {
                        val resp = DISCOVERY_MAGIC + " " + JSONObject().apply {
                            put("app", "jmgo-control")
                            put("api", API_VERSION)
                            put("model", Build.MODEL)
                            put("port", PORT)
                        }.toString()
                        val out = resp.toByteArray(Charsets.UTF_8)
                        ds.send(DatagramPacket(out, out.size, pkt.address, pkt.port))
                    }
                } catch (t: SocketTimeoutException) {
                }
            }
        } catch (t: Throwable) {
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 15000
            val req = AgentHttp.readRequest(socket) ?: return
            val out = BufferedOutputStream(socket.getOutputStream())
            AgentHttp.writeResponse(out, route(req))
            out.flush()
        } catch (t: Throwable) {
        } finally {
            try {
                socket.close()
            } catch (t: Throwable) {
            }
        }
    }

    private fun route(req: HttpRequest): HttpResponse {
        if (req.path == "/ping") {
            return json(
                JSONObject().apply {
                    put("app", "jmgo-control")
                    put("api", API_VERSION)
                    put("model", Build.MODEL)
                    put("port", PORT)
                }
            )
        }
        val prefs = AppPrefs(this)
        if ((req.headers["x-pin"] ?: "") != prefs.agentPin) {
            val r = HttpResponse()
            r.code = 403
            r.json(JSONObject().put("error", "pin").toString())
            return r
        }
        return when (req.path) {
            "/info" -> json(buildInfo(prefs))
            "/settings/get" -> {
                val svc = req.query["service"]?.toIntOrNull() ?: 0
                val cmd = req.query["cmd"]?.toIntOrNull() ?: 0
                json(JSONObject().put("value", api?.get(svc, cmd) ?: JSONObject.NULL))
            }
            "/settings/getstr" -> {
                val svc = req.query["service"]?.toIntOrNull() ?: 0
                val cmd = req.query["cmd"]?.toIntOrNull() ?: 0
                json(JSONObject().put("value", api?.getString(svc, cmd) ?: JSONObject.NULL))
            }
            "/settings/set" -> {
                val svc = req.query["service"]?.toIntOrNull() ?: 0
                val cmd = req.query["cmd"]?.toIntOrNull() ?: 0
                var v = req.query["value"]?.toIntOrNull() ?: 0
                if (svc == HolatekCmd.SVC_DLP && cmd == HolatekCmd.LUMINANCE) {
                    val floor = prefs.brightnessFloor
                    if (v < floor) v = floor
                    if (v <= 0) v = 1
                    val max = api?.max(cmd)?.takeIf { it > 0 } ?: 100
                    if (v > max) v = max
                }
                json(JSONObject().put("result", api?.set(svc, cmd, v) ?: JSONObject.NULL))
            }
            "/screenshot" -> screenshot()
            "/install" -> install(req)
            "/pkgreset" -> pkgReset()
            "/files" -> files(req)
            "/file" -> fileDownload(req)
            "/upload" -> fileUpload(req)
            else -> {
                val r = HttpResponse()
                r.code = 404
                r.json(JSONObject().put("error", "not_found").toString())
                r
            }
        }
    }

    private fun buildInfo(prefs: AppPrefs): JSONObject {
        return JSONObject().apply {
            put("app", "jmgo-control")
            put("api", API_VERSION)
            put("appVersion", BuildConfig.VERSION_NAME)
            put("model", Build.MODEL)
            put("android", Build.VERSION.RELEASE)
            put("port", PORT)
            put("brightness", api?.get(HolatekCmd.SVC_DLP, HolatekCmd.LUMINANCE) ?: JSONObject.NULL)
            put("brightnessMax", api?.max(HolatekCmd.LUMINANCE) ?: 100)
            put("brightnessFloor", prefs.brightnessFloor)
            put("battery", api?.get(HolatekCmd.SVC_DLP, HolatekCmd.BATTERY) ?: JSONObject.NULL)
            put("acdc", api?.get(HolatekCmd.SVC_DLP, HolatekCmd.AC_DC_STATE) ?: JSONObject.NULL)
            put("sn", api?.getString(HolatekCmd.SVC_DLP, HolatekCmd.SN) ?: JSONObject.NULL)
        }
    }

    private fun screenshot(): HttpResponse {
        val r = HttpResponse()
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val dm = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(dm)
            val cls = Class.forName("android.view.SurfaceControl")
            val m = cls.getMethod(
                "screenshot",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            val bmp = m.invoke(null, dm.widthPixels, dm.heightPixels) as? Bitmap
            if (bmp == null) {
                r.code = 500
                r.json(JSONObject().put("error", "screenshot_null").toString())
                return r
            }
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 90, bos)
            bmp.recycle()
            r.contentType = "image/png"
            r.bytes = bos.toByteArray()
            return r
        } catch (t: Throwable) {
            r.code = 500
            r.json(
                JSONObject().put(
                    "error",
                    "screenshot: " + (t.message ?: t.javaClass.simpleName)
                ).toString()
            )
            return r
        }
    }

    private fun install(req: HttpRequest): HttpResponse {
        return try {
            val name = req.query["name"] ?: "push.apk"
            val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val f = File(cacheDir, "push_" + safe)
            f.writeBytes(req.body)
            val out = exec("pm install -r " + f.absolutePath)
            val ok = out.contains("Success", ignoreCase = true)
            json(
                JSONObject().apply {
                    put("ok", ok)
                    put("message", out)
                    put("size", req.body.size)
                }
            )
        } catch (t: Throwable) {
            json(
                JSONObject().apply {
                    put("ok", false)
                    put("message", t.message ?: t.javaClass.simpleName)
                }
            )
        }
    }

    private fun pkgReset(): HttpResponse {
        val out = exec("pm clear com.android.packageinstaller")
        val ok = out.contains("Success", ignoreCase = true)
        return json(JSONObject().apply { put("ok", ok); put("message", out) })
    }

    private fun files(req: HttpRequest): HttpResponse {
        val path = req.query["path"] ?: "/sdcard"
        val dir = File(path)
        if (!dir.isDirectory) {
            val r = HttpResponse()
            r.code = 404
            r.json(JSONObject().put("error", "not_a_directory").toString())
            return r
        }
        val arr = JSONArray()
        val list = dir.listFiles() ?: emptyArray()
        for (f in list.sortedBy { it.name.lowercase() }) {
            arr.put(
                JSONObject().apply {
                    put("name", f.name)
                    put("dir", f.isDirectory)
                    put("size", if (f.isDirectory) 0L else f.length())
                    put("mtime", f.lastModified())
                }
            )
        }
        return json(JSONObject().apply { put("path", dir.absolutePath); put("items", arr) })
    }

    private fun fileDownload(req: HttpRequest): HttpResponse {
        val path = req.query["path"] ?: ""
        val f = File(path)
        if (!f.isFile) {
            val r = HttpResponse()
            r.code = 404
            r.json(JSONObject().put("error", "not_found").toString())
            return r
        }
        val r = HttpResponse()
        r.contentType = "application/octet-stream"
        r.bytes = f.readBytes()
        return r
    }

    private fun fileUpload(req: HttpRequest): HttpResponse {
        return try {
            val dir = File(req.query["path"] ?: "/sdcard/Download")
            val name = (req.query["name"] ?: "upload.bin").replace("/", "_")
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, name)
            f.writeBytes(req.body)
            json(JSONObject().apply { put("ok", true); put("path", f.absolutePath) })
        } catch (t: Throwable) {
            json(
                JSONObject().apply {
                    put("ok", false)
                    put("message", t.message ?: t.javaClass.simpleName)
                }
            )
        }
    }

    private fun exec(cmd: String): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val stdout = p.inputStream.bufferedReader().readText()
            val stderr = p.errorStream.bufferedReader().readText()
            p.waitFor()
            (stdout + "\n" + stderr).trim()
        } catch (t: Throwable) {
            "exec_failed: " + (t.message ?: t.javaClass.simpleName)
        }
    }

    private fun json(o: JSONObject): HttpResponse = HttpResponse().apply { json(o.toString()) }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.agent_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pin = AppPrefs(this).agentPin
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app_logo)
            .setContentTitle(getString(R.string.agent_notif_title))
            .setContentText(getString(R.string.agent_notif_text, pin, PORT))
            .setOngoing(true)
            .setContentIntent(tapIntent)
            .build()
    }

    companion object {
        const val PORT = 9399
        const val UDP_PORT = 9188
        const val API_VERSION = 1
        const val DISCOVERY_MAGIC = "JMGOAGENT1"
        private const val NOTIF_ID = 9399
        private const val CHANNEL_ID = "agent"

        fun start(context: Context) {
            val i = Intent(context, AgentService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AgentService::class.java))
        }
    }
}
