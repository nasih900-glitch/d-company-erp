"""Bounded daily retention for private Support bytes and client diagnostics.

Run from cron inside the backend container:

    python -m app.workers.support_attachments
"""

from __future__ import annotations

import argparse
import asyncio

from app.core.db import AsyncSessionLocal
from app.services.bug_reports.attachments import purge_expired_bug_report_attachments
from app.services.client_diagnostics import purge_expired_client_diagnostics


async def run(*, batch_size: int, max_rows: int) -> tuple[int, int, int]:
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
    diagnostic_rows = 0
    while diagnostic_rows < max_rows:
        current_batch = min(batch_size, max_rows - diagnostic_rows)
        async with AsyncSessionLocal() as session, session.begin():
            purged = await purge_expired_client_diagnostics(
                session,
                batch_size=current_batch,
            )
        diagnostic_rows += purged
        if purged < current_batch:
            break
    return total_rows, total_bytes, diagnostic_rows


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Purge expired private Support bytes and client diagnostics"
    )
    parser.add_argument("--batch-size", type=int, default=100)
    parser.add_argument("--max-rows", type=int, default=1_000)
    args = parser.parse_args()
    if not 1 <= args.batch_size <= 1_000:
        parser.error("--batch-size must be between 1 and 1000")
    if not 1 <= args.max_rows <= 100_000:
        parser.error("--max-rows must be between 1 and 100000")
    rows, released, diagnostics = asyncio.run(
        run(batch_size=args.batch_size, max_rows=args.max_rows)
    )
    print(
        f"Purged {rows} expired Support screenshot(s), released {released} byte(s), "
        f"and purged {diagnostics} expired client diagnostic event(s)."
    )


if __name__ == "__main__":
    main()
