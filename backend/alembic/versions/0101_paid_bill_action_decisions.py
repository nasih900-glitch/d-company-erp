"""Retain one exact accepted/cancelled decision, including old-process acceptance."""

from alembic import op

revision = "0101"
down_revision = "0100"
branch_labels = None
depends_on = None

FUNCTIONS = r"""
CREATE FUNCTION dcompany_paid_action_valid_command(v jsonb) RETURNS boolean
LANGUAGE plpgsql IMMUTABLE AS $$
BEGIN
 IF jsonb_typeof(v) IS DISTINCT FROM 'object' THEN RETURN false; END IF;
 IF (SELECT count(*) FROM jsonb_object_keys(v))<>10 OR NOT v ?& ARRAY[
  'protocol','order_id','operation','target_total_minor','money_received','reason',
  'settlement_shift_id','refund_mode','source_revision','source_fingerprint'] THEN
  RETURN false; END IF;
 RETURN coalesce(jsonb_typeof(v->'protocol')='number' AND v->>'protocol'='1' AND v->>'order_id'~
  '^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$'
  AND v->>'operation' IN ('decrease','discard')
  AND jsonb_typeof(v->'target_total_minor')='number'
  AND v->>'target_total_minor'~'^[0-9]+$'
  AND (v->>'target_total_minor')::numeric BETWEEN 0 AND 9000000000000000
  AND (v->>'operation'<>'discard' OR (v->>'target_total_minor')::numeric=0)
  AND v->>'money_received' IN ('received','not_received','unknown')
  AND jsonb_typeof(v->'reason')='string' AND length(v->>'reason') BETWEEN 3 AND 500
  AND v->>'reason'=btrim(v->>'reason',E' \t\n\r\f\v')
  AND ((v->'settlement_shift_id'='null'::jsonb AND v->'refund_mode'='null'::jsonb) OR
   (v->>'settlement_shift_id'~
    '^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$'
    AND v->>'refund_mode' IN ('cash','original')))
  AND jsonb_typeof(v->'source_revision')='number' AND v->>'source_revision'~'^[0-9]+$'
  AND v->>'source_fingerprint'~'^[a-f0-9]{64}$',false);
END $$;
CREATE FUNCTION dcompany_paid_action_cancel_response(p_id uuid,p_at timestamptz,p_reason text)
RETURNS jsonb LANGUAGE sql IMMUTABLE AS $$
 SELECT jsonb_build_object('id',p_id,'cancelled_at',
  to_char(p_at AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
  'cancellation_reason',p_reason,'financial_effect',false,'stock_effect',false)
$$;
CREATE FUNCTION dcompany_guard_paid_action_decision() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE i paid_bill_amendment_intents%ROWTYPE; o orders%ROWTYPE;
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Paid bill decisions are append-only'
  USING ERRCODE='23514';
  END IF;
 IF NOT dcompany_paid_action_valid_command(NEW.request_body)
  OR NEW.request_body->>'order_id' IS DISTINCT FROM NEW.order_id::text
  OR NEW.request_path<>'/api/v1/pos/paid-bill-amendments/commit'
  OR NEW.request_hash<>encode(sha256(convert_to(NEW.request_body::text,'UTF8')),'hex') THEN
  RAISE EXCEPTION 'Paid bill decision command or hash is invalid' USING ERRCODE='23514';
  END IF;
 IF NEW.outcome='accepted' THEN
  SELECT * INTO i FROM paid_bill_amendment_intents WHERE id=NEW.accepted_intent_id;
  IF NOT FOUND OR (NEW.id,NEW.company_id,NEW.recorded_by,NEW.order_id,NEW.branch_id,
    NEW.terminal_id,NEW.action_key,NEW.request_path,NEW.request_hash,NEW.request_body,
    NEW.response_body,NEW.decided_at)
   IS DISTINCT FROM (i.id,i.company_id,i.recorded_by,i.order_id,i.branch_id,i.terminal_id,
    i.action_key,i.request_path,i.request_hash,i.request_body,i.response_body,i.recorded_at) THEN
   RAISE EXCEPTION 'Accepted decision must exactly derive from its immutable intent'
    USING ERRCODE='23514';
  END IF;
 ELSE
  -- No business-parent FK or row lock: old V6 may already hold this Order.
  PERFORM 1 FROM users WHERE id=NEW.recorded_by AND company_id=NEW.company_id
   AND status='active' AND deleted_at IS NULL FOR SHARE;
  IF NOT FOUND OR NOT EXISTS(SELECT 1 FROM user_roles ur JOIN roles r ON r.id=ur.role_id
    WHERE ur.user_id=NEW.recorded_by AND r.company_id=NEW.company_id AND r.code='super_owner')
   THEN RAISE EXCEPTION 'Cancellation requires current protected Owner'
    USING ERRCODE='23514';
  END IF;
  SELECT * INTO o FROM orders WHERE id=NEW.order_id AND company_id=NEW.company_id
   AND branch_id=NEW.branch_id AND terminal_id=NEW.terminal_id;
  IF NOT FOUND OR o.source_integrity_revision<>48 OR o.status NOT IN ('paid','refunded')
   OR nullif(o.invoice_no,'') IS NULL OR o.invoice_issued_at IS NULL OR o.closed_at IS NULL THEN
   RAISE EXCEPTION 'Cancellation requires exact original issued receipt scope'
    USING ERRCODE='23514';
  END IF;
  IF NEW.decided_at NOT BETWEEN statement_timestamp()-interval '30 seconds'
     AND statement_timestamp()+interval '30 seconds'
   OR NEW.response_body IS DISTINCT FROM dcompany_paid_action_cancel_response(
    NEW.id,NEW.decided_at,NEW.cancellation_reason) THEN
   RAISE EXCEPTION 'Cancellation response or decision time is invalid' USING ERRCODE='23514';
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE FUNCTION dcompany_complete_paid_action_decision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.outcome='accepted' THEN
  IF NOT EXISTS(SELECT 1 FROM paid_bill_amendment_intents i
    WHERE i.id=NEW.accepted_intent_id AND i.company_id=NEW.company_id
    AND i.recorded_by=NEW.recorded_by AND i.order_id=NEW.order_id
    AND i.branch_id=NEW.branch_id AND i.terminal_id=NEW.terminal_id
    AND i.action_key=NEW.action_key AND i.request_path=NEW.request_path
    AND i.request_hash=NEW.request_hash AND i.request_body=NEW.request_body
    AND i.response_body=NEW.response_body AND i.recorded_at=NEW.decided_at)
   OR NOT EXISTS(SELECT 1 FROM audit_log WHERE company_id=NEW.company_id
    AND entity_type='PaidBillAmendmentIntent' AND entity_id=NEW.accepted_intent_id::text
    AND action='create') THEN
   RAISE EXCEPTION 'Accepted decision requires its exact audited intent' USING ERRCODE='23514';
  END IF;
 ELSE
  IF (SELECT count(*) FROM audit_log WHERE company_id=NEW.company_id
    AND actor_user_id=NEW.recorded_by AND entity_type='PaidBillActionDecision'
    AND entity_id=NEW.id::text AND action='create')<>1 THEN
   RAISE EXCEPTION 'Cancellation requires its exact atomic audit record' USING ERRCODE='23514';
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE FUNCTION dcompany_accept_paid_action_decision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 -- This unconditional unique-index INSERT is the old-process barrier at every isolation level.
 INSERT INTO paid_bill_action_decisions(id,company_id,recorded_by,order_id,branch_id,terminal_id,
  action_key,request_path,request_hash,request_body,outcome,accepted_intent_id,
  cancellation_reason,response_body,decided_at)
 VALUES(NEW.id,NEW.company_id,NEW.recorded_by,NEW.order_id,NEW.branch_id,NEW.terminal_id,
  NEW.action_key,NEW.request_path,NEW.request_hash,NEW.request_body,'accepted',NEW.id,
  NULL,NEW.response_body,NEW.recorded_at);
 RETURN NEW;
END $$;
"""


