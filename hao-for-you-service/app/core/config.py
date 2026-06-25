import os
from app.core.prompt_constant import SYSTEM_PROMPT

class BaseConfig:

    # class GoogleCalendarConfig:
    #     SCOPES = ["https://www.googleapis.com/auth/calendar.events"]
    #     TOKEN_FILE = Path(os.getenv("GOOGLE_TOKEN_FILE", ".data/google_calendar_minimal_token.json"))
    #     CREDENTIALS_FILE = Path(os.getenv("DEFAULT_CREDENTIALS_FILE", ""))
    #     TIMEZONE = "Asia/Taipei"
    # class LineConfig:
    #     """line bot config"""
    #     channel_secret = os.getenv("LINE_CHANNEL_SECRET", "channel_secret")
    #     channel_access_token = os.getenv(
    #         "LINE_CHANNEL_ACCESS_TOKEN",
    #         "channel_access_token",
    #     )

    class DatabaseConfig:
        """database config"""
        DB_PATH = "chat_memory.db"
        DATABASE_URL = f"sqlite+aiosqlite:///{DB_PATH}"

    class ModelConfig:
        """model config"""
        system_prompt = SYSTEM_PROMPT
        ollama_model = os.getenv("OLLAMA_MODEL", "qwen3.5:4b")
        timeout = 10
        generate_config = {
            'temperature': 0.1,
            'num_predict': 256,
            'top_p': 0.95,
            'repeat_penalty': 1.15,
            'reasoning': False,
        }


class DevConfig(BaseConfig):
    """dev config"""

    class ModelConfig(BaseConfig.ModelConfig):
        """model config for local development"""
        ollama_host = os.getenv("OLLAMA_BASE_URL", "http://localhost:11434")


class ProdConfig(BaseConfig):
    """prod config"""

    class ModelConfig(BaseConfig.ModelConfig):
        """model config for docker production"""
        ollama_host = os.getenv("OLLAMA_BASE_URL", "http://ollama:11434")


def get_config():
    """get config based on ENV"""
    env = os.getenv('ENV', 'dev')
    if env == 'prod':
        return ProdConfig()
    return DevConfig()


config = get_config()
