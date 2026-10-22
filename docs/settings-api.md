# 设置项 API 逆向（自开发设置 App 指南）

> 目标：用一个自己开发的 App 替代/补充 JMGO M6 的设置界面。
> 本文给出**全部可编程设置项**、实现通道与调用方式，全部基于本固件逆向结论。

## 1. 有三条 API 通道

| 通道 | 访问方式 | 覆盖范围 | 第三方 App 可用性 |
|---|---|---|---|
| **A. `android.app.HolatekOSManager`**（框架系统服务） | `context.getSystemService("holatekos_mananger")` | **投影仪硬件全部设置**：亮度/对比度/RGB/图像模式/画面比例/数码变焦/投影方式/梯形校正/风扇/对焦/光机电源/温度/定时关机/3D/声音/输入源/工厂模式/SN/MAC/MCU 等 | 服务未剥离；launcher（普通 app 进程）即这样取用 → **预期可用**（权限校验未发现，需真机验证） |
| **B. `com.jmgo.systemapi.SettingService`**（AIDL 绑定） | `bindService(action="com.jmgo.systemapi.SettingService", pkg="com.jmgo.systemapi")` → `ISettings` | 系统级杂项：语言/时区/时间/输入法/音量/以太网/静态IP/热点/ADB开关/静默安装/清缓存/恢复出厂/重启关机/默认桌面等 | 服务有 name 校验逻辑待测；launcher 即这样绑定 |
| **C. 标准 Android API** | 常规 SDK | WiFi/蓝牙/音量/显示/应用管理 | 完全可用（部分需系统权限，见下） |

外加两条**广播**（内核/框架自定义）：`android.intent.action.auto_adjust`（一键自动校正/对焦）、`android.intent.action.HOLATEK_DLP_POWER_ON`、`android.intent.action.HOLATEK_ONE_KEY_THREED`。

## 2. 通道 A：`HolatekOSManager` 完整 API（核心）

取用方式（任何 App）：

```java
Object m = context.getSystemService("holatekos_mananger");   // android.app.HolatekOSManager
```

通用方法（框架 `IHolatekOSService`，`services.odex` 内 `com.android.server.holatek.HolatekOSService` 实现）：

```java
int   get(int service, int command);                        // 读整型值
int   set(int service, int command, int value);             // 写整型值（返回 1=成功）
int[] getArray(int service, int command);                   // 读数组（如 RGB）
int   setArray(int service, int command, int[] value);      // 写数组
String getString(int service, int command);                 // 读字符串（SN/MAC/版本）
int   setString(int service, int command, String v);
int   getParametersMin(int command);   int getParametersMax(int command);  // 取值范围
int   getState();   int reset();   int setEnable(boolean);
```

`service` 取值（`HolatekOSConfig.Service`）：**0 = DLP**（光机/MCU，必用）、1 = TV（MTK 中间件）、2 = UPDATE。

### 2.1 已实测确认的 command 表（从 launcher `.so`+`dex` 逆向，含实测值）

