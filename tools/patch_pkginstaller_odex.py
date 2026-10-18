#!/usr/bin/env python3
"""Patch the JMGO M6 PackageInstaller odex to remove the 10-app install limit.

Background (see docs/install-limit.md):
  /priv-app/PackageInstaller is a JMGO ("Holatek") fork of AOSP PackageInstaller.
  HolatekPackageInstallerActivity.processPackageUri() calls canInstallApp(),
  which blocks installs (UI path only; adb bypasses) when the SharedPreferences
  list ("apps"/"list") holds >= 10 still-installed package names.

  Compiled call site (build 1.0.53, compiler-filter=speed):
    0x48927c  adrp/ldr   -> load canInstallApp entrypoint
    0x489288  blr x30    -> call it (kept, side effects preserved)
    0x48928c  cbnz w0,#+0xe0  -> if (ok) continue install
  Patch: make that branch unconditional (single 4-byte change).

Usage:
  patch_pkginstaller_odex.py <input.odex> <output.odex>
  patch_pkginstaller_odex.py --in-img <system.img>     # patch in place
"""
import sys, os, hashlib
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ext4 import Ext4

OFFSET = 0x48928C
OLD = bytes.fromhex('00070035')   # cbnz w0, #+0xe0
NEW = bytes.fromhex('38000014')   # b #+0xe0
CTX_OFFSET = 0x48927C
CTX = bytes.fromhex('c00300f0009840f91e1840f9c0033fd6')  # adrp;ldr;ldr x30;blr x30
IMG_PATH = '/priv-app/PackageInstaller/oat/arm64/PackageInstaller.odex'


def patch_bytes(d: bytearray):
    if bytes(d[CTX_OFFSET:CTX_OFFSET + len(CTX)]) != CTX:
        sys.exit("context mismatch at 0x48927c — not the expected build (1.0.53)? aborting")
    if bytes(d[OFFSET:OFFSET + 4]) != OLD:
        sys.exit("branch bytes mismatch — already patched or unexpected build")
    d[OFFSET:OFFSET + 4] = NEW
    return d


def patch_image(img):
    fs = Ext4(img)
    ino = fs.resolve(IMG_PATH)
    if ino is None:
        sys.exit(f"not found in image: {IMG_PATH}")
    data = bytearray(fs.read_file(ino))
    patch_bytes(data)
    bs = fs.block_size
    mapping = {}
    for start, phys, length in fs.file_blocks(ino):
        for k in range(length):
            mapping[start + k] = phys + k
    lb, within = divmod(OFFSET, bs)
    phys = mapping.get(lb)
    if phys is None:
        sys.exit("logical block not mapped")
    with open(img, 'r+b') as f:
        f.seek(phys * bs + within)
        cur = f.read(4)
        if cur != OLD:
            sys.exit(f"on-disk bytes mismatch: {cur.hex()}")
        f.seek(phys * bs + within)
        f.write(NEW)
    print(f"in-image patch done: {img} :: {IMG_PATH} @ 0x{OFFSET:x}")


def main():
    if len(sys.argv) == 3 and sys.argv[1] == '--in-img':
        patch_image(sys.argv[2])
        return
    if len(sys.argv) != 3:
        print(__doc__); sys.exit(1)
    src, dst = sys.argv[1], sys.argv[2]
    d = bytearray(open(src, 'rb').read())
    sha_before = hashlib.sha256(d).hexdigest()
    d = patch_bytes(d)
    open(dst, 'wb').write(d)
    print(f"input : {src} sha256={sha_before}")
    print(f"output: {dst} sha256={hashlib.sha256(d).hexdigest()}")
    print(f"change: 0x{OFFSET:x}: {OLD.hex()} -> {NEW.hex()} (cbnz -> b, install always allowed)")


if __name__ == '__main__':
    main()
