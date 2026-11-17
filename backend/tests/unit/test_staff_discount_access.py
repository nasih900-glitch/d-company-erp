"""Owner-controlled discount supplement preserves staff identity and authority."""
from datetime import datetime, timezone
from unittest.mock import AsyncMock, patch
from uuid import uuid4

import pytest
from pydantic import ValidationError
from app.api.v1.staff import router as staff
from app.core.errors import AuthError, BusinessRuleError, ForbiddenError, NotFoundError
from app.core.permissions import GAMING_CASHIER_ACCESS, PERMISSIONS, ROLE_PERMISSIONS, _role_allows_permission
from app.core.tenant import TenantContext
from app.models import AuditLog, Role, User, UserRole


class Result:
    def __init__(self, scalar=None, rows=()):
        self.scalar, self.rows = scalar, rows
    def scalar_one_or_none(self):
        return self.scalar
    def scalars(self):
        return self
    def all(self):
        return self.rows


class Session:
    def __init__(self, *results):
        self.results = list(results)
        self.added, self.statements = [], []
        self.flush_count = 0
    async def execute(self, statement):
        self.statements.append(statement)
        assert self.results, str(statement)
        return self.results.pop(0)
    def add(self, value):
        self.added.append(value)
    async def flush(self):
        self.flush_count += 1


def fixture(role='owner'):
    tenant = TenantContext(user_id=uuid4(), company_id=uuid4(), branch_id=uuid4(),
                           terminal_id=uuid4(), roles=(role,))
    user = User(id=uuid4(), company_id=tenant.company_id, email='staff@example.invalid',
                name='Staff', password_hash='unused', status='active', auth_version=5,
                last_login_at=datetime.now(timezone.utc))
    discount_role = Role(id=uuid4(), company_id=tenant.company_id, code='pos_discount',
                         name='Manual discounts', permissions=[])
    return tenant, user, discount_role


def test_supplement_adds_only_discount_to_existing_staff_access():
    assert ROLE_PERMISSIONS['pos_discount'] == {'pos.discount.large'}
    assert (GAMING_CASHIER_ACCESS | ROLE_PERMISSIONS['pos_discount']) - GAMING_CASHIER_ACCESS == {'pos.discount.large'}
    assert ROLE_PERMISSIONS['gaming_cashier'] == GAMING_CASHIER_ACCESS
    for perm in PERMISSIONS:
        assert _role_allows_permission(role='pos_discount', perm=perm, module='pos', override=True) == (perm == 'pos.discount.large')
    assert not _role_allows_permission(role='pos_discount', perm='pos.discount.large', module='pos', override=False)


@pytest.mark.asyncio
@pytest.mark.parametrize('role', ['owner', 'co_owner', 'super_owner'])
async def test_owner_grant_preserves_base_role_and_branch(role):
    tenant, user, discount_role = fixture(role)
    branch = uuid4()
    session = Session(Result(scalar=discount_role))
    with patch('app.services.auth.credentials.validated_role_assignment', AsyncMock(return_value=(['gaming_cashier'], branch))):
        await staff._set_manual_discount_access(session, tenant, user, True)
    assignment = next(value for value in session.added if isinstance(value, UserRole))
    audit = next(value for value in session.added if isinstance(value, AuditLog))
    assert assignment.role_id == discount_role.id and assignment.branch_id == branch
    assert assignment.granted_by == tenant.user_id
    assert audit.actor_user_id == tenant.user_id and audit.target_user_id == user.id
    assert audit.company_id == tenant.company_id
    assert audit.before['before_roles'] == ['gaming_cashier']
    assert set(audit.after['after_roles']) == {'gaming_cashier', 'pos_discount'}
    assert audit.after['manual_discount_allowed'] is True
    assert len(session.statements) == 1
    assert user.auth_version == 5  # Database trigger owns session revocation.


@pytest.mark.asyncio
async def test_revoke_deletes_only_supplement_and_records_actor():
    tenant, user, discount_role = fixture()
    session = Session(Result(scalar=discount_role), Result())
    with patch('app.services.auth.credentials.validated_role_assignment', AsyncMock(return_value=(['gaming_cashier', 'pos_discount'], None))):
        await staff._set_manual_discount_access(session, tenant, user, False)
    assert not any(isinstance(value, UserRole) for value in session.added)
    deletion = session.statements[-1].compile().params
    assert user.id in deletion.values() and discount_role.id in deletion.values()
    audit = next(value for value in session.added if isinstance(value, AuditLog))
    assert audit.after['after_roles'] == ['gaming_cashier']
    assert audit.after['manual_discount_allowed'] is False


@pytest.mark.asyncio
@pytest.mark.parametrize('allowed,roles', [(True, ['gaming_cashier', 'pos_discount']), (False, ['gaming_cashier'])])
async def test_repeating_same_grant_is_noop_without_token_eviction(allowed, roles):
    tenant, user, _ = fixture()
    session = Session()
    with patch('app.services.auth.credentials.validated_role_assignment', AsyncMock(return_value=(roles, None))):
        await staff._set_manual_discount_access(session, tenant, user, allowed)
    assert session.added == [] and session.statements == []
    assert user.auth_version == 5


