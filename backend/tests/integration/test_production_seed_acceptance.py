"""Clean-database proof for the production owner handoff contract."""

from __future__ import annotations

import base64
import os
import subprocess
import sys
from contextlib import contextmanager
from pathlib import Path
from uuid import uuid4

import psycopg
import pytest
from psycopg import sql
from sqlalchemy.engine import make_url

_BACKEND_ROOT = Path(__file__).resolve().parents[2]


@contextmanager
def _disposable_database(prefix: str):
    source_url = make_url(
        os.environ.get(
            "DATABASE_URL",
            "postgresql+psycopg://erp:erp@localhost:5432/erp",
        )
    )
    if source_url.get_backend_name() != "postgresql" or source_url.host not in {
        "localhost",
        "127.0.0.1",
        "::1",
    }:
        pytest.skip("production seed proof is restricted to local PostgreSQL")

    database_name = f"{prefix}_{uuid4().hex[:16]}"
    source_url = source_url.set(drivername="postgresql+psycopg")
    test_url = source_url.set(database=database_name)
    admin_dsn = source_url.set(
        drivername="postgresql",
        database="postgres",
    ).render_as_string(hide_password=False)
    try:
        with psycopg.connect(admin_dsn, autocommit=True) as admin:
            admin.execute(sql.SQL("CREATE DATABASE {}").format(sql.Identifier(database_name)))
    except Exception as exc:
        pytest.skip(f"local role cannot create a disposable database: {exc}")

    try:
        yield test_url.render_as_string(hide_password=False)
    finally:
        try:
            with psycopg.connect(admin_dsn, autocommit=True) as admin:
                admin.execute(
                    "SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                    "WHERE datname = %s AND pid <> pg_backend_pid()",
                    (database_name,),
                )
                admin.execute(
                    sql.SQL("DROP DATABASE IF EXISTS {}").format(sql.Identifier(database_name))
                )
        except Exception:  # noqa: S110 - best-effort disposable test cleanup
            pass


def _production_env(database_url: str, *, email: str, password: str) -> dict[str, str]:
    return {
        **os.environ,
        "DATABASE_URL": database_url,
        "ENV": "prod",
        "JWT_SECRET": "j" * 48,
        "REMOTE_ASSISTANCE_PAIRING_SECRET": "p" * 48,
        "REMOTE_ASSISTANCE_RELAY_SECRET": base64.b64encode(b"r" * 32).decode("ascii"),
        "REDIS_URL": f"redis://erp_backend:{'d' * 64}@redis:6379/0",
        "S3_SECRET_KEY": "s" * 48,
        "SEED_OWNER_EMAIL": email,
        "SEED_OWNER_PASSWORD": password,
        "PUBLIC_URL": "https://erp.example.com",
        "CORS_ORIGINS": '["https://erp.example.com"]',
        "ANDROID_UPDATE_ALLOWED_ORIGIN": "https://erp.example.com",
    }


