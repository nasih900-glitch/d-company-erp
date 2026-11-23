"""Append-only correction of entirely mistaken, unsupplied retained Gaming bills.

Revision ID: 0096
Revises: 0095
"""

from alembic import op

revision = "0096"
down_revision = "0095"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("""
        CREATE TABLE gaming_retained_bill_corrections (
            id uuid PRIMARY KEY,
            company_id uuid NOT NULL REFERENCES companies(id) ON DELETE RESTRICT,
            original_receipt_id uuid NOT NULL REFERENCES gaming_void_receipts(id) ON DELETE RESTRICT,
            retained_order_id uuid NOT NULL REFERENCES orders(id) ON DELETE RESTRICT,
            gaming_session_id uuid NOT NULL REFERENCES gaming_sessions(id) ON DELETE RESTRICT,
            actor_user_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
            terminal_id uuid NOT NULL REFERENCES terminals(id) ON DELETE RESTRICT,
            expected_checkout_version integer NOT NULL,
            all_items_mistaken_not_supplied boolean NOT NULL,
            reason varchar(500) NOT NULL,
            idempotency_key varchar(160) NOT NULL,
            request_hash varchar(64) NOT NULL,
            review_hash varchar(64) NOT NULL,
            order_snapshot jsonb NOT NULL,
            line_snapshot jsonb NOT NULL,
            request_payload jsonb NOT NULL,
            response_payload jsonb NOT NULL,
            corrected_at timestamptz NOT NULL,
            CONSTRAINT uq_gaming_retained_correction_order UNIQUE(retained_order_id),
            CONSTRAINT uq_gaming_retained_correction_key UNIQUE(company_id, idempotency_key),
            CONSTRAINT ck_gaming_retained_correction_identity CHECK (
                all_items_mistaken_not_supplied AND length(trim(reason)) BETWEEN 3 AND 500
                AND expected_checkout_version >= 1 AND request_hash ~ '^[0-9a-f]{64}$'
                AND review_hash ~ '^[0-9a-f]{64}$' AND length(trim(idempotency_key)) > 0),
            CONSTRAINT ck_gaming_retained_correction_snapshots CHECK (
                jsonb_typeof(order_snapshot) = 'object' AND jsonb_typeof(line_snapshot) = 'array'
                AND jsonb_typeof(request_payload) = 'object' AND jsonb_typeof(response_payload) = 'object')
        );
        CREATE FUNCTION reject_gaming_retained_correction_mutation()
        RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
            RAISE EXCEPTION 'retained Gaming correction receipt is append-only' USING ERRCODE='23514';
        END $$;
        CREATE TRIGGER trg_retained_correction_immutable BEFORE UPDATE OR DELETE
            ON gaming_retained_bill_corrections FOR EACH ROW
            EXECUTE FUNCTION reject_gaming_retained_correction_mutation();

        CREATE FUNCTION assert_gaming_retained_correction(target_order_id uuid)
        RETURNS void LANGUAGE plpgsql AS $$
        DECLARE
            correction gaming_retained_bill_corrections%ROWTYPE;
            original gaming_void_receipts%ROWTYPE;
            bill orders%ROWTYPE;
            source gaming_sessions%ROWTYPE;
            current_lines jsonb;
        BEGIN
            SELECT * INTO correction FROM gaming_retained_bill_corrections WHERE retained_order_id=target_order_id;
            IF correction.id IS NULL THEN RETURN; END IF;
            SELECT * INTO original FROM gaming_void_receipts WHERE id=correction.original_receipt_id;
            SELECT * INTO bill FROM orders WHERE id=target_order_id;
            SELECT * INTO source FROM gaming_sessions WHERE id=correction.gaming_session_id;
            SELECT coalesce(jsonb_agg(to_jsonb(l) ORDER BY l.id), '[]'::jsonb) INTO current_lines
                FROM order_lines l WHERE l.order_id=target_order_id;
            IF original.company_id IS DISTINCT FROM correction.company_id
               OR original.mode IS DISTINCT FROM 'unlinked_addons'
               OR original.retained_order_id IS DISTINCT FROM target_order_id
               OR original.gaming_session_id IS DISTINCT FROM correction.gaming_session_id
               OR source.company_id IS DISTINCT FROM correction.company_id
               OR source.status IS DISTINCT FROM 'cancelled'
               OR source.retained_addon_order_id IS DISTINCT FROM target_order_id
               OR bill.company_id IS DISTINCT FROM correction.company_id
               OR bill.terminal_id IS DISTINCT FROM correction.terminal_id
               OR bill.type IS DISTINCT FROM 'session' OR bill.table_id IS NOT NULL
               OR bill.status IS DISTINCT FROM 'void'
               OR correction.request_payload IS DISTINCT FROM jsonb_build_object(
                    'original_receipt_id', correction.original_receipt_id,
                    'expected_checkout_version', correction.expected_checkout_version,
                    'review_hash', correction.review_hash, 'all_items_mistaken_not_supplied', true,
                    'reason', correction.reason)
               OR correction.response_payload IS DISTINCT FROM jsonb_build_object(
                    'correction_id', correction.id, 'order_id', target_order_id,
                    'original_receipt_id', correction.original_receipt_id,
                    'gaming_session_id', correction.gaming_session_id, 'order_status', 'void')
               OR correction.order_snapshot->>'status' IS DISTINCT FROM 'held'
               OR (correction.order_snapshot->>'checkout_version')::integer IS DISTINCT FROM correction.expected_checkout_version
               OR bill.checkout_version IS DISTINCT FROM correction.expected_checkout_version + 1
               OR (to_jsonb(bill) - ARRAY['status','checkout_version','updated_at']) IS DISTINCT FROM
                  (correction.order_snapshot - ARRAY['status','checkout_version','updated_at'])
               OR current_lines IS DISTINCT FROM correction.line_snapshot
               OR jsonb_array_length(current_lines) <> original.addon_count
               OR bill.total_minor IS DISTINCT FROM original.retained_total_minor
               OR bill.invoice_no IS NOT NULL OR bill.invoice_issued_at IS NOT NULL
               OR bill.fiscal_year IS NOT NULL OR bill.irn IS NOT NULL OR bill.closed_at IS NOT NULL
               OR bill.irn_ack_no IS NOT NULL OR bill.irn_acknowledged_at IS NOT NULL
               OR bill.e_invoice_qr IS NOT NULL OR bill.source_integrity_revision IS NOT NULL
               OR bill.tip_minor <> 0 OR bill.manual_discount_minor <> 0 OR bill.points_redeemed_minor <> 0
               OR EXISTS (SELECT 1 FROM payments WHERE order_id=target_order_id)
               OR EXISTS (SELECT 1 FROM refunds WHERE order_id=target_order_id)
               OR EXISTS (SELECT 1 FROM membership_benefit_reservations WHERE order_id=target_order_id)
               OR EXISTS (SELECT 1 FROM stock_movements WHERE ref_id=target_order_id)
               OR EXISTS (SELECT 1 FROM order_checkout_claims WHERE order_id=target_order_id)
               OR NOT EXISTS (SELECT 1 FROM users u WHERE u.id=correction.actor_user_id AND u.company_id=correction.company_id)
               OR NOT EXISTS (SELECT 1 FROM shifts sh JOIN stations st ON st.id=source.station_id
                   WHERE sh.id=bill.shift_id AND sh.company_id=correction.company_id
                     AND sh.branch_id=bill.branch_id AND sh.terminal_id=bill.terminal_id AND st.branch_id=bill.branch_id)
            THEN
                RAISE EXCEPTION 'invalid retained Gaming mistake correction evidence' USING ERRCODE='23514';
            END IF;
            PERFORM assert_gaming_void_final_state(source.id, source.company_id);
        END $$;
        CREATE FUNCTION validate_gaming_retained_correction()
        RETURNS trigger LANGUAGE plpgsql AS $$
        DECLARE target uuid;
        BEGIN
            IF TG_TABLE_NAME='gaming_retained_bill_corrections' THEN target := NEW.retained_order_id;
            ELSIF TG_TABLE_NAME='orders' THEN target := NEW.id;
            ELSIF TG_TABLE_NAME='stock_movements' THEN target := NEW.ref_id;
            ELSE target := NEW.order_id;
            END IF;
            PERFORM assert_gaming_retained_correction(target);
            RETURN NULL;
        END $$;
        CREATE CONSTRAINT TRIGGER trg_retained_correction_validate AFTER INSERT ON gaming_retained_bill_corrections
            DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_gaming_retained_correction();
        CREATE CONSTRAINT TRIGGER trg_orders_retained_correction_validate AFTER UPDATE ON orders
            DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_gaming_retained_correction();
        CREATE CONSTRAINT TRIGGER trg_lines_retained_correction_validate AFTER INSERT OR UPDATE ON order_lines
            DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_gaming_retained_correction();
        CREATE CONSTRAINT TRIGGER trg_payments_retained_correction_validate AFTER INSERT OR UPDATE ON payments
            DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_gaming_retained_correction();
        CREATE CONSTRAINT TRIGGER trg_refunds_retained_correction_validate AFTER INSERT OR UPDATE ON refunds
            DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_gaming_retained_correction();
        CREATE CONSTRAINT TRIGGER trg_benefits_retained_correction_validate AFTER INSERT OR UPDATE ON membership_benefit_reservations
            DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_gaming_retained_correction();
        CREATE CONSTRAINT TRIGGER trg_stock_retained_correction_validate AFTER INSERT OR UPDATE ON stock_movements
            DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_gaming_retained_correction();
        CREATE OR REPLACE FUNCTION assert_gaming_void_final_state(
            target_session_id uuid,
            target_company_id uuid
        ) RETURNS void LANGUAGE plpgsql AS $$
        DECLARE
            target_session gaming_sessions%ROWTYPE;
            receipt gaming_void_receipts%ROWTYPE;
            active_addons integer;
            mapped_addons integer;
            retained_status text;
            retained_total bigint;
            source_status text;
            source_company uuid;
            source_branch uuid;
            source_terminal uuid;
            session_branch uuid;
            charge_order uuid;
            charge_voided_at timestamptz;
            charge_unit_price bigint;
            charge_line_total bigint;
            charge_discount bigint;
            charge_taxable bigint;
            charge_cgst bigint;
            charge_sgst bigint;
            charge_igst bigint;
            charge_cess bigint;
        BEGIN
            SELECT * INTO target_session FROM gaming_sessions
             WHERE id = target_session_id;
            IF target_session.id IS NULL
               OR target_session.company_id IS DISTINCT FROM target_company_id THEN
                RAISE EXCEPTION 'Gaming Void final state has invalid session scope'
                    USING ERRCODE = '23514';
            END IF;
            SELECT * INTO receipt FROM gaming_void_receipts
             WHERE gaming_session_id = target_session.id;
            IF target_session.status <> 'cancelled' THEN
                IF receipt.id IS NOT NULL THEN
                    RAISE EXCEPTION 'Gaming Void receipt requires a cancelled session'
                        USING ERRCODE = '23514';
                END IF;
                RETURN;
            END IF;
            SELECT count(*) INTO active_addons FROM gaming_session_addons a
             WHERE a.gaming_session_id = target_session.id
               AND a.company_id = target_session.company_id
               AND a.voided_at IS NULL;
            IF active_addons = 0 AND receipt.id IS NULL THEN
                RETURN;
            END IF;
            IF receipt.id IS NULL
               OR receipt.company_id IS DISTINCT FROM target_session.company_id
               OR receipt.retained_order_id IS DISTINCT FROM target_session.retained_addon_order_id
               OR target_session.order_id IS NOT NULL
               OR receipt.addon_count <> active_addons THEN
                RAISE EXCEPTION 'cancelled Gaming session does not match its immutable Void receipt'
                    USING ERRCODE = '23514';
            END IF;
            IF target_session.amount_minor IS DISTINCT FROM 0
               OR target_session.billable_minutes IS DISTINCT FROM 0
               OR target_session.cancelled_at IS DISTINCT FROM receipt.voided_at
               OR target_session.cancelled_by IS DISTINCT FROM receipt.actor_user_id
               OR target_session.cancel_reason IS DISTINCT FROM receipt.reason THEN
                RAISE EXCEPTION 'cancelled Gaming session facts do not match its immutable Void receipt'
                    USING ERRCODE = '23514';
            END IF;
            SELECT station.branch_id INTO session_branch
              FROM stations station WHERE station.id = target_session.station_id;
            IF session_branch IS NULL THEN
                RAISE EXCEPTION 'Gaming Void final state has invalid station scope'
                    USING ERRCODE = '23514';
            END IF;
            IF receipt.source_order_id IS NOT NULL THEN
                SELECT company_id, branch_id, terminal_id, status
                  INTO source_company, source_branch, source_terminal, source_status
                  FROM orders WHERE id = receipt.source_order_id;
                IF source_company IS DISTINCT FROM receipt.company_id
                   OR source_branch IS DISTINCT FROM session_branch
                   OR source_terminal IS DISTINCT FROM receipt.terminal_id THEN
                    RAISE EXCEPTION 'Gaming Void source order has invalid company/branch/terminal scope'
                        USING ERRCODE = '23514';
                END IF;
                SELECT line.order_id, line.voided_at, line.unit_price_minor,
                       line.line_total_minor, line.discount_minor,
                       line.taxable_value_minor, line.cgst_minor, line.sgst_minor,
                       line.igst_minor, line.cess_minor
                  INTO charge_order, charge_voided_at, charge_unit_price,
                       charge_line_total, charge_discount, charge_taxable,
                       charge_cgst, charge_sgst, charge_igst, charge_cess
                  FROM order_lines line WHERE line.id = receipt.gaming_charge_line_id;
                IF charge_order IS DISTINCT FROM receipt.source_order_id
                   OR charge_voided_at IS NULL
                   OR charge_unit_price IS DISTINCT FROM receipt.charge_unit_price_minor
                   OR charge_line_total IS DISTINCT FROM receipt.charge_line_total_minor
                   OR charge_discount IS DISTINCT FROM receipt.charge_discount_minor
                   OR charge_taxable IS DISTINCT FROM receipt.charge_taxable_value_minor
                   OR charge_cgst IS DISTINCT FROM receipt.charge_cgst_minor
                   OR charge_sgst IS DISTINCT FROM receipt.charge_sgst_minor
                   OR charge_igst IS DISTINCT FROM receipt.charge_igst_minor
                   OR charge_cess IS DISTINCT FROM receipt.charge_cess_minor THEN
                    RAISE EXCEPTION 'Gaming charge line does not match its immutable Void receipt'
                        USING ERRCODE = '23514';
                END IF;
            END IF;
            IF active_addons > 0 THEN
                SELECT status, total_minor INTO retained_status, retained_total FROM orders
                 WHERE id = target_session.retained_addon_order_id;
                IF (retained_status NOT IN ('held','paid','refunded') AND NOT (
                    retained_status = 'void' AND EXISTS (SELECT 1 FROM gaming_retained_bill_corrections c
                    WHERE c.original_receipt_id = receipt.id AND c.retained_order_id = target_session.retained_addon_order_id)))
                   OR retained_total IS DISTINCT FROM receipt.retained_total_minor THEN
                    RAISE EXCEPTION 'retained Gaming add-on bill is missing or not payable'
                        USING ERRCODE = '23514';
                END IF;
                SELECT count(*) INTO mapped_addons
                  FROM gaming_void_addon_lines map
                  JOIN gaming_session_addons addon ON addon.id = map.addon_id
                  JOIN order_lines line ON line.id = map.order_line_id
                 WHERE map.receipt_id = receipt.id
                   AND addon.gaming_session_id = target_session.id
                   AND addon.voided_at IS NULL
                   AND map.source_order_id IS NOT DISTINCT FROM receipt.source_order_id
                   AND map.retained_order_id = target_session.retained_addon_order_id
                   AND line.order_id = target_session.retained_addon_order_id
                   AND line.voided_at IS NULL
                   AND line.client_line_id = addon.client_line_id
                   AND line.menu_item_id = addon.menu_item_id
                   AND line.qty = addon.qty
                   AND line.unit_price_minor = addon.unit_price_minor
                   AND line.line_total_minor = addon.line_total_minor
                   AND line.discount_minor = addon.discount_minor
                   AND line.taxable_value_minor = addon.taxable_value_minor
                   AND line.cgst_minor = addon.cgst_minor
                   AND line.sgst_minor = addon.sgst_minor
                   AND line.igst_minor = addon.igst_minor
                   AND line.cess_minor = addon.cess_minor
                   AND line.hsn_or_sac IS NOT DISTINCT FROM addon.hsn_or_sac
                   AND line.tax_rate IS NOT DISTINCT FROM addon.tax_rate
                   AND line.menu_item_name_snapshot = addon.menu_item_name_snapshot
                   AND line.menu_item_type_snapshot = addon.menu_item_type_snapshot
                   AND line.variant_id IS NOT DISTINCT FROM addon.variant_id
                   AND line.variant_snapshot IS NOT DISTINCT FROM addon.variant_snapshot
                   AND line.modifiers IS NOT DISTINCT FROM addon.modifiers
                   AND line.note IS NOT DISTINCT FROM addon.note
                   AND line.kitchen_status = 'served'
                   AND line.kitchen_released_at IS NOT DISTINCT FROM addon.created_at
                   AND line.kitchen_served_at IS NOT DISTINCT FROM addon.created_at
                   AND line.created_at IS NOT DISTINCT FROM addon.created_at;
                IF mapped_addons <> active_addons THEN
                    RAISE EXCEPTION 'retained Gaming add-on bill is not an exact immutable bijection'
                        USING ERRCODE = '23514';
                END IF;
            ELSIF receipt.source_order_id IS NOT NULL THEN
                IF source_status IS DISTINCT FROM 'void' THEN
                    RAISE EXCEPTION 'Gaming Void without retained add-ons must void its source bill'
                        USING ERRCODE = '23514';
                END IF;
            END IF;
            RETURN;
        END $$;
        CREATE OR REPLACE FUNCTION guard_gaming_bill_void()
        RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
            IF NEW.status='void' AND OLD.status IS DISTINCT FROM 'void' THEN
                IF EXISTS (SELECT 1 FROM gaming_retained_bill_corrections c WHERE c.retained_order_id=NEW.id) THEN
                    PERFORM assert_gaming_retained_correction(NEW.id);
                ELSIF EXISTS (SELECT 1 FROM gaming_sessions gs WHERE gs.retained_addon_order_id=NEW.id)
                   OR (EXISTS (SELECT 1 FROM gaming_sessions gs WHERE gs.order_id=NEW.id)
                       AND NOT EXISTS (SELECT 1 FROM gaming_void_receipts r WHERE r.source_order_id=NEW.id)) THEN
                    RAISE EXCEPTION 'Gaming-linked orders require the dedicated Owner receipt' USING ERRCODE='23514';
                END IF;
            END IF;
            RETURN NULL;
        END $$;

    """)


