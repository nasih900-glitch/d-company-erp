#!/usr/bin/env python3
"""Check read-only Build 72 readiness evidence. This does not admit an upgrade."""

from __future__ import annotations

import argparse
from datetime import UTC, datetime, timedelta
import hashlib
import json
import os
from pathlib import Path
import re
from runpy import run_path
import stat
import sys
from uuid import UUID


BUILD71 = "b9b19bd2462e0f417922849a455e12116e8207e2"
REDIS_REPAIRS = Path("/var/lib/dcompany-erp/config-repairs")
MAX_BYTES = 1024 * 1024
MAX_CAPTURE_AGE = timedelta(minutes=5)
MAX_TABLET_AGE = timedelta(minutes=15)
REDIS_FUNCTIONAL_DELTA = {"+lpush", "+llen", "+rpop"}
REDIS_COMMAND = ["redis-server", "--aclfile", "/run/redis-users.acl"]
REDIS_RECEIPT_KEYS = ("active", "result", "hold")
SERVICES = {"backend", "caddy", "frontend", "postgres", "redis"}
LEGACY_REVIEWED_VERSIONS = {37: "3.1.29", 41: "3.1.33", 54: "3.1.46"}
FLEET_COUNTS = {
    "installation_row_count", "pending_outbox_count", "nonzero_installation_count",
    "client_installation_guard_trigger_count",
}
FLEET_GUARD_HASHES = {
    "client_installation_guard_function_sha256",
    "client_installation_guard_function_definition_sha256",
    "client_installation_guard_trigger_definition_sha256",
}
FLEET_OPAQUE = {
    "pending_outbox_count", "nonzero_installation_count",
    "nonzero_installations_identity_sha256", "nonzero_installations",
}
FLEET_ROW_FIELDS = {
    "row_id", "installation_id", "company_id", "terminal_id", "platform", "version_name",
    "version_code", "pending_outbox_count", "last_successful_sync_at", "last_seen_at", "identity_sha256",
}


def require(condition: object, message: str) -> None:
    if not condition:
        raise ValueError(message)


def check_fields(value: object, expected: set[str], name: str) -> None:
    require(isinstance(value, dict) and set(value) == expected, f"{name} fields changed")


def check_hex(value: object, size: int, label: str, *, prefix: str = "") -> None:
    require(isinstance(value, str) and re.fullmatch(prefix + f"[a-f0-9]{{{size}}}", value), f"{label} is malformed")


def canonical_uuid(value: object) -> bool:
    try:
        return isinstance(value, str) and str(UUID(value)) == value
    except ValueError:
        return False


def utc_timestamp(value: object, label: str) -> datetime:
    require(isinstance(value, str) and re.fullmatch(
        r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{6})?Z", value
    ), f"{label} is not a canonical UTC timestamp")
    return datetime.fromisoformat(value)


def _unique_pairs(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "duplicate JSON field")
        result[key] = value
    return result


def _reject_constant(value: str) -> None:
    raise ValueError("unexpected JSON numeric constant")


def read_root_document(path: Path) -> tuple[dict, str]:
    require(path.is_absolute() and path.name and ".." not in path.parts, "evidence path must be canonical and absolute")
    directory_flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
    descriptor = os.open("/", directory_flags)
    try:
        for part in path.parts[1:-1]:
            child = os.open(part, directory_flags, dir_fd=descriptor)
            os.close(descriptor)
            descriptor = child
            info = os.fstat(descriptor)
            require(info.st_uid == 0 and not stat.S_IMODE(info.st_mode) & 0o022, "evidence ancestry is writable or not root-owned")
        fd = os.open(path.name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=descriptor)
    finally:
        os.close(descriptor)
    with os.fdopen(fd, "rb") as stream:
        before = os.fstat(stream.fileno())
        require(
            stat.S_ISREG(before.st_mode) and before.st_uid == 0
            and stat.S_IMODE(before.st_mode) == 0o600
            and before.st_nlink == 1 and 0 < before.st_size <= MAX_BYTES,
            "evidence must be a bounded root-owned mode0600 regular file with one link",
        )
        data = stream.read(MAX_BYTES + 1)
        after = os.fstat(stream.fileno())
        require(len(data) == before.st_size and before.st_ctime_ns == after.st_ctime_ns, "evidence changed during read")
    record = json.loads(data, object_pairs_hook=_unique_pairs, parse_constant=_reject_constant)
    require(isinstance(record, dict), "evidence document must be an object")
    return record, hashlib.sha256(data).hexdigest()


