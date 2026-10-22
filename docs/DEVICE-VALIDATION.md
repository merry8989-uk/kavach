# Real-device validation

The spec for this phase is blunt, and it is the sentence this document exists to
respect:

> **Do not mark a feature VERIFIED based solely on compilation.**

Everything in this project has been built, compiled, unit-tested and shipped. None
of it has been run on a phone. That is not a small caveat — it is the difference
between "the code is right" and "the feature works", and only the second one is
worth telling a user.

So there are two things here: what the app can check by itself, and what only you
can check.

---

## 1. What the app checks by itself

**Settings → Troubleshooting → Self-test this device**

It runs on the device and reports four separate lists:

| List | What it means |
|---|---|
| **Passed** | it ran here, and the answer was right |
| **Failed** | it ran, and the answer was wrong |
| **Not run** | it could not run — no permission, no hardware, no role |
| **Needs a device** | it cannot be settled from inside the app |

### What it covers

- **The decision rules** — number normalisation, the spam scorer, sender-ID
  parsing, OTP detection, the phishing-link checks, the event schema. Each is
  given a known input and a known right answer. A failure here is a real bug.
- **Permissions** — which are granted, and therefore which features can run.
- **Roles** — default dialer, default SMS, call screening.
- **Storage and encryption** — a write/read probe, free space, and a Keystore
  round trip. If the Keystore cannot encrypt here, several things are quietly
  broken.
- **The diagnostics writer** — an event is encoded and validated end to end.
- **Package visibility** — whether the app can see a dialer, Arattai and Hyped.
  This one has bitten the project before: without a `<queries>` block an Android
  11+ build cannot see other apps at all, and every "open in…" action silently
  does nothing.

### Why "Not run" is not "Failed"

A permission the user declined is not a defect. Reporting it as a failure would
train everyone to ignore the list. It is a feature that cannot run, which is a
different statement, and it is reported as one.

---

## 2. What only you can check

The self-test lists these as **Needs a device**, and no amount of code will move
them out of that bucket. They depend on the phone network, the OEM's audio stack,
or a judgement only a person can make.

Work down this list with the app installed. Write down what you saw.

| # | Check | How to test | Expected |
|---|---|---|---|
| 1 | Incoming call shows the caller screen | Call this phone from another number | The Kavach incoming screen appears, with the name if known and "Unknown caller" if not |
| 2 | Screening blocks a blocked number | Add a rule to Block, then call from it | The call does not ring |
| 3 | Silence mode silences without rejecting | Set a rule to Silence, then call | No ring; the caller is not rejected |
| 4 | Announcement is audible | Turn the announcer on, call in | The number or name is spoken |
| 5 | Recording captures audio and plays back | Record a call, then play it from Recordings | Both sides audible if on speaker; your side always |
| 6 | Recording is encrypted at rest | Record, then check the file in `files/recordings` | The file starts with `KVEN`, not with audio |
| 7 | The dialer works as the default dialer | Set Kavach as default, place a call | The call connects; the in-call screen shows |
| 8 | Sending an SMS works | Open a thread, send | The message arrives |
| 9 | OTP detection and safe preview | Receive a real OTP | The list shows "One-time password", never the code |
| 10 | Phishing link warning | Receive a message with a shortened link | A warning with a reason |
| 11 | Arattai and Hyped open with the number | Open a number's details, use each action | The app opens, or a clear explanation appears |
| 12 | Hindi across the app | Settings → Appearance → हिन्दी | Every screen in Hindi, nothing English left behind |
| 13 | Update check | Settings → Updates → Check | Either "you are on the latest version" or a real download |
| 14 | Diagnostics export | Diagnostics → Export as… → each format | The file opens in the app it claims |
| 15 | Email the log | Diagnostics → Email | The composer opens with the log attached and the subject filled |

### What a pass looks like

A row is **verified** only when it was actually exercised and behaved as
expected. Anything else stays in the list, honestly, as unverified.

---

## 3. What is automated already

| Check | Where |
|---|---|
| Unit tests — 136 of them | `gradle :app:testDebugUnitTest` in CI |
| Backend tests — 125 of them | `gradle`… no, `pytest` in Backend CI |
| Android lint | `gradle :app:lintDebug` in CI |
| Translation key parity | `tools/check_translations.py` in CI |
| Release build, signed | `release.yml` |
| Migration round trip | Backend CI, upgrade → downgrade → upgrade |
| Production config guard | Backend CI — the server refuses to boot on a default secret |

## 4. What is *not* automated, and why

- **Instrumentation and UI tests.** They need an emulator or a device in CI. The
  repository has none, so writing them would produce tests that never run — which
  is worse than not having them, because it looks like coverage.
- **A real call.** No emulator reproduces the carrier's audio path, and the
  OEM restrictions on call recording are the whole difficulty.
- **Dependency vulnerability scanning.** Not wired up. `pip-audit` and a Gradle
  dependency check are the obvious next step.

---

## The standing rule

Until a row above has been exercised on a device, it is **not verified**, and
nothing in this repository will say it is. Compilation is not evidence.
