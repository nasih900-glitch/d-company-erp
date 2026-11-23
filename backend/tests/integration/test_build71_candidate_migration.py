"""Populated immutable Build71 backup/restore and candidate migration rehearsal.

This is synthetic local PostgreSQL evidence, never production or fleet admission.
"""

from __future__ import annotations

import io
import json
import os
from collections import Counter
from collections.abc import Callable
from copy import deepcopy
from functools import partial
from pathlib import Path
from runpy import run_path
import shutil
import subprocess
import sys
import tarfile

import psycopg
from psycopg import sql
import pytest

from tests.integration.test_client_installation_capacity_migration import (
    _disposable_database,
    _run_alembic,
    _sync_url,
)


ROOT = Path(__file__).resolve().parents[3]
BACKEND = ROOT / "backend"
BUILD71 = "b9b19bd2462e0f417922849a455e12116e8207e2"
# Immutable source-only producers used to create historical test rows.
PAID_BILL_AMENDMENT_PRODUCER = "9f7431005a3fb9b95ddbcb2f3b83cb3838ac61e4"
PAID_BILL_DECISION_PRODUCER = "0d1b879e4f1c8de37add1e67e6b9f952cd264436"
NEW_LEDGERS = {
    "0096": {"gaming_retained_bill_corrections"},
    "0097": {"gaming_retained_bill_corrections", "pos_receipt_corrections"},
    "0098": {
        "gaming_retained_bill_corrections", "pos_receipt_corrections",
        "pos_receipt_stock_restorations",
    },
    "0099": {
        "gaming_retained_bill_corrections", "pos_receipt_corrections",
        "pos_receipt_stock_restorations", "shift_start_work_receipts",
        "shift_start_work_cancellations",
    },
    "0100": {
        "gaming_retained_bill_corrections", "pos_receipt_corrections",
        "pos_receipt_stock_restorations", "shift_start_work_receipts",
        "shift_start_work_cancellations", "paid_bill_amendment_intents",
    },
}
NEW_LEDGERS["0101"] = NEW_LEDGERS["0100"] | {"paid_bill_action_decisions"}
NEW_LEDGERS["0102"] = NEW_LEDGERS["0101"] | {"paid_bill_recovery_sessions"}


def _export_build71_backend(destination: Path, revision: str = BUILD71) -> Path:
    result = subprocess.run(
        ["git", "archive", "--format=tar", revision, "backend"],
        cwd=ROOT, capture_output=True, check=True,
    )
    destination.mkdir()
    with tarfile.open(fileobj=io.BytesIO(result.stdout)) as package:
        assert all(
            (member.name == "backend" and member.isdir()) or member.name.startswith("backend/")
            for member in package.getmembers()
        )
        package.extractall(destination, filter="data")
    return destination / "backend"


def _run_at(backend: Path, database_url: str, arguments: list[str]) -> subprocess.CompletedProcess:
    environment = {**os.environ, "DATABASE_URL": database_url, "ENV": "test"}
    for key in ("PYTHONPATH", "PYTHONHOME"):
        environment.pop(key, None)
    result = subprocess.run(
        [sys.executable, *arguments], cwd=backend, env=environment,
        capture_output=True, text=True, check=False, timeout=180,
    )
    assert result.returncode == 0, result.stdout + result.stderr
    return result


def _run_feature(database_url: str, file: str, selector: str, *, backend: Path = BACKEND):
    return _run_at(backend, database_url, ["-m", "pytest", file, "-k", selector, "-q", "--no-cov"])


def _client_command(database_url: str, command: str, *arguments: str) -> subprocess.CompletedProcess:
    binary = shutil.which(command)
    if binary is None:
        pytest.skip(f"PostgreSQL16 {command} is required for real backup/restore rehearsal")
    version = subprocess.run([binary, "--version"], capture_output=True, text=True, check=True)
    assert "(PostgreSQL) 16." in version.stdout, version.stdout
    return subprocess.run(
        [binary, *arguments, "--dbname", _sync_url(database_url)],
        capture_output=True, text=True, check=False, timeout=120,
    )


def _inventory(database_url: str, *, grn_backfill: bool = False) -> dict:
    result = _client_command(
        database_url, "psql", "-X", "--tuples-only", "--no-align",
        *(["--set", "build72_grn_observation=on"] if grn_backfill else []),
        "--file", str(ROOT / "infra/scripts/verify-build55-transition-state.sql"),
    )
    assert result.returncode == 0, result.stdout + result.stderr
    lines = [line for line in result.stdout.splitlines() if line.startswith("{")]
    assert len(lines) == 1, result.stdout
    return json.loads(lines[0])


def _columns(database_url: str) -> dict[str, tuple[str, ...]]:
    with psycopg.connect(_sync_url(database_url)) as connection:
        rows = connection.execute(
            "SELECT table_name,column_name FROM information_schema.columns "
            "WHERE table_schema='public' ORDER BY table_name,ordinal_position"
        ).fetchall()
    output: dict[str, list[str]] = {}
    for table, column in rows:
        output.setdefault(table, []).append(column)
    return {table: tuple(columns) for table, columns in output.items()}


def _legacy_projection(database_url: str, columns: dict[str, tuple[str, ...]]) -> dict:
    with psycopg.connect(_sync_url(database_url)) as connection:
        return {
            table: connection.execute(
                sql.SQL(
                    "SELECT count(*),encode(sha256(convert_to(coalesce(string_agg("
                    "to_jsonb(t)::text,chr(10) ORDER BY to_jsonb(t)::text),''),'UTF8')),'hex') "
                    "FROM (SELECT {} FROM public.{}) t"
                ).format(sql.SQL(",").join(map(sql.Identifier, names)), sql.Identifier(table))
            ).fetchone()
            for table, names in columns.items() if table != "alembic_version"
        }


