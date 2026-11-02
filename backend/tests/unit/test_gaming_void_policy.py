from datetime import UTC, datetime, timedelta
from types import SimpleNamespace

import pytest

from app.api.v1.gaming.router import (
    SessionCancel,
    _VOID_REASON_REQUIRED_AFTER_MS,
    _VOID_WITHOUT_USER_REASON,
    _session_void_elapsed_ms,
    _void_reason_policy,
    _void_request_reason_matches,
)
from app.core.errors import BusinessRuleError
from app.core.roles import has_owner_role


def _session(*, status: str, start_at: datetime, end_at: datetime | None = None):
    return SimpleNamespace(
        status=status,
        start_at=start_at,
        end_at=end_at,
        paused_at=None,
        paused_duration_ms=0,
    )


def test_void_elapsed_and_reason_policy_use_only_verifiable_server_facts() -> None:
    started_at = datetime(2026, 10, 26, 8, 0, tzinfo=UTC)
    active = _session(status="active", start_at=started_at)

    below = _session_void_elapsed_ms(
        active,
        server_now=started_at + timedelta(milliseconds=_VOID_REASON_REQUIRED_AFTER_MS - 1),
    )
    assert below == _VOID_REASON_REQUIRED_AFTER_MS - 1
    assert _void_reason_policy(SessionCancel(), elapsed_ms=below) == (
        _VOID_WITHOUT_USER_REASON,
        False,
    )

    at_boundary = _session_void_elapsed_ms(
        active,
        server_now=started_at + timedelta(milliseconds=_VOID_REASON_REQUIRED_AFTER_MS),
    )
    with pytest.raises(BusinessRuleError, match="reached 30 minutes"):
        _void_reason_policy(SessionCancel(), elapsed_ms=at_boundary)
    assert _void_reason_policy(
        SessionCancel(reason="  Customer requested correction  "),
        elapsed_ms=at_boundary,
    ) == ("Customer requested correction", True)


@pytest.mark.parametrize(
    "candidate",
    [
        _session(
            status="ended",
            start_at=datetime(2026, 10, 26, 8, 0, tzinfo=UTC),
            end_at=None,
        ),
        _session(
            status="cancelled",
            start_at=datetime(2026, 10, 26, 8, 0, tzinfo=UTC),
            end_at=None,
        ),
        _session(
            status="ended",
            start_at=datetime(2026, 10, 26, 8, 0, tzinfo=UTC),
            end_at=datetime(2026, 10, 26, 7, 59, tzinfo=UTC),
        ),
        _session(
            status="paused",
            start_at=datetime(2026, 10, 26, 8, 0, tzinfo=UTC),
        ),
        SimpleNamespace(
            status="paused",
            start_at=datetime(2026, 10, 26, 8, 0, tzinfo=UTC),
            end_at=None,
            paused_at=datetime(2026, 10, 26, 7, 59, tzinfo=UTC),
            paused_duration_ms=0,
        ),
        SimpleNamespace(
            status="paused",
            start_at=datetime(2026, 10, 26, 8, 0, tzinfo=UTC),
            end_at=None,
            paused_at=datetime(2026, 10, 26, 8, 1),
            paused_duration_ms=0,
        ),
        SimpleNamespace(
            status="paused",
            start_at=datetime(2026, 10, 26, 8, 0, tzinfo=UTC),
            end_at=None,
            paused_at=datetime(2026, 10, 26, 8, 1, tzinfo=UTC),
            paused_duration_ms=60_001,
        ),
        SimpleNamespace(
            status="ended",
            start_at=datetime(2026, 10, 26, 8, 0, tzinfo=UTC),
            end_at=datetime(2026, 10, 26, 8, 1, tzinfo=UTC),
            paused_at=None,
            paused_duration_ms=60_001,
        ),
    ],
)
def test_unknown_or_invalid_elapsed_never_makes_reason_optional(candidate) -> None:
    assert _session_void_elapsed_ms(candidate) is None
    with pytest.raises(BusinessRuleError, match="exact played time is unavailable"):
        _void_reason_policy(SessionCancel(), elapsed_ms=None)


