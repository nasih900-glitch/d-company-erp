import copy
from datetime import UTC, datetime
import hashlib
import json
from pathlib import Path
from runpy import run_path

import pytest


SCRIPTS = Path(__file__).resolve().parents[1] / "infra/scripts"
READINESS = run_path(str(SCRIPTS / "verify-build72-readiness.py"))
QUIESCENCE = run_path(str(SCRIPTS / "verify-production-business-quiescence.py"))
NOW = datetime(2026, 11, 17, 10, 30, tzinfo=UTC)
REVIEWED_SOURCE = "a" * 64
CANDIDATE = "b" * 40
TABLET = "11111111-1111-4111-8111-111111111111"


def opaque_row(number: int, version: str, version_code: int, pending: int) -> dict:
    row = {
        "row_id": f"00000000-0000-4000-8000-{number:012}",
        "installation_id": f"22222222-2222-4222-8222-{number:012}",
        "company_id": "33333333-3333-4333-8333-333333333333",
        "terminal_id": None,
        "platform": "android",
        "version_name": version,
        "version_code": version_code,
        "pending_outbox_count": pending,
        "last_successful_sync_at": None,
        "last_seen_at": "2020-01-01T00:00:00.000000Z",
    }
    row["identity_sha256"] = hashlib.sha256("\x1f".join(
        str(row[key]) if row[key] is not None else "" for key in (
            "row_id", "installation_id", "company_id", "terminal_id", "platform",
            "version_name", "version_code", "pending_outbox_count", "last_seen_at",
        )
    ).encode()).hexdigest()
    return row


@pytest.fixture()
def evidence():
    rows = [opaque_row(1, "3.1.29", 37, 2), opaque_row(2, "3.1.46", 54, 3)]
    fleet = {
        "schema_version": 1,
        "database_revision": "0094",
        "installation_row_count": 4,
        "pending_outbox_count": 5,
        "nonzero_installation_count": 2,
        "all_installations_identity_sha256": "1" * 64,
        "client_installation_guard_trigger_count": 1,
        "client_installation_guard_function_sha256": "2" * 64,
        "client_installation_guard_function_definition_sha256": "3" * 64,
        "client_installation_guard_trigger_definition_sha256": "4" * 64,
        "nonzero_installations_identity_sha256": hashlib.sha256("\n".join(
            row["identity_sha256"] for row in rows
        ).encode()).hexdigest(),
        "nonzero_installations": rows,
    }
    images = {service: f"sha256:{number}" + "0" * 63 for number, service in enumerate(
        sorted(READINESS["SERVICES"])
    )}
    current = {
        "schema_version": 1,
        "source_revision": READINESS["BUILD71"],
        "candidate_revision": CANDIDATE,
        "source_archive_sha256": REVIEWED_SOURCE,
        "captured_at": "2026-11-17T10:29:00Z",
        "image_ids": images,
        "business_quiescence": {"schema_version": 1, **dict.fromkeys(QUIESCENCE["COUNT_FIELDS"], 0)},
        "fleet": {**copy.deepcopy(fleet), "database_revision": "0095", "installation_row_count": 5,
                  "all_installations_identity_sha256": "5" * 64},
        "current_tablet": {
            "installation_id": TABLET, "platform": "android", "version_name": "3.1.64",
            "version_code": 71, "pending_outbox_count": 0, "last_seen_at": "2026-11-17T10:28:30Z",
            "last_successful_sync_at": "2026-11-17T10:28:00Z",
        },
        "redis": {
            "container_id": "c" * 64, "image_id": images["redis"], "acl_sha256": "6" * 64,
            "data_volume": "example_redis_data", "data_mount": "/data", "working_dir": "/data",
            "entrypoint": ["docker-entrypoint.sh"],
            "command": ["redis-server", "--aclfile", "/run/redis-users.acl"],
        },
    }
    records = READINESS["REDIS_REPAIRS"] / "example-evidence"
    delta = ["+lpush", "+llen", "+rpop"]
    active = {
        "active_repair_receipt": str(records / "RESULT.json"),
        "hold": str(records / "CONFIG_RECREATION_HOLD.json"),
        "compose_recreation_requires_reviewed_config_carry_forward": True,
        "no_apk": True, "ordinary_restart_preserves_fix": True,
    }
    result = {
        "all_image_ids_unchanged": True, "db_inventory_unchanged": True,
        "future_compose_recreation_hold": True, "no_apk": True, "no_migration": True,
        "evidence_directory": str(records), "only_functional_change": delta.copy(),
        "restricted_audit_probe": "pass", "same_named_volume": "example_redis_data",
        "schema": "0095", "source_revision": READINESS["BUILD71"], "status": "server_config_repair_pass",
    }
    hold = {
        "functional_delta": delta.copy(), "live_container": "c" * 64, "prior_container": "d" * 64,
        "old_compose_recreation_forbidden": True, "ordinary_restart_preserves_fix": True,
        "reason": "Former Compose startup could replace the reviewed configuration.",
        "source_images_schema_unchanged": True,
    }
    receipt_sha256 = {"active": "7" * 64, "result": "8" * 64, "hold": "9" * 64}
    runtime = {
        "schema_version": 1,
        "source_revision": READINESS["BUILD71"],
        "source_archive_sha256": REVIEWED_SOURCE,
        "image_ids": images.copy(),
        "redis_acl_sha256": "6" * 64,
        "redis_receipt_sha256": receipt_sha256.copy(),
    }
    return current, fleet, (active, result, hold), runtime, receipt_sha256


def verify(evidence) -> None:
    READINESS["verify_evidence"](
        *evidence, candidate_revision=CANDIDATE, installation_id=TABLET, now=NOW,
    )


