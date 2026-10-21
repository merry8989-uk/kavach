# Kavach — Implementation Status

**As of release v0.7.0.**

Status: **NOT_STARTED · IN_PROGRESS · IMPLEMENTED · VERIFIED · BLOCKED**

> ### Read this first
> **No app feature is VERIFIED.** The Android app has never been run on a device or
> emulator — the build environment contains no Android SDK and no device. Everything
> marked IMPLEMENTED compiles and passes CI, which is *not* the same as working.
>
> **The backend IS verified.** Its 40 tests were executed locally and in CI, and the
> Alembic migration was round-tripped (`upgrade → downgrade → upgrade`).

## 1. Counts

| Status | Features |
|---|---|
| IMPLEMENTED | 42 |
| IN_PROGRESS | 16 |
| NOT_STARTED | 26 |
| BLOCKED (client not wired to backend) | 4 |
| VERIFIED (app, on-device) | **0** |
| VERIFIED (backend) | **all 40 tests** |

## 2. Reconstruction pass (v0.7.0)

This release restructured the project to conform to the master specification.

**Backend — built from nothing (`backend/`)**
- FastAPI + SQLAlchemy 2.0 + PostgreSQL (SQLite for dev/tests) + Alembic.
- Implements `docs/API_CONTRACT.md`: lookup, reputation, business search, reports,
  identity corrections, privacy/deletion, and the three AI endpoints.
- **40 tests, all passing**, covering normalisation, scoring (caps, verified-business
  cap, false-positive discount, bounds), lookup (explicit no-result, source-trust
  ordering, auth), report deduplication, correction moderation, pagination,
  account deletion, and the AI endpoints' honest `503`.
- Alembic migration verified with a full round trip; 6 tables, 9 indexes.
- Dockerfile + docker-compose (Postgres + Redis + API), `.env.example`, own CI job.

**App — Clean Architecture and the missing spec dependencies**
- New `domain/` layer: `model/CallerIdentity` (moved out of `ai/`) and five use cases
  (`DecideCallAction`, `IdentifyCaller`, `SaveContact`, `SyncContacts`,
  `CheckForUpdates`). Cross-source logic now lives in use cases: the screening
  service, `ContactsViewModel` and `UpdateViewModel` call them instead of touching
  repositories directly.
- `SaveContactUseCase` adds real validation (name-or-number required, minimum
  subscriber length) that is unit-tested and cannot be bypassed by the UI.
- **WorkManager is now used**: `CallLogSyncWorker` (every 6 h, network-constrained)
  and `CacheCleanupWorker` (daily 30-day retention), scheduled from `KavachApp`.
  Dependencies come from a Hilt entry point, so WorkManager keeps its default
  initialiser.
- **Coil** added and used for real device contact photos, with a monogram fallback.
- **MockK + kotlinx-coroutines-test** added; use-case tests written.

## 3. What exists and compiles (app)

**Foundation** — single-activity Compose, Material 3, 5 tabs + stacked routes, Hilt DI,
Room v2 (5 tables, migration 1→2), DataStore settings, 5 theme packs, CI + signed
auto-release.

**Protection** — `KavachScreeningService` (Block/Silence/Send-to-AI/Allow),
`RuleMatcher` (8 rule types, unit-tested), `KavachInCallService`, `Announcer` (5 modes).

**Communication** — dialer (keypad, T9, dual-SIM, caller-ID preview), contacts
(read/sync/add/edit/delete with device write-back), call log (idempotent mirror,
filters), SMS threads + send, recordings playback.

**Intelligence** — `PhoneUtils` normalisation, local-first lookup, `SpamScorer`
(heuristics, unit-tested), `OnDeviceScreener` (**keyword rules, not a model**).

**Assistant** — `CallAssistantService` (answer → consent → greeting → capture →
transcribe → brief), `AssistantOrchestrator`, `CallBriefNotifier`.

**Platform** — first-run guided setup (permissions + dialer/caller-ID roles),
in-app update checker, WorkManager jobs.

## 4. What is partial, and exactly how

| Area | Reality |
|---|---|
| Recording | Capture works via fallback sources; **two-way audio unverified**. No encryption, quota, consent beep or region gating. |
| AI | Orchestration is real; the **backend now exists but the client is not wired to it**, so cloud paths still return placeholders. Dialogue policy and auto-SMS not built. |
| Caller ID | Local + heuristic only. The backend can serve remote lookups; the client does not call it yet, and there is no data in the database. |
| Blocking | Rule engine + screening work; SMS blocking, schedules, post-call prompt not built. |
| Contacts | CRUD + photos work; import/export and a full detail screen not built. |
| Settings | Real and persisted, but **no central feature registry (F16)** — modules cannot be independently disabled and there is no per-feature capability reporting. |
| Themes | 5 packs work; motion, reduced-animation and Hindi localisation not built. |
| Premium | Nothing. No billing, no entitlements. |

## 5. Blocked (client not wired to the backend)

| Feature | Blocked on |
|---|---|
| Remote caller lookup (F9.3) | Client integration with the backend |
| Crowdsourced tags (F9.4) | Same, plus data |
| Business directory (F9.6) | Same |
| Spam reputation from server (F9.5 remote half) | Same |

The **server side of all four now exists and is tested**; only the Android client's
`data/remote/` layer is missing.

## 6. Honest claims audit

| Claim | Verdict |
|---|---|
| "Assistant that takes the call first" | Accurate for the plumbing; transcription needs the backend wired up. Stated in the README. |
| `OnDeviceScreener` described as AI | **It is keyword rules.** Labelled as such in code, README and here. |
| "Call recording" | Stated with its Android 10+ restriction and per-device caveat. |
| Backend "works" | Backed by 40 executed tests and a verified migration. |
| Anything device-verified | **Nothing.** Stated at the top of this document. |

## 7. Environment limitations

1. No Android SDK/Gradle in the working sandbox → app builds run only in GitHub Actions.
2. No emulator or device → no instrumentation, no Compose UI tests, no call testing.
3. No hosting → the backend cannot be deployed or end-to-end tested from the app.
4. OEM-specific behaviour (MIUI, One UI) cannot be exercised.

## 8. Next phase

Per the spec's workflow, the next incomplete phase is **Phase 12 — the central feature
registry (F16)**: stable feature IDs, required permission/role/subscription, runtime
capability check, failure reason, and independent enable/disable per module. It needs
no backend and unblocks the spec's rule that every setting must control real behaviour.

Ordered plan after that:
1. **F16 feature registry** (next).
2. **Wire the Android client to the backend** — a `data/remote/` layer + `CallerLookupDataSource`, closing the four BLOCKED items.
3. **Security hardening** — SQLCipher + EncryptedFile (highest-risk gap).
4. **Phase 6 remainder** — in-call screen, favourites, speed dial, contact detail.
5. **Phase 9** — recording capability assessment in the UI, encryption, quota, consent.
6. **Phase 11** — billing with server-side verification.
7. **Testing** — Room migration tests, Compose UI smoke tests, lint in CI.
8. **Device verification** — run `TEST_PLAN.md` on real hardware; promote to VERIFIED.
