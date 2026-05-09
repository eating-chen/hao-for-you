import asyncio
import os
import configparser
from fastapi import FastAPI, Request, HTTPException
from linebot.v3 import WebhookHandler
from linebot.v3.messaging import (
    Configuration, ApiClient, MessagingApi,
    ReplyMessageRequest, TextMessage,
)
from linebot.v3.webhooks import MessageEvent, TextMessageContent
from linebot.v3.exceptions import InvalidSignatureError
import ollama


def create_app() -> FastAPI:
    config = configparser.ConfigParser()
    config.read('config.ini')

    channel_secret = os.environ.get(
        'LINE_CHANNEL_SECRET',
        config.get('line', 'channel_secret', fallback='')
    )
    channel_access_token = os.environ.get(
        'LINE_CHANNEL_ACCESS_TOKEN',
        config.get('line', 'channel_access_token', fallback='')
    )
    ollama_model = os.environ.get(
        'OLLAMA_MODEL',
        config.get('ollama', 'model', fallback='qwen2.5:14b')
    )

    handler = WebhookHandler(channel_secret)
    line_config = Configuration(access_token=channel_access_token)

    app = FastAPI()

    @app.get('/')
    def index():
        return {
            'message': 'hao-for-you Python server is running',
            'status': 'ok'
        }

    @app.get('/health')
    def health():
        return {'status': 'ok'}

    @handler.add(MessageEvent, message=TextMessageContent)
    def handle_message(event):
        user_message = event.message.text
        response = ollama.chat(
            model=ollama_model,
            messages=[{'role': 'user', 'content': user_message}]
        )
        reply_text = response.message.content

        with ApiClient(line_config) as api_client:
            line_bot_api = MessagingApi(api_client)
            line_bot_api.reply_message(
                ReplyMessageRequest(
                    reply_token=event.reply_token,
                    messages=[TextMessage(text=reply_text)]
                )
            )

    @app.post('/webhook')
    async def webhook(request: Request):
        signature = request.headers.get('X-Line-Signature', '')
        body = await request.body()
        body_text = body.decode('utf-8')

        try:
            await asyncio.to_thread(handler.handle, body_text, signature)
        except InvalidSignatureError:
            raise HTTPException(status_code=400, detail='Invalid signature')

        return 'OK'

    return app


app = create_app()


if __name__ == '__main__':
    import uvicorn

    config = configparser.ConfigParser()
    config.read('config.ini')

    port = int(os.environ.get('PORT', config.get('server', 'port', fallback='8008')))
    host = os.environ.get('HOST', config.get('server', 'host', fallback='0.0.0.0'))
    debug_str = os.environ.get('DEBUG', config.get('server', 'debug', fallback='false'))
    reload = debug_str.lower() in ('1', 'true', 'yes')

    uvicorn.run('app_core:app', host=host, port=port, reload=reload)
