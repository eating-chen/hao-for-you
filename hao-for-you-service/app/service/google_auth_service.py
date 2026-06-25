"""Google OAuth 2.0 service：token 管理、API client 建立、LINE push"""
import json
import os
import urllib.request
from datetime import datetime, timedelta

from google.auth.transport.requests import Request
from google.oauth2.credentials import Credentials
from google_auth_oauthlib.flow import Flow
from googleapiclient.discovery import build

from app.core.config import config
from app.service.logger_service import logger, error_logger

# google_auth_oauthlib 1.x 預設 autogenerate_code_verifier=True，
# 授權 URL 會帶 code_challenge，token 交換必須帶同一個 code_verifier。
# 用檔案儲存以防 server 重啟後遺失。
_PKCE_STORE_PATH = "pkce_store.json"


def _pkce_save(line_user_id: str, verifier: str) -> None:
    store = _pkce_load_all()
    store[line_user_id] = {
        "verifier": verifier,
        "expires": (datetime.now() + timedelta(minutes=15)).isoformat(),
    }
    with open(_PKCE_STORE_PATH, "w") as f:
        json.dump(store, f)


def _pkce_pop(line_user_id: str) -> str | None:
    store = _pkce_load_all()
    entry = store.pop(line_user_id, None)
    with open(_PKCE_STORE_PATH, "w") as f:
        json.dump(store, f)
    if not entry:
        return None
    if datetime.now() > datetime.fromisoformat(entry["expires"]):
        return None
    return entry["verifier"]


def _pkce_load_all() -> dict:
    if os.path.exists(_PKCE_STORE_PATH):
        try:
            with open(_PKCE_STORE_PATH) as f:
                return json.load(f)
        except (json.JSONDecodeError, OSError):
            pass
    return {}


# ──────────────────────────────────────────────
# 內部工具
# ──────────────────────────────────────────────

def _client_config() -> dict:
    """
    使用 "installed"（桌面應用程式）類型，與 Google Cloud Console 設定一致。
    autogenerate_code_verifier 由呼叫端控制，不在此啟用。
    """
    return {
        "installed": {
            "client_id": config.GoogleConfig.client_id,
            "client_secret": config.GoogleConfig.client_secret,
            "auth_uri": "https://accounts.google.com/o/oauth2/auth",
            "token_uri": "https://oauth2.googleapis.com/token",
            "redirect_uris": [config.GoogleConfig.redirect_uri],
        }
    }


def _create_flow(autogenerate_code_verifier: bool = False) -> Flow:
    return Flow.from_client_config(
        _client_config(),
        scopes=config.GoogleConfig.scopes,
        redirect_uri=config.GoogleConfig.redirect_uri,
        autogenerate_code_verifier=autogenerate_code_verifier,
    )


# ──────────────────────────────────────────────
# OAuth 流程
# ──────────────────────────────────────────────

def build_auth_url(line_user_id: str) -> str:
    """
    產生 Google OAuth 授權 URL，同時把 code_verifier 存到檔案。
    - state 設為 line_user_id，callback 時用來對應回哪個 LINE 使用者
    - prompt='consent' 強制每次都返回 refresh_token
    """
    flow = _create_flow(autogenerate_code_verifier=True)
    auth_url, _ = flow.authorization_url(
        access_type="offline",
        state=line_user_id,
        prompt="consent",
    )
    # flow.code_verifier 由 google_auth_oauthlib 自動生成，儲存以供 callback 使用
    _pkce_save(line_user_id, flow.code_verifier)
    logger.info("Auth URL generated for user: %s", line_user_id)
    return auth_url


def exchange_code_for_tokens(code: str, line_user_id: str) -> dict:
    """
    用 authorization code 換取 access_token + refresh_token。
    回傳 dict: {access_token, refresh_token, expiry}
    """
    verifier = _pkce_pop(line_user_id)
    if verifier is None:
        raise ValueError(
            f"找不到 line_user_id={line_user_id} 的 PKCE verifier，"
            "請重新從 LINE 請求授權連結後再試。"
        )
    flow = _create_flow(autogenerate_code_verifier=False)
    flow.code_verifier = verifier
    flow.fetch_token(code=code)
    creds = flow.credentials
    return {
        "access_token": creds.token,
        "refresh_token": creds.refresh_token,
        "expiry": creds.expiry,
    }


def get_user_email(access_token: str) -> str:
    """用 access_token 取得 Google 帳號 email"""
    try:
        req = urllib.request.Request(
            "https://www.googleapis.com/oauth2/v3/userinfo",
            headers={"Authorization": f"Bearer {access_token}"},
        )
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read())
            return data.get("email", "unknown@gmail.com")
    except Exception as e:
        error_logger.exception("Failed to get user email: %s", e)
        return "unknown@gmail.com"


# ──────────────────────────────────────────────
# Token / Credentials 管理
# ──────────────────────────────────────────────

def build_credentials(
    access_token: str,
    refresh_token: str,
    expiry: datetime | None,
) -> Credentials:
    """從 DB 儲存的 token 重建 Credentials 物件"""
    return Credentials(
        token=access_token,
        refresh_token=refresh_token,
        token_uri="https://oauth2.googleapis.com/token",
        client_id=config.GoogleConfig.client_id,
        client_secret=config.GoogleConfig.client_secret,
        scopes=config.GoogleConfig.scopes,
        expiry=expiry,
    )


def refresh_if_needed(creds: Credentials) -> tuple[Credentials, bool]:
    """
    若 access_token 已過期，用 refresh_token 自動換新的。
    回傳 (creds, was_refreshed)
    was_refreshed=True 代表 access_token 有更新，呼叫方需要更新 DB。
    """
    if creds.expired and creds.refresh_token:
        try:
            creds.refresh(Request())
            logger.info("Google credentials refreshed successfully.")
            return creds, True
        except Exception as e:
            error_logger.exception("Token refresh failed: %s", e)
    return creds, False


# ──────────────────────────────────────────────
# Google API service builders
# ──────────────────────────────────────────────

def build_calendar_service(creds: Credentials):
    """建立 Google Calendar API v3 service"""
    return build("calendar", "v3", credentials=creds)


def build_gmail_service(creds: Credentials):
    """建立 Gmail API v1 service（Phase 3 使用）"""
    return build("gmail", "v1", credentials=creds)


# ──────────────────────────────────────────────
# LINE push message（OAuth callback 後通知用）
# ──────────────────────────────────────────────

def push_line_message(line_user_id: str, text: str) -> None:
    """主動推播 LINE 訊息給指定使用者（不佔用 reply token）"""
    try:
        from linebot.v3.messaging import (
            ApiClient,
            Configuration,
            MessagingApi,
            PushMessageRequest,
            TextMessage,
        )
        configuration = Configuration(
            access_token=config.LineConfig.channel_access_token
        )
        with ApiClient(configuration) as api_client:
            api = MessagingApi(api_client)
            api.push_message(
                PushMessageRequest(
                    to=line_user_id,
                    messages=[TextMessage(text=text)],
                )
            )
        logger.info("LINE push sent to: %s", line_user_id)
    except Exception as e:
        error_logger.exception("LINE push failed: %s", e)
