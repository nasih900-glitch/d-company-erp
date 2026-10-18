from datetime import UTC, date, datetime
from decimal import Decimal
from uuid import uuid4

import pytest

from app.core.errors import BusinessRuleError
from app.core.security import issue_audit_page_cursor
from app.models import AuditLog
from app.services.audit.catalogue import (
    BY_CODE,
    CATALOGUE,
    EXPLICIT,
    STOCK_CODES,
    category_for,
    classify_values,
    describe,
)
from app.services.audit.query import date_bounds, decode_cursor, encode_cursor, filter_fingerprint
from app.services.audit.recorder import _enrich_manual_audit_row, json_safe


def test_catalogue_contains_27_unique_real_operations_with_valid_states():
    assert len(BY_CODE) == len(CATALOGUE) == 27
    assert "STK-XFR" not in BY_CODE
    assert "PIK" not in BY_CODE
    for action, expected in EXPLICIT.items():
        definition = BY_CODE[expected[0]]
        assert expected[1] in definition.stages
        assert expected[2] in definition.outcomes
        assert classify_values(action, "Any", None, {"purpose": "password_reset"}) == expected


@pytest.mark.parametrize(("movement_type", "code"), STOCK_CODES.items())
def test_stock_types_require_retained_movement_evidence(movement_type, code):
    assert classify_values(
        "create",
        "StockMovement",
        None,
        {
            "type": movement_type,
            "qty_delta": "-2.0000",
            "batch_id": "batch-a",
        },
    ) == (code, "committed", "completed")
    assert classify_values("create", "StockMovement", None, {"type": movement_type}) is None
    assert classify_values("update", "Ingredient", None, {"name": "Water"}) is None


@pytest.mark.parametrize(
    ("entity", "stage", "outcome"),
    [
        ("PosRefundRequest", "requested", "pending"),
        ("PosRefundCashHandoff", "awaiting_settlement", "pending"),
        ("PosRefundProviderPayoutStart", "awaiting_settlement", "pending"),
        ("PosRefundWithdrawal", "withdrawn", "withdrawn"),
        ("Refund", "physically_settled", "completed"),
    ],
)
def test_refund_request_is_not_payout(entity, stage, outcome):
    values = {
        "amount_minor": 100,
        "order_id": "order-a",
        "payment_id": "payment-a",
        "refund_request_id": "request-a",
        "request_id": "request-a",
        "withdrawn_at": "2026-10-19T00:00:00Z",
        "resolution": "cash_not_handed_over",
        "settled_at": "2026-10-19T00:00:00Z",
        "settled_by": "owner-a",
        "receipt_no": "REF-TEST",
        "settlement_method": "cash",
    }
    assert classify_values("create", entity, None, values) == ("POS-RFD", stage, outcome)


def test_no_guess_from_arbitrary_gaming_create_or_product_edit():
    assert classify_values("create", "GamingSession", None, {"status": "active"}) is None
    assert classify_values("update", "Ingredient", {"current_qty": 1}, {"current_qty": 2}) is None
    assert (
        classify_values("security_otp_requested", "AuthAccess", None, {"purpose": "register"})
        is None
    )
    assert category_for("gaming.session.pause", "gaming_sessions", "GAM-PAU") == "operations"
    assert category_for("create", "PosRefundCashHandoffCompletion") == "pos"
    assert category_for("create", "FinanceSourceCorrection") == "finance"
    assert category_for("unknown", "UnknownModel") == "other"


def test_nested_json_is_typed_bounded_and_credentials_are_redacted():
    snapshot = json_safe(
        {
            "details": {"password_hash": "secret-a", "otp": "123456", "quantity": Decimal("1.50")},
            "list": [{"refresh_token": "secret-b", "id": uuid4()}],
            "timestamp": datetime(2026, 10, 19, tzinfo=UTC),
        }
    )
    assert isinstance(snapshot["details"], dict)
    assert snapshot["details"]["quantity"] == "1.50"
    assert snapshot["details"]["password_hash"] == "***REDACTED***"
    assert snapshot["details"]["otp"] == "***REDACTED***"
    assert snapshot["list"][0]["refresh_token"] == "***REDACTED***"
    assert "secret-a" not in repr(snapshot)
    assert "secret-b" not in repr(snapshot)
    assert len(json_safe([0] * 500)) == 200


def test_failed_target_is_never_authenticated_actor_and_legacy_is_not_rewritten():
    target = uuid4()
    row = AuditLog(
        company_id=uuid4(),
        actor_user_id=target,
        action="login_failed",
        entity_type="User",
        entity_id=str(target),
        after={"email": "staff@test.local"},
    )
    prior = describe(row)
    assert prior["actor_kind"] == "unauthenticated"
    assert prior["target_user_id"] == target
    assert row.actor_user_id == target  # read projection preserves historical stored evidence
    _enrich_manual_audit_row(row)
    assert row.actor_user_id is None
    assert row.target_user_id == target
    assert row.action_code == "SEC-LGN"
    unknown = describe(
        AuditLog(company_id=uuid4(), action="legacy_import", entity_type="Unknown", entity_id="x")
    )
    assert unknown["actor_kind"] == "unverified_legacy"
    assert unknown["outcome"] == "unknown"
    assert unknown["classification_source"] == "unclassified"


