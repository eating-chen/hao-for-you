"""Controlled Gmail queries backed by hao-for-you-gateway."""
from __future__ import annotations

import json
from dataclasses import asdict, dataclass
from datetime import date as Date
from typing import Any

import httpx

from app.core.config import config


REAUTH_ERROR_CODES = {"GOOGLE_NOT_LINKED", "TOKEN_REFRESH_FAILED"}
MAX_QUERY_LIMIT = 20


@dataclass(frozen=True)
class GmailToolResult:
    status: str
    message: str
    data: Any = None
    auth_url: str | None = None
    error_code: str | None = None

    def to_json(self) -> str:
        return json.dumps(asdict(self), ensure_ascii=False)


class GoogleMailGateway:
    """HTTP boundary for Gmail query and OAuth gateway APIs."""

    def __init__(
        self,
        base_url: str | None = None,
        timeout: float | None = None,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self.base_url = (base_url or config.GatewayConfig.base_url).rstrip("/")
        self.timeout = timeout or config.GatewayConfig.timeout_seconds
        self.transport = transport

    async def query_messages(
        self,
        *,
        line_user_id: str,
        date: str | None = None,
        date_to: str | None = None,
        keyword: str | None = None,
        sender: str | None = None,
        unread_only: bool = False,
        limit: int = 5,
    ) -> GmailToolResult:
        validation_error = self._validate_query(
            date=date,
            date_to=date_to,
            limit=limit,
        )
        if validation_error is not None:
            return validation_error

        payload = {
            "line_user_id": line_user_id,
            "date": date,
            "date_to": date_to,
            "keyword": keyword,
            "from": sender,
            "unread_only": unread_only,
            "limit": limit,
        }
        payload = {key: value for key, value in payload.items() if value is not None}

        try:
            async with httpx.AsyncClient(
                base_url=self.base_url,
                timeout=self.timeout,
                transport=self.transport,
            ) as client:
                response = await client.post("/google/mail/query", json=payload)
                body = self._json_body(response)

                if response.status_code == 401:
                    error = body.get("error") or {}
                    error_code = error.get("code")
                    if error_code in REAUTH_ERROR_CODES:
                        return await self._authorization_result(
                            client=client,
                            line_user_id=line_user_id,
                            error_code=error_code,
                        )

                if response.is_success and body.get("success") is True:
                    return GmailToolResult(
                        status="success",
                        message="Gmail 郵件查詢成功。",
                        data=body.get("data"),
                    )

                error = body.get("error") or {}
                return GmailToolResult(
                    status="error",
                    message=error.get("message") or "查詢 Gmail 郵件失敗。",
                    error_code=error.get("code") or f"HTTP_{response.status_code}",
                )
        except httpx.RequestError:
            return GmailToolResult(
                status="error",
                message="目前無法連線至 Gmail 服務，請稍後再試。",
                error_code="GATEWAY_UNAVAILABLE",
            )

    @staticmethod
    def _validate_query(
        *,
        date: str | None,
        date_to: str | None,
        limit: int,
    ) -> GmailToolResult | None:
        if not 1 <= limit <= MAX_QUERY_LIMIT:
            return GmailToolResult(
                status="error",
                message=f"查詢數量必須介於 1 到 {MAX_QUERY_LIMIT} 之間。",
                error_code="INVALID_LIMIT",
            )

        if date_to and not date:
            return GmailToolResult(
                status="error",
                message="指定結束日期時，也必須提供開始日期。",
                error_code="MISSING_START_DATE",
            )

        try:
            start_date = Date.fromisoformat(date) if date else None
            end_date = Date.fromisoformat(date_to) if date_to else None
        except ValueError:
            return GmailToolResult(
                status="error",
                message="日期格式錯誤，必須是 YYYY-MM-DD。",
                error_code="INVALID_DATE",
            )

        if start_date and end_date and end_date < start_date:
            return GmailToolResult(
                status="error",
                message="結束日期不可早於開始日期。",
                error_code="INVALID_DATE_RANGE",
            )
        return None

    async def _authorization_result(
        self,
        *,
        client: httpx.AsyncClient,
        line_user_id: str,
        error_code: str,
    ) -> GmailToolResult:
        try:
            response = await client.get(
                "/google/auth",
                params={"line_user_id": line_user_id},
            )
            body = self._json_body(response)
        except httpx.RequestError:
            return GmailToolResult(
                status="error",
                message="無法取得 Google 帳號授權連結，請稍後再試。",
                error_code="GATEWAY_UNAVAILABLE",
            )

        auth_url = body.get("auth_url")
        if response.is_success and auth_url:
            return GmailToolResult(
                status="authorization_required",
                message="請先開啟授權連結並完成 Google 帳號連結，再重新查詢 Gmail。",
                auth_url=auth_url,
                error_code=error_code,
            )
        return GmailToolResult(
            status="error",
            message="無法取得 Google 帳號授權連結，請稍後再試。",
            error_code="AUTH_URL_UNAVAILABLE",
        )

    @staticmethod
    def _json_body(response: httpx.Response) -> dict[str, Any]:
        try:
            body = response.json()
        except ValueError:
            return {}
        return body if isinstance(body, dict) else {}