def verify_fleet(report: dict, schema: str) -> None:
    check_fields(report, FLEET_COUNTS | FLEET_GUARD_HASHES | FLEET_OPAQUE | {
        "schema_version", "database_revision", "all_installations_identity_sha256",
    }, "fleet report")
    require(type(report["schema_version"]) is int and report["schema_version"] == 1
            and report["database_revision"] == schema, "fleet schema changed")
    require(all(type(report[key]) is int and report[key] >= 0 for key in FLEET_COUNTS)
            and report["client_installation_guard_trigger_count"] == 1, "fleet counters or guard changed")
    for key in FLEET_GUARD_HASHES | {"all_installations_identity_sha256", "nonzero_installations_identity_sha256"}:
        check_hex(report[key], 64, "fleet checksum")
    rows = report["nonzero_installations"]
    require(isinstance(rows, list), "opaque fleet rows must be a list")
    identities = {}
    installation_ids = set()
    pending = 0
    for row in rows:
        check_fields(row, FLEET_ROW_FIELDS, "opaque fleet row")
        require(
            all(canonical_uuid(row[key]) for key in ("row_id", "installation_id", "company_id"))
            and (row["terminal_id"] is None or canonical_uuid(row["terminal_id"]))
            and row["row_id"] not in identities and row["installation_id"] not in installation_ids
            and row["platform"] == "android" and type(row["version_code"]) is int
            and LEGACY_REVIEWED_VERSIONS.get(row["version_code"]) == row["version_name"]
            and isinstance(row["version_name"], str)
            and type(row["pending_outbox_count"]) is int and row["pending_outbox_count"] > 0,
            "opaque fleet row is malformed or not a legacy Android installation",
        )
        utc_timestamp(row["last_seen_at"], "opaque fleet heartbeat")
        if row["last_successful_sync_at"] is not None:
            utc_timestamp(row["last_successful_sync_at"], "opaque fleet sync")
        parts = (
            row["row_id"], row["installation_id"], row["company_id"], row["terminal_id"] or "",
            row["platform"], row["version_name"], str(row["version_code"]), str(row["pending_outbox_count"]),
            row["last_successful_sync_at"], row["last_seen_at"],
        )
        digest = hashlib.sha256(chr(31).join(part for part in parts if part is not None).encode()).hexdigest()
        require(row["identity_sha256"] == digest, "opaque fleet row hash changed")
        identities[row["row_id"]] = digest
        installation_ids.add(row["installation_id"])
        pending += row["pending_outbox_count"]
    combined = hashlib.sha256("\n".join(identities[key] for key in sorted(identities)).encode()).hexdigest()
    require(
        len(rows) == report["nonzero_installation_count"] <= report["installation_row_count"]
        and pending == report["pending_outbox_count"]
        and combined == report["nonzero_installations_identity_sha256"],
        "opaque fleet counts or combined hash changed",
    )


def verify_tablet(tablet: dict, installation_id: str, captured_at: datetime) -> None:
    check_fields(tablet, {
        "installation_id", "platform", "version_code", "version_name",
        "pending_outbox_count", "last_successful_sync_at", "last_seen_at",
    }, "current tablet")
    require(
        canonical_uuid(installation_id) and tablet["installation_id"] == installation_id
        and tablet["platform"] == "android" and type(tablet["version_code"]) is int
        and tablet["version_code"] == 71 and tablet["version_name"] == "3.1.64"
        and type(tablet["pending_outbox_count"]) is int and tablet["pending_outbox_count"] == 0,
        "selected tablet is not Build 71 with a known empty outbox",
    )
    seen = utc_timestamp(tablet["last_seen_at"], "current tablet heartbeat")
    synced = utc_timestamp(tablet["last_successful_sync_at"], "current tablet sync")
    require(captured_at - MAX_TABLET_AGE <= synced <= seen <= captured_at, "selected tablet sync or heartbeat is stale or inconsistent")


