# ruff: noqa: E501
"""Mailer — sends HTML emails via SMTP.

Reads SMTP settings from env at instantiation time so that secrets stay in
the deploy environment, never in source. Returns False (does NOT raise)
when credentials are missing, so callers can decide whether to log + skip
or to fail loudly.

To enable: set these env vars on the droplet's /opt/d-company-erp/.env:
    SMTP_HOST       (e.g. smtp.resend.com / smtp.gmail.com / smtp.mailgun.org)
    SMTP_PORT       (587 for TLS, 465 for SSL)
    SMTP_USER       (your SMTP username — for Resend this is literally 'resend')
    SMTP_PASSWORD   (your SMTP password / API key)
    FROM_EMAIL      (sender — e.g. reports@dcompany.in)
    FROM_NAME       (optional display name, default 'D Company ERP')
"""

from __future__ import annotations

import os
import smtplib
import ssl
from email.message import EmailMessage
from html import escape
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from app.services.alerts import BusinessAlert
    from app.services.reports import PnLReport


class Mailer:
    def __init__(self) -> None:
        self.host = os.getenv("SMTP_HOST", "").strip()
        self.port = int(os.getenv("SMTP_PORT", "587"))
        self.user = os.getenv("SMTP_USER", "").strip()
        self.password = os.getenv("SMTP_PASSWORD", "").strip()
        self.from_email = os.getenv("FROM_EMAIL", "").strip()
        self.from_name = os.getenv("FROM_NAME", "D Company ERP").strip()

    @property
    def configured(self) -> bool:
        return all([self.host, self.user, self.password, self.from_email])

    def send(self, to: str | list[str], subject: str, html: str, text: str | None = None) -> bool:
        if not self.configured:
            return False
        recipients = [to] if isinstance(to, str) else list(to)
        if not recipients:
            return False

        msg = EmailMessage()
        msg["From"] = f"{self.from_name} <{self.from_email}>"
        msg["To"] = ", ".join(recipients)
        msg["Subject"] = subject
        msg.set_content(text or _html_to_text(html))
        msg.add_alternative(html, subtype="html")

        ctx = ssl.create_default_context()
        if self.port == 465:
            with smtplib.SMTP_SSL(self.host, self.port, context=ctx, timeout=20) as s:
                s.login(self.user, self.password)
                s.send_message(msg)
        else:
            with smtplib.SMTP(self.host, self.port, timeout=20) as s:
                s.ehlo()
                s.starttls(context=ctx)
                s.ehlo()
                s.login(self.user, self.password)
                s.send_message(msg)
        return True


def _html_to_text(html: str) -> str:
    # Crude HTML→text fallback. Sufficient for the daily P&L which is mostly
    # tables. Production-grade callers can pass an explicit text version.
    import re
    text = re.sub(r"<br\s*/?>", "\n", html, flags=re.I)
    text = re.sub(r"</p>", "\n\n", text, flags=re.I)
    text = re.sub(r"<[^>]+>", "", text)
    return text.strip()


def _rs(minor: int) -> str:
    return f"₹{minor / 100:,.2f}"


def _metric_card(label: str, value: str, color: str) -> str:
    return f"""\
    <div style="flex:1;min-width:140px;background:#f9fafb;border-radius:10px;padding:12px;">
      <div style="font-size:11px;color:#6b7280;text-transform:uppercase;">{escape(label)}</div>
      <div style="font-size:22px;font-weight:700;color:{color};">{escape(value)}</div>
    </div>"""


def _money_row(label: str, value_minor: int) -> str:
    return (
        f'<tr><td style="padding:6px 0;color:#6b7280;">{escape(label)}</td>'
        f'<td align="right" style="padding:6px 0;font-family:monospace;">'
        f"{_rs(value_minor)}</td></tr>"
    )


def _alert_block(alerts: list[BusinessAlert]) -> str:
    if not alerts:
        return ""
    color_by_severity = {
        "critical": ("#fef2f2", "#ef4444"),
        "warning": ("#fffbeb", "#f59e0b"),
        "info": ("#eff6ff", "#3b82f6"),
    }
    rows = []
    for alert in alerts:
        background, color = color_by_severity[alert.severity]
        rows.append(
            f"""\
    <div style="background:{background};border-left:4px solid {color};border-radius:8px;padding:10px 12px;margin:8px 0;">
      <div style="font-weight:700;color:#111827;">{escape(alert.title)}</div>
      <div style="font-size:13px;color:#4b5563;line-height:1.45;">{escape(alert.detail)}</div>
    </div>"""
        )
    return "\n".join(rows)


