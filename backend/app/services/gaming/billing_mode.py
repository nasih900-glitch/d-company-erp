"""Immutable gaming-session billing-mode classification.

The package catalog foreign key is intentionally nullable because catalog rows
may be retired or hard-deleted. Financial code must therefore use the persisted
discriminator and locked package snapshots, never ``package_id`` alone.
"""

from __future__ import annotations

from typing import Literal

from app.models import GamingSession

BillingMode = Literal["hourly", "package", "legacy_ambiguous"]

PACKAGE_SNAPSHOT_FIELDS = (
    "package_price_minor_snapshot",
    "package_duration_minutes_snapshot",
    "package_variant_snapshot",
    "package_station_type_snapshot",
)


def has_any_package_snapshot(gaming_session: GamingSession) -> bool:
    return any(
        getattr(gaming_session, field, None) is not None for field in PACKAGE_SNAPSHOT_FIELDS
    )


def has_complete_package_snapshot(gaming_session: GamingSession) -> bool:
    return all(
        getattr(gaming_session, field, None) is not None for field in PACKAGE_SNAPSHOT_FIELDS
    )


def has_partial_package_snapshot(gaming_session: GamingSession) -> bool:
    return has_any_package_snapshot(gaming_session) and not has_complete_package_snapshot(
        gaming_session
    )


def is_package_billed(gaming_session: GamingSession) -> bool:
    """Fail-safe package classification independent of the nullable catalog FK.

    Any package evidence wins over an inconsistent ``hourly`` discriminator so
    callers refuse an hourly reprice/benefit rather than undercharging. The
    final compatibility branch mirrors migration 0038's only safe inference:
    pre-discriminator running hourly rows never carried an amount, while fixed
    package rows locked one at Start.
    """
    explicit_mode = getattr(gaming_session, "billing_mode", None)
    if explicit_mode in {"package", "legacy_ambiguous"}:
        return True
    package_evidence = getattr(
        gaming_session, "package_id", None
    ) is not None or has_any_package_snapshot(gaming_session)
    if package_evidence:
        return True
    return (
        explicit_mode is None
        and getattr(gaming_session, "status", None) in {"active", "paused"}
        and getattr(gaming_session, "amount_minor", None) is not None
    )


def resolved_billing_mode(gaming_session: GamingSession) -> BillingMode:
    """Expose ambiguity truthfully while normalizing inconsistent evidence."""
    if getattr(gaming_session, "billing_mode", None) == "legacy_ambiguous":
        return "legacy_ambiguous"
    return "package" if is_package_billed(gaming_session) else "hourly"
