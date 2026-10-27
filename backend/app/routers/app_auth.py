"""Per-install registration.

The problem this solves: the app shipped one bearer token, identical on every
device. An APK is easy to take apart, so that token was public in practice — and
because it was shared, revoking it would have broken every user at once, which
means in practice it was never revoked.

So the shared token is demoted to a **bootstrap**. It can do one thing: register
an install. That call is rate-limited per source, and what comes back is a token
belonging to that one install, revocable on its own, and stored only as a hash.

This does not make the app un-clonable — nothing shipped in a client is secret.
What it does is bound the damage: one leaked install can be cut off without
touching anyone else, and minting installs in bulk is expensive rather than free.
"""
import hashlib
import secrets
import time

from fastapi import APIRouter, Depends, Header, HTTPException, Request
from sqlalchemy.orm import Session

from ..config import Settings, get_settings
from ..db import get_db
from ..models import AppInstall
from ..schemas import AppRegisterBody, AppRegisterResponse

router = APIRouter(tags=["app"])

# Deliberately tight: a real install registers once.
REGISTRATIONS_PER_HOUR_PER_SOURCE = 5


def _source_hash(request: Request, settings: Settings) -> str:
    """A hashed source, used only to spot bulk registration.

    Hashed rather than stored raw, because an IP is personal data and this is
    only ever used to count.

    **`X-Forwarded-For` is client-supplied and is ignored unless the deployment
    says it is behind a proxy that overwrites it.** Trusting it unconditionally
    meant anyone could send a different value per request and the per-source
    registration limit stopped existing — a rate limit that the limited party
    controls is not a rate limit.
    """
    source = ""
    if settings.trust_forwarded_for:
        forwarded = request.headers.get("x-forwarded-for", "")
        source = forwarded.split(",")[0].strip()
    if not source:
        source = request.client.host if request.client else ""
    return hashlib.sha256(source.encode()).hexdigest()


def _require_bootstrap(
    authorization: str | None = Header(default=None),
    settings: Settings = Depends(get_settings),
) -> None:
    """Only the shared bootstrap token is accepted here — and only here."""
    if not authorization or not authorization.lower().startswith("bearer "):
        raise HTTPException(status_code=401, detail="missing bearer token")
    token = authorization.split(" ", 1)[1].strip()
    if not secrets.compare_digest(token, settings.bootstrap_token):
        raise HTTPException(status_code=403, detail="invalid bootstrap token")


@router.post("/v1/app/register", response_model=AppRegisterResponse, status_code=201)
def register(
    payload: AppRegisterBody,
    request: Request,
    db: Session = Depends(get_db),
    _bootstrap: None = Depends(_require_bootstrap),
) -> AppRegisterResponse:
    """Exchange the bootstrap token for a token belonging to this install."""
    now = int(time.time())
    source = _source_hash(request, get_settings())

    recent = (
        db.query(AppInstall)
        .filter(AppInstall.registration_ip_hash == source)
        .filter(AppInstall.created_at > now - 3600)
        .count()
    )
    if recent >= REGISTRATIONS_PER_HOUR_PER_SOURCE:
        raise HTTPException(
            status_code=429,
            detail="too many registrations from this source; try again later",
            headers={"Retry-After": "3600"},
        )

    token = secrets.token_urlsafe(48)
    digest = hashlib.sha256(token.encode()).hexdigest()

    existing = db.query(AppInstall).filter(AppInstall.install_id == payload.install_id).one_or_none()
    if existing is not None:
        if existing.revoked:
            # A revoked install does not get a new token by asking again.
            raise HTTPException(status_code=403, detail="this install has been revoked")
        existing.token_hash = digest
        existing.last_seen_at = now
        existing.app_version = payload.app_version
    else:
        db.add(
            AppInstall(
                install_id=payload.install_id,
                token_hash=digest,
                platform=payload.platform,
                app_version=payload.app_version,
                created_at=now,
                last_seen_at=now,
                registration_ip_hash=source,
            )
        )

    db.commit()
    return AppRegisterResponse(token=token, install_id=payload.install_id)
