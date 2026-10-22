"""Appeals, and a moderation queue that actually does something.

Before this, `moderation_queue` had a writer and no reader: numbers were flagged
for burst reporting and for crossing a threshold, and every flag went into a table
nobody opened. A user had no way to say a number had been labelled wrongly.

The tests below are mostly about the *effect* of a decision. Changing a status is
not a fix — approving an appeal has to change what the next lookup returns, or the
queue is just a diary.
"""
import time

from app.models import ModerationItem, Number, SpamScore
from app.hashing import phone_hash

BOOTSTRAP = {"Authorization": "Bearer test-token"}


def _install_token(client, install_id="install-abcdefgh") -> str:
    response = client.post(
        "/v1/app/register",
        json={"install_id": install_id, "platform": "android"},
        headers=BOOTSTRAP,
    )
    assert response.status_code == 201, response.text
    return response.json()["token"]


def _app_headers(client) -> dict:
    return {"Authorization": f"Bearer {_install_token(client)}"}


def _scored_number(db, e164="+919876500100", score=70) -> int:
    """A number with a score, as a real one would have after reports."""
    digest = phone_hash(e164)
    number = Number(phone_hash=digest, country="IN", first_seen_at=int(time.time()))
    db.add(number)
    db.flush()
    db.add(
        SpamScore(
            number_id=number.id,
            score=score,
            report_count=9,
            distinct_reporters=6,
            last_report_at=int(time.time()),
            updated_at=int(time.time()),
        )
    )
    db.commit()
    return number.id


# ------------------------------------------------------------------- appeals --

def test_an_install_can_file_an_appeal(client):
    response = client.post(
        "/v1/appeals",
        json={"number": "+919876500100", "statement": "This is my own number."},
        headers=_app_headers(client),
    )
    assert response.status_code == 202, response.text
    body = response.json()
    assert body["accepted"] is True
    assert body["appeal_id"] is not None
    # Honest about what happens next.
    assert "nothing changes immediately" in body["detail"].lower()


def test_an_appeal_needs_a_token(client):
    # The fixture sets a default Authorization header, so an unauthenticated
    # request has to say so explicitly.
    response = client.post(
        "/v1/appeals",
        json={"number": "+919876500100"},
        headers={"Authorization": ""},
    )
    assert response.status_code in (401, 403)


def test_an_appeal_needs_a_real_number(client):
    response = client.post(
        "/v1/appeals",
        json={"number": "abc"},
        headers=_app_headers(client),
    )
    assert response.status_code == 422


def test_a_statement_is_optional(client):
    """Requiring one would stop the people most affected from filing at all."""
    response = client.post(
        "/v1/appeals",
        json={"number": "+919876500101"},
        headers=_app_headers(client),
    )
    assert response.status_code == 202


def test_the_same_install_filing_twice_does_not_duplicate(client, db):
    """One person must not be able to bury the queue."""
    headers = _app_headers(client)
    first = client.post(
        "/v1/appeals",
        json={"number": "+919876500102", "statement": "first"},
        headers=headers,
    ).json()
    second = client.post(
        "/v1/appeals",
        json={"number": "+919876500102", "statement": "second, clearer"},
        headers=headers,
    ).json()

    assert first["appeal_id"] == second["appeal_id"]
    assert "already have an open appeal" in second["detail"].lower()

    item = db.query(ModerationItem).filter(ModerationItem.id == first["appeal_id"]).one()
    assert item.detail == "second, clearer"


def test_the_app_cannot_read_or_decide_the_queue(client):
    """The moderation queue is the operator's, not the app's.

    This is the whole reason app tokens and service tokens are separate things.
    An install token is what every copy of the APK can obtain; if it could read
    the queue, the queue would be public.
    """
    install = {"Authorization": f"Bearer {_install_token(client, 'install-queue-test')}"}

    assert client.get("/v1/admin/moderation", headers=install).status_code == 403
    assert (
        client.post("/v1/admin/moderation/1", json={"decision": "approved"}, headers=install).status_code
        == 403
    )


# ---------------------------------------------------------------- the queue --

def test_the_operator_can_read_the_queue(client):
    _scored_number(db=None, e164="+919876500103") if False else None
    client.post(
        "/v1/appeals",
        json={"number": "+919876500103", "statement": "not spam"},
        headers=_app_headers(client),
    )

    response = client.get("/v1/admin/moderation", headers=BOOTSTRAP)
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["pending_total"] >= 1
    assert any(item["reason"] == "user_appeal" for item in body["items"])


def test_the_queue_shows_a_digest_not_the_number(client):
    """The raw number is not stored, so the queue cannot pretend to show it."""
    client.post(
        "/v1/appeals",
        json={"number": "+919876500104"},
        headers=_app_headers(client),
    )
    body = client.get("/v1/admin/moderation", headers=BOOTSTRAP).json()
    for item in body["items"]:
        if item["number"] is not None:
            assert len(item["number"]) <= 12
            assert "+" not in item["number"]


