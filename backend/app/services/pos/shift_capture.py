"""Validate and durably identify captured shift openings, never rebase clocks."""

from dataclasses import dataclass
from datetime import datetime, timezone
from hashlib import sha256
from fastapi import Request
from app.core.errors import BusinessRuleError


@dataclass(frozen=True)
class OpeningCapture:
    key: str | None
    request_hash: str | None
    opened_at: datetime
    received_at: datetime
    offline: bool

    def require_fresh(self) -> None:
        """Reject impossible future clocks, not legitimate long outages.

        A captured shift is a durable financial parent for queued sessions and
        sales.  Expiring it after an arbitrary number of hours permanently
        strands those dependent records.  The open-shift route separately
        rejects an existing open shift, overlap with a closed shift, scope
        mismatch, or changed action identity, so age alone is neither a useful
        integrity check nor a safe recovery policy.
        """
        if self.opened_at > self.received_at:
            raise BusinessRuleError(
                "Saved shift opening time is in the future. Correct the tablet clock and ask an owner to review the saved shift; nothing was discarded."
            )


def read_opening_capture(request: Request | None, now: datetime) -> OpeningCapture:
    if request is None:
        return OpeningCapture(None, None, now, now, False)
    key = getattr(request.state, "idempotency_key", None)
    body_hash = getattr(request.state, "idempotency_request_hash", None)
    offline = request.headers.get("X-Offline-Captured", "").strip().lower() in {"1", "true", "yes"}
    opened = now
    if offline:
        if not key or not body_hash or request.headers.get("X-Client-Action-Id", "").strip() != key:
            raise BusinessRuleError(
                "Saved shift opening requires a matching durable action identity and Idempotency-Key. Reconnect and retry from the original saved shift."
            )
        raw = request.headers.get("X-Client-Occurred-At", "").strip()
        try:
            opened = datetime.fromisoformat(raw.replace("Z", "+00:00"))
            if opened.tzinfo is None:
                raise ValueError("timezone required")
            opened = opened.astimezone(timezone.utc)
        except (ValueError, TypeError) as exc:
            raise BusinessRuleError(
                "Saved shift opening needs a valid captured time including timezone. The saved action is unchanged; ask an owner to review it."
            ) from exc
    # Include the header-only timestamp in identity: otherwise the same JSON
    # and key could silently change the accountable opening time on retry.
    bound_hash = (
        sha256(
            f"{body_hash}|{offline}|{opened.isoformat() if offline else 'server'}".encode()
        ).hexdigest()
        if key and body_hash
        else None
    )
    return OpeningCapture(key, bound_hash, opened, now, offline)
