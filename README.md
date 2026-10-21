# Kavach (कवच)

**Armour for every call.** An Indian-first, AI-native caller intelligence app —
caller ID, smart blocking, call recording, and an AI assistant that answers
unknown calls, transcribes them, texts the caller, and hands you a one-screen
brief.

> Built with Kotlin + Jetpack Compose. Hybrid on-device + cloud AI.
> Kavach gives away the features competitors lock behind a paywall, and
> monetises the AI assistant instead.

**🌐 Product site & prototype:** `docs/index.html` (GitHub Pages)
**💬 Feature requests & feedback:** [@osintgram_io](https://instagram.com/osintgram_io)

---

## Why Kavach is different

1. **An AI that takes the call first.** Unknown number rings → Kavach's assistant
   answers, greets, transcribes, classifies intent (delivery / appointment /
   emergency / sales / spam / personal), texts the caller if configured, and
   sends you the transcript, summary, sentiment and a spam-risk score.
2. **A living, futuristic UI.** Dynamic Material 3 with Aurora, Carbon, Daylight
   and Neon Noir theme packs, animated caller radar, spam gauge and waveforms.

## What's inside

| Area | Highlights |
|---|---|
| **Dialer** | Keypad, T9 search, dual-SIM, in-call controls, caller-ID preview |
| **Contacts** | Rich profiles — add / edit / delete, multiple numbers, company, notes, tags, photos |
| **Call log** | System log merge, filters, spam scores, AI-answered badges |
| **Blocklist** | Exact / prefix / wildcard / regex / range / country rules, private & non-contact rules |
| **Blocking** | Silent reject via `CallScreeningService`, Block / Silence / Send-to-AI / Allow |
| **Messaging** | SMS threads + conversation view, compose & send, unread badges |
| **Announcer** | TTS speaks name / company / number, Indian-English voice, spam verdict |
| **Recording** | Layered capture (VOICE_CALL → recognition → mic), WAV output, playback screen, consent notice |
| **Caller ID** | Local-first lookup, crowdsourced tags, heuristic + cloud spam scoring |
| **AI assistant** | Answers the call, announces consent, speaks a greeting, captures audio, transcribes (cloud), posts a brief |
| **Themes** | Five theme packs that apply app-wide, persisted in settings |
| **Onboarding** | Runtime permissions + Call screening / Default dialer / Default SMS roles |
| **Updates** | In-app check against GitHub Releases, download + install, optional launch check |

Full breakdown: [`docs/PLAN.md`](docs/PLAN.md) — every function split into parts
with a phased build roadmap.

## Architecture

```
UI (Compose)  →  Domain (use cases, repositories)  →  Data (Room, ContactsContract, CallLog)
              ↘  System (CallScreeningService, Recorder, TTS, SMS)
              ↘  AI (OnDeviceScreener · CloudAiClient · AssistantOrchestrator)
```

## Build & run

Requirements: JDK 17, Android SDK (compileSdk 34).

```bash
# Debug build (the wrapper is included)
./gradlew :app:assembleDebug

# Unit tests
./gradlew :app:testDebugUnitTest

# Signed release (needs keystore/keystore.properties — see keystore/README.md)
./gradlew :app:assembleRelease -PversionCode=3 -PversionName=0.2.0
```

Install the APK:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Releases (signed, in-place updates)

Tag a version and CI does the rest:

```bash
git tag v0.1.0 && git push origin v0.1.0
```

See [`RELEASING.md`](RELEASING.md) and [`keystore/README.md`](keystore/README.md).
**The private keystore is never committed.**

## Project layout

```
app/                     Android app module (Kotlin, Compose)
  src/main/java/com/kavach/caller/
    data/                Room, DAOs, system readers (contacts/call log/SMS), repos, DataStore settings
    ai/                  On-device screener, cloud client, orchestrator
    service/             CallScreeningService, InCallService, Announcer
    recording/           CallRecorder, CallRecorderService
    ui/                  theme/, nav/, screens/ (dialer, contacts, calls, messages, blocklist, assistant, onboarding, settings)
docs/                    GitHub Pages site + PLAN.md
.github/workflows/       build + auto-release
keystore/                signing docs (key itself is git-ignored)
```

## Status

This repository is a **working MVP foundation**. Implemented: permission
onboarding + role requests, persisted settings with a working theme switcher,
dialer with T9 search and dual-SIM, rich contacts, call log with de-duplicated
sync, blocklist + screening engine, SMS threads with send, TTS announcer, layered
call recorder, and AI orchestration.

Still integration points (marked in code): the cloud ASR/LLM backend
(`ai/CloudAiClient.kt`) and the trained on-device intent model
(`ai/OnDeviceScreener.kt`, which currently ships a keyword fallback).

### Fixed in 0.2.0

- Added first-run permission onboarding and Call screening / Default dialer / Default SMS role requests — nothing was ever granted before.
- Settings now persist in DataStore, and the theme switcher actually recolours the app.
- Added an `InCallService`, required for the Default Dialer role.
- Added the missing Messages feature (SMS threads, conversation view, send).
- Call-log sync is now idempotent (unique index + v1→v2 migration); it used to duplicate every row on every sync.
- Contact lookup on the call-screening hot path is a single SQL query instead of loading every contact.
- Added T9 name search and a dual-SIM picker to the dialer.
- Fixed wildcard rules treating `+` and `?` as regex metacharacters.
- Added the Gradle wrapper, an MIT LICENSE, JVM unit tests and a CI workflow.

### Added in 0.3.0

- **The AI audio bridge is real now.** A `CallAssistantService` answers the call, sets speakerphone, speaks the consent notice + greeting, captures the call to a WAV, transcribes it via the cloud client (when enabled), and posts the post-call brief as a notification. Screening's Send-to-AI action actually drives it.
- **Recording playback screen** (`RecordingsScreen`), reachable from Settings.
- **"Record all calls" now does something** — the screening service starts the recorder for allowed calls when the setting is on.
- `InCallService` now tracks live calls so the assistant can answer/hang up when Kavach owns them.
- Documented the honest platform limit: Android exposes no public API to inject audio into a cellular uplink, so a natural two-way AI conversation needs a server-side telephony gateway (see `docs/PLAN.md`).

### Added in 0.4.0

- **In-app update checker.** Settings → Updates shows the installed version and a *Check for updates* button. It reads the latest GitHub Release, compares versions, and when a newer build exists offers **Download & install** (downloads the APK with a progress bar, then hands it to the system installer via a `FileProvider`) or a link to the release notes.
- **Optional launch-time check** (on by default, toggleable in Settings) surfaces a slim "Kavach x.y.z is available" banner at the top of the app.
- The downloader only accepts URLs under Kavach's own GitHub release path, so a spoofed API response can't push an arbitrary APK.
- Added a unit test for the version comparison.

### Added in 0.5.0

- **First-run setup is now a guided flow.** On first install Kavach asks for every permission group (Contacts, Phone & call log, Messages, Microphone, Notifications) *and* the two roles it depends on: **Default dialer** and **Caller ID & spam** (call screening) — with the SMS role offered as optional.
- Each item shows a live tick as it's granted, and one primary button always performs the next outstanding step: *Grant all permissions* → *Set as Default dialer* → *Set as Caller ID & spam* → *Finish setup*.
- Setup completion is remembered, so the flow appears on first install (or if the core permissions are later revoked) rather than every launch — the nav graph waits for settings to load so it never flashes.
- *Skip for now* dismisses it for the session, and an *Open app settings* shortcut helps with permissions the user has permanently denied.

### Added in 0.6.0

- **Contacts are now editable.** A **+** button on the Contacts tab opens an add-contact form, and tapping any contact opens it for editing. Fields: name, multiple labelled numbers (add/remove), company, job title, notes and tags.
- Saves write to the **device contact store** first (via a new `ContactsWriter`), so a contact added in Kavach also shows up in the phone's own contacts app — with Kavach's enriched copy (tags, notes) kept alongside.
- **Delete** with a confirmation dialog removes the contact from Kavach and, when it is linked, from the device too.

## Legal & ethics

Kavach does **not** reverse-engineer, scrape, or reuse any competitor's app,
database or backend. All caller intelligence is first-party (user-contributed
crowdsourced tags), licensed public data, or on-device inference. Call recording
and AI answering are always announced to the caller and gated by consent, in line
with Indian law (IT Act, DPDP Act 2023) and Google Play policy.

## Contributing

Have a feature idea or feedback? Message **[@osintgram_io](https://instagram.com/osintgram_io)**
— new features and suggestions are discussed there.