def downgrade() -> None:
    # A correction is business history; rollback must never silently destroy it.
    op.execute("""LOCK TABLE gaming_retained_bill_corrections IN ACCESS EXCLUSIVE MODE;
        DO $$ BEGIN IF EXISTS (SELECT 1 FROM gaming_retained_bill_corrections) THEN
        RAISE EXCEPTION 'retained correction history prevents downgrade'; END IF; END $$;""")
    op.execute("""
        CREATE OR REPLACE FUNCTION assert_gaming_void_final_state(
            target_session_id uuid,
            target_company_id uuid
        ) RETURNS void LANGUAGE plpgsql AS $$
        DECLARE
            target_session gaming_sessions%ROWTYPE;
            receipt gaming_void_receipts%ROWTYPE;
            active_addons integer;
            mapped_addons integer;
            retained_status text;
            retained_total bigint;
            source_status text;
            source_company uuid;
            source_branch uuid;
            source_terminal uuid;
            session_branch uuid;
            charge_order uuid;
            charge_voided_at timestamptz;
            charge_unit_price bigint;
            charge_line_total bigint;
            charge_discount bigint;
            charge_taxable bigint;
            charge_cgst bigint;
            charge_sgst bigint;
            charge_igst bigint;
            charge_cess bigint;
        BEGIN
            SELECT * INTO target_session FROM gaming_sessions
             WHERE id = target_session_id;
            IF target_session.id IS NULL
               OR target_session.company_id IS DISTINCT FROM target_company_id THEN
                RAISE EXCEPTION 'Gaming Void final state has invalid session scope'
                    USING ERRCODE = '23514';
            END IF;
            SELECT * INTO receipt FROM gaming_void_receipts
             WHERE gaming_session_id = target_session.id;
            IF target_session.status <> 'cancelled' THEN
                IF receipt.id IS NOT NULL THEN
                    RAISE EXCEPTION 'Gaming Void receipt requires a cancelled session'
                        USING ERRCODE = '23514';
                END IF;
                RETURN;
            END IF;
            SELECT count(*) INTO active_addons FROM gaming_session_addons a
             WHERE a.gaming_session_id = target_session.id
               AND a.company_id = target_session.company_id
               AND a.voided_at IS NULL;
            IF active_addons = 0 AND receipt.id IS NULL THEN
                RETURN;
            END IF;
            IF receipt.id IS NULL
               OR receipt.company_id IS DISTINCT FROM target_session.company_id
               OR receipt.retained_order_id IS DISTINCT FROM target_session.retained_addon_order_id
               OR target_session.order_id IS NOT NULL
               OR receipt.addon_count <> active_addons THEN
                RAISE EXCEPTION 'cancelled Gaming session does not match its immutable Void receipt'
                    USING ERRCODE = '23514';
            END IF;
            IF target_session.amount_minor IS DISTINCT FROM 0
               OR target_session.billable_minutes IS DISTINCT FROM 0
               OR target_session.cancelled_at IS DISTINCT FROM receipt.voided_at
               OR target_session.cancelled_by IS DISTINCT FROM receipt.actor_user_id
               OR target_session.cancel_reason IS DISTINCT FROM receipt.reason THEN
                RAISE EXCEPTION 'cancelled Gaming session facts do not match its immutable Void receipt'
                    USING ERRCODE = '23514';
            END IF;
            SELECT station.branch_id INTO session_branch
              FROM stations station WHERE station.id = target_session.station_id;
            IF session_branch IS NULL THEN
                RAISE EXCEPTION 'Gaming Void final state has invalid station scope'
                    USING ERRCODE = '23514';
            END IF;
            IF receipt.source_order_id IS NOT NULL THEN
                SELECT company_id, branch_id, terminal_id, status
                  INTO source_company, source_branch, source_terminal, source_status
                  FROM orders WHERE id = receipt.source_order_id;
                IF source_company IS DISTINCT FROM receipt.company_id
                   OR source_branch IS DISTINCT FROM session_branch
                   OR source_terminal IS DISTINCT FROM receipt.terminal_id THEN
                    RAISE EXCEPTION 'Gaming Void source order has invalid company/branch/terminal scope'
                        USING ERRCODE = '23514';
                END IF;
                SELECT line.order_id, line.voided_at, line.unit_price_minor,
                       line.line_total_minor, line.discount_minor,
                       line.taxable_value_minor, line.cgst_minor, line.sgst_minor,
                       line.igst_minor, line.cess_minor
                  INTO charge_order, charge_voided_at, charge_unit_price,
                       charge_line_total, charge_discount, charge_taxable,
                       charge_cgst, charge_sgst, charge_igst, charge_cess
                  FROM order_lines line WHERE line.id = receipt.gaming_charge_line_id;
                IF charge_order IS DISTINCT FROM receipt.source_order_id
                   OR charge_voided_at IS NULL
                   OR charge_unit_price IS DISTINCT FROM receipt.charge_unit_price_minor
                   OR charge_line_total IS DISTINCT FROM receipt.charge_line_total_minor
                   OR charge_discount IS DISTINCT FROM receipt.charge_discount_minor
                   OR charge_taxable IS DISTINCT FROM receipt.charge_taxable_value_minor
                   OR charge_cgst IS DISTINCT FROM receipt.charge_cgst_minor
                   OR charge_sgst IS DISTINCT FROM receipt.charge_sgst_minor
                   OR charge_igst IS DISTINCT FROM receipt.charge_igst_minor
                   OR charge_cess IS DISTINCT FROM receipt.charge_cess_minor THEN
                    RAISE EXCEPTION 'Gaming charge line does not match its immutable Void receipt'
                        USING ERRCODE = '23514';
                END IF;
            END IF;
            IF active_addons > 0 THEN
                SELECT status, total_minor INTO retained_status, retained_total FROM orders
                 WHERE id = target_session.retained_addon_order_id;
                IF retained_status NOT IN ('held','paid','refunded')
                   OR retained_total IS DISTINCT FROM receipt.retained_total_minor THEN
                    RAISE EXCEPTION 'retained Gaming add-on bill is missing or not payable'
                        USING ERRCODE = '23514';
                END IF;
                SELECT count(*) INTO mapped_addons
                  FROM gaming_void_addon_lines map
                  JOIN gaming_session_addons addon ON addon.id = map.addon_id
                  JOIN order_lines line ON line.id = map.order_line_id
                 WHERE map.receipt_id = receipt.id
                   AND addon.gaming_session_id = target_session.id
                   AND addon.voided_at IS NULL
                   AND map.source_order_id IS NOT DISTINCT FROM receipt.source_order_id
                   AND map.retained_order_id = target_session.retained_addon_order_id
                   AND line.order_id = target_session.retained_addon_order_id
                   AND line.voided_at IS NULL
                   AND line.client_line_id = addon.client_line_id
                   AND line.menu_item_id = addon.menu_item_id
                   AND line.qty = addon.qty
                   AND line.unit_price_minor = addon.unit_price_minor
                   AND line.line_total_minor = addon.line_total_minor
                   AND line.discount_minor = addon.discount_minor
                   AND line.taxable_value_minor = addon.taxable_value_minor
                   AND line.cgst_minor = addon.cgst_minor
                   AND line.sgst_minor = addon.sgst_minor
                   AND line.igst_minor = addon.igst_minor
                   AND line.cess_minor = addon.cess_minor
                   AND line.hsn_or_sac IS NOT DISTINCT FROM addon.hsn_or_sac
                   AND line.tax_rate IS NOT DISTINCT FROM addon.tax_rate
                   AND line.menu_item_name_snapshot = addon.menu_item_name_snapshot
                   AND line.menu_item_type_snapshot = addon.menu_item_type_snapshot
                   AND line.variant_id IS NOT DISTINCT FROM addon.variant_id
                   AND line.variant_snapshot IS NOT DISTINCT FROM addon.variant_snapshot
                   AND line.modifiers IS NOT DISTINCT FROM addon.modifiers
                   AND line.note IS NOT DISTINCT FROM addon.note
                   AND line.kitchen_status = 'served'
                   AND line.kitchen_released_at IS NOT DISTINCT FROM addon.created_at
                   AND line.kitchen_served_at IS NOT DISTINCT FROM addon.created_at
                   AND line.created_at IS NOT DISTINCT FROM addon.created_at;
                IF mapped_addons <> active_addons THEN
                    RAISE EXCEPTION 'retained Gaming add-on bill is not an exact immutable bijection'
                        USING ERRCODE = '23514';
                END IF;
            ELSIF receipt.source_order_id IS NOT NULL THEN
                IF source_status IS DISTINCT FROM 'void' THEN
                    RAISE EXCEPTION 'Gaming Void without retained add-ons must void its source bill'
                        USING ERRCODE = '23514';
                END IF;
            END IF;
            RETURN;
        END $$;
        CREATE OR REPLACE FUNCTION guard_gaming_bill_void()
        RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
            IF NEW.status = 'void' AND OLD.status IS DISTINCT FROM 'void'
               AND (
                    EXISTS (SELECT 1 FROM gaming_sessions gs WHERE gs.order_id = NEW.id)
                    OR EXISTS (SELECT 1 FROM gaming_sessions gs WHERE gs.retained_addon_order_id = NEW.id)
               )
               AND NOT EXISTS (
                    SELECT 1 FROM gaming_void_receipts r
                     WHERE r.source_order_id = NEW.id
               ) THEN
                RAISE EXCEPTION 'Gaming-linked orders require the dedicated Owner Void receipt'
                    USING ERRCODE = '23514';
            END IF;
            IF NEW.status = 'void' AND OLD.status IS DISTINCT FROM 'void'
               AND EXISTS (
                    SELECT 1 FROM gaming_sessions gs
                     WHERE gs.retained_addon_order_id = NEW.id
               ) THEN
                RAISE EXCEPTION 'retained Gaming add-on bills cannot use generic order Void'
                    USING ERRCODE = '23514';
            END IF;
            RETURN NULL;
        END $$;
        DROP TRIGGER trg_benefits_retained_correction_validate ON membership_benefit_reservations;
        DROP TRIGGER trg_stock_retained_correction_validate ON stock_movements;
        DROP TRIGGER trg_refunds_retained_correction_validate ON refunds;
        DROP TRIGGER trg_payments_retained_correction_validate ON payments;
        DROP TRIGGER trg_lines_retained_correction_validate ON order_lines;
        DROP TRIGGER trg_orders_retained_correction_validate ON orders;
        DROP TRIGGER trg_retained_correction_validate ON gaming_retained_bill_corrections;
        DROP FUNCTION validate_gaming_retained_correction();
        DROP FUNCTION assert_gaming_retained_correction(uuid);
        DROP TABLE gaming_retained_bill_corrections;
        DROP FUNCTION reject_gaming_retained_correction_mutation();
    """)
