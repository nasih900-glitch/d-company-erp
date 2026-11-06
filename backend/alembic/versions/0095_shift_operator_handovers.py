"""Record future online operator handovers; leave historical custody unknown.

Revision ID: 0095
Revises: 0094
"""

from alembic import op

revision = "0095"
down_revision = "0094"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("""
        ALTER TABLE shifts ADD COLUMN operator_user_id uuid REFERENCES users(id) ON DELETE RESTRICT;
        ALTER TABLE shifts ADD COLUMN operator_revision integer;
        ALTER TABLE shifts ADD CONSTRAINT ck_shift_operator_state CHECK (
            (operator_user_id IS NULL AND operator_revision IS NULL) OR
            (operator_user_id IS NOT NULL AND operator_revision IS NOT NULL AND
                operator_revision >= 1)
        );
        CREATE TABLE shift_operator_handovers (
            id uuid PRIMARY KEY,
            company_id uuid NOT NULL REFERENCES companies(id) ON DELETE RESTRICT,
            shift_id uuid NOT NULL REFERENCES shifts(id) ON DELETE RESTRICT,
            branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT,
            terminal_id uuid NOT NULL REFERENCES terminals(id) ON DELETE RESTRICT,
            initiated_by_user_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
            previous_operator_user_id uuid REFERENCES users(id) ON DELETE RESTRICT,
            incoming_user_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
            from_revision integer NOT NULL,
            to_revision integer NOT NULL,
            handover_kind varchar(24) NOT NULL DEFAULT 'handover',
            reason varchar(500),
            resolves_handover_id uuid REFERENCES shift_operator_handovers(id) ON DELETE RESTRICT,
            action_key varchar(160) NOT NULL,
            request_hash varchar(64) NOT NULL,
            cash_count_status varchar(16) NOT NULL DEFAULT 'NOT_COUNTED',
            counted_cash_minor bigint,
            created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
            CONSTRAINT uq_shift_operator_revision UNIQUE(company_id, shift_id, to_revision),
            CONSTRAINT uq_shift_operator_action UNIQUE(action_key),
            CONSTRAINT ck_shift_operator_revision CHECK(from_revision >= 0 AND to_revision =
                from_revision+1),
            CONSTRAINT ck_shift_operator_people CHECK(handover_kind='owner_recovery' OR
                initiated_by_user_id <> incoming_user_id),
            CONSTRAINT ck_shift_operator_no_cash_count CHECK(cash_count_status='NOT_COUNTED' AND
                counted_cash_minor IS NULL),
            CONSTRAINT ck_shift_operator_request_hash CHECK(length(request_hash)=64),
            CONSTRAINT ck_shift_operator_recovery CHECK(
                (handover_kind='handover' AND reason IS NULL AND resolves_handover_id IS NULL) OR
                (handover_kind='owner_recovery' AND reason IS NOT NULL
                    AND length(trim(reason)) BETWEEN 12 AND 500 AND resolves_handover_id IS NOT
                        NULL)
            )
        );
        CREATE INDEX ix_shift_operator_handovers_company_id ON shift_operator_handovers(company_id);
        CREATE INDEX ix_shift_operator_handovers_shift_id ON shift_operator_handovers(shift_id);

        CREATE FUNCTION guard_shift_operator_state() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
            IF TG_OP = 'INSERT' THEN
                IF NEW.operator_user_id IS NOT NULL OR NEW.operator_revision IS NOT NULL THEN
                    RAISE EXCEPTION 'new shifts have unrecorded operator responsibility' USING
                        ERRCODE='23514';
                END IF;
            ELSIF (NEW.operator_user_id, NEW.operator_revision) IS DISTINCT FROM
                  (OLD.operator_user_id, OLD.operator_revision) THEN
                IF NOT EXISTS (
                    SELECT 1 FROM shift_operator_handovers h
                    WHERE h.shift_id=NEW.id AND h.company_id=NEW.company_id
                        AND h.incoming_user_id=NEW.operator_user_id
                        AND h.to_revision=NEW.operator_revision
                        AND h.from_revision=coalesce(OLD.operator_revision,0)
                        AND h.previous_operator_user_id IS NOT DISTINCT FROM OLD.operator_user_id
                ) THEN
                    RAISE EXCEPTION 'operator responsibility requires an immutable handover'
                        USING ERRCODE='23514';
                END IF;
            END IF;
            RETURN NEW;
        END $$;
        CREATE TRIGGER trg_shift_operator_state BEFORE INSERT OR UPDATE ON shifts
            FOR EACH ROW EXECUTE FUNCTION guard_shift_operator_state();

        CREATE FUNCTION guard_shift_operator_handover() RETURNS trigger LANGUAGE plpgsql AS $$
        DECLARE s shifts%ROWTYPE;
        BEGIN
            IF TG_OP <> 'INSERT' THEN
                RAISE EXCEPTION 'operator handover history is immutable' USING ERRCODE='23514';
            END IF;
            SELECT * INTO s FROM shifts WHERE id=NEW.shift_id FOR UPDATE;
            IF NOT FOUND OR s.company_id<>NEW.company_id OR s.branch_id<>NEW.branch_id
                OR s.terminal_id<>NEW.terminal_id OR s.status<>'open'
                OR NEW.from_revision<>coalesce(s.operator_revision,0)
                OR NEW.previous_operator_user_id IS DISTINCT FROM s.operator_user_id
                OR (NEW.handover_kind='handover' AND s.operator_user_id IS NOT NULL
                    AND s.operator_user_id<>NEW.initiated_by_user_id)
                OR s.operator_user_id=NEW.incoming_user_id
                OR NOT EXISTS (SELECT 1 FROM users WHERE id=NEW.initiated_by_user_id AND
                    company_id=NEW.company_id)
                OR NOT EXISTS (SELECT 1 FROM users WHERE id=NEW.incoming_user_id AND
                    company_id=NEW.company_id)
                OR NOT EXISTS (SELECT 1 FROM branches b JOIN terminals t ON t.branch_id=b.id
                    WHERE b.id=NEW.branch_id AND b.company_id=NEW.company_id AND b.deleted_at IS
                        NULL
                        AND t.id=NEW.terminal_id AND t.is_active)
            THEN
                RAISE EXCEPTION 'operator handover scope or revision is invalid' USING
                    ERRCODE='23514';
            END IF;
            IF NEW.handover_kind='owner_recovery' AND (
                NOT EXISTS (SELECT 1 FROM user_roles ur JOIN roles r ON r.id=ur.role_id
                    JOIN users u ON u.id=ur.user_id
                    WHERE ur.user_id=NEW.initiated_by_user_id AND r.company_id=NEW.company_id
                        AND u.company_id=NEW.company_id AND u.status='active' AND u.deleted_at
                        IS NULL
                        AND r.code IN ('owner','co_owner','super_owner')
                        AND (ur.branch_id IS NULL OR ur.branch_id=NEW.branch_id))
                OR NOT EXISTS (SELECT 1 FROM shift_operator_handovers h
                    WHERE h.id=NEW.resolves_handover_id AND h.shift_id=NEW.shift_id
                        AND h.company_id=NEW.company_id AND h.to_revision=NEW.from_revision)
            ) THEN
                RAISE EXCEPTION 'Owner recovery must resolve the current recorded handover'
                    USING ERRCODE='23514';
            END IF;
            UPDATE shifts SET operator_user_id=NEW.incoming_user_id,
                operator_revision=NEW.to_revision
                WHERE id=NEW.shift_id;
            RETURN NEW;
        END $$;
        CREATE TRIGGER trg_shift_operator_handover_insert AFTER INSERT ON shift_operator_handovers
            FOR EACH ROW EXECUTE FUNCTION guard_shift_operator_handover();
        CREATE TRIGGER trg_shift_operator_handover_immutable BEFORE UPDATE OR DELETE ON
            shift_operator_handovers
            FOR EACH ROW EXECUTE FUNCTION guard_shift_operator_handover();
        CREATE TRIGGER trg_shift_operator_handover_no_truncate BEFORE TRUNCATE ON
            shift_operator_handovers
            FOR EACH STATEMENT EXECUTE FUNCTION guard_shift_operator_handover();
    """)


def downgrade() -> None:
    op.execute("""
        LOCK TABLE shift_operator_handovers IN ACCESS EXCLUSIVE MODE;
        DO $$ BEGIN
            IF EXISTS(SELECT 1 FROM shift_operator_handovers)
                OR EXISTS(SELECT 1 FROM shifts WHERE operator_user_id IS NOT NULL OR
                    operator_revision IS NOT NULL) THEN
                RAISE EXCEPTION 'cannot discard retained operator responsibility history';
            END IF;
        END $$;
        DROP TRIGGER trg_shift_operator_state ON shifts;
        DROP TABLE shift_operator_handovers;
        DROP FUNCTION guard_shift_operator_handover();
        DROP FUNCTION guard_shift_operator_state();
        ALTER TABLE shifts DROP CONSTRAINT ck_shift_operator_state;
        ALTER TABLE shifts DROP COLUMN operator_revision;
        ALTER TABLE shifts DROP COLUMN operator_user_id;
    """)
