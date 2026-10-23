# Kavach — the hybrid plan

Three documents were written for this project at different times, by different
hands, at different levels of abstraction. They overlap, they disagree in places,
and none of them is a plan on its own. This merges them into one.

**At release v0.38.0.** Everything below was checked against the code, not against
the previous status document.

---

## 1. The three inputs, and what each is actually for

| Source | What it is | What it contributes | What it cannot do |
|---|---|---|---|
| **`paste-1.md`** — 17 phases | The master specification | The complete surface: what a caller-ID app must contain, phase by phase | Reads as a checklist. Every phase is "done" when the file says so, which is not the same as working |
| **`paste-1-2.md`** — external review | An outsider reading the repository | **Priority.** Eight things a user would notice, plus a backend defect table with a P0–P2 ranking | Its roadmap was written before most of it was built, so it describes work now finished |
| **`paste-1-3.md`** — logging taxonomy | A logging reference | Twelve log categories, six severities, one envelope | Written for a different product. Its *examples* do not apply; its *structure* does |

**How they fit together.** The specification says what must exist. The review says
what to fix first and why. The taxonomy says how to see whether any of it is
working in the field. That is a complete loop, and it is why the merge is worth
doing rather than picking one.

**Where they conflict, and how each was settled:**

| Conflict | Settled |
|---|---|
| `paste-1.md` Phase 11 requires premium tiers; the instruction was "totally free" | **Excluded.** Not deferred — not wanted |
| `paste-1.md` says request permissions contextually; an early instruction said ask for everything at first launch | **Contextual**, with a one-tap bulk grant kept as an explicit choice (v0.32.0) |
| The review's 5-phase roadmap vs the spec's 17 phases | Merged into **six workstreams** below, which neither document has |
| The taxonomy's examples (job search, feed imports) | Dropped. The twelve categories were re-expressed in Kavach's own events |

---

## 2. The merged plan — six workstreams

Neither source document has this shape. The spec is by phase, the review is by
priority, and neither groups work by *what it is for*. That is what a plan needs.

### A · Trust — the project must not claim what is untrue

Everything here is a place where the app, the docs, or a status field said
something that was not so. This comes first because a missing feature is visible
and a false claim is not.

| | Item | State |
|---|---|---|
| A1 | Permissions requested all at once against the spec's contextual rule | **Done** v0.32.0 — the real cause was a gate in the nav, not the dialog |
| A2 | No home dashboard; the app opened on a keypad | **Done** v0.34.0 |
| A3 | The moderation queue was write-only — flags were written and never read | **Done** v0.33.0 |
| A4 | False-positive appeals were a column, not a flow | **Done** v0.33.0 — and approving one now *clears the score* |
| A5 | The status document was twelve releases out of date | **Done** — and this plan replaces it |
| A6 | Lint was not in CI, and hid 17 real crashes | **Done** v0.30.0 |

**A6 is the one worth remembering.** `startForegroundService` and
`ThreadLocal.withInitial` are API 26, `setSilenceCall` and `RoleManager` are API
29, and minSdk is 24 — so the app crashed on Android 7. It compiled. It passed
136 tests. Nothing said so until lint was turned on.

### B · Verification — proving any of it works

The largest genuine gap, and the one that makes every other claim provisional.

| | Item | State |
|---|---|---|
| B1 | Nothing is verified on a device | **Open — needs a phone.** 15 checks in `docs/DEVICE-VALIDATION.md` |
| B2 | The self-test has never been run either | **Open** — it is the tool for B1, so it is checked first |
| B3 | The backend has never run against PostgreSQL in anger | **Open** — 157 tests pass against SQLite |
| B4 | No instrumentation or UI tests | **Open, with a decision** — CI has no emulator; tests that never run are worse than none |

### C · Backend — from "complete" to "usable"

The review's P0–P2 table, re-checked against the code.

| Review item | Priority | State |
|---|---|---|
| Real OTP/SMS provider | P0 | **Done** — MSG91 + Twilio adapters; returns 503 rather than faking success when unconfigured |
| Secure authentication | P0 | **Done** v0.28.0 — app tokens separated from user JWTs; the deletion bug that followed is fixed |
| Distributed rate limiting | P0 | **Done** v0.26.0 — Redis-backed with an honest in-process fallback |
| Data deletion bug | P0 | **Done** v0.26.0 — reports were deleted before the query that looked for corrections |
| Database consistency | P1 | **Partly** — the lookup response, score and counts agree; no test asserts it end-to-end |
| Reliable offline fallback | P1 | **Done** — 2.5 s screening deadline, fails open |
| Real integration tests | P1 | **Partly** — 157 backend tests, but no full journey test |
| Monitoring | P2 | **Done** v0.42.0 — a Cloudflare Worker sink in `monitoring/`, a dashboard, and a log shipper on the server. Off by default; the sink refuses any event carrying a `user_code` |
| **Self-hosting** | — | **Done** v0.38.0 — one command, and the app can now point at it |

### D · Caller experience — the eight things a user notices

The review's first section, item by item.

