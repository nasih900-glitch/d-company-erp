"""Record immutable full no-value POS corrections without customer payouts.

Revision ID: 0097
Revises: 0096
"""

from alembic import op

revision = "0097"
down_revision = "0096"
branch_labels = None
depends_on = None


SOURCE_FUNCTION_SQL = """
CREATE FUNCTION dcompany_pos_receipt_source(p_order uuid,p_company uuid)
RETURNS jsonb LANGUAGE sql STABLE SET timezone='UTC' AS $$

    SELECT jsonb_build_object(
        'order',to_jsonb(o),'shift',to_jsonb(s),
        'branch',jsonb_build_object('id',b.id,'company_id',b.company_id,
            'timezone',b.timezone,'deleted_at',b.deleted_at),
        'terminal',jsonb_build_object('id',t.id,'branch_id',t.branch_id),
        'company',jsonb_build_object('id',c.id,'timezone',c.timezone,
            'currency',c.currency,'currency_minor_units',c.currency_minor_units,
            'collection_timezone',coalesce(c.google_sheets_summary_timezone,c.timezone)),
        'lines',coalesce((SELECT jsonb_agg(to_jsonb(l) ORDER BY l.id)
            FROM order_lines l WHERE l.order_id=o.id),'[]'::jsonb),
        'payments',coalesce((SELECT jsonb_agg(to_jsonb(p) ORDER BY p.id)
            FROM payments p WHERE p.order_id=o.id),'[]'::jsonb),
        'refunds',coalesce((SELECT jsonb_agg(to_jsonb(r) ORDER BY r.id)
            FROM refunds r WHERE r.order_id=o.id),'[]'::jsonb),
        'refund_requests',coalesce((SELECT jsonb_agg(to_jsonb(r) ORDER BY r.id)
            FROM pos_refund_requests r WHERE r.order_id=o.id),'[]'::jsonb),
        'tender_corrections',coalesce((SELECT jsonb_agg(to_jsonb(r) ORDER BY r.id)
            FROM payment_tender_corrections r WHERE r.order_id=o.id),'[]'::jsonb),
        'corrections',coalesce((SELECT jsonb_agg(to_jsonb(r) ORDER BY r.id)
            FROM pos_receipt_corrections r WHERE r.order_id=o.id),'[]'::jsonb),
        'active_claims',coalesce((SELECT jsonb_agg(to_jsonb(r) ORDER BY r.id)
            FROM order_checkout_claims r WHERE r.order_id=o.id
            AND r.expires_at>statement_timestamp()),'[]'::jsonb),
        'stock_movements',coalesce((SELECT jsonb_agg(to_jsonb(r) ORDER BY r.id)
            FROM stock_movements r WHERE r.ref_id=o.id),'[]'::jsonb),
        'benefits',coalesce((SELECT jsonb_agg(to_jsonb(r) ORDER BY r.id)
            FROM membership_benefit_reservations r WHERE r.order_id=o.id),'[]'::jsonb),
        'points_redemptions',coalesce((SELECT jsonb_agg(to_jsonb(r) ORDER BY r.id)
            FROM points_redemptions r WHERE r.order_id=o.id),'[]'::jsonb),
        'loyalty_settlements',coalesce((SELECT jsonb_agg(to_jsonb(r) ORDER BY r.id)
            FROM order_loyalty_settlements r WHERE r.order_id=o.id),'[]'::jsonb),
        'rank_awards',coalesce((SELECT jsonb_agg(to_jsonb(r) ORDER BY r.id)
            FROM customer_gaming_rank_awards r WHERE r.order_id=o.id),'[]'::jsonb),
        'gaming_sessions',coalesce((SELECT jsonb_agg(to_jsonb(g) ORDER BY g.id)
            FROM gaming_sessions g WHERE g.order_id=o.id
            OR g.retained_addon_order_id=o.id),'[]'::jsonb),
        'gaming_voids',coalesce((SELECT jsonb_agg(to_jsonb(v) ORDER BY v.id)
            FROM gaming_void_receipts v WHERE v.source_order_id=o.id
            OR v.retained_order_id=o.id),'[]'::jsonb),
        'gaming_addons',coalesce((SELECT jsonb_agg(to_jsonb(a) ORDER BY a.id)
            FROM gaming_session_addons a WHERE a.gaming_session_id IN (
            SELECT g.id FROM gaming_sessions g WHERE g.order_id=o.id
            OR g.retained_addon_order_id=o.id)),'[]'::jsonb),
        'gaming_stations',coalesce((SELECT jsonb_agg(to_jsonb(st) ORDER BY st.id)
            FROM stations st WHERE st.id IN (SELECT g.station_id FROM gaming_sessions g
            WHERE g.order_id=o.id OR g.retained_addon_order_id=o.id)),'[]'::jsonb),
        'gaming_links',coalesce((SELECT jsonb_agg(jsonb_build_object(
            'link',to_jsonb(v),'addon',to_jsonb(a)) ORDER BY v.id)
            FROM gaming_void_addon_lines v JOIN gaming_session_addons a ON a.id=v.addon_id
            WHERE v.retained_order_id=o.id),'[]'::jsonb)
    ) AS source
    FROM orders o JOIN shifts s ON s.id=o.shift_id
    JOIN branches b ON b.id=o.branch_id JOIN terminals t ON t.id=o.terminal_id
    JOIN companies c ON c.id=o.company_id
    WHERE o.id=p_order AND o.company_id=p_company

$$;
"""


