"""Current create authority and exact completed GRN proof, without new economics."""

from __future__ import annotations

import hashlib
import json
import re

from sqlalchemy import select, text

from app.core.errors import AuthError, BusinessRuleError, ConflictError
from app.core.middleware import idempotency_request_hash
from app.core.permissions import require_permission
from app.core.tenant import get_tenant_context
from app.models import GrnActionDecision, GrnCompletionProof, User
from app.schemas.grn_commands import PATH, parse_wire


async def require_read_committed(session):
    if await session.scalar(text("SHOW transaction_isolation")) != "read committed":
        raise ConflictError(
            "GRN recovery requires a fresh request.",
            details={"retryable": True, "outcome": "unconfirmed"},
        )


def key_owner(key, company, actor):
    if not isinstance(key, str) or not re.fullmatch(
        rf"grn:v1:{company}:{actor}:[a-f0-9]{{8}}-[a-f0-9]{{4}}-[a-f0-9]{{4}}-[a-f0-9]{{4}}-[a-f0-9]{{12}}",
        key,
    ):
        raise BusinessRuleError("Use the exact original owned GRN command.")


def raw_hash(key, raw):
    return idempotency_request_hash(
        method="POST", path=PATH, query="", key=key, body=raw.encode("utf-8")
    )


def digest(value):
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


async def create_authority(session, request, tenant):
    await session.scalar(
        select(User)
        .where(User.id == tenant.user_id, User.company_id == tenant.company_id)
        .with_for_update(read=True)
        .execution_options(populate_existing=True)
    )
    authorization, terminal = (
        request.headers.get("Authorization"),
        request.headers.get("X-Terminal-Id"),
    )

    async def fresh():
        current = await get_tenant_context(request, session, authorization, terminal)
        if current != tenant:
            raise AuthError("The original GRN session changed. Recover the saved command.")
        await require_permission(session, current, "inventory.write")

    await fresh()
    raw = None
    if request.headers.get("X-GRN-Command-Protocol") is not None:
        if request.headers.get("X-GRN-Command-Protocol") != "1" or request.query_params:
            raise BusinessRuleError("Unsupported GRN command profile.")
        key_owner(request.state.idempotency_key, tenant.company_id, tenant.user_id)
        try:
            raw = (await request.body()).decode("utf-8", errors="strict")
            parse_wire(raw)
        except (ValueError, UnicodeError) as exc:
            raise BusinessRuleError("The saved GRN wire command is invalid.") from exc
    return fresh, raw


async def add_producer_proof(session, grn, tenant, raw):
    if raw is None:
        return
    source = await session.scalar(text("SELECT dcompany_grn_source(:id)"), {"id": grn.id})
    response = await session.scalar(
        text("SELECT dcompany_grn_response(CAST(:source AS jsonb))"), {"source": json.dumps(source)}
    )
    session.add(
        GrnCompletionProof(
            id=grn.id,
            profile="wire_v1",
            original_wire_json=raw,
            terminal_id=tenant.terminal_id,
            terminal_known=True,
            source=source,
            response=response,
        )
    )
    await session.flush()


async def cached_response(session, key, tenant, cached, strict_transport):
    row = await decision(session, key)
    if (
        row is None
        or row.outcome != "accepted"
        or row.company_id != tenant.company_id
        or row.actor_user_id != tenant.user_id
        or not tenant.in_branch(row.branch_id)
    ):
        raise ConflictError("The exact completed GRN source is unconfirmed.")
    proof = await session.get(GrnCompletionProof, row.id)
    if (
        proof is None
        or proof.response != cached
        or strict_transport
        and (not proof.terminal_known or proof.terminal_id != tenant.terminal_id)
    ):
        raise ConflictError("The exact completed GRN source is unconfirmed.")
    return proof.response


async def retained_response(session, grn, tenant, strict_transport):
    proof = await session.get(GrnCompletionProof, grn.id)
    row = await decision(session, grn.idempotency_key)
    if (
        row is None
        or row.company_id != tenant.company_id
        or row.actor_user_id != tenant.user_id
        or not tenant.in_branch(row.branch_id)
        or proof is None
        or strict_transport
        and (not proof.terminal_known or proof.terminal_id != tenant.terminal_id)
    ):
        raise ConflictError(
            "The completed GRN proof is unavailable. Keep the saved command.",
            details={"outcome": "unconfirmed"},
        )
    return proof.response


async def decision(session, key):
    return await session.scalar(
        select(GrnActionDecision).where(GrnActionDecision.action_key == key)
    )
