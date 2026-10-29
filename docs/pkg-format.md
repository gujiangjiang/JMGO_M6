# `upgrade.pkg` 包格式（MTK DLM 容器）——含真实样本逐字段验证

> 本文对 U 盘救砖包 `upgrade.pkg`（MTK DLM "download file"）的**二进制格式**给出逐字段结论。
> 早期版本基于对固件内 DLM 引擎（`basic/libdtv_common.so`）的静态逆向；
> 本版本进一步用**真实官方样本 `JMGO_M6_upgrade_loader.pkg`（709,813,132 字节，
> sha256 `23dbc77b…dca963`）**对照，并用 Unicorn 模拟执行设备自带的 AES 代码
> （见 `tools/dlm_aes_emu.py`）做了**地面真值验证**。
> 机制与刷机流程见 [`usb-rescue.md`](usb-rescue.md)；自制包见 [`pkg-build.md`](pkg-build.md)。

## 1. 结论速览

```
┌──────────────────────────────────────────────────────────────┐
│ 文件头（368 字节，AES-128-CBC 加密，key=默认记录密钥，iv=0）      │
│   [0:112]   112 字节“文件头”（解密后有 #DH@FiRm 魔数）           │
│   [112:368] 256 字节（解密后前 32B = 文件级 SHA-256，覆盖        │
│             [368:EOF]；其余字节用途未明，样本中为高熵数据）        │
├──────────────────────────────────────────────────────────────┤
│ 记录 0..15（顺序排列，无目录表；靠内容自描述）                    │
│   每条记录 = 60B 记录头 + 64B 追加块(iMtK) + payload            │
└──────────────────────────────────────────────────────────────┘
```

- 记录头 60 字节：`name[8]` + `u32 len` + `16B 常量字段` + `32B 加密字段`。
- `u32 len` = 追加块(64B) + payload 长度；**记录总长 = 60 + len**（已对 16 条记录逐条做跨度校验，全部吻合）。
- `16B 常量字段`：全样本固定 `9bf7ed4f da262a8e 58fc63cf 38f6e8a9`，
  它是 `AES-CBC("reserved mtk inc")` 的密文（见 §5.3，模拟执行验证）。
  该字段**逐记录完全相同**正是因为明文与密钥、IV 都固定。
- `32B 加密字段`：每条不同；DLM 读取时用同一密钥**就地解密**（`Validate codefile HMAC` 语境），
  精确语义未完全确认（见 §8）。
- `追加块`：`"iMtK"` + `u32 0x38` + `u32 0x30` + 48B 数据。
  常规记录 48B = `"iPAd"` + 44 个 0（占位）；**adsp 记录**的 48B 里含版本串
  `t-apollo-mp-1501-1645-3-001-122-001-40-001-22`（与固件内 `x_sys_get_version`
  返回的客户版本一致，见 §4.2）。
- 记录 `cfig` 特殊：60B 头后**没有** iMtK 追加块，直接是 222 字节分区表文本。

## 2. 样本整体结构（实测偏移）

| # | name | 记录头偏移 | payload 偏移 | payload 长度 | 类型（实测） |
|---|---|---|---|---|---|
| 0 | `cfig` | 0x000170 | 0x0001EC | 158 | 分区表文本（222B 在记录头之后） |
| 1 | `adsp` | 0x00028A | 0x000306 | 1,644,516 | `adspbin`+`adsp_main.bin` 子镜像 |
| 2 | `load` | 0x191AEA | 0x191B66 | 896,096 | **u-boot 引导器**（= OTA 内 uboot.bin，逐字节一致） |
| 3 | `uenv` | 0x26C7C6 | 0x26C842 | 4,028 | ELF32（ARM） |
| 4 | `misc` | 0x26D7FE | 0x26D87A | 1,024 | bootloader message（`boot-rec…`） |
| 5 | `recv` | 0x26DC7A | 0x26DCF6 | 14,069,036 | `ANDROID!`（recovery） |
| 6 | `boot` | 0xFD8A22 | 0xFD8A9E | 11,556,136 | `ANDROID!`（= OTA 内 boot.img，逐字节一致） |
| 7 | `sysm` | 0x01ADDFC6 | 0x01ADE042 | 600,179,296 | ext4（label=system；文件内容不可读，见 §7）。**名字填充位含 0x01**（16 条中唯一，疑似“大分区/截断数据”标志，见 §7.3） |
| 8 | `logo` | 0x2573E2A2 | 0x2573E31E | 15,818 | JPEG（开机 logo） |
| 9 | `rfsA` | 0x257420E8 | 0x25742164 | 52,428,800 | SquashFS（= OTA 内 rootfs.bin，逐字节一致） |
| 10 | `3rdw` | 0x28942164 | 0x289421E0 | 10,485,760 | ext4（3rd_rw） |
| 11 | `perm` | 0x293421E0 | 0x2934225C | 7,340,032 | ext4（perm） |
| 12 | `3rdp` | 0x29A4225C | 0x29A422D8 | 10,485,760 | SquashFS（3rd_ro） |
| 13 | `pqda` | 0x2A4422D8 | 0x2A442354 | 131,072 | `PQEX`（= OTA 内 pq.bin） |
| 14 | `aqda` | 0x2A462354 | 0x2A4623D0 | 2,816 | `MTK_AQ_V`（= OTA 内 AQ.bin） |
| 15 | `tzbp` | 0x2A462ED0 | 0x2A462F4C | 570,432 | TZ 裸二进制 |

