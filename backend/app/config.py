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

    # ---- consent-based caller ID (see docs/CALLER-ID-PLAN.md) ----

    # Pepper for HMAC_SHA256 phone hashing. MUST be overridden in production;
    # when it is left at this default the API reports itself unconfigured.
    phone_pepper: str = "dev-pepper-change-me"

    # JWT signing secret and access-token lifetime.
    jwt_secret: str = "dev-jwt-secret-change-me"
    jwt_ttl_seconds: int = 3600

    # OTP behaviour.
    otp_length: int = 6
    otp_ttl_seconds: int = 300
    otp_resend_seconds: int = 30
    # Development only: echo the OTP in the response so a local client can be
    # tested without an SMS gateway. NEVER enable this in production.
    otp_debug_return: bool = False

    # Spam-score thresholds (plan section 6).
    spam_likely_threshold: int = 40
    spam_threshold: int = 70
    # Reports older than this stop counting toward the score.
    spam_report_half_life_days: int = 90

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
