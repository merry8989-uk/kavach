# Logging standard — how Kavach implements it

Kavach's diagnostics log is built to the shared logging checklist and event
schema (v1.0). The rule it enforces, in the standard's own words:

> a log must capture **intent, outcome and context** together, not intent alone.

The previous implementation broke this: it wrote free-text lines such as
`2026-10-21 14:40:12 INFO  Screening  BLOCK ******3210`. That recorded *that*
something happened and never whether it worked — the same blind spot that made a
71-attempt loop visible but unexplainable.

## Where it lives

| Concern | File |
|---|---|
| Event catalogue, enums, schema version | `data/diagnostics/EventSchema.kt` |
| Envelope + JSON codec | `data/diagnostics/LogEvent.kt` |
| Privacy gate | `data/diagnostics/Redaction.kt` |
| Session and device context | `data/diagnostics/SessionContext.kt` |
| Validation (runtime + CI) | `data/diagnostics/EventValidator.kt` |
| Writer, sampling, retention, export | `data/diagnostics/DiagnosticsRepository.kt` |
| Friction signals | `data/diagnostics/FrictionTracker.kt` |
| CI gate | `app/src/test/java/com/kavach/caller/LoggingSchemaTest.kt` |

## Checklist, and how each line is met

| Requirement | Implementation |
|---|---|
| Structured format, never free text | Every line is one JSON object; there is no free-text field |
| Intent + outcome + context together | `access_fix_requested` and `access_fix_result` are separate, linked by `attempt_id`; required properties are enforced |
| Correlation id | `session_id` per launch; `attempt_id` groups a request with its result |
| Session context once per session | Carried in the envelope by `SessionContext`, not re-read per event |
| Outcomes logged, not just requests | `outcome ∈ {success, no_op, failed}` is a required property of every action event |
| Levels used deliberately | `debug / info / warn / error`; errors and warnings always written |
| Sampling at scale | 100% of warn/error, 1-in-4 info, debug only in debug builds, plus an always-keep list |
| Retention defined and enforced | error 30d, warn 14d, info 7d, debug 1d — pruned on every write |
| No PII; identical redaction | `Redaction` — forbidden keys plus value-level phone/email/path scrubbing |
| ISO 8601 UTC timestamps | `LogEvent.iso8601()` |
| Versioned schema | `EventSchema.VERSION`, validated at runtime and in CI |
| Validated in CI | `LoggingSchemaTest` — 17 tests, run on every push |

## The permission and role fix loop

This is the standard's headline case, applied to Kavach. `PermissionsScreen`
records every fix attempt and closes it out with a real outcome:

```
access_fix_requested   { permission, state_before }              attempt_id=att_ab12
access_fix_result      { action_taken, state_after, outcome }    attempt_id=att_ab12
```

Three attempts on the same permission in one session also raise
`permission_loop`, so the next "user is stuck" report explains itself.

`state` uses the standard's vocabulary: `granted`, `partial`, `denied`,
`permanently_denied`, `not_applicable`.

## Privacy rules (Part F)

Enforced in `Redaction`, not trusted to call sites:

- forbidden property names (`phone`, `number`, `email`, `name`, `token`, `body`,
  `file_path`, …) are replaced with `[redacted]` whatever the caller passes;
- a phone number, email address or absolute path is scrubbed **even under an
  innocent key**;
- route-like values (`api_request.endpoint`) are exempt from the path rule, so
  the log stays useful;
- `user_id` is a salted SHA-256 hash, never a raw identifier;
- crash logs carry a stack **hash**, never the stack;
- a raw search query is recorded as its length only.

## Friction signals (Part E)

`rage_tap` (3+ taps in 2s), `dead_click` (a tap that changed nothing),
`back_button_spam` (3+ back presses in 2s), `drop_off` and
`time_to_first_value`. Each fires once per burst, so a frustrated user does not
flood the log.

## Honest limits

- The backend's own logs are not yet on this schema — only the Android app is.
- `scroll_depth` is defined in the catalogue but not yet emitted by a screen.
- There is no log dashboard; the on-device viewer and the export formats are the
  current way to read it.

## Automatic push to Notion

The dashboard can fill itself. `data/notion/` implements it:

| Concern | File |
|---|---|
| Token storage | `SecretStore` — Keystore-encrypted, never in the APK |
| Event → row mapping | `NotionEventMapper` |
| Queue, flush, status | `NotionSyncRepository` |
| Background upload | `work/NotionSyncWorker` + `WorkerScheduler` |

**How it works.** When the user switches it on, every event is appended to a
local queue file as it is written. A periodic worker flushes up to 40 rows per
run to `POST https://api.notion.com/v1/pages`, honouring a Wi-Fi-only constraint
if the user asked for one. Nothing is deleted from the queue until Notion has
accepted it, so a flight or an outage loses nothing.

**Why the user's own token.** Kavach ships no Notion credentials. A token baked
into the APK could be extracted and used to write to the workspace, so instead
the user creates an internal integration, shares the Kavach Diagnostics page with
it, and pastes the token into Settings. It is stored encrypted by the Android
Keystore — a copied preferences file is useless on another phone — and it is sent
only to `api.notion.com`.

