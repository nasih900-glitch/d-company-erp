from __future__ import annotations

import pytest

from app.workers import support_attachments as worker


class _AsyncContext:
    async def __aenter__(self):
        return self

    async def __aexit__(self, _exc_type, _exc, _traceback) -> None:
        return None


class _Session(_AsyncContext):
    def begin(self) -> _AsyncContext:
        return _AsyncContext()


@pytest.mark.asyncio
async def test_run_all_keeps_legacy_result_and_applies_independent_auth_limit(
    monkeypatch,
) -> None:
    refresh_batches: list[int] = []

    async def run_legacy(**kwargs) -> tuple[int, int, int]:
        assert kwargs == {
            "batch_size": 2,
            "max_rows": 1,
            "diagnostic_max_rows": 5,
        }
        return 1, 512, 5

    async def purge_refresh_sessions(_session, *, batch_size: int) -> int:
        refresh_batches.append(batch_size)
        return batch_size

    monkeypatch.setattr(worker, "AsyncSessionLocal", _Session)
    monkeypatch.setattr(worker, "run", run_legacy)
    monkeypatch.setattr(
        worker,
        "purge_expired_refresh_sessions",
        purge_refresh_sessions,
    )

    result = await worker.run_all(
        batch_size=2,
        max_rows=1,
        diagnostic_max_rows=5,
        refresh_session_max_rows=5,
    )

    assert result == (1, 512, 5, 5)
    assert refresh_batches == [2, 2, 1]


@pytest.mark.asyncio
async def test_run_all_stops_auth_batches_after_a_short_batch(monkeypatch) -> None:
    refresh_batches: list[int] = []

    async def run_legacy(**_kwargs) -> tuple[int, int, int]:
        return 0, 0, 0

    async def purge_refresh_sessions(_session, *, batch_size: int) -> int:
        refresh_batches.append(batch_size)
        return 1

    monkeypatch.setattr(worker, "AsyncSessionLocal", _Session)
    monkeypatch.setattr(worker, "run", run_legacy)
    monkeypatch.setattr(
        worker,
        "purge_expired_refresh_sessions",
        purge_refresh_sessions,
    )

    assert await worker.run_all(
        batch_size=3,
        max_rows=1,
        refresh_session_max_rows=10,
    ) == (0, 0, 0, 1)
    assert refresh_batches == [3]


@pytest.mark.asyncio
async def test_run_all_attempts_auth_cleanup_after_legacy_failure(monkeypatch) -> None:
    auth_attempted = False

    async def run_legacy(**_kwargs) -> tuple[int, int, int]:
        raise RuntimeError("support cleanup failed")

    async def purge_refresh_sessions(_session, *, batch_size: int) -> int:
        nonlocal auth_attempted
        auth_attempted = True
        assert batch_size == 2
        return 0

    monkeypatch.setattr(worker, "AsyncSessionLocal", _Session)
    monkeypatch.setattr(worker, "run", run_legacy)
    monkeypatch.setattr(
        worker,
        "purge_expired_refresh_sessions",
        purge_refresh_sessions,
    )

    with pytest.raises(RuntimeError, match="support cleanup failed"):
        await worker.run_all(
            batch_size=2,
            max_rows=1,
            refresh_session_max_rows=2,
        )
    assert auth_attempted


@pytest.mark.asyncio
async def test_run_all_preserves_both_independent_failures(monkeypatch) -> None:
    async def run_legacy(**_kwargs) -> tuple[int, int, int]:
        raise RuntimeError("support cleanup failed")

    async def purge_refresh_sessions(_session, *, batch_size: int) -> int:
        assert batch_size == 2
        raise ValueError("refresh cleanup failed")

    monkeypatch.setattr(worker, "AsyncSessionLocal", _Session)
    monkeypatch.setattr(worker, "run", run_legacy)
    monkeypatch.setattr(
        worker,
        "purge_expired_refresh_sessions",
        purge_refresh_sessions,
    )

    with pytest.raises(ExceptionGroup) as captured:
        await worker.run_all(
            batch_size=2,
            max_rows=1,
            refresh_session_max_rows=2,
        )

    assert [str(error) for error in captured.value.exceptions] == [
        "support cleanup failed",
        "refresh cleanup failed",
    ]
