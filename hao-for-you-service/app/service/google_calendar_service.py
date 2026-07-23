"""Controlled Google Calendar operations backed by hao-for-you-gateway."""
from __future__ import annotations

import json
from dataclasses import asdict, dataclass
from datetime import date as Date
from datetime import datetime, time as Time
from typing import Any, Callable
from zoneinfo import ZoneInfo

import httpx

from app.core.config import config


REAUTH_ERROR_CODES = {"GOOGLE_NOT_LINKED", "TOKEN_REFRESH_FAILED"}
TAIPEI_TIMEZONE = ZoneInfo("Asia/Taipei")


@dataclass(frozen=True)
class CalendarToolResult:
    status: str
    message: str
    data: Any = None
    auth_url: str | None = None
    error_code: str | None = None
    resolved_date: str | None = None

    def to_json(self) -> str:
        return json.dumps(asdict(self), ensure_ascii=False)


class GoogleCalendarGateway:
    """HTTP boundary for Google Calendar and OAuth gateway APIs."""

    def __init__(
        self,
        base_url: str | None = None,
        timeout: float | None = None,
        transport: httpx.AsyncBaseTransport | None = None,
        now_provider: Callable[[], datetime] | None = None,
    ) -> None:
        self.base_url = (base_url or config.GatewayConfig.base_url).rstrip("/")
        self.timeout = timeout or config.GatewayConfig.timeout_seconds
        self.transport = transport
        self.now_provider = now_provider or (
            lambda: datetime.now(tz=TAIPEI_TIMEZONE)
        )

    async def create_event(
        self,
        *,
        line_user_id: str,
        title: str,
        date: str,
        description: str | None = None,
        start_time: str | None = None,
        end_time: str | None = None,
        all_day: bool = False,
    ) -> CalendarToolResult:
        validation_error = self._validate_event(
            title=title,
            date=date,
            start_time=start_time,
            end_time=end_time,
            all_day=all_day,
        )
        if validation_error is not None:
            return validation_error

        payload = {
            "line_user_id": line_user_id,
            "title": title,
            "date": date,
            "description": description,
            "start_time": start_time,
            "end_time": end_time,
            "all_day": all_day,
        }
        payload = {key: value for key, value in payload.items() if value is not None}

        try:
            async with httpx.AsyncClient(
                base_url=self.base_url,
                timeout=self.timeout,
                transport=self.transport,
            ) as client:
                response = await client.post("/google/calendar/create", json=payload)
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
                    return CalendarToolResult(
                        status="created",
                        message="Google Calendar 行程已建立成功。",
                        data=body.get("data"),
                    )

                error = body.get("error") or {}
                return CalendarToolResult(
                    status="error",
                    message=error.get("message") or "建立 Google Calendar 行程失敗。",
                    error_code=error.get("code") or f"HTTP_{response.status_code}",
                )
        except httpx.RequestError:
            return CalendarToolResult(
                status="error",
                message="目前無法連線至 Google Calendar 服務，請稍後再試。",
                error_code="GATEWAY_UNAVAILABLE",
            )

    def _validate_event(
        self,
        *,
        title: str,
        date: str,
        start_time: str | None,
        end_time: str | None,
        all_day: bool,
    ) -> CalendarToolResult | None:
        """Reject unsafe model output before it can create an external event."""
        now = self.now_provider().astimezone(TAIPEI_TIMEZONE)

        if not title.strip():
            return CalendarToolResult(
                status="error",
                message="行程標題不可為空。",
                error_code="INVALID_TITLE",
            )

        try:
            event_date = Date.fromisoformat(date)
        except ValueError:
            return CalendarToolResult(
                status="error",
                message="日期格式錯誤，必須是 YYYY-MM-DD。",
                error_code="INVALID_DATE",
            )

        if event_date < now.date():
            return CalendarToolResult(
                status="error",
                message=(
                    f"不可建立過去日期的行程。今天是 {now.date().isoformat()}，"
                    f"收到的日期是 {event_date.isoformat()}；請重新確認年份。"
                ),
                error_code="PAST_DATE",
            )

        if all_day:
            return None

        if not start_time:
            return CalendarToolResult(
                status="error",
                message="非全天行程必須提供開始時間。",
                error_code="MISSING_START_TIME",
            )

        try:
            parsed_start = Time.fromisoformat(start_time)
            parsed_end = Time.fromisoformat(end_time) if end_time else None
        except ValueError:
            return CalendarToolResult(
                status="error",
                message="時間格式錯誤，必須是 HH:MM 或 HH:MM:SS。",
                error_code="INVALID_TIME",
            )

        event_start = datetime.combine(
            event_date,
            parsed_start,
            tzinfo=TAIPEI_TIMEZONE,
        )
        if event_start < now:
            return CalendarToolResult(
                status="error",
                message="不可建立已經過去的行程時間，請重新確認日期與時間。",
                error_code="PAST_DATETIME",
            )

        if parsed_end is not None and parsed_end <= parsed_start:
            return CalendarToolResult(
                status="error",
                message="結束時間必須晚於開始時間。",
                error_code="INVALID_TIME_RANGE",
            )
        return None

    async def _authorization_result(
        self,
        *,
        client: httpx.AsyncClient,
        line_user_id: str,
        error_code: str,
    ) -> CalendarToolResult:
        try:
            response = await client.get(
                "/google/auth",
                params={"line_user_id": line_user_id},
            )
            body = self._json_body(response)
        except httpx.RequestError:
            return CalendarToolResult(
                status="error",
                message="無法取得 Google 帳號授權連結，請稍後再試。",
                error_code="GATEWAY_UNAVAILABLE",
            )

        auth_url = body.get("auth_url")
        if response.is_success and auth_url:
            return CalendarToolResult(
                status="authorization_required",
                message="請先開啟授權連結並完成 Google 帳號連結，完成後再重新建立行程。",
                auth_url=auth_url,
                error_code=error_code,
            )
        return CalendarToolResult(
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
