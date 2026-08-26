"""Shared, TLS-enforced SMTP transport for host-side operations scripts."""

from __future__ import annotations

import smtplib
import ssl
from contextlib import contextmanager
from collections.abc import Iterator, Mapping


class SMTPConfigurationError(ValueError):
    """Raised when SMTP settings are missing or unsafe."""


def alert_recipients(env: Mapping[str, str]) -> list[str]:
    """Return deduplicated operational recipients without accepting blanks."""
    raw = env.get("ALERT_RECIPIENT_EMAILS", "")
    values = [part.strip() for part in raw.replace(";", ",").split(",")]
    if not any(values):
        values = [env.get("ACCOUNT_SECURITY_EMAIL", "").strip()]
    return list(dict.fromkeys(value for value in values if value))


@contextmanager
def authenticated_smtp(
    env: Mapping[str, str], *, timeout_seconds: int = 30
) -> Iterator[smtplib.SMTP]:
    """Open an authenticated SMTP session using implicit TLS or STARTTLS.

    Port 465 uses implicit TLS. Every other configured port must successfully
    negotiate STARTTLS before credentials are sent. This mirrors the backend
    mailer and avoids the previous host-script bug that attempted SMTP_SSL on
    port 587.
    """
    host = env.get("SMTP_HOST", "").strip()
    user = env.get("SMTP_USER", "").strip()
    password = env.get("SMTP_PASSWORD", "")
    if not host or not user or not password:
        raise SMTPConfigurationError(
            "SMTP_HOST, SMTP_USER and SMTP_PASSWORD are required"
        )
    try:
        port = int(env.get("SMTP_PORT", "587"))
    except ValueError as exc:
        raise SMTPConfigurationError("SMTP_PORT must be an integer") from exc
    if not 1 <= port <= 65535:
        raise SMTPConfigurationError("SMTP_PORT must be between 1 and 65535")

    context = ssl.create_default_context()
    if port == 465:
        with smtplib.SMTP_SSL(
            host, port, context=context, timeout=timeout_seconds
        ) as session:
            session.login(user, password)
            yield session
        return

    with smtplib.SMTP(host, port, timeout=timeout_seconds) as session:
        session.ehlo()
        session.starttls(context=context)
        session.ehlo()
        session.login(user, password)
        yield session
