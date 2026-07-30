import json
from datetime import datetime
from zoneinfo import ZoneInfo

import httpx
import pytest
from langchain.tools import ToolRuntime

import app.service.agent_tools as agent_tools
from app.service.agent_tools import (
    AgentContext,
    create_google_calendar_event,
    modify_google_calendar_event,
    query_google_calendar_events,
)
from app.service.google_calendar_service import CalendarToolResult, GoogleCalendarGateway


FIXED_NOW = datetime(2026, 7, 21, 12, 0, tzinfo=ZoneInfo("Asia/Taipei"))


def fixed_now() -> datetime:
    return FIXED_NOW


@pytest.mark.asyncio
async def test_create_event_returns_created_without_requesting_auth():
    requests: list[httpx.Request] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(
            200,
            json={"success": True, "data": {"event_id": "event-123"}, "error": None},
        )

    gateway = GoogleCalendarGateway(
        base_url="http://gateway.test",
        transport=httpx.MockTransport(handler),
        now_provider=fixed_now,
    )
    result = await gateway.create_event(
        line_user_id="U123",
        title="Meeting",
        date="2026-10-01",
        start_time="09:00:00",
        end_time="17:00:00",
    )

    assert result.status == "created"
    assert result.data == {"event_id": "event-123"}
    assert [request.url.path for request in requests] == [
        "/google/calendar/create"
    ]
    assert json.loads(requests[0].content)["line_user_id"] == "U123"


@pytest.mark.asyncio
async def test_create_event_returns_auth_url_when_google_is_not_linked():
    requests: list[httpx.Request] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        if request.url.path == "/google/calendar/create":
            return httpx.Response(
                401,
                json={
                    "success": False,
                    "data": None,
                    "error": {
                        "code": "GOOGLE_NOT_LINKED",
                        "message": "此用戶尚未連結 Google 帳號",
                    },
                },
            )
        return httpx.Response(
            200,
            json={"auth_url": "https://accounts.google.com/oauth/example"},
        )

    gateway = GoogleCalendarGateway(
        base_url="http://gateway.test",
        transport=httpx.MockTransport(handler),
        now_provider=fixed_now,
    )
    result = await gateway.create_event(
        line_user_id="U123",
        title="Meeting",
        date="2026-10-01",
        start_time="09:00:00",
    )

    assert result.status == "authorization_required"
    assert result.auth_url == "https://accounts.google.com/oauth/example"
    assert result.error_code == "GOOGLE_NOT_LINKED"
    assert [request.url.path for request in requests] == [
        "/google/calendar/create",
        "/google/auth",
    ]
    assert requests[1].url.params["line_user_id"] == "U123"


def test_calendar_tool_does_not_expose_trusted_user_id_to_llm():
    assert "line_user_id" not in create_google_calendar_event.args
    assert "runtime" not in create_google_calendar_event.args


def test_calendar_tool_describes_every_llm_parameter():
    parameter_schema = create_google_calendar_event.args

    assert set(parameter_schema) == {
        "title",
        "date",
        "description",
        "start_time",
        "end_time",
        "all_day",
    }
    for schema in parameter_schema.values():
        serialized = json.dumps(schema, ensure_ascii=False)
        assert '"description"' in serialized


def test_calendar_query_and_modify_tools_hide_trusted_user_id():
    for calendar_tool in (
        query_google_calendar_events,
        modify_google_calendar_event,
    ):
        assert "line_user_id" not in calendar_tool.args
        assert "runtime" not in calendar_tool.args

    assert set(query_google_calendar_events.args) == {
        "date",
        "date_to",
        "keyword",
        "limit",
    }
    assert set(modify_google_calendar_event.args) == {
        "event_id",
        "title",
        "date",
        "description",
        "all_day",
        "start_time",
        "end_time",
    }


@pytest.mark.asyncio
async def test_calendar_tool_uses_model_date_without_keyword_resolution(
    monkeypatch,
):
    captured: dict = {}

    class FakeGateway:
        async def create_event(self, **kwargs):
            captured.update(kwargs)
            return CalendarToolResult(status="created", message="建立成功")

    monkeypatch.setattr(agent_tools, "GoogleCalendarGateway", FakeGateway)
    runtime = ToolRuntime(
        state={"messages": []},
        context=AgentContext(user_id="U123", platform="line"),
        config={},
        stream_writer=lambda _: None,
        tool_call_id="call-1",
        store=None,
    )

    raw_result = await create_google_calendar_event.coroutine(
        title="打球",
        date="2025-12-31",
        runtime=runtime,
        start_time="18:00",
        end_time="19:00",
    )

    assert captured["date"] == "2025-12-31"
    assert json.loads(raw_result)["resolved_date"] == "2025-12-31"


