# `upgrade.pkg` 包格式逆向（MTK DLM 容器）

> 本文是对 U 盘救砖包 `upgrade.pkg` 二进制格式的逆向结论，
> 基于 Linux 世界里的 **DLM（Download Manager）引擎**实现：
> - `basic/libdtv_common.so` —— DLM 真身（`x_dlm_*` + `dlm_*`，含 AES/RSA/digest）
> - `basic/libmtkapp.so` —— NAV_UPDATER 调用方（`a_fw_updater_*`/`c_dlm_*` 封装 + 标签表）
> - `basic/libapp_if.so` —— 薄封装（`c_dlm_*` → `x_dlm_*`）
> - `basic/dtv_driver.ko` —— 分区名表 + eMMC 读写（写入落地）
>
> 组件调用链与刷机流程见 [`usb-rescue.md`](usb-rescue.md)。

## 1. 结论速览

`upgrade.pkg` = **MTK DLM "download file"**（同一套引擎也用于 LAN/OAD 升级）。
结构是**带魔数的头 + 追加段 + 镜像表 + 分标签（tag）组件**，带整链校验（digest/HMAC/RSA 签名，可选 AES 加密）。

```
+------------------------------+
| File/DL Header (112 B)       |  magic "#DH@FiRm" @+0x04, 冗余校验 @+0x44
|  模型名 / 版本 / UID / 尺寸 / |  可 AES 加密（"Header encrypt by AES"）
|  摘要 / 签名                  |
+------------------------------+
| Append 段(可多个)             |  magic "iMtK"（4B）+ 长度 + 摘要
|                              |  "extra append data length +8 must align with 16"
+------------------------------+
| Codefile / 镜像表             |  dlm_locate_codefile_header() 定位，
|  tag 列表 + 组件条目           |  HMAC 校验（"Validate codefile HMAC"）
+------------------------------+
| 组件数据（按 tag）:            |
|   sign  kern  root  sysm ...  |  4 字符 tag；"UID = %c%c%c%c"
+------------------------------+
```

## 2. 魔数与关键常量（实测）

| 位置/字段 | 值 | 证据 |
|---|---|---|
| 文件头魔数 | ASCII **`#DH@FiRm`**（8 字节，@+0x04） | `dlm_locate_codefile_header`：`memcmp(buf+4, "#DH@FiRm", 8)`（0x4e050 处），失败打印 "File Header Magic check failed" |
| 头部冗余副本 | 同魔数 @ **+0x44** | 同函数第二处 `memcmp(buf+0x44, "#DH@FiRm", 8)`（0x4e124） |
| Append 魔数 | ASCII **`iMtK`**（4 字节） | `x_dlm_download_by_tag`：`memcmp(p, "iMtK", 4)`（0x502ec 处），并列打印 "extra append data length +8 must align with 16 bytes." |
| 组件 tag | `sign` `kern` `root` `sysm` `chrm` | `libmtkapp.so` 0x775958 起 8 字节步长标签表；`nav_updater` 用其枚举/删除条目 |
| 头块尺寸 | 112 (0x70) 字节（解密/转储单位） | `dlm_locate_codefile_header` 内 `dlm_aes(..., 112)` / `dlm_dump_buffer(..., 112)`；另一分支 432 (0x1B0) |

## 3. 文件头字段（已确认部分）

运行时结构体偏移（来自 `x_dlm_integrity_check` 校验代码）：

| 偏移 | 含义 | 证据 |
|---|---|---|
| +0x04 | 8 字节魔数 `#DH@FiRm` | 见上 |
| +0x0C | 指针 → 镜像描述区；**描述区 +0x00 = 4 字节 UID**，**+0x50 = 32 字节模型名** | ID 校验：`memcmp(..., [hdr+0xC], 4)` + 打印 "Validate ID "- ok/failed；模型校验：`memcmp(hdr+0x50, my+0x50, 32)` + "Validate model name pt_hdr->s_model = %s, pt_myHdr->s_model = %s" |
| +0x1C | 校验开关字节：bit 0x10 控制 ID 校验、bit 0x40 控制模型校验（置位=跳过对应校验） | 上述两处校验前的 `tst r2,#16 / #64` 分支 |
| 版本 | 字符串（对当前固件比较，只接受更新版本） | "Current firmware version - %s"/"Download firmware version - %s"/"Only accept more newer version firmware"/"my %d, downlaod %d, small = %d, upgrade = %d" |
| 尺寸字段 | 头部/附加段长度；附加段 `len+8` 需 16 字节对齐 | "Note!!!!!! extra append data length +8 must align with 16 bytes." / "Adjust extra_app_hdr_len"/"Real extra_app_hdr_len" |
| 摘要/签名 | 设备固件摘要、包摘要、HMAC、MTK RSA 签名 | "Encrypted digest:"/"Download digest"/"Validate codefile HMAC"/"New MTK Signature"/"MTK Signature matched" |

