def test_privacy_disclosure_lists_real_data_flows(client):
    body = client.get("/v1/account/privacy").json()
    assert body["dataFlows"]
    assert any("never uploaded" in flow for flow in body["dataFlows"])
    assert body["deletionAvailable"] is True


def test_account_deletion_removes_reports(client):
    client.post("/v1/reports", json={"number": "9876543210", "category": "SPAM"})

    response = client.delete("/v1/account")
    assert response.status_code == 200
    assert response.json()["deleted"] is True
    assert "report" in response.json()["detail"]

    # The reporter's data is gone; the number's aggregate is recalculated.
    assert client.delete("/v1/account").json()["detail"].startswith("removed 0 report")


def test_account_endpoints_require_auth(client):
    assert client.get("/v1/account/privacy", headers={"Authorization": ""}).status_code == 401
    assert client.delete("/v1/account", headers={"Authorization": ""}).status_code == 401


def test_account_deletion_removes_the_actors_own_corrections(client, db):
    """The regression that mattered.

    Deletion looked for corrections via a subquery over the reporter's own
    reports, and it ran *after* those reports had been deleted. The subquery
    therefore always matched nothing, so no correction was ever removed — a
    right-to-erasure request that silently kept the data.

    Corrections are now attributed to the actor who submitted them, and are
    deleted by that, independently of the reports.
    """
    from app.models import Correction

    client.post(
        "/v1/identity-corrections",
        json={"number": "9876500001", "suggestedName": "Not A Scam", "evidence": "x"},
    )
    assert db.query(Correction).count() == 1

    # A report on a *different* number, so the old subquery would not have
    # matched this correction even if the ordering had been right.
    client.post("/v1/reports", json={"number": "9876500002", "category": "SPAM"})

    detail = client.delete("/v1/account").json()["detail"]
    assert "1 correction" in detail, detail
    assert db.query(Correction).count() == 0


def test_deletion_counts_each_kind_separately(client):
    """A report must not be able to hide a correction in the counts."""
    client.post(
        "/v1/identity-corrections",
        json={"number": "9876500003", "suggestedName": "Someone", "evidence": None},
    )
    detail = client.delete("/v1/account").json()["detail"]
    assert "0 report" in detail
    assert "1 correction" in detail


def test_a_correction_records_who_submitted_it(client, db):
    from app.models import Correction

    client.post(
        "/v1/identity-corrections",
        json={"number": "9876500004", "suggestedName": "Name", "evidence": None},
    )
    row = db.query(Correction).one()
    assert row.actor_hash, "a correction was stored with no author"
