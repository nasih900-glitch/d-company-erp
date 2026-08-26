"""Application settings.

Loaded from environment variables (12-factor). Never hardcode secrets.
"""

from __future__ import annotations

from functools import lru_cache
from typing import Literal
from uuid import UUID

from pydantic import AnyHttpUrl, Field, PostgresDsn, RedisDsn, field_validator, model_validator
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
        default="CHANGE_ME_IN_PROD_AT_LEAST_32_CHARS",
        min_length=32,
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
    account_security_email: str = "business@retrocafe.online"
    account_security_company_id: UUID | None = None
    account_otp_ttl_minutes: int = Field(default=10, ge=5, le=30)
    account_otp_max_attempts: int = Field(default=5, ge=3, le=10)
    account_otp_request_limit: int = Field(default=3, ge=1, le=10)
    login_ip_limit_per_minute: int = Field(default=30, ge=5, le=300)
    login_identity_limit_per_15_minutes: int = Field(default=10, ge=5, le=100)
    max_request_body_bytes: int = Field(
        default=25 * 1024 * 1024,
        ge=1024,
        le=100 * 1024 * 1024,
    )
    # Native release compatibility is server-controlled. Raising the minimum
    # lets operations block a build whose local schema/API contract is no
    # longer safe, without shipping another already-obsolete APK first.
    android_min_supported_version_code: int = Field(default=1, ge=1)
    android_latest_version_code: int = Field(default=2, ge=1)
    android_update_url: AnyHttpUrl | None = None
    ios_min_supported_version_code: int = Field(default=1, ge=1)
    ios_latest_version_code: int = Field(default=1, ge=1)
    ios_update_url: AnyHttpUrl | None = None
    client_update_message: str | None = Field(default=None, max_length=500)
    require_native_version_headers: bool = False
    # A shared Tables/Gaming bill is leased while one POS device confirms and
    # collects payment. Ten minutes covers a realistic cash/UPI interaction;
    # clients still fail closed and reacquire after expiry or any bill change.
    checkout_claim_ttl_seconds: int = Field(default=600, ge=30, le=600)

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

    @model_validator(mode="after")
    def _enforce_prod_secret(self) -> "Settings":
        # Fail closed: a prod/staging boot must never fall back to the public
        # default HS256 secret, or tokens become forgeable by anyone.
        if self.env in {"prod", "staging"} and self.jwt_algorithm == "HS256":
            if self.jwt_secret.startswith("CHANGE_ME") or len(self.jwt_secret) < 32:
                raise ValueError(
                    "JWT_SECRET must be set to a strong non-default value "
                    "(>=32 chars) when ENV is prod or staging"
                )
        if self.android_latest_version_code < self.android_min_supported_version_code:
            raise ValueError(
                "ANDROID_LATEST_VERSION_CODE cannot be lower than "
                "ANDROID_MIN_SUPPORTED_VERSION_CODE"
            )
        if self.ios_latest_version_code < self.ios_min_supported_version_code:
            raise ValueError(
                "IOS_LATEST_VERSION_CODE cannot be lower than "
                "IOS_MIN_SUPPORTED_VERSION_CODE"
            )
        if self.env in {"prod", "staging"}:
            for label, update_url in (
                ("ANDROID_UPDATE_URL", self.android_update_url),
                ("IOS_UPDATE_URL", self.ios_update_url),
            ):
                if update_url and update_url.scheme != "https":
                    raise ValueError(f"{label} must use HTTPS in prod or staging")
        return self

    @field_validator("account_security_company_id", mode="before")
    @classmethod
    def _blank_company_id_is_none(cls, v: object) -> object:
        return None if v == "" else v

    @field_validator(
        "android_update_url",
        "ios_update_url",
        "client_update_message",
        mode="before",
    )
    @classmethod
    def _blank_optional_text_is_none(cls, v: object) -> object:
        return None if v == "" else v


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """Cached settings accessor. Override in tests with `get_settings.cache_clear()`."""
    return Settings()
