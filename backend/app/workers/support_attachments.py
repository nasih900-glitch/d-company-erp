"""Bounded daily retention for private Support and authentication data.

Run from cron inside the backend container:

    python -m app.workers.support_attachments
"""

from __future__ import annotations

import argparse
import asyncio

from app.core.db import AsyncSessionLocal
from app.services.auth.retention import purge_expired_refresh_sessions
from app.services.bug_reports.attachments import purge_expired_bug_report_attachments
from app.services.client_diagnostics import purge_expired_client_diagnostics

DEFAULT_REFRESH_SESSION_MAX_ROWS = 10_000


async def run(
    *,
    batch_size: int,
    max_rows: int,
    diagnostic_max_rows: int | None = None,
) -> tuple[int, int, int]:
    total_rows = 0
    total_bytes = 0
    while total_rows < max_rows:
        current_batch = min(batch_size, max_rows - total_rows)
        async with AsyncSessionLocal() as session, session.begin():
            result = await purge_expired_bug_report_attachments(
                session,
                batch_size=current_batch,
            )
        total_rows += result.rows
        total_bytes += result.bytes_released
        if result.rows < current_batch:
            break
    diagnostic_limit = max_rows if diagnostic_max_rows is None else diagnostic_max_rows
    diagnostic_rows = 0
    while diagnostic_rows < diagnostic_limit:
        current_batch = min(batch_size, diagnostic_limit - diagnostic_rows)
        async with AsyncSessionLocal() as session, session.begin():
            purged = await purge_expired_client_diagnostics(
                session,
                batch_size=current_batch,
            )
        diagnostic_rows += purged
        if purged < current_batch:
            break
    return total_rows, total_bytes, diagnostic_rows


async def run_all(
    *,
    batch_size: int,
    max_rows: int,
    diagnostic_max_rows: int | None = None,
    refresh_session_max_rows: int = DEFAULT_REFRESH_SESSION_MAX_ROWS,
) -> tuple[int, int, int, int]:
    """Run every retention class with an independent bounded work allowance."""

    legacy_result: tuple[int, int, int] | None = None
    legacy_error: Exception | None = None
    legacy_traceback = None
    try:
        legacy_result = await run(
            batch_size=batch_size,
            max_rows=max_rows,
            diagnostic_max_rows=diagnostic_max_rows,
        )
    except Exception as exc:  # preserve the original maintenance failure
        legacy_error = exc
        legacy_traceback = exc.__traceback__

    refresh_sessions = 0
    refresh_error: Exception | None = None
    refresh_traceback = None
    try:
        while refresh_sessions < refresh_session_max_rows:
            current_batch = min(
                batch_size,
                refresh_session_max_rows - refresh_sessions,
            )
            async with AsyncSessionLocal() as session, session.begin():
                purged = await purge_expired_refresh_sessions(
                    session,
                    batch_size=current_batch,
                )
            refresh_sessions += purged
            if purged < current_batch:
                break
    except Exception as exc:
        refresh_error = exc
        refresh_traceback = exc.__traceback__

    if legacy_error is not None and refresh_error is not None:
        raise ExceptionGroup(
            "Support and refresh-session retention both failed",
            [
                legacy_error.with_traceback(legacy_traceback),
                refresh_error.with_traceback(refresh_traceback),
            ],
        )
    if legacy_error is not None:
        raise legacy_error.with_traceback(legacy_traceback)
    if refresh_error is not None:
        raise refresh_error.with_traceback(refresh_traceback)
    if legacy_result is None:  # pragma: no cover - defensive exhaustiveness
        raise RuntimeError("legacy retention returned no result")
    screenshots, released, diagnostics = legacy_result
    return screenshots, released, diagnostics, refresh_sessions


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Purge expired private Support, diagnostic, and auth-session data"
    )
    parser.add_argument("--batch-size", type=int, default=100)
    parser.add_argument("--max-rows", type=int, default=1_000)
    parser.add_argument(
        "--diagnostic-max-rows",
        type=int,
        default=50_000,
        help="maximum expired diagnostic rows to purge independently of screenshots",
    )
    parser.add_argument(
        "--refresh-session-max-rows",
        type=int,
        default=DEFAULT_REFRESH_SESSION_MAX_ROWS,
        help="maximum expired refresh-session rows to purge independently",
    )
    args = parser.parse_args()
    if not 1 <= args.batch_size <= 1_000:
        parser.error("--batch-size must be between 1 and 1000")
    if not 1 <= args.max_rows <= 100_000:
        parser.error("--max-rows must be between 1 and 100000")
    if not 1 <= args.diagnostic_max_rows <= 100_000:
        parser.error("--diagnostic-max-rows must be between 1 and 100000")
    if not 1 <= args.refresh_session_max_rows <= 100_000:
        parser.error("--refresh-session-max-rows must be between 1 and 100000")
    rows, released, diagnostics, refresh_sessions = asyncio.run(
        run_all(
            batch_size=args.batch_size,
            max_rows=args.max_rows,
            diagnostic_max_rows=args.diagnostic_max_rows,
            refresh_session_max_rows=args.refresh_session_max_rows,
        )
    )
    print(
        f"Purged {rows} expired Support screenshot(s), released {released} byte(s), "
        f"purged {diagnostics} expired client diagnostic event(s), and purged "
        f"{refresh_sessions} expired refresh session(s)."
    )


if __name__ == "__main__":
    main()
