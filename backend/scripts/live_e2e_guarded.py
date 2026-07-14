#!/usr/bin/env python3
"""Guarded live E2E smoke test for the D Company ERP backend.

This intentionally touches production only when LIVE_E2E_ALLOW_PRODUCTION is
set to the exact confirmation token below. It creates isolated test data,
exercises the public API, and then removes every row it created.
"""

from __future__ import annotations

import asyncio
import base64
import json
import os
import secrets
import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from uuid import UUID, uuid4

from sqlalchemy import String, cast, delete, false, or_, select, text

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.core.db import AsyncSessionLocal
from app.core.security import hash_password
from app.models import (
    AuditLog,
    Asset,
    Attendance,
    AuthOtpChallenge,
    Batch,
    Branch,
    CapitalEntry,
    Company,
    Customer,
    CustomerMembership,
    Event,
    EventTicket,
    Expense,
    ExpenseCategory,
    Floor,
    GRN,
    GRNLine,
    GamingBooking,
    GamingSession,
    IdempotencyKey,
    Ingredient,
    InvoiceCounter,
    MembershipTier,
    MenuCategory,
    MenuItem,
    Order,
    OrderLine,
    OcrExtraction,
    OcrUpload,
    OcrVerification,
    Partner,
    Payment,
    PurchaseOrder,
    PurchaseOrderLine,
    Refund,
    Reservation,
    Role,
    Station,
    StockMovement,
    Supplier,
    Table,
    Shift,
    Terminal,
    User,
    UserRole,
)


CONFIRMATION = "I_UNDERSTAND_THIS_TOUCHES_LIVE"
BASE_URL = os.environ.get("LIVE_E2E_BASE_URL", "https://dcompany.duckdns.org/api/v1").rstrip("/")
ALLOW = os.environ.get("LIVE_E2E_ALLOW_PRODUCTION")
RUN_ID = os.environ.get("LIVE_E2E_RUN_ID") or f"LIVE_E2E_{datetime.now(timezone.utc):%Y%m%dT%H%M%SZ}_{secrets.token_hex(3)}"
MARKER = f"E2E{int(time.time()):x}{secrets.token_hex(2)}"[:18]
TIMEOUT = 25


class E2EError(RuntimeError):
    pass


def _json_default(value: Any) -> str:
    if isinstance(value, UUID):
        return str(value)
    if isinstance(value, datetime):
        return value.isoformat()
    return str(value)


def _dump(payload: Any) -> bytes:
    return json.dumps(payload, default=_json_default).encode("utf-8")


def http_request(
    method: str,
    path: str,
    *,
    token: str | None = None,
    terminal_id: str | None = None,
    pricing_token: str | None = None,
    audit_token: str | None = None,
    idem: str | None = None,
    payload: dict[str, Any] | None = None,
    raw_data: bytes | None = None,
    content_type: str | None = None,
    expected: tuple[int, ...] = (200, 201, 204),
) -> tuple[int, dict[str, str], bytes]:
    headers = {
        "Accept": "application/json",
        "User-Agent": f"dcompany-live-e2e/{MARKER}/{RUN_ID}",
    }
    data = raw_data
    if payload is not None:
        headers["Content-Type"] = "application/json"
        data = _dump(payload)
    elif content_type:
        headers["Content-Type"] = content_type
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if terminal_id:
        headers["X-Terminal-Id"] = terminal_id
    if pricing_token:
        headers["X-Pricing-Token"] = pricing_token
    if audit_token:
        headers["X-Audit-Token"] = audit_token
    if idem:
        headers["Idempotency-Key"] = idem

    req = Request(f"{BASE_URL}{path}", data=data, headers=headers, method=method)
    try:
        with urlopen(req, timeout=TIMEOUT) as resp:
            body = resp.read()
            if resp.status not in expected:
                raise E2EError(
                    f"{method} {path} returned HTTP {resp.status}, expected {expected}"
                )
            return resp.status, dict(resp.headers.items()), body
    except HTTPError as exc:
        raw_bytes = exc.read()
        if exc.code in expected:
            return exc.code, dict(exc.headers.items()), raw_bytes
        raw = raw_bytes.decode("utf-8", errors="replace")
        try:
            body = json.loads(raw)
        except json.JSONDecodeError:
            body = raw
        raise E2EError(f"{method} {path} failed HTTP {exc.code}: {body}") from exc
    except URLError as exc:
        raise E2EError(f"{method} {path} failed: {exc}") from exc


def http_json(
    method: str,
    path: str,
    **kwargs: Any,
) -> Any:
    _, _, raw = http_request(method, path, **kwargs)
    if not raw:
        return {}
    try:
        return json.loads(raw.decode("utf-8"))
    except json.JSONDecodeError as exc:
        raise E2EError(f"{method} {path} returned non-JSON: {raw[:500]!r}") from exc


def http_multipart(
    path: str,
    *,
    token: str,
    fields: dict[str, str],
    filename: str,
    file_content: bytes,
    file_type: str,
) -> Any:
    boundary = f"----DCompany{secrets.token_hex(12)}"
    chunks: list[bytes] = []
    for key, value in fields.items():
        chunks.extend([
            f"--{boundary}\r\n".encode(),
            f'Content-Disposition: form-data; name="{key}"\r\n\r\n'.encode(),
            value.encode(),
            b"\r\n",
        ])
    chunks.extend([
        f"--{boundary}\r\n".encode(),
        (
            f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'
            f"Content-Type: {file_type}\r\n\r\n"
        ).encode(),
        file_content,
        b"\r\n",
        f"--{boundary}--\r\n".encode(),
    ])
    _, _, raw = http_request(
        "POST",
        path,
        token=token,
        raw_data=b"".join(chunks),
        content_type=f"multipart/form-data; boundary={boundary}",
    )
    return json.loads(raw.decode("utf-8"))


async def _delete_count(session: Any, model: Any, *criteria: Any) -> int:
    result = await session.execute(delete(model).where(*criteria))
    return int(result.rowcount or 0)


def _contains(col: Any, needle: str = MARKER) -> Any:
    return col.ilike(f"%{needle}%")


def _eq(col: Any, value: Any) -> Any:
    """Avoid broad nullable cleanup predicates when setup failed before IDs exist."""
    return col == value if value is not None else false()


async def setup_identity() -> dict[str, Any]:
    """Create a temporary company-scoped admin user, terminal, and branch."""
    async with AsyncSessionLocal() as session:
        company = (
            await session.execute(
                select(Company)
                .where(Company.deleted_at.is_(None))
                .order_by(Company.created_at.asc())
                .limit(1)
            )
        ).scalar_one_or_none()
        if company is None:
            raise E2EError("no active company exists")

        role = (
            await session.execute(
                select(Role)
                .where(Role.company_id == company.id, Role.code == "super_owner")
                .limit(1)
            )
        ).scalar_one_or_none()
        if role is None:
            raise E2EError("no existing super_owner role exists for live E2E user")

        original_webhook_url = company.google_sheets_webhook_url
        company.google_sheets_webhook_url = None

        suffix = RUN_ID[-6:].lower()
        # Invoice numbers are capped at 20 chars; keep the temporary branch code short.
        branch_code = f"E{suffix[:4].upper()}"
        branch = Branch(
            id=uuid4(),
            company_id=company.id,
            code=branch_code,
            name=f"{MARKER} Branch",
            address=MARKER,
            state_code="32",
            # Isolated test identity; the temporary branch and all its data are
            # deleted in cleanup. This keeps the real company tax profile untouched.
            branch_gstin="32TESTE0000E1Z5",
        )
        terminal = Terminal(
            id=uuid4(),
            branch_id=branch.id,
            name=f"{MARKER} Terminal",
            device_id=f"{MARKER}-terminal",
        )
        password = secrets.token_urlsafe(18)
        user = User(
            id=uuid4(),
            company_id=company.id,
            email=f"{MARKER.lower()}@dcompany.local",
            password_hash=hash_password(password),
            name=f"{MARKER} User",
            phone=f"9{secrets.randbelow(10**9):09d}",
            status="active",
            mfa_secret=None,
        )
        session.add_all([branch, terminal, user])
        await session.flush()

        user_role = UserRole(
            id=uuid4(),
            user_id=user.id,
            role_id=role.id,
            branch_id=branch.id,
        )

        session.add(user_role)
        await session.commit()

        return {
            "company_id": str(company.id),
            "branch_id": str(branch.id),
            "terminal_id": str(terminal.id),
            "role_id": str(role.id),
            "user_id": str(user.id),
            "email": user.email,
            "password": password,
            "original_webhook_url": original_webhook_url,
        }


