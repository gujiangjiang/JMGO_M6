#!/usr/bin/env python3
"""Verify an OTA zip the way Android recovery does:
  - whole-file SignApk(-w) signature: sha1(file[0 : size-comment_size-2]) RSA-SHA1
  - signer cert public key must match provided key (testkey.x509.pem by default)
  - META-INF/MANIFEST.MF entry digests for every entry
  - CERT.SF self-consistency (SHA1-Digest-Manifest + per-section digests)
Usage: verify_ota.py update.zip [expected.x509.pem]
"""
import sys, struct, hashlib, base64, subprocess, zipfile, tempfile, os

def tlv_parse(b, off=0):
    tag = b[off]; l = b[off + 1]; o = off + 2
    if l & 0x80:
        n = l & 0x7f
        l = int.from_bytes(b[o:o + n], 'big'); o += n
    return tag, o, l

def find_pkcs7(comment):
    sig_magic = bytes.fromhex('06092a864886f70d010702')
    i = comment.find(sig_magic)
    while i > 0:
        start = i - 4  # 30 82 <len16> then OID tag/len
        if start >= 0 and comment[start] == 0x30 and comment[start + 1] == 0x82:
            t, o, l = tlv_parse(comment, start)
            if comment[o:o + len(sig_magic)] == sig_magic and o + l == len(comment) - 6:
                return comment[start:o + l]
        i = comment.find(sig_magic, i + 1)
    return None

def parse_pkcs7_cert_and_sig(p7):
    t, o, l = tlv_parse(p7)             # ContentInfo
    t, o2, l2 = tlv_parse(p7, o)        # OID
    t, o3, l3 = tlv_parse(p7, o2 + l2)  # [0] signedData
    t, p, l = tlv_parse(p7, o3)         # SEQUENCE signedData (p = content start)
    # version
    t, cp, ll = tlv_parse(p7, p); p = cp + ll
    # digestAlgorithms
    t, cp, ll = tlv_parse(p7, p); p = cp + ll
    # contentInfo
    t, cp, ll = tlv_parse(p7, p); p = cp + ll
    # certificates [0]
    t, cp, ll = tlv_parse(p7, p)
    cert_start = cp
    t2, cp2, ll2 = tlv_parse(p7, cp)
    cert = p7[cert_start:cp2 + ll2]
    p = cp + ll
    # signerInfos SET
    t, cp, ll = tlv_parse(p7, p)
    # first signerInfo SEQUENCE
    t, si, sil = tlv_parse(p7, cp)
    sig = None
    q = si
    end = si + sil
    while q < end:
        tt, qq, ql = tlv_parse(p7, q)
        if tt == 0x04 and ql in (256, 512):
            sig = p7[qq:qq + ql]
        q = qq + ql
    return cert, sig

def rsa_sha1_verify(pub_pem, content_path, sig):
    tmp = tempfile.mkdtemp()
    sp = os.path.join(tmp, 's')
    open(sp, 'wb').write(sig)
    r = subprocess.run(['openssl', 'dgst', '-sha1', '-verify', pub_pem, '-signature', sp, content_path],
                       capture_output=True)
    return b'Verified OK' in r.stdout, r.stdout.decode() + r.stderr.decode()

def parse_manifest(mf):
    out = {}
    lines = mf.split(b'\r\n')
    name = None
    for ln in lines:
        if ln.startswith(b' '):
            # continuation
            if cur_key is not None:
                cur_val += ln[1:]
            continue
        if ln == b'':
            continue
        k, _, v = ln.partition(b': ')
        k = k.decode(); v = v.decode()
        if k == 'Name':
            name = v
        elif name and k == 'SHA1-Digest':
            out[name] = v
    return out

def parse_sf(sfd):
    lines = sfd.split(b'\r\n')
    manifest_digest = None
    entries = {}
    name = None
    for ln in lines:
        if ln.startswith(b' '):
            continue
        if ln == b'':
            continue
        k, _, v = ln.partition(b': ')
        k = k.decode(); v = v.decode()
        if k == 'SHA1-Digest-Manifest':
            manifest_digest = v
        elif k == 'Name':
            name = v
        elif name and k == 'SHA1-Digest':
            entries[name] = v
    return manifest_digest, entries

