"""Dedicated revocable exact-command recovery credentials; no ordinary auth changes."""
from alembic import op

revision = "0102"
down_revision = "0101"
branch_labels = None
depends_on = None


def upgrade():
    op.execute(r"""
CREATE TABLE paid_bill_recovery_sessions (
 id uuid PRIMARY KEY, company_id uuid NOT NULL, user_id uuid NOT NULL,
 branch_id uuid NOT NULL, terminal_id uuid NOT NULL, order_id uuid NOT NULL,
 purpose varchar(40) NOT NULL CHECK(purpose='paid_bill_action_recovery'),
 request_path varchar(100) NOT NULL CHECK(request_path='/api/v1/pos/paid-bill-amendments/commit'),
 action_key_hash varchar(64) NOT NULL CHECK(action_key_hash~'^[a-f0-9]{64}$'),
 command_hash varchar(64) NOT NULL CHECK(command_hash~'^[a-f0-9]{64}$'),
 token_hash varchar(64) NOT NULL CHECK(token_hash~'^[a-f0-9]{64}$'),
 auth_version integer NOT NULL CHECK(auth_version>=0),
 issued_at timestamptz NOT NULL, expires_at timestamptz NOT NULL,
 revoked_at timestamptz, revocation_reason varchar(20),
 CONSTRAINT uq_paid_recovery_token UNIQUE(token_hash),
 CHECK(expires_at>issued_at AND expires_at<=issued_at+interval '300 seconds'),
 CHECK((revoked_at IS NULL AND revocation_reason IS NULL) OR
  (revoked_at IS NOT NULL AND revoked_at>=issued_at AND revocation_reason IN ('locked','replaced')))
);
CREATE INDEX ix_paid_recovery_exact ON paid_bill_recovery_sessions
 (company_id,user_id,action_key_hash,command_hash);
CREATE INDEX ix_paid_recovery_expiry ON paid_bill_recovery_sessions(expires_at);
CREATE FUNCTION dcompany_guard_paid_recovery_session() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN
  IF OLD.expires_at>statement_timestamp() THEN
   RAISE EXCEPTION 'Only expired recovery credentials may be removed' USING ERRCODE='23514';
  END IF;
  RETURN OLD;
 END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.revoked_at IS NOT NULL OR NEW.revocation_reason IS NOT NULL OR
   NEW.issued_at NOT BETWEEN statement_timestamp()-interval '30 seconds'
     AND statement_timestamp()+interval '1 second' THEN
   RAISE EXCEPTION 'Recovery credential issuance is invalid' USING ERRCODE='23514';
  END IF;
 ELSE
  IF NEW IS NOT DISTINCT FROM OLD THEN RETURN NEW; END IF;
  IF (to_jsonb(NEW)-'revoked_at'-'revocation_reason') IS DISTINCT FROM
     (to_jsonb(OLD)-'revoked_at'-'revocation_reason') OR OLD.revoked_at IS NOT NULL OR
    NEW.revoked_at IS NULL OR NEW.revocation_reason IS NULL OR
    NEW.revoked_at>statement_timestamp()+interval '1 second' THEN
   RAISE EXCEPTION 'Recovery scope is immutable and revocation cannot reset or extend'
    USING ERRCODE='23514';
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE FUNCTION dcompany_complete_paid_recovery_session() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE expected_action text;
BEGIN
 expected_action=CASE WHEN TG_OP='INSERT' THEN 'paid_bill_recovery_issued'
  WHEN NEW.revocation_reason='locked' THEN 'paid_bill_recovery_locked'
  ELSE 'paid_bill_recovery_replaced' END;
 IF TG_OP='UPDATE' AND NEW IS NOT DISTINCT FROM OLD THEN RETURN NEW; END IF;
 IF NOT EXISTS(SELECT 1 FROM audit_log a WHERE a.company_id=NEW.company_id
   AND a.actor_user_id=NEW.user_id AND a.entity_type='PaidBillRecoverySession'
   AND a.entity_id=NEW.id::text AND a.action=expected_action) THEN
  RAISE EXCEPTION 'Recovery credential requires its atomic safe audit' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER paid_recovery_guard BEFORE INSERT OR UPDATE OR DELETE ON paid_bill_recovery_sessions
 FOR EACH ROW EXECUTE FUNCTION dcompany_guard_paid_recovery_session();
CREATE TRIGGER paid_recovery_no_truncate BEFORE TRUNCATE ON paid_bill_recovery_sessions
 FOR EACH STATEMENT EXECUTE FUNCTION guard_audit_log_append_only();
CREATE CONSTRAINT TRIGGER paid_recovery_complete AFTER INSERT OR UPDATE
 ON paid_bill_recovery_sessions
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
 EXECUTE FUNCTION dcompany_complete_paid_recovery_session();
""")


def downgrade():
    op.execute("""
LOCK TABLE paid_bill_recovery_sessions IN ACCESS EXCLUSIVE MODE;
DO $$ BEGIN IF EXISTS(SELECT 1 FROM paid_bill_recovery_sessions
 WHERE revoked_at IS NULL AND expires_at>statement_timestamp()) THEN
 RAISE EXCEPTION 'Active recovery credentials prevent downgrade; revoke or wait for expiry';
END IF; END $$;
DROP TABLE paid_bill_recovery_sessions;
DROP FUNCTION dcompany_complete_paid_recovery_session();
DROP FUNCTION dcompany_guard_paid_recovery_session();
""")
