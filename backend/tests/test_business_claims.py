"""Business claims, and the field that could never become true.

`Business.verified` has been in the schema since the first migration and **nothing
ever set it**. The directory could show a listing as a business; no listing could
ever become a *verified* one, because there was no way to claim it and no approval
that had any effect.

So these tests are mostly about the effect. A claim that is approved has to change
what a lookup returns — otherwise the queue records that a business was believed
and leaves the app showing it as unverified, which is the same lie in a new place.
"""
import time

from app.hashing import phone_hash
from app.models import Business, ModerationItem, NameEntry, Number

BOOTSTRAP = {"Authorization": "Bearer test-token"}
ADMIN = {"Authorization": "Bearer test-admin-token"}


def _install(client, install_id="install-claim-1") -> str:
    r = client.post(
        "/v1/app/register",
        json={"install_id": install_id, "platform": "android"},
        headers=BOOTSTRAP,
    )
    assert r.status_code == 201, r.text
    return r.json()["token"]


def _headers(client, install_id="install-claim-1") -> dict:
    return {"Authorization": f"Bearer {_install(client, install_id)}"}


CLAIM = {
    "number": "+919876540001",
    "business_name": "Sunrise Diagnostics",
    "category": "Healthcare",
    "evidence": "Registered at sunrise-diagnostics.example, listed in the state "
                "clinical establishments register under this number.",
}


# ------------------------------------------------------------------ filing --

def test_a_business_can_claim_a_listing(client):
    r = client.post("/v1/businesses/claim", json=CLAIM, headers=_headers(client))
    assert r.status_code == 202, r.text
    body = r.json()
    assert body["accepted"] is True
    assert body["claim_id"] is not None
    # Honest about what happens next.
    assert "nothing changes until then" in body["detail"].lower()


def test_a_claim_needs_a_token(client):
    r = client.post("/v1/businesses/claim", json=CLAIM, headers={"Authorization": ""})
    assert r.status_code in (401, 403)


def test_a_claim_needs_evidence(client):
    """A claim with nothing to check can only be decided on confidence."""
    r = client.post(
        "/v1/businesses/claim",
        json={**CLAIM, "evidence": "short"},
        headers=_headers(client),
    )
    assert r.status_code == 422


def test_a_claim_needs_a_real_number(client):
    r = client.post(
        "/v1/businesses/claim",
        json={**CLAIM, "number": "not-a-number"},
        headers=_headers(client),
    )
    assert r.status_code == 422


def test_claiming_twice_does_not_duplicate(client, db):
    headers = _headers(client)
    first = client.post("/v1/businesses/claim", json=CLAIM, headers=headers).json()
    second = client.post(
        "/v1/businesses/claim",
        json={**CLAIM, "business_name": "Sunrise Diagnostics Pvt Ltd"},
        headers=headers,
    ).json()

    assert first["claim_id"] == second["claim_id"]
    assert "already have an open claim" in second["detail"].lower()

    item = db.query(ModerationItem).filter(ModerationItem.id == first["claim_id"]).one()
    assert item.claim_name == "Sunrise Diagnostics Pvt Ltd"


def test_the_queue_shows_what_is_being_claimed(client):
    client.post("/v1/businesses/claim", json=CLAIM, headers=_headers(client))

    items = client.get("/v1/admin/moderation", headers=ADMIN).json()["items"]
    claim = next(i for i in items if i["reason"] == "business_claim")
    # A moderator has to see the name they are about to publish, not a note
    # about it.
    assert claim["claim_name"] == "Sunrise Diagnostics"
    assert claim["claim_category"] == "Healthcare"


def test_the_claim_reaches_the_moderation_queue_not_the_directory(client, db):
    """Claiming must not publish anything on its own."""
    client.post("/v1/businesses/claim", json=CLAIM, headers=_headers(client))

    number = db.query(Number).filter(Number.phone_hash == phone_hash("+919876540001")).one()
    published = (
        db.query(NameEntry)
        .filter(NameEntry.number_id == number.id, NameEntry.source == "business")
        .count()
    )
    assert published == 0, "an unapproved claim must not be publishable"
    assert db.query(Business).filter(Business.verified.is_(True)).count() == 0


