"""Application exception hierarchy + FastAPI handlers."""

from __future__ import annotations

from typing import Any

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.core.logging import get_logger

log = get_logger(__name__)


class AppError(Exception):
    """Base for all application errors."""

    status_code: int = 500
    code: str = "internal_error"

    def __init__(
        self,
        message: str,
        *,
        details: dict[str, Any] | None = None,
        headers: dict[str, str] | None = None,
    ) -> None:
        super().__init__(message)
        self.message = message
        self.details = details or {}
        self.headers = headers or {}


class NotFoundError(AppError):
    status_code = 404
    code = "not_found"


class ConflictError(AppError):
    status_code = 409
    code = "conflict"


class ClientTelemetryCapacityError(ConflictError):
    """Immutable client evidence reached a configured admission ceiling."""

    code = "client_telemetry_capacity"


class ClientTelemetryIdentityConflictError(ConflictError):
    """Native identity headers disagree with the installation heartbeat body."""

    code = "client_telemetry_identity_conflict"


class DiagnosticIdempotencyConflictError(ConflictError):
    """A diagnostic event UUID was reused with a different immutable payload."""

    code = "diagnostic_idempotency_conflict"


class DiagnosticIngestRetryError(ConflictError):
    """Diagnostic admission raced with another writer and is safe to retry."""

    code = "diagnostic_ingest_retry"

    def __init__(self, message: str) -> None:
        super().__init__(
            message,
            details={"retry_after_seconds": 1},
            headers={"Retry-After": "1"},
        )


class CheckoutClaimRequiredError(ConflictError):
    code = "checkout_claim_required"


class CheckoutClaimConflictError(ConflictError):
    code = "checkout_claim_conflict"


class CheckoutClaimExpiredError(ConflictError):
    code = "checkout_claim_expired"


class CheckoutClaimInvalidError(ConflictError):
    code = "checkout_claim_invalid"


class CheckoutClaimStaleError(ConflictError):
    code = "checkout_claim_stale"


class CheckoutClaimUnavailableError(ConflictError):
    code = "checkout_claim_unavailable"


class ValidationError(AppError):
    status_code = 422
    code = "validation_error"


class AuthError(AppError):
    status_code = 401
    code = "unauthorized"


class ForbiddenError(AppError):
    status_code = 403
    code = "forbidden"


class IdempotencyConflict(AppError):
    status_code = 409
    code = "idempotency_conflict"


class IdempotencyInProgress(AppError):
    """The same request may still be committing; retry with the same key."""

    status_code = 409
    code = "idempotency_in_progress"


class TenantViolation(AppError):
    status_code = 403
    code = "tenant_violation"


class BusinessRuleError(AppError):
    """Domain rule rejected the operation (e.g. negative stock, closed shift)."""

    status_code = 422
    code = "business_rule"


class GamingSourceShiftClosedError(BusinessRuleError):
    """A stopped gaming bill needs protected-owner shift reconciliation."""

    code = "gaming_source_shift_closed"


class GamingBillingRepairRequiredError(ConflictError):
    """An ended/corrupt gaming row must not silently become a zero bill."""

    code = "gaming_billing_repair_required"


class GamingExtensionNotAppliedError(ConflictError):
    """Proof that one scoped extension action has no immutable charge receipt."""

    code = "gaming_extension_not_applied"


class GamingLegacyServerSessionNotFoundError(BusinessRuleError):
    """A recovery probe conclusively found no accepted server Start.

    Unlike ambiguous or contradictory evidence, this rolled-back outcome is
    safe for the protected owner to replace with a manual-bill or no-play
    decision using a fresh request body for the same retained local action.
    """

    code = "gaming_legacy_server_session_not_found"


class GamingLegacyStopOwnerReviewRequiredError(BusinessRuleError):
    """A retained hourly Stop predates the accepted server Start timestamp."""

    code = "gaming_legacy_stop_owner_review_required"


