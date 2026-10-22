# Kavach — Audit and Restructured Plan

**Audit date:** 2026-10-22 · **At release:** v0.30.0

This is a full check of the repository against the master specification
(`paste-1.md`), the external review (`paste-1-2.md`), and the code as it actually
is. It ends with a plan grouped by **what matters**, not by the order the phases
happened to be written in.

---

## Part 1 — What is actually here

Counted from the tree, not from memory.

| | Count |
|---|---|
| Screens | 23 |
| Services | 13 |
| Backend routers | 10 |
| App unit tests | 136 |
| Backend tests | 125 |
| Documents in `docs/` | 18 |
| Releases shipped | v0.10.0 → v0.30.0, all signed |

### Phase-by-phase

| Phase | Subject | State |
|---|---|---|
| 0 | Repo audit and product plan | **Done** — all eight required documents exist |
| 1 | Application foundation | **Mostly** — see gap A2 (no home dashboard) |
| 2 | Permissions and Android integration | **Violation** — see gap A1 |
| 3 | Caller identification engine | **Done** |
| 4 | Live screening and caller UI | **Done** — including a 2.5 s deadline |
| 5 | Spam detection and blocker | **Mostly** — no appeal flow (B3) |
| 6 | Dialer, contacts, call history | **Done** |
| 7 | Reverse search and business directory | **Mostly** — no search history (B4), moderation is write-only (A3) |
| 8 | SMS spam protection | **Done** — categories, OTP, link warnings, controls |
| 9 | Recording and call notes | **Done** — encrypted at rest, quota, consent gate |
| 10 | Advanced AI | **Partial** — see B5 |
| 11 | Premium and subscriptions | **Deliberately excluded** — the product is free |
| 12 | Feature control centre | **Done** |
| 13 | Security, privacy, backend | **Mostly** — no vulnerability scan (D1) |
| 14 | UI quality and personalisation | **Mostly** — no motion setting (B6) |
| 15 | Testing and device validation | **Partial** — see the whole of Part 2C |
| 16 | Release and delivery | **Done** |

---

## Part 2 — The restructured plan

The phase order is historical, not a priority order. Grouped by what the work is
actually for.

### A. Trust — things that are wrong or misleading

These come first because each one is a place where the project says something
that is not true. That is worse than a missing feature, because a missing feature
is visible.

---

**A1 · Permissions are requested all at once, and the spec forbids it** — *fixed in v0.32.0*

- **Evidence:** `PermissionsScreen.kt:417` → `permissionLauncher.launch(allPermissions())`.
  The spec, Phase 2: *"Request permissions contextually, not all at first launch."*
- **Why it matters:** asking for nine permissions before the user has seen the app
  is the single biggest reason people refuse, and a refusal at that moment costs
  every dependent feature at once. It is also the most visible way the app differs
  from the spec it was built from.
- **This was flagged early and never resolved.** It was carried as "awaiting the
  user's call" for the whole project. Carrying it further was the problem.

**Fixed.** What the fix turned out to be about:

1. **The real cause was a gate, not a dialog.** `KavachNav` refused to open the
   app until `READ_CONTACTS`, `READ_CALL_LOG` and `READ_PHONE_STATE` were all
   granted — no matter how many times the user tapped Skip. That made the spec's
   contextual rule *impossible*: there was no way to reach a screen that could ask
   for what it needed, because every screen was behind the wall. Changing the
   dialogs without changing the gate would have achieved nothing.
2. **Roles now come first in setup.** Holding the dialer role is what makes Kavach
   *the* phone app, and it is the one thing worth asking before the app has shown
   anything — it is a statement about what the app is, not a request for data. The
   spec's rule is about permissions.
3. **Permissions moved to the point of use.** `ui/permissions/PermissionNotice.kt`
   puts a card on Contacts, Calls, Messages and the Dialer that appears *only*
   when the permission is missing, says why in one sentence, and asks for that one
   thing. The onboarding screen still offers "grant the rest now (optional)" for
   someone who would rather get it over with — the choice is now the user's
   rather than the app's.
4. **Contextual requests are logged** as `access_fix_requested`, with the same
   event the onboarding loop uses, so a future review can answer the empirical
   question this change rests on: did asking at the point of use actually work
   better than asking everything up front?

---

**A2 · The spec asks for a home dashboard; home is the dialer** — *fixed in v0.34.0*

