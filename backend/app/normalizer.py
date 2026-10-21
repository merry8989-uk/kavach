"""Phone-number normalisation.

Deliberately mirrors the Android client's `PhoneUtils` so the server and the
device agree on what a number *is*. Keep the two in sync.
"""
import re

_DIGITS = re.compile(r"\D")


def normalize(raw: str | None) -> str:
    """Digits only, preserving a leading '+'. Canonical lookup key."""
    if not raw:
        return ""
    trimmed = raw.strip()
    plus = trimmed.startswith("+")
    digits = _DIGITS.sub("", trimmed)
    return f"+{digits}" if plus else digits


def to_e164(raw: str | None, default_country_code: str = "91") -> str:
    """Best-effort E.164. Defaults to India, like the client."""
    n = normalize(raw)
    if not n:
        return ""
    if n.startswith("+"):
        return n
    if len(n) == 10:
        return f"+{default_country_code}{n}"
    if len(n) == 11 and n.startswith("0"):
        return f"+{default_country_code}{n[1:]}"
    if n.startswith(default_country_code) and len(n) == 12:
        return f"+{n}"
    return n


def subscriber_key(raw: str | None) -> str:
    """Last 10 digits — what actually identifies a subscriber in India."""
    digits = normalize(raw).lstrip("+")
    return digits[-10:] if len(digits) >= 10 else digits


def is_private(raw: str | None) -> bool:
    return normalize(raw) == ""


def is_valid_e164(e164: str) -> bool:
    """Structural validation only — never a claim about who owns the number."""
    if not e164.startswith("+"):
        return False
    digits = e164[1:]
    return digits.isdigit() and 8 <= len(digits) <= 15
