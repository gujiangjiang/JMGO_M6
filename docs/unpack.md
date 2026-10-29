# 解包指南（OTA → 系统镜像 → 文件）

> 前提：`update_jmgo.zip` 放于项目根目录同级（本仓库不入库，MD5 `4800aa07821c285423fcba24bbe5c8e8`）。

## 0. 环境

- 任意系统只需 `python3`（标准库）。macOS 可直接完成除「写 ext4」外的全部步骤。

## 1. 拆包

```bash
mkdir -p work/extracted
cd work/extracted
unzip ../../update_jmgo.zip
```

得到：`system.new.dat` / `system.transfer.list` / `boot.img` / 各固件 bin / `META-INF/`。

## 2. transfer.list v4 → raw ext4 镜像（标准做法）

```bash
python3 tools/sdat2img.py work/extracted/system.transfer.list \
                       work/extracted/system.new.dat \
                       work/system.img
# version=4 header_total=256153 image=320000 blocks (1310720000 bytes)
# new read from dat: 1007869952 / 1007869952 OK
```

得到 320000 块（1.31 GB）的分区级镜像，前 315000 块为 ext4 文件系统。

> **关键事实**：本包的 `.dat` 就是 raw ext4 内容按 `new` 区间顺序拼接，尾部零块被裁剪；
> v4 语法：`<cmd> N,s1,e1,s2,e2,...`，`N` = 其后数值个数，区间 `(start,end)` 左闭右开。

## 3. 免挂载读/取文件（macOS 可用）

```bash
python3 tools/ext4.py work/system.img ls /app           # 列目录
python3 tools/ext4.py work/system.img cat /build.prop   # 输出文件内容
python3 tools/ext4.py work/system.img get /app/JmGoOS/JmGoOS.apk JmGoOS.apk
python3 tools/ext4.py work/system.img find jmgo         # 全盘搜索
python3 tools/ext4.py work/system.img du                # 统计
```

## 4. boot.img 解包（MTK）

```bash
python3 - <<'EOF'
import struct
d = open('work/extracted/boot.img','rb').read()
ks, rs, ps = struct.unpack_from('<I', d, 8)[0], struct.unpack_from('<I', d, 16)[0], struct.unpack_from('<I', d, 36)[0]
pages = lambda n: (n + ps - 1)//ps
open('work/boot_kernel','wb').write(d[ps:ps+ks])
open('work/boot_ramdisk.gz','wb').write(d[ps*(1+pages(ks)):ps*(1+pages(ks))+rs])
print("page", ps, "kernel", ks, "ramdisk", rs)
EOF
gunzip -kf work/boot_ramdisk.gz && mkdir -p work/ramdisk && bsdtar -xf work/boot_ramdisk -C work/ramdisk
```

`boot_kernel` 为 uImage（`file` 可见 Linux 3.18.25），`work/ramdisk/` 即根文件系统（`init.rc`、`fstab.mt5863`、`default.prop` 等）。

## 5. rootfs.bin（MTK 变体 squashfs，LZO）

50 MiB（52,428,800 B）。`file` 会误报 lzo；实际是 **MTK C30 变体 squashfs（超块 compression id = 3 → LZO，
沿用 3.x 编号）**，且与主线格式有差异（dirent 无 type 数组、inode diff 非累积、
fragment 间接表为裸 u64 数组、符号链接 inode 末尾多 4 字节 xattr 索引）。
本仓库提供纯 Python 解包器（无需依赖）：

```bash
python3 tools/squashfs_lzo.py list    work/extracted/rootfs.bin
python3 tools/squashfs_lzo.py extract work/extracted/rootfs.bin work/rootfs_x
```

解出的即 Linux 世界 rootfs（含 `bin/`、`basic/` 中间件、`etc/checker.sh`、
`basic/dtv_driver.ko` 等）——U 盘救砖机制就在其中，见 [`usb-rescue.md`](usb-rescue.md)。

## 6. APK 解析（AXML）

```bash
python3 tools/axml.py apks/JmGOUpdate.apk summary   # 元素树（含 package/activity）
python3 tools/axml.py apks/JmGOUpdate.apk raw       # 原始树
```

`AndroidManifest.xml` 为二进制 AXML；`tools/axml.py` 支持字符串池/资源映射/属性名识别（`android:name` 等）。

## 7. 校验（可选）

```bash
md5 update_jmgo.zip        # 4800aa07821c285423fcba24bbe5c8e8
shasum -a 256 update_jmgo.zip
```