- **Evidence:** `KavachNav.kt:214` → `startDestination = "dialer"`.
- **Why it matters:** the spec lists a home dashboard as the first Phase 1 screen,
  and the external review asked for the same thing (item 2, "simple home screen
  with search and protection status"). A user opening Kavach currently lands on a
  keypad, with no statement of what the app is doing for them.
- **Honest note:** this is a genuine product gap, not just a spec checkbox. There
  is nowhere that says "protection is on, 12 calls screened this week".
- **Fixed.** A **Home** tab is now the start destination. It shows protection
  status in the module registry's own words, what needs attention (with the exact
  missing permission or role rather than a generic apology), today's calls read
  from the call log, a number search, and shortcuts.

  Every figure is read from something real. Where a number cannot be known — no
  calls today — the card says so instead of showing a zero that looks like an
  answer.

  **One deliberate deviation:** this makes six bottom tabs, and Material 3
  recommends at most five. All six are genuinely primary, and burying one behind
  a menu costs more than a slightly tighter tab bar.

---

**A3 · The moderation queue is write-only** — *fixed in v0.33.0*

- **Evidence:** `services/spam.py:182` writes `_flag_for_moderation(...)`; no router
  and no screen reads the table.
- **Why it matters:** flagged numbers accumulate and nobody ever looks. Burst
  reporting — the abuse signal the phase asks for — is detected and then thrown
  away. This is a feature that reports success and does nothing.
- **Fixed.** `GET /v1/admin/moderation` (service token) returns pending items
  **oldest first**, and `POST /v1/admin/moderation/{id}` decides one. The queue is
  now indexed on `(status, created_at)`, which is the order it is read in.

---

**A4 · False-positive appeals are a column, not a flow** — *fixed in v0.33.0*

- **Evidence:** `scoring.py:26` takes a `false_positive_rate`; `models.py:245` has
  `user_appeal` in a comment. There is no way for a user to say "this is not spam".
- **Why it matters:** the spec asks for it in Phase 5, and the review asked for it
  in item 7. A number wrongly labelled spam is a real harm, and right now the only
  remedy is to block it locally, which does not correct the shared record.
- **Fixed.** `POST /v1/appeals` (app token) plus a **"This is not spam"** button
  next to the risk score it disputes.

  The part worth keeping is the **effect**: approving an appeal zeroes the
  number's score and counts, so the next lookup returns a *different answer*
  rather than a different status. A decision with no effect is a comment.

---

**A5 · The status document is twelve releases out of date**

- **Evidence:** `docs/IMPLEMENTATION_STATUS.md` says "As of release v0.7.0" and
  reports 42 implemented / 26 not started. The current release is v0.30.0.
- **Why it matters:** it is the spec's own answer to "where is this project", and
  it currently understates the work by a wide margin. Anyone reading it — including
  a future session — starts from a wrong picture.
- **Work:** this audit replaces it. See the note at the end.
- **Effort:** done with this document.

---

### B. Spec gaps — the spec says X, there is no X

Real features, none of them urgent, all of them asked for.

**B1 · Search history** — the spec lists it in Phase 7. There is no record of what
someone searched for. *Small.*

**B2 · Instrumentation and UI tests** — see D2; listed here because the spec asks
for them by name.

**B3 · Business verification flow** — the directory exists and `services/names.py`
publishes only `self_declared` and `business` names, but there is no way for a
business to *claim* a listing, which is how "verified business" is supposed to
become true. *Medium.*

**B4 · Scam-pattern detection** — Phase 10. The spam scorer is rule-based and
explainable, which is right, but there is no pattern analysis across numbers.
*Medium, and only worth doing with real data.*

**B5 · Missed-call prioritisation and business categorisation** — Phase 10. Neither
exists. *Small each.*

**B6 · Motion and reduced-animation settings** — Phase 14 asks for them; there are
none. Accessibility, and cheap. *Small.*

**B7 · Accuracy measurement for the scorer** — Phase 10 asks for precision, recall
and false-positive rates "where appropriate". There is a `false_positive_rate`
input but no measurement of the scorer against anything. *Small to build, needs
labelled data to mean anything.*

---

### C. Verification — proving any of it works

This is the largest genuine gap, and the one that makes every other claim in this
project provisional.

**C1 · Nothing is verified on a device.** Twenty-one releases have been built,
signed and shipped. Not one has been run on a phone. `docs/DEVICE-VALIDATION.md`
lists fifteen checks that only a person can perform — an incoming call, a blocked
call not ringing, a recording playing back, an OTP arriving.

