"""Real PostgreSQL proof for the retained Code30.2 outbox exception query.

The production-only verifier pins immutable cleanup evidence while permitting
normal Android heartbeat fields and audited post-cleanup device-key expiry
churn.  This test executes the same SQL against a freshly migrated database so
its lifecycle predicates and database-guard fingerprints cannot drift silently.
"""

from __future__ import annotations

import hashlib
import json
from datetime import UTC, datetime, timedelta
from pathlib import Path
from uuid import UUID, uuid4

import psycopg
import pytest
from psycopg.types.json import Jsonb

from tests.integration.test_cafe_workflow_migration import (
    _disposable_database,
    _run_alembic,
)

_ROOT = Path(__file__).resolve().parents[3]
_STATE_QUERY = _ROOT / "infra/scripts/verify-code30-2-post-cleanup-state.sql"
_COMPANY_ID = UUID("8f323fba-4358-45fe-9d3b-a8e0fae52993")
_REGISTERED_USER_ID = UUID("c2aed53f-401f-4e09-8237-11c366e612ef")
_RECEIPT_ACTOR_ID = UUID("7016c42c-11c9-48fa-a30a-5d66a8c970b7")
_TERMINAL_ID = UUID("789353a8-09e4-4ef2-9fa8-ac73c426bfc8")
_INSTALLATION_ROW_ID = UUID("92b491f1-c35b-4437-af9a-a6be68035001")
_PUBLIC_INSTALLATION_ID = UUID("d664b4a5-d293-48a7-a96c-8c3050ed5e76")
_RECEIPT_AT = datetime(2026, 9, 20, 14, 34, 47, 822128, tzinfo=UTC)


def _query_statement() -> str:
    source = _STATE_QUERY.read_text(encoding="utf-8")
    start = source.index("WITH receipt_candidates AS")
    end = source.index("\n;", start)
    return source[start:end]


def _device_ref() -> str:
    material = f"{_COMPANY_ID}:{_PUBLIC_INSTALLATION_ID}".encode("ascii")
    return hashlib.sha256(material).hexdigest()[:20]


def _key_ref(key_id: UUID, fingerprint: str) -> str:
    material = f"{_COMPANY_ID}:{key_id}:{fingerprint}".encode("ascii")
    return hashlib.sha256(material).hexdigest()[:20]


def _insert_release(connection: psycopg.Connection, version_code: int) -> None:
    version_name = "3.1.28" if version_code == 36 else "3.1.29"
    connection.execute(
        """
        INSERT INTO android_releases (
            id, channel, version_code, version_name, update_url, release_notes,
            apk_sha256, apk_size_bytes, apk_signing_cert_sha256, manifest_sha256,
            source_git_sha, source_release_ref, source_workflow_run_id,
            source_workflow_run_attempt, status
        ) VALUES (
            %s, 'direct', %s, %s, %s, 'Migration verifier fixture',
            %s, 1, %s, %s, %s, %s, %s, 1, 'staged'
        )
        """,
        (
            uuid4(),
            version_code,
            version_name,
            f"https://example.invalid/{version_name}.apk",
            f"{version_code:064x}",
            f"{version_code + 1:064x}",
            f"{version_code + 2:064x}",
            f"{version_code:040x}",
            f"v{version_name}",
            version_code,
        ),
    )


