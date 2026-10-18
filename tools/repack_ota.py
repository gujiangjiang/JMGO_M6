#!/usr/bin/env python3
"""Rebuild + sign an OTA zip (JMGO/M6 style) with a modified system image.
 - generates system.transfer.list / system.new.dat (v4) from the new image
 - keeps every other entry (boot.img, firmware bins, updater-script, ...) as in the original
 - whole-file signs like SignApk -w
Usage: repack_ota.py origin.zip system.img out.zip testkey.pk8 testkey.x509.pem
"""
import sys, os, struct, subprocess, zipfile, shutil
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import img2sdat_v4
import signzip

def main():
    orig, img, out, pk8, pem = sys.argv[1:6]
    stage = out + '.stage'
    os.makedirs(stage, exist_ok=True)
    prefix = os.path.join(stage, 'system')
    sys.argv = ['img2sdat_v4.py', img, prefix]
    img2sdat_v4.main()

    unsigned = out + '.unsigned'
    zin = zipfile.ZipFile(orig)
    replace = {
        'system.new.dat': prefix + '.new.dat',
        'system.transfer.list': prefix + '.transfer.list',
    }
    with zipfile.ZipFile(unsigned, 'w') as z:
        for info in zin.infolist():
            if info.filename in ('META-INF/MANIFEST.MF', 'META-INF/CERT.SF', 'META-INF/CERT.RSA'):
                continue
            ni = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            ni.compress_type = info.compress_type
            ni.external_attr = info.external_attr
            src_path = replace.get(info.filename)
            with z.open(ni, 'w') as dst:
                if src_path:
                    with open(src_path, 'rb') as f:
                        shutil.copyfileobj(f, dst, 1 << 20)
                else:
                    with zin.open(info) as f:
                        shutil.copyfileobj(f, dst, 1 << 20)
    zin.close()
    signzip.main.__globals__['sys'].argv = ['signzip.py', unsigned, out, pk8, pem]
    signzip.main()
    os.remove(unsigned)
    os.remove(prefix + '.new.dat')
    os.remove(prefix + '.transfer.list')
    os.rmdir(stage)

if __name__ == '__main__':
    main()
