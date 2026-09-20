"""Inert compatibility marker for the retired direct Google Sheets sink.

Google Sheets delivery is owned by ``google_sheets_mirror`` and its durable
database outbox.  This module intentionally contains no delivery functions,
network client, event handler, database access, or runtime registration.
The path remains present so upgrades and regression guards do not mistake the
retirement of the legacy implementation for an unreviewed source deletion.
"""

LEGACY_DIRECT_SINK_ENABLED = False

__all__ = ["LEGACY_DIRECT_SINK_ENABLED"]
