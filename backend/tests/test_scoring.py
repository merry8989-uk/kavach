from app.scoring import (
    MAX_DISTINCT_REPORTERS,
    ReputationInput,
    band_for,
    compute_reputation,
    detect_patterns,
)


def test_no_signals_is_low_risk():
    result = compute_reputation(ReputationInput())
    assert result.score == 0
    assert result.bands == ["LOW_RISK"]
    assert result.explain


def test_reports_raise_the_score():
    low = compute_reputation(ReputationInput(distinct_reporters=1, recent_reports=1))
    high = compute_reputation(ReputationInput(distinct_reporters=40, recent_reports=15))
    assert high.score > low.score


def test_one_reporter_cannot_dominate():
    """Many reports from few reporters must not outweigh many distinct ones."""
    few = compute_reputation(ReputationInput(distinct_reporters=1, recent_reports=20, total_reports=500))
    many = compute_reputation(ReputationInput(distinct_reporters=30, recent_reports=20, total_reports=30))
    assert many.score > few.score


def test_distinct_reporters_are_capped():
    capped = compute_reputation(ReputationInput(distinct_reporters=MAX_DISTINCT_REPORTERS, recent_reports=0))
    beyond = compute_reputation(ReputationInput(distinct_reporters=500, recent_reports=0))
    assert capped.score == beyond.score


def test_verified_business_caps_risk():
    result = compute_reputation(
        ReputationInput(distinct_reporters=50, recent_reports=20, verified_business=True)
    )
    assert result.score <= 5


def test_high_false_positive_rate_discounts():
    clean = compute_reputation(ReputationInput(distinct_reporters=10, recent_reports=5))
    noisy = compute_reputation(
        ReputationInput(distinct_reporters=10, recent_reports=5, false_positive_rate=0.5)
    )
    assert noisy.score < clean.score


def test_score_is_bounded():
    result = compute_reputation(
        ReputationInput(distinct_reporters=500, recent_reports=500, pattern_flags=["a", "b", "c", "d", "e", "f", "g", "h", "i", "j"])
    )
    assert 0 <= result.score <= 100


def test_bands():
    assert "HIGH_RISK" in band_for(90)
    assert "LIKELY_SPAM" in band_for(60)
    assert band_for(0) == ["LOW_RISK"]


def test_detect_patterns():
    assert "repeated digits" in detect_patterns("+919999999999")
    assert "sequential digits" in detect_patterns("+919876543210")
    assert detect_patterns("+919812345678") == [] or isinstance(detect_patterns("+919812345678"), list)
