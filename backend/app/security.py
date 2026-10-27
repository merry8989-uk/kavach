"""Authentication, actor hashing and rate limiting.

The token scheme is intentionally minimal but real: a bearer token compared in
constant time, and a sliding-window rate limiter. In production the limiter
belongs in Redis so it is shared across instances (noted in README).
"""
import hashlib
import secrets
import time

from fastapi import Depends, Header, HTTPException

from sqlalchemy.orm import Session

from .config import Settings, get_settings
from .db import get_db
from .models import AppInstall

def reset_rate_limiter() -> None:
    """Test helper. Clears whichever limiter is live."""
    from .ratelimit import get_limiter, set_limiter

    limiter = get_limiter()
    if hasattr(limiter, "reset"):
        limiter.reset()
    set_limiter(None)


def actor_hash(token: str) -> str:
    """Stable, non-reversible actor id. Raw identities are never stored."""
    return hashlib.sha256(token.encode()).hexdigest()


def require_token(
    authorization: str | None = Header(default=None),
    settings: Settings = Depends(get_settings),
) -> str:
    """Deprecated: the shared token, accepted anywhere.

    Kept only for the development escape hatch. `require_app_token` is what
    endpoints should use, because it identifies *which* install is calling.
    """
    if not authorization or not authorization.lower().startswith("bearer "):
        raise HTTPException(status_code=401, detail="missing bearer token")
    token = authorization.split(" ", 1)[1].strip()
    if not secrets.compare_digest(token, settings.api_token):
        raise HTTPException(status_code=403, detail="invalid token")
    return token


def require_admin_token(
    authorization: str | None = Header(default=None),
    settings: Settings = Depends(get_settings),
) -> str:
    """A server-only credential, for operations that are not a user's to make.

    Separate from both other tokens on purpose:

    - `api_token` is the bootstrap token **and it ships in the APK**. Anyone who
      extracts it must not be able to read the moderation queue or purge records.
    - a per-install token identifies one phone. An install is not an operator.

    Refuses rather than falling back. A fallback here would quietly restore the
    exact hole the audit found.
    """
    if not authorization or not authorization.lower().startswith("bearer "):
        raise HTTPException(status_code=401, detail="missing bearer token")
    token = authorization.split(" ", 1)[1].strip()

    if not settings.admin_token:
        # Misconfiguration, not a bad request. Said plainly so an operator fixes
        # the deployment rather than hunting a client bug.
        raise HTTPException(
            status_code=503,
            detail="this server has no ADMIN_TOKEN configured, so administrative "
                   "operations are disabled",
        )

    if not secrets.compare_digest(token, settings.admin_token):
        raise HTTPException(status_code=403, detail="this operation needs the admin token")
    return token


def require_app_token(
    authorization: str | None = Header(default=None),
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
) -> str:
    """Identify the calling install, or refuse.

    Accepts a **per-install** token (the normal case) and, only when
    `ALLOW_SHARED_TOKEN` is on, the shared bootstrap token as well. That flag
    exists so the pre-registration client contract keeps working in development;
    the production guard refuses to start with it enabled.

    Returns the install id, so an endpoint can tell callers apart and a single
    install can be cut off without touching anyone else.
    """
    if not authorization or not authorization.lower().startswith("bearer "):
        raise HTTPException(status_code=401, detail="missing bearer token")
    token = authorization.split(" ", 1)[1].strip()

    digest = hashlib.sha256(token.encode()).hexdigest()
    install = (
        db.query(AppInstall)
        .filter(AppInstall.token_hash == digest)
        .filter(AppInstall.revoked.is_(False))
        .one_or_none()
    )
    if install is not None:
        install.last_seen_at = int(time.time())
        db.flush()
        return install.install_id

    # Not a known install. The shared token is only honoured when explicitly
    # allowed, and never in production.
    if settings.allow_shared_token and secrets.compare_digest(token, settings.api_token):
        return "shared"

    raise HTTPException(
        status_code=403,
        detail="unknown install token; register at /v1/app/register",
    )


def rate_limit(
    authorization: str | None = Header(default=None),
    settings: Settings = Depends(get_settings),
) -> None:
    """Count one request against the caller's bucket.

    Delegates to `app.ratelimit`, which is Redis-backed when REDIS_URL is set and
    in-process otherwise. The in-process version is per-replica: with N replicas
    the effective limit is N times the configured one, which is why the backend
    in use is reported by `backend_status()` rather than left to assumption.
    """
    from .ratelimit import get_limiter

    key = actor_hash(authorization or "anonymous")[:16]
    decision = get_limiter(settings).check(key, settings.rate_limit_per_minute)
    if not decision.allowed:
        raise HTTPException(
            status_code=429,
            detail="rate limit exceeded",
            headers={
                "Retry-After": str(decision.retry_after),
                "X-RateLimit-Limit": str(settings.rate_limit_per_minute),
                "X-RateLimit-Backend": decision.backend,
            },
        )


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
