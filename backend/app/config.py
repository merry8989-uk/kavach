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

    # Development escape hatch: accept the shared token on every endpoint, which
    # is what the pre-registration contract did. Never on in production — the
    # production guard refuses it.
    allow_shared_token: bool = False

    # Requests per minute per token for rate-limited endpoints.
    rate_limit_per_minute: int = 60

    # ---- shipping this server's own logs to a monitoring sink ----
    #
    # **Off by default**, and that default is the point: a server that starts
    # posting to an endpoint somebody typed into a config file without meaning to
    # is a server that leaks, and the quiet default is the right one.
    #
    # This ships the *server's* logs — latency, errors, provider failures. It is
    # not a path for the app's diagnostics, which stay on the device. The sink
    # refuses anything carrying a `user_code`, so that is enforced there too.
    log_ship_enabled: bool = False
    log_ship_url: str = ""
    log_ship_token: str = ""
    # Batches are bounded so a burst cannot turn into one enormous request.
    log_ship_batch_size: int = 50
    log_ship_flush_seconds: float = 5.0

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

    # ---- shared rate limiting (app/ratelimit.py) ----
    # Empty means the in-process limiter, which is per-replica. Set it in
    # production when you run more than one instance.
    redis_url: str = ""

    # ---- SMS delivery for OTP (app/sms.py) ----
    # "none" means the OTP endpoint refuses rather than pretending to send.
    sms_provider: str = "none"
    sms_api_key: str = ""
    sms_sender_id: str = ""
    sms_template_id: str = ""
    sms_account_sid: str = ""
    sms_from: str = ""

    @property
    def bootstrap_token(self) -> str:
        """The shared token, used only to register an install."""
        return self.api_token

    @property
    def is_production(self) -> bool:
        return self.environment.strip().lower() in {"production", "prod"}

    def unsafe_configuration(self) -> list[str]:
        """Reasons this server must not be started in production **at all**.

        Shipping with a documented default secret is worse than not shipping: the
        values below are in the public repository, so anyone could mint a token or
        reverse a phone hash. These are the only reasons that stop the process.

        Kept separate from [unconfigured_features] because the two are different
        kinds of problem and were treated as one for a long time — see the note
        there.
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

        if self.allow_shared_token:
            problems.append(
                "ALLOW_SHARED_TOKEN must be false in production — the shared token is in the "
                "APK, so accepting it everywhere makes it public",
            )

        return problems

    def unconfigured_features(self) -> list[str]:
        """Features that will not work, on a server that is safe to run anyway.

        **This is the correction of a real mistake.** A missing SMS provider used
        to be in the list above, so a server with no gateway refused to start —
        which meant that caller lookup, spam reputation and business claims, none
        of which touch SMS, were all taken down by an optional provider nobody had
        configured yet.

        That is the wrong trade. An unconfigured provider is a feature that does
        not work; the endpoints for it answer 503, which is what they already do
        for the AI providers. Taking the whole process down is not safer, it is
        just less useful — and a deployment that cannot be completed is a
        deployment that gets abandoned, which is its own kind of unsafe.

        These are logged loudly at boot so the gap is visible rather than
        discovered later by a user who cannot sign in.
        """
        problems: list[str] = []

        from .sms import is_configured, missing_settings

        if not is_configured(self):
            absent = ", ".join(missing_settings(self))
            problems.append(
                "No working SMS provider, so OTP sign-in cannot deliver a code "
                f"(missing: {absent}) — the sign-in endpoints will answer 503",
            )

        return problems

    def validate_for_production(self) -> list[str]:
        """Everything wrong with this configuration, unsafe first.

        Kept as the single entry point so a caller that just wants "is this
        deployable" does not have to know about the split.
        """
        return self.unsafe_configuration() + self.unconfigured_features()

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