def _work_evidence(database_url: str) -> dict:
    result = _client_command(
        database_url, "psql", "-X", "--tuples-only", "--no-align",
        "--file", str(ROOT / "infra/scripts/verify-production-business-quiescence.sql"),
    )
    assert result.returncode == 0, result.stdout + result.stderr
    lines = [line for line in result.stdout.splitlines() if line.startswith("{")]
    assert len(lines) == 1
    with psycopg.connect(_sync_url(database_url)) as connection:
        fleet = connection.execute(
            "SELECT count(*),coalesce(sum(pending_outbox_count),0),"
            "count(*) FILTER(WHERE pending_outbox_count>0),"
            "coalesce(jsonb_agg(to_jsonb(i) ORDER BY id),'[]') FROM client_installations i"
        ).fetchone()
    return {"business": json.loads(lines[0]), "fleet": fleet}


def _populate_build71(backend: Path, database_url: str, *, legacy_pending=False) -> None:
    _run_at(backend, database_url, ["-m", "alembic", "upgrade", "0095"])
    _run_feature(
        database_url, "tests/integration/test_gaming_session_addons.py",
        "unlinked_void_retains_consumed_addons_on_explicit_pos_shift or "
        "combined_payment_deducts_addon_stock_once_and_reconciles_finance",
        backend=backend,
    )
    _run_feature(
        database_url, "tests/integration/test_shared_terminal_attendance.py",
        "secondary_clock_preserves_cashier_shift_credentials_and_attributes_b", backend=backend,
    )
    with psycopg.connect(_sync_url(database_url)) as connection:
        company, user, terminal = connection.execute(
            "SELECT c.id,u.id,t.id FROM companies c JOIN users u ON u.company_id=c.id "
            "JOIN branches b ON b.company_id=c.id JOIN terminals t ON t.branch_id=b.id "
            "ORDER BY c.id,u.id,t.id LIMIT 1"
        ).fetchone()
        for pending in (0, 7):
            connection.execute(
                "INSERT INTO client_installations(id,company_id,installation_id,registered_by_user_id,"
                "last_user_id,terminal_id,platform,distribution_channel,version_name,version_code,"
                "pending_outbox_count,update_state,last_seen_at) "
                "VALUES(gen_random_uuid(),%s,gen_random_uuid(),%s,%s,%s,'android','direct',"
                "%s,%s,%s,'idle','2000-01-01T00:00:00Z')",
                (company, user, user, terminal, *(("3.1.46", 54) if legacy_pending and pending else ("3.1.64", 71)), pending),
            )
        sequences = connection.execute(
            "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace "
            "WHERE n.nspname='public' AND c.relkind='S' ORDER BY c.relname"
        ).fetchall()
        assert sequences, "baseline should include its populated audit sequence"
        for (sequence,) in sequences:
            connection.execute(sql.SQL("SELECT nextval({})").format(sql.Literal(sequence)))


def _restore_catalogue(rows: list[str]) -> list[str]:
    return run_path(str(ROOT / "infra/scripts/verify-build72-transition.py"))["restore_catalogue"](rows)


def _assert_retained(before: dict, after: dict, columns: dict, projection: dict, work: dict,
                     database_url: str, revision: str, *, grn_backfill: Callable[[dict, dict], None] | None = None):
    assert after["database_revision"] == revision
    if grn_backfill is None:
        assert after["sequences"] == before["sequences"]
        assert _legacy_projection(database_url, columns) == projection
    else:
        grn_backfill(before, after)
        assert _legacy_projection(database_url, {k: v for k, v in columns.items() if k != "audit_log"}) == {
            k: v for k, v in projection.items() if k != "audit_log"
        }
    assert _work_evidence(database_url) == work
    current_columns = _columns(database_url)
    # No 0096-0101 migration adds a column to a Build71 table. Keep this exact
    # assertion separate from row projection so an unreviewed NULL column fails.
    assert {table: current_columns[table] for table in columns} == columns
    baseline_column_catalogue = {
        row for row in before["schema_catalog_rows"] if row.startswith("column|")
    }
    retained_column_catalogue = {
        row for row in after["schema_catalog_rows"]
        if row.startswith("column|") and row.split("|", 2)[1] in columns
    }
    assert retained_column_catalogue == baseline_column_catalogue
    assert set(after["tables"]) == set(before["tables"]) | NEW_LEDGERS[revision]
    populated_ledgers = {"grn_action_decisions", "grn_completion_proofs"} if grn_backfill is not None else set()
    for table in NEW_LEDGERS[revision] - populated_ledgers:
        assert after["tables"][table] == {"row_count": 0, "row_sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"}


def test_restore_aliases_accept_only_the_observed_complete_pairs():
    pairs = json.loads((Path(__file__).parent / "fixtures" /
                        "build71_pg16_restore_catalogue_pairs.json").read_text())
    original = [pair[0] for pair in pairs]
    restored = [pair[1] for pair in pairs]
    assert _restore_catalogue(original) == _restore_catalogue(restored)
    for altered in (restored[:-1], [*restored, restored[0]], [restored[0] + " changed", *restored[1:]]):
        with pytest.raises(ValueError):
            _restore_catalogue(altered)


