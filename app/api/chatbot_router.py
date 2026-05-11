"""access to FInishInfo data"""
import asyncio
import json
import urllib.request
from fastapi import APIRouter, HTTPException, Request, status, Body
from linebot.v3.exceptions import InvalidSignatureError
from linebot.v3.messaging import (
    ApiClient,
    MessagingApi,
    ReplyMessageRequest,
    TextMessage,
)
from linebot.v3.webhooks import MessageEvent, TextMessageContent

from app.core.config import config
from app.schemas.cahtbot_schema import EchoInfoSchema
from app.service.util_service import prompt_template
from app.service.logger_service import logger, error_logger
from app.manager.modelManager import OllamaModelManager

router = APIRouter()


def register_line_handlers(line_handler, line_config):
    """register line webhook event handlers"""

    @line_handler.add(MessageEvent, message=TextMessageContent)
    def handle_message(event):
        user_message = event.message.text

        reply_text = OllamaModelManager.generate_response(user_message)

        logger.info(f"Line Received message: {user_message}, reply with: {reply_text}")

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
    reply_text = OllamaModelManager.generate_response(prompt)

    return reply_text
