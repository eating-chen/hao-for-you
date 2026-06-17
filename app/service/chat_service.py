from app.schemas.cahtbot_schema import State
import asyncio
from typing import Any, Optional
from app.core.config import config
from app.service.logger_service import logger
from langchain_ollama import ChatOllama
import threading
from app.manager.database import db_manager
from app.db.crud.user_chat import ensure_session, ensure_user, save_message, count_messages, get_summary, get_all_messages_text, update_summary, get_recent_messages
from langchain_core.messages import (
    HumanMessage,
    SystemMessage
)
from langgraph.graph import StateGraph, START, END


def run_async(coro):
    try:
        asyncio.get_running_loop()
    except RuntimeError:
        return asyncio.run(coro)

    raise RuntimeError("Async DB helpers cannot be called from an already running event loop.")


def load_memory(state: State) -> State:
    """
    從 SQLite 讀取：
    1. session summary
    2. 最近 5 輪對話
    """

    print("\n-------> ENTERING load_memory")

    platform = state.get("platform", "line")
    platform_user_id = state.get("platform_user_id") or state["user_id"]

    async def _load_memory():
        await db_manager.init_db()
        async with db_manager.get_session() as db:
            await ensure_user(
                db,
                user_id=state["user_id"],
                display_name=state.get("display_name"),
                platform=platform,
                platform_user_id=platform_user_id,
            )
            await ensure_session(
                db,
                user_id=state["user_id"],
                session_id=state["session_id"],
                platform=platform,
            )

            summary = await get_summary(db, state["session_id"])
            recent_messages = await get_recent_messages(
                db,
                session_id=state["session_id"],
                rounds=5,
            )
            return summary, recent_messages

    summary, recent_messages = run_async(_load_memory())

    return State(
        user_id=state["user_id"],
        session_id=state["session_id"],
        user_input=state["user_input"],
        platform=platform,
        platform_user_id=platform_user_id,
        display_name=state.get("display_name"),
        summary=summary,
        messages=recent_messages,
        ai_response=None,
    )


def ask_question(state: State) -> State:
    """
    把這次使用者輸入加入 messages。

    注意：
    這裡不要放 system prompt。
    system prompt 應該在 chatbot node 組 prompt 時加入。
    """

    print("\n-------> ENTERING ask_question")

    return State(
        user_id=state["user_id"],
        session_id=state["session_id"],
        user_input=state["user_input"],
        platform=state.get("platform", "line"),
        platform_user_id=state.get("platform_user_id", ""),
        display_name=state.get("display_name"),
        summary=state.get("summary", ""),
        messages=[
            HumanMessage(content=state["user_input"])
        ],
        ai_response=None,
    )


def chatbot(chat_model: Any):
    """
    因為 node 需要使用 chat_model，
    所以這邊用 closure 包一層。
    """
    import time
    
    def _chatbot(state: State) -> State:
        print("\n-------> ENTERING chatbot")

        summary = state.get("summary", "")

        summary_message = SystemMessage(
            content=f"""
這是目前的對話摘要:
{summary if summary else "目前沒有摘要。"}

請根據摘要與最近對話，保持回答連貫。
"""
        )

        prompt = [
            SystemMessage(content=config.ModelConfig.system_prompt),
            summary_message,
            *state["messages"],
        ]
        start_time = time.time()
        response = chat_model.invoke(prompt)
        end_time = time.time()
        print(f"\n-------> AI RESPONSE TIME: {end_time - start_time:.2f} seconds")

        print("\n-------> AI RESPONSE")
        print(response.content)

        return State(
            user_id=state["user_id"],
            session_id=state["session_id"],
            user_input=state["user_input"],
            platform=state.get("platform", "line"),
            platform_user_id=state.get("platform_user_id", ""),
            display_name=state.get("display_name"),
            summary=summary,
            messages=[response],
            ai_response=response.content,
        )

    return _chatbot


def save_messages(state: State) -> State:
    """
    把這一輪 human message 和 ai response 存進 SQLite。
    """

    print("\n-------> ENTERING save_messages")

    if not state.get("ai_response"):
        raise ValueError("ai_response is empty. chatbot node may have failed.")

    async def _save_messages():
        await db_manager.init_db()
        async with db_manager.get_session() as db:
            await save_message(
                db,
                session_id=state["session_id"],
                role="human",
                content=state["user_input"],
            )

            await save_message(
                db,
                session_id=state["session_id"],
                role="ai",
                content=state["ai_response"],
            )

    run_async(_save_messages())

    return state


def should_summarize(state: State) -> str:
    """
    conditional edge 用的 function。

    messages 超過 threshold 才更新 summary。
    """

    print("\n-------> CHECK should_summarize")

    threshold = 20

    async def _count_messages():
        await db_manager.init_db()
        async with db_manager.get_session() as db:
            return await count_messages(db, state["session_id"])

    total = run_async(_count_messages())

    print(f"-------> message count: {total}")

    if total > threshold:
        return "summarize"

    return "end"


