# Kavach (कवच)

**Armour for every call.** An Indian-first, AI-native caller intelligence app —
caller ID, smart blocking, call recording, and an AI assistant that answers
unknown calls, transcribes them, texts the caller, and hands you a one-screen
brief.

> Built with Kotlin + Jetpack Compose. Hybrid on-device + cloud AI.
> **Completely free** — no subscriptions, no premium tier, no ads, no paywall.
> Every feature ships to every user.

**🌐 Product site & prototype:** `docs/index.html` (GitHub Pages)
**💬 Feature requests & feedback:** [@osintgram_io](https://instagram.com/osintgram_io)

---

## Why Kavach is different

1. **An assistant that takes the call first.** Unknown number rings → Kavach's
   assistant answers, speaks a consent notice and greeting, captures the audio and
   posts a brief (transcript, summary, intent, sentiment, spam risk).
   *Honest scope:* the answer / announce / capture / brief plumbing is implemented.
   Full transcription and summarisation need the backend, which is **not built**.
   Intent classification currently uses **keyword rules, not a trained model**.
   Android also forbids injecting audio into a cellular call uplink, so the assistant
   speaks via the speaker rather than holding a natural two-way conversation.
2. **A living, futuristic UI.** Dynamic Material 3 with five theme packs (Aurora,
   Carbon, Daylight, Neon Noir, Dynamic) that apply app-wide and persist.

## What's inside

| Area | Highlights |
|---|---|
| **Dialer** | Stock-dialer layout: identity card, create/add contact, video call, send message, keypad with long-press shortcuts, dual-SIM, Call pill |
| **In-call screen** | Full-screen: name, number, region, direction, live duration, SIM label, keypad/mute/speaker/hold/record/Bluetooth, answer/decline/end |
| **Call notification** | Ongoing notification: who, saved-or-not, SIM, direction, self-updating duration |
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
| **Features screen** | Central registry: per-module on/off, live capability status, honest reasons |
| **Caller lookup** | Send a number to WhatsApp, WhatsApp Business, Telegram, Arattai, Hyped, mail, Google, or Caller Name (truecallers.in) |
| **Diagnostics** | Opt-in local technical log with masked numbers, readable and shareable from Settings |

> **Verification status: nothing here is device-verified.** The project compiles,
> passes 136 unit tests, passes Android lint with zero errors, and ships signed —
> but it has never been run on a phone or emulator, and the spec is explicit that
> compilation is not verification. The app now carries a **self-test** that checks
> what it can on the device, and the remaining list is 15 checks in
> [`docs/DEVICE-VALIDATION.md`](docs/DEVICE-VALIDATION.md).
>
> **What is pending, and in what order:** [`docs/AUDIT-AND-PLAN.md`](docs/AUDIT-AND-PLAN.md).

## Self-hosting a server

Kavach works with no server at all. A server adds caller lookup and spam
reputation, and nothing else — diagnostics stay on the device, always.

```bash
cd backend && ./self-host.sh
```

One command: generates secrets, writes a mode-600 `.env`, builds the image,
starts Postgres + Redis + the API, waits until it is healthy, and prints what to
paste into **Settings → Self-hosted lookup**. Guide: [SELF-HOST.md](SELF-HOST.md).

## Monitoring

The server ships its own logs — latency, error rates, provider failures — to a
Cloudflare Worker, which stores them in D1 and serves a dashboard. Off by default.

```bash
cd monitoring && wrangler deploy
```

Guide: [monitoring/README.md](monitoring/README.md).

**This is not where the app's diagnostics go.** Those stay on the device and leave
only when the user chooses. The sink enforces it: every app event carries a
`user_code`, the server never emits one, and anything carrying one is refused.

## Docs

| Document | Contents |
|---|---|
| [`docs/PLAN.md`](docs/PLAN.md) | Every function split into parts + phased roadmap |
| [`docs/PRODUCT_REQUIREMENTS.md`](docs/PRODUCT_REQUIREMENTS.md) | Requirements, personas, constraints, acceptance |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Layers, package map, data flows, dependency deviations |
| [`docs/FEATURE_MATRIX.md`](docs/FEATURE_MATRIX.md) | Per-feature status, API, permissions, privacy, tests |
| [`docs/PERMISSIONS.md`](docs/PERMISSIONS.md) | Permission + role matrix and request policy |
| [`docs/SECURITY.md`](docs/SECURITY.md) | Threat model, data inventory, encryption gaps |
| [`docs/TEST_PLAN.md`](docs/TEST_PLAN.md) | What passed, what hasn't run, manual scripts |
| [`docs/IMPLEMENTATION_STATUS.md`](docs/IMPLEMENTATION_STATUS.md) | Honest status per feature |
| [`docs/API_CONTRACT.md`](docs/API_CONTRACT.md) | Backend contract (server not built) |

## Architecture

```
UI (Compose)  →  Domain (use cases, repositories)  →  Data (Room, ContactsContract, CallLog)
              ↘  System (CallScreeningService, Recorder, TTS, SMS)
              ↘  AI (OnDeviceScreener · CloudAiClient · AssistantOrchestrator)
```

## Backend

`backend/` is a real, tested service implementing [`docs/API_CONTRACT.md`](docs/API_CONTRACT.md)
— caller lookup, explainable reputation scoring, spam reports, identity corrections,
business search, privacy/deletion, and ASR/LLM adapters that return `503` rather than
fabricating a result.

```bash
cd backend && docker compose up --build     # Postgres + Redis + API
cd backend && pytest                        # 40 tests
```

It is **not deployed**, and the Android client does not call it yet.

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
    domain/              Clean Architecture: model/ + usecase/ (5 use cases)
    data/                Room, DAOs, system readers/writers, repos, DataStore settings, update
    work/                WorkManager workers (call-log sync, retention cleanup)
    ai/                  On-device screener (keyword rules), cloud client, orchestrator
    service/             CallScreeningService, InCallService, CallAssistantService, Announcer
    recording/           CallRecorder, CallRecorderService, AudioCaptureEngine
    ui/                  theme/, nav/, screens/ (dialer, contacts, calls, messages, blocklist, assistant, recordings, onboarding, settings, updates)
backend/                 FastAPI + SQLAlchemy + Postgres service (40 tests passing)
docs/                    GitHub Pages site + the eight specification documents
.github/workflows/       app CI, backend CI, build + auto-release
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

### Added in 0.8.0

- **Dialer rebuilt to match a stock phone-app layout.** Typing a number now shows:
  - an identity header — the contact name, or **"Not in your contacts"** — with the last call time and a call-back button;
  - action rows: **Create new contact**, **Add to a contact**, **Video call**, **Send a message**;
  - the number display with an overflow menu (**Paste**, **Copy number**, **Add 2-sec pause**, **Add wait**) and backspace;
  - a keypad with the usual long-press shortcuts — 1 = voicemail, 0 = `+`, `*` = pause, `#` = wait;
  - a green **Call** pill.
- **Create new contact** opens the editor pre-filled with the typed number; **Add to a contact** picks an existing contact and appends the number.
- **Send a message** opens the system SMS composer for that number (works without Kavach holding the SMS role).
- **Video call** shows an honest unavailable state explaining it needs the default-dialer role and carrier video support — it does not pretend to work.
- Added `SearchContactsByT9UseCase` and a `lastCallFor` query so the identity card shows real history.

### Added in 0.9.0 — Phase 4 caller UI

Before this, Kavach showed **nothing** during a call. Now:

- **Full-screen in-call screen** (`InCallActivity` + `InCallScreen`), driven by `KavachInCallService`:
  - "Calling via <SIM>" / "Incoming call via <SIM>" header;
  - the **contact name** when known, the formatted number, and the **region**;
  - a **direction marker** (incoming/outgoing) and a **live duration**;
  - a "Saved contact" / "Not in your contacts" chip;
  - controls: Keypad (real DTMF), Mute, Speaker, and a More menu with **working** Hold, Record and Bluetooth;
  - Answer/Decline while ringing, End call once connected.
- **Ongoing call notification** showing the name/number, whether they're saved, the **SIM**, the **direction**, and a **self-updating duration** (platform chronometer).
- New `CallSession` domain model + `CallSessionManager` so the screen and the notification can never disagree.
- **Fallback `CallStateMonitor`** for when Kavach is *not* the default dialer: it derives the number, direction and SIM from the system call log, and stands down automatically while the accurate InCallService path is active.
- **SIM attribution in the call log** — `phoneAccountId` is now read and mapped to a SIM label (DB migration v2→v3).

Honest limits: the full in-call UI needs the **default-dialer role**; without it the fallback notification is reduced (the first moments of a ringing call may not be attributable, since the call-log row is written as the call starts). Video calling remains unimplemented and says so.

### Added in 0.11.0 — Phase 12: the feature registry (F16)

- **A central registry** (`FeatureId` + `FeatureRegistry`) now declares, for every optional
  module: a stable id, title, category, default state, whether it is essential, the
  permissions and Android role it needs, and its dependencies.
- **A Features screen** (Settings -> Features & modules) lists every module grouped by
  category with a **live status**: *On*, *Off*, *Limited*, or *Unavailable* — and a
  plain-language reason when it can't run ("Needs the caller ID & spam role", "Requires
  \"Call screening\" to be on first").
