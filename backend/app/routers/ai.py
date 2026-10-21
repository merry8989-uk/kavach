"""AI endpoints (ASR / summarisation / dialogue).

These are **adapters**, not implementations. If no provider is configured the
endpoint returns 503 with a clear reason — it never fabricates a transcript or
a summary. The Android client treats any non-200 as "fall back to local".
"""
import httpx
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field

from ..config import Settings, get_settings
from ..security import rate_limit, require_token

router = APIRouter(tags=["ai"])


class TranscribeRequest(BaseModel):
    audioBase64: str = Field(min_length=1)
    language: str = "en-IN"


class TranscribeResponse(BaseModel):
    transcript: str
    confidence: float = 0.0


class SummariseRequest(BaseModel):
    transcript: str = Field(min_length=1)
    number: str


class CallBrief(BaseModel):
    number: str
    transcript: str
    summary: str
    intent: str = "UNKNOWN"
    sentiment: str = "NEUTRAL"
    risk: int = 0
    recordingPath: str | None = None
    timestamp: int = 0


class DialogueRequest(BaseModel):
    transcriptSoFar: str = ""
    callerName: str | None = None


class DialogueResponse(BaseModel):
    say: str
    shouldEscalate: bool = False


def _require(url: str | None, what: str) -> str:
    if not url:
        raise HTTPException(
            status_code=503,
            detail=f"{what} provider not configured on this deployment",
        )
    return url


async def _post(url: str, key: str | None, payload: dict) -> dict:
    headers = {"Content-Type": "application/json"}
    if key:
        headers["Authorization"] = f"Bearer {key}"
    try:
        async with httpx.AsyncClient(timeout=60.0) as client:
            response = await client.post(url, json=payload, headers=headers)
    except httpx.HTTPError as exc:  # provider unreachable
        raise HTTPException(status_code=502, detail=f"provider error: {exc}") from exc
    if response.status_code >= 400:
        raise HTTPException(status_code=502, detail="provider rejected the request")
    return response.json()


@router.post("/v1/transcribe", response_model=TranscribeResponse)
async def transcribe(
    payload: TranscribeRequest,
    _token: str = Depends(require_token),
    _rl: None = Depends(rate_limit),
    settings: Settings = Depends(get_settings),
) -> TranscribeResponse:
    url = _require(settings.asr_provider_url, "ASR")
    data = await _post(
        url,
        settings.asr_provider_key,
        {"audio": payload.audioBase64, "language": payload.language},
    )
    return TranscribeResponse(
        transcript=data.get("transcript", ""),
        confidence=float(data.get("confidence", 0.0)),
    )


@router.post("/v1/summarise", response_model=CallBrief)
async def summarise(
    payload: SummariseRequest,
    _token: str = Depends(require_token),
    _rl: None = Depends(rate_limit),
    settings: Settings = Depends(get_settings),
) -> CallBrief:
    url = _require(settings.llm_provider_url, "LLM")
    data = await _post(
        url,
        settings.llm_provider_key,
        {"task": "summarise_call", "transcript": payload.transcript, "number": payload.number},
    )
    return CallBrief(**data)


@router.post("/v1/dialogue", response_model=DialogueResponse)
async def dialogue(
    payload: DialogueRequest,
    _token: str = Depends(require_token),
    _rl: None = Depends(rate_limit),
    settings: Settings = Depends(get_settings),
) -> DialogueResponse:
    url = _require(settings.llm_provider_url, "LLM")
    data = await _post(
        url,
        settings.llm_provider_key,
        {"task": "dialogue", "transcriptSoFar": payload.transcriptSoFar, "callerName": payload.callerName},
    )
    return DialogueResponse(**data)
