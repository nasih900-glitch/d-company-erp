"""Create a user with a chosen role from the command line.

Usage:
    python -m scripts.create_user --email friend@example.com --name "Mo's Friend" \
        --role auditor --password "<set-a-strong-password>"

Roles:
    super_owner       — protected full access (Nasih)
    owner             — business owner access without protected system controls
    partner           — finance read + capital write + analytics
    manager           — branch operations
    cashier           — POS only
    kitchen           — KDS only
    gaming_supervisor — gaming + POS read
    auditor           — read-only across every module (good for accountants / overseas partners)

Idempotent: re-running with the same email updates name/password/role instead of
creating duplicates.
"""

from __future__ import annotations

import argparse
import asyncio
from uuid import uuid4

from sqlalchemy import delete, select

from app.core.db import AsyncSessionLocal
from app.core.security import hash_password
from app.models import Company, Role, User, UserRole

ROLES = {
    "super_owner",
    "owner",
    "partner",
    "manager",
    "cashier",
    "kitchen",
    "gaming_supervisor",
    "auditor",
}


async def upsert_user(email: str, name: str, password: str, role: str) -> None:
    if role not in ROLES:
        raise SystemExit(f"Invalid role: {role!r}. Choose one of: {sorted(ROLES)}")

    async with AsyncSessionLocal() as s:
        company = (await s.execute(select(Company).limit(1))).scalar_one_or_none()
        if not company:
            raise SystemExit("No company found — run `python -m scripts.seed` first.")

        # Find or create the role for this company.
        role_row = (
            await s.execute(
                select(Role).where(Role.company_id == company.id, Role.code == role)
            )
        ).scalar_one_or_none()
        if not role_row:
            raise SystemExit(
                f"Role {role!r} not found in seed. Re-run `python -m scripts.seed` "
                f"or check models/seed.py."
            )

        existing = (
            await s.execute(
                select(User).where(User.company_id == company.id, User.email == email)
            )
        ).scalar_one_or_none()

        if existing:
            print(f"User {email} exists — updating name + password + role.")
            existing.name = name
            existing.password_hash = hash_password(password)
            existing.status = "active"
            # Replace role bindings
            await s.execute(delete(UserRole).where(UserRole.user_id == existing.id))
            user_id = existing.id
        else:
            user_id = uuid4()
            s.add(
                User(
                    id=user_id,
                    company_id=company.id,
                    email=email,
                    name=name,
                    password_hash=hash_password(password),
                    status="active",
                )
            )
            print(f"Created user {email}.")

        # Ensure exactly one role binding (idempotent)
        s.add(UserRole(id=uuid4(), user_id=user_id, role_id=role_row.id))

        await s.commit()
        print(f"  • role={role}")
        print(f"  • company={company.name}")
        print("  • login at https://YOUR_DOMAIN with this email + the password you set")


def main() -> None:
    p = argparse.ArgumentParser(description="Create or update a user account.")
    p.add_argument("--email", required=True)
    p.add_argument("--name", required=True)
    p.add_argument("--password", required=True)
    p.add_argument(
        "--role",
        required=True,
        choices=sorted(ROLES),
        help="auditor = read-only across all modules (good for an overseas friend)",
    )
    args = p.parse_args()
    asyncio.run(upsert_user(args.email, args.name, args.password, args.role))


if __name__ == "__main__":
    main()
