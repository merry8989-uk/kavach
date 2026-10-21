# Kavach — Test Plan & Results

## 1. Environment reality

The build environment has **no Android SDK, no emulator and no physical device**.
It has git, Node, Python and network access, and it drives GitHub Actions.

Consequences, stated plainly:

- **Unit tests run in CI** (GitHub-hosted runners with JDK 17 + Android SDK).
- **Instrumentation and Compose UI tests cannot be run here**, and none are written yet.
- **Nothing can be marked VERIFIED.** Device-dependent behaviour (screening,
  recording, answering calls, roles) is unverified.

## 2. Status of test execution

### Tests passed
| Suite | What it covers | Where it ran |
|---|---|---|
| `PhoneUtilsTest` (7 cases) | E.164 for Indian numbers, leading zero, existing `+`, subscriber key, T9 mapping, private/blank detection, pretty formatting | GitHub Actions `CI` |
| `RuleMatcherTest` (7 cases) | country, prefix, wildcard regex escaping, regex, private, non-contact, range | GitHub Actions `CI` |
| `SpamScorerTest` (4 cases) | contacts never spam, repeated digits, sequential digits, report weighting | GitHub Actions `CI` |
| `UpdateCheckerTest` (7 cases) | semver comparison incl. shorter strings and blank input | GitHub Actions `CI` |
| Compilation | `:app:assembleDebug` and `:app:assembleRelease` | GitHub Actions `CI` + `Release` |

### Tests failed
None currently. (Historically: 5 compile failures and 1 R8 failure were found and
fixed during development — missing import, Gradle script `Properties`, Hilt KSP
artifact, Retrofit converter, TFLite R8 keep rules.)

### Tests not run
- Android lint (not wired into CI yet).
- Instrumentation tests — none written.
- Compose UI tests — none written.
- Backend tests — no backend.
- Dependency vulnerability scan — not wired.
- Anything requiring a device.

## 3. Automated test inventory (JVM, `app/src/test/`)

| File | Focus |
|---|---|
| `PhoneUtilsTest.kt` | Number normalisation and T9 |
| `RuleMatcherTest.kt` | Block-rule evaluation (pure logic) |
| `SpamScorerTest.kt` | Heuristic spam scoring |
| `UpdateCheckerTest.kt` | Version comparison |

`testOptions { unitTests.isReturnDefaultValues = true }` lets Android framework stubs
return defaults so pure logic is testable off-device.

Run locally:

```bash
./gradlew :app:testDebugUnitTest
```

## 4. Required test coverage (from the master spec) — gap analysis

| Area | Covered? |
|---|---|
| Number normalisation | ✅ |
| Identity resolution | ❌ |
| Cache invalidation | ❌ |
| Spam scoring | ✅ (heuristics only) |
| Blocklist/allowlist priority | ⚠️ partial (rule matching tested; ordering by type is not) |
| API errors | ❌ |
| Authentication | ❌ (no auth) |
| Database migrations | ❌ |
| Permission denial/revocation | ❌ |
| Role changes | ❌ |
| Settings persistence | ❌ |
| Subscription entitlements | ❌ (no billing) |
| UI navigation | ❌ |
| Background work | ❌ |
| Privacy deletion | ❌ |
| Offline behaviour | ❌ |
| Duplicate call events | ❌ |
| Race conditions | ❌ |
| Rate limiting | ❌ |
| False-positive handling | ❌ |

Highest-value additions, in order: DAO/Room migration tests (Robolectric or
instrumented), `BlockRepository.decide` ordering with a fake DAO, `UpdateInstaller`
URL-allowlist rejection, and Compose navigation smoke tests.

## 5. Manual verification scripts

These must be run on a device before anything is marked VERIFIED.

### M1 — First-run setup
1. Fresh install. 2. Setup screen appears. 3. Tap the primary button repeatedly.
4. Confirm each item ticks as granted. 5. Set Default dialer and Caller ID roles.
6. Confirm "Finish setup" → app opens on the Dialer. 7. Relaunch → setup does **not**
reappear.

### M2 — Screening & blocking
1. Add an EXACT block rule for a second phone. 2. Call from it → rejected silently,
no ring. 3. Change action to SILENCE → rings muted. 4. Change to SEND_TO_AI → the
assistant notification appears.

### M3 — Contacts CRUD
1. Contacts → `+`. 2. Add two numbers, company, notes, a tag. 3. Save. 4. Verify it
appears in the phone's own contacts app. 5. Tap it → edit the name → Save → verify
both places updated. 6. Delete → confirm → verify removal from both.

### M4 — AI assistant
1. Enable AI in Settings. 2. Call from an unknown number. 3. Confirm the assistant
answers, the consent sentence is audible, and the greeting plays. 4. Hang up.
5. Confirm a brief notification appears. 6. Open Calls → AI briefs → the card is there.

### M5 — Recording
1. Enable "Record all calls". 2. Place a call. 3. Confirm the persistent notification.
4. End the call. 5. Settings → Call recordings → play it back. **Record which audio
source was used and whether the far side is audible — this is the per-device
question.**

### M6 — Updates
1. Settings → Updates → Check. 2. With a newer release published, confirm "Download &
install" appears and completes. 3. Confirm the app updates in place.

## 6. Device matrix to cover

| Dimension | Values to test |
|---|---|
| Android version | 7.0 (API 24), 10, 12, 13, 14 |
| OEM | AOSP/Pixel, Samsung One UI, Xiaomi MIUI/HyperOS, Oppo/Realme, Vivo |
| SIM | single, dual |
| Role state | screening only, dialer only, both, neither |
| Permission state | all granted, partially denied, permanently denied |

OEM skins are the main risk: they frequently block background foreground-service
starts and restrict third-party call recording.
