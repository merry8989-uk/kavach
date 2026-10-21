"""Phone verification: OTP request and verify (plan section 4.1).

`POST /v1/auth/otp/request` → send a code.
`POST /v1/auth/otp/verify`  → exchange it for a user token.

Privacy: the number is hashed on arrival and only the hash is stored. The code
is never logged. In development `OTP_DEBUG_RETURN` echoes the code back so a
client can be exercised without an SMS gateway — it must stay off in production,
and the response says so explicitly when it is used.
"""
import uuid

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from ..config import Settings, get_settings
from ..db import get_db
from ..hashing import mask, phone_hash
from ..jwtutil import issue
from ..normalizer import is_valid_e164, to_e164
from ..otp import OtpError, OtpThrottled, get_store
from ..schemas import OtpRequestBody, OtpRequestResponse, OtpVerifyBody, TokenResponse
from ..security import rate_limit
from ..services import consent

router = APIRouter(tags=["auth"])


@router.post("/v1/auth/otp/request", response_model=OtpRequestResponse)
def request_otp(
    payload: OtpRequestBody,
    _rl: None = Depends(rate_limit),
    settings: Settings = Depends(get_settings),
) -> OtpRequestResponse:
    e164 = to_e164(payload.phone)
    if not is_valid_e164(e164):
        raise HTTPException(status_code=422, detail="invalid phone number")

    digest = phone_hash(e164)
    request_id = str(uuid.uuid4())

    try:
        code = get_store().request(request_id, digest, settings)
    except OtpThrottled as exc:
        raise HTTPException(
            status_code=429,
            detail=str(exc),
            headers={"Retry-After": str(exc.retry_after)},
        ) from exc

    # In production this is where the SMS gateway call goes. It is deliberately
    # behind the OTP store so a provider can be swapped without touching routes.
    _deliver(digest, code, settings)

    return OtpRequestResponse(
        request_id=request_id,
        expires_in=settings.otp_ttl_seconds,
        debug_code=code if settings.otp_debug_return else None,
    )


@router.post("/v1/auth/otp/verify", response_model=TokenResponse)
def verify_otp(
    payload: OtpVerifyBody,
    db: Session = Depends(get_db),
    _rl: None = Depends(rate_limit),
    settings: Settings = Depends(get_settings),
) -> TokenResponse:
    try:
        digest = get_store().verify(payload.request_id, payload.otp)
    except OtpError as exc:
        raise HTTPException(status_code=401, detail=str(exc)) from exc

    user, created = consent.get_or_create_user(db, digest)
    consent.record_event(
        db, user.id,
        "account_created" if created else "signed_in",
    )
    db.commit()

    return TokenResponse(
        access_token=issue(user.id, settings=settings),
        expires_in=settings.jwt_ttl_seconds,
        is_new=created,
        user_id=user.id,
    )


def _deliver(phone_digest: str, code: str, settings: Settings) -> None:
    """SMS hand-off point.

    There is no gateway wired up in this repository, so nothing is sent and
    nothing is logged. Wire MSG91 / Twilio / SNS here; keep the code out of logs.
    """
    if settings.otp_debug_return:
        # Local development only, and obvious in the response.
        return
    return


__all__ = ["router", "mask"]
