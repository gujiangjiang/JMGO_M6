#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""pkg_dlm.py — MTK DLM 救砖包（upgrade.pkg / *_upgrade_loader.pkg）解析与解包工具。

格式（全部经真机固件代码 + 模拟执行验证，见 docs/pkg-format.md）：

  ┌ 368 字节文件头 ──────────────────────────────────────────┐
  │ [0:112]   AES-128-CBC 加密的文件头（见下）                │
  │ [112:368] AES-128-CBC 加密的 256 字节：前 32 字节 =        │
  │           文件级 SHA-256（覆盖 [368:EOF]），其余未定义      │
  └──────────────────────────────────────────────────────────┘
  之后依次排列 16 条记录：
  ┌ 60 字节记录头 ───────────────────────────────────────────┐
  │ +0x00 name[8]  组件名（ASCII，\0 补齐）                    │
  │ +0x08 u32     上部长度 = 64 + payload 长度（含追加块）      │
  │ +0x0C [16]     常量 9bf7ed4fda262a8e58fc63cf38f6e8a9      │
  │               （AES 加密的 "reserved mtk inc"，固定密文）   │
  │ +0x1C [32]     加密的 32 字节数据（HMAC/摘要区，实现中按     │
  │               记录密钥就地解密后使用）                      │
  └──────────────────────────────────────────────────────────┘
  ┌ 64 字节追加块（除 cfig 外所有记录均有） ──────────────────┐
  │ "iMtK" + u32(0x38) + [u32 0x30 + 48 字节数据]              │
  │   常规记录：48 字节 = "iPAd" + 0（占位）                    │
  │   adsp 记录：48 字节 = 内部长度(0x30 语义占位) + 版本串     │
  └──────────────────────────────────────────────────────────┘
  payload 数据（自记录起始处 +124 字节开始，长度 = u32 - 64）

  cfig 记录特殊：60 字节头之后直接是 222 字节的分区表文本。

用法：
  python3 tools/pkg_dlm.py list    <pkg>
  python3 tools/pkg_dlm.py extract <pkg> <输出目录>
  python3 tools/pkg_dlm.py sha256  <pkg>          # 各组件 sha256（与 OTA 包对比用）
"""
import hashlib
import mmap
import os
import struct
import sys

MAGIC_CONST = bytes.fromhex("9bf7ed4fda262a8e58fc63cf38f6e8a9")


def iter_records(mm):
    """扫描常量密文定位 16 条记录；产量 (name, rec_off, val, payload_off, payload_len)。"""
    size = len(mm)
    pos = 0
    hits = []
    while True:
        i = mm.find(MAGIC_CONST, pos)
        if i < 0:
            break
        hits.append(i)
        pos = i + 1
    for k, o in enumerate(hits):
        rec = o - 12
        name = bytes(mm[rec:rec + 8]).rstrip(b"\0").decode("latin1")
        val = struct.unpack("<I", mm[rec + 8:rec + 12])[0]
        payload_off = rec + 124
        payload_len = val - 64
        yield name, rec, val, payload_off, payload_len


def detect_magic(blob):
    """按组件 payload 首部魔数识别类型。"""
    sigs = [
        (b"ANDROID!", "Android boot image"),
        (b"hsqs", "SquashFS (LE)"),
        (b"\x7fELF", "ELF"),
        (b"PQEX", "PQ 数据 (MStar)"),
        (b"MTK_AQ_V", "MTK AQ 数据"),
        (b"\xff\xd8\xff", "JPEG (开机 logo)"),
        (b"adspbin", "adsp 子镜像头"),
        (b"boot-rec", "bootloader message (misc)"),
    ]
    for sig, desc in sigs:
        if blob.startswith(sig):
            return desc
    if len(blob) > 0x43A:
        m = struct.unpack("<H", blob[0x438:0x43A])[0]
        if m == 0xEF53:
            return "ext4 文件系统"
    if blob[:4] == b"\0\0\0\0" and len(blob) > 1024 and blob[:32] == b"\0" * 32:
        return "零填充/裸数据"
    return "未知"


def cmd_list(pkg):
    f = open(pkg, "rb")
    mm = mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ)
    size = len(mm)
    print("file: %s  size=%d (0x%x)" % (pkg, size, size))
    print("file header: 368 bytes (AES-128-CBC encrypted; key = 构建常量×4, iv=0)")
    print()
    print("%-6s %-8s %-11s %-12s %-10s %-12s %s" % ("#", "name", "rec_off", "payload_off", "size", "size(hex)", "type"))
    seen = []
    for k, (name, rec, val, poff, plen) in enumerate(iter_records(mm)):
        blob = bytes(mm[poff:poff + 64])
        typ = detect_magic(blob)
        print("%-6d %-8s 0x%08x  0x%08x   %-10d 0x%-10x %s" % (k, name, rec, poff, plen, plen, typ))
        seen.append((name, rec, val, poff, plen))
    # cfig 表格
    for name, rec, val, poff, plen in seen:
        if name == "cfig":
            table = bytes(mm[rec + 60:rec + 60 + val])
            print()
            print("cfig 分区表 (%d bytes):" % val)
            print("  " + table.replace(b"\n", b" | ").decode("latin1"))
    mm.close()


def cmd_sha256(pkg):
    f = open(pkg, "rb")
    mm = mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ)
    for name, rec, val, poff, plen in iter_records(mm):
        blob = bytes(mm[poff:poff + plen])
        print("%-8s %s" % (name, hashlib.sha256(blob).hexdigest()))
    mm.close()


def cmd_extract(pkg, outdir):
    os.makedirs(outdir, exist_ok=True)
    f = open(pkg, "rb")
    mm = mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ)
    for name, rec, val, poff, plen in iter_records(mm):
        blob = bytes(mm[poff:poff + plen])
        safe = "".join(c for c in name if c.isalnum() or c in "_-")
        out = os.path.join(outdir, safe + ".bin")
        with open(out, "wb") as g:
            g.write(blob)
        print("%-8s -> %s (%d bytes)" % (name, out, plen))
    mm.close()


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 1
    cmd, pkg = sys.argv[1], sys.argv[2]
    if cmd == "list":
        cmd_list(pkg)
    elif cmd == "sha256":
        cmd_sha256(pkg)
    elif cmd == "extract":
        cmd_extract(pkg, sys.argv[3])
    else:
        print(__doc__)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
