#!/usr/bin/env python3
"""Materialize one reviewed zlib VEX document for one exact image."""

from __future__ import annotations

import json
import os
from pathlib import Path
import re
import sys
from typing import Any


CVE = "CVE-2026-85091"
PACKAGE_PURL = "pkg:apk/alpine/zlib@1.3.2-r0"
PRODUCT_PLACEHOLDER = "__D_COMPANY_IMAGE_PRODUCT__"
IMAGE_ID_PLACEHOLDER = "__D_COMPANY_IMAGE_ID__"
IMAGE_ID_HEX_PLACEHOLDER = "__D_COMPANY_IMAGE_ID_HEX__"
IMAGE_PRODUCT = re.compile(r"[A-Za-z0-9][A-Za-z0-9._/:@-]{0,254}")
IMAGE_ID = re.compile(r"sha256:[0-9a-f]{64}")


def load_template(path: Path) -> dict[str, Any]:
    try:
        raw = path.read_text(encoding="utf-8")
        value = json.loads(raw)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise SystemExit(f"Invalid zlib VEX template {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise SystemExit("zlib VEX template must be a JSON object")
    expected_placeholders = {
        PRODUCT_PLACEHOLDER: 1,
        IMAGE_ID_PLACEHOLDER: 1,
        IMAGE_ID_HEX_PLACEHOLDER: 1,
    }
    for placeholder, expected_count in expected_placeholders.items():
        if raw.count(placeholder) != expected_count:
            raise SystemExit(
                f"zlib VEX template must contain exactly one {placeholder} placeholder"
            )
    statements = value.get("statements")
    if not isinstance(statements, list) or len(statements) != 1:
        raise SystemExit("zlib VEX template must contain exactly one statement")
    statement = statements[0]
    expected_products = [
        {
            "@id": PRODUCT_PLACEHOLDER,
            "subcomponents": [{"@id": PACKAGE_PURL}],
        }
    ]
    if not isinstance(statement, dict) or statement.get("products") != expected_products:
        raise SystemExit("zlib VEX template has an unexpected product scope")
    if statement.get("vulnerability") != {"name": CVE}:
        raise SystemExit("zlib VEX template is not bound to the reviewed CVE")
    if statement.get("status") != "fixed":
        raise SystemExit("zlib VEX template must use fixed status")
    return value


def create_exclusive(path: Path, payload: bytes) -> None:
    if path.parent.resolve(strict=True) != path.parent or path.is_symlink():
        raise SystemExit("zlib VEX output must use an existing canonical directory")
    # Grype runs as the fixed non-root scanner UID and receives this file by a
    # read-only bind mount. The parent evidence directory remains root-only;
    # the non-secret document itself must be world-readable inside the mount.
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o444)
    try:
        # The production installer deliberately runs with umask 077. Set the
        # reviewed public-read mode on the already opened, no-follow descriptor
        # so Grype's unprivileged UID can read the bind-mounted VEX document.
        os.fchmod(descriptor, 0o444)
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
    except BaseException:
        try:
            os.close(descriptor)
        except OSError:
            pass
        try:
            path.unlink()
        except OSError:
            pass
        raise


def main(arguments: list[str]) -> int:
    if len(arguments) != 5:
        raise SystemExit(
            f"Usage: {arguments[0]} TEMPLATE IMAGE_PRODUCT IMAGE_ID OUTPUT"
        )
    template_path = Path(arguments[1]).resolve(strict=True)
    product = arguments[2]
    image_id = arguments[3]
    output_path = Path(arguments[4]).absolute()
    image_name = product.rsplit("/", 1)[-1]
    if (
        IMAGE_PRODUCT.fullmatch(product) is None
        or "@" in product
        or ":" not in image_name
        or image_name.endswith(":")
    ):
        raise SystemExit("Image product must be one exact, tag-qualified image reference")
    if IMAGE_ID.fullmatch(image_id) is None:
        raise SystemExit("Image ID must be an immutable sha256 identity")
    if output_path.exists() or output_path.is_symlink():
        raise SystemExit("Refusing to overwrite zlib VEX output")

    document = load_template(template_path)
    statement = document["statements"][0]
    document["@id"] = str(document["@id"]).replace(
        IMAGE_ID_HEX_PLACEHOLDER, image_id.removeprefix("sha256:")
    )
    statement["products"][0]["@id"] = product
    statement["status_notes"] = str(statement["status_notes"]).replace(
        IMAGE_ID_PLACEHOLDER, image_id
    )
    payload = (json.dumps(document, indent=2, ensure_ascii=True) + "\n").encode("utf-8")
    if b"__D_COMPANY_" in payload:
        raise SystemExit("Rendered zlib VEX still contains a template placeholder")
    create_exclusive(output_path, payload)
    print(f"zlib_vex_product={product}")
    print(f"zlib_vex_image_id={image_id}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
