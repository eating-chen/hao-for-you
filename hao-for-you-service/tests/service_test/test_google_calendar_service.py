import json
from datetime import date, datetime
from zoneinfo import ZoneInfo

import httpx
import pytest
from langchain_core.messages import HumanMessage
from langchain.tools import ToolRuntime

import app.service.agent_tools as agent_tools
from app.core.config import config
from app.service import internal_auth
from app.service.agent_tools import AgentContext, create_google_calendar_event
from app.service.google_calendar_service import CalendarToolResult, GoogleCalendarGateway


FIXED_NOW = datetime(2026, 7, 21, 12, 0, tzinfo=ZoneInfo("Asia/Taipei"))
FIXED_EPOCH = int(FIXED_NOW.timestamp())
INTERNAL_SECRET = "gateway-test-secret"


def fixed_now() -> datetime:
    return FIXED_NOW


@pytest.fixture(autouse=True)
def _pin_internal_secret(monkeypatch):
    """All gateway calls sign with a known secret so signatures are verifiable."""
    monkeypatch.setattr(config.SecurityConfig, "internal_api_secret", INTERNAL_SECRET)
    monkeypatch.setattr(config.SecurityConfig, "internal_api_timestamp_window", 300)


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


@pytest.mark.asyncio
async def test_calendar_tool_overrides_model_year_from_original_user_text(
    monkeypatch,
):
    captured: dict = {}

    class FakeGateway:
        async def create_event(self, **kwargs):
            captured.update(kwargs)
            return CalendarToolResult(status="created", message="建立成功")

    monkeypatch.setattr(agent_tools, "GoogleCalendarGateway", FakeGateway)
    monkeypatch.setattr(agent_tools, "_today_in_taipei", lambda: date(2026, 7, 21))
    runtime = ToolRuntime(
        state={
            "messages": [
                HumanMessage(content="幫我新增 7/27 晚上6:00到7:00打球")
            ]
        },
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

    assert captured["date"] == "2026-07-27"
    assert json.loads(raw_result)["resolved_date"] == "2026-07-27"


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


def _assert_signature_matches_sent_request(request: httpx.Request) -> None:
    """The signature on the wire must verify against the exact bytes sent."""
    ts = request.headers.get("X-Timestamp")
    signature = request.headers.get("X-Signature")
    assert ts is not None
    assert signature is not None
    assert internal_auth.verify(
        secret=INTERNAL_SECRET,
        timestamp=ts,
        signature=signature,
        method=request.method,
        path=request.url.path,
        body=request.content.decode("utf-8"),
        window_seconds=300,
        now=FIXED_EPOCH,
    )


@pytest.mark.asyncio
async def test_create_event_signs_the_exact_post_body_it_sends():
    requests: list[httpx.Request] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(
            200, json={"success": True, "data": {"event_id": "e1"}, "error": None}
        )

    gateway = GoogleCalendarGateway(
        base_url="http://gateway.test",
        transport=httpx.MockTransport(handler),
        now_provider=fixed_now,
    )
    await gateway.create_event(
        line_user_id="U123",
        title="Meeting",
        date="2026-10-01",
        start_time="09:00:00",
        end_time="17:00:00",
    )

    request = requests[0]
    assert request.method == "POST"
    assert request.url.path == "/google/calendar/create"
    assert request.headers["X-Timestamp"] == str(FIXED_EPOCH)
    _assert_signature_matches_sent_request(request)


@pytest.mark.asyncio
async def test_authorization_request_signs_get_with_empty_body():
    requests: list[httpx.Request] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        if request.url.path == "/google/calendar/create":
            return httpx.Response(
                401,
                json={
                    "success": False,
                    "data": None,
                    "error": {"code": "GOOGLE_NOT_LINKED", "message": "not linked"},
                },
            )
        return httpx.Response(200, json={"auth_url": "https://accounts.google.com/x"})

    gateway = GoogleCalendarGateway(
        base_url="http://gateway.test",
        transport=httpx.MockTransport(handler),
        now_provider=fixed_now,
    )
    await gateway.create_event(
        line_user_id="U123",
        title="Meeting",
        date="2026-10-01",
        start_time="09:00:00",
    )

    auth_request = requests[1]
    assert auth_request.method == "GET"
    assert auth_request.url.path == "/google/auth"
    assert auth_request.content == b""
    # query string is intentionally excluded from the signature
    assert auth_request.url.params["line_user_id"] == "U123"
    _assert_signature_matches_sent_request(auth_request)


@pytest.mark.asyncio
async def test_create_event_authorization_fail_401_does_not_trigger_reauth():
    """Signature-rejection 401 (AUTHORIZATION_FAIL) must not be treated as reauth."""
    requests: list[httpx.Request] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(
            401,
            json={
                "success": False,
                "data": None,
                "error": {
                    "code": "AUTHORIZATION_FAIL",
                    "message": "內部服務驗證失敗",
                },
            },
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

    assert result.status == "error"
    assert result.error_code == "AUTHORIZATION_FAIL"
    assert result.auth_url is None
    # must NOT fall into the reauth flow -> only the create call, no /google/auth
    assert [request.url.path for request in requests] == ["/google/calendar/create"]


@pytest.mark.asyncio
async def test_create_event_raises_when_internal_api_secret_is_empty(monkeypatch):
    """空 secret 時 ``internal_auth.sign`` 拋 ``RuntimeError``；此例外發生在
    ``httpx.RequestError`` try 區塊之外，因此不會被吞掉、也完全不會送出請求
    （空 secret 不簽出請求）。
    """
    monkeypatch.setattr(config.SecurityConfig, "internal_api_secret", "")

    requests: list[httpx.Request] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(200, json={"success": True, "data": {}})

    gateway = GoogleCalendarGateway(
        base_url="http://gateway.test",
        transport=httpx.MockTransport(handler),
        now_provider=fixed_now,
    )

    with pytest.raises(RuntimeError, match="INTERNAL_API_SECRET is not configured"):
        await gateway.create_event(
            line_user_id="U123",
            title="Meeting",
            date="2026-10-01",
            start_time="09:00:00",
        )

    assert requests == []