@pytest.mark.integration
def test_populated_immutable_build71_dump_restore_to_0101_and_empty_ledger_roundtrip(tmp_path):
    old_backend = _export_build71_backend(tmp_path / "build71")
    with _disposable_database("erp_b71_source") as source, _disposable_database("erp_b72_restore") as restored:
        _populate_build71(old_backend, source)
        before = _inventory(source)
        columns = _columns(source)
        projection = _legacy_projection(source, columns)
        work = _work_evidence(source)
        assert work["fleet"][:3] == (2, 7, 1)
        assert work["business"]["open_shifts"] > 0
        assert before["tables"]["payments"]["row_count"] > 0
        assert before["tables"]["stock_movements"]["row_count"] > 0
        assert before["tables"]["attendance"]["row_count"] > 0
        backup = tmp_path / "build71.dump"
        dumped = _client_command(source, "pg_dump", "--format=custom", "--no-owner", "--no-privileges", "--file", str(backup))
        assert dumped.returncode == 0, dumped.stderr
        recovered = _client_command(restored, "pg_restore", "--no-owner", "--no-privileges", "--single-transaction", "--exit-on-error", str(backup))
        assert recovered.returncode == 0, recovered.stderr
        actual = _inventory(restored)
        assert actual["database_revision"] == "0095"
        assert actual["tables"] == before["tables"]
        assert actual["sequences"] == before["sequences"]
        assert _work_evidence(restored) == work
        assert _restore_catalogue(actual["schema_catalog_rows"]) == _restore_catalogue(before["schema_catalog_rows"])
        assert actual["trigger_catalog_rows"] == before["trigger_catalog_rows"]
        restored_baseline = actual
        for revision in ("0096", "0097", "0098", "0099", "0100", "0101"):
            result = _run_alembic(restored, "upgrade", revision)
            assert result.returncode == 0, result.stdout + result.stderr
            _assert_retained(restored_baseline, _inventory(restored), columns, projection, work, restored, revision)
        final = _inventory(restored)
        downgraded = _run_alembic(restored, "downgrade", "0095")
        assert downgraded.returncode == 0, downgraded.stdout + downgraded.stderr
        empty = _inventory(restored)
        assert empty["tables"] == restored_baseline["tables"]
        assert empty["sequences"] == restored_baseline["sequences"]
        assert _restore_catalogue(empty["schema_catalog_rows"]) == _restore_catalogue(restored_baseline["schema_catalog_rows"])
        assert empty["trigger_catalog_rows"] == restored_baseline["trigger_catalog_rows"]
        assert _work_evidence(restored) == work
        reupgraded = _run_alembic(restored, "upgrade", "0101")
        assert reupgraded.returncode == 0, reupgraded.stdout + reupgraded.stderr
        repeated = _inventory(restored)
        for key in ("tables", "sequences", "schema_catalog_rows", "trigger_catalog_rows"):
            assert repeated[key] == final[key], key
        assert _work_evidence(restored) == work
        with psycopg.connect(_sync_url(restored)) as connection:
            attendance_before = connection.execute("SELECT count(*) FROM attendance").fetchone()[0]
        _run_feature(
            restored, "tests/integration/test_captured_shift_opening.py",
            "captured_shift_drives_offline_session_chain_and_durable_replay",
        )
        with psycopg.connect(_sync_url(restored)) as connection:
            assert connection.execute("SELECT count(*) FROM attendance").fetchone()[0] == attendance_before
            assert connection.execute("SELECT count(*) FROM shift_start_work_receipts").fetchone()[0] == 0
        _run_feature(
            restored, "tests/integration/test_shift_start_work.py",
            "existing_attendance_is_preserved_and_other_open_shift_is_not_adopted or "
            "atomic_start_and_replay_after_clockout_and_shift_close",
        )
        with psycopg.connect(_sync_url(restored)) as connection:
            assert connection.execute("SELECT count(*) FROM attendance").fetchone()[0] == attendance_before + 2
            assert connection.execute("SELECT count(*) FROM shift_start_work_receipts").fetchone()[0] == 2
        (tmp_path / "rehearsal-result.json").write_text(json.dumps({
            "source_baseline": BUILD71,
            "schema_steps": ["0095", "0096", "0097", "0098", "0099", "0100", "0101", "0095", "0101"],
            "baseline_public_tables": len(columns),
            "baseline_columns": sum(map(len, columns.values())),
            "preserved_sequences": len(before["sequences"]),
            "preserved_business_counters": work["business"],
            "preserved_installation_rows_pending_total_nonzero": list(work["fleet"][:3]),
            "legacy_captured_chain_created_no_attendance": True,
            "current_start_work_preservation_and_historic_replay": True,
            "production_admission": False,
        }, indent=2) + "\n")


@pytest.mark.integration
@pytest.mark.parametrize(("file", "selector", "target", "message"), [
    ("test_paid_bill_amendments.py", "intent_reserves_once_replays_without_financial_effect", "0099", "Paid bill intent evidence prevents downgrade"),
    ("test_pos_receipt_stock_corrections.py", "full_stock_restore_preserves_originals_and_replays_once", "0097", "cannot remove retained stock correction evidence"),
    ("test_shift_start_work.py", "atomic_start_and_replay_after_clockout_and_shift_close", "0098", "retain start-work evidence"),
    ("test_shift_start_work.py", "owner_cancellation_is_exact_durable_and_blocks_delayed_and_legacy_start", "0098", "retain start-work evidence"),
])
def test_committed_candidate_evidence_refuses_lossy_downgrade(tmp_path, file, selector, target, message):
    with _disposable_database("erp_b72_evidence") as database_url:
        revision = "0100" if target == "0099" else "0101"
        upgraded = _run_alembic(database_url, "upgrade", revision)
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        feature_backend = (_export_build71_backend(tmp_path / "v6", PAID_BILL_AMENDMENT_PRODUCER)
            if target == "0099" else BACKEND)
        _run_feature(database_url, "tests/integration/" + file, selector, backend=feature_backend)
        before = _inventory(database_url)
        if target == "0099":
            assert before["tables"]["paid_bill_amendment_intents"]["row_count"] == 1
        elif target == "0097":
            assert before["tables"]["pos_receipt_stock_restorations"]["row_count"] > 0
        elif selector.startswith("owner_cancellation"):
            assert before["tables"]["shift_start_work_receipts"]["row_count"] == 0
            assert before["tables"]["shift_start_work_cancellations"]["row_count"] == 1
        else:
            assert before["tables"]["shift_start_work_receipts"]["row_count"] == 1
        refused = _run_alembic(database_url, "downgrade", target)
        assert refused.returncode != 0
        assert message in refused.stdout + refused.stderr
        after = _inventory(database_url)
        assert after["database_revision"] == revision
        for key in ("tables", "sequences", "schema_catalog_rows", "trigger_catalog_rows"):
            assert after[key] == before[key], key


