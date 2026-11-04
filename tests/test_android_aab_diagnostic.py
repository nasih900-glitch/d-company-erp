"""Synthetic byte-retention controls; no Gradle, network, signing or emulator."""
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('aab_diagnostic', Path(__file__).resolve().parents[1] / 'scripts/diagnose_android_aab_repro.py')
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


def archive(path, pairs, stamp=(1980, 1, 1, 0, 0, 0)):
    with zipfile.ZipFile(path, 'w') as output:
        for name, payload in pairs:
            info = zipfile.ZipInfo(name, stamp)
            output.writestr(info, payload, compress_type=zipfile.ZIP_DEFLATED)


class DiagnosticControls(unittest.TestCase):
    def test_first_mismatch_still_retains_and_compares_all_seven(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); producer = root / 'producer'; rebuilt = root / 'source'; out = root / 'out'
            for folder in (producer, rebuilt, out): folder.mkdir()
            paths = {}
            for name in probe.PAYLOADS:
                if name in probe.ARCHIVES:
                    archive(producer / name, [('classes.dex', b'one'), ('classes3.dex', b'three'), ('classes4.dex', b'four')])
                    archive(rebuilt / name, [('classes.dex', b'one'), ('classes3.dex', b'four' if name == 'play-unsigned.apk' else b'three'), ('classes4.dex', b'three' if name == 'play-unsigned.apk' else b'four')])
                else:
                    (producer / name).write_bytes(b'unchanged'); (rebuilt / name).write_bytes(b'unchanged')
                paths[name] = rebuilt / name
            result = probe.collect(producer, paths, out)
            self.assertEqual(result['status'], 'HOLD_MISMATCH_OR_INCOMPLETE')
            self.assertEqual(len(result['comparisons']), 7)
            self.assertFalse(result['comparisons'][0]['identical'])
            self.assertTrue(all(row['identical'] for row in result['comparisons'][1:]))
            self.assertEqual({p.name for p in (out / 'rebuilt').iterdir()}, set(probe.PAYLOADS))
            self.assertTrue((out / 'ZIP_ENTRY_COMPARISONS.json').is_file())
            for name in probe.ARCHIVES:
                self.assertTrue((out / ('rebuilt-' + name + '-entries.json')).is_file())

    def test_zip_headers_and_dex_order_are_observed_without_normalization(self):
        with tempfile.TemporaryDirectory() as temporary:
            left, right = Path(temporary) / 'left.aab', Path(temporary) / 'right.aab'
            archive(left, [('base/dex/classes4.dex', b'four'), ('base/dex/classes3.dex', b'three')])
            archive(right, [('base/dex/classes3.dex', b'three'), ('base/dex/classes4.dex', b'four')], (1981, 2, 3, 4, 5, 6))
            a, b = probe.archive_entries(left), probe.archive_entries(right)
            self.assertEqual([x['name'] for x in a['entries']], ['base/dex/classes4.dex', 'base/dex/classes3.dex'])
            self.assertNotEqual(a['archive_sha256'], b['archive_sha256'])
            self.assertNotEqual(a['entries'][0]['date_time'], b['entries'][0]['date_time'])
            self.assertEqual({x['name']: x['sha256'] for x in a['entries']}, {x['name']: x['sha256'] for x in b['entries']})
            self.assertEqual(left.read_bytes()[:4], b'PK\x03\x04')

    def test_rewritten_self_checksums_cannot_override_independent_producer_pins(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for name in probe.PRODUCER: (root / name).write_bytes(name.encode())
            pinned = {name: probe.sha(root / name) for name in probe.PRODUCER}
            probe.verify_producer(root, pinned)
            (root / 'play-unsigned.aab').write_bytes(b'tampered')
            # A handoff may self-declare the tampered bytes; independently fixed pins still refuse it.
            (root / 'UNSIGNED_SHA256SUMS').write_text(hashlib.sha256(b'tampered').hexdigest() + '  play-unsigned.aab\n')
            with self.assertRaisesRegex(RuntimeError, 'fixed producer hash differs'):
                probe.verify_producer(root, pinned)


if __name__ == '__main__':
    unittest.main(verbosity=2)
