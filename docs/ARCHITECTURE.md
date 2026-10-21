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
│  Domain / data access   repositories                                                    │
│    ContactRepository · BlockRepository (+RuleMatcher) · CallLogRepository                │
│    SettingsRepository · UpdateChecker / UpdateInstaller                                  │
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
| Networking | Retrofit or Ktor + OkHttp | **OkHttp + kotlinx.serialization directly** | One less dependency surface; Retrofit's kotlinx converter failed to resolve in CI. Functionally equivalent for two endpoints. |
| Image loading | Coil | **Not used** — monogram avatars | No remote images exist yet; Coil becomes necessary when business logos/profile photos load. **Gap to close.** |
| DI | "dependency injection" | Hilt | ✓ |
| DB | Room | Room | ✓ |
| Prefs | DataStore | DataStore | ✓ |
| Background | WorkManager | Declared, **not yet used** | Scheduled sync/cleanup not implemented. **Gap.** |
| Architecture | Clean Architecture, MVVM | MVVM + repository, **without a separate domain layer of use-case classes** | Deviation: repositories are called directly from ViewModels. Acceptable at this size; a `domain/usecase` layer should be introduced when business logic grows. |
| Backend | REST + PostgreSQL + Redis | **Not built** | `API_CONTRACT.md` specifies it. |

## 6. Build & release

- Gradle Kotlin DSL, AGP 8.5.2, Kotlin 1.9.24, compileSdk/targetSdk 34, minSdk 24.
- `./gradlew :app:assembleDebug` / `:app:testDebugUnitTest`.
- CI (`ci.yml`) runs unit tests + debug assemble on every push.
- Release (`release.yml`) on tag `v*` or manual dispatch: restores the keystore from
  GitHub Secrets, builds signed APK + AAB, bumps `versionCode` from the run number,
  publishes a GitHub Release.
- Signing key never enters the repo (`.gitignore` + `keystore/README.md`).

## 7. What is deliberately absent

- No backend, no crowdsourced database, no trained ML model.
- No analytics/crash SDK.
- No billing.
- Nothing is device-verified (no Android SDK or emulator in the build environment).
