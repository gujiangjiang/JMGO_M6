# 重打包指南（修改 system → 重新生成 OTA → 签名）

> 现状：**写 ext4** 需要 Linux 环境（`debugfs` 或 loop 挂载）；macOS 上完成生成/签名/校验（本仓库工具均为纯 Python）。
> 安全前提：只改 `/system`（可选 `boot.img`），**保留原 `updater-script`**（即不动 uboot/tz 等）。

## 1. 修改 system 镜像（Linux）

```bash
# 方式 A：debugfs（免 root，推荐）
debugfs -w system.img -R "rm /app/预装应用.apk"          # 删除文件
debugfs -w system.img -R "write 新桌面.apk /app/xxx.apk" # 写入文件（先确保空间足够）
# 注意：debugfs 写文件默认权限 0644，必要时 -R "set_inode_field <path> mode 0100755"

# 方式 B：loop 挂载
sudo mount -o loop,rw system.img /mnt
sudo cp ... /mnt/app/... && sudo rm /mnt/xxx
sudo umount /mnt
```

**约束**
- 镜像总大小保持 **320000 块**（1,310,720,000 B）；文件系统的 315000 块中空闲约 326 MB。
- 大改动（换桌面）后建议 Linux 上 `e2fsck -f system.img` 自检。

## 2. 生成 v4 OTA 文件

```bash
python3 tools/img2sdat_v4.py system_mod.img out/system
# 生成 out/system.transfer.list + out/system.new.dat
# 自测：用 tools/sdat2img.py 重建后 cmp system_mod.img 重建结果
```

## 3. 重打包 + 签名（一条命令）

```bash
python3 tools/repack_ota.py update_jmgo.zip system_mod.img update_jmgo_mod.zip \
        testkey.pk8 artifacts/certs/testkey.x509.pem
```

内部流程（`tools/repack_ota.py`）：
1. 用 `img2sdat_v4` 生成新的 `system.transfer.list`/`system.new.dat`；
2. 以官方包为底，**替换**这两个条目，其余（boot.img、固件、updater-script、metadata）原样保留；
3. 调 `signzip.py` 做 SignApk whole-file 签名（含 MANIFEST.MF/CERT.SF 重建）。

## 4. 校验

```bash
python3 tools/verify_ota.py update_jmgo_mod.zip artifacts/certs/testkey.x509.pem
# whole-file signature: OK / signer cert matches: True / entry digests: OK
```

## 5. 刷机前置自查（强烈建议）

1. **空改预检**：用官方 `system.img` 不做任何修改跑一遍 §3，得到"重签官方包"，先刷一次验证设备接受 testkey 重签（详见 `docs/flash.md` 的风险与回退）。
2. 设备侧核对：
   ```bash
   adb root && adb shell "dd if=/dev/block/mmcblk0p4 of=/sdcard/recovery.img"
   adb pull /sdcard/recovery.img   # 分析 /res/keys（AOSP recovery 内置公钥）
   ```

## 6. `testkey.pk8` 获取

见 `artifacts/certs/README.md`（AOSP 公开文件，不入库）。

## 7. 常见坑

| 现象 | 原因 | 处理 |
|---|---|---|
| updater 报 `E3003` | 设备 build 比包新（时间戳断言） | 保持 `metadata/manifest` 的 post-timestamp 或改 updater-script（不建议） |
| 验签失败拒刷 | 未用 testkey 签 / 打包后又被修改 | 用 `verify_ota.py` 自检；签名必须最后做 |
| 刷完黑屏/卡 LOGO | system 修改破坏启动（删了关键应用/库存放位置错误） | recovery 重刷原版 `update_jmgo.zip`（U 盘本地升级仍可用） |
| zip 里出现 `META-INF/CERT.RSA` 不一致 | 先签名后又改了 zip 内容 | 重新签名 |
