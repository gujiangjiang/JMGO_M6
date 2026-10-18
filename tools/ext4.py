#!/usr/bin/env python3
"""Minimal ext4 reader (read-only): list dirs, stat, extract files.
Supports extent trees and classic indirect blocks. 64bit feature ok.
"""
import struct, sys, os, posixpath

class Ext4:
    def __init__(self, path):
        self.f = open(path, 'rb')
        sb = self._read(1024, 1024)
        self.inodes_count = struct.unpack_from('<I', sb, 0)[0]
        self.blocks_count = struct.unpack_from('<I', sb, 4)[0]
        self.log_block_size = struct.unpack_from('<I', sb, 24)[0]
        self.blocks_per_group = struct.unpack_from('<I', sb, 32)[0]
        self.inodes_per_group = struct.unpack_from('<I', sb, 40)[0]
        self.first_data_block = struct.unpack_from('<I', sb, 20)[0]
        self.inode_size = struct.unpack_from('<H', sb, 88)[0]
        self.feature_incompat = struct.unpack_from('<I', sb, 96)[0]
        self.desc_size = 32
        if self.feature_incompat & 0x80:
            self.desc_size = max(32, struct.unpack_from('<H', sb, 254)[0])
        self.block_size = 1024 << self.log_block_size
        ngroups = (self.blocks_count + self.blocks_per_group - 1) // self.blocks_per_group
        self.ngroups = ngroups
        # group descriptor table: block after superblock (block 1 when bs>1024)
        gdt_start = (self.first_data_block + 1) * self.block_size if self.block_size > 1024 else 2 * self.block_size
        gdt = self._read(gdt_start, self.desc_size * ngroups)
        self.inode_tables = []
        for g in range(ngroups):
            base = g * self.desc_size
            it_lo = struct.unpack_from('<I', gdt, base + 8)[0]
            it_hi = 0
            if self.desc_size >= 64:
                it_hi = struct.unpack_from('<I', gdt, base + 40)[0]
            self.inode_tables.append(it_lo | (it_hi << 32))

    def _read(self, off, size):
        self.f.seek(off)
        d = self.f.read(size)
        return d

    def read_block(self, b):
        return self._read(b * self.block_size, self.block_size)

    def inode(self, ino):
        g = (ino - 1) // self.inodes_per_group
        idx = (ino - 1) % self.inodes_per_group
        off = self.inode_tables[g] * self.block_size + idx * self.inode_size
        return self._read(off, self.inode_size)

    def inode_info(self, ino):
        i = self.inode(ino)
        mode = struct.unpack_from('<H', i, 0)[0]
        size = struct.unpack_from('<I', i, 4)[0]
        size |= struct.unpack_from('<I', i, 108)[0] << 32
        flags = struct.unpack_from('<I', i, 32)[0]
        i_block = i[40:100]
        return dict(mode=mode, size=size, flags=flags, i_block=i_block,
                    ft=struct.unpack_from('<H', i, 0)[0] >> 12)

    def file_blocks(self, ino):
        info = self.inode_info(ino)
        out = []
        blkbytes = info['i_block']
        if info['flags'] & 0x80000:  # EXT4_EXTENTS_FL
            self._extents(blkbytes, out)
        else:
            ptrs = list(struct.unpack('<15I', blkbytes))
            for p in ptrs[:12]:
                if p:
                    out.append((out[-1][1] if out and out[-1][1] == self.block_size else 0, p, 1))
            def walk_indirect(p, depth, span):
                if not p:
                    return
                b = self.read_block(p)
                n = self.block_size // 4
                if depth == 1:
                    for i in range(n):
                        v = struct.unpack_from('<I', b, i * 4)[0]
                        if v:
                            out.append((span * i, v, 1))
                else:
                    for i in range(n):
                        v = struct.unpack_from('<I', b, i * 4)[0]
                        if v:
                            walk_indirect(v, depth - 1, span * n)
            if ptrs[12]:
                walk_indirect(ptrs[12], 1, 1)
            if ptrs[13]:
                walk_indirect(ptrs[13], 2, 1)
            if ptrs[14]:
                walk_indirect(ptrs[14], 3, 1)
        return out

    def _extents(self, blkbytes, out):
        magic, entries, max_entries, depth, generation = struct.unpack('<HHHHI', blkbytes[:12])
        assert magic == 0xF30A, hex(magic)
        if depth == 0:
            for i in range(entries):
                ee_block, ee_len, ee_start_hi, ee_start_lo = struct.unpack_from('<IHHI', blkbytes, 12 + i * 12)
                if ee_len > 32768:
                    ee_len -= 32768
                out.append((ee_block, ee_start_lo | (ee_start_hi << 32), ee_len))
        else:
            for i in range(entries):
                ei_block, ei_leaf_lo, ei_leaf_hi, _ = struct.unpack_from('<IIHH', blkbytes, 12 + i * 12)
                leaf = self.read_block(ei_leaf_lo | (ei_leaf_hi << 32))
                self._extents(leaf, out)

    def read_file(self, ino, size=None):
        info = self.inode_info(ino)
        if size is None:
            size = info['size']
        data = bytearray()
        blocks = self.file_blocks(ino)
        # map logical block -> physical
        need = (size + self.block_size - 1) // self.block_size
        mapping = {}
        for start, phys, length in blocks:
            for k in range(length):
                mapping[start + k] = phys + k
        for lb in range(need):
            phys = mapping.get(lb)
            if phys is None:
                chunk = b'\x00' * self.block_size
            else:
                chunk = self.read_block(phys)
            data += chunk
        return bytes(data[:size])

    def list_dir(self, ino):
        info = self.inode_info(ino)
        entries = {}
        blocks = self.file_blocks(ino)
        for start, phys, length in blocks:
            for k in range(length):
                b = self.read_block(phys + k)
                off = 0
                while off + 8 <= self.block_size:
                    inode_num, rec_len, name_len, ftype = struct.unpack_from('<IHBB', b, off)
                    if rec_len == 0 or off + rec_len > self.block_size:
                        break
                    if inode_num != 0 and name_len > 0 and name_len <= rec_len - 8:
                        name = b[off + 8:off + 8 + name_len]
                        if b'\x00' not in name:
                            entries[name.decode('utf-8', 'replace')] = (inode_num, ftype)
                    off += rec_len
        return entries

    def resolve(self, path):
        ino = 2
        for part in path.strip('/').split('/'):
            if not part:
                continue
            d = self.list_dir(ino)
            if part not in d:
                return None
            ino = d[part][0]
        return ino

    def walk(self, path='/', cb=None, _ino=None):
        ino = self.resolve(path) if _ino is None else _ino
        if ino is None:
            return
        info = self.inode_info(ino)
        if info['ft'] == 4:  # dir (S_IFDIR>>12)
            if cb:
                cb(path + '/', info, None)
            for name, (cino, ft) in sorted(self.list_dir(ino).items()):
                if name in ('.', '..'):
                    continue
                cp = (path.rstrip('/') + '/' + name)
                ci = self.inode_info(cino)
                if ci['ft'] == 4:
                    self.walk(cp, cb, cino)
                else:
                    if cb:
                        cb(cp, ci, cino)


