# OTA 格式与整包签名逆向（SignApk whole-file）

> 本文记录 `update_jmgo.zip` 的**逐字节级**格式分析结论。所有结论均有实测证据（对官方包原样复现）。
> 是仓库内 `tools/signzip.py` / `tools/verify_ota.py` / `tools/sdat2img.py` 的实现依据。

## 1. 包格式总览

```
update_jmgo.zip
├── system.transfer.list      # block OTA v4（区间语义见 §3）
├── system.new.dat            # 按 new 区间顺序拼接的 raw ext4 数据（1,007,869,952 B）
├── system.patch.dat          # 空（整包升级）
├── boot.img / 各固件 bin
└── META-INF/
    ├── com/android/metadata  # ota-type=BLOCK, pre-device, post-build/timestamp
    ├── com/google/android/{update-binary, updater-script}
    ├── com/android/otacert   # = testkey.x509.pem（PEM）
    ├── MANIFEST.MF           # 每个条目的 SHA1-Digest
    ├── CERT.SF               # MANIFEST 摘要 + 各段摘要
    └── CERT.RSA              # PKCS#7（与实际整包签名为同一结构）
```

## 2. 整包签名（SignApk `-w` whole-file）

### 2.1 布局

ZIP 文件末尾的 EOCD **comment** 字段（本包 1738 字节）结构：

```
"signed by SignApk\0"        (18 B 前缀，非强制但保留)
PKCS#7 DER                    (1714 B，detached SignedData)
footer: 6 B:
  [0:2] sig_len LE16 = len(PKCS7)+6           (=1720)
  [2:4] 0xFFFF（魔数）
  [4:6] comment 总长 LE16                      (=1738，与 EOCD.comment_len 一致)
```

并且 EOCD 的 comment 长度字段（文件最后 2 字节处、也即 EOCD 尾部）在签名**之后**才被回填。

### 2.2 摘要范围（关键！）

```
signed_len = file_size - comment_size - 2
digest     = SHA1(file[0 : signed_len])
signature  = RSA-SHA1-PKCS1v1.5(digest)    # 私钥 = AOSP testkey
```

即：**comment（含 PKCS7 与 footer）以及 EOCD 的 2 字节 comment 长度字段都不参与摘要**。
`verify_ota.py` 与官方包互验通过（详见 §5）。

### 2.3 PKCS#7 结构（DER，detached）

```
ContentInfo { OID signedData(1.2.840.113549.1.7.2),
  [0] SignedData {
    version = 1,
    digestAlgorithms = { sha1(1.3.14.3.2.26)+NULL },
    contentInfo = { OID data(1.2.840.113549.1.7.1) }        # 无内容（detached）
    certificates = [0] { testkey 证书 }
    signerInfos = { SignerInfo {
        version = 1,
        issuerAndSerialNumber = SEQUENCE{ issuer, serial },  # 注意外层 SEQUENCE
        digestAlgorithm = sha1+NULL,
        digestEncryptionAlgorithm = rsaEncryption+NULL,
        encryptedDigest = OCTET STRING(256)
    } }
  }
}
```

`tools/signzip.py` 的 DER 组装结果与官方 PKCS7 **逐字节一致**（以原签名值回填比对通过）。

### 2.4 MANIFEST.MF / CERT.SF

- `MANIFEST.MF`：头两行 `Manifest-Version: 1.0` / `Created-By: 1.0 (Android SignApk)`，随后每条目：
  `Name: <zip内路径>` + `SHA1-Digest: base64(sha1(条目内容))`，行宽按 `java.util.jar.Manifest` 规则折行（>72 字符时从第 70 字符插入 `\r\n `）。
- `CERT.SF`：`Signature-Version: 1.0`、`SHA1-Digest-Manifest:`（= sha1(整个 MANIFEST.MF)）、`Created-By:`，随后每段 `SHA1-Digest:` = sha1(该段字节 + `\r\n\r\n`)。

### 2.5 签名校验（recovery 侧）

recovery 先校验整包签名（§2.2），再按 `MANIFEST.MF` 逐条目校验安装流中的内容；设备端 `/system/etc/security/otacerts.zip`（app 侧 `RecoverySystem` 校验用）即 **testkey**，与整包签名证书一致。**结论：拿到公开的 AOSP `testkey.pk8` 即可重签并被设备接受。**

## 3. transfer.list v4 语义

文件结构：

```
4                       # 版本
256153                  # header total = new 累计 + zero 累计（不含 erase）
0                       # stash entries
0                       # stash blocks
erase 6,66811,97792,... # 命令
new N,s1,e1,s2,e2,...   # N = 后续数值个数（=2×区间数）；区间 (start,end) 左闭右开
zero N,s1,e1,s2,e2,...
```

已验证（`tools/sdat2img.py` 输出）：

| 命令 | 区间数 | 块数 | 数据来源 |
|---|---|---|---|
| new | 2196 | 246062 | **恰好等于 .dat/4096**，按区间顺序顺序读取 |
| zero | 2204 | 10091 | 写零 |
| erase | 3 | 63847 | BLKDISCARD（等同写零） |
| 合计 | | 320000 | = p6 分区全部块 |

- 解析规则（updater 二进制实测反汇编确认）：每行 `<cmd> N,...`，首值必须等于其后数值个数；每对 `(start,end)` 必须 `end>start`；`RangeSet.total = Σ(end-start)`。
- 完成后 updater 断言 `written(new+zero) == header`（256153 = 246062 + 10091 ✓）。

## 4. 官方签名与密钥证据

- `openssl x509`：Subject `CN=Android/O=Android`，serial `93:6e:ac:be:07:f2:01:df`，RSA-2048，有效期 2008–2035 —— 即 AOSP `testkey`。
- 包内 `META-INF/com/android/otacert` 与系统 `/etc/security/otacerts.zip` 内容一致（`artifacts/certs/`）。

## 5. 工具与回归测试记录

| 测试 | 结果 |
|---|---|
| `verify_ota.py update_jmgo.zip` | 整包签名 OK / 证书匹配 / MANIFEST 16 条全过 |
| `signzip.py` PKCS7 组装 vs 官方 CERT.RSA | **逐字节一致**（1714 B） |
| 自签测试包（自建 RSA 密钥）→ 验签 | 通过；篡改任意字节 → 整包签名失败（拒刷） |
| 全尺寸重打包（556 MB，含 1GB dat）签名+验签 | 通过 |
| `img2sdat_v4` 往返（原图 → dat/list → 重建镜像） | `cmp` 逐字节一致 |

## 6. 复现命令

```bash
# 分析官方包签名
python3 tools/verify_ota.py update_jmgo.zip artifacts/certs/testkey.x509.pem

# 重签（需自备 testkey.pk8，公开文件）
python3 tools/signzip.py unsigned.zip signed.zip testkey.pk8 artifacts/certs/testkey.x509.pem
python3 tools/verify_ota.py signed.zip artifacts/certs/testkey.x509.pem
```
