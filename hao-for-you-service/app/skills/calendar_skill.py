"""Calendar Skill：查詢、新增 Google Calendar 行程"""
from datetime import datetime, timedelta, timezone

from app.skills.base_skill import BaseSkill, SkillContext, SkillResult
from app.db.database import AsyncSessionLocal
from app.db.crud.google_token_crud import get_google_token, upsert_google_token
from app.manager.modelManager import llm
from app.service.google_auth_service import (
    build_auth_url,
    build_credentials,
    build_calendar_service,
    refresh_if_needed,
)
from app.service.util_service import extract_json
from app.service.logger_service import logger, error_logger

TZ = timezone(timedelta(hours=8))  # Asia/Taipei


class CalendarSkill(BaseSkill):
    name = "calendar"
    description = "查詢或新增 Google Calendar 行程、會議、提醒，當使用者提到行事曆相關操作時使用"
    keywords = [
        "行事曆", "行程", "會議", "預約", "排程",
        "今天有", "明天有", "後天有", "這週", "下週",
        "幫我排", "幫我安排", "幫我記", "記一下",
        "什麼時候", "何時", "提醒我",
    ]

    # ──────────────────────────────────────────────
    # 主要入口
    # ──────────────────────────────────────────────

    async def execute(self, ctx: SkillContext) -> SkillResult:
        # 1. 檢查是否已授權 Google
        async with AsyncSessionLocal() as db:
            token = await get_google_token(db, ctx.user_id)

        if token is None:
            auth_url = build_auth_url(ctx.user_id)
            return SkillResult(
                success=True,
                reply_text=(
                    "你還沒有連結 Google 帳號。\n"
                    "請點擊以下連結完成授權，授權後即可使用行事曆功能：\n\n"
                    f"{auth_url}"
                ),
                skill_name=self.name,
            )

        # 2. 建立 Credentials，必要時自動 refresh
        creds = build_credentials(token.access_token, token.refresh_token, token.token_expiry)
        creds, was_refreshed = refresh_if_needed(creds)

        if was_refreshed:
            async with AsyncSessionLocal() as db:
                await upsert_google_token(
                    db, ctx.user_id,
                    access_token=creds.token,
                    refresh_token=token.refresh_token,
                    token_expiry=creds.expiry,
                    google_email=token.google_email,
                )

        # 3. 取得 Calendar service
        try:
            service = build_calendar_service(creds)
        except Exception as e:
            error_logger.exception("Failed to build calendar service: %s", e)
            return SkillResult(
                success=False,
                reply_text="無法連線到 Google Calendar，請稍後再試。",
                skill_name=self.name,
            )

        # 4. LLM 萃取參數，決定子意圖
        params = self._extract_params(ctx.message)
        sub_intent = params.get("sub_intent", "list_upcoming")
        logger.info("CalendarSkill sub_intent=%s params=%s", sub_intent, params)

        if sub_intent == "create_event":
            return self._create_event(service, params)
        elif sub_intent == "query_events":
            return self._query_events(service, params)
        else:
            return self._list_upcoming(service)

    # ──────────────────────────────────────────────
    # LLM 參數萃取
    # ──────────────────────────────────────────────

    def _extract_params(self, message: str) -> dict:
        today = datetime.now(TZ).strftime("%Y-%m-%d")
        prompt = (
            f"從以下訊息萃取行事曆操作所需的參數。\n"
            f"今天日期：{today}\n\n"
            f"訊息：{message}\n\n"
            "請以 JSON 格式回覆，欄位說明：\n"
            '- sub_intent: "query_events"（查詢特定日期行程）| "create_event"（新增事件）| "list_upcoming"（列出近期行程）\n'
            '- date: "YYYY-MM-DD"（若有提到日期，否則省略）\n'
            '- time: "HH:MM"（若有提到時間，24小時制，否則省略）\n'
            "- title: 事件標題（若有提到）\n"
            "- duration_minutes: 持續分鐘數（預設 60）\n\n"
            "只輸出 JSON，不要其他文字。"
        )
        raw = llm.generate(prompt)
        return extract_json(raw)

    # ──────────────────────────────────────────────
    # 查詢特定日期行程
    # ──────────────────────────────────────────────

    def _query_events(self, service, params: dict) -> SkillResult:
        date_str = params.get("date", datetime.now(TZ).strftime("%Y-%m-%d"))
        try:
            day_start = datetime.strptime(date_str, "%Y-%m-%d").replace(tzinfo=TZ)
        except ValueError:
            day_start = datetime.now(TZ).replace(hour=0, minute=0, second=0, microsecond=0)
            date_str = day_start.strftime("%Y-%m-%d")

        day_end = day_start + timedelta(days=1)

        try:
            result = service.events().list(
                calendarId="primary",
                timeMin=day_start.isoformat(),
                timeMax=day_end.isoformat(),
                singleEvents=True,
                orderBy="startTime",
            ).execute()
        except Exception as e:
            error_logger.exception("Calendar API error: %s", e)
            return SkillResult(
                success=False,
                reply_text="查詢行事曆時發生錯誤，請稍後再試。",
                skill_name=self.name,
            )

        events = result.get("items", [])
        date_label = datetime.strptime(date_str, "%Y-%m-%d").strftime("%m/%d")

        if not events:
            return SkillResult(
                success=True,
                reply_text=f"📅 {date_label} 沒有行程。",
                skill_name=self.name,
            )

        lines = [f"📅 {date_label} 的行程：\n"]
        for event in events:
            start = event["start"].get("dateTime", event["start"].get("date", ""))
            time_str = "全天"
            if "T" in start:
                dt = datetime.fromisoformat(start)
                time_str = dt.strftime("%H:%M")
            lines.append(f"• {time_str}  {event.get('summary', '（無標題）')}")

        return SkillResult(
            success=True,
            reply_text="\n".join(lines),
            skill_name=self.name,
        )

    # ──────────────────────────────────────────────
    # 新增事件
    # ──────────────────────────────────────────────

    def _create_event(self, service, params: dict) -> SkillResult:
        title = params.get("title") or "新事件"
        date_str = params.get("date", datetime.now(TZ).strftime("%Y-%m-%d"))
        time_str = params.get("time", "09:00")
        duration = int(params.get("duration_minutes", 60))

        try:
            start_dt = datetime.strptime(
                f"{date_str} {time_str}", "%Y-%m-%d %H:%M"
            ).replace(tzinfo=TZ)
        except ValueError:
            return SkillResult(
                success=False,
                reply_text="無法解析日期或時間，請再說清楚一點，例如：「明天下午三點」。",
                skill_name=self.name,
            )

        end_dt = start_dt + timedelta(minutes=duration)

        event_body = {
            "summary": title,
            "start": {"dateTime": start_dt.isoformat(), "timeZone": "Asia/Taipei"},
            "end": {"dateTime": end_dt.isoformat(), "timeZone": "Asia/Taipei"},
        }

        try:
            service.events().insert(calendarId="primary", body=event_body).execute()
        except Exception as e:
            error_logger.exception("Calendar create event error: %s", e)
            return SkillResult(
                success=False,
                reply_text="新增行程時發生錯誤，請稍後再試。",
                skill_name=self.name,
            )

        return SkillResult(
            success=True,
            reply_text=(
                f"✅ 已新增行程「{title}」\n"
                f"📅 {start_dt.strftime('%m/%d（%A）')} "
                f"{start_dt.strftime('%H:%M')} – {end_dt.strftime('%H:%M')}"
            ),
            skill_name=self.name,
        )

    # ──────────────────────────────────────────────
    # 列出近期行程
    # ──────────────────────────────────────────────

    def _list_upcoming(self, service) -> SkillResult:
        now = datetime.now(TZ).isoformat()

        try:
            result = service.events().list(
                calendarId="primary",
                timeMin=now,
                maxResults=5,
                singleEvents=True,
                orderBy="startTime",
            ).execute()
        except Exception as e:
            error_logger.exception("Calendar API error: %s", e)
            return SkillResult(
                success=False,
                reply_text="查詢行事曆時發生錯誤，請稍後再試。",
                skill_name=self.name,
            )

        events = result.get("items", [])

        if not events:
            return SkillResult(
                success=True,
                reply_text="📅 近期沒有行程。",
                skill_name=self.name,
            )

        lines = ["📅 近期行程：\n"]
        for event in events:
            start = event["start"].get("dateTime", event["start"].get("date", ""))
            if "T" in start:
                dt = datetime.fromisoformat(start)
                label = dt.strftime("%m/%d %H:%M")
            else:
                label = start[:10]
            lines.append(f"• {label}  {event.get('summary', '（無標題）')}")

        return SkillResult(
            success=True,
            reply_text="\n".join(lines),
            skill_name=self.name,
        )
