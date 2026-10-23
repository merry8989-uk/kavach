# Monitoring on Cloudflare

A Cloudflare Worker that receives the server's structured logs and answers the
questions worth asking about it: how slow, how many failures, which endpoint is
the problem, which provider is down.

```
backend (anywhere)  ──POST /v1/logs──▶  Worker  ──▶  D1  ──▶  dashboard
```

---

## Read this first: what this is, and what it is not

**It is** the server's own health. Categories 6, 7, 8 and 11 of the logging
taxonomy — infrastructure, network, database, background work. Latency, error
rates, 503s from an unconfigured provider. None of this can be seen from a phone.

**It is not** where the app's diagnostics go. Those stay on the device and leave
only when the user chooses to send them. That is a standing instruction and a
design decision.

**And it is enforced, not documented.** Every event the app emits carries a
`user_code` — its readable per-install pseudonym. The server never emits one. So
the sink refuses any event where that key is present at all:

```js
if ('user_code' in input) return { rejected: REJECTION.USER_DIAGNOSTICS };
```

A test pins it, including the empty-string and null cases. The rule is not "please
do not send user diagnostics here" — it is that they **cannot be stored here even
if something tried**.

---

## The other thing to know before you start

**The Kavach backend cannot run on Cloudflare Workers.**

It is FastAPI + SQLAlchemy + PostgreSQL + Redis. Workers run JavaScript and WASM;
there is no Python runtime that can hold a `psycopg` connection. D1 is SQLite, and
the backend's queries and migrations are Postgres-specific.

So this Worker is the **right half** of a Cloudflare deployment, and the backend
runs somewhere else — a VPS, Fly.io, Railway, or a container host — shipping its
logs here. That split is normal: Cloudflare is excellent at the edge and at small
aggregate workloads, and this is exactly that shape.

If you want the whole thing on Cloudflare, the only path today is **Cloudflare
Containers** (beta, paid plan) running the existing Docker image, with an external
Postgres behind it. See [the bottom of this file](#if-you-want-everything-on-cloudflare).

---

## Deploy

You need Node 18+ and a Cloudflare account.

```bash
cd monitoring
npm install -g wrangler        # or: npx wrangler@latest
wrangler login
```

**1. Create the database**

```bash
wrangler d1 create kavach-monitoring
```

Paste the printed `database_id` into `wrangler.toml`, replacing
`REPLACE_WITH_YOUR_D1_ID`.

**2. Create the table**

```bash
wrangler d1 execute kavach-monitoring --remote --file=schema.sql
```

**3. Set the ingest token**

```bash
# Generate one first:
python3 -c "import secrets; print(secrets.token_urlsafe(48))"

wrangler secret put INGEST_TOKEN
```

This is the token the backend sends. Anyone who has it can write to your
dashboard, so treat it as a secret and do not put it in `wrangler.toml`.

**4. Deploy**

```bash
wrangler deploy
```

It prints the URL, something like
`https://kavach-monitoring.<your-subdomain>.workers.dev`.

**5. Check it**

```bash
curl https://kavach-monitoring.<sub>.workers.dev/health
# {"ok":true}

curl -H "Authorization: Bearer $TOKEN" \
  "https://kavach-monitoring.<sub>.workers.dev/v1/summary?window=24h"
```

Open the root URL in a browser for the dashboard. It will be empty until the
backend starts shipping, which is expected.

---

## Point the backend at it

Three environment variables on the server:

```
LOG_SHIP_URL=https://kavach-monitoring.<sub>.workers.dev/v1/logs
LOG_SHIP_TOKEN=<the token from step 3>
LOG_SHIP_ENABLED=true
```

Set them in `.env` (see `backend/.env.example`). Restart, make a request, then
reload the dashboard — the `network` category should show it.

Shipping is **off by default**. A server that starts posting to an endpoint
somebody typed into a config file without meaning to is a server that leaks, and
the default should be the quiet one.

---

## What it stores

| Column | From |
|---|---|
| `ts`, `severity`, `category`, `message` | the envelope |
| `app_id`, `environment`, `request_id`, `logger` | the envelope |
| `duration_ms`, `status`, `endpoint` | **lifted out of `metadata`** |
| `exception_type` | the envelope |
| `metadata` | the rest, as JSON, capped at 4 KB |

Those three lifted fields are the point. They turn latency, error rate and
slow-endpoint reporting into plain indexed SQL instead of JSON parsing inside
SQLite — which is the difference between a query that runs and one that scans.

### Endpoints

| | |
|---|---|
| `GET /` | the dashboard |
| `GET /health` | liveness |
| `POST /v1/logs` | ingest, bearer token. `{events: [...]}`, max 200 per batch |
| `GET /v1/summary?window=1h\|6h\|24h\|7d\|30d` | aggregates |
| `GET /v1/recent?category=&severity=&limit=` | recent events |

### Rejections are reported, never silent

A batch response says what was stored **and what was refused, and why**:

```json
{ "stored": 18, "rejected": { "user_diagnostics_not_accepted": 2 } }
```

A sink that quietly discards is a sink that lies about its coverage.

---

## Retention

Thirty days. A cron trigger runs daily at 04:17 UTC and deletes anything older.

A sink with no ceiling eventually costs more than it is worth and holds data
nobody agreed to keep. Thirty days is enough to see a trend and short enough to
forget a bad week.

To change it, edit `RETENTION_DAYS` in `src/index.js` and the `crons` entry in
`wrangler.toml`.

---

## Tests

```bash
node --test test/
```

No dependencies — Node's own runner. 25 tests covering the guard, the
vocabularies, the metadata lifting, timestamp parsing, windows, and the summary
queries against a fake D1.

The guard test is the one worth reading. Its first version passed `user_code:
"KV-4F9-K2Q"` and asserted a refusal — which it got, for the wrong reason. The
implementation used `if (input.user_code)`, so an empty string would have sailed
through. The test now covers empty and null, and the code checks for the key's
presence instead.

---

## Cost

D1's free tier is 5 GB and 5 million row reads a day. A server doing a few
thousand requests a day produces well under that, and the retention sweep keeps
it bounded. Workers' free tier is 100,000 requests a day. For one operator
watching one backend this should sit inside both.

---

## If you want everything on Cloudflare

The backend is a container. Cloudflare Containers (beta, paid) can run it:

```bash
# In the backend directory, alongside the existing Dockerfile.
npx wrangler containers push kavach-server:latest
```

Then bind a container to a Worker and route `/v1/*` to it, with an external
Postgres reachable through **Hyperdrive**. Two things to know before committing
to that path:

- **Containers are beta and need a paid plan.** Check current pricing and
  availability for your account rather than trusting this file.
- **You still need a real Postgres somewhere.** Hyperdrive connects a Worker to an
  external database; it does not provide one. D1 is not a substitute — the
  backend's queries, indexes and Alembic migrations are Postgres-specific.

For a single self-hosted server, running the container on a small VPS and using
Cloudflare for DNS, TLS and this monitoring sink is simpler, cheaper and has fewer
moving parts. See [SELF-HOST.md](../SELF-HOST.md).
