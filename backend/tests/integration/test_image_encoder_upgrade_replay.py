"""A safe encoder upgrade may change bytes, never a submitted operation's identity."""
from __future__ import annotations

import hashlib
from io import BytesIO
from uuid import UUID, uuid4

import pytest
from PIL import Image, PngImagePlugin
from sqlalchemy import select
from sqlalchemy.orm import undefer

from app.api.v1.bug_reports import router as bug_router
from app.models import BugReportAttachment
from app.services.bug_reports.images import SanitizedBugReportImage
from tests.integration.test_bug_report_inbox import (
    _image_bytes,
    _report_payload,
    _staff_headers,
)


@pytest.mark.integration
@pytest.mark.asyncio
async def test_original_upload_replays_after_encoder_changes_canonical_bytes(
    client, session, seed_owner, monkeypatch
):
    _, headers = await _staff_headers(session, seed_owner)
    created = await client.post(
        "/api/v1/bug-reports",
        headers={**headers, "Idempotency-Key": f"encoder-report-{uuid4()}"},
        json=_report_payload(seed_owner),
    )
    assert created.status_code == 201, created.text
    report_id = created.json()["id"]
    source = _image_bytes("PNG")
    key = f"encoder-attachment-{uuid4()}"
    path = f"/api/v1/bug-reports/mine/{report_id}/attachments"
    upload = await client.post(
        path, headers={**headers, "Idempotency-Key": key},
        files={"file": ("screen.png", source, "image/png")},
    )
    assert upload.status_code == 201, upload.text
    original = upload.json()
    real_sanitize = bug_router.sanitize_bug_report_image

    def upgraded_encoder(body, claimed):
        clean = real_sanitize(body, claimed)
        output = BytesIO()
        with Image.open(BytesIO(clean.payload)) as image:
            image.save(output, format="PNG", compress_level=0, optimize=False)
        payload = output.getvalue()
        assert payload != clean.payload
        return SanitizedBugReportImage(clean.content_type, payload, hashlib.sha256(payload).hexdigest())

    monkeypatch.setattr(bug_router, "sanitize_bug_report_image", upgraded_encoder)
    replay = await client.post(
        path, headers={**headers, "Idempotency-Key": key},
        files={"file": ("screen.png", source, "image/png")},
    )
    assert replay.status_code == 201, replay.text
    assert replay.json() == original
    retained = (await session.execute(select(BugReportAttachment).options(undefer(BugReportAttachment.payload)).where(
        BugReportAttachment.company_id == seed_owner["company"].id,
        BugReportAttachment.bug_report_id == UUID(report_id),
    ))).scalars().all()
    assert len(retained) == 1
    assert retained[0].sha256 == original["sha256"]
    assert hashlib.sha256(retained[0].payload).hexdigest() == original["sha256"]

    # Pixel-equivalent but different original bytes still conflict: an encoder
    # change cannot broaden the user's approved idempotent operation.
    changed = BytesIO()
    metadata = PngImagePlugin.PngInfo()
    metadata.add_text("Description", "different original upload")
    with Image.open(BytesIO(source)) as image:
        image.save(changed, format="PNG", pnginfo=metadata)
    assert changed.getvalue() != source
    conflict = await client.post(
        path, headers={**headers, "Idempotency-Key": key},
        files={"file": ("screen.png", changed.getvalue(), "image/png")},
    )
    assert conflict.status_code == 409, conflict.text