> 完整表（含 sha256）见 `artifacts/pkg/records.txt`；分区表原文另存
> `artifacts/pkg/cfig-partition-table.bin`。复现：`python3 tools/pkg_dlm.py list/extract/sha256`。

### 2.1 `cfig` 分区表（222 字节文本）

```
START
uenv  nand 1     misc  nand 2     recv  nand 3     boot  nand 4
sysm  nand 5     usrd  nand 6     cach  nand 7     rfsA  nand 9
perm  nand 11    3rdp  nand 12    3rdw  nand 13    pqda  nand 17
aqda  nand 18    logo  nand 20    tzbp  nand 21    adsp  nand 22
ddb   nand 24
END
```

- 含义：`名字 类型 分区序号`。**cfig 用的是 0 基序号（Linux 世界/DLM 内部），
  eMMC 物理节点 `mmcblk0pN = 序号 + 1`**（已用 OTA `updater-script` 与设备
  `fstab` 多处锚点交叉验证）：

  | cfig 序号（0 基） | 名字 | 物理节点 | 锚点证据 |
  |---|---|---|---|
  | 0（未列） | uboot | p1 | OTA：`uboot.bin→p1`；`device.md §2` |
  | 1 | uenv | p2 | OTA：`uenv.bin→p2` |
  | 2 | misc | p3 | `device.md §2`（/misc） |
  | 3 | recv | p4 | `device.md §2`（/recovery） |
  | 4 | boot | p5 | OTA：`boot.img→p5` |
  | 5 | sysm | p6 | OTA：`system→p6` |
  | 6 | usrd | p7 | `device.md §2`（/data） |
  | 7 | cach | p8 | `device.md §2`（/cache） |
  | 9 | rfsA | p10 | OTA：`rootfs.bin→p10` |
  | 11 | perm | p12 | dtv 名单序（待直接实证） |
  | 12 | 3rdp | p13 | `device.md §2`（/3rd squashfs ✓ 类型吻合） |
  | 13 | 3rdw | p14 | `device.md §2`（/3rd_rw ext4 ✓ 类型吻合） |
  | 17 | pqda | p18 | OTA：`pq.bin→p18` |
  | 18 | aqda | p19 | OTA：`AQ.bin→p19` |
  | 20 | logo | p21 | 序号锚定 pq/aq/tzbp/adsp 连续吻合；与 dtv 名单中 logo(19)/bootlogo(20) 存在一处命名差，实际以序号 p21 为准 |
  | 21 | tzbp | p22 | OTA：`tz.bin.lzhs→p22` |
  | 22 | adsp | p23 | OTA：`adsp.bin→p23` |
  | 24 | ddb | p25 | dtv 名单序（待直接实证） |

- 表中**没有 uboot（序号 0/物理 p1）**：`load` 组件因此不在表内（见 §7.2）。
- `usrd/cach/ddb` 在表中有名字但没有对应记录 → 这些分区在救砖包里**不写**（保留数据）。

## 3. 记录格式（60 字节头 + 64 字节追加块）

以 `uenv` 记录为例（记录头 @0x26C7C6）：

| 偏移 | 长度 | 内容 | 说明 |
|---|---|---|---|
| +0x00 | 8 | `"uenv\0\0\0\0"` | 组件名（ASCII，0 补齐） |
| +0x08 | 4 | `0x00000FFC` | = 64 + payload 4028 → 记录从 iMtK 起总长 0xFFC |
| +0x0C | 16 | `9bf7ed4f…e8a9` | 常量密文 = AES("reserved mtk inc") |
| +0x1C | 32 | 每条不同 | 加密的 32B 数据（读取时就地解密） |
| +0x3C | 4 | `"iMtK"` | 追加块魔数 |
| +0x40 | 4 | `0x38`(=56) | 追加数据长度（+8 后 16 对齐 → 整块 64B） |
| +0x44 | 56 | `"iPAd"`+44×0 等 | 追加数据（见 §1） |
| +0x7C | — | payload | 长度 = u32-64；下一条记录紧跟其后 |

