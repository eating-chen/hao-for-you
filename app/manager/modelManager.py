import json
import urllib.request
from app.core.config import config
from app.service.util_service import prompt_template
from app.service.logger_service import logger, error_logger


class OllamaModelManager:
    """Ollama model manager"""

    @staticmethod
    def generate_response(prompt: str) -> str:

        payload = {
            "model": config.ModelConfig.ollama_model,
            "prompt": prompt_template(prompt),
            "stream": False,
            "raw": False,
            "think": False,
            "options": config.ModelConfig.generate_config
        }

        request = urllib.request.Request(
            config.ModelConfig.ollama_host + "/api/generate",
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            logger.info(
                "Calling Ollama API: host=%s, model=%s",
                config.ModelConfig.ollama_host,
                config.ModelConfig.ollama_model,
                )
            with urllib.request.urlopen(request, timeout=120) as response:
                data = json.loads(response.read().decode("utf-8"))
        except Exception as e:
            error_logger.exception(f"Error calling Ollama API: {e}")
            data = {
                "response": "抱歉，我現在暫時無法連線到 AI 服務，請稍後再試。"
            }
        
        return data.get("response", "")