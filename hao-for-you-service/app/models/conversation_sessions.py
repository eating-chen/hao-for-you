from datetime import datetime
from typing import List, Optional
from sqlalchemy import (
    DateTime,
    ForeignKey,
    Index,
    String,
    Text,
    func,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship
from app.models.base import Base


class ConversationSession(Base):
    __tablename__ = "conversation_sessions"
    __table_args__ = (
        Index("idx_conversation_sessions_ai_user_id", "ai_user_id"),
        Index("idx_conversation_sessions_session_id", "session_id"),
    )

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    ai_user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), nullable=False)
    session_id: Mapped[str] = mapped_column(String(150), unique=True, nullable=False)
    platform: Mapped[str] = mapped_column(String(50), nullable=False, default="line")
    status: Mapped[str] = mapped_column(String(30), nullable=False, default="active")
    summary: Mapped[Optional[str]] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(
        DateTime,
        nullable=False,
        server_default=func.current_timestamp(),
    )
    updated_at: Mapped[Optional[datetime]] = mapped_column(DateTime)
    last_message_at: Mapped[Optional[datetime]] = mapped_column(DateTime)

    user = relationship("User", back_populates="sessions")
    messages = relationship(
        "ConversationMessage",
        back_populates="session",
        cascade="all, delete-orphan",
    )