- **Toggles now control real behaviour.** Disabling a module stops its work:
  - call screening off -> every call is allowed through untouched;
  - recording off -> no recorder is started;
  - AI assistant off -> calls are never handed to the assistant;
  - background sync off -> the WorkManager job is **cancelled**, not just hidden;
  - update checks off -> no network call on launch.
- **Essential modules are locked** with an explanation rather than a switch that lies.
- The four old scattered toggles (AI, record-all, missed reminders, update checks) were
  **migrated into the registry**, so there is exactly one source of truth.
- **Fixed the SMS-role bug**: onboarding no longer offers a role Kavach can never hold
  (it declares no SMS intent filters, so Android would refuse). Message features run from
  the READ_SMS permission instead.
- Added a unit test asserting the registry's structure: no self-dependencies, no cycles,
  essential modules default to on, and every feature is labelled.

### Added in 0.12.0 — caller lookup + diagnostics

**"Find the caller's name"** — a lookup sheet reachable from the dialer (overflow menu),
the call log (long-press a row) and the contact detail screen. It hands the number to:
WhatsApp, WhatsApp Business, Telegram, Arattai, Hyped, Email, Google search, and
**Truecaller's public web search** (country path derived from the calling code).

- WhatsApp / Telegram / mail / Google / Truecaller use documented public links, so they
  work without Kavach knowing anything about the target app.