GUARDS_SQL = """
CREATE FUNCTION dcompany_assert_no_value_receipt(src jsonb) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE o jsonb; s jsonb; p jsonb; l jsonb; a jsonb; v jsonb; g jsonb;
 link jsonb; station jsonb; k text; total bigint; line_sum bigint:=0;
 has_gaming boolean; source_count integer; linked_count integer:=0;
BEGIN
 IF src IS NULL THEN RAISE EXCEPTION 'invalid receipt correction source' USING ERRCODE='23514'; END IF;
 o:=src->'order'; s:=src->'shift'; total:=(o->>'total_minor')::bigint;
 IF (o->>'status'='paid' AND o->>'invoice_no' IS NOT NULL
  AND o->>'invoice_issued_at' IS NOT NULL AND o->>'fiscal_year' IS NOT NULL
  AND o->>'closed_at' IS NOT NULL AND (o->>'source_integrity_revision')::int=48
  AND total>0 AND (o->>'subtotal_minor')::bigint=total
  AND s->>'id'=o->>'shift_id' AND s->>'company_id'=o->>'company_id'
  AND s->>'branch_id'=o->>'branch_id' AND s->>'terminal_id'=o->>'terminal_id'
  AND s->>'status'='closed' AND s->>'closed_at' IS NOT NULL
  AND src->'branch'->>'company_id'=o->>'company_id'
  AND src->'branch'->>'deleted_at' IS NULL
  AND src->'terminal'->>'branch_id'=o->>'branch_id') IS DISTINCT FROM TRUE THEN
  RAISE EXCEPTION 'receipt correction requires an issued complete closed-shift invoice' USING ERRCODE='23514';
 END IF;
 IF jsonb_array_length(src->'payments')<>1 THEN
  RAISE EXCEPTION 'receipt correction requires one full manual UPI payment' USING ERRCODE='23514'; END IF;
 p:=src->'payments'->0;
 IF (p->>'method'='upi' AND (p->>'amount_minor')::bigint=total
  AND p->>'order_id'=o->>'id' AND p->>'shift_id'=s->>'id'
  AND (p->>'source_integrity_revision')::int=48
  AND coalesce(p->>'ref_external','')='' AND p->>'tendered_minor' IS NULL
  AND p->>'change_minor' IS NULL) IS DISTINCT FROM TRUE THEN
  RAISE EXCEPTION 'receipt correction requires one full manual UPI payment' USING ERRCODE='23514'; END IF;
 FOREACH k IN ARRAY ARRAY['tax_minor','cgst_minor','sgst_minor','igst_minor','cess_minor',
  'tip_minor','round_off_minor','discount_minor','manual_discount_minor','points_redeemed_minor'] LOOP
  IF coalesce((o->>k)::bigint,0)<>0 THEN
   RAISE EXCEPTION 'unsupported complex receipt correction' USING ERRCODE='23514'; END IF;
 END LOOP;
 FOREACH k IN ARRAY ARRAY['irn','irn_ack_no','irn_acknowledged_at','e_invoice_qr','customer_id','customer_phone'] LOOP
  IF nullif(o->>k,'') IS NOT NULL THEN
   RAISE EXCEPTION 'unsupported invoice or customer effects' USING ERRCODE='23514'; END IF;
 END LOOP;
 FOREACH k IN ARRAY ARRAY['refunds','refund_requests','tender_corrections','corrections',
  'active_claims','stock_movements','benefits','points_redemptions','loyalty_settlements','rank_awards'] LOOP
  IF jsonb_array_length(src->k)<>0 THEN
   RAISE EXCEPTION 'recorded % prevents receipt correction',k USING ERRCODE='23514'; END IF;
 END LOOP;
 source_count:=jsonb_array_length(src->'lines');
 IF source_count<1 THEN RAISE EXCEPTION 'empty receipt correction source' USING ERRCODE='23514'; END IF;
 has_gaming:=o->>'type'='session' OR jsonb_array_length(src->'gaming_sessions')>0
  OR jsonb_array_length(src->'gaming_voids')>0 OR jsonb_array_length(src->'gaming_links')>0;
 IF has_gaming THEN
  IF jsonb_array_length(src->'gaming_sessions')<>1 OR jsonb_array_length(src->'gaming_voids')<>1
   OR jsonb_array_length(src->'gaming_stations')<>1
   OR jsonb_array_length(src->'gaming_links')<>source_count THEN
   RAISE EXCEPTION 'unsupported Gaming correction provenance' USING ERRCODE='23514'; END IF;
  g:=src->'gaming_sessions'->0; v:=src->'gaming_voids'->0; station:=src->'gaming_stations'->0;
  IF (o->>'type'='session' AND v->>'mode'='unlinked_addons'
   AND v->>'company_id'=o->>'company_id' AND v->>'retained_order_id'=o->>'id'
   AND v->>'source_order_id' IS NULL AND v->>'gaming_session_id'=g->>'id'
   AND g->>'company_id'=o->>'company_id' AND g->>'status'='cancelled'
   AND g->>'order_id' IS NULL AND g->>'retained_addon_order_id'=o->>'id'
   AND (g->>'amount_minor')::bigint=0 AND (g->>'billable_minutes')::int=0
   AND g->>'cancelled_at'=v->>'voided_at' AND g->>'cancelled_by'=v->>'actor_user_id'
   AND g->>'cancel_reason'=v->>'reason' AND station->>'id'=g->>'station_id'
   AND station->>'company_id'=o->>'company_id' AND station->>'branch_id'=o->>'branch_id'
   AND (v->>'retained_total_minor')::bigint=total AND (v->>'addon_count')::int=source_count
  ) IS DISTINCT FROM TRUE THEN
   RAISE EXCEPTION 'unsupported Gaming correction provenance' USING ERRCODE='23514'; END IF;
  IF (SELECT count(*) FROM jsonb_array_elements(src->'gaming_addons') x
      WHERE x->>'voided_at' IS NULL)<>source_count THEN
   RAISE EXCEPTION 'unmapped active Gaming value prevents correction' USING ERRCODE='23514'; END IF;
 END IF;
 FOR l IN SELECT value FROM jsonb_array_elements(src->'lines') LOOP
  IF (l->>'menu_item_type_snapshot' IN ('food','drink','dessert')
   AND length(trim(l->>'menu_item_name_snapshot'))>0 AND l->>'voided_at' IS NULL
   AND (l->>'qty')::numeric>0 AND (l->>'unit_price_minor')::bigint>0
   AND coalesce((l->>'tax_rate')::numeric,0) BETWEEN 0 AND 1
   AND (l->>'line_total_minor')::bigint>0
   AND (l->>'qty')::numeric*(l->>'unit_price_minor')::bigint=(l->>'line_total_minor')::bigint
   AND (l->>'taxable_value_minor')::bigint=(l->>'line_total_minor')::bigint
   AND l->>'variant_snapshot' IS NULL AND coalesce(l->'modifiers','null') IN ('null','[]')
  ) IS DISTINCT FROM TRUE THEN
   RAISE EXCEPTION 'unsupported plain-product correction line' USING ERRCODE='23514'; END IF;
  FOREACH k IN ARRAY ARRAY['discount_minor','cgst_minor','sgst_minor','igst_minor','cess_minor'] LOOP
   IF coalesce((l->>k)::numeric,0)<>0 THEN
    RAISE EXCEPTION 'unsupported taxed or discounted correction line' USING ERRCODE='23514'; END IF;
  END LOOP;
  IF has_gaming THEN
   SELECT value INTO link FROM jsonb_array_elements(src->'gaming_links') x
    WHERE x->'link'->>'order_line_id'=l->>'id';
   a:=link->'addon'; link:=link->'link';
   IF (link->>'receipt_id'=v->>'id' AND link->>'retained_order_id'=o->>'id'
    AND link->>'source_order_id' IS NULL AND link->>'addon_id'=a->>'id'
    AND a->>'company_id'=o->>'company_id' AND a->>'gaming_session_id'=g->>'id'
    AND a->>'voided_at' IS NULL AND a->>'created_at' IS NOT NULL
    AND l->>'created_at'=a->>'created_at' AND l->>'kitchen_released_at'=a->>'created_at'
    AND l->>'kitchen_served_at'=a->>'created_at' AND l->>'kitchen_status'='served'
    AND (l->>'kitchen_round_no')::int=1) IS DISTINCT FROM TRUE THEN
    RAISE EXCEPTION 'retained product materialization does not match' USING ERRCODE='23514'; END IF;
   FOREACH k IN ARRAY ARRAY['menu_item_id','client_line_id','menu_item_name_snapshot',
    'menu_item_type_snapshot','qty','unit_price_minor','line_total_minor','discount_minor',
    'taxable_value_minor','cgst_minor','sgst_minor','igst_minor','cess_minor','variant_snapshot',
    'modifiers','variant_id','hsn_or_sac','tax_rate','note'] LOOP
    IF a->k IS DISTINCT FROM l->k THEN
     RAISE EXCEPTION 'retained product snapshot does not match' USING ERRCODE='23514'; END IF;
   END LOOP;
   linked_count:=linked_count+1;
  ELSIF l->>'kitchen_released_at' IS NOT NULL OR l->>'kitchen_served_at' IS NOT NULL THEN
   RAISE EXCEPTION 'unsupported kitchen history prevents correction' USING ERRCODE='23514';
  END IF;
  line_sum:=line_sum+(l->>'line_total_minor')::bigint;
 END LOOP;
 IF line_sum<>total OR (has_gaming AND linked_count<>source_count) THEN
  RAISE EXCEPTION 'receipt correction source total does not match' USING ERRCODE='23514'; END IF;
END $$;

CREATE OR REPLACE FUNCTION dcompany_guard_pos_receipt_correction() RETURNS trigger
LANGUAGE plpgsql SET timezone='UTC' AS $$
DECLARE src jsonb; o orders%ROWTYPE; s shifts%ROWTYPE; p payments%ROWTYPE; zone text;
BEGIN
 IF TG_OP<>'INSERT' THEN
  RAISE EXCEPTION 'POS receipt corrections are append-only' USING ERRCODE='23514'; END IF;
 IF NEW.order_id IS NULL OR NEW.payment_id IS NULL OR NEW.company_id IS NULL THEN
  RAISE EXCEPTION 'invalid receipt correction source' USING ERRCODE='23514'; END IF;
 SELECT * INTO STRICT o FROM orders WHERE id=NEW.order_id FOR UPDATE;
 SELECT * INTO STRICT s FROM shifts WHERE id=o.shift_id FOR UPDATE;
 SELECT * INTO STRICT p FROM payments WHERE id=NEW.payment_id FOR UPDATE;
 PERFORM 1 FROM companies WHERE id=NEW.company_id FOR SHARE;
 PERFORM dcompany_collection_lock(NEW.company_id);
 PERFORM 1 FROM users WHERE id=NEW.recorded_by AND company_id=NEW.company_id
  AND status='active' AND deleted_at IS NULL FOR SHARE;
 IF NOT FOUND OR NOT EXISTS(SELECT 1 FROM user_roles ur JOIN roles r ON r.id=ur.role_id
  WHERE ur.user_id=NEW.recorded_by AND r.company_id=NEW.company_id AND r.code='super_owner') THEN
  RAISE EXCEPTION 'protected Owner receipt correction authority required' USING ERRCODE='23514'; END IF;
 src:=dcompany_pos_receipt_source(NEW.order_id,NEW.company_id);
 PERFORM dcompany_assert_no_value_receipt(src);
 SELECT coalesce(google_sheets_summary_timezone,timezone) INTO zone FROM companies WHERE id=NEW.company_id;
 IF (NEW.company_id=o.company_id AND NEW.branch_id=o.branch_id
  AND NEW.terminal_id=o.terminal_id AND NEW.original_shift_id=s.id
  AND p.order_id=o.id AND p.shift_id=s.id AND NEW.amount_minor=o.total_minor
  AND NEW.original_method='upi' AND NEW.invoice_no=o.invoice_no AND NEW.fiscal_year=o.fiscal_year
  AND NEW.no_funds_received AND NEW.all_items_not_supplied AND NEW.acknowledges_no_payout
  AND NEW.source_snapshot=src
  AND NEW.source_fingerprint=encode(sha256(convert_to(src::text,'UTF8')),'hex')
  AND NEW.recorded_at=NEW.accounting_at AND NEW.business_timezone=zone
  AND NEW.business_date=(NEW.accounting_at AT TIME ZONE zone)::date
  AND abs(extract(epoch FROM (clock_timestamp()-NEW.accounting_at)))<=30
  AND NEW.request_path='/api/v1/pos/receipt-corrections/commit'
  AND NEW.request_payload=jsonb_build_object('order_id',NEW.order_id,
   'source_fingerprint',NEW.source_fingerprint,'no_funds_received',true,
   'all_items_not_supplied',true,'acknowledges_no_payout',true,'reason',NEW.reason)
  AND NEW.request_hash=encode(sha256(convert_to(NEW.request_payload::text,'UTF8')),'hex')
  AND NEW.response_payload-'accounting_at'=jsonb_build_object(
   'id',NEW.id,'order_id',o.id,'payment_id',p.id,'original_shift_id',s.id,
   'invoice_no',o.invoice_no,'kind',NEW.kind,'amount_minor',NEW.amount_minor,
   'payout_minor',0,'original_records_unchanged',true)
  AND (NEW.response_payload->>'accounting_at')::timestamptz=NEW.accounting_at
 ) IS DISTINCT FROM TRUE THEN
  RAISE EXCEPTION 'receipt correction evidence, time, or scope changed' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;

CREATE FUNCTION dcompany_guard_corrected_pos_source() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target uuid; c uuid;
BEGIN
 target:=(CASE WHEN TG_TABLE_NAME='stock_movements'
  THEN to_jsonb(NEW)->>'ref_id' ELSE to_jsonb(NEW)->>'order_id' END)::uuid;
 IF target IS NULL THEN RETURN NEW; END IF;
 SELECT company_id INTO c FROM orders WHERE id=target FOR UPDATE;
 IF EXISTS(SELECT 1 FROM pos_receipt_corrections WHERE order_id=target AND company_id=c) THEN
  RAISE EXCEPTION 'no-value corrected receipt cannot create another financial or stock effect'
   USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER zz_collection_fence AFTER INSERT ON pos_receipt_corrections
 FOR EACH ROW EXECUTE FUNCTION dcompany_collection_fence();
"""


