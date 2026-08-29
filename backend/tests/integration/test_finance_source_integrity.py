"""PostgreSQL proofs for immutable finance sources and migration 0050."""

from __future__ import annotations

from datetime import UTC, datetime
from uuid import UUID, uuid4
from zoneinfo import ZoneInfo

import psycopg
import pytest
from psycopg import errors
from sqlalchemy import select

from app.models import Branch, CapitalEntry, ManualCollection, Partner, Role, UserRole
from tests.integration.test_cafe_workflow_migration import (
    _disposable_database,
    _run_alembic,
)
from tests.integration.test_inventory_purchase_accounting_migration import (
    _seed_legacy_grn,
)


def _sync_dsn(database_url: str) -> str:
    return database_url.replace("postgresql+psycopg://", "postgresql://", 1)


def _seed_company_branch_user(
    connection: psycopg.Connection,
    *,
    label: str,
    invoice_series: str,
) -> dict[str, UUID]:
    company_id = uuid4()
    branch_id = uuid4()
    user_id = uuid4()
    connection.execute(
        "INSERT INTO companies (id, name) VALUES (%s, %s)",
        (company_id, f"Finance {label}"),
    )
    connection.execute(
        "INSERT INTO branches "
        "(id, company_id, name, code, invoice_series_code) "
        "VALUES (%s, %s, %s, %s, %s)",
        (
            branch_id,
            company_id,
            f"Branch {label}",
            f"B{label[:3]}",
            invoice_series,
        ),
    )
    connection.execute(
        "INSERT INTO users (id, company_id, email, password_hash, name) "
        "VALUES (%s, %s, %s, 'not-a-real-password', %s)",
        (user_id, company_id, f"{label.lower()}-{uuid4()}@example.invalid", label),
    )
    return {"company": company_id, "branch": branch_id, "user": user_id}


