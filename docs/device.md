# 设备与分区（JmGO M6 / mt5863）

## 1. 设备基础信息

来自 `artifacts/build.prop`（提取自 `/system/build.prop`）：

| 属性 | 值 |
|---|---|
| `ro.product.model` | `JmGO_M6` |
| `ro.build.fingerprint` | `Android/JmGO_mt5863_64_n/JmGO_mt5863_64_n:7.0/NRD90M/1.0.53:userdebug/test-keys` |
| `ro.build.version.incremental` | `1.0.53` |
| `ro.build.date.utc` | `1560158187`（2019-06-10 17:16:27 CST） |
| `ro.build.type` | `userdebug`（`ro.debuggable=1`，adb root 可用） |
| `ro.product.board/platform` | `JmGO_mt5863_64_n` / `mt5863` |
| `ro.product.cpu.abilist` | `arm64-v8a,armeabi-v7a,armeabi` |
| `ro.sf.lcd_density` | `80`（投影 1080p 输出，`sys.display-size=3840x2160`） |
| `ro.adb.secure` / `service.adb.tcp.port` | `1` / `5555` |
| `ro.gms.installed` | `0`（无 GMS） |
| `sys.jmgo_product` | `JmGO_M6` |
| `ro.expect.recovery_id` | `0x4c3e4796e6d4fdd777e222853bd5a0af7cabb015...`（recovery 还原机制相关） |

`ramdisk/default.prop` 关键项（见 `artifacts/default.prop`）：

- `ro.debuggable=1`、`ro.secure=1`、`persist.sys.usb.config=adb`
- 系统内自带 `/xbin/su`（权限 `04750`，setuid root）与 `/bin/busybox` → **无需 Magisk 即可 root**

## 2. 分区表与挂载（依据 ramdisk/fstab.mt5863 + updater-script 实测）

| 分区节点 | 挂载点/用途 | 文件系统 | 备注 |
|---|---|---|---|
| p1 | uboot | raw | **bootloader，慎动** |
| p2 | uenv | raw (ELF) | u-boot 环境变量，**慎动** |
| p3 | /misc | emmc | recovery 命令区（OTA 命令写入处） |
| p4 | /recovery | emmc(raw) | AOSP recovery 镜像；系统启动有 `flash_recovery` 服务可还原 |
| p5 | /boot | Android boot.img | MTK 格式，含 kernel 3.18.25 uImage + ramdisk |
| p6 | /system | **ext4** | 本仓库主要修改对象；无 dm-verity |
| p7 | /data | ext4 | 用户数据 |
| p8 | /cache | ext4 | OTA 中转（`/cache/ota_update.zip`） |
| p10 | /linux_rootfs | **squashfs(LZO, MTK 变体)** | `rootfs.bin` 50 MiB（52,428,800 B，分区 0x3200000）；Linux 世界（U 盘救砖升级器所在），可用 `tools/squashfs_lzo.py` 解包 |
| p13 | /3rd | squashfs | 预置三方资源 |
| p14 | /3rd_rw | ext4 | 三方资源可写区 |
| p18 | pq | raw | `pq.bin` 画质参数表（头 `PQEX`） |
| p19 | AQ | raw | `AQ.bin`（头 `MTK_AQ_V.001`） |
| p22 | tz | raw | `tz.bin.lzhs` TrustZone（lzhs 压缩），**慎动** |
| p23 | adsp | raw | `adsp.bin` 音频 DSP 固件 |

> `updater-script` 中 system 的刷写目标为 `/dev/block/mmcblk0p6`，block_image_update 覆盖区间 [0, 320000) 块 —— 即 **p6 大小 = 320000 × 4096 = 1,310,720,000 字节（约 1.22 GiB）**。

## 3. system 分区镜像结构（实测）

- `system.new.dat` 解出后为 **raw ext4**：
  - 超级块：`s_inodes_count=78880`，`s_blocks_count=315000`（1.29 GB，即文件系统本身小于分区尾部）
  - `s_log_block_size=2`（4096 字节块）、`s_blocks_per_group=32768`、`s_inode_size` 见 `ext4.py` 解析
  - 卷标 `system`
  - 空闲空间约 326 MB（扫描 zero run 统计：79680 块）
- **无 dm-verity**：fstab 中 `/system` 挂载项无 `verify` 标志
- **boot.img 无 AVB**：无 `AVB0/AVBf` 魔数，无 vbmeta 分区

## 4. boot 镜像结构（MTK）

```
magic      ANDROID!
kernel     uImage (gzip, Linux 3.18.25), size 0x470c40, load 0x10008000
ramdisk    gzip cpio, size 0x6935c4, load 0x11000000
page_size  2048 （MTK 特有）
dt_size    0 （dtb 在 rootfs/内核内，未单独打包）
```

ramdisk 为完整 root：`init.rc`、`fstab.mt5863`、`init.mt5863.rc`、`dtv_driver.ko`、`sbin/adbd` 等。

## 5. 固件包组成（update_jmgo.zip）

| 文件 | 目标 | 说明 |
|---|---|---|
| `system.new.dat` + `system.transfer.list` | p6 | block OTA v4 数据（实为 raw ext4） |
| `system.patch.dat` | - | 空文件（整包升级无增量补丁） |
| `boot.img` | p5 | MTK boot（kernel+ramdisk） |
| `uboot.bin` | p1 | bootloader |
| `uenv.bin` | p2 | u-boot 环境 |
| `tz.bin.lzhs` | p22 | TrustZone |
| `AQ.bin` / `pq.bin` | p19 / p18 | 音频/画质参数 |
| `rootfs.bin` | p10 | linux_rootfs（squashfs-lzo，含 directfb 等） |
| `adsp.bin` | p23 | 音频 DSP 固件 |
| `META-INF/` | - | `updater-script`、`update-binary`(aarch64静态, AOSP O 版 updater)、`metadata`、整包签名 |

## 6. 官方升级流程（从 `com.jmgo.update` dex 逆向）

1. 桌面「设置 → 关于 → 系统升级 → 本地升级」由 `com.jmgo.update`（system uid）处理；
2. 将 U 盘中的 `update_jmgo.zip` 复制到 `/cache/ota_update.zip`（dex 字符串：`MSG_COPY_LOCAL_UPDATEZIP_*`）；
3. 写 `/cache/recovery/command`：`--update_package=/cache/ota_update.zip`，并调用 `RecoverySystem` 重启入 recovery；
4. recovery（AOSP）验签整包（testkey）→ 执行 `updater-script`（E3003 时间戳断言 / E3004 机型断言）→ 逐分区写入 → 自动重启。

> 相关字符串证据：`/cache/ota_update.zip`、`local update zip recovery check fail !`、`JmGO_M6_INT reboot recovery fail`（`apks/JmGOUpdate.apk` dex）。
