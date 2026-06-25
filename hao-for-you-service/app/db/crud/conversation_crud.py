"""Conversation CRUD operations"""
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.crud.base import AppCRUD
from app.db.models import Conversation


class ConversationCRUD(AppCRUD):

    async def add_message(
        self,
        line_user_id: str,
        role: str,
        content: str,
        skill_used: str | None = None,
    ) -> Conversation:
        msg = Conversation(
            line_user_id=line_user_id,
            role=role,
            content=content,
            skill_used=skill_used,
        )
        self.db.add(msg)
        await self.db.commit()
        await self.db.refresh(msg)
        return msg

    async def get_recent(self, line_user_id: str, limit: int = 20) -> list[Conversation]:
        """取得最近 N 筆，由舊到新排序（方便組成 chat messages）"""
        result = await self.db.execute(
            select(Conversation)
            .where(Conversation.line_user_id == line_user_id)
            .order_by(Conversation.created_at.desc())
            .limit(limit)
        )
        rows = result.scalars().all()
        return list(reversed(rows))  # 反轉成由舊到新


async def add_message(
    db: AsyncSession,
    line_user_id: str,
    role: str,
    content: str,
    skill_used: str | None = None,
) -> Conversation:
    """便利函式"""
    return await ConversationCRUD(db).add_message(line_user_id, role, content, skill_used)


async def get_recent_messages(
    db: AsyncSession,
    line_user_id: str,
    limit: int = 20,
) -> list[dict]:
    """回傳 list of dict，格式為 {"role": ..., "content": ...}，方便直接丟給 LLM"""
    rows = await ConversationCRUD(db).get_recent(line_user_id, limit)
    return [{"role": row.role, "content": row.content} for row in rows]