@pytest.mark.integration
def test_0050_freezes_finance_sources_and_refuses_lossy_downgrade() -> None:
    with _disposable_database("erp_finance_source_integrity") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0050")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr

        with psycopg.connect(_sync_dsn(database_url)) as connection:
            ids = _seed_company_branch_user(
                connection,
                label="Source50",
                invoice_series="S5",
            )
            category_id = uuid4()
            expense_id = uuid4()
            asset_id = uuid4()
            partner_id = uuid4()
            account_id = uuid4()
            journal_id = uuid4()
            debit_line_id = uuid4()
            credit_line_id = uuid4()
            now = datetime.now(UTC)

            connection.execute(
                "INSERT INTO expense_categories (id, company_id, name, code) "
                "VALUES (%s, %s, 'Utilities 0050', '5300')",
                (category_id, ids["company"]),
            )
            connection.execute(
                "INSERT INTO expenses "
                "(id, company_id, branch_id, category_id, amount_minor, paid_via, "
                "paid_at, vendor_name) VALUES (%s, %s, %s, %s, 2500, 'cash', "
                "%s, 'Power supplier')",
                (
                    expense_id,
                    ids["company"],
                    ids["branch"],
                    category_id,
                    now,
                ),
            )
            connection.execute(
                "INSERT INTO assets "
                "(id, company_id, branch_id, name, type, purchase_minor, "
                "purchase_date, depreciation_method, useful_life_months, "
                "salvage_minor) VALUES (%s, %s, %s, 'Coffee machine', "
                "'coffee_machine', 125000, %s, 'straight_line', 60, 5000)",
                (asset_id, ids["company"], ids["branch"], now),
            )
            connection.execute(
                "INSERT INTO partners "
                "(id, company_id, name, share_pct, joined_at, notes) "
                "VALUES (%s, %s, 'Source owner', 100, %s, 'Original agreement')",
                (partner_id, ids["company"], now),
            )
            connection.execute(
                "INSERT INTO accounts "
                "(id, company_id, code, name, type, normal_side, is_active) "
                "VALUES (%s, %s, '9950', '0050 Test Asset', 'asset', 'dr', true)",
                (account_id, ids["company"]),
            )
            connection.execute(
                "INSERT INTO journal_entries "
                "(id, company_id, branch_id, ref_type, ref_id, posted_at, "
                "total_minor) VALUES (%s, %s, %s, 'historical_setup_reconciliation', "
                "%s, %s, 100)",
                (journal_id, ids["company"], ids["branch"], uuid4(), now),
            )
            connection.execute(
                "INSERT INTO journal_lines "
                "(id, journal_entry_id, account_id, side, amount_minor) VALUES "
                "(%s, %s, %s, 'dr', 100), (%s, %s, %s, 'cr', 100)",
                (
                    debit_line_id,
                    journal_id,
                    account_id,
                    credit_line_id,
                    journal_id,
                    account_id,
                ),
            )
            connection.commit()

            revisions = connection.execute(
                "SELECT "
                "(SELECT source_integrity_revision FROM expenses WHERE id=%s), "
                "(SELECT source_integrity_revision FROM assets WHERE id=%s), "
                "(SELECT source_integrity_revision FROM partners WHERE id=%s)",
                (expense_id, asset_id, partner_id),
            ).fetchone()
            assert revisions == (50, 50, 50)

            with pytest.raises(errors.RaiseException, match="expense financial"):
                connection.execute(
                    "UPDATE expenses SET amount_minor=2600 WHERE id=%s",
                    (expense_id,),
                )
            connection.rollback()
            with pytest.raises(errors.RaiseException, match="void the expense"):
                connection.execute("DELETE FROM expenses WHERE id=%s", (expense_id,))
            connection.rollback()

            connection.execute(
                "UPDATE expenses SET voided_at=%s, voided_by=%s, "
                "void_reason='Duplicate supplier invoice' WHERE id=%s",
                (now, ids["user"], expense_id),
            )
            connection.commit()
            assert connection.execute(
                "SELECT void_reason, source_integrity_revision FROM expenses WHERE id=%s",
                (expense_id,),
            ).fetchone() == ("Duplicate supplier invoice", 50)
            with pytest.raises(errors.RaiseException, match="cannot be changed"):
                connection.execute(
                    "UPDATE expenses SET void_reason='Changed later' WHERE id=%s",
                    (expense_id,),
                )
            connection.rollback()

            with pytest.raises(errors.RaiseException, match="asset financial"):
                connection.execute(
                    "UPDATE assets SET purchase_minor=1 WHERE id=%s", (asset_id,)
                )
            connection.rollback()
            with pytest.raises(errors.RaiseException, match="cannot be deleted"):
                connection.execute("DELETE FROM assets WHERE id=%s", (asset_id,))
            connection.rollback()
            connection.execute(
                "UPDATE partners SET notes='Clarifying note only' WHERE id=%s",
                (partner_id,),
            )
            connection.commit()
            with pytest.raises(errors.RaiseException, match="owner reconciliation"):
                connection.execute(
                    "UPDATE partners SET share_pct=99 WHERE id=%s", (partner_id,)
                )
            connection.rollback()
            with pytest.raises(errors.RaiseException, match="cannot be deleted"):
                connection.execute("DELETE FROM partners WHERE id=%s", (partner_id,))
            connection.rollback()
            with pytest.raises(errors.RaiseException, match="cannot exceed 100"):
                connection.execute(
                    "INSERT INTO partners "
                    "(id, company_id, name, share_pct, joined_at) "
                    "VALUES (%s, %s, 'Overflow owner', 1, %s)",
                    (uuid4(), ids["company"], now),
                )
            connection.rollback()
            with pytest.raises(errors.RaiseException, match="category accounting"):
                connection.execute(
                    "UPDATE expense_categories SET name='Renamed' WHERE id=%s",
                    (category_id,),
                )
            connection.rollback()
            with pytest.raises(errors.RaiseException, match="account financial identity"):
                connection.execute(
                    "UPDATE accounts SET code='9951' WHERE id=%s", (account_id,)
                )
            connection.rollback()
            with pytest.raises(errors.RaiseException, match="journal lines are immutable"):
                connection.execute(
                    "UPDATE journal_lines SET amount_minor=99 WHERE id=%s",
                    (debit_line_id,),
                )
            connection.rollback()

            foreign = _seed_company_branch_user(
                connection,
                label="ForeignRuntime50",
                invoice_series="FR",
            )
            connection.commit()
            with pytest.raises(errors.RaiseException, match="invalid tenant"):
                connection.execute(
                    "INSERT INTO manual_collections "
                    "(id, company_id, branch_id, business_date, method, "
                    "amount_minor, source_kind, source_ref, idempotency_key, "
                    "created_by) VALUES "
                    "(%s, %s, %s, %s, 'cash', 100, 'manual_daily', %s, %s, %s)",
                    (
                        uuid4(),
                        ids["company"],
                        ids["branch"],
                        now.date(),
                        str(uuid4()),
                        str(uuid4()),
                        foreign["user"],
                    ),
                )
            connection.rollback()

            corrupt_journal_id = uuid4()
            connection.execute(
                "INSERT INTO journal_entries "
                "(id, company_id, branch_id, ref_type, ref_id, posted_at, "
                "total_minor) VALUES (%s, %s, %s, 'native_corruption', %s, %s, 100)",
                (
                    corrupt_journal_id,
                    ids["company"],
                    ids["branch"],
                    uuid4(),
                    now,
                ),
            )
            connection.execute(
                "INSERT INTO journal_lines "
                "(id, journal_entry_id, account_id, side, amount_minor) VALUES "
                "(%s, %s, %s, 'dr', 100), (%s, %s, %s, 'cr', 99)",
                (
                    uuid4(),
                    corrupt_journal_id,
                    account_id,
                    uuid4(),
                    corrupt_journal_id,
                    account_id,
                ),
            )
            with pytest.raises(errors.RaiseException, match="posted journal"):
                connection.commit()
            connection.rollback()

        blocked = _run_alembic(database_url, "downgrade", "0049")
        output = blocked.stdout + blocked.stderr
        assert blocked.returncode != 0
        assert "forward finance source activity" in output
        current = _run_alembic(database_url, "current")
        assert "0050" in current.stdout


