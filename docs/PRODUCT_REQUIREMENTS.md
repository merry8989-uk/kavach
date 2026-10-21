# Kavach — Product Requirements

Status vocabulary used across these docs: **NOT_STARTED · IN_PROGRESS · IMPLEMENTED · VERIFIED · BLOCKED**
`IMPLEMENTED` means code exists and compiles. `VERIFIED` means it was exercised on a
real device or emulator and behaved correctly. **Nothing in this repository is VERIFIED**
— see `IMPLEMENTATION_STATUS.md` for why.

---

## 1. Product

**Kavach (कवच, "armour")** — an Android caller-identity, spam-protection and call-
handling app for the Indian market. It identifies unknown callers, blocks spam
through supported Android APIs, provides a dialer/contacts/call-log experience,
records calls where the platform permits it, and offers an AI-assisted screening
module that answers, transcribes and summarises calls.

### 1.1 Problem
- Unknown-number calls are the primary vector for financial fraud in India.
- Users want to know who is calling before they answer.
- Existing options gate their best protections behind a subscription.

### 1.2 Positioning
**Kavach is completely free.** Every feature ships to every user: no
subscriptions, no premium tier, no in-app purchases, no advertising, and no
paywall on protection, privacy controls or permission explanations.

### 1.3 Non-goals
- Not a VoIP service.
- Not a surveillance or number-tracking tool.
- No scraping of private profiles, no identity inference from unauthorised sources.
- No reuse of any competitor's app, database, trademarks or assets.

---

## 2. Personas

| Persona | Need |
|---|---|
| Everyday user (India) | Know who's calling; block spam without thinking about it |
| Small-business owner | Separate client calls from spam; keep notes per contact |
| Privacy-conscious user | Everything works offline; nothing uploaded without consent |

---

## 3. Functional requirements

Each requirement maps to a Feature ID used in `FEATURE_MATRIX.md` and
`IMPLEMENTATION_STATUS.md`.

### F1 Dialer
- F1.1 Keypad with haptics and long-press `+`
- F1.2 Smart paste → E.164 normalisation + identity preview
- F1.3 Call placement (`ACTION_CALL` with permission, else `ACTION_DIAL`)
- F1.4 Dual-SIM selection
- F1.5 In-call screen (mute, speaker, hold, DTMF, timer)
- F1.6 Speed dial + favourites
- F1.7 T9 contact search
- F1.8 Caller-ID preview while typing

### F2 Contacts
- F2.1 Read device contacts (`ContactsContract`)
- F2.2 Rich profile: multiple numbers, company, job title, notes
- F2.3 Tags
- F2.4 Profile image
- F2.5 Merged identity (device + crowdsourced + spam stats)
- F2.6 Add / edit / delete with write-back to the device store
- F2.7 Contact detail screen
- F2.8 Import / export (vCard, CSV)

### F3 Call history
- F3.1 Read the system call log
- F3.2 Enrich with name, spam label, AI brief
- F3.3 Filters (missed / incoming / outgoing / spam / recorded / AI-answered)
- F3.4 Per-entry actions
- F3.5 Search + date grouping
- F3.6 Local mirror in Room, idempotent sync

### F4 Blocklist
- F4.1 Persistent rule store
- F4.2 Rule types: exact, prefix, wildcard, regex, range, country, private, non-contact
- F4.3 Special rules (private/unknown, non-contacts, foreign)
- F4.4 Import / export
- F4.5 Crowdsourced top-spam list

### F5 Blocking
- F5.1 `CallScreeningService` silent reject
- F5.2 `TelecomManager` fallback
- F5.3 Modes: Block / Silence / Send-to-AI / Allow
- F5.4 Per-rule action, per-contact override, global default
- F5.5 SMS blocking
- F5.6 Schedule / DND windows
- F5.7 Post-call spam prompt

### F6 Messaging
- F6.1 SMS threads + conversation view
- F6.2 Compose and send
- F6.3 Smart replies
- F6.4 Auto-reply rules
- F6.5 AI auto-text for screened calls
- F6.6 Spam SMS filter + keyword rules

### F7 Caller announcer
- F7.1 TTS announcement of name/company/number
- F7.2 Modes: always / known / unknown / headset / off
- F7.3 Language + voice selection
- F7.4 Spoken spam verdict
- F7.5 Bluetooth routing

