from __future__ import annotations

from types import SimpleNamespace

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
async def test_diagnostic_retention_limit_is_independent_of_screenshot_limit(
    monkeypatch,
) -> None:
    diagnostic_batches: list[int] = []

    async def purge_screenshots(_session, *, batch_size: int):
        assert batch_size == 1
        return SimpleNamespace(rows=0, bytes_released=0)

    async def purge_diagnostics(_session, *, batch_size: int) -> int:
        diagnostic_batches.append(batch_size)
        return batch_size

    monkeypatch.setattr(worker, "AsyncSessionLocal", _Session)
    monkeypatch.setattr(
        worker,
        "purge_expired_bug_report_attachments",
        purge_screenshots,
    )
    monkeypatch.setattr(
        worker,
        "purge_expired_client_diagnostics",
        purge_diagnostics,
    )

    screenshots, released, diagnostics = await worker.run(
        batch_size=2,
        max_rows=1,
        diagnostic_max_rows=5,
    )

    assert (screenshots, released, diagnostics) == (0, 0, 5)
    assert diagnostic_batches == [2, 2, 1]