@pytest.mark.integration
@pytest.mark.parametrize("outcome", ["accepted", "cancelled"])
def test_any_paid_action_decision_prevents_downgrade(outcome):
    with _disposable_database("erp_b101_decision") as database_url:
        assert _run_alembic(database_url,"upgrade","0101").returncode == 0
        selector = ("accepted_winner_preserves_original_task_and_legacy_reader" if outcome=="accepted"
            else "cancel_proves_exact_outcome_and_fences_delayed_original")
        _run_feature(database_url,"tests/integration/test_paid_bill_action_decisions.py",selector)
        before=_inventory(database_url)
        assert before["tables"]["paid_bill_action_decisions"]["row_count"]==1
        refused=_run_alembic(database_url,"downgrade","0100")
        assert refused.returncode != 0
        assert "Paid bill action decisions prevent downgrade" in refused.stdout+refused.stderr
        after=_inventory(database_url)
        assert after==before


@pytest.mark.integration
def test_populated_v6_dump_restore_backfills_inactive_historical_owner_without_reapproval(tmp_path, monkeypatch):
    old_backend=_export_build71_backend(tmp_path/"v6",PAID_BILL_AMENDMENT_PRODUCER)
    with _disposable_database("erp_v6_source") as source, _disposable_database("erp_v7_restore") as restored:
        assert _run_alembic(source,"upgrade","0100").returncode == 0
        _run_feature(source,"tests/integration/test_paid_bill_amendments.py",
            "intent_reserves_once_replays_without_financial_effect",backend=old_backend)
        with psycopg.connect(_sync_url(source)) as connection:
            connection.execute("UPDATE users SET status='inactive' WHERE id IN (SELECT recorded_by FROM paid_bill_amendment_intents)")
        columns=_columns(source)
        projection=_legacy_projection(source,columns)
        backup=tmp_path/"v6.dump"
        dump=_client_command(source,"pg_dump","--format=custom","--no-owner","--no-privileges","--file",str(backup))
        assert dump.returncode==0,dump.stderr
        restore=_client_command(restored,"pg_restore","--no-owner","--no-privileges","--single-transaction","--exit-on-error",str(backup))
        assert restore.returncode==0,restore.stderr
        monkeypatch.setenv("PGOPTIONS", "-c lock_timeout=2000")
        with psycopg.connect(_sync_url(restored)) as old_order_holder:
            old_order_holder.execute("SELECT id FROM orders FOR UPDATE").fetchall()
            upgraded = _run_alembic(restored,"upgrade","0101")
            assert upgraded.returncode==0, upgraded.stdout+upgraded.stderr
        assert _legacy_projection(restored,columns)==projection
        with psycopg.connect(_sync_url(restored)) as connection:
            assert connection.execute("""SELECT count(*) FROM paid_bill_amendment_intents i
              JOIN paid_bill_action_decisions d ON d.accepted_intent_id=i.id
              JOIN users u ON u.id=i.recorded_by WHERE u.status='inactive'
              AND d.id=i.id AND d.outcome='accepted' AND d.company_id=i.company_id
              AND d.recorded_by=i.recorded_by AND d.order_id=i.order_id
              AND d.branch_id=i.branch_id AND d.terminal_id=i.terminal_id
              AND d.action_key=i.action_key AND d.request_body=i.request_body
              AND d.request_hash=i.request_hash AND d.request_path=i.request_path
              AND d.response_body=i.response_body AND d.decided_at=i.recorded_at""").fetchone()==(1,)
            assert connection.execute("SELECT count(*) FROM audit_log WHERE entity_type='PaidBillActionDecision'").fetchone()==(0,)
        refused=_run_alembic(restored,"downgrade","0100")
        assert refused.returncode!=0
        assert "Paid bill action decisions prevent downgrade" in refused.stdout+refused.stderr
        assert _legacy_projection(restored,columns)==projection


def _assert_0102_catalogue_is_additive(before: dict, after: dict) -> None:
    table = "paid_bill_recovery_sessions"
    assert set(after["tables"]) == set(before["tables"]) | {table}
    assert before["database_revision"] == "0101"
    assert after["database_revision"] == "0102"
    assert before["tables"]["alembic_version"]["row_count"] == 1
    assert after["tables"]["alembic_version"]["row_count"] == 1
    original_tables = {name: values for name, values in before["tables"].items()
        if name != "alembic_version"}
    assert {name: after["tables"][name] for name in original_tables} == original_tables
    assert after["sequences"] == before["sequences"]
    old_rows, new_rows = set(before["schema_catalog_rows"]), set(after["schema_catalog_rows"])
    assert old_rows <= new_rows
    functions = set()
    for row in new_rows - old_rows:
        kind, name, *_ = row.split("|")
        if kind == "function":
            functions.add(name)
        else:
            assert kind in {"column", "constraint", "index", "trigger"}
            assert name == table, row
    assert functions == {
        "dcompany_guard_paid_recovery_session", "dcompany_complete_paid_recovery_session",
    }
    old_triggers = set(before["trigger_catalog_rows"])
    new_triggers = set(after["trigger_catalog_rows"])
    assert old_triggers <= new_triggers
    added = [row.split("|") for row in new_triggers - old_triggers]
    assert {row[2] for row in added} == {
        "paid_recovery_guard", "paid_recovery_no_truncate", "paid_recovery_complete",
    }
    assert len(added) == 3
    for row in added:
        assert row[:2] == ["trigger_binding", table]
        expected = ["O", "true", "true"] if row[2] == "paid_recovery_complete" else ["O", "false", "false"]
        assert row[3:6] == expected


