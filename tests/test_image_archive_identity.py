from __future__ import annotations

import gzip
import importlib.util
import io
import os
import subprocess
import sys
import tarfile
from pathlib import Path
from types import SimpleNamespace

import pytest


ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "tests" / "fixtures"
sys.path.insert(0, str(FIXTURES))

from image_archive_identity_fixtures import (  # noqa: E402
    blob_path,
    digest,
    raw_tar_header,
    rewrite_tar,
    write_classic_archive,
    write_modern_classic_archive,
    write_oci_index_archive,
)


VERIFIER = ROOT / "infra" / "scripts" / "verify-image-archive-identity.py"
VERIFIER_SPEC = importlib.util.spec_from_file_location(
    "archive_identity_verifier", VERIFIER
)
if VERIFIER_SPEC is None or VERIFIER_SPEC.loader is None:
    raise RuntimeError("cannot load archive identity verifier for unit tests")
VERIFIER_MODULE = importlib.util.module_from_spec(VERIFIER_SPEC)
sys.modules[VERIFIER_SPEC.name] = VERIFIER_MODULE
VERIFIER_SPEC.loader.exec_module(VERIFIER_MODULE)


def _run(path: Path, runtime_id: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(VERIFIER), str(path), runtime_id, "backend"],
        text=True,
        capture_output=True,
        check=False,
    )


def test_legacy_classic_archive_binds_runtime_config_and_uncompressed_layers(
    tmp_path: Path,
) -> None:
    fixture = write_classic_archive(tmp_path / "classic.tar")

    completed = _run(fixture.path, fixture.runtime_id)

    assert completed.returncode == 0, completed.stderr
    assert "backend_runtime_image_id_type=config" in completed.stdout
    assert f"backend_scanner_config_image_id={fixture.config_id}" in completed.stdout
    assert "backend_verified_archive_digest=sha256:" in completed.stdout
    assert "backend_runnable_manifest_digest=none" in completed.stdout
    assert "backend_archive_platform=linux/amd64" in completed.stdout


def test_modern_moby_classic_archive_does_not_misclassify_oci_wrapper(
    tmp_path: Path,
) -> None:
    fixture = write_modern_classic_archive(tmp_path / "modern-classic.tar")

    completed = _run(fixture.path, fixture.runtime_id)

    assert completed.returncode == 0, completed.stderr
    assert "backend_runtime_image_id_type=config" in completed.stdout
    assert (
        f"backend_runnable_manifest_digest={fixture.manifest_digest}"
        in completed.stdout
    )


def test_modern_classic_missing_plain_source_descriptor_is_rejected(
    tmp_path: Path,
) -> None:
    fixture = write_modern_classic_archive(
        tmp_path / "missing-plain.tar", missing_plain_source=True
    )

    completed = _run(fixture.path, fixture.runtime_id)

    assert completed.returncode != 0
    assert "missing plain OCI compatibility layer blob" in completed.stderr


def test_provenance_oci_index_binds_runtime_manifest_config_and_scanner_view(
    tmp_path: Path,
) -> None:
    fixture = write_oci_index_archive(tmp_path / "oci-index.tar")

    completed = _run(fixture.path, fixture.runtime_id)

    assert completed.returncode == 0, completed.stderr
    assert "backend_runtime_image_id_type=oci-index" in completed.stdout
    assert f"backend_runtime_image_id={fixture.runtime_id}" in completed.stdout
    assert (
        f"backend_runnable_manifest_digest={fixture.manifest_digest}"
        in completed.stdout
    )
    assert f"backend_scanner_config_image_id={fixture.config_id}" in completed.stdout


def test_wrong_runtime_identity_is_rejected(tmp_path: Path) -> None:
    fixture = write_oci_index_archive(tmp_path / "oci-index.tar")

    completed = _run(fixture.path, "sha256:" + "e" * 64)

    assert completed.returncode != 0
    assert (
        "runtime image ID does not match the top OCI index descriptor"
        in completed.stderr
    )


@pytest.mark.parametrize("target", ["config", "manifest", "layer"])
def test_missing_referenced_blob_is_rejected(tmp_path: Path, target: str) -> None:
    fixture = write_oci_index_archive(tmp_path / "source.tar")
    if target == "config":
        omitted = blob_path(fixture.config_id)
    elif target == "manifest":
        omitted = blob_path(fixture.manifest_digest or "")
    else:
        omitted = fixture.layer_paths[0]
    changed = tmp_path / f"missing-{target}.tar"
    rewrite_tar(
        fixture.path,
        changed,
        lambda members: [member for member in members if member[0] != omitted],
    )

    completed = _run(changed, fixture.runtime_id)

    assert completed.returncode != 0
    assert "missing" in completed.stderr


