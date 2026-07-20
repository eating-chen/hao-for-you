from typing import Any

from app.schemas.cahtbot_schema import ChatRequest, ChatResponse
from app.service.chat_service import run_chat
from app.service.logger_service import error_logger


class OllamaModelManager:
    """Adapt the agent response to the existing API contract."""

    @staticmethod
    async def generate_response(request: Any, body: ChatRequest) -> ChatResponse:
        session_id = body.session_id or f"{body.platform}-{body.user_id}"

        try:
            result = await run_chat(
                agent=request.app.state.agent,
                user_id=body.user_id,
                session_id=session_id,
                user_input=body.message,
                platform=body.platform,
            )
            return ChatResponse(
                success=True,
                user_id=body.user_id,
                session_id=session_id,
                platform=body.platform,
                response=result.response,
                tool_executions=[
                    {
                        "name": execution.name,
                        "arguments": execution.arguments,
                        "status": execution.status,
                        "result": execution.result,
                    }
                    for execution in result.tool_executions
                ],
            )
        except Exception:
            error_logger.exception("Error executing chat agent")
            return ChatResponse(
                success=False,
                user_id=body.user_id,
                session_id=session_id,
                platform=body.platform,
                response="抱歉，我現在暫時無法連線到 AI 服務，請稍後再試。",
                tool_executions=[],
            )