def test_the_queue_is_oldest_first(client, db):
    """A wrong label left standing for a week is worse than one left for an hour."""
    for offset in (0, 1, 2):
        db.add(
            ModerationItem(
                number_id=None,
                reason="burst_reports",
                status="pending",
                created_at=int(time.time()) - (100 - offset * 10),
            )
        )
    db.commit()

    items = client.get("/v1/admin/moderation", headers=BOOTSTRAP).json()["items"]
    ages = [item["age_seconds"] for item in items]
    assert ages == sorted(ages, reverse=True), "oldest should come first"


# --------------------------------------------------------------- decisions --

def test_approving_an_appeal_clears_the_score(client, db):
    """The part that makes it a workflow rather than a diary."""
    number_id = _scored_number(db, "+919876500105", score=70)

    appeal = client.post(
        "/v1/appeals",
        json={"number": "+919876500105", "statement": "mine"},
        headers=_app_headers(client),
    ).json()

    response = client.post(
        f"/v1/admin/moderation/{appeal['appeal_id']}",
        json={"decision": "approved", "note": "verified as the owner"},
        headers=BOOTSTRAP,
    )
    assert response.status_code == 200, response.text
    assert "cleared" in response.json()["detail"]

    score = db.query(SpamScore).filter(SpamScore.number_id == number_id).one()
    assert score.score == 0
    assert score.report_count == 0
    assert score.distinct_reporters == 0


def test_rejecting_an_appeal_leaves_the_score_alone(client, db):
    number_id = _scored_number(db, "+919876500106", score=70)

    appeal = client.post(
        "/v1/appeals",
        json={"number": "+919876500106"},
        headers=_app_headers(client),
    ).json()

    response = client.post(
        f"/v1/admin/moderation/{appeal['appeal_id']}",
        json={"decision": "rejected", "note": "reports are consistent"},
        headers=BOOTSTRAP,
    )
    assert response.status_code == 200
    assert "keeps its score" in response.json()["detail"]

    score = db.query(SpamScore).filter(SpamScore.number_id == number_id).one()
    assert score.score == 70


def test_a_decision_is_recorded_with_a_note(client, db):
    appeal = client.post(
        "/v1/appeals",
        json={"number": "+919876500107"},
        headers=_app_headers(client),
    ).json()
    client.post(
        f"/v1/admin/moderation/{appeal['appeal_id']}",
        json={"decision": "approved", "note": "checked against the DLT register"},
        headers=BOOTSTRAP,
    )

    item = db.query(ModerationItem).filter(ModerationItem.id == appeal["appeal_id"]).one()
    assert item.status == "approved"
    assert item.resolved_at is not None
    assert item.resolution_note == "checked against the DLT register"


def test_deciding_twice_is_refused(client):
    appeal = client.post(
        "/v1/appeals",
        json={"number": "+919876500108"},
        headers=_app_headers(client),
    ).json()
    first = client.post(
        f"/v1/admin/moderation/{appeal['appeal_id']}",
        json={"decision": "approved"},
        headers=BOOTSTRAP,
    )
    assert first.status_code == 200

    second = client.post(
        f"/v1/admin/moderation/{appeal['appeal_id']}",
        json={"decision": "rejected"},
        headers=BOOTSTRAP,
    )
    assert second.status_code == 409


def test_an_unknown_decision_is_refused(client):
    appeal = client.post(
        "/v1/appeals",
        json={"number": "+919876500109"},
        headers=_app_headers(client),
    ).json()
    response = client.post(
        f"/v1/admin/moderation/{appeal['appeal_id']}",
        json={"decision": "maybe"},
        headers=BOOTSTRAP,
    )
    assert response.status_code == 422


def test_an_unknown_item_is_a_404(client):
    response = client.post(
        "/v1/admin/moderation/999999",
        json={"decision": "approved"},
        headers=BOOTSTRAP,
    )
    assert response.status_code == 404


def test_an_approved_appeal_closes_other_pending_flags(client, db):
    """Once a number is cleared, the other flags on it are moot."""
    number_id = _scored_number(db, "+919876500110", score=55)
    db.add(
        ModerationItem(
            number_id=number_id,
            reason="burst_reports",
            status="pending",
            created_at=int(time.time()) - 50,
        )
    )
    db.commit()

    appeal = client.post(
        "/v1/appeals",
        json={"number": "+919876500110"},
        headers=_app_headers(client),
    ).json()
    client.post(
        f"/v1/admin/moderation/{appeal['appeal_id']}",
        json={"decision": "approved"},
        headers=BOOTSTRAP,
    )

    remaining = (
        db.query(ModerationItem)
        .filter(ModerationItem.number_id == number_id, ModerationItem.status == "pending")
        .count()
    )
    assert remaining == 0
