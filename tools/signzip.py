#!/usr/bin/env python3
"""Sign an OTA/update zip exactly like AOSP SignApk whole-file mode (-w):
  - rebuilds META-INF/MANIFEST.MF (SHA1-Digest per entry)
  - rebuilds META-INF/CERT.SF  (SHA1-Digest-Manifest + per-section digests)
  - signs SHA1(zip without comment, minus final 2 comment-length bytes) with RSA-SHA1
  - appends comment: "signed by SignApk\0" + PKCS7(detached) + footer
  - patches EOCD comment length (byte-for-byte compatible with SignApk/Android recovery)
Usage: signzip.py in.zip out.zip testkey.pk8 testkey.x509.pem
"""
import sys, os, struct, hashlib, base64, subprocess, zipfile

CHUNK = 1 << 20

def b64(d): return base64.b64encode(d).decode('ascii')

def make72safe(line):
    # exact port of java.util.jar.Manifest.make72Safe
    length = len(line)
    if length > 72:
        index = 70
        while index < length - 2:
            line = line[:index] + '\r\n ' + line[index:]
            index += 72
            length += 3
    return line

def entry_section(name, sha1_digest_b64, header='SHA1-Digest: '):
    line = make72safe('Name: ' + name).encode('utf-8') + b'\r\n'
    line += make72safe(header + sha1_digest_b64).encode('utf-8') + b'\r\n'
    return line

def build_manifest(digests):
    mf = b'Manifest-Version: 1.0\r\nCreated-By: 1.0 (Android SignApk)\r\n\r\n'
    for name, d in digests:
        mf += entry_section(name, b64(d)) + b'\r\n'
    return mf

def build_sf(manifest_bytes, digests):
    sf = b'Signature-Version: 1.0\r\n'
    sf += make72safe('SHA1-Digest-Manifest: ' + b64(hashlib.sha1(manifest_bytes).digest())).encode() + b'\r\n'
    sf += b'Created-By: 1.0 (Android SignApk)\r\n\r\n'
    sections = manifest_bytes.split(b'\r\n\r\n')[1:]
    for sec in sections:
        if not sec.startswith(b'Name: '):
            continue
        # digest of the raw section bytes (unwrapped lookup by parsing joined lines)
        joined = sec.replace(b'\r\n ', b'')
        name = joined.split(b'\r\n', 1)[0][6:].decode('utf-8')
        sf += entry_section(name, b64(hashlib.sha1(sec + b'\r\n\r\n').digest())) + b'\r\n'
    return sf

# ---------- minimal DER ----------
def der_len(n):
    if n < 0x80: return bytes([n])
    b = b''
    while n:
        b = bytes([n & 0xff]) + b
        n >>= 8
    return bytes([0x80 | len(b)]) + b

def tlv(tag, content):
    return bytes([tag]) + der_len(len(content)) + content

