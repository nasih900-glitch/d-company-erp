from __future__ import annotations

import gzip
import hashlib
import io
import json
import tarfile
from dataclasses import dataclass
from pathlib import Path
from typing import Callable


OCI_INDEX = "application/vnd.oci.image.index.v1+json"
OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json"
OCI_CONFIG = "application/vnd.oci.image.config.v1+json"
OCI_LAYER_GZIP = "application/vnd.oci.image.layer.v1.tar+gzip"
IN_TOTO = "application/vnd.in-toto+json"


@dataclass(frozen=True)
class ArchiveFixture:
    path: Path
    runtime_id: str
    config_id: str
    manifest_digest: str | None
    layer_paths: tuple[str, ...]
    blob_paths: tuple[str, ...]


def json_bytes(value: object) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":")).encode()


def digest(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


def blob_path(value: str) -> str:
    return "blobs/sha256/" + value.removeprefix("sha256:")


def raw_tar_header(
    *, name: str = "data", size: int = 0, type_flag: bytes = b"0"
) -> bytes:
    header = bytearray(512)
    fields = {
        (0, 100): name.encode(),
        (100, 108): b"0000444\0",
        (108, 116): b"0000000\0",
        (116, 124): b"0000000\0",
        (124, 136): f"{size:011o}\0".encode(),
        (136, 148): b"00000000000\0",
        (257, 265): b"ustar\0" + b"00",
    }
    for (start, end), value in fields.items():
        if len(value) > end - start:
            raise ValueError("raw tar fixture field overflow")
        header[start : start + len(value)] = value
    header[148:156] = b"        "
    header[156:157] = type_flag
    checksum = sum(header)
    header[148:156] = f"{checksum:06o}\0 ".encode()
    return bytes(header)


def write_tar(path: Path, members: list[tuple[str, bytes, bytes | None]]) -> None:
    with tarfile.open(path, "w") as archive:
        for name, data, type_override in members:
            info = tarfile.TarInfo(name)
            info.mtime = 0
            info.uid = 0
            info.gid = 0
            info.mode = 0o444
            info.size = len(data)
            if type_override is not None:
                info.type = type_override
                if type_override in (tarfile.SYMTYPE, tarfile.LNKTYPE):
                    info.linkname = "manifest.json"
                    info.size = 0
                    archive.addfile(info)
                    continue
            archive.addfile(info, io.BytesIO(data))


def _config(
    layer_data: list[bytes], *, architecture: str = "amd64", os_name: str = "linux"
) -> bytes:
    return json_bytes(
        {
            "architecture": architecture,
            "config": {"Cmd": ["fixture"]},
            "os": os_name,
            "rootfs": {
                "type": "layers",
                "diff_ids": [digest(layer) for layer in layer_data],
            },
        }
    )


def write_classic_archive(path: Path) -> ArchiveFixture:
    layers = [b"legacy-layer-one", b"legacy-layer-two"]
    config = _config(layers)
    config_id = digest(config)
    layer_paths = ("legacy-one/layer.tar", "legacy-two/layer.tar")
    manifest = json_bytes(
        [
            {
                "Config": config_id.removeprefix("sha256:") + ".json",
                "RepoTags": None,
                "Layers": list(layer_paths),
            }
        ]
    )
    members = [
        ("manifest.json", manifest, None),
        (config_id.removeprefix("sha256:") + ".json", config, None),
    ]
    members.extend(
        (name, data, None) for name, data in zip(layer_paths, layers, strict=True)
    )
    write_tar(path, members)
    return ArchiveFixture(path, config_id, config_id, None, layer_paths, ())


def classic_config_id() -> str:
    return digest(_config([b"legacy-layer-one", b"legacy-layer-two"]))


def write_modern_classic_archive(
    path: Path, *, missing_plain_source: bool = False
) -> ArchiveFixture:
    layers = [b"modern-classic-layer-one", b"modern-classic-layer-two"]
    compressed_sources = [gzip.compress(layer, mtime=0) for layer in layers]
    source_media_type = (
        "application/vnd.oci.image.layer.v1.tar"
        if missing_plain_source
        else OCI_LAYER_GZIP
    )
    source_data = (
        [b"unexported-plain-" + layer for layer in layers]
        if missing_plain_source
        else compressed_sources
    )
    config = _config(layers)
    config_id = digest(config)
    layer_paths = tuple(blob_path(digest(layer)) for layer in layers)
    oci_manifest = json_bytes(
        {
            "schemaVersion": 2,
            "mediaType": OCI_MANIFEST,
            "config": {
                "mediaType": OCI_CONFIG,
                "digest": config_id,
                "size": len(config),
            },
            "layers": [
                {
                    "mediaType": source_media_type,
                    "digest": digest(source),
                    "size": len(source),
                }
                for source in source_data
            ],
        }
    )
    manifest_digest = digest(oci_manifest)
    index = json_bytes(
        {
            "schemaVersion": 2,
            "mediaType": OCI_INDEX,
            "manifests": [
                {
                    "mediaType": OCI_MANIFEST,
                    "digest": manifest_digest,
                    "size": len(oci_manifest),
                }
            ],
        }
    )
    compatibility = json_bytes(
        [
            {
                "Config": blob_path(config_id),
                "RepoTags": None,
                "Layers": list(layer_paths),
            }
        ]
    )
    members = [
        ("manifest.json", compatibility, None),
        ("oci-layout", json_bytes({"imageLayoutVersion": "1.0.0"}), None),
        ("index.json", index, None),
        (blob_path(config_id), config, None),
        (blob_path(manifest_digest), oci_manifest, None),
    ]
    members.extend(
        (member, layer, None) for member, layer in zip(layer_paths, layers, strict=True)
    )
    write_tar(path, members)
    return ArchiveFixture(
        path,
        config_id,
        config_id,
        manifest_digest,
        layer_paths,
        tuple(name for name, _data, _type in members if name.startswith("blobs/")),
    )


def write_oci_index_archive(
    path: Path,
    *,
    architecture: str = "amd64",
    os_name: str = "linux",
    include_platform: bool = True,
    duplicate_runnable: bool = False,
    include_runnable: bool = True,
    include_attestation: bool = True,
    invalid_attestation_reference: bool = False,
    runnable_size_delta: int = 0,
    malformed_runnable_digest: bool = False,
    config_disagreement: bool = False,
    compatibility_order: tuple[int, ...] = (0, 1),
    duplicate_runtime_json_key: bool = False,
    layer_media_type: str = OCI_LAYER_GZIP,
) -> ArchiveFixture:
    plain_layers = [b"oci-layer-one" * 3, b"oci-layer-two" * 2]
    compressed_layers = [gzip.compress(layer, mtime=0) for layer in plain_layers]
    config = _config(plain_layers, architecture=architecture, os_name=os_name)
    config_id = digest(config)
    layer_digests = [digest(layer) for layer in compressed_layers]
    layer_paths = tuple(blob_path(value) for value in layer_digests)

    manifest_config = config
    manifest_config_id = config_id
    if config_disagreement:
        manifest_config = _config(plain_layers, architecture="arm64", os_name="linux")
        manifest_config_id = digest(manifest_config)
    runnable_manifest = json_bytes(
        {
            "schemaVersion": 2,
            "mediaType": OCI_MANIFEST,
            "config": {
                "mediaType": OCI_CONFIG,
                "digest": manifest_config_id,
                "size": len(manifest_config),
            },
            "layers": [
                {
                    "mediaType": layer_media_type,
                    "digest": layer_digest,
                    "size": len(layer),
                }
                for layer_digest, layer in zip(
                    layer_digests, compressed_layers, strict=True
                )
            ],
        }
    )
    manifest_digest = digest(runnable_manifest)
    runnable_descriptor: dict[str, object] = {
        "mediaType": OCI_MANIFEST,
        "digest": "sha512:" + "1" * 128
        if malformed_runnable_digest
        else manifest_digest,
        "size": len(runnable_manifest) + runnable_size_delta,
    }
    if include_platform:
        runnable_descriptor["platform"] = {"architecture": "amd64", "os": "linux"}

    predicate_type = "https://slsa.dev/provenance/v1"
    statement = json_bytes(
        {
            "_type": "https://in-toto.io/Statement/v1",
            "predicateType": predicate_type,
            "subject": [
                {
                    "name": "fixture",
                    "digest": {"sha256": manifest_digest.removeprefix("sha256:")},
                }
            ],
            "predicate": {},
        }
    )
    statement_digest = digest(statement)
    attestation_config = json_bytes(
        {
            "architecture": "unknown",
            "config": {},
            "os": "unknown",
            "rootfs": {"type": "layers", "diff_ids": [statement_digest]},
        }
    )
    attestation_config_digest = digest(attestation_config)
    attestation_manifest = json_bytes(
        {
            "schemaVersion": 2,
            "mediaType": OCI_MANIFEST,
            "config": {
                "mediaType": OCI_CONFIG,
                "digest": attestation_config_digest,
                "size": len(attestation_config),
            },
            "layers": [
                {
                    "mediaType": IN_TOTO,
                    "digest": statement_digest,
                    "size": len(statement),
                    "annotations": {"in-toto.io/predicate-type": predicate_type},
                }
            ],
        }
    )
    attestation_digest = digest(attestation_manifest)
    attestation_descriptor = {
        "mediaType": OCI_MANIFEST,
        "digest": attestation_digest,
        "size": len(attestation_manifest),
        "platform": {"architecture": "unknown", "os": "unknown"},
        "annotations": {
            "vnd.docker.reference.type": "attestation-manifest",
            "vnd.docker.reference.digest": (
                "sha256:" + "f" * 64
                if invalid_attestation_reference
                else manifest_digest
            ),
        },
    }
    children = [runnable_descriptor] if include_runnable else []
    if include_runnable and duplicate_runnable:
        children.append(dict(runnable_descriptor))
    if include_attestation:
        children.append(attestation_descriptor)
    runtime_index_object = {
        "schemaVersion": 2,
        "mediaType": OCI_INDEX,
        "manifests": children,
    }
    runtime_index = json_bytes(runtime_index_object)
    if duplicate_runtime_json_key:
        runtime_index = (
            b'{"schemaVersion":2,"mediaType":"'
            + OCI_INDEX.encode()
            + b'","mediaType":"'
            + OCI_INDEX.encode()
            + b'","manifests":'
            + json_bytes(children)
            + b"}"
        )
    runtime_id = digest(runtime_index)
    wrapper = json_bytes(
        {
            "schemaVersion": 2,
            "mediaType": OCI_INDEX,
            "manifests": [
                {
                    "mediaType": OCI_INDEX,
                    "digest": runtime_id,
                    "size": len(runtime_index),
                }
            ],
        }
    )
    compatibility_paths = [layer_paths[index] for index in compatibility_order]
    compatibility = json_bytes(
        [
            {
                "Config": blob_path(config_id),
                "RepoTags": None,
                "Layers": compatibility_paths,
            }
        ]
    )
    members = [
        ("manifest.json", compatibility, None),
        ("oci-layout", json_bytes({"imageLayoutVersion": "1.0.0"}), None),
        ("index.json", wrapper, None),
        (blob_path(runtime_id), runtime_index, None),
        (blob_path(manifest_digest), runnable_manifest, None),
        (blob_path(config_id), config, None),
        (blob_path(attestation_digest), attestation_manifest, None),
        (blob_path(attestation_config_digest), attestation_config, None),
        (blob_path(statement_digest), statement, None),
    ]
    if config_disagreement:
        members.append((blob_path(manifest_config_id), manifest_config, None))
    members.extend(
        (member, layer, None)
        for member, layer in zip(layer_paths, compressed_layers, strict=True)
    )
    write_tar(path, members)
    return ArchiveFixture(
        path,
        runtime_id,
        config_id,
        manifest_digest,
        layer_paths,
        tuple(name for name, _data, _type in members if name.startswith("blobs/")),
    )


def rewrite_tar(
    source: Path,
    destination: Path,
    transform: Callable[
        [list[tuple[str, bytes, bytes | None]]], list[tuple[str, bytes, bytes | None]]
    ],
) -> None:
    members: list[tuple[str, bytes, bytes | None]] = []
    with tarfile.open(source, "r") as archive:
        for member in archive:
            stream = archive.extractfile(member)
            members.append(
                (member.name, stream.read() if stream is not None else b"", None)
            )
    write_tar(destination, transform(members))
