"""Permanent exact-key GRN decisions, completed receipt proof and recovery family."""
from alembic import op

revision = "0103"
down_revision = "0102"
branch_labels = None
depends_on = None

FUNCTIONS = r"""
CREATE FUNCTION dcompany_grn_wire_hash(k text, raw text) RETURNS text
LANGUAGE sql IMMUTABLE AS $$ SELECT encode(sha256(convert_to(
 'POST'||E'\n/api/v1/inventory/grn\n\n'||k||E'\n'||raw,'UTF8')),'hex') $$;
CREATE FUNCTION dcompany_grn_unique_json(v json) RETURNS boolean LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE x json;
BEGIN
 IF json_typeof(v)='object' THEN
  IF (SELECT count(*)<>count(DISTINCT key) FROM json_each(v)) THEN RETURN false; END IF;
  FOR x IN SELECT value FROM json_each(v) LOOP IF NOT dcompany_grn_unique_json(x) THEN RETURN false; END IF; END LOOP;
 ELSIF json_typeof(v)='array' THEN
  FOR x IN SELECT value FROM json_array_elements(v) LOOP IF NOT dcompany_grn_unique_json(x) THEN RETURN false; END IF; END LOOP;
 END IF; RETURN true;
END $$;
CREATE FUNCTION dcompany_grn_valid_wire(raw text) RETURNS boolean LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE b jsonb; l jsonb; original_line json; n numeric; total numeric:=0; k text;
BEGIN
 IF raw IS NULL THEN RETURN false; END IF;
 IF octet_length(raw) NOT BETWEEN 2 AND 131072 OR NOT dcompany_grn_unique_json(raw::json) THEN RETURN false; END IF;
 b=raw::jsonb;
 IF jsonb_typeof(b)<>'object' OR NOT b ?& ARRAY['branch_id','supplier_id','lines'] OR EXISTS(
  SELECT 1 FROM jsonb_object_keys(b) AS a(k) WHERE a.k<>ALL(ARRAY['branch_id','supplier_id','lines','supplier_invoice_no','supplier_invoice_amount_minor','received_at','notes'])) THEN RETURN false; END IF;
 PERFORM (b->>'branch_id')::uuid,(b->>'supplier_id')::uuid;
 IF jsonb_typeof(b->'branch_id')<>'string' OR jsonb_typeof(b->'supplier_id')<>'string' OR jsonb_typeof(b->'lines')<>'array' OR jsonb_array_length(b->'lines') NOT BETWEEN 1 AND 200 THEN RETURN false; END IF;
 FOREACH k IN ARRAY ARRAY['supplier_invoice_no','notes'] LOOP
  IF b ? k AND b->k<>'null'::jsonb AND (jsonb_typeof(b->k)<>'string' OR length(b->>k)>CASE WHEN k='notes' THEN 500 ELSE 100 END) THEN RETURN false; END IF;
 END LOOP;
 FOR original_line IN SELECT value FROM json_array_elements(raw::json->'lines') LOOP
  l=original_line::jsonb;
  IF (original_line->'unit_cost_minor')::text !~ '^[0-9]+$' THEN RETURN false; END IF;
  IF l->>'expires_at' IS NOT NULL THEN
   IF jsonb_typeof(l->'expires_at')<>'string' OR l->>'expires_at' !~ '(Z|[+-][0-9]{2}:[0-9]{2})$' THEN RETURN false; END IF; PERFORM (l->>'expires_at')::timestamptz;
  END IF;
  IF jsonb_typeof(l)<>'object' OR NOT l ?& ARRAY['ingredient_id','qty','unit_cost_minor'] OR EXISTS(SELECT 1 FROM jsonb_object_keys(l) AS a(k) WHERE a.k<>ALL(ARRAY['ingredient_id','qty','unit_cost_minor','expires_at','lot_code'])) THEN RETURN false; END IF;
  PERFORM (l->>'ingredient_id')::uuid;
  IF jsonb_typeof(l->'ingredient_id')<>'string' OR jsonb_typeof(l->'qty')<>'string' OR l->>'qty' !~ '^(0|[1-9][0-9]{0,9})(\.[0-9]{0,3}[1-9])?$' OR (l->>'qty')::numeric<=0 OR (l->>'qty')::numeric>9999999999.9999 OR jsonb_typeof(l->'unit_cost_minor')<>'number' OR l->>'unit_cost_minor' !~ '^[0-9]+$' OR (l->>'unit_cost_minor')::numeric>9007199254740991 THEN RETURN false; END IF;
  n=div((l->>'qty')::numeric*10000*(l->>'unit_cost_minor')::numeric+5000,10000);
  IF n>9007199254740991 THEN RETURN false; END IF; total=total+n;
  IF l ? 'lot_code' AND l->'lot_code'<>'null'::jsonb AND (jsonb_typeof(l->'lot_code')<>'string' OR length(l->>'lot_code')>100) THEN RETURN false; END IF;
 END LOOP;
 IF total>9007199254740991 THEN RETURN false; END IF;
 IF b->>'received_at' IS NOT NULL THEN IF jsonb_typeof(b->'received_at')<>'string' OR b->>'received_at' !~ '(Z|[+-][0-9]{2}:[0-9]{2})$' THEN RETURN false; END IF; PERFORM (b->>'received_at')::timestamptz; END IF;
 IF b ? 'supplier_invoice_amount_minor' AND b->'supplier_invoice_amount_minor'<>'null'::jsonb AND ((raw::json->'supplier_invoice_amount_minor')::text !~ '^[0-9]+$' OR jsonb_typeof(b->'supplier_invoice_amount_minor')<>'number' OR b->>'supplier_invoice_amount_minor' !~ '^[0-9]+$' OR (b->>'supplier_invoice_amount_minor')::numeric<>total) THEN RETURN false; END IF;
 RETURN true;
EXCEPTION WHEN OTHERS THEN RETURN false;
END $$;
CREATE FUNCTION dcompany_grn_source(gid uuid) RETURNS jsonb LANGUAGE plpgsql SET timezone='UTC' AS $$
DECLARE g grns%ROWTYPE; p purchase_orders%ROWTYPE; total numeric; count_lines integer; j journal_entries%ROWTYPE; result jsonb;
BEGIN
 SELECT * INTO STRICT g FROM grns WHERE id=gid;
 SELECT * INTO STRICT p FROM purchase_orders WHERE id=g.purchase_order_id;
 -- Serialize supplier identity with arbitrary SQL identity writers as well as HTTP.
 PERFORM id FROM suppliers WHERE id=p.supplier_id FOR SHARE;
 IF NOT EXISTS(SELECT 1 FROM branches WHERE id=p.branch_id AND company_id=p.company_id) OR NOT EXISTS(SELECT 1 FROM suppliers WHERE id=p.supplier_id AND company_id=p.company_id) THEN RAISE EXCEPTION 'GRN completion requires exact tenant provenance' USING ERRCODE='23514'; END IF;
 SELECT count(*),sum(div(qty_received*10000*cost_per_unit_minor::numeric+5000,10000)) INTO count_lines,total FROM grn_lines WHERE grn_id=gid;
 IF count_lines<1 OR total IS NULL OR total<0 OR total>9223372036854775807 OR p.total_minor IS DISTINCT FROM total OR (g.supplier_invoice_amount_minor IS NOT NULL AND g.supplier_invoice_amount_minor<>total) THEN RAISE EXCEPTION 'GRN completion requires nonempty exact line total' USING ERRCODE='23514'; END IF;
 IF EXISTS(SELECT 1 FROM grn_lines l LEFT JOIN batches b ON b.id=l.batch_id LEFT JOIN ingredients i ON i.id=l.ingredient_id WHERE l.grn_id=gid AND (l.qty_received<=0 OR l.cost_per_unit_minor<0 OR b.id IS NULL OR i.id IS NULL OR i.company_id<>p.company_id OR b.ingredient_id<>l.ingredient_id OR b.grn_id IS DISTINCT FROM gid OR b.branch_id<>p.branch_id OR b.supplier_id IS DISTINCT FROM p.supplier_id OR b.qty_initial<>l.qty_received OR b.cost_per_unit_minor<>l.cost_per_unit_minor OR b.received_at<>g.received_at)) OR (SELECT count(DISTINCT batch_id) FROM grn_lines WHERE grn_id=gid)<>count_lines OR (SELECT count(*) FROM batches WHERE grn_id=gid)<>count_lines THEN RAISE EXCEPTION 'GRN completion has missing or inconsistent physical cohort' USING ERRCODE='23514'; END IF;
 IF EXISTS((SELECT ingredient_id,qty_received,cost_per_unit_minor,count(*) FROM grn_lines WHERE grn_id=gid GROUP BY 1,2,3 EXCEPT SELECT ingredient_id,qty_received,unit_cost_minor,count(*) FROM purchase_order_lines WHERE purchase_order_id=p.id AND qty_ordered=qty_received GROUP BY 1,2,3) UNION ALL (SELECT ingredient_id,qty_received,unit_cost_minor,count(*) FROM purchase_order_lines WHERE purchase_order_id=p.id GROUP BY 1,2,3 EXCEPT SELECT ingredient_id,qty_received,cost_per_unit_minor,count(*) FROM grn_lines WHERE grn_id=gid GROUP BY 1,2,3)) THEN RAISE EXCEPTION 'GRN completion requires exact PO line cohort' USING ERRCODE='23514'; END IF;
 IF (SELECT count(*) FROM stock_movements WHERE ref_id=gid)<>count_lines OR EXISTS(SELECT 1 FROM grn_lines l WHERE l.grn_id=gid AND (SELECT count(*) FROM stock_movements m WHERE m.ref_id=gid AND m.ref_type='grn' AND m.type='grn' AND m.batch_id=l.batch_id AND m.branch_id=p.branch_id AND m.qty_delta=l.qty_received AND m.cost_per_unit_minor=l.cost_per_unit_minor AND m.created_by IS NOT DISTINCT FROM g.received_by)<>1) THEN RAISE EXCEPTION 'GRN completion requires exact original movements' USING ERRCODE='23514'; END IF;
 IF total=0 THEN
  IF g.journal_entry_id IS NOT NULL OR EXISTS(SELECT 1 FROM journal_entries WHERE ref_type='grn_receipt' AND ref_id=gid) THEN RAISE EXCEPTION 'Zero GRN completion cannot contain a journal' USING ERRCODE='23514'; END IF;
 ELSE
  SELECT * INTO j FROM journal_entries WHERE id=g.journal_entry_id;
  IF NOT FOUND OR j.company_id<>p.company_id OR j.branch_id IS DISTINCT FROM p.branch_id OR j.ref_type<>'grn_receipt' OR j.ref_id IS DISTINCT FROM gid OR j.total_minor<>total OR j.posted_at<>g.received_at OR j.voided_at IS NOT NULL OR (SELECT count(*) FROM journal_lines WHERE journal_entry_id=j.id)<>2 OR NOT EXISTS(SELECT 1 FROM journal_lines l JOIN accounts a ON a.id=l.account_id WHERE l.journal_entry_id=j.id AND l.side='dr' AND l.amount_minor=total AND a.company_id=p.company_id AND a.code='1200' AND a.type='asset') OR NOT EXISTS(SELECT 1 FROM journal_lines l JOIN accounts a ON a.id=l.account_id WHERE l.journal_entry_id=j.id AND l.side='cr' AND l.amount_minor=total AND a.company_id=p.company_id AND a.code='2000' AND a.type='liability') THEN RAISE EXCEPTION 'GRN completion requires its exact Inventory AP journal' USING ERRCODE='23514'; END IF;
 END IF;
 SELECT jsonb_build_object('grn',to_jsonb(g)-'updated_at','po',jsonb_build_object('id',p.id,'company_id',p.company_id,'branch_id',p.branch_id,'supplier_id',p.supplier_id,'po_number',p.po_number,'total_minor',p.total_minor,'created_by',p.created_by,'expected_at',p.expected_at),
 'lines',(SELECT jsonb_agg(to_jsonb(l)-'updated_at' ORDER BY l.created_at,l.id) FROM grn_lines l WHERE grn_id=gid),
 'po_lines',(SELECT jsonb_agg(to_jsonb(l)-'updated_at' ORDER BY l.id) FROM purchase_order_lines l WHERE purchase_order_id=p.id),
 'batches',(SELECT jsonb_agg(to_jsonb(b)-'qty_on_hand'-'updated_at' ORDER BY b.id) FROM batches b WHERE grn_id=gid),
 'ingredients',(SELECT jsonb_agg(jsonb_build_object('id',i.id,'company_id',i.company_id,'base_unit',i.base_unit) ORDER BY i.id) FROM ingredients i WHERE id IN(SELECT ingredient_id FROM grn_lines WHERE grn_id=gid)),
 'movements',(SELECT jsonb_agg(to_jsonb(m)-'updated_at' ORDER BY m.id) FROM stock_movements m WHERE ref_id=gid),
 'journal',CASE WHEN j.id IS NULL THEN NULL ELSE to_jsonb(j)-'updated_at' END,
 'journal_lines',(SELECT coalesce(jsonb_agg(to_jsonb(l)-'updated_at' ORDER BY l.id),'[]') FROM journal_lines l WHERE journal_entry_id=g.journal_entry_id)) INTO result;
 RETURN result;
END $$;
CREATE FUNCTION dcompany_grn_response(src jsonb) RETURNS jsonb LANGUAGE sql IMMUTABLE AS $$
 SELECT jsonb_build_object('ok',true,'id',src->'grn'->>'id','purchase_order_id',src->'po'->>'id','batches_created',jsonb_array_length(src->'lines'),'batch_ids',(SELECT jsonb_agg(l->>'batch_id') FROM jsonb_array_elements(src->'lines') l),'line_ids',(SELECT jsonb_agg(l->>'id') FROM jsonb_array_elements(src->'lines') l),'total_minor',(src->'po'->>'total_minor')::bigint,'supplier_invoice_no',src->'grn'->'supplier_invoice_no','journal_entry_id',src->'grn'->'journal_entry_id','accounting_status',CASE WHEN src->'grn'->>'journal_entry_id' IS NULL THEN 'no_balance' ELSE 'posted' END)
$$;
CREATE FUNCTION dcompany_grn_append_only() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'GRN command evidence is append-only' USING ERRCODE='23514'; END $$;
CREATE FUNCTION dcompany_grn_accept() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p purchase_orders%ROWTYPE; terminal uuid; known boolean:=false;
BEGIN
 IF NEW.idempotency_key IS NULL THEN RETURN NEW; END IF;
 SELECT * INTO STRICT p FROM purchase_orders WHERE id=NEW.purchase_order_id;
 IF NEW.idempotency_key LIKE 'grn:v1:%' AND NEW.idempotency_key !~ ('^grn:v1:'||p.company_id::text||':'||coalesce(NEW.received_by::text,'MISSING')||':[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$') THEN RAISE EXCEPTION 'GRN namespace ownership mismatch' USING ERRCODE='23514'; END IF;
 SELECT terminal_id INTO terminal FROM idempotency_keys WHERE key=NEW.idempotency_key AND request_hash=NEW.request_hash AND user_id IS NOT DISTINCT FROM NEW.received_by;
 known=terminal IS NOT NULL;
 INSERT INTO grn_action_decisions(id,action_key,company_id,branch_id,actor_user_id,terminal_id,terminal_known,request_hash,outcome,accepted_grn_id,decided_at)
 VALUES(NEW.id,NEW.idempotency_key,p.company_id,p.branch_id,NEW.received_by,terminal,known,NEW.request_hash,'accepted',NEW.id,statement_timestamp());
 RETURN NEW;
END $$;
CREATE FUNCTION dcompany_grn_proof_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE d grn_action_decisions%ROWTYPE; src jsonb; body jsonb;
BEGIN
 SELECT * INTO STRICT d FROM grn_action_decisions WHERE id=NEW.id;
 src=dcompany_grn_source(d.accepted_grn_id);
 IF d.outcome<>'accepted' OR NEW.source IS DISTINCT FROM src OR NEW.response IS DISTINCT FROM dcompany_grn_response(src) THEN RAISE EXCEPTION 'GRN proof must exactly match completed source' USING ERRCODE='23514'; END IF;
 IF NEW.profile='wire_v1' THEN
  IF NEW.original_wire_json IS NULL OR NOT dcompany_grn_valid_wire(NEW.original_wire_json) OR dcompany_grn_wire_hash(d.action_key,NEW.original_wire_json)<>d.request_hash OR NOT NEW.terminal_known THEN RAISE EXCEPTION 'GRN producer proof requires validated exact wire identity' USING ERRCODE='23514'; END IF;
  body=NEW.original_wire_json::jsonb;
  IF (body->>'branch_id')::uuid<>d.branch_id OR (body->>'supplier_id')::uuid<>(src->'po'->>'supplier_id')::uuid OR (body->>'supplier_invoice_no') IS DISTINCT FROM src->'grn'->>'supplier_invoice_no' OR (body->>'notes') IS DISTINCT FROM src->'grn'->>'notes' OR (body->>'supplier_invoice_amount_minor')::numeric IS DISTINCT FROM (src->'grn'->>'supplier_invoice_amount_minor')::numeric OR (body->>'received_at' IS NOT NULL AND (body->>'received_at')::timestamptz<>(src->'grn'->>'received_at')::timestamptz) OR NOT EXISTS(SELECT 1 FROM idempotency_keys WHERE key=d.action_key AND request_hash=d.request_hash AND user_id=d.actor_user_id AND terminal_id IS NOT DISTINCT FROM NEW.terminal_id) OR EXISTS((SELECT (l->>'ingredient_id')::uuid,(l->>'qty')::numeric,(l->>'unit_cost_minor')::bigint,(l->>'expires_at')::timestamptz,l->>'lot_code',count(*) FROM jsonb_array_elements(body->'lines') l GROUP BY 1,2,3,4,5 EXCEPT SELECT (l->>'ingredient_id')::uuid,(l->>'qty_received')::numeric,(l->>'cost_per_unit_minor')::bigint,(b->>'expires_at')::timestamptz,b->>'lot_code',count(*) FROM jsonb_array_elements(src->'lines') l JOIN jsonb_array_elements(src->'batches') b ON b->>'id'=l->>'batch_id' GROUP BY 1,2,3,4,5) UNION ALL (SELECT (l->>'ingredient_id')::uuid,(l->>'qty_received')::numeric,(l->>'cost_per_unit_minor')::bigint,(b->>'expires_at')::timestamptz,b->>'lot_code',count(*) FROM jsonb_array_elements(src->'lines') l JOIN jsonb_array_elements(src->'batches') b ON b->>'id'=l->>'batch_id' GROUP BY 1,2,3,4,5 EXCEPT SELECT (l->>'ingredient_id')::uuid,(l->>'qty')::numeric,(l->>'unit_cost_minor')::bigint,(l->>'expires_at')::timestamptz,l->>'lot_code',count(*) FROM jsonb_array_elements(body->'lines') l GROUP BY 1,2,3,4,5)) THEN RAISE EXCEPTION 'GRN wire producer source mismatch' USING ERRCODE='23514'; END IF;
 ELSE
  IF NEW.original_wire_json IS NOT NULL OR NEW.terminal_known IS DISTINCT FROM d.terminal_known OR NEW.terminal_id IS DISTINCT FROM d.terminal_id THEN RAISE EXCEPTION 'Legacy GRN provenance cannot be invented' USING ERRCODE='23514'; END IF;
 END IF;
 INSERT INTO audit_log(company_id,actor_user_id,action,entity_type,entity_id,before,after) VALUES(d.company_id,d.actor_user_id,'grn_command_accepted','GrnCompletionProof',NEW.id::text,NULL,'{"purpose":"inventory_grn_receive"}');
 RETURN NEW;
END $$;
CREATE FUNCTION dcompany_grn_complete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE d grn_action_decisions%ROWTYPE; p grn_completion_proofs%ROWTYPE; src jsonb;
BEGIN
 SELECT * INTO STRICT d FROM grn_action_decisions WHERE id=NEW.id;
 IF d.outcome='cancelled' THEN
  IF NOT EXISTS(SELECT 1 FROM grn_recovery_sessions s JOIN users u ON u.id=s.user_id WHERE s.id=d.recovery_session_id AND s.revoked_at IS NULL AND s.expires_at>clock_timestamp() AND s.auth_version=u.auth_version AND u.status='active' AND u.deleted_at IS NULL) THEN RAISE EXCEPTION 'GRN retirement authority expired before commit' USING ERRCODE='23514'; END IF;
  RETURN NEW; END IF;
 src=dcompany_grn_source(d.accepted_grn_id);
 SELECT * INTO p FROM grn_completion_proofs WHERE id=d.id;
 IF NOT FOUND THEN INSERT INTO grn_completion_proofs(id,profile,terminal_id,terminal_known,source,response) VALUES(d.id,'legacy',d.terminal_id,d.terminal_known,src,dcompany_grn_response(src));
 ELSIF p.source IS DISTINCT FROM src OR p.response IS DISTINCT FROM dcompany_grn_response(src) THEN RAISE EXCEPTION 'GRN final source changed after producer proof' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
"""