def upgrade():
    # Backfill cannot acquire Order/Company/other parent-row locks while this table is locked.
    op.execute("LOCK TABLE paid_bill_amendment_intents IN ACCESS EXCLUSIVE MODE")
    op.execute(r"""
CREATE TABLE paid_bill_action_decisions (
 id uuid PRIMARY KEY, company_id uuid NOT NULL, recorded_by uuid NOT NULL,
 order_id uuid NOT NULL, branch_id uuid NOT NULL, terminal_id uuid NOT NULL,
 action_key varchar(160) NOT NULL CHECK(length(action_key) BETWEEN 1 AND 160
  AND action_key=btrim(action_key)),
 request_path varchar(100) NOT NULL, request_hash varchar(64) NOT NULL
  CHECK(request_hash~'^[a-f0-9]{64}$'),
 request_body jsonb NOT NULL, outcome varchar(9) NOT NULL
  CHECK(outcome IN ('accepted','cancelled')),
 accepted_intent_id uuid REFERENCES paid_bill_amendment_intents(id) ON DELETE RESTRICT
  DEFERRABLE INITIALLY DEFERRED,
 cancellation_reason varchar(500), response_body jsonb NOT NULL, decided_at timestamptz NOT NULL,
 CONSTRAINT uq_paid_bill_decision_key UNIQUE(company_id,action_key),
 CONSTRAINT uq_paid_bill_decision_intent UNIQUE(accepted_intent_id),
 CHECK((outcome='accepted' AND accepted_intent_id IS NOT NULL AND id=accepted_intent_id
   AND cancellation_reason IS NULL) OR
  (outcome='cancelled' AND accepted_intent_id IS NULL AND cancellation_reason IS NOT NULL
   AND length(cancellation_reason) BETWEEN 3 AND 500
   AND cancellation_reason=btrim(cancellation_reason,E' \t\n\r\f\v')))
);
CREATE INDEX ix_paid_bill_decision_actor ON paid_bill_action_decisions(company_id,recorded_by);
CREATE INDEX ix_paid_bill_decision_order ON paid_bill_action_decisions(company_id,order_id);
INSERT INTO paid_bill_action_decisions(id,company_id,recorded_by,order_id,branch_id,terminal_id,
 action_key,request_path,request_hash,request_body,outcome,accepted_intent_id,cancellation_reason,
 response_body,decided_at)
 SELECT id,company_id,recorded_by,order_id,branch_id,terminal_id,action_key,request_path,
 request_hash,request_body,'accepted',id,NULL,response_body,recorded_at
 FROM paid_bill_amendment_intents;
""")
    # Trusted historical rows need no new approval; live guards are installed only after backfill.
    op.execute(FUNCTIONS)
    op.execute(r"""
CREATE TRIGGER paid_action_guard BEFORE INSERT OR UPDATE OR DELETE ON paid_bill_action_decisions
 FOR EACH ROW EXECUTE FUNCTION dcompany_guard_paid_action_decision();
CREATE TRIGGER paid_action_no_truncate BEFORE TRUNCATE ON paid_bill_action_decisions
 FOR EACH STATEMENT EXECUTE FUNCTION guard_audit_log_append_only();
CREATE CONSTRAINT TRIGGER paid_action_complete AFTER INSERT ON paid_bill_action_decisions
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
 EXECUTE FUNCTION dcompany_complete_paid_action_decision();
CREATE TRIGGER paid_intent_action_decision AFTER INSERT ON paid_bill_amendment_intents
 FOR EACH ROW EXECUTE FUNCTION dcompany_accept_paid_action_decision();
""")


def downgrade():
    op.execute("""
LOCK TABLE paid_bill_amendment_intents,paid_bill_action_decisions IN ACCESS EXCLUSIVE MODE;
DO $$ BEGIN IF EXISTS(SELECT 1 FROM paid_bill_action_decisions) THEN
 RAISE EXCEPTION 'Paid bill action decisions prevent downgrade; retain every outcome';
END IF; END $$;
DROP TRIGGER paid_intent_action_decision ON paid_bill_amendment_intents;
DROP TABLE paid_bill_action_decisions;
DROP FUNCTION dcompany_accept_paid_action_decision();
DROP FUNCTION dcompany_complete_paid_action_decision();
DROP FUNCTION dcompany_guard_paid_action_decision();
DROP FUNCTION dcompany_paid_action_cancel_response(uuid,timestamptz,text);
DROP FUNCTION dcompany_paid_action_valid_command(jsonb);
""")