def main():
    fs_img = sys.argv[1]
    fs = Ext4(fs_img)
    cmd = sys.argv[2]
    if cmd == 'ls':
        path = sys.argv[3] if len(sys.argv) > 3 else '/'
        ino = fs.resolve(path)
        if ino is None:
            print("not found")
            return
        for name, (cino, ft) in sorted(fs.list_dir(ino).items()):
            st = fs.inode_info(cino)
            kind = {4: 'd', 8: 'f', 10: 'l'}.get(st['ft'], '?')
            print(f"{kind} {st['size']:>10} {name}")
    elif cmd == 'cat':
        ino = fs.resolve(sys.argv[3])
        if ino is None:
            print("not found")
            return
        sys.stdout.buffer.write(fs.read_file(ino))
    elif cmd == 'get':
        ino = fs.resolve(sys.argv[3])
        if ino is None:
            print("not found")
            return
        out = sys.argv[4]
        os.makedirs(os.path.dirname(out) or '.', exist_ok=True)
        with open(out, 'wb') as f:
            f.write(fs.read_file(ino))
        print("wrote", out)
    elif cmd == 'find':
        pat = sys.argv[3].lower()
        def cb(path, info, ino):
            if pat in path.lower():
                print(path, info['size'])
        fs.walk('/', cb)
    elif cmd == 'du':
        total = 0
        count = 0
        def cb(path, info, ino):
            nonlocal total, count
            if info['ft'] != 8:
                total += info['size']
                count += 1
        fs.walk('/', cb)
        print(count, "files,", total, "bytes")

if __name__ == '__main__':
    main()
