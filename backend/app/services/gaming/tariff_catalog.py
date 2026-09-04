"""Canonical D Company gaming tariff catalog.

The printed tariff is represented as data, not pricing formulas. Updating the
active catalog never changes a ``GamingSession`` snapshot or an immutable
extension receipt, so historical bills retain the exact amount accepted at the
time of play.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING, Final
from uuid import UUID, uuid4

from sqlalchemy import func, or_, select
from sqlalchemy.dialects.postgresql import insert as pg_insert

from app.models import AuditLog, Branch, GamingPackage

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession


@dataclass(frozen=True, slots=True)
class GamingTariffSpec:
    code: str
    station_type: str
    variant: str
    pricing_tier: str
    kind: str
    name: str
    duration_minutes: int
    price_minor: int
    included_players: int
    max_players: int
    sort_order: int


# Exact transcription of the D Company printed card supplied on 2026-09-03.
# All money is integer paise (₹80 == 8_000), never floating-point rupees.
D_COMPANY_GAMING_TARIFF: Final[tuple[GamingTariffSpec, ...]] = (
    GamingTariffSpec(
        "standard-single-session-30m",
        "ps5",
        "single",
        "standard",
        "base",
        "Single Mode · 30 min",
        30,
        8_000,
        1,
        1,
        10,
    ),
    GamingTariffSpec(
        "standard-single-session-60m",
        "ps5",
        "single",
        "standard",
        "base",
        "Single Mode · 1 hour",
        60,
        12_000,
        1,
        1,
        20,
    ),
    GamingTariffSpec(
        "standard-single-extension-30m",
        "ps5",
        "single",
        "standard",
        "extension",
        "Single Mode · 30 min extension",
        30,
        6_000,
        1,
        1,
        30,
    ),
    GamingTariffSpec(
        "standard-single-extension-60m",
        "ps5",
        "single",
        "standard",
        "extension",
        "Single Mode · 1 hour extension",
        60,
        10_000,
        1,
        1,
        40,
    ),
    GamingTariffSpec(
        "standard-dual-session-30m",
        "ps5",
        "dual",
        "standard",
        "base",
        "Dual Mode · 30 min",
        30,
        10_000,
        2,
        4,
        10,
    ),
    GamingTariffSpec(
        "standard-dual-session-60m",
        "ps5",
        "dual",
        "standard",
        "base",
        "Dual Mode · 1 hour",
        60,
        15_000,
        2,
        4,
        20,
    ),
    GamingTariffSpec(
        "standard-dual-extension-30m",
        "ps5",
        "dual",
        "standard",
        "extension",
        "Dual Mode · 30 min extension",
        30,
        7_000,
        2,
        4,
        30,
    ),
    GamingTariffSpec(
        "standard-dual-extension-60m",
        "ps5",
        "dual",
        "standard",
        "extension",
        "Dual Mode · 1 hour extension",
        60,
        13_000,
        2,
        4,
        40,
    ),
    GamingTariffSpec(
        "standard-simdrive-session-15m",
        "simulator",
        "simdrive",
        "standard",
        "base",
        "Simdrive · 15 min",
        15,
        7_000,
        1,
        1,
        10,
    ),
    GamingTariffSpec(
        "standard-simdrive-session-30m",
        "simulator",
        "simdrive",
        "standard",
        "base",
        "Simdrive · 30 min",
        30,
        10_000,
        1,
        1,
        20,
    ),
    GamingTariffSpec(
        "standard-simdrive-session-60m",
        "simulator",
        "simdrive",
        "standard",
        "base",
        "Simdrive · 1 hour",
        60,
        18_000,
        1,
        1,
        30,
    ),
    GamingTariffSpec(
        "premium-single-session-60m",
        "ps5",
        "single",
        "premium",
        "base",
        "Premium Single Mode · 1 hour",
        60,
        15_000,
        1,
        1,
        110,
    ),
    GamingTariffSpec(
        "premium-single-extension-30m",
        "ps5",
        "single",
        "premium",
        "extension",
        "Premium Single Mode · 30 min extension",
        30,
        7_000,
        1,
        1,
        120,
    ),
    GamingTariffSpec(
        "premium-single-extension-60m",
        "ps5",
        "single",
        "premium",
        "extension",
        "Premium Single Mode · 1 hour extension",
        60,
        12_000,
        1,
        1,
        130,
    ),
    GamingTariffSpec(
        "premium-dual-session-60m",
        "ps5",
        "dual",
        "premium",
        "base",
        "Premium Dual Mode · 1 hour",
        60,
        19_000,
        2,
        4,
        110,
    ),
    GamingTariffSpec(
        "premium-dual-extension-30m",
        "ps5",
        "dual",
        "premium",
        "extension",
        "Premium Dual Mode · 30 min extension",
        30,
        9_000,
        2,
        4,
        120,
    ),
    GamingTariffSpec(
        "premium-dual-extension-60m",
        "ps5",
        "dual",
        "premium",
        "extension",
        "Premium Dual Mode · 1 hour extension",
        60,
        15_000,
        2,
        4,
        130,
    ),
)

_CANONICAL_CODES: Final[frozenset[str]] = frozenset(
    spec.code for spec in D_COMPANY_GAMING_TARIFF
)
_COVERED_STATION_TYPES: Final[frozenset[str]] = frozenset({"ps5", "simulator"})
_UPSERT_FIELDS: Final[tuple[str, ...]] = (
    "station_type",
    "variant",
    "pricing_tier",
    "kind",
    "name",
    "duration_minutes",
    "price_minor",
    "included_players",
    "max_players",
    "sort_order",
    "is_active",
)
_AUDIT_SOURCE: Final[str] = "script/ensure_gaming_tariff-v1"
_AUDIT_REASON: Final[str] = "Applied the owner-approved D Company tariff card dated 2026-09-03."


@dataclass(frozen=True, slots=True)
class GamingTariffConflict:
    id: UUID
    code: str
    name: str
    station_type: str
    variant: str
    kind: str
    duration_minutes: int
    price_minor: int


class GamingTariffCatalogConflictError(RuntimeError):
    """Fail closed instead of hiding or silently retiring unknown products."""

    def __init__(self, conflicts: tuple[GamingTariffConflict, ...]) -> None:
        self.conflicts = conflicts
        rows = "; ".join(
            f"{row.id} code={row.code!r} name={row.name!r} "
            f"{row.station_type}/{row.variant}/{row.kind} "
            f"{row.duration_minutes}m={row.price_minor} paise"
            for row in conflicts
        )
        super().__init__(
            "unexpected active PS5/simulator package rows block tariff sync; "
            f"review or explicitly retire them first: {rows}"
        )


@dataclass(frozen=True, slots=True)
class GamingTariffUpsertResult:
    created_codes: tuple[str, ...]
    updated_codes: tuple[str, ...]
    unchanged_codes: tuple[str, ...]
    dry_run: bool

    @property
    def changed_count(self) -> int:
        return len(self.created_codes) + len(self.updated_codes)


def _desired_values(spec: GamingTariffSpec) -> dict[str, object]:
    return {
        "station_type": spec.station_type,
        "variant": spec.variant,
        "pricing_tier": spec.pricing_tier,
        "kind": spec.kind,
        "name": spec.name,
        "duration_minutes": spec.duration_minutes,
        "price_minor": spec.price_minor,
        "included_players": spec.included_players,
        "max_players": spec.max_players,
        "sort_order": spec.sort_order,
        "is_active": True,
    }


def _audit_values(
    *,
    package_id: UUID,
    company_id: UUID,
    branch_id: UUID,
    code: str,
    values: dict[str, object],
) -> dict[str, object]:
    """Return a JSON-safe catalog snapshot for the immutable audit trail."""
    return {
        "id": str(package_id),
        "company_id": str(company_id),
        "branch_id": str(branch_id),
        "code": code,
        **values,
    }


def _validate_catalog_definition() -> None:
    if len(D_COMPANY_GAMING_TARIFF) != 17 or len(_CANONICAL_CODES) != 17:
        raise RuntimeError("D Company gaming tariff must contain 17 unique package codes")
    for spec in D_COMPANY_GAMING_TARIFF:
        if spec.pricing_tier not in {"standard", "premium"}:
            raise RuntimeError(f"invalid pricing tier in gaming tariff: {spec.code}")
        if spec.kind not in {"base", "extension"}:
            raise RuntimeError(f"invalid package kind in gaming tariff: {spec.code}")
        if not (1 <= spec.included_players <= spec.max_players <= 10):
            raise RuntimeError(f"invalid player limits in gaming tariff: {spec.code}")
        supports_extra_players = (
            spec.station_type == "ps5"
            and spec.variant == "dual"
            and spec.included_players == 2
        )
        if spec.max_players > spec.included_players and not supports_extra_players:
            raise RuntimeError(f"invalid multiplayer package in gaming tariff: {spec.code}")


_validate_catalog_definition()


async def upsert_d_company_gaming_tariff(
    session: AsyncSession,
    *,
    company_id: UUID,
    branch_id: UUID,
    dry_run: bool = False,
) -> GamingTariffUpsertResult:
    """Plan and, unless dry-run, upsert the canonical 17-row tariff.

    Active non-canonical rows in the covered PS5/simulator scope are reported
    as conflicts. They are never silently deleted, retired, renamed, or folded
    into the new catalog.
    """

    if not dry_run:
        # Package-row locks cannot serialize an empty catalog. Lock the owning
        # branch first so concurrent deploy commands cannot both plan the same
        # canonical code as a create and then record an audit entity ID that
        # lost the ON CONFLICT race. This also validates the tenant/branch pair
        # before any business-price write is attempted.
        locked_branch_id = (
            await session.execute(
                select(Branch.id)
                .where(
                    Branch.id == branch_id,
                    Branch.company_id == company_id,
                    Branch.deleted_at.is_(None),
                )
                .with_for_update()
            )
        ).scalar_one_or_none()
        if locked_branch_id is None:
            raise RuntimeError(
                f"active branch {branch_id} was not found for company {company_id}"
            )

    rows = (
        await session.execute(
            select(GamingPackage)
            .where(
                GamingPackage.company_id == company_id,
                GamingPackage.branch_id == branch_id,
                GamingPackage.deleted_at.is_(None),
                or_(
                    GamingPackage.code.in_(_CANONICAL_CODES),
                    (
                        GamingPackage.is_active.is_(True)
                        & GamingPackage.station_type.in_(_COVERED_STATION_TYPES)
                    ),
                ),
            )
            .order_by(GamingPackage.code, GamingPackage.id)
            .with_for_update()
            .execution_options(populate_existing=True)
        )
    ).scalars().all()

    conflicts = tuple(
        GamingTariffConflict(
            id=row.id,
            code=row.code,
            name=row.name,
            station_type=row.station_type,
            variant=row.variant,
            kind=row.kind,
            duration_minutes=int(row.duration_minutes),
            price_minor=int(row.price_minor),
        )
        for row in rows
        if row.code not in _CANONICAL_CODES
        and row.station_type in _COVERED_STATION_TYPES
    )
    if conflicts:
        raise GamingTariffCatalogConflictError(conflicts)

    existing_by_code = {row.code: row for row in rows if row.code in _CANONICAL_CODES}
    created_codes: list[str] = []
    updated_codes: list[str] = []
    unchanged_codes: list[str] = []
    for spec in D_COMPANY_GAMING_TARIFF:
        existing = existing_by_code.get(spec.code)
        if existing is None:
            created_codes.append(spec.code)
            continue
        desired = _desired_values(spec)
        if any(getattr(existing, field) != value for field, value in desired.items()):
            updated_codes.append(spec.code)
        else:
            unchanged_codes.append(spec.code)

    result = GamingTariffUpsertResult(
        created_codes=tuple(created_codes),
        updated_codes=tuple(updated_codes),
        unchanged_codes=tuple(unchanged_codes),
        dry_run=dry_run,
    )
    if dry_run:
        return result

    specs_by_code = {spec.code: spec for spec in D_COMPANY_GAMING_TARIFF}
    updated_audit_diffs: dict[str, tuple[dict[str, object], dict[str, object]]] = {}
    for code in updated_codes:
        package = existing_by_code[code]
        desired = _desired_values(specs_by_code[code])
        before: dict[str, object] = {}
        after: dict[str, object] = {}
        for field, expected in desired.items():
            current = getattr(package, field)
            if current != expected:
                before[field] = current
                after[field] = expected
        updated_audit_diffs[code] = (before, after)

    created_ids = {code: uuid4() for code in created_codes}
    values = [
        {
            "id": (
                created_ids[spec.code]
                if spec.code in created_ids
                else existing_by_code[spec.code].id
            ),
            "company_id": company_id,
            "branch_id": branch_id,
            "code": spec.code,
            **_desired_values(spec),
        }
        for spec in D_COMPANY_GAMING_TARIFF
    ]
    insert_statement = pg_insert(GamingPackage).values(values)
    excluded = insert_statement.excluded
    update_values = {
        field: getattr(excluded, field)
        for field in _UPSERT_FIELDS
    }
    update_values["updated_at"] = func.now()
    statement = insert_statement.on_conflict_do_update(
        index_elements=[
            GamingPackage.company_id,
            GamingPackage.branch_id,
            GamingPackage.code,
        ],
        index_where=GamingPackage.deleted_at.is_(None),
        set_=update_values,
        where=or_(
            *(
                getattr(GamingPackage, field).is_distinct_from(
                    getattr(excluded, field)
                )
                for field in _UPSERT_FIELDS
            )
        ),
    )
    await session.execute(statement)

    for code in created_codes:
        desired = _desired_values(specs_by_code[code])
        package_id = created_ids[code]
        session.add(
            AuditLog(
                actor_user_id=None,
                company_id=company_id,
                action="create",
                entity_type="GamingPackage",
                entity_id=str(package_id),
                before=None,
                after=_audit_values(
                    package_id=package_id,
                    company_id=company_id,
                    branch_id=branch_id,
                    code=code,
                    values=desired,
                ),
                user_agent=_AUDIT_SOURCE,
                reason=_AUDIT_REASON,
            )
        )
    for code in updated_codes:
        package = existing_by_code[code]
        before, after = updated_audit_diffs[code]
        session.add(
            AuditLog(
                actor_user_id=None,
                company_id=company_id,
                action="update",
                entity_type="GamingPackage",
                entity_id=str(package.id),
                before=before,
                after=after,
                user_agent=_AUDIT_SOURCE,
                reason=_AUDIT_REASON,
            )
        )
    return result


__all__ = [
    "D_COMPANY_GAMING_TARIFF",
    "GamingTariffCatalogConflictError",
    "GamingTariffConflict",
    "GamingTariffSpec",
    "GamingTariffUpsertResult",
    "upsert_d_company_gaming_tariff",
]
