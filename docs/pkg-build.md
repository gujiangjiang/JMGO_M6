# 自制 DLM 救砖包（`upgrade.pkg`）指南

> 目标：把官方救砖包换成/改装成自己的包（如自定义 boot/system/logo），
> 让设备的 U 盘救砖流程接受并刷入。
> 前置知识：[`pkg-format.md`](pkg-format.md)（容器格式与校验链）与
> [`usb-rescue.md`](usb-rescue.md)（刷机流程）。工具：`tools/pkg_dlm.py`、
> `tools/dlm_aes_emu.py`、`tools/pkg_build.py`。

## 0. 可行性结论（2026-10 状态）

| 环节 | 状态 | 说明 |
|---|---|---|
| 容器结构（头/记录/追加块） | ✅ 完全掌握 | 逐字段验证，样本 16/16 记录跨度吻合 |
| 记录级写入格式（tag↔分区） | ✅ 掌握 | cfig 表 + 组件对照见 pkg-format §2/§6 |
| AES 算法 | ✅ 标准 AES-128-CBC | Unicorn 执行固件代码实证（含标准向量） |
| 默认密钥 | ✅ 机制明确 | = **该固件版本的编译常量（栈保护金丝雀）×4，iv=0**；随版本随机 |
| 文件头 112B + 摘要 blob 加解密 | ✅ 可复现 | `pkg_build.py` 按设备代码同款算法生成 |
| 文件级 SHA-256（[368:EOF]） | ✅ 可复现 | 重算并加密写入 |
| 32B 每记录字段 | ⚠ 语义未完全确认 | 改装时**原样保留**即可；全新建包用占位（有风险） |
| 头部 0x4C 标志位组合 | ⚠ 未实测 | 提供 `--flags` 直接写值 |

**最大的门槛是密钥版本匹配**：DLM 默认密钥来自编译器在每个固件版本重新生成的
栈保护常量（Python 工具会读出来），所以：
- 官方 pkg 只能被“与它同一编译批次”的固件版本接受；
- 自制包必须用**目标设备当前系统里那份 `libdtv_common.so`** 来打
  （或找到设备同版本的固件包解出该文件）。

> 已实测：官方 `JMGO_M6_upgrade_loader.pkg` 不匹配本仓库
> `update_jmgo.zip`（1.0.53）里的 `libdtv_common.so`（key=0x94102909）；
> 说明官方 pkg 是按更早出厂固件版本构建的。你的设备实际跑哪个版本，
> 决定了你能不能继续用官方 pkg —— 也决定自制包要用哪个版本的 lib。

## 1. 第一步：拿到目标版本的 `libdtv_common.so` 并验证

三种来源（按可行性）：

1. **从设备当前系统提取**（最可靠；设备已 root，adb root 可用）：
   ```bash
   adb root && adb pull /basic/libdtv_common.so        # Linux 世界（two-worlds）根
   # 若 adb 拿不到，可从 /dev/block 里定位 linux_rootfs(p9) 分区拉 squashfs，
   # 用 tools/squashfs_lzo.py 解出 basic/libdtv_common.so
   ```
2. **从与设备同版本的官方固件包**（`update_jmgo.zip` 之类）里解出：
   `rootfs.bin` → `tools/squashfs_lzo.py extract` → `basic/libdtv_common.so`。
3. 本仓库现成的 `work/rootfs_x/basic/libdtv_common.so`（=1.0.53，**仅当**你的
   设备就是 1.0.53 时可用）。

先验证它能否解开你手里的官方 pkg（决定你的设备是否还能用官方救援包）：

```bash
python3 tools/dlm_aes_emu.py keyscan <该lib> JMGO_M6_upgrade_loader.pkg
# 输出两侧都 OK  → 该 lib 版本与官方 pkg 匹配（你的设备可用该 pkg 救砖）
# 输出 FAIL     → 版本不匹配！官方 pkg 对这台设备无效（或设备不是这个版本）
```

## 2. 第二步：改装（推荐路线）

```bash
# 以官方 pkg 为基底，替换一个组件（例：boot），版本号必须比设备当前版本“更新”
python3 tools/pkg_build.py repack \
    --base JMGO_M6_upgrade_loader.pkg \
    --lib  <设备当前版本的 libdtv_common.so> \
    --replace boot=/path/to/my_boot.img \
    --version t-apollo-mp-1600 \
    --out custom.pkg
```

要点：
- `--replace` 可多次；未列出的组件（含 sysm、cfig、adsp 追加块、32B 字段）
  **原样保留**，风险最小。
