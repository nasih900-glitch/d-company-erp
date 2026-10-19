"""Explicit Core fixture insertion into a pinned historical schema."""

from __future__ import annotations

from types import SimpleNamespace

from sqlalchemy import MetaData, Table


def insert_historical(session, table_name: str, values: dict) -> SimpleNamespace:
    """Reject unknown supplied facts; never invoke current ORM defaults/listeners."""
    metadata = session.info.setdefault("historical_seed_metadata", MetaData())
    table = metadata.tables.get(table_name)
    if table is None:
        table = Table(table_name, metadata, autoload_with=session.connection(), resolve_fks=False)
    unknown = set(values) - set(table.columns.keys())
    assert not unknown, f"fixture supplied columns absent at this migration: {sorted(unknown)}"
    primary_keys = list(table.primary_key.columns)
    result = session.execute(table.insert().values(**values).returning(*primary_keys)).one()
    supplied = dict(values)
    supplied.update({key.name: value for key, value in zip(primary_keys, result, strict=True)})
    return SimpleNamespace(**supplied)