def test_accepts_exact_opaque_legacy_rows_without_claiming_drain(evidence):
    verify(evidence)
    assert evidence[0]["fleet"]["pending_outbox_count"] == 5


@pytest.mark.parametrize(("location", "value", "reason"), [
    (("candidate_revision",), "f" * 40, "reviewed identity"),
    (("source_archive_sha256",), "f" * 64, "reviewed identity"),
    (("captured_at",), "2026-11-17T10:23:00Z", "evidence is stale"),
    (("image_ids", "backend"), "sha256:" + "f" * 64, "image IDs differ"),
    (("business_quiescence", "open_shifts"), 1, "open_shifts=1"),
    (("fleet", "pending_outbox_count"), 0, "fleet counts"),
    (("fleet", "nonzero_installations", 0, "pending_outbox_count"), 8, "row hash changed"),
    (("fleet", "nonzero_installations_identity_sha256"), "0" * 64, "combined hash"),
    (("fleet", "client_installation_guard_function_definition_sha256"), "0" * 64, "write guard changed"),
    (("current_tablet", "last_successful_sync_at"), None, "canonical UTC timestamp"),
    (("current_tablet", "last_successful_sync_at"), "2026-11-17T09:00:00Z", "heartbeat is stale"),
    (("current_tablet", "pending_outbox_count"), False, "known empty outbox"),
    (("current_tablet", "pending_outbox_count"), 1, "known empty outbox"),
    (("redis", "data_volume"), "different-volume", "retained configuration"),
    (("redis", "data_mount"), "/somewhere-else", "image, volume"),
    (("redis", "acl_sha256"), "f" * 64, "ACL checksum differs"),
    (("redis", "entrypoint"), ["/bin/sh"], "image, volume"),
    (("redis", "container_id"), "e" * 64, "recreation hold differs"),
])
def test_refuses_changed_live_record(evidence, location, value, reason):
    target = evidence[0]
    for key in location[:-1]:
        target = target[key]
    target[location[-1]] = value
    with pytest.raises(ValueError, match=reason):
        verify(evidence)


def test_refuses_changed_legacy_set_even_if_rehashed(evidence):
    current = evidence[0]
    current["fleet"] = {
        **current["fleet"], "pending_outbox_count": 0, "nonzero_installation_count": 0,
        "nonzero_installations": [], "nonzero_installations_identity_sha256": hashlib.sha256(b"").hexdigest(),
    }
    with pytest.raises(ValueError, match="opaque fleet rows"):
        verify(evidence)


def test_refuses_redis_link_outside_config_repair_directory(evidence):
    evidence[2][0]["active_repair_receipt"] = "/tmp/RESULT.json"
    with pytest.raises(ValueError, match="canonical evidence directory"):
        verify(evidence)


def test_refuses_redis_hold_with_wider_acl(evidence):
    evidence[2][2]["functional_delta"].append("+rpush")
    with pytest.raises(ValueError, match="recreation hold differs"):
        verify(evidence)


@pytest.mark.parametrize("receipt", READINESS["REDIS_RECEIPT_KEYS"])
def test_refuses_changed_redis_receipt_bytes(evidence, receipt):
    evidence[4][receipt] = "f" * 64
    with pytest.raises(ValueError, match="receipt checksums differ"):
        verify(evidence)


def test_refuses_same_container_before_and_after_redis_replacement(evidence):
    hold = evidence[2][2]
    hold["prior_container"] = hold["live_container"]
    with pytest.raises(ValueError, match="recreation hold differs"):
        verify(evidence)


def test_refuses_non_absolute_root_evidence_input():
    with pytest.raises(ValueError, match="canonical and absolute"):
        READINESS["read_root_document"](Path("evidence.json"))


def test_cli_never_reports_admission(evidence, monkeypatch, capsys):
    main = READINESS["main"]
    current, prior, records, runtime, receipt_sha256 = evidence
    active, result, hold = records
    checked_digest = "7" * 64
    runtime_digest = "a" * 64
    reports = {
        "/current.json": (current, "b" * 64),
        "/prior.json": (prior, checked_digest),
        "/runtime.json": (runtime, runtime_digest),
        str(READINESS["REDIS_REPAIRS"] / "ACTIVE_REDIS_REPAIR.json"): (active, receipt_sha256["active"]),
        active["active_repair_receipt"]: (result, receipt_sha256["result"]),
        active["hold"]: (hold, receipt_sha256["hold"]),
    }
    monkeypatch.setitem(main.__globals__, "read_root_document", lambda path: reports[str(path)])

    class Clock:
        @staticmethod
        def now(zone):
            return NOW

        fromisoformat = datetime.fromisoformat

    monkeypatch.setitem(main.__globals__, "datetime", Clock)
    args = [
        "verify-build72-readiness.py", "--current", "/current.json", "--reviewed-prior-fleet", "/prior.json",
        "--reviewed-prior-fleet-sha256", checked_digest,
        "--reviewed-runtime", "/runtime.json", "--reviewed-runtime-sha256", runtime_digest,
        "--candidate-revision", CANDIDATE, "--tablet-installation-id", TABLET,
    ]
    monkeypatch.setattr("sys.argv", args)
    assert main() == 0
    assert json.loads(capsys.readouterr().out) == {
        "accepted_legacy_queues_retained": 2, "selected_tablet_reported_empty": True,
        "redis_recreation_forbidden": True, "production_admission": False,
    }
    args[args.index(checked_digest)] = "0" * 64
    assert main() == 1
    assert "prior fleet evidence" in capsys.readouterr().err
    args[args.index("0" * 64)] = checked_digest
    args[args.index(runtime_digest)] = "0" * 64
    assert main() == 1
    assert "runtime baseline" in capsys.readouterr().err
