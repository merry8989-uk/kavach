"""Application configuration.

All secrets come from the environment (see .env.example). Nothing is hardcoded
beyond safe development defaults.
"""
from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    # "development" or "production". In production the app refuses to start on
    # any development default — see validate_for_production().
    environment: str = "development"

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

    @property
    def is_production(self) -> bool:
        return self.environment.strip().lower() in {"production", "prod"}

    def validate_for_production(self) -> list[str]:
        """Return the reasons this configuration must not be deployed.

        Shipping with a documented default secret is worse than not shipping:
        the values below are in the public repository, so anyone could mint a
        token or reverse a phone hash. An empty list means it is safe to run.
        """
        problems: list[str] = []

        if self.api_token in {"", "dev-token", "replace-me-with-a-long-random-value"}:
            problems.append("API_TOKEN is empty or still the development default")
        elif len(self.api_token) < 24:
            problems.append("API_TOKEN is shorter than 24 characters")

        if self.phone_pepper in {"", "dev-pepper-change-me"}:
            problems.append("PHONE_PEPPER is still the development default — phone hashes would be reversible")
        elif len(self.phone_pepper) < 16:
            problems.append("PHONE_PEPPER is shorter than 16 characters")

        if self.jwt_secret in {"", "dev-jwt-secret-change-me"}:
            problems.append("JWT_SECRET is still the development default — anyone could mint user tokens")

        if self.auto_create_tables:
            problems.append("AUTO_CREATE_TABLES must be false in production; run Alembic instead")

        if self.database_url.startswith("sqlite"):
            problems.append("DATABASE_URL points at SQLite; use Postgres in production")

        if self.otp_debug_return:
            problems.append("OTP_DEBUG_RETURN must be false in production — it hands out login codes")

        return problems

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
