"""The three consent-based layers (docs/CALLER-ID-PLAN.md).

Grouped here because the layers are only meaningful together:

1. crowd-sourced names — `name_entries`
2. consent-based profile — OTP + `profiles` + `consent_events`
3. spam flagging — `spam_reports` + `spam_scores`

The single most important assertion in this file is
`test_a_community_name_is_never_published`: if that ever fails, the product has
become the thing it was designed not to be.
"""
import time

import pytest

from app.hashing import phone_hash
from app.services import names as names_service

ALICE = "+919876543210"
BOB = "+919812345678"


# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------


def verified_user(client, phone: str) -> tuple[str, dict]:
    """Run the OTP flow and return `(user_id, auth_headers)`."""
    req = client.post("/v1/auth/otp/request", json={"phone": phone})
    assert req.status_code == 200, req.text
    body = req.json()
    assert body["debug_code"], "test build must echo the OTP"

    verified = client.post(
        "/v1/auth/otp/verify",
        json={"request_id": body["request_id"], "otp": body["debug_code"]},
    )
    assert verified.status_code == 200, verified.text
    token = verified.json()["access_token"]
    return verified.json()["user_id"], {"Authorization": f"Bearer {token}"}


def lookup(client, number: str) -> dict:
    response = client.get("/v1/lookup", params={"number": number})
    assert response.status_code == 200, response.text
    return response.json()


# ---------------------------------------------------------------------------
# 1. consent-based profile (OTP)
# ---------------------------------------------------------------------------


def test_otp_flow_issues_a_user_token(client):
    user_id, headers = verified_user(client, ALICE)
    assert user_id

    me = client.get("/v1/me", headers=headers)
    assert me.status_code == 200
    assert me.json()["is_verified"] is True
    # Consent is OFF until the user says otherwise.
    assert me.json()["publish_name"] is False


def test_a_wrong_otp_is_rejected(client):
    req = client.post("/v1/auth/otp/request", json={"phone": ALICE}).json()
    bad = client.post(
        "/v1/auth/otp/verify",
        json={"request_id": req["request_id"], "otp": "000000" if req["debug_code"] != "000000" else "111111"},
    )
    assert bad.status_code == 401


def test_an_otp_cannot_be_reused(client):
    req = client.post("/v1/auth/otp/request", json={"phone": ALICE}).json()
    payload = {"request_id": req["request_id"], "otp": req["debug_code"]}

    assert client.post("/v1/auth/otp/verify", json=payload).status_code == 200
    assert client.post("/v1/auth/otp/verify", json=payload).status_code == 401


def test_the_number_is_never_stored_in_the_clear(client, session_factory):
    _, headers = verified_user(client, ALICE)
    # Publishing a name is what puts the number into the consent store.
    client.put("/v1/me/profile", headers=headers, json={
        "display_name": "Alice", "publish_name": True,
    })
    from app.models import Number, User

    # Fresh session, so we read what the request committed.
    session = session_factory()
    try:
        rows = session.query(Number).all()
        assert rows, "the number should have been recorded"
        # The consent store keeps hashes only — the raw number appears nowhere.
        assert all(ALICE not in (row.phone_hash or "") for row in rows)
        assert session.query(Number).filter(Number.phone_hash == phone_hash(ALICE)).count() == 1
        # The user row is keyed by the same hash, and likewise holds no raw number.
        users = session.query(User).all()
        assert users and all(ALICE not in (u.phone_hash or "") for u in users)
    finally:
        session.close()


# ---------------------------------------------------------------------------
# 2. publishing a name is opt-in, and opt-out is honoured
# ---------------------------------------------------------------------------


def test_a_name_is_not_published_without_consent(client):
    _, headers = verified_user(client, ALICE)

    # Set a name but keep publishing OFF.
    client.put("/v1/me/profile", headers=headers, json={
        "display_name": "Alice", "publish_name": False,
    })

    assert lookup(client, ALICE)["displayName"] is None


def test_a_name_is_published_once_consent_is_given(client):
    _, headers = verified_user(client, ALICE)

    client.put("/v1/me/profile", headers=headers, json={
        "display_name": "Alice Kumar", "publish_name": True,
    })

    result = lookup(client, ALICE)
    assert result["displayName"] == "Alice Kumar"
    assert result["nameSource"] == "self_declared"
    assert result["verificationStatus"] == "VERIFIED"


def test_opting_out_immediately_removes_the_name(client):
    _, headers = verified_user(client, ALICE)
    client.put("/v1/me/profile", headers=headers, json={
        "display_name": "Alice Kumar", "publish_name": True,
    })
    assert lookup(client, ALICE)["displayName"] == "Alice Kumar"

    client.put("/v1/me/profile", headers=headers, json={
        "display_name": "Alice Kumar", "publish_name": False,
    })

    # Opt-out is absolute: no name, and the reason says so.
    result = lookup(client, ALICE)
    assert result["displayName"] is None
    assert result["unavailableReason"] == "NO_RELIABLE_SOURCE"


def test_publishing_without_a_name_is_refused(client):
    _, headers = verified_user(client, ALICE)
    response = client.put("/v1/me/profile", headers=headers, json={
        "display_name": "", "publish_name": True,
    })
    assert response.status_code == 422


