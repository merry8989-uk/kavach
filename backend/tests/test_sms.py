"""OTP delivery must never claim to have sent a code it did not send.

The endpoint used to return success with no SMS leaving the building, so a user
waited for a code that was never coming while the API looked healthy. These tests
pin the opposite behaviour: fail loudly, and never leak the number.
"""
import httpx
import pytest

from app.config import Settings
from app.sms import SmsError, SmsNotConfigured, deliver_otp, is_configured, missing_settings


def _settings(**overrides) -> Settings:
    base = dict(
        sms_provider="none",
        sms_api_key="",
        sms_sender_id="",
        sms_template_id="",
        sms_account_sid="",
        sms_from="",
    )
    base.update(overrides)
    return Settings(**base)


# ------------------------------------------------------------------ config --

def test_no_provider_is_not_configured():
    assert not is_configured(_settings())
    assert missing_settings(_settings())


def test_a_complete_msg91_configuration_is_configured():
    s = _settings(sms_provider="msg91", sms_api_key="k", sms_sender_id="KAVACH", sms_template_id="t")
    assert is_configured(s)
    assert missing_settings(s) == []


def test_a_partial_msg91_configuration_names_what_is_missing():
    s = _settings(sms_provider="msg91", sms_api_key="k")
    assert not is_configured(s)
    absent = missing_settings(s)
    assert "SMS_SENDER_ID" in absent
    assert "SMS_TEMPLATE_ID" in absent


def test_a_complete_twilio_configuration_is_configured():
    s = _settings(sms_provider="twilio", sms_account_sid="AC1", sms_api_key="tok", sms_from="+1")
    assert is_configured(s)


def test_an_unknown_provider_is_not_configured():
    s = _settings(sms_provider="carrier-pigeon")
    assert not is_configured(s)
    assert any("unknown value" in m for m in missing_settings(s))


# -------------------------------------------------------------- delivery ----

def test_delivery_without_a_provider_refuses():
    with pytest.raises(SmsNotConfigured) as exc:
        deliver_otp("+919876543210", "123456", _settings())
    # It says what to do, rather than failing vaguely.
    assert "SMS_PROVIDER" in str(exc.value)


def test_delivery_does_not_leak_the_number_in_the_error():
    with pytest.raises(SmsNotConfigured) as exc:
        deliver_otp("+919876543210", "123456", _settings(sms_provider="msg91"))
    message = str(exc.value)
    assert "9876543210" not in message
    assert "missing" in message.lower()


def test_a_provider_failure_is_raised_not_swallowed(monkeypatch):
    def boom(*args, **kwargs):
        raise httpx.ConnectError("no route to host")

    monkeypatch.setattr(httpx, "post", boom)

    s = _settings(sms_provider="msg91", sms_api_key="k", sms_sender_id="S", sms_template_id="t")
    with pytest.raises(SmsError) as exc:
        deliver_otp("+919876543210", "123456", s)

    message = str(exc.value)
    assert "9876543210" not in message
    # The masked form is what is safe to show.
    assert "3210" in message


def test_a_successful_send_posts_the_code(monkeypatch):
    captured = {}

    class FakeResponse:
        status_code = 200

    def fake_post(url, **kwargs):
        captured["url"] = url
        captured["json"] = kwargs.get("json")
        return FakeResponse()

    monkeypatch.setattr(httpx, "post", fake_post)

    s = _settings(sms_provider="msg91", sms_api_key="k", sms_sender_id="KAVACH", sms_template_id="T")
    deliver_otp("+919876543210", "482913", s)

    assert "msg91" in captured["url"]
    assert captured["json"]["recipients"][0]["mobiles"] == "919876543210"
    assert captured["json"]["recipients"][0]["var1"] == "482913"


def test_a_provider_rejection_is_raised(monkeypatch):
    class FakeResponse:
        status_code = 401

    monkeypatch.setattr(httpx, "post", lambda url, **kwargs: FakeResponse())

    s = _settings(sms_provider="msg91", sms_api_key="bad", sms_sender_id="S", sms_template_id="T")
    with pytest.raises(SmsError) as exc:
        deliver_otp("+919876543210", "123456", s)
    assert "401" in str(exc.value)


# ------------------------------------------------------------- the route ----

def test_the_otp_endpoint_reports_503_when_it_cannot_send(client):
    """The whole point: a code that was not sent is not a success.

    The test suite runs with OTP_DEBUG_RETURN on so the rest of the flow can be
    exercised without a gateway. This test turns it off, because that is the
    production shape — and there the endpoint must refuse rather than answer
    with a request id for a message that never went out.
    """
    from app.config import Settings, get_settings
    from app.main import app

    production_like = Settings(
        otp_debug_return=False,
        sms_provider="none",
    )
    app.dependency_overrides[get_settings] = lambda: production_like
    try:
        response = client.post("/v1/auth/otp/request", json={"phone": "+919876543210"})
    finally:
        app.dependency_overrides.pop(get_settings, None)

    assert response.status_code == 503
    assert "provider" in response.json()["detail"].lower()
