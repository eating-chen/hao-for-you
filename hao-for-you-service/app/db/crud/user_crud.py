"""User CRUD operations"""
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.crud.base import AppCRUD
from app.db.models import User


class UserCRUD(AppCRUD):

    async def get_by_line_id(self, line_user_id: str) -> User | None:
        result = await self.db.execute(
            select(User).where(User.line_user_id == line_user_id)
        )
        return result.scalar_one_or_none()

    async def create(self, line_user_id: str, display_name: str | None = None) -> User:
        user = User(line_user_id=line_user_id, display_name=display_name)
        self.db.add(user)
        await self.db.commit()
        await self.db.refresh(user)
        return user

    async def get_or_create(self, line_user_id: str) -> User:
        user = await self.get_by_line_id(line_user_id)
        if not user:
            user = await self.create(line_user_id)
        return user


async def get_or_create_user(db: AsyncSession, line_user_id: str) -> User:
    """便利函式，不需要手動實例化 UserCRUD"""
    return await UserCRUD(db).get_or_create(line_user_id)
