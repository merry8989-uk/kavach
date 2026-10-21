"""Minimal HS256 JWT, so auth needs no extra dependency.

Deliberately small: sign/verify only, constant-time comparison, `exp` enforced.
If you later need RS256 or JWKS, swap this for PyJWT — the call sites do not
change shape.
"""
import base64
import hashlib
import hmac
import json
import time

from .config import Settings, get_settings


class TokenError(Exception):
    """Raised for any invalid, malformed or expired token."""


def _b64(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def _unb64(text: str) -> bytes:
    padding = "=" * (-len(text) % 4)
    return base64.urlsafe_b64decode(text + padding)


def _sign(message: bytes, secret: str) -> str:
    digest = hmac.new(secret.encode("utf-8"), message, hashlib.sha256).digest()
    return _b64(digest)


def issue(
    subject: str,
    claims: dict | None = None,
    ttl_seconds: int | None = None,
    settings: Settings | None = None,
) -> str:
    """Create a signed access token for `subject` (a user id)."""
    s = settings or get_settings()
    now = int(time.time())
    payload = {
        "sub": subject,
        "iat": now,
        "exp": now + (ttl_seconds if ttl_seconds is not None else s.jwt_ttl_seconds),
        **(claims or {}),
    }
    header = _b64(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
    body = _b64(json.dumps(payload, separators=(",", ":")).encode())
    message = f"{header}.{body}".encode("ascii")
    return f"{header}.{body}.{_sign(message, s.jwt_secret)}"


def verify(token: str, settings: Settings | None = None) -> dict:
    """Return the payload, or raise TokenError."""
    s = settings or get_settings()
    parts = (token or "").split(".")
    if len(parts) != 3:
        raise TokenError("malformed token")
    header, body, signature = parts
    expected = _sign(f"{header}.{body}".encode("ascii"), s.jwt_secret)
    if not hmac.compare_digest(signature, expected):
        raise TokenError("bad signature")
    try:
        payload = json.loads(_unb64(body))
    except Exception as exc:  # noqa: BLE001 - any decode failure is a bad token
        raise TokenError("bad payload") from exc
    if int(payload.get("exp", 0)) < int(time.time()):
        raise TokenError("expired")
    if not payload.get("sub"):
        raise TokenError("missing subject")
    return payload
