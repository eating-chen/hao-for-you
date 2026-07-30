"""Chatbot route unit tests."""
import json

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

import app.api.chatbot_router as chatbot_router
from app.api.chatbot_router import echo
from app.core.config import config
from app.schemas.cahtbot_schema import ChatResponse
from app.service import internal_auth


SECRET = "router-test-secret"
WINDOW = 300


def test_echo():
    response = echo("Hello, World!")
    assert response == {
        "received": "Hello, World!",
        "message": "Echo from hao-for-you server",
    }


@pytest.fixture
def secret(monkeypatch):
    """Pin a known shared secret and window for signature verification."""
    monkeypatch.setattr(config.SecurityConfig, "internal_api_secret", SECRET)
    monkeypatch.setattr(config.SecurityConfig, "internal_api_timestamp_window", WINDOW)
    return SECRET


@pytest.fixture
def client(monkeypatch):
    """Minimal app around the chatbot router; avoids main.py lifespan (DB/agent).

    ``OllamaModelManager.generate_response`` is stubbed so a valid request can
    reach the handler without touching Ollama.
    """
    async def fake_generate_response(request, body):
        return ChatResponse(
            success=True,
            user_id=body.user_id,
            session_id=body.session_id or f"{body.platform}-{body.user_id}",
            platform=body.platform,
            response="stubbed reply",
            tool_executions=[],
        )

    monkeypatch.setattr(
        chatbot_router.OllamaModelManager,
        "generate_response",
        staticmethod(fake_generate_response),
    )

    app = FastAPI()
    app.include_router(chatbot_router.router)
    return TestClient(app)


def _sign_post_chat(body_str: str, *, secret: str = SECRET, timestamp=None):
    ts, signature = internal_auth.sign(
        secret, "POST", "/v1/chat", body_str, timestamp=timestamp
    )
    return {
        "Content-Type": "application/json",
        "X-Timestamp": ts,
        "X-Signature": signature,
    }


# --- open endpoints (no signature required) -------------------------------


def test_health_check_is_open_without_signature(client):
    response = client.get("/")

    assert response.status_code == 200
    assert response.json()["status"] == "ok"


def test_echo_endpoint_is_open_without_signature(client):
    response = client.post("/echo", params={"prompt": "hi"})

    assert response.status_code == 200
    assert response.json() == {
        "received": "hi",
        "message": "Echo from hao-for-you server",
    }


# --- /v1/chat protected endpoint ------------------------------------------


def test_chat_accepts_valid_signature(client, secret):
    body_str = json.dumps({"user_id": "U1", "platform": "line", "message": "hi"})
    headers = _sign_post_chat(body_str)

    response = client.post("/v1/chat", content=body_str, headers=headers)

    assert response.status_code == 200
    payload = response.json()
    assert payload["success"] is True
    assert payload["response"] == "stubbed reply"
    assert payload["user_id"] == "U1"


def test_chat_rejects_missing_signature_headers(client, secret):
    body_str = json.dumps({"user_id": "U1", "platform": "line", "message": "hi"})

    response = client.post(
        "/v1/chat", content=body_str, headers={"Content-Type": "application/json"}
    )

    assert response.status_code == 401
    assert response.json()["detail"]["code"] == "AUTHORIZATION_FAIL"


def test_chat_rejects_wrong_signature(client, secret):
    body_str = json.dumps({"user_id": "U1", "platform": "line", "message": "hi"})
    headers = _sign_post_chat(body_str, secret="wrong-secret")

    response = client.post("/v1/chat", content=body_str, headers=headers)

    assert response.status_code == 401
    assert response.json()["detail"]["code"] == "AUTHORIZATION_FAIL"


def test_chat_rejects_body_tampered_after_signing(client, secret):
    body_str = json.dumps({"user_id": "U1", "platform": "line", "message": "hi"})
    headers = _sign_post_chat(body_str)
    tampered = json.dumps({"user_id": "U1", "platform": "line", "message": "TAMPERED"})

    response = client.post("/v1/chat", content=tampered, headers=headers)

    assert response.status_code == 401
    assert response.json()["detail"]["code"] == "AUTHORIZATION_FAIL"


