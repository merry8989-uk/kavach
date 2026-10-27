# Kavach edge server

A Cloudflare Worker that gives the Android app the two features it shows as
**Unavailable**: online caller lookup, and community reports.

```
app  ──GET /v1/lookup──▶  Worker  ──▶  D1
     ──POST /v1/reports──▶
```

---

## What this is, and what it is not

**It is** a real server for two of the three features that need one.

**It is not** a home for the third. **Verified profile needs an SMS gateway** to
deliver an OTP, and that is a paid account nobody has configured. The app will
keep saying so, because the alternative is a feature that looks available and
cannot work.

**It is not** the FastAPI backend in `backend/`. That is Python with Postgres and
it cannot run on Workers at all — Workers run JavaScript and WASM. This is a
deliberate, narrow rewrite of two endpoints, not a port.

---

## Numbers are never stored

Every row keys on `number_hash`, an HMAC-SHA256 of the E.164 number under a pepper
that lives in a Worker secret.

The raw number is never written, never logged, and cannot be recovered from the
database without the pepper. A phone number identifies a person; a table of them
is a liability whether or not the names beside them are right.

Rotating the pepper makes every stored hash useless. That is a blunt instrument,
but it is one that exists — and it is why `PHONE_PEPPER` must not be the same
value as anything else.

---

## What it refuses to do

A caller-ID service that guesses is worse than one that says it does not know,
because a wrong name beside a number is a claim about a person. So:

| | |
|---|---|
| **A community suggestion is never returned as a name** | It is counted. `buildLookup` will not publish `community` provenance, and there is a test for it |
| **Only a self-declared name is called verified** | A business listing is a listing, not a verification |
| **A score never reaches 100** | It caps at 95. A campaign of bad-faith reports must not look like proof |
| **One report per person per number** | Two taps is one report. Otherwise one annoyed person is indistinguishable from a crowd |
| **Old reports fade** | 180 days. A complaint from three years ago should not still be a sentence |
| **An unknown number says so** | `source: NONE` and a reason, not a blank card |

---

## Deploy

Needs Node 18+ and a Cloudflare account with Workers and D1.

```bash
cd edge
npm install -g wrangler
wrangler login
```

**1. Create the database**

```bash
wrangler d1 create kavach-edge
```

Paste the printed `database_id` into `wrangler.toml`.

**2. Create the tables**

```bash
wrangler d1 execute kavach-edge --remote --file=schema.sql
```

**3. Set the two secrets**

```bash
python3 -c "import secrets; print(secrets.token_urlsafe(48))"   # APP_TOKEN
python3 -c "import secrets; print(secrets.token_urlsafe(32))"   # PHONE_PEPPER

wrangler secret put APP_TOKEN
wrangler secret put PHONE_PEPPER
```

`APP_TOKEN` is what the app sends. `PHONE_PEPPER` is what makes the stored hashes
irreversible — **set it once and keep it**, because changing it orphans every row.

**4. Deploy**

```bash
wrangler deploy
```

**5. Check it**

```bash
curl https://kavach-edge.<sub>.workers.dev/health
# {"status":"ok","service":"kavach-edge"}

curl -H "Authorization: Bearer $APP_TOKEN" \
  "https://kavach-edge.<sub>.workers.dev/v1/lookup?number=%2B919876543210"
# {"number":"+919876543210","displayName":null,"source":"NONE",...}
```

---

## Point the app at it

**Settings → Self-hosted lookup** → the Worker URL and `APP_TOKEN` → **Test
connection**.

The URL policy rejects a plain `http://` public host on purpose — the token would
otherwise go over the wire in clear. `workers.dev` is `https`, so it is fine.

Once it connects, **Online caller lookup** and **Community reports** stop saying
*Unavailable*. They still have to be switched on individually: the server being
reachable is not the same as you wanting your number looked up.

---

## Endpoints

| | |
|---|---|
| `GET /health` | open, because Test connection asks before it has a token |
| `GET /v1/lookup?number=` | what is known about a number |
| `POST /v1/reports` | `{number, category, note?}` |

Categories: `telemarketing`, `fraud`, `loan`, `insurance`, `survey`, `robocall`,
`silence`, `other`. An unknown one is **refused**, not filed as `other` — that
would hide a typo forever.

---

## Tests

```bash
npm install
npm test              # both files
npm run test:unit     # the rules, no runtime needed
npm run test:integration
```

**Unit — 30 tests.** Number normalisation, hashing, the score curve, freshness,
the lookup rules, and report validation.

**Integration — boots the Worker in Miniflare** with a real D1 and dispatches real
requests. It proves the routes exist, the binding matches, and D1 accepts the SQL.
It needs about 3 GB and **skips itself with the reason** below that, checking
memory *before* the miniflare import.

The two worth reading are the ones that assert a **community suggestion is never
returned as a name**, in both files — unit for the rule, integration for the rule
surviving a real database round trip.

---

## Cost

D1's free tier is 5 GB and 5 million row reads a day; Workers' is 100,000 requests
a day. For a lookup service at hobby scale this sits inside both with room.

---

## The Cloudflare credential

Deploying this needs a working Cloudflare credential on the tooling side. It has
been failing with:

```
error 400, code 6003 / 6103: "Invalid format for X-Auth-Key header"
```

That means an **API Token** is being sent where a **Global API Key** is expected.
Create an API Token at `My Profile → API Tokens` with:

```
Account · Workers Scripts · Edit
Account · D1               · Edit
Account · Account Settings · Read
```

Until that is fixed, nothing here can be deployed — `wrangler d1 create` needs the
same credential.
