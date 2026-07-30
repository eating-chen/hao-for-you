import json

import httpx
import pytest
from langchain.tools import ToolRuntime

import app.service.agent_tools as agent_tools
from app.service.agent_tools import AgentContext, query_gmail_messages
from app.service.google_mail_service import GmailToolResult, GoogleMailGateway


def test_gmail_tool_does_not_expose_trusted_user_id_to_llm():
    assert "line_user_id" not in query_gmail_messages.args
    assert "runtime" not in query_gmail_messages.args


def test_gmail_tool_describes_expected_llm_parameters():
    assert set(query_gmail_messages.args) == {
        "date",
        "date_to",
        "keyword",
        "sender",
        "unread_only",
        "limit",
    }


@pytest.mark.asyncio
async def test_query_messages_sends_filters_and_returns_messages():
    requests: list[httpx.Request] = []
    messages = [
        {
            "id": "1789abcdef123456",
            "subject": "會議通知",
            "from": "andy@example.com",
            "date": "2023-10-01T10:00:00Z",
            "snippet": "會議將於下週舉行",
            "body": "各位同事，提醒您...",
        }
    ]

    async def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(
            200,
            json={"success": True, "data": {"messages": messages}, "error": None},
        )

    gateway = GoogleMailGateway(
        base_url="http://gateway.test",
        transport=httpx.MockTransport(handler),
    )
    result = await gateway.query_messages(
        line_user_id="U123",
        date="2026-10-01",
        date_to="2026-10-05",
        keyword="會議",
        sender="john.doe@example.com",
        unread_only=False,
        limit=10,
    )

    assert result.status == "success"
    assert result.data == {"messages": messages}
    assert [request.url.path for request in requests] == ["/google/mail/query"]
    assert json.loads(requests[0].content) == {
        "line_user_id": "U123",
        "date": "2026-10-01",
        "date_to": "2026-10-05",
        "keyword": "會議",
        "from": "john.doe@example.com",
        "unread_only": False,
        "limit": 10,
    }


@pytest.mark.asyncio
async def test_query_messages_returns_auth_url_when_google_is_not_linked():
    requests: list[httpx.Request] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        if request.url.path == "/google/mail/query":
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

    gateway = GoogleMailGateway(
        base_url="http://gateway.test",
        transport=httpx.MockTransport(handler),
    )
    result = await gateway.query_messages(
        line_user_id="U123",
        keyword="會議",
    )

    assert result.status == "authorization_required"
    assert result.auth_url == "https://accounts.google.com/oauth/example"
    assert result.error_code == "GOOGLE_NOT_LINKED"
    assert [request.url.path for request in requests] == [
        "/google/mail/query",
        "/google/auth",
    ]


@pytest.mark.asyncio
async def test_query_messages_rejects_invalid_date_range_without_request():
    requests: list[httpx.Request] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(200, json={"success": True, "data": {}})

    gateway = GoogleMailGateway(
        base_url="http://gateway.test",
        transport=httpx.MockTransport(handler),
    )
    result = await gateway.query_messages(
        line_user_id="U123",
        date="2026-10-05",
        date_to="2026-10-01",
    )

    assert result.status == "error"
    assert result.error_code == "INVALID_DATE_RANGE"
    assert requests == []


@pytest.mark.asyncio
async def test_gmail_tool_injects_line_user_id_from_runtime(monkeypatch):
    captured: dict = {}

    class FakeGateway:
        async def query_messages(self, **kwargs):
            captured.update(kwargs)
            return GmailToolResult(
                status="success",
                message="查詢成功",
                data={"messages": []},
            )

    monkeypatch.setattr(agent_tools, "GoogleMailGateway", FakeGateway)
    runtime = ToolRuntime(
        state={"messages": []},
        context=AgentContext(user_id="U123", platform="line"),
        config={},
        stream_writer=lambda _: None,
        tool_call_id="call-1",
        store=None,
    )

    raw_result = await query_gmail_messages.coroutine(
        runtime=runtime,
        keyword="會議",
        limit=10,
    )

    assert captured["line_user_id"] == "U123"
    assert captured["keyword"] == "會議"
    assert json.loads(raw_result)["data"] == {"messages": []}