GUARDS = r"""
CREATE FUNCTION dcompany_grn_decision_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE s grn_recovery_sessions%ROWTYPE; u users%ROWTYPE; g grns%ROWTYPE; p purchase_orders%ROWTYPE;
BEGIN
 IF NEW.outcome='accepted' THEN
  SELECT * INTO STRICT g FROM grns WHERE id=NEW.accepted_grn_id;
  SELECT * INTO STRICT p FROM purchase_orders WHERE id=g.purchase_order_id;
  IF NEW.terminal_id IS DISTINCT FROM(SELECT terminal_id FROM idempotency_keys WHERE key=g.idempotency_key AND request_hash=g.request_hash AND user_id IS NOT DISTINCT FROM g.received_by) OR NEW.terminal_known IS DISTINCT FROM coalesce((SELECT terminal_id IS NOT NULL FROM idempotency_keys WHERE key=g.idempotency_key AND request_hash=g.request_hash AND user_id IS NOT DISTINCT FROM g.received_by),false) THEN RAISE EXCEPTION 'Legacy GRN observed transport cannot be invented' USING ERRCODE='23514'; END IF;
  IF (NEW.id,NEW.company_id,NEW.branch_id,NEW.actor_user_id,NEW.action_key,NEW.request_hash) IS DISTINCT FROM (g.id,p.company_id,p.branch_id,g.received_by,g.idempotency_key,g.request_hash) THEN RAISE EXCEPTION 'GRN acceptance must derive from exact source' USING ERRCODE='23514'; END IF;
 ELSE
  IF NEW.action_key !~ ('^grn:v1:'||NEW.company_id::text||':'||NEW.actor_user_id::text||':[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$') OR NOT dcompany_grn_valid_wire(NEW.original_wire_json) OR (NEW.original_wire_json::jsonb->>'branch_id')::uuid<>NEW.branch_id OR dcompany_grn_wire_hash(NEW.action_key,NEW.original_wire_json)<>NEW.request_hash THEN RAISE EXCEPTION 'GRN retirement requires exact owned wire command' USING ERRCODE='23514'; END IF;
  SELECT * INTO s FROM grn_recovery_sessions WHERE id=NEW.recovery_session_id;
  SELECT * INTO u FROM users WHERE id=NEW.actor_user_id;
  IF NOT FOUND OR u.deleted_at IS NOT NULL OR u.status<>'active' OR u.company_id<>NEW.company_id OR s.id IS NULL OR s.company_id<>NEW.company_id OR s.user_id<>NEW.actor_user_id OR s.branch_id<>NEW.branch_id OR s.terminal_id IS DISTINCT FROM NEW.terminal_id OR s.action_key_hash<>encode(sha256(convert_to(NEW.action_key,'UTF8')),'hex') OR s.request_hash<>NEW.request_hash OR s.auth_version<>u.auth_version OR s.revoked_at IS NOT NULL OR s.expires_at<=statement_timestamp() OR NOT EXISTS(SELECT 1 FROM companies WHERE id=NEW.company_id AND deleted_at IS NULL) OR NOT EXISTS(SELECT 1 FROM branches WHERE id=NEW.branch_id AND company_id=NEW.company_id) OR NEW.decided_at NOT BETWEEN statement_timestamp()-interval '30 seconds' AND statement_timestamp()+interval '1 second' THEN RAISE EXCEPTION 'GRN retirement requires current exact recovery authority' USING ERRCODE='23514'; END IF;
  IF EXISTS(SELECT 1 FROM grns WHERE idempotency_key=NEW.action_key) OR EXISTS(SELECT 1 FROM idempotency_keys WHERE key=NEW.action_key AND (request_hash<>NEW.request_hash OR user_id IS DISTINCT FROM NEW.actor_user_id OR terminal_id IS DISTINCT FROM NEW.terminal_id OR response_status IS NOT NULL)) THEN RAISE EXCEPTION 'GRN retirement cannot contradict retained acceptance or identity' USING ERRCODE='23514'; END IF;
  INSERT INTO audit_log(company_id,actor_user_id,action,entity_type,entity_id,before,after) VALUES(NEW.company_id,NEW.actor_user_id,'grn_command_cancelled','GrnActionDecision',NEW.id::text,NULL,'{"purpose":"inventory_grn_receive","financial_effect": false,"stock_effect": false}');
 END IF;
 RETURN NEW;
END $$;
CREATE FUNCTION dcompany_grn_session_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN IF OLD.expires_at>statement_timestamp() THEN RAISE EXCEPTION 'Only expired GRN credentials may be removed' USING ERRCODE='23514'; END IF; RETURN OLD; END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.revoked_at IS NOT NULL OR NEW.issued_at NOT BETWEEN statement_timestamp()-interval '30 seconds' AND statement_timestamp()+interval '1 second' THEN RAISE EXCEPTION 'GRN credential issuance is invalid' USING ERRCODE='23514'; END IF;
 ELSE
  IF NEW IS NOT DISTINCT FROM OLD THEN RETURN NEW; END IF;
  IF (to_jsonb(NEW)-'revoked_at'-'revocation_reason') IS DISTINCT FROM(to_jsonb(OLD)-'revoked_at'-'revocation_reason') OR OLD.revoked_at IS NOT NULL OR NEW.revoked_at IS NULL OR NEW.revocation_reason IS NULL OR NEW.revoked_at>statement_timestamp()+interval '1 second' THEN RAISE EXCEPTION 'GRN recovery scope and revocation are immutable' USING ERRCODE='23514'; END IF;
 END IF; RETURN NEW;
END $$;
CREATE FUNCTION dcompany_grn_session_complete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE expected_action text;
BEGIN
 IF TG_OP='UPDATE' AND NEW IS NOT DISTINCT FROM OLD THEN RETURN NEW; END IF;
 expected_action=CASE WHEN TG_OP='INSERT' THEN 'grn_recovery_issued' ELSE 'grn_recovery_'||NEW.revocation_reason END;
 IF NOT EXISTS(SELECT 1 FROM audit_log a WHERE a.company_id=NEW.company_id AND a.actor_user_id=NEW.user_id AND a.entity_type='GrnRecoverySession' AND a.entity_id=NEW.id::text AND a.action=expected_action AND a.after='{"purpose":"inventory_grn_recovery"}'::jsonb) THEN RAISE EXCEPTION 'GRN credential requires atomic safe audit' USING ERRCODE='23514'; END IF; RETURN NEW;
END $$;
CREATE FUNCTION dcompany_grn_source_no_truncate() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF EXISTS(SELECT 1 FROM grn_completion_proofs) THEN RAISE EXCEPTION 'Completed GRN evidence prevents source truncation' USING ERRCODE='23514'; END IF; RETURN NULL; END $$;
CREATE FUNCTION dcompany_grn_sealed_source() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE obj jsonb; selector jsonb; destination jsonb; protected boolean:=false; allowed text[]:=ARRAY['updated_at'];
BEGIN
 obj=CASE WHEN TG_OP='INSERT' THEN to_jsonb(NEW) ELSE to_jsonb(OLD) END;
 IF TG_OP='UPDATE' AND TG_TABLE_NAME='grns' AND (to_jsonb(NEW)->'idempotency_key',to_jsonb(NEW)->'request_hash') IS DISTINCT FROM(obj->'idempotency_key',obj->'request_hash') THEN RAISE EXCEPTION 'GRN request identity is immutable after INSERT, including keyless legacy receipts' USING ERRCODE='23514'; END IF;
 IF TG_OP='INSERT' THEN
  IF TG_TABLE_NAME='grns' THEN selector=jsonb_build_object('po',jsonb_build_object('id',obj->'purchase_order_id'));
  ELSIF TG_TABLE_NAME IN('grn_lines','batches') THEN selector=jsonb_build_object('grn',jsonb_build_object('id',obj->'grn_id'));
  ELSIF TG_TABLE_NAME='purchase_order_lines' THEN selector=jsonb_build_object('po',jsonb_build_object('id',obj->'purchase_order_id'));
  ELSIF TG_TABLE_NAME='stock_movements' THEN selector=jsonb_build_object('grn',jsonb_build_object('id',obj->'ref_id'));
  ELSIF TG_TABLE_NAME='journal_lines' THEN selector=jsonb_build_object('journal',jsonb_build_object('id',obj->'journal_entry_id'));
  ELSIF TG_TABLE_NAME='journal_entries' AND obj->>'ref_type'='grn_receipt' THEN selector=jsonb_build_object('grn',jsonb_build_object('id',obj->'ref_id'));
  ELSE RETURN NEW; END IF;
 ELSE
  selector=CASE TG_TABLE_NAME
   WHEN 'grns' THEN jsonb_build_object('grn',jsonb_build_object('id',obj->'id'))
   WHEN 'purchase_orders' THEN jsonb_build_object('po',jsonb_build_object('id',obj->'id'))
   WHEN 'grn_lines' THEN jsonb_build_object('lines',jsonb_build_array(jsonb_build_object('id',obj->'id')))
   WHEN 'purchase_order_lines' THEN jsonb_build_object('po_lines',jsonb_build_array(jsonb_build_object('id',obj->'id')))
   WHEN 'batches' THEN jsonb_build_object('batches',jsonb_build_array(jsonb_build_object('id',obj->'id')))
   WHEN 'stock_movements' THEN jsonb_build_object('movements',jsonb_build_array(jsonb_build_object('id',obj->'id')))
   WHEN 'ingredients' THEN jsonb_build_object('ingredients',jsonb_build_array(jsonb_build_object('id',obj->'id')))
   WHEN 'suppliers' THEN jsonb_build_object('po',jsonb_build_object('supplier_id',obj->'id'))
   WHEN 'journal_entries' THEN jsonb_build_object('journal',jsonb_build_object('id',obj->'id'))
   WHEN 'journal_lines' THEN jsonb_build_object('journal_lines',jsonb_build_array(jsonb_build_object('id',obj->'id'))) END;
 END IF;
 IF TG_OP IN('INSERT','UPDATE') THEN
  destination=CASE TG_TABLE_NAME
   WHEN 'grns' THEN jsonb_build_object('po',jsonb_build_object('id',to_jsonb(NEW)->'purchase_order_id'))
   WHEN 'grn_lines' THEN jsonb_build_object('grn',jsonb_build_object('id',to_jsonb(NEW)->'grn_id'))
   WHEN 'batches' THEN jsonb_build_object('grn',jsonb_build_object('id',to_jsonb(NEW)->'grn_id'))
   WHEN 'purchase_order_lines' THEN jsonb_build_object('po',jsonb_build_object('id',to_jsonb(NEW)->'purchase_order_id'))
   WHEN 'stock_movements' THEN jsonb_build_object('grn',jsonb_build_object('id',to_jsonb(NEW)->'ref_id'))
   WHEN 'journal_lines' THEN jsonb_build_object('journal',jsonb_build_object('id',to_jsonb(NEW)->'journal_entry_id'))
   WHEN 'journal_entries' THEN CASE WHEN to_jsonb(NEW)->>'ref_type'='grn_receipt' THEN jsonb_build_object('grn',jsonb_build_object('id',to_jsonb(NEW)->'ref_id')) END END;
 END IF;
 SELECT EXISTS(SELECT 1 FROM grn_completion_proofs WHERE source @> selector OR source @> destination) INTO protected;
 IF NOT protected THEN IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF; END IF;
 IF TG_OP='UPDATE' THEN
  IF TG_TABLE_NAME='journal_entries' AND (to_jsonb(NEW)-ARRAY['voided_at','voided_by','void_reason','updated_at']) IS NOT DISTINCT FROM(to_jsonb(OLD)-ARRAY['voided_at','voided_by','void_reason','updated_at']) THEN RETURN NEW; END IF;
  IF TG_TABLE_NAME='batches' THEN allowed=ARRAY['qty_on_hand','updated_at'];
  ELSIF TG_TABLE_NAME='ingredients' THEN IF (NEW.company_id,NEW.base_unit) IS NOT DISTINCT FROM(OLD.company_id,OLD.base_unit) THEN RETURN NEW; END IF;
  ELSIF TG_TABLE_NAME='suppliers' THEN IF NEW.company_id IS NOT DISTINCT FROM OLD.company_id THEN RETURN NEW; END IF;
  ELSIF TG_TABLE_NAME='purchase_orders' THEN allowed=ARRAY['status','updated_at']; END IF;
  IF (to_jsonb(NEW)-allowed) IS NOT DISTINCT FROM(to_jsonb(OLD)-allowed) THEN RETURN NEW; END IF;
 END IF;
 IF TG_TABLE_NAME='grns' THEN
  IF TG_OP='DELETE' THEN RAISE EXCEPTION 'posted GRNs are immutable and cannot be deleted' USING ERRCODE='23514'; END IF;
  RAISE EXCEPTION 'posted GRN financial/provenance fields are immutable' USING ERRCODE='23514';
 ELSIF TG_TABLE_NAME='grn_lines' THEN RAISE EXCEPTION 'posted GRN lines are immutable after receipt posting' USING ERRCODE='23514'; END IF;
 RAISE EXCEPTION 'Completed GRN source is immutable; later ordinary inventory movements remain allowed' USING ERRCODE='23514';
END $$;
"""


