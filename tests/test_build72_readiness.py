import copy
from datetime import UTC, datetime
import hashlib
import io
import json
from pathlib import Path
from runpy import run_path
from types import SimpleNamespace
import subprocess

import pytest


SCRIPTS = Path(__file__).resolve().parents[1] / "infra/scripts"
READINESS = run_path(str(SCRIPTS / "verify-build72-readiness.py"))
QUIESCENCE = run_path(str(SCRIPTS / "verify-production-business-quiescence.py"))
UPGRADE_RUNTIME = run_path(str(SCRIPTS / "verify-build72-upgrade-runtime.py"))
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


@pytest.mark.parametrize("changed", ("DATABASE_URL", "REDIS_URL", "JWT_SECRET"))
def test_refuses_candidate_environment_overrides_of_live_connections(changed):
    live = {"DATABASE_URL": "example-database", "REDIS_URL": "example-redis", "JWT_SECRET": "example-signing-key"}
    running = {"Config": {"Env": [f"{key}={value}" for key, value in live.items()]}}
    candidate = {"services": {"backend": {"environment": live.copy()}}}
    check = UPGRADE_RUNTIME["verify_backend_connections"]
    check(running, candidate)
    candidate["services"]["backend"]["environment"][changed] = "different"
    with pytest.raises(ValueError, match="differs from the running backend"):
        check(running, candidate)


@pytest.mark.parametrize("drift", (None, "wrong-volume", "wrong-command", "unhealthy", "wrong-network"))
def test_retained_redis_matches_docker_mount_command_network_and_acl(drift, monkeypatch):
    observe = UPGRADE_RUNTIME["redis_observation"]
    row = {
        "Id": "a" * 64, "Image": "sha256:" + "b" * 64, "RestartCount": 0,
        "Config": {"Entrypoint": ["docker-entrypoint.sh"], "WorkingDir": "/data",
                   "Cmd": ["/bin/sh", "-ec", "umask 077\nexec redis-server --aclfile /run/redis-users.acl\n"]},
        "State": {"Running": True, "Status": "running", "Paused": False, "Restarting": False,
                  "Health": {"Status": "healthy"}, "StartedAt": "original-start"},
        "Mounts": [{"Destination": "/data", "Type": "volume", "Driver": "local", "RW": True,
                    "Name": "old_data", "Source": "/docker/volumes/old_data/_data"}],
        "NetworkSettings": {"Networks": {"internal": {"NetworkID": "c" * 64, "DNSNames": ["redis"], "Aliases": None}}},
    }
    if drift == "wrong-volume":
        row["Mounts"][0]["Source"] = "/wrong"
    if drift == "wrong-command":
        row["Config"]["Cmd"][2] = "exec redis-server --aclfile /run/redis-users.acl --save ''"
    if drift == "unhealthy":
        row["State"]["Health"]["Status"] = "unhealthy"
    if drift == "wrong-network":
        row["NetworkSettings"]["Networks"]["internal"]["DNSNames"] = []

    def docker(*args):
        if args == ("volume", "inspect", "old_data"):
            return json.dumps([{"Name": "old_data", "Driver": "local", "Mountpoint": "/docker/volumes/old_data/_data"}])
        assert args == ("exec", row["Id"], "sha256sum", "/run/redis-users.acl")
        return "d" * 64 + "  /run/redis-users.acl"

    monkeypatch.setitem(observe.__globals__, "docker", docker)
    if drift is None:
        redis, retained = observe(row)
        assert redis["acl_sha256"] == "d" * 64
        assert retained["network_id"] == "c" * 64
        assert retained["started_at"] == "original-start"
    else:
        with pytest.raises(ValueError):
            observe(row)


