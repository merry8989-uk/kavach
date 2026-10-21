# Kavach — Architecture

## 1. Layering

```
┌──────────────────────────── UI (Jetpack Compose, Material 3) ───────────────────────────┐
│  screens/  dialer · contacts (+edit) · calllog · messages · blocklist · assistant ·      │
│            recordings · onboarding · settings · updates                                  │
│  nav/      KavachNav (onboarding gate → KavachMain, 5 tabs + 3 stacked routes)           │
│  theme/    ThemePack engine (Aurora / Carbon / Daylight / Neon Noir / Dynamic)           │
├──────────────────────────────────────────────────────────────────────────────────────────┤
│  Presentation state   ViewModels (Hilt), StateFlow / mutableStateOf                      │
├──────────────────────────────────────────────────────────────────────────────────────────┤
│  Domain (Clean Architecture)                                                             │
│    model/    CallerIdentity                                                              │
│    usecase/  DecideCallAction · IdentifyCaller · SaveContact · SyncContacts ·             │
│              CheckForUpdates                                                             │
├──────────────────────────────────────────────────────────────────────────────────────────┤
│  Data access   repositories                                                              │
│    ContactRepository · BlockRepository (+RuleMatcher) · CallLogRepository                │
│    SettingsRepository · UpdateChecker / UpdateInstaller                                  │
├──────────────────────────────────────────────────────────────────────────────────────────┤
│  Background work   work/  CallLogSyncWorker · CacheCleanupWorker · WorkerScheduler        │
├──────────────────────────────────────────────────────────────────────────────────────────┤
│  Data sources                                                                           │
│    local/    Room: contacts_enriched, block_rules, call_log, recordings, transcripts     │
│    system/   ContactsReader · ContactsWriter · CallLogReader · SmsReader                  │
│    settings/ DataStore (kavach_settings)                                                 │
│    update/   GitHub Releases REST (OkHttp + kotlinx.serialization)                        │
├──────────────────────────────────────────────────────────────────────────────────────────┤
│  Android integration (services)                                                          │
│    KavachScreeningService (CallScreeningService)  · KavachInCallService (InCallService)   │
│    CallAssistantService (FGS, assistant runtime)  · CallRecorderService (FGS, recording)  │
│    Announcer (TTS) · CallAssistantController (Telecom/AudioManager) · CallBriefNotifier   │
├──────────────────────────────────────────────────────────────────────────────────────────┤
│  AI                                                                                      │
│    OnDeviceScreener (keyword rules — NOT a trained model)                                 │
│    SpamScorer (deterministic heuristics)                                                  │
│    CloudAiClient (transcribe / summarise / dialogue — backend not yet built)              │
│    AssistantOrchestrator (state machine)                                                  │
└──────────────────────────────────────────────────────────────────────────────────────────┘
                                   │ HTTPS (opt-in only)
                          ┌────────▼─────────┐
                          │  Backend  — NOT BUILT  (see API_CONTRACT.md)   │
                          │  lookup · reports · reputation · ASR · LLM     │
                          └──────────────────┘
```

## 2. Module / package map

```
app/src/main/java/com/kavach/caller/
  KavachApp.kt                    @HiltAndroidApp, warms TTS
  MainActivity.kt                 single activity, theme from settings
  di/AppModule.kt                 Room + DAOs + OkHttp, migration 1→2
  data/
    local/  KavachDatabase, Converters, entity/, dao/
    system/ ContactsReader, ContactsWriter, CallLogReader, SmsReader
    settings/ AppSettings, SettingsRepository (DataStore)
    update/ UpdateInfo, UpdateChecker, UpdateInstaller
    repo/   ContactRepository, ContactDraft, BlockRepository, RuleMatcher,
            CallLogRepository
  domain/   model/CallerIdentity, usecase/ (5 use cases)
  work/     Workers (sync + retention), WorkerScheduler
  ai/       AiModels, SpamScorer, OnDeviceScreener, CloudAiClient,
            AssistantOrchestrator
  service/  KavachScreeningService, KavachInCallService, CallAssistantController,
            CallAssistantService, CallBriefNotifier, Announcer
  recording/ CallRecorder, CallRecorderService, AudioCaptureEngine, WavWriter
  ui/       theme/, nav/, screens/
  util/     PhoneUtils
```

## 3. Key data flows

### 3.1 Incoming call
```
OS → KavachScreeningService.onScreenCall(details)
      ├─ ContactRepository.nameFor(number)        (single SQL query)
      ├─ BlockRepository.decide(number, isContact) → RuleMatcher (pure)
      └─ response:
           BLOCK        → disallow + reject
           SILENCE      → silence
           SEND_TO_AI   → queue + CallAssistantService.start()
           ALLOW        → Announcer.announceIncoming() (+ recorder if recordAll)
```

