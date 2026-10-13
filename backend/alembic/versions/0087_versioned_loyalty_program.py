"""Add versioned loyalty program and immutable rank/redemption provenance.

Revision ID: 0087
Revises: 0086
"""

from __future__ import annotations

import json

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision = "0087"
down_revision = "0086"
branch_labels = None
depends_on = None


LEGACY_RULES = {
    "gaming_earn_points": 2, "gaming_earn_rupees": 10, "minor_per_point": 10,
    "ranks": [
        {"key": "rookie", "label": "Rookie", "threshold": 0, "bonus_points": 0},
        {"key": "player", "label": "Player", "threshold": 200, "bonus_points": 50},
        {"key": "pro", "label": "Pro", "threshold": 500, "bonus_points": 150},
        {"key": "legend", "label": "Legend", "threshold": 1000, "bonus_points": 300},
    ],
    "rewards": [
        {"key": "extra_controller", "label": "Free extra controller", "description": "One session, any dual-mode station", "points_cost": 50, "value_minor": 3000, "min_rank_key": "rookie", "enabled": True},
        {"key": "snack", "label": "Free snack or drink", "description": "Any single food or drink item", "points_cost": 60, "value_minor": 4000, "min_rank_key": "rookie", "enabled": True},
        {"key": "ps5_30", "label": "Free 30 min · PS5 Single", "description": "Redeem at session start", "points_cost": 150, "value_minor": 8000, "min_rank_key": "rookie", "enabled": True},
        {"key": "ps5_60", "label": "Free 1 hour · PS5 Single", "description": "Redeem at session start", "points_cost": 220, "value_minor": 12000, "min_rank_key": "player", "enabled": True},
        {"key": "squad_night", "label": "Squad Night", "description": "1 hour PS5 Dual + a free extra controller", "points_cost": 350, "value_minor": 18000, "min_rank_key": "pro", "enabled": True},
        {"key": "vr_legend", "label": "Legend pick: 1 hour VR Racing Sim", "description": "Our top-tier experience, for our top-tier players", "points_cost": 500, "value_minor": 25000, "min_rank_key": "legend", "enabled": True},
        {"key": "legend_credit_1000", "label": "Legend's ₹1,000 Credit", "description": "Up to ₹1,000 off any single bill", "points_cost": 1000, "value_minor": 100000, "min_rank_key": "legend", "enabled": True},
    ],
}


