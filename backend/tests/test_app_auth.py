"""Per-install tokens.

The app shipped one shared bearer token. An APK is trivially decompilable, so
that token was public in practice — and because it was shared, revoking it would
have broken every user at once, which means it was never revoked.

These tests pin the replacement: the shared token only registers, what comes back
belongs to one install, and cutting off one install leaves the others working.
"""
import uuid

BOOTSTRAP = {"Authorization": "Bearer test-token"}


def register(client, install_id: str | None = None) -> str:
    response = client.post(
        "/v1/app/register",
        json={
            "install_id": install_id or str(uuid.uuid4()),
            "platform": "android",
            "app_version": "0.28.0 (1)",
        },
        headers=BOOTSTRAP,
    )
    assert response.status_code == 201, response.text
    return response.json()["token"]


# ------------------------------------------------------------- registration --

def test_the_bootstrap_token_registers_an_install(client):
    token = register(client)
    assert token and len(token) > 30


def test_registration_needs_the_bootstrap_token(client):
    response = client.post(
        "/v1/app/register",
        json={"install_id": str(uuid.uuid4()), "platform": "android"},
        headers={"Authorization": "Bearer not-the-bootstrap"},
    )
    assert response.status_code == 403


def test_registration_needs_some_token_at_all(client):
    response = client.post(
        "/v1/app/register",
        json={"install_id": str(uuid.uuid4()), "platform": "android"},
        headers={"Authorization": ""},
    )
    assert response.status_code == 401


def test_an_install_id_must_be_reasonable(client):
    response = client.post(
        "/v1/app/register",
        json={"install_id": "x", "platform": "android"},
        headers=BOOTSTRAP,
    )
    assert response.status_code == 422


def test_reregistering_the_same_install_issues_a_fresh_token(client):
    install = str(uuid.uuid4())
    first = register(client, install)
    second = register(client, install)
    assert first != second
    # The new one works, and the old one has been superseded.
    assert client.get("/v1/lookup", params={"number": "9876543210"}, headers={"Authorization": f"Bearer {second}"}).status_code == 200
    assert client.get("/v1/lookup", params={"number": "9876543210"}, headers={"Authorization": f"Bearer {first}"}).status_code == 403


# ------------------------------------------------------------- using tokens --

def test_an_install_token_can_look_up(client):
    token = register(client)
    response = client.get("/v1/lookup", params={"number": "9876543210"}, headers={"Authorization": f"Bearer {token}"})
    assert response.status_code == 200


def test_an_unknown_token_is_refused(client):
    response = client.get("/v1/lookup", params={"number": "9876543210"}, headers={"Authorization": "Bearer invented"})
    assert response.status_code == 403


def test_the_shared_token_is_not_enough_for_a_lookup_when_it_is_disabled(client):
    """The production shape: the token in the APK cannot call the API."""
    from app.config import Settings, get_settings
    from app.main import app

    strict = Settings(api_token="test-token", allow_shared_token=False, phone_pepper="test-pepper",
                      jwt_secret="test-jwt-secret-value-long-enough")
    app.dependency_overrides[get_settings] = lambda: strict
    try:
        response = client.get("/v1/lookup", params={"number": "9876543210"}, headers=BOOTSTRAP)
    finally:
        app.dependency_overrides.pop(get_settings, None)

    assert response.status_code == 403
    assert "register" in response.json()["detail"]


def test_two_installs_are_told_apart(client):
    """The point of the exercise: cutting off one install must not touch another."""
    from app.models import AppInstall

    first = register(client)
    second = register(client)
    assert first != second

    # Both work.
    for token in (first, second):
        assert client.get("/v1/lookup", params={"number": "9876543210"}, headers={"Authorization": f"Bearer {token}"}).status_code == 200


def test_a_revoked_install_is_cut_off_and_others_are_not(client, db):
    from app.models import AppInstall

    first = register(client)
    second = register(client)

    # Revoke only the first.
    digest = __import__("hashlib").sha256(first.encode()).hexdigest()
    row = db.query(AppInstall).filter(AppInstall.token_hash == digest).one()
    row.revoked = True
    db.commit()

    assert client.get("/v1/lookup", params={"number": "9876543210"}, headers={"Authorization": f"Bearer {first}"}).status_code == 403
    assert client.get("/v1/lookup", params={"number": "9876543210"}, headers={"Authorization": f"Bearer {second}"}).status_code == 200


def test_a_revoked_install_cannot_register_again(client, db):
    from app.models import AppInstall

    install = str(uuid.uuid4())
    token = register(client, install)
    digest = __import__("hashlib").sha256(token.encode()).hexdigest()
    row = db.query(AppInstall).filter(AppInstall.token_hash == digest).one()
    row.revoked = True
    db.commit()

    response = client.post(
        "/v1/app/register",
        json={"install_id": install, "platform": "android"},
        headers=BOOTSTRAP,
    )
    assert response.status_code == 403


def test_only_the_hash_is_stored(client, db):
    """A database leak must not hand out working tokens."""
    from app.models import AppInstall

    token = register(client)
    row = db.query(AppInstall).one()
    assert row.token_hash != token
    assert len(row.token_hash) == 64


def test_bulk_registration_is_rate_limited(client):
    """Minting installs must cost something."""
    from app.routers.app_auth import REGISTRATIONS_PER_HOUR_PER_SOURCE

    for _ in range(REGISTRATIONS_PER_HOUR_PER_SOURCE):
        register(client)

    response = client.post(
        "/v1/app/register",
        json={"install_id": str(uuid.uuid4()), "platform": "android"},
        headers=BOOTSTRAP,
    )
    assert response.status_code == 429
