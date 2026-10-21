def test_report_is_accepted_and_counted(client):
    response = client.post("/v1/reports", json={"number": "9876543210", "category": "FINANCE_SCAM"})
    assert response.status_code == 202
    assert response.json()["accepted"] is True
    assert response.json()["reports"] == 1


def test_duplicate_report_from_same_actor_is_deduplicated(client):
    first = client.post("/v1/reports", json={"number": "9876543210", "category": "FINANCE_SCAM"})
    second = client.post("/v1/reports", json={"number": "9876543210", "category": "FINANCE_SCAM"})
    assert second.status_code == 202
    assert second.json()["reports"] == first.json()["reports"]
    assert "deduplication" in (second.json()["detail"] or "")


def test_reporting_raises_reputation(client):
    before = client.get("/v1/number-reputation", params={"number": "9876543210"}).json()
    client.post("/v1/reports", json={"number": "9876543210", "category": "FINANCE_SCAM"})
    after = client.get("/v1/number-reputation", params={"number": "9876543210"}).json()
    assert after["score"] >= before["score"]
    assert after["explain"]


def test_reputation_is_explainable_and_versioned(client):
    body = client.get("/v1/number-reputation", params={"number": "9876543210"}).json()
    assert body["ruleVersion"]
    assert isinstance(body["explain"], list) and body["explain"]
    assert isinstance(body["bands"], list)


def test_invalid_number_rejected_on_report(client):
    response = client.post("/v1/reports", json={"number": "x", "category": "SPAM"})
    assert response.status_code == 422


def test_correction_is_held_for_moderation(client):
    response = client.post(
        "/v1/identity-corrections",
        json={"number": "9876543210", "suggestedName": "Rahul S."},
    )
    assert response.status_code == 202
    assert response.json()["status"] == "PENDING"


def test_correction_does_not_auto_apply(client):
    client.post("/v1/identity-corrections",
                json={"number": "9876543210", "suggestedName": "Rahul S."})
    body = client.get("/v1/lookup", params={"number": "9876543210"}).json()
    assert body["displayName"] is None