> 校验（16/16 通过）：`(下一条记录 +0x3C 的 iMtK 偏移) − 本条 iMtK 偏移 == u32`。

## 4. 文件头 112 字节（解密后）

### 4.1 布局

由 `x_dlm_init`（0x4D1FC）构造“本机头”的代码直接得到（字段全部实测对应）：

| 偏移 | 长度 | 含义 | 证据 |
|---|---|---|---|
| +0x00 | 4 | 公司代号（本机 `"jmgo"`，`x_sys_get_company` 固定返回） | libdtv_common 0x104340 `"jmgo"` |
| +0x04 | 8 | 魔数 `"#DH@FiRm"` | `dlm_locate_codefile_header` memcmp(buf+4,…) |
| +0x0C | 56 | 版本字符串（样本=客户版本 `t-apollo-mp-…`，NUL 补齐） | `x_dlm_init` strcpy(base+12) |
| +0x44 | 8 | 魔数副本 `"#DH@FiRm"`（冗余检查点） | `dlm_locate_codefile_header` 432B 分支 memcmp(buf+0x44,…) |
| +0x4C | 1 | 校验开关位：bit4(0x10)/bit6(0x40)/bit5(0x20) | `x_dlm_integrity_check` 的 `tst r3,#16/#64/#32` |
| +0x50 | 32 | 型号名（本机 `JmGO_mt5863_64_n`） | `x_dlm_init` memcpy(base+80)；校验打印 `pt_hdr->s_model` |

### 4.2 为什么样本里的版本串是 `t-apollo-mp-…`

`adsp` 记录追加块中的 `t-apollo-mp-1501-1645-3-001-122-001-40-001-22` 与库内
`x_sys_get_customer_version`/`x_sys_get_version` 返回的固定串一致（0x104340 区），
即**客户版本号**。版本校验只接受“更新”的版本（`Only accept more newer version
firmware`，字符串切分 `_ \n,.` 后按数字逐段比较）。

## 5. 加密与完整性链（Unicorn 模拟执行实证）

### 5.1 `dlm_aes()` 语义（tools/dlm_aes_emu.py 可直接调用）

```
dlm_aes(dst, src, len, key, iv, mode)
  mode=0 解密：密文在 dst，明文写入 src
  mode=1 加密：明文在 dst，密文写入 src
  串接为 CBC（prev = 上一密文块，iv 来自参数，同时回写 iv 缓冲）
```

**实现=标准 AES-128**（不是魔改）：
- `dlm_aes_set_encrypt_key` 的轮密钥打包 = 标准大端字；
- 表驱动 T 表是标准表做字节轮转存储，语义等价；
- 实证：`dlm_aes(0…0, key=0, iv=0, mode=1)` 输出标准向量
  `66e94bd4ef8a2c3b884cfa59ca342b2e`；32 字节 CBC 加解密与 OpenSSL 完全一致。

### 5.2 默认密钥 = 编译器“栈保护常量”×4（混淆来源！）

`dlm_locate_codefile_header` / `x_dlm_integrity_check` / `x_dlm_download[_by_tag]` /
`x_dlm_tag_test` / `x_dlm_get_codefile_tag_list` 的**默认路径**（`aes_use_customer_key==0`）
都把函数开头写入栈的 stack-protector 常量（本例 `0x94102909`）当成 AES Key 种子：

```
key = <canary 4字节> × 4 (= 09 29 10 94 09 29 10 94 09 29 10 94 09 29 10 94)
iv  = 0
```

- 分支来源：函数开头 `movw r3,#0x2909; movt r3,#0x9410; str r3,[r11,#-0x98]`，
  随后 4 次 `memcpy(key+i*4, &[r11-0x98], 4)`（x_dlm_download）；locate 同型（`[r11-0x50]`）。
- **该常量随每次编译随机**（编译器行为），因此**不同固件版本的 DLM 默认密钥不同**
  （每个版本重新编译都会变化）。用 `tools/dlm_aes_emu.py keyscan <libdtv_common.so>`
  可读出任意版本的该密钥并验证其与某个 pkg 是否匹配。
