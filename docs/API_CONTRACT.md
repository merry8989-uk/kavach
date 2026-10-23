# Kavach — Backend API Contract

> **STATUS: NOT BUILT.** No server exists. This document is the contract the Android
> client is written against, so the backend can be implemented to match. Endpoints
> the app already calls are marked **[client wired]**; everything else is a
> specification only. Do not read this as a working integration.

Base URL (client constant): `https://api.kavach.app/` — `CloudAiClient.BASE_URL`.
All endpoints are HTTPS. Auth is a bearer token (not yet implemented client-side).

---

## 1. [client wired] `POST /v1/transcribe`

Transcribes a recorded call clip. The client sends base64 PCM/WAV, capped at ~3 MB.

```json
// request
{ "audioBase64": "<base64>", "language": "en-IN" }
// response 200
{ "transcript": "Hello, I have a delivery for you...", "confidence": 0.91 }
```
Client behaviour on failure: falls back to a placeholder string; never blocks the call.

## 2. [client wired] `POST /v1/summarise`

Produces the structured post-call brief.

```json
// request
{ "transcript": "<full text>", "number": "+919876543210" }
// response 200  (maps to CallBrief)
{
  "number": "+919876543210",
  "transcript": "<full text>",
  "summary": "Delivery agent for an Amazon parcel.",
  "intent": "DELIVERY",          // DELIVERY|APPOINTMENT|EMERGENCY|SALES|SPAM|PERSONAL|UNKNOWN
  "sentiment": "NEUTRAL",        // POSITIVE|NEUTRAL|NEGATIVE
  "risk": 12,                    // 0..100
  "recordingPath": null,
  "timestamp": 1760000000000
}
```
Client behaviour on failure: builds the brief locally with a deterministic summary.

## 3. [client wired] `POST /v1/dialogue`

The assistant's next spoken line during a screened call.

```json
// request
{ "transcriptSoFar": "<text>", "callerName": "Rahul" }
// response 200
{ "say": "Could you tell me the order number?", "shouldEscalate": false }
```

---

## 4. Specified only (no client code yet)

### `GET /v1/lookup?number=+919876543210`
Caller identity. Rate-limited; authenticated for bulk use.

```json
{
  "number": "+919876543210",
  "displayName": "Rahul Sharma",
  "numberType": "MOBILE",
  "businessCategory": null,
  "source": "COMMUNITY",          // CONTACT|COMMUNITY|BUSINESS|PROVIDER|NONE
  "confidence": 0.72,             // 0..1
  "verificationStatus": "UNVERIFIED", // VERIFIED|UNVERIFIED|DISPUTED
  "spamRisk": 8,
  "reports": 3,
  "lastUpdated": 1760000000000,
  "freshness": "FRESH",           // FRESH|STALE|UNKNOWN
  "unavailableReason": null
}
```
Rules: never invent a name; return `displayName: null` + `source: "NONE"` when nothing
reliable exists. A prefix or circle is never proof of identity.

### `GET /v1/businesses/search?q=&category=&page=&pageSize=`
```json
{ "page": 1, "pageSize": 20, "total": 41,
  "results": [ { "name": "...", "category": "...", "verified": true, "source": "BUSINESS" } ] }
```

### `POST /v1/reports`
```json
// request
{ "number": "+919876543210", "category": "FINANCE_SCAM", "note": null }
// response 202
{ "accepted": true, "reports": 4 }
```
One report per user per number per window; repeated reports must not dominate.

### `POST /v1/identity-corrections`
```json
{ "number": "+919876543210", "suggestedName": "Rahul S.", "evidence": null }
```

### `GET /v1/number-reputation?number=`
```json
{ "number": "...", "score": 0..100, "bands": ["HIGH_RISK"], "ruleVersion": "2026-01", "explain": ["reported by 42 distinct users", "recent surge"] }
```
Scoring must be explainable and versioned; community allegations must be distinguishable
from verified findings.