async def cleanup(identity: dict[str, Any] | None = None) -> dict[str, int]:
    """Remove every row that this E2E run created."""
    counts: dict[str, int] = {}
    async with AsyncSessionLocal() as session:
        company_id = UUID(identity["company_id"]) if identity and identity.get("company_id") else None
        branch_id = UUID(identity["branch_id"]) if identity and identity.get("branch_id") else None
        terminal_id = UUID(identity["terminal_id"]) if identity and identity.get("terminal_id") else None
        user_id = UUID(identity["user_id"]) if identity and identity.get("user_id") else None

        if identity and company_id:
            company = await session.get(Company, company_id)
            if company is not None:
                company.google_sheets_webhook_url = identity.get("original_webhook_url")

        marker_customer_ids = (
            select(Customer.id)
            .where(or_(_contains(Customer.name), _contains(Customer.phone), _contains(Customer.notes)))
        )
        marker_order_ids = (
            select(Order.id)
            .where(or_(_contains(Order.customer_name), _contains(Order.customer_phone), _contains(Order.notes), _contains(Order.idempotency_key)))
        )
        marker_shift_ids = (
            select(Shift.id)
            .where(or_(_eq(Shift.branch_id, branch_id), _eq(Shift.terminal_id, terminal_id), _eq(Shift.opened_by, user_id)))
        )
        marker_station_ids = (
            select(Station.id)
            .where(or_(_contains(Station.code), _contains(Station.name), _contains(Station.notes), _eq(Station.branch_id, branch_id)))
        )
        marker_ingredient_ids = (
            select(Ingredient.id)
            .where(or_(_contains(Ingredient.sku), _contains(Ingredient.name)))
        )
        marker_supplier_ids = (
            select(Supplier.id)
            .where(or_(_contains(Supplier.name), _contains(Supplier.contact), _contains(Supplier.payment_terms)))
        )
        marker_grn_ids = (
            select(GRN.id)
            .where(or_(_contains(GRN.supplier_invoice_no), _contains(GRN.notes), _eq(GRN.received_by, user_id)))
        )
        marker_po_ids = (
            select(PurchaseOrder.id)
            .where(or_(_contains(PurchaseOrder.po_number), _eq(PurchaseOrder.created_by, user_id), _eq(PurchaseOrder.branch_id, branch_id)))
        )
        marker_batch_ids = (
            select(Batch.id)
            .where(
                or_(
                    _contains(Batch.lot_code),
                    _eq(Batch.branch_id, branch_id),
                    Batch.ingredient_id.in_(marker_ingredient_ids),
                    Batch.supplier_id.in_(marker_supplier_ids),
                    Batch.grn_id.in_(marker_grn_ids),
                )
            )
        )
        marker_menu_category_ids = (
            select(MenuCategory.id)
            .where(_contains(MenuCategory.name))
        )
        marker_menu_item_ids = (
            select(MenuItem.id)
            .where(or_(_contains(MenuItem.sku), _contains(MenuItem.name), _contains(MenuItem.description), MenuItem.category_id.in_(marker_menu_category_ids)))
        )
        marker_user_ids = select(User.id).where(
            or_(_contains(User.email), _contains(User.name), _contains(User.phone))
        )
        marker_floor_ids = select(Floor.id).where(
            or_(_contains(Floor.name), _eq(Floor.branch_id, branch_id))
        )
        marker_table_ids = select(Table.id).where(
            or_(_contains(Table.code), Table.floor_id.in_(marker_floor_ids))
        )
        marker_event_ids = select(Event.id).where(
            or_(
                _contains(Event.name), _contains(Event.description),
                _contains(Event.screen), _contains(Event.poster_url),
                _eq(Event.branch_id, branch_id),
            )
        )
        marker_tier_ids = select(MembershipTier.id).where(
            or_(
                _contains(MembershipTier.code), _contains(MembershipTier.name),
                _contains(MembershipTier.description),
            )
        )
        marker_partner_ids = select(Partner.id).where(
            or_(_contains(Partner.name), _contains(Partner.notes))
        )
        marker_expense_category_ids = select(ExpenseCategory.id).where(
            or_(_contains(ExpenseCategory.name), _contains(ExpenseCategory.code))
        )
        marker_ocr_upload_ids = select(OcrUpload.id).where(
            or_(
                _contains(OcrUpload.storage_key), _eq(OcrUpload.branch_id, branch_id),
                _eq(OcrUpload.uploaded_by, user_id),
            )
        )
        marker_ocr_extraction_ids = select(OcrExtraction.id).where(
            OcrExtraction.ocr_upload_id.in_(marker_ocr_upload_ids)
        )

        counts["audit_log"] = await _delete_count(
            session,
            AuditLog,
            or_(
                _eq(AuditLog.actor_user_id, user_id),
                cast(AuditLog.entity_id, String).ilike(f"%{MARKER}%"),
                _contains(AuditLog.action),
                _contains(AuditLog.entity_type),
                _contains(AuditLog.user_agent),
                cast(AuditLog.before, String).ilike(f"%{MARKER}%"),
                cast(AuditLog.after, String).ilike(f"%{MARKER}%"),
            ),
        )
        counts["auth_otp_challenges"] = await _delete_count(
            session,
            AuthOtpChallenge,
            or_(
                _contains(AuthOtpChallenge.target_email),
                _contains(AuthOtpChallenge.request_user_agent),
                _eq(AuthOtpChallenge.target_user_id, user_id),
                _eq(AuthOtpChallenge.requested_by_user_id, user_id),
            ),
        )
        counts["ocr_verifications"] = await _delete_count(
            session,
            OcrVerification,
            or_(
                OcrVerification.ocr_extraction_id.in_(marker_ocr_extraction_ids),
                _eq(OcrVerification.reviewed_by, user_id),
                _contains(OcrVerification.notes),
            ),
        )
        counts["event_tickets"] = await _delete_count(
            session,
            EventTicket,
            or_(
                EventTicket.event_id.in_(marker_event_ids),
                _contains(EventTicket.customer_name), _contains(EventTicket.note),
                _eq(EventTicket.sold_by, user_id), _eq(EventTicket.checked_in_by, user_id),
            ),
        )
        counts["reservations"] = await _delete_count(
            session,
            Reservation,
            or_(
                Reservation.table_id.in_(marker_table_ids),
                _contains(Reservation.guest_name), _contains(Reservation.contact),
                _contains(Reservation.notes), _eq(Reservation.created_by, user_id),
            ),
        )
        counts["attendance"] = await _delete_count(
            session,
            Attendance,
            or_(
                Attendance.user_id.in_(marker_user_ids), _eq(Attendance.branch_id, branch_id),
                _contains(Attendance.notes),
            ),
        )
        counts["capital_entries"] = await _delete_count(
            session,
            CapitalEntry,
            or_(CapitalEntry.partner_id.in_(marker_partner_ids), _contains(CapitalEntry.note)),
        )
        counts["expenses"] = await _delete_count(
            session,
            Expense,
            or_(
                _eq(Expense.branch_id, branch_id),
                Expense.category_id.in_(marker_expense_category_ids),
                Expense.ocr_extraction_id.in_(marker_ocr_extraction_ids),
                _contains(Expense.vendor_name), _contains(Expense.invoice_no),
                _contains(Expense.note),
            ),
        )
        counts["assets"] = await _delete_count(
            session,
            Asset,
            or_(
                _eq(Asset.branch_id, branch_id), _contains(Asset.name),
                _contains(Asset.type), _contains(Asset.notes),
            ),
        )
        counts["refunds"] = await _delete_count(
            session,
            Refund,
            or_(Refund.order_id.in_(marker_order_ids), _eq(Refund.approved_by, user_id), _contains(Refund.note)),
        )
        counts["payments"] = await _delete_count(
            session,
            Payment,
            or_(Payment.order_id.in_(marker_order_ids), Payment.shift_id.in_(marker_shift_ids), _contains(Payment.ref_external)),
        )
        counts["order_lines"] = await _delete_count(
            session,
            OrderLine,
            or_(OrderLine.order_id.in_(marker_order_ids), OrderLine.menu_item_id.in_(marker_menu_item_ids)),
        )
        counts["gaming_sessions"] = await _delete_count(
            session,
            GamingSession,
            or_(
                GamingSession.station_id.in_(marker_station_ids),
                GamingSession.shift_id.in_(marker_shift_ids),
                _eq(GamingSession.opened_by, user_id),
                GamingSession.order_id.in_(marker_order_ids),
                _contains(GamingSession.customer_name),
                _contains(GamingSession.customer_phone),
            ),
        )
        counts["gaming_bookings"] = await _delete_count(
            session,
            GamingBooking,
            or_(GamingBooking.station_id.in_(marker_station_ids), _eq(GamingBooking.created_by, user_id), _contains(GamingBooking.guest_name), _contains(GamingBooking.contact)),
        )
        counts["stock_movements"] = await _delete_count(
            session,
            StockMovement,
            or_(
                StockMovement.batch_id.in_(marker_batch_ids),
                _eq(StockMovement.branch_id, branch_id),
                _eq(StockMovement.created_by, user_id),
                _contains(StockMovement.note),
                cast(StockMovement.ref_id, String).ilike(f"%{MARKER}%"),
            ),
        )
        counts["grn_lines"] = await _delete_count(
            session,
            GRNLine,
            or_(GRNLine.grn_id.in_(marker_grn_ids), GRNLine.batch_id.in_(marker_batch_ids), GRNLine.ingredient_id.in_(marker_ingredient_ids)),
        )
        counts["batches"] = await _delete_count(
            session,
            Batch,
            or_(Batch.id.in_(marker_batch_ids), _contains(Batch.lot_code), _eq(Batch.branch_id, branch_id)),
        )
        counts["grns"] = await _delete_count(
            session,
            GRN,
            or_(GRN.id.in_(marker_grn_ids), _eq(GRN.received_by, user_id), _contains(GRN.supplier_invoice_no), _contains(GRN.notes)),
        )
        counts["purchase_order_lines"] = await _delete_count(
            session,
            PurchaseOrderLine,
            or_(PurchaseOrderLine.purchase_order_id.in_(marker_po_ids), PurchaseOrderLine.ingredient_id.in_(marker_ingredient_ids)),
        )
        counts["purchase_orders"] = await _delete_count(
            session,
            PurchaseOrder,
            or_(PurchaseOrder.id.in_(marker_po_ids), _eq(PurchaseOrder.branch_id, branch_id), _eq(PurchaseOrder.created_by, user_id), _contains(PurchaseOrder.po_number)),
        )
        counts["orders"] = await _delete_count(
            session,
            Order,
            or_(Order.id.in_(marker_order_ids), _eq(Order.branch_id, branch_id), _eq(Order.terminal_id, terminal_id), Order.shift_id.in_(marker_shift_ids), _eq(Order.opened_by, user_id)),
        )
        counts["idempotency_keys"] = await _delete_count(
            session,
            IdempotencyKey,
            or_(_contains(IdempotencyKey.key), _eq(IdempotencyKey.terminal_id, terminal_id), _eq(IdempotencyKey.user_id, user_id)),
        )
        counts["shifts"] = await _delete_count(
            session,
            Shift,
            or_(Shift.id.in_(marker_shift_ids), _eq(Shift.branch_id, branch_id), _eq(Shift.terminal_id, terminal_id), _eq(Shift.opened_by, user_id)),
        )
        counts["stations"] = await _delete_count(
            session,
            Station,
            or_(Station.id.in_(marker_station_ids), _contains(Station.code), _contains(Station.name), _contains(Station.notes), _eq(Station.branch_id, branch_id)),
        )
        counts["menu_items"] = await _delete_count(
            session,
            MenuItem,
            or_(MenuItem.id.in_(marker_menu_item_ids), _contains(MenuItem.sku), _contains(MenuItem.name), _contains(MenuItem.description)),
        )
        counts["menu_categories"] = await _delete_count(
            session,
            MenuCategory,
            or_(MenuCategory.id.in_(marker_menu_category_ids), _contains(MenuCategory.name)),
        )
        counts["customer_memberships"] = await _delete_count(
            session,
            CustomerMembership,
            or_(
                CustomerMembership.customer_id.in_(marker_customer_ids),
                CustomerMembership.tier_id.in_(marker_tier_ids),
            ),
        )
        counts["customers"] = await _delete_count(
            session,
            Customer,
            or_(Customer.id.in_(marker_customer_ids), _contains(Customer.name), _contains(Customer.phone), _contains(Customer.notes)),
        )
        counts["ingredients"] = await _delete_count(
            session,
            Ingredient,
            or_(Ingredient.id.in_(marker_ingredient_ids), _contains(Ingredient.sku), _contains(Ingredient.name)),
        )
        counts["suppliers"] = await _delete_count(
            session,
            Supplier,
            or_(Supplier.id.in_(marker_supplier_ids), _contains(Supplier.name), _contains(Supplier.contact), _contains(Supplier.payment_terms)),
        )
        counts["ocr_extractions"] = await _delete_count(
            session,
            OcrExtraction,
            OcrExtraction.id.in_(marker_ocr_extraction_ids),
        )
        counts["ocr_uploads"] = await _delete_count(
            session,
            OcrUpload,
            OcrUpload.id.in_(marker_ocr_upload_ids),
        )
        counts["tables"] = await _delete_count(
            session,
            Table,
            Table.id.in_(marker_table_ids),
        )
        counts["floors"] = await _delete_count(
            session,
            Floor,
            Floor.id.in_(marker_floor_ids),
        )
        counts["events"] = await _delete_count(
            session,
            Event,
            Event.id.in_(marker_event_ids),
        )
        counts["membership_tiers"] = await _delete_count(
            session,
            MembershipTier,
            MembershipTier.id.in_(marker_tier_ids),
        )
        counts["partners"] = await _delete_count(
            session,
            Partner,
            Partner.id.in_(marker_partner_ids),
        )
        counts["expense_categories"] = await _delete_count(
            session,
            ExpenseCategory,
            ExpenseCategory.id.in_(marker_expense_category_ids),
        )
        counts["invoice_counters"] = await _delete_count(
            session,
            InvoiceCounter,
            _eq(InvoiceCounter.branch_id, branch_id),
        )
        counts["user_roles"] = await _delete_count(
            session,
            UserRole,
            or_(UserRole.user_id.in_(marker_user_ids), _eq(UserRole.branch_id, branch_id)),
        )
        counts["users"] = await _delete_count(
            session,
            User,
            User.id.in_(marker_user_ids),
        )
        counts["roles"] = await _delete_count(
            session,
            Role,
            or_(_contains(Role.code), _contains(Role.name), _contains(Role.description)),
        )
        counts["terminals"] = await _delete_count(
            session,
            Terminal,
            or_(_eq(Terminal.id, terminal_id), _contains(Terminal.name), _contains(Terminal.device_id)),
        )
        counts["branches"] = await _delete_count(
            session,
            Branch,
            or_(_eq(Branch.id, branch_id), _contains(Branch.code), _contains(Branch.name), _contains(Branch.address)),
        )

        await session.commit()
    return counts


