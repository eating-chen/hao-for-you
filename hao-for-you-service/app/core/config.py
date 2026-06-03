import os
from app.service.logger_service import logger, error_logger


def _getenv(*keys, default=""):
    """get the first non-empty environment value"""
    for key in keys:
        value = os.getenv(key)
        if value:
            return value.strip()
    return default


class BaseConfig:
    class LineConfig:
        """line bot config"""
        channel_secret = _getenv("LINE_CHANNEL_SECRET")
        channel_access_token = _getenv("LINE_CHANNEL_ACCESS_TOKEN")
        logger.info(
            "LineConfig loaded - channel_secret: %s, channel_access_token: %s",
            bool(channel_secret),
            bool(channel_access_token),
        )

    class DatabaseConfig:
        """database config"""
        database_url = _getenv("DATABASE_URL", default="sqlite+aiosqlite:///./hao.db")
        # 對話記憶保留的最大輪數（1輪 = user + assistant 各一筆）
        max_conversation_turns = 10

    class GoogleConfig:
        """Google OAuth 2.0 config"""
        client_id = _getenv("GOOGLE_CLIENT_ID")
        client_secret = _getenv("GOOGLE_CLIENT_SECRET")
        # 本地開發用 localhost，部署時換成 ngrok 或正式 domain
        redirect_uri = _getenv(
            "GOOGLE_REDIRECT_URI",
            default="http://localhost:8008/auth/google/callback",
        )
        scopes = [
            "https://www.googleapis.com/auth/calendar",
            "https://www.googleapis.com/auth/gmail.readonly",
            "openid",
            "https://www.googleapis.com/auth/userinfo.email",
        ]

    class ModelConfig:
        """model config"""
        system_prompt = (
            "你叫 Hao，是一位 AI 助手\n"
            "請用繁體中文回答，語氣友善、專業、簡潔\n"
            "除非使用者要求，否則不要提供範例、不要重複問題、不要延伸補充\n"
            "你目前能幫助使用者解決的問題如下:\n"
            "1. 提供資訊: 根據使用者的問題，提供相關的資訊\n"
        )
        ollama_model = os.getenv("OLLAMA_MODEL", "qwen3.5:0.8b")
        timeout = 10
        generate_config = {
            'temperature': 0.1,
            'num_predict': 256,
            'top_p': 0.95,
            'repeat_penalty': 1.15,
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
