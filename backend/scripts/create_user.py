"""Create one non-protected ERP user without exposing a password in argv.

Examples:

    python -m scripts.create_user --email friend@example.com \
        --name "Mo's Friend" --role auditor

The password is read twice with terminal echo disabled. Controlled automation
may pass one line on stdin with ``--password-stdin``. Existing accounts are
never modified; password/role changes belong to authenticated ERP workflows.
"""

from __future__ import annotations

import argparse
import asyncio
import getpass
import sys
from uuid import UUID, uuid4

from sqlalchemy import select

from app.core.config import get_settings
from app.core.db import AsyncSessionLocal
from app.core.errors import BusinessRuleError
from app.core.security import hash_password
from app.models import AuditLog, Company, Role, User, UserRole
from app.services.auth.otp import normalize_account_email

ROLES = {
    "owner",
    "partner",
    "manager",
    "cashier",
    "kitchen",
    "gaming_supervisor",
    "auditor",
    "staff",
}
_MAX_PASSWORD_LENGTH = 256


def _read_password(*, password_stdin: bool) -> str:
    if password_stdin:
        value = sys.stdin.readline()
        if not value:
            raise SystemExit("No password was received on stdin.")
        password = value.rstrip("\r\n")
    else:
        if not sys.stdin.isatty():
            raise SystemExit(
                "Interactive user creation requires a TTY; "
                "use --password-stdin for controlled automation."
            )
        password = getpass.getpass("Temporary password: ")
        confirmation = getpass.getpass("Confirm temporary password: ")
        if password != confirmation:
            raise SystemExit("Password confirmation did not match.")
    minimum = get_settings().password_min_length
    if not minimum <= len(password) <= _MAX_PASSWORD_LENGTH:
        raise SystemExit(
            f"Password must contain {minimum} to {_MAX_PASSWORD_LENGTH} characters."
        )
    return password


async def create_user(
    *,
    email: str,
    name: str,
    password: str,
    role: str,
    company_id: UUID | None = None,
) -> UUID:
    if role not in ROLES:
        raise SystemExit(f"Invalid role: {role!r}. Choose one of: {sorted(ROLES)}")
    try:
        normalized_email = normalize_account_email(email)
    except BusinessRuleError as exc:
        raise SystemExit("Email must be a valid normalized login identity.") from exc
    if normalized_email != email:
        raise SystemExit("Email must already be lowercase with no surrounding whitespace.")
    normalized_name = name.strip()
    if not normalized_name or len(normalized_name) > 120:
        raise SystemExit("Name must contain 1 to 120 non-whitespace characters.")

    async with AsyncSessionLocal() as session:
        if company_id is not None:
            company = await session.get(Company, company_id)
            if company is None:
                raise SystemExit("The requested company does not exist.")
        else:
            companies = (
                await session.execute(select(Company).order_by(Company.id).limit(2))
            ).scalars().all()
            if len(companies) != 1:
                raise SystemExit(
                    "Company is ambiguous; pass --company-id with the exact tenant UUID."
                )
            company = companies[0]

        role_row = (
            await session.execute(
                select(Role).where(Role.company_id == company.id, Role.code == role)
            )
        ).scalar_one_or_none()
        if role_row is None:
            raise SystemExit(
                f"Role {role!r} is not seeded for this company; run ensure_roles first."
            )

        existing = (
            await session.execute(
                select(User)
                .where(
                    User.company_id == company.id,
                    User.email == normalized_email,
                    User.deleted_at.is_(None),
                )
                .with_for_update()
            )
        ).scalar_one_or_none()
        if existing is not None:
            existing_roles = set(
                (
                    await session.execute(
                        select(Role.code)
                        .join(UserRole, UserRole.role_id == Role.id)
                        .where(UserRole.user_id == existing.id)
                    )
                ).scalars()
            )
            if "super_owner" in existing_roles:
                raise SystemExit(
                    "Refusing to modify the protected super owner; use the dedicated "
                    "role-preserving owner reset command."
                )
            raise SystemExit(
                "A live user with this email already exists. No changes were made; "
                "use authenticated Staff/OTP workflows for role or password changes."
            )

        user_id = uuid4()
        session.add(
            User(
                id=user_id,
                company_id=company.id,
                email=normalized_email,
                name=normalized_name,
                password_hash=hash_password(password),
                status="active",
            )
        )
        session.add(UserRole(id=uuid4(), user_id=user_id, role_id=role_row.id))
        session.add(
            AuditLog(
                actor_user_id=None,
                company_id=company.id,
                action="user_created_console",
                entity_type="User",
                entity_id=str(user_id),
                before=None,
                after={
                    "email": normalized_email,
                    "role": role,
                    "mechanism": "local_console_secret_input",
                },
                reason="operator_user_provisioning",
            )
        )
        await session.commit()
        return user_id


def main() -> None:
    parser = argparse.ArgumentParser(description="Create a new non-protected ERP user.")
    parser.add_argument("--email", required=True)
    parser.add_argument("--name", required=True)
    parser.add_argument("--role", required=True, choices=sorted(ROLES))
    parser.add_argument("--company-id", type=UUID)
    parser.add_argument(
        "--password-stdin",
        action="store_true",
        help="read one password line from stdin instead of an interactive no-echo prompt",
    )
    args = parser.parse_args()
    user_id = asyncio.run(
        create_user(
            email=args.email,
            name=args.name,
            password=_read_password(password_stdin=args.password_stdin),
            role=args.role,
            company_id=args.company_id,
        )
    )
    print(f"Created user {args.email} ({user_id}).")
    print(f"Role: {args.role}")
    print("The temporary password was not displayed; transfer it through an approved channel.")


if __name__ == "__main__":
    main()
