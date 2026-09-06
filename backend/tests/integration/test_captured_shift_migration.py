"""Opening receipt migration is additive; rollback cannot erase captured work."""

from datetime import UTC, datetime, timedelta
from uuid import uuid4
import psycopg
import pytest
from tests.integration.test_cafe_workflow_migration import (
    _disposable_database,
    _run_alembic,
    _seed_0036_cafe_scope,
)


@pytest.mark.integration
@pytest.mark.parametrize("was_offline", [False, True], ids=["online-keyed", "offline-captured"])
def test_0069_preserves_old_shifts_and_blocks_receipt_loss(was_offline):
    with _disposable_database("erp_shift_capture_0069") as url:
        result = _run_alembic(url, "upgrade", "0036")
        assert result.returncode == 0, result.stdout + result.stderr
        dsn = url.replace("postgresql+psycopg://", "postgresql://", 1)
        with psycopg.connect(dsn) as conn:
            ids = _seed_0036_cafe_scope(conn)
            conn.execute("DELETE FROM orders WHERE id=%s", (ids["order_two"],))
            before = conn.execute(
                "SELECT id,opened_at,opening_float_minor,status FROM shifts ORDER BY id"
            ).fetchall()
            conn.commit()
        result = _run_alembic(url, "upgrade", "0069")
        assert result.returncode == 0, result.stdout + result.stderr
        with psycopg.connect(dsn) as conn:
            assert (
                conn.execute(
                    "SELECT id,opened_at,opening_float_minor,status FROM shifts ORDER BY id"
                ).fetchall()
                == before
            )
            assert conn.execute(
                "SELECT opening_action_id,opening_request_hash,opening_received_at,opening_was_offline,opening_protocol_revision,opening_client_platform,opening_client_installation_id FROM shifts WHERE id=%s",
                (ids["shift"],),
            ).fetchone() == (None, None, None, False, None, None, None)
        result = _run_alembic(url, "downgrade", "0068")
        assert result.returncode == 0, result.stdout + result.stderr
        result = _run_alembic(url, "upgrade", "0069")
        assert result.returncode == 0, result.stdout + result.stderr
        with psycopg.connect(dsn) as conn:
            terminal = uuid4()
            receipt_shift_id = uuid4()
            installation_id = uuid4()
            now = datetime.now(UTC)
            branch = uuid4()
            conn.execute(
                "INSERT INTO branches(id,company_id,name,code,invoice_series_code) VALUES(%s,%s,'Capture shop','CAP','CP')",
                (branch, ids["company"]),
            )
            conn.execute(
                "INSERT INTO terminals(id,branch_id,name,purpose,is_active) VALUES(%s,%s,'Migration capture','hybrid',true)",
                (terminal, branch),
            )
            conn.execute(
                "INSERT INTO shifts(id,company_id,branch_id,terminal_id,opened_by,opened_at,opening_float_minor,expected_minor,status,opening_action_id,opening_request_hash,opening_received_at,opening_was_offline,opening_client_platform,opening_client_installation_id) VALUES(%s,%s,%s,%s,%s,%s,0,0,'open','shift-open:migration-open',%s,%s,%s,'android',%s)",
                (
                    receipt_shift_id,
                    ids["company"],
                    branch,
                    terminal,
                    ids["user"],
                    now - timedelta(minutes=5) if was_offline else now,
                    "a" * 64,
                    now,
                    was_offline,
                    installation_id,
                ),
            )
            conn.commit()
        result = _run_alembic(url, "downgrade", "0068")
        assert result.returncode != 0
        assert "new-protocol shift opening exists" in result.stdout + result.stderr
        with psycopg.connect(dsn) as conn:
            assert conn.execute("SELECT version_num FROM alembic_version").fetchone() == ("0069",)
            # A refused downgrade must preserve identity even for an ordinary
            # online POST: a lost response can be retried after its shift closes.
            assert conn.execute(
                "SELECT opening_action_id, opening_request_hash, opening_received_at, opening_was_offline, opening_protocol_revision, opening_client_platform, opening_client_installation_id FROM shifts WHERE id=%s",
                (receipt_shift_id,),
            ).fetchone() == (
                "shift-open:migration-open",
                "a" * 64,
                now,
                was_offline,
                1,
                "android",
                installation_id,
            )


@pytest.mark.integration
def test_0069_unkeyed_web_open_is_new_protocol_and_blocks_downgrade():
    """Downgrade must not erase the vintage of an unkeyed post-0069 shift."""
    with _disposable_database("erp_shift_capture_0069_web") as url:
        result = _run_alembic(url, "upgrade", "0036")
        assert result.returncode == 0, result.stdout + result.stderr
        dsn = url.replace("postgresql+psycopg://", "postgresql://", 1)
        with psycopg.connect(dsn) as conn:
            ids = _seed_0036_cafe_scope(conn)
            conn.execute("DELETE FROM orders WHERE id=%s", (ids["order_two"],))
            conn.commit()
        result = _run_alembic(url, "upgrade", "0069")
        assert result.returncode == 0, result.stdout + result.stderr
        with psycopg.connect(dsn) as conn:
            web_shift_id = uuid4()
            web_branch_id = uuid4()
            web_terminal_id = uuid4()
            # The 0036 fixture deliberately leaves its original shift open.
            # Use a separate terminal so this assertion tests protocol defaults,
            # not the one-open-shift-per-terminal invariant.
            conn.execute(
                "INSERT INTO branches(id,company_id,name,code,invoice_series_code) "
                "VALUES(%s,%s,'Web shop','WEB','WB')",
                (web_branch_id, ids["company"]),
            )
            conn.execute(
                "INSERT INTO terminals(id,branch_id,name,purpose,is_active) "
                "VALUES(%s,%s,'Web capture','hybrid',true)",
                (web_terminal_id, web_branch_id),
            )
            conn.execute(
                "INSERT INTO shifts(id,company_id,branch_id,terminal_id,opened_by,opened_at,opening_float_minor,expected_minor,status) VALUES(%s,%s,%s,%s,%s,%s,0,0,'open')",
                (
                    web_shift_id,
                    ids["company"],
                    web_branch_id,
                    web_terminal_id,
                    ids["user"],
                    datetime.now(UTC),
                ),
            )
            conn.commit()
            assert conn.execute(
                "SELECT opening_action_id,opening_protocol_revision,opening_client_platform,opening_client_installation_id FROM shifts WHERE id=%s",
                (web_shift_id,),
            ).fetchone() == (None, 1, "web", None)

        result = _run_alembic(url, "downgrade", "0068")
        assert result.returncode != 0
        assert "new-protocol shift opening exists" in result.stdout + result.stderr
        with psycopg.connect(dsn) as conn:
            assert conn.execute("SELECT version_num FROM alembic_version").fetchone() == (
                "0069",
            )
