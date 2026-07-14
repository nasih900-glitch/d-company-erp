"""Per-company overrides of the static role->permission defaults.

Lets the protected owner toggle a role's access to a feature module on/off
live, without a code deploy. Absence of a row means "use the static default
in app.core.permissions.ROLE_PERMISSIONS" — this table only stores explicit
deviations from that default.
"""

from __future__ import annotations

from uuid import UUID

from sqlalchemy import Boolean, ForeignKey, String, UniqueConstraint
from sqlalchemy.dialects.postgresql import UUID as PG_UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, TimestampMixin, _uuid_pk


class RolePermissionOverride(Base, TimestampMixin):
    __tablename__ = "role_permission_overrides"
    __table_args__ = (
        UniqueConstraint("company_id", "role_code", "module", name="uq_role_perm_override"),
    )

    id: Mapped[UUID] = _uuid_pk()
    company_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("companies.id", ondelete="CASCADE"), nullable=False, index=True
    )
    role_code: Mapped[str] = mapped_column(String(30), nullable=False)
    module: Mapped[str] = mapped_column(String(30), nullable=False)
    allowed: Mapped[bool] = mapped_column(Boolean, nullable=False)
