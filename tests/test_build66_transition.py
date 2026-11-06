"""Build66 may add custody history without changing the quiesced legacy data."""

from copy import deepcopy
import importlib.util
from pathlib import Path
import subprocess

import pytest

ROOT = Path(__file__).resolve().parents[1]


@pytest.fixture
def verifier():
    spec = importlib.util.spec_from_file_location(
        "build66_transition", ROOT / "infra/scripts/verify-build66-transition.py"
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.fixture
def reports(verifier, monkeypatch):
    def stamp_catalogue(report, prefix, rows):
        report[f"{prefix}_catalog_rows"] = rows
        report[f"{prefix}_catalog_row_count"] = len(rows)
        report[f"{prefix}_catalog_sha256"] = verifier.catalogue_digest(rows)

    before = {
        "kind": "build55-full-transition-v2",
        "schema_version": 2,
        "database_revision": "0094",
        "database_identity": {"database_name": "erp", "database_oid": 7, "system_identifier": "1"},
        "gaming_sessions_legacy_projection": {"row_count": 0},
        "tables": {
            "alembic_version": {"row_count": 1, "row_sha256": "a" * 64},
            "client_installations": {"row_count": 1, "row_sha256": "b" * 64},
            "shifts": {"row_count": 1, "row_sha256": "c" * 64},
            "payments": {"row_count": 1, "row_sha256": "d" * 64},
        },
        "sequences": {"number_seq": {"last_value": 4, "is_called": True}},
    }
    after = deepcopy(before)
    after["database_revision"] = "0095"
    after["tables"]["alembic_version"]["row_sha256"] = "1" * 64
    after["tables"]["shifts"]["row_sha256"] = "2" * 64
    after["tables"]["shift_operator_handovers"] = {
        "row_count": 0, "row_sha256": verifier.EMPTY_SHA256
    }
    additions = {}
    for prefix in verifier.CATALOGUE_ADDITIONS:
        added = f"{prefix}|0095"
        stamp_catalogue(before, prefix, [f"{prefix}|0094"])
        stamp_catalogue(after, prefix, [f"{prefix}|0094", added])
        additions[prefix] = (1, verifier.catalogue_digest([added]))
    monkeypatch.setattr(verifier, "CATALOGUE_ADDITIONS", additions)
    pre_shift, post_shift = (
        {
            "database_revision": report["database_revision"],
            "database_identity": deepcopy(report["database_identity"]),
            "custody_columns": count,
            "nonnull_count": 0,
            **before["tables"]["shifts"],
        }
        for report, count in ((before, 0), (after, 2))
    )
    return before, pre_shift, after, post_shift


def test_only_expected_migration_changes_are_accepted(verifier, reports):
    verifier.verify_after(*reports)


@pytest.mark.parametrize(
    ("target", "path", "value", "reason"),
    [
        (2, ("tables", "client_installations", "row_sha256"), "3" * 64, "legacy table"),
        (2, ("tables", "payments", "row_sha256"), "4" * 64, "legacy table"),
        (2, ("tables", "shift_operator_handovers", "row_count"), 1, "not empty"),
        (2, ("sequences", "number_seq", "is_called"), False, "sequences"),
        (2, ("database_identity", "database_oid"), 9, "database_identity"),
        (2, ("database_revision",), "0096", "database revision"),
        (3, ("row_sha256",), "5" * 64, "legacy shift"),
        (3, ("nonnull_count",), 1, "schema or custody"),
        (3, ("custody_columns",), 1, "schema or custody"),
    ],
)
def test_rejects_unexpected_migration_change(verifier, reports, target, path, value, reason):
    obj = reports[target]
    for part in path[:-1]:
        obj = obj[part]
    obj[path[-1]] = value
    with pytest.raises(ValueError, match=reason):
        verifier.verify_after(*reports)


@pytest.mark.parametrize("prefix", ["schema", "trigger"])
def test_rejects_unexpected_catalogue_delta_even_if_report_is_self_consistent(verifier, reports, prefix):
    after = reports[2]
    rows = after[f"{prefix}_catalog_rows"]
    rows[0] = f"{prefix}|unexpected replacement"
    after[f"{prefix}_catalog_sha256"] = verifier.catalogue_digest(rows)
    with pytest.raises(ValueError, match=f"{prefix} catalogue delta"):
        verifier.verify_after(*reports)


def test_restore_allows_a_new_database_oid_but_checks_every_row(verifier, reports):
    before = reports[0]
    restored = deepcopy(before)
    restored["database_identity"].update(database_name="erp_disposable", database_oid=9)
    verifier.verify_restore(before, restored)
    restored["tables"]["client_installations"]["row_count"] = 2
    with pytest.raises(ValueError, match="restored backup differs"):
        verifier.verify_restore(before, restored)


def test_pin_all_five_images_without_changing_any_other_compose_field(verifier):
    attestation = {
        "version_name": "3.1.63",
        "services": {name: {"image_id": "sha256:" + "a" * 64} for name in verifier.IMAGE_SERVICES},
    }
    base = {"services": {name: {"image": f"{name}:candidate", "command": ["normal"]}
                         for name in verifier.IMAGE_SERVICES}}
    pinned = deepcopy(base)
    for name, override in verifier.image_override(attestation)["services"].items():
        pinned["services"][name].update(override)
    verifier.verify_compose(attestation, deepcopy(base), pinned)
    pinned["services"]["backend"]["command"] = ["different"]
    with pytest.raises(ValueError, match="pinned Compose"):
        verifier.verify_compose(attestation, base, pinned)
    for historical in ("3.1.59", "3.1.60", "3.1.61", "3.1.62"):
        with pytest.raises(ValueError, match="candidate version"):
            verifier.image_override({**attestation, "version_name": historical})
    del attestation["services"]["redis"]
    with pytest.raises(ValueError, match="service inventory"):
        verifier.image_override(attestation)


def test_installer_orders_quiescence_backup_migration_and_ingress():
    script = (ROOT / "infra/scripts/install-on-vm.sh").read_text()
    steps = (
        'echo "==> Exact candidate image SBOM/CVE gate passed:',
        'bash "$CAPACITY_CHECK_TOOL"',
        'wait_for_build66_owner_input "$BUILD66_ACCEPTANCE_CREDENTIAL_FILE"',
        'public --require-owner --domain',
        'MAINTENANCE_ACTIVE=true',
        'verify_production_business_quiescence "after writer stop"',
        '"$BUILD66_VERIFIER" pre "$BUILD66_PRE_STATE"',
        '"$BUILD66_VERIFIER" restore "$BUILD66_PRE_STATE" "$UPGRADE_SNAPSHOT/build66-backup-restore.json"',
        'echo "==> Candidate environment promoted atomically."',
        '"$BUILD66_VERIFIER" compose "$BUILD66_ATTESTATION"',
        'build66-rebound.json',
        'backend -m alembic upgrade 0095',
        '"$BUILD66_VERIFIER" post "$BUILD66_PRE_STATE"',
        'BUILD66_APP_WRITERS_STARTED=true',
        'up -d --no-build --pull never postgres redis backend frontend',
        'internal-probe --require-owner --container-id',
        'echo "==> Backend acceptance passed; reopening public ingress…"',
    )
    positions = [script.index(step) for step in steps]
    assert positions == sorted(positions)
    assert (
        'elif [ "$BUILD66_EXACT_TRANSITION" = true ]; then\n'
        '  wait_for_build66_owner_input "$BUILD66_ACCEPTANCE_CREDENTIAL_FILE"\n'
        '  (umask 077; python3 "$BUILD55_AUTH_TOOL" public --require-owner'
    ) in script
    failure_handler = script.split("handle_install_failure() {", 1)[1]
    writer_failure = failure_handler.split('[ "$BUILD66_APP_WRITERS_STARTED" = true ]; then', 1)[1]
    writer_failure = writer_failure.split("  elif ", 1)[0]
    assert "stop_build66_services caddy backend" in writer_failure
    assert "pg_restore" not in writer_failure


@pytest.mark.parametrize(
    ("scenario", "expected_sleeps", "expected_status"),
    [
        ("private-arrival", 2, 0),
        ("timeout", 300, 1),
        ("present-invalid", 0, 0),
        ("dangling-link", 0, 0),
    ],
)
def test_owner_input_wait_is_bounded_and_leaves_validation_to_the_checker(
    tmp_path, scenario, expected_sleeps, expected_status
):
    script = (ROOT / "infra/scripts/install-on-vm.sh").read_text()
    wait = script.split("wait_for_build66_owner_input() {", 1)[1].split("\n}\n", 1)[0]
    path = tmp_path / "private-owner-input.json"
    if scenario == "present-invalid":
        path.write_text("invalid")
    elif scenario == "dangling-link":
        path.symlink_to(tmp_path / "absent")
    body = r'''
set -eu
scenario=$2
path=$1
ticks=0
sleep() {
  test "$1" = 1
  ticks=$((ticks + 1))
  SECONDS=$((SECONDS + 1))
  if [[ "$scenario" == private-arrival && "$ticks" == 2 ]]; then
    (umask 077; printf '%s\n' 'fixture' > "$path")
  fi
}
status=0
wait_for_build66_owner_input "$1" || status=$?
printf 'sleeps=%s\n' "$ticks"
exit "$status"
'''
    result = subprocess.run(
        ["bash", "-c", "wait_for_build66_owner_input() {" + wait + "\n}\n" + body,
         "wait-test", str(path), scenario],
        capture_output=True, text=True, check=False, timeout=5,
    )
    assert result.returncode == expected_status, result.stderr
    assert f"sleeps={expected_sleeps}\n" in result.stdout
    assert str(path) not in result.stdout + result.stderr
    assert ("waiting for normal Owner acceptance input" in result.stdout) == bool(expected_sleeps)
    assert ("did not arrive before maintenance" in result.stderr) == bool(expected_status)
    if scenario == "private-arrival":
        assert path.read_text() == "fixture\n"
        assert path.stat().st_mode & 0o777 == 0o600
    elif scenario == "present-invalid":
        assert path.read_text() == "invalid"
    elif scenario == "dangling-link":
        assert path.is_symlink()


def set_schema(verifier, report, rows):
    report["schema_catalog_rows"] = rows
    report["schema_catalog_row_count"] = len(rows)
    report["schema_catalog_sha256"] = verifier.catalogue_digest(rows)


def test_only_reviewed_check_rendering_is_tolerated_for_restore(verifier, reports):
    before, pre_shift, after, post_shift = reports
    restore_helper = verifier.run_path(
        str(ROOT / "infra/scripts/verify-build55-pending-compatibility.py")
    )
    original, rerendered = restore_helper["RESTORE_CONSTRAINT_PAIRS"][0]

    set_schema(verifier, before, [original])
    restored = deepcopy(before)
    set_schema(verifier, restored, [rerendered])
    verifier.verify_restore(before, restored)

    set_schema(verifier, after, [rerendered, "schema|0095"])
    with pytest.raises(ValueError, match="schema catalogue delta"):
        verifier.verify_after(before, pre_shift, after, post_shift)

    set_schema(verifier, restored, [original + " unexpected"])
    with pytest.raises(ValueError, match="reviewed CHECK"):
        verifier.verify_restore(before, restored)


@pytest.mark.parametrize(
    ("table", "constraint", "first", "second"),
    [
        ("android_update_policy", "android_update_policy_mode_check", "optional", "required"),
        ("gaming_void_receipts", "ck_gaming_void_receipt_mode", "unlinked_addons", "linked_pos"),
    ],
)
def test_build66_accepts_exact_rehearsed_check_renderings_only_for_restore(
    verifier, reports, table, constraint, first, second,
):
    before, pre_shift, after, post_shift = reports
    prefix = f"constraint|{table}|{constraint}|CHECK (mode::text = ANY ("
    original = f"{prefix}ARRAY['{first}'::character varying, '{second}'::character varying]::text[]))"
    rerendered = f"{prefix}ARRAY['{first}'::character varying::text, '{second}'::character varying::text]))"
    set_schema(verifier, before, [original])
    restored = deepcopy(before)

    set_schema(verifier, restored, [rerendered])
    verifier.verify_restore(before, restored)

    for unreviewed_rows in (
        [rerendered.replace(f"'{second}'", "'unexpected'")],
        [original, rerendered],
    ):
        set_schema(verifier, restored, unreviewed_rows)
        with pytest.raises(ValueError, match="reviewed CHECK"):
            verifier.verify_restore(before, restored)

    set_schema(verifier, after, [original, "schema|0095"])
    verifier.verify_after(before, pre_shift, after, post_shift)
    set_schema(verifier, after, [rerendered, "schema|0095"])
    with pytest.raises(ValueError, match="schema catalogue delta"):
        verifier.verify_after(before, pre_shift, after, post_shift)


def test_runtime_override_reuses_admitted_no_seed_command_and_only_the_message(verifier):
    entrypoint = verifier.build65_runtime_support()["BACKEND_UVICORN_ENTRYPOINT"]
    config = {
        "Entrypoint": entrypoint,
        "Cmd": [],
        "User": "erp",
        "Env": ["CLIENT_UPDATE_MESSAGE=Retain \u20ac and $ literally", "JWT_SECRET=private"],
    }
    assert verifier.runtime_override(config) == {
        "services": {"backend": {
            "entrypoint": entrypoint,
            "command": [],
            "environment": {"CLIENT_UPDATE_MESSAGE": "Retain \u20ac and $$ literally"},
        }}
    }
    for change in ({"Entrypoint": ["/entrypoint.sh"]}, {"Cmd": ["seed"]}, {"User": "root"}):
        with pytest.raises(ValueError, match="no-seed executable or user"):
            verifier.runtime_override({**config, **change})
    with pytest.raises(ValueError, match="update message"):
        verifier.runtime_override({**config, "Env": ["JWT_SECRET=private"]})
    with pytest.raises(ValueError, match="duplicate"):
        verifier.runtime_override({**config, "Env": config["Env"] + ["CLIENT_UPDATE_MESSAGE=other"]})


def test_prior_and_candidate_compose_reuse_no_seed_runtime_before_ingress():
    script = (ROOT / "infra/scripts/install-on-vm.sh").read_text()
    assert 'candidate_compose+=(-f "$BUILD66_RUNTIME_OVERRIDE")' in script
    assert 'prior_compose+=(-f "$BUILD66_RUNTIME_OVERRIDE" -f "$BUILD66_PRIOR_IMAGE_OVERRIDE")' in script
    assert 'sha256sum build66-runtime.override.json build66-prior-images.override.json' in script
    assert script.index('cmp -s "$BUILD66_RUNTIME_OVERRIDE" "$UPGRADE_SNAPSHOT/build66-running-runtime.json"') < (
        script.index('echo "==> Backend acceptance passed; reopening public ingress…"')
    )
    assert script.index('cmp -s "$BUILD66_RUNTIME_OVERRIDE" "$UPGRADE_SNAPSHOT/build66-rollback-runtime.json"') < (
        script.index('--force-recreate caddy || rollback_succeeded=false')
    )


def run_rollback_counterfactual(tmp_path, body, *, fault, writer_started=False):
    script = (ROOT / "infra/scripts/install-on-vm.sh").read_text()
    stop_helpers = script.split("build66_services_are_stopped() {", 1)[1]
    stop_helpers = "build66_services_are_stopped() {" + stop_helpers.split(
        "verify_build66_baseline_seal() {", 1
    )[0]
    failure_handler = script.split("handle_install_failure() {", 1)[1]
    failure_handler = "handle_install_failure() {" + failure_handler.split(
        "handle_post_ingress_failure() {", 1
    )[0]
    (tmp_path / "database.dump").write_bytes(b"synthetic archive")
    harness = r'''
set -u
fault=$1
BUILD66_APP_WRITERS_STARTED=$2
ENV_CANDIDATE= VERIFY_DATABASE= EXISTING_POSTGRES_CONTAINER=
BUILD55_EXACT_TRANSITION=false BUILD66_EXACT_TRANSITION=true PROMOTION_COMPLETE=true
CODE30_2_CLEANUP_WINDOW_ACTIVE=false CODE30_2_CLEANUP_SUCCEEDED=false
UPGRADE_SNAPSHOT=$PWD DEPLOY_PROJECT_NAME=synthetic ROLLBACK_STATE_MANIFEST_DIGEST=
candidate_compose=(compose_stub)
compose_stub() {
  echo "stop" >> calls
  [ "$fault" != stop ]
}
verify_build66_baseline_seal() { return 0; }
docker() {
  case "$1" in
    ps)
      if [[ " $* " == *com.docker.compose.service=caddy* ]]; then
        printf '%064d\n' 1
      fi
      ;;
    inspect)
      if [ "$fault" = running ]; then
        echo 'synthetic|caddy|running'
      elif [ "$fault" = foreign ]; then
        echo 'other|caddy|exited'
      else
        echo 'synthetic|caddy|exited'
      fi
      ;;
    exec)
      if [ "$2" = -i ]; then
        echo "pg_restore" >> calls
        [[ " $* " == *" --single-transaction --exit-on-error --no-owner "* ]] || return 99
        [ "$fault" != pg_restore ]
      else
        echo "$3" >> calls
        [ "$fault" != "$3" ]
      fi
      ;;
    *) return 99 ;;
  esac
}
'''
    result = subprocess.run(
        ["bash", "-c", harness + stop_helpers + failure_handler + body,
         "test-build66", fault, str(writer_started).lower()],
        cwd=tmp_path, capture_output=True, text=True, check=False,
    )
    calls = tmp_path / "calls"
    return result, calls.read_text().splitlines() if calls.exists() else []


@pytest.mark.parametrize("fault", ["stop", "running", "foreign"])
@pytest.mark.parametrize("writer_started", [False, True])
def test_failed_stop_or_readback_never_claims_containment_or_restores(tmp_path, fault, writer_started):
    result, calls = run_rollback_counterfactual(
        tmp_path, "\nfalse\nhandle_install_failure\n", fault=fault, writer_started=writer_started,
    )
    assert result.returncode == 1
    assert calls and set(calls) == {"stop"}
    assert "containment failed" in result.stderr.lower()
    assert "verified Caddy and backend stopped" not in result.stderr
    assert "ingress remains closed" not in result.stderr
    if writer_started:
        assert "retained for forward repair" in result.stderr
    else:
        assert "restore was refused" in result.stderr


@pytest.mark.parametrize(
    ("fault", "calls"),
    [
        ("running", []),
        ("psql", ["psql"]),
        ("dropdb", ["psql", "dropdb"]),
        ("createdb", ["psql", "dropdb", "createdb"]),
        ("pg_restore", ["psql", "dropdb", "createdb", "pg_restore"]),
        ("none", ["psql", "dropdb", "createdb", "pg_restore"]),
    ],
)
def test_build66_restore_stops_at_first_failed_stage(tmp_path, fault, calls):
    result, actual = run_rollback_counterfactual(
        tmp_path, '\nrestore_build66_database pg "$UPGRADE_SNAPSHOT/database.dump"\n', fault=fault,
    )
    assert actual == calls
    assert (result.returncode == 0) is (fault == "none")