def test_signed_cursor_binds_user_tenant_filters_and_snapshot():
    company, user = uuid4(), uuid4()
    fingerprint = filter_fingerprint({"code": "STK-ADJ", "q": "Water", "limit": 50})
    token = encode_cursor(
        company_id=company,
        user_id=user,
        fingerprint=fingerprint,
        snapshot=100,
        last=75,
        snapshot_id="a" * 32,
    )
    assert decode_cursor(token, company_id=company, user_id=user, fingerprint=fingerprint) == (
        100,
        75,
        "a" * 32,
    )
    for kwargs in (
        {"company_id": uuid4(), "user_id": user, "fingerprint": fingerprint},
        {"company_id": company, "user_id": uuid4(), "fingerprint": fingerprint},
        {"company_id": company, "user_id": user, "fingerprint": "changed"},
    ):
        with pytest.raises(BusinessRuleError):
            decode_cursor(token, **kwargs)
    invalid = issue_audit_page_cursor(
        {
            "company_id": str(company),
            "sub": str(user),
            "filters": fingerprint,
            "snapshot": True,
            "last": 1,
            "snapshot_id": "a" * 32,
        }
    )
    with pytest.raises(BusinessRuleError):
        decode_cursor(invalid, company_id=company, user_id=user, fingerprint=fingerprint)


def test_shop_date_filters_are_ist_midnights_not_browser_timezone():
    start, end = date_bounds(date(2026, 10, 18), date(2026, 10, 18), "Asia/Kolkata")
    assert start == datetime(2026, 10, 17, 18, 30, tzinfo=UTC)
    assert end == datetime(2026, 10, 18, 18, 30, tzinfo=UTC)
    with pytest.raises(BusinessRuleError):
        date_bounds(date(2026, 10, 19), date(2026, 10, 18), "Asia/Kolkata")
    with pytest.raises(BusinessRuleError):
        date_bounds(None, date.max, "Asia/Kolkata")


def test_incomplete_legacy_refund_does_not_prove_physical_settlement():
    for values in ({}, {"amount_minor": 100}, {"mode": "cash", "amount_minor": 100}):
        assert classify_values("create", "Refund", None, values) == (
            "POS-RFD",
            "recorded",
            "unknown",
        )
    values = {
        "amount_minor": 100,
        "request_id": "request-a",
        "settled_at": "today",
        "settled_by": "owner",
        "receipt_no": "REF-TEST",
        "settlement_method": "upi",
    }
    assert classify_values("create", "Refund", None, values)[2] == "unknown"
    values.update(external_reference="provider-ref", provider_settled_at="today")
    assert classify_values("create", "Refund", None, values)[2] == "completed"


def test_membership_refund_resolution_distinguishes_unpaid_from_returned_money():
    values = {"refund_id": "refund-a", "resolved_at": "today", "resolution": "cash_returned"}
    assert classify_values("create", "MembershipRefundResolution", None, values) == (
        "POS-RFD",
        "reversed",
        "reversed",
    )
    values["resolution"] = "provider_reversed"
    assert classify_values("create", "MembershipRefundResolution", None, values)[2] == "unknown"
    values["external_reference"] = "provider-ref"
    assert classify_values("create", "MembershipRefundResolution", None, values)[2] == "reversed"


def test_nonsecret_business_codes_survive_typed_audit_serialization():
    from app.models import Role
    from app.services.audit.recorder import _serialize

    role = Role(id=uuid4(), company_id=uuid4(), code="cashier", name="Cashier", permissions=[])
    assert _serialize(role)["code"] == "cashier"
    assert json_safe({"code": "STK-RCV"})["code"] == "STK-RCV"
    assert json_safe({"challenge_id": "challenge-a", "code": "123456"})["code"] == "***REDACTED***"


@pytest.mark.parametrize("method", ["cash", "card", "upi", "qr", "wallet"])
def test_every_supported_pos_refund_rail_can_be_proven(method):
    values = {
        "amount_minor": 100,
        "request_id": "request-a",
        "settled_at": "2026-10-19T00:00:00Z",
        "settled_by": "owner",
        "receipt_no": "REF-TEST",
        "settlement_method": method,
        "external_reference": "provider-ref",
        "provider_settled_at": "2026-10-19T00:00:00Z",
    }
    assert classify_values("create", "Refund", None, values)[2] == "completed"
    if method in {"qr", "wallet"}:
        membership = {
            **values,
            "refund_id": "refund-a",
            "method": method,
            "external_ref": "provider-ref",
        }
        assert (
            classify_values("create", "MembershipRefundSettlement", None, membership)[2]
            == "unknown"
        )


def test_coarse_access_override_is_access_change_not_unknown_system_noise():
    assert classify_values(
        "update", "RolePermissionOverride", {"allowed": False}, {"allowed": True}
    ) == ("SEC-ROL", "committed", "completed")
    assert category_for("enrolled", "RemoteAssistanceDeviceKey") == "system"
    assert category_for("update", "Company") == "system"


@pytest.mark.parametrize(
    ("entity", "proof"),
    [
        (
            "PosRefundCashHandoffCompletion",
            {
                "refund_request_id": "request-a",
                "handed_over_at": "2026-10-19T00:00:00Z",
                "recorded_by": "operator",
            },
        ),
        (
            "PosRefundProviderSettlement",
            {
                "refund_request_id": "request-a",
                "provider_settled_at": "2026-10-19T00:00:00Z",
                "settled_by": "operator",
                "external_reference": "provider-a",
                "settlement_method": "wallet",
            },
        ),
        (
            "MembershipRefundCompletion",
            {
                "refund_id": "refund-a",
                "completed_at": "2026-10-19T00:00:00Z",
                "completed_by": "operator",
                "amount_minor": 100,
                "method": "cash",
            },
        ),
    ],
)
def test_value_capture_is_distinct_from_final_refund_receipt(entity, proof):
    assert classify_values("create", entity, None, proof) == (
        "POS-RFD",
        "value_captured",
        "pending",
    )
    assert classify_values("create", entity, None, {}) == ("POS-RFD", "recorded", "unknown")
