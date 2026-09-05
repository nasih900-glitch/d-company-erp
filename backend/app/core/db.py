"""Async SQLAlchemy engine + session factory.

Sync engine is exposed for Alembic only.
"""

from __future__ import annotations

from collections.abc import AsyncIterator
from typing import Annotated

from fastapi import Depends, Request
from sqlalchemy import text
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


_CONSISTENT_REPORT_MODULES = frozenset(
    f"app.api.v1.{module}.router"
    for module in ("reports", "finance", "accounting", "analytics", "insights")
)


def _uses_report_snapshot(request: Request) -> bool:
    route = request.scope.get("route")
    # Lazy included routers do not carry inherited OpenAPI tags on the live
    # route object. Use the exact audited endpoint modules, never URL prefixes.
    endpoint = getattr(route, "endpoint", None)
    return request.method == "GET" and (
        getattr(endpoint, "__module__", None) in _CONSISTENT_REPORT_MODULES
    )


async def begin_report_snapshot(session: AsyncSession) -> None:
    """Start a consistent reporting transaction before issuing any data query."""
    await session.execute(text(
        "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY"
    ))


async def get_session(request: Request) -> AsyncIterator[AsyncSession]:
    """FastAPI dependency yielding a session per request.

    Commits on success, rolls back on exception. Service layers should
    NOT commit themselves; the request boundary owns the transaction.
    """
    async with AsyncSessionLocal() as session:
        try:
            if _uses_report_snapshot(request):
                # Set this before tenant/auth lookups acquire the transaction's
                # first snapshot. All financial report queries then see one
                # committed state even while another till completes a sale.
                # Writes retain their existing READ COMMITTED/row-lock logic.
                await begin_report_snapshot(session)
            yield session
            await session.commit()
        except Exception:
            await session.rollback()
            raise


# Commit or roll back before FastAPI sends the response. Otherwise a deferred
# constraint failure can reach the client as a false 2xx success.
SessionDep = Annotated[AsyncSession, Depends(get_session, scope="function")]