def test_tampered_layer_blob_is_rejected(tmp_path: Path) -> None:
    fixture = write_oci_index_archive(tmp_path / "source.tar")
    changed = tmp_path / "tampered.tar"

    def tamper(
        members: list[tuple[str, bytes, bytes | None]],
    ) -> list[tuple[str, bytes, bytes | None]]:
        return [
            (name, data + b"tampered" if name == fixture.layer_paths[0] else data, kind)
            for name, data, kind in members
        ]

    rewrite_tar(fixture.path, changed, tamper)

    completed = _run(changed, fixture.runtime_id)

    assert completed.returncode != 0
    assert any(
        failure in completed.stderr
        for failure in (
            "descriptor size mismatch",
            "digest mismatch",
            "cannot decompress",
        )
    )


def test_tampered_config_blob_is_rejected(tmp_path: Path) -> None:
    fixture = write_oci_index_archive(tmp_path / "source.tar")
    changed = tmp_path / "tampered-config.tar"
    config_path = blob_path(fixture.config_id)

    def tamper(
        members: list[tuple[str, bytes, bytes | None]],
    ) -> list[tuple[str, bytes, bytes | None]]:
        return [
            (name, data + b" " if name == config_path else data, kind)
            for name, data, kind in members
        ]

    rewrite_tar(fixture.path, changed, tamper)

    completed = _run(changed, fixture.runtime_id)

    assert completed.returncode != 0
    assert "Config path does not match its content digest" in completed.stderr


def test_descriptor_size_mismatch_is_rejected(tmp_path: Path) -> None:
    fixture = write_oci_index_archive(
        tmp_path / "wrong-size.tar", runnable_size_delta=1
    )

    completed = _run(fixture.path, fixture.runtime_id)

    assert completed.returncode != 0
    assert "descriptor size mismatch for runnable OCI manifest" in completed.stderr


@pytest.mark.parametrize("malformation", ["invalid", "duplicate-key", "oversized"])
def test_malformed_or_ambiguous_json_is_rejected(
    tmp_path: Path, malformation: str
) -> None:
    if malformation == "duplicate-key":
        fixture = write_oci_index_archive(
            tmp_path / "bad.tar", duplicate_runtime_json_key=True
        )
        completed = _run(fixture.path, fixture.runtime_id)
    else:
        fixture = write_classic_archive(tmp_path / "source.tar")
        changed = tmp_path / "bad.tar"
        replacement = (
            b"{" if malformation == "invalid" else b" " * (16 * 1024 * 1024 + 1)
        )

        def replace_manifest(
            members: list[tuple[str, bytes, bytes | None]],
        ) -> list[tuple[str, bytes, bytes | None]]:
            return [
                (name, replacement if name == "manifest.json" else data, kind)
                for name, data, kind in members
            ]

        rewrite_tar(fixture.path, changed, replace_manifest)
        completed = _run(changed, fixture.runtime_id)

    assert completed.returncode != 0
    assert (
        "duplicate JSON key" in completed.stderr
        or "malformed" in completed.stderr
        or "byte limit" in completed.stderr
    )


@pytest.mark.parametrize("unsafe_name", ["../escape", "/absolute"])
def test_unsafe_member_path_is_rejected(tmp_path: Path, unsafe_name: str) -> None:
    fixture = write_classic_archive(tmp_path / "source.tar")
    changed = tmp_path / "unsafe.tar"
    rewrite_tar(
        fixture.path,
        changed,
        lambda members: members + [(unsafe_name, b"untrusted", None)],
    )

    completed = _run(changed, fixture.runtime_id)

    assert completed.returncode != 0
    assert "member path is unsafe" in completed.stderr


def test_duplicate_member_name_is_rejected(tmp_path: Path) -> None:
    fixture = write_classic_archive(tmp_path / "source.tar")
    changed = tmp_path / "duplicate.tar"
    rewrite_tar(fixture.path, changed, lambda members: members + [members[0]])

    completed = _run(changed, fixture.runtime_id)

    assert completed.returncode != 0
    assert "duplicate archive member" in completed.stderr


@pytest.mark.parametrize("link_type", [tarfile.SYMTYPE, tarfile.LNKTYPE])
def test_archive_data_links_are_rejected(tmp_path: Path, link_type: bytes) -> None:
    fixture = write_classic_archive(tmp_path / "source.tar")
    changed = tmp_path / "link.tar"
    rewrite_tar(
        fixture.path,
        changed,
        lambda members: members + [("linked-data", b"", link_type)],
    )

    completed = _run(changed, fixture.runtime_id)

    assert completed.returncode != 0
    assert "raw tar member type is unsupported" in completed.stderr