def summarize_messages(chat_model: Any):
    def _summarize_messages(state: State) -> State:
        print("\n-------> ENTERING summarize_messages")

        async def _load_summary_input():
            await db_manager.init_db()
            async with db_manager.get_session() as db:
                old_summary = await get_summary(db, state["session_id"])
                conversation_text = await get_all_messages_text(db, state["session_id"])
                return old_summary, conversation_text

        old_summary, conversation_text = run_async(_load_summary_input())

        summary_prompt = f"""
你正在維護一段使用者與 AI 助手的對話摘要。

請根據「既有摘要」和「新的對話內容」更新摘要。

要求：
1. 保留使用者已提供的重要資訊
2. 保留尚未完成的任務
3. 保留行程、時間、地點、人名等關鍵資訊
4. 不要逐字記錄
5. 摘要要簡潔
6. 使用繁體中文

既有摘要：
{old_summary if old_summary else "目前沒有摘要。"}

新的對話內容：
{conversation_text}

請輸出更新後的摘要。
"""

        response = chat_model.invoke(
            [HumanMessage(content=summary_prompt)]
        )

        print("\n-------> NEW SUMMARY")
        print(response.content)

        return State(
            user_id=state["user_id"],
            session_id=state["session_id"],
            user_input=state["user_input"],
            platform=state.get("platform", "line"),
            platform_user_id=state.get("platform_user_id", ""),
            display_name=state.get("display_name"),
            summary=response.content,
            messages=[],
            ai_response=state["ai_response"],
        )

    return _summarize_messages


def save_summary(state: State) -> State:
    """
    將新的 summary 寫回 SQLite。
    """

    print("\n-------> ENTERING save_summary")

    async def _save_summary():
        await db_manager.init_db()
        async with db_manager.get_session() as db:
            await update_summary(
                db,
                session_id=state["session_id"],
                summary=state["summary"],
            )

    run_async(_save_summary())

    return state


def _run_summary_job(chat_model: Any, session_id: str) -> None:
    """
    背景更新 summary。

    這個工作不應該影響使用者這次 response；任何錯誤都只記錄 log。
    """

    try:
        print("\n-------> ENTERING background summarize_messages")

        async def _load_summary_input():
            await db_manager.init_db()
            async with db_manager.get_session() as db:
                old_summary = await get_summary(db, session_id)
                conversation_text = await get_all_messages_text(db, session_id)
                return old_summary, conversation_text

        old_summary, conversation_text = run_async(_load_summary_input())

        summary_prompt = f"""
你正在維護一段使用者與 AI 助手的對話摘要。

請根據「既有摘要」和「新的對話內容」更新摘要。

要求：
1. 保留使用者已提供的重要資訊
2. 保留尚未完成的任務
3. 保留行程、時間、地點、人名等關鍵資訊
4. 不要逐字記錄
5. 摘要要簡潔
6. 使用繁體中文

既有摘要：
{old_summary if old_summary else "目前沒有摘要。"}

新的對話內容：
{conversation_text}

請輸出更新後的摘要。
"""

        response = chat_model.invoke(
            [HumanMessage(content=summary_prompt)]
        )

        print("\n-------> NEW SUMMARY")
        print(response.content)

        async def _save_summary():
            await db_manager.init_db()
            async with db_manager.get_session() as db:
                await update_summary(
                    db,
                    session_id=session_id,
                    summary=response.content,
                )

        run_async(_save_summary())
    except Exception:
        logger.exception("Background summary failed for session_id=%s", session_id)


def maybe_start_background_summary(chat_model: Any):
    def _maybe_start_background_summary(state: State) -> State:
        print("\n-------> ENTERING maybe_start_background_summary")

        try:
            should_run_summary = should_summarize(state) == "summarize"
        except Exception:
            logger.exception(
                "Background summary check failed for session_id=%s",
                state["session_id"],
            )
            return state

        if not should_run_summary:
            return state

        summary_thread = threading.Thread(
            target=_run_summary_job,
            args=(chat_model, state["session_id"]),
            daemon=True,
        )
        summary_thread.start()

        return state

    return _maybe_start_background_summary


def build_default_graph():
    

    chat_model = ChatOllama(
        model=config.ModelConfig.ollama_model,
        base_url=config.ModelConfig.ollama_host,
        temperature=config.ModelConfig.generate_config['temperature'],
        num_predict=config.ModelConfig.generate_config['num_predict'],
        top_p=config.ModelConfig.generate_config['top_p'],
        repeat_penalty=config.ModelConfig.generate_config['repeat_penalty'],
        reasoning=config.ModelConfig.generate_config['reasoning'],
        validate_model_on_init=True,
    )

    return build_graph(chat_model)


def build_graph(chat_model: Any):
    builder = StateGraph(State)

    builder.add_node("load_memory", load_memory)
    builder.add_node("ask_question", ask_question)
    builder.add_node("chatbot", chatbot(chat_model))
    builder.add_node("save_messages", save_messages)
    builder.add_node("maybe_start_background_summary", maybe_start_background_summary(chat_model))

    builder.add_edge(START, "load_memory")
    builder.add_edge("load_memory", "ask_question")
    builder.add_edge("ask_question", "chatbot")
    builder.add_edge("chatbot", "save_messages")
    builder.add_edge("save_messages", "maybe_start_background_summary")
    builder.add_edge("maybe_start_background_summary", END)

    return builder.compile()


def run_chat(
    graph,
    user_id: str,
    session_id: str,
    user_input: str,
    platform: str = "line",
    platform_user_id: Optional[str] = None,
    display_name: Optional[str] = None,
) -> str:
    result = graph.invoke(
        {
            "user_id": user_id,
            "session_id": session_id,
            "user_input": user_input,
            "platform": platform,
            "platform_user_id": platform_user_id or user_id,
            "display_name": display_name,
            "summary": "",
            "messages": [],
            "ai_response": None,
        }
    )

    return result["ai_response"]