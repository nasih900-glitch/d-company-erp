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
EMPTY_INVENTORY = {"row_count": 0, "row_sha256": EMPTY_SHA256}
GRN_LEDGERS = {"grn_action_decisions": "decision_sha256", "grn_completion_proofs": "proof_sha256"}
AUDIT_SEQUENCE = "audit_log_id_seq"
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
    verify_prior_audit(before)


def verify_prior_audit(before):
    evidence = before["legacy_grn_backfill"]
    prefix = evidence["audit_prefix"]
    inventory = before["tables"]["audit_log"]
    high_water = prefix["high_water"]
    REQUIRE(prefix == {**inventory, "high_water": high_water}
            and (high_water is None if inventory["row_count"] == 0 else type(high_water) is int),
            "stopped audit prefix differs from the complete audit inventory")
    REQUIRE(evidence["audit_append"] == {**EMPTY_INVENTORY, "first_id": None, "last_id": None,
                "timestamp_count": 0, "timestamps_observed": True}, "stopped audit already has an append partition")
    for key in (*GRN_LEDGERS.values(), "audit_sha256"):
        PREVIOUS["row_digest"]({"row_count": evidence["row_count"], "row_sha256": evidence[key]})


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
    verify_prior_audit(before)
    verify_prior_audit(restored)
    keys = ("tables", "sequences", "gaming_sessions_legacy_projection", "trigger_catalog_rows", "legacy_grn_backfill")
    REQUIRE(all(before[key] == restored[key] for key in keys), "restored database rows, sequences or triggers differ")


def verify_legacy_grn_backfill(before, after):
    old = before["legacy_grn_backfill"]
    new = after["legacy_grn_backfill"]
    source_keys = ("row_count", *GRN_LEDGERS.values(), "audit_sha256")
    REQUIRE(all(old[key] == new[key] for key in source_keys), "retained GRN source projection changed")
    REQUIRE(old["audit_prefix"] == new["audit_prefix"], "retained audit prefix changed")

    count = old["row_count"]
    sequence = before["sequences"][AUDIT_SEQUENCE]
    first = last = None
    expected_sequences = before["sequences"]
    if count:
        REQUIRE(set(sequence) == {"last_value", "is_called", "start_value", "increment_by", "min_value",
                                 "max_value", "cache_size", "cycle", "owned_by"}
                and all(type(sequence[key]) is int for key in ("last_value", "start_value", "min_value", "max_value"))
                and type(sequence["is_called"]) is bool
                and sequence["increment_by"] == 1 and sequence["cache_size"] == 1
                and sequence["cycle"] is False and sequence["owned_by"] == "public.audit_log.id"
                and sequence["min_value"] <= sequence["last_value"] <= sequence["max_value"],
                "audit sequence cannot allocate a verified contiguous migration tail")
        first = sequence["last_value"] + int(sequence["is_called"])
        last = first + count - 1
        high_water = old["audit_prefix"]["high_water"]
        REQUIRE((high_water is None or high_water < first) and last <= sequence["max_value"],
                "migration audit IDs overlap retained rows or exceed sequence bounds")
        expected_sequences = {**expected_sequences, AUDIT_SEQUENCE: {**sequence, "last_value": last, "is_called": True}}
    REQUIRE(after["sequences"] == expected_sequences, "unexpected audit or other sequence change")

    REQUIRE(new["audit_append"] == {"row_count": count, "row_sha256": old["audit_sha256"],
                "first_id": first, "last_id": last, "timestamp_count": int(count > 0), "timestamps_observed": True}
            and after["tables"]["audit_log"]["row_count"] == before["tables"]["audit_log"]["row_count"] + count,
            "unexpected migration audit append")
    if not count:
        REQUIRE(after["tables"]["audit_log"] == before["tables"]["audit_log"], "complete audit inventory changed")
    for ledger, digest_key in GRN_LEDGERS.items():
        REQUIRE(after["tables"][ledger] == {"row_count": count, "row_sha256": old[digest_key]},
                "GRN ledger differs from the exact retained source projection")


def verify_after(before, pre_fleet, after, post_fleet):
    verify_before(before, pre_fleet)
    PREVIOUS["inventory"](after, "0105")
    FLEET["verify_fleet"](post_fleet, "0105")
    REQUIRE({**pre_fleet, "database_revision": "0105"} == post_fleet, "legacy or current fleet changed")
    keys = ("database_identity", "gaming_sessions_legacy_projection")
    REQUIRE(all(before[key] == after[key] for key in keys), "database identity or retained projection changed")
    verify_legacy_grn_backfill(before, after)
    expected = {**before["tables"], "alembic_version": after["tables"]["alembic_version"],
                "audit_log": after["tables"]["audit_log"],
                **{table: EMPTY_INVENTORY for table in NEW_TABLES - GRN_LEDGERS.keys()},
                **{table: after["tables"][table] for table in GRN_LEDGERS}}
    REQUIRE(expected == after["tables"], "retained rows changed or a candidate ledger is not empty")
    for name, (removed, added) in CATALOGUE_DELTA.items():
        old = set(before[f"{name}_catalog_rows"])
        new = set(after[f"{name}_catalog_rows"])
        digest = PREVIOUS["catalogue_digest"]
        REQUIRE((len(old - new), digest(old - new)) == removed and (len(new - old), digest(new - old)) == added,
                f"unexpected {name} catalogue delta")


def image_override(attestation, retained):
    images = attestation["services"]
    REQUIRE(attestation["version_name"] == "3.1.79" and set(images) == PREVIOUS["IMAGE_SERVICES"],
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