COLLECTIONS_SQL = """
ALTER FUNCTION dcompany_collection_day_snapshot(uuid,uuid,date,text)
 RENAME TO dcompany_collection_day_snapshot_before_pos_correction;
CREATE FUNCTION dcompany_collection_day_snapshot(p_company uuid,p_branch uuid,p_day date,p_zone text)
RETURNS jsonb LANGUAGE sql STABLE SET timezone='UTC' AS $$
 WITH original AS (
  SELECT dcompany_collection_day_snapshot_before_pos_correction(p_company,p_branch,p_day,p_zone) value
 ), corrections AS (
  SELECT coalesce(sum(amount_minor),0)::bigint amount,count(*)::bigint n
  FROM pos_receipt_corrections WHERE company_id=p_company AND branch_id=p_branch
  AND (accounting_at AT TIME ZONE p_zone)::date=p_day
 ) SELECT value || jsonb_build_object(
  'upi_minor',(value->>'upi_minor')::bigint-amount,
  'total_minor',(value->>'total_minor')::bigint-amount,
  'movement_count',(value->>'movement_count')::bigint+n,
  'receipt_corrections_minor',amount,'receipt_corrections_count',n)
 FROM original CROSS JOIN corrections
$$;
ALTER FUNCTION dcompany_collection_fingerprint(uuid,uuid,date,text)
 RENAME TO dcompany_collection_fingerprint_before_pos_correction;
CREATE FUNCTION dcompany_collection_fingerprint(p_company uuid,p_branch uuid,p_day date,p_zone text)
RETURNS text LANGUAGE sql VOLATILE SET timezone='UTC' AS $$
 SELECT encode(sha256(convert_to(jsonb_build_object(
  'base',dcompany_collection_fingerprint_before_pos_correction(p_company,p_branch,p_day,p_zone),
  'receipt_corrections',(SELECT coalesce(jsonb_agg(jsonb_build_object(
   'id',id,'order_id',order_id,'payment_id',payment_id,'amount_minor',amount_minor,
   'accounting_at',accounting_at,'source_fingerprint',source_fingerprint,'request_hash',request_hash)
   ORDER BY id),'[]'::jsonb) FROM pos_receipt_corrections WHERE company_id=p_company)
 )::text,'UTF8')),'hex')
$$;
CREATE FUNCTION dcompany_pos_correction_configuration_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM pos_receipt_corrections WHERE company_id=OLD.id) AND
  (NEW.timezone,NEW.currency,NEW.currency_minor_units,NEW.google_sheets_summary_timezone)
  IS DISTINCT FROM (OLD.timezone,OLD.currency,OLD.currency_minor_units,OLD.google_sheets_summary_timezone) THEN
  RAISE EXCEPTION 'receipt correction collection-date evidence requires explicit configuration migration'
   USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER aa_pos_correction_configuration BEFORE UPDATE OF timezone,currency,
 currency_minor_units,google_sheets_summary_timezone ON companies
 FOR EACH ROW EXECUTE FUNCTION dcompany_pos_correction_configuration_guard();
"""


