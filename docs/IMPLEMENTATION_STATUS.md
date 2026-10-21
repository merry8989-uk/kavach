# Kavach — Implementation Status

**As of release v0.6.0.**

Status: **NOT_STARTED · IN_PROGRESS · IMPLEMENTED · VERIFIED · BLOCKED**

> ### Read this first
> **No feature is VERIFIED.** This project has never been run on an Android device or
> emulator — the build environment contains no Android SDK and no device. Everything
> below marked IMPLEMENTED compiles and passes CI, which is *not* the same as working.
> Device-dependent behaviour (screening, recording, answering, roles) is unverified
> and may well need fixes on first real hardware.

## 1. Counts

| Status | Features |
|---|---|
| IMPLEMENTED | 40 |
| IN_PROGRESS | 18 |
| NOT_STARTED | 26 |
| BLOCKED (needs backend) | 4 |
| VERIFIED | **0** |

## 2. What exists and compiles

**App foundation**
- Single-activity Compose app, Material 3, five bottom tabs (Dialer, Contacts, Calls,
  Messages, Settings) plus stacked routes (Blocklist, AI briefs, Recordings, contact
  editor).
- Hilt DI, Room v2 (5 tables, migration 1→2), DataStore settings with a synchronous
  `snapshot` for services.
- Theme engine: 5 packs, applied app-wide from persisted settings.
- CI (unit tests + debug assemble) and a signed auto-release pipeline.

**Protection**
- `KavachScreeningService` with Block / Silence / Send-to-AI / Allow.
- `RuleMatcher`: 8 rule types, pure and unit-tested.
- `KavachInCallService` (required for the Default Dialer role) tracking live calls.
- `Announcer`: TTS with 5 modes driven by persisted settings.

**Communication**
- Dialer: keypad, T9 search, dual-SIM picker, caller-ID preview, CALL/DIAL fallback.
- Contacts: read, sync, and full add/edit/delete with write-back to the device store.
- Call log: system mirror with idempotent sync, filters, spam/AI badges.
- Messages: SMS threads, conversation view, send.
- Recordings: playback screen.

**Caller intelligence**
- `PhoneUtils` normalisation, local-first `nameFor` lookup (single SQL query).
- `SpamScorer` deterministic heuristics (unit-tested).
- `OnDeviceScreener` — **keyword rules, explicitly not a trained model**.

**AI-assisted screening**
- `CallAssistantService`: answer → consent announcement → greeting → audio capture
  (VOICE_CALL → RECOGNITION → MIC) → transcription (cloud, if enabled) → brief
  notification.
- `AssistantOrchestrator` state machine; `CallBriefNotifier` with urgent escalation.

**Platform**
- First-run guided setup: all permission groups + Default dialer and Caller ID roles,
  live status, next-step button, persisted completion, no-flash gate.
- In-app update checker against GitHub Releases with download + install.
- Signed releases with in-place updates; private keystore never committed.

## 3. What is partial, and exactly how

| Area | Reality |
|---|---|
| Recording | Capture works via fallback sources; **two-way audio is unverified** and likely device-dependent. No encryption, no quota, no consent beep, no region gating. |
| AI | Orchestration and plumbing are real; the transcription/summary backend does not exist, so cloud paths return placeholders. Dialogue policy and auto-SMS are not built. |
| Caller ID | Local + heuristic only. No crowdsourced database, no business directory, no remote lookup. |
| Blocking | Rule engine and screening work; SMS blocking, schedules and the post-call prompt are not built. |
| Contacts | CRUD works; profile-photo selection, import/export, and a full detail screen are not built. |
| Settings | Real and persisted, but there is **no central feature registry** (F16), so modules cannot be independently disabled and there is no per-feature capability reporting. |
| Themes | 5 packs work; motion system, reduced-animation support and localisation (Hindi) are not built. |
| Premium | Nothing. No billing, no entitlements. |

## 4. Blocked (needs infrastructure that does not exist)

| Feature | Blocked on |
|---|---|
| Crowdsourced tags, remote lookup, reputation API | Backend + PostgreSQL + Redis |
| Cloud transcription / summarisation / dialogue | Backend + ASR + LLM provider |
| Spam-report abuse controls | Backend |
| Premium entitlements | Backend verification + Play Billing |

## 5. Honest claims audit

The master spec forbids overclaiming. Current wording, audited:

| Claim | Verdict |
|---|---|
| "AI call assistant that answers, transcribes and summarises" | **Accurate for the plumbing**; transcription needs the backend. The README states this. |
| `OnDeviceScreener` described as AI | **It is keyword rules.** Labelled "keyword fallback" in code, README and here. Must never be presented as a trained model. |
| "Call recording" | Described with its Android 10+ restriction and per-device caveat. |
| "Kavach gives away features competitors paywall" | Positioning claim, not a functional claim. |
| Nothing claims device verification | Correct — nothing is verified. |

## 6. Environment limitations (why VERIFIED is unreachable here)

1. No Android SDK / Gradle in the working sandbox → builds run only in GitHub Actions.
2. No emulator or device → no instrumentation, no Compose UI tests, no call testing.
3. No backend hosting → cloud features cannot be end-to-end tested.
4. OEM-specific behaviour (MIUI, One UI, etc.) cannot be exercised at all.

## 7. Next phase

Per the master spec's workflow, the next incomplete phase is **Phase 12 — the central
feature registry (F16)**, because it is a prerequisite for honestly reporting
capability state and for letting users disable modules. It is buildable entirely
on-device with no backend.

Ordered plan after that:
1. **F16 feature registry** — feature IDs, required permission/role, runtime
   capability check, failure reason, enable/disable, honest status indicators.
2. **Security hardening** — SQLCipher + EncryptedFile (the highest-risk gap).
3. **Phase 6 remainder** — in-call screen, favourites, speed dial, contact detail.
4. **Phase 9** — recording capability assessment surfaced in the UI, encryption,
   quota, consent beep, region gating.
5. **Backend** — Phase 3/7/13: lookup, reports, reputation, ASR/LLM, auth.
6. **Phase 11** — billing with server-side verification.
7. **Testing** — Room migration tests, repository tests with fakes, Compose UI smoke
   tests, lint in CI.
8. **Device verification** — run the manual scripts in `TEST_PLAN.md` on real hardware
   and promote features from IMPLEMENTED to VERIFIED.
