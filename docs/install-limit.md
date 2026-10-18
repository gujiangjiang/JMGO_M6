# 安装数量限制：分析、绕过与永久修复

## 1. 现象

- 通过设备 UI 安装第三方 APK（U 盘/浏览器/文件管理器触发系统安装器）到 **约 8~10 个** 后，
  继续安装会提示：**「安装失败,应用过多，请先卸载其他应用！」**
- 通过 **`adb install`** 安装不受限制（可继续装）。

## 2. 根因定位

### 2.1 消息来源

全盘扫描 `system.img` 中的中文字符串，定位到该消息位于：

```
/priv-app/PackageInstaller/PackageInstaller.apk
  resources.arsc: 0x7f0d013f  string/install_failed_overabundance
    = "安装失败,应用过多，请先卸载其他应用！"
```

该 APK 并非 AOSP 原版：包名 `com.android.packageinstaller`（`original-package`），
但安装界面是坚果自研（资源中出现 `holatek_button_*`、`cocos_bg.jpg`），
主 Activity 为 `.HolatekPackageInstallerActivity`，代码被预编译进
`/priv-app/PackageInstaller/oat/arm64/PackageInstaller.odex`（APK 内无 classes.dex）。

### 2.2 限制逻辑（反汇编 ODEK 内嵌 DEX）

`HolatekPackageInstallerActivity.processPackageUri`：

```smali
invoke-direct {v14,v2,v0}, HolatekPackageInstallerActivity->canInstallApp   # 检查
move-result v2
if-eqz v2, :deny            # false -> 走到错误界面
invoke-direct {v14}, ->initiateInstall        # 允许 -> 继续安装
...
:deny
const v2, 0x7f0d013f        # "安装失败,应用过多，请先卸载其他应用！"
invoke-virtual {v13,v2}, TextView->setText
const v2, 0x7f11009b        # 错误界面按钮
...
```

`canInstallApp()` 反汇编（完整逻辑）：

```java
boolean canInstallApp(...) {
    boolean ok = true; int count = 0; boolean alreadyListed = false;
    StringBuffer sb = new StringBuffer();
    SharedPreferences sp = getSharedPreferences("apps", ...);
    String list = sp.getString("list", "");
    if (!TextUtils.isEmpty(list)) {
        for (String name : list.split(",")) {
            try {
                pm.getPackageInfo(name, 0);      // 只统计“仍然装着的”
                sb.append(name).append(",");
                count++;
                if (name.equals(当前安装包名)) alreadyListed = true;
            } catch (NameNotFoundException e) { /* 已被卸载，不计入 */ }
        }
    }
    sp.edit().putString("list", sb.toString()).commit();   // 顺手清理列表
    if (!alreadyListed && count >= 10) ok = false;         // ← 阈值 10
    return ok;
}
```

**要点**

| 事实 | 说明 |
|---|---|
| 阈值 | **10 个**（`const/16 v9, 10`）|
| 计数来源 | 安装器自己的 SharedPreferences：`/data/data/com.android.packageinstaller/shared_prefs/apps.xml`，键 `list`（逗号分隔包名） |
| 更新不受限 | 包名已在列表中（`alreadyListed`）时直接放行 |
| 计数会自清理 | 每次检查会把已卸载的包名从列表剔除 |
| 为什么 adb 能突破 | 限制只写在 **系统安装器 UI** 的代码里；`adb install` 走 `pm`/PackageManagerService，不经过此 Activity |
| 编译器行为 | odex 头 key-value 存有 `compiler-filter=speed` → 运行时执行**.text 中的 AArch64 编译代码**，而非解释 dex |

### 2.3 编译代码中的检查点（ODEK）

`processPackageUri` 编译后：

```asm
0x48927c  adrp x0, ...; ldr x0,[x0,#0x98]   ; 取 canInstallApp 方法入口
0x489288  ldr x30,[x0,#0x18]; blr x30       ; 调用 canInstallApp
0x48928c  cbnz w0, +0xe0                    ; 非 0 -> 允许安装（跳过错误界面）
0x489290  ...                               ; 错误界面（设置“安装失败,应用过多”文案）
```

`canInstallApp` 全 dex 中**仅此一处调用**（已全量扫描 invoke 指令确认），
即：这是 UI 安装路径上唯一的限制闸门。

## 3. 临时绕过（免刷机）

adb 可用（userdebug 出厂即授权）时，清空安装器数据即可将计数归零：

```bash
adb root
adb shell pm clear com.android.packageinstaller        # 方式 A：整数据清除
# 或只删计数文件（root）：
adb shell rm -f /data/data/com.android.packageinstaller/shared_prefs/apps.xml
```

之后可继续装 10 个；再满时重复。注意：`pm clear` 会强制停止安装器（无副作用，安装器本身无持久状态）。

## 4. 永久修复（已在本仓库验证）

把 `0x48928c` 的 `cbnz w0, +0xe0` 改为无条件跳转 `b +0xe0`：
**无论检查结果如何，UI 安装始终放行**（调用保留，副作用不变；错误界面成为死代码）。

```
offset 0x48928c: 00 07 00 35  (cbnz w0, #+0xe0)
             ↓
                 38 00 00 14  (b #+0xe0)
```

- 不改 APK → **不动签名**（该 APK 由厂商私有密钥签名，重签会失去系统权限，故此法最优）
- 不改 dex/校验和 → 补丁信息、odex-APK 一致性检查（按 APK 内 dex 校验）不受影响
- .text 代码区无完整性校验 → 可直接改

### 操作步骤（macOS 可全程完成，除最终签名需 testkey.pk8）

```bash
# 1) 官方包 -> system.img
python3 tools/sdat2img.py work/extracted/system.transfer.list \
                         work/extracted/system.new.dat work/system.img

# 2) 原位打补丁（带上下文校验，非 1.0.53 版会拒绝）
python3 tools/patch_pkginstaller_odex.py --in-img work/system.img
#    或先单独产出 odex 再替换（apks/PackageInstaller.odex.patched 已提供）

# 3) 重打包 + 重签（需要自备 testkey.pk8，见 artifacts/certs/README.md）
python3 tools/repack_ota.py update_jmgo.zip work/system.img update_jmgo_mod.zip \
        testkey.pk8 artifacts/certs/testkey.x509.pem
python3 tools/verify_ota.py update_jmgo_mod.zip artifacts/certs/testkey.x509.pem

# 4) U 盘本地升级刷入（见 docs/flash.md）
```

### 验证与回滚

- 刷后测试：UI 安装第 11、12 个应用应正常进入安装确认界面；`logcat` 里可见
  `PackageInstaller: canInstallApp false`（限制逻辑仍会打印）但**不再拦截**。
- 回滚：重刷官方 `update_jmgo.zip` 即恢复原状。

## 5. 备注

- `work/PackageInstaller.odex` 与 `.patched` 的 sha256 见 `apks/README.md`。
- 本限制与"预装应用 `/system/preinstall`"无关；预装由 `com.jmgo.update` 静默安装，不经过此检查。
- 同族思路（未采用）：替换整个 PackageInstaller 为 AOSP 版（需私钥重签+权限风险）、修改 dex 阈值（编译版为 speed，改 dex 不生效）。
