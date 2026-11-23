"""Retain settlement-conditioned paid decrease/discard intent without posting money."""

from alembic import op

revision = "0100"
down_revision = "0099"
branch_labels = None
depends_on = None

SOURCE_SQL = r"""
CREATE FUNCTION dcompany_paid_bill_source(p_order uuid,p_company uuid,p_shift uuid)
RETURNS jsonb LANGUAGE sql STABLE SET timezone='UTC' AS $$
 SELECT (dcompany_pos_receipt_source(p_order,p_company) - ARRAY[
   'stock_movements','gaming_sessions','gaming_stations','gaming_addons','gaming_links',
   'gaming_voids'])
 || jsonb_build_object(
  'e_invoicing_enabled',(SELECT e_invoicing_enabled FROM companies WHERE id=p_company),
  'settlement_shift',(SELECT to_jsonb(s) FROM shifts s WHERE s.id=p_shift AND
  s.company_id=p_company),
  'refund_progress',coalesce((SELECT jsonb_agg(jsonb_build_object(
    'request_id',r.id,'handoff',to_jsonb(h),'cash_completion',to_jsonb(c),
    'provider_start',to_jsonb(ps),'provider_completion',to_jsonb(pc),'withdrawal',to_jsonb(w))
    ORDER BY r.id)
    FROM pos_refund_requests r LEFT JOIN pos_refund_cash_handoffs h ON h.refund_request_id=r.id
    LEFT JOIN pos_refund_cash_handoff_completions c ON c.refund_request_id=r.id
    LEFT JOIN pos_refund_provider_payout_starts ps ON ps.refund_request_id=r.id
    LEFT JOIN pos_refund_provider_settlements pc ON pc.refund_request_id=r.id
    LEFT JOIN pos_refund_withdrawals w ON w.refund_request_id=r.id WHERE r.order_id=p_order),
    '[]'::jsonb),
  'amendments',coalesce((SELECT jsonb_agg(jsonb_build_object('id',a.id,'revision',a.revision,
    'operation',a.operation,'target_minor',a.target_minor,'refund_request_id',
    a.refund_request_id) ORDER BY a.revision)
    FROM paid_bill_amendment_intents a WHERE a.order_id=p_order AND a.company_id=p_company),
    '[]'::jsonb))
$$;
CREATE FUNCTION dcompany_paid_bill_revision(src jsonb) RETURNS bigint LANGUAGE sql IMMUTABLE AS $$
 SELECT coalesce((src->'order'->>'checkout_version')::bigint,0)
 +jsonb_array_length(src->'payments')+jsonb_array_length(src->'refunds')
 +jsonb_array_length(src->'refund_requests')+jsonb_array_length(src->'tender_corrections')
 +jsonb_array_length(src->'corrections')+jsonb_array_length(src->'amendments')
 +(SELECT count(*) FROM jsonb_array_elements(src->'refund_progress') p,
   jsonb_each(p) e WHERE e.key<>'request_id' AND e.value<>'null'::jsonb)
$$;
CREATE FUNCTION dcompany_paid_bill_blockers(src jsonb) RETURNS text[] LANGUAGE plpgsql AS $$
DECLARE o jsonb:=src->'order'; paid bigint; refunded bigint; r jsonb; problems text[]:='{}';
 line jsonb; field text; line_count integer:=0; line_subtotal numeric:=0; line_total numeric:=0;
 line_discount numeric:=0; line_cgst numeric:=0; line_sgst numeric:=0; line_igst numeric:=0;
 line_cess numeric:=0;
BEGIN
 IF src IS NULL OR o IS NULL THEN RETURN ARRAY['Receipt source is unavailable.']; END IF;
 IF jsonb_typeof(src->'lines') IS DISTINCT FROM 'array' THEN
  RETURN ARRAY['The captured active invoice-line cohort is unavailable.']; END IF;
 FOREACH field IN ARRAY ARRAY['subtotal_minor','cgst_minor','sgst_minor','igst_minor','cess_minor',
  'tax_minor','round_off_minor','manual_discount_minor','points_redeemed_minor','tip_minor',
  'total_minor','discount_minor'] LOOP
  IF jsonb_typeof(o->field) IS DISTINCT FROM 'number' OR (o->>field)!~'^-?[0-9]+$' THEN
   RETURN ARRAY['Original fiscal header facts are missing or invalid.']; END IF;
  IF field<>'round_off_minor' AND (o->>field)::numeric<0 THEN
   RETURN ARRAY['Original fiscal header contains a negative amount.']; END IF;
 END LOOP;
 -- This new profile supports only magnitudes proven equivalent to the existing
 -- Decimal(28) cumulative refund allocator. It does not change legacy refunds.
 FOREACH field IN ARRAY ARRAY['total_minor','cgst_minor','sgst_minor','igst_minor',
  'cess_minor'] LOOP
  IF (o->>field)::numeric>1000000000000 THEN
   RETURN ARRAY['Captured amounts exceed the supported paid-bill allocation profile.']; END IF;
 END LOOP;
 FOR line IN SELECT * FROM jsonb_array_elements(src->'lines') LOOP
  IF NOT line ? 'voided_at' THEN
   RETURN ARRAY['Original invoice-line activity is unknown.']; END IF;
  IF line->>'voided_at' IS NOT NULL THEN CONTINUE; END IF;
  line_count:=line_count+1;
  IF jsonb_typeof(line->'qty') IS DISTINCT FROM 'number' THEN
   RETURN ARRAY['Original invoice-line quantity is unknown.']; END IF;
  IF (line->>'qty')::numeric<=0 THEN
   RETURN ARRAY['Original invoice-line quantity is invalid.']; END IF;
  FOREACH field IN ARRAY ARRAY['line_total_minor','taxable_value_minor','discount_minor',
   'cgst_minor','sgst_minor','igst_minor','cess_minor'] LOOP
   IF jsonb_typeof(line->field) IS DISTINCT FROM 'number' OR (line->>field)!~'^[0-9]+$' THEN
    RETURN ARRAY['Original fiscal line facts are missing or invalid.']; END IF;
  END LOOP;
  IF (line->>'line_total_minor')::numeric IS DISTINCT FROM
    (line->>'taxable_value_minor')::numeric+(line->>'cgst_minor')::numeric+
    (line->>'sgst_minor')::numeric+(line->>'igst_minor')::numeric+(line->>'cess_minor')::numeric
    THEN
   problems:=array_append(problems,
   'Original line total does not reconcile to its captured tax components.'); END IF;
  line_subtotal:=line_subtotal+(line->>'taxable_value_minor')::numeric;
  line_total:=line_total+(line->>'line_total_minor')::numeric;
  line_discount:=line_discount+(line->>'discount_minor')::numeric;
  line_cgst:=line_cgst+(line->>'cgst_minor')::numeric;
  line_sgst:=line_sgst+(line->>'sgst_minor')::numeric;
  line_igst:=line_igst+(line->>'igst_minor')::numeric;
  line_cess:=line_cess+(line->>'cess_minor')::numeric;
 END LOOP;
 IF line_count=0 THEN RETURN ARRAY['A captured active invoice-line cohort is required.']; END IF;
 IF ((o->>'subtotal_minor')::numeric,(o->>'cgst_minor')::numeric,(o->>'sgst_minor')::numeric,
     (o->>'igst_minor')::numeric,(o->>'cess_minor')::numeric)
   IS DISTINCT FROM (line_subtotal,line_cgst,line_sgst,line_igst,line_cess)
  OR (o->>'tax_minor')::numeric<>line_cgst+line_sgst+line_igst+line_cess
  OR (o->>'discount_minor')::numeric<>line_discount+(o->>'manual_discount_minor')::numeric+
     (o->>'points_redeemed_minor')::numeric
  OR (o->>'total_minor')::numeric<>line_total+(o->>'round_off_minor')::numeric-
     (o->>'manual_discount_minor')::numeric-(o->>'points_redeemed_minor')::numeric+(o->>'tip_minor')::numeric
  OR line_total+(o->>'round_off_minor')::numeric<
     (o->>'manual_discount_minor')::numeric+(o->>'points_redeemed_minor')::numeric THEN
  problems:=array_append(problems,
  'Captured header does not reconcile to active lines, discounts, rounding and tip.'); END IF;

 IF (o->>'status' IN ('paid','refunded') AND (o->>'source_integrity_revision')::int=48
   AND nullif(o->>'invoice_no','') IS NOT NULL AND o->>'invoice_issued_at' IS NOT NULL
   AND o->>'closed_at' IS NOT NULL AND nullif(o->>'fiscal_year','') IS NOT NULL
   AND o->>'company_id'=src->'shift'->>'company_id'
   AND o->>'branch_id'=src->'shift'->>'branch_id' AND o->>'terminal_id'=src->'shift'->>'terminal_id'
   AND o->>'company_id'=src->'branch'->>'company_id' AND src->'branch'->>'deleted_at' IS NULL
   AND o->>'branch_id'=src->'terminal'->>'branch_id') IS DISTINCT FROM TRUE THEN
   problems:=array_append(problems,'A complete original issued paid invoice is required.'); END IF;
 SELECT coalesce(sum((p->>'amount_minor')::bigint),0) INTO paid FROM
 jsonb_array_elements(src->'payments') p;
 SELECT coalesce(sum((p->>'amount_minor')::bigint),0) INTO refunded FROM
 jsonb_array_elements(src->'refunds') p;
 IF paid IS DISTINCT FROM (o->>'total_minor')::bigint OR paid<=0 OR refunded<0 OR refunded>paid THEN
  problems:=array_append(problems,
  'Original collections and completed refunds do not reconcile.'); END IF;
 IF EXISTS(SELECT 1 FROM jsonb_array_elements(src->'payments') p WHERE
   (p->>'source_integrity_revision')::int IS DISTINCT FROM 48 OR p->>'order_id'<>o->>'id'
   OR p->>'shift_id'<>o->>'shift_id') THEN
  problems:=array_append(problems,'Payment provenance needs reconciliation.'); END IF;
 IF jsonb_array_length(src->'corrections')>0 THEN
  problems:=array_append(problems,
  'Use the separate no-payment correction history for this receipt.'); END IF;
 IF coalesce((src->>'e_invoicing_enabled')::boolean,false) OR o->>'irn' IS NOT NULL
   OR o->>'irn_ack_no' IS NOT NULL OR o->>'e_invoice_qr' IS NOT NULL THEN
  problems:=array_append(problems,
  'This fiscal document needs a separately supported statutory adjustment.'); END IF;
 IF jsonb_array_length(src->'active_claims')>0 THEN
  problems:=array_append(problems,
  'Resolve the active checkout action before changing this receipt.'); END IF;
 FOR r IN SELECT * FROM jsonb_array_elements(src->'refund_requests') LOOP
  IF NOT EXISTS(SELECT 1 FROM jsonb_array_elements(src->'refunds') f WHERE
  f->>'request_id'=r->>'id')
   AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(src->'refund_progress') p
     WHERE p->>'request_id'=r->>'id' AND p->'withdrawal'<>'null'::jsonb) THEN
   problems:=array_append(problems,
   'Resolve the existing refund task before changing the paid bill.'); EXIT;
  END IF;
 END LOOP;
 IF EXISTS(SELECT 1 FROM jsonb_array_elements(src->'refunds') f WHERE
   (f->>'source_integrity_revision')::int IS DISTINCT FROM 48 OR f->>'request_id' IS NULL
   OR f->>'loyalty_reconciliation_state' IS NULL
   OR f->>'loyalty_reconciliation_state' NOT IN ('applied','not_applicable',
   'legacy_redemption_restored')
   OR (f->>'customer_spend_reconciled')::boolean IS DISTINCT FROM TRUE) THEN
  problems:=array_append(problems,
  'Completed refund allocation or customer/loyalty evidence needs reconciliation.'); END IF;
 IF (o->>'customer_id' IS NOT NULL OR jsonb_array_length(src->'points_redemptions')>0
  OR jsonb_array_length(src->'loyalty_settlements')>0
  OR (o->>'points_redeemed_minor')::numeric>0)
  AND NOT (jsonb_array_length(src->'loyalty_settlements')=1 AND EXISTS(
   SELECT 1 FROM jsonb_array_elements(src->'loyalty_settlements') l
   WHERE l->>'company_id'=o->>'company_id' AND l->>'customer_id'=o->>'customer_id'
   AND l->>'order_id'=o->>'id' AND (l->>'order_paid_minor')::numeric=paid
   AND l->>'provenance' IN ('exact','legacy_redemption_only') AND (
    ((o->>'points_redeemed_minor')::numeric=0
     AND jsonb_array_length(src->'points_redemptions')=0
     AND (l->>'points_redeemed')::numeric=0) OR
    ((o->>'points_redeemed_minor')::numeric>0
     AND jsonb_array_length(src->'points_redemptions')=1 AND EXISTS(
      SELECT 1 FROM jsonb_array_elements(src->'points_redemptions') p
      WHERE p->>'order_id'=o->>'id' AND p->>'customer_id'=o->>'customer_id'
      AND p->>'consumed_at' IS NOT NULL
      AND jsonb_typeof(p->'amount_minor')='number' AND p->>'amount_minor'~'^[0-9]+$'
      AND (p->>'amount_minor')::numeric=(o->>'points_redeemed_minor')::numeric
      AND jsonb_typeof(p->'points_spent')='number' AND p->>'points_spent'~'^[0-9]+$'
      AND (p->>'points_spent')::numeric>0
      AND (l->>'points_redeemed')::numeric=(p->>'points_spent')::numeric))))) THEN
  problems:=array_append(problems,'Original customer/loyalty allocation needs reconciliation.');
  END IF;
 RETURN problems;
END $$;
CREATE FUNCTION dcompany_paid_bill_proportional(
 p_component bigint,p_cumulative bigint,p_total bigint)
RETURNS bigint LANGUAGE sql IMMUTABLE STRICT AS $$
 SELECT CASE WHEN p_component<=0 OR p_cumulative<=0 OR p_total<=0 THEN 0 ELSE
  div(2*p_component::numeric*least(p_cumulative,p_total)::numeric+p_total::numeric,
      2*p_total::numeric)::bigint END
$$;
CREATE FUNCTION dcompany_paid_bill_allocation_blockers(src jsonb,p_target bigint)
RETURNS text[] LANGUAGE plpgsql AS $$
DECLARE o jsonb:=src->'order'; total bigint; tip bigint; bill bigint; previous bigint:=0;
 amount bigint; cumulative bigint; tax_delta numeric; delta numeric; current_tip bigint;
 prior_tip bigint; field text; r jsonb; amounts bigint[]:='{}'; difference bigint;
BEGIN
 IF cardinality(dcompany_paid_bill_blockers(src))>0 THEN RETURN '{}'; END IF;
 total:=(o->>'total_minor')::bigint; tip:=(o->>'tip_minor')::bigint; bill:=total-tip;
 IF bill<0 OR (bill=0 AND (o->>'tax_minor')::bigint<>0) THEN
  RETURN ARRAY['The captured tax has no supported collected-bill allocation basis.']; END IF;
 FOR r IN SELECT v FROM jsonb_array_elements(src->'refunds') v
  ORDER BY coalesce((v->>'settled_at')::timestamptz,(v->>'created_at')::timestamptz),v->>'id' LOOP
  IF jsonb_typeof(r->'amount_minor') IS DISTINCT FROM 'number' OR (r->>'amount_minor')!~'^[0-9]+$'
    OR (r->>'amount_minor')::bigint<=0 THEN
   RETURN ARRAY['Completed refund allocation evidence is invalid.']; END IF;
  amounts:=array_append(amounts,(r->>'amount_minor')::bigint);
 END LOOP;
 SELECT coalesce(sum(v),0) INTO previous FROM unnest(amounts) v;
 difference:=total-previous-p_target;
 IF difference>0 THEN amounts:=array_append(amounts,difference); END IF;
 previous:=0;
 FOREACH amount IN ARRAY amounts LOOP
  cumulative:=previous+amount; tax_delta:=0;
  FOREACH field IN ARRAY ARRAY['cgst_minor','sgst_minor','igst_minor','cess_minor'] LOOP
   IF bill<=0 THEN delta:=0; ELSE
    delta:=dcompany_paid_bill_proportional((o->>field)::bigint,cumulative,bill)-
           dcompany_paid_bill_proportional((o->>field)::bigint,previous,bill);
   END IF;
   IF delta<0 THEN RETURN ARRAY['The captured cumulative tax allocation is unsupported.']; END IF;
   tax_delta:=tax_delta+delta;
  END LOOP;
  current_tip:=greatest(0,least(tip,cumulative-bill));
  prior_tip:=greatest(0,least(tip,previous-bill));
  IF current_tip-prior_tip<0 OR tax_delta+current_tip-prior_tip>amount THEN
   RETURN ARRAY['The cumulative refund tax/tip allocation exceeds this exact refund.']; END IF;
  previous:=cumulative;
 END LOOP;
 RETURN '{}';
END $$;

"""

