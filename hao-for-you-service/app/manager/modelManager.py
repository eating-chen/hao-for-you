"""LLM provider abstraction layer"""
import json
import urllib.request
from abc import ABC, abstractmethod

from app.core.config import config
from app.service.logger_service import logger, error_logger


# ──────────────────────────────────────────────
# Abstract base
# ──────────────────────────────────────────────

class BaseLLMProvider(ABC):
    """所有 LLM provider 的抽象介面"""

    @abstractmethod
    def generate(self, prompt: str) -> str:
        """單輪生成：prompt → response string"""

    @abstractmethod
    def chat(self, messages: list[dict]) -> str:
        """多輪對話：messages 格式為 [{"role": "user/assistant/system", "content": "..."}]"""


# ──────────────────────────────────────────────
# Ollama implementation
# ──────────────────────────────────────────────

class OllamaProvider(BaseLLMProvider):
    """Ollama 本地 LLM provider"""

    def _post(self, endpoint: str, payload: dict) -> dict:
        """發送 HTTP POST 到 Ollama，回傳 parsed JSON"""
        url = config.ModelConfig.ollama_host + endpoint
        request = urllib.request.Request(
            url,
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(request, timeout=120) as response:
            return json.loads(response.read().decode("utf-8"))

    def generate(self, prompt: str) -> str:
        """
        使用 /api/generate（單輪）
        適合：意圖分類、參數萃取等需要結構化輸出的場景
        """
        payload = {
            "model": config.ModelConfig.ollama_model,
            "prompt": prompt,
            "stream": False,
            "raw": False,
            "think": False,
            "options": config.ModelConfig.generate_config,
        }
        try:
            logger.info(
                "Ollama generate: host=%s, model=%s",
                config.ModelConfig.ollama_host,
                config.ModelConfig.ollama_model,
            )
            data = self._post("/api/generate", payload)
            return data.get("response", "")
        except Exception as e:
            error_logger.exception("Ollama generate error: %s", e)
            return "抱歉，我現在暫時無法連線到 AI 服務，請稍後再試。"

    def chat(self, messages: list[dict]) -> str:
        """
        使用 /api/chat（多輪對話）
        messages 格式：[{"role": "system|user|assistant", "content": "..."}]
        適合：帶有對話歷史的一般聊天
        """
        payload = {
            "model": config.ModelConfig.ollama_model,
            "messages": messages,
            "stream": False,
            "think": False,
            "options": config.ModelConfig.generate_config,
        }
        try:
            logger.info(
                "Ollama chat: host=%s, model=%s, turns=%d",
                config.ModelConfig.ollama_host,
                config.ModelConfig.ollama_model,
                len(messages),
            )
            data = self._post("/api/chat", payload)
            return data.get("message", {}).get("content", "")
        except Exception as e:
            error_logger.exception("Ollama chat error: %s", e)
            return "抱歉，我現在暫時無法連線到 AI 服務，請稍後再試。"


# ──────────────────────────────────────────────
# Singleton（全域共用同一個 provider）
# ──────────────────────────────────────────────

llm: BaseLLMProvider = OllamaProvider()
