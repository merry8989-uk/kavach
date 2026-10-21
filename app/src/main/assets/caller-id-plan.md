# Consent-Based Caller ID + Spam Reporting App
### Architecture Plan, MVP Plan, and a Ready-to-Use Build Prompt
**Platform:** Android (Kotlin) · **Backend:** Python (FastAPI) · **DB:** PostgreSQL

---

## 0. What we are building (and the line we will not cross)

A caller-identification and spam-blocking app for India where **every piece of personal data comes from the number owner's own consent, or is a community opinion (a spam report), or is a public business listing.**

We deliberately do **not** build the classic "upload everyone's phonebook" model, and we do **not** scrape any third-party caller-ID site. Names shown in the app come from exactly three lawful sources:

1. **Self-declared profiles** — a user sets their own display name after verifying their number (consent).
2. **Verified business / public listings** — numbers published by businesses themselves.
3. **Community spam labels** — "Spam", "Telemarketer", "Fraud" etc., which are opinions about a number, not a claim about a person's identity.

This keeps the product useful (identify unknown and spam callers) while staying defensible under India's DPDP Act 2023.

---

## 1. Guiding principles (non-negotiables)

| # | Principle | What it means in practice |
|---|-----------|---------------------------|
| 1 | **Consent first** | A number's real name is published only if its owner opted in. No bulk phonebook uploads. |
| 2 | **Data minimisation** | Store phone numbers **hashed** (HMAC-SHA256 + server pepper). Keep raw numbers only transiently where the law/flow requires. |
| 3 | **Purpose limitation** | Data is used only for caller ID and spam detection. Never sold, never shared with ad networks. |
| 4 | **User control** | Users can view, correct, and delete their data; opt out of having their name published at any time. |
| 5 | **Honest sourcing** | The app never claims a name is authoritative. Every result carries a confidence + source tag. |
| 6 | **Abuse resistance** | Spam reports are rate-limited, weighted, and moderated so a coordinated group can't bury a number. |
| 7 | **No hidden scraping** | We never scrape third-party sites, and we never hide where our data came from. |

---

## 2. High-level architecture

```
┌───────────────────────────────────────────────────────────────┐
│                      ANDROID CLIENT (Kotlin)                  │
│                                                               │
│  UI (Jetpack Compose)                                         │
│   • Onboarding / OTP      • Number search                     │
│   • Settings & consent    • Report a number                   │
│   • Caller-ID notification/overlay                            │
│                                                               │
│  CallScreeningService (ROLE_CALL_SCREENING)  ──┐              │
│  WorkManager sync                             │              │
│  Room local cache (encrypted)                 │              │
│  Retrofit/OkHttp  ────────────────────────────┼───────┐      │
└───────────────────────────────────────────────┼───────┼──────┘
                                                 │       │
                                     HTTPS / JWT │       │
                                                 ▼       │
┌───────────────────────────────────────────────────────────────┐
│                    BACKEND (FastAPI, Python)                  │
│                                                               │
│  API layer (routers)                                          │
│   • /auth        OTP request + verify → JWT                   │
│   • /me          profile, consent flags, delete account       │
│   • /lookup      number → {name?, spam score, carrier, ...}   │
│   • /reports     submit / aggregate spam reports              │
│                                                               │
│  Services                                                     │
│   • AuthService     (OTP, JWT, rate limit)                    │
│   • LookupService   (hash → identity, cache-aware)            │
│   • SpamService     (scoring, weighting, thresholds)          │
│   • ConsentService  (publish/opt-out, audit log)              │
│                                                               │
│  Data                                                         │
│   • PostgreSQL (hashed numbers, profiles, reports, scores)    │
│   • Redis (rate limiting, hot-number cache, OTP store)        │
│   • Background worker (score recompute, moderation jobs)      │
└───────────────────────────────────────────────────────────────┘
             │                          │
             ▼                          ▼
     SMS gateway (OTP)          Admin console / moderation
   MSG91 / Twilio / AWS SNS     (internal web UI)
```

**Component responsibilities**

- **Android client** — verifies the user, lets them search a number, detects incoming calls, shows the identified name / spam label, and lets them report spam. Caches lookups locally so repeat calls need no network.
- **FastAPI backend** — the single source of truth. Authenticates users, answers lookups, aggregates reports into a spam score, and enforces consent + rate limits.
- **PostgreSQL** — durable store. **Redis** — fast, ephemeral: OTP codes, rate-limit counters, hot-number cache.
- **SMS gateway** — delivers OTPs. Keep it behind an interface so you can swap providers.
- **Admin console** — a simple internal web UI to review abuse, approve verified business names, and handle grievances.

