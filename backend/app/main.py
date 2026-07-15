"""FastAPI application entrypoint.

This module exposes the ASGI app. It must not import anything that
performs I/O at import time. All wiring happens inside `create_app`.
"""

from __future__ import annotations

from contextlib import asynccontextmanager
from typing import TYPE_CHECKING

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from redis.asyncio import Redis
from sqlalchemy import text

from app.api.v1.router import api_router
from app.core.config import get_settings
from app.core.db import async_engine
from app.core.errors import register_exception_handlers
from app.core.logging import configure_logging, get_logger
from app.core.middleware import (
    IdempotencyMiddleware,
    RequestBodyLimitMiddleware,
    RequestContextMiddleware,
    TimingMiddleware,
)
from app.services.audit.recorder import install_audit_listeners

if TYPE_CHECKING:
    from collections.abc import AsyncIterator

settings = get_settings()
logger = get_logger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    """Application lifespan — startup and shutdown hooks."""
    configure_logging(settings)
    install_audit_listeners()
    logger.info("erp.startup", env=settings.env, version=app.version)
    # Future: open redis pool, warm caches, register event subscribers.
    yield
    logger.info("erp.shutdown")


def create_app() -> FastAPI:
    """Application factory.

    Kept as a function so tests can build a fresh app with overridden
    settings or dependencies.
    """
    app = FastAPI(
        title="D Company ERP",
        version="1.0.0",
        description="Production-grade café + gaming lounge ERP.",
        docs_url="/docs" if settings.expose_docs else None,
        redoc_url="/redoc" if settings.expose_docs else None,
        openapi_url="/openapi.json" if settings.expose_docs else None,
        lifespan=lifespan,
    )

    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.cors_origins,
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )
    app.add_middleware(RequestContextMiddleware)
    app.add_middleware(TimingMiddleware)
    app.add_middleware(IdempotencyMiddleware)
    app.add_middleware(
        RequestBodyLimitMiddleware,
        max_bytes=settings.max_request_body_bytes,
    )

    register_exception_handlers(app)

    app.include_router(api_router, prefix=settings.api_prefix)

    @app.get("/healthz", tags=["meta"])
    async def healthz() -> dict[str, str]:
        return {"status": "ok"}

    @app.get("/readyz", tags=["meta"])
    async def readyz() -> dict[str, object]:
        checks: dict[str, str] = {}
        try:
            async with async_engine.connect() as conn:
                await conn.execute(text("select 1"))
        except Exception as exc:
            logger.warning("erp.readyz_failed", dependency="database", error=str(exc))
            checks["database"] = "down"
        else:
            checks["database"] = "ok"

        redis = Redis.from_url(
            str(settings.redis_url),
            decode_responses=True,
            socket_connect_timeout=2,
            socket_timeout=2,
        )
        try:
            await redis.ping()
        except Exception as exc:
            logger.warning("erp.readyz_failed", dependency="redis", error=str(exc))
            checks["redis"] = "down"
        else:
            checks["redis"] = "ok"
        finally:
            await redis.aclose()

        if "down" in checks.values():
            raise HTTPException(
                status_code=503,
                detail={
                    "status": "not_ready",
                    "checks": checks,
                },
            )
        return {"status": "ready", "checks": checks}

    return app


app = create_app()