# ---------------------------------------------------------------- approval --

def _file_and_approve(client, db) -> int:
    claim = client.post("/v1/businesses/claim", json=CLAIM, headers=_headers(client)).json()
    r = client.post(
        f"/v1/admin/moderation/{claim['claim_id']}",
        json={"decision": "approved", "note": "checked the register entry"},
        headers=ADMIN,
    )
    assert r.status_code == 200, r.text
    return claim["claim_id"]


def test_approving_publishes_the_claimed_name(client, db):
    """The whole point: the approval has to change what the app shows."""
    _file_and_approve(client, db)

    number = db.query(Number).filter(Number.phone_hash == phone_hash("+919876540001")).one()
    entry = (
        db.query(NameEntry)
        .filter(NameEntry.number_id == number.id, NameEntry.is_active.is_(True))
        .one()
    )
    assert entry.name == "Sunrise Diagnostics"
    # `business` provenance is what makes it publishable and puts it above a
    # community suggestion.
    assert entry.source == "business"
    assert entry.confidence >= 90


def test_approving_marks_the_directory_listing_verified(client, db):
    """`Business.verified` finally has something that sets it."""
    _file_and_approve(client, db)

    row = db.query(Business).filter(Business.name.ilike("Sunrise Diagnostics")).one()
    assert row.verified is True
    assert row.category == "Healthcare"


def test_the_verified_listing_is_returned_by_the_directory(client, db):
    _file_and_approve(client, db)

    results = client.get(
        "/v1/businesses/search", params={"q": "Sunrise"}, headers=_headers(client, "install-search")
    ).json()["results"]
    match = next(r for r in results if r["name"] == "Sunrise Diagnostics")
    assert match["verified"] is True


def test_rejecting_leaves_the_listing_unverified(client, db):
    claim = client.post("/v1/businesses/claim", json=CLAIM, headers=_headers(client)).json()
    r = client.post(
        f"/v1/admin/moderation/{claim['claim_id']}",
        json={"decision": "rejected", "note": "the register entry names a different number"},
        headers=ADMIN,
    )
    assert r.status_code == 200

    number = db.query(Number).filter(Number.phone_hash == phone_hash("+919876540001")).one()
    published = (
        db.query(NameEntry)
        .filter(NameEntry.number_id == number.id, NameEntry.source == "business")
        .count()
    )
    assert published == 0
    assert db.query(Business).filter(Business.verified.is_(True)).count() == 0


def test_a_claim_with_no_name_publishes_nothing(client, db):
    """Refusing is the difference between a no-op and a wrong record."""
    claim = client.post("/v1/businesses/claim", json=CLAIM, headers=_headers(client)).json()
    item = db.query(ModerationItem).filter(ModerationItem.id == claim["claim_id"]).one()
    item.claim_name = None
    db.commit()

    client.post(
        f"/v1/admin/moderation/{claim['claim_id']}",
        json={"decision": "approved"},
        headers=ADMIN,
    )

    assert db.query(Business).filter(Business.verified.is_(True)).count() == 0


def test_a_business_name_outranks_a_community_suggestion(client, db):
    """Provenance decides, not recency."""
    number = Number(phone_hash=phone_hash("+919876540002"), first_seen_at=int(time.time()))
    db.add(number)
    db.flush()
    db.add(
        NameEntry(
            number_id=number.id,
            name="Sunny Diagnostics",
            source="community",
            confidence=40,
            is_active=True,
            created_at=int(time.time()),
        )
    )
    db.commit()

    claim = client.post(
        "/v1/businesses/claim",
        json={**CLAIM, "number": "+919876540002"},
        headers=_headers(client, "install-rank"),
    ).json()
    client.post(
        f"/v1/admin/moderation/{claim['claim_id']}",
        json={"decision": "approved"},
        headers=ADMIN,
    )

    from app.services import names

    best = names.best_publishable_name(db, number.id)
    assert best is not None
    assert best[0] == "Sunrise Diagnostics"
    assert best[1] == "business"
