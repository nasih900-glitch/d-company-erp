"""Private immutable expense receipts and append-only review decisions."""

from __future__ import annotations

import hashlib
from datetime import UTC, datetime
from io import BytesIO
from uuid import UUID, uuid4

import pytest
from PIL import Image
from sqlalchemy import func, select, text
from sqlalchemy.exc import DBAPIError

from app.core.security import issue_access_token
from app.models import (
    AuditLog,
    Branch,
    Company,
    Expense,
    ExpenseCategory,
    ExpenseReceipt,
    ExpenseReceiptReview,
)


def _headers(
    seed,
    *,
    key: str | None = None,
    branch_id: UUID | None = None,
    roles: list[str] | None = None,
) -> dict[str, str]:
    owner = seed["owner"]
    token = issue_access_token(
        user_id=owner.id,
        company_id=seed["company"].id,
        branch_id=branch_id or seed["branch"].id,
        roles=roles or ["owner"],
        auth_version=owner.auth_version,
    )
    headers = {"Authorization": f"Bearer {token}"}
    if key is not None:
        headers["Idempotency-Key"] = key
    return headers


def _png_bytes(color: tuple[int, int, int] = (220, 170, 40)) -> bytes:
    output = BytesIO()
    Image.new("RGB", (12, 8), color).save(output, format="PNG")
    return output.getvalue()


def _pdf_bytes(marker: str = "one") -> bytes:
    return (
        b"%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\n"
        + marker.encode()
        + b"\n%%EOF\n"
    )


def _heic_bytes() -> bytes:
    compatible = b"mif1heic"
    box_size = 16 + len(compatible)
    return box_size.to_bytes(4, "big") + b"ftyp" + b"heic" + b"\x00\x00\x00\x00" + compatible


async def _expense(session, seed, *, branch: Branch | None = None) -> Expense:
    branch = branch or seed["branch"]
    category = ExpenseCategory(
        id=uuid4(),
        company_id=seed["company"].id,
        name=f"Receipt test {uuid4().hex[:8]}",
        code=f"RT{uuid4().hex[:10]}",
    )
    # The production trigger resolves category identity from the database, so
    # make the reference visible before the expense insert in this direct
    # fixture (the API naturally validates an already-existing category).
    session.add(category)
    await session.flush()
    expense = Expense(
        id=uuid4(),
        company_id=seed["company"].id,
        branch_id=branch.id,
        category_id=category.id,
        amount_minor=12_345,
        paid_via="upi",
        paid_at=datetime.now(UTC),
        vendor_name="Receipt test vendor",
        invoice_no=f"INV-{uuid4().hex[:8]}",
        note="Immutable receipt test",
        source_integrity_revision=50,
    )
    session.add(expense)
    await session.commit()
    return expense


