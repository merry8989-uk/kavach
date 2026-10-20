# Releasing Kavach (signed, in-place updates)

Kavach ships signed builds through GitHub Actions so users get updates **without
uninstalling** — the old version upgrades cleanly because every release is
signed with the same key and carries a higher `versionCode`.

## One-time setup

1. **Create the keystore** (or use the one delivered with this repo):

   ```bash
   keytool -genkeypair -v \
     -keystore keystore/kavach-release.jks \
     -alias kavach \
     -keyalg RSA -keysize 2048 -validity 10950 \
     -storetype PKCS12
   ```

2. **Add the four GitHub secrets** (Settings → Secrets and variables → Actions):
   `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
   See `keystore/README.md` for exact values.

3. **Enable GitHub Pages** (Settings → Pages → Source: `main` branch, `/docs`
   folder). The prototype site then lives at
   `https://<your-user>.github.io/<repo>/`.

## Every release

```bash
git tag v0.2.0
git push origin v0.2.0
```

That tag triggers `.github/workflows/release.yml`, which:

1. restores the keystore from secrets,
2. builds a **signed APK + AAB**,
3. bumps `versionCode` from the CI run number (always increasing),
4. publishes a GitHub Release with the artifacts attached.

You can also run it manually: **Actions → Release → Run workflow**, and type a
version name.

## How in-place updates reach users

- **Direct / sideload:** users install the APK from the latest Release. Android
  replaces the previous install because the signature matches and `versionCode`
  is higher.
- **Play Store:** upload the `.aab` to a Play track. Play handles staged rollout
  and delta updates automatically.

## Rules to never break

- Never change `applicationId` (`com.kavach.caller`) after release.
- Never sign a release with a different key.
- Always increase `versionCode` (the workflow does this for you).
