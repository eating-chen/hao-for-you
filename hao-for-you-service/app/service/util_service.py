"""define util function"""
import json
import re

from app.core.config import config


def prompt_template(user_message: str):
    return "\n\n".join([
        config.ModelConfig.system_prompt,
        "使用者問題: " + user_message,
        "請直接回答，不要輸出思考過程。",
    ])


def extract_json(text: str) -> dict:
    """
    從 LLM 輸出中安全地解析 JSON。
    允許輸出前後有雜訊（e.g., markdown code block、多餘說明文字）。
    """
    # 先去除 markdown code block
    text = re.sub(r"```(?:json)?", "", text).strip()

    # 嘗試直接 parse
    try:
        return json.loads(text.strip())
    except json.JSONDecodeError:
        pass

    # 嘗試用 regex 抓出第一個 {...}
    match = re.search(r"\{.*\}", text, re.DOTALL)
    if match:
        try:
            return json.loads(match.group())
        except json.JSONDecodeError:
            pass

    return {}
