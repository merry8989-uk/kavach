"""SMS delivery for one-time passwords.

`_deliver` in the auth router used to be a stub: the OTP endpoint returned a
`request_id` and a success response while **no SMS was ever sent**. A user would
wait for a code that was never coming, and the API would look healthy the whole
time.

That is worse than an outage, because an outage is visible. So the rule here is
the opposite: if no gateway is configured, delivery **fails loudly** and the
endpoint returns 503. It never pretends.

Two providers are implemented because India has a specific shape:

  * **MSG91** — the common Indian choice. Needs an auth key, a sender id and a
    DLT-approved template id (TRAI requires the template for transactional SMS).
  * **Twilio** — international, or for testing.

The phone number is used **only** to address the message. It is never logged, and
it is not stored — the caller has already hashed it by the time we are reached.
"""
from __future__ import annotations

import httpx

from .config import Settings

# Deliberately short: an OTP is a synchronous, user-visible action.
TIMEOUT_SECONDS = 10.0


class SmsError(RuntimeError):
    """Delivery failed. The caller turns this into a 503."""


class SmsNotConfigured(SmsError):
    """No gateway is configured, so no code can be delivered."""


def _mask(phone: str) -> str:
    """Never let a full number into a log line or an error message."""
    digits = "".join(ch for ch in phone if ch.isdigit())
    return f"******{digits[-4:]}" if len(digits) >= 4 else "******"


def provider_name(settings: Settings) -> str:
    return (settings.sms_provider or "none").strip().lower()


def is_configured(settings: Settings) -> bool:
    provider = provider_name(settings)
    if provider == "msg91":
        return bool(settings.sms_api_key and settings.sms_sender_id and settings.sms_template_id)
    if provider == "twilio":
        return bool(settings.sms_account_sid and settings.sms_api_key and settings.sms_from)
    return False


def missing_settings(settings: Settings) -> list[str]:
    """Which values are absent, so the error can name them."""
    provider = provider_name(settings)
    missing: list[str] = []
    if provider == "none":
        return ["SMS_PROVIDER (set it to msg91 or twilio)"]
    if provider == "msg91":
        if not settings.sms_api_key:
            missing.append("SMS_API_KEY")
        if not settings.sms_sender_id:
            missing.append("SMS_SENDER_ID")
        if not settings.sms_template_id:
            missing.append("SMS_TEMPLATE_ID")
    elif provider == "twilio":
        if not settings.sms_account_sid:
            missing.append("SMS_ACCOUNT_SID")
        if not settings.sms_api_key:
            missing.append("SMS_API_KEY (the auth token)")
        if not settings.sms_from:
            missing.append("SMS_FROM")
    else:
        missing.append(f"SMS_PROVIDER (unknown value '{provider}')")
    return missing


def deliver_otp(phone_e164: str, code: str, settings: Settings) -> None:
    """Send [code] to [phone_e164]. Raises [SmsError] on any failure.

    The caller must treat a raised error as "the user did not get a code" and
    say so, rather than returning a request id for a message that was never sent.
    """
    provider = provider_name(settings)

    if provider == "none":
        raise SmsNotConfigured(
            "No SMS provider is configured, so no code can be delivered. "
            "Set SMS_PROVIDER to msg91 or twilio, or set OTP_DEBUG_RETURN=true "
            "for local development only.",
        )

    absent = missing_settings(settings)
    if absent:
        raise SmsNotConfigured(
            "SMS provider is set to '" + provider + "' but these are missing: "
            + ", ".join(absent),
        )

    try:
        if provider == "msg91":
            _send_msg91(phone_e164, code, settings)
        elif provider == "twilio":
            _send_twilio(phone_e164, code, settings)
        else:
            raise SmsNotConfigured(f"Unknown SMS provider '{provider}'.")
    except httpx.HTTPError as exc:
        # The number never appears in the message.
        raise SmsError(
            f"{provider} could not be reached while sending to {_mask(phone_e164)} "
            f"({exc.__class__.__name__}).",
        ) from exc


def _send_msg91(phone_e164: str, code: str, settings: Settings) -> None:
    """MSG91 flow API — the DLT-approved template path used in India."""
    # MSG91 wants the number without the leading '+'.
    mobile = phone_e164.lstrip("+")
    response = httpx.post(
        "https://control.msg91.com/api/v5/flow/",
        headers={
            "authkey": settings.sms_api_key,
            "Content-Type": "application/json",
            "Accept": "application/json",
        },
        json={
            "template_id": settings.sms_template_id,
            "sender": settings.sms_sender_id,
            "short_url": "0",
            "recipients": [{"mobiles": mobile, "var1": code}],
        },
        timeout=TIMEOUT_SECONDS,
    )
    if response.status_code >= 400:
        # The body can contain the number, so only the status is surfaced.
        raise SmsError(f"MSG91 rejected the request with {response.status_code}.")


def _send_twilio(phone_e164: str, code: str, settings: Settings) -> None:
    body = f"Your Kavach verification code is {code}. Do not share it with anyone."
    response = httpx.post(
        f"https://api.twilio.com/2010-04-01/Accounts/{settings.sms_account_sid}/Messages.json",
        auth=(settings.sms_account_sid, settings.sms_api_key),
        data={"To": phone_e164, "From": settings.sms_from, "Body": body},
        timeout=TIMEOUT_SECONDS,
    )
    if response.status_code >= 400:
        raise SmsError(f"Twilio rejected the request with {response.status_code}.")