- Arattai and Hyped have no documented public deep link, so they are handled as "open if
  installed, else show the store listing". **Their package names are best-effort and need
  checking on a real device.**
- Failures are reported in the sheet ("WhatsApp isn't installed on this phone") instead of
  appearing to do nothing.

**Diagnostics** (Settings -> Diagnostics & logs) — an opt-in local technical log:

- Records startup, module states, screening decisions, call sessions and update checks.
- **Phone numbers are masked to their last 4 digits before writing**; contact names, message
  content and audio are never logged. The masking is enforced in the repository, not left to
  callers.
- Readable in-app with level colouring, and shareable as a file via the system share sheet
  (through the existing `FileProvider`). Nothing is ever uploaded automatically.
- Capped at 512 KB with rotation, so it cannot grow without bound. Clear button included.

### Added in 0.13.0 — backend wired, and the Caller Name lookup

**The Android client is now wired to the backend.** `data/remote/` adds a
`KavachApiClient` (OkHttp + kotlinx.serialization) and a `CallerLookupDataSource`
with a short-lived cache, implementing the endpoints in `docs/API_CONTRACT.md`:

- `GET /v1/lookup`, `GET /v1/number-reputation`, `GET /v1/businesses/search`,
  `POST /v1/reports`, `POST /v1/identity-corrections`.
- `IdentifyCallerUseCase` now resolves **contacts -> server -> local heuristics**, and
  never invents a name. A weak community tag is carried with a low `confidence` so the UI
  can present it as a possibility rather than a fact.
- **Kavach ships no hosted backend.** The server URL and token are settings; until they
  are filled in, the new `REMOTE_LOOKUP` feature reports itself **Unavailable** with a
  clear reason and nothing is ever called. Calls are bounded by a 4-second timeout, and
  remote lookup is deliberately *not* on the call-screening path, which stays offline.

**Caller Name (truecallers.in) replaced the old Truecaller entry.** I drove that live
site to check how its search works, and the result changed the design: its search is
entirely client-side — **the URL never changes and a `?number=` parameter is ignored**.
There is therefore no deep link that can carry a number, so instead of faking one,
Kavach opens the site **and copies the number to the clipboard**, with the UI saying
"number copied — paste it into the search box".

### Added in 0.27.0 — Hindi

The interface ships in English and Hindi. Every user-facing string in the Compose
UI now comes from `values/strings.xml`, with `values-hi/strings.xml` alongside it,
and a picker under Settings → Appearance that applies immediately.

The language is stored in a plain preference rather than DataStore, because it has
to be readable in `attachBaseContext` before anything asynchronous can be
awaited — a `runBlocking` DataStore read there is a deadlock waiting to happen.

CI now fails if a translation drifts from the default, because a missing key shows
English in the middle of a Hindi screen rather than an obvious error.