| 设置项 | service | command | 说明 / 取值 |
|---|---|---|---|
| **亮度** | 0 | **100** | `DLP_CMD_LUMINANCE`；范围查 `getParametersMin/Max(100)`；launcher 固定用 100 |
| 对比度 | 0 | 101 | `DLP_CMD_CONTRAST` |
| 图像模式 | 0 | 102 | `DLP_CMD_VIDEO_MODE`（或经由 140 的 `video_model`） |
| 画面比例 | 0 | 103 | `DLP_CMD_ASPECT_RATIO`：0=4:3, 1=16:9, 2=16:10（ordinal） |
| 数码变焦 | 0 | 104 | `DLP_CMD_DIGITAL_ZOOM`（Big 变焦=142） |
| **自动梯形校正** | 0 | 105 | `DLP_CMD_TRAPEZOID_AUTO`：0/1 |
| **手动梯形校正** | 0 | 106 | `DLP_CMD_TRAPEZOID_HAND`；范围 `getParametersMin/Max(106)` |
| （位置/K 角） | 0 | 107 或 108 | 108=投影方式（见下）；107 与 108 相近，试 `getParametersMin/Max` 探测 |
| **投影方式** | 0 | **108** | `DLP_CMD_PROJECTION_MODEL`；值= 枚举 ordinal：**0=正投(FRONT), 1=吊装正投(FRONT_CEILING), 2=背投(REAR), 3=吊装背投(REAR_CEILING)**；`setHang(true)` 即切到 1/3 |
| 亮度 RGB 三色 | 0 | **109**（数组） | `int[3]` = {R,G,B}；`getArray/setArray(0,109,...)`；单色上限用 `getParametersMax(109/110/111)`（红/绿/蓝） |
| 绿/蓝参数 | 0 | 110/111 | 见上 |
| **风扇** | 0 | **112** | `DLP_CMD_FAN_SPEED`；0=自动, 1=高速, 2..6=档位1..6（不同机型档位数不同，M6 为"三档+自动"，查 `getParametersMin/Max(112)`）；风扇状态(错误码)见 `SYS_VAL_FAN_STATUS_MODE` |
| 电源(AC/DC) | 0 | 115 | 0/1 表示直流(电池)或交流 |
| 电池电量 | 0 | 116 | 百分比 |
| **光机电源** | 0 | 120/121 | 两个写口都置 1 = 开光机（`setDlpPowerState(true)`）；有 `action.HOLATEK_DLP_POWER_ON` 广播可用 |
| **关机** | 0 | 122 | 写 0 触发系统关机（`shutdownSystem()`） |
| 恢复默认 | 0 | 124 | 写 0（`cleanDefault()`） |
| DLP 版本 | 0 | 126 | `getString` |
| MCU 版本 | 0 | 127 | `getString` |
| **定时关机** | 0 | 129 | `DLP_CMD_TIMER_SHUTDOWN`（分钟？） |
| 低功耗模式 | 0 | 132 | `DLP_LOW_POWER_MODE`：0=正常,1=低（HolatekOSConfig 枚举）；本机通过 handler 延迟写 |
| 视频模式(video_model) | 0 | 140 | `get/set(0,140,v)`；JNI 用 "VIDEO_MODEL" 键；取值 `DLP_VAL_VIDEO_MODE`：亮/游戏/电影/PC/用户 |
| 数码变焦(Big/光学) | 0 | 142 | `DISPLAY_ZOOM_IN` |
| **SN 序列号** | 0 | 147 | `getString` |
| **开机自动对焦** | 0 | 150 | `open_focus_auto`：1=开,0=关 |
| 校准(陀螺仪?) | 0 | 160 | `set Calibration`（写 1） |
| **工厂模式** | 0 | 162 | `DLP_CMD_FACTORY_MODE`；读=当前，写=进入 |
| 3D 模式 | 1 | 400 | `TV_CMD_3D_DISPLAY_MODE_DB`；3D 转 2D=401 |
| **信号源(输入源)** | 1 | 403 | `TV_CMD_INPUTSOURCE`；取值 `TV_VAL_INPUTSOURCE`：`INPUTSOURCE_*`（ATV/DTV/HDMI1/HDMI2/AV/…），配合 `getString`/枚举 |
| 声音模式 | 1 | 404 | `TV_CMD_SOUND_MODE`（标准/电影/音乐/新闻/用户…） |
| SPDIF 输出 | 1 | 408 | `TV_CMD_SPDIF_OUT`：PCM/RAW/SPDIF/HDMI |
| 声音输出通道 | 1 | 421 | `TV_CMD_SOUND_OUTPUT_MODE` |
| 静音(开关位) | 1 | 422/423 | 写 14=静音开？(`setSoundMute`) |
| **开机默认信号源** | 1 | 424 | `TV_CMD_INPUTSOURCE_DEFAULT_SYSTEM_STARTUP`（即"自启"信号源），值同 403 |
| **蓝牙工作模式** | 1 | 427 | `TV_CMD_ANDROID_BT_MODE`；返回值 2 = "bt"（走 BT 遥控/栈），否则 "android"（系统蓝牙） |