def _insert_key(
    connection: psycopg.Connection,
    *,
    enrolled_at: datetime,
    status: str,
    suffix: int,
    audited: bool,
) -> UUID:
    key_id = uuid4()
    spki = b"0" * 89 + bytes([suffix % 256])
    fingerprint = hashlib.sha256(spki).hexdigest()
    pending_expires_at = enrolled_at + timedelta(minutes=10)
    updated_at = pending_expires_at + timedelta(seconds=1) if status == "expired" else enrolled_at
    connection.execute(
        """
        INSERT INTO remote_assistance_device_keys (
            id, company_id, client_installation_id, public_key_spki,
            public_key_fingerprint_sha256, status, enrollment_id,
            enrolled_by_user_id, enrolled_at, pending_expires_at,
            created_at, updated_at
        ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
        """,
        (
            key_id,
            _COMPANY_ID,
            _INSTALLATION_ROW_ID,
            spki,
            fingerprint,
            status,
            uuid4(),
            _REGISTERED_USER_ID,
            enrolled_at,
            pending_expires_at,
            enrolled_at,
            updated_at,
        ),
    )
    if not audited:
        return key_id

    after_enrollment = {
        "device_ref": _device_ref(),
        "key_ref": _key_ref(key_id, fingerprint),
        "status": "pending",
    }
    connection.execute(
        """
        INSERT INTO audit_log (
            actor_user_id, company_id, action, entity_type, entity_id,
            before, after, ip, user_agent, terminal_id, request_id,
            client_platform, client_version_code, client_was_offline, created_at
        ) VALUES (
            %s, %s, 'remote_assistance.device_key.enrolled',
            'RemoteAssistanceDeviceKey', %s, %s, %s,
            '127.0.0.1', 'postgres-lifecycle-fixture', %s, %s,
            'android', 37, false, %s
        )
        """,
        (
            _REGISTERED_USER_ID,
            _COMPANY_ID,
            str(key_id),
            Jsonb(None),
            Jsonb(after_enrollment),
            _TERMINAL_ID,
            f"enroll-{suffix}",
            # The application timestamp is captured after PostgreSQL's
            # transaction timestamp in the real enrollment path.
            enrolled_at - timedelta(milliseconds=10),
        ),
    )
    if status == "expired":
        connection.execute(
            """
            INSERT INTO audit_log (
                actor_user_id, company_id, action, entity_type, entity_id,
                before, after, ip, user_agent, terminal_id, request_id,
                client_platform, client_version_code, client_was_offline, created_at
            ) VALUES (
                NULL, %s, 'remote_assistance.device_key.expired',
                'RemoteAssistanceDeviceKey', %s, %s, %s,
                '127.0.0.1', 'postgres-lifecycle-fixture', %s, %s,
                'android', 37, false, %s
            )
            """,
            (
                _COMPANY_ID,
                str(key_id),
                Jsonb({"status": "pending"}),
                Jsonb({**after_enrollment, "status": "expired"}),
                _TERMINAL_ID,
                f"expire-{suffix}",
                pending_expires_at + timedelta(microseconds=1),
            ),
        )
    return key_id


def _seed_state(connection: psycopg.Connection) -> None:
    branch_id = uuid4()
    connection.execute(
        "INSERT INTO companies (id, name) VALUES (%s, 'Post-cleanup verifier')",
        (_COMPANY_ID,),
    )
    connection.execute(
        """
        INSERT INTO users (id, company_id, email, password_hash, name)
        VALUES
            (%s, %s, 'device-owner@test.local', 'not-a-password', 'Device owner'),
            (%s, %s, 'cleanup-owner@test.local', 'not-a-password', 'Cleanup owner')
        """,
        (
            _REGISTERED_USER_ID,
            _COMPANY_ID,
            _RECEIPT_ACTOR_ID,
            _COMPANY_ID,
        ),
    )
    connection.execute(
        """
        INSERT INTO branches (id, company_id, name, invoice_series_code)
        VALUES (%s, %s, 'Verifier branch', 'PV')
        """,
        (branch_id, _COMPANY_ID),
    )
    connection.execute(
        """
        INSERT INTO terminals (id, branch_id, name, purpose, device_id)
        VALUES (%s, %s, 'Verifier terminal', 'hybrid', %s)
        """,
        (_TERMINAL_ID, branch_id, f"verifier-{uuid4()}"),
    )
    _insert_release(connection, 36)
    _insert_release(connection, 37)
    connection.execute(
        """
        INSERT INTO client_installations (
            id, company_id, installation_id, registered_by_user_id,
            last_user_id, terminal_id, platform, distribution_channel,
            version_name, version_code, pending_outbox_count,
            last_successful_sync_at, update_state, last_seen_at,
            created_at, updated_at
        ) VALUES (
            %s, %s, %s, %s, %s, %s, 'android', 'direct',
            '3.1.29', 37, 1, %s, 'idle', %s, %s, %s
        )
        """,
        (
            _INSTALLATION_ROW_ID,
            _COMPANY_ID,
            _PUBLIC_INSTALLATION_ID,
            _REGISTERED_USER_ID,
            _REGISTERED_USER_ID,
            _TERMINAL_ID,
            datetime(2026, 9, 21, 11, 0, 48, 341000, tzinfo=UTC),
            datetime(2026, 9, 21, 11, 0, 49, 448650, tzinfo=UTC),
            datetime(2026, 9, 15, 10, 14, 46, 26411, tzinfo=UTC),
            datetime(2026, 9, 21, 11, 0, 49, 448650, tzinfo=UTC),
        ),
    )

    for index in range(29):
        _insert_key(
            connection,
            enrolled_at=datetime(2026, 9, 19, 9, 0, tzinfo=UTC) + timedelta(minutes=11 * index),
            status="expired",
            suffix=index,
            audited=False,
        )

    enrolled_at = _RECEIPT_AT + timedelta(minutes=1)
    for index, status in enumerate(("expired", "expired", "pending"), start=100):
        _insert_key(
            connection,
            enrolled_at=enrolled_at,
            status=status,
            suffix=index,
            audited=True,
        )
        enrolled_at += timedelta(minutes=11)

    connection.execute(
        """
        INSERT INTO audit_log (
            id, actor_user_id, company_id, action, entity_type, entity_id,
            before, after, user_agent, terminal_id, request_id,
            client_action_id, reason, created_at
        ) VALUES (
            28204, %s, %s, 'production_trial_cleanup', 'ReleaseCleanup',
            'code30.1-20260920', %s, %s,
            'cleanup-code30-production-trial-data/2', %s,
            'code30.1-production-trial-cleanup-20260920',
            'production-trial-cleanup-20260920', 'fixture receipt', %s
        )
        """,
        (
            _RECEIPT_ACTOR_ID,
            _COMPANY_ID,
            Jsonb({}),
            Jsonb({"executed_at": _RECEIPT_AT.isoformat()}),
            _TERMINAL_ID,
            _RECEIPT_AT,
        ),
    )


