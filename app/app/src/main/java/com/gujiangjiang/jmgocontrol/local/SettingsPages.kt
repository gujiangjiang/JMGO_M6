package com.gujiangjiang.jmgocontrol.local

import com.gujiangjiang.jmgocontrol.core.HolatekCmd

enum class Kind { SLIDER, LIST, SWITCH, ACTION, INFO, RGB }

data class Opt(val label: String, val value: Int)

data class SettingSpec(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val service: Int = HolatekCmd.SVC_DLP,
    val cmd: Int = 0,
    val kind: Kind = Kind.LIST,
    val options: List<Opt> = emptyList(),
    val min: Int = 0,
    val max: Int = 100,
    val risky: Boolean = false,
    val doubleConfirm: Boolean = false,
    val isAudioVolume: Boolean = false,
    val isAudioMute: Boolean = false,
    val broadcastAction: String? = null,
    val writeValue: Int? = null,
    val isStringInfo: Boolean = false
)

/**
 * 本地设置页清单。命令码来源：docs/settings-api.md 实测表。
 * 说明：
 * - risky=true 的设置应用后会弹出倒计时确认，超时自动回退；
 * - doubleConfirm=true 的设置（实验室页）点击后先弹高风险确认框；
 * - 标注"枚举序"的选项值为 HolatekOSConfig$* 值类声明序，真机读回可校验。
 */
object SettingsPages {

    fun general(): List<SettingSpec> = listOf(
        SettingSpec(
            "luminance", "光机亮度", "受最低亮度阈值保护（阈值可在应用设置中调整）",
            cmd = HolatekCmd.LUMINANCE, kind = Kind.SLIDER, min = 0, max = 100
        ),
        SettingSpec(
            "contrast", "对比度", null,
            cmd = HolatekCmd.CONTRAST, kind = Kind.SLIDER, min = 0, max = 100
        ),
        SettingSpec(
            "video_mode", "图像模式", null,
            cmd = HolatekCmd.VIDEO_MODE, kind = Kind.LIST,
            options = listOf(Opt("亮度", 0), Opt("游戏", 1), Opt("电影", 2), Opt("PC", 3), Opt("用户", 4))
        ),
        SettingSpec(
            "trap_auto", "自动梯形校正", "关闭后需手动校正（风险：画面可能变化）",
            cmd = HolatekCmd.TRAPEZOID_AUTO, kind = Kind.SWITCH,
            options = listOf(Opt("关", 0), Opt("开", 1)), risky = true
        ),
        SettingSpec(
            "proj_mode", "投影方式", "错误设置会导致画面倒置（自动回退保护）",
            cmd = HolatekCmd.PROJECTION_MODEL, kind = Kind.LIST,
            options = listOf(Opt("正投", 0), Opt("吊装正投", 1), Opt("背投", 2), Opt("吊装背投", 3)),
            risky = true
        ),
        SettingSpec(
            "focus_auto", "开机自动对焦", null,
            cmd = HolatekCmd.FOCUS_AUTO_ON_BOOT, kind = Kind.SWITCH,
            options = listOf(Opt("关", 0), Opt("开", 1))
        ),
        SettingSpec(
            "sound_mode", "声音模式", null,
            service = HolatekCmd.SVC_TV, cmd = HolatekCmd.TV_SOUND_MODE, kind = Kind.LIST,
            options = listOf(Opt("标准", 0), Opt("电影", 1), Opt("音乐", 2), Opt("新闻", 3), Opt("用户", 4))
        ),
        SettingSpec(
            "volume", "媒体音量", "系统媒体音量（AudioManager）",
            kind = Kind.SLIDER, isAudioVolume = true, min = 0, max = 15
        ),
        SettingSpec(
            "mute", "静音", null,
            kind = Kind.SWITCH, isAudioMute = true,
            options = listOf(Opt("关", 0), Opt("开", 1))
        ),
        SettingSpec(
            "input_source", "信号源", "切换输入源；无信号时可能黑屏（自动回退保护）",
            service = HolatekCmd.SVC_TV, cmd = HolatekCmd.TV_INPUTSOURCE, kind = Kind.LIST,
            options = listOf(Opt("无", 0), Opt("ATV", 1), Opt("DTV", 2), Opt("AV", 3), Opt("HDMI1", 4), Opt("HDMI2", 5)),
            risky = true
        ),
        SettingSpec(
            "timer_shutdown", "定时关机", "数值按分钟直写（视固件行为而定）",
            cmd = HolatekCmd.TIMER_SHUTDOWN, kind = Kind.LIST,
            options = listOf(Opt("关闭", 0), Opt("30 分钟", 30), Opt("60 分钟", 60), Opt("90 分钟", 90), Opt("120 分钟", 120))
        ),
        SettingSpec(
            "auto_adjust", "一键自动校正/对焦", "等价于遥控器上的自动校正按钮",
            kind = Kind.ACTION, broadcastAction = HolatekCmd.ACTION_AUTO_ADJUST
        )
    )