@pytest.mark.integration
@pytest.mark.asyncio
async def test_receipt_upload_replay_review_download_and_expense_summary(
    client,
    session,
    seed_owner,
) -> None:
    expense = await _expense(session, seed_owner)
    image = _png_bytes()
    upload_key = f"expense-receipt:{uuid4()}"
    uploaded = await client.post(
        f"/api/v1/finance/expenses/{expense.id}/receipts",
        headers=_headers(seed_owner, key=upload_key),
        data={"source": "camera"},
        files={"file": ("shop-bill.png", image, "image/png")},
    )
    assert uploaded.status_code == 201, uploaded.text
    receipt = uploaded.json()
    assert receipt == {
        "id": receipt["id"],
        "expense_id": str(expense.id),
        "original_filename": "shop-bill.png",
        "content_type": "image/png",
        "size_bytes": len(image),
        "sha256": hashlib.sha256(image).hexdigest(),
        "source": "camera",
        "status": "pending",
        "review_note": None,
        "created_at": receipt["created_at"],
    }

    # Multipart boundaries change between network retries. Canonical content
    # identity still gives the native outbox the original response.
    replay = await client.post(
        f"/api/v1/finance/expenses/{expense.id}/receipts",
        headers=_headers(seed_owner, key=upload_key),
        data={"source": "camera"},
        files={"file": ("shop-bill.png", image, "image/png")},
    )
    assert replay.status_code == 201, replay.text
    assert replay.json() == receipt

    changed_replay = await client.post(
        f"/api/v1/finance/expenses/{expense.id}/receipts",
        headers=_headers(seed_owner, key=upload_key),
        data={"source": "camera"},
        files={"file": ("different-bill.png", _png_bytes((20, 40, 60)), "image/png")},
    )
    assert changed_replay.status_code == 409, changed_replay.text

    missing_key = await client.post(
        f"/api/v1/finance/expenses/{expense.id}/receipts",
        headers=_headers(seed_owner),
        data={"source": "camera"},
        files={"file": ("missing-key.png", image, "image/png")},
    )
    assert missing_key.status_code == 422, missing_key.text

    # A fresh key carrying the same receipt also resolves to the retained row;
    # it does not append a second file or a second pending-review event.
    duplicate = await client.post(
        f"/api/v1/finance/expenses/{expense.id}/receipts",
        headers=_headers(seed_owner, key=f"expense-receipt:{uuid4()}"),
        data={"source": "camera"},
        files={"file": ("shop-bill.png", image, "image/png")},
    )
    assert duplicate.status_code == 201, duplicate.text
    assert duplicate.json()["id"] == receipt["id"]
    assert (
        await session.execute(
            select(func.count(ExpenseReceipt.id)).where(
                ExpenseReceipt.expense_id == expense.id
            )
        )
    ).scalar_one() == 1
    assert (
        await session.execute(
            select(func.count(ExpenseReceiptReview.id)).where(
                ExpenseReceiptReview.expense_id == expense.id
            )
        )
    ).scalar_one() == 1

    downloaded = await client.get(
        f"/api/v1/finance/expense-receipts/{receipt['id']}/content",
        headers=_headers(seed_owner),
    )
    assert downloaded.status_code == 200, downloaded.text
    assert downloaded.content == image
    assert downloaded.headers["cache-control"] == "private, no-store"
    assert downloaded.headers["x-content-type-options"] == "nosniff"
    assert downloaded.headers["content-security-policy"] == "sandbox"
    read_only_headers = _headers(seed_owner, roles=["partner"])
    read_only_download = await client.get(
        f"/api/v1/finance/expense-receipts/{receipt['id']}/content",
        headers=read_only_headers,
    )
    assert read_only_download.status_code == 200, read_only_download.text
    denied_write = await client.post(
        f"/api/v1/finance/expenses/{expense.id}/receipts",
        headers={**read_only_headers, "Idempotency-Key": f"receipt:{uuid4()}"},
        data={"source": "file"},
        files={"file": ("denied.pdf", _pdf_bytes("denied"), "application/pdf")},
    )
    assert denied_write.status_code == 403, denied_write.text

    review_key = f"expense-receipt-review:{uuid4()}"
    verified = await client.post(
        f"/api/v1/finance/expenses/{expense.id}/receipt-review",
        headers=_headers(seed_owner, key=review_key),
        json={"status": "verified", "review_note": "Matched to the vendor invoice"},
    )
    assert verified.status_code == 201, verified.text
    assert verified.json()["status"] == "verified"
    review_replay = await client.post(
        f"/api/v1/finance/expenses/{expense.id}/receipt-review",
        headers=_headers(seed_owner, key=review_key),
        json={"status": "verified", "review_note": "Matched to the vendor invoice"},
    )
    assert review_replay.status_code == 201, review_replay.text
    assert review_replay.json() == verified.json()
    changed_review_replay = await client.post(
        f"/api/v1/finance/expenses/{expense.id}/receipt-review",
        headers=_headers(seed_owner, key=review_key),
        json={"status": "rejected", "review_note": "The total does not match"},
    )
    assert changed_review_replay.status_code == 409, changed_review_replay.text

    listed = await client.get(
        f"/api/v1/finance/expenses/{expense.id}/receipts",
        headers=_headers(seed_owner),
    )
    assert listed.status_code == 200, listed.text
    assert listed.json()[0]["sha256"] == hashlib.sha256(image).hexdigest()
    assert listed.json()[0]["status"] == "verified"
    assert listed.json()[0]["review_note"] == "Matched to the vendor invoice"
    expenses = await client.get(
        "/api/v1/finance/expenses",
        headers=_headers(seed_owner),
    )
    assert expenses.status_code == 200, expenses.text
    expense_read = next(row for row in expenses.json() if row["id"] == str(expense.id))
    assert expense_read["receipt_count"] == 1
    assert expense_read["receipt_status"] == "verified"
    history = await client.get(
        f"/api/v1/finance/expenses/{expense.id}/receipt-reviews",
        headers=_headers(seed_owner),
    )
    assert history.status_code == 200, history.text
    assert [row["status"] for row in history.json()] == ["verified", "pending"]
    audit_rows = (
        (
            await session.execute(
                select(AuditLog).where(
                    AuditLog.company_id == seed_owner["company"].id,
                    AuditLog.entity_type == "Expense",
                    AuditLog.entity_id == str(expense.id),
                    AuditLog.action.in_(
                        {"expense_receipt_add", "expense_receipt_review"}
                    ),
                )
            )
        )
        .scalars()
        .all()
    )
    assert {row.action for row in audit_rows} == {
        "expense_receipt_add",
        "expense_receipt_review",
    }
    review_audit = next(
        row for row in audit_rows if row.action == "expense_receipt_review"
    )
    assert review_audit.before == {"status": "pending", "review_note": None}
    assert review_audit.after["status"] == "verified"

    voided = await client.post(
        f"/api/v1/finance/expenses/{expense.id}/void",
        headers=_headers(seed_owner),
        json={"reason": "Correcting the posted expense"},
    )
    assert voided.status_code == 200, voided.text
    assert voided.json()["receipt_count"] == 1
    assert voided.json()["receipt_status"] == "verified"
    retained_after_void = await client.get(
        f"/api/v1/finance/expense-receipts/{receipt['id']}/content",
        headers=_headers(seed_owner),
    )
    assert retained_after_void.status_code == 200, retained_after_void.text
    blocked_after_void = await client.post(
        f"/api/v1/finance/expenses/{expense.id}/receipts",
        headers=_headers(seed_owner, key=f"receipt:{uuid4()}"),
        data={"source": "file"},
        files={"file": ("late.pdf", _pdf_bytes("late"), "application/pdf")},
    )
    assert blocked_after_void.status_code == 404, blocked_after_void.text


