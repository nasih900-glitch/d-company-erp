"""Executable least-privilege proof for the production Redis ACL."""

from __future__ import annotations

import asyncio
import shutil
import socket
import subprocess
import time
from contextlib import closing
from pathlib import Path
from uuid import uuid4

import pytest
import redis
from redis.exceptions import AuthenticationError, NoPermissionError, RedisError

from app.core.config import Settings
from app.core.errors import BusinessRuleError, ServiceUnavailableError
from app.services.audit import snapshots
from app.services.remote_assistance import relay
from app.services.remote_assistance.relay import ValidatedJpeg

_PASSWORD = "0123456789abcdef" * 4


def _production_backend_acl() -> str:
    # Execute the actual production template, not a duplicated test allowlist.
    compose = Path(__file__).resolve().parents[3] / "docker-compose.prod.yml"
    lines = [
        line.strip().removesuffix("\\").strip()
        for line in compose.read_text().splitlines()
        if '"user erp_backend on ' in line
    ]
    assert len(lines) == 1
    quoted = lines[0]
    assert quoted.startswith('"')
    assert quoted.endswith('"')
    template = quoted[1:-1]
    assert template.startswith("user erp_backend on >$${REDIS_PASSWORD} ~dcompany:* -@all ")
    result = template.replace("$${REDIS_PASSWORD}", _PASSWORD)
    assert "$" not in result
    return result


def _free_port() -> int:
    with closing(socket.socket(socket.AF_INET, socket.SOCK_STREAM)) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