def render_pnl_email(
    *,
    company_name: str,
    period_name: str,
    report: PnLReport,
    alerts: list[BusinessAlert],
    public_url: str,
) -> tuple[str, str]:
    """Return (subject, html_body) for an automated P&L email."""
    profit_color = "#10b981" if report.net_profit_minor >= 0 else "#ef4444"
    period_label = f"{report.label} ({report.period_start.isoformat()} to {report.period_end.isoformat()})"
    subject = (
        f"D Company - {period_name} P&L {report.label} - "
        f"Net {_rs(report.net_profit_minor)}"
    )
    expenses_rows = "\n".join(
        _money_row(line.category, line.amount_minor) for line in report.expenses[:8]
    )
    if not expenses_rows:
        expenses_rows = (
            '<tr><td style="padding:6px 0;color:#6b7280;">No expenses recorded</td>'
            '<td align="right" style="padding:6px 0;font-family:monospace;">₹0.00</td></tr>'
        )
    html = f"""\
<!doctype html>
<html><body style="margin:0;padding:24px;background:#f6f7fb;font-family:-apple-system,BlinkMacSystemFont,Segoe UI,sans-serif;color:#1a1a1a;">
<div style="max-width:640px;margin:0 auto;background:#fff;border-radius:16px;padding:24px;border:1px solid #e5e7eb;">
  <h1 style="margin:0 0 4px;font-size:22px;">{escape(company_name)}</h1>
  <p style="margin:0 0 24px;color:#6b7280;">{escape(period_name)} P&amp;L &middot; {escape(period_label)}</p>

  <div style="display:flex;gap:12px;margin-bottom:20px;flex-wrap:wrap;">
{_metric_card("Revenue", _rs(report.gross_revenue_minor), "#10b981")}
{_metric_card("Expenses", _rs(report.expense_total_minor), "#ef4444")}
{_metric_card("Net profit", _rs(report.net_profit_minor), profit_color)}
  </div>

  <table style="width:100%;font-size:14px;border-collapse:collapse;">
    <tr><td colspan="2" style="padding:8px 0;border-bottom:1px solid #e5e7eb;font-weight:600;">Revenue breakdown</td></tr>
    {_money_row("Food, drinks, desserts", report.revenue.food_minor)}
    {_money_row("Gaming", report.revenue.gaming_minor)}
    {_money_row("Hookah", report.revenue.hookah_minor)}
    {_money_row("Events", report.revenue.event_tickets_minor)}
    {_money_row("Delivery aggregators", report.revenue.delivery_aggregator_minor)}
    {_money_row("Other", report.revenue.other_minor)}
    <tr><td style="padding:6px 0;color:#6b7280;border-top:1px solid #e5e7eb;">Orders</td><td align="right" style="padding:6px 0;font-family:monospace;border-top:1px solid #e5e7eb;">{report.orders_count}</td></tr>
    <tr><td style="padding:6px 0;color:#6b7280;">Average order</td><td align="right" style="padding:6px 0;font-family:monospace;">{_rs(report.avg_ticket_minor)}</td></tr>
    <tr><td style="padding:6px 0;color:#6b7280;">GST collected</td><td align="right" style="padding:6px 0;font-family:monospace;">{_rs(report.tax_collected.total_minor)}</td></tr>
  </table>

  <table style="width:100%;font-size:14px;border-collapse:collapse;margin-top:20px;">
    <tr><td colspan="2" style="padding:8px 0;border-bottom:1px solid #e5e7eb;font-weight:600;">Top expenses</td></tr>
    {expenses_rows}
  </table>

  <div style="margin-top:20px;">
    <div style="font-weight:700;margin-bottom:8px;">Automated alerts</div>
{_alert_block(alerts)}
  </div>

  <p style="margin:24px 0 0;">
    <a href="{escape(public_url)}" style="background:#1a1d2e;color:#fff;text-decoration:none;padding:10px 18px;border-radius:8px;display:inline-block;">Open full Reports</a>
  </p>
  <p style="margin:20px 0 0;color:#9ca3af;font-size:12px;">
    Generated automatically by D Company ERP. Alerts are rule-based operational checks, not accounting advice.
  </p>
</div>
</body></html>
"""
    return subject, html


