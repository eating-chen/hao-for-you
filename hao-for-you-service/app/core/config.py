import os
from app.core.prompt_constant import SUMMARY_PROMPT, SYSTEM_PROMPT

class BaseConfig:

    class AgentConfig:
        """Version checkpoint state across incompatible agent changes."""
        state_version = os.getenv("AGENT_STATE_VERSION", "v1")

    class DatabaseConfig:
        """database config"""
        DATABASE_URL = os.getenv(
            "DATABASE_URL",
            "postgresql://haoforyou:haoforyou@localhost:5433/haoforyou?sslmode=disable",
        )
        SQLALCHEMY_DATABASE_URL = DATABASE_URL.replace(
            "postgresql://",
            "postgresql+psycopg://",
            1,
        )
        SETUP_CHECKPOINTER = os.getenv("SETUP_CHECKPOINTER", "true").lower() == "true"

    class ModelConfig:
        """model config"""
        system_prompt = SYSTEM_PROMPT
        summary_prompt = SUMMARY_PROMPT
        ollama_model = os.getenv("OLLAMA_MODEL", "qwen3.5:4b")
        summary_model = os.getenv("SUMMARY_MODEL", ollama_model)
        summary_trigger_messages = int(os.getenv("SUMMARY_TRIGGER_MESSAGES", "30"))
        summary_keep_messages = int(os.getenv("SUMMARY_KEEP_MESSAGES", "20"))
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
