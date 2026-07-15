"""Idempotency keys are scoped to an exact HTTP operation."""

from types import SimpleNamespace
from uuid import uuid4

import pytest

from app.core.errors import IdempotencyConflict, IdempotencyInProgress
from app.core.idempotency import check_or_reserve
from app.core.middleware import idempotency_request_hash


class _Result:
    def __init__(self, row) -> None:
        self.row = row

    def scalar_one_or_none(self):
        return self.row


class _Session:
    def __init__(self, row) -> None:
        self.row = row

    async def execute(self, _statement):
        return _Result(self.row)


def _hash(
    *,
    method: str = "POST",
    path: str = "/api/v1/pos/orders/a/payments",
    query: str = "",
) -> str:
    return idempotency_request_hash(
        method=method,
        path=path,
        query=query,
        key="checkout-123",
        body=b'{"amount_minor":1000,"method":"cash"}',
    )


def test_identical_operation_and_body_hash_identically() -> None:
    assert _hash() == _hash()


def test_same_key_and_body_on_different_order_cannot_replay() -> None:
    assert _hash(path="/api/v1/pos/orders/a/payments") != _hash(
        path="/api/v1/pos/orders/b/payments"
    )


def test_method_and_query_are_part_of_operation_identity() -> None:
    assert _hash(method="POST") != _hash(method="PATCH")
    assert _hash(query="force=true") != _hash(query="force=false")


@pytest.mark.asyncio
async def test_matching_reserved_request_is_retryable_with_the_same_key() -> None:
    user_id = uuid4()
    terminal_id = uuid4()
    row = SimpleNamespace(
        request_hash="same-hash",
        user_id=user_id,
        terminal_id=terminal_id,
        response_status=None,
        response_body=None,
    )

    with pytest.raises(IdempotencyInProgress):
        await check_or_reserve(
            _Session(row),
            key="same-key",
            request_hash="same-hash",
            user_id=user_id,
            terminal_id=terminal_id,
        )


@pytest.mark.asyncio
async def test_payload_mismatch_remains_a_non_retryable_conflict() -> None:
    row = SimpleNamespace(
        request_hash="original-hash",
        user_id=None,
        terminal_id=None,
        response_status=201,
        response_body={"id": "existing"},
    )

    with pytest.raises(IdempotencyConflict):
        await check_or_reserve(
            _Session(row),
            key="same-key",
            request_hash="different-hash",
        )
