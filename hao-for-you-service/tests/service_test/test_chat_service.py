from app.service.chat_service import (
    ToolExecution,
    build_thread_config,
    extract_message_text,
    extract_summary,
    extract_tool_executions,
    make_thread_id,
    build_current_system_prompt,
)
from app.core.config import config
from datetime import datetime
from zoneinfo import ZoneInfo


class Message:
    def __init__(self, content, additional_kwargs=None):
        self.content = content
        self.additional_kwargs = additional_kwargs or {}


def test_thread_id_is_stable_and_isolated():
    first = make_thread_id("user-1", "session-1")
    assert first == make_thread_id("user-1", "session-1")
    assert first != make_thread_id("user-2", "session-1")
    assert build_thread_config("user-1", "session-1") == {
        "configurable": {"thread_id": first}
    }


def test_thread_id_changes_with_agent_state_version(monkeypatch):
    original = make_thread_id("user-1", "session-1")
    monkeypatch.setattr(config.AgentConfig, "state_version", "next-version")
    assert make_thread_id("user-1", "session-1") != original


def test_extract_message_text_supports_content_blocks():
    message = Message([{"type": "text", "text": "hello"}, " world"])
    assert extract_message_text(message) == "hello world"


def test_extract_summary_reads_middleware_message():
    message = Message(
        "Here is a summary of the conversation to date:\n\nimportant context",
        {"lc_source": "summarization"},
    )
    assert extract_summary([message]) == "important context"


def test_extract_tool_executions_only_reads_latest_turn():
    old_human = Message("old question")
    old_human.type = "human"
    old_tool = Message("3")
    old_tool.type = "tool"
    old_tool.name = "add_operation"
    old_tool.tool_call_id = "old-call"
    old_tool.status = "success"

    human = Message("what is 10 + 20?")
    human.type = "human"
    ai_call = Message("")
    ai_call.type = "ai"
    ai_call.tool_calls = [
        {"id": "new-call", "name": "add_operation", "args": {"a": 10, "b": 20}}
    ]
    tool = Message("30")
    tool.type = "tool"
    tool.name = "add_operation"
    tool.tool_call_id = "new-call"
    tool.status = "success"

    assert extract_tool_executions([old_human, old_tool, human, ai_call, tool]) == [
        ToolExecution(
            name="add_operation",
            arguments={"a": 10, "b": 20},
            status="success",
            result="30",
        )
    ]


def test_current_system_prompt_includes_taipei_clock():
    now = datetime(2026, 7, 21, 15, 30, tzinfo=ZoneInfo("Asia/Taipei"))

    prompt = build_current_system_prompt(now)

    assert "目前台北日期是 2026-07-21" in prompt
    assert "時間是 15:30:00" in prompt