@pytest.mark.integration
def test_0050_preflight_rejects_cross_company_expense_scope() -> None:
    with _disposable_database("erp_finance_source_preflight") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0049")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        with psycopg.connect(_sync_dsn(database_url)) as connection:
            local = _seed_company_branch_user(
                connection,
                label="Local50",
                invoice_series="L5",
            )
            foreign = _seed_company_branch_user(
                connection,
                label="Foreign50",
                invoice_series="F5",
            )
            foreign_category = uuid4()
            bad_expense = uuid4()
            connection.execute(
                "INSERT INTO expense_categories (id, company_id, name, code) "
                "VALUES (%s, %s, 'Foreign utilities', '5300')",
                (foreign_category, foreign["company"]),
            )
            connection.execute(
                "INSERT INTO expenses "
                "(id, company_id, branch_id, category_id, amount_minor, paid_via, "
                "paid_at) VALUES (%s, %s, %s, %s, 100, 'cash', %s)",
                (
                    bad_expense,
                    local["company"],
                    local["branch"],
                    foreign_category,
                    datetime.now(UTC),
                ),
            )
            connection.commit()

        blocked = _run_alembic(database_url, "upgrade", "0050")
        output = blocked.stdout + blocked.stderr
        assert blocked.returncode != 0
        assert "invalid amount, rail, tenant/reference scope" in output
        current = _run_alembic(database_url, "current")
        assert "0049" in current.stdout


@pytest.mark.integration
@pytest.mark.parametrize("source_kind", ["expense", "asset"])
def test_0050_preflight_rejects_unaudited_soft_deleted_finance_sources(
    source_kind: str,
) -> None:
    with _disposable_database(
        f"erp_finance_soft_deleted_{source_kind}"
    ) as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0049")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        with psycopg.connect(_sync_dsn(database_url)) as connection:
            ids = _seed_company_branch_user(
                connection,
                label=f"Soft{source_kind}",
                invoice_series="SD",
            )
            now = datetime.now(UTC)
            if source_kind == "expense":
                category_id = uuid4()
                connection.execute(
                    "INSERT INTO expense_categories (id, company_id, name, code) "
                    "VALUES (%s, %s, 'Soft-deleted utility', '5300')",
                    (category_id, ids["company"]),
                )
                connection.execute(
                    "INSERT INTO expenses "
                    "(id, company_id, branch_id, category_id, amount_minor, "
                    "paid_via, paid_at, deleted_at) VALUES "
                    "(%s, %s, %s, %s, 500, 'cash', %s, %s)",
                    (
                        uuid4(),
                        ids["company"],
                        ids["branch"],
                        category_id,
                        now,
                        now,
                    ),
                )
            else:
                connection.execute(
                    "INSERT INTO assets "
                    "(id, company_id, branch_id, name, type, purchase_minor, "
                    "purchase_date, depreciation_method, useful_life_months, "
                    "salvage_minor, deleted_at) VALUES "
                    "(%s, %s, %s, 'Hidden asset', 'equipment', 10000, %s, "
                    "'straight_line', 24, 0, %s)",
                    (uuid4(), ids["company"], ids["branch"], now, now),
                )
            connection.commit()

        blocked = _run_alembic(database_url, "upgrade", "0050")
        output = blocked.stdout + blocked.stderr
        assert blocked.returncode != 0
        assert "unaudited soft-delete" in output
        current = _run_alembic(database_url, "current")
        assert "0049" in current.stdout


