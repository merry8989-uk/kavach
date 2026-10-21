# Kavach — Security & Privacy

## 1. Threat model (what we defend against)

| Threat | Mitigation | Status |
|---|---|---|
| Credential / key leakage via the repo | Keystore git-ignored; only GitHub Secrets hold it; CI deletes it after build | IMPLEMENTED |
| Tampered update APK | `UpdateInstaller` only accepts URLs under `https://github.com/merry8989-uk/kavach/` | IMPLEMENTED |
| Plaintext network traffic | All endpoints are HTTPS; no cleartext config | IMPLEMENTED |
| Local data read by other apps | App-private storage; `allowBackup="false"` | IMPLEMENTED |
| Local data read from a rooted/stolen device | **Not yet encrypted at rest** | NOT_STARTED |
| Address-book exfiltration | Contacts are never uploaded; cloud AI is opt-in and off by default | IMPLEMENTED (no backend exists to leak to) |
| Call-audio exfiltration | Audio stays in app-private storage unless the user enables cloud AI, which then uploads only a size-capped clip | IMPLEMENTED |
| Spoofed identity data | Identity results carry a source + confidence; no name is invented | IN_PROGRESS |
| Spam-report manipulation | Report rate-limiting / abuse detection | BLOCKED (needs backend) |

## 2. Data inventory

| Data | Where it lives | Leaves the device? |
|---|---|---|
| Contacts (name, numbers, company, notes, tags) | Device `ContactsContract` + Room `contacts_enriched` | No |
| Call history | Room `call_log` (mirror of system log) | No |
| SMS bodies | Read on demand from `Telephony.Sms`; **not persisted by Kavach** | No |
| Call recordings (`.wav`) | `filesDir/recordings`, `filesDir/assistant` | Only if cloud AI is on (capped upload) |
| Transcripts + briefs | Room `transcripts` | No |
| Block rules | Room `block_rules` | No |
| Settings | DataStore `kavach_settings` | No |
| Update check | Version only (GitHub Releases API) | Only the HTTP request itself |

## 3. Secrets handling

- **Never committed:** `keystore/*.jks`, `keystore.properties`, `*.p12`, `secrets.properties`, `*.env` (see `.gitignore`).
- Release signing material lives in GitHub Actions secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) and is decoded only inside the release job, then deleted in an `if: always()` step.
- No API keys exist in the app. When the backend arrives, its base URL is a constant and its credentials must come from build config, never from the repo.

## 4. Encryption status (honest)

| Layer | Current state |
|---|---|
| Transport | HTTPS only |
| Room database | **Not encrypted.** `AppModule` has the SQLCipher hook commented out: `.openHelperFactory(SupportFactory(passphrase))` |
| Recordings | **Not encrypted.** Files are app-private but plaintext on disk |
| Preferences | DataStore, app-private, plaintext |
| Keystore (Android) | Not yet used to derive a passphrase |

**This is a known gap and the highest-priority security item.** Closing it means:
generate a random passphrase, wrap it with an Android Keystore key, open Room through
SQLCipher, and encrypt recording files with `EncryptedFile`.

## 5. Privacy principles enforced in code

1. **Offline by default.** Every caller-ID path resolves locally first; cloud is used
   only when the user has explicitly enabled it (`settings.snapshot.cloudAiEnabled`).
2. **Data minimisation.** SMS bodies are read for display and not stored. Call audio
   uploads are capped at 3 MB.
3. **Consent is spoken.** The assistant announces, audibly, that the call is being
   handled by an automated assistant and is being recorded.
4. **No fabricated identity.** Unknown numbers render as "Unknown caller"; the app
   never invents a name or a "verified" badge.
5. **No address-book upload.** There is no code path that sends contacts anywhere.

## 6. Not yet implemented (required before a production release)

- Encrypted local storage (SQLCipher + EncryptedFile).
- Data retention and deletion policy + account-deletion workflow.
- Consent records and export.
- Backup exclusions for sensitive files.
- Dependency vulnerability scanning in CI.
- Server-side authorisation, rate limiting, audit logging (no backend yet).
- Telemetry: intentionally absent, and if added must be opt-in and scrubbed.

## 7. Reporting

Security issues: contact **[@osintgram_io](https://instagram.com/osintgram_io)**.
