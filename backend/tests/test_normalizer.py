from app.normalizer import is_private, is_valid_e164, normalize, subscriber_key, to_e164


def test_normalize_keeps_leading_plus():
    assert normalize("+91 98765 43210") == "+919876543210"
    assert normalize("98765-43210") == "9876543210"


def test_to_e164_indian_ten_digit():
    assert to_e164("9876543210") == "+919876543210"


def test_to_e164_strips_leading_zero():
    assert to_e164("09876543210") == "+919876543210"


def test_to_e164_keeps_existing_international():
    assert to_e164("+14155552671") == "+14155552671"


def test_subscriber_key_last_ten_digits():
    assert subscriber_key("+91 98765 43210") == "9876543210"


def test_is_private_for_blank_and_none():
    assert is_private("") is True
    assert is_private(None) is True
    assert is_private("9876543210") is False


def test_is_valid_e164_rejects_garbage():
    assert is_valid_e164("+919876543210") is True
    assert is_valid_e164("not-a-number") is False
    assert is_valid_e164("+123") is False
