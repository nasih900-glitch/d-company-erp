"""Safety contracts for the destructive disposable-database E2E runner."""

import asyncio
from datetime import UTC, datetime

import pytest

from scripts import live_e2e_guarded as guarded
from scripts.live_e2e_guarded import (
    CONFIRMATION,
    FULL_RESET_CONFIRMATION,
    FULL_RESET_ENV,
    E2EError,
    _build_full_reset_sql,
    _business_report_periods,
    _full_database_reset_requested,
    _is_loopback_host,
    _truncate_public_ordinary_tables,
)


def test_loopback_guard_accepts_url_and_postgres_address_forms() -> None:
    assert _is_loopback_host("localhost")
    assert _is_loopback_host("127.0.0.1")
    assert _is_loopback_host("::1")
    assert _is_loopback_host("127.0.0.1/32")
    assert _is_loopback_host("::1/128")


def test_loopback_guard_rejects_remote_and_malformed_hosts() -> None:
    assert not _is_loopback_host(None)
    assert not _is_loopback_host("")
    assert not _is_loopback_host("example.com")
    assert not _is_loopback_host("192.0.2.10")
    assert not _is_loopback_host("192.0.2.10/32")
    assert not _is_loopback_host("127.0.0.1/not-a-prefix")


def test_destructive_guard_requires_loopback_exact_confirmation_and_prefix(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(guarded, "BASE_URL", "http://127.0.0.1:8001/api/v1")
    monkeypatch.setattr(guarded, "DISPOSABLE_CONFIRMATION", CONFIRMATION)
    monkeypatch.setattr(guarded, "EXPECTED_DATABASE", "dcompany_e2e_code25")
    guarded._validate_destructive_confirmation()

    monkeypatch.setattr(guarded, "BASE_URL", "https://example.com/api/v1")
    with pytest.raises(E2EError, match="loopback"):
        guarded._validate_destructive_confirmation()

    monkeypatch.setattr(guarded, "BASE_URL", "http://127.0.0.1:8001/api/v1")
    monkeypatch.setattr(guarded, "DISPOSABLE_CONFIRMATION", "almost")
    with pytest.raises(E2EError, match="exact"):
        guarded._validate_destructive_confirmation()

    monkeypatch.setattr(guarded, "DISPOSABLE_CONFIRMATION", CONFIRMATION)
    monkeypatch.setattr(guarded, "EXPECTED_DATABASE", "production")
    with pytest.raises(E2EError, match="allowed disposable prefix"):
        guarded._validate_destructive_confirmation()


def test_full_reset_requires_a_second_exact_opt_in(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv(FULL_RESET_ENV, raising=False)
    assert not _full_database_reset_requested()

    monkeypatch.setenv(FULL_RESET_ENV, " ")
    assert not _full_database_reset_requested()

    monkeypatch.setenv(FULL_RESET_ENV, "yes")
    with pytest.raises(E2EError, match="exact full-reset"):
        _full_database_reset_requested()

    monkeypatch.setenv(FULL_RESET_ENV, FULL_RESET_CONFIRMATION)
    assert _full_database_reset_requested()


def test_full_reset_sql_quotes_identifiers_and_preserves_alembic_version() -> None:
    assert _build_full_reset_sql([]) is None
    assert _build_full_reset_sql(["orders", "branches", "orders"]) == (
        'TRUNCATE TABLE "public"."branches", "public"."orders" '
        "RESTART IDENTITY CASCADE"
    )
    assert _build_full_reset_sql(['odd"table']) == (
        'TRUNCATE TABLE "public"."odd""table" RESTART IDENTITY CASCADE'
    )
    with pytest.raises(E2EError, match="alembic_version"):
        _build_full_reset_sql(["orders", "alembic_version"])


class _FakeScalars:
    def __init__(self, values: list[str]) -> None:
        self.values = values

    def __iter__(self):  # type: ignore[no-untyped-def]
        return iter(self.values)


class _FakeResult:
    def __init__(self, values: list[str]) -> None:
        self.values = values

    def scalars(self) -> _FakeScalars:
        return _FakeScalars(self.values)


class _FakeSession:
    def __init__(self) -> None:
        self.statements: list[str] = []

    async def execute(self, statement):  # type: ignore[no-untyped-def]
        rendered = str(statement)
        self.statements.append(rendered)
        return _FakeResult(["orders", "branches"])


def test_public_table_enumeration_drives_one_atomic_truncate() -> None:
    session = _FakeSession()

    table_names = asyncio.run(_truncate_public_ordinary_tables(session))

    assert table_names == ["orders", "branches"]
    assert "information_schema.tables" in session.statements[0]
    assert "table_schema = 'public'" in session.statements[0]
    assert "table_type = 'BASE TABLE'" in session.statements[0]
    assert "table_name <> 'alembic_version'" in session.statements[0]
    assert session.statements[1] == (
        'TRUNCATE TABLE "public"."branches", "public"."orders" '
        "RESTART IDENTITY CASCADE"
    )


def test_report_periods_use_business_timezone_across_utc_day_boundary() -> None:
    periods = _business_report_periods(
        {"timezone": "Asia/Kolkata", "fiscal_year_start_month": 4},
        now_utc=datetime(2026, 9, 5, 22, 28, tzinfo=UTC),
    )

    assert periods == {
        "today": "2026-09-06",
        "yyyy_mm": "2026-09",
        "fiscal_year": "2026-27",
        "fiscal_quarter": 2,
    }


def test_report_periods_use_local_fiscal_boundary() -> None:
    periods = _business_report_periods(
        {"timezone": "Asia/Kolkata", "fiscal_year_start_month": 4},
        now_utc=datetime(2026, 3, 31, 20, 0, tzinfo=UTC),
    )

    assert periods["today"] == "2026-04-01"
    assert periods["yyyy_mm"] == "2026-04"
    assert periods["fiscal_year"] == "2026-27"
    assert periods["fiscal_quarter"] == 1


def test_report_periods_fail_closed_for_invalid_configuration() -> None:
    with pytest.raises(E2EError, match="invalid business timezone"):
        _business_report_periods({"timezone": "not/a-zone"})
    with pytest.raises(E2EError, match="invalid fiscal year start"):
        _business_report_periods(
            {"timezone": "UTC", "fiscal_year_start_month": 13}
        )
