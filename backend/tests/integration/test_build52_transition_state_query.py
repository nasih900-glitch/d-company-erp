"""Real PostgreSQL proof for the Build52 quiesced transition snapshot."""

from __future__ import annotations

import importlib.util
import json
import re
import shutil
import subprocess
from pathlib import Path

import psycopg
import pytest
from psycopg import sql
from psycopg.conninfo import conninfo_to_dict, make_conninfo

from tests.integration.test_cafe_workflow_migration import (
    _disposable_database,
    _run_alembic,
)

ROOT = Path(__file__).resolve().parents[3]
QUERY = ROOT / "infra/scripts/verify-build52-transition-state.sql"
VERIFIER = ROOT / "infra/scripts/verify-build52-pending-compatibility.py"


def _load_verifier():
    spec = importlib.util.spec_from_file_location("build52_transition_verifier", VERIFIER)
    assert spec is not None
    assert spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _snapshot(url: str, clients: dict[str, str]) -> dict:
    dsn = url.replace("postgresql+psycopg://", "postgresql://", 1)
    result = subprocess.run(  # noqa: S603 - validated local PostgreSQL client, argv only
        [
            clients["psql"],
            "-X",
            "--no-psqlrc",
            "--quiet",
            "--tuples-only",
            "--no-align",
            dsn,
            "-f",
            str(QUERY),
        ],
        capture_output=True,
        text=True,
        check=False,
    )
    assert result.returncode == 0, result.stderr
    return json.loads(result.stdout)


