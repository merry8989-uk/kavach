"""Request/response models. Mirrors docs/API_CONTRACT.md."""
from pydantic import BaseModel, Field


class LookupResponse(BaseModel):
    number: str
    displayName: str | None = None
    numberType: str | None = None
    businessCategory: str | None = None
    source: str = "NONE"                     # CONTACT|COMMUNITY|BUSINESS|PROVIDER|NONE
    confidence: float = 0.0
    verificationStatus: str = "UNVERIFIED"   # VERIFIED|UNVERIFIED|DISPUTED
    spamRisk: int = 0
    reports: int = 0
    distinctReporters: int = 0
    lastUpdated: int = 0
    freshness: str = "UNKNOWN"               # FRESH|STALE|UNKNOWN
    # Added for docs/CALLER-ID-PLAN.md: honest provenance on every result.
    nameSource: str | None = None            # self_declared|business|null
    spamLabel: str = "clean"                 # clean|likely_spam|spam
    carrier: str | None = None
    telecomCircle: str | None = None
    unavailableReason: str | None = None


class BusinessResult(BaseModel):
    name: str
    category: str | None = None
    verified: bool = False
    source: str = "BUSINESS"


class BusinessSearchResponse(BaseModel):
    page: int
    pageSize: int
    total: int
    results: list[BusinessResult]


class ReportRequest(BaseModel):
    number: str = Field(min_length=3, max_length=24)
    category: str = Field(min_length=2, max_length=40)
    note: str | None = Field(default=None, max_length=500)


class ReportResponse(BaseModel):
    accepted: bool
    reports: int
    detail: str | None = None


class CorrectionRequest(BaseModel):
    number: str = Field(min_length=3, max_length=24)
    suggestedName: str = Field(min_length=1, max_length=200)
    evidence: str | None = Field(default=None, max_length=1000)


class CorrectionResponse(BaseModel):
    accepted: bool
    status: str


class ReputationResponse(BaseModel):
    number: str
    score: int
    bands: list[str]
    explain: list[str]
    ruleVersion: str


class PrivacyResponse(BaseModel):
    dataFlows: list[str]
    retention: str
    exportsAvailable: bool
    deletionAvailable: bool


class DeletionResponse(BaseModel):
    deleted: bool
    detail: str


# ---------------------------------------------------------------------------
# Consent-based caller ID (docs/CALLER-ID-PLAN.md).
# ---------------------------------------------------------------------------


class OtpRequestBody(BaseModel):
    phone: str = Field(min_length=4, max_length=24)
    country: str = Field(default="IN", max_length=2)


class OtpRequestResponse(BaseModel):
    request_id: str
    expires_in: int
    # Present ONLY when OTP_DEBUG_RETURN is enabled (local development).
    debug_code: str | None = None


class OtpVerifyBody(BaseModel):
    request_id: str = Field(min_length=4, max_length=64)
    otp: str = Field(min_length=4, max_length=10)


class TokenResponse(BaseModel):
    access_token: str
    token_type: str = "Bearer"
    expires_in: int
    is_new: bool
    user_id: str


class ProfileBody(BaseModel):
    display_name: str | None = Field(default=None, max_length=120)
    publish_name: bool = False
    allow_spam_reports: bool | None = None


class MeResponse(BaseModel):
    user_id: str
    # The server stores only a hash of the number, so it cannot reconstruct a
    # mask. The client holds its own number; this is None by design.
    phone_masked: str | None = None
    display_name: str | None = None
    publish_name: bool = False
    allow_spam_reports: bool = True
    is_verified: bool = False
    trust_score: int = 0
    created_at: int = 0


class ConsentEventOut(BaseModel):
    action: str
    detail: str | None = None
    created_at: int


class CategoryCount(BaseModel):
    category: str
    count: int


class NumberReportsResponse(BaseModel):
    count: int
    distinct_reporters: int
    top_categories: list[CategoryCount]
    score: int
    label: str
    last_report_at: int


class ContributeBody(BaseModel):
    """A crowd-sourced name suggestion. Held for moderation; never auto-published."""

    number: str = Field(min_length=3, max_length=24)
    name: str = Field(min_length=1, max_length=200)
    evidence: str | None = Field(default=None, max_length=500)


# ---------------------------------------------------------------------------
# Telemetry collection (server-side counterpart of the app's logging standard).
# ---------------------------------------------------------------------------


class TelemetryBatch(BaseModel):
    """A batch of schema events from one install."""

    user_code: str = Field(min_length=3, max_length=16)
    events: list[dict] = Field(min_length=1, max_length=200)


class TelemetryAck(BaseModel):
    accepted: int
    rejected: int
    # A few reasons, not all of them — enough to debug a bad build without
    # turning the response into a log dump.
    reasons: list[str] = []



# ---------------------------------------------------------------------------
# Per-install app registration (app/routers/app_auth.py).
# ---------------------------------------------------------------------------


class AppRegisterBody(BaseModel):
    """What an install tells the server about itself.

    `install_id` is generated on the device and stays there. It is not a device
    identifier — it is a random value the app makes once, so the server can tell
    two installs apart without knowing anything about the phone.
    """

    install_id: str = Field(min_length=8, max_length=64)
    platform: str = Field(default="android", max_length=16)
    app_version: str | None = Field(default=None, max_length=32)


class AppRegisterResponse(BaseModel):
    """The install's own token. Shown once, then only its hash is kept."""

    token: str
    install_id: str
    expires_in: int | None = None


# ---------------------------------------------------------------------------
# Appeals and moderation (app/routers/appeals.py).
# ---------------------------------------------------------------------------


class AppealRequest(BaseModel):
    """A user saying a number has been labelled wrongly.

    Deliberately short. The number is what the appeal is about; the statement is
    optional because requiring one would stop the people most affected — someone
    whose own number is marked as spam — from filing at all.
    """

    number: str = Field(min_length=4, max_length=24)
    statement: str | None = Field(default=None, max_length=1000)


class AppealResponse(BaseModel):
    accepted: bool
    appeal_id: int | None = None
    # Honest about what happens next: nothing is corrected instantly.
    detail: str


class ModerationItemView(BaseModel):
    id: int
    number: str | None = None
    reason: str
    detail: str | None = None
    age_seconds: int
    created_at: int


class ModerationQueueResponse(BaseModel):
    items: list[ModerationItemView]
    pending_total: int


class ModerationDecisionBody(BaseModel):
    decision: str = Field(description="approved or rejected")
    note: str | None = Field(default=None, max_length=500)


class ModerationDecisionResponse(BaseModel):
    id: int
    status: str
    detail: str