GUARDS_SQL = r"""
CREATE FUNCTION dcompany_guard_paid_bill_intent() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE src jsonb; o orders; paid bigint; refunded bigint; problems text[]; expected jsonb;
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Paid bill amendment intents are append-only' USING
 ERRCODE='23514'; END IF;
 PERFORM 1 FROM users WHERE id=NEW.recorded_by AND company_id=NEW.company_id FOR SHARE;
 SELECT * INTO o FROM orders WHERE id=NEW.order_id AND company_id=NEW.company_id
  AND branch_id=NEW.branch_id AND terminal_id=NEW.terminal_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Paid bill source scope is invalid' USING ERRCODE='23514'; END
 IF;
 IF NOT EXISTS(SELECT 1 FROM users u JOIN user_roles ur ON ur.user_id=u.id
   JOIN roles r ON r.id=ur.role_id WHERE u.id=NEW.recorded_by AND u.company_id=NEW.company_id
   AND u.deleted_at IS NULL AND u.status='active' AND r.company_id=NEW.company_id AND
   r.code='super_owner') THEN
  RAISE EXCEPTION 'Paid bill amendment requires current protected Owner' USING ERRCODE='23514';
  END IF;
 IF NEW.settlement_shift_id IS NOT NULL THEN
  PERFORM 1 FROM shifts WHERE id=NEW.settlement_shift_id AND company_id=NEW.company_id
   AND branch_id=NEW.branch_id AND terminal_id=NEW.terminal_id AND status='open' AND closed_at IS
   NULL FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'Paid bill settlement scope is invalid' USING
  ERRCODE='23514'; END IF;
 END IF;
 PERFORM 1 FROM shifts WHERE id=o.shift_id AND company_id=NEW.company_id
  AND branch_id=NEW.branch_id AND terminal_id=NEW.terminal_id FOR UPDATE;
 src:=dcompany_paid_bill_source(NEW.order_id,NEW.company_id,NEW.settlement_shift_id);
 problems:=dcompany_paid_bill_blockers(src)||dcompany_paid_bill_allocation_blockers(src,
 NEW.target_minor);
 IF cardinality(problems)>0 THEN RAISE EXCEPTION 'Paid bill source blocked: %',problems USING
 ERRCODE='23514'; END IF;
 SELECT coalesce(sum(amount_minor),0) INTO paid FROM payments WHERE order_id=o.id;
 SELECT coalesce(sum(amount_minor),0) INTO refunded FROM refunds WHERE order_id=o.id;
 IF NEW.original_shift_id<>o.shift_id OR NEW.invoice_no<>o.invoice_no OR
 NEW.prior_effective_minor<>paid-refunded
  OR NEW.source_revision<>dcompany_paid_bill_revision(src) OR NEW.revision<>NEW.source_revision+1
  THEN
  RAISE EXCEPTION 'Paid bill source revision or amount changed' USING ERRCODE='23514'; END IF;
 expected:=jsonb_build_object('protocol',1,'order_id',NEW.order_id,'operation',NEW.operation,
  'target_total_minor',NEW.target_minor,'money_received','received','reason',NEW.reason,
  'settlement_shift_id',NEW.settlement_shift_id,'refund_mode',NEW.refund_mode,
  'source_revision',NEW.source_revision,'source_fingerprint',NEW.source_fingerprint);
 IF NEW.request_body IS DISTINCT FROM expected OR
 NEW.request_path<>'/api/v1/pos/paid-bill-amendments/commit'
  OR NEW.request_hash<>encode(sha256(convert_to(expected::text,'UTF8')),'hex')
  OR NEW.source_snapshot IS DISTINCT FROM (src||jsonb_build_object('selection',
  expected-ARRAY['source_revision','source_fingerprint']))
  OR NEW.source_fingerprint<>encode(sha256(convert_to(NEW.source_snapshot::text,'UTF8')),'hex') THEN
  RAISE EXCEPTION 'Paid bill quote or semantic command changed' USING ERRCODE='23514'; END IF;
 IF NEW.difference_minor=0 AND (NEW.operation<>'discard' OR paid<>refunded OR refunded<=0
   OR NEW.refund_request_id IS NOT NULL OR NEW.settlement_shift_id IS NOT NULL OR NEW.refund_mode
   IS NOT NULL) THEN
  RAISE EXCEPTION 'Zero-money discard requires fully completed refund lineage' USING
  ERRCODE='23514'; END IF;
 IF NEW.difference_minor>0 AND (NEW.refund_request_id IS NULL OR NEW.settlement_shift_id IS NULL
 OR NEW.refund_mode IS NULL) THEN
  RAISE EXCEPTION 'Paid bill difference requires one exact refund reservation' USING
  ERRCODE='23514'; END IF;
 IF EXISTS(SELECT 1 FROM paid_bill_amendment_intents a WHERE a.order_id=o.id AND
 a.operation='discard'
  AND (a.refund_request_id IS NULL OR EXISTS(SELECT 1 FROM refunds r WHERE
  r.request_id=a.refund_request_id))) THEN
  RAISE EXCEPTION 'This receipt is already discarded' USING ERRCODE='23514'; END IF;
 expected:=jsonb_build_object('id',NEW.id,'order_id',NEW.order_id,'revision',NEW.revision,
  'refund_request_id',NEW.refund_request_id,'target_total_minor',NEW.target_minor,
  'status',CASE WHEN NEW.difference_minor=0 THEN 'discarded' ELSE 'pending_settlement' END,
  'financial_effect',false);
 IF NEW.response_body IS DISTINCT FROM expected THEN
  RAISE EXCEPTION 'Paid bill durable acceptance response is invalid' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE FUNCTION dcompany_complete_paid_bill_intent() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r pos_refund_requests; live jsonb; methods text[]; expected_method text:='cash';
BEGIN
 IF NOT EXISTS(SELECT 1 FROM users u JOIN user_roles ur ON ur.user_id=u.id
  JOIN roles role ON role.id=ur.role_id WHERE u.id=NEW.recorded_by AND u.company_id=NEW.company_id
  AND u.status='active' AND u.deleted_at IS NULL AND role.company_id=NEW.company_id AND
  role.code='super_owner') THEN
  RAISE EXCEPTION 'Paid bill amendment requires current protected Owner at durable commit' USING
  ERRCODE='23514'; END IF;
 IF NEW.refund_request_id IS NOT NULL THEN
  IF NEW.refund_mode='original' THEN
   SELECT array_agg(DISTINCT coalesce(tc.effective_method,p.method)) INTO methods FROM payments p
    LEFT JOIN payment_tender_corrections tc ON tc.payment_id=p.id AND tc.company_id=NEW.company_id
    WHERE p.order_id=NEW.order_id;
   IF cardinality(methods) IS DISTINCT FROM 1 THEN
    RAISE EXCEPTION 'Paid bill original refund rail is not unique' USING ERRCODE='23514'; END IF;
   expected_method:=methods[1];
  END IF;
  SELECT * INTO r FROM pos_refund_requests WHERE id=NEW.refund_request_id;
  IF NOT FOUND OR (r.company_id,r.order_id,r.branch_id,r.terminal_id,r.shift_id,r.approved_by,
     r.amount_minor,r.mode,r.reason_code,r.order_refundable_snapshot_minor)
   IS DISTINCT FROM (NEW.company_id,NEW.order_id,NEW.branch_id,NEW.terminal_id,
   NEW.settlement_shift_id,
     NEW.recorded_by,NEW.difference_minor,NEW.refund_mode,'paid_bill_amendment'::varchar,
     NEW.prior_effective_minor)
   OR r.settlement_method IS DISTINCT FROM expected_method
   OR r.accepted_at<NEW.recorded_at OR r.accepted_at>NEW.recorded_at+interval '30 seconds'
   OR r.note IS NOT NULL OR r.external_reference IS NOT NULL OR r.provider_settled_at IS NOT NULL
   OR r.client_action_id IS DISTINCT FROM 'paid-bill:'||NEW.id::text
   OR r.idempotency_key IS DISTINCT FROM 'paid-bill:'||NEW.id::text THEN
   RAISE EXCEPTION 'Paid bill exact refund link is incomplete or invalid' USING ERRCODE='23514';
   END IF;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM audit_log WHERE company_id=NEW.company_id
   AND entity_type='PaidBillAmendmentIntent' AND entity_id=NEW.id::text AND action='create') THEN
  RAISE EXCEPTION 'Paid bill intent requires its atomic audit record' USING ERRCODE='23514'; END IF;
 live:=dcompany_paid_bill_source(NEW.order_id,NEW.company_id,NEW.settlement_shift_id);
 -- Remove only this command's own append-only acceptance facts. All other
 -- financial and settlement-scope evidence must still match at durable commit.
 live:=jsonb_set(live,'{refund_requests}',(SELECT coalesce(jsonb_agg(v ORDER BY v->>'id'),
 '[]'::jsonb)
  FROM jsonb_array_elements(live->'refund_requests') v WHERE (v->>'id')::uuid IS DISTINCT FROM
  NEW.refund_request_id));
 live:=jsonb_set(live,'{refund_progress}',(SELECT coalesce(jsonb_agg(v ORDER BY v->>'request_id'),
 '[]'::jsonb)
  FROM jsonb_array_elements(live->'refund_progress') v WHERE (v->>'request_id')::uuid IS DISTINCT
  FROM NEW.refund_request_id));
 live:=jsonb_set(live,'{amendments}',(SELECT coalesce(jsonb_agg(v ORDER BY
 (v->>'revision')::bigint),'[]'::jsonb)
  FROM jsonb_array_elements(live->'amendments') v WHERE (v->>'id')::uuid<>NEW.id));
 IF live||jsonb_build_object('selection',NEW.source_snapshot->'selection') IS DISTINCT FROM
 NEW.source_snapshot THEN
  RAISE EXCEPTION 'Paid bill captured source changed before durable commit' USING
  ERRCODE='23514'; END IF;
 RETURN NULL;
END $$;
CREATE FUNCTION dcompany_guard_paid_bill_legacy_source() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target uuid; c uuid; a paid_bill_amendment_intents;
BEGIN
 target:=NEW.order_id;
 SELECT company_id INTO c FROM orders WHERE id=target FOR UPDATE;
 SELECT * INTO a FROM paid_bill_amendment_intents WHERE order_id=target AND company_id=c ORDER BY
 revision DESC LIMIT 1;
 IF FOUND AND NOT coalesce(a.refund_request_id IS NOT NULL AND
 ((TG_TABLE_NAME='pos_refund_requests' AND TG_OP='INSERT' AND NEW.id=a.refund_request_id)
   OR (TG_TABLE_NAME='refunds' AND TG_OP='INSERT' AND
   (to_jsonb(NEW)->>'request_id')::uuid=a.refund_request_id)),false) THEN
  RAISE EXCEPTION
  'This paid bill has amendment history; use its current revision-aware Owner action' USING
  ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER paid_bill_intent_guard BEFORE INSERT OR UPDATE OR DELETE ON
paid_bill_amendment_intents
 FOR EACH ROW EXECUTE FUNCTION dcompany_guard_paid_bill_intent();
CREATE TRIGGER paid_bill_intent_no_truncate BEFORE TRUNCATE ON paid_bill_amendment_intents
 FOR EACH STATEMENT EXECUTE FUNCTION guard_audit_log_append_only();
CREATE CONSTRAINT TRIGGER paid_bill_intent_complete AFTER INSERT ON paid_bill_amendment_intents
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION dcompany_complete_paid_bill_intent();
"""


