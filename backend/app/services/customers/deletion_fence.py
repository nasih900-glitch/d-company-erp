"""PII-free customer deletion generation and mutation fence."""

from __future__ import annotations

from dataclasses import dataclass
from uuid import UUID

from sqlalchemy import select
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.errors import ConflictError
from app.models import CustomerDirectoryState

CUSTOMER_DIRECTORY_REVISION_HEADER = "X-Customer-Directory-Revision"
CUSTOMER_DIRECTORY_COMPANY_HEADER = "X-Customer-Directory-Company-Id"
MAX_DIRECTORY_REVISION = 2**63 - 1


@dataclass(frozen=True)
class CustomerDirectoryFence:
    current_revision: int
    captured_revision: int | None

    @property
    def allows_identity_mutation(self) -> bool:
        return self.captured_revision == self.current_revision or (
            self.captured_revision is None and self.current_revision == 0
        )

    @property
    def revision_to_persist(self) -> int | None:
        if self.captured_revision is None and self.current_revision == 0:
            return 0
        return self.captured_revision


async def lock_customer_directory(
    session: AsyncSession,
    *,
    company_id: UUID,
    captured_revision: int | None,
    captured_company_id: UUID | None = None,
    shared: bool = True,
) -> tuple[CustomerDirectoryState, CustomerDirectoryFence]:
    if captured_company_id is not None and captured_company_id != company_id:
        raise ConflictError(
            "Customer directory revision belongs to another company. Refresh customers and retry."
        )
    if captured_revision is not None and captured_company_id is None:
        raise ConflictError(
            "Customer directory revision is missing its company scope. Refresh customers and retry."
        )
    await session.execute(
        insert(CustomerDirectoryState)
        .values(company_id=company_id, deletion_revision=0)
        .on_conflict_do_nothing(index_elements=[CustomerDirectoryState.company_id])
    )
    state = (
        await session.execute(
            select(CustomerDirectoryState)
            .where(CustomerDirectoryState.company_id == company_id)
            .with_for_update(read=shared)
        )
    ).scalar_one()
    current = int(state.deletion_revision)
    if captured_revision is not None and captured_revision > current:
        raise ConflictError(
            "Customer directory revision is newer than the server. Refresh customers and retry."
        )
    return state, CustomerDirectoryFence(current, captured_revision)


async def read_customer_directory_revision(
    session: AsyncSession,
    *,
    company_id: UUID,
) -> int:
    await session.execute(
        insert(CustomerDirectoryState)
        .values(company_id=company_id, deletion_revision=0)
        .on_conflict_do_nothing(index_elements=[CustomerDirectoryState.company_id])
    )
    state = (
        await session.execute(
            select(CustomerDirectoryState)
            .where(CustomerDirectoryState.company_id == company_id)
            .with_for_update(read=True)
        )
    ).scalar_one()
    return int(state.deletion_revision)


def require_current_customer_directory(fence: CustomerDirectoryFence) -> None:
    if not fence.allows_identity_mutation:
        raise ConflictError(
            "Customer details were saved before the directory changed. "
            "Refresh customers and create a new action."
        )
