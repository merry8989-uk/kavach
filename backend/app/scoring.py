"""Deterministic, explainable spam scoring.

This is a rule-based algorithm, **not** a trained model. It is versioned so
thresholds can change without silently reinterpreting history.

Design rules taken from the spec:
- community allegations must be distinguishable from verified findings;
- repeated reports from one user must not dominate reputation;
- the result must be explainable;
- never promise that all spam can be detected.
"""
from dataclasses import dataclass, field

# Caps that stop a single actor (or a small mob) from dominating the score.
MAX_DISTINCT_REPORTERS = 50
MAX_RECENT_REPORTS = 20

RISK_BANDS = ((80, "HIGH_RISK"), (50, "LIKELY_SPAM"), (20, "SUSPICIOUS"), (0, "LOW_RISK"))


@dataclass
class ReputationInput:
    distinct_reporters: int = 0
    total_reports: int = 0
    recent_reports: int = 0
    false_positive_rate: float = 0.0
    verified_business: bool = False
    pattern_flags: list[str] = field(default_factory=list)


@dataclass
class ReputationResult:
    score: int
    bands: list[str]
    explain: list[str]
    rule_version: str


def band_for(score: int) -> list[str]:
    return [name for threshold, name in RISK_BANDS if score >= threshold]


def compute_reputation(data: ReputationInput, rule_version: str = "2026-01") -> ReputationResult:
    explain: list[str] = []
    score = 0.0

    # 1. Distinct reporters — the strongest signal, and capped.
    capped = min(data.distinct_reporters, MAX_DISTINCT_REPORTERS)
    if capped:
        score += capped * 1.2
        explain.append(f"reported by {data.distinct_reporters} distinct user(s)")
        if data.distinct_reporters > MAX_DISTINCT_REPORTERS:
            explain.append(f"contribution capped at {MAX_DISTINCT_REPORTERS} reporters")

    # 2. Recency — recent reports weigh more, also capped.
    if data.recent_reports:
        score += min(data.recent_reports, MAX_RECENT_REPORTS) * 0.8
        explain.append(f"{data.recent_reports} report(s) in the last 30 days")

    # 3. Historical accuracy — a number reported badly in the past is discounted.
    if data.false_positive_rate > 0.3:
        score *= 0.6
        explain.append("discounted: high historical false-positive rate")

    # 4. Verified business — trust beats allegations.
    if data.verified_business:
        score = min(score, 5.0)
        explain.append("verified business — risk capped at 5")

    # 5. Structural patterns.
    for flag in data.pattern_flags:
        score += 10
        explain.append(f"pattern: {flag}")

    if not explain:
        explain.append("no negative signals on record")

    final = int(max(0, min(100, round(score))))
    return ReputationResult(
        score=final,
        bands=band_for(final),
        explain=explain,
        rule_version=rule_version,
    )


def detect_patterns(number: str) -> list[str]:
    """Cheap structural heuristics. Never a claim about identity."""
    digits = "".join(c for c in number if c.isdigit())
    flags: list[str] = []
    if digits.startswith("1800"):
        flags.append("toll-free prefix")
    if len(set(digits[-10:])) <= 2 and len(digits) >= 10:
        flags.append("repeated digits")
    if "0123456789" in digits or "9876543210" in digits:
        flags.append("sequential digits")
    return flags
