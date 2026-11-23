"""Actual 0093 schema/backstop and downgrade-retention proof."""

from datetime import UTC, datetime, timedelta
from uuid import uuid4

import psycopg
import pytest

from tests.integration.test_cafe_workflow_migration import (
    _disposable_database,
    _run_alembic,
)


def _seed_0092_void_scope(connection: psycopg.Connection) -> dict[str, object]:
    """Seed only valid 0092 rows required by the 0093 adversaries."""

    ids = {
        "company": uuid4(),
        "branch": uuid4(),
        "terminal": uuid4(),
        "user": uuid4(),
        "shift": uuid4(),
        "category": uuid4(),
        "item": uuid4(),
    }
    now = datetime.now(UTC)
    connection.execute(
        "INSERT INTO companies(id,name,gst_registration_type) "
        "VALUES(%s,'0093 Void proof','unregistered')",
        (ids["company"],),
    )
    connection.execute(
        "INSERT INTO branches(id,company_id,name,code,state_code,invoice_series_code) "
        "VALUES(%s,%s,'Main','M1','32','M1')",
        (ids["branch"], ids["company"]),
    )
    connection.execute(
        "INSERT INTO terminals(id,branch_id,name,device_id,purpose,is_active) "
        "VALUES(%s,%s,'0093 Hybrid',%s,'hybrid',true)",
        (ids["terminal"], ids["branch"], f"0093-{uuid4()}"),
    )
    connection.execute(
        "INSERT INTO users(id,company_id,email,password_hash,name) "
        "VALUES(%s,%s,%s,'not-a-real-password-hash','0093 Tester')",
        (ids["user"], ids["company"], f"0093-{uuid4()}@test.local"),
    )
    connection.execute(
        "INSERT INTO shifts(id,company_id,branch_id,terminal_id,opened_by,opened_at,"
        "opening_float_minor,expected_minor,status) "
        "VALUES(%s,%s,%s,%s,%s,%s,0,0,'open')",
        (
            ids["shift"],
            ids["company"],
            ids["branch"],
            ids["terminal"],
            ids["user"],
            now,
        ),
    )
    connection.execute(
        "INSERT INTO menu_categories(id,company_id,name) VALUES(%s,%s,'0093')",
        (ids["category"], ids["company"]),
    )
    connection.execute(
        "INSERT INTO menu_items(id,company_id,category_id,sku,name,type,"
        "base_price_minor,tax_rate,hsn_code,price_includes_tax,is_available) "
        "VALUES(%s,%s,%s,%s,'0093 Item','service',0,0,'999692',true,true)",
        (ids["item"], ids["company"], ids["category"], f"V-{uuid4().hex[:8]}"),
    )
    connection.commit()
    return ids