def main():
    zpath = sys.argv[1]
    expected_pem = sys.argv[2] if len(sys.argv) > 2 else 'testkey.x509.pem'
    d = open(zpath, 'rb').read()
    size = len(d)
    i = d.rfind(b'PK\x05\x06')
    clen = struct.unpack_from('<H', d, i + 20)[0]
    if clen == 0:
        print("FAIL: no comment (not a signed whole-file package)")
        return 1
    comment = d[i + 22:i + 22 + clen]
    footer = d[-6:]
    sig_len, magic, csize = struct.unpack('<HHH', footer)
    print(f"comment_size={clen} footer[sig_len={sig_len} magic={magic:#06x} csize={csize}]")
    if magic != 0xffff or csize != clen:
        print("FAIL: footer mismatch")
        return 1
    signed_len = size - clen - 2
    digest = hashlib.sha1(d[:signed_len]).digest()

    p7 = find_pkcs7(comment)
    if p7 is None:
        print("FAIL: cannot locate PKCS7")
        return 1
    cert, sig = parse_pkcs7_cert_and_sig(p7)
    tmp = tempfile.mkdtemp()
    cert_der = os.path.join(tmp, 'c.der'); cert_pem = os.path.join(tmp, 'c.pem')
    open(cert_der, 'wb').write(cert)
    subprocess.run(['openssl', 'x509', '-inform', 'DER', '-in', cert_der, '-out', cert_pem], check=True, capture_output=True)
    pub = os.path.join(tmp, 'pub.pem')
    with open(pub, 'wb') as f:
        subprocess.run(['openssl', 'x509', '-in', cert_pem, '-pubkey', '-noout'], check=True, stdout=f)
    content_path = os.path.join(os.path.dirname(os.path.abspath(zpath)), '_ota_content.bin')
    with open(zpath, 'rb') as f, open(content_path, 'wb') as out:
        left = signed_len
        while left > 0:
            c = f.read(min(1 << 20, left))
            if not c: break
            out.write(c); left -= len(c)
    ok, msg = rsa_sha1_verify(pub, content_path, sig)
    os.remove(content_path)
    print(f"whole-file signature: {'OK' if ok else 'FAIL: ' + msg.strip()}")
    if not ok:
        return 1
    # cert matches expected key
    e1 = subprocess.run(['openssl', 'x509', '-in', cert_pem, '-noout', '-fingerprint', '-sha256'], capture_output=True).stdout
    e2 = subprocess.run(['openssl', 'x509', '-in', expected_pem, '-noout', '-fingerprint', '-sha256'], capture_output=True).stdout
    same = e1 == e2
    print(f"signer cert matches {expected_pem}: {same}")
    if not same:
        return 1
    # manifest checks
    z = zipfile.ZipFile(zpath)
    mf = z.read('META-INF/MANIFEST.MF')
    sfd = z.read('META-INF/CERT.SF')
    man = parse_manifest(mf)
    md, sfe = parse_sf(sfd)
    ok = base64.b64encode(hashlib.sha1(mf).digest()).decode() == md
    print(f"SHA1-Digest-Manifest: {'OK' if ok else 'FAIL'}")
    bad = 0
    for info in z.infolist():
        if info.filename in ('META-INF/MANIFEST.MF', 'META-INF/CERT.SF', 'META-INF/CERT.RSA'):
            continue
        h = base64.b64encode(hashlib.sha1(z.read(info)).digest()).decode()
        if man.get(info.filename) != h:
            print("FAIL digest:", info.filename)
            bad += 1
    # SF per-entry digests
    for name, val in sfe.items():
        sec = b''
        for ln in mf.split(b'\r\n'):
            pass
        # find section
        idx = mf.find(b'Name: ' + name.encode() + b'\r\n')
        if idx < 0:
            print("FAIL: no section for", name); bad += 1; continue
        sec = mf[idx:]
        # cut at blank line
        cut = sec.find(b'\r\n\r\n')
        sec = sec[:cut]
        h = base64.b64encode(hashlib.sha1(sec + b'\r\n\r\n').digest()).decode()
        if h != val:
            print("FAIL sf digest:", name); bad += 1
    print(f"entry digests: {'OK' if bad == 0 else str(bad) + ' BAD'} ({len(man)} entries)")
    return 0 if bad == 0 else 1

if __name__ == '__main__':
    sys.exit(main())
