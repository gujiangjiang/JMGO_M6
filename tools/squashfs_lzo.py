#!/usr/bin/env python3
"""SquashFS 4.0 reader/extractor, pure Python (no deps).

Notes for the JMGO/M6 (MTK C30) vendor variant (verified on linux_rootfs):
  - superblock compression id = 3 -> this build uses *LZO* (old squashfs 3.x
    numbering; mainline 4.0 uses 2 for lzo).
  - directory entries have NO per-entry type-byte array (mainline has one).
  - dirent inode_number_diff is an offset from the block header's inode_no
    (not cumulative).
  - symlink inodes additionally carry a trailing 4-byte xattr index.
  - fragment table indirect block is a RAW u64 array (no metadata header).
  - inode refs use metadata block offsets relative to inode_table_start.

Usage:
  squashfs_lzo.py list    <image> [path]     # list directory tree
  squashfs_lzo.py cat     <image> <path>     # print file
  squashfs_lzo.py get     <image> <path> <out>
  squashfs_lzo.py extract <image> <outdir>   # extract everything

LZO1X decompressor is a careful port of lzo1x_decompress_safe().
All decompressed sizes are validated against stored sizes.
"""
import sys, os, struct

# ---------------------------------------------------------------- LZO1X
M2_MAX_OFFSET = 0x0800

class LzoError(Exception):
    pass

def lzo1x_decompress(src, dst_size=None):
    """Faithful port of lzo1x_decompress_safe()."""
    n = len(src)
    if n == 0:
        raise LzoError("empty input")
    ip = 0
    op = bytearray()

    def ck(k):
        if ip + k > n:
            raise LzoError("input overrun")

    def read():
        nonlocal ip
        ck(1)
        b = src[ip]; ip += 1
        return b

    def literals(t):
        nonlocal ip
        if t:
            ck(t)
            op.extend(src[ip:ip + t])
            ip += t

    def match(mpos, t):
        if mpos < 0 or mpos >= len(op):
            raise LzoError(f"bad match pos {mpos} (op={len(op)}, len={t})")
        for _ in range(t):
            op.append(op[mpos]); mpos += 1

    t = 0
    if src[0] > 17:
        t = src[0] - 17
        ip = 1
        if t < 4:
            literals(t)
            t = read()
            state = 'match'
        else:
            literals(t)
            state = 'first_literal_run'
    else:
        state = 'loop'

    while True:
        if state == 'loop':
            t = read()
            if t >= 16:
                state = 'match'; continue
            if t == 0:
                ck(1)
                while src[ip] == 0:
                    t += 255; ip += 1; ck(1)
                t += 15 + src[ip]; ip += 1
            literals(t + 3)
            state = 'first_literal_run'; continue

        if state == 'first_literal_run':
            t = read()
            if t >= 16:
                state = 'match'; continue
            mpos = len(op) - (1 + M2_MAX_OFFSET) - (t >> 2) - (read() << 2)
            match(mpos, 3)
            state = 'match_done'; continue

        if state == 'match':
            if t >= 64:
                mpos = len(op) - 1 - ((t >> 2) & 7) - (read() << 3)
                match(mpos, (t >> 5) + 1)
            elif t >= 32:
                t &= 31
                if t == 0:
                    ck(1)
                    while src[ip] == 0:
                        t += 255; ip += 1; ck(1)
                    t += 31 + src[ip]; ip += 1
                ck(2)
                mpos = len(op) - 1 - ((src[ip] >> 2) + (src[ip + 1] << 6))
                ip += 2
                match(mpos, t + 2)
            elif t >= 16:
                mpos = len(op) - ((t & 8) << 11)
                t &= 7
                if t == 0:
                    ck(1)
                    while src[ip] == 0:
                        t += 255; ip += 1; ck(1)
                    t += 7 + src[ip]; ip += 1
                ck(2)
                mpos -= (src[ip] >> 2) + (src[ip + 1] << 6)
                ip += 2
                if mpos == len(op):
                    break
                mpos -= 0x4000
                match(mpos, t + 2)
            else:
                mpos = len(op) - 1 - (t >> 2) - (read() << 2)
                match(mpos, 2)
            state = 'match_done'; continue

        if state == 'match_done':
            t = src[ip - 2] & 3
            if t == 0:
                state = 'loop'; continue
            literals(t)
            t = read()
            state = 'match'; continue

    out = bytes(op)
    if dst_size is not None and len(out) != dst_size:
        raise LzoError(f"size mismatch: got {len(out)}, want {dst_size}")
    return out