def test_upgrade_samples_live_services_before_and_after_writer_shutdown(evidence, monkeypatch):
    current, prior, redis_records, runtime, receipt_hashes = evidence
    observe = UPGRADE_RUNTIME["observe"]
    scope = observe.__globals__
    record = {"reviewed_prior_fleet": "/prior.json", "reviewed_prior_fleet_sha256": "a" * 64,
              "reviewed_runtime": "/runtime.json", "reviewed_runtime_sha256": "b" * 64,
              "tablet_installation_id": TABLET}
    retained = {"redis": current["redis"], "started_at": "unchanged-docker-start"}
    statuses = dict.fromkeys(READINESS["SERVICES"], "running")
    events = []

    def service(project, service):
        assert project == "erp-qa"
        events.append(service)
        state = statuses[service]
        return {"Id": service, "Image": runtime["image_ids"][service], "State": {
            "Status": state, "Running": state == "running", "Restarting": False, "Paused": False,
        }}

    def query(container, sql):
        assert container == "postgres"
        if "pg_settings" in sql:
            return json.loads((SCRIPTS.parent / "security/vex/postgres16-pgcrypto-openssl-reviewed.json").read_text())["database_state"]
        return current["fleet"] if "client_installation_guard_function_sha256" in sql else current["business_quiescence"]

    class Clock:
        @staticmethod
        def now(zone):
            return NOW

    monkeypatch.setitem(scope, "datetime", Clock)
    monkeypatch.setitem(scope, "checked_document", lambda path, sha: {"/prior.json": prior, "/runtime.json": runtime}[path])
    monkeypatch.setitem(scope, "receipts", lambda: (redis_records, receipt_hashes))
    monkeypatch.setitem(scope, "service", service)
    monkeypatch.setitem(scope, "postgres_mount", lambda row: {"Name": "example_pgdata"})
    monkeypatch.setitem(scope, "database_identity", lambda container: {"system_identifier": "example"})
    monkeypatch.setitem(scope, "caddy_storage", lambda row, *args, **kwargs: {"/data": "example_tls"})
    monkeypatch.setitem(scope, "sealed_storage", lambda args: running["storage"])
    monkeypatch.setitem(scope, "redis_observation", lambda row: (current["redis"], retained.copy()))
    monkeypatch.setitem(scope, "query", query)
    monkeypatch.setitem(scope, "tablet", lambda container, installation: current["current_tablet"])
    monkeypatch.setitem(scope, "read_root_document", lambda path: (saved, "c" * 64))
    args = SimpleNamespace(project="erp-qa", phase="running", baseline=None, baseline_sha256="c" * 64,
                           source_revision=READINESS["BUILD71"], source_archive_sha256=REVIEWED_SOURCE,
                           postgres="postgres", revision=CANDIDATE, apk_root=Path("/example/releases/android"),
                           caddy_config=Path("/example/Caddyfile"))
    running = observe(args, record)
    saved = running["retained"]
    assert set(events) == READINESS["SERVICES"]
    assert running["current"]["fleet"] == current["fleet"]

    args.phase, args.baseline = "stopped", Path("/sealed.json")
    with pytest.raises(ValueError, match="writer is still running"):
        observe(args, record)
    statuses.update(caddy="exited", backend="exited", frontend="exited")
    assert observe(args, record)["retained"] == saved

    args.phase = "redis-only"
    events.clear()
    assert observe(args, record) == {"retained_redis": True}
    assert events == ["redis"]
    retained["started_at"] = "restarted-outside-installer"
    with pytest.raises(ValueError, match="sealed start identity changed"):
        observe(args, record)


@pytest.mark.parametrize("image_matches", (True, False))
def test_caddy_is_created_without_dependencies_and_started_only_after_image_check(image_matches):
    installer = (SCRIPTS / "install-on-vm.sh").read_text()
    routine = "build72_start_caddy() {" + installer.split("build72_start_caddy() {", 1)[1].split("\n}\n")[0] + "\n}\n"
    result = subprocess.run(["bash", "-c", routine + r'''
set -eu
verify_build72_retained_redis() { echo 'redis proof'; }
compose() {
  if [ "$1" = ps ]; then printf '%064d\n' 1; else echo "$*"; fi
}
docker() {
  case "$1" in
    start) echo 'started inspected caddy' >&2 ;;
    *) exit 99 ;;
  esac
}
build72_runtime() { [ "$1" = caddy-before-start ] && [ "$5" = expected ] && [ "$observed" = expected ]; }
observed=$1
build72_start_caddy expected /example/Caddyfile compose
''', "build72-caddy", "expected" if image_matches else "foreign"],
        capture_output=True, text=True, check=False, timeout=5)
    assert result.returncode == (0 if image_matches else 1)
    assert "up --no-start --no-deps --no-build --pull never --force-recreate caddy" in result.stdout
    assert ("started inspected caddy" in result.stderr) == image_matches
    assert result.stdout.count("redis proof") == (2 if image_matches else 1)


def test_postgres_storage_gate_refuses_wrong_mount_or_cluster_before_sql_restore(monkeypatch):
    verify_storage = UPGRADE_RUNTIME["verify_storage"]
    expected_mount = {"Name": "erp_pgdata", "Source": "/volumes/erp_pgdata", "Destination": "/var/lib/postgresql/data"}
    identity = {"system_identifier": "old-cluster", "database_oid": 9, "database_name": "erp"}
    container = {"Id": "test-db", "Image": "expected", "State": {"Status": "created"}}
    actual = {"mount": expected_mount, "identity": identity}
    scope = verify_storage.__globals__
    monkeypatch.setitem(scope, "sealed_storage", lambda args: {"postgres_mount": expected_mount, "database_identity": identity})
    monkeypatch.setitem(scope, "service", lambda project, name: container)
    monkeypatch.setitem(scope, "postgres_mount", lambda row: actual["mount"])
    monkeypatch.setitem(scope, "database_identity", lambda container: actual["identity"])
    args = SimpleNamespace(phase="postgres-before-start", project="erp", container_id="test-db", image_id="expected")
    verify_storage(args)
    actual["mount"] = {**expected_mount, "Name": "other_volume"}
    with pytest.raises(ValueError, match="mount differs"):
        verify_storage(args)
    actual["mount"] = expected_mount
    args.phase = "postgres-before-restore"
    container["State"] = {"Status": "running", "Running": True, "Paused": False, "Restarting": False}
    verify_storage(args)
    actual["identity"] = {**identity, "system_identifier": "wrong-cluster"}
    with pytest.raises(ValueError, match="different PostgreSQL data cluster"):
        verify_storage(args)


