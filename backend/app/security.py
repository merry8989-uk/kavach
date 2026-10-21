"""Authentication, actor hashing and rate limiting.

The token scheme is intentionally minimal but real: a bearer token compared in
constant time, and a sliding-window rate limiter. In production the limiter
belongs in Redis so it is shared across instances (noted in README).
"""
import hashlib
import secrets
import time

from fastapi import Depends, Header, HTTPException

from .config import Settings, get_settings

# token -> list of request timestamps
_buckets: dict[str, list[float]] = {}


def reset_rate_limiter() -> None:
    """Test helper."""
    _buckets.clear()


def actor_hash(token: str) -> str:
    """Stable, non-reversible actor id. Raw identities are never stored."""
    return hashlib.sha256(token.encode()).hexdigest()


def require_token(
    authorization: str | None = Header(default=None),
    settings: Settings = Depends(get_settings),
) -> str:
    if not authorization or not authorization.lower().startswith("bearer "):
        raise HTTPException(status_code=401, detail="missing bearer token")
    token = authorization.split(" ", 1)[1].strip()
    if not secrets.compare_digest(token, settings.api_token):
        raise HTTPException(status_code=403, detail="invalid token")
    return token


def rate_limit(
    authorization: str | None = Header(default=None),
    settings: Settings = Depends(get_settings),
) -> None:
    key = actor_hash(authorization or "anonymous")[:16]
    now = time.time()
    window = _buckets.setdefault(key, [])
    window[:] = [t for t in window if now - t < 60]
    if len(window) >= settings.rate_limit_per_minute:
        raise HTTPException(
            status_code=429,
            detail="rate limit exceeded",
            headers={"Retry-After": "60"},
        )
    window.append(now)


# ---------------------------------------------------------------------------
# Consent-based auth (docs/CALLER-ID-PLAN.md, section 4.1).
#
# Lookups and reports keep accepting the static service token so the existing
# client contract is unchanged. The /v1/me endpoints require a *user* token
# issued by /v1/auth/otp/verify, because they act on somebody's own data.
# ---------------------------------------------------------------------------
from fastapi import Depends  # noqa: E402  (kept next to its use)
from sqlalchemy.orm import Session  # noqa: E402

from .db import get_db  # noqa: E402
from .jwtutil import TokenError, verify  # noqa: E402


def bearer_token(authorization: str | None = Header(default=None)) -> str:
    if not authorization or not authorization.lower().startswith("bearer "):
        raise HTTPException(status_code=401, detail="missing bearer token")
    return authorization.split(" ", 1)[1].strip()


def require_user(
    token: str = Depends(bearer_token),
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
):
    """Resolve the caller to a verified user, or 401."""
    from .models import User

    try:
        payload = verify(token, settings)
    except TokenError as exc:
        raise HTTPException(status_code=401, detail=f"invalid token: {exc}") from exc

    user = db.query(User).filter(User.id == payload["sub"]).one_or_none()
    if user is None:
        raise HTTPException(status_code=401, detail="unknown user")
    return user