def _state(connection: psycopg.Connection) -> dict[str, object]:
    connection.execute("SET LOCAL TIME ZONE 'UTC'")
    connection.execute("SET LOCAL bytea_output = 'hex'")
    value = connection.execute(_query_statement()).fetchone()
    assert value is not None
    document = value[0]
    if isinstance(document, str):
        document = json.loads(document)
    assert isinstance(document, dict)
    return document


@pytest.mark.integration
def test_post_cleanup_query_accepts_only_guarded_audited_key_churn() -> None:
    with _disposable_database("erp_code30_post_cleanup") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "head")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        dsn = database_url.replace("postgresql+psycopg://", "postgresql://", 1)
        with psycopg.connect(dsn) as connection:
            _seed_state(connection)
            connection.commit()

            document = _state(connection)
            assert document["retired_installation_count"] == 1
            assert document["retired_installation_identity_sha256"] == (
                "78ca810cea80109b50fa226622ea12531979d3e20e4dcdbe3cc4fe692859f747"
            )
            assert document["retired_installation_invalid_telemetry_count"] == 0
            assert document["client_installation_guard_trigger_count"] == 1
            assert document["client_installation_guard_function_sha256"] == (
                "5e19e5e24daa5ef6de4d51f479e158f29981a01defded0c3f56a4d42a615f575"
            )
            assert document["client_installation_guard_function_definition_sha256"] == (
                "e04d616ebcd1c53e04424bd4071b881e1e6e439a6f2fda56419b6bcc2e3d6084"
            )
            assert document["client_installation_guard_trigger_definition_sha256"] == (
                "dcd3cdf560b382ab046934d824c29a3a90af5ba56a86fe56c87e78716e190ae7"
            )
            assert document["retained_remote_assistance_device_key_count"] == 29
            assert document["retained_remote_assistance_device_key_invalid_count"] == 0
            assert document["post_cleanup_remote_assistance_device_key_count"] == 3
            assert document["post_cleanup_remote_assistance_device_key_expired_count"] == 2
            assert document["post_cleanup_remote_assistance_device_key_pending_count"] == 1
            assert document["post_cleanup_remote_assistance_device_key_invalid_count"] == 0
            assert document["post_cleanup_remote_assistance_device_key_enrollment_audit_count"] == 3
            assert document["post_cleanup_remote_assistance_device_key_expiration_audit_count"] == 2
            assert document["post_cleanup_remote_assistance_device_key_invalid_audit_count"] == 0
            assert document["remote_assistance_device_key_guard_trigger_count"] == 1
            assert document["remote_assistance_device_key_guard_function_sha256"] == (
                "93fbbce17b8f62657054d019deef4c8963ad4c7680be9fbc1acae781c433d999"
            )
            assert (
                document["remote_assistance_device_key_guard_function_definition_sha256"]
                == "831c79f1f5e8a89edf391709e0f171d37ec8ef14dd8382567343a2354e9cdd37"
            )
            assert (
                document["remote_assistance_device_key_guard_trigger_definition_sha256"]
                == "23c0907ae06aa7213082cf1cdbc55c3588c0ef5c1d9e738892b2520d0017265b"
            )

            # A structurally valid key without its exact enrollment audit must
            # remain visible as unexplained evidence rather than being accepted.
            _insert_key(
                connection,
                enrolled_at=_RECEIPT_AT + timedelta(hours=1),
                status="expired",
                suffix=200,
                audited=False,
            )
            changed = _state(connection)
            assert changed["post_cleanup_remote_assistance_device_key_count"] == 4
            assert changed["post_cleanup_remote_assistance_device_key_invalid_count"] == 1
            assert changed["post_cleanup_remote_assistance_device_key_enrollment_audit_count"] == 3

            # The exception is scoped to the one historical installation. A
            # second tablet with pending work must still raise the aggregate
            # counts that the Python verifier rejects.
            connection.execute(
                """
                INSERT INTO client_installations (
                    id, company_id, installation_id, registered_by_user_id,
                    last_user_id, terminal_id, platform, distribution_channel,
                    version_name, version_code, pending_outbox_count,
                    last_successful_sync_at, update_state, last_seen_at
                ) VALUES (
                    %s, %s, %s, %s, %s, %s, 'android', 'direct',
                    '3.1.29', 37, 1, %s, 'idle', %s
                )
                """,
                (
                    uuid4(),
                    _COMPANY_ID,
                    uuid4(),
                    _REGISTERED_USER_ID,
                    _REGISTERED_USER_ID,
                    _TERMINAL_ID,
                    datetime(2026, 9, 21, 12, 0, tzinfo=UTC),
                    datetime(2026, 9, 21, 12, 0, 1, tzinfo=UTC),
                ),
            )
            blocked = _state(connection)
            assert blocked["pending_outbox_count"] == 2
            assert blocked["nonzero_installation_count"] == 2


