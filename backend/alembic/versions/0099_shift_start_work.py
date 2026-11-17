"""Retain atomic online shift/attendance start receipts without changing legacy opens.


Revision ID: 0099
Revises: 0098
"""

# Retained SQL predicates and canonical digest expressions.
# ruff: noqa: E501

from alembic import op

revision = "0099"
down_revision = "0098"
branch_labels = None
depends_on = None

CANCELLATION_SQL = """
CREATE TABLE shift_start_work_cancellations (
 id uuid PRIMARY KEY, company_id uuid NOT NULL REFERENCES companies(id) ON DELETE RESTRICT,
 actor_user_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
 branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT,
 terminal_id uuid NOT NULL REFERENCES terminals(id) ON DELETE RESTRICT,
 installation_id uuid, action_key varchar(160) NOT NULL UNIQUE,
 request_hash varchar(64) NOT NULL CHECK(request_hash ~ '^[0-9a-f]{64}$'),
 request_path varchar(80) NOT NULL CHECK(request_path='/api/v1/pos/shifts/start-work'),
 request_body jsonb NOT NULL, client_platform varchar(10) NOT NULL CHECK(client_platform IN ('web','android','ios')),
 cancelled_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
 cancelled_at timestamptz NOT NULL, reason varchar(500) NOT NULL CHECK(length(trim(reason)) BETWEEN 12 AND 500),
 response_body jsonb NOT NULL,
 CHECK(action_key ~ '^shift-open:[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$')
);
CREATE INDEX ix_shift_start_work_cancellations_company_id ON shift_start_work_cancellations(company_id);
CREATE FUNCTION dcompany_start_work_lock(k text) RETURNS void LANGUAGE sql AS $$
 SELECT pg_advisory_xact_lock(hashtextextended('start-work:'||k,0));
$$;
CREATE FUNCTION dcompany_guard_start_work_outcome() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM dcompany_start_work_lock(NEW.action_key);
 IF EXISTS(SELECT 1 FROM shift_start_work_cancellations WHERE action_key=NEW.action_key) THEN
  RAISE EXCEPTION 'start-work action was permanently cancelled' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER start_work_outcome BEFORE INSERT ON shift_start_work_receipts
 FOR EACH ROW EXECUTE FUNCTION dcompany_guard_start_work_outcome();
CREATE FUNCTION dcompany_guard_cancelled_start_shift() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.opening_action_id IS NOT NULL THEN
  PERFORM dcompany_start_work_lock(NEW.opening_action_id);
  IF EXISTS(SELECT 1 FROM shift_start_work_cancellations WHERE action_key=NEW.opening_action_id) THEN
   RAISE EXCEPTION 'cancelled start-work identity cannot open a shift' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER cancelled_start_work_shift BEFORE INSERT ON shifts
 FOR EACH ROW EXECUTE FUNCTION dcompany_guard_cancelled_start_shift();
CREATE FUNCTION dcompany_guard_start_work_cancel() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE f bigint; expected jsonb;
BEGIN
 PERFORM dcompany_start_work_lock(NEW.action_key);
 IF EXISTS(SELECT 1 FROM shift_start_work_receipts WHERE action_key=NEW.action_key)
   OR EXISTS(SELECT 1 FROM shifts WHERE opening_action_id=NEW.action_key)
   OR EXISTS(SELECT 1 FROM idempotency_keys WHERE key=NEW.action_key) THEN
  RAISE EXCEPTION 'existing start-work evidence cannot be cancelled' USING ERRCODE='23514'; END IF;
 f:=(NEW.request_body->>'opening_float_minor')::bigint;
 IF (NEW.cancelled_at>=transaction_timestamp() AND NEW.cancelled_at<=clock_timestamp() AND f>=0 AND NEW.request_body=jsonb_build_object('opening_float_minor',f)
   AND NEW.request_hash=encode(sha256(convert_to('POST'||chr(10)||NEW.request_path||chr(10)||chr(10)||NEW.action_key||chr(10)||format('{"opening_float_minor":%s}',f),'UTF8')),'hex')
   AND (NEW.client_platform<>'android' OR NEW.installation_id IS NOT NULL)
   AND EXISTS(SELECT 1 FROM users u WHERE u.id=NEW.actor_user_id AND u.company_id=NEW.company_id)
   AND EXISTS(SELECT 1 FROM terminals t JOIN branches b ON b.id=t.branch_id
      WHERE t.id=NEW.terminal_id AND t.branch_id=NEW.branch_id AND b.company_id=NEW.company_id)
   AND EXISTS(SELECT 1 FROM users u JOIN user_roles ur ON ur.user_id=u.id JOIN roles r ON r.id=ur.role_id
      WHERE u.id=NEW.cancelled_by AND u.company_id=NEW.company_id AND u.status='active' AND u.deleted_at IS NULL
      AND r.company_id=NEW.company_id AND r.code IN ('owner','co_owner','super_owner')
      AND (ur.branch_id IS NULL OR ur.branch_id=NEW.branch_id))) IS DISTINCT FROM TRUE THEN
  RAISE EXCEPTION 'invalid exact-action cancellation evidence' USING ERRCODE='23514'; END IF;
 expected:=jsonb_build_object('id',NEW.id,'action_key',NEW.action_key,'company_id',NEW.company_id,
  'actor_user_id',NEW.actor_user_id,'branch_id',NEW.branch_id,'terminal_id',NEW.terminal_id,
  'installation_id',NEW.installation_id,'opening_float_minor',f,'request_hash',NEW.request_hash,
  'client_platform',NEW.client_platform,'cancelled_by',NEW.cancelled_by,'cancelled_at',NEW.response_body->'cancelled_at',
  'reason',NEW.reason);
 IF NEW.response_body IS DISTINCT FROM expected OR (NEW.response_body->>'cancelled_at')::timestamptz IS DISTINCT FROM NEW.cancelled_at THEN
  RAISE EXCEPTION 'invalid cancellation response' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER valid_start_work_cancel BEFORE INSERT ON shift_start_work_cancellations
 FOR EACH ROW EXECUTE FUNCTION dcompany_guard_start_work_cancel();
CREATE TRIGGER immutable_start_work_cancel BEFORE UPDATE OR DELETE ON shift_start_work_cancellations
 FOR EACH ROW EXECUTE FUNCTION guard_audit_log_append_only();
CREATE TRIGGER immutable_start_work_cancel_truncate BEFORE TRUNCATE ON shift_start_work_cancellations
 FOR EACH STATEMENT EXECUTE FUNCTION guard_audit_log_append_only();
"""


