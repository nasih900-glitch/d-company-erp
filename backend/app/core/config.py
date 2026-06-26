"""Application settings.

Loaded from environment variables (12-factor). Never hardcode secrets.
"""

from __future__ import annotations

from functools import lru_cache
from typing import Literal

from pydantic import Field, PostgresDsn, RedisDsn, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )

    # ----- runtime -----
    env: Literal["dev", "staging", "prod", "test"] = "dev"
    api_prefix: str = "/api/v1"
    expose_docs: bool = True
    log_level: Literal["DEBUG", "INFO", "WARNING", "ERROR"] = "INFO"
    log_format: Literal["json", "console"] = "json"

    # ----- database -----
    database_url: PostgresDsn = Field(
        default="postgresql+psycopg://erp:erp@localhost:5432/erp",  # type: ignore[arg-type]
        description="SQLAlchemy DSN; use psycopg (sync) for migrations and asyncpg for app.",
    )
    database_pool_size: int = 10
    database_max_overflow: int = 20
    database_echo: bool = False

    # ----- redis / queue -----
    redis_url: RedisDsn = Field(default="redis://localhost:6379/0")  # type: ignore[arg-type]

    # ----- security -----
    jwt_secret: str = Field(
        default="CHANGE_ME_IN_PROD",
        min_length=16,
        description="HS256 dev fallback; use RS256 keypair in prod via jwt_private_key.",
    )
    jwt_algorithm: Literal["HS256", "RS256"] = "HS256"
    jwt_private_key: str | None = None
    jwt_public_key: str | None = None
    access_token_minutes: int = 15
    refresh_token_days: int = 7
    password_min_length: int = 10
    failed_login_lockout_threshold: int = 5
    failed_login_lockout_minutes: int = 15

    # ----- cors -----
    cors_origins: list[str] = Field(default_factory=lambda: ["http://localhost:5173"])

    # ----- object storage -----
    s3_endpoint_url: str | None = None  # MinIO in dev; None for AWS default
    s3_region: str = "us-east-1"
    s3_bucket_uploads: str = "erp-uploads"
    s3_access_key: str | None = None
    s3_secret_key: str | None = None

    # ----- ocr -----
    ocr_provider: Literal["tesseract", "google_vision", "aws_textract"] = "tesseract"
    ocr_confidence_threshold: float = 0.75

    # ----- multi-tenant defaults -----
    default_currency: str = "INR"
    default_currency_minor_units: int = 100  # paise per rupee
    default_timezone: str = "Asia/Kolkata"

    @field_validator("jwt_secret")
    @classmethod
    def _warn_default_secret(cls, v: str) -> str:
        # In prod, this should be set from a secret manager.
        return v


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """Cached settings accessor. Override in tests with `get_settings.cache_clear()`."""
    return Settings()
