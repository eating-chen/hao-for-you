from typing import List, Optional
from datetime import datetime
from sqlalchemy import (
    func,
    select,
)
from sqlalchemy.orm import Session
from langchain_core.messages import BaseMessage, HumanMessage, AIMessage
from app.models.user import User
from app.models.conversation_sessions import ConversationSession
from app.models.conversation_messages import ConversationMessage


async def ensure_user(
    db: Session,
    user_id: str,
    platform: str = "line",
) -> int:

    user = await db.scalar(select(User).where(User.user_id == user_id))
    if user is None:
        user = User(
            user_id=user_id,
            platform=platform,
        )
        db.add(user)
    else:
        user.platform = platform
        user.updated_at = datetime.utcnow()

    await db.commit()
    await db.refresh(user)
    return user.id


async def ensure_session(
    db: Session,
    user_id: str,
    session_id: str,
    platform: str = "line",
) -> int:
    user = await db.scalar(select(User).where(User.user_id == user_id))
    if user is None:
        raise ValueError(f"ai_user not found: {user_id}")

    conversation_session = await db.scalar(
        select(ConversationSession).where(ConversationSession.session_id == session_id)
    )
    if conversation_session is None:
        conversation_session = ConversationSession(
            ai_user_id=user.id,
            session_id=session_id,
            platform=platform,
        )
        db.add(conversation_session)
    else:
        conversation_session.ai_user_id = user.id
        conversation_session.platform = platform
        conversation_session.updated_at = datetime.utcnow()

    await db.commit()
    await db.refresh(conversation_session)
    return conversation_session.id


async def get_conversation_session(db: Session, session_id: str) -> ConversationSession:
    conversation_session = await db.scalar(
        select(ConversationSession).where(ConversationSession.session_id == session_id)
    )
    if conversation_session is None:
        raise ValueError(f"conversation_session not found: {session_id}")

    return conversation_session


async def get_summary(db: Session, session_id: str) -> str:
    conversation_session = await db.scalar(
        select(ConversationSession.summary).where(ConversationSession.session_id == session_id)
    )
    return conversation_session or ""


async def update_summary(db: Session, session_id: str, summary: str) -> None:
    conversation_session = await get_conversation_session(db, session_id)
    conversation_session.summary = summary
    conversation_session.updated_at = datetime.utcnow()
    await db.commit()


async def save_message(
    db: Session,
    session_id: str,
    role: str,
    content: str,
) -> None:
    conversation_session = await get_conversation_session(db, session_id)

    message = ConversationMessage(
        session_id=conversation_session.id,
        role=role,
        content=content,
    )
    conversation_session.last_message_at = datetime.utcnow()
    conversation_session.updated_at = datetime.utcnow()

    db.add(message)
    await db.commit()


async def get_recent_messages(
    db: Session,
    session_id: str,
    rounds: int = 5,
) -> List[BaseMessage]:
    """
    5 輪對話大約是 10 筆 message：
    human + ai = 1 輪
    """

    limit = rounds * 2
    conversation_session = await get_conversation_session(db, session_id)

    result = await db.scalars(
        select(ConversationMessage)
        .where(ConversationMessage.session_id == conversation_session.id)
        .order_by(ConversationMessage.id.desc())
        .limit(limit)
    )

    rows = list(result)

    messages: List[BaseMessage] = []

    for row in reversed(rows):
        if row.role == "human":
            messages.append(HumanMessage(content=row.content))
        elif row.role == "ai":
            messages.append(AIMessage(content=row.content))

    return messages


async def count_messages(db: Session, session_id: str) -> int:
    conversation_session = await get_conversation_session(db, session_id)
    total = await db.scalar(
        select(func.count(ConversationMessage.id)).where(
            ConversationMessage.session_id == conversation_session.id
        )
    )
    return int(total or 0)


async def get_all_messages_text(db: Session, session_id: str) -> str:
    conversation_session = await get_conversation_session(db, session_id)
    rows = await db.scalars(
        select(ConversationMessage)
        .where(ConversationMessage.session_id == conversation_session.id)
        .order_by(ConversationMessage.id.asc())
    )

    conversation_text = ""

    for row in rows:
        conversation_text += f"{row.role}: {row.content}\n\n"

    return conversation_text