| # | The review asked for | State |
|---|---|---|
| 1 | Smart incoming-call screen: name, business category, spam warning **with a reason** | **Done** v0.40.0 — `CallerVerdict` produces a headline and a reason, and the reason always attributes the claim ("Reported by 12 people… This is a community report, not a verified finding") |
| 2 | Simple home screen: search, recent, protection status, short setup | **Done** v0.34.0 |
| 3 | Reliable search results: source, risk, freshness, "unavailable" + retry | **Done** v0.40.0 — the DTO already carried `lastUpdated`, `freshness` and `unavailableReason`; the app simply was not showing them. All three now surface, with a retry that appears only when a retry could help |
| 4 | Customisable spam protection: toggles, thresholds, allowlist, undo | **Done** v0.40.0 — the review was explicit that a score must not kill a genuine call on its own, so the threshold ships **off** and `ScorePolicy` enforces three rules: off means off, a saved contact is never acted on, and the gentlest action is the default |
| 5 | Privacy and permissions centre | **Done** — `docs/PERMISSIONS.md` and the data-policy screen |
| 6 | Advanced settings, every toggle doing something real | **Done** — the feature registry reports why a module cannot run |
| 7 | Trustworthy reporting: categories, abuse control, appeal, confirmation | **Done** v0.33.0 |
| 8 | Lightweight and accessible: Hindi, dark mode, low-end, offline states | **Mostly** — Hindi, dark mode and a motion setting are done; launch time is unmeasured |

### E · The remaining specification gaps

| | Item | Phase | State |
|---|---|---|---|
| E1 | Search history | 7 | **Done** v0.37.0 |
| E2 | Motion / reduced-animation settings | 14 | **Done** v0.37.0 |
| E3 | Missed-call prioritisation | 10 | **Done** v0.37.0 |
| E4 | Business-claim flow (how a listing becomes *verified*) | 7 | **Done** v0.41.0 — `Business.verified` had existed since the first schema with nothing ever setting it. A claim now files to the moderation queue, and approving one publishes the name, marks the listing verified, and changes what a lookup returns |
| E5 | Scam-pattern detection across numbers | 10 | **Open** — worth doing only with real data |
| E6 | Business categorisation | 10 | **Open** |
| E7 | Accuracy measurement: precision, recall, false-positive rate | 10 | **Open** — the harness is cheap; it means nothing without labelled data |

### F · Observability — the taxonomy, applied

Twelve categories, six severities, one envelope, on both sides of the wire.

| | State |
|---|---|
| App: categories 1–5, 7 | **Done** from the start |
| App: categories 6, 9, 11, 12 | **Done** v0.35.0 — schema 1.1.0 added `trace`, `fatal`, `category`, `request_id`, `environment` |
| Server structured logging | **Done** v0.35.0 — it had none at all |
| Correlation across app and server | **Done** — the app mints `request_id`, the server reuses it |
| `analytics_event`, `db_query`, `trace_step` emit sites | **Open** — catalogue and API exist and are tested; the triggers do not |
| Dependency vulnerability scanning | **Backend done** (pip-audit as a gate). **Android open** — needs a Gradle lockfile |

---

## 3. Step-by-step order

Sequenced by dependency and by what unblocks the most. Not by phase number.

| # | Work | Why here | Effort |
|---|---|---|---|
| 1 | **Run the self-test, then the 15 device checks** | Nothing else can be called verified until this happens, and it is one afternoon | S |
| ~~2~~ | ~~Business-claim flow~~ (E4) | **Done** v0.41.0 | M |
| ~~3~~ | ~~Spam warning reason on the in-call screen~~ (D1) | **Done** v0.40.0 | S |
| ~~4~~ | ~~Result freshness + retry~~ (D3) | **Done** v0.40.0 | S |
| ~~5~~ | ~~User-visible auto-block threshold~~ (D4) | **Done** v0.40.0 | S |
| 6 | **Integration test for the full journey** (C7) | Register → lookup → report → appeal → delete, in one test | M |
| 7 | **Android dependency scanning** (F) | Add Gradle dependency locking, then osv-scanner. Deliberately deferred: a lockfile has real maintenance cost and a stale one breaks the build | M |
| ~~8~~ | ~~Monitoring~~ (C8) | **Done** v0.42.0 | M |
| 9 | **Accuracy measurement** (E7) | Build the harness now; it means something once there is labelled data | M |
| 10 | **Scam-pattern detection, business categorisation** (E5, E6) | Worth doing with real data, not before | M–L |

---

## 4. What this plan deliberately does not contain

- **Premium and subscriptions.** Excluded, not deferred.
- **Automatic diagnostics upload.** Removed at v0.29.0 and staying removed. The
  server exists; the app does not call it for diagnostics.
- **A claim that anything app-side is verified.** It is not. Sixteen releases have
  been built, signed and shipped, and not one has been run on a phone.

---

## 5. The honest summary

Three documents described a large, well-specified caller-ID application. What
actually exists is that application: 24 screens, 13 services, a real backend, 151
app tests, 157 backend tests, a signed release pipeline, structured logging on
both sides of the wire, and a self-host path that takes one command.

What it does not have is **evidence**. Everything above rests on code that
compiled and tests that passed, and none of it on a phone that has rung.

That is the whole of the remaining risk, and it is now a short list rather than an
open-ended one.
