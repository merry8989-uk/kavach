# AI call agent — a plan, not a build

**Status: not built.** This document exists so the design decisions are settled
before any code, because most of them are not engineering decisions.

What **is** built today, and is separate from this: **on-device transcription of a
recording**, so a user who could not hear the call can read it. That does not need
an AI, a server, or a paid provider, and it is not waiting on anything here.

---

## What was asked for

An agent that picks up when the user is unavailable, talks to the caller, answers
questions, and — with the user's permission — stores the audio, the text and the
caller's details so it can be trained further.

That is four separate things wearing one name, and they have very different
costs. Splitting them is most of the work.

---

## The hard limits, before anything else

These are not "not yet". They are reasons a design has to change.

### 1. A third-party app cannot answer a call and speak into it

Android gives no API to inject audio into a cellular uplink. A call can be
**answered** (`TelecomManager.acceptRingingCall` with `ANSWER_PHONE_CALLS`) and a
call can be **recorded**, but the app cannot put a synthesised voice down the line.
The caller hears silence.

So "the agent picks up and talks to them" cannot mean what it sounds like. What it
can mean:

- **Answer and listen.** The line is open, the caller speaks, the app records and
  transcribes. The caller hears nothing.
- **Answer, then hang up and send an SMS.** The agent answers, waits a beat, and
  replies by message. The caller gets a text, not a conversation.
- **Be a real assistant on the network side**, which requires a VoIP number
  (Twilio, Exotel) — the call reaches the agent, not the phone. This is the only
  version where "talks and answers" is literally true, and it means the agent's
  number is not the user's number.

**This choice decides everything else and must be made first.**

### 2. Call recording does not capture the other party on most phones

Already true of the existing feature: see `RecordingCapability`, which has said so
since it was written. A transcript of a one-sided recording is a transcript of the
user.

An agent that "listens to the caller" needs audio the platform usually will not
give it. The network-side version (above) is the only reliable path.

### 3. Anything that stores caller audio is a liability with a legal edge

Recording a call in India requires the other party's knowledge in most contexts,
and the rules differ by state elsewhere. Storing the audio **and** the caller's
details turns Kavach into a processor of other people's personal data — people who
never installed the app and never agreed to anything.

That is not a reason to refuse. It is a reason that consent has to be **the
caller's**, not only the user's, and that retention has to be short and explicit.

---

## The four parts, split

### Part A — Answer and record (buildable today)

| | |
|---|---|
| What | Answer when the user is unavailable, record, transcribe on-device |
| Needs | `ANSWER_PHONE_CALLS`, recording that works, `OnDeviceTranscriber` |
| Honest limit | The caller hears nothing; this is a voicemail, not a conversation |
| Risk | Low. Nothing leaves the phone |

This is worth building on its own and does not need an AI.

### Part B — Auto-reply by SMS (buildable today)

| | |
|---|---|
| What | Answer, hang up, send "I can't talk, tell me in a message" |
| Needs | `SmsAutoReplyReceiver`, which already exists |
| Honest limit | It is a text message, not an agent |
| Risk | Low |

Also worth building alone, and it is what most people actually want.

### Part C — A real conversational agent (needs a paid provider)

| | |
|---|---|
| What | A voice that answers, understands, replies |
| Needs | A VoIP number (Twilio/Exotel), speech-to-text, an LLM, text-to-speech, a server to run the state machine |
| Cost | Per-minute on the number, per-token on the model, per-minute on STT/TTS |
| Honest limit | **The agent's number is not the user's number.** Callers dial something new |
| Risk | High — see below |

**This is the part that cannot be faked and cannot be cheap.** Any version that
claims to do it without a VoIP number is a demo that fails on the first real call.

### Part D — "Train itself on the user's data" (the dangerous one)

The request was to store the caller's audio, text and details, with the user's
permission, so the agent improves.

**The user's permission is not sufficient, and this is the part to be careful
about.** The audio is of the *caller*, who did not install Kavach and did not agree
to anything. A consent screen the user taps does not transfer the caller's consent
to us.

What is defensible:

- **The user's own voice** — theirs to give. Fine.
- **The caller's words, only with the caller told**, e.g. an announcement at the
  start of the call: "this call is being recorded and transcribed". That is what
  makes it consent rather than a checkbox.
- **Never the caller's details as a training set.** A corpus of "who called whom
  and what they said" is a surveillance database whatever it is called.

And "trains itself" should not be a black box. Two honest versions:

1. **Per-user adaptation** — the agent learns this user's phrasing and contacts, on
   the device, and the data never leaves. Safe, useful, and buildable.
2. **A shared model trained on call audio** — requires the announcement above, an
   explicit opt-in that is off by default, a retention limit, and a deletion path
   that actually deletes. This is a company-level commitment, not a feature.

**Recommendation: build (1) only.** (2) is not a feature to add quietly in an app
update.

---

## Suggested order

1. **Part B** — auto-reply by SMS. Already half-built. Useful today.
2. **Part A** — answer and transcribe. Uses `OnDeviceTranscriber`, which exists.
3. **Decide the number question** — is the agent the user's number, or a new one?
4. **Part C** — only after 3, and only with a provider budget.
5. **Part D(1)** — per-user, on-device adaptation.
6. **Part D(2)** — not without a legal review and a consent announcement.

Steps 1 and 2 need no AI provider, no server and no money. Steps 3 onwards need all
three.

---

## What this document is not

It is not a claim that any of this is close. It is a map of which parts are
engineering and which are decisions about other people's data, written down before
the code so the second kind does not get made by accident.

The app does not currently claim any of it, and should not start until Part A or B
ships.
