from contextlib import asynccontextmanager

from sqlalchemy.ext.asyncio import AsyncSession, create_async_engine
from sqlalchemy.orm import sessionmaker
from app.models.base import Base
from app.core.config import config


class DatabaseManager:
    """Async PostgreSQL database manager for application tables."""

    def __init__(self, db_url: str):
        try:
            self.engine = create_async_engine(db_url)
            self.session = sessionmaker(
                bind=self.engine,
                class_=AsyncSession,
                expire_on_commit=False,
            )
        except Exception as exc:
            raise Exception(f"Failed to connect to database: {str(exc)}") from exc

    async def connect(self):
        """For structural purposes, this method is not implemented in this class."""
        pass

    async def init_db(self):
        # Register the existing application models before creating metadata.
        from app.models import conversation_messages  # noqa: F401
        from app.models import conversation_sessions  # noqa: F401
        from app.models import user  # noqa: F401

        async with self.engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)

    @asynccontextmanager
    async def get_session(self):
        """Get a database session."""
        async with self.session() as session:
            try:
                yield session
            except Exception as exc:
                await session.rollback()
                raise Exception(f"Failed to get session: {str(exc)}") from exc

    async def disconnect(self):
        """Close database connections."""
        try:
            await self.engine.dispose()
        except Exception as exc:
            raise Exception(f"Failed to disconnect from database: {str(exc)}") from exc


db_manager = DatabaseManager(config.DatabaseConfig.SQLALCHEMY_DATABASE_URL)
