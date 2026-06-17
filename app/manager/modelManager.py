from typing import Any

from app.core.config import config
from app.manager.promptManager import PromptManager
from app.service.logger_service import logger, error_logger
# from app.service.google_service import create_calendar_event
from langchain_core.messages import ToolMessage
from app.service.chat_service import run_chat
from app.schemas.cahtbot_schema import ChatRequest, ChatResponse

class OllamaModelManager:
    """Ollama model manager"""

    @staticmethod
    def generate_response(request: Any, body: ChatRequest) -> str:

        # tools = [create_calendar_event]
        # tools_by_name = {item.name: item for item in tools}

        # llm = ChatOllama(
        #     model=config.ModelConfig.ollama_model,
        #     base_url=config.ModelConfig.ollama_host,
        #     temperature=config.ModelConfig.generate_config['temperature'],
        #     num_predict=config.ModelConfig.generate_config['num_predict'],
        #     top_p=config.ModelConfig.generate_config['top_p'],
        #     repeat_penalty=config.ModelConfig.generate_config['repeat_penalty'],
        #     reasoning=config.ModelConfig.generate_config['reasoning'],
        #     validate_model_on_init=True,
        # )
        # llm_with_tools = llm.bind_tools(tools)

        # messages = PromptManager.build_prompt(prompt)

        try:
            answer = run_chat(
                graph=request.app.state.graph,
                user_id=body.app_user_id,
                session_id=body.session_id,
                user_input=body.message,
                platform=body.platform,
                platform_user_id=body.platform_user_id,
                display_name=body.display_name,
            )
        #     response = llm_with_tools.invoke(messages)
        #     print("\n=== FIRST MODEL RESPONSE ===")
        #     print(response.content)
        #     if response.tool_calls:
        #         messages.append(response)
        #         for tool_call in response.tool_calls:
        #             tool_name = tool_call["name"]
        #             selected_tool = tools_by_name.get(tool_name)
        #             if selected_tool is None:
        #                 result = f"Unknown tool: {tool_name}"
        #             else:
        #                 result = selected_tool.invoke(tool_call["args"])

        #             print(f"\n=== EXECUTING TOOL: {tool_name} ===")
        #             print(result)
        #             messages.append(
        #                 ToolMessage(
        #                     content=str(result),
        #                     tool_call_id=tool_call["id"],
        #                 )
        #             )
        #         response = llm_with_tools.invoke(messages)
        #         print("\n=== FINAL MODEL RESPONSE ===")
        #         print(response.content)
            data = ChatResponse(
                app_user_id=body.app_user_id,
                session_id=body.session_id,
                platform=body.platform,
                response=answer,
            )
        except Exception as e:
            error_logger.exception(f"Error calling Ollama API: {e}")
            data = ChatResponse(
                app_user_id=body.app_user_id,
                session_id=body.session_id,
                platform=body.platform,
                response="抱歉，我現在暫時無法連線到 AI 服務，請稍後再試。",
            )
        
        return data
    


    