def upgrade():
    op.execute(r"""
CREATE TABLE paid_bill_amendment_intents (
 id uuid PRIMARY KEY, company_id uuid NOT NULL REFERENCES companies(id) ON DELETE RESTRICT,
 order_id uuid NOT NULL REFERENCES orders(id) ON DELETE RESTRICT,
 branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT,
 terminal_id uuid NOT NULL REFERENCES terminals(id) ON DELETE RESTRICT,
 original_shift_id uuid NOT NULL REFERENCES shifts(id) ON DELETE RESTRICT,
 settlement_shift_id uuid REFERENCES shifts(id) ON DELETE RESTRICT,
 recorded_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
 refund_request_id uuid REFERENCES pos_refund_requests(id) ON DELETE RESTRICT DEFERRABLE
 INITIALLY DEFERRED,
 invoice_no varchar(80) NOT NULL, operation varchar(16) NOT NULL CHECK(operation IN ('decrease',
 'discard')),
 refund_mode varchar(16) CHECK(refund_mode IN ('cash','original')),
 prior_effective_minor bigint NOT NULL CHECK(prior_effective_minor>=0), target_minor bigint NOT
 NULL CHECK(target_minor>=0),
 difference_minor bigint NOT NULL CHECK(difference_minor>=0 AND
 difference_minor=prior_effective_minor-target_minor),
 source_revision bigint NOT NULL CHECK(source_revision>=0), revision bigint NOT NULL
 CHECK(revision=source_revision+1),
 source_fingerprint varchar(64) NOT NULL CHECK(source_fingerprint ~ '^[0-9a-f]{64}$'),
 source_snapshot jsonb NOT NULL,
 reason varchar(500) NOT NULL CHECK(length(trim(reason)) BETWEEN 3 AND 500),
 action_key varchar(160) NOT NULL CHECK(length(trim(action_key)) BETWEEN 1 AND 160),
 request_path varchar(100) NOT NULL, request_hash varchar(64) NOT NULL CHECK(request_hash ~
 '^[0-9a-f]{64}$'),
 request_body jsonb NOT NULL, response_body jsonb NOT NULL, recorded_at timestamptz NOT NULL,
 CONSTRAINT uq_paid_bill_action UNIQUE(company_id,action_key), CONSTRAINT uq_paid_bill_revision
 UNIQUE(order_id,revision),
 CONSTRAINT uq_paid_bill_refund_link UNIQUE(refund_request_id),
 CHECK(operation<>'discard' OR target_minor=0), CHECK(difference_minor>0 OR operation='discard'),
 CHECK(recorded_at BETWEEN statement_timestamp()-interval '30 seconds' AND
 statement_timestamp()+interval '30 seconds')
);
CREATE INDEX ix_paid_bill_amendment_intents_company_id ON paid_bill_amendment_intents(company_id);
CREATE INDEX ix_paid_bill_amendment_intents_branch_id ON paid_bill_amendment_intents(branch_id);
CREATE INDEX ix_paid_bill_amendment_intents_terminal_id ON paid_bill_amendment_intents(terminal_id);
CREATE INDEX ix_paid_bill_amendment_intents_original_shift_id ON
paid_bill_amendment_intents(original_shift_id);
CREATE INDEX ix_paid_bill_amendment_intents_settlement_shift_id ON
paid_bill_amendment_intents(settlement_shift_id);
CREATE INDEX ix_paid_bill_amendment_intents_recorded_by ON paid_bill_amendment_intents(recorded_by);

""")
    op.execute(SOURCE_SQL)
    op.execute(GUARDS_SQL)
    for table in (
        "pos_refund_requests",
        "refunds",
        "payment_tender_corrections",
        "pos_receipt_corrections",
        "payments",
    ):
        op.execute(
            f"CREATE TRIGGER aa_paid_bill_revision_source BEFORE INSERT OR UPDATE ON {table} "
            "FOR EACH ROW EXECUTE FUNCTION dcompany_guard_paid_bill_legacy_source()"
        )