def der_int(n):
    b = n.to_bytes((n.bit_length() + 7) // 8 or 1, 'big')
    if b[0] & 0x80: b = b'\x00' + b
    return tlv(0x02, b)

def der_oid(oid_str):
    parts = [int(x) for x in oid_str.split('.')]
    body = bytes([parts[0] * 40 + parts[1]])
    for p in parts[2:]:
        enc = []
        while True:
            enc.append(p & 0x7f)
            p >>= 7
            if not p: break
        enc = enc[::-1]
        enc = [e | 0x80 for e in enc[:-1]] + enc[-1:]
        body += bytes(enc)
    return tlv(0x06, body)

NULL = b'\x05\x00'
OID_SIGNED_DATA = der_oid('1.2.840.113549.1.7.2')
OID_DATA = der_oid('1.2.840.113549.1.7.1')
OID_SHA1 = der_oid('1.3.14.3.2.26')
OID_RSA = der_oid('1.2.840.113549.1.1.1')

def tlv_parse(b, off=0):
    tag = b[off]; l = b[off + 1]; o = off + 2
    if l & 0x80:
        n = l & 0x7f
        l = int.from_bytes(b[o:o + n], 'big'); o += n
    return tag, o, l

def cert_issuer_serial(cert_der):
    t, o, l = tlv_parse(cert_der)
    t, o2, l2 = tlv_parse(cert_der, o)      # tbsCertificate
    q = o2
    t0, q2, lq = tlv_parse(cert_der, q)
    if t0 == 0xA0:
        q = q2 + lq
    ser_start = q
    t, q2, lq = tlv_parse(cert_der, q)
    serial_tlv = cert_der[ser_start:q2 + lq]
    p = q2 + lq
    t, p2, lp = tlv_parse(cert_der, p)       # signature AlgorithmIdentifier
    p = p2 + lp
    iss_start = p
    t, p2, lp = tlv_parse(cert_der, p)       # issuer Name
    issuer_tlv = cert_der[iss_start:p2 + lp]
    return issuer_tlv, serial_tlv

def build_pkcs7(cert_der, signature):
    issuer_tlv, serial_tlv = cert_issuer_serial(cert_der)
    alg_sha1 = tlv(0x30, OID_SHA1 + NULL)
    alg_rsa = tlv(0x30, OID_RSA + NULL)
    signed_data = tlv(0x30,
        der_int(1) +
        tlv(0x31, alg_sha1) +
        tlv(0x30, OID_DATA) +
        tlv(0xA0, cert_der) +
        tlv(0x31, tlv(0x30,
            der_int(1) + tlv(0x30, issuer_tlv + serial_tlv) + alg_sha1 + alg_rsa +
            tlv(0x04, signature))))
    return tlv(0x30, OID_SIGNED_DATA + tlv(0xA0, signed_data))

def digest_entries(zin):
    out = []
    for info in zin.infolist():
        if info.filename in ('META-INF/MANIFEST.MF', 'META-INF/CERT.SF', 'META-INF/CERT.RSA'):
            continue
        h = hashlib.sha1()
        with zin.open(info) as f:
            while True:
                c = f.read(CHUNK)
                if not c: break
                h.update(c)
        out.append((info, h.digest()))
    return out

def main():
    in_zip, out_zip, pk8, pem = sys.argv[1:5]
    zin = zipfile.ZipFile(in_zip)
    digests = digest_entries(zin)
    mf = build_manifest([(i.filename, d) for i, d in digests])
    sf = build_sf(mf, digests)

    tmp = out_zip + '.tmp'
    with zipfile.ZipFile(tmp, 'w') as z:
        for info, _ in digests:
            ni = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            ni.compress_type = info.compress_type
            ni.external_attr = info.external_attr
            with zin.open(info) as src, z.open(ni, 'w') as dst:
                while True:
                    c = src.read(CHUNK)
                    if not c: break
                    dst.write(c)
        zi = zipfile.ZipInfo('META-INF/MANIFEST.MF')
        z.writestr(zi, mf)
        zi = zipfile.ZipInfo('META-INF/CERT.SF')
        z.writestr(zi, sf)
    zin.close()

    # sign sha1 of file except last 2 bytes (EOCD comment length field)
    data = open(tmp, 'rb').read()
    assert data[-22:-18] == b'PK\x05\x06', "no EOCD"
    assert struct.unpack_from('<H', data, len(data) - 2)[0] == 0, "comment must be empty"
    del data
    key_pem = tmp + '.key.pem'
    subprocess.run(['openssl', 'pkcs8', '-inform', 'DER', '-in', pk8, '-nocrypt', '-out', key_pem],
                   check=True, capture_output=True)
    # make a copy without the last 2 bytes, sign that whole file (SHA1withRSA)
    size = os.path.getsize(tmp)
    subprocess.run(['dd', 'if=' + tmp, 'of=' + tmp + '.content', 'bs=1', 'count=' + str(size - 2),
                    'status=none'], check=True)
    subprocess.run(['openssl', 'dgst', '-sha1', '-sign', key_pem, '-out', tmp + '.sig', tmp + '.content'],
                   check=True, capture_output=True)
    sig = open(tmp + '.sig', 'rb').read()

    subprocess.run(['openssl', 'x509', '-in', pem, '-outform', 'DER', '-out', tmp + '.cert.der'],
                   check=True, capture_output=True)
    cert_der = open(tmp + '.cert.der', 'rb').read()
    pkcs7 = build_pkcs7(cert_der, sig)

    prefix = b'signed by SignApk\x00'
    footer = struct.pack('<HHH', len(pkcs7) + 6, 0xffff, len(prefix) + len(pkcs7) + 6)
    comment = prefix + pkcs7 + footer

    with open(out_zip, 'wb') as f:
        with open(tmp + '.content', 'rb') as f2:
            while True:
                c = f2.read(CHUNK)
                if not c: break
                f.write(c)
        f.write(struct.pack('<H', len(comment)))
        f.write(comment)
    for p in (tmp, tmp + '.content', tmp + '.key.pem', tmp + '.sig', tmp + '.cert.der'):
        os.remove(p)
    print(f"signed: {out_zip} (comment {len(comment)} bytes, pkcs7 {len(pkcs7)} bytes)")

if __name__ == '__main__':
    main()
