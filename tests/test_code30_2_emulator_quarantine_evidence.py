from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import shutil

import pytest


ROOT = Path(__file__).resolve().parents[1]
VERIFIER = ROOT / "infra" / "scripts" / "verify-code30-2-emulator-quarantine.py"
INSTALLER = ROOT / "infra" / "scripts" / "install-on-vm.sh"
EVIDENCE = ROOT / "releases" / "evidence" / "code30-2-emulator-quarantine.json"


def _load_verifier():
    spec = importlib.util.spec_from_file_location("quarantine_verifier", VERIFIER)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _document(module) -> dict:
    return {
        "schema_version": 1,
        "captured_at_utc": "2026-09-20T12:30:00Z",
        "package_name": "cloud.dcompany.erp",
        "archive_sha256": module.EXPECTED_ARCHIVE_SHA256,
        "archived_room_unresolved_outbox_count": 0,
        "retired_installation_ids": list(module.EXPECTED_INSTALLATION_IDS),
        "avds": [
            {
                "name": name,
                "factory_reset_or_previously_clean": True,
                "package_absent": True,
                "data_absent": True,
                "network_disabled_during_verification": True,
                "verified_at_utc": "2026-09-20T12:00:00Z",
            }
            for name in module.EXPECTED_AVD_NAMES
        ],
    }


def _write(path: Path, document: dict) -> None:
    path.write_text(
        json.dumps(document, sort_keys=True, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )


def test_exact_complete_quarantine_evidence_is_accepted(tmp_path: Path) -> None:
    verifier = _load_verifier()
    evidence = tmp_path / "evidence.json"
    shutil.copy2(EVIDENCE, evidence)

    digest, count = verifier.verify_evidence(evidence)

    assert len(digest) == 64
    assert count == 18


def test_structurally_valid_changed_evidence_bytes_are_rejected(tmp_path: Path) -> None:
    verifier = _load_verifier()
    document = json.loads(EVIDENCE.read_text(encoding="utf-8"))
    document["captured_at_utc"] = "2026-09-20T23:59:59Z"
    evidence = tmp_path / "changed.json"
    _write(evidence, document)

    with pytest.raises(verifier.EvidenceError, match="reviewed SHA-256"):
        verifier.verify_evidence(evidence)


@pytest.mark.parametrize(
    ("mutation", "expected_message"),
    [
        (lambda document: document.update(archive_sha256="0" * 64), "archive hash"),
        (
            lambda document: document.update(
                archived_room_unresolved_outbox_count=1
            ),
            "unresolved outbox",
        ),
        (lambda document: document["retired_installation_ids"].pop(), "identities"),
        (lambda document: document["avds"].pop(), "complete reviewed AVD"),
        (
            lambda document: document["avds"][0].update(package_absent=False),
            "package_absent",
        ),
        (
            lambda document: document["avds"][0].update(
                network_disabled_during_verification=False
            ),
            "network_disabled_during_verification",
        ),
        (
            lambda document: document["avds"][0].update(
                verified_at_utc="2026-09-20T13:00:00Z"
            ),
            "later than capture",
        ),
    ],
)
def test_incomplete_or_changed_quarantine_is_rejected(
    tmp_path: Path, mutation, expected_message: str
) -> None:
    verifier = _load_verifier()
    document = _document(verifier)
    mutation(document)
    evidence = tmp_path / "evidence.json"
    _write(evidence, document)

    with pytest.raises(verifier.EvidenceError, match=expected_message):
        verifier.verify_evidence(evidence)


def test_duplicate_keys_and_symlink_evidence_are_rejected(tmp_path: Path) -> None:
    verifier = _load_verifier()
    duplicate = tmp_path / "duplicate.json"
    duplicate.write_text('{"schema_version":1,"schema_version":1}\n', encoding="utf-8")
    with pytest.raises(verifier.EvidenceError, match="duplicate JSON key"):
        verifier.verify_evidence(duplicate)

    evidence = tmp_path / "evidence.json"
    _write(evidence, _document(verifier))
    linked = tmp_path / "linked.json"
    linked.symlink_to(evidence)
    with pytest.raises(verifier.EvidenceError, match="regular non-symlink"):
        verifier.verify_evidence(linked)

    real_parent = tmp_path / "real-parent"
    real_parent.mkdir()
    nested = real_parent / "nested.json"
    _write(nested, _document(verifier))
    linked_parent = tmp_path / "linked-parent"
    linked_parent.symlink_to(real_parent, target_is_directory=True)
    with pytest.raises(verifier.EvidenceError, match="regular non-symlink"):
        verifier.verify_evidence(linked_parent / "nested.json")


def test_installer_bridge_is_exact_non_mutating_and_evidence_bound() -> None:
    source = INSTALLER.read_text(encoding="utf-8")

    assert (
        'EMULATOR_QUARANTINE_VERIFIER="$CANDIDATE_BUILD_ROOT/infra/scripts/'
        'verify-code30-2-emulator-quarantine.py"'
    ) in source
    assert (
        'EMULATOR_QUARANTINE_EVIDENCE="$CANDIDATE_BUILD_ROOT/releases/evidence/'
        'code30-2-emulator-quarantine.json"'
    ) in source
    assert '"$EMULATOR_QUARANTINE_VERIFIER" "$EMULATOR_QUARANTINE_EVIDENCE"' in source

    start = source.index("stale_outbox_signature=$(docker exec")
    end = source.index('echo "==> Creating final quiesced pre-upgrade PostgreSQL backup…"')
    bridge = source[start:end]
    assert "CANDIDATE_APP_VERSION\" != 3.1.29" in bridge
    assert "PRIOR_DB_HEAD\" != 0073" in bridge
    assert "92b491f1-c35b-4437-af9a-a6be68035001" in bridge
    assert "d664b4a5-d293-48a7-a96c-8c3050ed5e76" in bridge
    assert "2026-09-19 08:33:01.020059+00" in bridge
    assert '"$stale_outbox_signature" != "1|1|1"' in bridge
    assert 'python3 "$EMULATOR_QUARANTINE_VERIFIER"' in bridge
    assert (
        "sha256=379c6368936d03223e19482cc840c2a9d2483dc9a96909fba22cd9f59911eec8 avds=18"
        in bridge
    )
    assert "UPDATE client_installations" not in bridge
    assert "DELETE FROM client_installations" not in bridge
    assert source.index('python3 "$EMULATOR_QUARANTINE_VERIFIER"') < end
