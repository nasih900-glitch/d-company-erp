"""Restore exact inventory for wholly unsupplied no-payout receipt corrections.

Revision ID: 0098
Revises: 0097
"""

# SQL templates deliberately retain exact legacy guard fragments.
# ruff: noqa: E501
from __future__ import annotations

import importlib.util
from pathlib import Path

from alembic import op

revision = "0098"
down_revision = "0097"
branch_labels = None
depends_on = None


def legacy_guard_sql() -> str:
    path = Path(__file__).with_name("0097_pos_receipt_corrections.py")
    spec = importlib.util.spec_from_file_location("receipt_correction_0097", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    start = module.GUARDS_SQL.index(
        "CREATE OR REPLACE FUNCTION dcompany_guard_pos_receipt_correction()"
    )
    end = module.GUARDS_SQL.index("CREATE FUNCTION dcompany_guard_corrected_pos_source()")
    return module.GUARDS_SQL[start:end]


def stock_guard_sql() -> str:
    sql = legacy_guard_sql()
    replacements = {
        "PERFORM dcompany_assert_no_value_receipt(src);": """IF NEW.stock_source_snapshot IS NULL THEN
  PERFORM dcompany_assert_no_value_receipt(src);
 ELSE
  PERFORM dcompany_assert_receipt_stock_source(NEW.stock_source_snapshot,NEW.order_id,NEW.company_id);
  IF NEW.accounting_at<=(SELECT max((m->>'created_at')::timestamptz)
   FROM jsonb_array_elements(NEW.stock_source_snapshot->'batches') b,
   jsonb_array_elements(b->'movements') m WHERE m->>'type'<>'grn') THEN
   RAISE EXCEPTION 'stock restoration cannot precede its captured trail' USING ERRCODE='23514'; END IF;
  PERFORM dcompany_assert_no_value_receipt(src || jsonb_build_object('stock_movements','[]'::jsonb));
 END IF;""",
        "AND NEW.request_path='/api/v1/pos/receipt-corrections/commit'": """AND NEW.request_path=CASE WHEN NEW.stock_source_snapshot IS NULL
   THEN '/api/v1/pos/receipt-corrections/commit' ELSE '/api/v1/pos/receipt-corrections/stock/commit' END""",
        "'all_items_not_supplied',true,'acknowledges_no_payout',true,'reason',NEW.reason)": """'all_items_not_supplied',true,'acknowledges_no_payout',true,'reason',NEW.reason)
   || CASE WHEN NEW.stock_source_snapshot IS NULL THEN '{}'::jsonb ELSE jsonb_build_object(
    'restore_stock',true,'stock_never_consumed',true,'stock_fingerprint',encode(sha256(convert_to(dcompany_pos_receipt_stock_source(NEW.order_id,NEW.company_id)::text,'UTF8')),'hex')) END""",
        "AND NEW.response_payload-'accounting_at'=jsonb_build_object(": "AND NEW.response_payload-'accounting_at'=jsonb_build_object(",
        "'payout_minor',0,'original_records_unchanged',true)": """'payout_minor',0,'original_records_unchanged',true)
   || CASE WHEN NEW.stock_source_snapshot IS NULL THEN '{}'::jsonb ELSE jsonb_build_object(
    'stock_restored',true,'restored_movement_count',jsonb_array_length(src->'stock_movements'),
    'inventory_increase_minor',dcompany_receipt_stock_value(NEW.stock_source_snapshot)) END""",
    }
    for before, after in replacements.items():
        assert sql.count(before) == 1, before
        sql = sql.replace(before, after)
    return sql


SOURCE_SQL = r"""
CREATE FUNCTION dcompany_pos_receipt_stock_source(p_order uuid,p_company uuid)
RETURNS jsonb LANGUAGE sql STABLE SET timezone='UTC' AS $$
 WITH receipt AS (SELECT dcompany_pos_receipt_source(p_order,p_company) source),
 originals AS (SELECT value m FROM receipt,jsonb_array_elements(source->'stock_movements'))
 SELECT jsonb_build_object('receipt',source,
 'restorations',coalesce((SELECT jsonb_agg(to_jsonb(r) ORDER BY r.id)
   FROM pos_receipt_stock_restorations r WHERE r.original_movement_id IN
    (SELECT (m->>'id')::uuid FROM originals)),'[]'::jsonb),
 'batches',coalesce((SELECT jsonb_agg(jsonb_build_object(
  'batch',to_jsonb(b),'ingredient',to_jsonb(i),'branch',to_jsonb(br),
  'movements',coalesce((SELECT jsonb_agg(to_jsonb(sm) ORDER BY sm.created_at,sm.id)
    FROM stock_movements sm WHERE sm.batch_id=b.id),'[]'::jsonb),
  'ingredient_batches',coalesce((SELECT jsonb_agg(to_jsonb(ib) || jsonb_build_object('company_id',ibr.company_id) ORDER BY ib.id)
    FROM batches ib JOIN branches ibr ON ibr.id=ib.branch_id WHERE ib.ingredient_id=i.id),'[]'::jsonb)
 ) ORDER BY b.id) FROM batches b JOIN ingredients i ON i.id=b.ingredient_id
 JOIN branches br ON br.id=b.branch_id WHERE b.id IN (SELECT (m->>'batch_id')::uuid FROM originals)),'[]'::jsonb))
 FROM receipt
$$;

CREATE FUNCTION dcompany_receipt_stock_value(src jsonb) RETURNS bigint
LANGUAGE sql IMMUTABLE AS $$
 SELECT coalesce(sum(round(((item->'batch'->>'qty_on_hand')::numeric +
   (SELECT sum(-(m->>'qty_delta')::numeric) FROM jsonb_array_elements(src->'receipt'->'stock_movements') m
    WHERE m->>'batch_id'=item->'batch'->>'id'))*(item->'batch'->>'cost_per_unit_minor')::bigint)
   -round((item->'batch'->>'qty_on_hand')::numeric*(item->'batch'->>'cost_per_unit_minor')::bigint)),0)::bigint
 FROM jsonb_array_elements(src->'batches') item
$$;

CREATE FUNCTION dcompany_assert_receipt_stock_source(src jsonb,p_order uuid,p_company uuid)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE receipt jsonb; m jsonb; item jsonb; b jsonb; ing jsonb; trail jsonb; ib jsonb;
 balance numeric; qty numeric; cost bigint; found_batches integer; original_count integer;
BEGIN
 -- Protect FK parents before inventory locks; raw SQL deadlocks must retry
 -- the complete transaction. Branch company ownership is immutable in0091.
 PERFORM sm.id FROM stock_movements sm WHERE sm.batch_id IN (
  SELECT original.batch_id FROM stock_movements original WHERE original.ref_id=p_order)
  ORDER BY sm.id FOR KEY SHARE;
 -- Raw SQL correction inserts must obey the same lock order as API writers.
 PERFORM i.id FROM ingredients i WHERE i.id IN (
  SELECT b.ingredient_id FROM batches b JOIN stock_movements sm ON sm.batch_id=b.id
  WHERE sm.ref_id=p_order) ORDER BY i.id FOR UPDATE;
 PERFORM b.id FROM batches b WHERE b.ingredient_id IN (
  SELECT original_batch.ingredient_id FROM batches original_batch
  JOIN stock_movements sm ON sm.batch_id=original_batch.id WHERE sm.ref_id=p_order)
  ORDER BY b.ingredient_id,b.id FOR UPDATE;
 IF src IS NULL OR src IS DISTINCT FROM dcompany_pos_receipt_stock_source(p_order,p_company) THEN
  RAISE EXCEPTION 'stock correction source changed' USING ERRCODE='23514'; END IF;
 receipt:=src->'receipt'; original_count:=jsonb_array_length(receipt->'stock_movements');
 IF original_count<1 OR jsonb_array_length(src->'restorations')<>0 THEN
  RAISE EXCEPTION 'stock correction requires unreversed original consumption' USING ERRCODE='23514'; END IF;
 FOR m IN SELECT value FROM jsonb_array_elements(receipt->'stock_movements') LOOP
  IF (m->>'type'='sale' AND m->>'ref_type'='order' AND m->>'ref_id'=p_order::text
   AND m->>'branch_id'=receipt->'order'->>'branch_id' AND (m->>'qty_delta')::numeric<0) IS DISTINCT FROM TRUE THEN
   RAISE EXCEPTION 'unsupported stock correction movement' USING ERRCODE='23514'; END IF;
  SELECT count(*) INTO found_batches FROM jsonb_array_elements(src->'batches') x
   WHERE x->'batch'->>'id'=m->>'batch_id';
  IF found_batches<>1 THEN RAISE EXCEPTION 'stock correction batch missing' USING ERRCODE='23514'; END IF;
 END LOOP;
 FOR item IN SELECT value FROM jsonb_array_elements(src->'batches') LOOP
  b:=item->'batch'; ing:=item->'ingredient'; cost:=(b->>'cost_per_unit_minor')::bigint;
  IF (ing->>'company_id'=p_company::text AND ing->>'deleted_at' IS NULL
   AND ing->>'base_unit' IN ('ml','g','unit')
   AND item->'branch'->>'company_id'=p_company::text
   AND b->>'branch_id'=receipt->'order'->>'branch_id' AND cost>0
   AND coalesce(b->>'lot_code','')<>'SYSTEM-UNCOSTED-SHORTAGE'
   AND (b->>'qty_initial') NOT IN ('NaN','Infinity','-Infinity')
   AND (b->>'qty_on_hand') NOT IN ('NaN','Infinity','-Infinity')
   AND (ing->>'current_qty') NOT IN ('NaN','Infinity','-Infinity')
   AND (b->>'qty_initial')::numeric>=0 AND (b->>'qty_on_hand')::numeric>=0) IS DISTINCT FROM TRUE THEN
   RAISE EXCEPTION 'unreconciled stock correction scope or cost' USING ERRCODE='23514'; END IF;
  balance:=(b->>'qty_initial')::numeric;
  FOR trail IN SELECT value FROM jsonb_array_elements(item->'movements') LOOP
   IF trail->>'type'='grn' THEN CONTINUE; END IF;
   qty:=(trail->>'qty_delta')::numeric;
   IF (trail->>'branch_id'=b->>'branch_id' AND (trail->>'cost_per_unit_minor')::bigint=cost
    AND (trail->>'qty_delta') NOT IN ('NaN','Infinity','-Infinity')
    AND (trail->>'created_at')::timestamptz<=statement_timestamp()
    AND qty<>0 AND trail->>'type' IN ('sale','refund_restock','receipt_restore','adjustment','waste','damage')
    AND (trail->>'type' NOT IN ('sale','waste','damage') OR qty<0)
    AND (trail->>'type' NOT IN ('refund_restock','receipt_restore') OR qty>0)) IS DISTINCT FROM TRUE THEN
    RAISE EXCEPTION 'unreconciled stock correction movement trail' USING ERRCODE='23514'; END IF;
   balance:=balance+qty;
   IF balance<0 THEN RAISE EXCEPTION 'unreconciled historical stock shortage' USING ERRCODE='23514'; END IF;
  END LOOP;
  IF balance<>(b->>'qty_on_hand')::numeric THEN
   RAISE EXCEPTION 'unreconciled stock correction batch quantity' USING ERRCODE='23514'; END IF;
  balance:=0;
  FOR ib IN SELECT value FROM jsonb_array_elements(item->'ingredient_batches') LOOP
   IF (ib->>'company_id'=p_company::text AND ib->>'ingredient_id'=ing->>'id'
    AND (ib->>'qty_on_hand') NOT IN ('NaN','Infinity','-Infinity')) IS DISTINCT FROM TRUE THEN
    RAISE EXCEPTION 'stock correction crosses ingredient tenant' USING ERRCODE='23514'; END IF;
   balance:=balance+(ib->>'qty_on_hand')::numeric;
  END LOOP;
  IF balance<>(ing->>'current_qty')::numeric THEN
   RAISE EXCEPTION 'unreconciled stock correction ingredient quantity' USING ERRCODE='23514'; END IF;
 END LOOP;
END $$;
"""

RESTORATION_GUARDS_SQL = r"""
CREATE FUNCTION dcompany_guard_receipt_stock_restoration() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE c pos_receipt_corrections%ROWTYPE; original stock_movements%ROWTYPE;
 restored stock_movements%ROWTYPE; b batches%ROWTYPE; tenant uuid;
BEGIN
 IF TG_OP<>'INSERT' THEN
  RAISE EXCEPTION 'POS receipt stock restorations are append-only' USING ERRCODE='23514'; END IF;
 SELECT * INTO STRICT c FROM pos_receipt_corrections WHERE id=NEW.correction_id FOR UPDATE;
 SELECT * INTO STRICT original FROM stock_movements WHERE id=NEW.original_movement_id;
 SELECT * INTO STRICT restored FROM stock_movements WHERE id=NEW.restoration_movement_id;
 SELECT * INTO STRICT b FROM batches WHERE id=original.batch_id;
 SELECT company_id INTO tenant FROM ingredients WHERE id=b.ingredient_id;
 IF (c.stock_source_snapshot IS NOT NULL AND NEW.company_id=c.company_id AND tenant=c.company_id
  AND original.type='sale' AND original.ref_type='order' AND original.ref_id=c.order_id
  AND original.branch_id=c.branch_id AND b.branch_id=c.branch_id
  AND restored.type='receipt_restore' AND restored.ref_type='pos_receipt_correction'
  AND restored.ref_id=c.id AND restored.batch_id=original.batch_id
  AND restored.branch_id=original.branch_id AND restored.qty_delta=-original.qty_delta
  AND restored.cost_per_unit_minor=original.cost_per_unit_minor
  AND restored.created_by=c.recorded_by AND restored.created_at=c.accounting_at
  AND EXISTS(SELECT 1 FROM jsonb_array_elements(c.source_snapshot->'stock_movements') x WHERE x=to_jsonb(original)))
 IS DISTINCT FROM TRUE THEN
  RAISE EXCEPTION 'stock restoration does not match exact correction source' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;

CREATE FUNCTION dcompany_guard_receipt_stock_movement() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target uuid; last_restore timestamptz; batch_ids uuid[];
BEGIN
 IF TG_OP='TRUNCATE' THEN
  IF EXISTS(SELECT 1 FROM pos_receipt_stock_restorations) THEN
   RAISE EXCEPTION 'retained stock correction evidence cannot be truncated' USING ERRCODE='23514'; END IF;
  RETURN NULL;
 END IF;
 IF TG_OP IN ('UPDATE','DELETE') AND OLD.ref_type='order' THEN
  PERFORM 1 FROM orders WHERE id=OLD.ref_id FOR UPDATE;
 END IF;
 batch_ids:=CASE WHEN TG_OP='INSERT' THEN ARRAY[NEW.batch_id]
  WHEN TG_OP='DELETE' THEN ARRAY[OLD.batch_id] ELSE ARRAY[OLD.batch_id,NEW.batch_id] END;
 PERFORM i.id FROM ingredients i WHERE i.id IN (SELECT b.ingredient_id FROM batches b
  WHERE b.id=ANY(batch_ids)) ORDER BY i.id FOR UPDATE;
 PERFORM b.id FROM batches b WHERE b.id=ANY(batch_ids) ORDER BY b.ingredient_id,b.id FOR UPDATE;
 IF TG_OP='UPDATE' AND (NEW.type='receipt_restore' OR NEW.ref_type='pos_receipt_correction') THEN
  RAISE EXCEPTION 'stock restoration can only be inserted' USING ERRCODE='23514'; END IF;
 IF TG_OP IN ('UPDATE','DELETE') AND (
  EXISTS(SELECT 1 FROM pos_receipt_stock_restorations r
    JOIN stock_movements restored ON restored.id=r.restoration_movement_id
    WHERE restored.batch_id=OLD.batch_id AND OLD.created_at<=restored.created_at)
  OR OLD.type='receipt_restore' OR EXISTS(SELECT 1 FROM pos_receipt_corrections c,
    jsonb_array_elements(c.source_snapshot->'stock_movements') x WHERE x->>'id'=OLD.id::text)) THEN
  RAISE EXCEPTION 'corrected original and restored stock movements are immutable' USING ERRCODE='23514'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 SELECT max(restored.created_at) INTO last_restore FROM pos_receipt_stock_restorations r
  JOIN stock_movements restored ON restored.id=r.restoration_movement_id WHERE restored.batch_id=NEW.batch_id;
 IF NEW.type<>'receipt_restore' AND NEW.created_at<=last_restore THEN
  RAISE EXCEPTION 'new stock movement predates retained restoration; retry in a fresh transaction'
   USING ERRCODE='23514'; END IF;
 IF NEW.type='receipt_restore' OR NEW.ref_type='pos_receipt_correction' THEN
  SELECT id INTO target FROM pos_receipt_corrections
   WHERE id=NEW.ref_id AND stock_source_snapshot IS NOT NULL FOR UPDATE;
  IF target IS NULL OR NEW.type<>'receipt_restore' OR NEW.ref_type<>'pos_receipt_correction' THEN
   RAISE EXCEPTION 'stock restoration requires its retained correction' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END $$;

CREATE FUNCTION dcompany_check_receipt_stock_complete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE ident uuid; c pos_receipt_corrections%ROWTYPE; item jsonb; orig jsonb;
 b batches%ROWTYPE; ing ingredients%ROWTYPE; qty numeric; n integer;
BEGIN
 IF TG_TABLE_NAME='pos_receipt_corrections' THEN ident:=NEW.id;
 ELSIF TG_TABLE_NAME='stock_movements' THEN
  IF NEW.type<>'receipt_restore' THEN RETURN NULL; END IF;
  ident:=NEW.ref_id;
 ELSE ident:=NEW.correction_id;
 END IF;
 SELECT * INTO STRICT c FROM pos_receipt_corrections WHERE id=ident;
 IF c.stock_source_snapshot IS NULL THEN RETURN NULL; END IF;
 SELECT count(*) INTO n FROM pos_receipt_stock_restorations WHERE correction_id=c.id;
 IF n<>jsonb_array_length(c.source_snapshot->'stock_movements')
  OR n<>(SELECT count(*) FROM stock_movements WHERE type='receipt_restore' AND ref_id=c.id) THEN
  RAISE EXCEPTION 'stock correction restoration is incomplete' USING ERRCODE='23514'; END IF;
 FOR orig IN SELECT value FROM jsonb_array_elements(c.source_snapshot->'stock_movements') LOOP
  IF NOT EXISTS(SELECT 1 FROM pos_receipt_stock_restorations r
    JOIN stock_movements restored ON restored.id=r.restoration_movement_id
    WHERE r.correction_id=c.id AND r.original_movement_id=(orig->>'id')::uuid
    AND restored.qty_delta=-(orig->>'qty_delta')::numeric) THEN
   RAISE EXCEPTION 'stock correction original movement lacks exact restoration' USING ERRCODE='23514'; END IF;
 END LOOP;
 FOR item IN SELECT value FROM jsonb_array_elements(c.stock_source_snapshot->'batches') LOOP
  SELECT * INTO STRICT b FROM batches WHERE id=(item->'batch'->>'id')::uuid;
  SELECT coalesce(sum(-(x->>'qty_delta')::numeric),0) INTO qty
   FROM jsonb_array_elements(c.source_snapshot->'stock_movements') x WHERE x->>'batch_id'=b.id::text;
  IF (b.ingredient_id=(item->'batch'->>'ingredient_id')::uuid
    AND b.branch_id=c.branch_id AND b.cost_per_unit_minor=(item->'batch'->>'cost_per_unit_minor')::bigint
    AND b.qty_initial=(item->'batch'->>'qty_initial')::numeric
    AND b.received_at=(item->'batch'->>'received_at')::timestamptz
    AND coalesce(b.lot_code,'')=coalesce(item->'batch'->>'lot_code','')) IS DISTINCT FROM TRUE THEN
   RAISE EXCEPTION 'stock correction batch provenance changed' USING ERRCODE='23514'; END IF;
  IF (SELECT coalesce(jsonb_agg(to_jsonb(m) ORDER BY m.created_at,m.id),'[]'::jsonb)
    FROM stock_movements m WHERE m.batch_id=b.id
    AND NOT(m.type='receipt_restore' AND m.ref_id=c.id)) IS DISTINCT FROM item->'movements' THEN
   RAISE EXCEPTION 'stock correction captured trail changed' USING ERRCODE='23514'; END IF;
  IF b.qty_on_hand<>(item->'batch'->>'qty_on_hand')::numeric+qty THEN
   RAISE EXCEPTION 'stock correction batch restoration differs' USING ERRCODE='23514'; END IF;
  SELECT * INTO STRICT ing FROM ingredients WHERE id=b.ingredient_id;
  SELECT coalesce(sum(-(m->>'qty_delta')::numeric),0) INTO qty
   FROM jsonb_array_elements(c.source_snapshot->'stock_movements') m
   JOIN batches original_batch ON original_batch.id=(m->>'batch_id')::uuid
   WHERE original_batch.ingredient_id=ing.id;
  IF ing.company_id<>c.company_id OR ing.base_unit IS DISTINCT FROM item->'ingredient'->>'base_unit'
   OR NOT EXISTS(
    SELECT 1 FROM branches WHERE id=b.branch_id AND company_id=c.company_id) THEN
   RAISE EXCEPTION 'stock correction inventory ownership changed' USING ERRCODE='23514'; END IF;
  IF ing.current_qty<>(item->'ingredient'->>'current_qty')::numeric+qty THEN
   RAISE EXCEPTION 'stock correction ingredient restoration differs' USING ERRCODE='23514'; END IF;
 END LOOP;
 RETURN NULL;
END $$;
CREATE FUNCTION dcompany_guard_receipt_inventory_identity() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE used boolean;
BEGIN
 IF TG_TABLE_NAME='batches' THEN
  SELECT EXISTS(SELECT 1 FROM pos_receipt_stock_restorations r
   JOIN stock_movements m ON m.id=r.original_movement_id WHERE m.batch_id=OLD.id) INTO used;
  IF used AND (NEW.ingredient_id,NEW.branch_id,NEW.cost_per_unit_minor,NEW.qty_initial,NEW.received_at,NEW.lot_code)
   IS DISTINCT FROM (OLD.ingredient_id,OLD.branch_id,OLD.cost_per_unit_minor,OLD.qty_initial,OLD.received_at,OLD.lot_code) THEN
   RAISE EXCEPTION 'retained stock correction batch provenance is immutable' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='ingredients' THEN
  SELECT EXISTS(SELECT 1 FROM pos_receipt_stock_restorations r JOIN stock_movements m
   ON m.id=r.original_movement_id JOIN batches b ON b.id=m.batch_id WHERE b.ingredient_id=OLD.id) INTO used;
  IF used AND (NEW.company_id,NEW.base_unit) IS DISTINCT FROM (OLD.company_id,OLD.base_unit) THEN
   RAISE EXCEPTION 'retained stock correction ingredient ownership and unit are immutable' USING ERRCODE='23514'; END IF;

 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER receipt_stock_batch_identity BEFORE UPDATE ON batches
 FOR EACH ROW EXECUTE FUNCTION dcompany_guard_receipt_inventory_identity();
CREATE TRIGGER receipt_stock_ingredient_identity BEFORE UPDATE OF company_id,base_unit ON ingredients
 FOR EACH ROW EXECUTE FUNCTION dcompany_guard_receipt_inventory_identity();
CREATE TRIGGER receipt_stock_restoration_write_guard BEFORE INSERT OR UPDATE OR DELETE
 ON pos_receipt_stock_restorations FOR EACH ROW EXECUTE FUNCTION dcompany_guard_receipt_stock_restoration();
CREATE TRIGGER receipt_stock_restoration_no_truncate BEFORE TRUNCATE ON pos_receipt_stock_restorations
 FOR EACH STATEMENT EXECUTE FUNCTION dcompany_guard_receipt_stock_restoration();
CREATE TRIGGER aa_receipt_stock_movement BEFORE INSERT OR UPDATE OR DELETE ON stock_movements
 FOR EACH ROW EXECUTE FUNCTION dcompany_guard_receipt_stock_movement();
CREATE TRIGGER receipt_stock_no_truncate BEFORE TRUNCATE ON stock_movements
 FOR EACH STATEMENT EXECUTE FUNCTION dcompany_guard_receipt_stock_movement();
CREATE CONSTRAINT TRIGGER receipt_stock_complete AFTER INSERT ON pos_receipt_corrections
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION dcompany_check_receipt_stock_complete();
CREATE CONSTRAINT TRIGGER receipt_stock_complete AFTER INSERT ON pos_receipt_stock_restorations
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION dcompany_check_receipt_stock_complete();
CREATE CONSTRAINT TRIGGER receipt_stock_complete AFTER INSERT ON stock_movements
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION dcompany_check_receipt_stock_complete();
"""


def upgrade() -> None:
    op.execute("""
      ALTER TABLE pos_receipt_corrections ADD COLUMN stock_source_snapshot jsonb;
      ALTER TABLE pos_receipt_corrections ADD CONSTRAINT ck_receipt_stock_snapshot
       CHECK(stock_source_snapshot IS NULL OR jsonb_typeof(stock_source_snapshot)='object');
      CREATE TABLE pos_receipt_stock_restorations(
       id uuid PRIMARY KEY, company_id uuid NOT NULL REFERENCES companies(id) ON DELETE RESTRICT,
       correction_id uuid NOT NULL REFERENCES pos_receipt_corrections(id) ON DELETE RESTRICT,
       original_movement_id uuid NOT NULL REFERENCES stock_movements(id) ON DELETE RESTRICT,
       restoration_movement_id uuid NOT NULL REFERENCES stock_movements(id) ON DELETE RESTRICT,
       CONSTRAINT uq_receipt_restore_original UNIQUE(original_movement_id),
       CONSTRAINT uq_receipt_restore_movement UNIQUE(restoration_movement_id));
      CREATE INDEX ix_pos_receipt_stock_restorations_company_id ON pos_receipt_stock_restorations(company_id);
      CREATE INDEX ix_pos_receipt_stock_restorations_correction_id ON pos_receipt_stock_restorations(correction_id);
    """)
    op.execute(SOURCE_SQL)
    op.execute(stock_guard_sql())
    op.execute(RESTORATION_GUARDS_SQL)


def downgrade() -> None:
    op.execute("""
      LOCK TABLE pos_receipt_corrections,pos_receipt_stock_restorations,stock_movements IN ACCESS EXCLUSIVE MODE;
      DO $$ BEGIN
       IF EXISTS(SELECT 1 FROM pos_receipt_corrections WHERE stock_source_snapshot IS NOT NULL) THEN
        RAISE EXCEPTION 'cannot remove retained stock correction evidence'; END IF;
      END $$;
      DROP TRIGGER receipt_stock_complete ON pos_receipt_corrections;
      DROP TRIGGER receipt_stock_complete ON stock_movements;
      DROP TRIGGER aa_receipt_stock_movement ON stock_movements;
      DROP TRIGGER receipt_stock_no_truncate ON stock_movements;
      DROP TABLE pos_receipt_stock_restorations;
      DROP FUNCTION dcompany_guard_receipt_inventory_identity() CASCADE;
      DROP FUNCTION dcompany_check_receipt_stock_complete();
      DROP FUNCTION dcompany_guard_receipt_stock_movement();
      DROP FUNCTION dcompany_guard_receipt_stock_restoration();
      DROP FUNCTION dcompany_assert_receipt_stock_source(jsonb,uuid,uuid);
      DROP FUNCTION dcompany_receipt_stock_value(jsonb);
      DROP FUNCTION dcompany_pos_receipt_stock_source(uuid,uuid);
    """)
    op.execute(legacy_guard_sql())
    op.execute("ALTER TABLE pos_receipt_corrections DROP COLUMN stock_source_snapshot")