def test_transition_query_preserves_every_old_table_through_upgrade_and_restore(tmp_path):
    verifier = _load_verifier()
    with _disposable_database("erp_build52_transition") as url:
        migration = _run_alembic(url, "upgrade", "0089")
        assert migration.returncode == 0, migration.stderr
        dsn = url.replace("postgresql+psycopg://", "postgresql://", 1)
        clients: dict[str, str] = {}
        # Use the server's major for both dump and restore. Newer archivers can
        # emit settings an older server cannot restore, even from its own dump.
        with psycopg.connect(dsn) as connection:
            assert connection.info.server_version // 10000 == 16
        for name in ("psql", "pg_dump", "pg_restore"):
            installed = Path("/usr/lib/postgresql/16/bin") / name
            executable = str(installed) if installed.is_file() else shutil.which(name)
            assert executable is not None, f"PostgreSQL 16 {name} is required"
            version = subprocess.run(  # noqa: S603 - fixed installed tool, version probe only
                [executable, "--version"],
                capture_output=True,
                text=True,
                check=True,
            ).stdout
            match = re.search(r"\(PostgreSQL\) (\d+)(?:\.|\b)", version)
            assert match is not None, f"PostgreSQL 16 {name} is required"
            assert match.group(1) == "16", f"PostgreSQL 16 {name} is required"
            # Preserve argv[0] for distributions using a pg_wrapper symlink.
            clients[name] = str(Path(executable).absolute())
        with psycopg.connect(dsn) as connection:
            connection.execute(
                "CREATE TABLE public.pg_am (id integer PRIMARY KEY, marker text NOT NULL)"
            )
            connection.execute("INSERT INTO public.pg_am(id,marker) VALUES(1,'catalog shadow')")
            connection.execute(
                "CREATE TABLE public.build52_sequence_fixture "
                "(id bigserial PRIMARY KEY, marker text NOT NULL)"
            )
            connection.execute(
                "INSERT INTO public.build52_sequence_fixture(marker) "
                "VALUES ('first'),('second'),('third')"
            )
            connection.execute(
                "INSERT INTO public.audit_log "
                "(company_id, action, entity_type, entity_id, before, after) "
                "SELECT '00000000-0000-0000-0000-000000000052'::uuid, "
                "'create', 'build52_fixture', n::text, NULL, "
                "jsonb_build_object('retained', n) FROM generate_series(1,3) n"
            )
            connection.commit()
        before = _snapshot(url, clients)
        (tmp_path / "before0089.json").write_text(json.dumps(before, sort_keys=True))
        shadow = next(row for row in before["table_inventory"] if row["table_name"] == "pg_am")
        assert shadow["row_count"] == 1
        assert (
            next(row for row in before["table_inventory"] if row["table_name"] == "audit_log")[
                "row_count"
            ]
            == 3
        )
        for name in ("audit_log_id_seq", "build52_sequence_fixture_id_seq"):
            sequence = next(
                row for row in before["sequence_inventory"] if row["sequence_name"] == name
            )
            assert (sequence["last_value"], sequence["is_called"]) == (3, True)

        with psycopg.connect(dsn) as connection:
            connection.execute("ALTER TABLE public.audit_log ADD COLUMN action_code varchar(24)")
            connection.commit()
        with pytest.raises(verifier.CompatibilityError, match="wrong phase"):
            verifier._verify_transition_pair(_snapshot(url, clients), before, rollback=True)
        with psycopg.connect(dsn) as connection:
            connection.execute("ALTER TABLE public.audit_log DROP COLUMN action_code")
            connection.commit()
        assert _snapshot(url, clients) == before

        dump = tmp_path / "build52-before.dump"
        archived = subprocess.run(  # noqa: S603 - validated local client and disposable DB
            [clients["pg_dump"], "--format=custom", "--file", str(dump), dsn],
            capture_output=True,
            text=True,
            check=False,
        )
        assert archived.returncode == 0, archived.stderr

        migration = _run_alembic(url, "upgrade", "head")
        assert migration.returncode == 0, migration.stderr
        after = _snapshot(url, clients)
        (tmp_path / "after0091.json").write_text(json.dumps(after, sort_keys=True))
        proof = verifier._verify_transition_pair(before, after, rollback=False)
        assert proof["preserved_table_count"] >= 100
        assert all(
            row
            == {
                "state": "present",
                "row_count": 0,
                "row_sha256": verifier.EMPTY_SHA256,
            }
            for row in after["finance_history"].values()
        )
        with psycopg.connect(dsn) as connection:
            connection.execute("UPDATE public.pg_am SET marker='changed' WHERE id=1")
            connection.commit()
        with pytest.raises(verifier.CompatibilityError, match="history changed"):
            verifier._verify_transition_pair(before, _snapshot(url, clients), rollback=False)
        with psycopg.connect(dsn) as connection:
            connection.execute("UPDATE public.pg_am SET marker='catalog shadow' WHERE id=1")
            connection.execute("SELECT nextval('public.build52_sequence_fixture_id_seq')")
            connection.commit()
        with pytest.raises(verifier.CompatibilityError, match="sequence"):
            verifier._verify_transition_pair(before, _snapshot(url, clients), rollback=False)
        with psycopg.connect(dsn) as connection:
            connection.execute("SELECT setval('public.build52_sequence_fixture_id_seq',3,true)")
            connection.commit()
        assert _snapshot(url, clients) == after

        migration = _run_alembic(url, "downgrade", "0089")
        assert migration.returncode == 0, migration.stderr
        downgraded = _snapshot(url, clients)
        (tmp_path / "downgraded0089.json").write_text(json.dumps(downgraded, sort_keys=True))
        assert (
            verifier._verify_transition_pair(before, downgraded, rollback=True)[
                "logical_restore_verified"
            ]
            is True
        )

        connection_options = conninfo_to_dict(dsn)
        database_name = connection_options["dbname"]
        admin_options = {**connection_options, "dbname": "postgres"}
        with psycopg.connect(make_conninfo(**admin_options), autocommit=True) as admin:
            admin.execute(
                "SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                "WHERE datname=%s AND pid<>pg_backend_pid()",
                (database_name,),
            )
            admin.execute(sql.SQL("DROP DATABASE {}").format(sql.Identifier(database_name)))
            admin.execute(
                sql.SQL("CREATE DATABASE {} TEMPLATE template0").format(
                    sql.Identifier(database_name)
                )
            )
        restored_result = subprocess.run(  # noqa: S603 - validated local client and own dump
            [clients["pg_restore"], "--exit-on-error", "--no-owner", "--dbname", dsn, str(dump)],
            capture_output=True,
            text=True,
            check=False,
        )
        assert restored_result.returncode == 0, restored_result.stderr
        restored = _snapshot(url, clients)
        (tmp_path / "restored0089.json").write_text(json.dumps(restored, sort_keys=True))
        assert (
            restored["database_identity"]["database_oid"]
            != before["database_identity"]["database_oid"]
        )
        proof = verifier._verify_transition_pair(before, restored, rollback=True)
        assert proof["logical_restore_verified"] is True
        assert all(
            row == {"state": "absent", "row_count": None, "row_sha256": None}
            for row in restored["finance_history"].values()
        )
