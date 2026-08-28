"""Bounded retention job for private Support screenshot bytes.

Run from cron inside the backend container:

    python -m app.workers.support_attachments
"""

from __future__ import annotations

import argparse
import asyncio

from app.core.db import AsyncSessionLocal
from app.services.bug_reports.attachments import purge_expired_bug_report_attachments


async def run(*, batch_size: int, max_rows: int) -> tuple[int, int]:
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
    return total_rows, total_bytes


def main() -> None:
    parser = argparse.ArgumentParser(description="Purge expired private Support screenshots")
    parser.add_argument("--batch-size", type=int, default=100)
    parser.add_argument("--max-rows", type=int, default=1_000)
    args = parser.parse_args()
    if not 1 <= args.batch_size <= 1_000:
        parser.error("--batch-size must be between 1 and 1000")
    if not 1 <= args.max_rows <= 100_000:
        parser.error("--max-rows must be between 1 and 100000")
    rows, released = asyncio.run(run(batch_size=args.batch_size, max_rows=args.max_rows))
    print(f"Purged {rows} expired Support screenshot(s); released {released} byte(s).")


if __name__ == "__main__":
    main()
