#!/usr/bin/env python3
"""Sample the reviewed Build 71 host and verify that its Redis remains intact."""

from __future__ import annotations

import argparse
from datetime import UTC, datetime
import hashlib
import json
import os
from pathlib import Path
import re
from runpy import run_path
import subprocess
import sys
import tempfile


SCRIPTS = Path(__file__).resolve().parent
READY = run_path(str(SCRIPTS / "verify-build72-readiness.py"))
require = READY["require"]
read_root_document = READY["read_root_document"]
PG_MOUNT = "/var/lib/postgresql/data"
CADDY_CONFIG = "/etc/caddy/Caddyfile"
CADDY_APKS = "/srv/releases/android"
INSTALL_RECEIPTS = Path("/var/lib/dcompany-erp/build72-deployments")
RELEASE_VERSION = "3.1.78"
RELEASE_BUILD = 85
RELEASE_SCHEMA = "0105"
CANDIDATE_SERVICES = ("postgres", "backend", "frontend", "caddy")


def fixed_sha(value, label, size=64):
    READY["check_hex"](value, size, label)


def inputs(path: Path, revision: str, digest: str | None) -> tuple[dict, str]:
    record, actual_sha = read_root_document(path)
    require(digest is None or digest == actual_sha, "upgrade evidence changed")
    READY["check_fields"](record, {
        "schema_version", "candidate_revision", "tablet_installation_id", "owner_credential_file",
        "candidate_source_archive_sha256",
        "reviewed_runtime", "reviewed_runtime_sha256", "reviewed_prior_fleet", "reviewed_prior_fleet_sha256",
    }, "upgrade evidence")
    fixed_sha(revision, "candidate revision", size=40)
    require(type(record["schema_version"]) is int and record["schema_version"] == 1
            and record["candidate_revision"] == revision, "upgrade evidence is for another source revision")
    require(READY["canonical_uuid"](record["tablet_installation_id"]), "invalid selected tablet")
    for key in ("reviewed_runtime", "reviewed_prior_fleet", "owner_credential_file"):
        value = record[key]
        require(isinstance(value, str) and value and not any(char in value for char in "\0\n\r\t"),
                "invalid private input path")
        path = Path(value)
        require(path.is_absolute() and str(path) == value and ".." not in path.parts, "noncanonical private input path")
    fixed_sha(record["candidate_source_archive_sha256"], "candidate source archive checksum")
    for key in ("reviewed_runtime", "reviewed_prior_fleet"):
        fixed_sha(record[f"{key}_sha256"], "reviewed input checksum")
    return record, actual_sha


def checked_document(path: str, sha: str) -> dict:
    record, actual = read_root_document(Path(path))
    require(actual == sha, "reviewed input checksum changed")
    return record


def docker(*args: str, sql: str | None = None) -> str:
    result = subprocess.run(["docker", *args], input=sql, text=True, capture_output=True, timeout=60, check=False)
    # Docker/psql error details may contain secrets or operational identifiers.
    require(result.returncode == 0, "Docker or PostgreSQL evidence capture failed")
    return result.stdout.strip()


def service(project: str, name: str) -> dict:
    ids = docker("ps", "-aq", "--no-trunc", "--filter", f"label=com.docker.compose.project={project}",
                 "--filter", f"label=com.docker.compose.service={name}").splitlines()
    require(len(ids) == 1, "production service identity is missing or ambiguous")
    fixed_sha(ids[0], "container identity")
    row, = json.loads(docker("inspect", ids[0]))
    labels = row["Config"]["Labels"]
    require(row["Id"] == ids[0] and labels["com.docker.compose.project"] == project
            and labels["com.docker.compose.service"] == name, "production service labels differ")
    return row


def process_is_running(container: dict) -> bool:
    state = container["State"]
    return state["Running"] is True and state["Status"] == "running" and not state["Restarting"] and not state["Paused"]


def mount_at(row: dict, target: str) -> dict:
    mount, = [entry for entry in row["Mounts"] if entry["Destination"] == target]
    return mount


def named_mount(row: dict, target: str) -> dict:
    mount = mount_at(row, target)
    require(mount["Type"] == "volume" and mount["Driver"] == "local" and mount["RW"] is True,
            "a retained service does not have a writable local named volume")
    volume, = json.loads(docker("volume", "inspect", mount["Name"]))
    require(volume["Name"] == mount["Name"] and volume["Driver"] == "local"
            and volume["Mountpoint"] == mount["Source"], "retained named volume does not resolve to its live mount")
    return {key: mount[key] for key in ("Name", "Source", "Destination")}


