"""The server must refuse to run insecurely.

A server that boots with a secret published in a public repository is worse than
one that does not boot: the failure is silent and the exposure is total. These
tests pin that behaviour down.
"""
import pytest

from app.config import Settings


def _settings(**overrides) -> Settings:
    base = dict(
        environment="production",
        database_url="postgresql+psycopg://kavach:x@db:5432/kavach",
        api_token="a" * 48,
        phone_pepper="b" * 32,
        jwt_secret="c" * 48,
        auto_create_tables=False,
        otp_debug_return=False,
        # Production cannot deliver an OTP without a gateway, so a complete
        # configuration now includes one.
        sms_provider="msg91",
        sms_api_key="k",
        sms_sender_id="KAVACH",
        sms_template_id="t",
    )
    base.update(overrides)
    return Settings(**base)


def test_a_complete_configuration_has_no_problems():
    assert _settings().validate_for_production() == []


def test_development_environment_is_recognised():
    assert not Settings(environment="development").is_production
    assert Settings(environment="production").is_production
    assert Settings(environment="PROD").is_production


@pytest.mark.parametrize(
    "overrides, expected",
    [
        ({"api_token": "dev-token"}, "API_TOKEN"),
        ({"api_token": "short"}, "API_TOKEN"),
        ({"phone_pepper": "dev-pepper-change-me"}, "PHONE_PEPPER"),
        ({"phone_pepper": "tiny"}, "PHONE_PEPPER"),
        ({"jwt_secret": "dev-jwt-secret-change-me"}, "JWT_SECRET"),
        ({"auto_create_tables": True}, "AUTO_CREATE_TABLES"),
        ({"database_url": "sqlite:///./kavach.db"}, "SQLite"),
        ({"otp_debug_return": True}, "OTP_DEBUG_RETURN"),
        ({"sms_provider": "none"}, "SMS provider"),
    ],
)
def test_each_unsafe_value_is_caught(overrides, expected):
    problems = _settings(**overrides).validate_for_production()
    assert problems, f"{overrides} should have been rejected"
    assert any(expected in problem for problem in problems)


def test_every_problem_is_reported_not_just_the_first():
    problems = _settings(
        api_token="dev-token",
        phone_pepper="dev-pepper-change-me",
        jwt_secret="dev-jwt-secret-change-me",
        auto_create_tables=True,
    ).validate_for_production()
    # Four distinct mistakes, four lines telling you about them.
    assert len(problems) >= 4


def test_production_refuses_without_a_way_to_deliver_otp():
    """A login endpoint that can never send a code should not start at all."""
    problems = _settings(sms_provider="none").validate_for_production()
    assert any("SMS provider" in p for p in problems)


def test_the_readiness_probe_reports_the_database(client):
    response = client.get("/health/ready")
    assert response.status_code == 200
    assert response.json()["database"] == "ok"


def test_liveness_stays_simple(client):
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json()["status"] == "ok"
