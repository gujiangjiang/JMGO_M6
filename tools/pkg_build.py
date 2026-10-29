#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""pkg_build.py — DLM 救砖包（upgrade.pkg）装配/改装工具（参考实现）。

⚠️ 重大前提（务必先读 docs/pkg-build.md）：
  1. DLM 默认 AES 密钥 = 编译期“栈保护常量”×4，**每一个固件版本都不同**。
     必须用目标设备当前固件版本对应的 libdtv_common.so 参与打包
     （--lib 参数，内部经 Unicorn 执行该库的真实 AES 代码，见 dlm_aes_emu.py）。
  2. 建议先用 tools/dlm_aes_emu.py keyscan <你的lib> <官方pkg> 验证：
     官方 pkg 能被该 lib 解开（"reserved mtk inc" / "#DH@FiRm" 命中）后再自制。
  3. 文件级 SHA-256 覆盖 [368:EOF] 会重新计算并加密写入文件头；
     但“32B 每记录字段”的语义尚未完全确认：默认保持原样（repack 场景），
     新增组件时填 AES(32×0) 占位——**存在校验不过的风险**，须先用模拟执行
     路径自检（见 pkg-build.md §验证）。

用法示例：
  # 1) 改装：把 boot 换成自编译 boot.img，版本号上调（只接受更新版本）
  python3 tools/pkg_build.py repack --base ../JMGO_M6_upgrade_loader.pkg \
      --lib work/rootfs_x/basic/libdtv_common.so \
      --replace boot=/tmp/my_boot.img \
      --version t-apollo-mp-1600 \
      --out /tmp/custom.pkg

  # 2) 从零装配（组件需齐全；append 块用常规占位；cfig 用样本表）
  python3 tools/pkg_build.py build --lib <lib> \
      --company jmgo --model JmGO_mt5863_64_n --version <ver> \
      --cfig artifacts/pkg/cfig-partition-table.bin \
      --component uenv=... --component misc=... ... --out /tmp/new.pkg

  # 3) 只列出/校验 base 的记录
  python3 tools/pkg_build.py info --base <pkg>
