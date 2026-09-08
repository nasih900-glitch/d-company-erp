#!/usr/bin/env python3
"""Create and verify a disposable tenant for the Android physical audit lane.

This helper is intentionally fail-closed.  It accepts only a freshly migrated,
empty PostgreSQL database on the local machine whose name begins with
``dcompany_physical_audit_``.  It must never be pointed at staging or production.

Usage (from ``backend/``)::

    PHYSICAL_AUDIT_CONFIRMATION=I_UNDERSTAND_THIS_IS_A_DISPOSABLE_LOCAL_DATABASE \
    PHYSICAL_AUDIT_EXPECTED_DATABASE=dcompany_physical_audit_... \
    PHYSICAL_AUDIT_USER_EMAIL=employee-...@physical-audit.test \
    PHYSICAL_AUDIT_USER_PASSWORD='generated-secret' \
    python -m scripts.physical_audit_fixture seed

    python -m scripts.physical_audit_fixture verify

The seed command never prints the password.  ``verify`` is a post-run business
reconciliation gate; a green UI driver without this database proof is not a
complete business-workflow result.
"""

from __future__ import annotations

import argparse
import asyncio
import ipaddress
import json
import os
from collections import Counter
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from pathlib import Path
from urllib.parse import urlparse
from uuid import UUID, uuid4

from sqlalchemy import func, select, text

from app.core.config import get_settings
from app.core.db import AsyncSessionLocal
from app.core.security import hash_password
from app.models import (
    Account,
    AuditLog,
    Batch,
    Branch,
    Company,
    GamingPackage,
    GamingSession,
    GamingSessionAddon,
    GamingSessionExtension,
    Ingredient,
    MenuCategory,
    MenuItem,
    Order,
    OrderLine,
    Payment,
    Recipe,
    RecipeLine,
    Role,
    Shift,
    Station,
    StockMovement,
    Terminal,
    User,
    UserRole,
)
from app.models.gaming import GamingPauseEvent
from app.services.accounting.accounts import DEFAULT_CHART_OF_ACCOUNTS
from app.services.auth.otp import normalize_account_email
from app.services.gaming.tariff_catalog import upsert_d_company_gaming_tariff

CONFIRMATION = "I_UNDERSTAND_THIS_IS_A_DISPOSABLE_LOCAL_DATABASE"
DATABASE_PREFIX = "dcompany_physical_audit_"
FIXTURE_NAME = "D Company Physical Audit"
FIXTURE_BRANCH = "Physical Audit Shop"
FIXTURE_TERMINAL = "Physical Audit Hybrid"
FIXTURE_EMPLOYEE = "Audit Employee 1"
FIXTURE_COLA_SKU = "AUDIT-COLA"
FIXTURE_COLA_INGREDIENT_SKU = "AUDIT-COLA-UNIT"
FIXTURE_COLA_OPENING_QTY = Decimal("10.0000")
FIXTURE_COLA_COGS_MINOR = 2_000
FIXTURE_CRISPS_INGREDIENT_SKU = "AUDIT-CRISPS-UNIT"
FIXTURE_CRISPS_OPENING_QTY = Decimal("10.0000")
FIXTURE_CRISPS_COGS_MINOR = 1_000
FIXTURE_PAUSE_REASON = "Physical audit pause stability"
FIXTURE_RESUME_REASON = "Continue session"
FIXTURE_MINIMUM_PAUSE_DURATION_MS = 8_000


class PhysicalAuditFixtureError(RuntimeError):
    pass


def _is_loopback(host: str | None) -> bool:
    if host is None:
        return False
    if host.lower() == "localhost":
        return True
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        try:
            return ipaddress.ip_interface(host).ip.is_loopback
        except ValueError:
            return False


def _parse_response_datetime(value: object) -> datetime | None:
    if not isinstance(value, str) or not value.strip():
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        return None
    return parsed.astimezone(UTC)


def _pause_event_interval_matches_authoritative_duration(
    pause_at: datetime,
    resume_at: datetime,
    paused_duration_ms: int,
) -> bool:
    """Match persisted event timestamps to the server's floored millisecond duration."""
    if paused_duration_ms < 0:
        return False
    delta = resume_at - pause_at
    interval_us = (
        delta.days * 86_400_000_000
        + delta.seconds * 1_000_000
        + delta.microseconds
    )
    authoritative_floor_us = paused_duration_ms * 1_000
    # PostgreSQL retains microseconds while pause_clock.duration_ms deliberately
    # floors to milliseconds.  The only valid difference is therefore 0..999us;
    # network or device timing cannot justify a wider tolerance because both
    # event timestamps and the duration are produced by the same server clock.
    return authoritative_floor_us <= interval_us < authoritative_floor_us + 1_000