@pytest.mark.parametrize(
    "options,error",
    [
        ({"include_platform": False}, "platform"),
        ({"architecture": "arm64"}, "linux/amd64"),
        ({"os_name": "windows"}, "linux/amd64"),
        ({"config_disagreement": True}, "config digest disagrees"),
    ],
)
def test_missing_wrong_or_disagreeing_platform_and_config_are_rejected(
    tmp_path: Path, options: dict[str, object], error: str
) -> None:
    fixture = write_oci_index_archive(tmp_path / "platform.tar", **options)

    completed = _run(fixture.path, fixture.runtime_id)

    assert completed.returncode != 0
    assert error in completed.stderr


def test_ambiguous_runnable_manifest_is_rejected(tmp_path: Path) -> None:
    fixture = write_oci_index_archive(
        tmp_path / "ambiguous.tar", duplicate_runnable=True
    )

    completed = _run(fixture.path, fixture.runtime_id)

    assert completed.returncode != 0
    assert "exactly one runnable linux/amd64" in completed.stderr


def test_missing_runnable_manifest_is_rejected(tmp_path: Path) -> None:
    fixture = write_oci_index_archive(
        tmp_path / "missing-runnable.tar", include_runnable=False
    )

    completed = _run(fixture.path, fixture.runtime_id)

    assert completed.returncode != 0
    assert "exactly one runnable linux/amd64" in completed.stderr


def test_invalid_attestation_reference_is_rejected(tmp_path: Path) -> None:
    fixture = write_oci_index_archive(
        tmp_path / "bad-attestation.tar", invalid_attestation_reference=True
    )

    completed = _run(fixture.path, fixture.runtime_id)

    assert completed.returncode != 0
    assert "attestation does not reference the runnable manifest" in completed.stderr


def test_missing_attestation_is_rejected_for_index_root_archive(tmp_path: Path) -> None:
    fixture = write_oci_index_archive(
        tmp_path / "missing-attestation.tar", include_attestation=False
    )

    completed = _run(fixture.path, fixture.runtime_id)

    assert completed.returncode != 0
    assert "exactly one recognized attestation" in completed.stderr


@pytest.mark.parametrize("compatibility_order", [(1, 0), (0, 0)])
def test_compatibility_layer_reorder_or_substitution_is_rejected(
    tmp_path: Path, compatibility_order: tuple[int, ...]
) -> None:
    fixture = write_oci_index_archive(
        tmp_path / "bad-compatibility.tar", compatibility_order=compatibility_order
    )

    completed = _run(fixture.path, fixture.runtime_id)

    assert completed.returncode != 0
    assert "compatibility layer" in completed.stderr


def test_malformed_digest_is_rejected(tmp_path: Path) -> None:
    fixture = write_oci_index_archive(
        tmp_path / "malformed-digest.tar", malformed_runnable_digest=True
    )

    completed = _run(fixture.path, fixture.runtime_id)

    assert completed.returncode != 0
    assert "lowercase sha256 digest" in completed.stderr


def test_unsupported_layer_media_type_is_rejected(tmp_path: Path) -> None:
    fixture = write_oci_index_archive(
        tmp_path / "zstd.tar",
        layer_media_type="application/vnd.oci.image.layer.v1.tar+zstd",
    )

    completed = _run(fixture.path, fixture.runtime_id)

    assert completed.returncode != 0
    assert "unsupported media type" in completed.stderr


def test_compressed_outer_archive_is_not_silently_accepted(tmp_path: Path) -> None:
    fixture = write_classic_archive(tmp_path / "source.tar")
    compressed = tmp_path / "outer.tar.gz"
    compressed.write_bytes(gzip.compress(fixture.path.read_bytes(), mtime=0))

    completed = _run(compressed, fixture.runtime_id)

    assert completed.returncode != 0
    assert "raw tar size is not a complete" in completed.stderr


def test_symlink_archive_path_is_rejected(tmp_path: Path) -> None:
    fixture = write_classic_archive(tmp_path / "source.tar")
    linked = tmp_path / "linked.tar"
    os.symlink(fixture.path, linked)

    completed = _run(linked, fixture.runtime_id)

    assert completed.returncode != 0
    assert "non-symlink regular file" in completed.stderr


class _ReadSpy(io.BytesIO):
    def __init__(self, value: bytes) -> None:
        super().__init__(value)
        self.read_sizes: list[int] = []

    def read(self, size: int = -1) -> bytes:
        self.read_sizes.append(size)
        if size > 512 or size < 0:
            raise AssertionError(f"unsafe preflight read requested: {size}")
        return super().read(size)


