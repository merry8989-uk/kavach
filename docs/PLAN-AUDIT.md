# Plan audit — what is actually left

**As of v0.56.0.** Every status below was checked against the code, not against
the other documents, because several of them are out of date.

---

## The documents disagree with the code

Two of the planning documents are stale enough to mislead:

| Document | Says | Reality |
|---|---|---|
| `IMPLEMENTATION_STATUS.md` | "As of release v0.30.0", 136 app tests, 125 backend tests, 23 screens | v0.56.0, **227** app tests, **205** backend tests |
| `FEATURE_MATRIX.md` | 21 features `NOT_STARTED` | **8 of those 21 are done** and the matrix has not been updated |

The eight that `FEATURE_MATRIX` calls `NOT_STARTED` and that are actually
implemented:

| Feature | Where it actually lives |
|---|---|
| F6.3 Smart replies | `data/repo/ReplySettingsRepository.kt`, `ui/screens/settings/RepliesScreen.kt` |
| F6.4 Auto-reply rules | `service/SmsAutoReplyReceiver.kt` — reads the setting and sends |
| F6.6 Spam SMS filter | `sms/SmsClassifier.kt` |
| F8.3 Encrypted local storage | `data/local/DatabaseKey.kt`, SQLCipher |
| F8.5 Storage quota | `recording/RecordingStore.kt` |
| F11.4 Motion / reduced animation | `AppSettings.motion`, wired in the theme |
| F11.6 Localisation | `res/values-hi/strings.xml` — 162 strings, same count as the default |
| F12.3 WorkManager background work | `work/Workers.kt`, `work/WorkerScheduler.kt` |
| F12.8 Crash/telemetry | `data/diagnostics/` — local only, by design |
| F16.4 Data-saver / offline-only | `AppSettings.dataSaver`, `AppSettings.offlineOnly` |

That is ten, not eight — `FEATURE_MATRIX` is wrong about ten of its own rows.

---

## Genuinely not done

### Needs a decision, not code

**F5.5 SMS blocking.** Blocked senders are *hidden* in the Kavach message list,
not blocked. Actually blocking requires `ROLE_SMS`, and the app declares no SMS
intent filters, so Android would never grant it — which is why the role was
removed from setup. This is not a gap to close by writing code; it is a gap that
needs a decision about whether Kavach should become an SMS app.

**F4.5 Crowdsourced top-spam.** Marked `BLOCKED` because there was no server.
**There is a server now**, so this is no longer blocked — it is simply not built.
The endpoint that would serve it (`/v1/lookup`) already returns a report count.

**F12.5 OTP authentication.** The app has the whole flow; the server has no SMS
gateway. Needs a paid MSG91 or Twilio account. Nothing can be built around it.

### Real work, nothing blocking it

| Feature | State |
|---|---|
| **F2.8 Import** | vCard **export** exists (`domain/VCard.kt`, share sheet). **Import does not** — no reader at all |
| **F4.4 Import/export of block rules** | **Done** — `domain/BlockRuleCodec.kt`, 13 tests |
| **F5.6 Schedule / DND windows** | **Done** — `domain/QuietHours.kt`, 18 tests |
| **F5.7 Post-call spam prompt** | **Done** — set when a call ends, shown on the next open |
| **F7.5 Bluetooth routing** | **Was already done** — `KavachInCallService.routeBluetooth()` calls `setAudioRoute(ROUTE_BLUETOOTH)`. My first pass said "not started" because I grepped for `setCommunicationDevice` and it uses the Telecom API instead. It is now honest about failure too, which it was not |
| **F12.4 Encrypted backup and restore** | **Done** — AES-GCM under a passphrase, 10 tests |
| **F10.5 Dialogue policy** | Not started — see `docs/AI-AGENT-PLAN.md` for why the first decision is not code |
| **F10.6 Auto-SMS to caller** | Not started |
| **F10.8 Synthetic-voice reply** | Not started |

The last three are the "AI answers the call" feature. What exists is
**transcription and a missed-call priority**, which is a different thing from an
assistant that holds a conversation. The distinction matters: the app does not
currently claim the larger feature, and it should not start.

### Messaging — six items, all one cause

Reactions, sender labels in groups, attachments, voice notes, group send, and
message backup all need **MMS**, which was dropped by decision. Scheduled send is
the only item in that list that does not need MMS.

So the honest summary is: **one messaging item is buildable** (scheduled send),
and the other six are not, because the transport they need was excluded.

---

## Carried over from the audit (`docs/AUDIT-FIXES.md`)

| Finding | State |
|---|---|
| Raw phone numbers in the legacy FastAPI models | **Not fixed.** "Numbers are never stored" is true of the hashed model and both edge servers, and false of the legacy FastAPI tables |
| Retention promise vs actual cleanup | **Not fixed** |
| Export claim with no export endpoint | **Not fixed** |
| OTP store is in-process, not multi-instance safe | **Not fixed** |
| Redis limiter failure allows requests | **Not fixed** |
| AI request body unbounded | **Not fixed** |

These are all in the FastAPI backend, which **is not deployed**. They matter the
day it is.

---

## What is genuinely finished

- **Caller identification**: screening, spam scoring, blocking, verdicts, appeals
- **Dialer, contacts, call log**: search, speed dial, favourites, missed reminders, ringtone
- **Messaging**: send, receive, delivery state, search, pin, mute, multi-select, partial text selection, quick reply, export
- **Recording**: encrypted at rest, quota, consent gate, playback
- **Calling cards, reminders, vCard share**
- **Diagnostics**: local only, no caps, Room-backed, streamed export
- **The server**: caller lookup and community reports, live and verified
- **Set as default**: shown only when not default, "later" re-asks in two days
- **227 app tests, 205 backend tests, 30 + 14 edge tests, lint clean**

---

## What "not done" does not mean

**No app feature is VERIFIED on a device.** Everything above compiles, passes
tests and ships as a signed APK. That is not the same as working, and the
15-item checklist in `docs/DEVICE-VALIDATION.md` is what would settle it.

This document is a map of the gap between the plans and the code. It is not a
claim that the code works.
