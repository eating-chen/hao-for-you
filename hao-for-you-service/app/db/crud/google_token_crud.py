"""Google Token CRUD operations"""
from datetime import datetime

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.crud.base import AppCRUD
from app.db.models import GoogleToken


class GoogleTokenCRUD(AppCRUD):

    async def get(self, line_user_id: str) -> GoogleToken | None:
        """取得某位使用者的 Google token，若不存在回傳 None"""
        result = await self.db.execute(
            select(GoogleToken).where(GoogleToken.line_user_id == line_user_id)
        )
        return result.scalar_one_or_none()

    async def upsert(
        self,
        line_user_id: str,
        access_token: str,
        refresh_token: str,
        token_expiry: datetime | None = None,
        google_email: str | None = None,
    ) -> GoogleToken:
        """
        新增或更新 token（upsert）
        - 第一次授權 → INSERT
        - 重新授權或 token 刷新 → UPDATE
        """
        token = await self.get(line_user_id)

        if token is None:
            token = GoogleToken(
                line_user_id=line_user_id,
                access_token=access_token,
                refresh_token=refresh_token,
                token_expiry=token_expiry,
                google_email=google_email,
            )
            self.db.add(token)
        else:
            token.access_token = access_token
            token.refresh_token = refresh_token
            if token_expiry is not None:
                token.token_expiry = token_expiry
            if google_email is not None:
                token.google_email = google_email

        await self.db.commit()
        await self.db.refresh(token)
        return token

    async def delete(self, line_user_id: str) -> bool:
        """撤銷授權時刪除 token，回傳是否有刪到"""
        token = await self.get(line_user_id)
        if token is None:
            return False
        await self.db.delete(token)
        await self.db.commit()
        return True

    async def is_authorized(self, line_user_id: str) -> bool:
        """快速判斷某使用者是否已授權 Google"""
        token = await self.get(line_user_id)
        return token is not None


# ── 便利函式（不需要手動實例化 CRUD）──

async def get_google_token(db: AsyncSession, line_user_id: str) -> GoogleToken | None:
    return await GoogleTokenCRUD(db).get(line_user_id)


async def upsert_google_token(
    db: AsyncSession,
    line_user_id: str,
    access_token: str,
    refresh_token: str,
    token_expiry: datetime | None = None,
    google_email: str | None = None,
) -> GoogleToken:
    return await GoogleTokenCRUD(db).upsert(
        line_user_id, access_token, refresh_token, token_expiry, google_email
    )


async def delete_google_token(db: AsyncSession, line_user_id: str) -> bool:
    return await GoogleTokenCRUD(db).delete(line_user_id)


async def is_google_authorized(db: AsyncSession, line_user_id: str) -> bool:
    return await GoogleTokenCRUD(db).is_authorized(line_user_id)
