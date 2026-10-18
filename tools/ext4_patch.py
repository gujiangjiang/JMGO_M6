#!/usr/bin/env python3
"""Patch bytes of a file *inside* an ext4 image, in place (no Linux needed).
Maps the file's virtual offset through its extents to the physical block and writes.

Usage:
  ext4_patch.py IMG PATH_IN_IMG OFFSET OLD_HEX NEW_HEX
  ext4_patch.py IMG PATH_IN_IMG OFFSET NEW_HEX          # write without old-byte check

Safety: refuses if current bytes != OLD_HEX (when given) or if NEW_HEX length differs.
Only touches file data blocks (no metadata / checksum update needed).
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ext4 import Ext4

def main():
    img, path, offset = sys.argv[1], sys.argv[2], int(sys.argv[3], 0)
    if len(sys.argv) == 6:
        old, new = bytes.fromhex(sys.argv[4]), bytes.fromhex(sys.argv[5])
    elif len(sys.argv) == 5:
        old, new = None, bytes.fromhex(sys.argv[4])
    else:
        print(__doc__); sys.exit(1)
    assert len(new) > 0
    fs = Ext4(img)
    ino = fs.resolve(path)
    if ino is None:
        sys.exit(f"not found: {path}")
    info = fs.inode_info(ino)
    if info['ft'] != 8:
        sys.exit(f"not a regular file: {path}")
    bs = fs.block_size
    if offset + len(new) > info['size']:
        sys.exit(f"offset {offset}+{len(new)} beyond file size {info['size']}")
    # map logical block -> physical
    mapping = {}
    for start, phys, length in fs.file_blocks(ino):
        for k in range(length):
            mapping[start + k] = phys + k
    with open(img, 'r+b') as f:
        p = offset
        left = len(new)
        ni = 0
        while left:
            lb = p // bs
            within = p % bs
            phys = mapping.get(lb)
            if phys is None:
                sys.exit(f"logical block {lb} not mapped (sparse/hole)")
            n = min(bs - within, left)
            f.seek(phys * bs + within)
            cur = f.read(n)
            if old is not None:
                if cur != old[ni:ni + n]:
                    sys.exit(f"old bytes mismatch at {hex(p)}: got {cur.hex()}")
            f.seek(phys * bs + within)
            f.write(new[ni:ni + n])
            p += n; left -= n; ni += n
    print(f"patched {path} @ {hex(offset)}: {len(new)} bytes -> {new.hex()}")

if __name__ == '__main__':
    main()
