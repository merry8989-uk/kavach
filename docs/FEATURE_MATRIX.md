# Kavach — Feature Matrix

Status: **NOT_STARTED · IN_PROGRESS · IMPLEMENTED · VERIFIED · BLOCKED**

> **Ten rows below are wrong.** They say `NOT_STARTED` for features that are
> implemented — smart replies, auto-reply, spam SMS filtering, encrypted storage,
> storage quota, motion, Hindi localisation, WorkManager, diagnostics and
> data-saver. See [`PLAN-AUDIT.md`](PLAN-AUDIT.md) for the list with file paths.
> The matrix is kept for the parts it still gets right; the audit is what to trust.
`IMPLEMENTED` = code exists and compiles in CI. `VERIFIED` = exercised on a real
device/emulator. **No feature is VERIFIED** — the build environment has no Android
SDK, emulator or device. See `IMPLEMENTATION_STATUS.md`.

API = minimum Android API the feature needs. Perms/Roles = what it requires.
Backend = whether a server is needed. Privacy = data touched.

| ID | Feature | Status | API | Perms / Roles | Backend | Privacy | Auto tests |
|---|---|---|---|---|---|---|---|
| F1.1 | Dialer keypad | IMPLEMENTED | 24 | CALL_PHONE (for ACTION_CALL) | — | none | — |
| F1.3 | Call placement | IMPLEMENTED | 24 | CALL_PHONE | — | number | — |
| F1.4 | Dual-SIM picker | IMPLEMENTED | 24 | READ_PHONE_STATE | — | SIM metadata | — |
| F1.5 | In-call screen | IMPLEMENTED | 24 | Default-dialer role | — | number, name | — |
| F1.9 | Ongoing call notification | IMPLEMENTED | 24 | POST_NOTIFICATIONS, READ_PHONE_STATE | — | number, name, SIM | — |
| F1.6 | Speed dial / favourites | IMPLEMENTED | 24 | READ_CONTACTS | — | contacts | — |
| F1.10 | Call state fallback monitor | IMPLEMENTED | 24 | READ_PHONE_STATE, READ_CALL_LOG | — | call metadata | — |
| F1.7 | T9 search | IMPLEMENTED | 24 | READ_CONTACTS | — | contacts | — |
| F1.8 | Caller-ID preview | IMPLEMENTED | 24 | READ_CONTACTS | — | number | — |
| F2.1 | Read device contacts | IMPLEMENTED | 24 | READ_CONTACTS | — | full address book (local only) | — |
| F2.2 | Rich profile fields | IMPLEMENTED | 24 | READ/WRITE_CONTACTS | — | contacts + notes | — |
| F2.3 | Tags | IMPLEMENTED | 24 | — | — | local only | — |
| F2.4 | Profile image | IN_PROGRESS | 24 | READ_CONTACTS | — | photo URI (read); no upload | — |
| F2.5 | Merged identity | IN_PROGRESS | 24 | READ_CONTACTS | yes (for crowd data) | number | — |
| F2.6 | Add / edit / delete contact | IMPLEMENTED | 24 | READ/WRITE_CONTACTS | — | contacts | — |
| F2.7 | Contact detail screen | IMPLEMENTED | 24 | READ_CONTACTS | — | contacts | — |
| F2.8 | Import / export (vCard, CSV) | NOT_STARTED | 24 | READ/WRITE_CONTACTS, storage | — | contacts | — |
| F3.1 | Read system call log | IMPLEMENTED | 24 | READ_CALL_LOG | — | call metadata | — |
| F3.2 | Enrich with name/spam/AI | IMPLEMENTED | 24 | READ_CALL_LOG | yes | call metadata | — |
| F3.3 | Filters | IMPLEMENTED | 24 | READ_CALL_LOG | — | — | — |
| F3.4 | Per-entry actions | IMPLEMENTED | 24 | CALL_PHONE, SEND_SMS | — | — | — |
| F3.6 | Local mirror, idempotent sync | IMPLEMENTED | 24 | READ_CALL_LOG | — | call metadata | — |
| F4.1 | Rule store | IMPLEMENTED | 24 | — | — | numbers | RuleMatcherTest |
| F4.2 | Rule types (8) | IMPLEMENTED | 24 | — | — | numbers | RuleMatcherTest |
| F4.3 | Special rules | IMPLEMENTED | 24 | — | — | numbers | RuleMatcherTest |
| F4.4 | Import / export rules | NOT_STARTED | 24 | — | — | numbers | — |
| F4.5 | Crowdsourced top-spam | BLOCKED | 24 | — | yes | numbers | — |
| F5.1 | CallScreeningService reject | IMPLEMENTED | 24 | Call-screening role | — | number | — |
| F5.3 | Block / Silence / Send-to-AI / Allow | IMPLEMENTED | 24 | Call-screening role | — | number | — |
| F5.4 | Per-rule / per-contact action | IN_PROGRESS | 24 | Call-screening role | — | number | — |
| F5.5 | SMS blocking | NOT_STARTED | 24 | SMS role | — | message metadata | — |
| F5.6 | Schedule / DND windows | NOT_STARTED | 24 | Call-screening role | — | — | — |
| F5.7 | Post-call spam prompt | NOT_STARTED | 24 | — | — | — | — |
| F6.1 | SMS threads + conversation | IMPLEMENTED | 24 | READ_SMS | — | message bodies (local) | — |
| F6.2 | Compose + send | IMPLEMENTED | 24 | SEND_SMS | — | message body | — |
| F6.3 | Smart replies | NOT_STARTED | 24 | — | maybe | message body | — |
| F6.4 | Auto-reply rules | NOT_STARTED | 24 | SEND_SMS | — | — | — |
| F6.6 | Spam SMS filter | NOT_STARTED | 24 | SMS role | maybe | message body | — |
| F7.1 | TTS announcer | IMPLEMENTED | 24 | — | — | number/name | — |
| F7.2 | Announcement modes | IMPLEMENTED | 24 | — | — | — | — |
| F7.3 | Language / voice | IN_PROGRESS | 24 | — | — | — | — |
| F7.4 | Spoken spam verdict | IN_PROGRESS | 24 | — | — | — | — |
| F7.5 | Bluetooth routing | NOT_STARTED | 24 | — | — | — | — |
| F8.1 | Recording capability assessment | IN_PROGRESS | 24 | RECORD_AUDIO | — | — | — |
| F8.2 | Capture with fallback | IMPLEMENTED | 24 | RECORD_AUDIO | — | call audio | — |
| F8.3 | Encrypted local storage | NOT_STARTED | 24 | — | — | call audio | — |
| F8.4 | Playback / deletion | IN_PROGRESS | 24 | — | — | call audio | — |
| F8.5 | Storage quota | NOT_STARTED | 24 | — | — | — | — |
| F8.6 | Consent notice / region gating | IN_PROGRESS | 24 | — | — | — | — |
| F8.7 | Transcription of recordings | IN_PROGRESS | 24 | — | yes | call audio | — |
| F9.1 | Number normalisation | IMPLEMENTED | 24 | — | — | number | PhoneUtilsTest |
| F9.2 | Local-first lookup | IMPLEMENTED | 24 | READ_CONTACTS | — | number | — |
| F9.3 | Remote lookup | IMPLEMENTED (client) | 24 | INTERNET | yes | number | needs a configured server |
| F9.10 | Crowd-sourced name entries (consent-gated) | IMPLEMENTED | 24 | INTERNET | yes | hashed number only | never publishes community names |
| F9.11 | Self-declared profile + OTP verification | IMPLEMENTED | 24 | INTERNET | yes | hashed number only | consent defaults off |
| F9.12 | Weighted spam reports + aggregation | IMPLEMENTED | 24 | INTERNET | yes | hashed number only | one report per user/category |
| F9.4 | Crowdsourced tags | IMPLEMENTED (client) | 24 | INTERNET | yes | number | needs a configured server |
| F9.5 | Explainable spam score | IMPLEMENTED | 24 | — | — | number | SpamScorerTest |
| F9.6 | Business directory | IMPLEMENTED (client) | 24 | INTERNET | yes | number | needs a configured server |
| F9.7 | Offline cache | IN_PROGRESS | 24 | — | — | number | — |
| F9.8 | Typed result + source/confidence | IN_PROGRESS | 24 | — | — | number | — |
| F10.1 | Screening gate | IMPLEMENTED | 24 | Call-screening role | — | number | — |
| F10.2 | Answer + announce + greet | IMPLEMENTED | 26 | ANSWER_PHONE_CALLS, RECORD_AUDIO | — | call audio | — |
| F10.3 | Transcription (on-device / cloud) | IN_PROGRESS | 24 | — | yes | call audio | — |
| F10.4 | Intent classification | IN_PROGRESS | 24 | — | — | transcript | — |
| F10.5 | Dialogue policy | NOT_STARTED | 24 | — | yes | transcript | — |
| F10.6 | Auto-SMS to caller | NOT_STARTED | 24 | SEND_SMS | — | — | — |
| F10.7 | Post-call brief | IMPLEMENTED | 24 | POST_NOTIFICATIONS | yes | transcript | — |
| F10.8 | Synthetic-voice reply | NOT_STARTED | 24 | — | yes | voice | — |
| F10.9 | Escalation for urgent calls | IN_PROGRESS | 24 | POST_NOTIFICATIONS | — | — | — |
| F11.1 | Dynamic Material 3 colour | IMPLEMENTED | 31 | — | — | — | — |
| F11.2 | Theme packs | IMPLEMENTED | 24 | — | — | — | — |
| F11.4 | Motion / reduced animation | NOT_STARTED | 24 | — | — | — | — |
| F11.5 | Accessibility | IN_PROGRESS | 24 | — | — | — | — |
| F11.6 | Localisation (Hindi/English) | NOT_STARTED | 24 | — | — | — | — |
| F12.1 | Permission manager / onboarding | IMPLEMENTED | 24 | (all) | — | — | — |
| F12.2 | Role requests | IMPLEMENTED | 29 | — | — | — | — |
| F12.3 | WorkManager background work | NOT_STARTED | 24 | — | — | — | — |
| F12.4 | Encrypted backup & sync | NOT_STARTED | 24 | INTERNET | yes | contacts | — |
| F12.5 | OTP authentication | NOT_STARTED | 24 | INTERNET | yes | phone number | — |
| F12.6 | Notifications | IMPLEMENTED | 24 | POST_NOTIFICATIONS | — | — | — |
| F12.7 | Settings hub | IMPLEMENTED | 24 | — | — | — | — |
| F12.8 | Crash/telemetry | NOT_STARTED | 24 | INTERNET | yes | diagnostics | — |
| F12.9 | Billing | N/A | — | — | — | — | Removed: Kavach is free |
| F13.x | Free by design (no paywall) | IMPLEMENTED | — | — | — | — | — |
| F16.1 | Feature registry: stable IDs, state, requirements | IMPLEMENTED | 24 | — | — | — | — |
| F16.2 | Per-module enable/disable with real effect | IMPLEMENTED | 24 | — | — | — | — |
| F16.3 | Runtime capability check + honest failure reason | IMPLEMENTED | 24 | varies per module | varies | — | — |
| F16.4 | Battery-saver / data-saver / offline-only modes | NOT_STARTED | 24 | — | — | — | — |
| F16.5 | Diagnostics log (opt-in, masked, shareable) | IMPLEMENTED | 24 | — | — | masked numbers only | — |
| F9.9 | Lookup elsewhere (WhatsApp/TG/Arattai/Hyped/mail/Google/Caller Name) | IMPLEMENTED | 24 | — | — | number only, leaves the device via the chosen app | — |
| F14.1 | First-run permission groups | IMPLEMENTED | 24 | (all) | — | — | — |
| F14.2 | Role requests in setup | IMPLEMENTED | 29 | — | — | — | — |
| F14.4 | Persisted setup completion | IMPLEMENTED | 24 | — | — | — | — |
| F15.1 | Update check | IMPLEMENTED | 24 | INTERNET | — | version only | UpdateCheckerTest |
| F15.2 | Download + install | IMPLEMENTED | 24 | REQUEST_INSTALL_PACKAGES | — | — | — |

