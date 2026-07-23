from datetime import datetime
from typing import Optional
from sqlalchemy import (
    DateTime,
    String,
    UniqueConstraint,
    func,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship
from app.models.base import Base


class User(Base):
    __tablename__ = "users"
    __table_args__ = (
        UniqueConstraint(
            "platform",
            "user_id",
            name="idx_users_platform_user",
        ),
    )

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    user_id: Mapped[str] = mapped_column(String(100), nullable=False)
    platform: Mapped[str] = mapped_column(String(50), nullable=False, default="line")
    created_at: Mapped[datetime] = mapped_column(
        DateTime,
        nullable=False,
        server_default=func.current_timestamp(),
    )
    updated_at: Mapped[Optional[datetime]] = mapped_column(DateTime)

    sessions = relationship(
        'ConversationSession',
        back_populates="user",
        cascade="all, delete-orphan",
    )
