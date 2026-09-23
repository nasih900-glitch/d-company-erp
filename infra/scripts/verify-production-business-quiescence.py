#!/usr/bin/env python3
"""Fail closed unless production contains no in-flight business work."""

from __future__ import annotations

import argparse
import json
import sys
from typing import NoReturn


SCHEMA_VERSION = 1
MAX_EVIDENCE_BYTES = 64 * 1024
COUNT_FIELDS = (
    "open_shifts",
    "invalid_shift_statuses",
    "open_or_held_orders",
    "invalid_order_statuses",
    "unacknowledged_kitchen_cancellations",
    "active_or_paused_gaming_sessions",
    "ended_gaming_awaiting_pos_or_void",
    "invalid_gaming_session_statuses",
    "unresolved_pos_refund_requests",
    "unresolved_membership_payment_requests",
    "unresolved_membership_refunds",
    "unresolved_membership_refund_recoveries",
    "google_sheets_pending_deliveries",
    "google_sheets_leased_deliveries",
    "google_sheets_quarantined_deliveries",
)


class QuiescenceError(ValueError):
    """The database evidence is malformed or production is still active."""


def _reject_duplicate_keys(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise QuiescenceError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _reject_constant(value: str) -> NoReturn:
    raise QuiescenceError(f"invalid JSON constant: {value}")


def verify_state(document: object) -> None:
    """Validate the exact query contract and require every blocker count to be zero."""

    if not isinstance(document, dict):
        raise QuiescenceError("business-quiescence evidence must be a JSON object")

    expected_fields = {"schema_version", *COUNT_FIELDS}
    actual_fields = set(document)
    if actual_fields != expected_fields:
        missing = sorted(expected_fields - actual_fields)
        unexpected = sorted(actual_fields - expected_fields)
        raise QuiescenceError(
            "business-quiescence evidence fields changed: "
            f"missing={missing}, unexpected={unexpected}"
        )
    if (
        type(document["schema_version"]) is not int
        or document["schema_version"] != SCHEMA_VERSION
    ):
        raise QuiescenceError("business-quiescence schema version changed")

    active: list[str] = []
    for field in COUNT_FIELDS:
        value = document[field]
        if isinstance(value, bool) or not isinstance(value, int) or value < 0:
            raise QuiescenceError(f"{field} must be a non-negative integer")
        if value:
            active.append(f"{field}={value}")
    if active:
        raise QuiescenceError(
            "production still has in-flight business work: " + ", ".join(active)
        )


def _fail(message: str) -> NoReturn:
    print(f"Business quiescence verification failed: {message}", file=sys.stderr)
    raise SystemExit(1)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args()
    try:
        raw = sys.stdin.buffer.read(MAX_EVIDENCE_BYTES + 1)
        if not raw or len(raw) > MAX_EVIDENCE_BYTES:
            raise QuiescenceError("business-quiescence evidence size is invalid")
        document = json.loads(
            raw,
            object_pairs_hook=_reject_duplicate_keys,
            parse_constant=_reject_constant,
        )
        verify_state(document)
    except (json.JSONDecodeError, QuiescenceError, UnicodeDecodeError) as exc:
        _fail(str(exc))
    if not args.quiet:
        print("Production business state is quiescent.")


if __name__ == "__main__":
    main()
