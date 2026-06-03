"""Skill 系統的抽象基底類別與資料結構"""
from abc import ABC, abstractmethod
from dataclasses import dataclass, field


@dataclass
class SkillContext:
    """執行 skill 時傳入的上下文"""
    user_id: str                            # LINE user ID
    message: str                            # 使用者本次訊息
    conversation_history: list[dict]        # 對話歷史 [{"role": "user/assistant", "content": "..."}]
    extracted_params: dict = field(default_factory=dict)  # intent router 萃取的參數


@dataclass
class SkillResult:
    """Skill 執行結果"""
    success: bool
    reply_text: str
    skill_name: str
    error_message: str | None = None


class BaseSkill(ABC):
    """所有 Skill 必須繼承此類別"""

    name: str           # skill 唯一識別名，用於 registry 和 DB 紀錄
    description: str    # 給 LLM 看的描述，用於 intent 分類
    keywords: list[str] # 快速關鍵字比對清單（中文詞）

    @abstractmethod
    async def execute(self, ctx: SkillContext) -> SkillResult:
        """執行 skill 的核心邏輯，必須由子類別實作"""

    def keyword_match(self, message: str) -> bool:
        """預設關鍵字比對：任一關鍵字出現在訊息中即命中"""
        return any(kw in message for kw in self.keywords)

    def __repr__(self) -> str:
        return f"<Skill name={self.name}>"
