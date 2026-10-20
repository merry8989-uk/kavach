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
| **Contacts** | Rich profiles — multiple numbers, company, notes, tags, photos |
| **Call log** | System log merge, filters, spam scores, AI-answered badges |
| **Blocklist** | Exact / prefix / wildcard / regex / range / country rules, private & non-contact rules |
| **Blocking** | Silent reject via `CallScreeningService`, Block / Silence / Send-to-AI / Allow |
| **Messaging** | SMS threads, smart replies, auto-reply rules, AI auto-text |
| **Announcer** | TTS speaks name / company / number, Indian-English voice, spam verdict |
| **Recording** | Layered capture strategy, consent-gated, encrypted storage, playback |
| **Caller ID** | Local-first lookup, crowdsourced tags, heuristic + cloud spam scoring |
| **AI assistant** | Screening, transcription, dialogue, auto-SMS, post-call briefs |
| **Themes** | Dynamic colour + four theme packs, motion system, accessibility |

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
# Debug build
gradle :app:assembleDebug

# Signed release (needs keystore/keystore.properties — see keystore/README.md)
gradle :app:assembleRelease -PversionCode=2 -PversionName=0.2.0
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
    data/                Room entities, DAOs, system readers, repositories
    ai/                  On-device screener, cloud client, orchestrator
    service/             CallScreeningService, Announcer
    recording/           CallRecorder, CallRecorderService
    ui/                  theme/, nav/, screens/
docs/                    GitHub Pages site + PLAN.md
.github/workflows/       build + auto-release
keystore/                signing docs (key itself is git-ignored)
```

## Status

This repository is a **scaffold + MVP foundation**: the data layer, blocking
engine, screening service, announcer, recording engine, AI orchestration and the
core Compose screens are implemented. The cloud ASR/LLM endpoints and the
on-device intent model are integration points marked in code — see
`ai/CloudAiClient.kt` and `ai/OnDeviceScreener.kt`.

## Legal & ethics

Kavach does **not** reverse-engineer, scrape, or reuse any competitor's app,
database or backend. All caller intelligence is first-party (user-contributed
crowdsourced tags), licensed public data, or on-device inference. Call recording
and AI answering are always announced to the caller and gated by consent, in line
with Indian law (IT Act, DPDP Act 2023) and Google Play policy.

## Contributing

Have a feature idea or feedback? Message **[@osintgram_io](https://instagram.com/osintgram_io)**
— new features and suggestions are discussed there.
