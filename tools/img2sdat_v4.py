#!/usr/bin/env python3
"""Generate Android/MTK block-OTA v4 files from a raw system image:
   system.transfer.list + system.new.dat
Format: <cmd> N,s1,e1,s2,e2,...  ranges are (start,end) exclusive block ranges.
Non-zero block runs -> 'new' (data appended to .dat in order), zero runs -> 'zero'.
Usage: img2sdat_v4.py system.img out_prefix [block_size=4096]
"""
import sys, os

def main():
    img, prefix = sys.argv[1], sys.argv[2]
    bs = int(sys.argv[3]) if len(sys.argv) > 3 else 4096
    size = os.path.getsize(img)
    assert size % bs == 0, f"image size {size} not multiple of {bs}"
    nblocks = size // bs

    ZERO_CHUNK = b'\x00' * bs
    new_ranges = []
    zero_ranges = []
    dat = open(prefix + '.new.dat', 'wb')
    new_total = 0
    zero_total = 0
    with open(img, 'rb') as f:
        cur_kind = None
        cur_start = 0
        for i in range(nblocks):
            blk = f.read(bs)
            kind = 'zero' if blk == ZERO_CHUNK else 'new'
            if kind != cur_kind:
                if cur_kind == 'new':
                    new_ranges.append((cur_start, i))
                    new_total += i - cur_start
                elif cur_kind == 'zero':
                    zero_ranges.append((cur_start, i))
                    zero_total += i - cur_start
                cur_kind = kind
                cur_start = i
            if kind == 'new':
                dat.write(blk)
        if cur_kind == 'new':
            new_ranges.append((cur_start, nblocks))
            new_total += nblocks - cur_start
        else:
            zero_ranges.append((cur_start, nblocks))
            zero_total += nblocks - cur_start
    dat.close()

    total = new_total + zero_total
    with open(prefix + '.transfer.list', 'w') as tl:
        tl.write("4\n")
        tl.write(f"{total}\n")
        tl.write("0\n")
        tl.write("0\n")
        nvals = [str(x) for r in new_ranges for x in r]
        tl.write("new " + str(len(nvals)) + "," + ",".join(nvals) + "\n")
        zvals = [str(x) for r in zero_ranges for x in r]
        tl.write("zero " + str(len(zvals)) + "," + ",".join(zvals) + "\n")
    print(f"image: {nblocks} blocks | new: {len(new_ranges)} ranges/{new_total} blocks"
          f" | zero: {len(zero_ranges)} ranges/{zero_total} blocks | header total: {total}")
    print(f"dat size: {os.path.getsize(prefix + '.new.dat')} (expected {new_total * bs})")

if __name__ == '__main__':
    main()
