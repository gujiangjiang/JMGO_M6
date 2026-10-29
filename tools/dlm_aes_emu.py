#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""dlm_aes_emu.py — 直接在仿真器中执行固件自带的 DLM AES 实现（ARM32/Unicorn）。

用途：
  * 任意 libdtv_common.so 版本的“默认记录密钥”验证 / 加解密（跨固件版本密钥不同！
    默认密钥 = 构建常量(栈保护金丝雀)×4，随每次编译随机变化，见 docs/pkg-format.md）。
  * 不依赖任何密码学假设：跑的就是设备将要执行的同一段机器码。

依赖：python3 + unicorn（pip3 install unicorn）+ llvm-objdump（Xcode CLT 自带）。

用法：
  python3 tools/dlm_aes_emu.py keyscan <lib.so> [<pkg>] [canary_hex]
      验证默认 key（lib 内的金丝雀×4，或指定）能否把 pkg 的常量记录字段
      9bf7ed4fda262a8e58fc63cf38f6e8a9 解出 "reserved mtk inc"。
  python3 tools/dlm_aes_emu.py aes <lib.so> <dec|enc> <key_hex> <iv_hex> <in.bin> <out.bin>
      用该 lib 的 AES 实现做 AES-128-CBC 加/解密（key/iv 16 字节十六进制）。

说明（经实测验证）：
  * dlm_aes(dst, src, len, key, iv, mode)：mode 0=解密、1=加密；
    解密：密文在 dst、明文写入 src；加密：明文在 dst、密文写入 src；
    两者均为标准 AES-128-CBC（其 T 表为字节序变体，但语义等价标准算法）。
