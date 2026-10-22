"""Test fixtures.

Each test gets a fresh file-backed SQLite database (not in-memory) so the
request sessions and the seeding session use separate connections.
"""
import os

os.environ.setdefault("ALLOW_SHARED_TOKEN", "true")

# Must be set before the app package is imported.
os.environ["AUTO_CREATE_TABLES"] = "false"
os.environ["API_TOKEN"] = "test-token"
os.environ["RATE_LIMIT_PER_MINUTE"] = "1000"
# Deterministic pepper so hashes are stable within a test run, and no production
# default is ever exercised.
os.environ["PHONE_PEPPER"] = "test-pepper"
os.environ["JWT_SECRET"] = "test-jwt-secret"
# Echo the OTP back so the auth flow can be tested without an SMS gateway.
# This flag must stay false in production.
os.environ["OTP_DEBUG_RETURN"] = "true"
os.environ["OTP_RESEND_SECONDS"] = "0"

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402
from sqlalchemy import create_engine  # noqa: E402
from sqlalchemy.orm import sessionmaker  # noqa: E402

from app import models  # noqa: F401,E402  (register tables)
from app.db import Base, get_db  # noqa: E402
from app.main import app as fastapi_app  # noqa: E402
from app.security import reset_rate_limiter  # noqa: E402

TOKEN = "test-token"
AUTH = {"Authorization": f"Bearer {TOKEN}"}


@pytest.fixture()
def engine(tmp_path):
    eng = create_engine(
        f"sqlite:///{tmp_path / 'test.db'}",
        connect_args={"check_same_thread": False},
    )
    Base.metadata.create_all(eng)
    return eng


@pytest.fixture()
def session_factory(engine):
    return sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)


@pytest.fixture()
def db(session_factory):
    """A session for seeding data, sharing the same database as the client."""
    session = session_factory()
    yield session
    session.close()


@pytest.fixture()
def client(session_factory):
    def override():
        session = session_factory()
        try:
            yield session
            session.commit()
        except Exception:
            session.rollback()
            raise
        finally:
            session.close()

    fastapi_app.dependency_overrides[get_db] = override
    reset_rate_limiter()
    with TestClient(fastapi_app) as test_client:
        test_client.headers.update(AUTH)
        yield test_client
    fastapi_app.dependency_overrides.clear()