SCAN_SQL = {
    "branches": "select count(*) from branches where code ilike :m or name ilike :m or address ilike :m",
    "terminals": "select count(*) from terminals where name ilike :m or device_id ilike :m",
    "roles": "select count(*) from roles where code ilike :m or name ilike :m or description ilike :m",
    "users": "select count(*) from users where email ilike :m or name ilike :m or phone ilike :m",
    "menu_categories": "select count(*) from menu_categories where name ilike :m",
    "menu_items": "select count(*) from menu_items where sku ilike :m or name ilike :m or description ilike :m",
    "ingredients": "select count(*) from ingredients where sku ilike :m or name ilike :m",
    "suppliers": "select count(*) from suppliers where name ilike :m or contact ilike :m or payment_terms ilike :m",
    "batches": "select count(*) from batches where lot_code ilike :m",
    "stock_movements": "select count(*) from stock_movements where note ilike :m or ref_id::text ilike :m",
    "purchase_orders": "select count(*) from purchase_orders where po_number ilike :m",
    "grns": "select count(*) from grns where supplier_invoice_no ilike :m or notes ilike :m",
    "orders": "select count(*) from orders where customer_name ilike :m or customer_phone ilike :m or notes ilike :m or idempotency_key ilike :m",
    "payments": "select count(*) from payments where ref_external ilike :m",
    "refunds": "select count(*) from refunds where note ilike :m",
    "customers": "select count(*) from customers where name ilike :m or phone ilike :m or notes ilike :m",
    "stations": "select count(*) from stations where code ilike :m or name ilike :m or notes ilike :m",
    "gaming_sessions": "select count(*) from gaming_sessions where customer_name ilike :m or customer_phone ilike :m",
    "gaming_bookings": "select count(*) from gaming_bookings where guest_name ilike :m or contact ilike :m",
    "floors": "select count(*) from floors where name ilike :m",
    "tables": "select count(*) from tables where code ilike :m",
    "reservations": "select count(*) from reservations where guest_name ilike :m or contact ilike :m or notes ilike :m",
    "events": "select count(*) from events where name ilike :m or description ilike :m or screen ilike :m or poster_url ilike :m",
    "event_tickets": "select count(*) from event_tickets where customer_name ilike :m or customer_phone ilike :m or note ilike :m",
    "membership_tiers": "select count(*) from membership_tiers where code ilike :m or name ilike :m or description ilike :m",
    "partners": "select count(*) from partners where name ilike :m or notes ilike :m",
    "capital_entries": "select count(*) from capital_entries where note ilike :m",
    "expense_categories": "select count(*) from expense_categories where name ilike :m or code ilike :m",
    "expenses": "select count(*) from expenses where vendor_name ilike :m or invoice_no ilike :m or note ilike :m",
    "assets": "select count(*) from assets where name ilike :m or type ilike :m or notes ilike :m",
    "attendance": "select count(*) from attendance where notes ilike :m",
    "ocr_uploads": "select count(*) from ocr_uploads where storage_key ilike :m",
    "ocr_verifications": "select count(*) from ocr_verifications where notes ilike :m",
    "idempotency_keys": "select count(*) from idempotency_keys where key ilike :m",
    "audit_log": "select count(*) from audit_log where action ilike :m or entity_type ilike :m or entity_id::text ilike :m or user_agent ilike :m or before::text ilike :m or after::text ilike :m",
    "auth_otp_challenges": "select count(*) from auth_otp_challenges where target_email ilike :m or request_user_agent ilike :m",
}