def upgrade() -> None:
    op.execute("""
        CREATE TABLE pos_receipt_corrections (
            id uuid PRIMARY KEY,
            company_id uuid NOT NULL REFERENCES companies(id) ON DELETE RESTRICT,
            order_id uuid NOT NULL REFERENCES orders(id) ON DELETE RESTRICT,
            payment_id uuid NOT NULL REFERENCES payments(id) ON DELETE RESTRICT,
            branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT,
            terminal_id uuid NOT NULL REFERENCES terminals(id) ON DELETE RESTRICT,
            original_shift_id uuid NOT NULL REFERENCES shifts(id) ON DELETE RESTRICT,
            recorded_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
            kind varchar(48) NOT NULL,
            original_method varchar(20) NOT NULL,
            invoice_no varchar(32) NOT NULL,
            fiscal_year varchar(7) NOT NULL,
            amount_minor bigint NOT NULL,
            no_funds_received boolean NOT NULL,
            all_items_not_supplied boolean NOT NULL,
            acknowledges_no_payout boolean NOT NULL,
            reason varchar(500) NOT NULL,
            idempotency_key varchar(160) NOT NULL,
            request_hash varchar(64) NOT NULL,
            request_path varchar(200) NOT NULL,
            source_fingerprint varchar(64) NOT NULL,
            source_snapshot jsonb NOT NULL,
            request_payload jsonb NOT NULL,
            response_payload jsonb NOT NULL,
            recorded_at timestamptz NOT NULL,
            accounting_at timestamptz NOT NULL,
            business_date date NOT NULL,
            business_timezone varchar(64) NOT NULL,
            CONSTRAINT uq_pos_receipt_correction_order UNIQUE(order_id),
            CONSTRAINT uq_pos_receipt_correction_payment UNIQUE(payment_id),
            CONSTRAINT uq_pos_receipt_correction_action UNIQUE(company_id,idempotency_key),
            CONSTRAINT ck_pos_receipt_correction_no_value CHECK (
                kind='payment_not_received_full_mistake' AND amount_minor>0
                AND original_method='upi' AND no_funds_received
                AND all_items_not_supplied AND acknowledges_no_payout),
            CONSTRAINT ck_pos_receipt_correction_identity CHECK (
                length(trim(reason)) BETWEEN 3 AND 500
                AND length(trim(idempotency_key)) BETWEEN 1 AND 160
                AND source_fingerprint ~ '^[0-9a-f]{64}$'
                AND request_hash ~ '^[0-9a-f]{64}$'),
            CONSTRAINT ck_pos_receipt_correction_snapshots CHECK (
                jsonb_typeof(source_snapshot)='object'
                AND jsonb_typeof(request_payload)='object'
                AND jsonb_typeof(response_payload)='object')
        );
        CREATE INDEX ix_pos_receipt_corrections_company_id
            ON pos_receipt_corrections(company_id);
        CREATE INDEX ix_pos_receipt_corrections_branch_id
            ON pos_receipt_corrections(branch_id);
        CREATE INDEX ix_pos_receipt_corrections_accounting_at
            ON pos_receipt_corrections(accounting_at);
        CREATE INDEX ix_pos_receipt_corrections_business_date
            ON pos_receipt_corrections(business_date);
        CREATE FUNCTION dcompany_guard_pos_receipt_correction()
        RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
            IF TG_OP='INSERT' THEN
                RAISE EXCEPTION 'receipt correction writes are not activated'
                    USING ERRCODE='23514';
            END IF;
            RAISE EXCEPTION 'POS receipt corrections are append-only' USING ERRCODE='23514';
        END $$;
        CREATE TRIGGER pos_receipt_correction_write_guard BEFORE INSERT OR UPDATE OR DELETE
            ON pos_receipt_corrections FOR EACH ROW
            EXECUTE FUNCTION dcompany_guard_pos_receipt_correction();
        CREATE TRIGGER pos_receipt_correction_no_truncate BEFORE TRUNCATE
            ON pos_receipt_corrections FOR EACH STATEMENT
            EXECUTE FUNCTION dcompany_guard_pos_receipt_correction();
    """)

    op.execute(SOURCE_FUNCTION_SQL)
    op.execute(GUARDS_SQL)
    op.execute(COLLECTIONS_SQL)
    for table in (
        "refunds", "pos_refund_requests", "payment_tender_corrections", "payments",
        "stock_movements", "membership_benefit_reservations", "points_redemptions",
        "order_loyalty_settlements", "customer_gaming_rank_awards",
    ):
        op.execute(f"CREATE TRIGGER aa_pos_corrected_source BEFORE INSERT OR UPDATE ON {table} "
                   "FOR EACH ROW EXECUTE FUNCTION dcompany_guard_corrected_pos_source()")



