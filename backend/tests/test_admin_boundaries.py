"""The audit's critical findings, pinned as tests.

Three of the audit's five release blockers were one mistake wearing different
hats: **the credential that ships in the APK was also the credential for
administrative operations.** Anyone who extracted it from an install could read
the moderation queue, approve or reject decisions about other people's phone
numbers, and purge every diagnostic record older than a day.

These tests exist so that cannot come back quietly.
"""

from __future__ import annotations

import pytest
from fastapi import HTTPException

from app.config import Settings
from app.security import require_admin_token


ADMIN = "a" * 40
BOOTSTRAP = "b" * 40


def settings(**overrides) -> Settings:
    base = dict(
        environment="production",
        api_token=BOOTSTRAP,
        admin_token=ADMIN,
        phone_pepper="c" * 32,
        jwt_secret="d" * 40,
        database_url="postgresql://u:p@db:5432/kavach",
        auto_create_tables=False,
        otp_debug_return=False,
        allow_shared_token=False,
    )
    base.update(overrides)
    return Settings(**base)


def call(token: str, s: Settings) -> str:
    """Invoke the dependency the way FastAPI would."""
    return require_admin_token(authorization=f"Bearer {token}", settings=s)


# ------------------------------------------------------------ the separation --

def test_the_admin_token_is_accepted():
    assert call(ADMIN, settings()) == ADMIN


def test_the_bootstrap_token_is_refused_for_admin_operations():
    """**The finding.** API_TOKEN ships in the APK; it must not open the queue."""
    with pytest.raises(HTTPException) as caught:
        call(BOOTSTRAP, settings())
    assert caught.value.status_code == 403
    assert "admin token" in caught.value.detail.lower()


def test_a_per_install_token_is_refused():
    """An install is not an operator, however valid its token is."""
    with pytest.raises(HTTPException) as caught:
        call("some-install-token", settings())
    assert caught.value.status_code == 403


def test_a_missing_token_is_a_401_not_a_403():
    # The distinction matters to a client: 401 means "tell me who you are",
    # 403 means "I know who you are and you may not".
    with pytest.raises(HTTPException) as caught:
        require_admin_token(authorization=None, settings=settings())
    assert caught.value.status_code == 401


def test_an_unconfigured_admin_token_disables_the_operation():
    """503, not 500 and not a silent fallback.

    A fallback to `api_token` here would restore the exact hole the audit found,
    and it would do it invisibly.
    """
    with pytest.raises(HTTPException) as caught:
        call(ADMIN, settings(admin_token=""))
    assert caught.value.status_code == 503
    assert "ADMIN_TOKEN" in caught.value.detail


# --------------------------------------------------------- the production guard --

def test_production_refuses_a_shared_admin_token():
    """The guard names the reason rather than saying "unsafe configuration"."""
    problems = settings(admin_token=BOOTSTRAP).unsafe_configuration()
    joined = " ".join(problems).lower()
    assert "admin_token must differ" in joined


def test_production_refuses_a_missing_admin_token():
    problems = settings(admin_token="").unsafe_configuration()
    assert any("ADMIN_TOKEN is not set" in p for p in problems)


def test_production_refuses_a_short_admin_token():
    problems = settings(admin_token="short").unsafe_configuration()
    assert any("ADMIN_TOKEN is shorter" in p for p in problems)


def test_production_refuses_a_short_jwt_secret():
    """The audit found no minimum length at all on this."""
    problems = settings(jwt_secret="too-short").unsafe_configuration()
    assert any("JWT_SECRET is shorter" in p for p in problems)


def test_a_correct_configuration_is_accepted():
    """And it must not refuse a good one, or the check gets worked around."""
    assert settings().unsafe_configuration() == []


# ------------------------------------------------------------ forwarded for --

def test_forwarded_for_is_not_trusted_by_default():
    """A rate limit whose key is supplied by the limited party is not a limit."""
    assert settings(environment="development").trust_forwarded_for is False