### F8 Recording
- F8.1 Capability assessment before enabling (device, API, region, policy)
- F8.2 Capture via permitted audio source, with graceful fallback
- F8.3 Encrypted local storage
- F8.4 Playback and deletion
- F8.5 Storage quota management
- F8.6 Consent notice + region gating
- F8.7 Transcription of recorded audio

### F9 Caller identification
- F9.1 Number normalisation (`PhoneNumberNormalizer`)
- F9.2 Local-first lookup (<10 ms) before any network call
- F9.3 Remote lookup when online and permitted
- F9.4 Crowdsourced tags (opt-in, k-anonymised)
- F9.5 Explainable spam score
- F9.6 Business directory + verification
- F9.7 Offline cache of recent lookups
- F9.8 Typed result with source, confidence, freshness

### F10 AI-assisted screening
- F10.1 Screening gate
- F10.2 Answer + consent announcement + greeting
- F10.3 Transcription (on-device for keywords; cloud for full text)
- F10.4 Intent classification
- F10.5 Dialogue policy
- F10.6 Auto-SMS to the caller
- F10.7 Post-call brief (transcript, summary, intent, sentiment, risk)
- F10.8 Optional synthetic-voice reply (consent-gated)
- F10.9 Escalation for urgent calls

### F11 Themes & UI
- F11.1 Dynamic Material 3 colour
- F11.2 Theme packs (Aurora, Carbon, Daylight, Neon Noir, Dynamic)
- F11.3 Animated caller radar / spam gauge / waveforms
- F11.4 Motion system + reduced-animation support
- F11.5 Accessibility (large text, contrast, TalkBack)
- F11.6 Localisation (Hindi + English)

### F12 Platform
- F12.1 Onboarding + permission manager
- F12.2 Roles (default dialer, call screening, SMS)
- F12.3 Background work (WorkManager)
- F12.4 Encrypted backup & sync
- F12.5 OTP authentication
- F12.6 Notifications
- F12.7 Settings hub
- F12.8 Crash/telemetry (opt-in)
- F12.9 Billing — **removed: Kavach is free**

### F13 Free by design
- F13.1 No paywall on any feature
- F13.2 No subscriptions or in-app purchases
- F13.3 No advertising
- F13.4 No entitlement system in the client

### F14 First-run setup
- F14.1 Permission groups with rationale
- F14.2 Role requests (dialer, caller ID, SMS)
- F14.3 Live status
- F14.4 Persisted completion
- F14.5 Contextual re-request on revocation

### F15 Updates
- F15.1 In-app update check against GitHub Releases
- F15.2 Download + install via FileProvider
- F15.3 Optional launch-time check

### F16 Central feature registry
- F16.1 Stable feature ID, state, required permission/role
- F16.2 Runtime capability check + failure reason
- F16.3 Independent enable/disable of optional modules
- F16.4 Disabling stops scheduled work and background collection

---

## 4. Non-functional requirements

| Area | Requirement |
|---|---|
| Performance | Local lookup must not block the screening callback; screening must respond within the platform deadline |
| Reliability | Network failure must never block a screening decision |
| Privacy | Cloud is opt-in; nothing leaves the device by default |
| Security | HTTPS only; no secrets in the repo; sensitive local data encrypted where appropriate |
| Accessibility | TalkBack labels, scalable text, contrast |
| Testability | Pure logic unit-tested on the JVM |
| Distribution | Signed releases with in-place updates (same key, increasing versionCode) |

---

## 5. Constraints & known platform limitations

These are recorded honestly; they shape what is achievable.

1. **No audio injection into a cellular uplink.** Android exposes no public API for a
   third-party app to inject arbitrary audio into a call. The assistant can answer,
   capture and speak via the speaker; natural two-way conversation requires a
   server-side telephony gateway.
2. **Call recording is restricted.** Since Android 10, third-party `VOICE_CALL`
   capture is blocked on most devices. A layered fallback exists; two-way recording
   must be verified per device before it can be claimed.
3. **Background foreground-service start.** Starting a microphone foreground service
   from the call-screening callback is restricted on Android 12+; it is reliable when
   Kavach holds the default-dialer role.
4. **Role and SMS capabilities vary** by OEM and Android version.
5. **No crowdsourced database exists yet** — the moat requires the backend and users.

---

## 6. Acceptance criteria (product level)

- App builds; core features perform real operations (no fabricated data).
- Caller identity comes from traceable, authorised sources.
- Blocking works through supported Android APIs.
- Settings actually control module behaviour.
- Nothing is gated behind a payment — every feature is available to every user.
- Missing dependencies produce honest unavailable states.
- Every remaining limitation is documented.