@pytest.mark.integration
def test_current_0102_preserves_populated_build71_restore_and_empty_roundtrip(tmp_path):
    old_backend = _export_build71_backend(tmp_path / "build71-current102")
    with (
        _disposable_database("erp_b71_current102_source") as source,
        _disposable_database("erp_b72_current102_restore") as restored,
    ):
        _populate_build71(old_backend, source)
        before = _inventory(source)
        columns = _columns(source)
        projection = _legacy_projection(source, columns)
        work = _work_evidence(source)
        assert before["tables"]["payments"]["row_count"] > 0
        assert before["tables"]["stock_movements"]["row_count"] > 0
        assert before["tables"]["attendance"]["row_count"] > 0
        assert work["fleet"][:3] == (2, 7, 1)
        backup = tmp_path / "current102-build71.dump"
        dumped = _client_command(source, "pg_dump", "--format=custom", "--no-owner",
            "--no-privileges", "--file", str(backup))
        assert dumped.returncode == 0, dumped.stderr
        recovered = _client_command(restored, "pg_restore", "--no-owner", "--no-privileges",
            "--single-transaction", "--exit-on-error", str(backup))
        assert recovered.returncode == 0, recovered.stderr
        baseline = _inventory(restored)
        assert baseline["tables"] == before["tables"]
        assert baseline["sequences"] == before["sequences"]
        assert _restore_catalogue(baseline["schema_catalog_rows"]) == _restore_catalogue(before["schema_catalog_rows"])
        assert baseline["trigger_catalog_rows"] == before["trigger_catalog_rows"]
        assert _work_evidence(restored) == work
        assert _run_alembic(restored, "upgrade", "0101").returncode == 0
        at101 = _inventory(restored)
        _assert_retained(baseline, at101, columns, projection, work, restored, "0101")
        assert _run_alembic(restored, "upgrade", "0102").returncode == 0
        at102 = _inventory(restored)
        _assert_retained(baseline, at102, columns, projection, work, restored, "0102")
        _assert_0102_catalogue_is_additive(at101, at102)
        assert _run_alembic(restored, "downgrade", "0095").returncode == 0
        at95 = _inventory(restored)
        assert at95["database_revision"] == "0095"
        for key in ("tables", "sequences", "trigger_catalog_rows"):
            assert at95[key] == baseline[key], key
        assert _restore_catalogue(at95["schema_catalog_rows"]) == _restore_catalogue(baseline["schema_catalog_rows"])
        assert _run_alembic(restored, "upgrade", "0102").returncode == 0
        assert _inventory(restored) == at102
        assert _legacy_projection(restored, columns) == projection
        assert _work_evidence(restored) == work
        (tmp_path / "current102-rehearsal-result.json").write_text(json.dumps({
            "source_baseline": BUILD71,
            "schema_steps": ["0095", "0101", "0102", "0095", "0102"],
            "baseline_public_tables": len(columns),
            "baseline_columns": sum(map(len, columns.values())),
            "preserved_sequences": len(before["sequences"]),
            "preserved_business_counters": work["business"],
            "preserved_fleet_rows_pending_total_nonzero": list(work["fleet"][:3]),
            "exact_0101_catalogue_and_bindings_retained": True,
            "new_recovery_functions": 2, "new_recovery_trigger_bindings": 3,
            "production_admission": False,
        }, indent=2) + "\n")


@pytest.mark.integration
@pytest.mark.parametrize("outcome", ["accepted", "cancelled"])
def test_current_0102_preserves_populated_0101_decisions_without_reapproval(tmp_path, monkeypatch, outcome):
    historical = _export_build71_backend(tmp_path / ("v101-" + outcome), PAID_BILL_DECISION_PRODUCER)
    with _disposable_database("erp_b102_populated_decisions") as database_url:
        assert _run_alembic(database_url, "upgrade", "0101").returncode == 0
        selector = ("accepted_winner_preserves_original_task_and_legacy_reader" if outcome == "accepted"
            else "cancel_proves_exact_outcome_and_fences_delayed_original")
        _run_feature(database_url, "tests/integration/test_paid_bill_action_decisions.py", selector,
            backend=historical)
        with psycopg.connect(_sync_url(database_url)) as connection:
            connection.execute("UPDATE users SET status='inactive' WHERE id IN (SELECT recorded_by FROM paid_bill_action_decisions)")
        before = _inventory(database_url)
        columns = _columns(database_url)
        projection = _legacy_projection(database_url, columns)
        assert before["tables"]["paid_bill_action_decisions"]["row_count"] == 1
        monkeypatch.setenv("PGOPTIONS", "-c lock_timeout=2000")
        with psycopg.connect(_sync_url(database_url)) as holder:
            holder.execute("SELECT id FROM orders FOR UPDATE").fetchall()
            result = _run_alembic(database_url, "upgrade", "0102")
            assert result.returncode == 0, result.stdout + result.stderr
        after = _inventory(database_url)
        assert after["database_revision"] == "0102"
        assert _legacy_projection(database_url, columns) == projection
        _assert_0102_catalogue_is_additive(before, after)
        assert after["tables"]["paid_bill_recovery_sessions"]["row_count"] == 0
        assert _run_alembic(database_url, "downgrade", "0101").returncode == 0
        assert _inventory(database_url) == before
        assert _run_alembic(database_url, "upgrade", "0102").returncode == 0
        assert _inventory(database_url) == after


