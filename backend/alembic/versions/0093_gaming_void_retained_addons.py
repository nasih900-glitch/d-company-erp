"""Retain consumed Gaming add-ons when the Gaming charge is voided.

Revision ID: 0093
Revises: 0092
"""

# Embedded PostgreSQL functions are kept visually aligned with their SQL
# counterparts; wrapping them to Python's line width makes migration review
# harder without changing executable SQL.
# ruff: noqa: E501

from alembic import op

revision = "0093"
down_revision = "0092"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("""
        ALTER TABLE gaming_sessions
        ADD COLUMN retained_addon_order_id uuid
            REFERENCES orders(id) ON DELETE RESTRICT;
        CREATE UNIQUE INDEX uq_gaming_sessions_retained_addon_order
            ON gaming_sessions(retained_addon_order_id)
            WHERE retained_addon_order_id IS NOT NULL;

        CREATE TABLE gaming_void_receipts (
            id uuid PRIMARY KEY,
            company_id uuid NOT NULL REFERENCES companies(id) ON DELETE RESTRICT,
            gaming_session_id uuid NOT NULL REFERENCES gaming_sessions(id) ON DELETE RESTRICT,
            source_order_id uuid REFERENCES orders(id) ON DELETE RESTRICT,
            retained_order_id uuid REFERENCES orders(id) ON DELETE RESTRICT,
            gaming_charge_line_id uuid REFERENCES order_lines(id) ON DELETE RESTRICT,
            charge_unit_price_minor bigint,
            charge_line_total_minor bigint,
            charge_discount_minor bigint,
            charge_taxable_value_minor bigint,
            charge_cgst_minor bigint,
            charge_sgst_minor bigint,
            charge_igst_minor bigint,
            charge_cess_minor bigint,
            actor_user_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
            terminal_id uuid NOT NULL REFERENCES terminals(id) ON DELETE RESTRICT,
            mode varchar(24) NOT NULL,
            original_session_status varchar(20) NOT NULL,
            original_amount_minor bigint NOT NULL,
            session_billing_revision integer NOT NULL,
            session_participant_revision integer NOT NULL,
            source_order_status varchar(20),
            source_checkout_version integer,
            source_total_minor bigint,
            retained_total_minor bigint NOT NULL,
            addon_count integer NOT NULL,
            reason varchar(500) NOT NULL,
            idempotency_key varchar(160) NOT NULL,
            request_hash varchar(64) NOT NULL,
            request_payload jsonb NOT NULL,
            response_payload jsonb NOT NULL,
            voided_at timestamptz NOT NULL,
            CONSTRAINT uq_gaming_void_receipt_session UNIQUE (gaming_session_id),
            CONSTRAINT uq_gaming_void_receipt_company_key UNIQUE (company_id, idempotency_key),
            CONSTRAINT ck_gaming_void_receipt_mode CHECK (
                mode IN ('unlinked_addons','linked_pos')
            ),
            CONSTRAINT ck_gaming_void_receipt_identity CHECK (
                length(trim(reason)) BETWEEN 1 AND 500
                AND length(trim(idempotency_key)) > 0
                AND request_hash ~ '^[0-9a-f]{64}$'
            ),
            CONSTRAINT ck_gaming_void_receipt_amounts CHECK (
                original_amount_minor >= 0 AND retained_total_minor >= 0
                AND addon_count >= 0 AND session_billing_revision >= 0
                AND session_participant_revision >= 0
            ),
            CONSTRAINT ck_gaming_void_receipt_source_review CHECK (
                (source_order_id IS NULL AND source_order_status IS NULL
                 AND source_checkout_version IS NULL AND source_total_minor IS NULL
                 AND gaming_charge_line_id IS NULL
                 AND charge_unit_price_minor IS NULL AND charge_line_total_minor IS NULL
                 AND charge_discount_minor IS NULL AND charge_taxable_value_minor IS NULL
                 AND charge_cgst_minor IS NULL AND charge_sgst_minor IS NULL
                 AND charge_igst_minor IS NULL AND charge_cess_minor IS NULL)
                OR
                (source_order_id IS NOT NULL AND source_order_status = 'held'
                 AND source_checkout_version > 0 AND source_total_minor >= 0
                 AND gaming_charge_line_id IS NOT NULL
                 AND charge_unit_price_minor >= 0 AND charge_line_total_minor >= 0
                 AND charge_discount_minor >= 0 AND charge_taxable_value_minor >= 0
                 AND charge_cgst_minor >= 0 AND charge_sgst_minor >= 0
                 AND charge_igst_minor >= 0 AND charge_cess_minor >= 0
                 AND charge_unit_price_minor = charge_line_total_minor
                 AND charge_discount_minor <= original_amount_minor
                 AND charge_line_total_minor >= original_amount_minor - charge_discount_minor
                 AND charge_line_total_minor = charge_taxable_value_minor
                     + charge_cgst_minor + charge_sgst_minor + charge_igst_minor
                     + charge_cess_minor)
            ),
            CONSTRAINT ck_gaming_void_receipt_retained_bill CHECK (
                (addon_count = 0 AND retained_order_id IS NULL AND retained_total_minor = 0)
                OR (addon_count > 0 AND retained_order_id IS NOT NULL)
            ),
            CONSTRAINT ck_gaming_void_receipt_mode_scope CHECK (
                (mode = 'unlinked_addons' AND source_order_id IS NULL
                 AND addon_count > 0 AND retained_order_id IS NOT NULL)
                OR (mode = 'linked_pos' AND source_order_id IS NOT NULL)
            ),
            CONSTRAINT ck_gaming_void_receipt_payloads CHECK (
                jsonb_typeof(request_payload) = 'object'
                AND jsonb_typeof(response_payload) = 'object'
            )
        );
        CREATE UNIQUE INDEX uq_gaming_void_receipt_source_order
            ON gaming_void_receipts(source_order_id)
            WHERE source_order_id IS NOT NULL;
        CREATE UNIQUE INDEX uq_gaming_void_receipt_retained_order
            ON gaming_void_receipts(retained_order_id)
            WHERE retained_order_id IS NOT NULL;

        CREATE TABLE gaming_void_addon_lines (
            id uuid PRIMARY KEY,
            receipt_id uuid NOT NULL REFERENCES gaming_void_receipts(id) ON DELETE RESTRICT,
            addon_id uuid NOT NULL REFERENCES gaming_session_addons(id) ON DELETE RESTRICT,
            order_line_id uuid NOT NULL REFERENCES order_lines(id) ON DELETE RESTRICT,
            source_order_id uuid REFERENCES orders(id) ON DELETE RESTRICT,
            retained_order_id uuid NOT NULL REFERENCES orders(id) ON DELETE RESTRICT,
            CONSTRAINT uq_gaming_void_addon_line_addon UNIQUE (addon_id),
            CONSTRAINT uq_gaming_void_addon_line_order_line UNIQUE (order_line_id),
            CONSTRAINT ck_gaming_void_addon_line_same_linked_order CHECK (
                source_order_id IS NULL OR source_order_id = retained_order_id
            )
        );
        CREATE INDEX ix_gaming_void_addon_lines_receipt_id
            ON gaming_void_addon_lines(receipt_id);

        CREATE FUNCTION guard_gaming_void_evidence_immutable()
        RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
            RAISE EXCEPTION 'gaming Void financial evidence is immutable'
                USING ERRCODE = '23514';
        END $$;
        CREATE TRIGGER trg_gaming_void_receipts_immutable
            BEFORE UPDATE OR DELETE ON gaming_void_receipts
            FOR EACH ROW EXECUTE FUNCTION guard_gaming_void_evidence_immutable();
        CREATE TRIGGER trg_gaming_void_addon_lines_immutable
            BEFORE UPDATE OR DELETE ON gaming_void_addon_lines
            FOR EACH ROW EXECUTE FUNCTION guard_gaming_void_evidence_immutable();

        CREATE FUNCTION validate_gaming_void_addon_mapping()
        RETURNS trigger LANGUAGE plpgsql AS $$
        DECLARE
            receipt gaming_void_receipts%ROWTYPE;
            addon gaming_session_addons%ROWTYPE;
            line order_lines%ROWTYPE;
        BEGIN
            SELECT * INTO receipt FROM gaming_void_receipts WHERE id = NEW.receipt_id;
            SELECT * INTO addon FROM gaming_session_addons WHERE id = NEW.addon_id;
            SELECT * INTO line FROM order_lines WHERE id = NEW.order_line_id;
            IF receipt.id IS NULL OR addon.id IS NULL OR line.id IS NULL THEN
                RAISE EXCEPTION 'Gaming Void add-on mapping references missing evidence'
                    USING ERRCODE = '23514';
            END IF;
            IF receipt.addon_count < 1
               OR addon.company_id IS DISTINCT FROM receipt.company_id
               OR addon.gaming_session_id IS DISTINCT FROM receipt.gaming_session_id
               OR addon.voided_at IS NOT NULL THEN
                RAISE EXCEPTION 'Gaming Void add-on mapping has invalid receipt or session scope'
                    USING ERRCODE = '23514';
            END IF;
            IF NEW.source_order_id IS DISTINCT FROM receipt.source_order_id
               OR NEW.retained_order_id IS DISTINCT FROM receipt.retained_order_id
               OR line.order_id IS DISTINCT FROM receipt.retained_order_id
               OR line.voided_at IS NOT NULL THEN
                RAISE EXCEPTION 'Gaming Void add-on mapping has invalid retained order scope'
                    USING ERRCODE = '23514';
            END IF;
            IF line.client_line_id IS DISTINCT FROM addon.client_line_id
               OR line.menu_item_id IS DISTINCT FROM addon.menu_item_id
               OR line.qty IS DISTINCT FROM addon.qty
               OR line.unit_price_minor IS DISTINCT FROM addon.unit_price_minor
               OR line.line_total_minor IS DISTINCT FROM addon.line_total_minor
               OR line.discount_minor IS DISTINCT FROM addon.discount_minor
               OR line.taxable_value_minor IS DISTINCT FROM addon.taxable_value_minor
               OR line.cgst_minor IS DISTINCT FROM addon.cgst_minor
               OR line.sgst_minor IS DISTINCT FROM addon.sgst_minor
               OR line.igst_minor IS DISTINCT FROM addon.igst_minor
               OR line.cess_minor IS DISTINCT FROM addon.cess_minor
               OR line.hsn_or_sac IS DISTINCT FROM addon.hsn_or_sac
               OR line.tax_rate IS DISTINCT FROM addon.tax_rate THEN
                RAISE EXCEPTION 'Gaming Void add-on mapping has changed immutable financial values'
                    USING ERRCODE = '23514';
            END IF;
            IF line.menu_item_name_snapshot IS DISTINCT FROM addon.menu_item_name_snapshot
               OR line.menu_item_type_snapshot IS DISTINCT FROM addon.menu_item_type_snapshot
               OR line.variant_id IS DISTINCT FROM addon.variant_id
               OR line.variant_snapshot IS DISTINCT FROM addon.variant_snapshot
               OR line.modifiers IS DISTINCT FROM addon.modifiers
               OR line.note IS DISTINCT FROM addon.note
               OR line.kitchen_status IS DISTINCT FROM 'served'
               OR line.kitchen_released_at IS DISTINCT FROM addon.created_at
               OR line.kitchen_served_at IS DISTINCT FROM addon.created_at
               OR line.created_at IS DISTINCT FROM addon.created_at THEN
                RAISE EXCEPTION 'Gaming Void add-on mapping has changed immutable service evidence'
                    USING ERRCODE = '23514';
            END IF;
            RETURN NEW;
        END $$;
        CREATE CONSTRAINT TRIGGER trg_gaming_void_addon_lines_validate
            AFTER INSERT ON gaming_void_addon_lines
            DEFERRABLE INITIALLY DEFERRED
            FOR EACH ROW EXECUTE FUNCTION validate_gaming_void_addon_mapping();

        CREATE FUNCTION guard_gaming_retained_order_line()
        RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
            IF (
                EXISTS (
                    SELECT 1 FROM gaming_void_addon_lines map
                     WHERE map.order_line_id = OLD.id
                )
                OR EXISTS (
                    SELECT 1 FROM gaming_void_receipts receipt
                     WHERE receipt.gaming_charge_line_id = OLD.id
                )
            ) AND (
                NEW.order_id IS DISTINCT FROM OLD.order_id
                OR NEW.client_line_id IS DISTINCT FROM OLD.client_line_id
                OR NEW.menu_item_id IS DISTINCT FROM OLD.menu_item_id
                OR NEW.menu_item_name_snapshot IS DISTINCT FROM OLD.menu_item_name_snapshot
                OR NEW.menu_item_type_snapshot IS DISTINCT FROM OLD.menu_item_type_snapshot
                OR NEW.reporting_snapshot_revision IS DISTINCT FROM OLD.reporting_snapshot_revision
                OR NEW.variant_id IS DISTINCT FROM OLD.variant_id
                OR NEW.variant_snapshot IS DISTINCT FROM OLD.variant_snapshot
                OR NEW.modifiers IS DISTINCT FROM OLD.modifiers
                OR NEW.qty IS DISTINCT FROM OLD.qty
                OR NEW.unit_price_minor IS DISTINCT FROM OLD.unit_price_minor
                OR NEW.line_total_minor IS DISTINCT FROM OLD.line_total_minor
                OR NEW.discount_minor IS DISTINCT FROM OLD.discount_minor
                OR NEW.hsn_or_sac IS DISTINCT FROM OLD.hsn_or_sac
                OR NEW.tax_rate IS DISTINCT FROM OLD.tax_rate
                OR NEW.taxable_value_minor IS DISTINCT FROM OLD.taxable_value_minor
                OR NEW.cgst_minor IS DISTINCT FROM OLD.cgst_minor
                OR NEW.sgst_minor IS DISTINCT FROM OLD.sgst_minor
                OR NEW.igst_minor IS DISTINCT FROM OLD.igst_minor
                OR NEW.cess_minor IS DISTINCT FROM OLD.cess_minor
                OR NEW.note IS DISTINCT FROM OLD.note
                OR NEW.kitchen_status IS DISTINCT FROM OLD.kitchen_status
                OR NEW.kitchen_released_at IS DISTINCT FROM OLD.kitchen_released_at
                OR NEW.kitchen_round_no IS DISTINCT FROM OLD.kitchen_round_no
                OR NEW.kitchen_served_at IS DISTINCT FROM OLD.kitchen_served_at
                OR NEW.created_at IS DISTINCT FROM OLD.created_at
                OR NEW.voided_at IS DISTINCT FROM OLD.voided_at
                OR NEW.voided_by IS DISTINCT FROM OLD.voided_by
                OR NEW.void_reason IS DISTINCT FROM OLD.void_reason
            ) THEN
                RAISE EXCEPTION 'retained Gaming add-on line evidence is immutable'
                    USING ERRCODE = '23514';
            END IF;
            RETURN NEW;
        END $$;
        CREATE TRIGGER trg_order_lines_guard_retained_gaming_evidence
            BEFORE UPDATE ON order_lines
            FOR EACH ROW EXECUTE FUNCTION guard_gaming_retained_order_line();

        CREATE FUNCTION guard_gaming_retained_addon_link()
        RETURNS trigger LANGUAGE plpgsql AS $$
        DECLARE
            retained_company uuid;
            retained_branch uuid;
            retained_terminal uuid;
            retained_shift uuid;
            retained_status text;
            retained_type text;
            station_branch uuid;
            shift_company uuid;
            shift_branch uuid;
            shift_terminal uuid;
            shift_status text;
            terminal_active boolean;
            terminal_purpose text;
        BEGIN
            IF OLD.retained_addon_order_id IS NOT NULL
               AND NEW.retained_addon_order_id IS DISTINCT FROM OLD.retained_addon_order_id THEN
                RAISE EXCEPTION 'retained Gaming add-on order is one-way immutable'
                    USING ERRCODE = '23514';
            END IF;
            IF NEW.retained_addon_order_id IS NULL
               OR NEW.retained_addon_order_id IS NOT DISTINCT FROM OLD.retained_addon_order_id THEN
                RETURN NEW;
            END IF;
            SELECT o.company_id, o.branch_id, o.terminal_id, o.shift_id, o.status, o.type
              INTO retained_company, retained_branch, retained_terminal, retained_shift,
                   retained_status, retained_type
              FROM orders o WHERE o.id = NEW.retained_addon_order_id;
            SELECT s.branch_id INTO station_branch
              FROM stations s WHERE s.id = NEW.station_id;
            SELECT sh.company_id, sh.branch_id, sh.terminal_id, sh.status
              INTO shift_company, shift_branch, shift_terminal, shift_status
              FROM shifts sh WHERE sh.id = retained_shift;
            SELECT terminal.is_active, terminal.purpose
              INTO terminal_active, terminal_purpose
              FROM terminals terminal WHERE terminal.id = retained_terminal;
            IF retained_company IS DISTINCT FROM NEW.company_id
               OR retained_branch IS DISTINCT FROM station_branch
               OR retained_status IS DISTINCT FROM 'held'
               OR retained_type IS DISTINCT FROM 'session'
               OR shift_company IS DISTINCT FROM retained_company
               OR shift_branch IS DISTINCT FROM retained_branch
               OR shift_terminal IS DISTINCT FROM retained_terminal
               OR shift_status IS DISTINCT FROM 'open'
               OR terminal_active IS DISTINCT FROM true
               OR terminal_purpose IS NULL
               OR terminal_purpose NOT IN ('cafe_pos', 'hybrid') THEN
                RAISE EXCEPTION 'retained Gaming add-on bill has invalid company/branch scope'
                    USING ERRCODE = '23514';
            END IF;
            IF OLD.order_id IS NOT NULL
               AND (NEW.retained_addon_order_id IS DISTINCT FROM OLD.order_id
                    OR NEW.order_id IS NOT NULL) THEN
                RAISE EXCEPTION 'linked Gaming Void must retain the reviewed source order'
                    USING ERRCODE = '23514';
            END IF;
            RETURN NEW;
        END $$;
        CREATE TRIGGER trg_gaming_sessions_retained_addon_link
            BEFORE UPDATE OF retained_addon_order_id ON gaming_sessions
            FOR EACH ROW EXECUTE FUNCTION guard_gaming_retained_addon_link();

        DROP TRIGGER IF EXISTS trg_gaming_sessions_guard_addon_cancel ON gaming_sessions;
        DROP FUNCTION IF EXISTS guard_gaming_session_cancel_with_addons();

        CREATE FUNCTION assert_gaming_void_final_state(
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

        CREATE FUNCTION validate_gaming_void_final_state()
        RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
            PERFORM assert_gaming_void_final_state(NEW.id, NEW.company_id);
            RETURN NULL;
        END $$;
        CREATE CONSTRAINT TRIGGER trg_gaming_sessions_validate_void
            AFTER UPDATE OF status, order_id, retained_addon_order_id ON gaming_sessions
            DEFERRABLE INITIALLY DEFERRED
            FOR EACH ROW EXECUTE FUNCTION validate_gaming_void_final_state();

        CREATE FUNCTION validate_gaming_void_receipt_session()
        RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
            PERFORM assert_gaming_void_final_state(
                NEW.gaming_session_id,
                NEW.company_id
            );
            RETURN NULL;
        END $$;
        CREATE CONSTRAINT TRIGGER trg_gaming_void_receipts_validate_session
            AFTER INSERT ON gaming_void_receipts
            DEFERRABLE INITIALLY DEFERRED
            FOR EACH ROW EXECUTE FUNCTION validate_gaming_void_receipt_session();

        CREATE FUNCTION validate_gaming_source_order_unlink()
        RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
            IF OLD.order_id IS NOT NULL AND NEW.order_id IS NULL
               AND NOT EXISTS (
                    SELECT 1 FROM gaming_void_receipts receipt
                     WHERE receipt.gaming_session_id = NEW.id
                       AND receipt.company_id = NEW.company_id
                       AND receipt.mode = 'linked_pos'
                       AND receipt.source_order_id = OLD.order_id
               ) THEN
                RAISE EXCEPTION 'linked Gaming order cannot be detached without its immutable Owner Void receipt'
                    USING ERRCODE = '23514';
            END IF;
            RETURN NULL;
        END $$;
        CREATE CONSTRAINT TRIGGER trg_gaming_sessions_validate_order_unlink
            AFTER UPDATE OF order_id ON gaming_sessions
            DEFERRABLE INITIALLY DEFERRED
            FOR EACH ROW EXECUTE FUNCTION validate_gaming_source_order_unlink();

        CREATE FUNCTION guard_gaming_bill_void()
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
        CREATE CONSTRAINT TRIGGER trg_orders_guard_gaming_bill_void
            AFTER UPDATE OF status ON orders
            DEFERRABLE INITIALLY DEFERRED
            FOR EACH ROW EXECUTE FUNCTION guard_gaming_bill_void();

        CREATE FUNCTION guard_gaming_void_order_totals()
        RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
            IF EXISTS (
                SELECT 1 FROM gaming_void_receipts receipt
                 WHERE receipt.source_order_id = OLD.id
                    OR receipt.retained_order_id = OLD.id
            ) AND (
                NEW.subtotal_minor IS DISTINCT FROM OLD.subtotal_minor
                OR NEW.discount_minor IS DISTINCT FROM OLD.discount_minor
                OR NEW.manual_discount_minor IS DISTINCT FROM OLD.manual_discount_minor
                OR NEW.points_redeemed_minor IS DISTINCT FROM OLD.points_redeemed_minor
                OR NEW.cgst_minor IS DISTINCT FROM OLD.cgst_minor
                OR NEW.sgst_minor IS DISTINCT FROM OLD.sgst_minor
                OR NEW.igst_minor IS DISTINCT FROM OLD.igst_minor
                OR NEW.cess_minor IS DISTINCT FROM OLD.cess_minor
                OR NEW.tax_minor IS DISTINCT FROM OLD.tax_minor
                OR NEW.round_off_minor IS DISTINCT FROM OLD.round_off_minor
                OR NEW.tip_minor IS DISTINCT FROM OLD.tip_minor
                OR NEW.total_minor IS DISTINCT FROM OLD.total_minor
            ) THEN
                RAISE EXCEPTION 'Gaming Void order totals are immutable financial evidence'
                    USING ERRCODE = '23514';
            END IF;
            RETURN NEW;
        END $$;
        CREATE TRIGGER trg_orders_guard_gaming_void_totals
            BEFORE UPDATE OF subtotal_minor, discount_minor, manual_discount_minor,
                points_redeemed_minor, cgst_minor, sgst_minor, igst_minor,
                cess_minor, tax_minor, round_off_minor, tip_minor, total_minor
            ON orders
            FOR EACH ROW EXECUTE FUNCTION guard_gaming_void_order_totals();

        CREATE FUNCTION guard_gaming_bill_line_void()
        RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
            IF NEW.voided_at IS NOT NULL AND OLD.voided_at IS NULL
               AND (
                    EXISTS (SELECT 1 FROM gaming_sessions gs WHERE gs.order_id = NEW.order_id)
                    OR EXISTS (SELECT 1 FROM gaming_sessions gs WHERE gs.retained_addon_order_id = NEW.order_id)
               )
               AND NOT EXISTS (
                    SELECT 1 FROM gaming_void_receipts r
                     WHERE r.source_order_id = NEW.order_id
                       AND r.gaming_charge_line_id = NEW.id
               ) THEN
                RAISE EXCEPTION 'Gaming-linked order lines require the dedicated Owner Void workflow'
                    USING ERRCODE = '23514';
            END IF;
            RETURN NULL;
        END $$;
        CREATE CONSTRAINT TRIGGER trg_order_lines_guard_gaming_void
            AFTER UPDATE OF voided_at ON order_lines
            DEFERRABLE INITIALLY DEFERRED
            FOR EACH ROW EXECUTE FUNCTION guard_gaming_bill_line_void();
    """)

    # Revision 0055 remains immutable history.  Revision 0093 replaces its
    # trigger body so add-on snapshots can also resolve through the one-way
    # retained bill link, without consulting a renamed/current catalogue item.
    op.execute("""
        CREATE OR REPLACE FUNCTION capture_order_line_reporting_snapshot()
        RETURNS trigger LANGUAGE plpgsql AS $$
        DECLARE
            sold_item_name text;
            sold_item_type text;
            sold_item_company uuid;
            sale_company uuid;
            staged_item_name text;
            staged_item_type text;
            staged_company uuid;
        BEGIN
            IF TG_OP = 'UPDATE'
               AND NEW.order_id IS NOT DISTINCT FROM OLD.order_id
               AND NEW.menu_item_id IS NOT DISTINCT FROM OLD.menu_item_id THEN
                IF NEW.menu_item_name_snapshot IS DISTINCT FROM OLD.menu_item_name_snapshot
                   OR NEW.menu_item_type_snapshot IS DISTINCT FROM OLD.menu_item_type_snapshot
                   OR NEW.reporting_snapshot_revision IS DISTINCT FROM OLD.reporting_snapshot_revision THEN
                    RAISE EXCEPTION 'sold item reporting snapshots are immutable'
                        USING ERRCODE = '23514';
                END IF;
                RETURN NEW;
            END IF;
            SELECT item.name, item.type, item.company_id
              INTO sold_item_name, sold_item_type, sold_item_company
              FROM menu_items item WHERE item.id = NEW.menu_item_id;
            SELECT sale.company_id INTO sale_company
              FROM orders sale WHERE sale.id = NEW.order_id;
            IF sold_item_name IS NULL OR char_length(trim(sold_item_name)) < 1
               OR sold_item_type IS NULL OR char_length(trim(sold_item_type)) < 1
               OR sale_company IS NULL OR sold_item_company IS DISTINCT FROM sale_company THEN
                RAISE EXCEPTION 'order line and sold item must have matching company provenance'
                    USING ERRCODE = '23514';
            END IF;
            SELECT addon.menu_item_name_snapshot, addon.menu_item_type_snapshot, addon.company_id
              INTO staged_item_name, staged_item_type, staged_company
              FROM gaming_session_addons addon
              JOIN gaming_sessions gs ON gs.id = addon.gaming_session_id
               AND (gs.order_id = NEW.order_id OR gs.retained_addon_order_id = NEW.order_id)
             WHERE addon.client_line_id = NEW.client_line_id
               AND addon.menu_item_id = NEW.menu_item_id
               AND addon.voided_at IS NULL
             LIMIT 1;
            IF staged_company IS NOT NULL THEN
                IF staged_company IS DISTINCT FROM sale_company
                   OR (NEW.menu_item_name_snapshot IS NOT NULL
                       AND NEW.menu_item_name_snapshot IS DISTINCT FROM staged_item_name)
                   OR (NEW.menu_item_type_snapshot IS NOT NULL
                       AND NEW.menu_item_type_snapshot IS DISTINCT FROM staged_item_type) THEN
                    RAISE EXCEPTION 'gaming add-on order-line snapshot does not match its immutable source'
                        USING ERRCODE = '23514';
                END IF;
                NEW.menu_item_name_snapshot := staged_item_name;
                NEW.menu_item_type_snapshot := staged_item_type;
                NEW.reporting_snapshot_revision := 49;
                RETURN NEW;
            END IF;
            IF TG_OP = 'INSERT' AND (
                (NEW.menu_item_name_snapshot IS NOT NULL AND NEW.menu_item_name_snapshot IS DISTINCT FROM sold_item_name)
                OR (NEW.menu_item_type_snapshot IS NOT NULL AND NEW.menu_item_type_snapshot IS DISTINCT FROM sold_item_type)
            ) THEN
                RAISE EXCEPTION 'client-supplied sold item snapshot does not match the catalogue'
                    USING ERRCODE = '23514';
            END IF;
            NEW.menu_item_name_snapshot := sold_item_name;
            NEW.menu_item_type_snapshot := sold_item_type;
            NEW.reporting_snapshot_revision := 49;
            RETURN NEW;
        END $$;
    """)


