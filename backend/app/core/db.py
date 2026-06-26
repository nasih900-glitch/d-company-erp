"""Async SQLAlchemy engine + session factory.

Sync engine is exposed for Alembic only.
"""

from __future__ import annotations

from collections.abc import AsyncIterator
from typing import Annotated

from fastapi import Depends
from sqlalchemy.ext.asyncio import (
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)

from app.core.config import get_settings


def _async_url(dsn: str) -> str:
    # Translate psycopg sync DSN to asyncpg if needed.
    return dsn.replace("postgresql+psycopg://", "postgresql+asyncpg://").replace(
        "postgresql://", "postgresql+asyncpg://"
    )


_settings = get_settings()
async_engine = create_async_engine(
    _async_url(str(_settings.database_url)),
    pool_size=_settings.database_pool_size,
    max_overflow=_settings.database_max_overflow,
    echo=_settings.database_echo,
    pool_pre_ping=True,
)

AsyncSessionLocal = async_sessionmaker(
    bind=async_engine,
    expire_on_commit=False,
    autoflush=False,
    autocommit=False,
)


async def get_session() -> AsyncIterator[AsyncSession]:
    """FastAPI dependency yielding a session per request.

    Commits on success, rolls back on exception. Service layers should
    NOT commit themselves; the request boundary owns the transaction.
    """
    async with AsyncSessionLocal() as session:
        try:
            yield session
            await session.commit()
        except Exception:
            await session.rollback()
            raise


SessionDep = Annotated[AsyncSession, Depends(get_session)]
