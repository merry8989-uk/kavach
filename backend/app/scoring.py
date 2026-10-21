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


# ---------------------------------------------------------------------------
# Consent-based spam scoring (docs/CALLER-ID-PLAN.md, section 6).
#
#   raw = category_weighted_reports * recency_factor * reporter_diversity_factor
#         - whitelist_penalty
#
# Still rule-based and explainable — never a trained model, and never a claim
# that a number is fraudulent. It is a *community opinion*, expressed as a number.
# ---------------------------------------------------------------------------

# How much each category of report is worth before other factors.
CATEGORY_WEIGHTS: dict[str, float] = {
    "fraud": 3.0,
    "robocall": 2.0,
    "telemarketing": 1.5,
    "other": 1.0,
}

# A fully-trusted reporter, fresh, fraud: 3.0 * 1.0 * 1.0 * SCALE.
# With SCALE=5 that is 15 points, so ~3 fraud reports cross "likely spam" (40)
# and ~5 cross "spam" (70). Tune here, not in the database.
SIGNAL_SCALE = 5.0

# Only the strongest N signals count, so a large burst cannot inflate a score.
MAX_SIGNALS = 40
# Distinct reporters needed before the diversity factor reaches full strength.
DIVERSITY_TARGET = 5
# Points removed when the number is a verified business or a self-declared name.
WHITELIST_PENALTY = 15.0


@dataclass
class ReportSignal:
    """One report, reduced to what scoring needs."""

    category: str
    weight: int = 100      # reporter weight, 0..100
    age_days: float = 0.0


@dataclass
class ConsentScoreResult:
    score: int
    label: str
    explain: list[str]
    rule_version: str


def label_for(score: int, likely: int = 40, spam: int = 70) -> str:
    if score >= spam:
        return "spam"
    if score >= likely:
        return "likely_spam"
    return "clean"


def compute_consent_spam_score(
    signals: list[ReportSignal],
    distinct_reporters: int,
    whitelisted: bool = False,
    half_life_days: int = 90,
    likely_threshold: int = 40,
    spam_threshold: int = 70,
    rule_version: str = "2026-01",
) -> ConsentScoreResult:
    explain: list[str] = []

    if not signals:
        return ConsentScoreResult(
            score=0,
            label="clean",
            explain=["no reports on record"],
            rule_version=rule_version,
        )

    # Strongest signals first, then cap — a coordinated burst cannot dominate.
    ordered = sorted(signals, key=lambda s: (s.weight, -s.age_days), reverse=True)
    considered = ordered[:MAX_SIGNALS]
    if len(ordered) > MAX_SIGNALS:
        explain.append(f"only the strongest {MAX_SIGNALS} reports counted")

    base = 0.0
    category_counts: dict[str, int] = {}
    for signal in considered:
        category_weight = CATEGORY_WEIGHTS.get(signal.category, CATEGORY_WEIGHTS["other"])
        # New/low-trust accounts count for less, but never for nothing.
        reporter_factor = max(0.1, min(1.0, signal.weight / 100.0))
        recency = 0.5 ** (max(0.0, signal.age_days) / max(1, half_life_days))
        base += category_weight * reporter_factor * recency
        category_counts[signal.category] = category_counts.get(signal.category, 0) + 1

    # Many distinct reporters are worth more than one reporter filing repeatedly.
    diversity = 0.5 + 0.5 * min(1.0, distinct_reporters / DIVERSITY_TARGET)

    raw = base * diversity * SIGNAL_SCALE

    for category, count in sorted(category_counts.items()):
        explain.append(f"{count} report(s): {category}")
    explain.append(f"{distinct_reporters} distinct reporter(s)")

    if whitelisted:
        raw -= WHITELIST_PENALTY
        explain.append(f"reduced by {WHITELIST_PENALTY:.0f}: verified or self-declared number")

    score = int(max(0, min(100, round(raw))))
    return ConsentScoreResult(
        score=score,
        label=label_for(score, likely_threshold, spam_threshold),
        explain=explain,
        rule_version=rule_version,
    )


def reporter_weight_for_trust(trust_score: int) -> int:
    """Map an account's trust into a report weight (0..100).

    Reaching this point already required a verified phone number, so a new
    account starts high (80) rather than near zero — it is a real person, not a
    throwaway. History only nudges it upward. `trust_score` may be negative for
    an account that has had reports overturned, which is what pulls it down.
    """
    return int(max(10, min(100, 80 + (trust_score // 5))))
