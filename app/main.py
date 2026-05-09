"""fastapi app entry"""
from contextlib import asynccontextmanager

from fastapi import FastAPI
from linebot.v3 import WebhookHandler
from linebot.v3.messaging import Configuration

from app.api import chatbot_router
from app.api.api import router as api_router
from app.core.config import config
from app.service.logger_service import logger


@asynccontextmanager
async def lifespan(app: FastAPI):
    """start and shutdown event for FastAPI app"""
    logger.info("Starting FastAPI app...")
    yield
    logger.info("Shutting down FastAPI app...")

app = FastAPI(lifespan=lifespan)
app.state.line_handler = WebhookHandler(config.LineConfig.channel_secret)
app.state.line_config = Configuration(
    access_token=config.LineConfig.channel_access_token
)

chatbot_router.register_line_handlers(
    app.state.line_handler,
    app.state.line_config,
)
app.include_router(api_router)