def readonly_bind(row: dict, target: str) -> Path:
    mount = mount_at(row, target)
    source = Path(mount["Source"])
    require(mount["Type"] == "bind" and mount["RW"] is False and source.is_absolute()
            and source.resolve(strict=True) == source, "Caddy bind source is not canonical and read-only")
    return source


def postgres_mount(row: dict) -> dict:
    require(len(row["Mounts"]) == 1, "unexpected PostgreSQL mount may override retained data storage")
    return named_mount(row, PG_MOUNT)


def caddy_storage(row: dict, apk_root: Path, config: Path, *, exact_path: bool) -> dict:
    require(len(row["Mounts"]) == 4 and readonly_bind(row, CADDY_APKS) == apk_root,
            "Caddy is not serving the original Android release directory")
    actual_config = readonly_bind(row, CADDY_CONFIG)
    require((not exact_path or actual_config == config)
            and hashlib.sha256(actual_config.read_bytes()).digest() == hashlib.sha256(config.read_bytes()).digest(),
            "Caddy does not bind the reviewed immutable configuration")
    return {target: named_mount(row, target) for target in ("/data", "/config")}


def redis_observation(row: dict) -> tuple[dict, dict]:
    config, state = row["Config"], row["State"]
    require(process_is_running(row) and state["Health"]["Status"] == "healthy", "retained Redis is not healthy")
    mount = named_mount(row, "/data")
    command = config["Cmd"]
    if (isinstance(command, list) and len(command) == 3 and command[0] in {"sh", "/bin/sh"}
            and command[1] in {"-c", "-ec"} and isinstance(command[2], str)):
        final_line = command[2].strip().rsplit("\n", 1)[-1].strip()
        command = final_line.split()[1:] if final_line.startswith("exec ") else []
    require(command == READY["REDIS_COMMAND"], "Redis is not configured to start with the reviewed ACL file")
    checksum = docker("exec", row["Id"], "sha256sum", "/run/redis-users.acl").split()
    require(len(checksum) == 2 and checksum[1] == "/run/redis-users.acl", "Redis ACL file digest is unavailable")
    fixed_sha(checksum[0], "Redis ACL checksum")
    observed = {
        "container_id": row["Id"], "image_id": row["Image"], "data_volume": mount["Name"],
        "data_mount": mount["Destination"], "command": command, "working_dir": config["WorkingDir"],
        "entrypoint": config["Entrypoint"], "acl_sha256": checksum[0],
    }
    networks = row["NetworkSettings"]["Networks"]
    require(isinstance(networks, dict) and len(networks) == 1, "Redis network attachment is ambiguous")
    network, = networks.values()
    fixed_sha(network["NetworkID"], "Redis network identity")
    require("redis" in (network.get("Aliases") or []) or "redis" in (network.get("DNSNames") or []),
            "retained Redis is unreachable by its service name")
    retained = {"redis": observed, "started_at": state["StartedAt"], "mount_source": mount["Source"],
                "network_id": network["NetworkID"], "restart_count": row["RestartCount"]}
    require(isinstance(retained["started_at"], str) and retained["started_at"], "Redis start identity is unavailable")
    return observed, retained


def receipts() -> tuple[tuple[dict, dict, dict], dict]:
    active, active_sha = read_root_document(READY["REDIS_REPAIRS"] / "ACTIVE_REDIS_REPAIR.json")
    result_path, hold_path = READY["_redis_paths"](active)
    result, result_sha = read_root_document(result_path)
    hold, hold_sha = read_root_document(hold_path)
    checksums = dict(zip(READY["REDIS_RECEIPT_KEYS"], (active_sha, result_sha, hold_sha), strict=True))
    return (active, result, hold), checksums


def verify_retained_redis(project: str, expected: dict, expected_receipt_hashes: dict) -> dict:
    redis, current = redis_observation(service(project, "redis"))
    records, checksums = receipts()
    require(current == expected and checksums == expected_receipt_hashes,
            "deployed Redis or reviewed repair evidence differs")
    READY["verify_redis"](redis, expected["redis"]["image_id"], expected["redis"]["acl_sha256"], *records)
    return {"container_id": redis["container_id"], "image_id": redis["image_id"],
            "version_name": "3.1.64", "source_git_sha": READY["BUILD71"], "retained": True}


def deployment_root(path: Path) -> str:
    require(path.is_absolute() and path != Path("/") and path.resolve(strict=True) == path,
            "Build 72 deployment root is not canonical")
    return str(path)


def deployment_receipt_path(revision: str) -> Path:
    fixed_sha(revision, "candidate revision", size=40)
    return INSTALL_RECEIPTS / f"{revision}.json"


