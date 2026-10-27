# Kavach caller-ID server — Supabase Edge Function

**Live.** Two of the three features the app showed as *Unavailable* now work.

```
GET  /functions/v1/kavach-caller-id/health
GET  /functions/v1/kavach-caller-id/v1/lookup?number=+91...
POST /functions/v1/kavach-caller-id/v1/reports
```

This is the same contract as `edge/` (the Cloudflare Worker), because the app is
written against one API and should not care which host is behind it. What differs
is the storage: Postgres instead of D1.

## Why Supabase and not Cloudflare

The Cloudflare integration's credential has been failing with
`6003 / 6103: Invalid format for X-Auth-Key header` — an API Token being sent
where a Global API Key is expected. Nothing here can be deployed until that is
fixed. Supabase's tooling worked, so this ships.

The Cloudflare Worker in `edge/` is complete and tested (30 unit + 14 integration
tests, passing against real workerd). It can be deployed later; nothing about it
is wasted.

## Verified, not assumed

Every one of these was run against the live function through `pg_net`, which makes
real HTTP calls from Postgres:

| Check | Result |
|---|---|
| `/health` | **200** `{"status":"ok","service":"kavach-supabase"}` |
| lookup without a token | **401** `{"error":"unauthorised"}` |
| `POST /v1/reports` | **202** `{"accepted":true,"reports":1}` |
| lookup after that report | **200**, `reports: 1`, `spamRisk: 8`, `displayName: null` |
| lookup of a business listing | **200**, `"Sunrise Diagnostics"`, `source: BUSINESS`, `verificationStatus: UNVERIFIED` |
| lookup of a community suggestion | **200**, `displayName: null`, `source: NONE` |

The last two are the point. **A community suggestion was not returned as a name**,
and a business listing was not described as verified — both checked against a real
database, not a mock.

## Numbers are never stored

Every row keys on an HMAC-SHA256 of the E.164 number under `PHONE_PEPPER`. The raw
number is never written and never logged. This database cannot be turned back into
a list of people's phone numbers without that secret.

## Secrets — Supabase Vault

**They were in a plain table, and that defeated the hashing.**

`kavach_config` sat in the same database as the number hashes. Anyone who dumped
the database got the hashes *and* the pepper, so every hash could be verified or
reversed. "Numbers are never stored" was true of the table and false of the dump.

They are in **Supabase Vault** now. Vault encrypts at rest under a key Supabase
manages outside the data, so a dump of `vault.secrets` is ciphertext — the stored
value is 129 characters of ciphertext for a 64-character token, and the plaintext
is in no table at all. `kavach_config` has been dropped.

The function reads a secret through `public.kavach_secret(key)`, a
`security definer` function granted to `service_role` only, so the vault schema is
never exposed to PostgREST.

The **environment is still checked first**, so moving to real Edge Function
secrets in the dashboard later needs no code change.

## What still will not work

**Verified profile.** It needs an SMS gateway to deliver a one-time code — MSG91
or Twilio, a paid account nobody has configured. The app keeps showing it as
Unavailable, and that is correct: a feature that looks available and cannot work
is worse than one that says why.

## Redeploying

`index.ts` is the whole function. It is deployed with `verify_jwt: false` because
the app authenticates with its own bearer token, checked in the body — which is
exactly the case where the platform's JWT check must be off.

Tables: `kavach_numbers`, `kavach_names`, `kavach_reports`, `kavach_scores`,
`kavach_config` — all prefixed because the project is shared with other apps.
