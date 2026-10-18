#!/usr/bin/env python3
"""sdat2img for MTK/AOSP transfer.list v3/v4:
   each command line: <cmd> N,s1,e1,s2,e2,...
   ranges are (start,end) exclusive, in order; data for 'new' comes sequentially from .dat
   'zero' and 'erase' ranges are zeros.
"""
import sys

def parse(transfer_list_path):
    lines = [l.strip() for l in open(transfer_list_path) if l.strip()]
    version = int(lines[0])
    header_total = int(lines[1])
    stash_entries = int(lines[2])
    idx = 4
    if stash_entries:
        # stash section: next line has the block ranges for stashes
        idx += 1
    cmds = []
    for ln in lines[idx:]:
        toks = ln.split(' ')
        cmd = toks[0]
        vals = [int(x) for x in toks[1].split(',')]
        N, rest = vals[0], vals[1:]
        assert N == len(rest), (cmd, N, len(rest))
        pairs = [(rest[i], rest[i+1]) for i in range(0, len(rest), 2)]
        cmds.append((cmd, pairs))
    return version, header_total, cmds

def main(tl, dat, out):
    version, header_total, cmds = parse(tl)
    max_end = max(e for _, pairs in cmds for _, e in pairs)
    datf = open(dat, 'rb')
    with open(out, 'wb') as o:
        o.truncate(max_end * 4096)
        written = 0
        consumed = 0
        for cmd, pairs in cmds:
            if cmd == 'new':
                for s, e in pairs:
                    n = e - s
                    o.seek(s * 4096)
                    for _ in range(n):
                        blk = datf.read(4096)
                        consumed += len(blk)
                        o.write(blk)
                    written += n
            elif cmd in ('zero', 'erase'):
                for s, e in pairs:
                    o.seek(s * 4096)
                    o.write(b'\x00' * ((e - s) * 4096))
            else:
                raise SystemExit("unsupported cmd " + cmd)
    datf.seek(0, 2)
    size = datf.tell()
    print(f"version={version} header_total={header_total} image={max_end} blocks ({max_end*4096} bytes)")
    print(f"new read from dat: {consumed} / {size}" + (" OK" if consumed == size else " MISMATCH"))
    print(f"new blocks written: {written}")

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2], sys.argv[3])