def test_public_owner_roles_do_not_depend_on_protected_access() -> None:
    assert has_owner_role(["owner"])
    assert has_owner_role(["co_owner"])
    assert has_owner_role(["super_owner"])
    assert not has_owner_role(["manager", "gaming_supervisor"])


def _recorded_optional_reason():
    start = datetime(2026, 10, 26, 8, 0, tzinfo=UTC)
    gs = _session(status="cancelled", start_at=start, end_at=start + timedelta(minutes=10))
    gs.cancel_reason = _VOID_WITHOUT_USER_REASON
    request = SessionCancel(expected_billing_revision=0)
    request_hash = "a" * 64
    audit = SimpleNamespace(
        reason=_VOID_WITHOUT_USER_REASON,
        operation_id="recorded-void",
        business_details={
            "reason": _VOID_WITHOUT_USER_REASON,
            "user_reason_supplied": False,
            "server_play_elapsed_ms": 600_000,
            "reason_required_after_ms": _VOID_REASON_REQUIRED_AFTER_MS,
            "pre_stop_status": "active",
            "request": request.model_dump(mode="json"),
            "idempotency_key": "recorded-void",
            "request_hash": request_hash,
        },
    )
    return gs, request, audit, request_hash


def test_recorded_blank_reason_uses_original_policy_without_rewriting_the_command() -> None:
    gs, request, audit, request_hash = _recorded_optional_reason()
    before = request.model_dump(mode="json")
    assert _void_request_reason_matches(gs, request, audit, request_hash=request_hash)
    assert before == request.model_dump(mode="json")
    assert audit.business_details["request"]["reason"] is None
    assert SessionCancel(reason="   ").reason is None


@pytest.mark.parametrize("field,value", [
    ("server_play_elapsed_ms", None),
    ("server_play_elapsed_ms", True),
    ("server_play_elapsed_ms", -1),
    ("server_play_elapsed_ms", 600_001),
    ("server_play_elapsed_ms", _VOID_REASON_REQUIRED_AFTER_MS),
    ("server_play_elapsed_ms", _VOID_REASON_REQUIRED_AFTER_MS + 1),
    ("user_reason_supplied", True),
    ("user_reason_supplied", None),
    ("reason_required_after_ms", 3_600_000),
    ("pre_stop_status", "cancelled"),
    ("reason", "Changed policy explanation"),
    ("request_hash", "b" * 64),
    ("idempotency_key", "different-action"),
    ("request", {"reason": None}),
])
def test_blank_reason_rejects_missing_or_conflicting_immutable_policy(field, value) -> None:
    gs, request, audit, request_hash = _recorded_optional_reason()
    audit.business_details[field] = value
    assert not _void_request_reason_matches(gs, request, audit, request_hash=request_hash)


def test_blank_reason_rejects_unknown_cancelled_meter_and_changed_explanations() -> None:
    gs, request, audit, request_hash = _recorded_optional_reason()
    gs.end_at = None
    assert not _void_request_reason_matches(gs, request, audit, request_hash=request_hash)
    gs.end_at = gs.start_at + timedelta(minutes=10)
    audit.reason = "Changed audit explanation"
    assert not _void_request_reason_matches(gs, request, audit, request_hash=request_hash)
    audit.reason = _VOID_WITHOUT_USER_REASON
    gs.cancel_reason = "Changed session explanation"
    assert not _void_request_reason_matches(gs, request, audit, request_hash=request_hash)


def test_supplied_reason_retains_exact_normalized_matching() -> None:
    gs, _request, audit, request_hash = _recorded_optional_reason()
    request = SessionCancel(reason="  Customer requested correction  ")
    gs.cancel_reason = "Customer requested correction"
    assert _void_request_reason_matches(gs, request, audit, request_hash=request_hash)
    gs.cancel_reason = _VOID_WITHOUT_USER_REASON
    assert not _void_request_reason_matches(gs, request, audit, request_hash=request_hash)