def downgrade() -> None:
    op.execute("""
        LOCK TABLE gaming_sessions, gaming_void_receipts, gaming_void_addon_lines
            IN ACCESS EXCLUSIVE MODE;
        DO $$ BEGIN
            IF EXISTS (SELECT 1 FROM gaming_void_receipts)
               OR EXISTS (SELECT 1 FROM gaming_void_addon_lines)
               OR EXISTS (SELECT 1 FROM gaming_sessions WHERE retained_addon_order_id IS NOT NULL) THEN
                RAISE EXCEPTION 'cannot downgrade 0093 after Gaming Void retention evidence exists';
            END IF;
        END $$;
    """)
    op.execute("DROP TRIGGER IF EXISTS trg_order_lines_guard_gaming_void ON order_lines")
    op.execute("DROP FUNCTION IF EXISTS guard_gaming_bill_line_void()")
    op.execute("DROP TRIGGER IF EXISTS trg_orders_guard_gaming_void_totals ON orders")
    op.execute("DROP FUNCTION IF EXISTS guard_gaming_void_order_totals()")
    op.execute("DROP TRIGGER IF EXISTS trg_order_lines_guard_retained_gaming_evidence ON order_lines")
    op.execute("DROP FUNCTION IF EXISTS guard_gaming_retained_order_line()")
    op.execute("DROP TRIGGER IF EXISTS trg_orders_guard_gaming_bill_void ON orders")
    op.execute("DROP FUNCTION IF EXISTS guard_gaming_bill_void()")
    op.execute("DROP TRIGGER IF EXISTS trg_gaming_sessions_validate_order_unlink ON gaming_sessions")
    op.execute("DROP FUNCTION IF EXISTS validate_gaming_source_order_unlink()")
    op.execute("DROP TRIGGER IF EXISTS trg_gaming_void_receipts_validate_session ON gaming_void_receipts")
    op.execute("DROP FUNCTION IF EXISTS validate_gaming_void_receipt_session()")
    op.execute("DROP TRIGGER IF EXISTS trg_gaming_sessions_validate_void ON gaming_sessions")
    op.execute("DROP FUNCTION IF EXISTS validate_gaming_void_final_state()")
    op.execute("DROP FUNCTION IF EXISTS assert_gaming_void_final_state(uuid, uuid)")
    op.execute("DROP TRIGGER IF EXISTS trg_gaming_sessions_retained_addon_link ON gaming_sessions")
    op.execute("DROP FUNCTION IF EXISTS guard_gaming_retained_addon_link()")
    op.execute("DROP TRIGGER IF EXISTS trg_gaming_void_addon_lines_immutable ON gaming_void_addon_lines")
    op.execute("DROP TRIGGER IF EXISTS trg_gaming_void_addon_lines_validate ON gaming_void_addon_lines")
    op.execute("DROP FUNCTION IF EXISTS validate_gaming_void_addon_mapping()")
    op.execute("DROP TRIGGER IF EXISTS trg_gaming_void_receipts_immutable ON gaming_void_receipts")
    op.execute("DROP FUNCTION IF EXISTS guard_gaming_void_evidence_immutable()")
    op.execute("DROP INDEX IF EXISTS ix_gaming_void_addon_lines_receipt_id")
    op.execute("DROP TABLE gaming_void_addon_lines")
    op.execute("DROP INDEX IF EXISTS uq_gaming_void_receipt_retained_order")
    op.execute("DROP INDEX IF EXISTS uq_gaming_void_receipt_source_order")
    op.execute("DROP TABLE gaming_void_receipts")
    op.execute("DROP INDEX IF EXISTS uq_gaming_sessions_retained_addon_order")
    op.execute("ALTER TABLE gaming_sessions DROP COLUMN retained_addon_order_id")
    op.execute("""
        CREATE OR REPLACE FUNCTION capture_order_line_reporting_snapshot()
        RETURNS trigger LANGUAGE plpgsql AS $$
        DECLARE
            sold_item_name text;
            sold_item_type text;
            sold_item_company uuid;
            sale_company uuid;
            staged_item_name text;
            staged_item_type text;
            staged_company uuid;
        BEGIN
            IF TG_OP = 'UPDATE'
               AND NEW.order_id IS NOT DISTINCT FROM OLD.order_id
               AND NEW.menu_item_id IS NOT DISTINCT FROM OLD.menu_item_id THEN
                IF NEW.menu_item_name_snapshot IS DISTINCT FROM OLD.menu_item_name_snapshot
                   OR NEW.menu_item_type_snapshot IS DISTINCT FROM OLD.menu_item_type_snapshot
                   OR NEW.reporting_snapshot_revision IS DISTINCT FROM OLD.reporting_snapshot_revision THEN
                    RAISE EXCEPTION 'sold item reporting snapshots are immutable'
                        USING ERRCODE = '23514';
                END IF;
                RETURN NEW;
            END IF;
            SELECT item.name, item.type, item.company_id
              INTO sold_item_name, sold_item_type, sold_item_company
              FROM menu_items item WHERE item.id = NEW.menu_item_id;
            SELECT sale.company_id INTO sale_company
              FROM orders sale WHERE sale.id = NEW.order_id;
            IF sold_item_name IS NULL OR char_length(trim(sold_item_name)) < 1
               OR sold_item_type IS NULL OR char_length(trim(sold_item_type)) < 1
               OR sale_company IS NULL OR sold_item_company IS DISTINCT FROM sale_company THEN
                RAISE EXCEPTION 'order line and sold item must have matching company provenance'
                    USING ERRCODE = '23514';
            END IF;
            SELECT addon.menu_item_name_snapshot, addon.menu_item_type_snapshot, addon.company_id
              INTO staged_item_name, staged_item_type, staged_company
              FROM gaming_session_addons addon
              JOIN gaming_sessions gs ON gs.id = addon.gaming_session_id
               AND gs.order_id = NEW.order_id
             WHERE addon.client_line_id = NEW.client_line_id
               AND addon.menu_item_id = NEW.menu_item_id
               AND addon.voided_at IS NULL
             LIMIT 1;
            IF staged_company IS NOT NULL THEN
                IF staged_company IS DISTINCT FROM sale_company
                   OR (NEW.menu_item_name_snapshot IS NOT NULL
                       AND NEW.menu_item_name_snapshot IS DISTINCT FROM staged_item_name)
                   OR (NEW.menu_item_type_snapshot IS NOT NULL
                       AND NEW.menu_item_type_snapshot IS DISTINCT FROM staged_item_type) THEN
                    RAISE EXCEPTION 'gaming add-on order-line snapshot does not match its immutable source'
                        USING ERRCODE = '23514';
                END IF;
                NEW.menu_item_name_snapshot := staged_item_name;
                NEW.menu_item_type_snapshot := staged_item_type;
                NEW.reporting_snapshot_revision := 49;
                RETURN NEW;
            END IF;
            IF TG_OP = 'INSERT' AND (
                (NEW.menu_item_name_snapshot IS NOT NULL AND NEW.menu_item_name_snapshot IS DISTINCT FROM sold_item_name)
                OR (NEW.menu_item_type_snapshot IS NOT NULL AND NEW.menu_item_type_snapshot IS DISTINCT FROM sold_item_type)
            ) THEN
                RAISE EXCEPTION 'client-supplied sold item snapshot does not match the catalogue'
                    USING ERRCODE = '23514';
            END IF;
            NEW.menu_item_name_snapshot := sold_item_name;
            NEW.menu_item_type_snapshot := sold_item_type;
            NEW.reporting_snapshot_revision := 49;
            RETURN NEW;
        END $$;
    """)
    # Restore revision 0055's original immediate cancel guard. Downgrade is
    # refused above once any V2 evidence exists, so its stricter rule is safe.
    op.execute("""
        CREATE FUNCTION guard_gaming_session_cancel_with_addons()
        RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
            IF NEW.status = 'cancelled' AND OLD.status IS DISTINCT FROM 'cancelled'
               AND EXISTS (
                    SELECT 1 FROM gaming_session_addons addon
                     WHERE addon.gaming_session_id = OLD.id
                       AND addon.company_id = OLD.company_id
                       AND addon.voided_at IS NULL
               ) THEN
                RAISE EXCEPTION 'active Gaming add-ons must be voided before session cancellation'
                    USING ERRCODE = '23514';
            END IF;
            RETURN NEW;
        END $$;
        CREATE TRIGGER trg_gaming_sessions_guard_addon_cancel
            BEFORE UPDATE OF status ON gaming_sessions
            FOR EACH ROW EXECUTE FUNCTION guard_gaming_session_cancel_with_addons();
    """)