@pytest.mark.integration
def test_0093_installs_deferred_void_backstops_and_refuses_evidence_loss() -> None:
    with _disposable_database("erp_gaming_void_0093") as database_url:
        predecessor = _run_alembic(database_url, "upgrade", "0092")
        assert predecessor.returncode == 0, predecessor.stdout + predecessor.stderr
        dsn = database_url.replace("postgresql+psycopg://", "postgresql://", 1)
        with psycopg.connect(dsn) as connection:
            ids = _seed_0092_void_scope(connection)

        upgraded = _run_alembic(database_url, "upgrade", "0093")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr

        station_id = uuid4()
        session_id = uuid4()
        linked_session_id = uuid4()
        receipt_session_id = uuid4()
        second_retained_session_id = uuid4()
        retained_order_id = uuid4()
        source_order_id = uuid4()
        receipt_order_id = uuid4()
        now = datetime.now(UTC)
        with psycopg.connect(dsn) as connection:
            assert connection.execute(
                "SELECT version_num FROM alembic_version"
            ).fetchone() == ("0093",)
            assert connection.execute(
                "SELECT tgdeferrable, tginitdeferred FROM pg_trigger "
                "WHERE tgname='trg_gaming_sessions_validate_void'"
            ).fetchone() == (True, True)
            assert connection.execute(
                "SELECT tgdeferrable, tginitdeferred FROM pg_trigger "
                "WHERE tgname='trg_gaming_void_addon_lines_validate'"
            ).fetchone() == (True, True)
            assert connection.execute(
                "SELECT tgdeferrable, tginitdeferred FROM pg_trigger "
                "WHERE tgname='trg_gaming_void_receipts_validate_session'"
            ).fetchone() == (True, True)
            reciprocal_validator_wrappers = connection.execute(
                "SELECT proname, prosrc FROM pg_proc WHERE proname IN "
                "('validate_gaming_void_final_state',"
                "'validate_gaming_void_receipt_session') ORDER BY proname"
            ).fetchall()
            assert [row[0] for row in reciprocal_validator_wrappers] == [
                "validate_gaming_void_final_state",
                "validate_gaming_void_receipt_session",
            ]
            assert all(
                "assert_gaming_void_final_state" in row[1]
                for row in reciprocal_validator_wrappers
            )
            immutable_triggers = connection.execute(
                "SELECT tgname FROM pg_trigger WHERE NOT tgisinternal "
                "AND tgname IN ('trg_gaming_void_receipts_immutable',"
                "'trg_gaming_void_addon_lines_immutable',"
                "'trg_gaming_void_addon_lines_validate',"
                "'trg_gaming_void_receipts_validate_session',"
                "'trg_order_lines_guard_retained_gaming_evidence',"
                "'trg_orders_guard_gaming_bill_void',"
                "'trg_orders_guard_gaming_void_totals',"
                "'trg_order_lines_guard_gaming_void') ORDER BY tgname"
            ).fetchall()
            assert immutable_triggers == [
                ("trg_gaming_void_addon_lines_immutable",),
                ("trg_gaming_void_addon_lines_validate",),
                ("trg_gaming_void_receipts_immutable",),
                ("trg_gaming_void_receipts_validate_session",),
                ("trg_order_lines_guard_gaming_void",),
                ("trg_order_lines_guard_retained_gaming_evidence",),
                ("trg_orders_guard_gaming_bill_void",),
                ("trg_orders_guard_gaming_void_totals",),
            ]
            unique_void_links = connection.execute(
                "SELECT indexname FROM pg_indexes WHERE schemaname=current_schema() "
                "AND indexname IN ('uq_gaming_sessions_retained_addon_order',"
                "'uq_gaming_void_receipt_source_order',"
                "'uq_gaming_void_receipt_retained_order') ORDER BY indexname"
            ).fetchall()
            assert unique_void_links == [
                ("uq_gaming_sessions_retained_addon_order",),
                ("uq_gaming_void_receipt_retained_order",),
                ("uq_gaming_void_receipt_source_order",),
            ]
            assert connection.execute(
                "SELECT tgdeferrable, tginitdeferred FROM pg_trigger "
                "WHERE tgname='trg_gaming_sessions_validate_order_unlink'"
            ).fetchone() == (True, True)

            connection.execute(
                "INSERT INTO stations(id,company_id,branch_id,code,name,type,"
                "rate_per_hour_minor,is_active,tax_rate,sac_code,rate_includes_tax) "
                "VALUES(%s,%s,%s,%s,'0093 Gaming','ps5',12000,true,0,'999692',true)",
                (station_id, ids["company"], ids["branch"], f"V{uuid4().hex[:7]}"),
            )
            connection.execute(
                "INSERT INTO orders(id,company_id,branch_id,terminal_id,shift_id,opened_by,"
                "type,status,opened_at,held_at,subtotal_minor,discount_minor,tax_minor,"
                "round_off_minor,total_minor,kitchen_state) "
                "VALUES(%s,%s,%s,%s,%s,%s,'session','held',%s,%s,0,0,0,0,0,'received')",
                (
                    retained_order_id,
                    ids["company"],
                    ids["branch"],
                    ids["terminal"],
                    ids["shift"],
                    ids["user"],
                    now,
                    now,
                ),
            )
            connection.execute(
                "INSERT INTO orders(id,company_id,branch_id,terminal_id,shift_id,opened_by,"
                "type,status,opened_at,held_at,subtotal_minor,discount_minor,tax_minor,"
                "round_off_minor,total_minor,kitchen_state) "
                "VALUES(%s,%s,%s,%s,%s,%s,'session','held',%s,%s,0,0,0,0,0,'received')",
                (
                    receipt_order_id,
                    ids["company"],
                    ids["branch"],
                    ids["terminal"],
                    ids["shift"],
                    ids["user"],
                    now,
                    now,
                ),
            )
            connection.execute(
                "INSERT INTO orders(id,company_id,branch_id,terminal_id,shift_id,opened_by,"
                "type,status,opened_at,held_at,subtotal_minor,discount_minor,tax_minor,"
                "round_off_minor,total_minor,kitchen_state) "
                "VALUES(%s,%s,%s,%s,%s,%s,'session','held',%s,%s,0,0,0,0,0,'received')",
                (
                    source_order_id,
                    ids["company"],
                    ids["branch"],
                    ids["terminal"],
                    ids["shift"],
                    ids["user"],
                    now,
                    now,
                ),
            )
            connection.execute(
                "INSERT INTO gaming_sessions(id,company_id,station_id,shift_id,opened_by,"
                "start_at,rate_per_hour_minor,status,billing_mode,extra_controllers) "
                "VALUES(%s,%s,%s,%s,%s,%s,12000,'ended','hourly',0)",
                (
                    session_id,
                    ids["company"],
                    station_id,
                    ids["shift"],
                    ids["user"],
                    now - timedelta(minutes=5),
                ),
            )
            connection.execute(
                "INSERT INTO gaming_sessions(id,company_id,station_id,shift_id,opened_by,"
                "start_at,rate_per_hour_minor,status,billing_mode,extra_controllers,"
                "billable_minutes,amount_minor,cancelled_at,cancelled_by,cancel_reason) "
                "VALUES(%s,%s,%s,%s,%s,%s,12000,'cancelled','hourly',0,0,0,%s,%s,'reviewed')",
                (
                    receipt_session_id,
                    ids["company"],
                    station_id,
                    ids["shift"],
                    ids["user"],
                    now - timedelta(minutes=5),
                    now,
                    ids["user"],
                ),
            )
            connection.execute(
                "INSERT INTO gaming_sessions(id,company_id,station_id,order_id,shift_id,opened_by,"
                "start_at,rate_per_hour_minor,status,billing_mode,extra_controllers) "
                "VALUES(%s,%s,%s,%s,%s,%s,%s,12000,'ended','hourly',0)",
                (
                    linked_session_id,
                    ids["company"],
                    station_id,
                    source_order_id,
                    ids["shift"],
                    ids["user"],
                    now - timedelta(minutes=5),
                ),
            )
            connection.execute(
                "INSERT INTO gaming_sessions(id,company_id,station_id,shift_id,opened_by,"
                "start_at,rate_per_hour_minor,status,billing_mode,extra_controllers) "
                "VALUES(%s,%s,%s,%s,%s,%s,12000,'ended','hourly',0)",
                (
                    second_retained_session_id,
                    ids["company"],
                    station_id,
                    ids["shift"],
                    ids["user"],
                    now - timedelta(minutes=5),
                ),
            )
            connection.execute(
                "UPDATE gaming_sessions SET retained_addon_order_id=%s WHERE id=%s",
                (retained_order_id, session_id),
            )
            connection.commit()

            with pytest.raises(psycopg.errors.UniqueViolation):
                connection.execute(
                    "UPDATE gaming_sessions SET retained_addon_order_id=%s WHERE id=%s",
                    (retained_order_id, second_retained_session_id),
                )
            connection.rollback()

            connection.execute(
                "UPDATE gaming_sessions SET order_id=NULL WHERE id=%s",
                (linked_session_id,),
            )
            with pytest.raises(
                psycopg.errors.CheckViolation,
                match="immutable Owner Void receipt",
            ):
                connection.commit()
            connection.rollback()
            assert connection.execute(
                "SELECT order_id FROM gaming_sessions WHERE id=%s",
                (linked_session_id,),
            ).fetchone() == (source_order_id,)

            connection.execute(
                "UPDATE orders SET status='void' WHERE id=%s",
                (retained_order_id,),
            )
            with pytest.raises(
                psycopg.errors.CheckViolation,
                match="Gaming-linked orders require the dedicated Owner Void receipt",
            ) as retained_bill_guard:
                connection.commit()
            assert retained_bill_guard.value.sqlstate == "23514"
            connection.rollback()
            assert connection.execute(
                "SELECT status FROM orders WHERE id=%s",
                (retained_order_id,),
            ).fetchone() == ("held",)

            # A legacy cancelled row can exist without a receipt. Inserting a
            # receipt later must run the same complete final-state validator as
            # the session-update trigger; otherwise a held source bill and an
            # unvoided charge could be blessed by INSERT alone.
            charge_line_id = uuid4()
            receipt_id = uuid4()
            connection.execute(
                "INSERT INTO order_lines(id,order_id,menu_item_id,qty,unit_price_minor,"
                "line_total_minor,discount_minor,tax_rate,taxable_value_minor,cgst_minor,"
                "sgst_minor,igst_minor,cess_minor,kitchen_status,created_at) "
                "VALUES(%s,%s,%s,1,0,0,0,0,0,0,0,0,0,'queued',%s)",
                (charge_line_id, receipt_order_id, ids["item"], now),
            )
            connection.commit()

            connection.execute(
                "INSERT INTO gaming_void_receipts(id,company_id,gaming_session_id,"
                "source_order_id,gaming_charge_line_id,charge_unit_price_minor,"
                "charge_line_total_minor,charge_discount_minor,charge_taxable_value_minor,"
                "charge_cgst_minor,charge_sgst_minor,charge_igst_minor,charge_cess_minor,"
                "actor_user_id,terminal_id,mode,original_session_status,original_amount_minor,"
                "session_billing_revision,session_participant_revision,source_order_status,"
                "source_checkout_version,source_total_minor,retained_total_minor,addon_count,"
                "reason,idempotency_key,request_hash,request_payload,response_payload,voided_at) "
                "SELECT %s,%s,%s,%s,%s,0,0,0,0,0,0,0,0,%s,%s,'linked_pos','ended',0,"
                "0,0,'held',checkout_version,total_minor,0,0,'reviewed','0093-held-adversary',"
                "%s,'{}'::jsonb,'{}'::jsonb,%s FROM orders WHERE id=%s",
                (
                    receipt_id,
                    ids["company"],
                    receipt_session_id,
                    receipt_order_id,
                    charge_line_id,
                    ids["user"],
                    ids["terminal"],
                    "a" * 64,
                    now,
                    receipt_order_id,
                ),
            )
            with pytest.raises(
                psycopg.errors.CheckViolation,
                match="charge line does not match",
            ):
                connection.commit()
            connection.rollback()

            # Receipt INSERT is itself a protected entry point. It cannot bless
            # a pre-cancelled row whose durable cancellation facts differ from
            # the reviewed actor, reason, or timestamp.
            connection.execute(
                "UPDATE order_lines SET voided_at=%s,voided_by=%s,void_reason='reviewed' "
                "WHERE id=%s",
                (now, ids["user"], charge_line_id),
            )
            connection.execute(
                "UPDATE orders SET status='void' WHERE id=%s",
                (receipt_order_id,),
            )
            connection.execute(
                "INSERT INTO gaming_void_receipts(id,company_id,gaming_session_id,"
                "source_order_id,gaming_charge_line_id,charge_unit_price_minor,"
                "charge_line_total_minor,charge_discount_minor,charge_taxable_value_minor,"
                "charge_cgst_minor,charge_sgst_minor,charge_igst_minor,charge_cess_minor,"
                "actor_user_id,terminal_id,mode,original_session_status,original_amount_minor,"
                "session_billing_revision,session_participant_revision,source_order_status,"
                "source_checkout_version,source_total_minor,retained_total_minor,addon_count,"
                "reason,idempotency_key,request_hash,request_payload,response_payload,voided_at) "
                "SELECT %s,%s,%s,%s,%s,0,0,0,0,0,0,0,0,%s,%s,'linked_pos','ended',0,"
                "0,0,'held',checkout_version,total_minor,0,0,'reviewed','0093-facts-adversary',"
                "%s,'{}'::jsonb,'{}'::jsonb,%s FROM orders WHERE id=%s",
                (
                    receipt_id,
                    ids["company"],
                    receipt_session_id,
                    receipt_order_id,
                    charge_line_id,
                    ids["user"],
                    ids["terminal"],
                    "d" * 64,
                    now + timedelta(seconds=1),
                    receipt_order_id,
                ),
            )
            with pytest.raises(
                psycopg.errors.CheckViolation,
                match="session facts do not match",
            ):
                connection.commit()
            connection.rollback()

            # A foreign-company bill cannot be attached to an otherwise valid
            # cancelled session by constructing a receipt directly. The base
            # schema permits independently keyed scope columns, so 0093 must
            # bind the source order to the session/receipt scope explicitly.
            foreign_company_id = uuid4()
            foreign_category_id = uuid4()
            foreign_item_id = uuid4()
            foreign_order_id = uuid4()
            foreign_charge_line_id = uuid4()
            connection.execute(
                "INSERT INTO companies(id,name,gst_registration_type) "
                "VALUES(%s,'0093 Foreign proof','unregistered')",
                (foreign_company_id,),
            )
            connection.execute(
                "INSERT INTO menu_categories(id,company_id,name) "
                "VALUES(%s,%s,'0093 Foreign')",
                (foreign_category_id, foreign_company_id),
            )
            connection.execute(
                "INSERT INTO menu_items(id,company_id,category_id,sku,name,type,"
                "base_price_minor,tax_rate,hsn_code,price_includes_tax,is_available) "
                "VALUES(%s,%s,%s,%s,'0093 Foreign Item','service',0,0,'999692',true,true)",
                (
                    foreign_item_id,
                    foreign_company_id,
                    foreign_category_id,
                    f"VF-{uuid4().hex[:8]}",
                ),
            )
            connection.execute(
                "INSERT INTO orders(id,company_id,branch_id,terminal_id,shift_id,opened_by,"
                "type,status,opened_at,held_at,subtotal_minor,discount_minor,tax_minor,"
                "round_off_minor,total_minor,kitchen_state) "
                "VALUES(%s,%s,%s,%s,%s,%s,'session','void',%s,%s,0,0,0,0,0,'received')",
                (
                    foreign_order_id,
                    foreign_company_id,
                    ids["branch"],
                    ids["terminal"],
                    ids["shift"],
                    ids["user"],
                    now,
                    now,
                ),
            )
            connection.execute(
                "INSERT INTO order_lines(id,order_id,menu_item_id,qty,unit_price_minor,"
                "line_total_minor,discount_minor,tax_rate,taxable_value_minor,cgst_minor,"
                "sgst_minor,igst_minor,cess_minor,kitchen_status,voided_at,voided_by,"
                "void_reason,created_at) "
                "VALUES(%s,%s,%s,1,0,0,0,0,0,0,0,0,0,'queued',%s,%s,'reviewed',%s)",
                (
                    foreign_charge_line_id,
                    foreign_order_id,
                    foreign_item_id,
                    now,
                    ids["user"],
                    now,
                ),
            )
            connection.execute(
                "INSERT INTO gaming_void_receipts(id,company_id,gaming_session_id,"
                "source_order_id,gaming_charge_line_id,charge_unit_price_minor,"
                "charge_line_total_minor,charge_discount_minor,charge_taxable_value_minor,"
                "charge_cgst_minor,charge_sgst_minor,charge_igst_minor,charge_cess_minor,"
                "actor_user_id,terminal_id,mode,original_session_status,original_amount_minor,"
                "session_billing_revision,session_participant_revision,source_order_status,"
                "source_checkout_version,source_total_minor,retained_total_minor,addon_count,"
                "reason,idempotency_key,request_hash,request_payload,response_payload,voided_at) "
                "SELECT %s,%s,%s,%s,%s,0,0,0,0,0,0,0,0,%s,%s,'linked_pos','ended',0,"
                "0,0,'held',checkout_version,total_minor,0,0,'reviewed','0093-scope-adversary',"
                "%s,'{}'::jsonb,'{}'::jsonb,%s FROM orders WHERE id=%s",
                (
                    receipt_id,
                    ids["company"],
                    receipt_session_id,
                    foreign_order_id,
                    foreign_charge_line_id,
                    ids["user"],
                    ids["terminal"],
                    "e" * 64,
                    now,
                    foreign_order_id,
                ),
            )
            with pytest.raises(
                psycopg.errors.CheckViolation,
                match="source order has invalid company/branch/terminal scope",
            ):
                connection.commit()
            connection.rollback()

            # The same receipt becomes valid without touching the already
            # cancelled session only when the reviewed charge and empty source
            # order reach their exact final state in this transaction.
            connection.execute(
                "UPDATE order_lines SET voided_at=%s,voided_by=%s,void_reason='reviewed' "
                "WHERE id=%s",
                (now, ids["user"], charge_line_id),
            )
            connection.execute(
                "UPDATE orders SET status='void' WHERE id=%s",
                (receipt_order_id,),
            )
            connection.execute(
                "INSERT INTO gaming_void_receipts(id,company_id,gaming_session_id,"
                "source_order_id,gaming_charge_line_id,charge_unit_price_minor,"
                "charge_line_total_minor,charge_discount_minor,charge_taxable_value_minor,"
                "charge_cgst_minor,charge_sgst_minor,charge_igst_minor,charge_cess_minor,"
                "actor_user_id,terminal_id,mode,original_session_status,original_amount_minor,"
                "session_billing_revision,session_participant_revision,source_order_status,"
                "source_checkout_version,source_total_minor,retained_total_minor,addon_count,"
                "reason,idempotency_key,request_hash,request_payload,response_payload,voided_at) "
                "SELECT %s,%s,%s,%s,%s,0,0,0,0,0,0,0,0,%s,%s,'linked_pos','ended',0,"
                "0,0,'held',checkout_version,total_minor,0,0,'reviewed','0093-valid',"
                "%s,'{}'::jsonb,'{}'::jsonb,%s FROM orders WHERE id=%s",
                (
                    receipt_id,
                    ids["company"],
                    receipt_session_id,
                    receipt_order_id,
                    charge_line_id,
                    ids["user"],
                    ids["terminal"],
                    "b" * 64,
                    now,
                    receipt_order_id,
                ),
            )
            connection.commit()

            with pytest.raises(
                psycopg.errors.CheckViolation,
                match="order totals are immutable",
            ):
                connection.execute(
                    "UPDATE orders SET total_minor=1 WHERE id=%s",
                    (receipt_order_id,),
                )
            connection.rollback()

            with pytest.raises(psycopg.errors.UniqueViolation):
                connection.execute(
                    "INSERT INTO gaming_void_receipts(id,company_id,gaming_session_id,"
                    "source_order_id,gaming_charge_line_id,charge_unit_price_minor,"
                    "charge_line_total_minor,charge_discount_minor,charge_taxable_value_minor,"
                    "charge_cgst_minor,charge_sgst_minor,charge_igst_minor,charge_cess_minor,"
                    "actor_user_id,terminal_id,mode,original_session_status,original_amount_minor,"
                    "session_billing_revision,session_participant_revision,source_order_status,"
                    "source_checkout_version,source_total_minor,retained_total_minor,addon_count,"
                    "reason,idempotency_key,request_hash,request_payload,response_payload,voided_at) "
                    "VALUES(%s,%s,%s,%s,%s,0,0,0,0,0,0,0,0,%s,%s,'linked_pos','ended',0,"
                    "0,0,'held',1,0,0,0,'duplicate','0093-duplicate-source',%s,"
                    "'{}'::jsonb,'{}'::jsonb,%s)",
                    (
                        uuid4(),
                        ids["company"],
                        second_retained_session_id,
                        receipt_order_id,
                        charge_line_id,
                        ids["user"],
                        ids["terminal"],
                        "c" * 64,
                        now,
                    ),
                )
            connection.rollback()

        refused = _run_alembic(database_url, "downgrade", "0092")
        assert refused.returncode != 0
        assert "cannot downgrade 0093" in refused.stdout + refused.stderr
        with psycopg.connect(dsn) as connection:
            assert connection.execute(
                "SELECT version_num FROM alembic_version"
            ).fetchone() == ("0093",)
            assert connection.execute(
                "SELECT retained_addon_order_id FROM gaming_sessions WHERE id=%s",
                (session_id,),
            ).fetchone() == (retained_order_id,)


@pytest.mark.integration
def test_0093_empty_downgrade_restores_0092_schema() -> None:
    with _disposable_database("erp_gaming_void_0093_empty") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0093")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        downgraded = _run_alembic(database_url, "downgrade", "0092")
        assert downgraded.returncode == 0, downgraded.stdout + downgraded.stderr
        dsn = database_url.replace("postgresql+psycopg://", "postgresql://", 1)
        with psycopg.connect(dsn) as connection:
            assert connection.execute(
                "SELECT version_num FROM alembic_version"
            ).fetchone() == ("0092",)
            assert connection.execute(
                "SELECT to_regclass('gaming_void_receipts'), "
                "to_regclass('gaming_void_addon_lines')"
            ).fetchone() == (None, None)
            assert connection.execute(
                "SELECT 1 FROM information_schema.columns WHERE table_name='gaming_sessions' "
                "AND column_name='retained_addon_order_id'"
            ).fetchone() is None
