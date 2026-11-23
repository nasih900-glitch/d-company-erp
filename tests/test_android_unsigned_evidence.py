from __future__ import annotations

import importlib.util
import json
from pathlib import Path

MODULE = Path(__file__).resolve().parents[1] / "scripts/collect_android_unsigned_evidence.py"
spec = importlib.util.spec_from_file_location("unsigned_evidence", MODULE)
assert spec and spec.loader
collector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(collector)


def fixture(root: Path) -> Path:
    build = root / "android-native/app/build"
    for variant in ("release", "directRelease"):
        directory = build / "outputs/apk" / variant
        directory.mkdir(parents=True)
        (directory / "output-metadata.json").write_text(json.dumps({"elements": [{"outputFile": "app.apk"}]}))
        (directory / "app.apk").write_bytes(variant.encode())
        manifest = build / "intermediates/merged_manifests" / variant / ("process" + variant[0].upper() + variant[1:] + "Manifest") / "AndroidManifest.xml"
        manifest.parent.mkdir(parents=True)
        manifest.write_bytes(variant.encode())
    aab = build / "outputs/bundle/release/app-release.aab"
    aab.parent.mkdir(parents=True)
    aab.write_bytes(b"canonical")
    audit = build / "outputs/canonical-bundle-audit/release"
    audit.mkdir(parents=True)
    for name in ("raw-agp.aab", "entry-map.tsv", "SHA256SUMS"):
        (audit / name).write_bytes(name.encode())
    return build


def test_all_seven_outputs_and_raw_audit_are_preserved_before_any_verdict(tmp_path: Path) -> None:
    fixture(tmp_path)
    report = collector.collect(tmp_path, tmp_path / "evidence")
    assert len(report["files"]) == 10
    assert not report["missing_or_unavailable"]
    assert report["diagnostics_only"] is True
    assert (tmp_path / "evidence/play-unsigned.aab").read_bytes() == b"canonical"
    assert (tmp_path / "evidence/raw-agp.aab").read_bytes() == b"raw-agp.aab"


def test_one_missing_invalid_or_linked_output_does_not_discard_other_evidence(tmp_path: Path) -> None:
    build = fixture(tmp_path)
    (build / "outputs/apk/release/output-metadata.json").write_text('{"elements":[{"outputFile":"../escape.apk"}]}')
    canonical = build / "outputs/bundle/release/app-release.aab"
    canonical.unlink()
    canonical.symlink_to(build / "outputs/apk/directRelease/app.apk")
    report = collector.collect(tmp_path, tmp_path / "evidence")
    assert "play-unsigned.apk" in report["missing_or_unavailable"]
    assert "play-unsigned.aab" in report["missing_or_unavailable"]
    assert "direct-unsigned.apk" in report["files"]
    assert "direct-AndroidManifest.xml" in report["files"]
    assert "raw-agp.aab" in report["files"]
    assert not (tmp_path / "evidence/play-unsigned.aab").exists()
    assert json.loads((tmp_path / "evidence/EVIDENCE.json").read_text()) == report


def test_public_bundle_must_match_canonical_worker_audit_and_reference_apk(tmp_path: Path) -> None:
    import pytest

    build = fixture(tmp_path)
    audit = build / "outputs/canonical-bundle-audit/release"
    final = build / "outputs/bundle/release/app-release.aab"
    apk = build / "outputs/apk/release/app.apk"
    raw = audit / "raw-agp.aab"
    (audit / "SHA256SUMS").write_text(
        collector.digest(raw) + "  raw-agp.aab\n" + collector.digest(apk) + "  reference.apk\n" + collector.digest(final) + "  canonical.aab\n"
    )
    collector.verify_canonical(tmp_path)
    final.write_bytes(raw.read_bytes())
    with pytest.raises(ValueError, match="canonical.aab"):
        collector.verify_canonical(tmp_path)
    final.write_bytes(b"canonical")
    apk.write_bytes(b"wrong variant")
    with pytest.raises(ValueError, match="reference.apk"):
        collector.verify_canonical(tmp_path)