def test_chat_rejects_expired_timestamp(client, secret):
    import time

    body_str = json.dumps({"user_id": "U1", "platform": "line", "message": "hi"})
    expired_ts = int(time.time()) - (WINDOW + 60)
    headers = _sign_post_chat(body_str, timestamp=expired_ts)

    response = client.post("/v1/chat", content=body_str, headers=headers)

    assert response.status_code == 401
    assert response.json()["detail"]["code"] == "AUTHORIZATION_FAIL"


# --- empty secret: server misconfiguration, must surface as 500 -----------


def test_chat_returns_500_when_internal_api_secret_is_empty(monkeypatch):
    """空 secret 讓 ``internal_auth.verify`` 拋 ``RuntimeError``（伺服器設定錯誤），
    必須往外傳成 500，不可被誤轉成 401 AUTHORIZATION_FAIL，也不可被當成驗證通過（200）。
    帶的簽章值本身是任意的——secret 檢查發生在 header 檢查之前，不管簽章合不合法都會先炸。
    """
    monkeypatch.setattr(config.SecurityConfig, "internal_api_secret", "")
    monkeypatch.setattr(config.SecurityConfig, "internal_api_timestamp_window", WINDOW)

    app = FastAPI()
    app.include_router(chatbot_router.router)
    local_client = TestClient(app, raise_server_exceptions=False)

    body_str = json.dumps({"user_id": "U1", "platform": "line", "message": "hi"})
    response = local_client.post(
        "/v1/chat",
        content=body_str,
        headers={
            "Content-Type": "application/json",
            "X-Timestamp": "1769000000",
            "X-Signature": "arbitrary-signature-value",
        },
    )

    assert response.status_code == 500


def test_dependency_fail_closes_to_401_on_non_utf8_body(client, secret):
    """Review-added fail-closed branch: when the signature dependency runs on a
    non-UTF-8 body it returns 401 AUTHORIZATION_FAIL (never a 500).

    Uses a non-JSON content-type so FastAPI does NOT pre-parse the body as JSON,
    letting the dependency reach its ``(await request.body()).decode('utf-8')``
    fail-closed path. See discrepancy note for the application/json case.
    """
    raw = b"\xff\xfe"
    ts, signature = internal_auth.sign(SECRET, "POST", "/v1/chat", "", timestamp=None)

    response = client.post(
        "/v1/chat",
        content=raw,
        headers={
            "Content-Type": "text/plain",
            "X-Timestamp": ts,
            "X-Signature": signature,
        },
    )

    assert response.status_code == 401
    assert response.json()["detail"]["code"] == "AUTHORIZATION_FAIL"


def test_non_utf8_json_body_is_rejected_without_500(secret):
    """Safety guarantee that DOES hold: a non-UTF-8 body sent as application/json
    is rejected and never produces a 500.

    NOTE (discrepancy vs handoff): the handoff claims this yields 401
    AUTHORIZATION_FAIL, but FastAPI JSON-parses the body BEFORE the signature
    dependency runs, so an application/json non-UTF-8 body is rejected as 422 by
    body validation, not 401 by the dependency. Asserting the real, defensible
    invariant (rejected, never 500) rather than enshrining a specific status.
    """
    app = FastAPI()
    app.include_router(chatbot_router.router)
    local_client = TestClient(app, raise_server_exceptions=False)

    ts, signature = internal_auth.sign(SECRET, "POST", "/v1/chat", "", timestamp=None)
    response = local_client.post(
        "/v1/chat",
        content=b"\xff\xfe",
        headers={
            "Content-Type": "application/json",
            "X-Timestamp": ts,
            "X-Signature": signature,
        },
    )

    assert response.status_code != 500
    assert response.status_code in (401, 422)