def test_consent_decisions_are_audited(client):
    _, headers = verified_user(client, ALICE)
    client.put("/v1/me/profile", headers=headers, json={
        "display_name": "Alice", "publish_name": True,
    })
    client.put("/v1/me/profile", headers=headers, json={
        "display_name": "Alice", "publish_name": False,
    })

    history = client.get("/v1/me/consent", headers=headers).json()
    actions = [event["action"] for event in history]
    assert "publish_name_on" in actions
    assert "publish_name_off" in actions


def test_deleting_the_account_withdraws_the_name(client):
    _, headers = verified_user(client, ALICE)
    client.put("/v1/me/profile", headers=headers, json={
        "display_name": "Alice", "publish_name": True,
    })

    assert client.delete("/v1/me", headers=headers).status_code == 200
    assert lookup(client, ALICE)["displayName"] is None
    assert client.get("/v1/me", headers=headers).status_code == 401


# ---------------------------------------------------------------------------
# 3. crowd-sourced names are held, never published
# ---------------------------------------------------------------------------


def test_a_community_name_is_never_published(client):
    """The rule the whole product rests on."""
    _, headers = verified_user(client, BOB)

    response = client.post("/v1/me/contributions", headers=headers, json={
        "number": ALICE, "name": "Definitely Alice",
    })
    assert response.status_code == 202
    assert response.json()["status"] == "PENDING_REVIEW"

    result = lookup(client, ALICE)
    assert result["displayName"] is None, "a community suggestion must never be a name"


def test_an_unverified_business_claim_is_not_publishable(client, db):
    names_service.add_business_name(db, ALICE, "Some Shop", verified=False)
    db.commit()
    assert lookup(client, ALICE)["displayName"] is None


def test_a_verified_business_name_is_published(client, db):
    names_service.add_business_name(db, ALICE, "Acme Bank", verified=True)
    db.commit()

    result = lookup(client, ALICE)
    assert result["displayName"] == "Acme Bank"
    assert result["nameSource"] == "business"


# ---------------------------------------------------------------------------
# 4. spam flagging
# ---------------------------------------------------------------------------


def _report(client, headers, number, category="fraud"):
    return client.post("/v1/me/reports", headers=headers, json={
        "number": number, "category": category,
    })


def test_a_single_report_does_not_make_a_number_spam(client):
    _, headers = verified_user(client, "+919800000001")
    assert _report(client, headers, ALICE).status_code == 202

    result = lookup(client, ALICE)
    assert result["spamLabel"] == "clean"
    assert result["reports"] == 1


def test_enough_distinct_reporters_make_a_number_spam(client):
    for index in range(6):
        _, headers = verified_user(client, f"+91980000000{index}")
        _report(client, headers, ALICE)

    result = lookup(client, ALICE)
    assert result["reports"] == 6
    assert result["distinctReporters"] == 6
    assert result["spamLabel"] == "spam", result
    assert result["spamRisk"] >= 70


def test_one_reporter_cannot_pad_the_score(client):
    """Six categories from one account must not equal six distinct reporters."""
    _, headers = verified_user(client, "+919800000009")
    for category in ("fraud", "robocall", "telemarketing", "other"):
        _report(client, headers, ALICE, category=category)

    result = lookup(client, ALICE)
    assert result["reports"] == 4
    assert result["distinctReporters"] == 1
    assert result["spamLabel"] != "spam", "one reporter must not reach 'spam'"


def test_the_same_report_twice_is_not_counted_twice(client):
    _, headers = verified_user(client, "+919800000010")
    first = _report(client, headers, ALICE)
    second = _report(client, headers, ALICE)

    assert first.json()["accepted"] is True
    assert second.json()["accepted"] is False
    assert lookup(client, ALICE)["reports"] == 1


def test_an_invalid_category_is_refused(client):
    _, headers = verified_user(client, "+919800000011")
    assert _report(client, headers, ALICE, category="not-a-category").status_code == 422


def test_a_self_declared_number_gets_the_whitelist_reduction(client):
    """A verified owner is trusted over rumours about them."""
    _, owner_headers = verified_user(client, ALICE)
    client.put("/v1/me/profile", headers=owner_headers, json={
        "display_name": "Alice", "publish_name": True,
    })

    for index in range(6):
        _, headers = verified_user(client, f"+91980000010{index}")
        _report(client, headers, ALICE)

    # Same reports, but the score is discounted for a self-declared number.
    assert lookup(client, ALICE)["spamRisk"] < 70


def test_report_summary_never_reveals_reporters(client):
    _, headers = verified_user(client, "+919800000020")
    _report(client, headers, ALICE)

    summary = client.get(f"/v1/numbers/{ALICE}/reports").json()
    assert summary["count"] == 1
    assert summary["top_categories"][0]["category"] == "fraud"
    assert "reporter" not in str(summary["top_categories"]).lower()


def test_an_unknown_number_reports_nothing(client):
    summary = client.get("/v1/numbers/+919999999999/reports").json()
    assert summary["count"] == 0
    assert summary["label"] == "clean"
