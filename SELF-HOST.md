# Self-hosting the Kavach server

Kavach works with no server at all — call screening, blocking, the dialer,
contacts, recording and the SMS classifier are all on-device, and diagnostics are
local by design. A server adds two things and nothing else:

- **caller lookup** — a name for a number that is not in your contacts
- **spam reputation** — how many people have reported it, and for what

If you want those, this runs your own copy in one command. Nothing is sent
anywhere except to the server you are running.

> **What a self-hosted server does *not* receive:** your diagnostics log, your
> contacts, your messages, or your recordings. The app has no code path that
> uploads any of them, and has not since v0.29.0. That is a design decision, not a
> setting — see [Where the events go](docs/LOGGING_STANDARD.md).

---

## Before you start

You need **Docker** (with Compose v2, which Docker Desktop includes).

| | |
|---|---|
| Linux | `docker` + the `docker-compose-plugin` package, or Docker Desktop |
| macOS | [Docker Desktop](https://docs.docker.com/get-docker/) |
| Windows | Docker Desktop with WSL 2 |

Check it works:

```bash
docker info
```

If that errors, Docker is either not installed or not running. The script will
tell you which.

---

## Run it

```bash
git clone https://github.com/merry8989-uk/kavach.git
cd kavach/backend
./self-host.sh
```

If your checkout lost the executable bit (some archive tools do that):

```bash
bash self-host.sh
```

The first run takes a few minutes — it builds the image, then starts Postgres,
Redis and the API, then waits until `/health/ready` says the database is
reachable. It finishes by printing exactly what to paste into the app.

### What it generates

`self-host.sh` writes `backend/.env` with **mode 600** and four freshly generated
secrets: the bootstrap API token, the phone-hash pepper, the JWT signing key and
the database password.

It will **never overwrite an existing `.env`**. If one is there it says so and
carries on with it, so re-running the script is safe.

Two of those secrets are one-way doors:

- changing `PHONE_PEPPER` invalidates every stored number hash — all reputation
  data becomes unattributable
- changing `JWT_SECRET` signs every user out

---

## Point the app at it

Open **Kavach → Settings → Self-hosted lookup** and enter:

| Field | Value |
|---|---|
| Server URL | `http://<your-machine>:8000` |
| API token | the `API_TOKEN` from `.env` |

Get the token again any time:

```bash
./self-host.sh token
```

**On the same machine** the URL is `http://127.0.0.1:8000`.

**From a phone on the same Wi-Fi** you need the machine's LAN address, because
`127.0.0.1` on a phone means the phone:

```bash
# Linux
hostname -I | awk '{print $1}'

# macOS
ipconfig getifaddr en0
```

The API binds to `127.0.0.1` by default, so it is **not reachable from your phone
until you change that**. For a home network, set in `.env`:

```
API_BIND=0.0.0.0
```

then `./self-host.sh stop && ./self-host.sh`.

Tap **Test connection** in the app before relying on it — it tells you whether the
server answered, not just whether the URL was well-formed.

---

## Reaching it from outside your network

The API speaks plain HTTP and has no opinion about TLS. Do not expose it directly.
Put a reverse proxy in front:

```nginx
server {
    listen 443 ssl;
    server_name kavach.example.com;

    ssl_certificate     /etc/letsencrypt/live/kavach.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/kavach.example.com/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:8000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        # The API logs the request id it is given, so one user action can be
        # followed across the app and the server. Worth forwarding.
        proxy_set_header X-Request-Id $request_id;
    }
}
```

Keep `API_BIND=127.0.0.1` when you do this — the proxy is the only thing that
should reach the API.

**One thing to know before exposing it:** the API token ships inside the APK, so
it is a *registration* key rather than a secret. Anyone with the app can register
an install against your server. That is why `ALLOW_SHARED_TOKEN=false` — after
registration the app uses a per-install token, and the shared one cannot read or
write anything else. If you want to run this for yourself only, leave it bound to
localhost or your LAN.

---

## Commands

```bash
./self-host.sh            # set up and start
./self-host.sh status     # what is running, and is the API healthy
./self-host.sh token      # print the token to paste into the app
./self-host.sh logs       # follow the API log
./self-host.sh stop       # stop, keeping the database
./self-host.sh destroy    # stop and DELETE the database (asks you to confirm)
```

---

## Sign-in, and why it returns 503

User sign-in needs an SMS provider, because it works by sending a one-time code.
With `SMS_PROVIDER=none` — the default — the OTP endpoint returns **503** with a
clear reason rather than pretending to have sent a code.

That is deliberate. A sign-in that reports success and delivers nothing is worse
than one that is honestly unavailable, because the user waits for a message that
was never sent.

To enable it, set one of these in `.env` and restart:

| Provider | Use it when | Needs |
|---|---|---|
| `msg91` | India | a DLT-approved template (TRAI requires this) |
| `twilio` | anywhere else, or testing | an account SID and a from-number |

Everything that does not need an account — caller lookup, spam reputation,
reporting, appeals — works without a provider.

---

## AI features

Left unset, the AI endpoints return **503** rather than inventing a result. There
is no bundled model and no fallback that guesses.

| Variable | What it is for |
|---|---|
| `ASR_PROVIDER_URL`, `ASR_PROVIDER_KEY` | speech-to-text for call transcription |
| `LLM_PROVIDER_URL`, `LLM_PROVIDER_KEY` | call summaries and classification |

Point them at any OpenAI-compatible endpoint you are willing to send audio to.
Leave them blank if you are not.

---

## Checking it is healthy

```bash
curl -s http://127.0.0.1:8000/health        # the process is up
curl -s http://127.0.0.1:8000/health/ready  # the process can reach Postgres
```

Two endpoints on purpose. A container that answers liveness but cannot reach its
database should be taken out of rotation, not reported healthy.

`/health/ready` also reports whether rate limiting is shared across replicas.

---

## What you are running

| | |
|---|---|
| `GET /v1/lookup` | name, source, confidence and spam risk for a number |
| `GET /v1/businesses/search` | the business directory |
| `POST /v1/reports` | report spam, with categories |
| `POST /v1/appeals` | challenge a wrong label |
| `GET /v1/admin/moderation` | the moderation queue (service token only) |
| `GET /docs` | the generated OpenAPI documentation |

Full contract: [docs/API_CONTRACT.md](docs/API_CONTRACT.md).

The number itself is never stored — only a salted hash of it. Changing
`PHONE_PEPPER` breaks that link permanently, which is the point of having it.

---

## Troubleshooting

**"Docker is installed but not running"**
Start Docker Desktop, or `sudo systemctl start docker`. On Linux you may also need
to be in the `docker` group: `sudo usermod -aG docker $USER`, then log out and in.

**"The API container is not running"**
The script prints the container's last 40 log lines. The most common cause is a
missing value in `.env`, and the message names which one. The production guard
refuses to start on a development default rather than running insecurely.

**It starts, but the phone cannot reach it**
Almost always `API_BIND`. It defaults to `127.0.0.1`, which is not reachable from
another device. Set `API_BIND=0.0.0.0` and restart. Check your firewall too.

**"Test connection" fails but `curl` works from the machine**
The app requires HTTPS for anything that is not a private address. A plain
`http://` URL to a public host will be refused — put the reverse proxy in front.

**I lost the token**
`./self-host.sh token`. It reads it back out of `.env`.

**I want to start over**
`./self-host.sh destroy` deletes the database volume (it asks you to confirm),
then delete `backend/.env` and run `./self-host.sh` again.