---

## 3. Data model (PostgreSQL)

All phone numbers are stored as `phone_hash = HMAC_SHA256(pepper, E.164_number)`. The raw number is not stored in the main tables.

```
users
  id                uuid PK
  phone_hash        text UNIQUE          -- hashed E.164
  phone_country     char(2)
  is_verified       bool
  created_at        timestamptz
  last_seen_at      timestamptz
  trust_score       smallint default 0   -- grows with age + good behaviour

profiles
  user_id           uuid PK FK -> users
  display_name      text                 -- what the owner wants shown
  publish_name      bool default false   -- consent to show name publicly
  allow_spam_reports bool default true
  updated_at        timestamptz

numbers
  id                bigserial PK
  phone_hash        text UNIQUE
  country           char(2)
  carrier           text
  telecom_circle    text
  line_type         text                 -- mobile | landline | voip | unknown
  first_seen_at     timestamptz

name_entries                          -- multiple candidate names per number
  id                bigserial PK
  number_id         bigint FK -> numbers
  name              text
  source            text                 -- self_declared | business | community
  confidence        smallint             -- 0..100
  contributor_user  uuid NULL FK -> users
  is_active         bool default true
  created_at        timestamptz

spam_reports
  id                bigserial PK
  number_id         bigint FK -> numbers
  reporter_user     uuid FK -> users
  category          text                 -- fraud | telemarketing | robocall | other
  comment           text NULL
  weight            smallint             -- derived from reporter trust
  created_at        timestamptz
  UNIQUE (number_id, reporter_user, category)   -- one report per category per user

spam_scores
  number_id         bigint PK FK -> numbers
  score             smallint             -- 0..100
  report_count      int
  distinct_reporters int
  last_report_at    timestamptz
  updated_at        timestamptz

consent_events                        -- append-only audit trail
  id                bigserial PK
  user_id           uuid FK -> users
  action            text                 -- publish_name_on | publish_name_off | delete_account | ...
  created_at        timestamptz

moderation_queue
  id                bigserial PK
  number_id         bigint FK -> numbers
  reason            text                 -- flagged_by_threshold | user_appeal | ...
  status            text                 -- pending | approved | rejected
  created_at        timestamptz
```

---

## 4. Core flows

### 4.1 Onboarding (consent-based profile)
1. User enters phone → `POST /auth/otp/request` → backend sends OTP via SMS.
2. User enters OTP → `POST /auth/otp/verify` → backend returns JWT + `is_new`.
3. If new, show a **consent screen**: "Publish your name so people who call you know who you are?" (default OFF).
4. If the user opts in → `PUT /me/profile` sets `display_name` + `publish_name = true`, and a `consent_events` row is written.

### 4.2 Incoming-call identification
1. `CallScreeningService` fires with the incoming number.
2. Client checks the **Room cache** first.
3. On miss → `GET /lookup/{number}` (number sent over TLS; can be hashed client-side too).
4. Backend returns the best name + spam score + carrier/circle.
5. Client shows a **heads-up notification** (and optionally an overlay if you later take the default-dialer role).
6. Result is cached locally for next time.

### 4.3 Manual number search
Same lookup path, triggered from a search box. Works with **no special permission** — this is the easiest way to demo the backend in MVP.

### 4.4 Spam reporting
1. User taps "Report spam" → picks a category → optional comment.
2. `POST /reports` → backend validates, dedupes, stores with a reporter weight.
3. A background job recomputes the number's `spam_scores`.
4. Once the score crosses a threshold, the app labels the number "Likely spam" / "Spam".

### 4.5 Opt-out / deletion
`DELETE /me` → hard-delete the user's profile, deactivate their self-declared name entry, and keep only an anonymous aggregate report count. A `consent_events` row is appended.

---

## 5. Privacy, consent & DPDP Act 2023 compliance