@pytest.mark.asyncio
async def test_query_events_sends_supported_filters_and_returns_events():
    requests: list[httpx.Request] = []
    events = [
        {
            "title": "會議",
            "start": "2026-10-01T10:00:00+08:00",
            "end": "2026-10-01T11:00:00+08:00",
            "description": "會議將於下週舉行",
            "status": "confirmed",
            "event_id": "event_123",
            "all_day": False,
        }
    ]

    async def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(
            200,
            json={"success": True, "data": {"events": events}, "error": None},
        )

    gateway = GoogleCalendarGateway(
        base_url="http://gateway.test",
        transport=httpx.MockTransport(handler),
        now_provider=fixed_now,
    )
    result = await gateway.query_events(
        line_user_id="U123",
        date="2026-10-01",
        date_to="2026-10-05",
        keyword="會議",
        limit=10,
    )

    assert result.status == "success"
    assert result.data == {"events": events}
    assert [request.url.path for request in requests] == [
        "/google/calendar/query"
    ]
    assert json.loads(requests[0].content) == {
        "line_user_id": "U123",
        "date": "2026-10-01",
        "date_to": "2026-10-05",
        "keyword": "會議",
        "limit": 10,
    }


@pytest.mark.asyncio
async def test_modify_event_sends_complete_event_and_returns_updated_event():
    requests: list[httpx.Request] = []
    updated_event = {
        "title": "Meeting",
        "start": "2026-10-01T09:00:00+08:00",
        "end": "2026-10-01T17:00:00+08:00",
        "description": "會議邀請",
        "status": "updated",
        "event_id": "event_123",
        "all_day": False,
    }

    async def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(
            200,
            json={
                "success": True,
                "data": {"events": [updated_event]},
                "error": None,
            },
        )

    gateway = GoogleCalendarGateway(
        base_url="http://gateway.test",
        transport=httpx.MockTransport(handler),
        now_provider=fixed_now,
    )
    result = await gateway.modify_event(
        line_user_id="U123",
        event_id="event_123",
        title="Meeting",
        date="2026-10-01",
        description="會議邀請",
        start_time="09:00:00",
        end_time="17:00:00",
        all_day=False,
    )

    assert result.status == "modified"
    assert result.data == {"events": [updated_event]}
    assert [request.url.path for request in requests] == [
        "/google/calendar/modify"
    ]
    assert json.loads(requests[0].content) == {
        "line_user_id": "U123",
        "event_id": "event_123",
        "title": "Meeting",
        "date": "2026-10-01",
        "description": "會議邀請",
        "start_time": "09:00:00",
        "end_time": "17:00:00",
        "all_day": False,
    }


@pytest.mark.asyncio
async def test_modify_event_rejects_missing_end_time_without_request():
    requests: list[httpx.Request] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(200, json={"success": True, "data": {}})

    gateway = GoogleCalendarGateway(
        base_url="http://gateway.test",
        transport=httpx.MockTransport(handler),
        now_provider=fixed_now,
    )
    result = await gateway.modify_event(
        line_user_id="U123",
        event_id="event_123",
        title="Meeting",
        date="2026-10-01",
        description="",
        start_time="09:00:00",
        all_day=False,
    )

    assert result.status == "error"
    assert result.error_code == "MISSING_END_TIME"
    assert requests == []


@pytest.mark.asyncio
async def test_create_event_rejects_past_date_without_calling_gateway():
    requests: list[httpx.Request] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(200, json={"success": True, "data": {}})

    gateway = GoogleCalendarGateway(
        base_url="http://gateway.test",
        transport=httpx.MockTransport(handler),
        now_provider=fixed_now,
    )
    result = await gateway.create_event(
        line_user_id="U123",
        title="打球",
        date="2023-07-28",
        start_time="18:00:00",
        end_time="19:00:00",
    )

    assert result.status == "error"
    assert result.error_code == "PAST_DATE"
    assert "2026-07-21" in result.message
    assert requests == []


@pytest.mark.asyncio
async def test_create_event_rejects_invalid_time_range_without_gateway_call():
    requests: list[httpx.Request] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(200, json={"success": True, "data": {}})

    gateway = GoogleCalendarGateway(
        base_url="http://gateway.test",
        transport=httpx.MockTransport(handler),
        now_provider=fixed_now,
    )
    result = await gateway.create_event(
        line_user_id="U123",
        title="打球",
        date="2026-07-28",
        start_time="19:00:00",
        end_time="18:00:00",
    )

    assert result.error_code == "INVALID_TIME_RANGE"
    assert requests == []