class RateLimitError(AppError):
    status_code = 429
    code = "rate_limited"


class ServiceUnavailableError(AppError):
    status_code = 503
    code = "service_unavailable"


def _validation_field(location: tuple[Any, ...]) -> str:
    parts: list[str] = []
    for raw in location:
        if raw in {"body", "query", "path", "header", "cookie"} and not parts:
            continue
        if isinstance(raw, int):
            if parts:
                parts[-1] = f"{parts[-1]}[{raw}]"
            else:
                parts.append(f"[{raw}]")
        else:
            parts.append(str(raw))
    return ".".join(parts) or "request"


def _validation_message(error: dict[str, Any], field: str) -> str:
    kind = str(error.get("type") or "")
    context = error.get("ctx") if isinstance(error.get("ctx"), dict) else {}
    label = field.replace("_", " ")
    if kind == "missing":
        return f"{label} is required."
    if kind == "string_too_long" and context.get("max_length") is not None:
        return f"{label} must be at most {context['max_length']} characters."
    if kind == "string_too_short" and context.get("min_length") is not None:
        return f"{label} must be at least {context['min_length']} characters."
    if kind == "greater_than_equal" and context.get("ge") is not None:
        return f"{label} must be at least {context['ge']}."
    if kind == "less_than_equal" and context.get("le") is not None:
        return f"{label} must be at most {context['le']}."
    if kind == "uuid_parsing":
        return f"{label} must be a valid identifier."
    raw = str(error.get("msg") or "is invalid").removeprefix("Value error, ")
    return f"{label}: {raw.rstrip('.')}."


def request_validation_details(errors: list[dict[str, Any]]) -> tuple[str, list[dict[str, str]]]:
    """Return a field-aware envelope without echoing submitted values.

    FastAPI's default validation response includes the rejected ``input``.
    That may be a password, token or customer detail, so the application
    deliberately exposes only field, constraint type and a safe explanation.
    """
    issues: list[dict[str, str]] = []
    for error in errors:
        field = _validation_field(tuple(error.get("loc") or ()))
        issues.append(
            {
                "field": field,
                "type": str(error.get("type") or "invalid"),
                "message": _validation_message(error, field),
            }
        )
    if not issues:
        issues.append(
            {
                "field": "request",
                "type": "invalid",
                "message": "The request contains an invalid value.",
            }
        )
    summary = " ".join(issue["message"] for issue in issues[:3])
    if len(issues) > 3:
        summary += f" Check {len(issues) - 3} more highlighted fields."
    return summary, issues


def register_exception_handlers(app: FastAPI) -> None:
    @app.exception_handler(RequestValidationError)
    async def _handle_request_validation(
        request: Request,
        exc: RequestValidationError,
    ) -> JSONResponse:
        message, fields = request_validation_details(exc.errors())
        log.warning(
            "request.validation_error",
            method=request.method,
            path=request.url.path,
            fields=[{"field": row["field"], "type": row["type"]} for row in fields],
        )
        return JSONResponse(
            status_code=422,
            content={
                "error": {
                    "code": "validation_error",
                    "message": message,
                    "details": {"fields": fields},
                }
            },
        )

    @app.exception_handler(AppError)
    async def _handle_app_error(_: Request, exc: AppError) -> JSONResponse:
        log.warning("app.error", code=exc.code, msg=exc.message, details=exc.details)
        return JSONResponse(
            status_code=exc.status_code,
            headers=exc.headers,
            content={
                "error": {
                    "code": exc.code,
                    "message": exc.message,
                    "details": exc.details,
                }
            },
        )

    @app.exception_handler(Exception)
    async def _handle_unexpected(_: Request, exc: Exception) -> JSONResponse:
        log.exception("app.unexpected", exc_info=exc)
        return JSONResponse(
            status_code=500,
            content={
                "error": {
                    "code": "internal_error",
                    "message": "An unexpected error occurred.",
                }
            },
        )