| Requirement (DPDP Act) | How we satisfy it |
|------------------------|-------------------|
| **Notice + consent** | Explicit consent screen at onboarding; separate toggle for "publish my name". |
| **Purpose limitation** | Data used only for caller ID and spam detection. Documented in the privacy policy. |
| **Data minimisation** | Numbers stored hashed; no contact-book uploads; no raw logs of lookups beyond what's needed. |
| **Storage limitation** | Spam reports older than N months are aggregated and the raw rows pruned. |
| **Data principal rights** | In-app access, correction, and deletion; a named **Grievance Officer** with contact details in the app. |
| **Security safeguards** | TLS everywhere, encrypted Room DB, hashed identifiers, least-privilege DB roles, secrets in a vault. |
| **Breach notification** | Runbook to notify the Data Protection Board and affected users within the mandated window. |
| **Children's data** | No targeted processing of minors' data; age gate where required. |

**Hard rules to encode in code review:**
- Never publish a name whose source is not `self_declared` or `business`.
- Never let a user look up more than the rate limit allows (anti-harvesting).
- Never log raw phone numbers to plaintext logs.
- Always honour an opt-out: an opted-out number returns **no name**, only carrier/circle/spam.

> ⚠️ **This document is engineering guidance, not legal advice.** Before launch, have an Indian technology lawyer review your DPDP compliance, the privacy policy, and the grievance process.

---

## 6. Spam scoring design

A transparent, tunable score (0–100). Start simple, then refine with real data.

```
raw =  (category_weighted_reports)
     * recency_factor
     * reporter_diversity_factor
     -  whitelist_penalty

score = clamp(round(raw), 0, 100)
```

- **category_weighted_reports** — `fraud=3`, `robocall=2`, `telemarketing=1.5`, `other=1`, summed with each report's reporter weight.
- **recency_factor** — reports decay over time (e.g. half-life of 90 days).
- **reporter_diversity_factor** — many *distinct* reporters count for more than one reporter filing repeatedly.
- **whitelist_penalty** — verified business numbers and numbers with a self-declared name get a reduction.

**Thresholds (initial):** `0–39 = clean`, `40–69 = likely spam`, `70–100 = spam`.

**Anti-abuse:**
- One report per (number, user, category).
- New/low-trust accounts carry reduced weight (`trust_score`).
- Rate-limit reports per user per day.
- Flag sudden bursts from correlated accounts for moderation.

---

## 7. Android client architecture (Kotlin)

- **UI:** Jetpack Compose, Material 3.
- **Architecture:** MVVM + clean-ish layering (`ui` → `domain` → `data`).
- **DI:** Hilt.
- **Local:** Room (encrypted with SQLCipher) for the lookup cache + user prefs.
- **Network:** Retrofit + OkHttp + Kotlinx Serialization; auth via an OkHttp interceptor that attaches the JWT and refreshes on 401.
- **Background:** WorkManager for periodic cache sync + report upload retry.
- **Call handling:** `CallScreeningService` under `ROLE_CALL_SCREENING` for MVP (Play-approved for this purpose). A full **overlay on incoming calls** realistically requires the default-dialer role (`ROLE_DIALER` + `InCallService`) — plan that as a later phase, because Play restricts `READ_CALL_LOG`/overlay for non-dialer apps.
- **Notifications:** `NotificationManager` heads-up notification showing the identified name / spam label.

Suggested module layout:

```
app/src/main/java/com/yourapp/
  ui/            onboarding, search, settings, report, callerid
  domain/        usecases: LookupNumber, SubmitReport, SetProfile
  data/
    local/       Room entities, DAO, cache
    remote/      Retrofit api, dto, AuthInterceptor
    repo/        LookupRepository, ProfileRepository, ReportRepository
  callscreening/ CallScreeningServiceImpl
  work/          SyncWorker, ReportUploadWorker
  di/            Hilt modules
```

---

## 8. Backend architecture (FastAPI)

```
app/
  main.py
  api/
    routers/ auth.py  me.py  lookup.py  reports.py  admin.py
    deps.py          # auth dependency, rate-limit dependency
  core/
    config.py        # settings via pydantic-settings / env
    security.py      # JWT, HMAC hashing, pepper
    ratelimit.py     # Redis-backed
    logging.py       # structured, PII-scrubbed
  models/            # SQLAlchemy models
  schemas/           # Pydantic request/response
  services/
    auth_service.py  lookup_service.py  spam_service.py  consent_service.py
  db/
    session.py  migrations/ (Alembic)
  workers/           # arq / Celery: recompute scores, prune, moderation
  tests/
```

- **Auth:** OTP → short-lived JWT access token + refresh token.
- **Rate limiting:** Redis counters per phone/IP/user (protects OTP and lookup endpoints).
- **Hashing:** `HMAC_SHA256(pepper, e164)`; pepper kept in a secret manager, never in the repo.
- **Caching:** hot-number lookups cached in Redis with a short TTL.

