"""Kavach backend API.

Run locally:   uvicorn app.main:app --reload
Tests:         pytest
Schema:        alembic upgrade head   (production; dev can auto-create)
"""
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.responses import JSONResponse
from sqlalchemy import text

from . import models  # noqa: F401  (register models with Base.metadata)
from .config import get_settings
from .db import Base, get_engine
from .routers import account, ai, app_auth, auth, businesses, lookup, me, reports, telemetry


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()

    # Refuse to serve with development defaults in production. A server that
    # starts with a published secret is worse than one that does not start.
    if settings.is_production:
        problems = settings.validate_for_production()
        if problems:
            raise RuntimeError(
                "Refusing to start in production with an unsafe configuration:\n  - "
                + "\n  - ".join(problems)
            )

    if settings.auto_create_tables:
        Base.metadata.create_all(get_engine())
    yield


app = FastAPI(
    title="Kavach API",
    version="0.1.0",
    description=(
        "Caller identity, spam reputation and AI adapters for the Kavach Android app. "
        "See docs/API_CONTRACT.md."
    ),
    lifespan=lifespan,
)

app.include_router(app_auth.router)
app.include_router(lookup.router)
app.include_router(businesses.router)
app.include_router(reports.router)
app.include_router(telemetry.router)
app.include_router(ai.router)
app.include_router(auth.router)
app.include_router(me.router)
app.include_router(account.router)


@app.get("/health", tags=["meta"])
def health() -> dict:
    """Liveness: the process is up. Used by the container health check."""
    return {"status": "ok", "service": "kavach-api"}


@app.get("/health/ready", tags=["meta"])
def readiness() -> JSONResponse:
    """Readiness: the database is actually reachable.

    Separate from /health on purpose — a container that answers liveness but
    cannot reach Postgres should be taken out of rotation, not reported healthy.
    """
    try:
        with get_engine().connect() as connection:
            connection.execute(text("SELECT 1"))
    except Exception as exc:  # noqa: BLE001 - the reason is the point
        return JSONResponse(
            status_code=503,
            content={"status": "degraded", "database": "unreachable", "detail": exc.__class__.__name__},
        )
    from .ratelimit import backend_status

    return JSONResponse(
        status_code=200,
        content={"status": "ready", "database": "ok", "rate_limit": backend_status()},
    )
