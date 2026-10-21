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