def verify_live_schema(project: str, expected_postgres: str) -> None:
    postgres = service(project, "postgres")
    require(process_is_running(postgres) and postgres["Image"] == expected_postgres,
            "Build 72 PostgreSQL is not the admitted image")
    verify_postgres_crypto_runtime(postgres["Id"])
    observed = query(postgres["Id"], """
SELECT jsonb_build_object('revisions', jsonb_agg(version_num)) FROM public.alembic_version;
""")
    require(observed == {"revisions": [RELEASE_SCHEMA]}, "Build 72 database is not at its accepted migration head")


def write_deployment_receipt(args, reviewed: dict) -> None:
    retained, digest = read_root_document(args.baseline)
    require(digest == args.baseline_sha256, "sealed Redis baseline changed")
    verify_retained_redis(args.project, retained, reviewed["redis_receipt_sha256"])
    running = json.load(sys.stdin)
    rows = running["services"]
    require(set(rows) == set(CANDIDATE_SERVICES) and running["version_name"] == RELEASE_VERSION
            and running["source_git_sha"] == args.revision, "unexpected candidate running image inventory")
    expected = {name: rows[name]["image_id"] for name in CANDIDATE_SERVICES}
    for image in expected.values():
        READY["check_hex"](image, 64, "candidate running image", prefix="sha256:")
    verify_live_schema(args.project, expected["postgres"])
    payload = {
        "schema_version": 1, "version_code": RELEASE_BUILD, "version_name": RELEASE_VERSION,
        "source_git_sha": args.revision, "source_archive_sha256": args.candidate_source_archive_sha256,
        "database_head": RELEASE_SCHEMA, "project": args.project, "repo_root": deployment_root(args.repo_root),
        "candidate_images": expected, "retained_redis": retained, "redis_receipt_sha256": reviewed["redis_receipt_sha256"],
    }
    lock_support = run_path(str(SCRIPTS / "production_install_lock.py"))
    lock_support["ensure_private_state_directories"](state_subdirectory_names=(INSTALL_RECEIPTS.name,))
    destination = deployment_receipt_path(args.revision)
    with tempfile.NamedTemporaryFile(mode="w", dir=INSTALL_RECEIPTS, delete=False) as stream:
        temporary = Path(stream.name)
        try:
            json.dump(payload, stream, sort_keys=True)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
            os.replace(temporary, destination)
            descriptor = os.open(INSTALL_RECEIPTS, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
            try:
                os.fsync(descriptor)
            finally:
                os.close(descriptor)
        finally:
            temporary.unlink(missing_ok=True)


def staging_identity(root: Path, revision: str) -> tuple[str, dict, dict, str]:
    receipt, digest = read_root_document(deployment_receipt_path(revision))
    READY["check_fields"](receipt, {
        "schema_version", "version_code", "version_name", "source_git_sha", "source_archive_sha256",
        "database_head", "project", "repo_root", "candidate_images", "retained_redis", "redis_receipt_sha256",
    }, "accepted Build 72 deployment")
    require(type(receipt["schema_version"]) is int and receipt["schema_version"] == 1
            and type(receipt["version_code"]) is int and receipt["version_code"] == RELEASE_BUILD
            and receipt["version_name"] == RELEASE_VERSION and receipt["source_git_sha"] == revision
            and receipt["database_head"] == RELEASE_SCHEMA and receipt["repo_root"] == deployment_root(root),
            "accepted Build 72 deployment does not match the requested release")
    fixed_sha(receipt["source_archive_sha256"], "accepted Build 72 source archive")
    project = receipt["project"]
    require(re.fullmatch(r"[a-z0-9][a-z0-9_.-]*", project), "invalid deployment Compose project")
    expected = receipt["candidate_images"]
    READY["check_fields"](expected, set(CANDIDATE_SERVICES), "accepted Build 72 running images")
    for image in expected.values():
        READY["check_hex"](image, 64, "accepted Build 72 running image", prefix="sha256:")
    redis = verify_retained_redis(project, receipt["retained_redis"], receipt["redis_receipt_sha256"])
    verify_live_schema(project, expected["postgres"])
    return project, expected, redis, digest


def verify_backend_connections(container: dict, compose: dict) -> None:
    live = {}
    for item in container["Config"]["Env"]:
        key, separator, value = item.partition("=")
        require(separator and key not in live, "running backend has an ambiguous environment")
        live[key] = value
    candidate = compose["services"]["backend"]["environment"]
    require(all(live[key] and candidate[key] == live[key] for key in ("DATABASE_URL", "REDIS_URL", "JWT_SECRET")),
            "rendered candidate database, Redis or authentication connection differs from the running backend")


def query(postgres: str, statement: str, *psql_flags: str) -> dict:
    return json.loads(docker("exec", "-i", postgres, "psql", "-XqAt", "-v", "ON_ERROR_STOP=1", "-U", "erp",
                             "-d", "erp", *psql_flags, sql=statement))


def verify_postgres_crypto_runtime(postgres: str) -> None:
    observed = query(postgres, """
BEGIN READ ONLY;
SELECT jsonb_build_object(
  'settings', (SELECT jsonb_object_agg(name, setting) FROM pg_settings
    WHERE name IN ('ssl', 'dynamic_library_path', 'local_preload_libraries',
                   'shared_preload_libraries', 'session_preload_libraries')),
  'extensions', (SELECT jsonb_agg(jsonb_build_object('name', extname, 'version', extversion) ORDER BY extname)
    FROM pg_extension),
  'C_libraries', (SELECT jsonb_agg(probin ORDER BY probin) FROM
    (SELECT DISTINCT probin FROM pg_proc p JOIN pg_language l ON l.oid = p.prolang
     WHERE l.lanname = 'c') native),
  'role_database_library_overrides', (SELECT jsonb_agg(jsonb_build_object(
      'database', setdatabase, 'role', setrole, 'settings', setconfig) ORDER BY setdatabase, setrole)
    FROM pg_db_role_setting WHERE EXISTS (SELECT 1 FROM unnest(setconfig) setting
      WHERE split_part(setting, '=', 1) IN ('dynamic_library_path', 'local_preload_libraries',
                                          'shared_preload_libraries', 'session_preload_libraries'))));
COMMIT;
""")
    assessment = run_path(str(SCRIPTS / "service-openssl-assessments.py"))
    assessment["verify_postgres_database_state"](observed)


def tablet(postgres: str, installation_id: str) -> dict:
    return query(postgres, (SCRIPTS / "verify-build77-tablet-close.sql").read_text(),
                 "-v", f"selected={installation_id}")


def database_identity(postgres: str) -> dict:
    return query(postgres, """
SELECT jsonb_build_object(
    'system_identifier', (SELECT system_identifier::text FROM pg_control_system()),
    'database_oid', (SELECT oid::bigint FROM pg_database WHERE datname = current_database()),
    'database_name', current_database());
""")


def sealed_storage(args) -> dict:
    observed, checksum = read_root_document(args.storage_baseline)
    require(checksum == args.storage_baseline_sha256, "sealed predecessor storage evidence changed")
    return observed["storage"]


def verify_storage(args) -> None:
    expected = sealed_storage(args)
    component = "caddy" if args.phase == "caddy-before-start" else "postgres"
    row = service(args.project, component)
    require(row["Id"] == args.container_id and row["Image"] == args.image_id,
            "service container or image identity changed before a storage operation")
    if component == "caddy":
        require(row["State"]["Status"] == "created" and caddy_storage(
            row, args.apk_root, args.caddy_config, exact_path=True,
        ) == expected["caddy"], "Caddy certificate or configuration mounts changed")
    else:
        require(postgres_mount(row) == expected["postgres_mount"], "PostgreSQL data mount differs from the predecessor")
        if args.phase == "postgres-before-start":
            require(row["State"]["Status"] == "created", "PostgreSQL has already started on the new configuration")
        else:
            require(process_is_running(row) and database_identity(row["Id"]) == expected["database_identity"],
                    "restore prohibited on a different PostgreSQL data cluster or database")


def observe(args, record: dict) -> dict:
    require(re.fullmatch(r"[a-z0-9][a-z0-9_.-]*", args.project), "invalid production Compose project")
    reviewed = checked_document(record["reviewed_runtime"], record["reviewed_runtime_sha256"])
    redis, retained = redis_observation(service(args.project, "redis"))
    redis_records, receipt_hashes = receipts()
    if args.baseline is not None:
        expected, baseline_sha = read_root_document(args.baseline)
        require(baseline_sha == args.baseline_sha256 and retained == expected,
                "running Redis or its sealed start identity changed during upgrade")
    if args.phase == "redis-only":
        require(args.baseline is not None and receipt_hashes == reviewed["redis_receipt_sha256"],
                "reviewed Redis repair receipts changed")
        READY["verify_redis"](redis, reviewed["image_ids"]["redis"], reviewed["redis_acl_sha256"], *redis_records)
        return {"retained_redis": True}

    require(args.source_revision == READY["BUILD71"] and args.source_archive_sha256 is not None,
            "installed source is not the Build 71 predecessor")
    rows = {name: service(args.project, name) for name in READY["SERVICES"] - {"redis"}}
    for name, row in rows.items():
        if name == "postgres" or args.phase == "running":
            require(process_is_running(row), "an expected prior production service is stopped")
        else:
            require(row["State"]["Status"] in {"created", "exited"} and row["State"]["Running"] is False,
                    "a production application writer is still running")
    postgres = rows["postgres"]["Id"]
    require(postgres == args.postgres, "PostgreSQL identity changed from the installer snapshot")
    verify_postgres_crypto_runtime(postgres)
    storage = {
        "postgres_mount": postgres_mount(rows["postgres"]), "database_identity": database_identity(postgres),
        "caddy": caddy_storage(rows["caddy"], args.apk_root, args.caddy_config, exact_path=False),
    }
    if args.phase == "stopped":
        require(storage == sealed_storage(args), "PostgreSQL or Caddy storage changed during writer shutdown")
    tablet_before = tablet(postgres, record["tablet_installation_id"])
    business = query(postgres, (SCRIPTS / "verify-production-business-quiescence.sql").read_text())
    fleet = query(postgres, (SCRIPTS / "verify-build45-pending-state.sql").read_text())
    tablet_after = tablet(postgres, record["tablet_installation_id"])
    READY["verify_tablet_capture_pair"](tablet_before, tablet_after)
    sampled_at = datetime.now(UTC)
    current = {
        "schema_version": 1, "captured_at": sampled_at.isoformat(timespec="microseconds").replace("+00:00", "Z"),
        "source_revision": args.source_revision, "candidate_revision": args.revision,
        "source_archive_sha256": args.source_archive_sha256,
        "image_ids": {**{name: row["Image"] for name, row in rows.items()}, "redis": redis["image_id"]},
        "business_quiescence": business, "fleet": fleet,
        "current_tablet": tablet_after, "redis": redis,
    }
    prior = checked_document(record["reviewed_prior_fleet"], record["reviewed_prior_fleet_sha256"])
    READY["verify_evidence"](current, prior, redis_records, reviewed, receipt_hashes,
                             candidate_revision=args.revision, installation_id=record["tablet_installation_id"],
                             now=datetime.now(UTC))
    return {"current": current, "retained": retained, "storage": storage}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    storage_phases = ("postgres-before-start", "postgres-before-restore", "caddy-before-start")
    parser.add_argument("phase", choices=("inputs", "connections", "running", "stopped", "redis-only", "deployment-receipt", *storage_phases))
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--manifest-sha256")
    parser.add_argument("--revision", required=True)
    parser.add_argument("--candidate-source-archive-sha256", required=True)
    parser.add_argument("--baseline", type=Path)
    parser.add_argument("--baseline-sha256")
    parser.add_argument("--project", default="")
    parser.add_argument("--postgres")
    parser.add_argument("--source-revision")
    parser.add_argument("--source-archive-sha256")
    parser.add_argument("--storage-baseline", type=Path)
    parser.add_argument("--storage-baseline-sha256")
    parser.add_argument("--container-id")
    parser.add_argument("--image-id")
    parser.add_argument("--apk-root", type=Path)
    parser.add_argument("--caddy-config", type=Path)
    parser.add_argument("--repo-root", type=Path)
    args = parser.parse_args()
    try:
        record, digest = inputs(args.manifest, args.revision, args.manifest_sha256)
        require(args.candidate_source_archive_sha256 == record["candidate_source_archive_sha256"],
                "frozen candidate source archive differs from the reviewed source")
        if args.phase == "inputs":
            print(digest)
            print(record["owner_credential_file"])
            return
        require(args.manifest_sha256 is not None, "reviewed manifest digest is required")
        if args.phase == "deployment-receipt":
            write_deployment_receipt(args, checked_document(record["reviewed_runtime"], record["reviewed_runtime_sha256"]))
            return
        if args.phase == "connections":
            require(re.fullmatch(r"[a-z0-9][a-z0-9_.-]*", args.project), "invalid production Compose project")
            verify_backend_connections(service(args.project, "backend"), json.load(sys.stdin))
            return
        if args.phase in storage_phases:
            verify_storage(args)
            return
        print(json.dumps(observe(args, record), sort_keys=True))
    except (OSError, KeyError, TypeError, ValueError, subprocess.TimeoutExpired) as error:
        reason = str(error) if type(error) is ValueError else "malformed, unavailable or ambiguous evidence"
        parser.exit(1, f"Build 72 upgrade runtime refused: {reason}.\n")


if __name__ == "__main__":
    main()
