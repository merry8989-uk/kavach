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
