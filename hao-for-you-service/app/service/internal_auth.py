"""Shared-secret HMAC-SHA256 signing/verification for internal service calls.

signingString 的組法必須與 hao-for-you-gateway 端完全一致::

    timestamp + "\\n" + METHOD + "\\n" + path + "\\n" + body

其中 METHOD 一律大寫、path 不含 query string、GET 無 body 時 body 為空字串。
只用 Python 內建 ``hmac`` / ``hashlib``，不新增依賴。
"""

from __future__ import annotations

import hashlib
import hmac
import time


def build_signing_string(timestamp: str, method: str, path: str, body: str) -> str:
    """Assemble the canonical string that both ends sign / verify."""
    return f"{timestamp}\n{(method or '').upper()}\n{path}\n{body or ''}"


def _require_secret(secret: str | None) -> str:
    """Fail loud when the shared secret is missing.

    對齊 Gateway 行為：Java 端 ``Mac.init`` 拒絕零長度金鑰會拋例外。空 secret 屬
    伺服器設定錯誤（5xx），不可用空金鑰默默簽/驗，也不可被轉成 401。
    """
    if not secret:
        raise RuntimeError("INTERNAL_API_SECRET is not configured")
    return secret


def compute_signature(secret: str, signing_string: str) -> str:
    """hex(HMAC-SHA256(secret, signing_string))."""
    return hmac.new(
        (secret or "").encode("utf-8"),
        signing_string.encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()


def sign(
    secret: str,
    method: str,
    path: str,
    body: str = "",
    timestamp: int | None = None,
) -> tuple[str, str]:
    """Produce ``(X-Timestamp, X-Signature)`` header values for an outgoing request.

    ``timestamp`` 可注入（unix epoch 秒）以利測試；未提供時取當下時間。
    secret 為空/None 時拋 ``RuntimeError``（伺服器設定錯誤）。
    """
    _require_secret(secret)
    ts = str(int(timestamp if timestamp is not None else time.time()))
    signature = compute_signature(secret, build_signing_string(ts, method, path, body))
    return ts, signature


def verify(
    secret: str,
    timestamp: str | None,
    signature: str | None,
    method: str,
    path: str,
    body: str,
    window_seconds: int,
    now: int | None = None,
) -> bool:
    """Verify an inbound signature.

    缺標頭、時間戳無法解析、超出時間窗、或簽章不符都回傳 False。
    簽章比對採常數時間（``hmac.compare_digest``）。``now`` 可注入以利測試。

    secret 為空/None 時拋 ``RuntimeError``（伺服器設定錯誤，非驗簽失敗）——刻意在
    header 檢查之前先拋，讓它往外傳成 5xx 而非被當成 401。
    """
    _require_secret(secret)
    if not timestamp or not signature:
        return False
    try:
        ts = int(str(timestamp).strip())
    except (TypeError, ValueError):
        return False
    current = int(now if now is not None else time.time())
    if abs(current - ts) > window_seconds:
        return False
    expected = compute_signature(
        secret, build_signing_string(str(ts), method, path, body)
    )
    return hmac.compare_digest(expected, str(signature).strip())
