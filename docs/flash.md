# 刷机指南（官方方式 / 本地升级 / 风险与回退）

## 1. 官方刷机方式（U 盘本地升级）

官方步骤（坚果 M6）：

1. 下载 `update_jmgo.zip` 到 **U 盘根目录**；
2. U 盘接入设备；
3. 打开设备 → `设置 - 关于 - 系统升级`；
4. 系统升级界面点击 **“本地升级”** → **“立即安装”** → 等待升级成功；
5. 过程机器会关机并自动重启，**切勿断电**。

底层流程（逆向结论见 `docs/device.md` §6）：
U 盘 zip → `com.jmgo.update` 复制到 `/cache/ota_update.zip` → 写 `/cache/recovery/command --update_package=...` → 重启进 AOSP recovery → 验签 → `updater-script` 逐分区刷写 → 自动重启。

## 2. 刷入自制（重签）包

与官方流程完全相同：把 `update_jmgo_mod.zip`（名字可保持 `update_jmgo.zip`）放 U 盘根目录走“本地升级”。
recovery 只认 **testkey 签名**；本仓库产出的包默认用 testkey 重签，理论上与官方包等价可过验签。

**首次务必做“空改预检”**（见 `docs/repack.md` §5）：用未修改镜像重签一版刷一次，确认设备接受；此操作若不通过（极少见），recovery 会在写入前拒绝，**不会造成损坏**。

## 3. 兜底恢复手段

本机有**两条独立**的恢复通道：
- **Android 侧**：系统里的「本地升级」（U 盘 `update_jmgo.zip` → recovery OTA）；
- **Linux 世界侧**：U 盘 `upgrade.pkg` 救砖（独立于 Android，见 `usb-rescue.md`）。

| 场景 | 恢复方式 |
|---|---|
| 系统能起、OTA 刷坏 system / 黑屏但仍能进 recovery | 重新用**官方 `update_jmgo.zip`** U 盘本地升级（幂等覆盖）；或 recovery 清 `/data` 后重刷 |
| **Android 世界全损**（起不来、recovery 也进不去） | 用 **U 盘 `upgrade.pkg` 救砖**（官方救砖包，流程与前提见 `usb-rescue.md` §4/§4.6） |
| 误刷 recovery 后又想用官方 | 保留官方包即可；另外系统有 `flash_recovery` 服务会在启动时还原原厂 recovery（`ro.expect.recovery_id`） |

> 注意：只改 system（可选 boot）时，最坏情况是"Android 无法开机，但 Linux 世界救砖通道仍可用"；
> **Linux 世界本身（linux_rootfs/p10 等）与引导器（uboot/p1、preloader）才是真变砖区**，
> 见 `usb-rescue.md` §4.6 的可救范围表。
> **不要修改 `updater-script` 中对 uboot(p1)/uenv(p2)/tz(p22) 的刷写**。

## 4. 掉电/中断处理

- OTA 中断电：设备可能停在 recovery 或半刷状态；重新插 U 盘本地升级即可（block OTA 支持重刷覆盖）。
- 反复重启不动：进入 recovery（一般长按电源/复位组合键，或 `adb reboot recovery`）重刷。

## 5. adb 常用命令（userdebug 出厂即 root）

```bash
adb devices
adb root                      # userdebug 可直接 root
adb shell                     # root shell
adb reboot recovery           # 直接进 recovery（可跑本地升级流程）
adb pull /cache/recovery/last_log .   # OTA 日志（排错必备）
adb logcat -b all > all.log
```

设备内自带 `/xbin/su`（setuid）+ busybox，无需第三方 root 方案。

## 6. 危险操作清单（勿做）

- 禁止：用 `dd` 写 p1/p2/p22/p10 等底层分区；
- 禁止：修改 `updater-script` 增删固件分区写入；
- 禁止：在非官方 recovery 中乱格式化 `misc`（会丢 OTA 状态）；
- 禁止：签名/打包未自检就上机（务必 `verify_ota.py`）。