@pytest.mark.integration
def test_0050_clean_database_can_downgrade_to_0049() -> None:
    with _disposable_database("erp_finance_source_clean_downgrade") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0050")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        downgraded = _run_alembic(database_url, "downgrade", "0049")
        assert downgraded.returncode == 0, downgraded.stdout + downgraded.stderr
        current = _run_alembic(database_url, "current")
        assert "0049" in current.stdout


@pytest.mark.integration
def test_0050_preflight_rejects_unbalanced_general_journal() -> None:
    with _disposable_database("erp_finance_bad_general_journal") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0049")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        with psycopg.connect(_sync_dsn(database_url)) as connection:
            ids = _seed_company_branch_user(
                connection,
                label="BadJournal50",
                invoice_series="BJ",
            )
            account_id = uuid4()
            journal_id = uuid4()
            connection.execute(
                "INSERT INTO accounts "
                "(id, company_id, code, name, type, normal_side, is_active) "
                "VALUES (%s, %s, '9998', 'Migration preflight', 'asset', 'dr', true)",
                (account_id, ids["company"]),
            )
            connection.execute(
                "INSERT INTO journal_entries "
                "(id, company_id, branch_id, ref_type, ref_id, posted_at, "
                "total_minor) VALUES (%s, %s, %s, 'corrupt_legacy', %s, %s, 100)",
                (
                    journal_id,
                    ids["company"],
                    ids["branch"],
                    uuid4(),
                    datetime.now(UTC),
                ),
            )
            connection.execute(
                "INSERT INTO journal_lines "
                "(id, journal_entry_id, account_id, side, amount_minor) VALUES "
                "(%s, %s, %s, 'dr', 100), (%s, %s, %s, 'cr', 99)",
                (
                    uuid4(),
                    journal_id,
                    account_id,
                    uuid4(),
                    journal_id,
                    account_id,
                ),
            )
            connection.commit()

        blocked = _run_alembic(database_url, "upgrade", "0050")
        assert blocked.returncode != 0
        assert "journal" in blocked.stdout + blocked.stderr
        assert "unbalanced" in blocked.stdout + blocked.stderr


@pytest.mark.integration
@pytest.mark.parametrize("source_kind", ["capital", "manual", "tip"])
def test_0050_preflight_rejects_cross_tenant_finance_source_actor(
    source_kind: str,
) -> None:
    with _disposable_database(
        f"erp_finance_bad_{source_kind}_actor"
    ) as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0049")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        with psycopg.connect(_sync_dsn(database_url)) as connection:
            local = _seed_company_branch_user(
                connection,
                label=f"Local{source_kind}",
                invoice_series="LA",
            )
            foreign = _seed_company_branch_user(
                connection,
                label=f"Foreign{source_kind}",
                invoice_series="FA",
            )
            now = datetime.now(UTC)
            if source_kind == "capital":
                partner_id = uuid4()
                connection.execute(
                    "INSERT INTO partners "
                    "(id, company_id, name, share_pct, joined_at) "
                    "VALUES (%s, %s, 'Local partner', 100, %s)",
                    (partner_id, local["company"], now),
                )
                connection.execute(
                    "INSERT INTO capital_entries "
                    "(id, partner_id, type, amount_minor, effective_at, "
                    "settlement_account, created_by) "
                    "VALUES (%s, %s, 'invest', 100, %s, 'bank', %s)",
                    (uuid4(), partner_id, now, foreign["user"]),
                )
            elif source_kind == "manual":
                connection.execute(
                    "INSERT INTO manual_collections "
                    "(id, company_id, branch_id, business_date, method, "
                    "amount_minor, source_kind, source_ref, idempotency_key, "
                    "created_by) VALUES "
                    "(%s, %s, %s, %s, 'cash', 100, 'manual_daily', %s, %s, %s)",
                    (
                        uuid4(),
                        local["company"],
                        local["branch"],
                        now.date(),
                        str(uuid4()),
                        str(uuid4()),
                        foreign["user"],
                    ),
                )
            else:
                connection.execute(
                    "INSERT INTO tip_payouts "
                    "(id, company_id, branch_id, amount_minor, method, paid_at, "
                    "note, idempotency_key, created_by) VALUES "
                    "(%s, %s, %s, 100, 'cash', %s, 'Legacy tip payout', %s, %s)",
                    (
                        uuid4(),
                        local["company"],
                        local["branch"],
                        now,
                        str(uuid4()),
                        foreign["user"],
                    ),
                )
            connection.commit()

        blocked = _run_alembic(database_url, "upgrade", "0050")
        assert blocked.returncode != 0
        assert source_kind in (blocked.stdout + blocked.stderr).lower()