---

## Notes, manual verification and limitations by group

### F1 Dialer
- **Manual verification:** type a number → call button; grant/deny CALL_PHONE and
  confirm the intent degrades from `ACTION_CALL` to `ACTION_DIAL`; with two SIMs
  confirm the picker appears and the chosen account is passed.
- **Limitations:** in-call screen and speed dial are not implemented. The SIM label
  depends on `TelecomManager.getPhoneAccount(handle).label`, which some OEMs leave
  blank (falls back to "SIM").

### F2 Contacts
- **Manual verification:** `+` → add a contact with two numbers, company, notes and
  a tag → Save → confirm it appears in Kavach *and* in the phone's contacts app.
  Tap it → edit → Save. Delete → confirm the dialog → confirm removal from both.
- **Limitations:** profile photo selection is not implemented (system photo URI is
  displayed only). No import/export. Deleting a contact owned by a synced account
  (e.g. Google) may be refused by the OS — the local Kavach copy is still removed.

### F4/F5 Blocking
- **Manual verification:** create an EXACT rule for a second phone; call from it →
  call should be rejected silently. Change the action to SILENCE → should ring muted.
  Set SEND_TO_AI → the assistant service should start.
- **Limitations:** requires the call-screening role. Crowdsourced spam needs the
  backend.

### F8 Recording
- **Manual verification:** requires a real device. Enable "Record all calls", place a
  call, confirm the persistent notification appears and a `.wav` lands in the
  recordings screen.
- **Limitations:** `VOICE_CALL` is blocked on most Android 10+ devices; the engine
  falls back to `VOICE_RECOGNITION`/`MIC`, which captures the device side and
  ambient audio, not a clean two-way recording. **Must be verified per device before
  being claimed.** Not encrypted at rest yet.

### F9/F10 Caller ID and AI
- **Manual verification:** call from an unknown number with SEND_TO_AI → the assistant
  should answer, speak the consent notice and greeting, and after the call a
  notification brief should appear.
- **Limitations:** the on-device screener is a **keyword rule set, not a trained
  model** — it is labelled as such everywhere. Cloud transcription/summary needs a
  backend that does not exist. Audio injection into the call uplink is not possible
  on Android, so the assistant speaks via the speaker.

### F15 Updates
- **Manual verification:** Settings → Updates → Check for updates. With a newer
  release published it should offer Download & install.
- **Limitations:** install requires the user to allow "Install unknown apps" for
  Kavach.