---

## 9. API contract (v1)

```
POST /auth/otp/request        { phone }                       -> { request_id }
POST /auth/otp/verify         { request_id, otp }             -> { access_token, refresh_token, is_new }
GET  /me                                                      -> { phone_masked, display_name, publish_name, ... }
PUT  /me/profile              { display_name, publish_name }  -> { ok }
DELETE /me                                                    -> { ok }

GET  /lookup/{number}                                         -> {
                                                                   name?: string,
                                                                   name_source?: "self_declared"|"business"|null,
                                                                   spam_score: 0..100,
                                                                   spam_label: "clean"|"likely_spam"|"spam",
                                                                   carrier?, telecom_circle?, line_type?
                                                                 }

POST /reports                 { number, category, comment? }  -> { ok, number_report_count }
GET  /numbers/{number}/reports                                -> { count, top_categories }

# admin (JWT + role=admin)
GET  /admin/moderation/queue
POST /admin/moderation/{id}   { decision }
```

**Errors:** consistent JSON `{ "error": { "code", "message" } }`. **Versioning:** prefix `/v1` when you go public.

---

## 10. MVP plan

**Goal:** prove the loop end-to-end — *verify → look up a number → show name/spam → report spam* — on Android, against your own FastAPI backend.

**MVP scope (in):**
- OTP auth + consent-based profile
- Manual number search with name (if self-declared/business) + spam label
- Spam reporting + basic scoring
- Incoming-call identification via `CallScreeningService`
- Admin moderation (minimal: approve/reject verified business names)

**Explicitly out of MVP:** iOS, contact-book uploads, default-dialer overlay, business self-serve portal, ML scoring, multi-country.

### Phases & rough timeline (solo dev, part-time)

| Phase | Deliverable | ~Time |
|-------|-------------|-------|
| **0. Setup** | Repo, CI, Postgres+Redis, FastAPI skeleton, Android project, secrets management | 3–4 days |
| **1. Auth & consent** | OTP request/verify, JWT, profile + consent screen, `consent_events` | 1 week |
| **2. Lookup core** | `/lookup`, hashing, Redis cache, Room cache, manual search screen | 1.5 weeks |
| **3. Spam reporting** | `/reports`, dedupe, weighting, scoring job, label in UI | 1 week |
| **4. Call screening** | `ROLE_CALL_SCREENING`, incoming-call heads-up notification, cache-first lookup | 1.5 weeks |
| **5. Admin & hardening** | Moderation queue, rate limits, PII-scrubbed logs, DPDP pages (privacy policy + grievance officer) | 1 week |
| **6. Beta** | Closed beta, instrument, iterate on scoring thresholds | ongoing |

**Success metrics for MVP:** lookup p95 < 300 ms (cache hit < 20 ms) · ≥ 40% of beta users file ≥ 1 report · spam label precision ≥ 70% on a hand-labelled sample · zero plaintext phone numbers in logs.

---

## 11. Later (post-MVP) roadmap

- Default-dialer mode for a true incoming-call overlay.
- Verified business portal (claim a number, show logo + category).
- On-device scoring model (privacy-preserving, numbers never leave the phone).
- Community reputation for reporters.
- iOS app.
- Carrier/telecom enrichment APIs for circle + line type.

---

## 12. Risks & mitigations

| Risk | Mitigation |
|------|-----------|
| Play Store rejects call-related permissions | Use `CallScreeningService` (approved use); justify core functionality; no `READ_CALL_LOG` until default-dialer phase. |
| Number harvesting / scraping your API | Rate limits, auth on every lookup, anomaly detection, no bulk endpoint. |
| Coordinated false spam reports | Reporter trust weighting, burst detection, moderation queue. |
| Consent/privacy challenge | Consent-only names, opt-out honoured, DPDP audit trail, legal review before launch. |
| Cold-start (empty DB) | Seed with public business listings + rely on spam labels early; names grow as owners opt in. |

---

## 13. The copy-paste prompt for your AI coding assistant

> Paste everything between the lines below into your AI coding assistant (Cursor / Copilot / Claude / etc.) to scaffold and extend the app. Keep it as your project's standing instruction.

---

**PROJECT: Consent-Based Caller ID & Spam Reporting App**

You are a senior Android + Python engineer helping me build a caller-ID and spam-reporting app. Follow these rules exactly.

