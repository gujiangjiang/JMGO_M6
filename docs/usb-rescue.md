# U 盘救砖机制分析（`upgrade.pkg`）

> 现象：把官方 `.pkg` 格式刷机包放 U 盘，开机后设备自动进入刷机流程（救砖可用）。
> 本文给出该机制的组件级分析（基于对本次固件全部相关固件件的逆向结论）。
> 结论均可复现：见 §7 命令。

## 1. 结论速览

- 这台设备是 MTK（MStar 血统，MT5863/C30 平台）**"双世界"（two-worlds）架构**：
  SoC 上同时存在 **Linux 世界**（`linux_rootfs` + `basic` 中间件）与 **Android 世界**。
  `ro.mtk.twoworlds=1`（build.prop）即为此特性；开机时 Linux 世界先起、常驻，
  Android 作为"前台世界"运行。
- **U 盘整机刷机（救砖）由 Linux 世界里的中间件升级器完成**——即
  `libmtkapp.so` 中的 **NAV_UPDATER** 模块（MTK Navigator 平台的软件升级组件）：
  开机/插 U 盘时在 U 盘根寻找 **`upgrade.pkg`** → 完整性校验 → 按分区表擦写
  eMMC →（可选）切换 bank → 重启。
- 分区名表与包文件名由内核驱动 `basic/dtv_driver.ko`（`DRVCUST_InitQueryUptr`）
  提供的平台查询表给出；bootloader 镜像 `uboot.bin`（p1）内也含同一组
  `mt5863_m1v1_cn` / `upgrade.pkg` 字符串（用于启动阶段的识别）。
- 之所以"刷不死"：**升级器运行在 Linux 世界，而不是被刷写的 Android 系统里**；
  即使 Android 分区（boot/system/data）刷坏，Linux 世界仍能启动并再次执行 U 盘升级；
  且升级器支持 MTK 双 bank/SVL-TSL 机制（写非当前 bank + 切换）。

## 2. 参与组件与职责

| 组件 | 位置 | 职责 |
|---|---|---|
| Boot ROM + `uboot.bin`(p1) | eMMC 引导区 | MSDCBOOT/Preloader 引导；镜像内含 `mt5863_m1v1_cn`、`upgrade.pkg` 字符串 |
| Linux 世界 | p10 `linux_rootfs`(squashfs) + `basic`(p13/中间件) | 常驻后台世界；提供升级器、TV 中间件、驱动 |
| `basic/dtv_driver.ko` | Linux 世界内核模块 | 平台查询表（板型/包名/分区名）、eMMC 读写擦 API（`MSDCPART_*`、`Loader_*`） |
| `basic/libmtkapp.so` → NAV_UPDATER | Linux 世界用户态 | 找包、校验、擦写、双 bank 切换、重启；自带 UI |
| `basic/libusb_detect.so`(udetect) | Linux 世界 | USB 插拔/挂载检测（挂载点 `/dev/shm/shm_tmp/mnt/usb/...`） |
| `/etc/rc.local` + `/etc/checker.sh` | Linux 世界 | **应用级**升级（tgz 包）入口，`file_upgrading` 内核参数触发 |
| `com.jmgo.update` + `update_jmgo.zip` | Android 世界 | Android 世界自身的 OTA（见 docs/ota-signature.md），与 pkg 救砖是两套体系 |

## 3. 流程（开机救砖）

```
上电
 └─ SoC BootROM → MSDCBOOT/Preloader → u-boot(p1)
      └─ 启动 Linux 世界（linux_rootfs squashfs + basic 中间件）
           └─ NAV_UPDATER(usb 触发) / rc.local
                ├─ udetect 挂载 U 盘
                ├─ 在 U 盘查找 upgrade.pkg
                ├─ nav_updater_integrity_chk / c_dlm_integrity_check（完整性校验）
                ├─ 按 dtv_driver 分区表逐分区 erase/write（c_fm_erase_flash_part + MSDC 写入）
                ├─ _nav_updater_toggle_dual_bank / c_pcl_toggle_dual_bank_by_mask（双 bank 切换）
                └─ nav_updater_start_reboot_upgrade → "reboot system to upgrade firmware"
                     └─ 重启进入新固件（Android 世界）
```

升级器还有自己的屏上 UI（`_nav_updater_view_*`：进度条/确认列表），
即用户看到的"升级中"画面来自 **Linux 世界**，而不是 Android 的 recovery。

## 4. 证据链

### 4.1 `basic/libmtkapp.so`（NAV_UPDATER 模块）

符号（节选自符号表/字符串）：

```
nav_updater_trigger                 # 触发升级：trigger usb|internet|broadcase|uli|oad
nav_updater_download_firmware_and_trigger_update
nav_updater_start_reboot_upgrade    # -> "<NAV_UPDATER> reboot system to upgrade firmware"
nav_updater_integrity_chk
c_dlm_integrity_check               # "check %s integrity success/failed!"
c_fm_erase_flash_part               # 通过文件管理/驱动层擦除指定分区
_nav_updater_toggle_dual_bank
c_pcl_toggle_dual_bank_by_mask      # MTK 双 bank 切换
a_cfg_set_svl_tsl_dual_bank / a_cfg_get_svl_tsl_dual_bank   # SVL/TSL 版本表
a_util_dual_bank_{svl,tsl,nwl,lol,satl}_fs_store
a_mtktvapi_oad_start_flash          # OAD（空中升级）入口
nav_updater_oad_set_fw_path         # /3rd_rw/upgrade
nav_updater_usb_init / _nav_updater_usb_get_fw / _nav_updater_usb_rmv_dev_nfy_handler
```