@pytest.mark.asyncio
@pytest.mark.parametrize('allowed', [True, False])
async def test_manager_cannot_grant_or_revoke(allowed):
    tenant, user, _ = fixture('manager')
    session = Session()
    with pytest.raises(ForbiddenError, match='Only an Owner'):
        await staff._set_manual_discount_access(session, tenant, user, allowed)
    with pytest.raises(ForbiddenError, match='Only an Owner'):
        await staff.update_user(user.id, staff.UserUpdate(manual_discount_allowed=allowed), session, tenant)
    assert session.statements == []


@pytest.mark.asyncio
async def test_cross_company_target_is_rejected_before_assignment_lookup():
    tenant, user, _ = fixture()
    user.company_id = uuid4()
    with pytest.raises(NotFoundError):
        await staff._set_manual_discount_access(Session(), tenant, user, True)


@pytest.mark.asyncio
async def test_self_grant_is_rejected():
    tenant, user, _ = fixture()
    user.id = tenant.user_id
    with pytest.raises(BusinessRuleError, match='own discount'):
        await staff._set_manual_discount_access(Session(), tenant, user, True)


@pytest.mark.asyncio
@pytest.mark.parametrize('roles', [['cashier'], ['owner'], ['gaming_cashier', 'manager'], ['gaming_cashier', 'gaming_cashier']])
async def test_ineligible_or_duplicate_roles_are_rejected(roles):
    tenant, user, _ = fixture()
    with patch('app.services.auth.credentials.validated_role_assignment', AsyncMock(return_value=(roles, None))):
        with pytest.raises(BusinessRuleError):
            await staff._set_manual_discount_access(Session(), tenant, user, True)


@pytest.mark.asyncio
async def test_invalid_assignment_fails_closed():
    tenant, user, _ = fixture()
    with patch('app.services.auth.credentials.validated_role_assignment', AsyncMock(side_effect=AuthError('invalid branch assignment'))):
        with pytest.raises(AuthError):
            await staff._set_manual_discount_access(Session(), tenant, user, True)


@pytest.mark.asyncio
async def test_unconfigured_company_cannot_grant():
    tenant, user, _ = fixture()
    session = Session(Result())
    with patch('app.services.auth.credentials.validated_role_assignment', AsyncMock(return_value=(['gaming_cashier'], None))):
        with pytest.raises(BusinessRuleError, match='not configured'):
            await staff._set_manual_discount_access(session, tenant, user, True)
    assert not session.added
    assert tenant.company_id in session.statements[0].compile().params.values()


@pytest.mark.asyncio
async def test_supplement_cannot_be_selected_as_primary_role_even_if_already_assigned():
    tenant, user, _ = fixture()
    session = Session()
    with pytest.raises(BusinessRuleError, match='separate Owner control'):
        await staff._set_role(session, tenant, user.id, 'pos_discount')
    with pytest.raises(BusinessRuleError, match='separate Owner control'):
        await staff.update_user(user.id, staff.UserUpdate(role_code='pos_discount'), session, tenant)
    assert session.statements == []


@pytest.mark.asyncio
async def test_response_keeps_base_role_first_and_reports_discount():
    tenant, user, _ = fixture()
    result = await staff._user_read(Session(Result(rows=['pos_discount', 'gaming_cashier'])), tenant, user)
    assert result.roles == ['gaming_cashier', 'pos_discount']
    assert result.manual_discount_allowed is True


@pytest.mark.asyncio
async def test_store_staff_reports_builtin_discount_without_a_supplement():
    tenant, user, _ = fixture()
    result = await staff._user_read(Session(Result(rows=['store_staff'])), tenant, user)
    assert result.roles == ['store_staff']
    assert result.manual_discount_allowed is True


@pytest.mark.parametrize('value', ['true', 'false', 1, 0])
def test_permission_field_is_strict_boolean(value):
    with pytest.raises(ValidationError):
        staff.UserUpdate(manual_discount_allowed=value)


@pytest.mark.asyncio
async def test_explicit_null_is_rejected():
    tenant, user, _ = fixture()
    with pytest.raises(BusinessRuleError, match='true or false'):
        await staff.update_user(user.id, staff.UserUpdate(manual_discount_allowed=None), Session(), tenant)


@pytest.mark.asyncio
async def test_combined_role_change_is_flushed_before_discount_validation():
    tenant, user, _ = fixture()
    session = Session(Result(scalar=user))
    async def validate_after_flush(*args):
        assert session.flush_count == 1
    with patch.object(staff, '_raw_roles_for_user', AsyncMock(return_value=['cashier'])), \
         patch.object(staff, '_set_role', AsyncMock()) as change_role, \
         patch.object(staff, '_set_manual_discount_access', AsyncMock(side_effect=validate_after_flush)), \
         patch.object(staff, '_user_read', AsyncMock()):
        await staff.update_user(user.id, staff.UserUpdate(role_code='gaming_cashier', manual_discount_allowed=True), session, tenant)
    change_role.assert_awaited_once()
    assert session.flush_count == 2
