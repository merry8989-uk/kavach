# Getting a server, without a server

The Android app shows three features as **Unavailable** when it has no server:
verified profile, community reports, and online caller lookup. They need a shared
database of names and reports, so no amount of local work makes them function —
they need somewhere to talk to.

This is the shortest honest path to that, and it starts on GitHub.

---

## The thing that was actually blocking you

Before any of this, one bug made every deployment harder than it needed to be.

The server refused to start in production without an SMS provider. That check sat
in the same list as the published-default secrets, so a server with no SMS gateway
would not boot at all — and **caller lookup, spam reputation and business claims
have nothing to do with SMS**. One optional provider, not yet configured, took
down the three features you wanted.

The two are separate now:

| | What it means | What happens |
|---|---|---|
| `unsafe_configuration()` | Published secrets, debug OTP, SQLite in production | **Refuses to start.** A server running with a secret that is in the public repo is worse than one that is down |
| `unconfigured_features()` | No SMS provider, no AI provider | **Starts anyway**, logs the gap at boot, and the endpoints for that feature answer 503 |

A deployment you cannot finish is a deployment that gets abandoned, which is its
own kind of unsafe. Fixed in `backend/app/config.py`; the behaviour is pinned by
`backend/tests/test_server_boots.py`, which boots a real server and talks to it.

---

## Option 0 — already running (Supabase Edge Function)

Two of the three features are live on a Supabase Edge Function. See
`edge-supabase/README.md` for the URL and what was verified. This is what the app
should point at today.

## Option 1 — Render, one click

`render.yaml` is in the repository root. Render reads it and creates the web
service and the database together.

1. Sign in at [render.com](https://render.com) with GitHub.
2. **New → Blueprint**, pick `merry8989-uk/kavach`.
3. It asks for three secrets. Generate each with:

   ```bash
   python3 -c "import secrets; print(secrets.token_urlsafe(48))"
   ```

   Paste one into `API_TOKEN`, one into `PHONE_PEPPER`, one into `JWT_SECRET`.
   They are not interchangeable — the pepper is what makes phone hashes
   irreversible, and losing it means every stored hash is meaningless.

4. Deploy. It builds from `ghcr.io/merry8989-uk/kavach-server:latest` — the image
   CI already built and tested, so what runs is what passed.

You get a URL like `https://kavach-server.onrender.com`. Put that into
**Settings → Self-hosted lookup** in the app.

**Two things to know.** Render's free web service sleeps after inactivity, so the
first request after a quiet spell takes a few seconds — the app handles that, but
it is why the first lookup may feel slow. And the free Postgres expires after 90
days. Fine for trying; move to a paid instance before you rely on it.

## Option 2 — a GitHub Codespace

The most literal "on GitHub". A `.devcontainer/` is in the repository, so:

1. **Code → Codespaces → Create codespace on main.**
2. When the terminal appears: `bash .devcontainer/start.sh`
3. The **Ports** tab has a forwarded address for port 8000. Copy it.

It generates its own secrets, starts a Postgres, migrates, and prints the token to
paste into the app.

**This is not a host.** A codespace stops when it goes idle and is deleted after
its retention window. Good for testing a change against a real server; wrong for
anything that has to answer when you are not looking.

## Option 3 — your own machine, or a VPS

```bash
cd backend
bash self-host.sh
```

That is `SELF-HOST.md` in the repository root. It generates the secrets, writes a
mode-600 `.env` without ever overwriting one, builds, starts, and waits on
`/health/ready` before printing the URL and token. It fails loudly with an exact
reason rather than telling you to check the logs.

---

## What still will not work

**Sign-in.** OTP needs an SMS gateway — MSG91 or Twilio — and that is a paid
account you have to create. Until then the sign-in endpoints answer 503, which is
deliberate: an unconfigured provider should say so, not fail in a way that looks
like a bug.

**The app's diagnostics never go to your server.** That is a separate decision and
it has not changed: the log stays on the device and leaves only when the user
chooses to send it. The server is for caller lookup, spam reputation and business
claims. `monitoring/README.md` covers the server's own monitoring, which is a
different thing again.

---

## Pointing the app at it

**Settings → Self-hosted lookup** → server URL and token → **Test connection**.

The URL policy rejects anything that is not `https` or a private address, so a
plain `http://` public host is refused on purpose — the token would otherwise go
over the wire in clear. See `data/remote/ServerUrlPolicy.kt`.

Once it connects, the three features stop saying *Unavailable*. They still have to
be switched on individually, because the server being reachable is not the same as
you wanting your number published.