"""
import os
import re
import struct
import subprocess
import sys
import tempfile

try:
    from unicorn import *
    from unicorn.arm_const import *
except Exception:
    print("需要 unicorn：pip3 install unicorn", file=sys.stderr)
    raise

LLVM_OBJDUMP = "/Library/Developer/CommandLineTools/usr/bin/llvm-objdump"

MARKER = bytes.fromhex("9bf7ed4fda262a8e58fc63cf38f6e8a9")
MARKER_PLAIN = b"reserved mtk inc"

STACK_BASE = 0x70000000
STACK_SIZE = 0x100000
HEAP_BASE = 0x80000000
HEAP_SIZE = 0x1000000
DATA_BASE = 0x20000000
DATA_SIZE = 0x100000
BSS_END = 0x1c00000
SENTINEL = 0x0BADF004


class Dlm:
    def __init__(self, so_path):
        self.so_path = os.path.abspath(so_path)
        self.so = open(self.so_path, "rb").read()
        self.dis_path = self._ensure_dis()
        self.uc = Uc(UC_ARCH_ARM, UC_MODE_ARM)
        try:
            self.uc.ctl_set_cpu_model(UC_CPU_ARM_CORTEX_A15)
        except Exception:
            pass
        size = max(len(self.so), BSS_END)
        self.uc.mem_map(0, (size + 0xFFF) & ~0xFFF)
        self.uc.mem_write(0, self.so)
        self.uc.mem_map(STACK_BASE, STACK_SIZE)
        self.uc.mem_map(HEAP_BASE, HEAP_SIZE)
        self.uc.mem_map(DATA_BASE, DATA_SIZE)
        self.uc.mem_map(SENTINEL & ~0xFFF, 0x1000)
        self.heap = HEAP_BASE
        self._symbols()
        self._reloc()
        self._plt()
        self.uc.hook_add(UC_HOOK_CODE, self._hook)
        self.dlm_aes_addr = self.syms["dlm_aes"]
        if not self.dlm_aes_addr:
            raise RuntimeError("dlm_aes 符号未找到（动态符号表）")

    # ---- infrastructure ----
    def _ensure_dis(self):
        base = os.path.basename(self.so_path) + ".dis"
        dis = os.path.join(tempfile.gettempdir(), base)
        if not (os.path.exists(dis) and os.path.getmtime(dis) >= os.path.getmtime(self.so_path)):
            subprocess.run([LLVM_OBJDUMP, "-d", "--no-show-raw-insn", self.so_path],
                           stdout=open(dis, "w"), stderr=subprocess.DEVNULL, check=True)
        return dis

    def _symbols(self):
        out = subprocess.run([LLVM_OBJDUMP, "-T", self.so_path],
                             capture_output=True, text=True).stdout
        self.syms = {}
        for ln in out.splitlines():
            m = re.match(r'^([0-9a-f]{8})\s+\S+\s+\S+\s+\S+\s+\S+\s+(\S+)$', ln.strip())
            if m:
                self.syms[m.group(2)] = int(m.group(1), 16)

    def _reloc(self):
        out = subprocess.run([LLVM_OBJDUMP, "-R", self.so_path],
                             capture_output=True, text=True).stdout
        for ln in out.splitlines():
            m = re.match(r'^\s*([0-9a-f]{8})\s+(\S+)\s+(\S+)', ln)
            if not m:
                continue
            off, typ, name = int(m.group(1), 16), m.group(2), m.group(3)
            if typ in ("R_ARM_RELATIVE", "R_ARM_ABS32"):
                continue  # 基址 0，文件内容即加数
            if typ in ("R_ARM_GLOB_DAT", "R_ARM_JUMP_SLOT"):
                if self.syms.get(name):
                    self.uc.mem_write(off, struct.pack("<I", self.syms[name]))

    def _plt(self):
        self.plt = {}
        for m in re.finditer(r'^([0-9a-f]{8}) <([A-Za-z0-9_]+)@plt>:',
                             open(self.dis_path).read(), re.M):
            self.plt[int(m.group(1), 16)] = m.group(2)

    def _hook(self, uc, address, size, ud):
        if address == SENTINEL:
            uc.emu_stop()
            return
        name = self.plt.get(address)
        if name is None:
            return
        r = lambda i: uc.reg_read(i)
        if name == "x_mem_alloc":
            n = r(UC_ARM_REG_R0)
            p = self.heap
            self.heap = (self.heap + n + 0xF) & ~0xF
            uc.reg_write(UC_ARM_REG_R0, p)
        elif name == "x_mem_free":
            uc.reg_write(UC_ARM_REG_R0, 0)
        elif name in ("x_memcpy", "c_memcpy"):
            dst, src, n = r(UC_ARM_REG_R0), r(UC_ARM_REG_R1), r(UC_ARM_REG_R2)
            uc.mem_write(dst, bytes(uc.mem_read(src, n)))
            uc.reg_write(UC_ARM_REG_R0, dst)
        elif name in ("x_memset", "c_memset"):
            dst, v, n = r(UC_ARM_REG_R0), r(UC_ARM_REG_R1), r(UC_ARM_REG_R2)
            uc.mem_write(dst, bytes([v & 0xFF]) * n)
            uc.reg_write(UC_ARM_REG_R0, dst)
        elif name in self.syms and self.syms[name]:
            uc.reg_write(UC_ARM_REG_PC, self.syms[name])
            return
        else:
            # 其余外部函数在 dlm_aes 路径上不涉及；保险起见按 0 返回
            uc.reg_write(UC_ARM_REG_R0, 0)
        uc.reg_write(UC_ARM_REG_PC, r(UC_ARM_REG_LR))

    def call(self, addr, args):
        uc = self.uc
        sp = STACK_BASE + STACK_SIZE - 0x1000
        for v in reversed(args[4:]):
            sp -= 4
            uc.mem_write(sp, struct.pack("<I", v & 0xFFFFFFFF))
        for i, v in enumerate(args[:4]):
            uc.reg_write(UC_ARM_REG_R0 + i, v & 0xFFFFFFFF)
        uc.reg_write(UC_ARM_REG_SP, sp)
        uc.reg_write(UC_ARM_REG_LR, SENTINEL)
        uc.emu_start(addr, SENTINEL, timeout=60 * 1000 * 1000)
        return uc.reg_read(UC_ARM_REG_R0)

    # ---- API ----
    def aes(self, data, key, iv, mode):
        src = DATA_BASE + 0x1000
        dst = DATA_BASE + 0x2000
        kp = DATA_BASE + 0x3000
        ip = DATA_BASE + 0x4000
        self.uc.mem_write(dst, data)          # 输入在 arg0
        self.uc.mem_write(src, b"\0" * len(data))
        self.uc.mem_write(kp, key)
        self.uc.mem_write(ip, iv)
        rc = self.call(self.dlm_aes_addr, [dst, src, len(data), kp, ip, mode])
        if rc != 0:
            raise RuntimeError("dlm_aes rc=%d" % rc)
        return bytes(self.uc.mem_read(src, len(data)))   # 输出在 arg1

    def canary_key(self):
        """从 movw/movt 金丝雀常量推导默认记录密钥（常量×4）。

        DLM 各函数开头的 `movw rX,#imm16; movt rX,#imm16hi` 为编译器
        stack-protector 常量（每次编译随机），DLM 借用它作为默认 AES key 种子的
        “混淆来源”。扫描该配对即可得到任意固件版本的默认密钥。
        """
        movws = {}
        movts = {}
        n = len(self.so) // 4
        for i in range(n):
            off = i * 4
            w = struct.unpack_from("<I", self.so, off)[0]
            if (w & 0xFFF00000) == 0xE3000000:      # MOVW
                rd = (w >> 12) & 0xF
                imm16 = ((w >> 16) & 0xF) << 12 | (w & 0xFFF)
                movws.setdefault(rd, []).append((off, imm16))
            elif (w & 0xFFF00000) == 0xE3400000:    # MOVT
                rd = (w >> 12) & 0xF
                imm16 = ((w >> 16) & 0xF) << 12 | (w & 0xFFF)
                movts.setdefault(rd, []).append((off, imm16))
        # 找一组 movw -> movt（同寄存器、间隔 < 0x40 字节、低位非零）
        for rd, ws in movws.items():
            for woff, wimm in ws:
                if wimm == 0:
                    continue
                for toff, timm in movts.get(rd, []):
                    if 0 < toff - woff < 0x40:
                        canary = (timm << 16) | wimm
                        if canary == 0 or canary == 0xFFFFFFFF:
                            continue
                        return struct.pack("<I", canary) * 4, canary
        return None, None


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 1
    cmd = sys.argv[1]
    lib = sys.argv[2]
    d = Dlm(lib)
    if cmd == "keyscan":
        key, raw = d.canary_key()
        if key is None:
            print("未在 %s 中找到金丝雀常量模式（该版本 DLM 可能改用自定义密钥）" % lib)
            return 2
        print("lib: %s" % lib)
        print("默认记录密钥(canary×4) = %s  (canary=0x%08x)" % (key.hex(), raw))
        # 1) 样本内常量字段（若给了 pkg：取 cfig 记录 +12 处的 16B；否则用已知样本常量）
        marker = MARKER
        if len(sys.argv) > 3 and os.path.exists(sys.argv[3]):
            data = open(sys.argv[3], "rb").read()
            i = data.find(b"cfig\0\0\0\0")
            if i >= 0:
                marker = data[i + 12:i + 28]
                print("pkg 记录常量字段 = %s" % marker.hex())
        out = d.aes(marker, key, b"\0" * 16, 0)
        print("常量字段解密结果 = %r" % out)
        ok = out == MARKER_PLAIN
        print("与 'reserved mtk inc' 一致: %s" % ("是（该 lib 与目标 pkg 匹配）" if ok else "否（不匹配）"))
        if len(sys.argv) > 3 and os.path.exists(sys.argv[3]):
            pkg = open(sys.argv[3], "rb").read(368)
            hdr = d.aes(pkg[:112], key, b"\0" * 16, 0)
            hdr_ok = hdr[4:12] == b"#DH@FiRm"
            print("pkg 头解密魔数检查: %r (期望 '#DH@FiRm') %s" % (hdr[4:12], "OK" if hdr_ok else "FAIL"))
            ok = ok and hdr_ok
        return 0 if ok else 3
    if cmd == "aes":
        _, _, return_ = sys.argv[0:3]
        mode = sys.argv[3]
        key = bytes.fromhex(sys.argv[4])
        iv = bytes.fromhex(sys.argv[5])
        data = open(sys.argv[6], "rb").read()
        out = d.aes(data, key, iv, 0 if mode == "dec" else 1)
        open(sys.argv[7], "wb").write(out)
        print("wrote %s (%d bytes)" % (sys.argv[7], len(out)))
        return 0
    print(__doc__)
    return 1


if __name__ == "__main__":
    sys.exit(main())
