"""Application configuration.

All secrets come from the environment (see .env.example). Nothing is hardcoded
beyond safe development defaults.
"""
from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    # Postgres in production; SQLite is used for local dev and tests.
    database_url: str = "sqlite:///./kavach.db"

    # Bearer token for the API. MUST be overridden in production.
    api_token: str = "dev-token"

    # Requests per minute per token for rate-limited endpoints.
    rate_limit_per_minute: int = 60

    # Versioned scoring rules (spec: thresholds must be versioned).
    scoring_rule_version: str = "2026-01"

    # External providers. When unset the AI endpoints return 503 rather than
    # fabricating a result.
    asr_provider_url: str | None = None
    asr_provider_key: str | None = None
    llm_provider_url: str | None = None
    llm_provider_key: str | None = None

    # Create tables on startup. Convenient in dev; run Alembic in production.
    auto_create_tables: bool = True


@lru_cache
def get_settings() -> Settings:
    return Settings()
