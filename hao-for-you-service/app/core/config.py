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

        # "ollama" (local dev, no AWS needed) or "bedrock" (Amazon Bedrock)
        provider = os.getenv("MODEL_PROVIDER", "ollama")

        ollama_model = os.getenv("OLLAMA_MODEL", "qwen3.5:4b")
        bedrock_model_id = os.getenv("BEDROCK_MODEL_ID", "")
        aws_region = os.getenv("AWS_REGION", "us-east-1")

        # Model identifier for whichever provider is active.
        model_name = bedrock_model_id if provider == "bedrock" else ollama_model
        summary_model = os.getenv("SUMMARY_MODEL", model_name)

        summary_trigger_messages = int(os.getenv("SUMMARY_TRIGGER_MESSAGES", "30"))
        summary_keep_messages = int(os.getenv("SUMMARY_KEEP_MESSAGES", "20"))
        timeout = 10
        generate_config = {
            'temperature': 0.1,
            'max_tokens': 256,
            'top_p': 0.95,
            # Ollama-only sampling knobs; ignored when provider="bedrock"
            'repeat_penalty': 1.15,
            'reasoning': False,
        }

    class GatewayConfig:
        """hao-for-you-gateway connection settings."""
        base_url = os.getenv("GATEWAY_BASE_URL", "http://localhost:8008")
        timeout_seconds = float(os.getenv("GATEWAY_TIMEOUT_SECONDS", "10"))

    class SecurityConfig:
        """Shared-secret settings for internal service-to-service HMAC auth."""
        # 與 hao-for-you-gateway 共享同一把固定密鑰
        internal_api_secret = os.getenv("INTERNAL_API_SECRET", "")
        internal_api_timestamp_window = int(
            os.getenv("INTERNAL_API_TIMESTAMP_WINDOW", "300")
        )


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