NEW_LEDGERS["0103"] = NEW_LEDGERS["0102"] | {
    "grn_action_decisions", "grn_completion_proofs", "grn_recovery_sessions",
}
REPORT_TABLE = "pos_receipt_mistake_reports"
NEW_LEDGERS["0104"] = NEW_LEDGERS["0103"]
NEW_LEDGERS["0105"] = NEW_LEDGERS["0104"] | {REPORT_TABLE}


def _assert_0103_catalogue_is_additive(before: dict, after: dict, *,
                                       grn_backfill: Callable[[dict, dict], None] | None = None) -> None:
    tables = {"grn_action_decisions", "grn_completion_proofs", "grn_recovery_sessions"}
    sources = {
        "grns", "purchase_orders", "grn_lines", "purchase_order_lines", "batches",
        "stock_movements", "ingredients", "suppliers", "journal_entries", "journal_lines",
    }
    functions = {
        "dcompany_grn_wire_hash", "dcompany_grn_unique_json", "dcompany_grn_valid_wire",
        "dcompany_grn_source", "dcompany_grn_response", "dcompany_grn_append_only",
        "dcompany_grn_accept", "dcompany_grn_proof_guard", "dcompany_grn_complete",
        "dcompany_grn_decision_guard", "dcompany_grn_session_guard",
        "dcompany_grn_session_complete", "dcompany_grn_source_no_truncate",
        "dcompany_grn_sealed_source",
    }
    triggers = {
        "grn_decision_validate": "grn_action_decisions",
        "grn_proof_validate": "grn_completion_proofs",
        "grn_accept_decision": "grns",
        "grn_complete_decision": "grn_action_decisions",
        "grn_complete_proof": "grn_completion_proofs",
        "grn_decision_no_edit": "grn_action_decisions",
        "grn_decision_no_truncate": "grn_action_decisions",
        "grn_proof_no_edit": "grn_completion_proofs",
        "grn_proof_no_truncate": "grn_completion_proofs",
        "grn_session_guard": "grn_recovery_sessions",
        "grn_session_no_truncate": "grn_recovery_sessions",
        "grn_session_complete": "grn_recovery_sessions",
        **{f"grn_seal_{table}": table for table in sources},
        **{f"grn_no_truncate_{table}": table for table in sources},
    }
    assert len(functions) == 14
    assert len(triggers) == 32
    assert before["database_revision"] == "0102"
    assert after["database_revision"] == "0103"
    assert set(after["tables"]) == set(before["tables"]) | tables
    expected_changes = {"alembic_version"}
    if grn_backfill is None:
        assert after["sequences"] == before["sequences"]
    else:
        grn_backfill(before, after)
        expected_changes.add("audit_log")
    original = {name: value for name, value in before["tables"].items() if name not in expected_changes}
    assert {name: after["tables"][name] for name in original} == original
    assert before["tables"]["alembic_version"]["row_count"] == 1
    assert after["tables"]["alembic_version"]["row_count"] == 1
    old_rows, new_rows = set(before["schema_catalog_rows"]), set(after["schema_catalog_rows"])
    assert old_rows <= new_rows
    observed_functions = set()
    observed_triggers = set()
    for row in new_rows - old_rows:
        kind, name, *parts = row.split("|")
        if kind == "function":
            observed_functions.add(name)
        elif kind == "trigger":
            assert triggers.get(parts[0]) == name, row
            observed_triggers.add(parts[0])
        else:
            assert kind in {"column", "constraint", "index"}
            assert name in tables, row
    assert observed_functions == functions
    # SQL-standard information_schema excludes TRUNCATE; pg_trigger below retains all32.
    assert observed_triggers == {name for name in triggers if "truncate" not in name}
    old_bindings = set(before["trigger_catalog_rows"])
    new_bindings = set(after["trigger_catalog_rows"])
    assert old_bindings <= new_bindings
    added = [row.split("|") for row in new_bindings - old_bindings]
    assert len(added) == 32
    assert {row[2] for row in added} == set(triggers)
    deferred = {"grn_complete_decision", "grn_complete_proof", "grn_session_complete"}
    for row in added:
        assert row[:2] == ["trigger_binding", triggers[row[2]]]
        expected = ["O", "true", "true"] if row[2] in deferred else ["O", "false", "false"]
        assert row[3:6] == expected


def _assert_0104_stock_guard_delta(before: dict, after: dict) -> None:
    old_rows = set(before["schema_catalog_rows"])
    new_rows = set(after["schema_catalog_rows"])
    changes = {
        "dcompany_guard_receipt_stock_restoration",
        "dcompany_check_receipt_stock_complete",
    }
    assert len(old_rows - new_rows) == len(new_rows - old_rows) == len(changes)
    for previous in old_rows - new_rows:
        kind, name, *_ = previous.split("|", 3)
        assert kind == "function"
        changes.remove(name)
        marker = "AS $function$"
        assert previous.count(marker) == 1
        assert previous.replace(marker, " SET \"TimeZone\" TO 'UTC'\n" + marker) in new_rows
    assert set(after["tables"]) == set(before["tables"])
    assert after["trigger_catalog_rows"] == before["trigger_catalog_rows"]


