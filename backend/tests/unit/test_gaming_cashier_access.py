from contextlib import asynccontextmanager
from types import SimpleNamespace
from unittest.mock import patch
from uuid import uuid4

import pytest

from app.core.permissions import (
    GAMING_CASHIER_ACCESS,
    PERMISSIONS,
    ROLE_PERMISSIONS,
    _PERMISSION_TO_MODULE,
    _role_allows_permission,
)
from app.core.roles import OWNER_ROLE_CODES, has_full_access, has_protected_owner_access, role_name
from scripts.ensure_roles import ensure_roles
from scripts.seed import DEFAULT_ROLES


EXPECTED = {
    "pos.read", "pos.write", "pos.void", "pos.shift.open", "pos.shift.close",
    "menu.read", "gaming.read", "gaming.write", "staff.attendance.write",
}


@pytest.mark.parametrize("override", [None, True, False])
def test_daily_staff_permission_ceiling_survives_module_overrides(override):
    assert GAMING_CASHIER_ACCESS == ROLE_PERMISSIONS["gaming_cashier"] == EXPECTED
    allowed = {
        permission for permission in PERMISSIONS
        if _role_allows_permission(
            role="gaming_cashier", perm=permission,
            module=_PERMISSION_TO_MODULE.get(permission), override=override,
        )
    }
    assert allowed == (set() if override is False else EXPECTED)
    assert "gaming_cashier" not in OWNER_ROLE_CODES
    assert not has_full_access(["gaming_cashier"])
    assert not has_protected_owner_access(["gaming_cashier"])


@pytest.mark.asyncio
async def test_backfill_is_idempotent_and_preserves_existing_role_names_and_assignments():
    companies = [uuid4(), uuid4()]
    old_codes = {code for code, _ in DEFAULT_ROLES} - {"gaming_cashier"}
    rows = {company: {code: SimpleNamespace(code=code, name="Existing " + code)
                      for code in old_codes} for company in companies}
    existing = {company: dict(roles) for company, roles in rows.items()}
    added = []
    commits = []

    class Session:
        async def execute(self, query):
            params = query.compile().params
            values = (companies if not params
                      else list(rows[params["company_id_1"]]))
            return SimpleNamespace(scalars=lambda: SimpleNamespace(all=lambda: values))

        def add(self, role):
            assert role.company_id in rows
            assert role.code == "gaming_cashier"
            assert role.code not in rows[role.company_id]
            rows[role.company_id][role.code] = role
            added.append(role)

        async def commit(self):
            commits.append(True)

    @asynccontextmanager
    async def factory():
        yield Session()

    with patch("scripts.ensure_roles.AsyncSessionLocal", factory):
        await ensure_roles()
        await ensure_roles()

    assert len(added) == 2
    assert len(commits) == 1
    for company in companies:
        assert rows[company]["gaming_cashier"].name == "Gaming & POS staff"
        for code, role in existing[company].items():
            assert rows[company][code] is role
            assert role.name == "Existing " + code


def test_role_label_is_generic_and_existing_role_labels_are_unchanged():
    assert role_name("gaming_cashier") == "Gaming & POS staff"
    assert [code for code, _ in DEFAULT_ROLES].count("gaming_cashier") == 1
    for code, _ in DEFAULT_ROLES:
        if code != "gaming_cashier":
            assert role_name(code) == code.title().replace("_", " ")
    assert not _role_allows_permission(
        role="gaming_cashier", perm="gaming.future.manage", module="gaming", override=True,
    )