def downgrade():
    op.execute("""LOCK TABLE paid_bill_amendment_intents IN ACCESS EXCLUSIVE MODE;
DO $$ BEGIN IF EXISTS(SELECT 1 FROM paid_bill_amendment_intents) THEN
 RAISE EXCEPTION 'Paid bill intent evidence prevents downgrade'; END IF; END $$;""")
    for table in (
        "pos_refund_requests",
        "refunds",
        "payment_tender_corrections",
        "pos_receipt_corrections",
        "payments",
    ):
        op.execute(f"DROP TRIGGER aa_paid_bill_revision_source ON {table}")
    op.execute("""DROP FUNCTION dcompany_guard_paid_bill_legacy_source();
DROP TABLE paid_bill_amendment_intents;
DROP FUNCTION dcompany_guard_paid_bill_intent(); DROP FUNCTION dcompany_complete_paid_bill_intent();
DROP FUNCTION dcompany_paid_bill_allocation_blockers(jsonb,bigint);
DROP FUNCTION dcompany_paid_bill_proportional(bigint,bigint,bigint);
DROP FUNCTION dcompany_paid_bill_blockers(jsonb); DROP FUNCTION dcompany_paid_bill_revision(jsonb);
DROP FUNCTION dcompany_paid_bill_source(uuid,uuid,uuid);""")