### 2.2 其余命令名（值需在真机探测）

完整命令枚举（`HolatekOSConfig.Command`，共 ~102 个）：见
[`artifacts/settings/holatek-commands.txt`](../artifacts/settings/holatek-commands.txt)。
其中与用户设置直接相关的还有：

- **对焦**：`DLP_CMD_FOUCS_POSITIVE` / `DLP_CMD_FOUCS_REVERSE`（正/反向对焦步进）、`DLP_CMD_MOTOR_CONTROL`、`DLP_CMD_MOTOR_EDGE_DETECT`、`DLP_CMD_AUTO_FOCUS_STATUS`、`DLP_CMD_SENSOR_TRIGER_FOCUS`、`DLP_CMD_ACCELEROMETER_CALIBRATION`
- **梯形/校正**：`DLP_CMD_TRAPEZOID_MANUAL_CORRECTION`、`DLP_CMD_KEYSTONE`
- **温度/保护**：`DLP_CMD_TEMPERATURE`、`DLP_CMD_DEVICES_TEMPERATURES`、`DLP_CMD_PROXIMITY_SENSOR`（护眼）
- **图像高级**：`DLP_CMD_COLOR_R/G/B`、`DLP_CMD_COLOR_TEMPERATURE`、`DLP_CMD_GAMMA`、`DLP_CMD_DYNAMIC_BLACK`、`DLP_CMD_LOOK_SELECT`、`DLP_CMD_MAX_RGB_LED_CURRENT`、`DLP_CMD_DMD_TYPE`、`DLP_CMD_BLACK`、`DLP_CMD_FROZEN`
- **光学/机械**：`DLP_CMD_OPTICAL_ZOOM`、`DEV_CMD_INDEX_AUTOMOTOR_ALINE`（自动门/滑盖电机对齐）、`DLP_VAL_AUTO_MOTOR_DOOR_STATUS`、`DLP_VAL_HALL_GPIO_MOTOR_DOOR_STATUS`
- **维护**：`DLP_CMD_RESET_DLP`、`DLP_CMD_INIT_DLP`、`DLP_CMD_UPDATE_MCU`、`DLP_CMD_RESET_WIFI_GPIO`、`DLP_CMD_SYS_MAC_NUMBER`、`DLP_CMD_DEVICES_MCUVERSION`、`DLP_CMD_READ_BATTARY_*`、`DLP_CMD_DUTY_CYCLES_SELECT`、`DLP_CMD_DLP_AUDIO_OUPT_MODE`、`DLP_CMD_LED_CONTROL`
- **TV 侧其它**：`TV_CMD_BRIGHTNESS/CONTRAST/HUE/SATURARION/SHARPNESS/BASS/TREBLE/SOUND_BALANCE`（对 TV 输入的画质/音效，与 0/100 的 DLP 亮度不同）、`TV_CMD_COLOR_TEMP`、`TV_CMD_PICTURE_MODE`、`TV_CMD_ARC_TYPE`、`TV_CMD_DLC`、`TV_CMD_POWER_TIMER`、`TV_CMD_LRVIEWSWITCH`（左右视角）、`TV_CMD_RESTORE_TO_DEFAULT`、`TV_CMD_3D_*`、`TV_CMD_INPUT_SOURCE_STATUS`、`TV_CMD_SELF_3D_ADAPTIVE_DETECT`
- **UPDATE 服务(2)**：`UPDATE_CMD_GET/SET_FACTORY_FLAG`、`GET/SET_RECOVERY_FLAG`、`GET_LASTWORKTIME`

> 探测方法：对未知 command 调 `getParametersMin(cmd)/getParametersMax(cmd)`（返回 0 区间=无效），或 `get(0, cmd)` 看返回值是否合理。

## 3. 自开发 App 的调用方式（含可直接用的代码）

由于 `HolatekOSManager` 在 boot 类路径（`boot.oat` 内，`/system/framework/com.jmgo.android.jar` 是空壳），**编译期用反射调用最稳**（无需额外 jar）：