"""
import argparse
import hashlib
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

MARKER_PLAIN = b"reserved mtk inc"
MAGIC = b"#DH@FiRm"

# 常规记录的追加块数据（56B）= "iPAd" + 52×0，见 docs/pkg-format.md §1 实测
APPEND_REGULAR = b"iPAd" + b"\0" * 52


def u32(v):
    return struct.pack("<I", v)


def make_record(dlm, key, iv, name, payload, field32=None, append_data=None,
                is_cfig=False):
    """构造一条完整记录。cfig：60B 头 + 表；其它：60B 头 + 64B iMtK 块 + payload。"""
    if is_cfig:
        hdr = name.encode()[:8].ljust(8, b"\0") + u32(len(payload))
        hdr += dlm.aes(MARKER_PLAIN, key, iv, 1)           # 常量密文
        hdr += field32 if field32 else dlm.aes(b"\0" * 32, key, iv, 1)
        return hdr + payload
    if append_data is None:
        append_data = APPEND_REGULAR
    assert len(append_data) == 56, "追加数据必须 56 字节"
    body = b"iMtK" + u32(0x38) + append_data
    hdr = name.encode()[:8].ljust(8, b"\0") + u32(64 + len(payload))
    hdr += dlm.aes(MARKER_PLAIN, key, iv, 1)
    hdr += field32 if field32 else dlm.aes(b"\0" * 32, key, iv, 1)
    return hdr + body + payload


def build_header(dlm, key, iv, company, model, version, flags):
    h = bytearray(112)
    h[0:4] = company.encode()[:4].ljust(4, b"\0")
    h[4:12] = MAGIC
    h[12:68] = version.encode()[:55].ljust(56, b"\0")
    h[68:76] = MAGIC
    h[76] = flags & 0xFF
    h[80:112] = model.encode()[:31].ljust(32, b"\0")
    return bytes(h)


def read_base(pkg_path):
    """返回 [(name, field32, append56, payload, is_cfig), ...]"""
    data = open(pkg_path, "rb").read()
    const = bytes.fromhex("9bf7ed4fda262a8e58fc63cf38f6e8a9")
    out = []
    pos = 0
    hits = []
    while True:
        i = data.find(const, pos)
        if i < 0:
            break
        hits.append(i)
        pos = i + 1
    for k, o in enumerate(hits):
        rec = o - 12
        name = data[rec:rec + 8].rstrip(b"\0").decode("latin1")
        val = struct.unpack("<I", data[rec + 8:rec + 12])[0]
        field32 = data[o + 16:o + 48]
        if name == "cfig":
            out.append((name, field32, None, data[rec + 60:rec + 60 + val], True))
            continue
        append = data[rec + 68:rec + 68 + 56]
        payload = data[rec + 124:rec + 60 + val]
        out.append((name, field32, append, payload, False))
    return out


def assemble(dlm, key, iv, hdr_plain, records, out_path):
    body = b"".join(records)
    digest = hashlib.sha256(body).digest()
    blob = digest + b"\0" * (256 - 32)          # 后 224B 用途未确认，填 0
    prefix = dlm.aes(hdr_plain, key, iv, 1) + dlm.aes(blob, key, iv, 1)
    assert len(prefix) == 368
    with open(out_path, "wb") as f:
        f.write(prefix)
        f.write(body)
    print("wrote %s (%d bytes)" % (out_path, 368 + len(body)))


def cmd_info(args):
    for name, f32, append, payload, is_cfig in read_base(args.base):
        print("%-8s payload=%-12d is_cfig=%s field32=%s append=%r" %
              (name, len(payload), is_cfig, f32.hex()[:16], (append[:8] if append else b"")))


def cmd_repack(args):
    from dlm_aes_emu import Dlm
    dlm = Dlm(args.lib)
    key = bytes.fromhex(args.key) if args.key else dlm.canary_key()[0]
    if key is None:
        print("无法从 lib 推出密钥，请用 --key 显式指定", file=sys.stderr)
        return 2
    iv = b"\0" * 16
    records = []
    replaced = dict(kv.split("=", 1) for kv in args.replace)
    for name, f32, append, payload, is_cfig in read_base(args.base):
        if name in replaced:
            payload = open(replaced.pop(name), "rb").read()
            print("replace %-8s <- %s (%d bytes)" % (name, "file", len(payload)))
        records.append(make_record(dlm, key, iv, name, payload,
                                   field32=f32, append_data=append, is_cfig=is_cfig))
    if replaced:
        print("以下未找到同名记录: %s" % ", ".join(replaced), file=sys.stderr)
        return 2
    version = args.version or "t-apollo-mp-9999"
    hdr = build_header(dlm, key, iv, args.company, args.model, version, args.flags)
    assemble(dlm, key, iv, hdr, records, args.out)
    return 0


def cmd_build(args):
    from dlm_aes_emu import Dlm
    dlm = Dlm(args.lib)
    key = bytes.fromhex(args.key) if args.key else dlm.canary_key()[0]
    iv = b"\0" * 16
    comps = []
    if args.cfig:
        comps.append(make_record(dlm, key, iv, "cfig", open(args.cfig, "rb").read(),
                                 is_cfig=True))
    for kv in args.component or []:
        name, path = kv.split("=", 1)
        comps.append(make_record(dlm, key, iv, name, open(path, "rb").read()))
    hdr = build_header(dlm, key, iv, args.company, args.model, args.version, args.flags)
    assemble(dlm, key, iv, hdr, comps, args.out)
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("info")
    p.add_argument("--base", required=True)
    p.set_defaults(func=cmd_info)

    p = sub.add_parser("repack")
    p.add_argument("--base", required=True)
    p.add_argument("--lib", required=True)
    p.add_argument("--key", default=None)
    p.add_argument("--replace", action="append", default=[],
                   help="name=path，可多次")
    p.add_argument("--version", default=None)
    p.add_argument("--company", default="jmgo")
    p.add_argument("--model", default="JmGO_mt5863_64_n")
    p.add_argument("--flags", type=lambda x: int(x, 0), default=0x00)
    p.add_argument("--out", required=True)
    p.set_defaults(func=cmd_repack)

    p = sub.add_parser("build")
    p.add_argument("--lib", required=True)
    p.add_argument("--key", default=None)
    p.add_argument("--company", default="jmgo")
    p.add_argument("--model", default="JmGO_mt5863_64_n")
    p.add_argument("--version", required=True)
    p.add_argument("--flags", type=lambda x: int(x, 0), default=0x00)
    p.add_argument("--cfig", default=None)
    p.add_argument("--component", action="append", default=[])
    p.add_argument("--out", required=True)
    p.set_defaults(func=cmd_build)

    args = ap.parse_args()
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
