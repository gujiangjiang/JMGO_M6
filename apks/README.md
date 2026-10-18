# APK 备份（官方固件提取，仅供溯源/研究）

来源：官方固件 `update_jmgo.zip` 的 `/system` 分区（构建 `1.0.53`，2019-06-10）。
用途：后续做 APK 解包/修改时对比溯源；**修改后的版本另存为 `<name>.mod.apk` 并在此登记**。

## 已入库

| 文件 | 包名 | 说明 | SHA256 |
|---|---|---|---|
| `JmGOUpdate.apk` | `com.jmgo.update` | 系统升级/本地升级/静默安装（uid system） | `79f8dabafbf78232ad6d6d956d6f322638161e48e8dcc84591e386327edce309` |
| `SystemApi.apk` | `com.jmgo.systemapi` | 设置桥接服务（uid system，含 default_launcher 逻辑） | `365349cfaa82cad178e03b1a96ccf07642fe8183e3d42e99dfc6f42fbd80ee60` |
| `JGServersigned.apk` | `com.jianguo.server` | 坚果服务进程（uid system） | `7bd7f39a952cfddebe7c00be38fcaacc564858a358f488780a26b2bc9248e992` |
| `JmGOTV.apk` | `com.jmgo.tv` | TV 输入应用（uid system） | `60f915e4d4bdcefa6c10471df53499f061b4263f7a88b8cac0809b3025066028` |
| `jgvideoplayer.apk` | `com.jmgo.moive` | 视频播放器（uid system） | `d34e95f7f10c1396ae4547581f045d36def7a8b72d237db8bf0b44ddda5ee7ae` |
| `jmgowebbrowser.apk` | `com.jmgo.webbrowser` | 浏览器（uid system） | `e5343d89f5a146db28579eb6bb093ecf9a63d836ab0886062a831601729e9767` |
| `GoogleRemote.apk` | `com.softwinner.tvremoteserver` | 遥控服务（uid system，**慎删**） | `e6921b9d9c203142e809195b14f99ab726ff5008f1fa470687c6e29662e04765` |
| `YouCastRender.apk` | (投屏渲染) | priv-app `/priv-app/YouCast` | `12b3494f55098fd5632c96547d9381cabb66d65cb86a720bd35e774954f87f64` |
| `plugin-qiyi-playercore-1.56.0.apk` | `com.qiyi.playercore` | 内嵌于桌面 APK `assets/plugin/` 的爱奇艺播放核心 | `7d47a150fa41af6840657921fa7f1a874dc1c1e0716ad6dddd9d8fdff11522c6` |

## 未入库（过大，可用 tools 现场提取）

| 文件 | 大小 | 提取命令 |
|---|---|---|
| `JmGoOS.apk`（com.jmgo.launcher，Cocos 桌面） | 64 MB | `tools/ext4.py system.img get /app/JmGoOS/JmGoOS.apk` |
| `QyPlayerActivity.apk`（com.jmgo.qy.player） | 21 MB | `tools/ext4.py system.img get /app/QyPlayerActivity/QyPlayerActivity.apk` |
| `jgmusicplayer.apk`（com.jmgo.music） | 16 MB | 同上（/app/jgmusicplayer/） |
| 预装三方 `lebo/wps/youku` | 12/9.8/29 MB | `tools/ext4.py system.img get /preinstall/...` |

## 修改记录

| 日期 | 目标 | 修改 | 说明 |
|---|---|---|---|
| 2026-10-19 | `PackageInstaller`（priv-app） | `apks/PackageInstaller.odex.patched` | 解除第三方应用安装数量限制（10 个）。仅改 odex 中 4 字节（`0x48928c`: `cbnz w0` → `b`），不影响 APK 签名/权限。详见 `docs/install-limit.md` |

修改版校验：`PackageInstaller.odex.patched`
- 原始 odex sha256: `21cf269a72fea6eef5c843713aeba340739e40b37b0b7f45691f8c0616bf9b7f`
- 修改后 sha256: `7b08f3db32a50f72494f7b6f88173e919456d1fbe24354eeffff370e43b63ac9`
- 应用方式（macOS 可直接做）：`tools/patch_pkginstaller_odex.py --in-img system.img`
