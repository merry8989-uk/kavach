# Logging — Kavach's own standard

This is how Kavach logs, and why it logs that way. It is written from what this
app needed, in the order the need appeared.

The one rule everything else follows:

> **A log line must carry intent, outcome and context together. Intent alone is
> not a log.**

Kavach learned this the hard way. The first implementation wrote free text:

```
2026-10-21 14:40:12 INFO  Screening  BLOCK ******3210
```

That records *that* something happened and never whether it worked. It is why a
user stuck in a 71-attempt permission loop was visible in the log and completely
unexplainable — 71 identical lines, none of them saying whether any attempt had
changed anything.

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
| Server-side logging | `backend/app/logging.py` |
| Server-side event validation | `backend/app/telemetry_schema.py` |
| CI gate | `app/src/test/java/com/kavach/caller/LoggingSchemaTest.kt` |

## The twelve kinds of log Kavach keeps

Two things are independent and both are on every line: **severity** (how bad) and
**category** (what kind). A failed login is an `error` *and* a `security` event.
Without the split, a log is one flat list sorted by severity, and "show me
everything that touched the database" is a text search rather than a filter.

| # | Category | Kavach's events | Where |
|---|---|---|---|
| 1 | **Application** | `session_start`, `screen_view`, `feature_toggle`, `feature_snapshot` | app |
| 2 | **Error** | `crash`, `anr`, `error_state_shown`, failed `api_request` | app + server |
| 3 | **Security** | `security_event`, `access_fix_requested`, `access_fix_result`, refused requests | app + server |
| 4 | **Performance** | `performance_marker`, `perf_sample`, `api_request.duration_ms` | app + server |
| 5 | **User activity** | `screen_view`, `rage_tap`, `dead_click`, `back_button_spam`, `drop_off`, `scroll_depth`, `time_to_first_value` | app |
| 6 | **Infrastructure** | process start, request lifecycle, service availability | server |
| 7 | **Network** | `api_request` — method, endpoint, status, latency, `request_id` | app + server |
| 8 | **Database** | `data_query_result`, `db_query` — operation, outcome, duration, rows | app + server |
| 9 | **Audit** | `audit_event`, and the server's `AuditLog` — who changed what, when, on which resource | app + server |
| 10 | **Analytics** | `analytics_event` — counts, kept apart from the raw stream on purpose | app |
| 11 | **Background** | `background_job` — job, outcome, duration, reason; `update_check` | app + server |
| 12 | **Trace** | `trace_step`, and `request_id` correlation across app and server | app + server |

Categories 1–5 and 7 existed from the start. **6, 9, 11 and 12 did not exist at
all** until schema 1.1.0 — the server had no structured logging whatsoever, so
infrastructure and database logs were uvicorn's default text, and nothing could be
filtered, counted or joined.

### Six severities

| Level | Meaning | Kept |
|---|---|---|
| `trace` | step-by-step detail, only wanted when something is wrong | 1 day |
| `debug` | debugging information | 1 day |
| `info` | normal event | 7 days |
| `warn` | potential problem | 14 days |
| `error` | operation failed | 30 days |
| `fatal` | critical failure — the process ended | 90 days |

`trace` and `fatal` were missing until 1.1.0. Their absence is why **a crash and
a caught exception looked identical** in the log. A fatal is kept longest because
it is the one worth still having a month later.

Nothing above `warn` is ever sampled, at any volume. A fatal that was sampled away
is a crash nobody can explain.

### One envelope, both sides

```
timestamp · severity · category · message · app_id · environment · request_id · metadata
```

`request_id` is what makes the app's log and the server's log one story rather
than two. The app mints it per user action, sends it as `X-Request-Id`, and the
server reuses it — so the tap, the request it caused and the query that answered
it all carry the same id.

Without it, the only way to join them is by timestamp, which is a guess.

The server writes the same shape from `backend/app/logging.py`. Logged headers are
an **allow-list**, not a deny-list: the set of headers that might carry a secret is
unbounded and the set worth logging is not. `authorization` is deliberately absent.

## Where the events go

**Nowhere by default. Kavach keeps its diagnostics on the device.**

The log is a file. It is written locally, redacted on the way in, retained on a
schedule, and read by the user in Settings → Diagnostics & logs. From there they
can export it, share it, or email it — when they choose to.

**There is no automatic upload. Nothing leaves the phone unless the user sends it.**

Earlier versions had remote destinations: the user's own Notion database, an
operator's server, a Supabase project. All were removed at v0.29.0 because they
shared one flaw — each asked the user to configure something (a token, a URL, a
key) before diagnostics did anything useful, and a diagnostic that only works
after setup is a diagnostic that mostly does not work.

The server code still exists and still works. The app simply does not call it. A
local file the user can hand over is the version that actually gets used.

## Checklist, and how each line is met

