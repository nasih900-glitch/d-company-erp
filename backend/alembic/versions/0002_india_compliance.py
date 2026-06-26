"""india compliance — GST/HSN/SAC/FSSAI/PT + reference tables

Revision ID: 0002
Revises: 0001
Create Date: 2026-05-20

Adds:
 - GST identity (gstin, pan, registration_type, e_invoicing flag) on companies
 - State code, FSSAI/trade licence, branch code on branches
 - HSN code + price_includes_tax on menu_items
 - SAC code + tax_rate + rate_includes_tax on stations
 - CGST/SGST/IGST/cess split + round_off + tip on orders & order_lines
 - Invoice number columns + invoice_counters table for atomic per-branch
   per-FY sequence
 - E-invoice IRN / QR / ack on orders
 - Customer GSTIN / address / state on orders for B2B and inter-state
 - place_of_supply_state_code on orders
 - delivery_via on orders for Section 9(5) aggregator routing
 - is_reverse_charge on orders
 - E-way bill number on grns
 - Reference tables: in_state_codes, in_gst_rate_slabs, in_hsn_codes,
   in_sac_codes, in_kerala_pt_slabs

See docs/INDIA_TAX_COMPLIANCE.md for the rules these columns implement.
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision = "0002"
down_revision = "0001"
branch_labels = None
depends_on = None


def upgrade() -> None:
    # ---------- companies ----------
    op.alter_column("companies", "gstin", existing_type=sa.String(20), type_=sa.String(15))
    op.add_column("companies", sa.Column("pan", sa.String(10)))
    op.add_column(
        "companies",
        sa.Column(
            "gst_registration_type",
            sa.String(20),
            nullable=False,
            server_default="regular",
        ),
    )
    op.add_column(
        "companies",
        sa.Column("is_composition", sa.Boolean, nullable=False, server_default=sa.false()),
    )
    op.add_column(
        "companies",
        sa.Column(
            "e_invoicing_enabled", sa.Boolean, nullable=False, server_default=sa.false()
        ),
    )
    op.add_column(
        "companies",
        sa.Column("fiscal_year_start_month", sa.Integer, nullable=False, server_default="4"),
    )

    # ---------- branches ----------
    op.add_column("branches", sa.Column("code", sa.String(10)))
    op.add_column("branches", sa.Column("state_code", sa.String(2)))
    op.add_column("branches", sa.Column("fssai_license_no", sa.String(14)))
    op.add_column("branches", sa.Column("trade_license_no", sa.String(50)))
    op.add_column("branches", sa.Column("branch_gstin", sa.String(15)))

    # ---------- menu_items ----------
    op.add_column("menu_items", sa.Column("hsn_code", sa.String(8)))
    op.add_column(
        "menu_items",
        sa.Column("price_includes_tax", sa.Boolean, nullable=False, server_default=sa.true()),
    )

    # ---------- stations (gaming) ----------
    op.add_column(
        "stations",
        sa.Column("sac_code", sa.String(8), nullable=False, server_default="999692"),
    )
    op.add_column(
        "stations",
        sa.Column("tax_rate", sa.Numeric(5, 4), nullable=False, server_default="0.18"),
    )
    op.add_column(
        "stations",
        sa.Column("rate_includes_tax", sa.Boolean, nullable=False, server_default=sa.true()),
    )

    # ---------- orders ----------
    op.add_column("orders", sa.Column("delivery_via", sa.String(30)))
    op.add_column("orders", sa.Column("cgst_minor", sa.BigInteger, nullable=False, server_default="0"))
    op.add_column("orders", sa.Column("sgst_minor", sa.BigInteger, nullable=False, server_default="0"))
    op.add_column("orders", sa.Column("igst_minor", sa.BigInteger, nullable=False, server_default="0"))
    op.add_column("orders", sa.Column("cess_minor", sa.BigInteger, nullable=False, server_default="0"))
    op.add_column(
        "orders", sa.Column("round_off_minor", sa.BigInteger, nullable=False, server_default="0")
    )
    op.add_column("orders", sa.Column("tip_minor", sa.BigInteger, nullable=False, server_default="0"))
    op.add_column("orders", sa.Column("invoice_no", sa.String(20)))
    op.create_unique_constraint("uq_orders_invoice_no", "orders", ["invoice_no"])
    op.create_index("ix_orders_invoice_no", "orders", ["invoice_no"])
    op.add_column("orders", sa.Column("fiscal_year", sa.String(7)))
    op.add_column("orders", sa.Column("customer_gstin", sa.String(15)))
    op.add_column("orders", sa.Column("customer_address", sa.String(500)))
    op.add_column("orders", sa.Column("customer_state_code", sa.String(2)))
    op.add_column("orders", sa.Column("place_of_supply_state_code", sa.String(2)))
    op.add_column(
        "orders",
        sa.Column("is_reverse_charge", sa.Boolean, nullable=False, server_default=sa.false()),
    )
    op.add_column("orders", sa.Column("irn", sa.String(64)))
    op.add_column("orders", sa.Column("irn_ack_no", sa.String(64)))
    op.add_column("orders", sa.Column("irn_acknowledged_at", sa.DateTime(timezone=True)))
    op.add_column("orders", sa.Column("e_invoice_qr", sa.String(2048)))

    # ---------- order_lines ----------
    op.add_column("order_lines", sa.Column("hsn_or_sac", sa.String(8)))
    op.add_column(
        "order_lines",
        sa.Column("tax_rate", sa.Numeric(5, 4), nullable=False, server_default="0"),
    )
    op.add_column(
        "order_lines",
        sa.Column("taxable_value_minor", sa.BigInteger, nullable=False, server_default="0"),
    )
    op.add_column(
        "order_lines",
        sa.Column("cgst_minor", sa.BigInteger, nullable=False, server_default="0"),
    )
    op.add_column(
        "order_lines",
        sa.Column("sgst_minor", sa.BigInteger, nullable=False, server_default="0"),
    )
    op.add_column(
        "order_lines",
        sa.Column("igst_minor", sa.BigInteger, nullable=False, server_default="0"),
    )
    op.add_column(
        "order_lines",
        sa.Column("cess_minor", sa.BigInteger, nullable=False, server_default="0"),
    )

    # ---------- grns ----------
    op.add_column("grns", sa.Column("eway_bill_no", sa.String(20)))

    # =================================================================
    # Reference tables — India only
    # =================================================================
    op.create_table(
        "in_state_codes",
        sa.Column("code", sa.String(2), primary_key=True),
        sa.Column("name", sa.String(100), nullable=False),
        sa.Column(
            "is_union_territory", sa.Boolean, nullable=False, server_default=sa.false()
        ),
        sa.Column(
            "is_special_category", sa.Boolean, nullable=False, server_default=sa.false()
        ),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )

    op.create_table(
        "in_gst_rate_slabs",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("rate", sa.Numeric(6, 4), nullable=False, unique=True),
        sa.Column("label", sa.String(20), nullable=False),
        sa.Column("description", sa.String(500)),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )

    op.create_table(
        "in_hsn_codes",
        sa.Column("code", sa.String(8), primary_key=True),
        sa.Column("description", sa.String(500), nullable=False),
        sa.Column("default_gst_rate", sa.Numeric(6, 4), nullable=False),
        sa.Column("chapter", sa.String(2), nullable=False),
        sa.Column("is_active", sa.Boolean, nullable=False, server_default=sa.true()),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )

    op.create_table(
        "in_sac_codes",
        sa.Column("code", sa.String(8), primary_key=True),
        sa.Column("description", sa.String(500), nullable=False),
        sa.Column("default_gst_rate", sa.Numeric(6, 4), nullable=False),
        sa.Column("is_active", sa.Boolean, nullable=False, server_default=sa.true()),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )

    op.create_table(
        "in_invoice_counters",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "branch_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("branches.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("fiscal_year", sa.String(7), nullable=False),
        sa.Column("series", sa.String(20), nullable=False, server_default="invoice"),
        sa.Column("last_seq", sa.BigInteger, nullable=False, server_default="0"),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.UniqueConstraint(
            "branch_id", "fiscal_year", "series", name="uq_inv_counter_branch_fy_series"
        ),
    )
    op.create_index("ix_inv_counters_branch", "in_invoice_counters", ["branch_id"])

    op.create_table(
        "in_kerala_pt_slabs",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("min_half_year_salary", sa.Integer, nullable=False),
        sa.Column("max_half_year_salary", sa.Integer),
        sa.Column("half_year_pt", sa.Integer, nullable=False),
        sa.Column("effective_from", sa.String(10), nullable=False),
        sa.Column("effective_to", sa.String(10)),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )


def downgrade() -> None:
    op.drop_table("in_kerala_pt_slabs")
    op.drop_index("ix_inv_counters_branch", table_name="in_invoice_counters")
    op.drop_table("in_invoice_counters")
    op.drop_table("in_sac_codes")
    op.drop_table("in_hsn_codes")
    op.drop_table("in_gst_rate_slabs")
    op.drop_table("in_state_codes")

    op.drop_column("grns", "eway_bill_no")

    for col in (
        "cess_minor", "igst_minor", "sgst_minor", "cgst_minor",
        "taxable_value_minor", "tax_rate", "hsn_or_sac",
    ):
        op.drop_column("order_lines", col)

    for col in (
        "e_invoice_qr", "irn_acknowledged_at", "irn_ack_no", "irn",
        "is_reverse_charge", "place_of_supply_state_code",
        "customer_state_code", "customer_address", "customer_gstin",
        "fiscal_year",
    ):
        op.drop_column("orders", col)
    op.drop_index("ix_orders_invoice_no", table_name="orders")
    op.drop_constraint("uq_orders_invoice_no", "orders", type_="unique")
    op.drop_column("orders", "invoice_no")
    for col in (
        "tip_minor", "round_off_minor", "cess_minor", "igst_minor",
        "sgst_minor", "cgst_minor", "delivery_via",
    ):
        op.drop_column("orders", col)

    for col in ("rate_includes_tax", "tax_rate", "sac_code"):
        op.drop_column("stations", col)

    op.drop_column("menu_items", "price_includes_tax")
    op.drop_column("menu_items", "hsn_code")

    for col in ("branch_gstin", "trade_license_no", "fssai_license_no", "state_code", "code"):
        op.drop_column("branches", col)

    for col in (
        "fiscal_year_start_month", "e_invoicing_enabled", "is_composition",
        "gst_registration_type", "pan",
    ):
        op.drop_column("companies", col)
    op.alter_column("companies", "gstin", existing_type=sa.String(15), type_=sa.String(20))
