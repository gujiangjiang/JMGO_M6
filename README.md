# JMGO M6 (坚果 M6) 固件分析与定制

对官方最后版本固件 `update_jmgo.zip`（Android 7.0 / MTK mt5863，构建号 1.0.53，2019-06-10）的完整分析、解包/重打包工具链与定制记录。

> 本项目所有操作均针对**自有设备**的官方固件进行个人研究与备份，请遵守当地法律与厂商条款。

## 设备速览

| 项目 | 值 |
|---|---|
| 型号 | JmGO_M6（坚果 M6 投影仪） |
| 平台 | MediaTek MT5863（aarch64, Cortex-A53） |
| 系统 | Android 7.0 (NRD90M)，`JmGO_mt5863_64_n` |
| 构建 | `1.0.53`，编译于 2019-06-10，**userdebug + test-keys** |
| 固件包 | `update_jmgo.zip`（556 MB，Md5 `4800aa07821c285423fcba24bbe5c8e8`） |
| 签名 | AOSP 公开测试密钥（testkey，serial `936eacbe07f201df`）→ **可自行重签** |
| Root | 自带 `/xbin/su`（setuid）+ busybox + `ro.debuggable=1`（adb root 可用） |
| 防刷校验 | 无 dm-verity、无 AVB（system/boot 可自由修改） |

## 仓库结构

```
.
├── README.md               # 本文件
├── AGENTS.md               # AI/协作者环境约定与注意事项
├── docs/
│   ├── device.md           # 设备树、分区表、挂载与固件组成
│   ├── unpack.md           # 固件解包（OTA → 系统镜像 → 文件）
│   ├── repack.md           # 修改后重打包（镜像 → OTA → 签名）
│   ├── ota-signature.md    # OTA 格式与 SignApk 整包签名逆向
│   ├── flash.md            # 官方/本地刷机流程与安全注意事项
│   ├── apps.md             # 系统/预装应用清单
│   ├── install-limit.md    # 第三方应用安装数量限制的分析与修复
│   ├── usb-rescue.md       # U 盘救砖（upgrade.pkg）机制分析
│   └── pkg-format.md       # upgrade.pkg 包格式逆向（MTK DLM 容器）
├── tools/                  # 已验证的 Python 工具链（详见 docs）
├── artifacts/              # 小体积原始件：prop、fstab、证书、清单、校验值
└── apks/                   # JMGO 官方 APK 备份（用于解包/修改溯源）
```

## 快速开始

```bash
# 1) 解包官方包 → 得到 system.img（raw ext4）
python3 tools/sdat2img.py extracted/system.transfer.list extracted/system.new.dat system.img

# 2) 浏览/提取镜像内文件（免挂载，macOS 可用）
python3 tools/ext4.py system.img ls /app
python3 tools/ext4.py system.img get /build.prop build.prop

# 3) 修改镜像后（需 Linux + e2fsprogs）重打包并重签
python3 tools/repack_ota.py update_jmgo.zip system_mod.img update_jmgo_mod.zip testkey.pk8 artifacts/certs/testkey.x509.pem

# 4) 校验（等同于设备端 recovery 的验签逻辑）
python3 tools/verify_ota.py update_jmgo_mod.zip artifacts/certs/testkey.x509.pem
```

## 关键结论（TL;DR）

- 官方包中 `system.new.dat` 实际是**完整 raw ext4 镜像**（截掉尾部零块），`system.transfer.list` 为 **v4 格式**，语义已完全逆向并在本仓库工具中复现（重建镜像与原图逐字节一致）。
- 整包为 **SignApk whole-file 签名**，密钥为 **AOSP testkey（公开）**：`/system/etc/security/otacerts.zip` 即 `testkey.x509.pem`。只要拿到公开的 `testkey.pk8` 即可重签；recovery 验签先于写入，验签失败不会写盘。
- 只修改 system（可选 boot）时几乎无变砖风险；**不要碰 uboot/uenv/tz 等底层分区**（保留原 `updater-script` 即可）。
- 桌面 `com.jmgo.launcher`（JmGoOS）为 Cocos2d-x + LuaJIT 应用，设置项内置其中，由 `/etc/jmgodefault.dat` 指定为默认 launcher（无标准 HOME intent）。
- 预装应用位于 `/system/preinstall`（乐播投屏、WPS、优酷 CIBN），首次开机由 `com.jmgo.update` 静默安装。
- 自定义应用安装约 10 个后提示"应用过多"：限制在自研 `PackageInstaller`（Holatek）的 `canInstallApp()`（SharedPreferences `apps/list`，阈值 10，adb 绕过）。
  已给出免刷机绕过与 4 字节 odex 补丁，见 [安装数量限制分析与修复](docs/install-limit.md)。
- U 盘 `.pkg` 救砖：设备为 MTK"双世界"架构，Linux 世界（`linux_rootfs`+`basic` 中间件）中的
  NAV_UPDATER 开机扫描 U 盘 `upgrade.pkg`，校验后按分区表擦写并重启；与被刷的 Android 相互独立，故可救砖。
  见 [U 盘救砖机制分析](docs/usb-rescue.md)；包格式（magic `#DH@FiRm`、`iMtK` 追加段、
  分 tag 组件、digest/HMAC/RSA 校验链）见 [upgrade.pkg 包格式](docs/pkg-format.md)。

## 文档索引

- [设备与分区](docs/device.md)
- [解包指南](docs/unpack.md)
- [重打包指南](docs/repack.md)
- [OTA 格式与签名](docs/ota-signature.md)
- [刷机指南](docs/flash.md)
- [内置应用清单](docs/apps.md)
- [安装数量限制分析与修复](docs/install-limit.md)（含补丁工具与刷机步骤）
- [U 盘救砖机制分析（upgrade.pkg）](docs/usb-rescue.md)
- [upgrade.pkg 包格式逆向（MTK DLM 容器）](docs/pkg-format.md)

## 免责声明

刷机有风险，修改固件前请确认已备份原厂固件并理解恢复手段。本仓库内容仅供学习研究，不对任何设备损坏负责。
