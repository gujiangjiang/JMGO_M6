#!/usr/bin/env python3
"""Minimal Android binary XML (AXML) parser: dump element tree with attributes.
Good enough to read AndroidManifest.xml metadata (package, components, intents).
"""
import struct, sys, zipfile

class AXML:
    def __init__(self, data):
        self.d = data
        self.strings = []
        self.resids = []

    def u16(self, o): return struct.unpack_from('<H', self.d, o)[0]
    def u32(self, o): return struct.unpack_from('<I', self.d, o)[0]

    def parse(self):
        magic, size = self.u32(0), self.u32(4)
        assert magic == 0x00080003, hex(magic)
        self.parse_strings(8)
        off = 8 + self.sp_size
        assert self.u16(off) == 0x0180
        res_size = self.u32(off + 4)
        n = (res_size - 8) // 4
        self.resids = [self.u32(off + 8 + i * 4) for i in range(n)]
        off += res_size
        out = []
        self.stack = []
        while off < len(self.d):
            typ, hsz, csz = self.u16(off), self.u16(off + 2), self.u32(off + 4)
            if csz == 0: break
            if typ == 0x0102:
                line, ns, name = self.u32(off + 8), self.u32(off + 16), self.u32(off + 20)
                attr_start, attr_size, attr_count = self.u16(off + 24), self.u16(off + 26), self.u16(off + 28)
                attrs = []
                ao = off + 16 + attr_start
                for i in range(attr_count):
                    a_ns, a_name, a_raw, a_typ, a_data = struct.unpack_from('<IIIiI', self.d, ao)
                    ao += 20
                    name_s = self.attr_name(a_name)
                    if a_raw != 0xFFFFFFFF:
                        val = self.string(a_raw)
                    else:
                        if a_typ == 0x10: val = a_data
                        elif a_typ == 0x12: val = bool(a_data)
                        elif a_typ == 0x11: val = hex(a_data)
                        else: val = a_data
                    attrs.append((name_s, val))
                self.stack.append((self.string(name), attrs))
            elif typ == 0x0103:
                if self.stack:
                    name, attrs = self.stack.pop()
                    out.append((len(self.stack), name, attrs))
            off += csz
        return out

    def parse_strings(self, off):
        typ, hsz, csz = self.u16(off), self.u16(off + 2), self.u32(off + 4)
        assert typ == 0x0001
        count, style_count, flags, str_start = self.u32(off + 8), self.u32(off + 12), self.u32(off + 16), self.u32(off + 20)
        utf8 = bool(flags & 0x100)
        offs = [self.u32(off + hsz + i * 4) for i in range(count)]
        base = off + str_start
        for o in offs:
            p = base + o
            if utf8:
                # two lengths: chars, bytes (each u8, or u16 if high bit)
                n = self.d[p]
                if n & 0x80:
                    n = ((n & 0x7f) << 8) | self.d[p + 1]; p += 2
                else: p += 1
                nb = self.d[p]
                if nb & 0x80:
                    nb = ((nb & 0x7f) << 8) | self.d[p + 1]; p += 2
                else: p += 1
                s = self.d[p:p + nb].decode('utf-8', 'replace')
            else:
                n = self.u16(p); p += 2
                if n & 0x8000:
                    n = ((n & 0x7fff) << 16) | self.u16(p); p += 2
                s = self.d[p:p + n * 2].decode('utf-16-le', 'replace')
            self.strings.append(s)
        self.sp_size = csz

    def string(self, i):
        return self.strings[i] if 0 <= i < len(self.strings) else f"@str{i}"

    ANDROID_ATTRS = {
        0x01010000: 'android:theme', 0x01010001: 'android:label', 0x01010002: 'android:icon',
        0x01010003: 'android:name', 0x01010006: 'android:permission', 0x01010009: 'android:protectionLevel',
        0x0101000b: 'android:sharedUserId', 0x0101000e: 'android:enabled', 0x01010010: 'android:exported',
        0x01010011: 'android:process', 0x0101001d: 'android:priority', 0x0101020c: 'android:minSdkVersion',
        0x0101021b: 'android:versionCode', 0x0101021c: 'android:versionName', 0x01010270: 'android:targetSdkVersion',
        0x010102ce: 'android:installLocation', 0x0101026c: 'android:required', 0x01010018: 'android:authorities',
        0x01010004: 'android:manageSpaceActivity', 0x01010021: 'android:grantUriPermissions',
        0x01010272: 'android:allowBackup', 0x010103f8: 'android:supportsRtl', 0x01010020: 'android:description',
    }

    def attr_name(self, i):
        s = self.string(i)
        if not s and i < len(self.resids):
            rid = self.resids[i]
            return self.ANDROID_ATTRS.get(rid, f'android:0x{rid:08x}')
        if not s and i > 0 and (i - 1) < len(self.resids):
            rid = self.resids[i - 1]
            return self.ANDROID_ATTRS.get(rid, f'android:0x{rid:08x}')
        return s

def read_manifest(path):
    if path.endswith('.apk'):
        with zipfile.ZipFile(path) as z:
            data = z.read('AndroidManifest.xml')
    else:
        data = open(path, 'rb').read()
    return AXML(data).parse()

def main():
    path = sys.argv[1]
    mode = sys.argv[2] if len(sys.argv) > 2 else 'summary'
    tree = read_manifest(path)
    if mode == 'summary':
        for depth, name, attrs in tree:
            ad = dict(attrs)
            interesting = {}
            for k, v in ad.items():
                if k in ('package', 'android:name', 'android:versionName', 'android:versionCode',
                         'android:label', 'android:sharedUserId', 'android:targetSdkVersion',
                         'android:minSdkVersion', 'android:process', 'android:protectionLevel',
                         'android:exported', 'android:theme', 'android:permission',
                         'android:installLocation', 'android:required', 'android:priority'):
                    interesting[k] = v
            line = '  ' * depth + name
            if interesting:
                line += '  ' + ' '.join(f"{k.replace('android:', '')}={v}" for k, v in interesting.items())
            if name in ('action', 'category', 'meta-data', 'uses-permission', 'service', 'receiver', 'provider', 'activity', 'activity-alias', 'uses-sdk', 'manifest'):
                print(line)
            elif depth < 4:
                print(line)
    else:
        print(tree)

if __name__ == '__main__':
    main()
