# AGENTS.md — 环境约定与协作注意事项

本项目是对 JMGO M6 官方固件（`update_jmgo.zip`）的分析与定制记录。任何 AI/协作者（含 opencode）在修改本仓库前必须遵守以下约定。

## 0. 最高原则

1. **不改动设备本体**：本仓库只做离线分析/打包/签名，所有产出都是文件；刷机由人类手动执行。
2. **提交纪律（重要）**：每完成一小步就 commit 一次，commit message 用「范围: 描述」格式（如 `docs: 补充分区说明`、`tools: 修复 xx`）。**严禁一次性堆积大量改动再提交**，便于溯源。
3. **大文件不入库**：`update_jmgo.zip`、`system.img`、`system.new.dat`、APK 解包产物（>20MB）一律不入 git。参考 `.gitignore`。需要时用 `tools/` 现场解包生成。
4. **危险区**：不要修改/替换 `updater-script` 中对 `uboot/uenv/tz/rootfs` 等底层分区的刷写行为；这些是变砖高风险区。
5. **签名**：任何重签必须使用 AOSP testkey（官方包即 testkey 签名）。`testkey.pk8` 属公开私钥但**不入库**，由使用者自备（见 `artifacts/certs/README.md`）。`testkey.x509.pem` 已入库。

## 1. 运行环境

- 开发机：macOS（Apple Silicon），zsh。
- 工具依赖：`python3`（3.9+，仅标准库）、`openssl`（macOS 自带 LibreSSL 即可）、`git`。
- **无 brew / 无 JRE / 无 7z / 无 wget 网络受限**：不要假设能安装外部工具或访问外网；优先用仓库内 `tools/` 的纯 Python 实现。
- 修改 ext4 镜像（增删文件）需要 Linux 环境的 `debugfs`/挂载；macOS 上只做**读取**与**打包/签名**。
- 本机可用：`llvm-objdump`（反汇编）、`strings`、`bsdtar`（cpio）、`openssl`。

## 2. 目录约定

| 目录 | 内容 | 是否入库 |
|---|---|---|
| `docs/` | Markdown 文档 | 是 |
| `tools/` | 已验证的 Python 工具链 | 是 |
| `artifacts/` | 小体积原始件（prop/证书/清单/校验值） | 是（单文件 <5MB） |
| `apks/` | 需要溯源/修改的官方 APK 备份 | 是（单文件 <20MB） |
| `app/` | 自研 Android 应用（Kotlin，Gradle 8/AGP 8，minSdk 24，双 flavor：standard/system） | 是（源码；构建产物不入库） |
| `work/`（仓库外） | 解包中间产物、镜像等大文件 | 否 |

## 2.1 App 构建约定（改 `app/` 前必读）

- **无本地 Android SDK 环境**：依赖 GitHub Actions 构建（`.github/workflows/android.yml`）。
  推送到 `main` 且改动 `app/**` 自动构建并签名（AOSP testkey，CI 会校验其与
  `artifacts/certs/testkey.x509.pem` 公钥一致）；打 `app-v*` tag 自动发 Release。
- **无法登录 GitHub 时**：CI 失败会把日志推到 `ci-err` 分支（`git fetch origin ci-err` 查看），
  用于排查编译错误。
- 修改 app 代码前请在本地做「XML 可解析 + @string/@drawable 引用存在 + Kotlin 括号平衡」自检；
  提交信息前缀用 `app:` / `fix:` / `ci:`；版本号在 `app/app/build.gradle`（versionCode 递增）。
- 严禁向 App 添加广告/统计/云依赖；图标一律使用 `res/drawable` 下自绘矢量图（不得使用 emoji）。

## 3. 工具链要点（改工具前先读）

- `tools/sdat2img.py`：MTK/AOSP **transfer.list v4** → raw 镜像。v4 语义：`<cmd> N,s1,e1,s2,e2,...`，区间 `(start,end)` 左闭右开，`N=剩余数值个数`；new 数据按区间顺序从 .dat 顺序读取；zero/erase 写零。**不要按旧版 (start,len) 语义改**。
- `tools/img2sdat_v4.py`：raw → v4 文件。修改后必须通过「重建镜像 `cmp` 逐字节一致」自测。
- `tools/signzip.py`：SignApk `-w`（whole-file）克隆。摘要范围 = `file[0 : len-comment_size-2]`（注意**末尾 2 字节注释长度字段不参与签名**）；PKCS7 结构与官方包逐字节对齐已校验。改动后必须用 `verify_ota.py` 对官方包和自签包双向回归。
- `tools/verify_ota.py`：模拟 recovery 验签（整包签名 + MANIFEST 摘要 + 证书匹配）。
- `tools/ext4.py`：只读 ext4（支持 extents）。支持 `ls/cat/get/find/du` 子命令。
- `tools/ext4_patch.py`：ext4 镜像内**原位改字节**（4 字节级补丁无需 Linux）。
- `tools/patch_pkginstaller_odex.py`：解除安装数量限制的 odex 补丁（含上下文校验，仅 1.0.53 适用）。
- `tools/squashfs_lzo.py`：纯 Python SquashFS 解包（MTK C30 变体：压缩 id 3=LZO、dirent 无 type 数组、
  inode diff 非累积、fragment 间接表为裸 u64 数组）。解 `rootfs.bin` 用。
- `tools/pkg_dlm.py`：救砖包（DLM 容器）解析/解包/校验清单（`list/extract/sha256`）。
  记录定位靠常量字段 `9bf7ed4f…e8a9`；布局见 docs/pkg-format.md。
- `tools/dlm_aes_emu.py`：用 Unicorn 直接执行固件 `libdtv_common.so` 的 DLM AES
  （`keyscan` 输出该版本默认密钥=栈保护常量×4 并验证 pkg 匹配；`aes` 做加解密）。
  **依赖 unicorn（pip3 install unicorn）与 llvm-objdump**；本机已验证可装。
- `tools/pkg_build.py`：救砖包装配/改装（`repack` 以官方包为基底换组件；
  `build` 从零装配）。**必须用目标设备固件版本的 libdtv_common.so（--lib）**；
  32B 记录字段语义未完全确认，详见 docs/pkg-build.md 风险说明。

## 4. 修改 APK 的注意点

- `apks/JmGoOS.apk` >20MB **未入库**（可用 tools 从官方包提取）。其余 JMGO 应用已入库。
- `AndroidManifest.xml` 是 AXML 二进制：用 `tools/axml.py` 解析；修改会破坏 APK 签名（系统应用走 platform 签名校验的场景需注意，本机 testkeys 可重签为 platform 签名）。
- 官方 APK 均可用 AOSP testkey 重签（`signapk` 或本仓库思路）。

## 5. 文档写作要求

- 中文为主，命令/路径/字段名保持原文。
- 涉及结论必须注明**证据来源**（文件偏移、代码位置、实测命令输出）。
- 新增分析（如 `docs/install-limit.md`）需给出：现象 → 定位方法 → 证据 → 修复方案 → 风险。

## 6. Git/发布

- 远端：`https://github.com/gujiangjiang/JMGO_M6`（默认分支 `main`）。
- 提交前 `git status` 确认无大文件混入；本仓库禁止 `git add -A` 于仓库根之外的路径。
- 推送前确认大文件已按 `.gitignore` 排除。