def _redis_paths(active: dict) -> tuple[Path, Path]:
    flags = {
        "compose_recreation_requires_reviewed_config_carry_forward",
        "no_apk", "ordinary_restart_preserves_fix",
    }
    check_fields(active, flags | {"active_repair_receipt", "hold"}, "active Redis repair")
    require(all(active[key] is True for key in flags), "active Redis repair flags changed")
    result, hold = (Path(active[field]) for field in ("active_repair_receipt", "hold"))
    require(
        str(result) == active["active_repair_receipt"] and str(hold) == active["hold"]
        and result.is_absolute() and ".." not in result.parts and result.is_relative_to(REDIS_REPAIRS)
        and result.name == "RESULT.json" and hold == result.parent / "CONFIG_RECREATION_HOLD.json",
        "active Redis repair points outside its canonical evidence directory",
    )
    return result, hold


def _same_redis_delta(value: object) -> bool:
    return (isinstance(value, list) and len(value) == len(REDIS_FUNCTIONAL_DELTA)
            and all(isinstance(item, str) for item in value) and set(value) == REDIS_FUNCTIONAL_DELTA)


def verify_redis(live: dict, image_sha256: str, acl_sha256: str, active: dict, result: dict, hold: dict) -> None:
    result_path, _ = _redis_paths(active)
    check_fields(live, {
        "container_id", "image_id", "data_volume", "data_mount", "command", "working_dir",
        "entrypoint", "acl_sha256",
    }, "live Redis")
    check_hex(live["container_id"], 64, "Redis container identity")
    require(live["acl_sha256"] == acl_sha256, "Redis ACL checksum differs from the reviewed runtime")
    require(
        live["image_id"] == image_sha256
        and isinstance(live["data_volume"], str) and re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_.-]*", live["data_volume"])
        and live["data_mount"] == "/data" and live["working_dir"] == "/data"
        and live["command"] == REDIS_COMMAND and live["entrypoint"] == ["docker-entrypoint.sh"],
        "live Redis image, volume, ACL command or entrypoint differs",
    )
    result_flags = {
        "all_image_ids_unchanged", "db_inventory_unchanged",
        "future_compose_recreation_hold", "no_apk", "no_migration",
    }
    check_fields(result, result_flags | {
        "evidence_directory", "only_functional_change", "restricted_audit_probe",
        "same_named_volume", "schema", "source_revision", "status",
    }, "Redis repair receipt")
    require(
        all(result[key] is True for key in result_flags)
        and result["evidence_directory"] == str(result_path.parent)
        and _same_redis_delta(result["only_functional_change"])
        and result["restricted_audit_probe"] == "pass"
        and result["same_named_volume"] == live["data_volume"]
        and result["schema"] == "0095" and result["source_revision"] == BUILD71
        and result["status"] == "server_config_repair_pass",
        "Redis repair receipt does not attest the retained configuration",
    )
    hold_flags = {"old_compose_recreation_forbidden", "ordinary_restart_preserves_fix", "source_images_schema_unchanged"}
    check_fields(hold, hold_flags | {"functional_delta", "live_container", "prior_container", "reason"}, "Redis recreation hold")
    check_hex(hold["prior_container"], 64, "previous Redis container identity")
    require(
        all(hold[key] is True for key in hold_flags)
        and _same_redis_delta(hold["functional_delta"])
        and hold["live_container"] == live["container_id"] != hold["prior_container"]
        and isinstance(hold["reason"], str) and hold["reason"].strip(),
        "Redis recreation hold differs from the running configuration",
    )