@pytest.mark.integration
def test_clean_production_seed_login_has_exact_protected_owner_access() -> None:
    email = "first-owner@erp.example.com"
    password = "freshOwnerCredential0123456789"
    with _disposable_database("erp_seed_acceptance") as database_url:
        env = _production_env(database_url, email=email, password=password)
        migrated = subprocess.run(  # noqa: S603 - fixed module and local test DSN
            [sys.executable, "-m", "alembic", "upgrade", "head"],
            cwd=_BACKEND_ROOT,
            env=env,
            capture_output=True,
            text=True,
            check=False,
        )
        assert migrated.returncode == 0, migrated.stderr

        seeded = subprocess.run(  # noqa: S603 - fixed repository module
            [sys.executable, "-m", "scripts.seed"],
            cwd=_BACKEND_ROOT,
            env=env,
            capture_output=True,
            text=True,
            check=False,
        )
        assert seeded.returncode == 0, seeded.stderr
        assert password not in seeded.stdout + seeded.stderr
        roles = subprocess.run(  # noqa: S603 - fixed repository module
            [sys.executable, "-m", "scripts.ensure_roles"],
            cwd=_BACKEND_ROOT,
            env=env,
            capture_output=True,
            text=True,
            check=False,
        )
        assert roles.returncode == 0, roles.stderr

        sync_url = (
            make_url(database_url)
            .set(drivername="postgresql")
            .render_as_string(hide_password=False)
        )
        auditor_email = "new-auditor@erp.example.com"
        auditor_password = "temporaryAuditorCredential012345"
        created = subprocess.run(  # noqa: S603 - fixed repository module
            [
                sys.executable,
                "-m",
                "scripts.create_user",
                "--email",
                auditor_email,
                "--name",
                "New Auditor",
                "--role",
                "auditor",
                "--password-stdin",
            ],
            cwd=_BACKEND_ROOT,
            env=env,
            input=f"{auditor_password}\n",
            capture_output=True,
            text=True,
            check=False,
        )
        assert created.returncode == 0, created.stdout + created.stderr
        assert auditor_password not in created.stdout + created.stderr
        with psycopg.connect(sync_url) as connection:
            auditor_id, auditor_hash = connection.execute(
                "SELECT id, password_hash FROM users WHERE email = %s",
                (auditor_email,),
            ).fetchone()
            auditor_roles = {
                row[0]
                for row in connection.execute(
                    "SELECT r.code FROM roles r "
                    "JOIN user_roles ur ON ur.role_id = r.id "
                    "WHERE ur.user_id = %s",
                    (auditor_id,),
                ).fetchall()
            }
            created_audit = connection.execute(
                "SELECT count(*) FROM audit_log WHERE entity_id = %s "
                "AND action = 'user_created_console'",
                (str(auditor_id),),
            ).fetchone()[0]
        assert auditor_roles == {"auditor"}
        assert created_audit == 1

        replacement_attempt = "mustNotOverwriteExistingUser12345"
        duplicate = subprocess.run(  # noqa: S603 - fixed repository module
            [
                sys.executable,
                "-m",
                "scripts.create_user",
                "--email",
                auditor_email,
                "--name",
                "Changed Name",
                "--role",
                "owner",
                "--password-stdin",
            ],
            cwd=_BACKEND_ROOT,
            env=env,
            input=f"{replacement_attempt}\n",
            capture_output=True,
            text=True,
            check=False,
        )
        assert duplicate.returncode != 0
        assert replacement_attempt not in duplicate.stdout + duplicate.stderr
        assert "already exists" in duplicate.stderr
        with psycopg.connect(sync_url) as connection:
            auditor_after = connection.execute(
                "SELECT name, password_hash FROM users WHERE id = %s",
                (auditor_id,),
            ).fetchone()
        assert auditor_after == ("New Auditor", auditor_hash)

        protected_attempt = "mustNotOverwriteProtectedOwner12345"
        protected = subprocess.run(  # noqa: S603 - fixed repository module
            [
                sys.executable,
                "-m",
                "scripts.create_user",
                "--email",
                email,
                "--name",
                "Changed Owner",
                "--role",
                "owner",
                "--password-stdin",
            ],
            cwd=_BACKEND_ROOT,
            env=env,
            input=f"{protected_attempt}\n",
            capture_output=True,
            text=True,
            check=False,
        )
        assert protected.returncode != 0
        assert protected_attempt not in protected.stdout + protected.stderr
        assert "protected super owner" in protected.stderr

        probe = r"""
import asyncio

from httpx import ASGITransport, AsyncClient

from app.api.v1.auth import router as auth_router
from app.main import create_app


async def no_login_rate_limit(*_args, **_kwargs):
    return None


async def main():
    auth_router.enforce_login_rate_limit = no_login_rate_limit
    async with AsyncClient(
        transport=ASGITransport(app=create_app(), client=("127.0.0.1", 40000)),
        base_url="http://installer.test",
    ) as client:
        login = await client.post(
            "/api/v1/auth/login",
            json={
                "email": "first-owner@erp.example.com",
                "password": "freshOwnerCredential0123456789",
            },
        )
        assert login.status_code == 200, login.text
        headers = {
            "Authorization": f"Bearer {login.json()['access_token']}",
        }
        me = await client.get("/api/v1/auth/me", headers=headers)
        assert me.status_code == 200, me.text
        identity = me.json()
        assert identity["email"] == "first-owner@erp.example.com"
        assert identity["roles"] == ["owner"]
        assert identity["protected_access"] is True
        assert identity["audit_access"] is True
        assert "admin.system" in identity["effective_permissions"]
        protected = await client.get("/api/v1/remote-assistance/devices", headers=headers)
        assert protected.status_code == 200, protected.text


asyncio.run(main())
"""
        accepted = subprocess.run(  # noqa: S603 - fixed interpreter and inline probe
            [sys.executable, "-c", probe],
            cwd=_BACKEND_ROOT,
            env=env,
            capture_output=True,
            text=True,
            check=False,
        )
        assert accepted.returncode == 0, accepted.stdout + accepted.stderr

        with psycopg.connect(sync_url) as connection:
            owner_id, initial_auth_version = connection.execute(
                "SELECT id, auth_version FROM users WHERE email = %s",
                (email,),
            ).fetchone()
            initial_roles = {
                row[0]
                for row in connection.execute(
                    "SELECT r.code FROM roles r "
                    "JOIN user_roles ur ON ur.role_id = r.id "
                    "WHERE ur.user_id = %s",
                    (owner_id,),
                ).fetchall()
            }
            active_refresh_before = connection.execute(
                "SELECT count(*) FROM auth_refresh_sessions "
                "WHERE user_id = %s AND revoked_at IS NULL",
                (owner_id,),
            ).fetchone()[0]
        assert initial_roles == {"super_owner"}
        assert active_refresh_before >= 1

        replacement_password = "replacementOwnerCredential987654321"
        reset = subprocess.run(  # noqa: S603 - fixed repository module
            [
                sys.executable,
                "-m",
                "scripts.reset_owner_password",
                "--password-stdin",
            ],
            cwd=_BACKEND_ROOT,
            env=env,
            input=f"{replacement_password}\n",
            capture_output=True,
            text=True,
            check=False,
        )
        assert reset.returncode == 0, reset.stdout + reset.stderr
        assert password not in reset.stdout + reset.stderr
        assert replacement_password not in reset.stdout + reset.stderr

        with psycopg.connect(sync_url) as connection:
            auth_version = connection.execute(
                "SELECT auth_version FROM users WHERE id = %s",
                (owner_id,),
            ).fetchone()[0]
            roles_after = {
                row[0]
                for row in connection.execute(
                    "SELECT r.code FROM roles r "
                    "JOIN user_roles ur ON ur.role_id = r.id "
                    "WHERE ur.user_id = %s",
                    (owner_id,),
                ).fetchall()
            }
            active_refresh_after = connection.execute(
                "SELECT count(*) FROM auth_refresh_sessions "
                "WHERE user_id = %s AND revoked_at IS NULL",
                (owner_id,),
            ).fetchone()[0]
            reset_audit_count = connection.execute(
                "SELECT count(*) FROM audit_log WHERE entity_id = %s "
                "AND action = 'protected_owner_password_reset_console'",
                (str(owner_id),),
            ).fetchone()[0]
        assert auth_version == initial_auth_version + 1
        assert roles_after == {"super_owner"}
        assert active_refresh_after == 0
        assert reset_audit_count == 1

        reset_probe = f'''\
import asyncio

from httpx import ASGITransport, AsyncClient

from app.api.v1.auth import router as auth_router
from app.main import create_app


async def no_login_rate_limit(*_args, **_kwargs):
    return None


async def main():
    auth_router.enforce_login_rate_limit = no_login_rate_limit
    async with AsyncClient(
        transport=ASGITransport(app=create_app(), client=("127.0.0.1", 40001)),
        base_url="http://reset.test",
    ) as client:
        old_login = await client.post(
            "/api/v1/auth/login",
            json={{"email": {email!r}, "password": {password!r}}},
        )
        assert old_login.status_code == 401, old_login.text
        new_login = await client.post(
            "/api/v1/auth/login",
            json={{"email": {email!r}, "password": {replacement_password!r}}},
        )
        assert new_login.status_code == 200, new_login.text


asyncio.run(main())
'''
        reset_accepted = subprocess.run(  # noqa: S603 - fixed interpreter and probe
            [sys.executable, "-c", reset_probe],
            cwd=_BACKEND_ROOT,
            env=env,
            capture_output=True,
            text=True,
            check=False,
        )
        assert reset_accepted.returncode == 0, reset_accepted.stdout + reset_accepted.stderr

        # A misconfigured/demoted account must never be silently elevated or
        # have its credential changed by the emergency tool.
        with psycopg.connect(sync_url) as connection:
            owner_role_id = connection.execute(
                "SELECT id FROM roles WHERE company_id = "
                "(SELECT company_id FROM users WHERE id = %s) AND code = 'owner'",
                (owner_id,),
            ).fetchone()[0]
            connection.execute("DELETE FROM user_roles WHERE user_id = %s", (owner_id,))
            connection.execute(
                "INSERT INTO user_roles (id, user_id, role_id) VALUES (%s, %s, %s)",
                (uuid4(), owner_id, owner_role_id),
            )
            protected_state_before = connection.execute(
                "SELECT password_hash, auth_version FROM users WHERE id = %s",
                (owner_id,),
            ).fetchone()
            connection.commit()

        refused_password = "mustNotReplaceProtectedOwnerCredential123"
        refused = subprocess.run(  # noqa: S603 - fixed repository module
            [
                sys.executable,
                "-m",
                "scripts.reset_owner_password",
                "--password-stdin",
            ],
            cwd=_BACKEND_ROOT,
            env=env,
            input=f"{refused_password}\n",
            capture_output=True,
            text=True,
            check=False,
        )
        assert refused.returncode != 0
        assert refused_password not in refused.stdout + refused.stderr
        assert "not the protected super owner" in refused.stderr
        with psycopg.connect(sync_url) as connection:
            protected_state_after = connection.execute(
                "SELECT password_hash, auth_version FROM users WHERE id = %s",
                (owner_id,),
            ).fetchone()
        assert protected_state_after == protected_state_before
