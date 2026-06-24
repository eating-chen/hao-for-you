"""Google Auth Skill：連結、更換、解除 Google 帳號"""
from app.skills.base_skill import BaseSkill, SkillContext, SkillResult
from app.db.database import AsyncSessionLocal
from app.db.crud.google_token_crud import (
    get_google_token,
    delete_google_token,
)
from app.service.google_auth_service import build_auth_url


class GoogleAuthSkill(BaseSkill):
    name = "google_auth"
    description = "連結、更換或解除 Google 帳號授權，當使用者提到 Google 帳號綁定相關操作時使用"
    keywords = [
        "連結google", "連結 google",
        "綁定google", "綁定 google",
        "更換google", "更換 google",
        "解除google", "解除 google",
        "google帳號", "google 帳號",
        "授權google", "授權 google",
        "取消google", "取消 google",
    ]

    async def execute(self, ctx: SkillContext) -> SkillResult:
        msg_lower = ctx.message.lower()
        is_revoke = any(kw in msg_lower for kw in ["解除", "取消", "移除", "刪除", "斷開"])

        if is_revoke:
            return await self._revoke(ctx.user_id)
        else:
            return await self._link(ctx.user_id)

    async def _link(self, user_id: str) -> SkillResult:
        """產生授權連結（連結或更換帳號共用同一個流程）"""
        auth_url = build_auth_url(user_id)

        async with AsyncSessionLocal() as db:
            token = await get_google_token(db, user_id)

        if token and token.google_email:
            reply = (
                f"你目前已連結：{token.google_email}\n\n"
                f"如要更換帳號，請點擊以下連結重新授權：\n{auth_url}"
            )
        else:
            reply = f"請點擊以下連結授權你的 Google 帳號：\n{auth_url}"

        return SkillResult(success=True, reply_text=reply, skill_name=self.name)

    async def _revoke(self, user_id: str) -> SkillResult:
        """解除 Google 帳號授權"""
        async with AsyncSessionLocal() as db:
            deleted = await delete_google_token(db, user_id)

        if deleted:
            reply = "✅ 已解除 Google 帳號授權。\n如需重新連結，請傳送「連結 Google 帳號」。"
        else:
            reply = "你目前沒有連結任何 Google 帳號。"

        return SkillResult(success=True, reply_text=reply, skill_name=self.name)