@pytest.mark.integration
def test_0050_preflight_rejects_voided_grn_receipt_journal() -> None:
    with _disposable_database("erp_finance_voided_grn_receipt") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0044")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        with psycopg.connect(_sync_dsn(database_url)) as connection:
            ids = _seed_legacy_grn(connection)
        upgraded = _run_alembic(database_url, "upgrade", "0049")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        with psycopg.connect(_sync_dsn(database_url)) as connection:
            connection.execute(
                "UPDATE journal_entries SET voided_at=%s, voided_by=%s, "
                "void_reason='Invalid legacy receipt reversal' "
                "WHERE id=(SELECT journal_entry_id FROM grns WHERE id=%s)",
                (datetime.now(UTC), ids["user"], ids["grn"]),
            )
            connection.commit()

        blocked = _run_alembic(database_url, "upgrade", "0050")
        output = blocked.stdout + blocked.stderr
        assert blocked.returncode != 0
        assert "posted GRN" in output
        assert "receipt-journal provenance" in output


@pytest.mark.integration
def test_0050_partner_activity_is_immutable_and_blocks_lossy_downgrade() -> None:
    with _disposable_database("erp_finance_partner_downgrade") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0050")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        with psycopg.connect(_sync_dsn(database_url)) as connection:
            ids = _seed_company_branch_user(
                connection,
                label="Partner50",
                invoice_series="P5",
            )
            partner_id = uuid4()
            connection.execute(
                "INSERT INTO partners (id, company_id, name, share_pct, joined_at) "
                "VALUES (%s, %s, 'Forward owner', 100, %s)",
                (partner_id, ids["company"], datetime.now(UTC)),
            )
            connection.commit()
            assert connection.execute(
                "SELECT source_integrity_revision FROM partners WHERE id=%s",
                (partner_id,),
            ).fetchone() == (50,)

        blocked = _run_alembic(database_url, "downgrade", "0049")
        assert blocked.returncode != 0
        assert "forward finance source activity" in blocked.stdout + blocked.stderr
        current = _run_alembic(database_url, "current")
        assert "0050" in current.stdout


@pytest.mark.integration
def test_0050_preflight_rejects_partner_shares_above_one_hundred() -> None:
    with _disposable_database("erp_finance_partner_share_preflight") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0049")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        with psycopg.connect(_sync_dsn(database_url)) as connection:
            ids = _seed_company_branch_user(
                connection,
                label="BadShares50",
                invoice_series="BS",
            )
            now = datetime.now(UTC)
            connection.execute(
                "INSERT INTO partners (id, company_id, name, share_pct, joined_at) "
                "VALUES (%s, %s, 'Owner A', 60, %s), "
                "(%s, %s, 'Owner B', 60, %s)",
                (uuid4(), ids["company"], now, uuid4(), ids["company"], now),
            )
            connection.commit()

        blocked = _run_alembic(database_url, "upgrade", "0050")
        assert blocked.returncode != 0
        assert "exceed 100 percent" in blocked.stdout + blocked.stderr
        current = _run_alembic(database_url, "current")
        assert "0049" in current.stdout