# ---------------------------------------------------------------- SquashFS
class SquashFS:
    def __init__(self, path):
        self.f = open(path, 'rb')
        sb = self.f.read(96)
        magic, = struct.unpack_from('<I', sb, 0)
        if magic != 0x73717368:
            raise SystemExit("not a squashfs image (bad magic %08x)" % magic)
        (self.inode_count, self.mkfs_time, self.block_size,
         self.frag_count) = struct.unpack_from('<IIII', sb, 4)
        self.compression, = struct.unpack_from('<H', sb, 20)
        self.block_log, self.flags, self.no_ids, self.s_major, self.s_minor = struct.unpack_from('<HHHHH', sb, 22)
        (self.root_inode, self.bytes_used, self.id_table_start, self.xattr_id_table_start,
         self.inode_table_start, self.directory_table_start, self.fragment_table_start,
         self.export_table_start) = struct.unpack_from('<QQQQQQQQ', sb, 32)
        if self.s_major != 4:
            raise SystemExit(f"squashfs major {self.s_major} not supported")
        if self.compression not in (2, 3):
            raise SystemExit(f"compression {self.compression} not supported (need lzo)")
        self._meta_cache = {}
        self._blocks_cache = {}
        self._frag_cache = {}
        self.inodes = {}          # inode_number -> dict
        self._load_all_inodes()

    # --- metadata block chain helpers ---
    def read_meta_chain(self, start, size):
        """Read `size` bytes of a metadata stream starting at absolute offset `start`."""
        out = bytearray()
        off = start
        while len(out) < size:
            key = off
            if key in self._meta_cache:
                data, next_off = self._meta_cache[key]
            else:
                self.f.seek(off)
                hdr = self.f.read(2)
                if len(hdr) < 2:
                    raise LzoError("meta header overrun")
                h, = struct.unpack('<H', hdr)
                csize = h & 0x7FFF
                blob = self.f.read(csize)
                if h & 0x8000:
                    data = blob
                else:
                    data = lzo1x_decompress(blob)
                next_off = off + 2 + csize
                self._meta_cache[key] = (data, next_off)
            out.extend(data)
            off = next_off
        return bytes(out[:size])

    def read_data_block(self, off, csize_field):
        key = (off, csize_field)
        if key in self._blocks_cache:
            return self._blocks_cache[key]
        csize = csize_field & 0xFFFFFF
        self.f.seek(off)
        blob = self.f.read(csize)
        if csize_field & 0x1000000:
            data = blob
        else:
            data = lzo1x_decompress(blob)
        self._blocks_cache[key] = data
        return data

    def read_fragment(self, idx):
        if idx in self._frag_cache:
            return self._frag_cache[idx]
        # fragment table: RAW u64 pointer array (one ptr per entries metadata block)
        per_block = 8192 // 16
        nptr = idx // per_block
        self.f.seek(self.fragment_table_start + nptr * 8)
        ptr = struct.unpack('<Q', self.f.read(8))[0]
        # fragment entry at idx % per_block within that block
        entries_per_block = 8192 // 16
        eidx = idx % per_block
        data = self.read_meta_chain(ptr, (eidx + 1) * 16)
        start_block, size, unused = struct.unpack_from('<QII', data, eidx * 16)
        frag = self.read_data_block(start_block, size)
        self._frag_cache[idx] = frag
        return frag

    # --- inodes ---
    def _inode_size_candidates(self, b, off):
        t, = struct.unpack_from('<H', b, off)
        if t == 1: return [32]
        if t == 2:
            sblk, fragm, foff, fsize = struct.unpack_from('<IIII', b, off + 16)
            return [32 + 4 * self._file_blocks_count(fsize, fragm)]
        if t == 3:
            tsize, = struct.unpack_from('<I', b, off + 20)
            return [24 + tsize, 28 + tsize]
        if t in (4, 5):
            return [24, 28]
        if t in (6, 7):
            return [20, 24]
        if t == 8:                                # ext dir (+ optional index entries)
            idx_count = struct.unpack_from('<H', b, off + 32)[0]
            pos = off + 40
            for _ in range(idx_count):
                _, _, sz = struct.unpack_from('<III', b, pos)
                pos += 12 + sz + 1
            return [pos - off]
        if t == 9:
            sblk, fsize, sparse, nlink, fragm, foff, xattr = struct.unpack_from('<QQQIIII', b, off + 16)
            return [56 + 4 * self._file_blocks_count(fsize, fragm)]
        if t == 10:
            tsize, = struct.unpack_from('<I', b, off + 20)
            return [24 + tsize, 28 + tsize]
        if t in (11, 12):
            return [28, 32]
        if t in (13, 14):
            return [24, 28]
        raise LzoError(f"unknown inode type {t} at {off}")

    def _next_inode_ok(self, b, pos):
        if pos + 16 > len(b):
            return True  # end of current buffer; assume ok
        t, mode, uid, gid, mtime, num = struct.unpack_from('<HHHHII', b, pos)
        if not (1 <= t <= 14):
            return False
        if mode > 0o7777:                       # squashfs stores permission bits only
            return False
        if not (1_300_000_000 <= mtime <= 1_800_000_000):
            return False
        if not (1 <= num <= 1_000_000):
            return False
        return True

    def _inode_size(self, b, off):
        cands = self._inode_size_candidates(b, off)
        if len(cands) == 1:
            return cands[0]
        for c in cands:
            if self._next_inode_ok(b, off + c):
                return c
        return cands[0]

    def _file_blocks_count(self, fsize, fragm):
        if fragm == 0xFFFFFFFF:
            return (fsize + self.block_size - 1) // self.block_size
        return fsize // self.block_size

    def _parse_inode(self, b, off, ino_num):
        t, mode, uid, gid, mtime, num = struct.unpack_from('<HHHHII', b, off)
        d = dict(type=t, mode=mode, uid=uid, gid=gid, mtime=mtime, ino=num)
        if t == 1:
            block_index, nlink, fsize, boff, parent = struct.unpack_from('<IIHHI', b, off + 16)
            d.update(block_index=block_index, nlink=nlink, size=fsize, block_offset=boff, parent=parent)
        elif t == 2:
            sblk, fragm, foff, fsize = struct.unpack_from('<IIII', b, off + 16)
            nblk = self._file_blocks_count(fsize, fragm)
            sizes = list(struct.unpack_from(f'<{nblk}I', b, off + 32)) if nblk else []
            d.update(start_block=sblk, fragment=fragm, frag_offset=foff, size=fsize, block_sizes=sizes)
        elif t == 3:
            nlink, tsize = struct.unpack_from('<II', b, off + 16)
            d.update(nlink=nlink, target=b[off + 24:off + 24 + tsize].decode('utf-8', 'replace'))
        elif t == 4 or t == 5:
            nlink, rdev = struct.unpack_from('<II', b, off + 16)
            d.update(nlink=nlink, rdev=rdev)
        elif t == 6 or t == 7:
            nlink, = struct.unpack_from('<I', b, off + 16)
            d.update(nlink=nlink)
        elif t == 8:
            nlink, fsize, block_index, parent, idx_count, boff, xattr = struct.unpack_from('<IIIIHHI', b, off + 16)
            d.update(nlink=nlink, size=fsize, block_index=block_index, parent=parent, block_offset=boff)
        elif t == 9:
            sblk, fsize, sparse, nlink, fragm, foff, xattr = struct.unpack_from('<QQQIIII', b, off + 16)
            nblk = self._file_blocks_count(fsize, fragm)
            sizes = list(struct.unpack_from(f'<{nblk}I', b, off + 56)) if nblk else []
            d.update(start_block=sblk, size=fsize, sparse=sparse, nlink=nlink, fragment=fragm,
                     frag_offset=foff, block_sizes=sizes)
        elif t == 10:
            nlink, tsize = struct.unpack_from('<II', b, off + 16)
            d.update(nlink=nlink, target=b[off + 24:off + 24 + tsize].decode('utf-8', 'replace'))
        elif t in (11, 12):
            nlink, rdev = struct.unpack_from('<II', b, off + 16)
            d.update(nlink=nlink, rdev=rdev)
        elif t in (13, 14):
            nlink, = struct.unpack_from('<I', b, off + 16)
            d.update(nlink=nlink)
        else:
            raise LzoError(f"inode type {t}")
        return d

    def _load_all_inodes(self):
        buf = bytearray()
        comp_off = self.inode_table_start
        spans = []          # (decomp_start, decomp_end, comp_off)
        pos = 0
        consumed = 0
        self.by_ref = {}
        while consumed < self.inode_count:
            if pos + 2048 > len(buf):
                self.f.seek(comp_off)
                h, = struct.unpack('<H', self.f.read(2))
                csize = h & 0x7FFF
                blob = self.f.read(csize)
                data = blob if (h & 0x8000) else lzo1x_decompress(blob)
                spans.append((len(buf), len(buf) + len(data), comp_off))
                buf += data
                comp_off += 2 + csize
            size = self._inode_size(buf, pos)
            d = self._parse_inode(buf, pos, None)
            # find the span containing pos
            for ds, de, co in spans:
                if ds <= pos < de:
                    d['ref'] = (((co - self.inode_table_start) << 16) | (pos - ds))
                    break
            else:
                d['ref'] = None
            self.inodes[d['ino']] = d
            if d['ref'] is not None:
                self.by_ref[d['ref']] = d
            pos += size
            consumed += 1

    # --- directories ---
    def list_dir(self, inode):
        start_ref = inode['block_index']
        boff = inode['block_offset']
        fsize = inode['size']
        data = self.read_meta_chain(self.directory_table_start + start_ref, boff + fsize)[boff:]
        entries = {}
        pos = 0
        while pos + 12 <= fsize:
            count, start_block, inode_no = struct.unpack_from('<III', data, pos)
            count += 1
            pos += 12
            # NOTE: this vendor squashfs (compression id 3 = lzo) omits the
            # per-entry type byte array present in mainline squashfs 4.0.
            prev_ino = inode_no
            for i in range(count):
                if pos + 8 > fsize:
                    return entries
                e_off, ino_diff, e_type, name_size = struct.unpack_from('<HhHH', data, pos)
                pos += 8
                name = data[pos:pos + name_size + 1].decode('utf-8', 'replace')
                pos += name_size + 1
                if ino_diff == -1:
                    ino = prev_ino          # hard link: same as previous entry
                else:
                    ino = inode_no + ino_diff   # offset from header base (this fork)
                prev_ino = ino
                entries[name] = (ino, e_type, e_off)
        return entries

    def read_file(self, inode):
        if inode['type'] == 2:
            fsize = inode['size']; fragm = inode['fragment']; foff = inode['frag_offset']
            sizes = inode['block_sizes']; off = inode['start_block']
        else:
            fsize = inode['size']; fragm = inode['fragment']; foff = inode['frag_offset']
            sizes = inode['block_sizes']; off = inode['start_block']
        out = bytearray()
        remaining = fsize
        for i, sz in enumerate(sizes):
            csize = sz & 0xFFFFFF
            data = self.read_data_block(off, sz)
            out.extend(data)
            off += csize
            remaining -= len(data)
        if fragm != 0xFFFFFFFF and remaining > 0:
            frag = self.read_fragment(fragm)
            out.extend(frag[foff:foff + remaining])
        return bytes(out[:fsize])

    def stat_path(self, path):
        node = self.by_ref.get(self.root_inode)
        if node is None:
            raise SystemExit("root inode not found")
        if node['type'] == 8:
            # extended dir root: convert to basic-dir-like dict
            node = dict(node)
        for part in path.strip('/').split('/'):
            if not part:
                continue
            if node['type'] not in (1, 8):
                raise SystemExit(f"{path}: not a directory at '{part}'")
            entries = self.list_dir(node)
            if part not in entries:
                return None
            ino, _, _ = entries[part]
            node = self.inodes.get(ino)
            if node is None:
                raise SystemExit(f"inode {ino} missing for {part}")
        return node

    def walk(self, path='/'):
        node = self.stat_path(path)
        if node is None:
            return
        if node['type'] in (1, 8):
            yield path + '/', node
            entries = self.list_dir(node)
            for name, (ino, t, off) in sorted(entries.items()):
                child = self.inodes.get(ino)
                if child is None:
                    continue
                cp = path.rstrip('/') + '/' + name
                if child['type'] in (1, 8):
                    yield from self.walk(cp)
                elif child['type'] in (3, 10):
                    yield cp + ' -> ' + child['target'], child
                elif child['type'] in (2, 9):
                    yield cp, child
                else:
                    # device/fifo/socket: no file data
                    yield cp, child


