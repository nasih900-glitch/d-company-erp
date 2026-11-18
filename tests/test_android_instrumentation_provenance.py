"""Behavioral checks for actual source/APK evidence binding."""

from __future__ import annotations

import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "instrumentation_provenance", ROOT / "scripts/android_instrumentation_provenance.py"
)
assert SPEC and SPEC.loader
PROVENANCE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PROVENANCE)


class AndroidInstrumentationProvenanceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        self.source = self.root / "android-native/app/src/main/Screen.kt"
        self.source.parent.mkdir(parents=True)
        self.source.write_text("source bytes\n")
        (self.root / ".gitignore").write_text("android-native/app/build/\n")
        subprocess.run(["git", "add", "."], cwd=self.root, check=True)
        outputs = self.root / "android-native/app/build/outputs"
        outputs.mkdir(parents=True)
        self.target = outputs / "app.apk"
        self.test = outputs / "tests.apk"
        self.target.write_bytes(b"real target archive bytes")
        self.test.write_bytes(b"real test archive bytes")
        self.evidence = outputs / "evidence"

    def create(self) -> dict:
        receipt = PROVENANCE.create(self.root, self.target, self.test, self.evidence)
        self.receipt_digest = PROVENANCE.sha256(self.evidence / "provenance.json")
        return receipt

    def verify(self) -> dict:
        return PROVENANCE.verify(self.root, self.evidence, self.receipt_digest)

    def test_manifest_binds_dirty_and_new_source_bytes_and_real_apks(self) -> None:
        self.source.write_text("dirty source bytes\n")
        new_source = self.source.with_name("New.kt")
        new_source.write_text("new source bytes\n")
        receipt = self.create()
        manifest = (self.evidence / "source-inventory.json").read_bytes()
        rows = json.loads(manifest)["files"]
        self.assertEqual(sorted(row["path"] for row in rows), [row["path"] for row in rows])
        self.assertEqual(2, len(rows))
        self.assertEqual(hashlib.sha256(manifest).hexdigest(), receipt["source_inventory_sha256"])
        self.assertEqual(hashlib.sha256(self.target.read_bytes()).hexdigest(), receipt["target_apk"]["sha256"])
        self.assertEqual(hashlib.sha256(self.test.read_bytes()).hexdigest(), receipt["test_apk"]["sha256"])
        for key in ("capture_run_id", "persistence_run_id"):
            self.assertRegex(receipt[key], r"^[0-9a-f]{32}$")
        self.assertEqual(receipt, self.verify())
        self.assertEqual(manifest, PROVENANCE.source_manifest(self.root))
        self.source.write_text("mutated after capture\n")
        with self.assertRaisesRegex(ValueError, "Source/build inputs changed"):
            self.verify()

    def test_apk_and_retained_manifest_tampering_fail_closed(self) -> None:
        self.create()
        original = self.test.read_bytes()
        self.test.write_bytes(b"changed APK bytes")
        with self.assertRaisesRegex(ValueError, "test_apk bytes changed"):
            self.verify()
        self.test.write_bytes(original)
        manifest = self.evidence / "source-inventory.json"
        manifest.write_bytes(manifest.read_bytes() + b" ")
        with self.assertRaisesRegex(ValueError, "inventory hash changed"):
            self.verify()

    def test_deleted_tracked_source_and_symlink_inputs_are_rejected(self) -> None:
        original = self.source.read_bytes()
        self.source.unlink()
        with self.assertRaisesRegex(ValueError, "Missing evidence input"):
            self.create()
        self.source.symlink_to(self.target)
        with self.assertRaisesRegex(ValueError, "Symlinked evidence input"):
            self.create()
        self.source.unlink()
        self.source.write_bytes(original)
        self.test.unlink()
        self.test.symlink_to(self.target)
        with self.assertRaisesRegex(ValueError, "Symlinked evidence input"):
            self.create()

    def test_complete_apk_row_swap_is_rejected_by_initial_receipt_digest(self) -> None:
        receipt = self.create()
        receipt["target_apk"], receipt["test_apk"] = receipt["test_apk"], receipt["target_apk"]
        (self.evidence / "provenance.json").write_text(json.dumps(receipt))
        with self.assertRaisesRegex(ValueError, "receipt changed after initial capture"):
            self.verify()

    def test_sibling_protocol_asset_input_is_bound(self) -> None:
        fixture = self.root / "protocol-fixtures" / "contract.json"
        fixture.parent.mkdir()
        fixture.write_text('{"fixture":"original"}\n')
        self.create()
        manifest = json.loads((self.evidence / "source-inventory.json").read_text())
        self.assertIn("protocol-fixtures/contract.json", [row["path"] for row in manifest["files"]])
        fixture.write_text('{"fixture":"changed"}\n')
        with self.assertRaisesRegex(ValueError, "Source/build inputs changed"):
            self.verify()

    def test_invalid_nonce_and_missing_artifact_are_rejected(self) -> None:
        receipt = self.create()
        receipt["persistence_run_id"] = "f" * 64
        (self.evidence / "provenance.json").write_text(json.dumps(receipt))
        self.receipt_digest = PROVENANCE.sha256(self.evidence / "provenance.json")
        with self.assertRaisesRegex(ValueError, "Invalid persistence_run_id"):
            self.verify()
        self.test.unlink()
        with self.assertRaisesRegex(ValueError, "Missing evidence input"):
            self.create()


if __name__ == "__main__":
    unittest.main()
