from app.core.config import config
from langchain_core.messages import HumanMessage, SystemMessage, ToolMessage


class PromptManager:

    @staticmethod
    def build_prompt(user_input: str) -> list:
        messages = [
            SystemMessage(
                content=config.ModelConfig.system_prompt
            ),
            HumanMessage(content=user_input),
        ]

        return messages