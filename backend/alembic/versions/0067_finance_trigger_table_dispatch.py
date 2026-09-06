"""Dispatch finance insert validation before accessing table-specific fields.

Revision ID: 0067
Revises: 0066
"""

from alembic import op

revision = "0067"
down_revision = "0066"
branch_labels = None
depends_on = None


def upgrade() -> None:
    # A boolean TG_TABLE_NAME condition does not prevent PostgreSQL from
    # resolving NEW.source_kind against the tip_payouts record type. Separate
    # PL/pgSQL branches preserve all source checks without referencing columns
    # that do not exist on the current trigger's table.
    op.execute(
        """
        CREATE OR REPLACE FUNCTION validate_finance_source_insert_scope()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        DECLARE
            source_company uuid;
            branch_company uuid;
            creator_company uuid;
            voider_company uuid;
        BEGIN
            IF TG_TABLE_NAME = 'capital_entries' THEN
                SELECT partner.company_id INTO source_company
                  FROM partners partner WHERE partner.id = NEW.partner_id;
                IF NEW.created_by IS NOT NULL THEN
                    SELECT company_id INTO creator_company
                      FROM users WHERE id = NEW.created_by;
                END IF;
                IF NEW.voided_by IS NOT NULL THEN
                    SELECT company_id INTO voider_company
                      FROM users WHERE id = NEW.voided_by;
                END IF;
                IF source_company IS NULL
                   OR (
                       NEW.created_by IS NOT NULL
                       AND creator_company IS DISTINCT FROM source_company
                   )
                   OR (
                       NEW.voided_by IS NOT NULL
                       AND voider_company IS DISTINCT FROM source_company
                   )
                   OR NEW.type NOT IN ('invest', 'withdraw')
                   OR NEW.amount_minor <= 0
                   OR NEW.settlement_account NOT IN (
                       'cash', 'bank', 'upi', 'historical_funds'
                   ) THEN
                    RAISE EXCEPTION
                        'capital entry has invalid tenant, actor, type, amount, or rail';
                END IF;
                RETURN NEW;
            END IF;

            SELECT company_id INTO branch_company
              FROM branches WHERE id = NEW.branch_id;
            SELECT company_id INTO creator_company
              FROM users WHERE id = NEW.created_by;
            IF NEW.voided_by IS NOT NULL THEN
                SELECT company_id INTO voider_company
                  FROM users WHERE id = NEW.voided_by;
            END IF;
            IF branch_company IS DISTINCT FROM NEW.company_id
               OR creator_company IS DISTINCT FROM NEW.company_id
               OR (
                   NEW.voided_by IS NOT NULL
                   AND voider_company IS DISTINCT FROM NEW.company_id
               )
               OR NEW.method NOT IN ('cash', 'upi', 'card', 'bank')
               OR NEW.amount_minor <= 0 THEN
                RAISE EXCEPTION
                    '% has invalid tenant, actor, rail, or amount', TG_TABLE_NAME;
            END IF;
            IF TG_TABLE_NAME = 'manual_collections' THEN
                IF NEW.source_kind NOT IN ('manual_daily', 'legacy_daily')
                   OR length(trim(NEW.source_ref)) = 0
                   OR length(trim(NEW.idempotency_key)) = 0 THEN
                    RAISE EXCEPTION 'manual collection source identity is invalid';
                END IF;
            ELSIF TG_TABLE_NAME = 'tip_payouts' THEN
                IF length(trim(NEW.note)) < 3
                   OR length(trim(NEW.idempotency_key)) = 0 THEN
                    RAISE EXCEPTION 'tip payout source identity is invalid';
                END IF;
            END IF;
            RETURN NEW;
        END
        $$;
        """
    )


def downgrade() -> None:
    # The replacement uses the same tables, columns and signature as 0050.
    # Keep the corrected guard on rollback rather than restoring a function
    # that rejects every valid tip payout. No schema or source data changed.
    pass