@pytest.mark.integration
@pytest.mark.asyncio
async def test_receipt_type_limits_review_rules_and_branch_scope(
    client,
    session,
    seed_owner,
) -> None:
    expense = await _expense(session, seed_owner)
    auth = _headers(seed_owner)

    no_evidence_review = await client.post(
        f"/api/v1/finance/expenses/{expense.id}/receipt-review",
        headers={**auth, "Idempotency-Key": f"review:{uuid4()}"},
        json={"status": "verified", "review_note": None},
    )
    assert no_evidence_review.status_code == 422, no_evidence_review.text
    missing_reason = await client.post(
        f"/api/v1/finance/expenses/{expense.id}/receipt-review",
        headers={**auth, "Idempotency-Key": f"review:{uuid4()}"},
        json={"status": "not_required", "review_note": "  "},
    )
    assert missing_reason.status_code == 422, missing_reason.text
    not_required = await client.post(
        f"/api/v1/finance/expenses/{expense.id}/receipt-review",
        headers={**auth, "Idempotency-Key": f"review:{uuid4()}"},
        json={"status": "not_required", "review_note": "Petty expense has no bill"},
    )
    assert not_required.status_code == 201, not_required.text

    heic = _heic_bytes()
    rejected_heic = await client.post(
        f"/api/v1/finance/expenses/{expense.id}/receipts",
        headers={**auth, "Idempotency-Key": f"receipt:{uuid4()}"},
        data={"source": "gallery"},
        files={"file": ("camera.heic", heic, "application/octet-stream")},
    )
    assert rejected_heic.status_code == 422, rejected_heic.text

    canonical_name = await client.post(
        f"/api/v1/finance/expenses/{expense.id}/receipts",
        headers={**auth, "Idempotency-Key": f"receipt:{uuid4()}"},
        data={"source": "file"},
        files={"file": ("receipt.html", _png_bytes(), "image/png")},
    )
    assert canonical_name.status_code == 201, canonical_name.text
    assert canonical_name.json()["original_filename"] == "receipt.png"

    mismatch = await client.post(
        f"/api/v1/finance/expenses/{expense.id}/receipts",
        headers={**auth, "Idempotency-Key": f"receipt:{uuid4()}"},
        data={"source": "file"},
        files={"file": ("wrong.png", _png_bytes(), "application/pdf")},
    )
    assert mismatch.status_code == 422, mismatch.text
    oversized = await client.post(
        f"/api/v1/finance/expenses/{expense.id}/receipts",
        headers={**auth, "Idempotency-Key": f"receipt:{uuid4()}"},
        data={"source": "file"},
        files={
            "file": (
                "too-large.pdf",
                b"%PDF-1.4\n" + b"x" * (10 * 1024 * 1024) + b"%%EOF",
                "application/pdf",
            )
        },
    )
    assert oversized.status_code == 422, oversized.text

    # HEIC is the first file; four distinct PDFs reach the five-file cap.
    for index in range(4):
        response = await client.post(
            f"/api/v1/finance/expenses/{expense.id}/receipts",
            headers={**auth, "Idempotency-Key": f"receipt:{uuid4()}"},
            data={"source": "file"},
            files={
                "file": (
                    f"receipt-{index}.pdf",
                    _pdf_bytes(str(index)),
                    "application/pdf",
                )
            },
        )
        assert response.status_code == 201, response.text
    over_count = await client.post(
        f"/api/v1/finance/expenses/{expense.id}/receipts",
        headers={**auth, "Idempotency-Key": f"receipt:{uuid4()}"},
        data={"source": "file"},
        files={"file": ("sixth.pdf", _pdf_bytes("sixth"), "application/pdf")},
    )
    assert over_count.status_code == 422, over_count.text

    other_branch = Branch(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        name=f"Other receipt branch {uuid4().hex[:8]}",
        invoice_series_code=f"R{uuid4().hex[:1].upper()}",
    )
    session.add(other_branch)
    await session.commit()
    other_expense = await _expense(session, seed_owner, branch=other_branch)
    hidden = await client.get(
        f"/api/v1/finance/expenses/{other_expense.id}/receipts",
        headers=auth,
    )
    assert hidden.status_code == 404, hidden.text


