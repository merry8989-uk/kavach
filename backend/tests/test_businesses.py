from app.models import Business


def test_search_returns_paginated_results(client, db):
    db.add_all([
        Business(name="Acme Telecom", category="TELECOM", verified=True),
        Business(name="Acme Bank", category="FINANCE", verified=False),
        Business(name="Zenith Motors", category="AUTO", verified=True),
    ])
    db.commit()

    body = client.get("/v1/businesses/search", params={"q": "Acme"}).json()
    assert body["total"] == 2
    assert len(body["results"]) == 2
    assert {r["name"] for r in body["results"]} == {"Acme Telecom", "Acme Bank"}


def test_search_filters_by_category(client, db):
    db.add_all([
        Business(name="Acme Telecom", category="TELECOM"),
        Business(name="Acme Bank", category="FINANCE"),
    ])
    db.commit()

    body = client.get("/v1/businesses/search", params={"q": "Acme", "category": "FINANCE"}).json()
    assert body["total"] == 1
    assert body["results"][0]["name"] == "Acme Bank"


def test_search_pagination(client, db):
    for i in range(25):
        db.add(Business(name=f"Acme {i:02d}"))
    db.commit()

    page1 = client.get("/v1/businesses/search", params={"q": "Acme", "page": 1, "pageSize": 10}).json()
    page3 = client.get("/v1/businesses/search", params={"q": "Acme", "page": 3, "pageSize": 10}).json()
    assert page1["total"] == 25
    assert len(page1["results"]) == 10
    assert len(page3["results"]) == 5
