# Deploying the Kavach server

The server is what makes the app useful beyond one phone: caller lookup,
spam reputation, the consent database, and — at any real scale — the place
diagnostics events land so you own one dashboard instead of asking every user
to make their own Notion integration.

It ships as a container image, so deploying is three commands.

---

## 1. Get the image

Every push to `backend/**` on `main` (or a `server-v*` tag, or a manual
`Server Release` run) publishes a multi-architecture image to GitHub Container
Registry:

```
ghcr.io/merry8989-uk/kavach-server:latest
ghcr.io/merry8989-uk/kavach-server:<version>
ghcr.io/merry8989-uk/kavach-server:sha-<commit>
```

amd64 and arm64, so the same image runs on a normal VPS or an ARM one.

If the package is private, log in once:

```
echo $GITHUB_TOKEN | docker login ghcr.io -u YOUR_GITHUB_USERNAME --password-stdin
```

---

## 2. Prepare the server

Any small VPS is enough — 1 vCPU / 1 GB is plenty for a few hundred installs.

```bash
# Docker
curl -fsSL https://get.docker.com | sh

# The compose files
git clone https://github.com/merry8989-uk/kavach.git
cd kavach/backend

# Configuration
cp .env.example .env
```

Now edit `.env`. Three values must be generated, not invented:

```bash
python3 -c "import secrets; print('API_TOKEN=' + secrets.token_urlsafe(48))"
python3 -c "import secrets; print('PHONE_PEPPER=' + secrets.token_urlsafe(32))"
python3 -c "import secrets; print('JWT_SECRET=' + secrets.token_urlsafe(48))"
```

- **API_TOKEN** — the app sends this, and you paste the same value into
  Kavach → Settings → Server. Rotating it means re-pasting into every installed
  app, so generate it once and keep it.
- **PHONE_PEPPER** — the HMAC key behind phone hashing. If it leaks, stored
  hashes become reversible by dictionary attack. Changing it invalidates every
  existing hash.
- **JWT_SECRET** — signs per-user login tokens. Changing it signs everyone out.

Also set `POSTGRES_PASSWORD`. Then:

```bash
chmod 600 .env
```

---

## 3. Run it

```bash
docker compose -f docker-compose.prod.yml up -d
```

That brings up three things:

| Service | What it does |
|---|---|
| `db` | Postgres, not exposed to the host |
| `api` | Migrates on boot, then serves on `:8000` |
| `maintainer` | Every 15 min: drains telemetry into Notion, enforces retention |

Check it:

```bash
curl -s localhost:8000/health        # liveness
curl -s localhost:8000/health/ready  # readiness — actually touches Postgres
```

`/health` says the process is up. `/health/ready` says the database is
reachable, which is the one to point a load balancer at.

---

## 4. Put it behind TLS

Do not run this over plain HTTP: the app sends its API token on every request.

The simplest option is Caddy, which gets a certificate automatically:

```
# /etc/caddy/Caddyfile
your-domain.example {
    reverse_proxy 127.0.0.1:8000
}
```

Point an A record at the server first, then `systemctl reload caddy`.

---

## 5. Point the app at it

In Kavach:

1. **Settings → Server (advanced)**
   - Server URL: `https://your-domain.example`
   - API token: the `API_TOKEN` from `.env`
2. **Settings → Diagnostics & logs → Notion dashboard**
   - *Send to the Kavach server* → **ON**

From then on every install sends its events here. Enable
**Notion** as well (or instead) if you also want the server to write into your
own Notion database — that uses `NOTION_TOKEN` from `.env`, so no user ever
needs a Notion account.

---

## What the server refuses to do

On startup in production it checks its own configuration and **exits with a
list of problems** rather than running insecurely. It will not start if:

- `API_TOKEN` is empty, a documented default, or shorter than 24 characters
- `PHONE_PEPPER` is a default or shorter than 16 characters
- `JWT_SECRET` is a default
- `AUTO_CREATE_TABLES` is true (Alembic should own the schema)
- `DATABASE_URL` points at SQLite
- `OTP_DEBUG_RETURN` is true (it hands out login codes)

That list is the whole point: a server that starts with a published secret is
worse than one that does not start.

---

## Backups

The database holds the consent records and the telemetry — the only part of
Kavach that cannot be regenerated. Back it up:

```bash
docker compose -f docker-compose.prod.yml exec -T db \
  pg_dump -U kavach kavach | gzip > kavach-$(date +%F).sql.gz
```

Put that in a nightly cron and copy the file off the machine.

---

## Honest limits

- **The rate limiter is in-process.** Running more than one API replica behind
  a load balancer gives each replica its own budget. A shared limiter needs
  Redis, which the dev compose file already provisions.
- **No log shipping for the server itself.** Its own stdout is plain uvicorn
  output, not the app's JSON schema. Only the Android app is on the schema.
- **Telemetry retention is 30 days**, enforced by the `maintainer` service.
  Change `keep_days` on the purge call if you need longer.
- **You are the data fiduciary.** Storing other people's diagnostics makes you
  responsible under the DPDP Act for retention, deletion requests and a
  grievance contact. The code enforces retention; the paperwork is yours.
