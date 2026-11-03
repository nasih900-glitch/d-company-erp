"""Safety/result controls for the diagnostic; no emulator or business APIs used."""
import importlib.util
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('qemu_host_probe', REPO / 'scripts/diagnose_android_emulator_host.py')
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class ProbeControls(unittest.TestCase):
    def test_actual_signal_differs_from_positive_shell_status(self):
        with tempfile.TemporaryDirectory() as directory:
            code = 'import os,resource,signal; resource.setrlimit(resource.RLIMIT_CORE,(0,0)); os.kill(os.getpid(),signal.SIGSEGV)'
            child = subprocess.Popen([sys.executable, '-c', code], cwd=directory)
            child.wait(timeout=5)
            observed = probe.child_exit(child, True, False)
            self.assertEqual(observed['signal'], 'SIGSEGV')
            self.assertFalse(observed['planned_cleanup_already_marked'])
            positive = subprocess.Popen([sys.executable, '-c', 'raise SystemExit(137)'], cwd=directory)
            positive.wait(timeout=5)
            observed = probe.child_exit(positive, False, False)
            self.assertEqual(observed['returncode'], 137)
            self.assertIsNone(observed['signal'])
            self.assertFalse(observed['observed_as_qemu'])
            numeric = probe.child_exit(unittest.mock.Mock(poll=lambda: -35), True, False)
            self.assertEqual(numeric['signal_number'], 35)
            self.assertIsNotNone(numeric['signal'])

    def test_pid_reuse_cannot_signal_another_group(self):
        original = dict(pid=123, uid=os.getuid(), exe='/sdk/emulator/qemu-system-x86_64', start_ticks=20, session=123, pgrp=123)
        reused = {**original, 'start_ticks': 21}
        with patch.object(probe, 'identity', return_value=reused), patch.object(probe.os, 'killpg') as kill:
            with self.assertRaisesRegex(RuntimeError, 'refusing signal'):
                probe.signal_owned(original, signal.SIGTERM)
            kill.assert_not_called()

    def test_recycled_session_does_not_admit_new_processes(self):
        leader = dict(pid=123, uid=os.getuid(), exe='/java', start_ticks=20, session=123, pgrp=123)
        row = {**leader, 'pid': 124, 'start_ticks': 22}
        current_java = {**leader, 'exe': '/java'}
        shell_leader = {**leader, 'exe': '/bin/sh'}
        with patch.object(probe, 'identity', return_value=current_java):
            self.assertTrue(probe.session_admits(row, {123: shell_leader}, {}, {123: {'/bin/sh', '/java'}}))
            self.assertFalse(probe.session_admits(row, {123: shell_leader}, {}, {123: {'/bin/sh'}}))
        reused = {**leader, 'start_ticks': 21}
        with patch.object(probe, 'identity', return_value=reused):
            self.assertFalse(probe.session_admits(row, {123: leader}, {}, {123: {'/java'}}))
            self.assertTrue(probe.session_admits(row, {123: leader}, {(124, 22): row}, {123: {'/java'}}))

    def test_spontaneous_exit_during_evidence_capture_is_not_planned(self):
        child = subprocess.Popen([sys.executable, '-c', 'raise SystemExit(7)'])
        child.wait(timeout=5)
        observed = probe.child_exit(child, True, False)
        self.assertEqual(observed['returncode'], 7)
        self.assertFalse(observed['planned_cleanup_already_marked'])
        # Even an apparently plausible same-number replacement cannot be signaled.
        with patch.object(probe, 'identity') as lookup, patch.object(probe, 'signal_owned') as send:
            probe.cleanup_unobserved_child(child, Path('/sdk'), unittest.mock.Mock())
            lookup.assert_not_called()
            send.assert_not_called()

    def test_capture_retains_bounded_prefix_and_observes_truncation(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'output'
            result = probe.bounded_capture([sys.executable, '-c', 'import os; os.write(1,b"x"*1048576)'], output, byte_limit=127)
            self.assertEqual(output.read_bytes(), b'x' * 127)
            self.assertEqual(result['observed_output_bytes'], 1048576)
            self.assertTrue(result['truncated'])
            self.assertEqual(result['returncode'], 0)

    def test_capture_deadline_terminates_only_its_new_session(self):
        # Synthetic /proc identity supports this Linux-only guard on the macOS host;
        # the owned Popen group and actual timeout/signal behavior are real.
        def own(pid):
            return dict(pid=pid, uid=os.getuid(), exe=sys.executable, start_ticks=1, session=pid, pgrp=pid)
        with tempfile.TemporaryDirectory() as directory, patch.object(probe, 'identity', side_effect=own):
            result = probe.bounded_capture([sys.executable, '-c', 'import time; time.sleep(5)'], Path(directory) / 'timeout', timeout=.1)
        self.assertTrue(result['timed_out'])
        self.assertEqual(result['returncode'], -signal.SIGKILL)
        self.assertEqual(result['status'], 'unknown')

    def test_actual_cgroup_ancestry_keeps_missing_limits_unknown(self):
        with tempfile.TemporaryDirectory() as directory:
            mount = Path(directory).resolve()
            target = mount / 'job' / 'child'
            target.mkdir(parents=True)
            (mount / 'memory.max').write_text('536870912\n')
            (target / 'memory.current').write_text('1234\n')
            mountinfo = f'12 1 0:1 / {mount} rw - cgroup2 cgroup rw\n'
            targets = probe.cgroup_targets('0::/job/child\n', mountinfo)
            snapshots = probe.cgroup_snapshot(targets)
            self.assertEqual([x['path'] for x in snapshots], [str(target), str(target.parent), str(mount)])
            self.assertEqual(snapshots[-1]['files']['memory.max']['text'], '536870912\n')
            self.assertEqual(snapshots[0]['files']['memory.max']['status'], 'unknown')
            with self.assertRaisesRegex(RuntimeError, 'Unsafe cgroup'):
                probe.cgroup_targets('0::/../outside\n', mountinfo)

    def test_aborted_zero_test_xml_cannot_be_a_pass(self):
        verifier = probe.load_verifier(REPO)
        with tempfile.TemporaryDirectory() as directory:
            lane = Path(directory)
            results = lane / 'results'
            results.mkdir()
            xml = results / 'TEST-board.xml'
            xml.write_text('<testsuite tests="0" errors="0" failures="0"/>')
            with self.assertRaisesRegex(RuntimeError, 'exact successful inventory'):
                probe.validate_results(verifier, lane, [probe.FIRST])
            method = probe.FIRST.split('#')[1]
            xml.write_text(f'<testsuite><testcase classname="{probe.BOARD}" name="{method}"/></testsuite>')
            probe.validate_results(verifier, lane, [probe.FIRST])
            xml.write_text(f'<testsuite><testcase classname="{probe.BOARD}" name="{method}"><skipped/></testcase></testsuite>')
            with self.assertRaisesRegex(RuntimeError, 'exact successful inventory'):
                probe.validate_results(verifier, lane, [probe.FIRST])


if __name__ == '__main__':
    unittest.main()
