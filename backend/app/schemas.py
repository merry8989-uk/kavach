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
    lastUpdated: int = 0
    freshness: str = "UNKNOWN"               # FRESH|STALE|UNKNOWN
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
