"""access to FInishInfo data"""
import asyncio
from fastapi import APIRouter, HTTPException, Request, status, Body
from linebot.v3.exceptions import InvalidSignatureError
from linebot.v3.messaging import (
    ApiClient,
    MessagingApi,
    ReplyMessageRequest,
    TextMessage,
)
from linebot.v3.webhooks import MessageEvent, TextMessageContent

from app.schemas.cahtbot_schema import EchoInfoSchema
from app.service.util_service import prompt_template
from app.service.logger_service import logger
from app.manager.modelManager import llm
from app.manager.intentManager import route_intent
from app.skills.registry import SkillRegistry
from app.skills.base_skill import SkillContext
from app.db.database import AsyncSessionLocal
from app.db.crud.user_crud import get_or_create_user
from app.db.crud.conversation_crud import get_recent_messages, add_message
import app.skills  # noqa: F401 — 觸發 SkillRegistry 的 register

router = APIRouter()


async def _process_message(user_id: str, message: str) -> str:
    """意圖路由 + skill 執行的主要邏輯（async）"""
    async with AsyncSessionLocal() as db:
        await get_or_create_user(db, user_id)
        history = await get_recent_messages(db, user_id, limit=20)

    skill_name, params = route_intent(message, history)
    skill = SkillRegistry.get_by_name(skill_name) or SkillRegistry.get_by_name("general_chat")

    ctx = SkillContext(
        user_id=user_id,
        message=message,
        conversation_history=history,
        extracted_params=params,
    )
    result = await skill.execute(ctx)
    reply_text = result.reply_text

    async with AsyncSessionLocal() as db:
        await add_message(db, user_id, "user", message)
        await add_message(db, user_id, "assistant", reply_text, skill_used=skill_name)

    return reply_text


# Register LINE webhook event handlers
def register_line_handlers(line_handler, line_config):
    """register line webhook event handlers"""

    @line_handler.add(MessageEvent, message=TextMessageContent)
    def handle_message(event):
        user_id = event.source.user_id
        user_message = event.message.text

        # LINE handler 是 sync，用 asyncio.run() 在此 thread 建立獨立 event loop
        reply_text = asyncio.run(_process_message(user_id, user_message))

        logger.info("LINE user=%s msg=%s reply=%s", user_id, user_message, reply_text[:50])

        with ApiClient(line_config) as api_client:
            line_bot_api = MessagingApi(api_client)
            line_bot_api.reply_message(
                ReplyMessageRequest(
                    reply_token=event.reply_token,
                    messages=[TextMessage(text=reply_text)],
                )
            )


@router.get(
    "/",
    status_code=status.HTTP_200_OK,
    tags=["Health Check"],
    summary="FastAPI server 健康狀況"
)
def health_check():

    return {
        'message': 'hao-for-you Python server is running',
        'status': 'ok'
    }


@router.post(
    "/echo",
    status_code=status.HTTP_200_OK,
    response_model=EchoInfoSchema,
    tags=["finish_info"],
    summary="echo API，回傳收到的資料和訊息"
)
def echo(prompt: str):

    return {
        'received': prompt,
        'message': 'Echo from hao-for-you server'
    }


@router.post(
    "/webhook",
    status_code=status.HTTP_200_OK,
    tags=["line"],
    summary="LINE webhook callback"
)
async def webhook(request: Request):
    signature = request.headers.get("X-Line-Signature", "")
    body = await request.body()
    body_text = body.decode("utf-8")

    try:
        await asyncio.to_thread(
            request.app.state.line_handler.handle,
            body_text,
            signature,
        )
    except InvalidSignatureError:
        raise HTTPException(status_code=400, detail="Invalid signature")

    return "OK"


@router.post(
    "/test_ollama",
    status_code=status.HTTP_200_OK,
    tags=["ollama"],
    summary="測試 Ollama model 是否可以回應"
)
def test_ollama(prompt: str = Body(..., embed=True)):
    """測試 Ollama model 是否可以回應"""
    reply_text = llm.generate(prompt_template(prompt))

    return reply_text
