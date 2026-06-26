"""Declarative base + shared mixins."""

from __future__ import annotations

from datetime import datetime
from uuid import UUID, uuid4

from sqlalchemy import DateTime, ForeignKey, func
from sqlalchemy.dialects.postgresql import UUID as PG_UUID
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    """Declarative base. All ORM models inherit from this."""

    type_annotation_map = {
        UUID: PG_UUID(as_uuid=True),
    }


def _uuid_pk() -> Mapped[UUID]:
    return mapped_column(PG_UUID(as_uuid=True), primary_key=True, default=uuid4)


class TimestampMixin:
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        server_default=func.now(),
        onupdate=func.now(),
        nullable=False,
    )


class SoftDeleteMixin:
    deleted_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True, index=True
    )


class TenantMixin:
    """Row-level multi-tenant scoping. Every business row carries company_id."""

    company_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("companies.id", ondelete="RESTRICT"),
        nullable=False,
        index=True,
    )


# Re-export so models can import a single helper.
__all__ = ["Base", "TimestampMixin", "SoftDeleteMixin", "TenantMixin", "_uuid_pk"]
