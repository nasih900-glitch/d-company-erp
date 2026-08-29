"""Application settings.

Loaded from environment variables (12-factor). Never hardcode secrets.
"""

from __future__ import annotations

from functools import lru_cache
from typing import Literal
from uuid import UUID

from pydantic import AnyHttpUrl, Field, PostgresDsn, RedisDsn, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

_ANDROID_COMPATIBILITY_FLOOR_VERSION_CODE = 8


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
    # v8 is the first build that understands authoritative terminal purposes
    # and the explicit Gaming Area -> Cafe POS handoff. Older clients can route
    # a bill to the wrong local shift, so they must be stopped before reaching
    # operational write handlers.
    # Android and the native wire DTO both carry version codes as signed
    # 32-bit integers. Reject an operator typo at server startup instead of
    # advertising JSON that the installed app cannot deserialize.
    android_min_supported_version_code: int = Field(default=8, ge=1, le=2_147_483_647)
    android_latest_version_code: int = Field(default=8, ge=1, le=2_147_483_647)
    android_update_url: AnyHttpUrl | None = None
    android_latest_version_name: str | None = Field(default=None, max_length=80)
    android_update_release_notes: str | None = Field(default=None, max_length=2_000)
    android_update_apk_sha256: str | None = None
    android_update_apk_size_bytes: int | None = Field(
        default=None,
        ge=1,
        le=512 * 1024 * 1024,
    )
    android_update_signing_cert_sha256: str | None = None
    ios_min_supported_version_code: int = Field(default=1, ge=1, le=2_147_483_647)
    ios_latest_version_code: int = Field(default=1, ge=1, le=2_147_483_647)
    ios_update_url: AnyHttpUrl | None = None
    client_update_message: str | None = Field(default=None, max_length=500)
    require_native_version_headers: bool = True
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
                "IOS_LATEST_VERSION_CODE cannot be lower than IOS_MIN_SUPPORTED_VERSION_CODE"
            )
        if self.env in {"prod", "staging"}:
            for label, update_url in (
                ("ANDROID_UPDATE_URL", self.android_update_url),
                ("IOS_UPDATE_URL", self.ios_update_url),
            ):
                if update_url and update_url.scheme != "https":
                    raise ValueError(f"{label} must use HTTPS in prod or staging")
            if (
                max(
                    self.android_min_supported_version_code,
                    self.android_latest_version_code,
                )
                > _ANDROID_COMPATIBILITY_FLOOR_VERSION_CODE
                and self.android_update_url is None
            ):
                raise ValueError(
                    "ANDROID_UPDATE_URL is required before production advertises "
                    "or requires an Android build newer than the code-8 compatibility floor"
                )
        for label, update_url in (
            ("ANDROID_UPDATE_URL", self.android_update_url),
            ("IOS_UPDATE_URL", self.ios_update_url),
        ):
            if update_url and (
                update_url.username is not None
                or update_url.password is not None
                or update_url.fragment is not None
            ):
                raise ValueError(f"{label} must not contain credentials or a URL fragment")
        integrity_fields = (
            self.android_update_apk_sha256,
            self.android_update_apk_size_bytes,
            self.android_update_signing_cert_sha256,
        )
        is_direct_apk_url = bool(
            self.android_update_url and self.android_update_url.path.lower().endswith(".apk")
        )
        if any(value is not None for value in integrity_fields) and not all(
            value is not None for value in integrity_fields
        ):
            raise ValueError(
                "ANDROID_UPDATE_APK_SHA256, ANDROID_UPDATE_APK_SIZE_BYTES and "
                "ANDROID_UPDATE_SIGNING_CERT_SHA256 must be configured together"
            )
        if all(value is not None for value in integrity_fields) and (
            self.android_update_url is None or self.android_latest_version_name is None
        ):
            raise ValueError(
                "Verified Android direct updates also require ANDROID_UPDATE_URL "
                "and ANDROID_LATEST_VERSION_NAME"
            )
        if is_direct_apk_url and (
            not all(value is not None for value in integrity_fields)
            or self.android_latest_version_name is None
        ):
            raise ValueError(
                "An Android .apk update URL requires ANDROID_LATEST_VERSION_NAME "
                "and the complete SHA-256, byte-size and signing-certificate metadata"
            )
        if all(value is not None for value in integrity_fields):
            assert self.android_update_url is not None
            if (
                self.android_update_url.scheme != "https"
                or not self.android_update_url.path.lower().endswith(".apk")
            ):
                raise ValueError("Verified Android direct updates require an HTTPS .apk URL")
        return self

    @field_validator("account_security_company_id", mode="before")
    @classmethod
    def _blank_company_id_is_none(cls, v: object) -> object:
        return None if v == "" else v

    @field_validator(
        "android_update_url",
        "android_latest_version_name",
        "android_update_release_notes",
        "android_update_apk_sha256",
        "android_update_signing_cert_sha256",
        "ios_update_url",
        "client_update_message",
        mode="before",
    )
    @classmethod
    def _blank_optional_text_is_none(cls, v: object) -> object:
        return None if isinstance(v, str) and not v.strip() else v

    @field_validator("android_update_apk_size_bytes", mode="before")
    @classmethod
    def _blank_optional_integer_is_none(cls, v: object) -> object:
        return None if v == "" else v

    @field_validator(
        "android_update_apk_sha256",
        "android_update_signing_cert_sha256",
        mode="after",
    )
    @classmethod
    def _normalize_sha256(cls, v: str | None) -> str | None:
        if v is None:
            return None
        normalized = v.strip().lower().replace(":", "")
        if len(normalized) != 64 or any(char not in "0123456789abcdef" for char in normalized):
            raise ValueError("must contain exactly 64 hexadecimal SHA-256 digits")
        return normalized

    @field_validator("android_latest_version_name", mode="after")
    @classmethod
    def _normalize_android_version_name(cls, v: str | None) -> str | None:
        # PackageManager compares versionName exactly during archive
        # verification. Whitespace copied from an environment/secret editor
        # must not turn an otherwise valid signed update into a false reject.
        return v.strip() if v is not None else None


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """Cached settings accessor. Override in tests with `get_settings.cache_clear()`."""
    return Settings()
