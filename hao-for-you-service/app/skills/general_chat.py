"""General Chat Skill：一般對話，帶有對話歷史的 LLM 聊天"""
from app.skills.base_skill import BaseSkill, SkillContext, SkillResult
from app.manager.modelManager import llm
from app.core.config import config


class GeneralChatSkill(BaseSkill):
    name = "general_chat"
    description = "一般對話、問答、閒聊，以及無法判斷意圖時的 fallback"
    keywords = []  # 不做關鍵字比對，作為 fallback 使用

    async def execute(self, ctx: SkillContext) -> SkillResult:
        # 組裝 messages：system prompt + 對話歷史 + 本次使用者訊息
        messages = [
            {"role": "system", "content": config.ModelConfig.system_prompt}
        ]

        # 加入對話歷史（最多 max_conversation_turns * 2 筆）
        max_msgs = config.DatabaseConfig.max_conversation_turns * 2
        for turn in ctx.conversation_history[-max_msgs:]:
            messages.append({"role": turn["role"], "content": turn["content"]})

        # 加入本次訊息
        messages.append({"role": "user", "content": ctx.message})

        reply = llm.chat(messages)

        return SkillResult(
            success=True,
            reply_text=reply,
            skill_name=self.name,
        )