def _assert_0105_report_delta(before: dict, after: dict) -> None:
    assert set(after["tables"]) == set(before["tables"]) | {REPORT_TABLE}
    old_rows = set(before["schema_catalog_rows"])
    new_rows = set(after["schema_catalog_rows"])
    assert old_rows <= new_rows
    added = [row.split("|", 4) for row in new_rows - old_rows]
    assert Counter((row[0], row[1]) for row in added) == {
        ("column", REPORT_TABLE): 13,
        ("constraint", REPORT_TABLE): 10,
        ("index", REPORT_TABLE): 4,
        ("trigger", REPORT_TABLE): 2,
        ("function", "dcompany_keep_pos_receipt_mistake_reports"): 1,
    }
    assert {row[3] for row in added if row[0] == "column"} == {
        "id", "company_id", "branch_id", "order_id", "original_shift_id", "original_terminal_id",
        "reporter_user_id", "idempotency_key", "request_hash", "invoice_no", "reason", "note", "created_at",
    }
    old_bindings = set(before["trigger_catalog_rows"])
    new_bindings = set(after["trigger_catalog_rows"])
    assert old_bindings <= new_bindings
    triggers = [row.split("|") for row in new_bindings - old_bindings]
    assert len(triggers) == 2
    assert {row[2] for row in triggers} == {
        "pos_receipt_mistake_report_immutable", "pos_receipt_mistake_report_no_truncate",
    }
    assert all(row[:2] == ["trigger_binding", REPORT_TABLE] and row[3:6] == ["O", "false", "false"]
               for row in triggers)


def _retained_fleet(database_url: str) -> dict:
    result = _client_command(
        database_url, "psql", "-XqAt", "--file", str(ROOT / "infra/scripts/verify-build45-pending-state.sql"),
    )
    assert result.returncode == 0, result.stdout + result.stderr
    return json.loads(result.stdout)


def _assert_grn_backfill_fail_closed(source, before, fleet_before, migrated, fleet_after, verifier, keyless_id):
    verify = verifier["verify_after"]
    evidence = migrated["legacy_grn_backfill"]
    sequence = before["sequences"]["audit_log_id_seq"]
    count = before["legacy_grn_backfill"]["row_count"]
    assert not sequence["is_called"]
    assert evidence["audit_append"]["row_count"] == count
    assert evidence["audit_append"]["first_id"] == sequence["last_value"]
    assert evidence["audit_append"]["last_id"] == sequence["last_value"] + count - 1
    assert evidence["audit_prefix"] == before["legacy_grn_backfill"]["audit_prefix"]
    # A called sequence ending immediately before the same first ID must also pass.
    called = deepcopy(before)
    called["sequences"]["audit_log_id_seq"].update(last_value=sequence["last_value"] - 1, is_called=True)
    verify(called, fleet_before, migrated, fleet_after)
    for partition, field, value, message in (
        ("audit_prefix", "row_sha256", "1" * 64, "retained audit prefix changed"),
        ("audit_prefix", "high_water", sequence["last_value"], "retained audit prefix changed"),
        ("audit_append", "row_sha256", "2" * 64, "unexpected migration audit append"),
        ("audit_append", "first_id", sequence["last_value"] + 1, "unexpected migration audit append"),
        ("audit_append", "timestamp_count", 2, "unexpected migration audit append"),
    ):
        changed = deepcopy(migrated)
        changed["legacy_grn_backfill"][partition][field] = value
        with pytest.raises(ValueError, match=message):
            verify(before, fleet_before, changed, fleet_after)
    for ledger in ("grn_action_decisions", "grn_completion_proofs"):
        changed = deepcopy(migrated)
        changed["tables"][ledger]["row_sha256"] = "3" * 64
        with pytest.raises(ValueError, match="exact retained source projection"):
            verify(before, fleet_before, changed, fleet_after)
    changed = deepcopy(migrated)
    changed["sequences"]["audit_log_id_seq"]["cache_size"] += 1
    with pytest.raises(ValueError, match="unexpected audit or other sequence change"):
        verify(before, fleet_before, changed, fleet_after)

    with psycopg.connect(_sync_url(source)) as connection:
        assert connection.execute(
            "SELECT g.idempotency_key,d.id,p.id FROM grns g "
            "LEFT JOIN grn_action_decisions d ON d.id=g.id LEFT JOIN grn_completion_proofs p ON p.id=g.id "
            "WHERE g.id=%s", (keyless_id,),
        ).fetchone() == (None, None, None)
        connection.execute(
            "INSERT INTO audit_log(company_id, action, entity_type, entity_id) "
            "SELECT company_id, 'test_unrelated', 'GRN', id::text FROM grn_action_decisions LIMIT 1"
        )
    unrelated = _inventory(source, grn_backfill=True)
    with pytest.raises(ValueError, match="retained audit prefix changed|unexpected audit or other sequence change"):
        verify(before, fleet_before, unrelated, fleet_after)


