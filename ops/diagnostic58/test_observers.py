"""Negative controls for diagnostic interpretation and artifact privacy."""
import asyncio
import io
import json
from pathlib import Path
import stat
import tempfile
import unittest
import zipfile
from unittest.mock import patch

from dcompany_diag_plugin import await_chain, ReadOnlyDatabaseObserver, make_wait_observer
from emulator_compare import parse_round, validate_archive, BoundedDrain


class DiagnosticControls(unittest.TestCase):
    def report(self, codes=(1, 0), terminal=(-1,), extra=""):
        expected = {"fixture.Class#test"}
        lines = []
        for code in codes:
            lines.extend(["INSTRUMENTATION_STATUS: class=fixture.Class", "INSTRUMENTATION_STATUS: test=test", "INSTRUMENTATION_STATUS: numtests=1", f"INSTRUMENTATION_STATUS_CODE: {code}"])
        lines.append("OK (1 test)")
        lines.extend(f"INSTRUMENTATION_CODE: {code}" for code in terminal)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "raw.log"
            path.write_text("\n".join(lines) + "\n" + extra)
            return parse_round(path, expected)

    def test_complete_status_pair(self):
        self.assertTrue(self.report()["pass"])

    def test_duplicate_start_rejected(self):
        self.assertFalse(self.report((1, 1, 0))["pass"])

    def test_missing_start_rejected(self):
        self.assertFalse(self.report((0,))["pass"])

    def test_missing_completion_rejected(self):
        self.assertFalse(self.report((1,))["pass"])

    def test_failure_rejected(self):
        self.assertFalse(self.report((1, -2))["pass"])

    def test_assumption_skip_rejected(self):
        self.assertFalse(self.report((1, -4))["pass"])

    def test_bad_terminal_rejected(self):
        self.assertFalse(self.report(terminal=(0,))["pass"])

    def test_missing_terminal_rejected(self):
        self.assertFalse(self.report(terminal=())["pass"])

    def test_duplicate_terminal_rejected(self):
        self.assertFalse(self.report(terminal=(-1, -1))["pass"])

    def test_completion_before_start_rejected(self):
        self.assertFalse(self.report((0, 1))["pass"])

    def test_abort_marker_rejected(self):
        self.assertFalse(self.report(extra="INSTRUMENTATION_ABORTED: abandoned")["pass"])

    def test_failed_marker_rejected(self):
        self.assertFalse(self.report(extra="INSTRUMENTATION_FAILED: abandoned")["pass"])

    def test_status_after_terminal_rejected(self):
        self.assertFalse(self.report(extra="INSTRUMENTATION_STATUS: numtests=1")["pass"])

    def test_unfinished_bundle_before_terminal_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "raw.log"
            path.write_text("\n".join([
                "INSTRUMENTATION_STATUS: class=fixture.Class",
                "INSTRUMENTATION_STATUS: test=test",
                "INSTRUMENTATION_STATUS: numtests=1",
                "INSTRUMENTATION_STATUS_CODE: 1",
                "INSTRUMENTATION_STATUS: class=fixture.Class",
                "INSTRUMENTATION_STATUS: test=test",
                "INSTRUMENTATION_STATUS_CODE: 0",
                "OK (1 test)",
                "INSTRUMENTATION_STATUS: class=unfinished",
                "INSTRUMENTATION_CODE: -1",
            ]))
            self.assertFalse(parse_round(path, {"fixture.Class#test"})["pass"])

    def test_native_await_types_only(self):
        class Unknown:
            @property
            def cr_frame(self):
                raise AssertionError("Custom properties must not be evaluated")
        self.assertEqual(await_chain(Unknown()), [{"opaque_await_type": "Unknown"}])

    def test_delegate_outcomes_survive_snapshot_failure(self):
        sentinel = object()
        value = object()
        class Observer:
            def snapshot(self):
                raise OSError("secret must never be emitted")
        for original_error in (None, TimeoutError("original"), asyncio.CancelledError("original")):
            with self.subTest(error=type(original_error).__name__):
                calls, records, errors = [], [], []
                async def delegate(awaitable, timeout):
                    calls.append((awaitable, timeout))
                    if original_error is not None:
                        raise original_error
                    return value
                observed = make_wait_observer(delegate, lambda *_: "body_entry_setup", Observer(), records, errors)
                async def exercise():
                    if original_error is None:
                        self.assertIs(await observed(sentinel, 2), value)
                    else:
                        try:
                            await observed(sentinel, 2)
                        except BaseException as caught:
                            self.assertIs(caught, original_error)
                        else:
                            self.fail("Original exception was lost")
                asyncio.run(exercise())
                self.assertEqual(calls, [(sentinel, 2)])
                self.assertNotIn("secret must", json.dumps(errors))

    def test_stage_selector_failure_preserves_delegate(self):
        async def delegate(awaitable, timeout):
            return awaitable
        def broken(*_):
            raise OSError("private detail")
        errors = []
        observed = make_wait_observer(delegate, broken, None, [], errors)
        self.assertEqual(asyncio.run(observed("value", 2)), "value")
        self.assertEqual(errors, [{"operation": "select_stage", "error_type": "OSError"}])

    def test_non_target_wait_is_unobserved(self):
        records, errors = [], []
        async def delegate(awaitable, timeout):
            return awaitable
        observed = make_wait_observer(delegate, lambda *_: None, None, records, errors)
        self.assertEqual(asyncio.run(observed("value", 17)), "value")
        self.assertEqual((records, errors), ([], []))

    def test_original_wait_for_keyword_contract_is_preserved(self):
        value = object()
        async def delegate(fut, timeout):
            self.assertIs(fut, value)
            self.assertIsNone(timeout)
            return fut
        observed = make_wait_observer(delegate, lambda *_: None, None, [], [])
        self.assertIs(asyncio.run(observed(fut=value, timeout=None)), value)

    def test_log_cap_keeps_draining(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "bounded.log"
            drain = BoundedDrain(io.BytesIO(b"a" * 100), path, 7)
            drain.thread.start()
            drain.thread.join(timeout=1)
            self.assertEqual(path.read_bytes(), b"a" * 7)
            self.assertEqual(drain.summary(), {"observed_bytes": 100, "retained_bytes": 7, "truncated": True, "error_type": None, "drain_finished": True})

    def test_log_open_failure_still_drains(self):
        with tempfile.TemporaryDirectory() as directory:
            drain = BoundedDrain(io.BytesIO(b"a" * 100), Path(directory) / "absent/bounded.log", 7)
            drain.thread.start()
            drain.thread.join(timeout=1)
            self.assertEqual(drain.observed_bytes, 100)
            self.assertEqual(drain.error_type, "FileNotFoundError")

    def test_await_snapshot_excludes_locals(self):
        async def fixture():
            private_value = "must-never-be-captured-credential"
            await asyncio.sleep(10)
            return private_value
        coroutine = fixture()
        try:
            snapshot = json.dumps(await_chain(coroutine))
            self.assertNotIn("must-never-be-captured", snapshot)
            self.assertIn("fixture", snapshot)
        finally:
            coroutine.close()

    def test_pg_ring_is_bounded(self):
        observer = ReadOnlyDatabaseObserver()
        for index in range(200):
            observer.append({"index": index})
        self.assertEqual(len(observer.snapshot()), 128)
        self.assertEqual(observer.snapshot()[0], {"index": 72})

    def test_zip_confinement(self):
        for name in ("../escape", "/emulator/absolute", "emulator/../escape"):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "archive.zip"
                with zipfile.ZipFile(path, "w") as archive:
                    archive.writestr(name, "untrusted")
                with self.assertRaises(AssertionError):
                    validate_archive(path)

    def test_symlink_escape_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "archive.zip"
            entry = zipfile.ZipInfo("emulator/lib/link")
            entry.external_attr = (stat.S_IFLNK | 0o777) << 16
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr(entry, "../../../outside")
            with self.assertRaises(AssertionError):
                validate_archive(path)

    def test_safe_relative_symlink(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "archive.zip"
            entry = zipfile.ZipInfo("emulator/lib/link")
            entry.external_attr = (stat.S_IFLNK | 0o777) << 16
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr(entry, "../lib64/library.so")
            validate_archive(path)


if __name__ == "__main__":
    unittest.main()
