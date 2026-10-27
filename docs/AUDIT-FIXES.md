# Audit response — what was fixed, what was not

Response to the full repository audit. Every finding was checked against the
actual code before anything changed; two of them were **live in the deployed
Supabase function**, and one was a regression I had introduced myself.

---

## Release blockers

### 1. Admin authentication — **fixed**

**Found:** the moderation endpoints used `require_token`, which verifies
`api_token` — the same value that ships in the APK as the bootstrap token.

**Fixed:** a separate `ADMIN_TOKEN`, checked by a new `require_admin_token`. It
never appears in the app or in a client build.

Three behaviours worth naming, because each is a way this goes wrong again:

- **Refused, not fallen back.** With no `ADMIN_TOKEN` configured the endpoint
  answers **503** and says why. A fallback to `api_token` would restore the exact
  hole, invisibly.
- **The production guard refuses a shared one.** If `ADMIN_TOKEN == API_TOKEN` the
  server will not start, and the message says which pair is wrong.
- **401 and 403 stay distinct.** 401 means "tell me who you are"; 403 means "I
  know who you are and you may not". A client that cannot tell them apart reports
  the wrong problem.

### 2. Telemetry purge — **fixed**

**Found:** `POST /v1/admin/telemetry/purge` accepted ordinary install
authentication, so any valid install could delete every record older than a day
with `keep_days=1`.

**Fixed:** `require_admin_token`. A maintenance job is not a user action, and an
install is not an operator. There is now a test that the bootstrap token gets
`401`/`403` here.

### 3. Supabase reporter identity — **fixed, and it was live**

**Found:** the function hashed the `Authorization` header to identify a reporter.
Every client sends the same `APP_TOKEN`, so **every reporter produced an identical
hash**. Reports upsert on `(number_hash, reporter_hash)`, so the second person's
report **replaced** the first instead of adding to it. A crowd of fifty looked
like one person.

**Fixed:** the app now sends `X-Kavach-Install` — its own per-install pseudonym,
`KV-XXX-XXX`, generated on the device from nothing about the person. The function
hashes that, and **refuses a report without it** rather than silently collapsing
it into one bucket.

**Verified against the live function:** install A reports → `reports: 1`; install
B reports the same number → **`reports: 2`**. Before the fix it stayed at 1.

### 4. Android registration — **fixed**

**Found:** the client never called `/v1/app/register`. It saved the bootstrap
token and sent it on every request, while the backend's lookup and report
endpoints expect a per-install token — a 403 the user could not diagnose.

**Fixed:** `testConnection` now registers after a successful health check,
stores the issued token in the Keystore (`SecretStore.INSTALL_TOKEN`), and every
request prefers it over the bootstrap token.

Registering at that moment rather than lazily before the first call, because that
is when the user is watching and a failure is something they can act on. A server
that does not issue tokens (the Supabase function accepts the shared one) says so
and carries on rather than failing the connection.

### 5. Health-check mismatch — **fixed, and it was live**

**Found:** the app's `testConnection` requests `/health/ready`; the Supabase
function answered only `/health`, and `endsWith('/health')` does not match
`/health/ready`.

**Impact was worse than a 404.** The app reported *"Something answered at that
address, but it is not a Kavach server"* — against a server that was working
perfectly. A health check that lies about a healthy server is worse than none.

**Fixed:** the function answers both paths, and `/health/ready` does a real
database round trip so "ready" means what it means in the FastAPI backend.

**Verified against the live function:** `/health/ready` → **200**
`{"status":"ready","database":"ok"}`.

---

## Privacy and reliability

| Finding | Status |
|---|---|
| Raw phone numbers in legacy FastAPI models | **Not fixed.** Real, and the claim "numbers are never stored" is not universally true of this repo — only of the newer hashed model and the two edge implementations |
| Retention promise vs actual cleanup | **Not fixed** |
| Export claim with no export endpoint | **Not fixed** |
| `X-Forwarded-For` trusted by the rate limiter | **Fixed.** Off by default; only honoured behind a proxy that overwrites it. A rate limit whose key is supplied by the limited party is not a limit |
| JWT secret had no minimum length | **Fixed.** 32 characters, enforced by the production guard |
| OTP store in-process, not multi-instance safe | **Not fixed** |
| Redis limiter failure allows requests | **Not fixed** |
| AI request body unbounded | **Not fixed** |

The four unfixed ones are honest gaps, not oversights: each needs a decision about
behaviour (what should happen when the limiter is down, how long OTP state
survives a restart) rather than a mechanical change.

---

## Cross-implementation drift

**Found:** three backends — FastAPI/Postgres, Supabase Edge Function, Cloudflare
Worker/D1 — with no guarantee they agree.

**Fixed, in the cheapest useful form:** `edge-supabase/contract.test.ts` asserts
that the two edge implementations agree on the report categories, the publishable
provenance, the score cap and the report window. Plus a CI workflow that type
checks the function and runs those contract tests, because the audit was right
that Android CI passing was never evidence this API works.

The FastAPI backend is not covered by the contract tests. It has a different,
much larger surface, and pretending one test file covers all three would be the
kind of claim this document exists to avoid.

---

## What the audit got right about process

> *Source code inspect karna aur deployed service ko live test karna alag cheezein
> hain.*

That distinction is the reason two of these were found live rather than in review:
they compiled, they passed lint, and they made the deployed feature quietly wrong.
Every fix above is recorded with the HTTP response that proves it, not with the
line that changed.