### `GET /v1/account/privacy`
Data-flow disclosure for the privacy dashboard, plus export and deletion endpoints.

### `DELETE /v1/account`
Deletes the account and all server-side personal data. (Client-side not built.)

---

## 5. Cross-cutting requirements

| Requirement | Detail |
|---|---|
| Auth | Bearer token, short-lived access + refresh; verified server-side |
| Rate limiting | Per-token and per-IP; 429 with `Retry-After` |
| Validation | Reject malformed numbers; normalise server-side with the same rules as `PhoneUtils` |
| No bulk export | No endpoint may dump the identity database |
| Audit logging | Every identity write and report is attributable |
| Data minimisation | Do not accept contacts, SMS bodies or recordings on lookup endpoints |
| Purchases | **None.** Kavach is free; there is no billing endpoint and no entitlement system. |

## 6. Client integration points

| Client file | Contract |
|---|---|
| `ai/CloudAiClient.kt` | §1–§3 (wired, fails soft) |
| (future) `data/remote/CallerLookupDataSource.kt` | §4 lookup |
| (future) `data/remote/ReportDataSource.kt` | §4 reports |


## Appeals and moderation

Added at v0.33.0. Before this, `moderation_queue` had a writer and no reader:
numbers were flagged for burst reporting and for crossing a threshold, and every
flag went into a table nobody opened.

### `POST /v1/appeals`

App token. Files an appeal against a number being labelled as spam.

```json
{ "number": "+919876543210", "statement": "This is my own number." }
```

`statement` is optional — requiring one would stop the person most affected (whose
own number is marked as spam) from filing at all.

A second pending appeal from the same install about the same number **updates the
statement rather than filing a duplicate**, so one source cannot bury the queue.

The response is honest about what happens next:

> "Appeal recorded. Nothing changes immediately — a person reviews it, and an
> approved appeal clears the number's score."

### `GET /v1/admin/moderation`

Service token — **not** the app token, which is what every copy of the APK can
obtain. Returns pending items **oldest first**, because a wrong label left standing
for a week is worse than one left for an hour. The number is shown as a short
digest, since the raw value is not stored.

### `POST /v1/admin/moderation/{id}`

Service token. `{"decision": "approved" | "rejected", "note": "..."}`

**Approving clears the number's score and counts**, and closes the other pending
flags on the same number. That is the part that makes this a workflow: without it
the queue would record that a mistake was acknowledged and leave the mistake in
place. Deciding an already-decided item is a `409`.


## Business claims

Added at v0.41.0. This endpoint exists because `Business.verified` did not have
one: the column had been in the schema since the first migration and **nothing
ever set it**, so the directory could show a listing as a business and no listing
could ever become a verified one. A field that cannot become true is a field that
lies.

### `POST /v1/businesses/claim`

App token. A business says a listing is theirs.

```json
{
  "number": "+919876543210",
  "business_name": "Sunrise Diagnostics",
  "category": "Healthcare",
  "evidence": "Registered at sunrise-diagnostics.example, listed in the state register."
}
```

`evidence` is **required** (10–1000 characters). A claim with nothing to check can
only be decided on how confident the claimant sounds, which is not a decision.

A second pending claim from the same source about the same number updates the
statement rather than filing a duplicate.

The response says what happens next, in as many words:

> "Claim received. A person reviews the evidence before the listing is marked
> verified — nothing changes until then."

**Claiming publishes nothing on its own.** That is the point: a business is a claim
about a *person's phone number*, and the only thing standing between a real clinic
and someone impersonating one is a person reading the evidence.

### Approving a claim — `POST /v1/admin/moderation/{id}`

The queue's decision endpoint already existed; what is new is that approving a
`business_claim` now **has an effect**, the same way approving an appeal clears a
score:

1. the number is published under the claimed name with `business` provenance and
   a confidence above community suggestions, so a lookup returns it;
2. the directory row is marked `verified`;
3. a claim with no name publishes nothing, rather than creating an empty record.