```java
public class HolatekApi {
    private final Object m;                 // android.app.HolatekOSManager
    private final java.lang.reflect.Method mGet, mSet, mGetStr;

    public HolatekApi(Context ctx) throws Exception {
        m = ctx.getSystemService("holatekos_mananger");
        Class<?> c = Class.forName("android.app.HolatekOSManager");
        mGet    = c.getMethod("get", int.class, int.class);
        mSet    = c.getMethod("set", int.class, int.class, int.class);
        mGetStr = c.getMethod("getString", int.class, int.class);
    }
    public int  get(int svm, int cmd)                 { return (Integer) mGet.invoke(m, svm, cmd); }
    public int  set(int svm, int cmd, int v)          { return (Integer) mSet.invoke(m, svm, cmd, v); }
    public String getString(int svm, int cmd)         { return (String) mGetStr.invoke(m, svm, cmd); }

    // 常用封装
    public int  brightness()                          { return get(0, 100); }
    public void setBrightness(int v)                  { set(0, 100, v); }
    public int  fan()                                 { return get(0, 112); }
    public void setFan(int v)                         { set(0, 112, v); }
    public int  projMode()                            { return get(0, 108); }        // 0正投 1吊装 2背投 3吊装背投
    public void setProjMode(int v)                    { set(0, 108, v); }
    public int  autoTrapezoid()                       { return get(0, 105); }
    public void setAutoTrapezoid(boolean on)          { set(0, 105, on?1:0); }
    public int  manualTrapezoidRangeMax()             { return (Integer) invokeParams("getParametersMax", 106); }
    public void setManualTrapezoid(int v)             { set(0, 106, v); }
    public void oneKeyAutoAdjust()                    { ctx.sendBroadcast(new Intent("android.intent.action.auto_adjust")); }
}
```

> 也可做成 **priv-app**（放入 system/priv-app 并刷入本仓库重打包的 OTA）获得 `WRITE_SECURE_SETTINGS` 等权限，部分设置会更稳。

## 4. 通道 B：`com.jmgo.systemapi.SettingService`（系统杂项）

绑定（与 launcher 相同）：

```java
Intent i = new Intent("com.jmgo.systemapi.SettingService").setPackage("com.jmgo.systemapi");
bindService(i, conn, BIND_AUTO_CREATE);   // ISettings (AIDL, 描述符 "com.jmgo.systemapi.ISettings")
```

`ISettings` 方法（`TRANSACTION_*`，来自 dex）：网络/以太网 `setEthEnable / setEthEnabled / setMstarEthEnable / setStaticIp / setWifiApEnable(d) / setDataEnable / getDataEnable / getMacAddress`；
语言/时间/输入法 `setLanguage(Commen) / setTimeZone / onDateSet / onTimeSet`；
音量 `adjustVolume / setVolumeTo / setSoundMute`（`setKeySoundState`）；
系统 `enableAdb / restoreFactory / shutdonw / reStart / updateOta / clearAllAppCache / clearPkgData / cleanAllTask / formatSdcard / mount/unmountVolume / installApk / uninstallPkg / setLauncher(getLauncher 默认桌面) / setComponentEnable / setDefaultDataSubId` 等。
（完整清单见 `docs/install-limit.md` 同源的 TRANSACTION 列表或反编译 `apks/SystemApi.apk`。）

## 5. 通道 C：标准 Android（WiFi / 蓝牙等）