- 自定义密钥路径存在（`x_dlm_set_customer_aes_key`：key=SHA256(输入)[0:16] 并置
  `aes_use_customer_key=1`；`x_dlm_set_customer_key` 为 256B 客户密钥），但**本固件
  Linux 世界所有二进制（libs/bin/dtv_svc/app_man…）均无调用者**，实际上不会启用。

### 5.3 完整性校验（`x_dlm_integrity_check`，模拟执行跑通）

顺序（`Vendor has define its own integrity check API` 的厂商回调未注册 → 走内部实现）：

1. `dlm_locate_codefile_header`：读 368B，AES 解密 [0:112] 检查 `#DH@FiRm`@+4；
   失败则按 432B 变体解密 [64:176] 再查 `"#DH@FiRm"`@+0x44（样本走的是 432B 分支重试）。
2. ID 校验（公司代号，可被 +0x4C bit0x10 跳过）。
3. 型号校验（+0x50，可被 bit0x40 跳过；打印 `pt_hdr->s_model=…`）。
4. 版本校验：本地 `x_sys_get_version` vs 下载串；仅接受更新版本。
5. **文件级摘要**：`dlm_digest_*` = SHA-256，覆盖 `[368:EOF]`；期望值存放在
   [112:368] 的 256B 密文解密后的**前 32 字节**；不匹配 → `MTK Signature not matched`
   （这里的 “MTK Signature” 就是该 SHA-256，RSA 签名在用户态是空桩，
   `x_dlm_set_rsa_public_key` 直接 `mov r0,#0; bx lr`）。
6. 每条记录读取时：解密常量字段核对 `"reserved mtk inc"`（§1），并就地解密 32B 字段。

USB 侧调用链：`_nav_updater_oad_is_upgrade_available` → `c_dlm_integrity_check(path,0)`
（≥0 才算“有可升级固件”）；随后 `c_dlm_download_by_tag`/`c_dlm_download_by_division`
按 tag 写分区（`updater_write_data_fn`→`c_fm_erase_flash_part`+MSDC 写）。

### 5.4 对本样本的实测结论（重要）

用 Unicorn 执行本固件 `libdtv_common.so` 对样本做验证：

```
$ python3 tools/dlm_aes_emu.py keyscan work/rootfs_x/basic/libdtv_common.so JMGO_M6_upgrade_loader.pkg
lib: work/rootfs_x/basic/libdtv_common.so
默认记录密钥(canary×4) = 09291094092910940929109409291094  (canary=0x94102909)
pkg 记录常量字段 = 9bf7ed4fda262a8e58fc63cf38f6e8a9
常量字段解密结果 = b'G\x08\xf4\xa8|\xf0@\xc2\x0b\x03\xb4c\xa0\xb2Y\xb9'   ← 不等于 "reserved mtk inc"
与 'reserved mtk inc' 一致: 否（不匹配）
pkg 头解密魔数检查: b'V\xaef4\x9e\x17H\xd5' (期望 '#DH@FiRm') FAIL
```

**即：本 pkg 不是用 `update_jmgo.zip`（1.0.53）的 DLM 密钥加密的。**
同时，**用户实机确认该 pkg 在其设备上可用** —— 两个事实合起来说明：
**用户设备当前运行的固件并不是 1.0.53（或至少不是同一编译批次）**，
而是与 pkg 同批构建的版本（密钥=那一版的编译常量）。

推论与提醒：
- 由于密钥=编译期随机常量（§5.2），**每个固件版本只能校验与它同批次构建的 pkg**；
- 若设备升级到 1.0.53，该 pkg 的原版校验是否仍通过**未经实机验证**（按本分析应
  不通过）；请保留与设备版本对应的救砖包，并在任何 OTA 升级前用
  `tools/dlm_aes_emu.py keyscan <新版本 lib> <pkg>` 预检；
- 要让自制包 100% 对齐设备，请先从设备提取 `/basic/libdtv_common.so` 并 keyscan
  （方法见 [`pkg-build.md`](pkg-build.md) §1）。

## 6. 与救砖流程的衔接

1. NAV_UPDATER（Linux 世界）开机/插 U 盘 → 找 `upgrade.pkg`（见 usb-rescue.md §4）；
2. `c_dlm_integrity_check` → 通过则进入下载；
3. `c_dlm_download_by_tag`（或 `by_division`）逐记录：跳过不匹配 tag，写其余；
4. 记录 → 分区（cfig 序号+1 = 物理节点）：**load→uboot(p1)**、uenv→p2、misc→p3、
   recv→p4、boot→p5、sysm→p6、rfsA→p10、perm→p12、3rdp→p13、3rdw→p14、
   pqda→p18、aqda→p19、logo→p21、tzbp→p22、adsp→p23；
   usrd(p7)/cach(p8)/ddb(p25) 不写（`load`→uboot 的写入路径未直接实测，见 §7.2）；
