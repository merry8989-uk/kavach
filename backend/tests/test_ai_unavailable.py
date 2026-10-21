"""The AI endpoints must not fabricate results when no provider is configured."""


def test_transcribe_reports_unavailable(client):
    response = client.post("/v1/transcribe", json={"audioBase64": "AAAA", "language": "en-IN"})
    assert response.status_code == 503
    assert "not configured" in response.json()["detail"]


def test_summarise_reports_unavailable(client):
    response = client.post("/v1/summarise", json={"transcript": "hello", "number": "+919876543210"})
    assert response.status_code == 503


def test_dialogue_reports_unavailable(client):
    response = client.post("/v1/dialogue", json={"transcriptSoFar": "hello"})
    assert response.status_code == 503


def test_ai_requires_auth(client):
    response = client.post(
        "/v1/transcribe",
        json={"audioBase64": "AAAA"},
        headers={"Authorization": ""},
    )
    assert response.status_code == 401
