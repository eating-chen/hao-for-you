"""Intent Router：混合模式意圖判斷（關鍵字優先，LLM fallback）"""
from app.skills.registry import SkillRegistry
from app.manager.modelManager import llm
from app.service.logger_service import logger, error_logger
from app.service.util_service import extract_json

# ──────────────────────────────────────────────
# LLM Intent Classification Prompt
# ──────────────────────────────────────────────

_INTENT_SYSTEM_PROMPT = """\
你是一個意圖分類器。根據使用者的最新訊息，從以下意圖中選擇最符合的一個。

可用意圖：
{skill_descriptions}

規則：
- 只輸出 JSON，格式為 {{"intent": "<意圖名稱>", "params": {{}}}}
- 不要輸出任何其他文字或解釋
- 若無法判斷，選擇 general_chat
"""

_INTENT_USER_PROMPT = """\
對話歷史（最近3輪）：
{history}

使用者最新訊息：{message}
"""


def _build_skill_descriptions() -> str:
    return "\n".join(
        f"- {skill.name}: {skill.description}"
        for skill in SkillRegistry.get_all()
    )


# ──────────────────────────────────────────────
# Public interface
# ──────────────────────────────────────────────

def route_intent(message: str, history: list[dict]) -> tuple[str, dict]:
    """
    回傳 (skill_name, extracted_params)

    流程：
    1. 關鍵字快速比對 → 命中直接回傳
    2. LLM 分類（帶最近3輪 context）
    3. 全部失敗 → fallback 到 general_chat
    """

    # Step 1：關鍵字比對（只看有 keywords 的 skill）
    for skill in SkillRegistry.get_keyword_skills():
        if skill.keyword_match(message):
            logger.info("Intent matched by keyword: %s", skill.name)
            return skill.name, {}

    # Step 2：LLM 分類（只有超過一個 skill 時才有意義）
    if len(SkillRegistry.get_all()) > 1:
        try:
            recent_history = history[-6:]  # 最近 3 輪 = 6 筆
            history_text = "\n".join(
                f"{'使用者' if t['role'] == 'user' else 'Hao'}: {t['content']}"
                for t in recent_history
            ) or "（無對話歷史）"

            prompt = (
                _INTENT_SYSTEM_PROMPT.format(
                    skill_descriptions=_build_skill_descriptions()
                )
                + "\n\n"
                + _INTENT_USER_PROMPT.format(
                    history=history_text,
                    message=message,
                )
            )

            raw = llm.generate(prompt)
            result = extract_json(raw)
            intent = result.get("intent", "general_chat")
            params = result.get("params", {})

            # 確認 intent 是已知的 skill
            if SkillRegistry.get_by_name(intent):
                logger.info("Intent classified by LLM: %s", intent)
                return intent, params
            else:
                logger.warning("LLM returned unknown intent '%s', falling back.", intent)

        except Exception as e:
            error_logger.exception("Intent classification error: %s", e)

    # Step 3：Fallback
    logger.info("Intent fallback to general_chat")
    return "general_chat", {}