def upgrade():
    # Old creates acquire PO before GRN; drain them before the insertion barrier.
    op.execute("LOCK TABLE purchase_orders IN SHARE MODE")
    op.execute("LOCK TABLE grns IN ACCESS EXCLUSIVE MODE")
    op.execute("LOCK TABLE suppliers,ingredients,batches,grn_lines,purchase_order_lines,stock_movements,journal_entries,journal_lines IN SHARE MODE")
    op.execute(r"""
CREATE TABLE grn_action_decisions (
 id uuid PRIMARY KEY,action_key varchar(160) NOT NULL CONSTRAINT uq_grn_action_key UNIQUE,
 company_id uuid NOT NULL,branch_id uuid NOT NULL,actor_user_id uuid,terminal_id uuid,
 terminal_known boolean NOT NULL,request_hash varchar(64) NOT NULL CHECK(request_hash~'^[a-f0-9]{64}$'),
 outcome varchar(9) NOT NULL CHECK(outcome IN('accepted','cancelled')),
 accepted_grn_id uuid CONSTRAINT uq_grn_action_receipt UNIQUE REFERENCES grns(id) ON DELETE RESTRICT,
 original_wire_json text,cancellation_reason varchar(500),recovery_session_id uuid,decided_at timestamptz NOT NULL,
 CHECK(length(action_key) BETWEEN 1 AND 160 AND action_key=btrim(action_key)),
 CHECK(terminal_known OR terminal_id IS NULL),
 CONSTRAINT ck_grn_decision_shape CHECK((outcome='accepted' AND accepted_grn_id IS NOT NULL AND accepted_grn_id=id AND original_wire_json IS NULL AND cancellation_reason IS NULL AND recovery_session_id IS NULL) OR
 (outcome='cancelled' AND accepted_grn_id IS NULL AND actor_user_id IS NOT NULL AND terminal_known AND original_wire_json IS NOT NULL AND recovery_session_id IS NOT NULL AND cancellation_reason IS NOT NULL AND length(cancellation_reason) BETWEEN 3 AND 500 AND cancellation_reason=btrim(cancellation_reason,E' \t\n\r\f\v')))
);
CREATE INDEX ix_grn_action_actor ON grn_action_decisions(company_id,actor_user_id);
CREATE TABLE grn_completion_proofs (
 id uuid PRIMARY KEY REFERENCES grn_action_decisions(id) ON DELETE RESTRICT,
 profile varchar(20) NOT NULL CHECK(profile IN('legacy','wire_v1')),original_wire_json text,
 terminal_id uuid,terminal_known boolean NOT NULL,source jsonb NOT NULL,response jsonb NOT NULL,
 CONSTRAINT ck_grn_proof_profile CHECK((profile='legacy' AND original_wire_json IS NULL) OR (profile='wire_v1' AND original_wire_json IS NOT NULL AND terminal_known)),
 CHECK(terminal_known OR terminal_id IS NULL)
);
CREATE TABLE grn_recovery_sessions (
 id uuid PRIMARY KEY,company_id uuid NOT NULL,user_id uuid NOT NULL,branch_id uuid NOT NULL,terminal_id uuid,
 action_key_hash varchar(64) NOT NULL CHECK(action_key_hash~'^[a-f0-9]{64}$'),request_hash varchar(64) NOT NULL CHECK(request_hash~'^[a-f0-9]{64}$'),token_hash varchar(64) NOT NULL CONSTRAINT uq_grn_recovery_token UNIQUE CHECK(token_hash~'^[a-f0-9]{64}$'),auth_version integer NOT NULL CHECK(auth_version>=0),issued_at timestamptz NOT NULL,expires_at timestamptz NOT NULL,revoked_at timestamptz,revocation_reason varchar(20),
 CHECK(expires_at>issued_at AND expires_at<=issued_at+interval '300 seconds'),CHECK((revoked_at IS NULL AND revocation_reason IS NULL) OR(revoked_at IS NOT NULL AND revoked_at>=issued_at AND revocation_reason IN('locked','replaced')))
);
CREATE INDEX ix_grn_recovery_exact ON grn_recovery_sessions(company_id,user_id,action_key_hash,request_hash);
CREATE INDEX ix_grn_recovery_expiry ON grn_recovery_sessions(expires_at);
""")
    op.execute(FUNCTIONS)
    op.execute(GUARDS)
    # No actor reapproval or reconstructed wire body. Every keyed receipt is fenced.
    op.execute(r"""
INSERT INTO grn_action_decisions(id,action_key,company_id,branch_id,actor_user_id,terminal_id,terminal_known,request_hash,outcome,accepted_grn_id,decided_at)
 SELECT g.id,g.idempotency_key,p.company_id,p.branch_id,g.received_by,k.terminal_id,k.terminal_id IS NOT NULL,g.request_hash,'accepted',g.id,g.created_at
 FROM grns g JOIN purchase_orders p ON p.id=g.purchase_order_id LEFT JOIN idempotency_keys k ON k.key=g.idempotency_key AND k.request_hash=g.request_hash AND k.user_id IS NOT DISTINCT FROM g.received_by WHERE g.idempotency_key IS NOT NULL;
CREATE TRIGGER grn_decision_validate BEFORE INSERT ON grn_action_decisions FOR EACH ROW EXECUTE FUNCTION dcompany_grn_decision_guard();
CREATE INDEX ix_grn_proof_source ON grn_completion_proofs USING gin(source jsonb_path_ops);
CREATE TRIGGER grn_proof_validate BEFORE INSERT ON grn_completion_proofs FOR EACH ROW EXECUTE FUNCTION dcompany_grn_proof_guard();
INSERT INTO grn_completion_proofs(id,profile,terminal_id,terminal_known,source,response)
 SELECT d.id,'legacy',d.terminal_id,d.terminal_known,s.src,dcompany_grn_response(s.src) FROM grn_action_decisions d CROSS JOIN LATERAL(SELECT dcompany_grn_source(d.accepted_grn_id) src) s;
CREATE TRIGGER grn_accept_decision AFTER INSERT ON grns FOR EACH ROW EXECUTE FUNCTION dcompany_grn_accept();
CREATE CONSTRAINT TRIGGER grn_complete_decision AFTER INSERT ON grn_action_decisions DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION dcompany_grn_complete();
CREATE CONSTRAINT TRIGGER grn_complete_proof AFTER INSERT ON grn_completion_proofs DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION dcompany_grn_complete();
CREATE TRIGGER grn_decision_no_edit BEFORE UPDATE OR DELETE ON grn_action_decisions FOR EACH ROW EXECUTE FUNCTION dcompany_grn_append_only();
CREATE TRIGGER grn_decision_no_truncate BEFORE TRUNCATE ON grn_action_decisions FOR EACH STATEMENT EXECUTE FUNCTION dcompany_grn_append_only();
CREATE TRIGGER grn_proof_no_edit BEFORE UPDATE OR DELETE ON grn_completion_proofs FOR EACH ROW EXECUTE FUNCTION dcompany_grn_append_only();
CREATE TRIGGER grn_proof_no_truncate BEFORE TRUNCATE ON grn_completion_proofs FOR EACH STATEMENT EXECUTE FUNCTION dcompany_grn_append_only();
CREATE TRIGGER grn_session_guard BEFORE INSERT OR UPDATE OR DELETE ON grn_recovery_sessions FOR EACH ROW EXECUTE FUNCTION dcompany_grn_session_guard();
CREATE TRIGGER grn_session_no_truncate BEFORE TRUNCATE ON grn_recovery_sessions FOR EACH STATEMENT EXECUTE FUNCTION dcompany_grn_append_only();
CREATE CONSTRAINT TRIGGER grn_session_complete AFTER INSERT OR UPDATE ON grn_recovery_sessions DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION dcompany_grn_session_complete();
""")

    for table in ("grns","purchase_orders","grn_lines","purchase_order_lines","batches","stock_movements","ingredients","suppliers","journal_entries","journal_lines"):
        op.execute(f"CREATE TRIGGER grn_seal_{table} BEFORE INSERT OR UPDATE OR DELETE ON {table} FOR EACH ROW EXECUTE FUNCTION dcompany_grn_sealed_source()")
        op.execute(f"CREATE TRIGGER grn_no_truncate_{table} BEFORE TRUNCATE ON {table} FOR EACH STATEMENT EXECUTE FUNCTION dcompany_grn_source_no_truncate()")