def _receipt_amounts_match(
    line_total_minor: int,
    total_minor: int,
    round_off_minor: int,
    paid_minor: int,
    tip_minor: int = 0,
    manual_discount_minor: int = 0,
    points_redeemed_minor: int = 0,
) -> bool:
    """Independently reconcile line rounding, then order-level adjustments."""
    rounded_lines = ((line_total_minor + 50) // 100) * 100
    expected_total = (
        rounded_lines + tip_minor - manual_discount_minor - points_redeemed_minor
    )
    return (
        line_total_minor >= 0
        and tip_minor >= 0
        and 0 <= manual_discount_minor <= rounded_lines
        and 0 <= points_redeemed_minor <= rounded_lines - manual_discount_minor
        and expected_total >= 0
        and round_off_minor == rounded_lines - line_total_minor
        and total_minor == expected_total
        and paid_minor == expected_total
    )


def _single_shift_linkage_audit_failures(
    links: list[dict], audit_rows: list[dict]
) -> list[str]:
    """Prove each ordinary Hybrid send, not an impossible cross-terminal handoff."""
    failures = []
    if any(row.get("action") == "gaming_session_handoff_to_pos" for row in audit_rows):
        failures.append("unexpected cross-terminal handoff in the single-shift fixture")
    for link in links:
        session_id = link["session_id"]
        updates = [
            row for row in audit_rows
            if row.get("entity_type") == "GamingSession"
            and row.get("entity_id") == session_id
            and row.get("action") == "update"
            and "order_id" in (row.get("after") or {})
        ]
        creates = [
            row for row in audit_rows
            if row.get("entity_type") == "Order"
            and row.get("entity_id") == link["order_id"]
            and row.get("action") == "create"
        ]
        if len(updates) != 1 or len(creates) != 1:
            failures.append(f"send-to-POS audit cardinality failed for session {session_id}")
            continue
        update, create = updates[0], creates[0]
        before, after = update.get("before") or {}, update.get("after") or {}
        order_after = create.get("after") or {}
        context_matches = all(
            row.get(field) == link[field]
            for row in (update, create)
            for field in ("company_id", "actor_user_id", "terminal_id")
        )
        if not (
            context_matches
            and "order_id" in before
            and before["order_id"] is None
            and after.get("order_id") == link["order_id"]
            and after.get("sent_to_pos_by") == link["actor_user_id"]
            and link["sent_to_pos_at"] is not None
            and after.get("sent_to_pos_at") == link["sent_to_pos_at"]
            and all(
                order_after.get(field) == link[field]
                for field in ("company_id", "branch_id", "terminal_id", "shift_id")
            )
            and order_after.get("id") == link["order_id"]
            and order_after.get("opened_by") == link["actor_user_id"]
            and order_after.get("type") == "session"
            and order_after.get("status") == "held"
        ):
            failures.append(f"send-to-POS audit identity failed for session {session_id}")
    return failures


def _validated_environment() -> str:
    if os.environ.get("PHYSICAL_AUDIT_CONFIRMATION") != CONFIRMATION:
        raise PhysicalAuditFixtureError(
            "Set PHYSICAL_AUDIT_CONFIRMATION to the exact disposable-database token"
        )
    expected = os.environ.get("PHYSICAL_AUDIT_EXPECTED_DATABASE", "").strip()
    if not expected.startswith(DATABASE_PREFIX):
        raise PhysicalAuditFixtureError(
            f"PHYSICAL_AUDIT_EXPECTED_DATABASE must start with {DATABASE_PREFIX!r}"
        )
    settings = get_settings()
    if settings.env not in {"dev", "test"}:
        raise PhysicalAuditFixtureError("The physical audit fixture requires ENV=dev or ENV=test")
    parsed = urlparse(str(settings.database_url))
    if not _is_loopback(parsed.hostname):
        raise PhysicalAuditFixtureError("DATABASE_URL must point to localhost/loopback PostgreSQL")
    return expected


async def _assert_database(session, *, require_empty: bool) -> str:
    expected = _validated_environment()
    database, server_address = (
        await session.execute(
            text("SELECT current_database(), COALESCE(inet_server_addr()::text, 'unix_socket')")
        )
    ).one()
    database = str(database)
    server_address = str(server_address)
    if database != expected:
        raise PhysicalAuditFixtureError(
            f"Connected database {database!r} does not match expected {expected!r}"
        )
    if server_address != "unix_socket" and not _is_loopback(server_address):
        raise PhysicalAuditFixtureError(f"PostgreSQL server {server_address!r} is not local")
    if require_empty:
        company_count = int(
            (await session.execute(select(func.count()).select_from(Company))).scalar_one()
        )
        if company_count != 0:
            raise PhysicalAuditFixtureError(
                "The fixture may only seed a freshly migrated database with zero companies"
            )
    return database


def _manifest_path() -> Path | None:
    raw = os.environ.get("PHYSICAL_AUDIT_MANIFEST_PATH", "").strip()
    return Path(raw).expanduser().resolve() if raw else None


def _write_json(payload: dict) -> None:
    rendered = json.dumps(payload, indent=2, sort_keys=True)
    path = _manifest_path()
    if path is not None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(rendered + "\n", encoding="utf-8")
    print(rendered)


async def seed() -> None:
    email_raw = os.environ.get("PHYSICAL_AUDIT_USER_EMAIL", "").strip()
    password = os.environ.get("PHYSICAL_AUDIT_USER_PASSWORD", "")
    email = normalize_account_email(email_raw)
    if not email.endswith("@physical-audit.test"):
        raise PhysicalAuditFixtureError(
            "PHYSICAL_AUDIT_USER_EMAIL must use the synthetic @physical-audit.test domain"
        )
    if len(password) < 16:
        raise PhysicalAuditFixtureError(
            "PHYSICAL_AUDIT_USER_PASSWORD must be a generated value of at least 16 characters"
        )

    async with AsyncSessionLocal() as session:
        database = await _assert_database(session, require_empty=True)

        company = Company(
            id=uuid4(),
            name=FIXTURE_NAME,
            legal_name="Synthetic tablet acceptance tenant",
            currency="INR",
            currency_minor_units=100,
            timezone="Asia/Kolkata",
            country="IN",
            gst_registration_type="unregistered",
            is_composition=False,
            e_invoicing_enabled=False,
        )
        session.add(company)
        await session.flush()

        branch = Branch(
            id=uuid4(),
            company_id=company.id,
            name=FIXTURE_BRANCH,
            code="QA",
            invoice_series_code="QA",
            timezone="Asia/Kolkata",
            state_code="32",
        )
        session.add(branch)
        await session.flush()

        terminal = Terminal(
            id=uuid4(),
            branch_id=branch.id,
            name=FIXTURE_TERMINAL,
            purpose="hybrid",
            is_active=True,
            device_id=f"physical-audit-{uuid4().hex}",
        )
        role = Role(
            id=uuid4(),
            company_id=company.id,
            code="super_owner",
            name="Synthetic Audit Owner",
            description="Disposable full-access identity for the physical audit lane",
            permissions=[],
        )
        session.add_all([terminal, role])
        await session.flush()

        user = User(
            id=uuid4(),
            company_id=company.id,
            email=email,
            password_hash=hash_password(password),
            name=FIXTURE_EMPLOYEE,
            status="active",
        )
        session.add(user)
        await session.flush()
        session.add(
            UserRole(
                id=uuid4(),
                user_id=user.id,
                role_id=role.id,
                branch_id=branch.id,
            )
        )

        for definition in DEFAULT_CHART_OF_ACCOUNTS:
            session.add(
                Account(
                    id=uuid4(),
                    company_id=company.id,
                    **definition.seed_dict(),
                )
            )

        drinks = MenuCategory(
            id=uuid4(),
            company_id=company.id,
            name="Synthetic Drinks & Snacks",
            sort_order=10,
            icon="local_drink",
            is_gaming_centre_catalog=True,
        )
        session.add(drinks)
        await session.flush()
        audit_cola = MenuItem(
            id=uuid4(),
            company_id=company.id,
            category_id=drinks.id,
            sku=FIXTURE_COLA_SKU,
            name="Audit Cola",
            description="Synthetic product for physical workflow verification",
            type="drink",
            base_price_minor=5_000,
            tax_rate=0,
            hsn_code="220210",
            price_includes_tax=True,
            is_available=True,
        )
        audit_crisps = MenuItem(
            id=uuid4(),
            company_id=company.id,
            category_id=drinks.id,
            sku="AUDIT-CRISPS",
            name="Audit Crisps",
            description="Synthetic product used to prove reasoned add-on voids",
            type="food",
            base_price_minor=3_000,
            tax_rate=0,
            hsn_code="190590",
            price_includes_tax=True,
            is_available=True,
        )
        session.add_all([audit_cola, audit_crisps])
        await session.flush()

        cola_stock = Ingredient(
            id=uuid4(),
            company_id=company.id,
            sku=FIXTURE_COLA_INGREDIENT_SKU,
            name="Audit Cola unit",
            base_unit="unit",
            reorder_threshold=Decimal("2.0000"),
            reorder_qty=Decimal("10.0000"),
            avg_cost_minor=FIXTURE_COLA_COGS_MINOR,
            current_qty=FIXTURE_COLA_OPENING_QTY,
        )
        cola_recipe = Recipe(
            id=uuid4(),
            menu_item_id=audit_cola.id,
            name="Audit Cola packaged unit",
            yield_qty=Decimal("1.0000"),
            version=1,
            is_active=True,
            cost_minor=FIXTURE_COLA_COGS_MINOR,
        )
        crisps_stock = Ingredient(
            id=uuid4(),
            company_id=company.id,
            sku=FIXTURE_CRISPS_INGREDIENT_SKU,
            name="Audit Crisps unit",
            base_unit="unit",
            reorder_threshold=Decimal("2.0000"),
            reorder_qty=Decimal("10.0000"),
            avg_cost_minor=FIXTURE_CRISPS_COGS_MINOR,
            current_qty=FIXTURE_CRISPS_OPENING_QTY,
        )
        crisps_recipe = Recipe(
            id=uuid4(),
            menu_item_id=audit_crisps.id,
            name="Audit Crisps packaged unit",
            yield_qty=Decimal("1.0000"),
            version=1,
            is_active=True,
            cost_minor=FIXTURE_CRISPS_COGS_MINOR,
        )
        session.add_all([cola_stock, cola_recipe, crisps_stock, crisps_recipe])
        await session.flush()
        cola_batch = Batch(
            id=uuid4(),
            ingredient_id=cola_stock.id,
            branch_id=branch.id,
            received_at=datetime.now(UTC),
            qty_initial=FIXTURE_COLA_OPENING_QTY,
            qty_on_hand=FIXTURE_COLA_OPENING_QTY,
            cost_per_unit_minor=FIXTURE_COLA_COGS_MINOR,
            lot_code="PHYSICAL-AUDIT-OPENING",
        )
        crisps_batch = Batch(
            id=uuid4(),
            ingredient_id=crisps_stock.id,
            branch_id=branch.id,
            received_at=datetime.now(UTC),
            qty_initial=FIXTURE_CRISPS_OPENING_QTY,
            qty_on_hand=FIXTURE_CRISPS_OPENING_QTY,
            cost_per_unit_minor=FIXTURE_CRISPS_COGS_MINOR,
            lot_code="PHYSICAL-AUDIT-OPENING",
        )
        session.add_all(
            [
                RecipeLine(
                    id=uuid4(),
                    recipe_id=cola_recipe.id,
                    ingredient_id=cola_stock.id,
                    qty=Decimal("1.0000"),
                    wastage_pct=Decimal("0.0000"),
                ),
                cola_batch,
                RecipeLine(
                    id=uuid4(),
                    recipe_id=crisps_recipe.id,
                    ingredient_id=crisps_stock.id,
                    qty=Decimal("1.0000"),
                    wastage_pct=Decimal("0.0000"),
                ),
                crisps_batch,
            ]
        )

        station_specs = (
            ("PS5-01", "PS5 Station 1", "ps5", 15_000),
            ("PS5-02", "PS5 Station 2", "ps5", 15_000),
            ("PS5-03", "PS5 Station 3", "ps5", 15_000),
            ("PS5-04", "PS5 Station 4", "ps5", 15_000),
            ("SIM-01", "Racing Simulator 1", "simulator", 18_000),
            ("VR-01", "VR Pod 1", "vr", 20_000),
            ("VR-02", "VR Pod 2", "vr", 20_000),
            ("STR-01", "Streaming Booth 1", "streaming", 20_000),
            ("SH-01", "Shisha Table 1", "hookah", 35_000),
        )
        station_ids: dict[str, UUID] = {}
        for code, name, station_type, rate_minor in station_specs:
            station_id = uuid4()
            station_ids[code] = station_id
            session.add(
                Station(
                    id=station_id,
                    company_id=company.id,
                    branch_id=branch.id,
                    code=code,
                    name=name,
                    type=station_type,
                    rate_per_hour_minor=rate_minor,
                    is_active=True,
                    notes="Synthetic physical-audit station",
                    tax_rate=0,
                    rate_includes_tax=True,
                )
            )

        await session.flush()
        tariff_result = await upsert_d_company_gaming_tariff(
            session,
            company_id=company.id,
            branch_id=branch.id,
        )
        await session.commit()

        _write_json(
            {
                "fixture": "physical-audit",
                "database": database,
                "company_id": str(company.id),
                "branch_id": str(branch.id),
                "terminal_id": str(terminal.id),
                "user_id": str(user.id),
                "user_email": email,
                "ingredient_id": str(cola_stock.id),
                "batch_id": str(cola_batch.id),
                "void_control_ingredient_id": str(crisps_stock.id),
                "void_control_batch_id": str(crisps_batch.id),
                "station_ids": {key: str(value) for key, value in station_ids.items()},
                "tariff_created": list(tariff_result.created_codes),
                "tariff_count": 17,
                "menu_items": ["Audit Cola", "Audit Crisps"],
                "credential_secret_in_manifest": False,
            }
        )


async def verify() -> None:
    """Reconcile the terminal state after the physical workflow.

    This intentionally requires evidence from both payment rails, a discount,
    package and hourly sessions, a reasoned pause/resume, add-on create/void,
    extensions, and a closed shift. A shorter smoke plan will fail this gate
    and must be reported as a partial run.
    """

    async with AsyncSessionLocal() as session:
        database = await _assert_database(session, require_empty=False)
        company = (
            await session.execute(
                select(Company).where(
                    Company.name == FIXTURE_NAME,
                    Company.deleted_at.is_(None),
                )
            )
        ).scalar_one_or_none()
        if company is None:
            raise PhysicalAuditFixtureError("Synthetic physical-audit tenant was not found")

        company_id = company.id
        shifts = (
            (
                await session.execute(
                    select(Shift).where(Shift.company_id == company_id).order_by(Shift.opened_at)
                )
            )
            .scalars()
            .all()
        )
        sessions = (
            (
                await session.execute(
                    select(GamingSession).where(GamingSession.company_id == company_id)
                )
            )
            .scalars()
            .all()
        )
        orders = (
            (await session.execute(select(Order).where(Order.company_id == company_id)))
            .scalars()
            .all()
        )
        payments = (
            (
                await session.execute(
                    select(Payment)
                    .join(Order, Payment.order_id == Order.id)
                    .where(Order.company_id == company_id)
                )
            )
            .scalars()
            .all()
        )
        extensions = (
            (
                await session.execute(
                    select(GamingSessionExtension).where(
                        GamingSessionExtension.company_id == company_id
                    )
                )
            )
            .scalars()
            .all()
        )
        pause_events = (
            (
                await session.execute(
                    select(GamingPauseEvent)
                    .where(GamingPauseEvent.company_id == company_id)
                    .order_by(GamingPauseEvent.pause_version)
                )
            )
            .scalars()
            .all()
        )
        addons = (
            (
                await session.execute(
                    select(GamingSessionAddon).where(GamingSessionAddon.company_id == company_id)
                )
            )
            .scalars()
            .all()
        )
        order_lines = (
            (
                await session.execute(
                    select(OrderLine)
                    .join(Order, OrderLine.order_id == Order.id)
                    .where(Order.company_id == company_id)
                )
            )
            .scalars()
            .all()
        )
        package_codes = (
            (
                await session.execute(
                    select(GamingPackage.code)
                    .join(GamingSession, GamingSession.package_id == GamingPackage.id)
                    .where(GamingSession.company_id == company_id)
                )
            )
            .scalars()
            .all()
        )
        package_sessions = (
            (
                await session.execute(
                    select(GamingSession, GamingPackage.code)
                    .join(GamingPackage, GamingSession.package_id == GamingPackage.id)
                    .where(GamingSession.company_id == company_id)
                )
            )
            .all()
        )
        extension_receipts = (
            (
                await session.execute(
                    select(
                        GamingSessionExtension,
                        GamingPackage.code,
                        GamingSession.extra_controllers,
                    )
                    .join(
                        GamingPackage,
                        GamingSessionExtension.package_id == GamingPackage.id,
                    )
                    .join(
                        GamingSession,
                        GamingSessionExtension.gaming_session_id == GamingSession.id,
                    )
                    .where(GamingSessionExtension.company_id == company_id)
                )
            )
            .all()
        )
        fixture_user = (
            await session.execute(
                select(User).where(
                    User.company_id == company_id,
                    User.name == FIXTURE_EMPLOYEE,
                    User.deleted_at.is_(None),
                )
            )
        ).scalar_one()
        cola_stock = (
            await session.execute(
                select(Ingredient).where(
                    Ingredient.company_id == company_id,
                    Ingredient.sku == FIXTURE_COLA_INGREDIENT_SKU,
                    Ingredient.deleted_at.is_(None),
                )
            )
        ).scalar_one()
        cola_batch = (
            await session.execute(
                select(Batch).where(
                    Batch.ingredient_id == cola_stock.id,
                )
            )
        ).scalar_one()
        cola_movements = (
            (
                await session.execute(
                    select(StockMovement).where(
                        StockMovement.batch_id == cola_batch.id,
                        StockMovement.type == "sale",
                    )
                )
            )
            .scalars()
            .all()
        )
        crisps_stock = (
            await session.execute(
                select(Ingredient).where(
                    Ingredient.company_id == company_id,
                    Ingredient.sku == FIXTURE_CRISPS_INGREDIENT_SKU,
                    Ingredient.deleted_at.is_(None),
                )
            )
        ).scalar_one()
        crisps_batch = (
            await session.execute(select(Batch).where(Batch.ingredient_id == crisps_stock.id))
        ).scalar_one()
        crisps_movements = (
            (
                await session.execute(
                    select(StockMovement).where(
                        StockMovement.batch_id == crisps_batch.id,
                        StockMovement.type == "sale",
                    )
                )
            )
            .scalars()
            .all()
        )
        audit_rows = (
            (await session.execute(select(AuditLog).where(AuditLog.company_id == company_id)))
            .scalars()
            .all()
        )

        journal_imbalances = int(
            (
                await session.execute(
                    text(
                        "SELECT count(*) FROM ("
                        " SELECT je.id FROM journal_entries je"
                        " JOIN journal_lines jl ON jl.journal_entry_id = je.id"
                        " WHERE je.company_id = :company_id AND je.voided_at IS NULL"
                        " GROUP BY je.id"
                        " HAVING sum(CASE WHEN jl.side = 'dr' THEN jl.amount_minor "
                        "                 ELSE -jl.amount_minor END) <> 0"
                        ") AS unbalanced"
                    ),
                    {"company_id": company_id},
                )
            ).scalar_one()
        )

        failures: list[str] = []
        if not shifts or any(shift.status != "closed" for shift in shifts):
            failures.append("every created shift must be closed")
        if len(shifts) != 1:
            failures.append(f"expected exactly 1 shift, found {len(shifts)}")
        if not any(shift.opening_was_offline for shift in shifts):
            failures.append("no server-confirmed offline-captured shift opening exists")
        if any(
            shift.opened_by != fixture_user.id or shift.closed_by != fixture_user.id
            for shift in shifts
        ):
            failures.append("shift opener/closer attribution is not the synthetic employee")
        if any(
            session_row.status in {"active", "paused", "starting", "stopping"}
            for session_row in sessions
        ):
            failures.append("a gaming session remains active/paused/in transition")
        if any(
            session_row.status == "ended" and session_row.order_id is None
            for session_row in sessions
        ):
            failures.append("an ended gaming session remains unsent to POS")
        if any(order.status in {"open", "held"} for order in orders):
            failures.append("an open/held order remains")
        if len(sessions) != 16 or len(orders) != 16 or len(payments) != 16:
            failures.append(
                "expected exactly 16 sessions, 16 orders and 16 payments; "
                f"found {len(sessions)}, {len(orders)}, {len(payments)}"
            )
        linked_order_ids = [row.order_id for row in sessions if row.order_id is not None]
        if len(linked_order_ids) != len(set(linked_order_ids)) or len(linked_order_ids) != 16:
            failures.append("gaming session to POS order linkage is missing or duplicated")
        payments_per_order = Counter(payment.order_id for payment in payments)
        if (
            any(count != 1 for count in payments_per_order.values())
            or len(payments_per_order) != 16
        ):
            failures.append("each Gaming order must have exactly one payment")
        methods = Counter(payment.method for payment in payments)
        if methods["cash"] < 1 or methods["upi"] < 1:
            failures.append("both cash and UPI must be completed")
        if not any(order.manual_discount_minor > 0 for order in orders):
            failures.append("no paid workflow contains a manual discount")
        if not extensions:
            failures.append("no paid gaming package extension was recorded")
        if not any(not addon.voided_at for addon in addons):
            failures.append("no billable synthetic Gaming add-on was retained")
        if not any(addon.voided_at for addon in addons):
            failures.append("no reasoned Gaming add-on void was retained")
        if not any(session_row.billing_mode == "package" for session_row in sessions):
            failures.append("no package-priced gaming session was completed")
        if not any(session_row.billing_mode == "hourly" for session_row in sessions):
            failures.append("no hourly/open-ended gaming session was completed")
        if any(
            session_row.opened_by != fixture_user.id
            or session_row.stopped_by != fixture_user.id
            or session_row.sent_to_pos_by != fixture_user.id
            for session_row in sessions
        ):
            failures.append("Gaming start/stop/POS-handoff actor attribution is incomplete")
        if any(extension.created_by != fixture_user.id for extension in extensions):
            failures.append("Gaming extension actor attribution is incomplete")
        expected_pause_events = [
            ("pause", FIXTURE_PAUSE_REASON, 1),
            ("resume", FIXTURE_RESUME_REASON, 2),
        ]
        observed_pause_events = [
            (event.action, event.reason, int(event.pause_version))
            for event in pause_events
        ]
        if observed_pause_events != expected_pause_events:
            failures.append(
                "pause/resume receipts must preserve the exact action, reason and version: "
                f"{observed_pause_events}"
            )
        if any(
            event.actor_user_id != fixture_user.id
            or not shifts
            or event.terminal_id != shifts[0].terminal_id
            for event in pause_events
        ):
            failures.append("Gaming pause/resume actor or terminal attribution is incomplete")
        pause_session_ids = {event.gaming_session_id for event in pause_events}
        paused_session = next(
            (row for row in sessions if row.id in pause_session_ids),
            None,
        ) if len(pause_session_ids) == 1 else None
        paused_session_extension_codes = [
            code
            for extension, code, _extra_controllers in extension_receipts
            if paused_session is not None
            and extension.gaming_session_id == paused_session.id
        ]
        if (
            paused_session is None
            or paused_session.billing_mode != "package"
            or int(paused_session.package_price_minor_snapshot or -1) != 8_000
            or int(paused_session.package_duration_minutes_snapshot or -1) != 30
            or int(paused_session.timer_minutes or -1) != 90
            or int(paused_session.amount_minor or -1) != 18_000
            or int(paused_session.paused_duration_ms or 0)
            < FIXTURE_MINIMUM_PAUSE_DURATION_MS
            or int(paused_session.pause_version or 0) != 2
            or paused_session.paused_at is not None
            or paused_session_extension_codes != ["standard-single-extension-60m"]
        ):
            failures.append(
                "the paused Standard Single session did not preserve its locked ₹80 "
                "package, stable pause interval and billed ₹100 extension"
            )
        expected_pause_responses = [
            ("paused", 8_000, 30, "package", 8_000, 30, "single", "standard"),
            ("active", 8_000, 30, "package", 8_000, 30, "single", "standard"),
        ]
        observed_pause_responses = [
            (
                event.response.get("status"),
                int(event.response.get("amount_minor", -1)),
                int(event.response.get("timer_minutes", -1)),
                event.response.get("billing_mode"),
                int(event.response.get("package_price_minor_snapshot", -1)),
                int(event.response.get("package_duration_minutes_snapshot", -1)),
                event.response.get("package_variant_snapshot"),
                event.response.get("package_pricing_tier_snapshot"),
            )
            for event in pause_events
        ]
        if observed_pause_responses != expected_pause_responses:
            failures.append(
                "pause/resume changed the locked package amount or duration snapshot: "
                f"{observed_pause_responses}"
            )
        if len(pause_events) == 2:
            pause_event, resume_event = pause_events
            pause_response = pause_event.response
            resume_response = resume_event.response
            authoritative_pause_duration_ms = (
                int(paused_session.paused_duration_ms)
                if paused_session is not None
                and paused_session.paused_duration_ms is not None
                else -1
            )
            pause_response_at = _parse_response_datetime(pause_response.get("paused_at"))
            resume_timer_ends_at = _parse_response_datetime(
                resume_response.get("timer_ends_at")
            )
            resume_start_at = _parse_response_datetime(resume_response.get("start_at"))
            resume_pause_duration_ms = int(
                resume_response.get("paused_duration_ms", -1)
            )
            expected_resume_timer_ends_at = (
                resume_start_at
                + timedelta(
                    minutes=int(resume_response.get("timer_minutes", -1)),
                    milliseconds=resume_pause_duration_ms,
                )
                if resume_start_at is not None
                and int(resume_response.get("timer_minutes", -1)) > 0
                and resume_pause_duration_ms >= 0
                else None
            )
            if (
                int(pause_response.get("pause_version", -1)) != 1
                or pause_response_at != pause_event.occurred_at.astimezone(UTC)
                or int(pause_response.get("paused_duration_ms", -1)) != 0
                or pause_response.get("timer_ends_at") is not None
                or int(resume_response.get("pause_version", -1)) != 2
                or resume_response.get("paused_at") is not None
                or resume_pause_duration_ms < FIXTURE_MINIMUM_PAUSE_DURATION_MS
                or paused_session is None
                or resume_pause_duration_ms
                != authoritative_pause_duration_ms
                or not _pause_event_interval_matches_authoritative_duration(
                    pause_event.occurred_at,
                    resume_event.occurred_at,
                    authoritative_pause_duration_ms,
                )
                or resume_timer_ends_at is None
                or resume_timer_ends_at != expected_resume_timer_ends_at
            ):
                failures.append(
                    "pause/resume response clock fields are incomplete or internally "
                    "inconsistent (pause_version, paused_at, paused_duration_ms, "
                    "event interval, timer_ends_at)"
                )
        if any(
            addon.created_by != fixture_user.id
            or (addon.voided_at is not None and addon.voided_by != fixture_user.id)
            for addon in addons
        ):
            failures.append("Gaming add-on create/void actor attribution is incomplete")
        if any(order.opened_by != fixture_user.id for order in orders):
            failures.append("POS order opener attribution is incomplete")
        if any(payment.recorded_by != fixture_user.id for payment in payments):
            failures.append("POS payment actor attribution is incomplete")

        expected_package_sessions = Counter(
            {
                ("standard-single-session-30m", 0, 18_000): 1,
                ("standard-single-session-60m", 0, 18_000): 1,
                ("standard-dual-session-30m", 0, 10_000): 1,
                ("standard-dual-session-60m", 1, 28_000): 1,
                ("standard-dual-session-60m", 2, 40_000): 1,
                ("premium-single-session-60m", 0, 22_000): 1,
                ("premium-single-session-60m", 0, 27_000): 1,
                ("premium-dual-session-60m", 0, 34_000): 1,
                ("premium-dual-session-60m", 1, 40_000): 1,
                ("premium-dual-session-60m", 2, 40_000): 1,
                ("standard-simdrive-session-15m", 0, 7_000): 1,
                ("standard-simdrive-session-30m", 0, 10_000): 1,
                ("standard-simdrive-session-60m", 0, 18_000): 1,
            }
        )
        observed_package_sessions = Counter(
            (
                code,
                int(row.extra_controllers or 0),
                int(row.amount_minor or -1),
            )
            for row, code in package_sessions
        )
        if observed_package_sessions != expected_package_sessions:
            failures.append(
                "package/player/amount matrix differs from the 16-session physical plan: "
                f"{dict(observed_package_sessions)}"
            )

        expected_extensions = Counter(
            {
                ("standard-single-extension-60m", 0, 30, 90, 10_000, 0, 10_000): 1,
                ("standard-single-extension-30m", 0, 60, 90, 6_000, 0, 6_000): 1,
                ("standard-dual-extension-30m", 1, 60, 90, 7_000, 3_000, 10_000): 1,
                ("standard-dual-extension-60m", 2, 60, 120, 13_000, 6_000, 19_000): 1,
                ("premium-single-extension-30m", 0, 60, 90, 7_000, 0, 7_000): 1,
                ("premium-single-extension-60m", 0, 60, 120, 12_000, 0, 12_000): 1,
                ("premium-dual-extension-60m", 0, 60, 120, 15_000, 0, 15_000): 1,
                ("premium-dual-extension-60m", 1, 60, 120, 15_000, 3_000, 18_000): 1,
                ("premium-dual-extension-30m", 2, 60, 90, 9_000, 6_000, 15_000): 1,
            }
        )
        observed_extensions = Counter(
            (
                code,
                int(extra_controllers or 0),
                int(row.timer_before_minutes),
                int(row.timer_after_minutes),
                int(row.package_price_minor),
                int(row.controller_surcharge_minor),
                int(row.total_minor),
            )
            for row, code, extra_controllers in extension_receipts
        )
        if observed_extensions != expected_extensions:
            failures.append(
                "extension/controller arithmetic differs from the physical plan: "
                f"{dict(observed_extensions)}"
            )

        hourly_session_details = (
            await session.execute(
                select(GamingSession, Station)
                .join(Station, GamingSession.station_id == Station.id)
                .where(
                    GamingSession.company_id == company_id,
                    GamingSession.billing_mode == "hourly",
                )
            )
        ).all()
        hourly_station_types = Counter(
            station.type for _gaming_session, station in hourly_session_details
        )
        if hourly_station_types != Counter({"vr": 1, "streaming": 1, "hookah": 1}):
            failures.append(
                "hourly station coverage must be exactly VR, Streaming and Shisha: "
                f"{dict(hourly_station_types)}"
            )
        for hourly_session, station in hourly_session_details:
            billable_minutes = int(hourly_session.billable_minutes or 0)
            expected_amount_minor = (
                billable_minutes * int(hourly_session.rate_per_hour_minor) + 59
            ) // 60
            if (
                billable_minutes < 1
                or int(hourly_session.amount_minor or -1) != expected_amount_minor
            ):
                failures.append(
                    "hourly session duration/amount does not match authoritative "
                    f"ceiling-minute billing for station {station.code}"
                )
        if not any(
            station.code == "VR-02" for _gaming_session, station in hourly_session_details
        ):
            failures.append("the visible VR Pod 1 to VR Pod 2 transfer was not persisted")

        lines_by_order: dict[UUID, list[OrderLine]] = {}
        for line in order_lines:
            lines_by_order.setdefault(line.order_id, []).append(line)
        payments_by_order: dict[UUID, list[Payment]] = {}
        for payment in payments:
            payments_by_order.setdefault(payment.order_id, []).append(payment)
        orders_by_id = {order.id: order for order in orders}
        for hourly_session, station in hourly_session_details:
            linked_order = orders_by_id.get(hourly_session.order_id)
            linked_payments = (
                payments_by_order.get(linked_order.id, []) if linked_order is not None else []
            )
            linked_lines = [
                line for line in lines_by_order.get(hourly_session.order_id, [])
                if line.voided_at is None
            ]
            if (
                linked_order is None
                or len(linked_lines) != 1
                or int(linked_lines[0].line_total_minor) != int(hourly_session.amount_minor or -1)
                or len(linked_payments) != 1
                or not _receipt_amounts_match(
                    int(hourly_session.amount_minor or 0),
                    int(linked_order.total_minor),
                    int(linked_order.round_off_minor or 0),
                    int(linked_payments[0].amount_minor),
                )
            ):
                failures.append(
                    "hourly Gaming to POS to payment reconciliation failed for "
                    f"station {station.code}"
                )
        for order in orders:
            active_lines = [
                line for line in lines_by_order.get(order.id, []) if line.voided_at is None
            ]
            line_total = sum(int(line.line_total_minor) for line in active_lines)
            paid_total = sum(
                int(payment.amount_minor) for payment in payments_by_order.get(order.id, [])
            )
            if (
                order.status != "paid"
                or order.invoice_no is None
                or order.invoice_issued_at is None
                or order.fiscal_year is None
                or not _receipt_amounts_match(
                    line_total,
                    int(order.total_minor),
                    int(order.round_off_minor or 0),
                    paid_total,
                    int(order.tip_minor or 0),
                    int(order.manual_discount_minor or 0),
                    int(order.points_redeemed_minor or 0),
                )
            ):
                failures.append(f"receipt totals/invoice identity failed for order {order.id}")
        invoice_numbers = [order.invoice_no for order in orders if order.invoice_no is not None]
        if len(invoice_numbers) != 16 or len(set(invoice_numbers)) != 16:
            failures.append("paid receipt invoice numbers are missing or duplicated")
        for payment in payments:
            if payment.method == "cash" and (
                payment.tendered_minor is None
                or payment.change_minor is None
                or int(payment.change_minor)
                != int(payment.tendered_minor) - int(payment.amount_minor)
            ):
                failures.append(f"cash tender/change is inconsistent for payment {payment.id}")

        cash_receipts = Counter(
            (
                int(payment.amount_minor),
                int(payment.tendered_minor or -1),
                int(payment.change_minor or 0),
            )
            for payment in payments
            if payment.method == "cash"
        )
        expected_cash_receipts = Counter(
            {
                (21_000, 50_000, 29_000): 1,
                (7_000, 7_000, 0): 1,
            }
        )
        if cash_receipts != expected_cash_receipts:
            failures.append(f"cash tender/change matrix is wrong: {dict(cash_receipts)}")
        if methods != Counter({"upi": 14, "cash": 2}):
            failures.append(f"expected 14 UPI and 2 cash payments, found {dict(methods)}")

        if shifts:
            shift = shifts[0]
            if (
                int(shift.opening_float_minor) != 50_000
                or int(shift.expected_minor) != 78_000
                or int(shift.counted_minor or -1) != 78_000
                or int(shift.variance_minor or 0) != 0
            ):
                failures.append(
                    "shift drawer must close at opening ₹500 + exact cash ₹280 = ₹780 "
                    f"(opening={shift.opening_float_minor}, expected={shift.expected_minor}, "
                    f"counted={shift.counted_minor}, variance={shift.variance_minor})"
                )

        active_cola_addons = [
            addon
            for addon in addons
            if addon.menu_item_name_snapshot == "Audit Cola" and addon.voided_at is None
        ]
        voided_crisps_addons = [
            addon
            for addon in addons
            if addon.menu_item_name_snapshot == "Audit Crisps" and addon.voided_at is not None
        ]
        if len(active_cola_addons) != 1 or len(voided_crisps_addons) != 1:
            failures.append(
                "expected exactly one retained Cola and one reasoned-void Crisps add-on"
            )
        if Decimal(str(cola_stock.current_qty)) != Decimal("9.0000"):
            failures.append(
                f"Audit Cola aggregate stock expected 9, found {cola_stock.current_qty}"
            )
        if Decimal(str(cola_batch.qty_on_hand)) != Decimal("9.0000"):
            failures.append(f"Audit Cola FIFO batch expected 9, found {cola_batch.qty_on_hand}")
        if (
            len(cola_movements) != 1
            or Decimal(str(cola_movements[0].qty_delta)) != Decimal("-1.0000")
            or int(cola_movements[0].cost_per_unit_minor) != FIXTURE_COLA_COGS_MINOR
            or cola_movements[0].created_by != fixture_user.id
        ):
            failures.append("Audit Cola must have one attributed FIFO sale movement at exact COGS")
        if Decimal(str(crisps_stock.current_qty)) != FIXTURE_CRISPS_OPENING_QTY:
            failures.append(
                "The voided Audit Crisps item changed aggregate stock: "
                f"{crisps_stock.current_qty}"
            )
        if Decimal(str(crisps_batch.qty_on_hand)) != FIXTURE_CRISPS_OPENING_QTY:
            failures.append(
                "The voided Audit Crisps item changed its FIFO batch: "
                f"{crisps_batch.qty_on_hand}"
            )
        if crisps_movements:
            failures.append("The voided Audit Crisps item created a sale stock movement")

        cola_session = next(
            (
                row
                for row in sessions
                if active_cola_addons
                and row.id == active_cola_addons[0].gaming_session_id
            ),
            None,
        )
        cola_order = next(
            (
                order
                for order in orders
                if cola_session is not None and order.id == cola_session.order_id
            ),
            None,
        )
        if (
            cola_session is None
            or cola_order is None
            or int(cola_session.amount_minor or -1) != 18_000
            or int(cola_order.subtotal_minor) != 23_000
            or int(cola_order.manual_discount_minor or 0) != 2_000
            or int(cola_order.total_minor) != 21_000
        ):
            failures.append(
                "combined Gaming + Cola bill must be ₹230 less ₹20 discount = ₹210"
            )

        required_explicit_audits = {
            "login_success",
            "gaming_session_addon_added",
            "gaming_session_addon_voided",
        }
        linkage_audits = [
            {
                "entity_type": row.entity_type,
                "entity_id": row.entity_id,
                "action": row.action,
                "company_id": str(row.company_id),
                "actor_user_id": str(row.actor_user_id),
                "terminal_id": str(row.terminal_id),
                "before": row.before,
                "after": row.after,
            }
            for row in audit_rows
            if (row.entity_type in {"GamingSession", "Order"}
                and row.action in {"create", "update"})
            or row.action == "gaming_session_handoff_to_pos"
        ]
        linkage_expectations = [
            {
                "session_id": str(row.id),
                "order_id": str(row.order_id),
                "company_id": str(company_id),
                "actor_user_id": str(fixture_user.id),
                "terminal_id": str(orders_by_id[row.order_id].terminal_id),
                "branch_id": str(orders_by_id[row.order_id].branch_id),
                "shift_id": str(row.shift_id),
                "sent_to_pos_at": row.sent_to_pos_at.isoformat() if row.sent_to_pos_at else None,
            }
            for row in sessions if row.order_id in orders_by_id
        ]
        if len(linkage_expectations) != 16:
            failures.append("exactly sixteen session/order links are required for audit proof")
        failures.extend(_single_shift_linkage_audit_failures(linkage_expectations, linkage_audits))
        observed_explicit_audits = {row.action for row in audit_rows}
        missing_audits = sorted(required_explicit_audits - observed_explicit_audits)
        if missing_audits:
            failures.append("missing explicit audit actions: " + ", ".join(missing_audits))
        if any(
            row.actor_user_id != fixture_user.id
            for row in audit_rows
            if row.action in required_explicit_audits
        ):
            failures.append("an explicit workflow audit action has the wrong actor")
        expected_pause_audits = Counter(
            {
                ("gaming.session.pause", FIXTURE_PAUSE_REASON, str(paused_session.id)): 1,
                ("gaming.session.resume", FIXTURE_RESUME_REASON, str(paused_session.id)): 1,
            }
        ) if paused_session is not None else Counter()
        observed_pause_audits = Counter(
            (row.action, row.reason, row.entity_id)
            for row in audit_rows
            if row.action in {"gaming.session.pause", "gaming.session.resume"}
            and row.actor_user_id == fixture_user.id
        )
        if observed_pause_audits != expected_pause_audits:
            failures.append(
                "pause/resume audit history is missing its exact actor, reason or session: "
                f"{dict(observed_pause_audits)}"
            )
        tracked_runtime_types = {
            "GamingSession",
            "GamingSessionAddon",
            "GamingSessionExtension",
            "Order",
            "Payment",
            "Shift",
            "StockMovement",
        }
        if any(
            row.actor_user_id != fixture_user.id
            for row in audit_rows
            if row.entity_type in tracked_runtime_types and row.action in {"create", "update"}
        ):
            failures.append("a tracked workflow mutation has missing/wrong audit attribution")
        if journal_imbalances:
            failures.append(f"{journal_imbalances} posted journal entries are unbalanced")

        result = {
            "fixture": "physical-audit",
            "database": database,
            "company_id": str(company_id),
            "linkage_audit_expectations": linkage_expectations,
            "linkage_audit_records": linkage_audits,
            "counts": {
                "shifts": len(shifts),
                "sessions": len(sessions),
                "orders": len(orders),
                "payments": len(payments),
                "extensions": len(extensions),
                "pause_events": len(pause_events),
                "addons": len(addons),
                "order_lines": len(order_lines),
                "audit_rows": len(audit_rows),
            },
            "payment_methods": dict(sorted(methods.items())),
            "package_codes": sorted(package_codes),
            "package_player_amount_matrix": [
                {
                    "code": code,
                    "extra_controllers": extra_controllers,
                    "amount_minor": amount_minor,
                    "count": count,
                }
                for (code, extra_controllers, amount_minor), count in sorted(
                    observed_package_sessions.items()
                )
            ],
            "extension_matrix": [
                {
                    "code": key[0],
                    "extra_controllers": key[1],
                    "timer_before_minutes": key[2],
                    "timer_after_minutes": key[3],
                    "package_price_minor": key[4],
                    "controller_surcharge_minor": key[5],
                    "total_minor": key[6],
                    "count": count,
                }
                for key, count in sorted(observed_extensions.items())
            ],
            "pause_resume": {
                "session_id": str(paused_session.id) if paused_session is not None else None,
                "paused_duration_ms": (
                    int(paused_session.paused_duration_ms or 0)
                    if paused_session is not None
                    else None
                ),
                "final_amount_minor": (
                    int(paused_session.amount_minor or 0)
                    if paused_session is not None
                    else None
                ),
                "events": [
                    {
                        "action": event.action,
                        "reason": event.reason,
                        "pause_version": int(event.pause_version),
                        "actor_user_id": str(event.actor_user_id),
                        "terminal_id": str(event.terminal_id),
                        "response_status": event.response.get("status"),
                        "response_start_at": event.response.get("start_at"),
                        "response_amount_minor": event.response.get("amount_minor"),
                        "response_timer_minutes": event.response.get("timer_minutes"),
                        "response_timer_ends_at": event.response.get("timer_ends_at"),
                        "response_paused_at": event.response.get("paused_at"),
                        "response_paused_duration_ms": event.response.get(
                            "paused_duration_ms"
                        ),
                        "response_pause_version": event.response.get("pause_version"),
                        "response_billing_mode": event.response.get("billing_mode"),
                        "response_package_price_minor_snapshot": event.response.get(
                            "package_price_minor_snapshot"
                        ),
                        "response_package_duration_minutes_snapshot": event.response.get(
                            "package_duration_minutes_snapshot"
                        ),
                        "response_package_variant_snapshot": event.response.get(
                            "package_variant_snapshot"
                        ),
                        "response_package_pricing_tier_snapshot": event.response.get(
                            "package_pricing_tier_snapshot"
                        ),
                    }
                    for event in pause_events
                ],
            },
            "hourly_station_types": dict(sorted(hourly_station_types.items())),
            "hourly_billing_matrix": [
                {
                    "station": station.code,
                    "type": station.type,
                    "billable_minutes": int(gaming_session.billable_minutes or 0),
                    "rate_per_hour_minor": int(gaming_session.rate_per_hour_minor),
                    "amount_minor": int(gaming_session.amount_minor or 0),
                }
                for gaming_session, station in sorted(
                    hourly_session_details,
                    key=lambda row: row[1].code,
                )
            ],
            "gross_order_minor": sum(int(order.total_minor) for order in orders),
            "manual_discount_minor": sum(int(order.manual_discount_minor) for order in orders),
            "cash_collected_minor": sum(
                int(payment.amount_minor) for payment in payments if payment.method == "cash"
            ),
            "upi_collected_minor": sum(
                int(payment.amount_minor) for payment in payments if payment.method == "upi"
            ),
            "journal_imbalances": journal_imbalances,
            "inventory": {
                "ingredient_qty": str(cola_stock.current_qty),
                "fifo_qty": str(cola_batch.qty_on_hand),
                "sale_movements": len(cola_movements),
                "cogs_minor": sum(
                    abs(int(Decimal(str(row.qty_delta)) * row.cost_per_unit_minor))
                    for row in cola_movements
                ),
                "void_control_ingredient_qty": str(crisps_stock.current_qty),
                "void_control_fifo_qty": str(crisps_batch.qty_on_hand),
                "void_control_sale_movements": len(crisps_movements),
            },
            "observed_explicit_audits": sorted(observed_explicit_audits & required_explicit_audits),
            "failures": failures,
            "passed": not failures,
        }
        _write_json(result)
        if failures:
            raise PhysicalAuditFixtureError("; ".join(failures))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("seed", "verify"))
    args = parser.parse_args()
    asyncio.run(seed() if args.command == "seed" else verify())


if __name__ == "__main__":
    main()
