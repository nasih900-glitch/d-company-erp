"""Collapse D Company down to a single terminal.

Three terminals existed from initial seeding (POS-01, Gaming Terminal, Cafe
Terminal), forcing staff to pick one on every new device before they could
open a shift. D Company runs as one operation, not three separately-tracked
registers, so this merges everything onto one terminal and removes the
picker entirely (resolveTerminal() on the frontend auto-selects when only
one terminal exists for a branch).

Reassigns every Shift/Order currently pointing at a terminal being removed,
then deletes the now-unreferenced terminal rows. Refuses to run if any
shift is currently open on a terminal being removed (close it first).

Usage:
    python -m scripts.merge_terminals_to_one --keep "POS-01" --keep-name "Main Terminal"

Idempotent: re-running after the merge is already done finds nothing left
to reassign and exits cleanly.
"""

from __future__ import annotations

import argparse
import asyncio

from sqlalchemy import select

from app.core.db import AsyncSessionLocal
from app.models import Branch, Company, Order, Shift, Terminal


async def run(keep_name: str, rename_to: str) -> None:
    async with AsyncSessionLocal() as s:
        company = (await s.execute(select(Company).limit(1))).scalar_one_or_none()
        if not company:
            raise SystemExit("No company found — run `python -m scripts.seed` first.")

        branch_ids = (
            (await s.execute(select(Branch.id).where(Branch.company_id == company.id)))
            .scalars()
            .all()
        )
        all_terminals = (
            (await s.execute(select(Terminal).where(Terminal.branch_id.in_(branch_ids))))
            .scalars()
            .all()
        )
        keeper = next((t for t in all_terminals if t.name == keep_name), None)
        if keeper is None:
            raise SystemExit(
                f"No terminal named {keep_name!r} found among: {[t.name for t in all_terminals]}"
            )

        to_remove = [t for t in all_terminals if t.id != keeper.id]
        if not to_remove:
            print(f"Already down to one terminal: {keeper.name!r}. Nothing to do.")
        else:
            remove_ids = [t.id for t in to_remove]
            remove_names = [t.name for t in to_remove]

            open_on_removed = (
                await s.execute(
                    select(Shift).where(Shift.terminal_id.in_(remove_ids), Shift.status == "open")
                )
            ).scalars().all()
            if open_on_removed:
                raise SystemExit(
                    f"Refusing: {len(open_on_removed)} shift(s) currently open on a terminal "
                    f"being removed — close them first, then re-run."
                )

            shifts_to_move = (
                (await s.execute(select(Shift).where(Shift.terminal_id.in_(remove_ids))))
                .scalars()
                .all()
            )
            for sh in shifts_to_move:
                sh.terminal_id = keeper.id

            orders_to_move = (
                (await s.execute(select(Order).where(Order.terminal_id.in_(remove_ids))))
                .scalars()
                .all()
            )
            for o in orders_to_move:
                o.terminal_id = keeper.id

            await s.flush()

            for t in to_remove:
                await s.delete(t)

            print(
                f"Reassigned {len(shifts_to_move)} shift(s) and {len(orders_to_move)} order(s) "
                f"from {remove_names} onto {keeper.name!r}. Removed {remove_names}."
            )

        if rename_to and keeper.name != rename_to:
            print(f"Renaming {keeper.name!r} -> {rename_to!r}")
            keeper.name = rename_to

        await s.commit()
        print("Done.")


def main() -> None:
    p = argparse.ArgumentParser(description="Merge all terminals for the company down to one.")
    p.add_argument("--keep", required=True, help="Name of the terminal to keep (others get merged into it)")
    p.add_argument("--keep-name", default=None, help="Optionally rename the surviving terminal")
    args = p.parse_args()
    asyncio.run(run(args.keep, args.keep_name or args.keep))


if __name__ == "__main__":
    main()