关键字符串/路径：

```
upgrade.pkg                                     # 包名
/upgrade/upgrade.pkg                            # 默认固件路径
/3rd_rw/upgrade/upgrade.pkg                     # 中间件侧默认存放位置
http://192.168.1.1/upgrade.pkg                  # LAN 下载源（dl_svr）
trigger usb|internet|broadcase|uli|oad          # 触发来源枚举（usb=U 盘）
sign kern root sysm chrm                        # 包内组件 tag（OAD 包结构线索）
<NAV_UPDATER> Updater is running!! / recording!! / nav updater active!
pause menu
```

### 4.2 `basic/dtv_driver.ko`（平台表 + 刷写 API）

- 非剥离（`not stripped`）aarch64 模块；`DRVCUST_InitQueryUptr` 注册查询表，
  对 `.rela.text` 取样可看到以下字符串**全部**被该函数引用：

```
mt5863_m1v1_cn        (项目代号)
upgrade.pkg / upgrade / .pkg
uboot uboot_env misc recovery boot system userdata cache dmverity
linux_rootfs basic perm 3rd_ro 3rd_rw reserved channelA channelB
pq aq logo bootlogo tzbp adsp part_23 part_24 part_26..part_31
```

  见 `artifacts/rescue/partition-table.txt`（完整表）。
- 刷写 API：`MSDCPART_Read/Write/Write64/Erase/ShowPartition`、`Loader_*Msdc*`、
  `STORG_SyncMsdcRead`、`MSDC_EEPROM_*`。用户态升级器通过 ioctl 调这些接口完成分区写。

### 4.3 bootloader（p1 `uboot.bin`）

- 版本/平台：`2011.12`（u-boot）、`MTK/DTV/ROMCODE/MSDCBOOT/V01.00`、
  `EMMC boot`、`Hotboot preloader error !`、`Return back to preloader`（与 MCU T8032 握手）。
- 同一组标识字符串：`5863DTMBC_VDRTuner_P_atdeu`、`mt5863_m1v1_cn`、
  `upgrade.pkg`、`upgrade` —— 启动阶段即可识别救砖包名。
- u-boot 内**没有**常规 USB 命令字符串（`usb/fat/mass` 均无），结合双世界架构，
  实际找包/刷写动作发生在 Linux 世界（§4.1），bootloader 侧仅保留标识/兜底路径。

### 4.4 应用级升级（非整机）

- `/etc/init.d/rcS` → `/etc/rc.d/rc.local`：**仅当内核命令行含 `file_upgrading`** 时，
  挂载 `perm`/`3rd` 分区并执行 `/etc/checker.sh`。
- `/etc/checker.sh`：在本地 `/fw_upgrade/` 或 U 盘挂载点查找 `download.xml` +
  `*.tgz`（应用包），按 XML 解包到目标目录、更新 `version.xml`，随后
  `insmod /basic/dtv_driver.ko; reboot -f`。这是"应用/资源包"级别的 U 盘升级。

## 5. 为什么能救砖 / 风险点

- 升级链路（引导 → Linux 世界 → 升级器 → 驱动）**与被刷写的 Android 系统相互独立**；
  Android 分区全损也能执行。
- 升级器实现 MTK 双 bank/SVL-TSL（写另一份 + 切换）：即使更新后的固件起不来，
  仍可用 U 盘再次进入 Linux 世界重刷（相当于自带"恢复模式"）。
- 风险：整机刷机过程中**断电**可能导致被写分区（含 `linux_rootfs`/`uboot`）处于中间状态；
  但只要 bootloader（p1）未被写坏，仍可用 U 盘包再次恢复；若 bootloader 也写坏，
  则需走 SoC 级下载模式（T8032/USB 烧录工具），本仓库未覆盖。

## 6. 尚未证实（后续工作）

- `upgrade.pkg` 的**容器二进制格式**：已逆向出魔数与结构框架，见
  [`pkg-format.md`](pkg-format.md)（`#DH@FiRm` 头 / `iMtK` 追加段 / 分 tag 组件 /
  digest-HMAC-RSA 校验链）；字段级偏移仍需一份真实 pkg 样本对照。
- "开机即自动刷"是**自动触发**还是**屏上确认**（升级器含确认 UI 代码
  `_nav_updater_lb_confirm_proc_fct`，且字符串有 `pause menu`）。
- 双 bank 的物理布局（分区表未见 `*_a/*_b`，bank 概念可能只作用于 SVL/TSL 版本表与
  引导指针，未展开验证）。

## 7. 复现命令

```bash
# 1) 解包 Linux 世界（纯 Python，含 LZO；见 tools/squashfs_lzo.py 头注释的变体说明）
mkdir -p work/rootfs_x
python3 tools/squashfs_lzo.py extract work/extracted/rootfs.bin work/rootfs_x

# 2) 关键证据
grep -rl "upgrade.pkg" work/rootfs_x            # -> basic/dtv_driver.ko, basic/libmtkapp.so
strings -a work/rootfs_x/basic/libmtkapp.so | grep -A2 -B2 "NAV_UPDATER"
strings -a work/rootfs_x/basic/dtv_driver.ko | grep -A40 "mt5863_m1v1_cn"
cat work/rootfs_x/etc/checker.sh                # 应用级升级脚本
grep -n "file_upgrading" work/rootfs_x/etc/rc.d/rc.local

# 3) bootloader 侧标识
strings -n 3 -a work/extracted/uboot.bin | grep -iE "upgrade|mt5863"
```
