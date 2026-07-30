"""Unit tests for the cross-service HMAC-SHA256 signing/verification helper."""
import hashlib
import hmac

import pytest

from app.service import internal_auth


SECRET = "shared-test-secret"
WINDOW = 300
BASE_TS = 1_769_000_000  # fixed reference epoch second


# --- build_signing_string -------------------------------------------------


def test_build_signing_string_uses_newline_separated_canonical_format():
    result = internal_auth.build_signing_string("123", "POST", "/v1/chat", "{\"a\":1}")

    assert result == "123\nPOST\n/v1/chat\n{\"a\":1}"


def test_build_signing_string_uppercases_method():
    result = internal_auth.build_signing_string("123", "post", "/v1/chat", "body")

    assert result == "123\nPOST\n/v1/chat\nbody"


def test_build_signing_string_empty_body_for_get_is_trailing_empty_string():
    result = internal_auth.build_signing_string("123", "GET", "/google/auth", "")

    assert result == "123\nGET\n/google/auth\n"


def test_build_signing_string_none_method_and_body_become_empty():
    result = internal_auth.build_signing_string("123", None, "/p", None)

    assert result == "123\n\n/p\n"


# --- compute_signature ----------------------------------------------------


def test_compute_signature_matches_reference_hmac_sha256():
    signing_string = "123\nPOST\n/v1/chat\nbody"

    expected = hmac.new(
        SECRET.encode("utf-8"),
        signing_string.encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()

    assert internal_auth.compute_signature(SECRET, signing_string) == expected


def test_compute_signature_is_lowercase_hex_of_length_64():
    signature = internal_auth.compute_signature(SECRET, "anything")

    assert len(signature) == 64
    assert signature == signature.lower()
    assert all(c in "0123456789abcdef" for c in signature)


def test_compute_signature_differs_with_different_secret():
    signing_string = "123\nPOST\n/p\nbody"

    assert internal_auth.compute_signature("secret-a", signing_string) != (
        internal_auth.compute_signature("secret-b", signing_string)
    )


# --- sign -----------------------------------------------------------------


def test_sign_returns_injected_timestamp_as_string():
    ts, signature = internal_auth.sign(
        SECRET, "POST", "/v1/chat", "body", timestamp=BASE_TS
    )

    assert ts == str(BASE_TS)
    expected = internal_auth.compute_signature(
        SECRET, internal_auth.build_signing_string(str(BASE_TS), "POST", "/v1/chat", "body")
    )
    assert signature == expected


def test_sign_defaults_body_to_empty_string():
    ts, signature = internal_auth.sign(SECRET, "GET", "/google/auth", timestamp=BASE_TS)

    expected = internal_auth.compute_signature(
        SECRET, internal_auth.build_signing_string(str(BASE_TS), "GET", "/google/auth", "")
    )
    assert signature == expected


def test_sign_uses_current_time_when_timestamp_not_provided(monkeypatch):
    monkeypatch.setattr(internal_auth.time, "time", lambda: BASE_TS + 0.9)

    ts, _ = internal_auth.sign(SECRET, "POST", "/v1/chat", "body")

    assert ts == str(BASE_TS)  # int() truncation, not rounding


# --- verify: happy path ---------------------------------------------------


def test_verify_accepts_signature_produced_by_sign_round_trip():
    ts, signature = internal_auth.sign(
        SECRET, "POST", "/v1/chat", "body", timestamp=BASE_TS
    )

    assert internal_auth.verify(
        SECRET, ts, signature, "POST", "/v1/chat", "body", WINDOW, now=BASE_TS
    )


# --- verify: signature mismatch branches ----------------------------------


def test_verify_rejects_tampered_signature():
    ts, signature = internal_auth.sign(
        SECRET, "POST", "/v1/chat", "body", timestamp=BASE_TS
    )
    tampered = "0" * 64

    assert not internal_auth.verify(
        SECRET, ts, tampered, "POST", "/v1/chat", "body", WINDOW, now=BASE_TS
    )
    # sanity: original signature is not already all zeros
    assert signature != tampered


def test_verify_rejects_wrong_secret():
    ts, signature = internal_auth.sign(
        "signer-secret", "POST", "/v1/chat", "body", timestamp=BASE_TS
    )

    assert not internal_auth.verify(
        "verifier-secret", ts, signature, "POST", "/v1/chat", "body", WINDOW, now=BASE_TS
    )


def test_verify_rejects_tampered_body():
    ts, signature = internal_auth.sign(
        SECRET, "POST", "/v1/chat", "original", timestamp=BASE_TS
    )

    assert not internal_auth.verify(
        SECRET, ts, signature, "POST", "/v1/chat", "tampered", WINDOW, now=BASE_TS
    )


def test_verify_rejects_tampered_path():
    ts, signature = internal_auth.sign(
        SECRET, "POST", "/v1/chat", "body", timestamp=BASE_TS
    )

    assert not internal_auth.verify(
        SECRET, ts, signature, "POST", "/v1/other", "body", WINDOW, now=BASE_TS
    )


def test_verify_rejects_tampered_method():
    ts, signature = internal_auth.sign(
        SECRET, "POST", "/v1/chat", "body", timestamp=BASE_TS
    )

    assert not internal_auth.verify(
        SECRET, ts, signature, "GET", "/v1/chat", "body", WINDOW, now=BASE_TS
    )


# --- verify: missing / malformed header branches --------------------------


def test_verify_rejects_missing_timestamp():
    _, signature = internal_auth.sign(
        SECRET, "POST", "/v1/chat", "body", timestamp=BASE_TS
    )

    assert not internal_auth.verify(
        SECRET, None, signature, "POST", "/v1/chat", "body", WINDOW, now=BASE_TS
    )


def test_verify_rejects_empty_timestamp():
    _, signature = internal_auth.sign(
        SECRET, "POST", "/v1/chat", "body", timestamp=BASE_TS
    )

    assert not internal_auth.verify(
        SECRET, "", signature, "POST", "/v1/chat", "body", WINDOW, now=BASE_TS
    )


def test_verify_rejects_missing_signature():
    ts = str(BASE_TS)

    assert not internal_auth.verify(
        SECRET, ts, None, "POST", "/v1/chat", "body", WINDOW, now=BASE_TS
    )


def test_verify_rejects_non_numeric_timestamp():
    _, signature = internal_auth.sign(
        SECRET, "POST", "/v1/chat", "body", timestamp=BASE_TS
    )

    assert not internal_auth.verify(
        SECRET, "not-a-number", signature, "POST", "/v1/chat", "body", WINDOW, now=BASE_TS
    )


# --- verify: time window branches (both directions) -----------------------


def test_verify_rejects_timestamp_too_far_in_the_past():
    old_ts = BASE_TS - (WINDOW + 1)
    _, signature = internal_auth.sign(
        SECRET, "POST", "/v1/chat", "body", timestamp=old_ts
    )

    assert not internal_auth.verify(
        SECRET, str(old_ts), signature, "POST", "/v1/chat", "body", WINDOW, now=BASE_TS
    )


def test_verify_rejects_timestamp_too_far_in_the_future():
    future_ts = BASE_TS + (WINDOW + 1)
    _, signature = internal_auth.sign(
        SECRET, "POST", "/v1/chat", "body", timestamp=future_ts
    )

    assert not internal_auth.verify(
        SECRET, str(future_ts), signature, "POST", "/v1/chat", "body", WINDOW, now=BASE_TS
    )


def test_verify_accepts_timestamp_exactly_at_past_window_boundary():
    boundary_ts = BASE_TS - WINDOW
    _, signature = internal_auth.sign(
        SECRET, "POST", "/v1/chat", "body", timestamp=boundary_ts
    )

    assert internal_auth.verify(
        SECRET, str(boundary_ts), signature, "POST", "/v1/chat", "body", WINDOW, now=BASE_TS
    )


def test_verify_accepts_timestamp_exactly_at_future_window_boundary():
    boundary_ts = BASE_TS + WINDOW
    _, signature = internal_auth.sign(
        SECRET, "POST", "/v1/chat", "body", timestamp=boundary_ts
    )

    assert internal_auth.verify(
        SECRET, str(boundary_ts), signature, "POST", "/v1/chat", "body", WINDOW, now=BASE_TS
    )


def test_verify_tolerates_surrounding_whitespace_in_timestamp_and_signature():
    ts, signature = internal_auth.sign(
        SECRET, "POST", "/v1/chat", "body", timestamp=BASE_TS
    )

    assert internal_auth.verify(
        SECRET, f"  {ts}  ", f" {signature} ", "POST", "/v1/chat", "body", WINDOW, now=BASE_TS
    )


# --- empty/missing secret: fail loud (RuntimeError), never a silent empty-key HMAC


@pytest.mark.parametrize("empty_secret", ["", None])
def test_sign_raises_runtime_error_when_secret_is_empty_or_none(empty_secret):
    with pytest.raises(RuntimeError, match="INTERNAL_API_SECRET is not configured"):
        internal_auth.sign(empty_secret, "POST", "/v1/chat", "body", timestamp=BASE_TS)


@pytest.mark.parametrize("empty_secret", ["", None])
def test_verify_raises_runtime_error_when_secret_is_empty_or_none(empty_secret):
    with pytest.raises(RuntimeError, match="INTERNAL_API_SECRET is not configured"):
        internal_auth.verify(
            empty_secret,
            str(BASE_TS),
            "any-signature",
            "POST",
            "/v1/chat",
            "body",
            WINDOW,
            now=BASE_TS,
        )


def test_verify_raises_runtime_error_before_checking_missing_headers():
    """Empty secret is checked before the missing-header branch (fail loud first)."""
    with pytest.raises(RuntimeError, match="INTERNAL_API_SECRET is not configured"):
        internal_auth.verify(
            "", None, None, "POST", "/v1/chat", "body", WINDOW, now=BASE_TS
        )
