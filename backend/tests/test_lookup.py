from app.services.identity import merge_into

AUTH = {"Authorization": "Bearer test-token"}


def test_unknown_number_returns_explicit_no_result(client):
    response = client.get("/v1/lookup", params={"number": "+919812345678"})
    assert response.status_code == 200
    body = response.json()
    # We never invent a name for an unknown number.
    assert body["displayName"] is None
    assert body["source"] == "NONE"
    assert body["unavailableReason"] == "NO_RELIABLE_SOURCE"


def test_known_number_returns_identity(client, db):
    merge_into(db, "+919876543210", display_name="Rahul Sharma", source="COMMUNITY")
    db.commit()

    response = client.get("/v1/lookup", params={"number": "9876543210"})
    assert response.status_code == 200
    body = response.json()
    assert body["displayName"] == "Rahul Sharma"
    assert body["source"] == "COMMUNITY"
    assert body["verificationStatus"] == "UNVERIFIED"   # never verified without a process
    assert body["freshness"] == "FRESH"


def test_higher_trust_source_wins(client, db):
    merge_into(db, "+919876543210", display_name="Community Name", source="COMMUNITY")
    merge_into(db, "+919876543210", display_name="Acme Ltd", source="BUSINESS")
    db.commit()

    body = client.get("/v1/lookup", params={"number": "+919876543210"}).json()
    assert body["displayName"] == "Acme Ltd"
    assert body["source"] == "BUSINESS"


def test_lower_trust_source_does_not_overwrite(client, db):
    merge_into(db, "+919876543210", display_name="Acme Ltd", source="BUSINESS")
    merge_into(db, "+919876543210", display_name="Random Tag", source="COMMUNITY")
    db.commit()

    body = client.get("/v1/lookup", params={"number": "+919876543210"}).json()
    assert body["displayName"] == "Acme Ltd"


def test_invalid_number_is_rejected(client):
    response = client.get("/v1/lookup", params={"number": "abc"})
    assert response.status_code == 422


def test_missing_token_is_unauthorized(client):
    response = client.get("/v1/lookup", params={"number": "+919812345678"},
                          headers={"Authorization": ""})
    assert response.status_code == 401


def test_wrong_token_is_forbidden(client):
    response = client.get("/v1/lookup", params={"number": "+919812345678"},
                          headers={"Authorization": "Bearer nope"})
    assert response.status_code == 403