- `--version` 必须“更大”（校验逻辑按 `_ \n,.` 切分后逐段数字比较，
  只接受更新版本）。默认沿用原值 +9999 的做法不安全，建议显式指定更合理的串。
- `--flags` 对应文件头 0x4C：bit0x10 跳过 ID 校验、bit0x40 跳过型号校验
  （若不确定，保持 0 与官方一致）。
- 建议只改 **boot/logo** 这类“刷坏也能再刷”的分区做第一次实测；
  不要第一步就动 uboot/rootfs/tzbp。

自检（必须）：用同一 lib 检查重新生成的记录常量与文件头：

```bash
python3 tools/dlm_aes_emu.py keyscan <该lib> custom.pkg        # rc=0 且两个 OK
python3 tools/pkg_dlm.py list custom.pkg                        # 结构/尺寸与原件一致
```

## 3. 可选：从零装配

```bash
python3 tools/pkg_build.py build --lib <证件lib> \
    --company jmgo --model JmGO_mt5863_64_n --version <ver> \
    --cfig artifacts/pkg/cfig-partition-table.bin \
    --component uenv=... --component misc=... --component boot=... \
    --component rfsA=... --component 3rdp=... --component 3rdw=... \
    --component perm=... --component pqda=... --component aqda=... \
    --component logo=... --component tzbp=... --component adsp=... \
    --out new.pkg
```

注意（与 repack 相比风险更高）：
- 新增记录的 **32B 字段**由工具填 `AES(32×0)` 占位：其真实语义（可能为
  payload 摘要/HMAC）尚未完全确认，存在校验不过的设备端行为差异；
- `adsp` 的追加块里带客户版本串，全新建包时工具用常规占位（“iPAd”+0）——
  是否影响 adsp 升级校验未实测；**建议 adsp 直接用 repack 保留**；
- `load`(uboot) 写入 p0 属高风险，本工具默认不组装 `load`，确需时自行加入并自负风险。

## 4. 第三步：上机验证（风险自负）

1. U 盘 FAT32，根目录放包并改名为 `upgrade.pkg`（中间件默认路径还认
   `/upgrade/upgrade.pkg` 与 `/3rd_rw/upgrade/upgrade.pkg`，见 usb-rescue §4）；
2. 插到投影仪上开机，观察是否进入升级 UI；
3. **兜底**：保留原厂 pkg；若引导器（uboot）未被写坏，救砖流程可反复重试；
   引导器坏了就只能走 SoC 级线刷（T8032/USB，本仓库未覆盖）。

> 强烈建议第一次测试前，先用模拟执行把整条“读取→校验”链跑一遍：
> `dlm_aes_emu.py keyscan` 只能验证头与常量字段；文件级 SHA-256 由
> `pkg_build.py` 重算（规则已确认），如需完全模拟可继续用 Unicorn
> 调用 `x_dlm_integrity_check`（见 pkg-format §5.3 的调用点）。

## 5. 工作原理速查（与 pkg-format 对应）

- 文件头 112B（明文布局）：`company[4] | "#DH@FiRm"[8] | version[56] |
  "#DH@FiRm"[8] | flags[1]+pad[3] | model[32]`；随后 AES-CBC 加密，IV=0。
- 摘要 blob 256B：[0:32]=SHA-256([368:EOF])，[32:256] 用途未明（占位 0）。
- 记录：`name[8] | u32(64+payload) | AES("reserved mtk inc") | AES(32B) |
  "iMtK" | u32 0x38 | 56B | payload`。
- `cfig`：`name[8] | u32(表长) | AES(常量) | AES(32B) | 表文本`（无 iMtK 块）。

## 6. 常见问题

- **keyscan 报 “未找到金丝雀常量模式”**：该版本 DLM 可能改用其它密钥来源
  （客户密钥路径）。先 `strings lib | grep customer` 确认；并在设备上抓
  `dlm_aes` 运行时参数（gdb/预加载）——本仓库暂未覆盖该分支。
- **自制包比官方包小/大很多**：总长 = 368 + Σ(60+64+payload)（cfig 少 64），
  与官方结构一致即可；U 盘 FAT32 单文件 <4GB。
- **改写 sysm 的请求**：官方 pkg 的 sysm 是设备侧加密(截断)镜像（见
  pkg-format §7.1），直接在包内替换应用不可行；要定制 system 请走 OTA 路线
  （见 docs/repack.md、docs/flash.md）。