def downgrade():
    op.execute(r"""
LOCK TABLE grn_action_decisions,grn_completion_proofs,grn_recovery_sessions IN ACCESS EXCLUSIVE MODE;
DO $$ BEGIN IF EXISTS(SELECT 1 FROM grn_action_decisions) OR EXISTS(SELECT 1 FROM grn_completion_proofs) OR EXISTS(SELECT 1 FROM grn_recovery_sessions WHERE revoked_at IS NULL AND expires_at>statement_timestamp()) THEN RAISE EXCEPTION 'GRN command evidence prevents downgrade'; END IF; END $$;
DROP TRIGGER grn_accept_decision ON grns;
DO $$ DECLARE t text; BEGIN FOREACH t IN ARRAY ARRAY['grns','purchase_orders','grn_lines','purchase_order_lines','batches','stock_movements','ingredients','suppliers','journal_entries','journal_lines'] LOOP EXECUTE format('DROP TRIGGER grn_seal_%s ON %I',t,t); EXECUTE format('DROP TRIGGER grn_no_truncate_%s ON %I',t,t); END LOOP; END $$;
DROP TABLE grn_completion_proofs,grn_action_decisions,grn_recovery_sessions;
DROP FUNCTION dcompany_grn_source_no_truncate(),dcompany_grn_sealed_source(),dcompany_grn_session_complete(),dcompany_grn_session_guard(),dcompany_grn_decision_guard(),dcompany_grn_complete(),dcompany_grn_proof_guard(),dcompany_grn_accept(),dcompany_grn_append_only(),dcompany_grn_response(jsonb),dcompany_grn_source(uuid),dcompany_grn_valid_wire(text),dcompany_grn_unique_json(json),dcompany_grn_wire_hash(text,text);
""")
