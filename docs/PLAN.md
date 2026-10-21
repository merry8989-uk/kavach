# Kavach — Master Build Plan

> **Kavach (कवच)** — "armour". An Indian-first, AI-native caller intelligence app.
> Kotlin + Jetpack Compose · Hybrid on-device + cloud AI · Free core, pro intelligence.

---

## 0. What Kavach is (positioning)

Kavach is a caller-identity and call-protection platform. Where Truecaller gives
its best features (AI call screening, spam stats, advanced blocking, no ads) only
to paid users, Kavach ships those to everyone. Kavach is **free** — no subscription,
no premium tier, no paywall.

Two things make Kavach different, not just a clone:

1. **An AI that takes the call first.** When an unknown number rings, Kavach's
   assistant can answer, greet the caller, transcribe the conversation, decide
   intent (delivery / spam / urgent / personal), text the caller a message, and
   hand you a one-screen brief — with the recorded audio and transcript.
2. **A living, futuristic UI.** Dynamic Material 3, live caller radar, animated
   spam-risk gauge, glass/neon "Aurora" and "Carbon" themes, per-contact accent
   colours. Nothing about it looks like a 2019 dialer.

**Legal & ethical stance (non-negotiable):** Kavach does **not** reverse-engineer,
scrape, or reuse Truecaller's app, its database, or its backend. All caller
intelligence is first-party (user-contributed crowdsourced tags), licensed public
data, or on-device inference. Call recording and AI answering are always announced
to the caller and gated by consent, per Indian law (IT Act, DPDP Act 2023) and
Play Store policy. One-party/two-party consent rules are enforced by region.

---

## 1. Feature inventory — every function, broken into parts

Each feature below lists its **parts** (the units a developer builds) and the
**layer** (Data / Domain / UI / System / Cloud).

### F1. Dialer (mandatory)
- **F1.1** Number pad UI (Compose) — digits, long-press for +, haptics, T9 name search.
- **F1.2** Smart paste — paste a number, auto-format +E.164, show identity preview.
- **F1.3** Call placement — `Intent.ACTION_CALL` with `CALL_PHONE` permission + runtime gate.
- **F1.4** Dual-SIM selector — `TelephonyManager` SIM slots, per-call SIM pick.
- **F1.5** In-call screen — mute, speaker, hold, DTMF, add-call, live timer.
- **F1.6** Speed dial + favourites strip.
- **F1.7** T9 search index over contacts (SQLite FTS or in-memory trie).
- **F1.8** Caller-ID preview card while typing.

### F2. Contacts (mandatory, rich profiles)
- **F2.1** Read system contacts via `ContactsContract` (multi-account, dedupe).
- **F2.2** Profile fields — name, **multiple numbers** (labelled), email, **company**,
  job title, **notes**, address, birthday.
- **F2.3** **Tags** — user-defined labels (Family, Client, Vendor, Spam-adjacent) + smart tags.
- **F2.4** **Profile image** — system photo + Kavach avatar upload + auto monogram fallback.
- **F2.5** Merged identity — system contact + Kavach crowdsourced name + spam stats, one card.
- **F2.6** Add/edit/delete contact (write-back to `ContactsContract`).
- **F2.7** Contact detail screen — interaction history, notes timeline, quick actions.
- **F2.8** Import/export — vCard, CSV; backup to Kavach cloud (opt-in).

### F3. Call logs (mandatory)
- **F3.1** Read `CallLog.Calls` (type, duration, SIM, timestamp).
- **F3.2** Merge with Kavach enrichment (name, spam label, AI summary badge).
- **F3.3** Filters — missed / incoming / outgoing / spam / recorded / AI-answered.
- **F3.4** Per-entry actions — call back, message, block, add to contacts, play recording.
- **F3.5** Search + date grouping.
- **F3.6** Local mirror in Room DB so logs survive/annotate even if system log cleared.

### F4. Blocklist (mandatory)
- **F4.1** Room-backed rule store.
- **F4.2** Rule types — exact number, prefix, wildcard, regex, range, country code.
- **F4.3** Special rules — block all private/unknown, block non-contacts, block foreign.
- **F4.4** Import/export blocklist (CSV/JSON), share a block rule.
- **F4.5** Crowdsourced top-spam list (opt-in, anonymised).

### F5. Blocking options (mandatory — the "how")
- **F5.1** `CallScreeningService` (API 24+) — silent reject, without ringing.
- **F5.2** `ITelephony.endCall()` fallback for older/edge cases.
- **F5.3** Modes — Block (reject), Silence (ring muted), Send-to-AI (let assistant answer), Allow.
- **F5.4** Per-rule action + per-contact override + global default.
- **F5.5** Block SMS too (default SMS role or SMS filter), keyword rules.
- **F5.6** Schedule — block hours, "do not disturb" windows.
- **F5.7** Post-call spam prompt — "Block this number?" one-tap learning.