| Requirement | Implementation |
|---|---|
| Structured format, never free text | Every line is one JSON object; there is no free-text field |
| Intent + outcome + context together | `access_fix_requested` and `access_fix_result` are separate, linked by `attempt_id`; required properties are enforced |
| Correlation across the whole path | `session_id` per launch, `attempt_id` per fix attempt, `request_id` per user action — shared with the server |
| Session context once per session | Carried in the envelope by `SessionContext`, not re-read per event |
| Outcomes logged, not just requests | `outcome ∈ {success, no_op, failed}` is a required property of every action event |
| Levels used deliberately | six levels; nothing above `warn` is ever sampled |
| Sampling at scale | volume-aware: everything kept below 120 events/min, and warn/error/fatal always |
| Retention defined and enforced | fatal 90d, error 30d, warn 14d, info 7d, debug/trace 1d — pruned on every write |
| No PII; identical redaction | `Redaction` — forbidden keys plus value-level phone/email/path scrubbing |
| ISO 8601 UTC timestamps | `LogEvent.iso8601()` |
| Versioned schema | `EventSchema.VERSION` (currently 1.1.0), validated at runtime and in CI |
| Both sides on one schema | `backend/app/telemetry_schema.py` mirrors the catalogue, with a drift-guard test |
| Validated in CI | `LoggingSchemaTest` — 32 tests, on every push; 16 more in `backend/tests/test_logging.py` |

## The permission and role fix loop

This is the case that started all of it, and it is still the clearest example of
intent and outcome being logged together. `PermissionsScreen` records every fix
attempt and closes it out with a real outcome:

```
access_fix_requested   { permission, state_before }              attempt_id=att_ab12
access_fix_result      { action_taken, state_after, outcome }    attempt_id=att_ab12
```

Three attempts on the same permission in one session also raise
`permission_loop`, so the next "user is stuck" report explains itself.

`state` uses a fixed vocabulary: `granted`, `partial`, `denied`,
`permanently_denied`, `not_applicable`.

Since v0.32.0 the same event pair is also written when a permission is asked for
at the point of use, with `action_taken = request_contextually` — so the log can
answer the question the change was based on: did asking where it is needed work
better than asking everything up front?

## Privacy rules

Enforced in `Redaction`, not trusted to call sites:

- forbidden property names (`phone`, `number`, `email`, `name`, `token`, `body`,
  `file_path`, …) are replaced with `[redacted]` whatever the caller passes;
- a phone number, email address or absolute path is scrubbed **even under an
  innocent key**;
- route-like values (`api_request.endpoint`) are exempt from the path rule, so the
  log stays useful;
- `user_id` is a salted SHA-256 hash, never a raw identifier;
- crash logs carry a stack **hash**, never the stack;
- the server records an exception's **type and message only** — a traceback can
  carry local variable values, and a phone number in a traceback is a phone number
  in a log;
- a raw search query is recorded as its length only.

## Friction signals

`rage_tap` (3+ taps in 2s), `dead_click` (a tap that changed nothing),
`back_button_spam` (3+ back presses in 2s), `drop_off` and `time_to_first_value`.
Each fires once per burst, so a frustrated user does not flood the log.

## What a real export taught us

A diagnostics export from a TECNO LH8n (Android 14, three sessions) exposed six
things, all now fixed:

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
| Only **16 `screen_view` across 10 sessions**, and four sessions had none | A fixed 1-in-4 sample was discarding three quarters of the one event that shows what a user did | Sampling is now **volume-aware**: everything kept below 120 events/min |
| **12 `feature_toggle` rows** on a first run | A first run has no baseline, so every feature looked "changed" | A first run writes the baseline and emits one `feature_snapshot` |
| `screen_or_route` was `unknown` on **79%** of rows | Technically honest, completely useless | Launch-phase events say `app_start` |
| `time_to_first_value` in only **2 of 10 sessions** | It was emitted by the permissions screen, so sessions that skipped onboarding reported nothing | It is a property of the session and is emitted by the nav graph |

The sampling one was the most damaging, because it made the log look *quiet* when
it was being *censored*.

## Honest limits

- **`scroll_depth` is catalogued but not yet emitted** by a screen. The event is
  defined and validated; no screen calls it.
- **`analytics_event`, `db_query` and `trace_step` have no emit sites yet.** The
  catalogue and the API exist and are tested; the triggers do not. Analytics comes
  from a weekly review rather than a running app, Room queries are not centralised
  in one place to hook, and trace steps are development-only by design.
- **There is no log dashboard.** The on-device viewer and the export formats are
  the current way to read it.
- **The app's dependency coverage is Android lint's `Obsolete Gradle Dependency`
  advisories**, which report *age*, not vulnerabilities. A real scan needs a Gradle
  lockfile, which this project does not have.

## SMS classification

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
- **The message body never reaches the log.** Only the category does, and the OTP
  code is kept separate from the reasons specifically so the UI can offer to copy
  it without ever rendering it — and so it cannot leak into a preview or a log
  line.