@pytest.mark.integration
@pytest.mark.parametrize("keyed_grns", [False, True], ids=["empty-grn-roundtrip", "historical-grns"])
def test_build72_preserves_populated_build71_restore_and_empty_roundtrip(tmp_path, keyed_grns):
    old_backend = _export_build71_backend(tmp_path / "build71-current")
    inventory = partial(_inventory, grn_backfill=True)
    with (
        _disposable_database("erp_grn_recovery_20261113_b71_source") as source,
        _disposable_database("erp_grn_recovery_20261113_b103_restore") as restored,
    ):
        verifier = run_path(str(ROOT / "infra/scripts/verify-build72-transition.py"))
        _populate_build71(old_backend, source, legacy_pending=True)
        if keyed_grns:
            from tests.integration.test_grn_command_migration import ZERO_FIXTURE

            _run_feature(source, "tests/integration/test_inventory_purchase_accounting.py",
                         "grn_fifo_sale_finance_and_supplier_settlement_are_exact_and_idempotent", backend=old_backend)
            zero_fixture = "tests/integration/test_grn_migration_zero_fixture.py"
            (old_backend / zero_fixture).write_text(ZERO_FIXTURE)
            _run_feature(source, zero_fixture, "legacy_zero_provenance", backend=old_backend)
            with psycopg.connect(_sync_url(source)) as connection:
                # The historical fixture creates posted receipts, a keyed zero
                # receipt with unknown provenance, and a keyless zero receipt.
                [(keyless_id,)] = connection.execute("SELECT id FROM grns WHERE idempotency_key IS NULL").fetchall()
                connection.execute("SELECT setval('audit_log_id_seq', (SELECT max(id)+7 FROM audit_log), false)")
        before = inventory(source)
        assert _inventory(source) == {key: value for key, value in before.items() if key != "legacy_grn_backfill"}
        fleet_before = _retained_fleet(source)
        verifier["verify_before"](before, fleet_before)
        assert before["legacy_grn_backfill"]["row_count"] == (4 if keyed_grns else 0)
        columns = _columns(source)
        projection = _legacy_projection(source, columns)
        work = _work_evidence(source)
        assert before["tables"]["payments"]["row_count"] > 0
        assert before["tables"]["stock_movements"]["row_count"] > 0
        assert before["tables"]["attendance"]["row_count"] > 0
        assert work["fleet"][:3] == (2, 7, 1)
        backup = tmp_path / "current103-build71.dump"
        dumped = _client_command(source, "pg_dump", "--format=custom", "--no-owner",
            "--no-privileges", "--file", str(backup))
        assert dumped.returncode == 0, dumped.stderr
        recovered = _client_command(restored, "pg_restore", "--no-owner", "--no-privileges",
            "--single-transaction", "--exit-on-error", str(backup))
        assert recovered.returncode == 0, recovered.stderr
        baseline = inventory(restored)
        verifier["verify_restore"](before, baseline)
        verifier["verify_same_fleet"](fleet_before, _retained_fleet(restored))
        assert baseline["tables"] == before["tables"]
        assert baseline["sequences"] == before["sequences"]
        assert _restore_catalogue(baseline["schema_catalog_rows"]) == _restore_catalogue(
            before["schema_catalog_rows"])
        assert baseline["trigger_catalog_rows"] == before["trigger_catalog_rows"]
        assert _work_evidence(restored) == work
        assert _run_alembic(restored, "upgrade", "0102").returncode == 0
        at102 = inventory(restored)
        _assert_retained(baseline, at102, columns, projection, work, restored, "0102")
        assert _run_alembic(restored, "upgrade", "0103").returncode == 0
        at103 = inventory(restored)
        backfill = verifier["verify_legacy_grn_backfill"]
        _assert_retained(baseline, at103, columns, projection, work, restored, "0103", grn_backfill=backfill)
        _assert_0103_catalogue_is_additive(at102, at103, grn_backfill=backfill)
        assert _run_alembic(restored, "upgrade", "0104").returncode == 0
        at104 = inventory(restored)
        _assert_retained(baseline, at104, columns, projection, work, restored, "0104", grn_backfill=backfill)
        _assert_0104_stock_guard_delta(at103, at104)
        assert _run_alembic(restored, "upgrade", "0105").returncode == 0
        at105 = inventory(restored)
        _assert_retained(baseline, at105, columns, projection, work, restored, "0105", grn_backfill=backfill)
        _assert_0105_report_delta(at104, at105)
        for previous, following in ((at103, at104), (at104, at105)):
            assert all(following["tables"][table] == entry for table, entry in previous["tables"].items()
                       if table != "alembic_version")
        # The backup/restore probe can render known equivalent CHECK
        # spellings. Exercise the installer against the raw live-shaped delta.
        assert _run_alembic(source, "upgrade", "0105").returncode == 0
        migrated = inventory(source)
        fleet_after = _retained_fleet(source)
        verifier["verify_after"](before, fleet_before, migrated, fleet_after)
        modified = {**migrated, "tables": {**migrated["tables"], "payments": migrated["tables"]["attendance"]}}
        with pytest.raises(ValueError, match="retained rows changed"):
            verifier["verify_after"](before, fleet_before, modified, fleet_after)
        with pytest.raises(ValueError, match="fleet changed"):
            verifier["verify_after"](before, fleet_before, migrated,
                                    {**fleet_after, "all_installations_identity_sha256": "f" * 64})
        if keyed_grns:
            _assert_grn_backfill_fail_closed(source, before, fleet_before, migrated, fleet_after, verifier, keyless_id)
            refused = _run_alembic(restored, "downgrade", "0095")
            assert refused.returncode != 0 and "GRN command evidence prevents downgrade" in refused.stderr
            assert inventory(restored) == at105
            return
        assert _run_alembic(restored, "downgrade", "0095").returncode == 0
        at95 = inventory(restored)
        assert at95["database_revision"] == "0095"
        for key in ("tables", "sequences", "trigger_catalog_rows"):
            assert at95[key] == baseline[key], key
        assert _restore_catalogue(at95["schema_catalog_rows"]) == _restore_catalogue(
            baseline["schema_catalog_rows"])
        assert _run_alembic(restored, "upgrade", "0105").returncode == 0
        assert inventory(restored) == at105
        assert _legacy_projection(restored, columns) == projection
        assert _work_evidence(restored) == work
        (tmp_path / "build72-rehearsal-result.json").write_text(json.dumps({
            "source_baseline": BUILD71,
            "schema_steps": ["0095", "0102", "0103", "0104", "0105", "0095", "0105"],
            "baseline_public_tables": len(columns),
            "baseline_columns": sum(map(len, columns.values())),
            "preserved_sequences": len(before["sequences"]),
            "preserved_business_counters": work["business"],
            "preserved_fleet_rows_pending_total_nonzero": list(work["fleet"][:3]),
            "exact_old_catalogue_and_bindings_retained": True,
            "new_grn_tables": 3, "new_grn_functions": 14, "new_grn_trigger_bindings": 32,
            "configured_stock_guards": 2, "new_report_table": REPORT_TABLE,
            "production_admission": False,
        }, indent=2) + "\n")