def verify_evidence(
    current: dict, prior: dict, redis_records: tuple[dict, dict, dict],
    reviewed_runtime: dict, redis_receipt_sha256: dict,
    *, candidate_revision: str, installation_id: str, now: datetime,
) -> None:
    check_fields(current, {
        "schema_version", "captured_at", "source_revision", "candidate_revision", "source_archive_sha256",
        "image_ids", "business_quiescence", "fleet", "current_tablet", "redis",
    }, "live readiness evidence")
    check_hex(candidate_revision, 40, "candidate revision")
    check_fields(reviewed_runtime, {
        "schema_version", "source_revision", "source_archive_sha256",
        "image_ids", "redis_acl_sha256", "redis_receipt_sha256",
    }, "reviewed runtime")
    require(type(reviewed_runtime["schema_version"]) is int and reviewed_runtime["schema_version"] == 1
            and reviewed_runtime["source_revision"] == BUILD71, "reviewed runtime does not describe Build 71")
    check_hex(reviewed_runtime["source_archive_sha256"], 64, "reviewed source archive")
    check_hex(reviewed_runtime["redis_acl_sha256"], 64, "reviewed Redis ACL")
    require(
        type(current["schema_version"]) is int and current["schema_version"] == 1
        and current["source_revision"] == BUILD71 and current["candidate_revision"] == candidate_revision
        and current["source_archive_sha256"] == reviewed_runtime["source_archive_sha256"],
        "source or candidate differs from the reviewed identity",
    )
    captured = utc_timestamp(current["captured_at"], "current evidence capture")
    require(now - MAX_CAPTURE_AGE <= captured <= now, "current evidence is stale or future-dated")
    images = current["image_ids"]
    check_fields(images, SERVICES, "running images")
    expected_images = reviewed_runtime["image_ids"]
    check_fields(expected_images, SERVICES, "reviewed images")
    for image in expected_images.values():
        check_hex(image, 64, "reviewed image identity", prefix="sha256:")
    require(images == expected_images, "running image IDs differ from the reviewed runtime")
    expected_receipts = reviewed_runtime["redis_receipt_sha256"]
    check_fields(expected_receipts, set(REDIS_RECEIPT_KEYS), "reviewed Redis receipt checksums")
    for checksum in expected_receipts.values():
        check_hex(checksum, 64, "reviewed Redis receipt")
    require(redis_receipt_sha256 == expected_receipts, "Redis receipt checksums differ from the reviewed runtime")
    quiescence = run_path(str(Path(__file__).with_name("verify-production-business-quiescence.py")))
    quiescence["verify_state"](current["business_quiescence"])
    fleet = current["fleet"]
    verify_fleet(fleet, "0095")
    verify_fleet(prior, "0094")
    require(
        all(fleet[key] == prior[key] for key in FLEET_OPAQUE | FLEET_GUARD_HASHES)
        and fleet["installation_row_count"] > len(fleet["nonzero_installations"]),
        "reviewed opaque fleet rows or its write guard changed",
    )
    verify_tablet(current["current_tablet"], installation_id, captured)
    require(all(row["installation_id"] != installation_id for row in fleet["nonzero_installations"]),
            "selected tablet also appears in the opaque pending fleet")
    verify_redis(current["redis"], images["redis"], reviewed_runtime["redis_acl_sha256"], *redis_records)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--current", type=Path, required=True)
    parser.add_argument("--reviewed-prior-fleet", type=Path, required=True)
    parser.add_argument("--reviewed-prior-fleet-sha256", required=True)
    parser.add_argument("--reviewed-runtime", type=Path, required=True)
    parser.add_argument("--reviewed-runtime-sha256", required=True)
    parser.add_argument("--candidate-revision", required=True)
    parser.add_argument("--tablet-installation-id", required=True)
    args = parser.parse_args()
    try:
        current, _ = read_root_document(args.current)
        prior, observed_prior_sha = read_root_document(args.reviewed_prior_fleet)
        check_hex(args.reviewed_prior_fleet_sha256, 64, "reviewed prior fleet")
        require(observed_prior_sha == args.reviewed_prior_fleet_sha256, "prior fleet evidence does not match its reviewed checksum")
        runtime, observed_runtime_sha = read_root_document(args.reviewed_runtime)
        check_hex(args.reviewed_runtime_sha256, 64, "reviewed runtime")
        require(observed_runtime_sha == args.reviewed_runtime_sha256, "runtime baseline does not match its reviewed checksum")
        active, active_sha = read_root_document(REDIS_REPAIRS / "ACTIVE_REDIS_REPAIR.json")
        result_path, hold_path = _redis_paths(active)
        result, result_sha = read_root_document(result_path)
        hold, hold_sha = read_root_document(hold_path)
        receipt_sha256 = dict(zip(REDIS_RECEIPT_KEYS, (active_sha, result_sha, hold_sha), strict=True))
        verify_evidence(
            current, prior, (active, result, hold), runtime, receipt_sha256,
            candidate_revision=args.candidate_revision, installation_id=args.tablet_installation_id,
            now=datetime.now(UTC),
        )
    except (ValueError, TypeError, OSError) as error:
        # I/O error strings can expose private pathnames.
        reason = str(error) if isinstance(error, ValueError) else "invalid or unreadable private evidence"
        print(f"Build 72 readiness evidence rejected: {reason}", file=sys.stderr)
        return 1
    print(json.dumps({
        "accepted_legacy_queues_retained": prior["nonzero_installation_count"],
        "selected_tablet_reported_empty": True,
        "redis_recreation_forbidden": True,
        "production_admission": False,
    }, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