### 3.2 Assistant call
```
CallAssistantService
  buildDecision()  → OnDeviceScreener.shouldAnswer + SpamScorer
  answer           → KavachInCallService.answerCurrent() else TelecomManager
  announce         → Announcer.speak(consent + greeting)
  capture          → AudioCaptureEngine (VOICE_CALL → RECOGNITION → MIC) → WavWriter
  on end           → CloudAiClient.transcribe (if cloud AI on)
                   → AssistantOrchestrator.finish → TranscriptEntity + CallBrief
                   → CallBriefNotifier.notifyBrief
```

### 3.3 Contact write
```
ContactEditScreen → ContactsViewModel.save(draft)
  → ContactRepository.saveDraft
      → ContactsWriter.insert/update   (device store, fails soft)
      → ContactDao.upsert              (Kavach enriched copy + t9Key)
```

## 4. Persistence

| Store | Contents | Notes |
|---|---|---|
| Room `kavach.db` v2 | contacts_enriched, block_rules, call_log, recordings, transcripts | Migration 1→2 adds `t9Key`, de-dupes call_log, adds unique index |
| DataStore `kavach_settings` | theme, announcer mode, AI flags, recordAll, onboardingDone, autoCheckUpdates | Flow for UI + `snapshot` for synchronous service reads |
| Device `ContactsContract` | contacts | Read + write-back via ContactsReader/Writer |
| Device `CallLog` | call history | Read-only mirror |
| Device `Telephony.Sms` | messages | Read + send |
| App files | recordings (`files/recordings`, `files/assistant`), update APK (`cache/updates`) | |

## 5. Dependency choices (and honest deviations from the master spec)

| Concern | Spec asked for | Kavach uses | Why |
|---|---|---|---|
| Networking | Retrofit or Ktor + OkHttp | **OkHttp + kotlinx.serialization directly** | One less dependency surface; Retrofit's kotlinx converter failed to resolve in CI. Functionally equivalent for the endpoints involved. |
| Image loading | Coil | **coil-compose 2.6.0** — device contact photos in `ContactsScreen` | ✓ |
| DI | "dependency injection" | Hilt | ✓ |
| DB | Room | Room | ✓ |
| Prefs | DataStore | DataStore | ✓ |
| Background | WorkManager | **Used**: `CallLogSyncWorker` (6 h, network-constrained) + `CacheCleanupWorker` (daily retention) | ✓ |
| Architecture | Clean Architecture, MVVM | MVVM + repository + **`domain/usecase` layer** (5 use cases) | ✓ — services and ViewModels now call use cases for cross-source logic |
| Backend | REST + PostgreSQL + Redis | **Built**: `backend/` (FastAPI + SQLAlchemy + Postgres + Alembic), 40 tests passing | Not deployed, and the Android client does not call it yet |
| Backend framework | "maintainable server framework" | FastAPI (Python) rather than Ktor | Chosen because its tests can actually be **run and verified** in this environment, which the spec requires before claiming anything works |

## 6. Build & release

- Gradle Kotlin DSL, AGP 8.5.2, Kotlin 1.9.24, compileSdk/targetSdk 34, minSdk 24.
- `./gradlew :app:assembleDebug` / `:app:testDebugUnitTest`.
- CI (`ci.yml`) runs unit tests + debug assemble on every push.
- Release (`release.yml`) on tag `v*` or manual dispatch: restores the keystore from
  GitHub Secrets, builds signed APK + AAB, bumps `versionCode` from the run number,
  publishes a GitHub Release.
- Signing key never enters the repo (`.gitignore` + `keystore/README.md`).

## 7. Backend

A separate service lives in `backend/` (FastAPI + SQLAlchemy + PostgreSQL +
Alembic, Docker Compose, its own CI). It implements `API_CONTRACT.md`, including
the honest `503` behaviour for the AI endpoints when no provider is configured.

**It is not deployed and the Android client does not call it yet** —
`CloudAiClient` still points at `https://api.kavach.app/`, which does not exist.
Wiring the client to the real backend is a pending task.

## 8. What is deliberately absent

- No crowdsourced database population (the service exists; no data does).
- No trained ML model — `OnDeviceScreener` is keyword rules.
- No analytics/crash SDK.
- No billing — Kavach is free, by design, with no paywall or entitlement system.
- Nothing is device-verified (no Android SDK or emulator in the build environment).
