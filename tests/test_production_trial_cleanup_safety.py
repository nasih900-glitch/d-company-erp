from __future__ import annotations

import re
import subprocess
from hashlib import sha256
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RUNNER = ROOT / "infra" / "scripts" / "cleanup-code30-production-trial-data.sh"
SQL = ROOT / "infra" / "scripts" / "cleanup-code30-production-trial-data.sql"
QUARANTINE_EVIDENCE = (
    ROOT / "releases" / "evidence" / "code30-2-emulator-quarantine.json"
)
QUARANTINE_EVIDENCE_SHA256 = (
    "379c6368936d03223e19482cc840c2a9d2483dc9a96909fba22cd9f59911eec8"
)


def _source(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_cleanup_runner_is_dry_run_by_default_and_apply_is_explicit() -> None:
    source = _source(RUNNER)

    assert "apply=false" in source
    assert "APPLY_CODE30_1_VERIFIED_TRIAL_CLEANUP" in source
    assert "--expected-state-fingerprint" in source
    assert "--backup-file" in source
    assert "--backup-sha256" not in source
    assert "--backend-container" in source
    assert "--quarantine-archive-sha256" not in source
    assert "--quarantine-evidence-sha256" not in source
    assert "--source-git-sha" in source
    assert "--executor" in source
    assert 'backup_sha256=$(hash_file "$backup_file")' in source
    assert QUARANTINE_EVIDENCE_SHA256 in source
    assert "releases/evidence/code30-2-emulator-quarantine.json" in source
    assert (
        'quarantine_evidence_sha256=$(hash_file "$QUARANTINE_EVIDENCE_FILE")' in source
    )
    assert "canonical quarantine evidence does not match the reviewed SHA-256" in source
    assert (
        sha256(QUARANTINE_EVIDENCE.read_bytes()).hexdigest()
        == QUARANTINE_EVIDENCE_SHA256
    )
    assert "docker exec -i" in source
    assert "--set=ON_ERROR_STOP=1" in source
    assert "pg_restore --exit-on-error --no-owner --no-privileges" in source
    assert "createdb --username" in source
    assert "dropdb --username" in source
    assert "trap cleanup_restore EXIT" in source
    createdb = source.index('cleanup-code30 "$restore_db_candidate"')
    trap_armed = source.index("restore_db=$restore_db_candidate")
    assert createdb < trap_armed
    assert "restored backup is not at exact database migration 0078" in source
    assert "restored_fingerprint" in source
    assert "restored backup fingerprint does not match" in source
    assert "{{.Image}}" in source
    assert "org.opencontainers.image.revision" in source
    assert "backend image revision does not match --source-git-sha" in source
    assert "backend container must be stopped before cleanup" in source
    assert "com.docker.compose.project" in source
    assert "com.docker.compose.service" in source
    assert 'postgres_compose_service" == postgres' in source
    assert 'backend_compose_service" == backend' in source
    assert (
        "backend and PostgreSQL containers are not from the same Compose project"
        in source
    )
    assert "docker ps" in source
    assert (
        "a backend service container from the production Compose project is still running"
        in source
    )
    assert "rev-parse --show-toplevel" in source
    assert "status --porcelain=v1 --untracked-files=all" in source
    assert "cleanup checkout HEAD does not match --source-git-sha" in source
    assert "cleanup checkout must be completely clean before apply" in source
    assert "hash-object" in source
    assert "ls-files --error-unmatch" in source

    subprocess.run(["bash", "-n", str(RUNNER)], check=True)


def test_create_failure_never_drops_an_unowned_restore_database(tmp_path: Path) -> None:
    repo = tmp_path / "repo"
    scripts = repo / "infra" / "scripts"
    evidence_dir = repo / "releases" / "evidence"
    fake_bin = tmp_path / "bin"
    scripts.mkdir(parents=True)
    evidence_dir.mkdir(parents=True)
    fake_bin.mkdir()
    runner = scripts / RUNNER.name
    runner.write_bytes(RUNNER.read_bytes())
    (scripts / SQL.name).write_bytes(SQL.read_bytes())
    (evidence_dir / QUARANTINE_EVIDENCE.name).write_bytes(
        QUARANTINE_EVIDENCE.read_bytes()
    )

    source_sha = "c" * 40
    image_id = f"sha256:{'d' * 64}"
    docker_log = tmp_path / "docker.log"
    fake_docker = fake_bin / "docker"
    fake_docker.write_text(
        f"""#!/usr/bin/env bash
set -eu
printf '%s\\n' "$*" >> "$FAKE_DOCKER_LOG"
args="$*"
if [[ "$args" == *"createdb --username"* ]]; then
  printf 'simulated existing database name\\n' >&2
  exit 1
fi
if [[ "$args" == *"dropdb --username"* ]]; then
  exit 91
fi
if [[ "$1" == inspect && "$args" == *"{{{{.State.Running}}}}"* ]]; then
  [[ "${{@: -1}}" == postgres ]] && printf 'true\\n' || printf 'false\\n'
  exit 0
fi
if [[ "$1" == inspect && "$args" == *"com.docker.compose.project"* ]]; then
  printf 'd-company-erp\\n'
  exit 0
fi
if [[ "$1" == inspect && "$args" == *"com.docker.compose.service"* ]]; then
  [[ "${{@: -1}}" == postgres ]] && printf 'postgres\\n' || printf 'backend\\n'
  exit 0
fi
if [[ "$1" == inspect && "$args" == *"{{{{.Image}}}}"* ]]; then
  printf '{image_id}\\n'
  exit 0
fi
if [[ "$1" == image && "$2" == inspect ]]; then
  printf '{source_sha}\\n'
  exit 0
fi
if [[ "$1" == ps ]]; then
  exit 0
fi
if [[ "$1" == exec && "$args" == *"POSTGRES_DB"* ]]; then
  printf 'erp'
  exit 0
fi
if [[ "$1" == exec && "$args" == *"pg_restore --list"* ]]; then
  cat >/dev/null
  exit 0
fi
exit 0
""",
        encoding="utf-8",
    )
    fake_docker.chmod(0o755)

    fake_git = fake_bin / "git"
    fake_git.write_text(
        f"""#!/usr/bin/env bash
set -eu
args="$*"
if [[ "$args" == *"rev-parse --show-toplevel"* ]]; then
  printf '%s\\n' '{repo}'
elif [[ "$args" == *"rev-parse HEAD:"* || "$args" == *"hash-object"* ]]; then
  printf 'same-blob-id\\n'
elif [[ "$args" == *"rev-parse HEAD"* ]]; then
  printf '%s\\n' '{source_sha}'
elif [[ "$args" == *"status --porcelain"* ]]; then
  :
elif [[ "$args" == *"ls-files --error-unmatch"* ]]; then
  :
else
  printf 'unexpected fake git call: %s\\n' "$args" >&2
  exit 88
fi
""",
        encoding="utf-8",
    )
    fake_git.chmod(0o755)

    backup = tmp_path / "fresh.dump"
    backup.write_bytes(b"mock custom-format archive")
    env = {
        "PATH": f"{fake_bin}:{Path('/usr/bin')}:{Path('/bin')}",
        "FAKE_DOCKER_LOG": str(docker_log),
    }
    completed = subprocess.run(
        [
            "bash",
            str(runner),
            "--postgres-container",
            "postgres",
            "--backend-container",
            "backend",
            "--apply",
            "--confirm",
            "APPLY_CODE30_1_VERIFIED_TRIAL_CLEANUP",
            "--expected-state-fingerprint",
            "b" * 64,
            "--backup-file",
            str(backup),
            "--source-git-sha",
            source_sha,
            "--executor",
            "pytest",
        ],
        env=env,
        capture_output=True,
        text=True,
        check=False,
    )

    assert completed.returncode != 0
    calls = docker_log.read_text(encoding="utf-8")
    assert "createdb --username" in calls
    assert "dropdb --username" not in calls


def test_cleanup_sql_freezes_the_database_and_never_disables_integrity() -> None:
    source = _source(SQL)

    assert "BEGIN ISOLATION LEVEL SERIALIZABLE" in source
    assert "pg_try_advisory_xact_lock" in source
    assert "IN ACCESS EXCLUSIVE MODE NOWAIT" in source
    assert "expected exact database migration 0078" in source
    assert "cleanup dependency graph changed" in source
    assert "a retained business/system row changed during cleanup" in source
    assert "an unrelated production table changed during cleanup" in source
    assert "DISABLE TRIGGER" not in source.upper()
    assert "session_replication_role" not in source
    assert (
        re.search(r"\bDROP\s+(TABLE|TRIGGER|FUNCTION|SCHEMA)\b", source, re.IGNORECASE)
        is None
    )
    assert "nextval(" not in source.lower()

    audit_insert = source.index("INSERT INTO audit_log")
    apply_conditional = source.rindex("\\if :cleanup_apply", 0, audit_insert)
    apply_end = source.index("\\endif", audit_insert)
    assert apply_conditional < audit_insert < apply_end
    assert (
        "             NULL,\n             NULL,\n             NULL,\n             'Owner-authorized"
        in source
    )

    rollback = source.rindex("ROLLBACK;")
    commit = source.rindex("COMMIT;")
    conditional = source.rindex("\\if :cleanup_apply")
    assert conditional < commit < rollback


def test_cleanup_uses_only_the_reviewed_primary_key_allowlists() -> None:
    source = _source(SQL)

    expected_counts = {
        "shifts": 3,
        "orders": 3,
        "order_lines": 3,
        "gaming_sessions": 5,
        "menu_items": 1,
        "client_installations": 1,
        "remote_assistance_device_keys": 29,
        "idempotency_keys": 10,
        "audit_log": 37,
    }
    sections = {
        "shifts": (
            "d2337cb0-9b65-4c18-9baa-29b15fd163b6",
            "208335fe-f892-478b-b575-ee35498b4648",
            "4b53348d-3a1b-4e56-b97c-cdc1bc7b3e58",
        ),
        "orders": (
            "b293d66f-0469-47a8-82ee-887a864796c1",
            "089e8a8d-1351-4b2a-b6ce-d3e0fd402f81",
            "8fcd1dbd-da6d-4ff2-bfa2-bc6db95fd3c2",
        ),
        "order_lines": (
            "3b4a620f-bd83-43ab-9394-97ed38f2e6ad",
            "c3789167-7d0a-47f1-b026-7b8681e7dd4f",
            "88285974-aa81-48e2-a4e7-ac6f523d6c58",
        ),
        "gaming_sessions": (
            "7bb8a1af-d497-4c70-943c-2d78ae2cad5a",
            "b0245be8-0511-4268-8cc3-be23c546d955",
            "f28a30b1-d128-42d7-9510-b5909d7b2615",
            "f0d735ab-d6b8-4958-83cb-f63968e052fd",
            "633be5cf-f204-4a54-9087-184b8ec76a44",
        ),
        "menu_items": ("2f968f7c-df0b-49fe-bedb-395da7329d28",),
        "client_installations": ("92b491f1-c35b-4437-af9a-a6be68035001",),
    }
    for table_name, ids in sections.items():
        assert len(ids) == expected_counts[table_name]
        for row_id in ids:
            assert source.count(row_id) >= 1

    uuid_targets = re.search(
        r"INSERT INTO _cleanup_uuid_targets \(target_table, id\) VALUES(?P<body>.*?);",
        source,
        flags=re.DOTALL,
    )
    assert uuid_targets is not None
    assert uuid_targets.group("body").count("('remote_assistance_device_keys',") == 29

    assert source.count("gaming-session-start:") == 5
    assert source.count("gaming-session-stop:") == 5
    audit_values = re.search(
        r"INSERT INTO _cleanup_audit_targets \(id\) VALUES(?P<body>.*?);",
        source,
        flags=re.DOTALL,
    )
    assert audit_values is not None
    assert len(re.findall(r"\(\d+\)", audit_values.group("body"))) == 37

    # Every destructive statement is keyed through one of the reviewed temp
    # allowlists. The retired test installation and its immutable security keys
    # are evidence targets, not deletion targets.
    deletes = re.findall(
        r"DELETE FROM (?P<table>[a-z_]+)(?P<body>.*?)RETURNING 1", source, re.DOTALL
    )
    assert [table for table, _ in deletes] == [
        "audit_log",
        "idempotency_keys",
        "gaming_sessions",
        "order_lines",
        "orders",
        "menu_items",
        "shifts",
    ]
    for _table, body in deletes:
        assert "_cleanup_" in body
        assert " WHERE " in body or body.lstrip().startswith("WHERE ")


def test_cleanup_pins_exact_full_and_target_fingerprints() -> None:
    source = _source(SQL)

    expected_hashes = {
        "e2164c700b9ffc67edc1e63623baf943a89b4dd9cd7392a87a12a054aba7cc82",
        "49c31a308d7980d4b30c0831c03f4e559d27990b175139aa8d3514d7affcc266",
        "72894ce31664b065b73bdcdb7711ffc59d6d76831783f2c837691fc09c44289f",
        "f91749ce9c33b2b09c42f566581341fe25d43e80f52925d69dabf18245198743",
        "8617ab23715f3ddb053bbf8dfd97024d046477d0b26ff3377fc9020efeefe0d5",
        "9953dc55eddd7aae7f99160f5ee9bf8fc32c53aaea26fd5d92f737ac8b4ad26a",
        "383bc2351b2396ee9006474d59738e06af3cd7c5ad7949e611a01d58d5c0c402",
        "4c36b350e4f76ff53e867f98fefaf936df81676eb86f41a7cc9102a7fb169aed",
        "93c060904692477a228862f431fa0c992ebc25e3fef8f5af2f2a732fb2b64b15",
        "0e445d22547b647b02057f67290ac2fafbdae962a7656e5e26bbf23d93aaf45e",
        "53a1ec3a79f7057bc71c1cf12f864df7efb96912e9c75ccdcdae171d48003652",
        "c612890089bfbc6ed45012fe098a59e9e0c9a57381f921a3e5509976dd68e2b4",
        "7c4126a1d946ab6a098f1b92cd5b77db611a72d197afc35d8d20ce52b3f7118f",
        "e8b3123348e4622ff592ecccec3560310f4c2fa732dd9263112ee209e55c9403",
        "ef7cb3f79870b68da91cf0249c0105f1ac1c8451e539ab9c20df68b8db8473e4",
        "530ff69bd8e040457755bc0268216c8347442ebae858d5ce1152fb1fbfc65a71",
        "153459629d8c52fbd2c6b7ae40e80530b52a2da69108fd823e607c1478bffb78",
    }
    for value in expected_hashes:
        assert source.count(value) == 1
    assert (
        source.count("301905b96650f3f06fc6b3378a1450cc000159a9909f33af3b951a665eca2696")
        == 4
    )

    assert "audited full-table snapshot changed" in source
    assert "audited cleanup target snapshot changed" in source
    assert "state fingerprint mismatch" in source
    full_tables = re.search(
        r"'full_tables', \(\s*SELECT.*?FROM (?P<table>_cleanup_[a-z_]+)",
        source,
        flags=re.DOTALL,
    )
    assert full_tables is not None
    assert full_tables.group("table") == "_cleanup_all_pre"


def test_cleanup_receipt_and_postconditions_are_exact() -> None:
    source = _source(SQL)

    assert "'production_trial_cleanup'" in source
    assert "'ReleaseCleanup'" in source
    assert "'code30.1-20260920'" in source
    assert "'7016c42c-11c9-48fa-a30a-5d66a8c970b7'::uuid" in source
    assert "'8f323fba-4358-45fe-9d3b-a8e0fae52993'::uuid" in source
    assert "'789353a8-09e4-4ef2-9fa8-ac73c426bfc8'::uuid" in source
    assert "'backup_sha256'" in source
    assert "'quarantine_evidence_sha256'" in source
    assert "'source_git_sha'" in source
    assert "'backend_image_id'" in source
    assert "'executor'" in source
    assert "'replay_fence'" in source
    assert "'request_hash'" in source
    assert "'user_id'" in source
    assert "'terminal_id'" in source
    assert "(SELECT count(*) FROM shifts) <> 7" in source
    assert "(SELECT count(*) FROM orders) <> 4" in source
    assert "(SELECT count(*) FROM gaming_sessions) <> 4" in source
    assert "(SELECT count(*) FROM client_installations) <> 7" in source
    assert "(SELECT count(*) FROM remote_assistance_device_keys) <> 379" in source
    assert "(SELECT count(*) FROM payments) <> 3" in source
    assert "(SELECT count(*) FROM refunds) <> 0" in source
    assert "(SELECT count(*) FROM customers) <> 0" in source
    assert "(SELECT count(*) FROM idempotency_keys) <> 37" in source
    assert (
        "expected_audit_count := CASE WHEN apply_mode THEN 1235 ELSE 1234 END" in source
    )
    assert "UPDATE client_installations" not in source
    assert "SET pending_outbox_count" not in source
    assert "DELETE FROM client_installations" not in source
    assert "DELETE FROM remote_assistance_device_keys" not in source
    assert "fk_remote_assistance_device_keys_scoped_installation', 29" not in source
    assert "retained_remote_assistance_device_key_count', 29" in source
    assert "retained_remote_assistance_device_keys_sha256" in source
    assert "client_was_offline', NULL" in source
    assert "synced_at', NULL" in source
    assert "receipt.client_was_offline IS NULL" in source
    assert "receipt.synced_at IS NULL" in source
    assert "to_jsonb(post) = pre.full_row" in source
    assert "post.pending_outbox_count = 1" in source
    assert "'pending_outbox_count', 1" in source
    assert "'pending_outbox_count_unchanged', true" in source
    assert "'maintenance_mutated_installation', false" in source
    assert "'pending_tablet_outbox', 1" in source
    assert "'updated_counts', '{}'::jsonb" in source
    assert "retired_test_installation_security_evidence_unchanged" in source
    assert "direct_installation_identity_link_proven', false" in source


def test_cleanup_replay_fence_captures_every_deleted_action_identity() -> None:
    source = _source(SQL)

    assert "CREATE TEMP TABLE _cleanup_replay_fence" in source
    assert "(SELECT count(*) FROM _cleanup_replay_fence) <> 13" in source
    assert "opening_action_id, opening_request_hash, opened_by" in source
    assert "SELECT 'idempotency', key, request_hash, user_id, terminal_id" in source
    assert (
        "cleanup replay fence does not contain the exact 13 durable action identities"
        in source
    )

    fence_snapshot = source.index("INSERT INTO _cleanup_replay_fence")
    receipt_insert = source.index("INSERT INTO audit_log")
    first_delete = source.index("DELETE FROM audit_log")
    assert fence_snapshot < receipt_insert < first_delete