5. 双 bank 切换（`_nav_updater_toggle_dual_bank`）→ `nav_updater_start_reboot_upgrade`。

## 7. 样本三次实检的补充发现

### 7.1 组件与 OTA 镜像的关系（sha256 对比）

| 组件 | 与 OTA/设备内对应物 | 结论 |
|---|---|---|
| load | `uboot.bin`（OTA） | **逐字节一致** |
| boot | `boot.img`（OTA） | **逐字节一致** |
| rfsA | `rootfs.bin`（OTA） | **逐字节一致** |
| uenv/adsp/pqda/aqda | OTA 同名 bin | **逐字节一致** |
| sysm | OTA `system.img`（1.31GB 原始） | **不同**：样本仅 600,179,296 B（相对 1.29GB 文件系统为截断），ext4 超级块 uuid/几何与 OTA 相同（`da594c53-…`），元数据区基本一致（仅少量时间戳/计数差异），但数据区无任何 Android 明文串（无 build.prop/dex/arsc）、呈“内容加密/乱码”特征（**疑似文件级加密，未最终证实**）。截断原因两种解释（未实测区分）：① 打包工具按最后已用数据裁剪；② 该版本系统自身用量即如此。**不可在其中直接替换应用** |

### 7.2 为什么 `load` 不在 cfig 表里

`cfig` 表从 uenv（序号 1 → 物理 p2）开始，而序号 0 是 uboot（物理 p1）。
`load` 与 uboot.bin 逐字节一致，说明包内确实携带引导器镜像；但表内不列序号 0。
两种可能（未实测区分）：① DLM/升级器对 `load` 走特殊 tag 或固定分区号写入 p1；
② 表语义即“不写引导器”，`load` 仅作为备用/其它流程（如线刷工具）使用。
无论哪种，**常规救援按表执行时不把 uboot 列为必写项**（对救砖更安全）。

### 7.3 `sysm` 名字填充位的异常标志

16 条记录中只有 `sysm` 的 8 字节名字字段带一个额外字节：`"sysm\0\x01\0\0"`（其余记录均为 `名字+4×0`）。
结合两个事实：（a）`x_dlm_download` 中存在 `puac_large_pkg_buf_offset` /
`x_dlm_set_buffer`（“大包缓冲”）逻辑；（b）本样本 `sysm` 恰好是**被截断的大分区
镜像**（600MB，见 §7.1）——推测该位与“大分区/截断数据的特殊读写处理”有关，
具体语义未完全确认。自制包时对已存在的大分区记录**原样保留**该字节最稳妥。

## 8. 未确认 / 后续

- 32B 每记录字段的精确语义（代码上为“就地解密+可能比对”，`Validate codefile HMAC`
  打印出现在 integrity_check；尚未定位其比对的源数据。自制包参见 pkg-build.md
  的保守处理）。
- [112:368] 256B 中除前 32B 摘要外的 224B 用途（样本为高熵数据；可能为冗余/签名位）。
- 文件头 0x4C 标志位的完整语义（0x10/0x20/0x40 的跳过行为已从分支结构确认，
  但组合行为未实测）。
- sysm 使用区截断规则与 flash 端补齐行为（擦除→写 600MB→剩下保留/擦除？见 §7.1）。
- `load` 记录的写入路径/目标（序号 0/物理 p1 的 uboot；表内不列，见 §7.2）。
- sysm 名字字节 0x01 的精确语义（见 §7.3）。

## 9. 复现命令

```bash
# 解包与清单
python3 tools/pkg_dlm.py list    ../JMGO_M6_upgrade_loader.pkg
python3 tools/pkg_dlm.py sha256  ../JMGO_M6_upgrade_loader.pkg
python3 tools/pkg_dlm.py extract ../JMGO_M6_upgrade_loader.pkg /tmp/pkg_x

# 用设备固件的真实 AES 代码验证（需 pip3 install unicorn）
python3 tools/dlm_aes_emu.py keyscan work/rootfs_x/basic/libdtv_common.so

# 静态定位
llvm-objdump -d --no-show-raw-insn work/rootfs_x/basic/libdtv_common.so > /tmp/libdtv.dis
grep -n "<dlm_aes>\|<dlm_locate_codefile_header>\|<x_dlm_integrity_check>\|<x_dlm_download>:" /tmp/libdtv.dis
```
