# 内置应用清单（/system 分区）

> 数据来源：`work/system.img` 内目录枚举 + 各 APK 的 `AndroidManifest.xml` 解析（见 `artifacts/manifests/`）。
> 判定分级：**可删** = 删除后不影响系统关键功能；**慎删** = 与遥控/输入/系统服务相关；**保留** = 系统关键。

## 1. `/app`（25 个）

| 目录 | 包名 | uid | 说明 | 分级 |
|---|---|---|---|---|
| `JmGoOS` | `com.jmgo.launcher` | - | **桌面**（Cocos2d-x LuaJIT，64MB，内含"设置"：蓝牙配对/梯形校正等；捆绑极光推送/友盟统计） | 定制关键 |
| `JmGOUpdate` | `com.jmgo.update` | system | 系统升级/本地升级/静默安装/预装处理 | 保留 |
| `SystemApi` | `com.jmgo.systemapi` | system | 设置桥接（`default_launcher` 判定、`UpdateApkActivity`） | 保留 |
| `JGServersigned` | `com.jianguo.server` | system | 坚果服务（蓝牙遥控配对等） | 保留 |
| `JmGOTV` | `com.jmgo.tv` | system | TV Input（`default_tv`） | 慎删 |
| `GoogleRemote` | `com.softwinner.tvremoteserver` | system | 遥控服务（Softwinner 代码复用） | 慎删 |
| `QyPlayerActivity` | `com.jmgo.qy.player` | - | 爱奇艺播放入口（另有 `assets/plugin/` 内嵌 `com.qiyi.playercore`） | 可删 |
| `jgmusicplayer` | `com.jmgo.music` | - | 音乐播放器 | 可删 |
| `jgvideoplayer` | `com.jmgo.moive` | system | 视频播放器 | 可删* |
| `jmgowebbrowser` | `com.jmgo.webbrowser` | system | 浏览器 | 可删 |
| `WallpaperBackup` | `com.android.wallpaperbackup` | system | 壁纸备份（AOSP 组件） | 保留 |
| `BasicDreams` `Bluetooth` `CaptivePortalLogin` `CertInstaller` `CtsShimPrebuilt` `ExtShared` `HTMLViewer` `ImageBrowser` `KeyChain` `PacProcessor` `PicoTts` `PinyinIME` `PrintSpooler` `UserDictionaryProvider` `webview` | AOSP/MTK 标准件 | - | 大部分可删，但收益小、风险高 | 慎删 |

> `*` `jgvideoplayer` 为 uid system 且可能被桌面/系统 Intents 调用，删除前建议先在真机 `pm disable` 验证。

## 2. `/priv-app`（24 个）

| 目录 | 包名 | 说明 | 分级 |
|---|---|---|---|
| `SystemUI` | `com.android.systemui` | 系统 UI | 保留 |
| `TvSettings` | `com.android.tv.settings` | 原生 TV 设置（JMGO 设置不在这里） | 保留 |
| `MtkTvInput` | `com.mediatek.tvinput` | MTK TV 输入 | 慎删 |
| `LiveTVSettings` / `LiveTVSetupWizard` | `com.mediatek.wwtv.*` | MTK LiveTV（`sys.mtk.livetv.ready=0`，大概率未启用） | 可删* |
| `YouCast` | `YouCastRender.apk`（投屏渲染） | 投屏接收端 | 可删* |
| `PackageInstaller` `SettingsProvider` `MediaProvider` `DownloadProvider` `ContactsProvider` `ExternalStorageProvider` `FusedLocation` `InputDevices` `ProxyHandler` `SharedStorageBackup` `StatementService` `VpnDialogs` `BackupRestoreConfirmation` `DefaultContainerService` `ExtServices` `TvProvider` `Shell` `CtsShimPrivPrebuilt` | AOSP/MTK 标准件 | - | 保留 |

## 3. `/preinstall`（3 个，首启静默安装）

| 文件 | 包名 | 说明 |
|---|---|---|
| `lebo.apk` | `com.hpplay.happyplay.aw` | 乐播投屏 v8.1.10 |
| `wps_4.6_34.apk` | `cn.wps.moffice_i18n_TV` | WPS Office TV v4.6 |
| `youku.apk` | `com.cibn.tv`（优酷 CIBN 版） | 优酷 TV 客户端 |

安装机制：`com.jmgo.update`（JmGOUpdate）在首启解析 `preinstall` 目录并静默安装（dex 含 `PreInstallInfo`/`parsePreInstall`/`SILENT_INSTALL` 字符串）。
**注意**：删除 `/system/preinstall/*` 只对「恢复出厂后」生效；已装到 `/data` 的副本需再卸载。

## 4. 桌面与"设置"的关系（定制要点）

- `com.jmgo.launcher` 的 manifest **没有声明** `android.intent.category.HOME`；
- 系统通过 `/etc/jmgodefault.dat`（`default_launcher = com.jmgo.launcher`、`default_tv = com.jmgo.tv`）指定默认桌面，`com.jmgo.systemapi` 的 dex 中存在 `default_launcher` 字符串（判定逻辑所在）；
- 桌面内捆绑的推送/统计：`cn.jpush.android.*`（极光推送，服务+守护进程）与友盟（`meta-data: Umeng`），会常驻网络；
- 因此"换桌面"不是简单的 HOME 意图切换，需同步处理 `jmgodefault.dat` 与 SystemApi 逻辑（详见 README 的定制章节，后续文档补充）。

## 5. 快速检查命令

```bash
python3 tools/ext4.py system.img ls /app
python3 tools/ext4.py system.img ls /preinstall
python3 tools/axml.py apks/JmGOUpdate.apk summary | head -40
```

## 6. 关联：安装数量限制

`com.jmgo.launcher`（或 SystemApi/JGServersigned）中存在"第三方应用安装数量"限制逻辑，adb `pm install` 可绕过 —— 定位与修复见 [`install-limit.md`](install-limit.md)。