@pytest.mark.integration
def test_production_redis_acl_allows_app_ops_and_denies_admin_commands(
    tmp_path,
    monkeypatch,
) -> None:
    executable = shutil.which("redis-server")
    if executable is None:
        pytest.skip("redis-server is not installed")

    acl_path = tmp_path / "users.acl"
    acl_path.write_text(
        f"user default off\n{_production_backend_acl()}\n",
        encoding="ascii",
    )
    port = _free_port()
    process = subprocess.Popen(  # noqa: S603 - fixed local executable and test-only args
        [
            executable,
            "--bind",
            "127.0.0.1",
            "--port",
            str(port),
            "--save",
            "",
            "--appendonly",
            "no",
            "--aclfile",
            str(acl_path),
        ],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.PIPE,
        text=True,
    )
    client = redis.Redis(
        host="127.0.0.1",
        port=port,
        username="erp_backend",
        password=_PASSWORD,
        socket_connect_timeout=0.25,
        socket_timeout=0.5,
        decode_responses=True,
    )
    try:
        for _ in range(50):
            if process.poll() is not None:
                stderr = process.stderr.read() if process.stderr is not None else ""
                pytest.fail(f"redis-server exited before ACL test: {stderr}")
            try:
                if client.ping():
                    break
            except RedisError:
                time.sleep(0.02)
        else:
            pytest.fail("redis-server did not become ready")

        anonymous = redis.Redis(host="127.0.0.1", port=port, socket_timeout=0.5)
        with pytest.raises(AuthenticationError):
            anonymous.ping()

        count, ttl = client.eval(
            "local n=redis.call('INCR',KEYS[1]); "
            "redis.call('EXPIRE',KEYS[1],ARGV[1]); "
            "return {n,redis.call('TTL',KEYS[1])}",
            1,
            "dcompany:test:rate",
            30,
        )
        assert int(count) == 1
        assert int(ttl) > 0
        with client.pipeline(transaction=True) as pipeline:
            pipeline.set("dcompany:test:value", "ciphertext", ex=10)
            pipeline.hset("dcompany:test:metadata", mapping={"version": "1"})
            pipeline.get("dcompany:test:value")
            pipeline.hgetall("dcompany:test:metadata")
            results = pipeline.execute()
        assert results[-2:] == ["ciphertext", {"version": "1"}]

        redis_url = f"redis://erp_backend:{_PASSWORD}@127.0.0.1:{port}/0"
        relay_settings = Settings(
            redis_url=redis_url,
            remote_assistance_frame_ttl_seconds=2,
        )
        monkeypatch.setattr(relay, "get_settings", lambda: relay_settings)
        binary_client = redis.Redis(
            host="127.0.0.1",
            port=port,
            username="erp_backend",
            password=_PASSWORD,
            socket_timeout=0.5,
            decode_responses=False,
        )

        async def exercise_encrypted_frame_retention() -> None:
            company_id = uuid4()
            frame = ValidatedJpeg(
                content=b"\xff\xd8sanitized-frame\xff\xd9",
                width=640,
                height=480,
            )

            stale_session_id = uuid4()
            await relay.store_latest_frame(
                company_id=company_id,
                session_id=stale_session_id,
                frame_id=uuid4(),
                sequence=1,
                frame=frame,
            )
            stale_keys = relay._relay_keys(company_id, stale_session_id)
            stale_envelope = binary_client.get(stale_keys[2])
            stale_metadata = binary_client.hgetall(stale_keys[3])
            assert isinstance(stale_envelope, bytes)
            assert stale_metadata
            await asyncio.sleep(2.1)
            binary_client.set(stale_keys[2], stale_envelope, ex=60)
            binary_client.hset(stale_keys[3], mapping=stale_metadata)
            binary_client.expire(stale_keys[3], 60)
            assert (
                await relay.get_latest_frame(
                    company_id=company_id,
                    session_id=stale_session_id,
                )
                is None
            )
            assert binary_client.get(stale_keys[2]) is None
            assert binary_client.hgetall(stale_keys[3]) == {}
            assert binary_client.get(stale_keys[1]) == b"1"

            tampered_session_id = uuid4()
            await relay.store_latest_frame(
                company_id=company_id,
                session_id=tampered_session_id,
                frame_id=uuid4(),
                sequence=1,
                frame=frame,
            )
            tampered_keys = relay._relay_keys(company_id, tampered_session_id)
            envelope = binary_client.get(tampered_keys[2])
            assert isinstance(envelope, bytes)
            binary_client.set(
                tampered_keys[2],
                envelope[:-1] + bytes((envelope[-1] ^ 1,)),
                ex=60,
            )
            with pytest.raises(ServiceUnavailableError, match="integrity verification"):
                await relay.get_latest_frame(
                    company_id=company_id,
                    session_id=tampered_session_id,
                )
            assert binary_client.get(tampered_keys[2]) is None
            assert binary_client.hgetall(tampered_keys[3]) == {}
            assert binary_client.get(tampered_keys[1]) == b"1"

            metadata_session_id = uuid4()
            await relay.store_latest_frame(
                company_id=company_id,
                session_id=metadata_session_id,
                frame_id=uuid4(),
                sequence=1,
                frame=frame,
            )
            metadata_keys = relay._relay_keys(company_id, metadata_session_id)
            binary_client.hset(metadata_keys[3], "width", "641")
            with pytest.raises(ServiceUnavailableError, match="integrity verification"):
                await relay.get_latest_frame(
                    company_id=company_id,
                    session_id=metadata_session_id,
                )
            assert binary_client.get(metadata_keys[2]) is None
            assert binary_client.hgetall(metadata_keys[3]) == {}
            assert binary_client.get(metadata_keys[1]) == b"1"

            oversized_session_id = uuid4()
            await relay.store_latest_frame(
                company_id=company_id,
                session_id=oversized_session_id,
                frame_id=uuid4(),
                sequence=1,
                frame=frame,
            )
            oversized_keys = relay._relay_keys(company_id, oversized_session_id)
            envelope = binary_client.get(oversized_keys[2])
            assert isinstance(envelope, bytes)
            binary_client.set(
                oversized_keys[2],
                envelope + b"x" * relay_settings.remote_assistance_frame_max_bytes,
                ex=60,
            )
            with pytest.raises(ServiceUnavailableError, match="invalid encrypted envelope"):
                await relay.get_latest_frame(
                    company_id=company_id,
                    session_id=oversized_session_id,
                )
            assert binary_client.get(oversized_keys[2]) is None
            assert binary_client.hgetall(oversized_keys[3]) == {}
            assert binary_client.get(oversized_keys[1]) == b"1"

        try:
            asyncio.run(exercise_encrypted_frame_retention())
        finally:
            binary_client.close()

        monkeypatch.setattr(snapshots, "get_settings", lambda: relay_settings)
        monkeypatch.setattr(snapshots, "SNAPSHOT_TTL_SECONDS", 30)

        async def exercise_real_audit_snapshot_retention() -> None:
            company_id, user_id = uuid4(), uuid4()
            identities = []
            for number in range(snapshots.MAX_OWNER_SNAPSHOTS + 2):
                identity = await snapshots.save_snapshot(
                    company_id=company_id,
                    user_id=user_id,
                    fingerprint="synthetic-filter-fingerprint",
                    ids=[number + 1],
                )
                identities.append(identity)
                assert await snapshots.load_snapshot(
                    company_id=company_id,
                    user_id=user_id,
                    fingerprint="synthetic-filter-fingerprint",
                    snapshot_id=identity,
                ) == [number + 1]
            registry = snapshots._key(company_id, user_id, "recent")
            assert client.llen(registry) == snapshots.MAX_OWNER_SNAPSHOTS
            assert client.get(snapshots._key(company_id, user_id, identities[0])) is None
            with pytest.raises(BusinessRuleError, match="expired"):
                await snapshots.load_snapshot(
                    company_id=company_id,
                    user_id=user_id,
                    fingerprint="synthetic-filter-fingerprint",
                    snapshot_id=identities[0],
                )
            assert 0 < client.ttl(registry) <= 30
            monkeypatch.setattr(snapshots, "SNAPSHOT_TTL_SECONDS", 1)
            expiring_company, expiring_user = uuid4(), uuid4()
            expiring = await snapshots.save_snapshot(
                company_id=expiring_company,
                user_id=expiring_user,
                fingerprint="synthetic-filter-fingerprint",
                ids=[100],
            )
            await asyncio.sleep(1.1)
            assert client.get(snapshots._key(expiring_company, expiring_user, expiring)) is None
            assert client.llen(snapshots._key(expiring_company, expiring_user, "recent")) == 0
            with pytest.raises(BusinessRuleError, match="expired"):
                await snapshots.load_snapshot(
                    company_id=expiring_company,
                    user_id=expiring_user,
                    fingerprint="synthetic-filter-fingerprint",
                    snapshot_id=expiring,
                )

        asyncio.run(exercise_real_audit_snapshot_retention())

        with pytest.raises(NoPermissionError):
            client.set("outside:test", "denied")
        with pytest.raises(NoPermissionError):
            client.lpush("outside:audit", "denied")
        for command in (
            ("FLUSHALL",),
            ("CONFIG", "GET", "*"),
            ("SHUTDOWN", "NOSAVE"),
            ("ACL", "LIST"),
        ):
            with pytest.raises(NoPermissionError):
                client.execute_command(*command)
    finally:
        client.close()
        if process.poll() is None:
            process.terminate()
        try:
            process.wait(timeout=2)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=2)