@pytest.mark.integration
@pytest.mark.asyncio
async def test_distributable_api_never_treats_provider_clearing_as_spendable(
    client,
    session,
    seed_owner,
) -> None:
    company = seed_owner["company"]
    branch = seed_owner["branch"]
    owner = seed_owner["owner"]
    now = datetime.now(UTC)
    business_date = now.astimezone(ZoneInfo(company.timezone)).date()
    partner = Partner(
        id=uuid4(),
        company_id=company.id,
        name="Cash contract partner",
        share_pct=100,
        joined_at=now,
    )
    session.add(partner)
    await session.flush()
    session.add(
        CapitalEntry(
            id=uuid4(),
            partner_id=partner.id,
            type="invest",
            amount_minor=4_000,
            effective_at=now,
            settlement_account="bank",
            source_ref=f"cash-contract-capital-{uuid4()}",
            created_by=owner.id,
        )
    )
    for method, amount in (("cash", 1_000), ("upi", 2_000), ("card", 3_000)):
        session.add(
            ManualCollection(
                id=uuid4(),
                company_id=company.id,
                branch_id=branch.id,
                business_date=business_date,
                method=method,
                amount_minor=amount,
                source_kind="manual_daily",
                source_ref=f"cash-contract-{method}-{uuid4()}",
                note="Phase 10 cash-position integration proof",
                idempotency_key=f"cash-contract-idem-{uuid4()}",
                created_by=owner.id,
            )
        )
    await session.commit()

    login = await client.post(
        "/api/v1/auth/login",
        json={"email": owner.email, "password": seed_owner["password"]},
    )
    assert login.status_code == 200, login.text
    response = await client.get(
        "/api/v1/finance/distributable",
        headers={"Authorization": f"Bearer {login.json()['access_token']}"},
    )
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["spendable_cash_bank_minor"] == 5_000
    assert body["liquid_cash_minor"] == 7_000  # deprecated cash + bank + UPI
    assert body["cash_position"] == {
        "cash_on_hand_minor": 1_000,
        "bank_balance_minor": 4_000,
        "spendable_cash_bank_minor": 5_000,
        "card_clearing_minor": 3_000,
        "upi_qr_clearing_minor": 2_000,
        "wallet_clearing_minor": 0,
        "pos_settlement_clearing_minor": 0,
        "settlement_receivables_minor": 5_000,
        "historical_funds_pending_reconciliation_minor": 0,
        "unreconciled_settlement_minor": 0,
        "reconciliation_only_minor": 0,
    }
    assert body["cash_based_capacity_minor"] == 5_000
    assert body["safe_to_distribute_minor"] == 5_000

@pytest.mark.asyncio
async def test_partner_allocations_fail_closed_until_shares_total_one_hundred(
    client,
    session,
    seed_owner,
) -> None:
    owner = seed_owner["owner"]
    company = seed_owner["company"]
    session.add(
        Partner(
            id=uuid4(),
            company_id=company.id,
            name="Incomplete ownership setup",
            share_pct=60,
            joined_at=datetime.now(UTC),
        )
    )
    await session.commit()
    login = await client.post(
        "/api/v1/auth/login",
        json={"email": owner.email, "password": seed_owner["password"]},
    )
    assert login.status_code == 200, login.text
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}

    for path in ("/api/v1/finance/pnl/partners", "/api/v1/finance/distributable"):
        response = await client.get(path, headers=headers)
        assert response.status_code == 422, (path, response.text)
        assert "total 60%, not 100%" in response.text


@pytest.mark.asyncio
async def test_partner_allocations_fail_closed_when_no_partners_are_configured(
    client,
    seed_owner,
) -> None:
    owner = seed_owner["owner"]
    login = await client.post(
        "/api/v1/auth/login",
        json={"email": owner.email, "password": seed_owner["password"]},
    )
    assert login.status_code == 200, login.text
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}

    for path in ("/api/v1/finance/pnl/partners", "/api/v1/finance/distributable"):
        response = await client.get(path, headers=headers)
        assert response.status_code == 422, (path, response.text)
        assert "total 0%, not 100%" in response.text


