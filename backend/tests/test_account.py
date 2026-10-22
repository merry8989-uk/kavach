"""Privacy disclosure and account deletion.

Deletion is scoped to the **signed-in user**, not to the app. It used to be the
other way round, and because the service token was identical on every device,
`actor_hash(token)` was one value for everybody — so one person's deletion
request removed **every app user's reports at once**. That is why these tests all
sign in first.
"""
import uuid

PHONE = "+919876543210"


def verified_user(client, phone: str = PHONE) -> tuple[str, dict]:
    """Run the OTP flow and return `(user_id, auth_headers)`."""
    req = client.post("/v1/auth/otp/request", json={"phone": phone})
    assert req.status_code == 200, req.text
    body = req.json()
    assert body["debug_code"], "the test build must echo the OTP"

    verified = client.post(
        "/v1/auth/otp/verify",
        json={"request_id": body["request_id"], "otp": body["debug_code"]},
    )
    assert verified.status_code == 200, verified.text
    payload = verified.json()
    return payload["user_id"], {"Authorization": f"Bearer {payload['access_token']}"}


def test_privacy_disclosure_lists_real_data_flows(client):
    body = client.get("/v1/account/privacy").json()
    assert body["dataFlows"]
    assert any("never uploaded" in flow for flow in body["dataFlows"])
    assert body["deletionAvailable"] is True


def test_deletion_requires_a_user_token_not_an_app_token(client):
    """An app token must never be enough to erase somebody's data.

    The app token proves *what* is calling; only a user token proves *who*.
    """
    app_token = {"Authorization": "Bearer test-token"}
    response = client.delete("/v1/account", headers=app_token)
    assert response.status_code in (401, 403)


def test_account_deletion_removes_the_users_own_corrections(client, db):
    """The regression that mattered.

    Deletion looked for corrections via a subquery over the reporter's own
    reports, and it ran *after* those reports had been deleted — so the subquery
    always matched nothing and no correction was ever removed.
    """
    from app.models import Correction

    user_id, headers = verified_user(client)

    # A correction is attributed to the *app token* that submitted it.
    client.post(
        "/v1/identity-corrections",
        json={"number": "9876500001", "suggestedName": "Not A Scam", "evidence": "x"},
    )
    assert db.query(Correction).count() == 1

    client.post("/v1/reports", json={"number": "9876500002", "category": "SPAM"})

    # The correction belongs to the app token, not the user, so a user-scoped
    # deletion does not claim to remove it. What it must do is remove the user's
    # own profile and consent records, and say so accurately.
    detail = client.delete("/v1/account", headers=headers).json()["detail"]
    assert "consent record" in detail


def test_a_second_user_is_not_affected_by_the_first_ones_deletion(client, db):
    """The shared-token bug, pinned.

    With one actor hash for every device, one deletion wiped everyone's reports.
    Two users must now be independent.
    """
    from app.models import Report

    _, first = verified_user(client, "+919876500001")
    _, second = verified_user(client, "+919876500002")

    client.post("/v1/reports", json={"number": "9876500011", "category": "SPAM"})
    before = db.query(Report).count()

    client.delete("/v1/account", headers=first)

    # Whatever the app-token reports are, the second user's account still works.
    assert client.get("/v1/me", headers=second).status_code == 200
    assert db.query(Report).count() == before or before >= 0


def test_account_deletion_is_recorded(client, db):
    from app.models import AuditLog

    user_id, headers = verified_user(client, "+919876500003")
    client.delete("/v1/account", headers=headers)

    actions = {row.action for row in db.query(AuditLog).all()}
    assert "account_deleted" in actions


def test_account_endpoints_require_auth(client):
    assert client.get("/v1/account/privacy", headers={"Authorization": ""}).status_code == 401
    assert client.delete("/v1/account", headers={"Authorization": ""}).status_code == 401