**Why this is first among the verification work:** every report in this project
has carried the caveat, and the caveat is honest but useless on its own. It does
not say *which* things are unverified. It now does, and the list is short enough
to work through in an afternoon.

**C2 · The self-test has never been run either.** `SelfTest.kt` checks permissions,
roles, storage, a Keystore round trip and package visibility — and it, too, has
only ever been compiled. It is the tool for C1, so it is checked first: if it
crashes on open, nothing else can be trusted.

**C3 · The backend has never run against PostgreSQL in anger.** Its 125 tests pass
against SQLite. The migrations round-trip. But no query has run against the
database that production would use.

---

### D. Hardening — making the machine trustworthy

**D1 · No dependency vulnerability scanning.** — *backend done in v0.33.0* The spec asks for it in Phases 13
and 15. **Backend: done.** `pip-audit -r requirements.txt` is now a **gate** in Backend
CI, not a report — a dependency with a published CVE stops the build. Verified
locally: clean.

**Android: still open, deliberately.** osv-scanner needs a Gradle **lockfile**,
and this project has none. Adding `dependencyLocking` is a build change with real
maintenance cost — the lockfile must be regenerated on every dependency bump, and
a stale one breaks the build. Rather than add a check that cannot be verified from
here, the gap is left stated: the app's dependency coverage is Android lint's
`Obsolete Gradle Dependency` advisories, which report *age*, not vulnerabilities.

**D2 · No instrumentation or UI tests.** Deliberately not written, because CI has
no emulator — tests that never run are worse than no tests, since they look like
coverage. The honest options are to add an emulator to CI (slow, costs minutes per
run) or to keep the manual checklist as the UI test. *Medium, with a real trade-off.*

**D3 · No monitoring.** Nothing exports latency, error rates or provider failures.
For a backend that is not deployed, this is fine. It stops being fine the moment
it is. *Medium.*

**D4 · The backend is not deployed.** It is complete, containerised, published to
GHCR, and unreachable. Nothing the app does depends on it right now — diagnostics
are local — so this is a choice, not a defect. *Blocked by: a decision to run it.*

---

### E. Deliberately excluded

**E1 · Premium and subscriptions (Phase 11).** The product is free, by explicit
instruction. The phase is not "pending" — it is not wanted. All billing references
were removed in v0.10.0 and must not come back.

**E2 · Automatic diagnostics upload.** Removed in v0.29.0. The app keeps logs
locally and the user shares them. The backend endpoints still exist and are unused.

---

## Part 3 — The order I would actually do it in

Sequenced by dependency and by what unblocks the most.

| # | Work | Why now | Effort |
|---|---|---|---|
| 1 | **C1 + C2** — run the self-test, then the 15 manual checks | Nothing else can be called verified until this happens, and it is one afternoon | S |
| 2 | **A1** — contextual permissions | The one place the app knowingly contradicts its own spec | M |
| 3 | **D1** — vulnerability scanning in CI | Cheap, and it is the last automated check the spec asks for | S |
| 4 | **A3 + A4** — moderation endpoint, then appeals | Turns two write-only features into working ones | M |
| 5 | **A2** — home dashboard | The biggest remaining product gap, and the review asked for it | M |
| 6 | **A5** — refresh the status document | Do it while the audit is fresh | S |
| 7 | **B6, B1, B5** — motion setting, search history, missed-call priority | Small, self-contained, each visibly improves the app | S each |
| 8 | **D2** — decide on instrumentation tests | A decision, then work | M |
| 9 | **B3, B4, B7** — business claims, scam patterns, accuracy | Worth doing with real data, not before | M–L |
| 10 | **D3, D4** — monitoring and deployment | Only when the backend is actually used | M |

---

## Part 4 — What is not in this plan, and why

- **Nothing is marked VERIFIED.** Not one app feature has been exercised on a
  device. Compilation is not evidence, and the spec says so in as many words.
- **The remaining lint warnings are not listed.** 44 of them, all `Obsolete
  Gradle Dependency` and Play-policy advisories. None is a defect; they are a
  standing invitation to upgrade dependencies, which is its own piece of work.
- **Phase 11 is not listed as pending.** It is excluded, not deferred.

---

## The honest summary

Kavach is a large, working, tested application: 23 screens, 13 services, a real
backend, 261 tests, a signed release pipeline, and a self-test that is more
honest about its own limits than most shipping software.

What it does not have is **evidence**. Everything above rests on code that has
compiled and tests that have passed, and none of it on a phone that has rung.

That is a one-afternoon problem now, not an open-ended one. That is what changed.
