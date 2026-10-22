# Kavach — Implementation Status

**As of release v0.30.0.**

Status vocabulary: **NOT_STARTED · IN_PROGRESS · IMPLEMENTED · VERIFIED · BLOCKED · EXCLUDED**

> ### Read this first
> **No app feature is VERIFIED.**
>
> The Android app has never been run on a device or emulator. Everything marked
> IMPLEMENTED compiles, passes 136 unit tests, passes Android lint with zero
> errors, and ships as a signed APK — which is *not* the same as working.
>
> The spec is explicit: *"Do not mark a feature VERIFIED based solely on
> compilation."*
>
> What changed at v0.30.0 is that this is no longer a blanket caveat. There is
> now a **self-test** in the app that checks what it can on the device, and a
> **15-item manual checklist** in `docs/DEVICE-VALIDATION.md` for what only a
> person can settle. The unverified list is finite and written down.
>
> **The backend IS verified**, to the extent that 125 tests against SQLite and a
> migration round trip can verify it. It has never run against PostgreSQL in
> production, and it is not deployed.

---

## 1. Counts

| | Count |
|---|---|
| Screens | 23 |
| Services | 13 |
| Backend routers | 10 |
| App unit tests | 136 |
| Backend tests | 125 |
| Documents | 19 |
| Releases shipped | v0.10.0 → v0.30.0 |
| **VERIFIED (app, on-device)** | **0** |
| VERIFIED (backend, CI) | 125 tests |

## 2. Phase status

| Phase | Subject | State |
|---|---|---|
| 0 | Repo audit and product plan | IMPLEMENTED — all eight documents present |
| 1 | Application foundation | IMPLEMENTED — home dashboard added |
| 2 | Permissions and Android integration | IMPLEMENTED — contextual, roles first in setup |
| 3 | Caller identification engine | IMPLEMENTED |
| 4 | Live screening and caller UI | IMPLEMENTED — 2.5 s deadline, fails open |
| 5 | Spam detection and blocker | IN_PROGRESS — no appeal flow |
| 6 | Dialer, contacts, call history | IMPLEMENTED |
| 7 | Reverse search and business directory | IN_PROGRESS — moderation and search history done; no business-claim flow |
| 8 | SMS spam protection | IMPLEMENTED |
| 9 | Recording and call notes | IMPLEMENTED — encrypted at rest, quota, consent gate |
| 10 | Advanced AI | IN_PROGRESS — transcription and missed-call priority real; pattern detection and accuracy measurement not started |
| 11 | Premium and subscriptions | **EXCLUDED** — the product is free by instruction |
| 12 | Feature control centre | IMPLEMENTED |
| 13 | Security, privacy, backend | IN_PROGRESS — no dependency vulnerability scan |
| 14 | UI quality and personalisation | IMPLEMENTED — motion setting, following the system by default |
| 15 | Testing and device validation | IN_PROGRESS — see section 3 |
| 16 | Release and delivery | IMPLEMENTED |

## 3. What is not verified, precisely

Nineteen features cannot be settled without a device. They are listed with exact
steps in `docs/DEVICE-VALIDATION.md`; the app's self-test lists the same set under
"Needs a device".

Incoming-call screen · blocking actually blocks · silence actually silences ·
announcement audible · recording captures and plays back · recording encrypted on
disk · dialer usable as the default dialer · SMS sending · OTP detection on a real
message · phishing warning on a real message · Arattai and Hyped opening ·
Hindi across every screen · update check · diagnostics export · emailing the log.

## 4. Known limitations, stated plainly

- **Two-way call recording is not possible** for an ordinary Android app, and the
  app says so rather than implying otherwise. It records the microphone.
- **The backend is not deployed.** Nothing the app does depends on it now that
  diagnostics are local.
- **Truecaller's public search has no shareable URL** — verified — so the lookup
  action opens the site and copies the number, with a note.
- **SMS delete only works as the default SMS app.**
- **Video calling is unimplemented** and says so.
- **Arattai and Hyped have no documented deep link carrying a phone number.**
- **Hindi has not been read by a native reviewer.**

## 5. Where the detail is

| Question | Document |
|---|---|
| What is pending, and in what order? | `docs/AUDIT-AND-PLAN.md` |
| How do I check it on a device? | `docs/DEVICE-VALIDATION.md` |
| How is logging meant to work? | `docs/LOGGING_STANDARD.md` |
| How does authentication work? | `docs/APP-AUTH.md` |
| How do I deploy the server? | `docs/SERVER_DEPLOY.md` |
| What is the API? | `docs/API_CONTRACT.md` |