### Added in 0.26.0 — backend reliability fixes from the audit

Four P0 items confirmed against the code and fixed: **OTP delivery now actually
sends** (MSG91 and Twilio adapters, and the endpoint returns 503 rather than a
fake success), the **rate limiter is Redis-backed** when configured and honest
about being per-process when not, and the **account-deletion bug** is fixed —
corrections had no owner column at all, so a right-to-erasure request silently
kept them. The screening service also gained a 2.5 second deadline, because a
call with no response simply rings on. Full write-up in `docs/AUDIT-2026-02.md`.

### Added in 0.25.0 — Notion removed, Supabase is the diagnostics destination

Diagnostics are written straight into a Supabase Postgres table. No server to
run: the app appends over PostgREST with the public anon key, and row-level
security in `supabase/schema.sql` lets it insert but never read.

The Notion destination is gone. It needed every user to create their own
integration and paste a token, and their rows would have landed in their
workspace rather than yours. The upload queue now stands on its own
(`UploadQueue`) instead of belonging to one destination. See `docs/SUPABASE.md`.

### Added in 0.21.2 — the server is deployable

The backend now ships as a multi-architecture container image on GitHub
Container Registry, built and published by `.github/workflows/server-release.yml`.
Deploying is `docker compose -f docker-compose.prod.yml up -d` on any small VPS.

It also refuses to start insecurely: on boot it checks `API_TOKEN`,
`PHONE_PEPPER`, `JWT_SECRET`, `AUTO_CREATE_TABLES`, `OTP_DEBUG_RETURN` and the
database URL, and exits with a list of problems rather than running with a
secret that is published in this repository. Full walkthrough:
`docs/SERVER_DEPLOY.md`.

### Added in 0.20.0 — automatic Notion sync

Diagnostics events can now push themselves into the user's own Notion database
(`Kavach Diagnostics — Events`), so the weekly review fills itself. The token is
the user's own, stored Keystore-encrypted, and the feature is off until they
switch it on. Queued locally first, so an outage loses nothing.

### Added in 0.14.0 — our own consent-based database

The plan in `docs/CALLER-ID-PLAN.md` is now **bundled inside the app** (in
`assets/`, readable offline) and summarised on a new **"How Kavach identifies
callers"** screen in Settings.

The backend gained the plan's full three-layer data model, and the app gained the
screens to use it.

**1. Crowd-sourced names — `name_entries`.** Candidate names carry a lawful source
(`self_declared` / `business` / `community`) and a confidence. The rule that the
product rests on is enforced in exactly one place,
`services.names.best_publishable_name`: **only self-declared and verified-business
names are ever returned.** A community suggestion — however many people make it —
is stored for review and never published. There is a test named
`test_a_community_name_is_never_published` that fails if that ever changes.

**2. Consent-based profile — `users` / `profiles` / `consent_events`.** Phone
verification by OTP, then an explicit, **default-off** "publish my name" consent.
Every decision is appended to an audit trail the user can read in the app.
Withdrawing consent removes the name immediately; deleting the account withdraws it
and erases the profile.

**3. Spam flagging — `spam_reports` / `spam_scores` / `moderation_queue`.** Reports
are weighted by reporter trust, decay with a ~90-day half-life, and count for more
when they come from many distinct people than from one person repeating. Score
bands: 0–39 clean, 40–69 likely spam, 70–100 spam. A verified business or a
self-declared number is discounted.

**Privacy by construction.** Numbers are stored **only** as
`HMAC_SHA256(pepper, e164)` — there is no column anywhere in the consent tables
holding a raw number, and a test asserts it. Because the server cannot reverse a
hash, it also cannot reproduce your number: `/v1/me` returns no mask by design, and
the client shows its own number from local state. That is a deliberate trade of
convenience for not holding the data at all.

**Honest limits.** No SMS gateway is wired up, so OTPs are not delivered — in
development `OTP_DEBUG_RETURN` echoes the code and the response says so. The
backend is still not deployed anywhere, so none of this has run against a live
server.

## Legal & ethics

Kavach does **not** reverse-engineer, scrape, or reuse any competitor's app,
database or backend. All caller intelligence is first-party (user-contributed
crowdsourced tags), licensed public data, or on-device inference. Call recording
and AI answering are always announced to the caller and gated by consent, in line
with Indian law (IT Act, DPDP Act 2023) and Google Play policy.

## Contributing

Have a feature idea or feedback? Message **[@osintgram_io](https://instagram.com/osintgram_io)**
— new features and suggestions are discussed there.
