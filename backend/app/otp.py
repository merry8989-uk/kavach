"""OTP storage for phone verification (plan section 4.1).

The plan puts this in Redis. This module defines the interface and ships an
in-process implementation so the backend runs and tests without Redis. Swap
`get_store()` for a Redis-backed store in production — nothing else changes.

OTP codes are **never logged** and are stored only until they expire.
"""
import secrets
import time
from dataclasses import dataclass, field

from .config import Settings, get_settings

MAX_ATTEMPTS = 5


@dataclass
class _Entry:
    phone_hash: str
    code: str
    expires_at: float
    attempts: int = 0


@dataclass
class OtpStore:
    """In-process OTP store. Not shared across workers — use Redis in production."""

    _by_request: dict[str, _Entry] = field(default_factory=dict)
    _last_request_at: dict[str, float] = field(default_factory=dict)

    def request(self, request_id: str, phone_hash: str, settings: Settings | None = None) -> str:
        s = settings or get_settings()
        # Per-number resend throttle so the OTP endpoint cannot be used to spam.
        now = time.time()
        last = self._last_request_at.get(phone_hash, 0.0)
        if now - last < s.otp_resend_seconds:
            raise OtpThrottled(int(s.otp_resend_seconds - (now - last)) + 1)
        self._last_request_at[phone_hash] = now

        code = "".join(secrets.choice("0123456789") for _ in range(s.otp_length))
        self._by_request[request_id] = _Entry(
            phone_hash=phone_hash,
            code=code,
            expires_at=now + s.otp_ttl_seconds,
        )
        self._purge(now)
        return code

    def verify(self, request_id: str, code: str) -> str:
        """Return the phone_hash on success. Raises OtpError otherwise."""
        entry = self._by_request.get(request_id)
        if entry is None:
            raise OtpError("unknown or expired request")
        if time.time() > entry.expires_at:
            self._by_request.pop(request_id, None)
            raise OtpError("expired code")
        if entry.attempts >= MAX_ATTEMPTS:
            self._by_request.pop(request_id, None)
            raise OtpError("too many attempts")
        entry.attempts += 1
        if not secrets.compare_digest(entry.code, (code or "").strip()):
            raise OtpError("incorrect code")
        # Single use: consume it.
        self._by_request.pop(request_id, None)
        return entry.phone_hash

    def peek(self, request_id: str) -> str | None:
        """Test helper. Never expose this through the API."""
        entry = self._by_request.get(request_id)
        return entry.code if entry else None

    def reset(self) -> None:
        self._by_request.clear()
        self._last_request_at.clear()

    def _purge(self, now: float) -> None:
        for key in [k for k, v in self._by_request.items() if v.expires_at < now]:
            self._by_request.pop(key, None)


class OtpError(Exception):
    pass


class OtpThrottled(Exception):
    def __init__(self, retry_after: int) -> None:
        super().__init__("please wait before requesting another code")
        self.retry_after = retry_after


_store: OtpStore | None = None


def get_store() -> OtpStore:
    global _store
    if _store is None:
        _store = OtpStore()
    return _store