@pytest.mark.integration
@pytest.mark.asyncio
async def test_receipt_database_guards_preserve_evidence_and_tenant_identity(
    client,
    session,
    seed_owner,
) -> None:
    expense = await _expense(session, seed_owner)
    image = _png_bytes()
    uploaded = await client.post(
        f"/api/v1/finance/expenses/{expense.id}/receipts",
        headers=_headers(seed_owner, key=f"receipt:{uuid4()}"),
        data={"source": "file"},
        files={"file": ("guard.png", image, "image/png")},
    )
    assert uploaded.status_code == 201, uploaded.text
    receipt_id = UUID(uploaded.json()["id"])

    with pytest.raises(DBAPIError, match="immutable"):
        async with session.begin_nested():
            await session.execute(
                text(
                    "UPDATE expense_receipts SET original_filename = 'changed.png' "
                    "WHERE id = :receipt_id"
                ),
                {"receipt_id": receipt_id},
            )
    with pytest.raises(DBAPIError, match="immutable"):
        async with session.begin_nested():
            await session.execute(
                text("DELETE FROM expense_receipt_reviews WHERE expense_id = :expense_id"),
                {"expense_id": expense.id},
            )

    other_company = Company(id=uuid4(), name=f"Other receipt tenant {uuid4().hex[:8]}")
    session.add(other_company)
    await session.flush()
    insert = text(
        """
        INSERT INTO expense_receipts (
            id, company_id, expense_id, uploader_user_id, original_filename,
            content_type, size_bytes, sha256, source, payload, created_at
        ) VALUES (
            :id, :company_id, :expense_id, :uploader_user_id, 'cross-tenant.png',
            'image/png', :size_bytes, :sha256, 'file', :payload, now()
        )
        """
    )
    with pytest.raises(DBAPIError, match="same company"):
        async with session.begin_nested():
            await session.execute(
                insert,
                {
                    "id": uuid4(),
                    "company_id": other_company.id,
                    "expense_id": expense.id,
                    "uploader_user_id": seed_owner["owner"].id,
                    "size_bytes": len(image),
                    "sha256": hashlib.sha256(image).hexdigest(),
                    "payload": image,
                },
            )

    wrong_digest = "0" * 64
    with pytest.raises(DBAPIError, match="payload_integrity"):
        async with session.begin_nested():
            await session.execute(
                insert,
                {
                    "id": uuid4(),
                    "company_id": seed_owner["company"].id,
                    "expense_id": expense.id,
                    "uploader_user_id": seed_owner["owner"].id,
                    "size_bytes": len(image),
                    "sha256": wrong_digest,
                    "payload": image,
                },
            )