def upgrade():
    op.execute("""
CREATE TABLE shift_start_work_receipts (
 id uuid PRIMARY KEY,
 company_id uuid NOT NULL REFERENCES companies(id) ON DELETE RESTRICT,
 actor_user_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
 branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT,
 terminal_id uuid NOT NULL REFERENCES terminals(id) ON DELETE RESTRICT,
 installation_id uuid,
 shift_id uuid NOT NULL REFERENCES shifts(id) ON DELETE RESTRICT,
 attendance_id uuid NOT NULL REFERENCES attendance(id) ON DELETE RESTRICT,
 action_key varchar(160) NOT NULL UNIQUE,
 request_hash varchar(64) NOT NULL CHECK(request_hash ~ '^[0-9a-f]{64}$'),
 request_path varchar(80) NOT NULL CHECK(request_path='/api/v1/pos/shifts/start-work'),
 request_body jsonb NOT NULL,
 response_body jsonb NOT NULL,
 opening_float_minor bigint NOT NULL CHECK(opening_float_minor>=0),
 clock_in_at timestamptz NOT NULL,
 attendance_created boolean NOT NULL,
 started_at timestamptz NOT NULL,
 CHECK(action_key ~ '^shift-open:[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$')
);
CREATE INDEX ix_shift_start_work_receipts_company_id ON shift_start_work_receipts(company_id);
CREATE FUNCTION dcompany_validate_start_work() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE s shifts; a attendance; expected jsonb;
BEGIN
 SELECT * INTO STRICT s FROM shifts WHERE id=NEW.shift_id;
 SELECT * INTO STRICT a FROM attendance WHERE id=NEW.attendance_id;
 IF (s.company_id=NEW.company_id AND a.company_id=NEW.company_id
   AND s.opened_by=NEW.actor_user_id AND a.user_id=NEW.actor_user_id
   AND s.branch_id=NEW.branch_id AND a.branch_id=NEW.branch_id
   AND s.terminal_id=NEW.terminal_id AND s.status='open' AND a.clock_out_at IS NULL
   AND s.opening_was_offline=false AND s.opening_protocol_revision=1
   AND s.opening_action_id=NEW.action_key
   AND s.opening_request_hash=encode(sha256(convert_to(NEW.request_hash||'|False|server','UTF8')),'hex')
   AND s.opening_float_minor=NEW.opening_float_minor AND s.opened_at=NEW.started_at
   AND a.clock_in_at=NEW.clock_in_at AND NEW.clock_in_at<=NEW.started_at
   AND (NOT NEW.attendance_created OR (a.clock_in_at=NEW.started_at AND a.source='app'))
   AND s.opening_client_installation_id IS NOT DISTINCT FROM NEW.installation_id
   AND (s.opening_client_platform<>'android' OR NEW.installation_id IS NOT NULL)
   AND NEW.request_body=jsonb_build_object('opening_float_minor',NEW.opening_float_minor)
   AND NEW.started_at>=transaction_timestamp() AND NEW.started_at<=clock_timestamp()
   AND NEW.request_hash=encode(sha256(convert_to('POST'||chr(10)||NEW.request_path||chr(10)||chr(10)||NEW.action_key||chr(10)||format('{"opening_float_minor":%s}',NEW.opening_float_minor),'UTF8')),'hex')
   AND EXISTS(SELECT 1 FROM users u JOIN user_roles ur ON ur.user_id=u.id
      JOIN roles r ON r.id=ur.role_id WHERE u.id=NEW.actor_user_id
      AND u.company_id=NEW.company_id AND u.deleted_at IS NULL AND u.status='active'
      AND r.company_id=NEW.company_id AND (ur.branch_id IS NULL OR ur.branch_id=NEW.branch_id))
   AND EXISTS(SELECT 1 FROM terminals t JOIN branches b ON b.id=t.branch_id
      WHERE t.id=NEW.terminal_id AND t.branch_id=NEW.branch_id AND t.is_active
      AND t.purpose='hybrid' AND b.company_id=NEW.company_id AND b.deleted_at IS NULL)) IS DISTINCT FROM TRUE THEN
   RAISE EXCEPTION 'invalid atomic start-work provenance' USING ERRCODE='23514';
 END IF;
 expected:=jsonb_build_object('receipt_id',NEW.id,'company_id',NEW.company_id,
  'actor_user_id',NEW.actor_user_id,'branch_id',NEW.branch_id,'terminal_id',NEW.terminal_id,
  'installation_id',NEW.installation_id,'shift_id',NEW.shift_id,'shift_status_at_start','open',
  'opening_float_minor',NEW.opening_float_minor,'attendance_id',NEW.attendance_id,
  'clock_in_at',NEW.response_body->'clock_in_at','attendance_created',NEW.attendance_created,
  'original_attendance_preserved',NOT NEW.attendance_created,'started_at',NEW.response_body->'started_at');
 IF NEW.response_body IS DISTINCT FROM expected
   OR (NEW.response_body->>'clock_in_at')::timestamptz IS DISTINCT FROM NEW.clock_in_at
   OR (NEW.response_body->>'started_at')::timestamptz IS DISTINCT FROM NEW.started_at THEN
  RAISE EXCEPTION 'invalid atomic start-work response' USING ERRCODE='23514'; END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER valid_start_work_receipt AFTER INSERT ON shift_start_work_receipts
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION dcompany_validate_start_work();
CREATE TRIGGER immutable_start_work_receipt BEFORE UPDATE OR DELETE ON shift_start_work_receipts
 FOR EACH ROW EXECUTE FUNCTION guard_audit_log_append_only();
CREATE TRIGGER immutable_start_work_truncate BEFORE TRUNCATE ON shift_start_work_receipts
 FOR EACH STATEMENT EXECUTE FUNCTION guard_audit_log_append_only();
""")

    op.execute(CANCELLATION_SQL)


def downgrade():
    op.execute("""
LOCK TABLE shift_start_work_receipts, shift_start_work_cancellations IN ACCESS EXCLUSIVE MODE;
DO $$ BEGIN IF EXISTS(SELECT 1 FROM shift_start_work_receipts) OR EXISTS(SELECT 1 FROM shift_start_work_cancellations) THEN
 RAISE EXCEPTION 'retain start-work evidence; downgrade is not permitted'; END IF; END $$;
DROP TRIGGER cancelled_start_work_shift ON shifts;
DROP TABLE shift_start_work_receipts, shift_start_work_cancellations;
DROP FUNCTION dcompany_guard_start_work_outcome();
DROP FUNCTION dcompany_guard_start_work_cancel();
DROP FUNCTION dcompany_guard_cancelled_start_shift();
DROP FUNCTION dcompany_start_work_lock(text);
DROP FUNCTION dcompany_validate_start_work();
""")
