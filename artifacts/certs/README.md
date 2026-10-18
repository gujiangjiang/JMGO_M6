# 证书说明

本固件（含 recovery 验签、`/system/etc/security/otacerts.zip`）全部使用 **AOSP 公开测试密钥 testkey**。

## 已入库

| 文件 | 内容 | 说明 |
|---|---|---|
| `testkey.x509.pem` | testkey 公钥证书 | 与包内 `META-INF/com/android/otacert`、`/system/etc/security/otacerts.zip` 中一致（serial `936eacbe07f201df`，CN=Android） |
| `cert_from_otacert.pem` | 同上（从 otacerts.zip 提取） | 冗余备份 |
| `otacerts.zip` | 设备端 `/system/etc/security/otacerts.zip` | app 侧 `RecoverySystem.verifyPackage` 使用 |

指纹核对：

```bash
openssl x509 -in testkey.x509.pem -noout -fingerprint -sha256 -subject -serial
# subject=...CN=Android/emailAddress=android@android.com
# serial=936EACBE07F201DF
```

## 需要自备（不入库）

- **`testkey.pk8`**（私钥，PKCS#8 DER、无口令）：
  - 来源：AOSP 源码树 `build/target/product/security/testkey.pk8`（公开文件，网络上多处镜像可取）；
  - 用途：`tools/signzip.py` / `tools/repack_ota.py` 重签固件；
  - 校验（可选）：用其签名的结果必须能被 `tools/verify_ota.py` + 本目录 `testkey.x509.pem` 验证通过。

```bash
python3 tools/repack_ota.py update_jmgo.zip system_mod.img update_jmgo_mod.zip \
        /path/to/testkey.pk8 artifacts/certs/testkey.x509.pem
python3 tools/verify_ota.py update_jmgo_mod.zip artifacts/certs/testkey.x509.pem
```

## 原理

整包为 SignApk whole-file 签名：`RSA-SHA1(file[0 : size-comment_size-2])`。
详见 [`docs/ota-signature.md`](../../docs/ota-signature.md)。
