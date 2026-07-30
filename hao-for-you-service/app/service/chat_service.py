"""Agent construction and PostgreSQL-backed chat execution."""
from __future__ import annotations

import uuid
from dataclasses import dataclass
from datetime import datetime
from typing import Any
from zoneinfo import ZoneInfo

from langchain.agents import create_agent
from langchain.agents.middleware import (
    ModelRequest,
    SummarizationMiddleware,
    dynamic_prompt,
)
from langchain_aws import ChatBedrockConverse
from langchain_core.language_models import BaseChatModel
from langchain_ollama import ChatOllama
from langgraph.checkpoint.base import BaseCheckpointSaver

from app.core.config import config
from app.db.crud.user_chat import ensure_session, ensure_user, save_message, update_summary
from app.manager.database import db_manager
from app.service.util_service import (
    add_operation,
    subtract_operation,
    multiply_operation,
    divide_operation
)
from app.service.agent_tools import AGENT_TOOLS, AgentContext
from app.service.logger_service import logger
from app.core.prompt_constant import SYSTEM_PROMPT

TAIPEI_TIMEZONE = ZoneInfo("Asia/Taipei")

@dataclass(frozen=True)
class ToolExecution:
    name: str
    arguments: dict[str, Any]
    status: str
    result: str


@dataclass(frozen=True)
class ChatResult:
    response: str
    tool_executions: list[ToolExecution]


def build_current_system_prompt(now: datetime | None = None) -> str:
    current = now or datetime.now(tz=TAIPEI_TIMEZONE)
    current = current.astimezone(TAIPEI_TIMEZONE)
    return (
        f"{SYSTEM_PROMPT}\n"
        f"目前台北日期是 {current.date().isoformat()}，"
        f"時間是 {current.strftime('%H:%M:%S')}。"
    )


@dynamic_prompt
def current_datetime_prompt(request: ModelRequest[AgentContext]) -> str:
    """Give every model call a fresh, timezone-specific clock."""
    return build_current_system_prompt()


def _build_model(model_name: str) -> BaseChatModel:
    model_config = config.ModelConfig
    generate_config = model_config.generate_config

    if model_config.provider == "bedrock":
        logger.info(f"Using Amazon Bedrock model: {model_name} in region {model_config.aws_region}")
        # Claude on Bedrock rejects requests that set both temperature and top_p.
        return ChatBedrockConverse(
            model=model_name,
            region_name=model_config.aws_region,
            temperature=generate_config["temperature"],
            max_tokens=generate_config["max_tokens"],
        )

    return ChatOllama(
        model=model_name,
        base_url=model_config.ollama_host,
        temperature=generate_config["temperature"],
        num_predict=generate_config["max_tokens"],
        top_p=generate_config["top_p"],
        repeat_penalty=generate_config["repeat_penalty"],
        reasoning=generate_config["reasoning"],
    )


def build_agent(checkpointer: BaseCheckpointSaver):
    """Create the standard agent loop with persistent short-term memory."""
    model_config = config.ModelConfig
    return create_agent(
        model=_build_model(model_config.model_name),
        tools=[
            *AGENT_TOOLS,
        ],
        system_prompt=model_config.system_prompt,
        context_schema=AgentContext,
        checkpointer=checkpointer,
        middleware=[
            current_datetime_prompt,
            SummarizationMiddleware(
                model=_build_model(model_config.summary_model),
                trigger=("messages", model_config.summary_trigger_messages),
                keep=("messages", model_config.summary_keep_messages),
                summary_prompt=model_config.summary_prompt,
            )
        ],
    )


def make_thread_id(user_id: str, session_id: str) -> str:
    """Keep checkpoint identities stable and isolated across users/sessions."""
    raw_value = (
        f"hao-for-you:{config.AgentConfig.state_version}:{user_id}:{session_id}"
    )
    return str(uuid.uuid5(uuid.NAMESPACE_URL, raw_value))


def build_thread_config(user_id: str, session_id: str) -> dict[str, Any]:
    return {
        "configurable": {
            "thread_id": make_thread_id(user_id=user_id, session_id=session_id),
        }
    }


def extract_message_text(message: Any) -> str:
    content = message.content
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts: list[str] = []
        for block in content:
            if isinstance(block, str):
                parts.append(block)
            elif isinstance(block, dict) and block.get("text"):
                parts.append(str(block["text"]))
        return "".join(parts)
    return str(content)


def extract_summary(messages: list[Any]) -> str | None:
    """Read the summary message emitted by SummarizationMiddleware, if present."""
    prefix = "Here is a summary of the conversation to date:\n\n"
    for message in messages:
        metadata = getattr(message, "additional_kwargs", {})
        if metadata.get("lc_source") != "summarization":
            continue
        content = extract_message_text(message)
        return content.removeprefix(prefix)
    return None


def extract_tool_executions(messages: list[Any]) -> list[ToolExecution]:
    """Return tools completed during the latest user turn only."""
    turn_start = 0
    for index, message in enumerate(messages):
        metadata = getattr(message, "additional_kwargs", {})
        if getattr(message, "type", None) == "human" and metadata.get(
            "lc_source"
        ) != "summarization":
            turn_start = index

    calls_by_id: dict[str, dict[str, Any]] = {}
    executions: list[ToolExecution] = []
    for message in messages[turn_start + 1 :]:
        for tool_call in getattr(message, "tool_calls", []):
            calls_by_id[tool_call["id"]] = tool_call

        if getattr(message, "type", None) != "tool":
            continue
        tool_call_id = getattr(message, "tool_call_id", "")
        tool_call = calls_by_id.get(tool_call_id, {})
        executions.append(
            ToolExecution(
                name=getattr(message, "name", None) or tool_call.get("name", "unknown"),
                arguments=tool_call.get("args", {}),
                status=getattr(message, "status", "success"),
                result=extract_message_text(message),
            )
        )
    return executions


async def run_chat(
    agent: Any,
    user_id: str,
    session_id: str,
    user_input: str,
    platform: str = "line",
) -> ChatResult:
    """Invoke one turn and retain the existing application audit records."""
    async with db_manager.get_session() as db:
        await ensure_user(db, user_id=user_id, platform=platform)
        await ensure_session(
            db,
            user_id=user_id,
            session_id=session_id,
            platform=platform,
        )

    result = await agent.ainvoke(
        {"messages": [{"role": "user", "content": user_input}]},
        config=build_thread_config(user_id=user_id, session_id=session_id),
        context=AgentContext(user_id=user_id, platform=platform),
    )
    messages = result["messages"]
    answer = extract_message_text(messages[-1])
    summary = extract_summary(messages)
    tool_executions = extract_tool_executions(messages)

    async with db_manager.get_session() as db:
        await save_message(db, session_id=session_id, role="human", content=user_input)
        await save_message(db, session_id=session_id, role="ai", content=answer)
        if summary is not None:
            await update_summary(db, session_id=session_id, summary=summary)

    return ChatResult(response=answer, tool_executions=tool_executions)
