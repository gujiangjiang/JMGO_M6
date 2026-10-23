package com.gujiangjiang.jmgocontrol.core

/**
 * android.app.HolatekOSManager 反射封装（坚果定制系统服务）。
 *
 * 注意：系统服务名 "holatekos_mananger" 有一个拼写错误（mananger），
 * 这是官方固件原样，必须照抄。该类位于 boot 类路径（boot.oat），
 * 编译期不可直接引用，因此使用反射。
 *
 * 命令表见仓库 docs/settings-api.md 与 artifacts/settings/holatek-commands.txt。
 */
class HolatekApi private constructor(
    private val svc: Any,
    private val methods: Map<String, java.lang.reflect.Method>
) {

    private fun invoke(name: String, vararg args: Any?): Any? {
        val m = methods[name] ?: return null
        return try {
            m.invoke(svc, *args)
        } catch (t: Throwable) {
            null
        }
    }

    fun get(service: Int, cmd: Int): Int? = invoke("get", service, cmd) as? Int

    fun set(service: Int, cmd: Int, value: Int): Int? = invoke("set", service, cmd, value) as? Int

    fun getString(service: Int, cmd: Int): String? = invoke("getString", service, cmd) as? String

    fun setString(service: Int, cmd: Int, value: String): Int? =
        invoke("setString", service, cmd, value) as? Int

    fun getArray(service: Int, cmd: Int): IntArray? = invoke("getArray", service, cmd) as? IntArray

    fun setArray(service: Int, cmd: Int, value: IntArray): Int? =
        invoke("setArray", service, cmd, value) as? Int

    fun min(cmd: Int): Int? = invoke("getParametersMin", cmd) as? Int

    fun max(cmd: Int): Int? = invoke("getParametersMax", cmd) as? Int

    companion object {
        const val SERVICE_NAME = "holatekos_mananger"

        fun create(context: android.content.Context): HolatekApi? {
            val svc = try {
                context.getSystemService(SERVICE_NAME)
            } catch (t: Throwable) {
                null
            } ?: return null
            val cls = try {
                Class.forName("android.app.HolatekOSManager")
            } catch (t: Throwable) {
                return null
            }
            val map = HashMap<String, java.lang.reflect.Method>()
            fun add(name: String, vararg params: Class<*>) {
                try {
                    map[name] = cls.getMethod(name, *params)
                } catch (t: Throwable) {
                    // 个别方法缺失不影响其它功能
                }
            }
            val i = Int::class.javaPrimitiveType!!
            add("get", i, i)
            add("set", i, i, i)
            add("getString", i, i)
            add("setString", i, i, String::class.java)
            add("getArray", i, i)
            add("setArray", i, i, IntArray::class.java)
            add("getParametersMin", i)
            add("getParametersMax", i)
            if (map.isEmpty()) return null
            return HolatekApi(svc, map)
        }
    }
}

/** HolatekOSConfig.Command —— 已实测确认的命令码（详表见 docs/settings-api.md） */
object HolatekCmd {
    const val SVC_DLP = 0
    const val SVC_TV = 1
    const val SVC_UPDATE = 2

    const val LUMINANCE = 100
    const val CONTRAST = 101
    const val VIDEO_MODE = 102
    const val ASPECT_RATIO = 103
    const val DIGITAL_ZOOM = 104
    const val TRAPEZOID_AUTO = 105
    const val TRAPEZOID_HAND = 106
    const val PROJECTION_MODEL = 108
    const val COLOR_RGB = 109
    const val COLOR_G = 110
    const val COLOR_B = 111
    const val FAN_SPEED = 112
    const val AC_DC_STATE = 115
    const val BATTERY = 116
    const val POWER_ON_DLP = 120
    const val POWER_CTRL_DLP = 121
    const val SHUTDOWN = 122
    const val RESTORE_DEFAULT = 124
    const val VERSION_DLP = 126
    const val VERSION_MCU = 127
    const val TIMER_SHUTDOWN = 129
    const val LOW_POWER_MODE = 132
    const val VIDEO_MODEL = 140
    const val DISPLAY_ZOOM_IN = 142
    const val SN = 147
    const val FOCUS_AUTO_ON_BOOT = 150
    const val CALIBRATION = 160
    const val FACTORY_MODE = 162

    const val TV_3D_MODE = 400
    const val TV_3D_TO_2D = 401
    const val TV_INPUTSOURCE = 403
    const val TV_SOUND_MODE = 404
    const val TV_SPDIF = 408
    const val TV_SOUND_OUTPUT = 421
    const val TV_MUTE_ON = 422
    const val TV_MUTE_OFF = 423
    const val TV_INPUTSOURCE_BOOT = 424
    const val TV_BT_MODE = 427

    const val ACTION_AUTO_ADJUST = "android.intent.action.auto_adjust"
    const val ACTION_DLP_POWER_ON = "android.intent.action.HOLATEK_DLP_POWER_ON"
    const val ACTION_ONE_KEY_3D = "android.intent.action.HOLATEK_ONE_KEY_THREED"
}