def upgrade() -> None:
    op.create_table(
        "loyalty_program_revisions",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("company_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("companies.id", ondelete="RESTRICT"), nullable=False),
        sa.Column("revision", sa.Integer(), nullable=False),
        sa.Column("rules", postgresql.JSONB(astext_type=sa.Text()), nullable=False),
        sa.Column("published_by", postgresql.UUID(as_uuid=True), sa.ForeignKey("users.id", ondelete="RESTRICT")),
        sa.Column("published_at", sa.DateTime(timezone=True), nullable=False),
        sa.CheckConstraint("revision >= 0", name="ck_loyalty_program_revision_nonnegative"),
        sa.CheckConstraint(
            "(revision = 0 AND published_by IS NULL) OR "
            "(revision > 0 AND published_by IS NOT NULL)",
            name="ck_loyalty_program_publisher_shape",
        ),
        sa.UniqueConstraint("company_id", "revision", name="uq_loyalty_program_company_revision"),
    )
    op.create_index("ix_loyalty_program_company_revision", "loyalty_program_revisions", ["company_id", "revision"])
    op.create_table(
        "customer_gaming_rank_awards",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("company_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("companies.id", ondelete="RESTRICT"), nullable=False),
        sa.Column("customer_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("customers.id", ondelete="RESTRICT"), nullable=False),
        sa.Column("rank_key", sa.String(32), nullable=False),
        sa.Column("rank_label_snapshot", sa.String(40), nullable=False),
        sa.Column("threshold_snapshot", sa.Integer(), nullable=False),
        sa.Column("bonus_points", sa.Integer(), nullable=False),
        sa.Column("program_revision", sa.Integer(), nullable=False),
        sa.Column("source", sa.String(24), nullable=False),
        sa.Column("order_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("orders.id", ondelete="RESTRICT")),
        sa.Column("awarded_at", sa.DateTime(timezone=True), nullable=False),
        sa.CheckConstraint("source IN ('legacy_baseline','paid_gaming')", name="ck_customer_gaming_rank_award_source"),
        sa.CheckConstraint("bonus_points >= 0 AND program_revision >= 0", name="ck_customer_gaming_rank_award_nonnegative"),
        sa.CheckConstraint(
            "rank_key IN ('rookie','player','pro','legend')",
            name="ck_customer_gaming_rank_award_key",
        ),
        sa.CheckConstraint(
            "(source = 'legacy_baseline' AND program_revision = 0 "
            " AND bonus_points = 0 AND order_id IS NULL) OR "
            "(source = 'paid_gaming' AND order_id IS NOT NULL)",
            name="ck_customer_gaming_rank_award_shape",
        ),
        sa.UniqueConstraint("company_id", "customer_id", "rank_key", name="uq_customer_gaming_rank_award"),
    )
    op.create_index("ix_customer_gaming_rank_awards_customer", "customer_gaming_rank_awards", ["company_id", "customer_id"])

    for name, kind in (
        ("program_revision", sa.Integer()),
        ("redemption_kind", sa.String(24)),
        ("reward_key", sa.String(64)),
        ("accepted_points_cost", sa.Integer()),
        ("accepted_value_minor", sa.BigInteger()),
        ("rules_snapshot", postgresql.JSONB(astext_type=sa.Text())),
    ):
        op.add_column("points_redemptions", sa.Column(name, kind))
    op.execute(
        "UPDATE points_redemptions SET redemption_kind='legacy_frozen', "
        "accepted_points_cost=points_spent, accepted_value_minor=amount_minor"
    )
    op.alter_column("points_redemptions", "redemption_kind", nullable=False, server_default="legacy_frozen")
    op.alter_column("points_redemptions", "accepted_points_cost", nullable=False)
    op.alter_column("points_redemptions", "accepted_value_minor", nullable=False)
    op.create_check_constraint("ck_points_redemption_program_revision", "points_redemptions", "program_revision IS NULL OR program_revision >= 0")
    op.create_check_constraint("ck_points_redemption_kind", "points_redemptions", "redemption_kind IN ('generic','fixed_reward','legacy_frozen')")
    op.create_check_constraint("ck_points_redemption_accepted", "points_redemptions", "accepted_points_cost > 0 AND accepted_value_minor > 0")
    op.create_check_constraint("ck_points_redemption_reward_key", "points_redemptions", "(redemption_kind = 'fixed_reward') = (reward_key IS NOT NULL)")

    op.add_column("order_loyalty_settlements", sa.Column("program_revision", sa.Integer()))
    op.add_column("order_loyalty_settlements", sa.Column("rules_snapshot", postgresql.JSONB(astext_type=sa.Text())))
    op.create_check_constraint("ck_order_loyalty_program_revision", "order_loyalty_settlements", "program_revision IS NULL OR program_revision >= 0")

    rules_json = json.dumps(LEGACY_RULES, separators=(",", ":"))
    op.execute(
        sa.text(
            """
            INSERT INTO loyalty_program_revisions
              (id, company_id, revision, rules, published_by, published_at)
            SELECT gen_random_uuid(), c.id, 0, CAST(:rules AS jsonb),
                   NULL,
                   now()
              FROM companies c
            """
        ).bindparams(rules=rules_json)
    )
    op.execute(
        """
        INSERT INTO customer_gaming_rank_awards
          (id, company_id, customer_id, rank_key, rank_label_snapshot,
           threshold_snapshot, bonus_points, program_revision, source, order_id, awarded_at)
        SELECT gen_random_uuid(), c.company_id, c.id, r.key, r.label,
               r.threshold, 0, 0, 'legacy_baseline', NULL, now()
          FROM customers c
          CROSS JOIN (VALUES
            ('rookie','Rookie',0), ('player','Player',200),
            ('pro','Pro',500), ('legend','Legend',1000)
          ) AS r(key,label,threshold)
         WHERE c.lifetime_gaming_points_earned >= r.threshold
        """
    )
    op.execute(
        """
        CREATE FUNCTION dcompany_guard_loyalty_program_immutable()
        RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
          RAISE EXCEPTION 'loyalty program and rank award rows are immutable';
        END $$;
        CREATE TRIGGER trg_loyalty_program_revisions_immutable
        BEFORE UPDATE OR DELETE ON loyalty_program_revisions
        FOR EACH ROW EXECUTE FUNCTION dcompany_guard_loyalty_program_immutable();
        CREATE TRIGGER trg_customer_gaming_rank_awards_immutable
        BEFORE UPDATE OR DELETE ON customer_gaming_rank_awards
        FOR EACH ROW EXECUTE FUNCTION dcompany_guard_loyalty_program_immutable();

        CREATE FUNCTION dcompany_guard_loyalty_program_actor_scope()
        RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
          IF (NEW.revision = 0 AND NEW.published_by IS NOT NULL)
             OR (NEW.revision > 0 AND NEW.published_by IS NULL) THEN
            RAISE EXCEPTION 'loyalty program publisher evidence is invalid';
          END IF;
          IF NEW.published_by IS NOT NULL AND NOT EXISTS (
              SELECT 1 FROM users u
               WHERE u.id=NEW.published_by AND u.company_id=NEW.company_id) THEN
            RAISE EXCEPTION 'loyalty program publisher crosses tenant scope';
          END IF;
          RETURN NEW;
        END $$;
        CREATE TRIGGER trg_loyalty_program_revisions_actor_scope
        BEFORE INSERT ON loyalty_program_revisions
        FOR EACH ROW EXECUTE FUNCTION dcompany_guard_loyalty_program_actor_scope();

        CREATE FUNCTION dcompany_guard_rank_award_scope()
        RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
          IF NOT EXISTS (SELECT 1 FROM customers c WHERE c.id=NEW.customer_id AND c.company_id=NEW.company_id)
             OR (NEW.order_id IS NOT NULL AND NOT EXISTS (
                  SELECT 1 FROM orders o WHERE o.id=NEW.order_id AND o.company_id=NEW.company_id))
             OR NOT EXISTS (SELECT 1 FROM loyalty_program_revisions p
                  WHERE p.company_id=NEW.company_id AND p.revision=NEW.program_revision) THEN
            RAISE EXCEPTION 'gaming rank award crosses tenant or program scope';
          END IF;
          RETURN NEW;
        END $$;
        CREATE TRIGGER trg_customer_gaming_rank_awards_scope
        BEFORE INSERT ON customer_gaming_rank_awards
        FOR EACH ROW EXECUTE FUNCTION dcompany_guard_rank_award_scope();

        CREATE FUNCTION dcompany_guard_points_program_provenance()
        RETURNS trigger LANGUAGE plpgsql AS $$
        DECLARE order_company uuid; current_revision integer;
        BEGIN
          SELECT o.company_id INTO order_company FROM orders o WHERE o.id=NEW.order_id;
          SELECT max(p.revision) INTO current_revision FROM loyalty_program_revisions p
            WHERE p.company_id=order_company;
          IF current_revision > 0 AND NEW.program_revision IS NULL THEN
            RAISE EXCEPTION 'custom loyalty program requires revision-aware writer';
          END IF;
          IF NEW.program_revision IS NOT NULL AND NOT EXISTS (
              SELECT 1 FROM loyalty_program_revisions p
               WHERE p.company_id=order_company AND p.revision=NEW.program_revision) THEN
            RAISE EXCEPTION 'points redemption program revision is invalid';
          END IF;
          RETURN NEW;
        END $$;
        CREATE TRIGGER trg_points_redemptions_program_provenance
        BEFORE INSERT ON points_redemptions
        FOR EACH ROW EXECUTE FUNCTION dcompany_guard_points_program_provenance();

        CREATE FUNCTION dcompany_guard_settlement_program_provenance()
        RETURNS trigger LANGUAGE plpgsql AS $$
        DECLARE current_revision integer;
        BEGIN
          SELECT max(p.revision) INTO current_revision FROM loyalty_program_revisions p
            WHERE p.company_id=NEW.company_id;
          IF current_revision > 0 AND NEW.program_revision IS NULL THEN
            RAISE EXCEPTION 'custom loyalty program requires revision-aware settlement writer';
          END IF;
          IF NEW.program_revision IS NOT NULL AND NOT EXISTS (
              SELECT 1 FROM loyalty_program_revisions p
               WHERE p.company_id=NEW.company_id AND p.revision=NEW.program_revision) THEN
            RAISE EXCEPTION 'loyalty settlement program revision is invalid';
          END IF;
          RETURN NEW;
        END $$;
        CREATE TRIGGER trg_order_loyalty_settlements_program_provenance
        BEFORE INSERT ON order_loyalty_settlements
        FOR EACH ROW EXECUTE FUNCTION dcompany_guard_settlement_program_provenance();
        """
    )


def downgrade() -> None:
    irreversible = bool(op.get_bind().execute(sa.text("""
        SELECT EXISTS(SELECT 1 FROM loyalty_program_revisions WHERE revision > 0)
          OR EXISTS(SELECT 1 FROM customer_gaming_rank_awards
                     WHERE source <> 'legacy_baseline' OR bonus_points <> 0 OR order_id IS NOT NULL)
          OR EXISTS(SELECT 1 FROM points_redemptions
                     WHERE program_revision IS NOT NULL OR redemption_kind <> 'legacy_frozen')
          OR EXISTS(SELECT 1 FROM order_loyalty_settlements WHERE program_revision IS NOT NULL)
    """)).scalar_one())
    if irreversible:
        raise RuntimeError("0087 downgrade refused: customized or immutable loyalty facts exist")
    op.execute("DROP TRIGGER trg_order_loyalty_settlements_program_provenance ON order_loyalty_settlements")
    op.execute("DROP FUNCTION dcompany_guard_settlement_program_provenance()")
    op.execute("DROP TRIGGER trg_points_redemptions_program_provenance ON points_redemptions")
    op.execute("DROP FUNCTION dcompany_guard_points_program_provenance()")
    op.execute("DROP TRIGGER trg_customer_gaming_rank_awards_scope ON customer_gaming_rank_awards")
    op.execute("DROP FUNCTION dcompany_guard_rank_award_scope()")
    op.execute("DROP TRIGGER trg_loyalty_program_revisions_actor_scope ON loyalty_program_revisions")
    op.execute("DROP FUNCTION dcompany_guard_loyalty_program_actor_scope()")
    op.execute("DROP TRIGGER trg_customer_gaming_rank_awards_immutable ON customer_gaming_rank_awards")
    op.execute("DROP TRIGGER trg_loyalty_program_revisions_immutable ON loyalty_program_revisions")
    op.execute("DROP FUNCTION dcompany_guard_loyalty_program_immutable()")
    op.drop_constraint("ck_order_loyalty_program_revision", "order_loyalty_settlements", type_="check")
    op.drop_column("order_loyalty_settlements", "rules_snapshot")
    op.drop_column("order_loyalty_settlements", "program_revision")
    for constraint in ("ck_points_redemption_reward_key", "ck_points_redemption_accepted", "ck_points_redemption_kind", "ck_points_redemption_program_revision"):
        op.drop_constraint(constraint, "points_redemptions", type_="check")
    for column in ("rules_snapshot", "accepted_value_minor", "accepted_points_cost", "reward_key", "redemption_kind", "program_revision"):
        op.drop_column("points_redemptions", column)
    op.drop_index("ix_customer_gaming_rank_awards_customer", table_name="customer_gaming_rank_awards")
    op.drop_table("customer_gaming_rank_awards")
    op.drop_index("ix_loyalty_program_company_revision", table_name="loyalty_program_revisions")
    op.drop_table("loyalty_program_revisions")