### F6. Messaging (mandatory)
- **F6.1** SMS compose (default-SMS-role capable), threads UI.
- **F6.2** Quick replies / smart replies (on-device suggestion).
- **F6.3** Auto-reply rules — "busy", "driving", per-contact auto-text.
- **F6.4** AI auto-text for screened calls — assistant texts caller a custom message.
- **F6.5** Spam SMS filter + keyword blocklist.
- **F6.6** RCS-ready interface (future).

### F7. Caller announcer (mandatory)
- **F7.1** TTS announcer — speak name/company/number on ring (`TextToSpeech`, `STATE_RINGING`).
- **F7.2** Announcement modes — always / only known / only unknown / headset only / off.
- **F7.3** Language + voice selection (Indian English + regional TTS voices).
- **F7.4** Spoken spam verdict — "Likely spam: reported by 1,204 users."
- **F7.5** Bluetooth/headset routing.

### F8. Call recording (mandatory — hardest)
- **F8.1** `MediaRecorder` + `AudioSource.VOICE_CALL` where permitted.
- **F8.2** `AudioRecord`/`AudioPlaybackCapture` path for restricted OEMs.
- **F8.3** Accessibility-service capture path for Android 10+ locked devices.
- **F8.4** Recording storage — encrypted at rest, per-call file naming, auto-cleanup policy.
- **F8.5** Playback UI with waveform + seek, share/export.
- **F8.6** Auto-record rules — all / unknown / per-contact / never.
- **F8.7** Consent banner + beep + region gating (two-party consent regions).

### F9. Caller ID & spam intelligence (core)
- **F9.1** Incoming lookup — local DB first (<10 ms), then cloud (opt-in).
- **F9.2** Crowdsourced tags — users tag numbers; k-anonymised aggregation.
- **F9.3** Spam score model — frequency, report velocity, pattern heuristics.
- **F9.4** Name resolution for businesses (licensed directories).
- **F9.5** Offline cache of top-N lookups for no-network.
- **F9.6** Reverse number search inside app.

### F10. AI Call Assistant (the headline feature)
- **F10.1** Screening gate — decide whether to let AI answer (unknown/spam/DND).
- **F10.2** Telephony audio bridge — answer call, inject greeting TTS, capture caller audio.
- **F10.3** Streaming ASR (on-device small model for keywords; cloud Whisper-class for full transcript).
- **F10.4** Intent classifier — delivery / appointment / emergency / sales / spam / personal.
- **F10.5** Dialogue policy — scripted + LLM-guided responses ("Who's calling?", "I'll pass this on").
- **F10.6** Auto-SMS — text caller a message you configured, or a summary link.
- **F10.7** Post-call brief — transcript + summary + intent + sentiment + risk, pushed as notification.
- **F10.8** Voice reply — optionally reply to caller in your cloned/synthetic voice (consent-gated).
- **F10.9** Escalation — if urgent, ring through / call you back / notify emergency contact.

### F11. Themes & UI system (futuristic)
- **F11.1** Dynamic Material 3 colour from wallpaper.
- **F11.2** Theme packs — Aurora (glass/neon), Carbon (dark industrial), Daylight (clean), Neon Noir.
- **F11.3** Animated caller radar, spam gauge, live waveforms.
- **F11.4** Motion system — shared-element transitions, spring physics.
- **F11.5** Accessibility — large text, high contrast, TalkBack labels.

### F12. Platform / core infrastructure
- **F12.1** Onboarding + permission flow (rationale per permission).
- **F12.2** Roles — default dialer, call screening, SMS.
- **F12.3** Background services — screening, recording, sync (WorkManager).
- **F12.4** Backup & sync — encrypted, opt-in cloud sync of tags/notes/blocklist.
- **F12.5** Auth — phone-number OTP (Firebase/own backend), device binding.
- **F12.6** Notifications — incoming brief, missed-call digest, spam alerts.
- **F12.7** Settings hub.
- **F12.8** Telemetry & crash reporting (opt-in, privacy-scrubbed).
- **F12.9** ~~In-app billing~~ — **removed**: Kavach is free, with no paid tiers.

### F13. Free by design
- **F13.1** No paywall anywhere: every feature in this document ships to every user.
- **F13.2** No subscriptions, no in-app purchases, no entitlement system.
- **F13.3** No advertising.
- **F13.4** No server-side monetisation hooks.

If a future maintainer wants to add a paid tier, it must be a deliberate product
decision recorded here first — nothing in the code assumes one exists.

---

## 2. System architecture