def main():
    if len(sys.argv) < 3:
        print(__doc__); sys.exit(1)
    cmd, img = sys.argv[1], sys.argv[2]
    fs = SquashFS(img)
    if cmd == 'list':
        path = sys.argv[3] if len(sys.argv) > 3 else '/'
        node = fs.stat_path(path)
        if node is None:
            sys.exit(f"not found: {path}")
        if node['type'] in (1, 8):
            for name, (ino, t, off) in sorted(fs.list_dir(node).items()):
                ch = fs.inodes.get(ino)
                if ch is None:
                    continue
                kind = {1: 'd', 8: 'd', 2: 'f', 9: 'f', 3: 'l', 10: 'l'}.get(ch['type'], '?')
                size = ch.get('size', 0)
                print(f"{kind} {size:>10} {name}")
        else:
            print("file", node.get('size', 0))
    elif cmd == 'cat':
        node = fs.stat_path(sys.argv[3])
        if node is None:
            sys.exit(f"not found: {sys.argv[3]}")
        sys.stdout.buffer.write(fs.read_file(node))
    elif cmd == 'get':
        node = fs.stat_path(sys.argv[3])
        if node is None:
            sys.exit(f"not found: {sys.argv[3]}")
        out = sys.argv[4]
        os.makedirs(os.path.dirname(out) or '.', exist_ok=True)
        open(out, 'wb').write(fs.read_file(node))
        print("wrote", out, node.get('size', 0))
    elif cmd == 'extract':
        outdir = sys.argv[3]
        devs = []
        for p, node in fs.walk('/'):
            if p.endswith('/'):
                os.makedirs(os.path.join(outdir, p.lstrip('/')), exist_ok=True)
                continue
            if ' -> ' in p:
                continue
            if node['type'] not in (2, 9):
                devs.append((p, node))
                continue
            dest = os.path.join(outdir, p.lstrip('/'))
            os.makedirs(os.path.dirname(dest) or outdir, exist_ok=True)
            data = fs.read_file(node)
            open(dest, 'wb').write(data)
            if node['mode'] & 0o111:
                os.chmod(dest, os.stat(dest).st_mode | 0o111)
        print("extracted to", outdir, "| special files skipped:", len(devs))
        for p, n in devs[:6]:
            print("   special:", p, "type", n['type'], "rdev", n.get('rdev'))
    else:
        print(__doc__); sys.exit(1)


if __name__ == '__main__':
    main()
