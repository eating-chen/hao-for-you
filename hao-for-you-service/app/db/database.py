"""SQLAlchemy async engine and session factory"""
from sqlalchemy.ext.asyncio import (
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)
from sqlalchemy.orm import DeclarativeBase

from app.core.config import config
from app.service.logger_service import logger


engine = create_async_engine(
    config.DatabaseConfig.database_url,
    echo=False,
    future=True,
)

AsyncSessionLocal = async_sessionmaker(
    bind=engine,
    class_=AsyncSession,
    expire_on_commit=False,
)


class Base(DeclarativeBase):
    pass


async def init_db() -> None:
    """建立所有 table（首次啟動時執行）"""
    from app.db import models  # noqa: F401 — 確保 models 被 import，metadata 才會有 table 資訊

    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    logger.info("Database initialized.")


async def get_db():
    """FastAPI dependency：提供 async session"""
    async with AsyncSessionLocal() as session:
        yield session
