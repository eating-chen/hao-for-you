"""Skills package — 在這裡註冊所有 Skill"""
from app.skills.registry import SkillRegistry
from app.skills.general_chat import GeneralChatSkill
from app.skills.google_auth_skill import GoogleAuthSkill
from app.skills.calendar_skill import CalendarSkill

# 注意：關鍵字有優先順序，Keywords 越精準的 Skill 要先 register
# general_chat 沒有 keywords，永遠最後 fallback
SkillRegistry.register(GoogleAuthSkill())
SkillRegistry.register(CalendarSkill())
SkillRegistry.register(GeneralChatSkill())

# Phase 3 之後在這裡加：
# from app.skills.email_skill import EmailSkill
# SkillRegistry.register(EmailSkill())