| 设置 | API | 需要的权限 |
|---|---|---|
| **WiFi 开关/连接/扫描** | `WifiManager`（`setWifiEnabled`, `startScan`, `addNetwork+connect` on API24） | `ACCESS_WIFI_STATE` + `CHANGE_WIFI_STATE`（普通权限，声明即得）——**可完全自己实现** |
| 热点/AP | `WifiManager.setWifiApEnabled`（隐藏，需 `WRITE_SETTINGS`+系统 UID） | 或走通道 B `setWifiApEnable` |
| 静态 IP | `WifiConfiguration.ipAssignment` 或通道 B `setStaticIp` | 普通（连接配置） |
| **蓝牙开关/扫描/配对** | `BluetoothAdapter.enable/startDiscovery`；配对 `createBond`（隐藏）；连接 A2DP/HID 用对应 Profile Proxy | `BLUETOOTH`+`BLUETOOTH_ADMIN` 普通即可开关与扫描；`createBond` 为隐藏 API（反射可用）；部分操作需 `BLUETOOTH_PRIVILEGED`（signature\|priv）。JMGO 整套蓝牙 UI（`com.jmgo.setting.bluetooth.*`）就在 launcher 内，可直接参考其做法 |
| 蓝牙"工作模式" | 通道 A `get(1, 427)`（bt / android 两套栈） | — |
| 系统音量 | `AudioManager`（普通）或通道 B `setVolumeTo` | 普通 |
| 屏幕亮度（Android 层面）| `Settings.System.SCREEN_BRIGHTNESS` | `WRITE_SETTINGS`（API23+需用户授权）——但投影**灯泡亮度用通道 A 的 0/100** |
| 输入源切换 | 通道 A `set(1,403,v)` | — |
| 应用自启管理 | `pm disable/enable`（需 root）；JMGO 侧未见独立"自启白名单"服务，`select 默认信号源=开机自启` 即通道 A `1/424`；launcher 的 `com.jmgo.processmanagement` 仅用于内存清理 | root 或 priv-app |
| 恢复出厂 | 通道 B `restoreFactory`（或 `MASTER_CLEAR` 广播，需系统权限）| signature\|priv |
| 关机/重启 | 通道 A `set(0,122,0)`（关机）、`reStart`（通道 B）| — / priv |

## 6. 验证清单（真机）

1. `adb shell dumpsys | grep -i holatek` 或
   `adb shell service list | grep holatek` → 应看到 **holatekOSService/holatekos_mananger**。
2. 用本文 §3 的 harness（几十行）测 `get(0,100)` 是否返回亮度（应 ≈ 当前亮度 0..100）。
3. 测 `getParametersMin/Max(100)`（亮度范围）、`(106)`（梯形范围）、`(112)`（风扇档位）。
4. 若反射调用抛 `SecurityException`，改造成 priv-app（系统签名本仓库已具备 testkey 重签能力，见 `docs/repack.md`）后放 `system/priv-app` 再试。
5. 广播类：`am broadcast -a android.intent.action.auto_adjust` 观察是否触发自动校正（需在连接 ADB 的 Linux world/Android 侧）。

## 7. 证据与环境（便于复核）

- API 签名与枚举类：`/system/framework/arm64/boot-framework.oat`（内嵌 dex，magic `dex\n037` @0x28a8；含 `android.app.HolatekOSManager`、`HolatekOSConfig$*`、`android.os.IHolatekOSService`）。
- 服务实现：`/system/framework/oat/arm64/services.odex`（`com.android.server.holatek.HolatekOSService` 等）。
- 服务端存储：`EEPROM_*` 地址（亮度/风扇/梯形/投影方式/SN/开机信号源…）经 I²C 写入 DLP 板；MCU 命令 `API_FAN_SPEED_TO_MCU` 等；串口 `/dev/ttyMT1`。
- 行为与 command 值：`apks/JmGoOS.apk` 内 `lib/armeabi/libcocos2dlua.so` 的 `HolatekOSManagerHelper::*` + classes.dex 的 `org.cocos2dx.cpp.HolatekOSManagerHelper`（本文表格即逐一反汇编所得）。
- 完整命令枚举：`artifacts/settings/holatek-commands.txt`。

## 8. 注意事项

- `getSystemService("holatekos_mananger")` 名字**有一个拼写错误**（mananger），照抄即可。
- 服务返回约定：`set` 成功返回 1；失败/无效 command 返回非 1（探测时判 1）。
- **谨慎项**：`DLP_CMD_UPDATE_MCU`、`RESET_DLP`、`INIT_DLP`、`UPDATE_CMD_*`、`GPIO_CMD_POWER`、`DUTY_CYCLES_SELECT`（LED 占空比）等属工厂/维护命令，误用可能损坏光机或 MCU，勿在成品 App 暴露。