```
┌─────────────────────────── Android app (Kotlin, Compose) ───────────────────────────┐
│  UI layer        Dialer · Contacts · CallLog · Blocklist · Messages · Assistant      │
│                  Theme engine (Aurora/Carbon/…) · Navigation                          │
├──────────────────────────────────────────────────────────────────────────────────────┤
│  Domain layer    UseCases: IdentifyCaller · ScreenCall · DecideBlock · SummariseCall  │
│                  Repositories (interface) · Models                                    │
├──────────────────────────────────────────────────────────────────────────────────────┤
│  Data layer      Room (contacts_enriched, call_log, block_rules, recordings,          │
│                  transcripts, tags) · DataStore (settings) · ContactsContract adapter │
│                  · CallLog adapter · WorkManager sync · Retrofit API client           │
├──────────────────────────────────────────────────────────────────────────────────────┤
│  System layer    CallScreeningService · InCallService(default dialer) · Recorder       │
│                  · Announcer(TTS) · SmsReceiver · AccessibilityCapture                │
├──────────────────────────────────────────────────────────────────────────────────────┤
│  AI layer        OnDeviceScreener(TFLite keyword/intent) · CloudAI(ASR+LLM summary)   │
│                  AssistantOrchestrator (state machine)                                │
└──────────────────────────────────────────────────────────────────────────────────────┘
                                   │ HTTPS (opt-in)
┌──────────────────────────────────▼──────────────────────────────────────────────────┐
│  Backend (separate repo/service)   Identity/OTP · Crowd-tag API · Spam scoring ·      │
│                                    ASR proxy · LLM summariser                         │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

**Key design rules**
- Every system-touching capability degrades gracefully (no permission → feature off, app still works).
- Cloud is always opt-in; on-device path must be functional alone.
- All PII encrypted at rest (SQLCipher / EncryptedFile); transcripts encrypted too.
- AI audio never leaves the device unless the user enabled cloud AI.

---

## 3. Tech choices

| Concern | Choice | Why |
|---|---|---|
| Language / UI | Kotlin + Jetpack Compose | Native access to CallScreeningService, telephony, best perf |
| DI | Hilt | Standard, testable |
| DB | Room + SQLCipher | Offline-first, encrypted |
| Async | Coroutines + Flow | Compose-native |
| Settings | DataStore | Type-safe prefs |
| Background | WorkManager | Sync, cleanup, digest |
| Networking | Retrofit + OkHttp + kotlinx.serialization | Standard |
| On-device AI | TFLite / MediaPipe (intent, keyword spotting) | Privacy, zero cost |
| Cloud ASR | Whisper-class API | Accuracy |
| Cloud LLM | Configurable (any provider) | Summaries, dialogue |
| Auth | OTP (own backend or Firebase) | Standard |
| Billing | **None** | Kavach is free by design — no subscriptions, no paywall |
| Crash | Sentry (self-hostable) | Privacy |
| CI/CD | GitHub Actions → signed APK/AAB → Release | Your auto-release requirement |

---

## 4. Build roadmap (phased, professional cadence)

**Phase 0 — Foundations (week 1)**
Repo, CI, module structure, theme engine, navigation, Room schema, DI graph.

**Phase 1 — Core telephony MVP (weeks 2–3)**
F1 Dialer · F2 Contacts(read+rich) · F3 Call logs · F4 Blocklist store ·
F5 CallScreeningService blocking · F12 onboarding/permissions/roles.

**Phase 2 — Protection & UX (weeks 4–5)**
F9 Caller ID local+crowd · F7 Announcer · F6 Messaging + auto-reply ·
F11 theme packs + motion · F12 notifications.

**Phase 3 — Recording (weeks 6–7)**
F8 recording paths + consent gating + playback + storage policy.

**Phase 4 — AI Assistant (weeks 8–10)**
F10 orchestrator, on-device screening, cloud ASR/LLM, briefs, auto-SMS.

**Phase 5 — Backend & accounts (weeks 11–12)**
OTP auth, crowd-tag API, spam scoring, sync.

**Phase 6 — Hardening (weeks 13–14)**
Testing, accessibility, Play policy compliance, privacy policy, store assets.

**Phase 7 — Launch & iterate**
Beta (Play internal → closed → open), analytics, feedback loop via @osintgram_io.

---

## 5. Deliverables in this repo

- `docs/` — this plan + the GitHub Pages prototype site (feature guide).
- `app/` — the Kotlin/Compose scaffold with the MVP feature modules.
- `keystore/` — **private** signing material (never committed; see `.gitignore`).
- `.github/workflows/` — build + auto-release pipeline.
- `RELEASING.md` — how signed updates ship so users update in place (no uninstall).

---

## 6. What's deliberately out of scope here

- A production spam database (needs the backend + users; scaffold provides the API contract).
- Trained ASR/LLM models (the app integrates providers; models live server-side).
- Play Store listing assets (generated at Phase 6).

The scaffold compiles a real, runnable MVP (dialer, contacts, call log, blocklist,
caller-ID screening, announcer, themes) and stubs the AI/recording paths with
clear integration points.
