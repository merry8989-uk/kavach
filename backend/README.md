# Kavach Backend

Caller identity, spam reputation and AI adapters for the Kavach Android app.
Implements [`docs/API_CONTRACT.md`](../docs/API_CONTRACT.md).

**Status: runs and is tested.** 40 tests pass locally and in CI. It is not
deployed, and no AI provider is configured — the AI endpoints deliberately
return `503` rather than fabricating a transcript or summary.

## Stack

| Concern | Choice |
|---|---|
| Framework | FastAPI (Python 3.12) |
| ORM | SQLAlchemy 2.0 |
| Database | PostgreSQL (SQLite for dev/tests) |
| Migrations | Alembic |
| Config | pydantic-settings, environment only |
| Tests | pytest + FastAPI TestClient |

## Quick start (Docker)

```bash
cd backend
docker compose up --build
# API on http://localhost:8000  ·  docs at /docs
```

This starts Postgres, Redis and the API, and runs `alembic upgrade head` on boot.

## Quick start (local)

```bash
cd backend
python -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env          # then edit API_TOKEN
alembic upgrade head
uvicorn app.main:app --reload
```

## Tests

```bash
cd backend
pytest                 # 40 tests
```

Covered: number normalisation, spam scoring (caps, verified-business cap,
false-positive discount, bounds), lookup (unknown → explicit no-result, source
trust ordering, auth), reports (dedupe, reputation effect, corrections held for
moderation), business search (filtering, pagination), account privacy/deletion,
and the AI endpoints' honest `503` behaviour.

## Endpoints

| Method | Path | Notes |
|---|---|---|
| GET | `/v1/lookup` | Caller identity. Returns `source: NONE` + `unavailableReason` when nothing reliable exists — never invents a name |
| GET | `/v1/number-reputation` | Explainable, versioned score |
| GET | `/v1/businesses/search` | Paginated directory |
| POST | `/v1/reports` | Spam report; deduplicated per actor per number per 30 days |
| POST | `/v1/identity-corrections` | Held as `PENDING`; never auto-applied |
| POST | `/v1/transcribe` | `503` unless `ASR_PROVIDER_URL` is set |
| POST | `/v1/summarise` | `503` unless `LLM_PROVIDER_URL` is set |
| POST | `/v1/dialogue` | `503` unless `LLM_PROVIDER_URL` is set |
| GET | `/v1/account/privacy` | Data-flow disclosure |
| DELETE | `/v1/account` | Removes the caller's reports, corrections and consent records |
| GET | `/health` | Liveness |

All except `/health` require `Authorization: Bearer <API_TOKEN>`.

## Design decisions

- **No fabricated identity.** A prefix, circle or carrier is never treated as
  proof of a person's identity. Unknown numbers return an explicit no-result.
- **Verified means verified.** `verification_status` only becomes `VERIFIED`
  through a verification path, never from a report count.
- **One actor cannot dominate.** Reports are deduplicated per actor per number,
  and the scorer caps both distinct reporters and recent reports.
- **Explainable scoring.** Every score ships with human-readable reasons and a
  `ruleVersion`.
- **Corrections are moderated**, not auto-applied.
- **Honest AI.** The adapters proxy a configured provider or return `503`.

## Production hardening still required

- Move the rate limiter to Redis (`security.py`) so it is shared across instances.
- Real account authentication (the bearer token is a single shared secret here).
- Audit-log retention policy and log scrubbing.
- Dependency vulnerability scanning.
- Load testing and alerting.
- A moderation workflow for corrections and reports.

## Environment

See [`.env.example`](.env.example). Never commit a real `.env`.
