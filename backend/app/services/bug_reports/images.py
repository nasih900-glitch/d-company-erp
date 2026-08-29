"""Bounded decoding and canonicalisation for private Support screenshots."""

from __future__ import annotations

import hashlib
import math
import warnings
from dataclasses import dataclass
from io import BytesIO

from PIL import Image, ImageOps, UnidentifiedImageError

MAX_BUG_REPORT_IMAGE_BYTES = 2 * 1024 * 1024
MAX_BUG_REPORT_IMAGE_EDGE = 8_192
MAX_BUG_REPORT_IMAGE_PIXELS = 20_000_000
MAX_CANONICAL_IMAGE_EDGE = 4_096
MAX_CANONICAL_IMAGE_PIXELS = 12_000_000

_FORMAT_TO_CONTENT_TYPE = {
    "JPEG": "image/jpeg",
    "PNG": "image/png",
    "WEBP": "image/webp",
}

# Pillow warns before decoding an image above this threshold and raises above
# twice it. The explicit check below applies the exact threshold as a hard cap.
Image.MAX_IMAGE_PIXELS = MAX_BUG_REPORT_IMAGE_PIXELS


class BugReportImageError(ValueError):
    """A selected file cannot be retained as a safe Support screenshot."""


@dataclass(frozen=True, slots=True)
class SanitizedBugReportImage:
    content_type: str
    payload: bytes
    sha256: str


def _validate_dimensions(width: int, height: int) -> None:
    pixels = width * height
    if (
        width < 1
        or height < 1
        or width > MAX_BUG_REPORT_IMAGE_EDGE
        or height > MAX_BUG_REPORT_IMAGE_EDGE
        or pixels > MAX_BUG_REPORT_IMAGE_PIXELS
    ):
        raise BugReportImageError(
            "The screenshot dimensions are too large. Crop or resize it, then try again."
        )


def _clean_pixel_copy(image: Image.Image, *, image_format: str) -> Image.Image:
    has_alpha = "A" in image.getbands() or "transparency" in image.info
    if image_format == "PNG" and image.mode == "L" and not has_alpha:
        target_mode = "L"
    elif image_format != "JPEG" and has_alpha:
        target_mode = "RGBA"
    else:
        target_mode = "RGB"

    converted = image.convert(target_mode)
    try:
        clean = Image.new(target_mode, converted.size)
        clean.paste(converted)
        clean.info.clear()
        return clean
    finally:
        converted.close()


def _resize_to_canonical_bounds(image: Image.Image) -> Image.Image:
    width, height = image.size
    scale = min(
        1.0,
        MAX_CANONICAL_IMAGE_EDGE / max(width, height),
        math.sqrt(MAX_CANONICAL_IMAGE_PIXELS / (width * height)),
    )
    if scale >= 1.0:
        return image
    return image.resize(
        (max(1, int(width * scale)), max(1, int(height * scale))),
        Image.Resampling.LANCZOS,
    )


def _encode(image: Image.Image, *, image_format: str) -> bytes:
    output = BytesIO()
    if image_format == "PNG":
        image.save(output, format="PNG", compress_level=9, optimize=False)
    elif image_format == "JPEG":
        image.save(
            output,
            format="JPEG",
            quality=85,
            optimize=True,
            progressive=False,
            subsampling=2,
        )
    else:
        image.save(output, format="WEBP", quality=85, method=4, lossless=False)
    return output.getvalue()


def _encode_within_limit(image: Image.Image, *, image_format: str) -> bytes:
    current = _resize_to_canonical_bounds(image)
    owns_current = current is not image
    try:
        for _ in range(8):
            encoded = _encode(current, image_format=image_format)
            if 0 < len(encoded) <= MAX_BUG_REPORT_IMAGE_BYTES:
                return encoded

            width, height = current.size
            ratio = min(
                0.85,
                math.sqrt(MAX_BUG_REPORT_IMAGE_BYTES / max(1, len(encoded))) * 0.95,
            )
            next_size = (max(1, int(width * ratio)), max(1, int(height * ratio)))
            if next_size == current.size:
                break
            resized = current.resize(next_size, Image.Resampling.LANCZOS)
            if owns_current:
                current.close()
            current = resized
            owns_current = True
    finally:
        if owns_current:
            current.close()

    raise BugReportImageError(
        "The screenshot could not be reduced below 2 MB. Crop it, then try again."
    )


def sanitize_bug_report_image(
    body: bytes,
    claimed_content_type: str | None,
) -> SanitizedBugReportImage:
    """Decode one static image and return metadata-free canonical bytes."""
    if not body:
        raise BugReportImageError("The selected screenshot is empty.")
    if len(body) > MAX_BUG_REPORT_IMAGE_BYTES:
        raise BugReportImageError("The screenshot exceeds the 2 MB limit.")

    claimed = (claimed_content_type or "").split(";", 1)[0].strip().lower()
    invalid_message = "Attach a valid PNG, JPEG, or WebP screenshot."
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("error", Image.DecompressionBombWarning)
            with Image.open(BytesIO(body)) as probe:
                image_format = (probe.format or "").upper()
                detected = _FORMAT_TO_CONTENT_TYPE.get(image_format)
                if detected is None or claimed != detected:
                    raise BugReportImageError(invalid_message)
                if getattr(probe, "n_frames", 1) != 1:
                    raise BugReportImageError(
                        "Animated images cannot be attached. Choose a single screenshot."
                    )
                _validate_dimensions(*probe.size)
                probe.verify()

            with Image.open(BytesIO(body)) as decoded:
                if (decoded.format or "").upper() != image_format:
                    raise BugReportImageError(invalid_message)
                _validate_dimensions(*decoded.size)
                decoded.load()
                oriented = ImageOps.exif_transpose(decoded)
                try:
                    clean = _clean_pixel_copy(oriented, image_format=image_format)
                finally:
                    if oriented is not decoded:
                        oriented.close()
    except BugReportImageError:
        raise
    except (
        Image.DecompressionBombError,
        Image.DecompressionBombWarning,
        MemoryError,
        OSError,
        SyntaxError,
        UnidentifiedImageError,
        ValueError,
    ) as exc:
        raise BugReportImageError(invalid_message) from exc

    try:
        payload = _encode_within_limit(clean, image_format=image_format)
    except BugReportImageError:
        raise
    except (MemoryError, OSError, ValueError) as exc:
        raise BugReportImageError(invalid_message) from exc
    finally:
        clean.close()

    return SanitizedBugReportImage(
        content_type=detected,
        payload=payload,
        sha256=hashlib.sha256(payload).hexdigest(),
    )


__all__ = [
    "BugReportImageError",
    "MAX_BUG_REPORT_IMAGE_BYTES",
    "MAX_BUG_REPORT_IMAGE_EDGE",
    "MAX_BUG_REPORT_IMAGE_PIXELS",
    "SanitizedBugReportImage",
    "sanitize_bug_report_image",
]
