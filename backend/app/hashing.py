"""Phone-number hashing (plan section 1, principle 2: data minimisation).

Every phone number is stored as `HMAC_SHA256(pepper, e164)`. The raw number is
never persisted in the consent tables and never written to a log.

The pepper comes from the environment. If it is left at the development default
the app still works, but `is_pepper_configured()` returns False so the API can
refuse to serve in production — see `main.py`.
"""
import hashlib
import hmac

from .config import Settings, get_settings


def phone_hash(e164: str, settings: Settings | None = None) -> str:
    """Stable, non-reversible key for a number. The join key for everything."""
    s = settings or get_settings()
    return hmac.new(
        s.phone_pepper.encode("utf-8"),
        (e164 or "").encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()


def reporter_key(token: str, settings: Settings | None = None) -> str:
    """Non-reversible id for an authenticated actor. Never a raw identity."""
    s = settings or get_settings()
    return hmac.new(
        s.phone_pepper.encode("utf-8"),
        ("actor:" + (token or "")).encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()


def is_pepper_configured(settings: Settings | None = None) -> bool:
    s = settings or get_settings()
    return s.phone_pepper not in {"", "dev-pepper-change-me"}


def mask(e164: str | None) -> str:
    """What we may show or log: last four digits only."""
    digits = "".join(c for c in (e164 or "") if c.isdigit())
    if len(digits) < 4:
        return "******"
    return "******" + digits[-4:]