    fun advanced(): List<SettingSpec> = listOf(
        SettingSpec(
            "digital_zoom", "数码变焦", "修改画面缩放（自动回退保护）",
            cmd = HolatekCmd.DIGITAL_ZOOM, kind = Kind.SLIDER, min = 0, max = 100, risky = true
        ),
        SettingSpec(
            "trap_hand", "手动梯形校正", "范围由固件上下限决定（自动回退保护）",
            cmd = HolatekCmd.TRAPEZOID_HAND, kind = Kind.SLIDER, risky = true
        ),
        SettingSpec(
            "aspect", "画面比例", "选项值为枚举序（自动回退保护）",
            cmd = HolatekCmd.ASPECT_RATIO, kind = Kind.LIST,
            options = listOf(Opt("16:10", 0), Opt("16:9", 1), Opt("4:3", 2)), risky = true
        ),
        SettingSpec(
            "boot_source", "开机默认信号源", "仅影响开机自启时的信号源",
            service = HolatekCmd.SVC_TV, cmd = HolatekCmd.TV_INPUTSOURCE_BOOT, kind = Kind.LIST,
            options = listOf(Opt("无", 0), Opt("ATV", 1), Opt("DTV", 2), Opt("AV", 3), Opt("HDMI1", 4), Opt("HDMI2", 5))
        ),
        SettingSpec(
            "spdif", "SPDIF 输出", null,
            service = HolatekCmd.SVC_TV, cmd = HolatekCmd.TV_SPDIF, kind = Kind.LIST,
            options = listOf(Opt("PCM", 0), Opt("RAW", 1), Opt("SPDIF", 2), Opt("HDMI", 3))
        ),
        SettingSpec(
            "sound_out", "声音输出通道", null,
            service = HolatekCmd.SVC_TV, cmd = HolatekCmd.TV_SOUND_OUTPUT, kind = Kind.LIST,
            options = listOf(Opt("单声道", 0), Opt("双声道", 1))
        ),
        SettingSpec(
            "low_power", "低功耗模式", "降低光机功率与风扇转速（自动回退保护）",
            cmd = HolatekCmd.LOW_POWER_MODE, kind = Kind.SWITCH,
            options = listOf(Opt("正常", 0), Opt("低功耗", 1)), risky = true
        ),
        SettingSpec(
            "bt_mode", "蓝牙工作模式（只读）", "2 = 走 BT 专用栈；其它值 = 系统蓝牙",
            service = HolatekCmd.SVC_TV, cmd = HolatekCmd.TV_BT_MODE, kind = Kind.INFO
        )
    )

    fun lab(): List<SettingSpec> = listOf(
        SettingSpec(
            "rgb", "RGB 增益", "三色 LED 电流参数（cmd 109 数组）",
            cmd = HolatekCmd.COLOR_RGB, kind = Kind.RGB, doubleConfirm = true
        ),
        SettingSpec(
            "factory", "进入工厂模式", "写入工厂模式标志，可能影响系统稳定性",
            cmd = HolatekCmd.FACTORY_MODE, kind = Kind.ACTION, writeValue = 1, doubleConfirm = true
        ),
        SettingSpec(
            "restore", "恢复默认设置", "清除 DLP 侧自定义参数（恢复默认状态）",
            cmd = HolatekCmd.RESTORE_DEFAULT, kind = Kind.ACTION, writeValue = 0, doubleConfirm = true
        ),
        SettingSpec(
            "calibration", "陀螺仪校准", "写 1 触发校准；建议将投影仪水平静置后执行",
            cmd = HolatekCmd.CALIBRATION, kind = Kind.ACTION, writeValue = 1, doubleConfirm = true
        ),
        SettingSpec(
            "shutdown", "立即关机", "整机关机（写 0 触发，操作后设备关机）",
            cmd = HolatekCmd.SHUTDOWN, kind = Kind.ACTION, writeValue = 0, doubleConfirm = true
        ),
        SettingSpec(
            "info_dlp", "DLP 版本", null,
            cmd = HolatekCmd.VERSION_DLP, kind = Kind.INFO, isStringInfo = true
        ),
        SettingSpec(
            "info_mcu", "MCU 版本", null,
            cmd = HolatekCmd.VERSION_MCU, kind = Kind.INFO, isStringInfo = true
        ),
        SettingSpec(
            "info_sn", "序列号 SN", null,
            cmd = HolatekCmd.SN, kind = Kind.INFO, isStringInfo = true
        ),
        SettingSpec(
            "info_battery", "电池电量", null,
            cmd = HolatekCmd.BATTERY, kind = Kind.INFO
        ),
        SettingSpec(
            "info_acdc", "供电状态", "0 = 直流（电池）/ 1 = 交流",
            cmd = HolatekCmd.AC_DC_STATE, kind = Kind.INFO
        )
    )
}
