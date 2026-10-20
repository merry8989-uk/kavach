# Keystore (PRIVATE — never commit)

This folder is where the **release signing keystore** lives on your machine and
in CI. The keystore is deliberately **git-ignored** (see the root `.gitignore`);
only this README is committed.

## Why the keystore matters

Android identifies an app by its signing key, not its name. To let existing
users **update in place without uninstalling**, every future release must be
signed with the **same key**. Lose it and you can never update that app listing
again — you'd have to publish a new app under a new package name.

## Files you should have here (locally / in CI only)

| File | Purpose | Committed? |
|---|---|---|
| `kavach-release.jks` | The signing keystore (PKCS12) | ❌ never |
| `keystore.properties` | Points Gradle at the keystore + passwords | ❌ never |

`keystore.properties` looks like:

```properties
storeFile=keystore/kavach-release.jks
storeType=PKCS12
storePassword=********
keyAlias=kavach
keyPassword=********
```

## Where the secrets live for CI

The release workflow reads four GitHub repository secrets — set them under
**Settings → Secrets and variables → Actions**:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 keystore/kavach-release.jks` |
| `KEYSTORE_PASSWORD` | the store password |
| `KEY_ALIAS` | `kavach` |
| `KEY_PASSWORD` | the key password |

Generate the base64 on macOS/Linux:

```bash
base64 -w0 keystore/kavach-release.jks > keystore.b64
```

## Back it up

Store `kavach-release.jks` + its passwords in a password manager (and an
offline copy). If you enable **Play App Signing**, Google holds the app signing
key and this keystore becomes your *upload* key — still keep it safe.

## Replacing the key

Only replace it before your first public release, or if you deliberately start
a new app listing. Replacing it after release breaks in-place updates.
