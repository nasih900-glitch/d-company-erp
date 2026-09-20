#!/usr/bin/env python3
"""Verify one Docker-save identity chain in the supported plain-USTAR subset.

The outer archive deliberately permits only uncompressed USTAR regular files and
directories. PAX, GNU extensions, sparse records, links, and other tar formats
are rejected before a general tar parser sees them.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
import re
import stat
import sys
import tarfile
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import BinaryIO, NoReturn


SHA256_RE = re.compile(r"sha256:[0-9a-f]{64}\Z")
SERVICE_RE = re.compile(r"[a-z][a-z0-9-]{0,31}\Z")

OCI_INDEX = "application/vnd.oci.image.index.v1+json"
OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json"
OCI_CONFIG = "application/vnd.oci.image.config.v1+json"
OCI_LAYER = "application/vnd.oci.image.layer.v1.tar"
OCI_LAYER_GZIP = "application/vnd.oci.image.layer.v1.tar+gzip"
OCI_NONDISTRIBUTABLE_LAYER = "application/vnd.oci.image.layer.nondistributable.v1.tar"
OCI_NONDISTRIBUTABLE_LAYER_GZIP = (
    "application/vnd.oci.image.layer.nondistributable.v1.tar+gzip"
)
DOCKER_LAYER_GZIP = "application/vnd.docker.image.rootfs.diff.tar.gzip"
DOCKER_FOREIGN_LAYER_GZIP = "application/vnd.docker.image.rootfs.foreign.diff.tar.gzip"
IN_TOTO = "application/vnd.in-toto+json"

GZIP_LAYER_MEDIA_TYPES = {
    OCI_LAYER_GZIP,
    OCI_NONDISTRIBUTABLE_LAYER_GZIP,
    DOCKER_LAYER_GZIP,
    DOCKER_FOREIGN_LAYER_GZIP,
}
PLAIN_LAYER_MEDIA_TYPES = {OCI_LAYER, OCI_NONDISTRIBUTABLE_LAYER}

MAX_MEMBERS = 4096
MAX_MEMBER_NAME_BYTES = 512
MAX_JSON_BYTES = 16 * 1024 * 1024
MAX_DESCRIPTOR_BYTES = 16 * 1024 * 1024 * 1024
MAX_LAYER_UNCOMPRESSED_BYTES = 8 * 1024 * 1024 * 1024
MAX_TOTAL_UNCOMPRESSED_BYTES = 32 * 1024 * 1024 * 1024
MAX_LAYERS = 256
MAX_JSON_NESTING = 64
LINUX_ADDRESS_SPACE_BYTES = 512 * 1024 * 1024
TAR_BLOCK_BYTES = 512
SUPPORTED_TAR_TYPES = {b"\0", b"0", b"5"}
REJECTED_TAR_EXTENSION_TYPES = {b"x", b"g", b"L", b"K", b"S"}


class VerificationError(Exception):
    """The archive cannot establish the requested runtime/config identity."""


@dataclass(frozen=True)
class Result:
    archive_digest: str
    runtime_id: str
    runtime_id_type: str
    manifest_digest: str | None
    config_id: str
    platform: str


def _fail(message: str) -> NoReturn:
    raise VerificationError(message)


def _duplicate_key_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            _fail(f"duplicate JSON key: {key!r}")
        result[key] = value
    return result


def _reject_constant(value: str) -> NoReturn:
    _fail(f"unsupported JSON numeric constant: {value}")


def _check_json_nesting(text: str, label: str) -> None:
    depth = 0
    in_string = False
    escaped = False
    for character in text:
        if in_string:
            if escaped:
                escaped = False
            elif character == "\\":
                escaped = True
            elif character == '"':
                in_string = False
            continue
        if character == '"':
            in_string = True
        elif character in "[{":
            depth += 1
            if depth > MAX_JSON_NESTING:
                _fail(f"{label} exceeds the JSON nesting limit")
        elif character in "]}":
            depth -= 1


def _load_json(data: bytes, label: str) -> object:
    try:
        text = data.decode("utf-8")
        _check_json_nesting(text, label)
        return json.loads(
            text,
            object_pairs_hook=_duplicate_key_object,
            parse_constant=_reject_constant,
        )
    except VerificationError:
        raise
    except (
        UnicodeDecodeError,
        json.JSONDecodeError,
        ValueError,
        RecursionError,
    ) as exc:
        _fail(f"malformed {label} JSON: {exc}")


def _expect_object(value: object, label: str) -> dict[str, object]:
    if not isinstance(value, dict):
        _fail(f"{label} must be a JSON object")
    return value


def _expect_list(value: object, label: str) -> list[object]:
    if not isinstance(value, list):
        _fail(f"{label} must be a JSON array")
    return value


def _digest(value: object, label: str) -> str:
    if not isinstance(value, str) or SHA256_RE.fullmatch(value) is None:
        _fail(f"{label} must use a lowercase sha256 digest")
    return value


def _descriptor(
    value: object,
    label: str,
    allowed_media_types: set[str],
) -> dict[str, object]:
    descriptor = _expect_object(value, label)
    media_type = descriptor.get("mediaType")
    if not isinstance(media_type, str) or media_type not in allowed_media_types:
        _fail(f"{label} uses unsupported media type: {media_type!r}")
    _digest(descriptor.get("digest"), f"{label} digest")
    size = descriptor.get("size")
    if (
        isinstance(size, bool)
        or not isinstance(size, int)
        or not 0 <= size <= MAX_DESCRIPTOR_BYTES
    ):
        _fail(f"{label} has an invalid or oversized descriptor size")
    return descriptor


def _canonical_member_name(name: str) -> str:
    try:
        encoded_name = name.encode("utf-8")
    except UnicodeEncodeError:
        _fail("archive member has a non-UTF-8 name")
    if not name or "\x00" in name or len(encoded_name) > MAX_MEMBER_NAME_BYTES:
        _fail("archive member has an invalid or oversized name")
    path = PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts:
        _fail(f"archive member path is unsafe: {name!r}")
    parts = tuple(part for part in path.parts if part not in ("", "."))
    if not parts:
        _fail(f"archive member path is invalid: {name!r}")
    return "/".join(parts)


def _sha256_stream(stream: BinaryIO, limit: int, label: str) -> tuple[str, int]:
    digest = hashlib.sha256()
    total = 0
    while True:
        chunk = stream.read(1024 * 1024)
        if not chunk:
            break
        total += len(chunk)
        if total > limit:
            _fail(f"{label} exceeds the verification byte limit")
        digest.update(chunk)
    return "sha256:" + digest.hexdigest(), total


def _tar_string(field: bytes, label: str) -> str:
    value, separator, padding = field.partition(b"\0")
    if separator and any(padding):
        _fail(f"raw tar {label} has nonzero bytes after its terminator")
    try:
        return value.decode("utf-8")
    except UnicodeDecodeError:
        _fail(f"raw tar {label} is not UTF-8")


def _tar_octal(field: bytes, label: str) -> int:
    if field and field[0] & 0x80:
        _fail(f"raw tar {label} uses unsupported base-256 encoding")
    value = field.rstrip(b"\0 ").lstrip(b" ")
    if b"\0" in value or any(character not in b"01234567" for character in value):
        _fail(f"raw tar {label} is not a canonical octal value")
    return int(value or b"0", 8)


def _preflight_uncompressed_tar(file_object: BinaryIO, file_size: int) -> None:
    """Validate the deliberately supported plain-USTAR subset without payload reads."""
    if file_size < 2 * TAR_BLOCK_BYTES or file_size % TAR_BLOCK_BYTES != 0:
        _fail("raw tar size is not a complete 512-byte archive")
    file_object.seek(0)
    offset = 0
    member_count = 0
    zero_blocks = 0
    while offset < file_size:
        header = file_object.read(TAR_BLOCK_BYTES)
        if len(header) != TAR_BLOCK_BYTES:
            _fail("raw tar header is truncated")
        offset += TAR_BLOCK_BYTES
        if header == b"\0" * TAR_BLOCK_BYTES:
            zero_blocks += 1
            if zero_blocks < 2:
                continue
            while offset < file_size:
                trailing = file_object.read(min(1024 * 1024, file_size - offset))
                if not trailing or any(trailing):
                    _fail("raw tar has nonzero data after its end markers")
                offset += len(trailing)
            file_object.seek(0)
            return
        if zero_blocks:
            _fail("raw tar has only one end marker before another header")

        stored_checksum = _tar_octal(header[148:156], "checksum")
        calculated_checksum = sum(header[:148]) + (8 * ord(" ")) + sum(header[156:])
        if stored_checksum != calculated_checksum:
            _fail("raw tar header checksum mismatch")
        if header[257:265] != b"ustar\0" + b"00":
            _fail("raw tar is outside the supported USTAR subset")

        type_flag = header[156:157]
        if type_flag in REJECTED_TAR_EXTENSION_TYPES:
            _fail("raw tar extensions and sparse records are not supported")
        if type_flag not in SUPPORTED_TAR_TYPES:
            _fail(f"raw tar member type is unsupported: {type_flag!r}")
        prefix = _tar_string(header[345:500], "member prefix")
        name = _tar_string(header[0:100], "member name")
        _canonical_member_name(f"{prefix}/{name}" if prefix else name)
        size = _tar_octal(header[124:136], "member size")
        if size > MAX_DESCRIPTOR_BYTES:
            _fail("raw tar member is oversized")
        if type_flag == b"5" and size != 0:
            _fail("raw tar directory unexpectedly contains data")
        padded_size = (
            (size + TAR_BLOCK_BYTES - 1) // TAR_BLOCK_BYTES
        ) * TAR_BLOCK_BYTES
        if offset + padded_size > file_size:
            _fail("raw tar member extent exceeds the opened file")
        padding_size = padded_size - size
        if padding_size:
            file_object.seek(offset + size)
            padding = file_object.read(padding_size)
            if len(padding) != padding_size or any(padding):
                _fail("raw tar member has invalid data padding")
        offset += padded_size
        file_object.seek(offset)
        member_count += 1
        if member_count > MAX_MEMBERS:
            _fail("raw tar contains too many members")
    _fail("raw tar is missing its two end markers")


def _apply_linux_memory_ceiling() -> None:
    if not sys.platform.startswith("linux"):
        return
    try:
        import resource

        soft, hard = resource.getrlimit(resource.RLIMIT_AS)
        finite_limits = [LINUX_ADDRESS_SPACE_BYTES]
        if soft != resource.RLIM_INFINITY:
            finite_limits.append(soft)
        if hard != resource.RLIM_INFINITY:
            finite_limits.append(hard)
        target = min(finite_limits)
        resource.setrlimit(resource.RLIMIT_AS, (target, hard))
    except (ImportError, OSError, ValueError) as exc:
        _fail(f"cannot apply the Linux verifier memory ceiling: {exc}")


class ArchiveVerifier:
    def __init__(
        self, file_object: BinaryIO, runtime_id: str, archive_digest: str
    ) -> None:
        self.file_object = file_object
        self.runtime_id = runtime_id
        self.archive_digest = archive_digest
        self.tar: tarfile.TarFile | None = None
        self.members: dict[str, tarfile.TarInfo] = {}
        self.total_uncompressed = 0

    def verify(self) -> Result:
        self.file_object.seek(0)
        try:
            self.tar = tarfile.open(fileobj=self.file_object, mode="r:")
            self._index_members()
            compatibility = self._compatibility_manifest()
            config_path = self._member_path(
                compatibility.get("Config"), "manifest Config"
            )
            config_bytes = self._read(config_path, MAX_JSON_BYTES, "image config")
            config_id = "sha256:" + hashlib.sha256(config_bytes).hexdigest()
            self._verify_config_path(config_path, config_id)
            config = _expect_object(
                _load_json(config_bytes, "image config"), "image config"
            )
            platform, diff_ids = self._config_identity(config, "image config")
            compatibility_layers = self._compatibility_layers(compatibility, diff_ids)

            if self.runtime_id == config_id:
                manifest_digest = self._verify_optional_classic_oci(
                    config_id, config_bytes, platform, diff_ids
                )
                runtime_type = "config"
            else:
                manifest_digest = self._verify_index_root(
                    config_id,
                    config_bytes,
                    platform,
                    diff_ids,
                    compatibility_layers,
                )
                runtime_type = "oci-index"

            return Result(
                archive_digest=self.archive_digest,
                runtime_id=self.runtime_id,
                runtime_id_type=runtime_type,
                manifest_digest=manifest_digest,
                config_id=config_id,
                platform=platform,
            )
        except (tarfile.TarError, OSError, EOFError, TypeError) as exc:
            _fail(f"invalid image archive: {exc}")
        finally:
            if self.tar is not None:
                self.tar.close()

    def _index_members(self) -> None:
        if self.tar is None:
            _fail("archive parser was not initialized")
        for count, member in enumerate(self.tar, start=1):
            if count > MAX_MEMBERS:
                _fail("image archive contains too many members")
            name = _canonical_member_name(member.name)
            if name in self.members:
                _fail(f"duplicate archive member: {name}")
            if member.issym() or member.islnk():
                _fail(f"archive data links are not supported: {name}")
            if not (member.isdir() or member.isreg()):
                _fail(f"unsupported archive member type: {name}")
            if member.size < 0 or member.size > MAX_DESCRIPTOR_BYTES:
                _fail(f"archive member is oversized: {name}")
            self.members[name] = member

    def _read(self, name: str, limit: int, label: str) -> bytes:
        member = self.members.get(name)
        if member is None or not member.isreg():
            _fail(f"missing regular {label} member: {name}")
        if member.size > limit:
            _fail(f"{label} exceeds the verification byte limit")
        if self.tar is None:
            _fail("archive parser was not initialized")
        stream = self.tar.extractfile(member)
        if stream is None:
            _fail(f"cannot read {label} member: {name}")
        data = stream.read(limit + 1)
        if len(data) != member.size or len(data) > limit:
            _fail(f"{label} member size is inconsistent: {name}")
        return data

    def _stream(self, name: str, label: str) -> BinaryIO:
        member = self.members.get(name)
        if member is None or not member.isreg():
            _fail(f"missing regular {label} member: {name}")
        if self.tar is None:
            _fail("archive parser was not initialized")
        stream = self.tar.extractfile(member)
        if stream is None:
            _fail(f"cannot read {label} member: {name}")
        return stream

    def _member_path(self, value: object, label: str) -> str:
        if not isinstance(value, str):
            _fail(f"{label} must be an archive member path")
        return _canonical_member_name(value)

    def _compatibility_manifest(self) -> dict[str, object]:
        data = self._read(
            "manifest.json", MAX_JSON_BYTES, "Docker compatibility manifest"
        )
        manifest = _expect_list(
            _load_json(data, "Docker compatibility manifest"), "manifest.json"
        )
        if len(manifest) != 1:
            _fail("Docker compatibility manifest must describe exactly one image")
        return _expect_object(manifest[0], "Docker compatibility manifest entry")

    def _verify_config_path(self, path: str, config_id: str) -> None:
        encoded = config_id.removeprefix("sha256:")
        if path not in {f"blobs/sha256/{encoded}", f"{encoded}.json"}:
            _fail("Docker compatibility Config path does not match its content digest")

    def _config_identity(
        self, config: dict[str, object], label: str
    ) -> tuple[str, list[str]]:
        architecture = config.get("architecture")
        operating_system = config.get("os")
        if architecture != "amd64" or operating_system != "linux":
            _fail(f"{label} platform must be linux/amd64")
        rootfs = _expect_object(config.get("rootfs"), f"{label} rootfs")
        if rootfs.get("type") != "layers":
            _fail(f"{label} rootfs type must be layers")
        raw_diff_ids = _expect_list(rootfs.get("diff_ids"), f"{label} rootfs diff_ids")
        if not 1 <= len(raw_diff_ids) <= MAX_LAYERS:
            _fail(f"{label} must contain between 1 and {MAX_LAYERS} rootfs layers")
        return "linux/amd64", [
            _digest(value, f"{label} diff_id") for value in raw_diff_ids
        ]

    def _compatibility_layers(
        self, manifest: dict[str, object], diff_ids: list[str]
    ) -> list[str]:
        raw_layers = _expect_list(manifest.get("Layers"), "Docker compatibility Layers")
        if len(raw_layers) != len(diff_ids):
            _fail("Docker compatibility Layers disagree with config rootfs diff_ids")
        layers = [
            self._member_path(value, "compatibility layer") for value in raw_layers
        ]
        for position, (path, expected_diff_id) in enumerate(
            zip(layers, diff_ids, strict=True)
        ):
            actual_diff_id = self._layer_diff_id(
                path, None, f"compatibility layer {position}"
            )
            if actual_diff_id != expected_diff_id:
                _fail(
                    f"Docker compatibility layer {position} does not match config diff_id"
                )
        return layers

    def _layer_diff_id(self, path: str, media_type: str | None, label: str) -> str:
        stream = self._stream(path, label)
        if media_type in GZIP_LAYER_MEDIA_TYPES:
            compressed = True
        elif media_type in PLAIN_LAYER_MEDIA_TYPES:
            compressed = False
        elif media_type is None:
            prefix = stream.read(2)
            stream.seek(0)
            compressed = prefix == b"\x1f\x8b"
        else:
            _fail(f"{label} uses unsupported media type: {media_type!r}")
        try:
            data_stream: BinaryIO
            data_stream = gzip.GzipFile(fileobj=stream) if compressed else stream
            digest, size = _sha256_stream(
                data_stream, MAX_LAYER_UNCOMPRESSED_BYTES, f"{label} uncompressed data"
            )
            self.total_uncompressed += size
            if self.total_uncompressed > MAX_TOTAL_UNCOMPRESSED_BYTES:
                _fail("archive layers exceed the total uncompressed verification limit")
            return digest
        except (gzip.BadGzipFile, EOFError, OSError) as exc:
            _fail(f"cannot decompress {label}: {exc}")
        finally:
            stream.close()

    def _blob_path(self, digest: str) -> str:
        return "blobs/sha256/" + digest.removeprefix("sha256:")

    def _verify_descriptor_blob(
        self,
        descriptor: dict[str, object],
        label: str,
        capture_limit: int = MAX_DESCRIPTOR_BYTES,
    ) -> tuple[str, bytes | None]:
        digest = _digest(descriptor.get("digest"), f"{label} digest")
        path = self._blob_path(digest)
        member = self.members.get(path)
        if member is None or not member.isreg():
            _fail(f"missing descriptor blob for {label}: {digest}")
        expected_size = descriptor.get("size")
        if isinstance(expected_size, bool) or not isinstance(expected_size, int):
            _fail(f"descriptor size is invalid for {label}: {digest}")
        if member.size != expected_size:
            _fail(f"descriptor size mismatch for {label}: {digest}")
        if capture_limit > 0:
            if member.size > capture_limit:
                _fail(f"{label} exceeds the verification byte limit")
            data = self._read(path, capture_limit, label)
            actual = "sha256:" + hashlib.sha256(data).hexdigest()
            if actual != digest:
                _fail(f"descriptor digest mismatch for {label}: {digest}")
            return path, data
        stream = self._stream(path, label)
        try:
            actual, size = _sha256_stream(stream, MAX_DESCRIPTOR_BYTES, label)
        finally:
            stream.close()
        if size != expected_size or actual != digest:
            _fail(f"descriptor digest mismatch for {label}: {digest}")
        return path, None

    def _oci_header(
        self, value: object, label: str, media_type: str
    ) -> dict[str, object]:
        document = _expect_object(value, label)
        if (
            document.get("schemaVersion") != 2
            or document.get("mediaType") != media_type
        ):
            _fail(f"{label} has an unsupported schema or media type")
        return document

    def _oci_layout_index(self) -> dict[str, object]:
        layout = _expect_object(
            _load_json(
                self._read("oci-layout", MAX_JSON_BYTES, "OCI layout"), "OCI layout"
            ),
            "oci-layout",
        )
        if layout.get("imageLayoutVersion") != "1.0.0":
            _fail("OCI layout version must be 1.0.0")
        return self._oci_header(
            _load_json(
                self._read("index.json", MAX_JSON_BYTES, "OCI index"), "OCI index"
            ),
            "index.json",
            OCI_INDEX,
        )

    def _verify_optional_classic_oci(
        self,
        config_id: str,
        config_bytes: bytes,
        platform: str,
        diff_ids: list[str],
    ) -> str | None:
        present = {
            name for name in ("oci-layout", "index.json") if name in self.members
        }
        if not present:
            return None
        if present != {"oci-layout", "index.json"}:
            _fail("classic archive contains an incomplete OCI compatibility layout")
        index = self._oci_layout_index()
        descriptors = _expect_list(
            index.get("manifests"), "OCI compatibility index manifests"
        )
        if not descriptors:
            _fail("OCI compatibility index has no image manifest")
        manifest_digests: set[str] = set()
        for position, value in enumerate(descriptors):
            descriptor = _descriptor(
                value, f"OCI compatibility manifest {position}", {OCI_MANIFEST}
            )
            platform_value = descriptor.get("platform")
            if platform_value is not None:
                platform_object = _expect_object(
                    platform_value, "OCI compatibility platform"
                )
                if (
                    platform_object.get("os") != "linux"
                    or platform_object.get("architecture") != "amd64"
                ):
                    _fail("OCI compatibility manifest platform disagrees with config")
            manifest_digests.add(
                _digest(descriptor.get("digest"), "OCI compatibility manifest digest")
            )
            self._verify_descriptor_blob(
                descriptor, f"OCI compatibility manifest {position}", MAX_JSON_BYTES
            )
        if len(manifest_digests) != 1:
            _fail("OCI compatibility index contains multiple distinct image manifests")
        descriptor = _descriptor(
            descriptors[0], "OCI compatibility manifest", {OCI_MANIFEST}
        )
        _path, data = self._verify_descriptor_blob(
            descriptor, "OCI compatibility manifest", MAX_JSON_BYTES
        )
        if data is None:
            _fail("OCI compatibility manifest body was not captured")
        manifest = self._oci_header(
            _load_json(data, "OCI compatibility manifest"),
            "OCI compatibility manifest",
            OCI_MANIFEST,
        )
        self._verify_manifest_config(manifest, config_id, config_bytes, platform)
        layers = _expect_list(
            manifest.get("layers"), "OCI compatibility manifest layers"
        )
        if len(layers) != len(diff_ids):
            _fail("OCI compatibility manifest layer count disagrees with config")
        for position, value in enumerate(layers):
            descriptor = _descriptor(
                value,
                f"OCI compatibility layer {position}",
                GZIP_LAYER_MEDIA_TYPES | PLAIN_LAYER_MEDIA_TYPES,
            )
            blob = self.members.get(
                self._blob_path(_digest(descriptor.get("digest"), "layer digest"))
            )
            if blob is None:
                # Moby's graph-driver save can retain a compressed source descriptor
                # while exporting the verified compatibility layer by its diff ID.
                if descriptor.get("mediaType") not in GZIP_LAYER_MEDIA_TYPES:
                    _fail(
                        f"missing plain OCI compatibility layer blob at position {position}"
                    )
                continue
            path, _data = self._verify_descriptor_blob(
                descriptor, f"OCI compatibility layer {position}", 0
            )
            if (
                self._layer_diff_id(
                    path,
                    str(descriptor["mediaType"]),
                    f"OCI compatibility layer {position}",
                )
                != diff_ids[position]
            ):
                _fail(
                    f"OCI compatibility layer {position} disagrees with config diff_id"
                )
        return next(iter(manifest_digests))

    def _verify_manifest_config(
        self,
        manifest: dict[str, object],
        config_id: str,
        config_bytes: bytes,
        platform: str,
    ) -> None:
        descriptor = _descriptor(
            manifest.get("config"), "runnable config", {OCI_CONFIG}
        )
        if descriptor.get("digest") != config_id:
            _fail(
                "OCI runnable manifest config digest disagrees with compatibility Config"
            )
        _path, data = self._verify_descriptor_blob(
            descriptor, "runnable config", MAX_JSON_BYTES
        )
        if data is None:
            _fail("runnable config body was not captured")
        if data != config_bytes:
            _fail("OCI runnable config bytes disagree with compatibility Config")
        config = _expect_object(
            _load_json(data, "OCI runnable config"), "OCI runnable config"
        )
        actual_platform, _diff_ids = self._config_identity(
            config, "OCI runnable config"
        )
        if actual_platform != platform:
            _fail("OCI runnable config platform disagrees with compatibility Config")

    def _verify_index_root(
        self,
        config_id: str,
        config_bytes: bytes,
        platform: str,
        diff_ids: list[str],
        compatibility_layers: list[str],
    ) -> str:
        if "oci-layout" not in self.members or "index.json" not in self.members:
            _fail(
                "runtime image ID matches neither the config nor a complete OCI index"
            )
        wrapper = self._oci_layout_index()
        wrapper_descriptors = _expect_list(
            wrapper.get("manifests"), "top OCI index manifests"
        )
        if len(wrapper_descriptors) != 1:
            _fail("top OCI index must reference exactly one runtime index")
        runtime_descriptor = _descriptor(
            wrapper_descriptors[0], "runtime OCI index", {OCI_INDEX}
        )
        if runtime_descriptor.get("digest") != self.runtime_id:
            _fail("runtime image ID does not match the top OCI index descriptor")
        _path, runtime_bytes = self._verify_descriptor_blob(
            runtime_descriptor, "runtime OCI index", MAX_JSON_BYTES
        )
        if runtime_bytes is None:
            _fail("runtime OCI index body was not captured")
        runtime_index = self._oci_header(
            _load_json(runtime_bytes, "runtime OCI index"),
            "runtime OCI index",
            OCI_INDEX,
        )
        children = _expect_list(
            runtime_index.get("manifests"), "runtime OCI index manifests"
        )
        runnable: list[dict[str, object]] = []
        attestations: list[dict[str, object]] = []
        for position, value in enumerate(children):
            descriptor = _descriptor(
                value, f"runtime index child {position}", {OCI_MANIFEST}
            )
            child_platform = _expect_object(
                descriptor.get("platform"), f"runtime index child {position} platform"
            )
            os_name = child_platform.get("os")
            architecture = child_platform.get("architecture")
            if (os_name, architecture) == ("linux", "amd64"):
                runnable.append(descriptor)
            elif (os_name, architecture) == ("unknown", "unknown"):
                attestations.append(descriptor)
            else:
                _fail("runtime OCI index contains an unsupported platform")
        if len(runnable) != 1:
            _fail(
                "runtime OCI index must contain exactly one runnable linux/amd64 manifest"
            )
        if len(attestations) != 1:
            _fail("runtime OCI index must contain exactly one recognized attestation")
        runnable_descriptor = runnable[0]
        manifest_digest = _digest(
            runnable_descriptor.get("digest"), "runnable manifest digest"
        )
        _path, manifest_bytes = self._verify_descriptor_blob(
            runnable_descriptor, "runnable OCI manifest", MAX_JSON_BYTES
        )
        if manifest_bytes is None:
            _fail("runnable OCI manifest body was not captured")
        manifest = self._oci_header(
            _load_json(manifest_bytes, "runnable OCI manifest"),
            "runnable OCI manifest",
            OCI_MANIFEST,
        )
        self._verify_manifest_config(manifest, config_id, config_bytes, platform)
        layers = _expect_list(manifest.get("layers"), "runnable OCI manifest layers")
        if len(layers) != len(diff_ids):
            _fail("runnable OCI manifest layer count disagrees with config")
        expected_compatibility_paths: list[str] = []
        for position, value in enumerate(layers):
            descriptor = _descriptor(
                value,
                f"runnable OCI layer {position}",
                GZIP_LAYER_MEDIA_TYPES | PLAIN_LAYER_MEDIA_TYPES,
            )
            path, _data = self._verify_descriptor_blob(
                descriptor, f"runnable OCI layer {position}", 0
            )
            expected_compatibility_paths.append(path)
            actual_diff_id = self._layer_diff_id(
                path, str(descriptor["mediaType"]), f"runnable OCI layer {position}"
            )
            if actual_diff_id != diff_ids[position]:
                _fail(f"runnable OCI layer {position} disagrees with config diff_id")
        if compatibility_layers != expected_compatibility_paths:
            _fail("Docker compatibility Layers are not the ordered runnable OCI layers")
        for position, descriptor in enumerate(attestations):
            self._verify_attestation(descriptor, manifest_digest, position)
        return manifest_digest

    def _verify_attestation(
        self, descriptor: dict[str, object], runnable_digest: str, position: int
    ) -> None:
        annotations = _expect_object(
            descriptor.get("annotations"), "attestation annotations"
        )
        if (
            annotations.get("vnd.docker.reference.type") != "attestation-manifest"
            or annotations.get("vnd.docker.reference.digest") != runnable_digest
        ):
            _fail("attestation does not reference the runnable manifest")
        _path, data = self._verify_descriptor_blob(
            descriptor, f"attestation manifest {position}", MAX_JSON_BYTES
        )
        if data is None:
            _fail("attestation manifest body was not captured")
        manifest = self._oci_header(
            _load_json(data, "attestation manifest"),
            "attestation manifest",
            OCI_MANIFEST,
        )
        config_descriptor = _descriptor(
            manifest.get("config"), "attestation config", {OCI_CONFIG}
        )
        _config_path, config_bytes = self._verify_descriptor_blob(
            config_descriptor, "attestation config", MAX_JSON_BYTES
        )
        if config_bytes is None:
            _fail("attestation config body was not captured")
        config = _expect_object(
            _load_json(config_bytes, "attestation config"), "attestation config"
        )
        if config.get("os") != "unknown" or config.get("architecture") != "unknown":
            _fail("attestation config platform must be unknown/unknown")
        rootfs = _expect_object(config.get("rootfs"), "attestation config rootfs")
        diff_ids = _expect_list(rootfs.get("diff_ids"), "attestation config diff_ids")
        layers = _expect_list(manifest.get("layers"), "attestation layers")
        if rootfs.get("type") != "layers" or len(layers) != 1 or len(diff_ids) != 1:
            _fail("attestation must contain exactly one in-toto layer")
        layer_descriptor = _descriptor(layers[0], "attestation layer", {IN_TOTO})
        layer_digest = _digest(
            layer_descriptor.get("digest"), "attestation layer digest"
        )
        if diff_ids[0] != layer_digest:
            _fail("attestation config diff_id does not match its in-toto layer")
        layer_annotations = _expect_object(
            layer_descriptor.get("annotations"), "attestation layer annotations"
        )
        predicate_type = "https://slsa.dev/provenance/v1"
        if layer_annotations.get("in-toto.io/predicate-type") != predicate_type:
            _fail("attestation layer predicate type is unsupported")
        _layer_path, statement_bytes = self._verify_descriptor_blob(
            layer_descriptor, "attestation in-toto statement", MAX_JSON_BYTES
        )
        if statement_bytes is None:
            _fail("attestation in-toto statement body was not captured")
        statement = _expect_object(
            _load_json(statement_bytes, "attestation in-toto statement"),
            "attestation in-toto statement",
        )
        if (
            statement.get("_type") != "https://in-toto.io/Statement/v1"
            or statement.get("predicateType") != predicate_type
        ):
            _fail("attestation in-toto statement type is unsupported")
        subjects = _expect_list(statement.get("subject"), "attestation subjects")
        runnable_hex = runnable_digest.removeprefix("sha256:")
        if not any(
            isinstance(subject, dict)
            and isinstance(subject.get("digest"), dict)
            and subject["digest"].get("sha256") == runnable_hex
            for subject in subjects
        ):
            _fail("attestation subject does not bind the runnable manifest")


def verify_archive(path: Path, runtime_id: str) -> Result:
    _digest(runtime_id, "runtime image ID")
    try:
        path_stat = path.lstat()
    except OSError as exc:
        _fail(f"cannot stat image archive: {exc}")
    if stat.S_ISLNK(path_stat.st_mode) or not stat.S_ISREG(path_stat.st_mode):
        _fail("image archive must be a non-symlink regular file")
    flags = os.O_RDONLY
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    try:
        descriptor = os.open(path, flags)
    except OSError as exc:
        _fail(f"cannot open image archive: {exc}")
    with os.fdopen(descriptor, "rb") as file_object:
        opened_stat = os.fstat(file_object.fileno())
        if (opened_stat.st_dev, opened_stat.st_ino) != (
            path_stat.st_dev,
            path_stat.st_ino,
        ):
            _fail("image archive changed while opening")
        if opened_stat.st_size > MAX_DESCRIPTOR_BYTES:
            _fail("image archive exceeds the verification byte limit")
        _preflight_uncompressed_tar(file_object, opened_stat.st_size)
        archive_digest, archive_size = _sha256_stream(
            file_object, MAX_DESCRIPTOR_BYTES, "image archive"
        )
        if archive_size != opened_stat.st_size:
            _fail("image archive size changed while hashing")
        result = ArchiveVerifier(file_object, runtime_id, archive_digest).verify()
        final_stat = os.fstat(file_object.fileno())
        if (
            final_stat.st_size != opened_stat.st_size
            or final_stat.st_mtime_ns != opened_stat.st_mtime_ns
            or final_stat.st_ctime_ns != opened_stat.st_ctime_ns
        ):
            _fail("image archive changed during verification")
        try:
            final_path_stat = path.lstat()
        except OSError as exc:
            _fail(f"cannot restat image archive: {exc}")
        if stat.S_ISLNK(final_path_stat.st_mode) or (
            final_path_stat.st_dev,
            final_path_stat.st_ino,
        ) != (opened_stat.st_dev, opened_stat.st_ino):
            _fail("image archive pathname changed during verification")
        return result


def _parse_args(arguments: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("runtime_image_id")
    parser.add_argument("service")
    return parser.parse_args(arguments)


def main(arguments: list[str] | None = None) -> int:
    options = _parse_args(sys.argv[1:] if arguments is None else arguments)
    if SERVICE_RE.fullmatch(options.service) is None:
        print("Archive identity service name is invalid.", file=sys.stderr)
        return 2
    try:
        _apply_linux_memory_ceiling()
        result = verify_archive(options.archive, options.runtime_image_id)
    except VerificationError as exc:
        print(
            f"Archive identity verification failed for {options.service}: {exc}",
            file=sys.stderr,
        )
        return 1
    except MemoryError:
        print(
            f"Archive identity verification failed for {options.service}: memory ceiling exceeded",
            file=sys.stderr,
        )
        return 1
    prefix = options.service.replace("-", "_")
    manifest = result.manifest_digest or "none"
    print(f"{prefix}_verified_archive_digest={result.archive_digest}")
    print(f"{prefix}_runtime_image_id={result.runtime_id}")
    print(f"{prefix}_runtime_image_id_type={result.runtime_id_type}")
    print(f"{prefix}_runnable_manifest_digest={manifest}")
    print(f"{prefix}_scanner_config_image_id={result.config_id}")
    print(f"{prefix}_archive_platform={result.platform}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
