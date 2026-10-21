"""Database engine, session factory and FastAPI dependency."""
from collections.abc import Iterator

from sqlalchemy import create_engine
from sqlalchemy.orm import DeclarativeBase, Session, sessionmaker

from .config import get_settings


class Base(DeclarativeBase):
    """Declarative base for all ORM models."""


_engine = None
_session_factory: sessionmaker | None = None


def configure(database_url: str):
    """(Re)build the engine and session factory. Used by tests."""
    global _engine, _session_factory
    kwargs = {}
    if database_url.startswith("sqlite"):
        kwargs["connect_args"] = {"check_same_thread": False}
    _engine = create_engine(database_url, pool_pre_ping=True, **kwargs)
    _session_factory = sessionmaker(bind=_engine, autoflush=False, expire_on_commit=False)
    return _engine


def get_engine():
    if _engine is None:
        configure(get_settings().database_url)
    return _engine


def get_session_factory() -> sessionmaker:
    if _session_factory is None:
        configure(get_settings().database_url)
    return _session_factory


def get_db() -> Iterator[Session]:
    """FastAPI dependency: one session per request, committed on success."""
    session = get_session_factory()()
    try:
        yield session
        session.commit()
    except Exception:
        session.rollback()
        raise
    finally:
        session.close()
