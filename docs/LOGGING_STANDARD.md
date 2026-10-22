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

## The twelve categories

The standard this was built against asks for twelve kinds of log, six severities,
and one envelope. Schema **1.1.0** covers all twelve, on both sides of the wire.

| # | Category | Kavach's events | Where |
|---|---|---|---|
| 1 | **Application** | `session_start`, `screen_view`, `feature_toggle`, `feature_snapshot` | app |
| 2 | **Error** | `crash`, `anr`, `error_state_shown`, failed `api_request` | app + server |
| 3 | **Security** | `security_event`, `access_fix_requested`, `access_fix_result`, refused requests | app + server |
| 4 | **Performance** | `performance_marker`, `perf_sample`, `api_request.duration_ms` | app + server |
| 5 | **User activity** | `screen_view`, `rage_tap`, `dead_click`, `back_button_spam`, `drop_off`, `scroll_depth`, `time_to_first_value` | app |
| 6 | **Server & infrastructure** | process start, request lifecycle, service availability | server |
| 7 | **API & network** | `api_request` (method, endpoint, status, latency, `request_id`) | app + server |
| 8 | **Database** | `data_query_result`, `db_query` (operation, outcome, duration, rows) | app + server |
| 9 | **Audit** | `audit_event`, `AuditLog` (moderation decisions, identity writes) | app + server |
| 10 | **Analytics** | `analytics_event` — counts kept apart from the raw stream | app |
| 11 | **Background jobs** | `background_job` (job, outcome, duration, reason), `update_check` | app + server |
| 12 | **Debug & trace** | `trace_step`, `request_id` correlation across app and server | app + server |

### Six severities, not four

`trace` and `fatal` were missing. Their absence is why a crash and a caught
exception looked the same in the log. Retention now follows the level: a **fatal
is kept 90 days**, an error 30, a warn 14, info 7, and debug/trace one.

Nothing above `warn` is ever sampled, at any volume. A fatal that was sampled away
is a crash nobody can explain.

### One envelope, both sides

```
timestamp · severity · category · message · app_id · environment · request_id · metadata
```

`request_id` is the field that makes this one story rather than two. The app mints
it per user action, sends it as `X-Request-Id`, and the server reuses it — so the
tap, the request it caused and the query that answered it all carry the same id.
Without it the only way to join them is by timestamp, which is a guess.

The server logs in the same shape from `app/logging.py`. Headers are an
**allow-list** rather than a deny-list, because the set of headers that might
carry a secret is unbounded and the set worth logging is not — `authorization` is
deliberately absent.

## Where the events go

**Nowhere by default. Kavach keeps its diagnostics on the device.**

The log is a file. It is written locally, redacted on the way in, retained on a
schedule, and read by the user in Settings → Diagnostics & logs. From there they
can export it, share it, or email it — when they choose to.

There is no automatic upload. Nothing leaves the phone unless the user sends it.

Earlier versions had remote destinations — the user's own Notion database, an
operator's server, a Supabase project. They were removed because they all shared
the same flaw: they asked the user to configure something (a token, a URL, a key)
before the app's diagnostics did anything useful, and a diagnostic that only
works after setup is a diagnostic that mostly does not work.

A local file that the user can hand over is the version that actually gets used.

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

## Many installs

Not applicable any more: the app does not send anything anywhere.

There are two destinations, sharing one queue:

| Destination | Use it when | Where the key lives |
|---|---|---|
| **Supabase** | **the default — managed Postgres, no server to run** | on the device; the anon key is public by design and row-level security does the protecting |
| The Kavach server | you also want to own the caller-lookup and consent traffic | on the server, never in the app |

The server path is `POST /v1/telemetry/events` — the app batches up to 100 schema
events per run, the server validates each against `telemetry_schema.py` and
stores it in `telemetry_events`. Both sides keep the same rules: off by default,
queued locally first, invalid events counted and skipped rather than blocking the
batch.

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
