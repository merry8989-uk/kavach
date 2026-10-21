"""Kavach backend API.

Run locally:   uvicorn app.main:app --reload
Tests:         pytest
Schema:        alembic upgrade head   (production; dev can auto-create)
"""
from contextlib import asynccontextmanager

from fastapi import FastAPI

from . import models  # noqa: F401  (register models with Base.metadata)
from .config import get_settings
from .db import Base, get_engine
from .routers import account, ai, auth, businesses, lookup, me, reports, telemetry


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
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
    return {"status": "ok", "service": "kavach-api"}
