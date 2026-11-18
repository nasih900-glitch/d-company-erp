"""Use UTC in the receipt stock guards that compare canonical JSON."""

from alembic import op

revision = "0104"
down_revision = "0103"
branch_labels = None
depends_on = None

_GUARDS = (
    "dcompany_guard_receipt_stock_restoration()",
    "dcompany_check_receipt_stock_complete()",
)


def upgrade() -> None:
    # Receipt snapshots already serialize timestamptz values in UTC. Use the
    # same representation in both the immediate and deferred comparisons.
    for guard in _GUARDS:
        op.execute(f"ALTER FUNCTION {guard} SET timezone TO 'UTC'")


def downgrade() -> None:
    for guard in _GUARDS:
        op.execute(f"ALTER FUNCTION {guard} RESET timezone")
