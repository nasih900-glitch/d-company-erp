"""Fixed long-form CSV for the analytics-safe Overview v2 projection.

Only the DTO's operational allowlist is serialized. Monetary values remain paise;
quantity strings retain Decimal precision; unknown measurements are blank. Text
originating in editable data is always apostrophe-prefixed, not just when a known
spreadsheet formula prefix is present. csv.writer owns quoting and newlines.
"""

from __future__ import annotations

import csv
import io
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from app.api.v1.reports.router import ReportsOverviewV2DTO


OVERVIEW_CSV_COLUMNS = (
    "csv_schema_version",
    "schema_version",
    "branch_id",
    "period_start",
    "period_end",
    "timezone",
    "snapshot_at",
    "accounting_basis",
    "section",
    "row_key",
    "label",
    "metric",
    "value",
    "unit",
    "availability",
    "basis",
)


def _editable_text(value: str) -> str:
    return "'" + value if value else ""


def serialize_overview_csv(overview: ReportsOverviewV2DTO) -> bytes:
    """Serialize a fully loaded v2 DTO without queries, aggregation or P&L access."""
    output = io.StringIO(newline="")
    writer = csv.writer(output, lineterminator="\r\n")
    writer.writerow(OVERVIEW_CSV_COLUMNS)
    metadata = (
        "reports.overview.csv.v1",
        overview.schema_version,
        str(overview.branch_id),
        overview.period_start.isoformat(),
        overview.period_end.isoformat(),
        _editable_text(overview.timezone),
        overview.snapshot_at.isoformat(),
        overview.accounting_basis,
    )

    def row(
        section: str,
        key: str,
        label: str,
        metric: str,
        value: str | int | bool | None,
        unit: str,
        basis: str,
        availability: str = "available",
    ) -> None:
        if value is None:
            rendered = ""
        elif isinstance(value, bool):
            rendered = "true" if value else "false"
        else:
            rendered = str(value)
        writer.writerow(
            (*metadata, section, key, label, metric, rendered, unit, availability, basis)
        )

    for metric, unit, basis in (
        ("gross_revenue_minor", "paise", overview.accounting_basis),
        ("orders_count", "count", overview.accounting_basis),
        ("sessions_started_count", "count", overview.sessions_basis),
    ):
        row("overview", "summary", "Overview", metric, getattr(overview, metric), unit, basis)

    sources = overview.revenue_by_source
    for metric in (
        "food_minor",
        "gaming_minor",
        "hookah_minor",
        "event_tickets_minor",
        "memberships_minor",
        "delivery_aggregator_minor",
        "other_minor",
        "manual_collections_minor",
        "adjustments_minor",
        "positive_source_denominator_minor",
        "total_minor",
    ):
        row(
            "revenue_by_source",
            "summary",
            "Revenue by source",
            metric,
            getattr(sources, metric),
            "paise",
            sources.basis,
        )
    row(
        "revenue_by_source",
        "summary",
        "Revenue by source",
        "reconciles_to_gross",
        sources.reconciles_to_gross,
        "boolean",
        sources.basis,
    )
    payments = overview.payments_received
    for metric in (
        "cash_minor",
        "upi_minor",
        "card_minor",
        "bank_minor",
        "qr_minor",
        "wallet_minor",
        "other_minor",
        "total_minor",
    ):
        row(
            "payments_received",
            "summary",
            "Payments received",
            metric,
            getattr(payments, metric),
            "paise",
            payments.basis,
        )
    customers = overview.linked_customers
    for metric in (
        "distinct_linked_customers_count",
        "linked_orders_count",
        "unlinked_orders_count",
    ):
        row(
            "linked_customers",
            "summary",
            "Current order linkage",
            metric,
            getattr(customers, metric),
            "count",
            customers.basis,
        )

    hours = overview.local_hour_profile
    hour_basis = "pos_sales_tip_exclusive_local_hour_profile"
    if hours.availability == "available":
        row(
            "local_hour_profile",
            "summary",
            "POS sales by local hour",
            "timezone",
            _editable_text(hours.timezone),
            "text",
            hours.basis,
        )
        row(
            "local_hour_profile",
            "summary",
            "POS sales by local hour",
            "business_date",
            hours.business_date.isoformat(),
            "date",
            hours.basis,
        )
        for metric, unit in (
            ("day_length_minutes", "minutes"),
            ("total_revenue_minor", "paise"),
            ("total_orders_count", "count"),
            ("reconciles_to_daily_gross", "boolean"),
        ):
            row(
                "local_hour_profile",
                "summary",
                "POS sales by local hour",
                metric,
                getattr(hours, metric),
                unit,
                hours.basis,
            )
        for point in hours.points:
            for metric, unit in (
                ("hour", "local_hour"),
                ("revenue_minor", "paise"),
                ("orders_count", "count"),
            ):
                row(
                    "local_hour_profile",
                    f"{point.hour:02d}",
                    f"{point.hour:02d}:00",
                    metric,
                    getattr(point, metric),
                    unit,
                    hours.basis,
                )
    else:
        row(
            "local_hour_profile",
            "summary",
            "POS sales by local hour",
            "reason",
            hours.reason,
            "text",
            hour_basis,
            "unavailable",
        )
        for metric, unit in (("total_revenue_minor", "paise"), ("total_orders_count", "count")):
            row(
                "local_hour_profile",
                "summary",
                "POS sales by local hour",
                metric,
                None,
                unit,
                hour_basis,
                "unavailable",
            )

    products = overview.top_products
    product_label = "Top 5 products by gross settled POS line sales"
    for metric, value, unit in (
        ("limit", products.limit, "count"),
        ("rows_count", len(products.rows), "count"),
        ("refunds_basis", products.refunds_basis, "text"),
        ("reconciles_to_gross", products.reconciles_to_gross, "boolean"),
    ):
        row("top_products", "summary", product_label, metric, value, unit, products.basis)
    for rank, product in enumerate(products.rows, 1):
        label = _editable_text(product.name)
        for metric, value, unit in (
            ("menu_item_id", str(product.menu_item_id), "uuid"),
            ("product_type", _editable_text(product.product_type), "text"),
            ("quantity_sold", product.quantity_sold, "quantity"),
            ("gross_line_sales_minor", product.gross_line_sales_minor, "paise"),
        ):
            row("top_products", str(rank), label, metric, value, unit, products.basis)

    devices = overview.device_usage
    device_label = "Completed sessions by device type"
    for metric, value, unit in (
        ("grouping", devices.grouping, "text"),
        ("time_basis", devices.time_basis, "text"),
        ("rows_count", len(devices.rows), "count"),
    ):
        row("device_usage", "summary", device_label, metric, value, unit, devices.basis)
    for index, device in enumerate(devices.rows, 1):
        label = _editable_text(device.device_type)
        for metric in ("completed_sessions_count", "unknown_play_sessions_count"):
            row(
                "device_usage",
                str(index),
                label,
                metric,
                getattr(device, metric),
                "count",
                devices.basis,
            )
        row(
            "device_usage",
            str(index),
            label,
            "play_elapsed_ms",
            device.play_elapsed_ms,
            "milliseconds",
            devices.time_basis,
            "unknown" if device.play_elapsed_ms is None else "available",
        )
    return output.getvalue().encode("utf-8")