@pytest.mark.parametrize("type_flag", [b"x", b"g", b"L", b"K", b"S"])
def test_raw_preflight_rejects_extensions_before_reading_payload(
    type_flag: bytes,
) -> None:
    archive = _ReadSpy(
        raw_tar_header(size=1024 * 1024, type_flag=type_flag) + (b"\0" * 1024)
    )

    with pytest.raises(
        VERIFIER_MODULE.VerificationError,
        match="extensions and sparse records are not supported",
    ):
        VERIFIER_MODULE._preflight_uncompressed_tar(archive, len(archive.getvalue()))

    assert archive.read_sizes == [512]


def test_raw_extension_rejection_precedes_tarfile_parser(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    archive = tmp_path / "pax.tar"
    archive.write_bytes(
        raw_tar_header(size=1024 * 1024, type_flag=b"x") + (b"\0" * 1024)
    )
    parser_called = False

    def forbidden_tar_open(*_arguments: object, **_keywords: object) -> None:
        nonlocal parser_called
        parser_called = True
        raise AssertionError("tarfile.open was reached before raw rejection")

    monkeypatch.setattr(VERIFIER_MODULE.tarfile, "open", forbidden_tar_open)

    with pytest.raises(VERIFIER_MODULE.VerificationError, match="extensions"):
        VERIFIER_MODULE.verify_archive(archive, "sha256:" + "a" * 64)

    assert parser_called is False


def test_raw_preflight_rejects_extension_chain_before_first_body_read() -> None:
    second_header = raw_tar_header(size=1024 * 1024, type_flag=b"g")
    archive = _ReadSpy(
        raw_tar_header(size=512, type_flag=b"x") + second_header + (b"\0" * 1024)
    )

    with pytest.raises(VERIFIER_MODULE.VerificationError, match="extensions"):
        VERIFIER_MODULE._preflight_uncompressed_tar(archive, len(archive.getvalue()))

    assert archive.read_sizes == [512]


def test_raw_preflight_rejects_pax_size_override_without_reading_it() -> None:
    payload = b"24 size=17179869184\n".ljust(512, b"\0")
    archive = _ReadSpy(
        raw_tar_header(size=512, type_flag=b"x") + payload + (b"\0" * 1024)
    )

    with pytest.raises(VERIFIER_MODULE.VerificationError, match="extensions"):
        VERIFIER_MODULE._preflight_uncompressed_tar(archive, len(archive.getvalue()))

    assert archive.read_sizes == [512]


def test_raw_preflight_rejects_truncated_declared_member_extent() -> None:
    archive = _ReadSpy(raw_tar_header(size=4096) + (b"\0" * 1024))

    with pytest.raises(VERIFIER_MODULE.VerificationError, match="extent exceeds"):
        VERIFIER_MODULE._preflight_uncompressed_tar(archive, len(archive.getvalue()))

    assert archive.read_sizes == [512]


def test_raw_preflight_rejects_spoofed_header_checksum() -> None:
    header = bytearray(raw_tar_header())
    header[0] ^= 1
    archive = _ReadSpy(bytes(header) + (b"\0" * 1024))

    with pytest.raises(VERIFIER_MODULE.VerificationError, match="checksum mismatch"):
        VERIFIER_MODULE._preflight_uncompressed_tar(archive, len(archive.getvalue()))

    assert archive.read_sizes == [512]


@pytest.mark.parametrize(
    "label",
    [
        "runtime OCI index",
        "runnable OCI manifest",
        "attestation manifest",
        "attestation config",
        "attestation in-toto statement",
    ],
)
def test_metadata_capture_limit_rejects_before_body_read(
    monkeypatch: pytest.MonkeyPatch, label: str
) -> None:
    data = b"x" * 129
    value_digest = digest(data)
    path = blob_path(value_digest)
    verifier = VERIFIER_MODULE.ArchiveVerifier(
        io.BytesIO(), "sha256:" + "a" * 64, "sha256:" + "b" * 64
    )
    member = tarfile.TarInfo(path)
    member.size = len(data)
    verifier.members[path] = member

    def forbidden_read(*_arguments: object) -> None:
        raise AssertionError("metadata body was read before its size was rejected")

    monkeypatch.setattr(verifier, "_read", forbidden_read)
    monkeypatch.setattr(verifier, "_stream", forbidden_read)
    descriptor = {
        "mediaType": "fixture",
        "digest": value_digest,
        "size": len(data),
    }

    with pytest.raises(VERIFIER_MODULE.VerificationError, match="byte limit"):
        verifier._verify_descriptor_blob(descriptor, label, 128)


def test_metadata_capture_boundary_reads_and_hashes_exact_body() -> None:
    data = b"x" * 128
    value_digest = digest(data)
    path = blob_path(value_digest)
    verifier = VERIFIER_MODULE.ArchiveVerifier(
        io.BytesIO(), "sha256:" + "a" * 64, "sha256:" + "b" * 64
    )
    member = tarfile.TarInfo(path)
    member.size = len(data)
    verifier.members[path] = member

    class _FakeTar:
        def extractfile(self, _member: tarfile.TarInfo) -> io.BytesIO:
            return io.BytesIO(data)

    verifier.tar = _FakeTar()
    descriptor = {
        "mediaType": "fixture",
        "digest": value_digest,
        "size": len(data),
    }

    _path, captured = verifier._verify_descriptor_blob(descriptor, "metadata", 128)

    assert captured == data


def test_json_nesting_limit_is_explicit_and_normalized() -> None:
    deep_json = ("[" * 65 + "0" + "]" * 65).encode()

    with pytest.raises(VERIFIER_MODULE.VerificationError, match="nesting limit"):
        VERIFIER_MODULE._load_json(deep_json, "fixture")


def test_json_nesting_lexer_ignores_brackets_and_escaped_quotes_in_strings() -> None:
    document = b'{"value":"[ { \\"still a string\\" } ]"}'

    assert VERIFIER_MODULE._load_json(document, "fixture") == {
        "value": '[ { "still a string" } ]'
    }


@pytest.mark.parametrize(
    "current,expected_soft",
    [
        ((256 * 1024 * 1024, -1), 256 * 1024 * 1024),
        ((-1, 384 * 1024 * 1024), 384 * 1024 * 1024),
        ((-1, -1), 512 * 1024 * 1024),
    ],
)
def test_linux_memory_ceiling_never_raises_existing_limits(
    monkeypatch: pytest.MonkeyPatch,
    current: tuple[int, int],
    expected_soft: int,
) -> None:
    applied: list[tuple[int, tuple[int, int]]] = []
    fake_resource = SimpleNamespace(
        RLIMIT_AS=9,
        RLIM_INFINITY=-1,
        getrlimit=lambda _kind: current,
        setrlimit=lambda kind, limits: applied.append((kind, limits)),
    )
    monkeypatch.setattr(VERIFIER_MODULE.sys, "platform", "linux")
    monkeypatch.setitem(sys.modules, "resource", fake_resource)

    VERIFIER_MODULE._apply_linux_memory_ceiling()

    assert applied == [(9, (expected_soft, current[1]))]


def test_linux_memory_ceiling_failure_is_fail_closed(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def fail_setrlimit(_kind: int, _limits: tuple[int, int]) -> None:
        raise OSError("denied")

    fake_resource = SimpleNamespace(
        RLIMIT_AS=9,
        RLIM_INFINITY=-1,
        getrlimit=lambda _kind: (-1, -1),
        setrlimit=fail_setrlimit,
    )
    monkeypatch.setattr(VERIFIER_MODULE.sys, "platform", "linux")
    monkeypatch.setitem(sys.modules, "resource", fake_resource)

    with pytest.raises(VERIFIER_MODULE.VerificationError, match="memory ceiling"):
        VERIFIER_MODULE._apply_linux_memory_ceiling()


def test_memory_ceiling_is_a_noop_outside_linux(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    fake_resource = SimpleNamespace(
        getrlimit=lambda _kind: (_ for _ in ()).throw(AssertionError("unexpected")),
        setrlimit=lambda _kind, _limits: (_ for _ in ()).throw(
            AssertionError("unexpected")
        ),
    )
    monkeypatch.setattr(VERIFIER_MODULE.sys, "platform", "darwin")
    monkeypatch.setitem(sys.modules, "resource", fake_resource)

    VERIFIER_MODULE._apply_linux_memory_ceiling()


def test_main_normalizes_memory_error_without_success_output(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    monkeypatch.setattr(VERIFIER_MODULE, "_apply_linux_memory_ceiling", lambda: None)
    monkeypatch.setattr(
        VERIFIER_MODULE,
        "verify_archive",
        lambda _path, _runtime_id: (_ for _ in ()).throw(MemoryError()),
    )

    status = VERIFIER_MODULE.main(["unused.tar", "sha256:" + "a" * 64, "backend"])
    captured = capsys.readouterr()

    assert status == 1
    assert captured.out == ""
    assert "memory ceiling exceeded" in captured.err
