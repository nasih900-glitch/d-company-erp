#!/usr/bin/env python3
"""Verify the exact quiesced Build 71 to Build 72 production transition."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
from runpy import run_path


SCRIPTS = Path(__file__).resolve().parent
PREVIOUS = run_path(str(SCRIPTS / "verify-build66-transition.py"))
FLEET = run_path(str(SCRIPTS / "verify-build72-readiness.py"))
REQUIRE = PREVIOUS["require"]
EMPTY_SHA256 = PREVIOUS["EMPTY_SHA256"]
NEW_TABLES = {
    "gaming_retained_bill_corrections", "grn_action_decisions", "grn_completion_proofs", "grn_recovery_sessions",
    "paid_bill_action_decisions", "paid_bill_amendment_intents", "paid_bill_recovery_sessions",
    "pos_receipt_corrections", "pos_receipt_mistake_reports", "pos_receipt_stock_restorations",
    "shift_start_work_cancellations", "shift_start_work_receipts",
}
# Complete catalogue delta observed by migrating immutable Build 71 directly
# on PostgreSQL 16. This compares raw live DDL, independently of restore aliases.
CATALOGUE_DELTA = {
    "schema": ((4, "2652f095169c43c00716b3e151cf944d4449bd30a5f57f22af3db5af96fa058e"),
               (575, "a74c03bcfa067cb0405f5569c42dde051ee76f44e87217973c598ece14a33e5a")),
    "trigger": ((0, EMPTY_SHA256), (87, "7e5c8fd0c445c42c512c17a724fbe01b5c4876841870c0d6a21848cccad16555")),
}
RESTORE_PAIRS = (SCRIPTS.parents[1] / "backend/tests/integration/fixtures/build71_pg16_restore_catalogue_pairs.json")


def restore_catalogue(rows: list[str], path: Path = RESTORE_PAIRS) -> list[str]:
    historical = PREVIOUS["build65_runtime_support"]()["restore_catalogue"](rows)
    older_pairs = PREVIOUS["RESTORE_CHECK_PAIRS"]
    aliases = {value: live for live, restored in older_pairs for value in (live, restored)}
    for live, _ in older_pairs:
        prefix = live.rsplit("|", 1)[0] + "|"
        matching = [row for row in historical if row.startswith(prefix)]
        REQUIRE(len(matching) <= 1 and all(row in aliases for row in matching), "historical restore definition changed")
    historical = [aliases.get(row, row) for row in historical]
    pairs = PREVIOUS["read"](path)
    REQUIRE(isinstance(pairs, list) and len(pairs) == 103, "reviewed PostgreSQL restore aliases changed")

    def identity(row):
        parts = row.split("|")
        REQUIRE(len(parts) >= 4 and parts[0] in {"column", "constraint", "index"}, "invalid restore alias")
        return tuple(parts[:2] + [parts[3]]) if parts[0] == "column" else tuple(parts[:3])

    by_identity = {}
    for live, restored in pairs:
        key = identity(live)
        REQUIRE(identity(restored) == key and live != restored and key not in by_identity, "invalid reviewed restore pair")
        by_identity[key] = (live, restored)
    output, seen = [], set()
    for row in historical:
        if row.split("|", 1)[0] in {"column", "constraint", "index"}:
            key = identity(row)
            if key in by_identity:
                REQUIRE(key not in seen and row in by_identity[key], "restored SQL definition changed")
                seen.add(key)
                row = by_identity[key][0]
        output.append(row)
    REQUIRE(seen == by_identity.keys(), "reviewed restore identities are missing")
    return sorted(output)


def verify_before(before, fleet):
    PREVIOUS["inventory"](before, "0095")
    FLEET["verify_fleet"](fleet, "0095")
    REQUIRE("shift_operator_handovers" in before["tables"] and not NEW_TABLES.intersection(before["tables"]),
            "unexpected prior application tables")
    REQUIRE(fleet["installation_row_count"] == before["tables"]["client_installations"]["row_count"],
            "fleet differs from the stopped database inventory")


def verify_same_fleet(before, after):
    FLEET["verify_fleet"](before, "0095")
    FLEET["verify_fleet"](after, "0095")
    REQUIRE(before == after, "stopped fleet differs from the restored database")


def verify_restore(before, restored):
    PREVIOUS["inventory"](before, "0095")
    PREVIOUS["inventory"](restored, "0095")
    REQUIRE(before["database_identity"]["system_identifier"] == restored["database_identity"]["system_identifier"],
            "restore occurred on a different PostgreSQL data cluster")
    REQUIRE(restore_catalogue(before["schema_catalog_rows"]) == restore_catalogue(restored["schema_catalog_rows"]),
            "restored schema differs from the quiesced database")
    keys = ("tables", "sequences", "gaming_sessions_legacy_projection", "trigger_catalog_rows")
    REQUIRE(all(before[key] == restored[key] for key in keys), "restored database rows, sequences or triggers differ")


def verify_after(before, pre_fleet, after, post_fleet):
    verify_before(before, pre_fleet)
    PREVIOUS["inventory"](after, "0105")
    FLEET["verify_fleet"](post_fleet, "0105")
    REQUIRE({**pre_fleet, "database_revision": "0105"} == post_fleet, "legacy or current fleet changed")
    keys = ("database_identity", "sequences", "gaming_sessions_legacy_projection")
    REQUIRE(all(before[key] == after[key] for key in keys), "database identity or retained projection changed")
    expected = {**before["tables"], "alembic_version": after["tables"]["alembic_version"],
                **{table: {"row_count": 0, "row_sha256": EMPTY_SHA256} for table in NEW_TABLES}}
    REQUIRE(expected == after["tables"], "retained rows changed or a candidate ledger is not empty")
    for name, (removed, added) in CATALOGUE_DELTA.items():
        old = set(before[f"{name}_catalog_rows"])
        new = set(after[f"{name}_catalog_rows"])
        digest = PREVIOUS["catalogue_digest"]
        REQUIRE((len(old - new), digest(old - new)) == removed and (len(new - old), digest(new - old)) == added,
                f"unexpected {name} catalogue delta")


def image_override(attestation, retained):
    images = attestation["services"]
    REQUIRE(attestation["version_name"] == "3.1.77" and set(images) == PREVIOUS["IMAGE_SERVICES"],
            "unexpected candidate version or service inventory")
    services = {service: {"image": row["image_id"]} for service, row in images.items()}
    services["redis"]["image"] = retained["redis"]["image_id"]
    REQUIRE(all(re.fullmatch(r"sha256:[a-f0-9]{64}", row["image"]) for row in services.values()),
            "service image is not immutable")
    return {"services": services}


def verify_compose(attestation, retained, base, pinned):
    overrides = image_override(attestation, retained)["services"]
    REQUIRE(set(base["services"]) == PREVIOUS["IMAGE_SERVICES"], "unexpected base service inventory")
    for service, override in overrides.items():
        base["services"][service].update(override)
    REQUIRE(base == pinned, "pinned Compose changed more than reviewed image IDs")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="mode", required=True)
    commands = {
        "pre": (verify_before, ("before", "pre_fleet")),
        "restore": (verify_restore, ("before", "restored")),
        "fleet-equal": (verify_same_fleet, ("pre_fleet", "restored_fleet")),
        "post": (verify_after, ("before", "pre_fleet", "after", "post_fleet")),
        "runtime-override": (PREVIOUS["runtime_override"], ("backend_config",)),
        "image-override": (image_override, ("attestation", "retained")),
        "compose": (verify_compose, ("attestation", "retained", "base", "pinned")),
    }
    for name, (_, fields) in commands.items():
        command = sub.add_parser(name)
        for field in fields:
            command.add_argument(field)
    args = parser.parse_args()
    function, fields = commands[args.mode]
    try:
        result = function(*(PREVIOUS["read"](getattr(args, field)) for field in fields))
    except (KeyError, OSError, TypeError):
        parser.exit(1, "Build 72 transition refused: unreadable or invalid evidence.\n")
    except ValueError as error:
        parser.exit(1, f"Build 72 transition refused: {error}.\n")
    print(json.dumps(result or {"status": "verified", "mode": args.mode}, sort_keys=True))


if __name__ == "__main__":
    main()