def downgrade() -> None:
    op.execute("""
        LOCK TABLE pos_receipt_corrections IN ACCESS EXCLUSIVE MODE;
        DO $$ BEGIN
            IF EXISTS(SELECT 1 FROM pos_receipt_corrections) THEN
                RAISE EXCEPTION 'cannot remove retained POS receipt correction evidence';
            END IF;
        END $$;
        DO $$ BEGIN
          IF to_regprocedure('dcompany_collection_day_snapshot_before_pos_correction(uuid,uuid,date,text)') IS NOT NULL THEN
            DROP FUNCTION dcompany_collection_day_snapshot(uuid,uuid,date,text);
            ALTER FUNCTION dcompany_collection_day_snapshot_before_pos_correction(uuid,uuid,date,text)
              RENAME TO dcompany_collection_day_snapshot;
            DROP FUNCTION dcompany_collection_fingerprint(uuid,uuid,date,text);
            ALTER FUNCTION dcompany_collection_fingerprint_before_pos_correction(uuid,uuid,date,text)
              RENAME TO dcompany_collection_fingerprint;
          END IF;
        END $$;
        DROP FUNCTION IF EXISTS dcompany_pos_correction_configuration_guard() CASCADE;
        DROP TABLE pos_receipt_corrections;
        DROP FUNCTION IF EXISTS dcompany_assert_no_value_receipt(jsonb);
        DROP FUNCTION IF EXISTS dcompany_guard_corrected_pos_source() CASCADE;
        DROP FUNCTION IF EXISTS dcompany_pos_receipt_source(uuid,uuid);
        DROP FUNCTION dcompany_guard_pos_receipt_correction();
    """)
