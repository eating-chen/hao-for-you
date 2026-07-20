"""fastapi app entry"""
from contextlib import asynccontextmanager

from fastapi import FastAPI
from app.api.api import router as api_router
from app.core.config import config
from app.service.logger_service import logger
from app.service.chat_service import build_agent
from app.manager.database import db_manager
from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver


@asynccontextmanager
async def lifespan(app: FastAPI):
    """start and shutdown event for FastAPI app"""
    logger.info("Starting FastAPI app...")
    await db_manager.init_db()
    try:
        async with AsyncPostgresSaver.from_conn_string(
            config.DatabaseConfig.DATABASE_URL
        ) as checkpointer:
            if config.DatabaseConfig.SETUP_CHECKPOINTER:
                await checkpointer.setup()
            app.state.checkpointer = checkpointer
            app.state.agent = build_agent(checkpointer)
            yield
    finally:
        await db_manager.disconnect()
        logger.info("Shutting down FastAPI app...")

app = FastAPI(lifespan=lifespan)
app.include_router(api_router)