async def residual_scan() -> dict[str, int]:
    counts: dict[str, int] = {}
    async with AsyncSessionLocal() as session:
        for table, sql in SCAN_SQL.items():
            value = (
                await session.execute(
                    text(sql),
                    {"m": f"%{MARKER}%"},
                )
            ).scalar_one()
            counts[table] = int(value or 0)
    return counts


def check(condition: bool, label: str, checks: list[str]) -> None:
    if not condition:
        raise E2EError(label)
    checks.append(label)


async def main() -> int:
    if ALLOW != CONFIRMATION:
        print(json.dumps({
            "status": "BLOCKED",
            "reason": "Set LIVE_E2E_ALLOW_PRODUCTION to the exact confirmation token before running.",
            "run_id": RUN_ID,
        }, indent=2))
        return 2

    identity: dict[str, Any] | None = None
    checks: list[str] = []
    cleanup_counts: dict[str, int] = {}
    residual_counts: dict[str, int] = {}
    failures: list[str] = []
    status = "FAIL"

    try:
        identity = await setup_identity()

        login = http_json(
            "POST",
            "/auth/login",
            payload={"email": identity["email"], "password": identity["password"]},
        )
        token = login.get("access_token")
        check(isinstance(token, str) and len(token) > 20, "auth login returns bearer token", checks)

        common_headers = {"token": token, "terminal_id": identity["terminal_id"]}
        me = http_json("GET", "/auth/me", **common_headers)
        check(me.get("email") == identity["email"], "auth me returns temp user", checks)

        pricing = http_json(
            "POST",
            "/admin/pricing/unlock",
            token=token,
            payload={"password": identity["password"]},
        )
        pricing_token = pricing.get("pricing_token")
        check(isinstance(pricing_token, str) and pricing_token, "pricing unlock returns token", checks)

        audit = http_json(
            "POST",
            "/admin/audit/unlock",
            token=token,
            payload={"password": identity["password"]},
        )
        audit_token = audit.get("audit_token")
        check(isinstance(audit_token, str) and audit_token, "audit unlock returns token", checks)

        refreshed = http_json(
            "POST",
            "/auth/refresh",
            payload={"refresh_token": login["refresh_token"]},
        )
        token = refreshed.get("access_token")
        check(
            isinstance(token, str) and len(token) > 20
            and refreshed.get("refresh_token") != login.get("refresh_token"),
            "auth refresh rotates tokens",
            checks,
        )
        common_headers = {"token": token, "terminal_id": identity["terminal_id"]}

        company = http_json("GET", "/settings/company", token=token)
        branches = http_json("GET", "/settings/branches", token=token)
        terminals = http_json("GET", "/settings/terminals", token=token)
        staff_roles = http_json("GET", "/staff/roles", token=token)
        staff_users = http_json("GET", "/staff/users", token=token)
        check(company.get("id") == identity["company_id"], "settings company reads", checks)
        check(
            any(str(row.get("id")) == identity["branch_id"] for row in branches),
            "settings branches include temp branch",
            checks,
        )
        check(
            any(str(row.get("id")) == identity["terminal_id"] for row in terminals),
            "settings terminals include temp terminal",
            checks,
        )
        check(isinstance(staff_roles, list) and staff_roles, "staff roles read", checks)
        check(isinstance(staff_users, list) and staff_users, "staff users read", checks)

        floor = http_json(
            "POST",
            "/tables/floors",
            token=token,
            payload={"name": f"{MARKER} Floor", "branch_id": identity["branch_id"]},
        )
        table = http_json(
            "POST",
            "/tables",
            token=token,
            payload={
                "floor_id": floor["id"],
                "code": MARKER[:20],
                "seats": 4,
                "shape": "booth",
                "x": 12.5,
                "y": 8.25,
            },
        )
        occupied = http_json(
            "PATCH",
            f"/tables/{table['id']}/status",
            token=token,
            payload={"status": "occupied"},
        )
        check(occupied.get("status") == "occupied", "table status changes", checks)
        available = http_json(
            "PATCH",
            f"/tables/{table['id']}/status",
            token=token,
            payload={"status": "available"},
        )
        check(available.get("status") == "available", "table returns available", checks)
        reservation_start = datetime.now(timezone.utc) + timedelta(hours=4)
        reservation = http_json(
            "POST",
            "/tables/reservations",
            token=token,
            payload={
                "table_id": table["id"],
                "guest_name": f"{MARKER} Guest",
                "party_size": 3,
                "contact": MARKER,
                "starts_at": reservation_start.isoformat(),
                "ends_at": (reservation_start + timedelta(hours=1)).isoformat(),
                "notes": MARKER,
            },
        )
        check(reservation.get("status") == "held", "table reservation creates", checks)

        customer_phone = f"8{secrets.randbelow(10**9):09d}"
        customer = http_json(
            "POST",
            "/customers",
            token=token,
            payload={
                "phone": customer_phone,
                "name": f"{MARKER} Customer",
                "email": f"{MARKER.lower()}-customer@example.com",
                "notes": MARKER,
            },
        )
        check(bool(customer.get("id")), "customer creates", checks)

        tier = http_json(
            "POST",
            "/memberships/tiers",
            token=token,
            pricing_token=pricing_token,
            payload={
                "code": f"{MARKER[:16]}T",
                "name": f"{MARKER} Tier",
                "monthly_price_minor": 99900,
                "annual_price_minor": 999000,
                "food_discount_pct": 0.10,
                "gaming_discount_pct": 0.05,
                "hookah_discount_pct": 0.05,
                "point_multiplier": 1.5,
                "free_gaming_minutes_per_week": 30,
                "free_hookah_per_month": 1,
                "priority_booking": True,
                "description": MARKER,
                "sort_order": 99,
            },
        )
        tier_updated = http_json(
            "PATCH",
            f"/memberships/tiers/{tier['id']}",
            token=token,
            pricing_token=pricing_token,
            payload={"monthly_price_minor": 109900},
        )
        check(
            tier_updated.get("monthly_price_minor") == 109900,
            "membership pricing update persists",
            checks,
        )
        subscription = http_json(
            "POST",
            "/memberships/subscribe",
            token=token,
            payload={
                "customer_id": customer["id"],
                "tier_id": tier["id"],
                "billing_cycle": "monthly",
                "paid_via": "cash",
            },
        )
        current_subscription = http_json(
            "GET",
            f"/memberships/customer/{customer['id']}",
            token=token,
        )
        check(
            subscription.get("is_active") is True
            and current_subscription.get("id") == subscription.get("id"),
            "customer membership subscribes and reads active",
            checks,
        )

        categories: dict[str, str] = {}
        for idx, name in enumerate(["Food", "Drinks", "Gaming", "Shisha", "Streaming"], start=1):
            created = http_json(
                "POST",
                "/menu/categories",
                token=token,
                pricing_token=pricing_token,
                payload={"name": f"{MARKER} {name}", "sort_order": idx},
            )
            categories[name.lower()] = created["id"]
        check(len(categories) == 5, "menu categories create for food/drink/gaming/shisha/streaming", checks)

        items: dict[str, str] = {}
        item_specs = [
            ("coffee", "Drinks", "drink", 18000, "996331", 0.05),
            ("snack", "Food", "food", 12000, "996331", 0.05),
            ("ps5", "Gaming", "gaming", 20000, "999692", 0.18),
            ("shisha", "Shisha", "hookah", 35000, "999692", 0.18),
            ("streaming", "Streaming", "streaming", 15000, "999692", 0.18),
        ]
        for code, category, item_type, price, hsn, tax in item_specs:
            created = http_json(
                "POST",
                "/menu/items",
                token=token,
                pricing_token=pricing_token,
                payload={
                    "category_id": categories[category.lower()],
                    "sku": f"{RUN_ID}-{code}",
                    "name": f"{MARKER} {category} Item",
                    "type": item_type,
                    "base_price_minor": price,
                    "tax_rate": tax,
                    "hsn_code": hsn,
                    "price_includes_tax": True,
                    "description": MARKER,
                },
            )
            items[code] = created["id"]
        check(len(items) == 5, "menu items create with GST classes", checks)

        coffee_updated = http_json(
            "PATCH",
            f"/menu/items/{items['coffee']}",
            token=token,
            pricing_token=pricing_token,
            payload={"base_price_minor": 18500},
        )
        check(
            coffee_updated.get("base_price_minor") == 18500,
            "menu price update persists behind pricing unlock",
            checks,
        )
        category_rows = http_json("GET", "/menu/categories", token=token)
        item_rows = http_json("GET", "/menu/items", token=token)
        public_menu = http_json("GET", "/public/menu")
        check(
            all(any(str(row.get("id")) == value for row in category_rows) for value in categories.values()),
            "menu category list returns created rows",
            checks,
        )
        check(
            all(any(str(row.get("id")) == value for row in item_rows) for value in items.values()),
            "menu item list returns created rows",
            checks,
        )
        check(
            any(str(row.get("id")) == items["coffee"] for row in public_menu.get("items", [])),
            "public menu exposes available item without authentication",
            checks,
        )

        supplier = http_json(
            "POST",
            "/inventory/suppliers",
            token=token,
            payload={
                "name": f"{MARKER} Supplier",
                "contact": f"{MARKER} Contact",
                "gstin": None,
                "payment_terms": MARKER,
            },
        )
        ingredient = http_json(
            "POST",
            "/inventory/ingredients",
            token=token,
            payload={
                "sku": f"{RUN_ID}-ING",
                "name": f"{MARKER} Coffee Beans",
                "base_unit": "g",
                "reorder_threshold": 100,
                "reorder_qty": 500,
            },
        )
        check(bool(supplier.get("id") and ingredient.get("id")), "inventory supplier and ingredient create", checks)

        grn = http_json(
            "POST",
            "/inventory/grn",
            token=token,
            payload={
                "supplier_id": supplier["id"],
                "branch_id": identity["branch_id"],
                "supplier_invoice_no": f"{RUN_ID}-INV",
                "supplier_invoice_amount_minor": 45000,
                "notes": MARKER,
                "lines": [
                    {
                        "ingredient_id": ingredient["id"],
                        "qty": 10,
                        "unit_cost_minor": 4500,
                        "lot_code": f"{RUN_ID}-LOT",
                        "expires_at": (datetime.now(timezone.utc) + timedelta(days=30)).isoformat(),
                    }
                ],
            },
        )
        check(grn.get("ok") is True and grn.get("batches_created") == 1, "inventory GRN creates batch and stock trail", checks)

        adjustment = http_json(
            "POST",
            "/inventory/adjustments",
            token=token,
            payload={
                "ingredient_id": ingredient["id"],
                "branch_id": identity["branch_id"],
                "qty_delta": -1,
                "type": "waste",
                "note": MARKER,
            },
        )
        check(bool(adjustment.get("id")), "inventory adjustment creates stock movement", checks)

        stock_rows = http_json("GET", "/inventory/ingredients", token=token)
        batch_rows = http_json(
            "GET",
            f"/inventory/batches?ingredient_id={ingredient['id']}",
            token=token,
        )
        check(
            any(
                str(row.get("id")) == ingredient["id"]
                and float(row.get("current_qty") or 0) == 9.0
                for row in stock_rows
            ),
            "inventory stock view reflects GRN and adjustment",
            checks,
        )
        check(
            any(
                str(row.get("ingredient_id")) == ingredient["id"]
                and str(row.get("lot_code") or "") == f"{RUN_ID}-LOT"
                for row in batch_rows
            ),
            "inventory batch traceability reads",
            checks,
        )

        expense_category = http_json(
            "POST",
            "/settings/expense-categories",
            token=token,
            payload={"name": f"{MARKER} Expense", "code": MARKER[:20]},
        )
        expense = http_json(
            "POST",
            "/finance/expenses",
            token=token,
            payload={
                "branch_id": identity["branch_id"],
                "category_id": expense_category["id"],
                "supplier_id": supplier["id"],
                "amount_minor": 12500,
                "paid_via": "upi",
                "paid_at": datetime.now(timezone.utc).isoformat(),
                "vendor_name": f"{MARKER} Vendor",
                "invoice_no": f"{MARKER}-EXP",
                "note": MARKER,
            },
        )
        asset = http_json(
            "POST",
            "/finance/assets",
            token=token,
            payload={
                "branch_id": identity["branch_id"],
                "name": f"{MARKER} Espresso Machine",
                "type": f"{MARKER[:10]} asset",
                "purchase_minor": 250000,
                "purchase_date": datetime.now(timezone.utc).isoformat(),
                "useful_life_months": 60,
                "salvage_minor": 10000,
            },
        )
        partner = http_json(
            "POST",
            "/finance/partners",
            token=token,
            payload={
                "name": f"{MARKER} Partner",
                "share_pct": 1.0,
                "joined_at": datetime.now(timezone.utc).isoformat(),
                "notes": MARKER,
            },
        )
        capital = http_json(
            "POST",
            "/finance/capital-entries",
            token=token,
            payload={
                "partner_id": partner["id"],
                "type": "invest",
                "amount_minor": 500000,
                "effective_at": datetime.now(timezone.utc).isoformat(),
                "note": MARKER,
            },
        )
        expenses = http_json("GET", "/finance/expenses", token=token)
        assets = http_json("GET", "/finance/assets", token=token)
        partners = http_json("GET", "/finance/partners", token=token)
        capital_rows = http_json(
            "GET",
            f"/finance/partners/{partner['id']}/capital",
            token=token,
        )
        check(
            any(str(row.get("id")) == expense["id"] for row in expenses),
            "finance expense creates and lists",
            checks,
        )
        check(
            any(str(row.get("id")) == asset["id"] for row in assets),
            "finance asset creates and lists",
            checks,
        )
        check(
            any(str(row.get("id")) == partner["id"] for row in partners)
            and any(str(row.get("id")) == capital["id"] for row in capital_rows),
            "partner capital creates and lists",
            checks,
        )

        ocr_before = http_json("GET", "/ocr/queue", token=token)
        ocr_before_ids = {str(row.get("id")) for row in ocr_before}
        tiny_png = base64.b64decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9Zl1sAAAAASUVORK5CYII="
        )
        ocr_upload = http_multipart(
            "/ocr/uploads",
            token=token,
            fields={"branch_id": identity["branch_id"], "source": "manual"},
            filename=f"{MARKER}.png",
            file_content=tiny_png,
            file_type="image/png",
        )
        ocr_after = http_json("GET", "/ocr/queue", token=token)
        new_extractions = [row for row in ocr_after if str(row.get("id")) not in ocr_before_ids]
        check(
            ocr_upload.get("byte_size") == len(tiny_png) and len(new_extractions) == 1,
            "OCR receipt upload enters verification queue",
            checks,
        )
        ocr_verified = http_json(
            "POST",
            f"/ocr/extractions/{new_extractions[0]['id']}/verify",
            token=token,
            payload={"decision": "approve", "edits": None, "notes": MARKER},
        )
        check(
            ocr_verified.get("extraction_status") == "approved",
            "OCR extraction verifies",
            checks,
        )

        blocked_email = f"{MARKER.lower()}-cashier@dcompany.local"
        _, _, blocked_create_raw = http_request(
            "POST",
            "/staff/users",
            token=token,
            payload={
                "email": blocked_email,
                "name": f"{MARKER} Cashier",
                "password": f"Tmp1!{MARKER}",
                "phone": f"6{secrets.randbelow(10**9):09d}",
                "role_code": "cashier",
            },
            expected=(422,),
        )
        check(
            "otp approval" in blocked_create_raw.decode("utf-8", errors="replace").lower(),
            "legacy direct staff creation is blocked in favor of OTP registration",
            checks,
        )
        _, _, blocked_password_raw = http_request(
            "POST",
            f"/staff/users/{identity['user_id']}/password",
            token=token,
            payload={"new_password": f"Tmp2!{MARKER}"},
            expected=(422,),
        )
        check(
            "otp approval" in blocked_password_raw.decode("utf-8", errors="replace").lower(),
            "legacy direct password change is blocked in favor of OTP reset",
            checks,
        )
        attendance = http_json(
            "POST",
            "/staff/attendance/clock-in",
            token=token,
            payload={"branch_id": identity["branch_id"], "notes": MARKER},
        )
        check(bool(attendance.get("id")), "staff attendance clock-in records", checks)

        event_start = datetime.now(timezone.utc) + timedelta(days=2)
        event = http_json(
            "POST",
            "/events",
            token=token,
            pricing_token=pricing_token,
            payload={
                "name": f"{MARKER} Match",
                "description": MARKER,
                "event_type": "football",
                "screen": MARKER,
                "starts_at": event_start.isoformat(),
                "ends_at": (event_start + timedelta(hours=3)).isoformat(),
                "capacity": 20,
                "base_ticket_price_minor": 5000,
                "poster_url": None,
                "branch_id": identity["branch_id"],
            },
        )
        event_updated = http_json(
            "PATCH",
            f"/events/{event['id']}",
            token=token,
            pricing_token=pricing_token,
            payload={"base_ticket_price_minor": 5500},
        )
        tickets = http_json(
            "POST",
            f"/events/{event['id']}/tickets",
            token=token,
            payload={
                "customer_name": f"{MARKER} Fan",
                "customer_phone": customer_phone,
                "seat": "A1",
                "qty": 2,
                "note": MARKER,
            },
        )
        checked_ticket = http_json(
            "POST",
            f"/events/{event['id']}/tickets/{tickets[0]['id']}/check-in",
            token=token,
            payload={},
        )
        ticket_rows = http_json("GET", f"/events/{event['id']}/tickets", token=token)
        check(
            event_updated.get("base_ticket_price_minor") == 5500
            and len(tickets) == 2
            and checked_ticket.get("status") == "checked_in"
            and len(ticket_rows) == 2,
            "event pricing, ticket sale, check-in, and list work",
            checks,
        )

        stations: dict[str, str] = {}
        short_station_code = f"E2E{RUN_ID[-6:].upper()}"
        for station_type, title, rate, code_suffix in [
            ("ps5", "PS5 Station", 20000, "PS5"),
            ("vr", "VR Arena", 25000, "VR"),
            ("simulator", "Racing Simulator", 30000, "SIM"),
            ("streaming", "Streaming Booth", 18000, "STR"),
            ("hookah", "Shisha Table", 35000, "SHI"),
        ]:
            created = http_json(
                "POST",
                "/gaming/stations",
                token=token,
                pricing_token=pricing_token,
                payload={
                    "code": f"{short_station_code}{code_suffix}",
                    "name": f"{MARKER} {title}",
                    "type": station_type,
                    "rate_per_hour_minor": rate,
                    "branch_id": identity["branch_id"],
                    "notes": MARKER,
                },
            )
            stations[station_type] = created["id"]
        check(set(stations) == {"ps5", "vr", "simulator", "streaming", "hookah"}, "gaming stations create for all cafe session types", checks)

        shift = http_json(
            "POST",
            "/pos/shifts/open",
            token=token,
            terminal_id=identity["terminal_id"],
            payload={"opening_float_minor": 1000},
        )
        shift_id = shift["id"]
        check(bool(shift_id), "POS shift opens on temp terminal", checks)

        order = http_json(
            "POST",
            "/pos/orders",
            token=token,
            terminal_id=identity["terminal_id"],
            idem=f"{RUN_ID}-order-1",
            payload={
                "type": "dine_in",
                "table_id": table["id"],
                "shift_id": shift_id,
                "customer_name": MARKER,
                "customer_phone": customer_phone,
                "place_of_supply_state_code": "32",
                "notes": MARKER,
                "lines": [
                    {"menu_item_id": items["coffee"], "qty": 1},
                    {"menu_item_id": items["snack"], "qty": 1},
                    {"menu_item_id": items["ps5"], "qty": 1},
                    {"menu_item_id": items["shisha"], "qty": 1},
                    {"menu_item_id": items["streaming"], "qty": 1},
                ],
            },
        )
        total_minor = int(order["total_minor"])
        check(
            total_minor > 0
            and len(order.get("lines", [])) == 5
            and int(order.get("discount_minor") or 0) > 0,
            "POS order totals and auto-applies active membership discount",
            checks,
        )
        check(
            order.get("invoice_no") is None,
            "POS defers the invoice number until payment (no gaps for unpaid orders)",
            checks,
        )

        payment = http_json(
            "POST",
            f"/pos/orders/{order['id']}/payments",
            token=token,
            terminal_id=identity["terminal_id"],
            idem=f"{RUN_ID}-payment-1",
            payload={
                "method": "cash",
                "amount_minor": total_minor,
                "tendered_minor": total_minor,
                "ref_external": MARKER,
            },
        )
        check(payment.get("order_status") == "paid", "POS payment marks order paid", checks)

        persisted_order = http_json("GET", f"/pos/orders/{order['id']}", token=token)
        check(
            persisted_order.get("status") == "paid"
            and isinstance(persisted_order.get("invoice_no"), str)
            and 1 <= len(persisted_order["invoice_no"]) <= 20
            and bool(persisted_order.get("fiscal_year"))
            and len(persisted_order.get("lines", [])) == 5,
            "POS paid order reads back with an issued invoice and complete lines",
            checks,
        )

        kitchen_queue = http_json("GET", "/kitchen/queue", token=token)
        kitchen_order = next(
            (row for row in kitchen_queue if str(row.get("id")) == order["id"]),
            None,
        )
        check(
            kitchen_order is not None
            and {line.get("type") for line in kitchen_order.get("lines", [])}
            <= {"food", "drink", "dessert"}
            and len(kitchen_order.get("lines", [])) == 2,
            "KDS includes only food and drink lines from mixed order",
            checks,
        )
        for kitchen_state in ("preparing", "ready", "served"):
            state_row = http_json(
                "PATCH",
                f"/kitchen/orders/{order['id']}/state",
                token=token,
                payload={"state": kitchen_state},
            )
            check(
                state_row.get("kitchen_state") == kitchen_state,
                f"KDS advances order to {kitchen_state}",
                checks,
            )
        queue_after_served = http_json("GET", "/kitchen/queue", token=token)
        check(
            not any(str(row.get("id")) == order["id"] for row in queue_after_served),
            "KDS hides served order",
            checks,
        )

        refund = http_json(
            "POST",
            f"/pos/orders/{order['id']}/refunds",
            token=token,
            terminal_id=identity["terminal_id"],
            idem=f"{RUN_ID}-refund-1",
            payload={
                "reason_code": "customer_request",
                "amount_minor": 100,
                "mode": "original",
                "note": MARKER,
            },
        )
        check(bool(refund.get("id")), "POS partial refund records", checks)

        # --- Tables -> Send to Kitchen -> Send to POS -> bill lifecycle ---
        tables_before = http_json("GET", "/tables", token=token)
        table_row_before = next((r for r in tables_before if r["id"] == table["id"]), None)
        check(
            table_row_before is not None and table_row_before["status"] == "available",
            "table starts available before the new order lifecycle",
            checks,
        )
        table_order = http_json(
            "POST",
            "/pos/orders",
            token=token,
            terminal_id=identity["terminal_id"],
            idem=f"{RUN_ID}-table-order-1",
            payload={
                "type": "dine_in",
                "table_id": table["id"],
                "shift_id": shift_id,
                "notes": MARKER,
                "lines": [{"menu_item_id": items["coffee"], "qty": 1}],
            },
        )
        check(table_order.get("status") == "open", "table order creates in open status", checks)
        tables_after_create = http_json("GET", "/tables", token=token)
        table_row_after_create = next((r for r in tables_after_create if r["id"] == table["id"]), None)
        check(
            table_row_after_create is not None and table_row_after_create["status"] == "occupied",
            "table auto-flips to occupied once an order is sent to the kitchen",
            checks,
        )

        add_lines_key = f"{RUN_ID}-table-order-1-lines-1"
        appended = http_json(
            "POST",
            f"/pos/orders/{table_order['id']}/lines",
            token=token,
            terminal_id=identity["terminal_id"],
            idem=add_lines_key,
            payload={"lines": [{"menu_item_id": items["snack"], "qty": 1}]},
        )
        check(
            len(appended.get("lines", [])) == 2
            and appended["total_minor"] > table_order["total_minor"],
            "add-lines appends to the open table order and recomputes the total",
            checks,
        )
        replayed = http_json(
            "POST",
            f"/pos/orders/{table_order['id']}/lines",
            token=token,
            terminal_id=identity["terminal_id"],
            idem=add_lines_key,
            payload={"lines": [{"menu_item_id": items["snack"], "qty": 1}]},
        )
        check(
            len(replayed.get("lines", [])) == 2 and replayed["total_minor"] == appended["total_minor"],
            "add-lines is idempotent — replaying the same key does not double the lines",
            checks,
        )

        held = http_json(
            "PATCH",
            f"/pos/orders/{table_order['id']}/send-to-pos",
            token=token,
            terminal_id=identity["terminal_id"],
            payload={},
        )
        check(held.get("status") == "held", "sending a table order to POS marks it held", checks)

        _, _, resend_raw = http_request(
            "PATCH",
            f"/pos/orders/{table_order['id']}/send-to-pos",
            token=token,
            terminal_id=identity["terminal_id"],
            payload={},
            expected=(422,),
        )
        check(
            b"cannot send" in resend_raw.lower(),
            "sending an already-held order to POS again is rejected",
            checks,
        )

        held_queue = http_json("GET", "/pos/orders", token=token, terminal_id=identity["terminal_id"])
        # Note: default list_orders call has no status filter above; fetch the held-only view explicitly.
        held_queue_only = http_json(
            "GET", "/pos/orders?status=held", token=token, terminal_id=identity["terminal_id"]
        )
        held_row = next((r for r in held_queue_only if r["id"] == table_order["id"]), None)
        check(
            held_row is not None and held_row.get("source_label") == f"Table {table['code']}",
            "held-orders queue labels a table order by its table code",
            checks,
        )

        add_after_held = http_json(
            "POST",
            f"/pos/orders/{table_order['id']}/lines",
            token=token,
            terminal_id=identity["terminal_id"],
            idem=f"{RUN_ID}-table-order-1-lines-2",
            payload={"lines": [{"menu_item_id": items["coffee"], "qty": 1}]},
        )
        check(
            len(add_after_held.get("lines", [])) == 3,
            "POS can still add lines to a held order found via search",
            checks,
        )

        table_payment = http_json(
            "POST",
            f"/pos/orders/{table_order['id']}/payments",
            token=token,
            terminal_id=identity["terminal_id"],
            idem=f"{RUN_ID}-table-order-1-pay",
            payload={
                "method": "cash",
                "amount_minor": add_after_held["total_minor"],
                "tendered_minor": add_after_held["total_minor"],
                "ref_external": MARKER,
            },
        )
        check(table_payment.get("order_status") == "paid", "held table order pays and closes", checks)

        tables_after_pay = http_json("GET", "/tables", token=token)
        table_row_after_pay = next((r for r in tables_after_pay if r["id"] == table["id"]), None)
        check(
            table_row_after_pay is not None and table_row_after_pay["status"] == "available",
            "table auto-flips back to available once its order is paid",
            checks,
        )

        _, _, add_after_paid_raw = http_request(
            "POST",
            f"/pos/orders/{table_order['id']}/lines",
            token=token,
            terminal_id=identity["terminal_id"],
            idem=f"{RUN_ID}-table-order-1-lines-3",
            payload={"lines": [{"menu_item_id": items["coffee"], "qty": 1}]},
            expected=(422,),
        )
        check(
            b"cannot add lines" in add_after_paid_raw.lower(),
            "add-lines rejects a paid order",
            checks,
        )
        check(isinstance(held_queue, list), "unfiltered orders list still reads", checks)

        _, _, missing_terminal_raw = http_request(
            "POST",
            "/gaming/sessions/start",
            token=token,
            payload={
                "station_id": stations["ps5"],
                "shift_id": shift_id,
                "customer_name": MARKER,
                "timer_minutes": 60,
            },
            expected=(422,),
        )
        check(
            "x-terminal-id" in missing_terminal_raw.decode("utf-8", errors="replace").lower(),
            "gaming start rejects a missing terminal before creating a session",
            checks,
        )

        for station_type, station_id in stations.items():
            session_started = http_json(
                "POST",
                "/gaming/sessions/start",
                token=token,
                terminal_id=identity["terminal_id"],
                payload={
                    "station_id": station_id,
                    "shift_id": shift_id,
                    "customer_name": MARKER,
                    "customer_phone": f"7{secrets.randbelow(10**9):09d}",
                    "timer_minutes": 60,
                },
            )
            check(
                session_started.get("timer_minutes") == 60
                and bool(session_started.get("timer_ends_at")),
                f"gaming {station_type} session starts with a 60-minute timer",
                checks,
            )
            extended = http_json(
                "PATCH",
                f"/gaming/sessions/{session_started['id']}/timer",
                token=token,
                terminal_id=identity["terminal_id"],
                payload={"timer_minutes": 90},
            )
            check(
                extended.get("timer_minutes") == 90,
                f"gaming {station_type} session timer extends",
                checks,
            )
            time.sleep(0.05)
            stopped = http_json(
                "POST",
                f"/gaming/sessions/{session_started['id']}/stop",
                token=token,
                terminal_id=identity["terminal_id"],
                payload={},
            )
            check(
                stopped.get("status") == "ended",
                f"gaming {station_type} session starts and stops",
                checks,
            )

            sent = http_json(
                "POST",
                f"/gaming/sessions/{session_started['id']}/send-to-pos",
                token=token,
                terminal_id=identity["terminal_id"],
                payload={},
            )
            check(
                bool(sent.get("order_id")) and sent.get("amount_minor") == stopped.get("amount_minor"),
                f"gaming {station_type} session sends to POS and matches the stopped amount",
                checks,
            )
            session_order = http_json("GET", f"/pos/orders/{sent['order_id']}", token=token)
            check(
                session_order.get("status") == "held"
                and session_order.get("type") == "session"
                and len(session_order.get("lines", [])) == 1
                and session_order["lines"][0]["taxable_value_minor"]
                + session_order["lines"][0]["cgst_minor"]
                + session_order["lines"][0]["sgst_minor"]
                + session_order["lines"][0]["igst_minor"]
                == session_order["total_minor"],
                f"gaming {station_type} session order is held with one internally-consistent GST line",
                checks,
            )
            _, _, resend_session_raw = http_request(
                "POST",
                f"/gaming/sessions/{session_started['id']}/send-to-pos",
                token=token,
                terminal_id=identity["terminal_id"],
                payload={},
                expected=(422,),
            )
            check(
                b"already" in resend_session_raw.lower(),
                f"gaming {station_type} session cannot be sent to POS twice",
                checks,
            )
            session_after_send = http_json(
                "GET", "/gaming/sessions?status=ended", token=token,
            )
            check(
                any(
                    row["id"] == session_started["id"] and row.get("order_id") == sent["order_id"]
                    for row in session_after_send
                ),
                f"gaming {station_type} session records its order_id after send-to-pos",
                checks,
            )
            held_session_queue = http_json(
                "GET", "/pos/orders?status=held", token=token, terminal_id=identity["terminal_id"]
            )
            held_session_row = next(
                (r for r in held_session_queue if r["id"] == sent["order_id"]), None
            )
            check(
                held_session_row is not None and bool(held_session_row.get("source_label")),
                f"gaming {station_type} session order carries a station source_label in the held queue",
                checks,
            )
            # Pay off every station's session order — an unpaid held order
            # would otherwise block closing the temp shift at the end of this run.
            session_payment = http_json(
                "POST",
                f"/pos/orders/{sent['order_id']}/payments",
                token=token,
                terminal_id=identity["terminal_id"],
                idem=f"{RUN_ID}-session-order-pay-{station_type}",
                payload={
                    "method": "cash",
                    "amount_minor": session_order["total_minor"],
                    "tendered_minor": session_order["total_minor"],
                    "ref_external": MARKER,
                },
            )
            check(
                session_payment.get("order_status") == "paid",
                f"gaming {station_type} session order pays and closes end to end",
                checks,
            )

        booking = http_json(
            "POST",
            "/gaming/bookings",
            token=token,
            payload={
                "station_id": stations["vr"],
                "starts_at": (datetime.now(timezone.utc) + timedelta(hours=2)).isoformat(),
                "ends_at": (datetime.now(timezone.utc) + timedelta(hours=3)).isoformat(),
                "guest_name": MARKER,
                "contact": MARKER,
                "party_size": 2,
                "deposit_minor": 1000,
            },
        )
        check(bool(booking.get("id")), "gaming booking creates for future slot", checks)

        sessions_list = http_json("GET", "/gaming/sessions", token=token)
        orders_list = http_json("GET", "/pos/orders", token=token)
        audit_rows = http_json("GET", "/admin/audit", token=token, audit_token=audit_token)
        audit_facets = http_json("GET", "/admin/audit/facets", token=token, audit_token=audit_token)
        check(isinstance(sessions_list, list), "gaming sessions list reads", checks)
        check(isinstance(orders_list, list), "POS orders list reads", checks)
        check(isinstance(audit_rows, list), "audit log reads after unlock", checks)
        check(isinstance(audit_facets, dict), "audit facets read after unlock", checks)

        table_rows = http_json("GET", "/tables", token=token)
        customer_rows = http_json("GET", "/customers", token=token)
        event_rows = http_json("GET", "/events/all", token=token)
        check(
            any(str(row.get("id")) == table["id"] for row in table_rows),
            "tables list reads created table",
            checks,
        )
        check(
            any(str(row.get("id")) == customer["id"] for row in customer_rows),
            "customers list reads created customer",
            checks,
        )
        check(
            any(str(row.get("id")) == event["id"] for row in event_rows),
            "events list reads created event",
            checks,
        )

        now = datetime.now(timezone.utc)
        today = now.date().isoformat()
        yyyy_mm = now.strftime("%Y-%m")
        fy_start = now.year if now.month >= 4 else now.year - 1
        fy = f"{fy_start}-{str(fy_start + 1)[-2:]}"
        fiscal_quarter = ((now.month - 4) % 12) // 3 + 1
        report_daily = http_json("GET", f"/reports/daily?on_date={today}", token=token)
        report_monthly = http_json("GET", f"/reports/monthly?yyyy_mm={yyyy_mm}", token=token)
        report_quarterly = http_json(
            "GET",
            f"/reports/quarterly?fy={fy}&q={fiscal_quarter}",
            token=token,
        )
        report_yearly = http_json("GET", f"/reports/yearly?fy={fy}", token=token)
        report_monthly_default = http_json("GET", "/reports/monthly", token=token)
        report_quarterly_default = http_json("GET", "/reports/quarterly", token=token)
        report_yearly_default = http_json("GET", "/reports/yearly", token=token)
        legacy_pnl_default = http_json("GET", "/finance/pnl", token=token)
        report_range = http_json(
            "GET",
            f"/reports/range?from_date={today}&to_date={today}",
            token=token,
        )
        tax_compliance = http_json(
            "GET",
            f"/reports/tax-compliance?from_date={today}&to_date={today}",
            token=token,
        )
        for label, report in (
            ("daily", report_daily),
            ("monthly", report_monthly),
            ("quarterly", report_quarterly),
            ("yearly", report_yearly),
            ("range", report_range),
        ):
            check(
                isinstance(report.get("net_profit_minor"), int),
                f"{label} P&L report computes",
                checks,
            )
        daily_payments = report_daily.get("payments_received", {})
        check(
            isinstance(report_daily.get("refunds_issued_minor"), int)
            and report_daily.get("net_payments_received_minor")
            == daily_payments.get("total_minor", 0)
            - report_daily.get("refunds_issued_minor", 0),
            "reports separate gross payments, refunds and net movement",
            checks,
        )
        check(
            isinstance(tax_compliance.get("issues"), list)
            and isinstance(tax_compliance.get("checked_orders"), int),
            "GST compliance report computes",
            checks,
        )
        check(
            all(
                isinstance(row.get("net_profit_minor"), int)
                for row in (
                    report_monthly_default,
                    report_quarterly_default,
                    report_yearly_default,
                )
            ),
            "monthly, quarterly, and yearly reports have safe current-period defaults",
            checks,
        )
        check(
            isinstance(legacy_pnl_default.get("net_profit_minor"), int),
            "legacy P&L has safe current-month defaults",
            checks,
        )

        _, _, gstr1 = http_request(
            "GET",
            f"/reports/gstr1.csv?yyyy_mm={yyyy_mm}",
            token=token,
            expected=(200,),
        )
        _, _, gstr3b = http_request(
            "GET",
            f"/reports/gstr3b.csv?yyyy_mm={yyyy_mm}",
            token=token,
            expected=(200,),
        )
        check(b"GSTR-1" in gstr1 and len(gstr1) > 100, "GSTR-1 CSV exports", checks)
        check(b"GSTR-3B" in gstr3b and len(gstr3b) > 100, "GSTR-3B CSV exports", checks)

        dashboard = http_json("GET", f"/analytics/dashboard?on_date={today}", token=token)
        _, _, analytics_csv = http_request(
            "GET",
            f"/analytics/export.csv?period_start={today}&period_end={today}",
            token=token,
            expected=(200,),
        )
        check(
            isinstance(dashboard.get("revenue_total_minor"), int)
            and isinstance(dashboard.get("orders_count"), int),
            "analytics dashboard computes",
            checks,
        )
        check(
            b"D Company ERP analytics export" in analytics_csv,
            "analytics CSV exports",
            checks,
        )

        chart = http_json("GET", "/accounting/chart-of-accounts", token=token)
        trial_balance = http_json("GET", f"/accounting/trial-balance?as_of={today}", token=token)
        balance_sheet = http_json("GET", f"/accounting/balance-sheet?as_of={today}", token=token)
        ledger = http_json(
            "GET",
            f"/accounting/general-ledger?from_date={today}&to_date={today}",
            token=token,
        )
        check(isinstance(chart, list), "accounting chart of accounts reads", checks)
        check(
            isinstance(trial_balance.get("lines"), list)
            and isinstance(trial_balance.get("is_balanced"), bool),
            "accounting trial balance computes",
            checks,
        )
        check(
            isinstance(balance_sheet.get("assets"), dict)
            and isinstance(balance_sheet.get("is_balanced"), bool),
            "accounting balance sheet computes",
            checks,
        )
        check(isinstance(ledger, list), "accounting general ledger reads", checks)

        valuation = http_json("GET", "/insights/inventory/valuation", token=token)
        recipe_margin = http_json("GET", "/insights/menu/recipe-margin", token=token)
        growth = http_json("GET", "/insights/growth?period=mom", token=token)
        top_items = http_json(
            "GET",
            f"/insights/top-items?from_date={today}&to_date={today}",
            token=token,
        )
        heatmap = http_json(
            "GET",
            f"/insights/heatmap?from_date={today}&to_date={today}",
            token=token,
        )
        losses = http_json(
            "GET",
            f"/insights/losses?from_date={today}&to_date={today}",
            token=token,
        )
        check(
            isinstance(valuation.get("lines"), list)
            and isinstance(valuation.get("total_valuation_minor"), int),
            "inventory valuation insight computes",
            checks,
        )
        check(isinstance(recipe_margin, list), "recipe margin insight reads", checks)
        check(
            isinstance(growth.get("current"), dict),
            "growth insight computes",
            checks,
        )
        check(isinstance(top_items, list), "top-items insight reads", checks)
        check(isinstance(heatmap, list), "sales heatmap insight reads", checks)
        check(
            isinstance(losses.get("lines"), list),
            "inventory loss insight computes",
            checks,
        )

        cancelled = http_json(
            "POST",
            f"/memberships/{subscription['id']}/cancel",
            token=token,
            payload={},
        )
        check(
            cancelled.get("auto_renew") is False
            and cancelled.get("cancelled_at") is not None,
            "membership cancellation persists",
            checks,
        )

        http_request(
            "POST",
            "/customers",
            token=token,
            payload={
                "phone": f"5{secrets.randbelow(10**9):09d}",
                "name": "X" * 201,
            },
            expected=(422,),
        )
        check(True, "customer overlength input is rejected before persistence", checks)
        http_request(
            "POST",
            "/gaming/bookings",
            token=token,
            payload={
                "station_id": stations["vr"],
                "starts_at": (datetime.now(timezone.utc) + timedelta(days=3)).isoformat(),
                "ends_at": (datetime.now(timezone.utc) + timedelta(days=3, hours=1)).isoformat(),
                "guest_name": MARKER,
                "contact": "X" * 51,
                "party_size": 2,
                "deposit_minor": 0,
            },
            expected=(422,),
        )
        check(True, "gaming overlength contact is rejected before persistence", checks)
        http_request(
            "POST",
            "/pos/orders",
            token=token,
            terminal_id=identity["terminal_id"],
            idem="X" * 161,
            payload={
                "type": "takeaway",
                "shift_id": shift_id,
                "lines": [{"menu_item_id": items["coffee"], "qty": 1}],
            },
            expected=(400,),
        )
        check(True, "oversized idempotency key is rejected cleanly", checks)

        http_request(
            "POST",
            "/auth/login",
            payload={"email": identity["email"], "password": "definitely-wrong"},
            expected=(401,),
        )
        normalized_login = http_json(
            "POST",
            "/auth/login",
            payload={
                "email": f"  {identity['email'].upper()}  ",
                "password": identity["password"],
            },
        )
        check(
            isinstance(normalized_login.get("access_token"), str),
            "correct credentials recover immediately after failed attempt",
            checks,
        )

        close = http_json(
            "POST",
            f"/pos/shifts/{shift_id}/close",
            token=token,
            terminal_id=identity["terminal_id"],
            payload={"counted_minor": total_minor + 1000},
        )
        check(close.get("status") == "closed", "POS shift closes", checks)

        otp_request = http_json(
            "POST",
            "/auth/password-reset/request",
            payload={"email": identity["email"]},
            expected=(202,),
        )
        check(
            isinstance(otp_request.get("challenge_id"), str)
            and otp_request.get("destination") == "b***@retrocafe.online",
            "password-reset OTP is accepted by the configured security mailbox",
            checks,
        )

        async with AsyncSessionLocal() as session:
            user = await session.get(User, UUID(identity["user_id"]))
            if user is None:
                raise E2EError("temporary user disappeared before session-revocation check")
            user.auth_version += 1
            await session.commit()

        http_request(
            "GET",
            "/auth/me",
            token=token,
            expected=(401,),
        )
        check(True, "security change revokes the existing access token", checks)
        http_request(
            "POST",
            "/auth/refresh",
            payload={"refresh_token": refreshed["refresh_token"]},
            expected=(401,),
        )
        check(True, "security change revokes the existing refresh token", checks)

        status = "PASS"
    except Exception as exc:
        failures.append(str(exc))
    finally:
        try:
            cleanup_counts = await cleanup(identity)
        except Exception as exc:
            failures.append(f"cleanup failed: {exc}")
            status = "FAIL"
        try:
            residual_counts = await residual_scan()
            if any(residual_counts.values()):
                failures.append(f"residual marker rows remain: {residual_counts}")
                status = "FAIL"
        except Exception as exc:
            failures.append(f"residual scan failed: {exc}")
            status = "FAIL"

    print(json.dumps({
        "status": status if not failures else "FAIL",
        "run_id": RUN_ID,
        "marker": MARKER,
        "base_url": BASE_URL,
        "checks": checks,
        "cleanup_counts": cleanup_counts,
        "residual_counts": residual_counts,
        "failures": failures,
    }, indent=2, sort_keys=True))
    return 0 if status == "PASS" and not failures else 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