> 模型名在本机为 **`mt5863_m1v1_cn`**（与 `dtv_driver.ko` DRVCUST 表、u-boot 镜像中的项目代号一致，见 `artifacts/rescue/partition-table.txt`）。

## 4. 组件（tag）与写入

- NAV_UPDATER 侧 tag 表（`libmtkapp.so` @0x775958）：
  `sign`（签名块）、`kern`（内核）、`root`（rootfs）、`sysm`、`chrm`。
- DLM 提供**按 tag 下载/刷写**：`x_dlm_download_by_tag`、`x_dlm_get_codefile_tag_list`
  （打印 "UID = %c%c%c%c"、"Append file length = %d"、"locate tag failed"），
  也提供整包 `x_dlm_download` / `x_dlm_download_by_division`。
- 写入落地：NAV_UPDATER 的 `cust_write_data_fn` → `updater_write_data_fn` →
  `c_fm_erase_flash_part` 擦分区 + `dtv_driver.ko` 的 `MSDCPART_Write*` 写入；
  分区名全集见 `artifacts/rescue/partition-table.txt`（uboot/uboot_env/misc/recovery/boot/system/userdata/cache/dmverity/linux_rootfs/basic/perm/3rd_ro/3rd_rw/…/part_31）。

## 5. 完整性与加密链（DLM 实现）

| 环节 | 符号/字符串 | 说明 |
|---|---|---|
| 摘要 | `dlm_digest_init/update/final`、`[DLM]Append digest:`、`digest check %c%c%c%c percentage = %d` | 逐段摘要，按 4 字符 tag 汇报进度 |
| HMAC | "Validate codefile HMAC" | 镜像表带 HMAC |
| RSA 签名 | "New MTK Signature"/"MTK Signature matched/not matched"；`Rsa4Key`、`x_dlm_set_rsa_public_key`、`rsa_use_customer_key` | 校验方使用运行时注入的公钥（无 PEM 硬编码，见 §7） |
| AES | `dlm_aes_encrypt/decrypt`、`x_dlm_set_aes_key_iv`、`dlm_encrypt_efuse/decrypt_efuse`、`puc_customer_aes_key/iv`、`aes_use_customer_key` | 头部/数据可选加密；密钥来自 efuse 或客户密钥区 |
| 断点/CRC | "FileIO CRC value read from filesystem [%x]"/"save to Filesystem" | 下载状态 CRC 存文件系统 |

## 6. 与救砖流程的衔接

1. 开机后 NAV_UPDATER 以 `usb` 触发 → `_nav_updater_usb_get_fw` 找 `upgrade.pkg`；
2. `x_dlm_download`(或 `by_tag`) 解析头（`#DH@FiRm`）→ 校验（UID/模型/版本/摘要/签名/解密）；
3. 按 tag/分区表擦写 eMMC（可用 `sign` 校验块）；
4. `_nav_updater_toggle_dual_bank` 切换 → `nav_updater_start_reboot_upgrade` 重启。

## 7. 尚未确认 / 后续

- 文件头字段的**精确偏移表**（魔数/UID/模型名/flag 之外）：需要一份真实 `upgrade.pkg`
  样本对照验证（本文档给出解析入口：+4 魔数、+0x44 副本、`iMtK` 追加段）。
- 组件条目/镜像表（codefile header）的逐字段布局：在 `dlm_locate_codefile_header`
  与 `x_dlm_get_codefile_tag_list` 中继续跟进即可。
- **签名是否强制**：`Rsa4Key` 来自运行时注入（工厂/安全区），本固件内无公钥数据；
  即无法从本包内取得校验公钥，也就**无法凭空伪造能过签的官方式 pkg**。
  若要自制：需有 JMGO/MTK 的私钥，或确认该机型的 `rsa_use_customer_key`/校验开关
  为可关闭状态（需真机+样本实验）。

## 8. 复现命令

```bash
# 解包 Linux 世界
python3 tools/squashfs_lzo.py extract work/extracted/rootfs.bin work/rootfs_x

# 反汇编 DLM 引擎并定位关键函数
llvm-objdump -d work/rootfs_x/basic/libdtv_common.so > /tmp/libdtv.dis
grep -n "<dlm_locate_codefile_header>\|<x_dlm_integrity_check>\|<x_dlm_download_by_tag>" /tmp/libdtv.dis

# 魔数/标签字符串
strings -a work/rootfs_x/basic/libdtv_common.so | grep -E "#DH@FiRm|iMtK|Append Magic"
strings -a work/rootfs_x/basic/libmtkapp.so | grep -A2 -B2 "ICL_SET"

# 校验逻辑（模型名/ID/版本/签名打印点）
grep -n "Validate model name\|Validate ID\|MTK Signature" /tmp/libdtv.dis
```
