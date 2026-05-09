"""define util function"""
from app.core.config import config


def prompt_template(user_message: str):

    return "\n\n".join([
        config.ModelConfig.system_prompt,
        "使用者問題: " + user_message,
        "請直接回答，不要輸出思考過程。",
    ])
