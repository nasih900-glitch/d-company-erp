#!/usr/bin/env python3
"""Verify the quiesced 0094-to-0095 database transition and its backup."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
from runpy import run_path

EMPTY_SHA256 = hashlib.sha256(b"").hexdigest()
CATALOGUE_ADDITIONS = {
    "schema": (50, "7a6ce729171af6f12c99775628483bb0a20858da00997d1e1177078f5c88e1aa"),
    "trigger": (4, "1413cdf6921841cb7458f0dd2e626d45710c0d4b5f89d9d343bf9fe17e21540e"),
}
IMAGE_SERVICES = {"backend", "frontend", "caddy", "postgres", "redis"}

# Exact CHECK renderings observed in the Build65 backup restore rehearsal.
RESTORE_CHECK_PAIRS = (
    (
        "constraint|android_update_policy|android_update_policy_mode_check|"
        "CHECK (mode::text = ANY (ARRAY['optional'::character varying, 'required'::character varying]::text[]))",
        "constraint|android_update_policy|android_update_policy_mode_check|"
        "CHECK (mode::text = ANY (ARRAY['optional'::character varying::text, 'required'::character varying::text]))",
    ),
    (
        "constraint|gaming_void_receipts|ck_gaming_void_receipt_mode|"
        "CHECK (mode::text = ANY (ARRAY['unlinked_addons'::character varying, 'linked_pos'::character varying]::text[]))",
        "constraint|gaming_void_receipts|ck_gaming_void_receipt_mode|"
        "CHECK (mode::text = ANY (ARRAY['unlinked_addons'::character varying::text, 'linked_pos'::character varying::text]))",
    ),
)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def unique_pairs(pairs):
    result = dict(pairs)
    require(len(result) == len(pairs), "duplicate report field")
    return result


def read(path):
    return json.loads(Path(path).read_bytes(), object_pairs_hook=unique_pairs)


def row_digest(row):
    require(
        set(row) == {"row_count", "row_sha256"}
        and type(row["row_count"]) is int and row["row_count"] >= 0
        and re.fullmatch(r"[0-9a-f]{64}", row["row_sha256"])
        and (row["row_count"] != 0 or row["row_sha256"] == EMPTY_SHA256),
        "invalid row inventory",
    )
    return row


def catalogue_digest(rows):
    return hashlib.sha256("\n".join(sorted(rows)).encode()).hexdigest()


def inventory(report, revision):
    require(
        report["kind"] == "build55-full-transition-v2"
        and report["schema_version"] == 2 and report["database_revision"] == revision,
        "unexpected database revision or report format",
    )
    for key in ("alembic_version", "client_installations", "shifts"):
        require(key in report["tables"], "required table missing")
    require(report["tables"]["alembic_version"]["row_count"] == 1, "Alembic is not singular")
    for row in report["tables"].values():
        row_digest(row)
    require(isinstance(report["sequences"], dict), "invalid sequence inventory")
    for prefix in CATALOGUE_ADDITIONS:
        rows = report[f"{prefix}_catalog_rows"]
        require(
            isinstance(rows, list) and all(isinstance(row, str) for row in rows)
            and len(rows) == len(set(rows)) == report[f"{prefix}_catalog_row_count"]
            and catalogue_digest(rows) == report[f"{prefix}_catalog_sha256"],
            "invalid catalogue inventory",
        )


def projection(shift, report, columns):
    require(
        shift["database_identity"] == report["database_identity"]
        and shift["database_revision"] == report["database_revision"]
        and type(shift["custody_columns"]) is int and shift["custody_columns"] == columns
        and type(shift["nonnull_count"]) is int and shift["nonnull_count"] == 0,
        "shift projection has unexpected identity, schema or custody",
    )
    result = row_digest({key: shift[key] for key in ("row_count", "row_sha256")})
    require(result["row_count"] == report["tables"]["shifts"]["row_count"], "shift count changed")
    return result


def verify_before(before, shift):
    inventory(before, "0094")
    require("shift_operator_handovers" not in before["tables"], "handover ledger already exists")
    require(projection(shift, before, 0) == before["tables"]["shifts"], "prior shift projection mismatch")


def build65_runtime_support():
    return run_path(str(Path(__file__).with_name("verify-build55-pending-compatibility.py")))


def verify_restore(before, restored):
    inventory(before, "0094")
    inventory(restored, "0094")
    # Reuse the historical aliases and add only these exact restore renderings.
    # The live migration delta continues to compare the raw catalogue.
    historical_normalize = build65_runtime_support()["restore_catalogue"]
    aliases = {restored: live for live, restored in RESTORE_CHECK_PAIRS}

    def normalize(rows):
        rows = historical_normalize(rows)
        for live, restored in RESTORE_CHECK_PAIRS:
            identity = live.rsplit("|", 1)[0] + "|"
            matching = [row for row in rows if row.startswith(identity)]
            require(
                not matching or matching == [live] or matching == [restored],
                "restored reviewed CHECK is duplicated or changed",
            )
        return sorted(aliases.get(row, row) for row in rows)

    require(
        before["database_identity"]["system_identifier"] == restored["database_identity"]["system_identifier"]
        and normalize(before["schema_catalog_rows"]) == normalize(restored["schema_catalog_rows"])
        and all(before[key] == restored[key] for key in (
            "tables", "sequences", "gaming_sessions_legacy_projection",
            "trigger_catalog_rows", "trigger_catalog_sha256", "trigger_catalog_row_count",
        )),
        "restored backup differs from the quiesced prior data",
    )


def verify_after(before, pre_shift, after, post_shift):
    verify_before(before, pre_shift)
    inventory(after, "0095")
    for key in ("database_identity", "sequences", "gaming_sessions_legacy_projection"):
        require(before[key] == after[key], f"{key} changed")
    require(
        after["tables"].get("shift_operator_handovers") == {"row_count": 0, "row_sha256": EMPTY_SHA256},
        "new handover ledger is missing or not empty",
    )
    expected = dict(before["tables"])
    for allowed in ("alembic_version", "shifts"):
        expected[allowed] = after["tables"][allowed]
    expected["shift_operator_handovers"] = after["tables"]["shift_operator_handovers"]
    require(expected == after["tables"], "legacy table inventory changed")
    require(
        projection(post_shift, after, 2) == before["tables"]["shifts"],
        "legacy shift data changed",
    )
    for prefix, (count, digest) in CATALOGUE_ADDITIONS.items():
        old = set(before[f"{prefix}_catalog_rows"])
        new = set(after[f"{prefix}_catalog_rows"])
        added = new - old
        require(
            old <= new and len(added) == count and catalogue_digest(added) == digest,
            f"unexpected {prefix} catalogue delta",
        )


def runtime_override(config):
    reviewed = build65_runtime_support()
    entrypoint = reviewed["BACKEND_UVICORN_ENTRYPOINT"]
    require(
        config["Entrypoint"] == entrypoint and config["Cmd"] == [] and config["User"] == "erp",
        "backend no-seed executable or user changed",
    )
    message = reviewed["environment"](config["Env"]).get("CLIENT_UPDATE_MESSAGE")
    require(message is not None and "\0" not in message, "backend update message is missing or invalid")
    return {"services": {"backend": {
        "entrypoint": entrypoint,
        "command": [],
        "environment": {"CLIENT_UPDATE_MESSAGE": message.replace("$", "$$")},
    }}}


def image_override(attestation):
    images = attestation["services"]
    require(attestation["version_name"] == "3.1.64" and set(images) == IMAGE_SERVICES,
            "unexpected candidate version or service inventory")
    services = {service: {"image": row["image_id"]} for service, row in images.items()}
    require(all(re.fullmatch(r"sha256:[0-9a-f]{64}", row["image"]) for row in services.values()),
            "candidate image is not immutable")
    return {"services": services}


def verify_compose(attestation, base, pinned):
    overrides = image_override(attestation)["services"]
    require(set(base["services"]) == IMAGE_SERVICES, "unexpected base service inventory")
    for service, override in overrides.items():
        base["services"][service].update(override)
    require(base == pinned, "pinned Compose changed more than attested image IDs")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="mode", required=True)
    commands = {
        "pre": (verify_before, ("before", "pre_shift")),
        "restore": (verify_restore, ("before", "restored")),
        "post": (verify_after, ("before", "pre_shift", "after", "post_shift")),
        "runtime-override": (runtime_override, ("backend_config",)),
        "image-override": (image_override, ("attestation",)),
        "compose": (verify_compose, ("attestation", "base", "pinned")),
    }
    for name, (_, paths) in commands.items():
        command = sub.add_parser(name)
        for path in paths:
            command.add_argument(path)
    args = parser.parse_args()
    function, paths = commands[args.mode]
    try:
        result = function(*(read(getattr(args, path)) for path in paths))
    except (KeyError, OSError, TypeError):
        parser.exit(1, "Build66 proof refused: unreadable or invalid evidence.\n")
    except ValueError as exc:
        parser.exit(1, f"Build66 proof refused: {exc}.\n")
    print(json.dumps(result or {"status": "verified", "mode": args.mode}, sort_keys=True))


if __name__ == "__main__":
    main()