def render_daily_pnl_email(
    *,
    company_name: str,
    period_label: str,
    revenue_total_minor: int,
    expenses_total_minor: int,
    net_profit_minor: int,
    orders_count: int,
    revenue_food_minor: int,
    revenue_gaming_minor: int,
    revenue_hookah_minor: int,
    revenue_events_minor: int,
    public_url: str,
) -> tuple[str, str]:
    """Return (subject, html_body) for the daily P&L email."""
    def rs(minor: int) -> str:
        return f"₹{minor / 100:,.2f}"

    profit_color = "#10b981" if net_profit_minor >= 0 else "#ef4444"
    subject = f"D Company — Daily P&L {period_label} · Net {rs(net_profit_minor)}"
    html = f"""\
<!doctype html>
<html><body style="margin:0;padding:24px;background:#f6f7fb;font-family:-apple-system,BlinkMacSystemFont,Segoe UI,sans-serif;color:#1a1a1a;">
<div style="max-width:560px;margin:0 auto;background:#fff;border-radius:16px;padding:24px;border:1px solid #e5e7eb;">
  <h1 style="margin:0 0 4px;font-size:22px;">{company_name}</h1>
  <p style="margin:0 0 24px;color:#6b7280;">Daily P&amp;L &middot; {period_label}</p>

  <div style="display:flex;gap:12px;margin-bottom:20px;flex-wrap:wrap;">
    <div style="flex:1;min-width:140px;background:#f9fafb;border-radius:10px;padding:12px;">
      <div style="font-size:11px;color:#6b7280;text-transform:uppercase;">Revenue</div>
      <div style="font-size:22px;font-weight:700;color:#10b981;">{rs(revenue_total_minor)}</div>
    </div>
    <div style="flex:1;min-width:140px;background:#f9fafb;border-radius:10px;padding:12px;">
      <div style="font-size:11px;color:#6b7280;text-transform:uppercase;">Expenses</div>
      <div style="font-size:22px;font-weight:700;color:#ef4444;">{rs(expenses_total_minor)}</div>
    </div>
    <div style="flex:1;min-width:140px;background:#f9fafb;border-radius:10px;padding:12px;">
      <div style="font-size:11px;color:#6b7280;text-transform:uppercase;">Net profit</div>
      <div style="font-size:22px;font-weight:700;color:{profit_color};">{rs(net_profit_minor)}</div>
    </div>
  </div>

  <table style="width:100%;font-size:14px;border-collapse:collapse;">
    <tr><td colspan="2" style="padding:8px 0;border-bottom:1px solid #e5e7eb;font-weight:600;">Revenue breakdown</td></tr>
    <tr><td style="padding:6px 0;color:#6b7280;">Food</td><td align="right" style="padding:6px 0;font-family:monospace;">{rs(revenue_food_minor)}</td></tr>
    <tr><td style="padding:6px 0;color:#6b7280;">Gaming</td><td align="right" style="padding:6px 0;font-family:monospace;">{rs(revenue_gaming_minor)}</td></tr>
    <tr><td style="padding:6px 0;color:#6b7280;">Hookah</td><td align="right" style="padding:6px 0;font-family:monospace;">{rs(revenue_hookah_minor)}</td></tr>
    <tr><td style="padding:6px 0;color:#6b7280;">Events</td><td align="right" style="padding:6px 0;font-family:monospace;">{rs(revenue_events_minor)}</td></tr>
    <tr><td style="padding:6px 0;color:#6b7280;border-top:1px solid #e5e7eb;">Orders today</td><td align="right" style="padding:6px 0;font-family:monospace;border-top:1px solid #e5e7eb;">{orders_count}</td></tr>
  </table>

  <p style="margin:24px 0 0;">
    <a href="{public_url}" style="background:#1a1d2e;color:#fff;text-decoration:none;padding:10px 18px;border-radius:8px;display:inline-block;">Open full Reports →</a>
  </p>
  <p style="margin:20px 0 0;color:#9ca3af;font-size:12px;">
    This is your automatic 8 AM IST P&amp;L. Reply STOP to unsubscribe.
  </p>
</div>
</body></html>
"""
    return subject, html
