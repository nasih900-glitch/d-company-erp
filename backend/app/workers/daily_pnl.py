"""Compatibility wrapper for the daily P&L email worker.

The scheduled production job uses ``app.workers.pnl_alerts`` directly. This
module remains so older cron entries or manual commands still run the current
owner-only daily report path.
"""

from __future__ import annotations

import asyncio

from app.workers.pnl_alerts import send_reports

if __name__ == "__main__":
    asyncio.run(send_reports("daily"))