**Stack**
- Android client: Kotlin, Jetpack Compose (Material 3), MVVM, Hilt, Room (encrypted), Retrofit + OkHttp + Kotlinx Serialization, WorkManager. Min SDK 26.
- Backend: Python 3.12, FastAPI, SQLAlchemy 2.x, Alembic, Pydantic v2, PostgreSQL, Redis. Async everywhere (`async def`, async SQLAlchemy).
- Auth: phone OTP → short-lived JWT access token + refresh token.

**Non-negotiable product rules**
1. A number's real name is shown ONLY if its source is `self_declared` (owner consented) or `business` (verified public listing). Never invent names. Never upload contact books. Never scrape third-party caller-ID sites.
2. All phone numbers are stored as `HMAC_SHA256(pepper, e164)`. Never store or log raw numbers in plaintext. The pepper comes from env/secret manager.
3. Every lookup result carries a confidence/source tag; the UI must never imply a name is authoritative.
4. Honour opt-out: if a number owner opts out, return no name — only carrier/circle/spam.
5. Spam labels are community opinions, not identity claims.

**Architecture to follow**
- Backend layers: `api/routers` → `services` → `models`/`db`. Keep business logic in `services`, never in routers.
- Android layers: `ui` → `domain` (usecases) → `data` (`local`, `remote`, `repo`). UI never touches Retrofit or Room directly.
- Cache-first on the client: check Room before the network for lookups.
- Redis for OTP storage and rate limiting. Background worker recomputes spam scores.

**Data model** (Postgres): `users`, `profiles`, `numbers`, `name_entries`, `spam_reports`, `spam_scores`, `consent_events`, `moderation_queue`. Use hashed phone numbers as the join key. See the architecture doc for columns.

**API (v1)**
- `POST /auth/otp/request {phone}` → `{request_id}`
- `POST /auth/otp/verify {request_id, otp}` → `{access_token, refresh_token, is_new}`
- `GET /me`; `PUT /me/profile {display_name, publish_name}`; `DELETE /me`
- `GET /lookup/{number}` → `{name?, name_source?, spam_score, spam_label, carrier?, telecom_circle?, line_type?}`
- `POST /reports {number, category, comment?}`; `GET /numbers/{number}/reports`
- Admin: `GET /admin/moderation/queue`, `POST /admin/moderation/{id} {decision}`
- Errors: `{"error": {"code", "message"}}`.

**Spam scoring**
`raw = category_weighted_reports * recency_factor * reporter_diversity_factor - whitelist_penalty`, clamped 0–100. Weights: fraud=3, robocall=2, telemarketing=1.5, other=1. Thresholds: 0–39 clean, 40–69 likely spam, 70–100 spam. One report per (number, user, category); weight reports by reporter trust; decay over ~90-day half-life.

**Android call handling**
Use `CallScreeningService` under `ROLE_CALL_SCREENING` for incoming-call identification. Show a heads-up notification with the identified name or spam label. Do not request `READ_CALL_LOG` or overlay permissions in MVP. Cache results in Room.

**Security & privacy**
TLS everywhere; PII-scrubbed structured logging; Redis-backed rate limits on OTP and lookup; least-privilege DB roles; secrets from env, never hardcoded. Provide in-app account deletion and a grievance-officer contact.

**What I want you to do now**
1. Scaffold the FastAPI backend and the Android app with the structure above.
2. Implement Phase 1 (auth + consent profile) and Phase 2 (lookup + manual search) first, with tests.
3. For each endpoint, give me the Pydantic schema, the SQLAlchemy model, the service method, and a pytest.
4. Follow the code style of the existing codebase; write small, focused commits.
5. If a requirement is ambiguous, ask me before guessing.

---

## 14. Suggested repository layout

```
callerid/
├── backend/
│   ├── app/                 # FastAPI app (see section 8)
│   ├── migrations/          # Alembic
│   ├── tests/
│   ├── pyproject.toml
│   └── .env.example
├── android/
│   ├── app/src/main/java/com/yourapp/   # (see section 7)
│   ├── build.gradle.kts
│   └── settings.gradle.kts
├── docs/
│   ├── architecture.md      # this document
│   ├── privacy-policy.md
│   └── dpdp-notes.md
└── README.md
```

---

*This is engineering guidance, not legal advice. Get the privacy policy, consent flows, and DPDP obligations reviewed by an Indian technology lawyer before you launch.*