**Consent.** Off by default. Nothing leaves the device until the toggle is on,
and turning it off stops the worker and leaves the queue untouched.

**Known limits.**
- Notion's API creates **one page per request** and rate-limits, so a flush is
  capped per run rather than hammering it. A large backlog drains over several
  runs.
- Rows that Notion rejects with a 4xx are dropped rather than retried forever;
  the rest of the queue continues.
- The `Device` and `Release` columns are written as fixed values for now.

## What a real export taught us

A diagnostics export from a TECNO LH8n (Android 14, three sessions) exposed six
things worth fixing, all now done:

| What the export showed | Fix |
|---|---|
| 9 of 14 rows were `feature_toggle`, identical on every launch | Emit it **only when a module's state changes**, diffed against the last launch |
| The config snapshot was **sampled**, so each session showed a different, incomplete subset | Snapshots are never sampled — a half-missing config reads as fact |
| `screen_name=number/{number}` — a raw route pattern | Routes map to readable names (`number_details`); parameterised segments never reach the log |
| `cold_start_ms` / `warm_start_ms` declared but always empty | Removed from the schema; startup cost is now a real `performance_marker(flow=app_start)` |
| Device `TECNO TECNO LH8n` | The repeated manufacturer prefix is dropped |
| No `crash` row anywhere, ever | An uncaught-exception handler now emits `crash` with a stack hash |

The `crash` gap was the most serious: the event was declared from the beginning
and nothing ever called it, so a crash produced no row at all — which reads as
"the user closed the app".

## What the second real export taught us

A raw `kavach.log` (64 events, ten sessions, TECNO LH8n) exposed five more
problems. All are fixed.

| What the log showed | Why it mattered | Fix |
|---|---|---|
| `device_model` was `TECNO` — the model was **gone** | The de-duplication that fixed "TECNO TECNO LH8n" had over-corrected | Keep the model whole when it already carries the maker; regression test added |
| Only **16 `screen_view` across 10 sessions**, and four sessions had none | A fixed 1-in-4 sample was discarding three quarters of the one event that shows what a user did | Sampling is now **volume-aware**: everything is kept below 120 events/min |
| **12 `feature_toggle` rows** on a first run | A first run has no baseline, so every feature looked "changed" | A first run writes the baseline and emits one `feature_snapshot` |
| `screen_or_route` was `unknown` on **79%** of rows | Technically honest, completely useless | Launch-phase events say `app_start` |
| `time_to_first_value` in only **2 of 10 sessions** | It was emitted by the permissions screen, so sessions that skipped onboarding reported nothing | It is a property of the session and is emitted by the nav graph |

The sampling one was the most damaging, because it made the log look *quiet*
when it was being *censored*.

## Supabase

A third destination, and the one that needs no server: `SupabaseUploader` appends
to a Postgres table over PostgREST. The table, its row-level security, four views
and a nightly retention job are in `supabase/schema.sql`; the walkthrough is in
`docs/SUPABASE.md`.

The security model is the part worth knowing: the app ships the **anon** key,
which is public by design, and the table grants it `insert` and nothing else —
there is deliberately no select policy, so that key cannot read a single row
back. Everything that protects the data lives in the SQL, not in the app.

## Many installs

Direct-to-Notion only works for the handful of users willing to create a Notion
integration and paste a token, and their rows would land in *their* workspace,
not yours. So there are two destinations, sharing one queue:

| Destination | Use it when | Where the token lives |
|---|---|---|
| The user's own Notion | one person, their own dashboard | on their device, Keystore-encrypted |
| **The Kavach server** | **any number of installs** | **on the server, never in the app** |

The server path is `POST /v1/telemetry/events` — the app batches up to 100
schema events per run, the server validates each against `telemetry_schema.py`
and stores it in `telemetry_events`, and `POST /v1/admin/notion/push` drains the
table into the operator's Notion database. Both sides keep the same rules: off
by default, queued locally first, invalid events counted and skipped rather than
blocking the batch.

## SMS classification (Phase 8)

`SenderId`, `LinkInspector` and `SmsClassifier` are pure Kotlin — no Android, no
IO, no clock — so every rule is testable. That matters because a wrong "spam"
verdict on a real bank message is the failure this feature must not have.

What is deliberately **not** claimed:

- **TRAI category flags are not decoded.** The letter that would carry that
  meaning is not consistent across operators, and guessing it would label real
  bank messages as spam. Category comes from content, where the evidence is.
- **Brands are only named from a fixed list of real headers.** An unrecognised
  header is reported without a brand, because inventing one would tell someone a
  scam is their bank.
- **Nothing is auto-blocked from a link alone.** Findings are reasons; the user
  decides.
- **The message body never reaches the log.** Only the category does, and the
  OTP code is kept separate from the reasons specifically so the UI can offer to
  copy it without ever rendering it — and so it cannot leak into a preview or a
  log line.
