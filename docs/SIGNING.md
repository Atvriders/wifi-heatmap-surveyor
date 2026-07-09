# Release signing

Tagged `v*` releases are signed with a stable upload key so users can upgrade in
place. The key material lives only in GitHub Actions secrets, never in git.

## Secrets (already configured)

| Secret | Purpose |
|--------|---------|
| `KEYSTORE_BASE64` | base64 of the PKCS12 release keystore |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias (`upload`) |
| `KEY_PASSWORD` | key password (same as keystore password) |

The publish job decodes `KEYSTORE_BASE64` to a temp file and signs `assembleRelease`
with it. Non-tag pushes without the secret fall back to debug signing (still
installable). **Tag builds hard-fail if the keystore secret is missing** — a published
release is never allowed to ship debug-signed, because the ephemeral per-runner debug
key changes every build and would break in-place upgrades.

## Backing up the key

The keystore (`release.keystore`), its base64 (`keystore.b64`) and
`SIGNING-CREDENTIALS.txt` are all git-ignored. Keep a copy **outside the repo** —
losing the key means future updates can no longer be installed over an existing install.

## Rotating / regenerating

```
keytool -genkeypair -v -keystore release.keystore -storetype PKCS12 \
  -alias upload -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "<pass>" -keypass "<pass>" \
  -dname "CN=WiFi Heatmap Surveyor, OU=Atvriders, O=Atvriders, C=US"
base64 -w0 release.keystore   # → set as KEYSTORE_BASE64
```
Changing the key breaks in-place upgrades for anyone on the old key.
