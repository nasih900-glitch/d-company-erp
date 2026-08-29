from __future__ import annotations

import hashlib
from io import BytesIO

import pytest
from PIL import Image

from app.services.bug_reports import images
from app.services.bug_reports.images import (
    MAX_BUG_REPORT_IMAGE_BYTES,
    BugReportImageError,
    sanitize_bug_report_image,
)


def _image_bytes(image_format: str, *, size: tuple[int, int] = (12, 8)) -> bytes:
    mode = "RGBA" if image_format in {"PNG", "WEBP"} else "RGB"
    color = (15, 75, 135, 180) if mode == "RGBA" else (15, 75, 135)
    image = Image.new(mode, size, color)
    exif = Image.Exif()
    exif[0x010E] = "private support metadata"
    output = BytesIO()
    image.save(output, format=image_format, exif=exif)
    image.close()
    return output.getvalue()


@pytest.mark.parametrize(
    ("image_format", "content_type"),
    [("PNG", "image/png"), ("JPEG", "image/jpeg"), ("WEBP", "image/webp")],
)
def test_valid_images_are_decoded_stripped_and_canonically_reencoded(
    image_format: str,
    content_type: str,
) -> None:
    source = _image_bytes(image_format)
    with Image.open(BytesIO(source)) as original:
        assert original.getexif()

    result = sanitize_bug_report_image(source, content_type)

    assert result.content_type == content_type
    assert 0 < len(result.payload) <= MAX_BUG_REPORT_IMAGE_BYTES
    assert result.sha256 == hashlib.sha256(result.payload).hexdigest()
    with Image.open(BytesIO(result.payload)) as canonical:
        canonical.load()
        assert canonical.format == image_format
        assert canonical.size == (12, 8)
        assert not canonical.getexif()
        assert "exif" not in canonical.info
        assert "icc_profile" not in canonical.info


@pytest.mark.parametrize(
    ("content_type", "payload"),
    [
        ("image/jpeg", b"\xff\xd8\xffnot-a-jpeg"),
        ("image/png", b"\x89PNG\r\n\x1a\nnot-a-png"),
        ("image/webp", b"RIFFxxxxWEBPnot-a-webp"),
    ],
)
def test_magic_prefix_without_a_decodable_image_is_rejected(
    content_type: str,
    payload: bytes,
) -> None:
    with pytest.raises(BugReportImageError, match="valid PNG, JPEG, or WebP"):
        sanitize_bug_report_image(payload, content_type)


def test_claimed_type_must_match_the_decoded_format() -> None:
    with pytest.raises(BugReportImageError, match="valid PNG, JPEG, or WebP"):
        sanitize_bug_report_image(_image_bytes("PNG"), "image/jpeg")


def test_dimension_cap_is_checked_before_full_pixel_decode(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(images, "MAX_BUG_REPORT_IMAGE_EDGE", 4)

    with pytest.raises(BugReportImageError, match="dimensions are too large"):
        sanitize_bug_report_image(_image_bytes("PNG", size=(5, 1)), "image/png")


def test_canonical_encoding_never_exceeds_the_storage_cap() -> None:
    noisy = Image.effect_noise((2_400, 2_400), 80).convert("RGB")
    source_buffer = BytesIO()
    noisy.save(source_buffer, format="JPEG", quality=18)
    noisy.close()
    source = source_buffer.getvalue()
    assert len(source) <= MAX_BUG_REPORT_IMAGE_BYTES

    result = sanitize_bug_report_image(source, "image/jpeg")

    assert 0 < len(result.payload) <= MAX_BUG_REPORT_IMAGE_BYTES
    with Image.open(BytesIO(result.payload)) as canonical:
        canonical.verify()