@pytest.mark.asyncio
async def test_protected_owner_gets_truthful_partner_reconciliation_errors(
    client,
    session,
    seed_owner,
) -> None:
    owner = seed_owner["owner"]
    company = seed_owner["company"]
    now = datetime.now(UTC)
    partner = Partner(
        id=uuid4(),
        company_id=company.id,
        name="Immutable owner agreement",
        share_pct=100,
        joined_at=now,
    )
    protected_role = Role(
        id=uuid4(),
        company_id=company.id,
        code="super_owner",
        name="Protected owner",
        permissions=[],
    )
    session.add_all([partner, protected_role])
    await session.flush()
    assignment = (
        await session.execute(select(UserRole).where(UserRole.user_id == owner.id))
    ).scalar_one()
    assignment.role_id = protected_role.id
    await session.commit()

    login = await client.post(
        "/api/v1/auth/login",
        json={"email": owner.email, "password": seed_owner["password"]},
    )
    assert login.status_code == 200, login.text
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}

    immutable = await client.patch(
        f"/api/v1/finance/partners/{partner.id}",
        headers=headers,
        json={"share_pct": 99},
    )
    assert immutable.status_code == 422, immutable.text
    assert "does not yet support effective-dated" in immutable.text

    overflow = await client.post(
        "/api/v1/finance/partners",
        headers=headers,
        json={
            "name": "Impossible extra owner",
            "share_pct": 1,
            "joined_at": now.isoformat(),
        },
    )
    assert overflow.status_code == 422, overflow.text
    assert "would exceed 100%" in overflow.text


@pytest.mark.asyncio
async def test_branch_bound_finance_scopes_operations_and_hides_partner_facts(
    client,
    session,
    seed_owner,
) -> None:
    company = seed_owner["company"]
    branch_a = seed_owner["branch"]
    owner = seed_owner["owner"]
    now = datetime.now(UTC)
    business_date = now.astimezone(ZoneInfo(company.timezone)).date()
    branch_b = Branch(
        id=uuid4(),
        company_id=company.id,
        name="Finance scope B",
        code=f"FB{uuid4().hex[:4]}",
        invoice_series_code="F2",
    )
    session.add(branch_b)
    await session.flush()
    manager_role = Role(
        id=uuid4(),
        company_id=company.id,
        code="manager",
        name="Branch finance manager",
        permissions=[],
    )
    session.add(manager_role)
    await session.flush()
    for branch, amount in ((branch_a, 1_100), (branch_b, 9_900)):
        session.add(
            ManualCollection(
                id=uuid4(),
                company_id=company.id,
                branch_id=branch.id,
                business_date=business_date,
                method="cash",
                amount_minor=amount,
                source_kind="manual_daily",
                source_ref=f"finance-scope-{branch.id}-{uuid4()}",
                note="Branch finance isolation proof",
                idempotency_key=f"finance-scope-{uuid4()}",
                created_by=owner.id,
            )
        )
    assignment = (
        await session.execute(select(UserRole).where(UserRole.user_id == owner.id))
    ).scalar_one()
    assignment.role_id = manager_role.id
    assignment.branch_id = branch_a.id
    await session.commit()

    login = await client.post(
        "/api/v1/auth/login",
        json={"email": owner.email, "password": seed_owner["password"]},
    )
    assert login.status_code == 200, login.text
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}

    pnl = await client.get("/api/v1/finance/pnl", headers=headers)
    assert pnl.status_code == 200, pnl.text
    assert pnl.json()["revenue_minor"] == 1_100

    metrics = await client.get("/api/v1/finance/metrics", headers=headers)
    assert metrics.status_code == 200, metrics.text
    assert metrics.json()["orders_count"] == 0

    for path in (
        "/api/v1/finance/partners",
        "/api/v1/finance/pnl/partners",
        "/api/v1/finance/distributable",
    ):
        response = await client.get(path, headers=headers)
        assert response.status_code == 403, (path, response.text)
        assert "company-wide" in response.text


@pytest.mark.asyncio
@pytest.mark.parametrize("role_code", ["super_owner", "partner"])
async def test_trusted_owner_and_partner_identities_can_read_company_finance(
    role_code,
    client,
    session,
    seed_owner,
) -> None:
    company = seed_owner["company"]
    owner = seed_owner["owner"]
    role = Role(
        id=uuid4(),
        company_id=company.id,
        code=role_code,
        name=f"Finance identity {role_code}",
        permissions=[],
    )
    session.add(role)
    await session.flush()
    assignment = (
        await session.execute(select(UserRole).where(UserRole.user_id == owner.id))
    ).scalar_one()
    assignment.role_id = role.id
    await session.commit()

    login = await client.post(
        "/api/v1/auth/login",
        json={"email": owner.email, "password": seed_owner["password"]},
    )
    assert login.status_code == 200, login.text
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}
    response = await client.get("/api/v1/finance/partners", headers=headers)
    assert response.status_code == 200, response.text