def test_successful_deployment_receipt_is_private_bound_and_contains_no_credential(tmp_path, monkeypatch):
    write = UPGRADE_RUNTIME["write_deployment_receipt"]
    read = UPGRADE_RUNTIME["staging_identity"]
    scope = write.__globals__
    repository = (tmp_path / "erp").resolve()
    repository.mkdir()
    receipt_directory = tmp_path / "deployments"
    receipt_directory.mkdir(mode=0o700)
    baseline = Path("/protected/retained-redis.json")
    retained = {"redis": {"image_id": "older-verified-image"}}
    checksums = {"active": "8" * 64, "result": "9" * 64, "hold": "a" * 64}
    images = {name: "sha256:" + "c" * 64 for name in UPGRADE_RUNTIME["CANDIDATE_SERVICES"]}
    running = {"version_name": "3.1.68", "source_git_sha": CANDIDATE,
               "services": {name: {"image_id": image} for name, image in images.items()}}
    args = SimpleNamespace(baseline=baseline, baseline_sha256="d" * 64, revision=CANDIDATE,
                           candidate_source_archive_sha256="e" * 64, project="erp", repo_root=repository)
    observed = []

    def read_protected(path):
        if path == baseline:
            return retained, "d" * 64
        data = path.read_bytes()
        return json.loads(data), hashlib.sha256(data).hexdigest()

    def retained_redis(project, expected, expected_checksums):
        assert (project, expected, expected_checksums) == ("erp", retained, checksums)
        observed.append(project)
        return {"version_name": "3.1.64", "retained": True}

    def verify_schema(project, image):
        assert (project, image) == ("erp", images["postgres"])

    monkeypatch.setitem(scope, "INSTALL_RECEIPTS", receipt_directory)
    monkeypatch.setitem(scope, "read_root_document", read_protected)
    monkeypatch.setitem(scope, "verify_retained_redis", retained_redis)
    monkeypatch.setitem(scope, "verify_live_schema", verify_schema)
    monkeypatch.setitem(scope, "run_path", lambda file: {"ensure_private_state_directories": lambda **kwargs: None})
    monkeypatch.setattr(scope["sys"], "stdin", io.StringIO(json.dumps(running)))
    write(args, {"redis_receipt_sha256": checksums, "owner_credential_file": "/protected/do-not-persist"})

    receipt, = receipt_directory.iterdir()
    assert receipt.name == f"{CANDIDATE}.json"
    assert (receipt.stat().st_mode & 0o777) == 0o600
    assert "credential" not in receipt.read_text()
    project, expected, redis, digest = read(repository, CANDIDATE)
    assert (project, expected, redis) == ("erp", images, {"version_name": "3.1.64", "retained": True})
    assert hashlib.sha256(receipt.read_bytes()).hexdigest() == digest
    assert observed == ["erp", "erp"]
    with pytest.raises(FileNotFoundError):
        read(repository, "1" * 40)


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


def test_upgrade_checks_live_postgres_crypto_catalogue_read_only_and_fails_closed(monkeypatch):
    probe = UPGRADE_RUNTIME["verify_postgres_crypto_runtime"]
    expected = json.loads((SCRIPTS.parent / "security/vex/postgres16-pgcrypto-openssl-reviewed.json").read_text())["database_state"]
    observed = copy.deepcopy(expected)
    statements = []

    def query(container, statement):
        assert container == "postgres"
        statements.append(statement)
        return observed

    monkeypatch.setitem(probe.__globals__, "query", query)
    probe("postgres")
    assert statements and "BEGIN READ ONLY;" in statements[0]
    assert all(name in statements[0] for name in ("pg_settings", "pg_extension", "pg_proc", "pg_db_role_setting"))
    observed["settings"]["shared_preload_libraries"] = "unreviewed"
    with pytest.raises(ValueError, match="crypto/preload/extension"):
        probe("postgres")

    def unavailable(*args):
        raise OSError("database unavailable")

    monkeypatch.setitem(probe.__globals__, "query", unavailable)
    with pytest.raises(OSError):
        probe("postgres")