def _replace_trigger(
    connection: psycopg.Connection,
    *,
    table: str,
    trigger: str,
    create_statement: str,
) -> None:
    connection.execute(f"DROP TRIGGER {trigger} ON {table}")  # noqa: S608
    connection.execute(create_statement)


@pytest.mark.integration
def test_post_cleanup_query_rejects_weakened_or_rebound_guard_triggers() -> None:
    with _disposable_database("erp_code30_trigger_guards") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "head")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        dsn = database_url.replace("postgresql+psycopg://", "postgresql://", 1)
        with psycopg.connect(dsn) as connection:
            expected = _state(connection)
            assert expected["client_installation_guard_trigger_count"] == 1
            assert expected["remote_assistance_device_key_guard_trigger_count"] == 1

            _replace_trigger(
                connection,
                table="client_installations",
                trigger="trg_client_installations_scope",
                create_statement=(
                    "CREATE TRIGGER trg_client_installations_scope BEFORE INSERT "
                    "ON client_installations FOR EACH ROW EXECUTE FUNCTION "
                    "dcompany_validate_client_installation_scope()"
                ),
            )
            insert_only = _state(connection)
            assert insert_only["client_installation_guard_trigger_count"] == 1
            assert insert_only["client_installation_guard_trigger_definition_sha256"] != (
                "dcd3cdf560b382ab046934d824c29a3a90af5ba56a86fe56c87e78716e190ae7"
            )

            _replace_trigger(
                connection,
                table="client_installations",
                trigger="trg_client_installations_scope",
                create_statement=(
                    "CREATE TRIGGER trg_client_installations_scope "
                    "BEFORE INSERT OR UPDATE ON client_installations FOR EACH ROW "
                    "EXECUTE FUNCTION dcompany_validate_client_installation_scope()"
                ),
            )
            connection.execute(
                "ALTER TABLE client_installations DISABLE TRIGGER trg_client_installations_scope"
            )
            disabled = _state(connection)
            assert disabled["client_installation_guard_trigger_count"] == 0
            assert disabled["client_installation_guard_trigger_definition_sha256"] is None
            connection.execute(
                "ALTER TABLE client_installations ENABLE TRIGGER trg_client_installations_scope"
            )

            _replace_trigger(
                connection,
                table="remote_assistance_device_keys",
                trigger="trg_remote_assistance_device_keys_guard",
                create_statement=(
                    "CREATE TRIGGER trg_remote_assistance_device_keys_guard BEFORE UPDATE "
                    "ON remote_assistance_device_keys FOR EACH ROW EXECUTE FUNCTION "
                    "dcompany_guard_remote_assistance_device_key()"
                ),
            )
            update_only = _state(connection)
            assert update_only["remote_assistance_device_key_guard_trigger_count"] == 1
            assert update_only["remote_assistance_device_key_guard_trigger_definition_sha256"] != (
                "23c0907ae06aa7213082cf1cdbc55c3588c0ef5c1d9e738892b2520d0017265b"
            )

            connection.execute(
                """
                CREATE FUNCTION dcompany_test_noop_trigger()
                RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RETURN NEW;
                END;
                $$
                """
            )
            _replace_trigger(
                connection,
                table="remote_assistance_device_keys",
                trigger="trg_remote_assistance_device_keys_guard",
                create_statement=(
                    "CREATE TRIGGER trg_remote_assistance_device_keys_guard "
                    "BEFORE INSERT OR DELETE OR UPDATE ON remote_assistance_device_keys "
                    "FOR EACH ROW EXECUTE FUNCTION dcompany_test_noop_trigger()"
                ),
            )
            rebound = _state(connection)
            assert rebound["remote_assistance_device_key_guard_trigger_count"] == 0
            assert rebound["remote_assistance_device_key_guard_trigger_definition_sha256"] is None
