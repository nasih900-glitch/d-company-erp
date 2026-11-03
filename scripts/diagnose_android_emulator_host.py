#!/usr/bin/env python3
"""Diagnostic-only owned emulator lifetime/resource probe; never release acceptance."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import re
import selectors
import shutil
import signal
import subprocess
import sys
import threading
import time
from datetime import datetime, timezone

BOARD = 'cloud.dcompany.erp.ui.screens.gaming.GamingBoardUiTest'
FIRST = BOARD + '#compactAvailablePs5StartEditorRecoversAfterInitialStationSyncAt800By640'
BASE = 'c16431b5503639e191a9e9fcd8414b52630051ca'
CGROUP_FILES = (
    'memory.current', 'memory.max', 'memory.peak', 'memory.events', 'memory.events.local',
    'memory.pressure', 'memory.swap.current', 'memory.swap.max', 'memory.oom.group',
    'pids.current', 'pids.max', 'pids.peak', 'cpu.max', 'cpuset.cpus.effective',
    'memory.usage_in_bytes', 'memory.limit_in_bytes', 'memory.max_usage_in_bytes',
    'memory.failcnt', 'memory.oom_control', 'memory.memsw.usage_in_bytes',
)


def require(value, message):
    if not value:
        raise RuntimeError(message)


def read(path, limit=32768):
    try:
        with Path(path).open('rb') as stream:
            data = stream.read(limit + 1)
        return {'status': 'read', 'text': data[:limit].decode(errors='replace'), 'truncated': len(data) > limit}
    except OSError as error:
        return {'status': 'unknown', 'error': type(error).__name__, 'errno': error.errno}


def identity(pid):
    try:
        root = Path('/proc') / str(pid)
        raw = (root / 'stat').read_text()
        fields = raw.rsplit(')', 1)[1].split()
        result = {'pid': pid, 'ppid': int(fields[1]), 'pgrp': int(fields[2]),
                  'session': int(fields[3]), 'start_ticks': int(fields[19]),
                  'uid': root.stat().st_uid, 'exe': str((root / 'exe').resolve(strict=True)),
                  'comm': raw[raw.index('(') + 1:raw.rindex(')')]}
        again = (root / 'stat').read_text().rsplit(')', 1)[1].split()
        require(result['start_ticks'] == int(again[19]), 'PID changed while observing identity')
        return result
    except (OSError, ValueError, RuntimeError):
        return None


def same_process(expected, actual):
    return actual is not None and all(actual[k] == expected[k] for k in ('pid', 'uid', 'exe', 'start_ticks', 'session'))


def signal_owned(expected, sig):
    """Never signal a process group unless the still-live group leader is ours."""
    actual = identity(expected['pid'])
    require(same_process(expected, actual) and actual['pgrp'] == actual['session'] == actual['pid'],
            'Owned process identity changed; refusing signal')
    os.killpg(actual['pgrp'], sig)


def observe_child_session(process):
    """Observe this unreaped child through short exec transitions, never guess identity."""
    deadline = time.monotonic() + .5
    while True:
        bound = identity(process.pid)
        if bound is not None or process.poll() is not None or time.monotonic() >= deadline:
            return bound
        time.sleep(.01)


def child_exit(process, verified_qemu, planned_cleanup):
    code = process.poll()
    if code is None:
        return None
    number = -code if code < 0 else None
    try:
        label = signal.Signals(number).name if number is not None else None
    except ValueError:
        label = 'signal-' + str(number)  # Linux realtime values need not be enum members.
    return {'returncode': code, 'signal_number': number, 'signal': label,
            'observed_as_qemu': verified_qemu, 'planned_cleanup_already_marked': planned_cleanup}


def cgroup_targets(member_text, mount_text):
    """Resolve controller paths within their actual mounted cgroup filesystem."""
    members = []
    for line in member_text.splitlines():
        _, controllers, path = line.split(':', 2)
        require(path.startswith('/') and '..' not in PurePosixPath(path).parts, 'Unsafe cgroup membership path')
        members.append((set(controllers.split(',')), path, controllers == ''))
    targets = []
    for line in mount_text.splitlines():
        left, right = line.split(' - ', 1)
        fields, options = left.split(), right.split()
        if options[0] not in ('cgroup', 'cgroup2'):
            continue
        decode = lambda s: re.sub(r'\\([0-7]{3})', lambda m: chr(int(m[1], 8)), s)
        mount_root, mountpoint = PurePosixPath(decode(fields[3])), Path(decode(fields[4]))
        for controllers, member, unified in members:
            if unified != (options[0] == 'cgroup2'):
                continue
            if not unified and not controllers.intersection(set(options[2].split(','))):
                continue
            try:
                relative = PurePosixPath(member).relative_to(mount_root)
            except ValueError:
                continue  # Namespace mapping is unknown, never guess another root.
            canonical_mount = mountpoint.resolve(strict=True)
            target = (canonical_mount / relative).resolve(strict=True)
            require(target.is_relative_to(canonical_mount), 'Cgroup target escaped its mount')
            if (str(target), str(canonical_mount)) not in targets:
                targets.append((str(target), str(canonical_mount)))
    return targets


def cgroup_snapshot(targets):
    result = []
    for target, mount in targets:
        path, boundary = Path(target), Path(mount)
        for _ in range(64):
            result.append({'path': str(path), 'mount_boundary': str(boundary),
                           'files': {name: read(path / name) for name in CGROUP_FILES}})
            if path == boundary:
                break
            require(path.is_relative_to(boundary), 'Cgroup ancestor escaped mount')
            path = path.parent
        else:
            raise RuntimeError('Cgroup hierarchy exceeded observation bound')
    return result


def process_resources(bound):
    if not same_process(bound, identity(bound['pid'])):
        return {'identity': bound, 'status': 'gone_or_changed'}
    root = Path('/proc') / str(bound['pid'])
    status = read(root / 'status')
    if status['status'] == 'read':
        status['text'] = '\n'.join(line for line in status['text'].splitlines()
                                   if line.split(':', 1)[0] in {'Name', 'State', 'PPid', 'Uid', 'Threads', 'VmRSS', 'VmHWM', 'VmPeak', 'VmSwap'})
    return {'identity': bound, 'status': status, 'limits': read(root / 'limits'),
            'oom_score': read(root / 'oom_score'), 'oom_score_adj': read(root / 'oom_score_adj')}


class Recorder:
    def __init__(self, path):
        self.stream = path.open('x', encoding='utf-8')
        self.lock = threading.Lock()

    def write(self, kind, **values):
        with self.lock:
            self.stream.write(json.dumps({'kind': kind, 'epoch': time.time(), 'monotonic': time.monotonic(), **values}) + '\n')
            self.stream.flush()
            os.fsync(self.stream.fileno())

    def close(self):
        self.stream.close()


def bounded_capture(argv, output, timeout=4, byte_limit=262144):
    """Drain output without retaining more than byte_limit; deadline includes pipe EOF."""
    process = subprocess.Popen(argv, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, start_new_session=True)
    observed = retained = 0
    timed_out = False
    deadline = time.monotonic() + timeout
    with output.open('wb') as stream, selectors.DefaultSelector() as selector:
        selector.register(process.stdout, selectors.EVENT_READ)
        try:
            while selector.get_map():
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    timed_out = True
                    break
                for key, _ in selector.select(min(.1, remaining)):
                    data = os.read(key.fd, 8192)
                    if not data:
                        selector.unregister(key.fileobj)
                        continue
                    observed += len(data)
                    prefix = data[:max(0, byte_limit - retained)]
                    stream.write(prefix)
                    retained += len(prefix)
        finally:
            if process.poll() is None and timed_out:
                bound = identity(process.pid)
                require(bound is not None, 'Capture identity unknown; refusing unbound timeout signal')
                signal_owned(bound, signal.SIGKILL)
            process.stdout.close()
            code = process.wait(timeout=2)
    return {'returncode': code, 'timed_out': timed_out, 'bytes': retained,
            'observed_output_bytes': observed, 'truncated': observed > retained,
            'status': 'read' if code == 0 and not timed_out else 'unknown'}


def session_admits(row, sessions, proven, allowed_leader_executables):
    """A recycled numeric session alone never establishes process ownership."""
    previous = proven.get((row['pid'], row['start_ticks']))
    if previous is not None and same_process(previous, row):
        return True
    leader = sessions.get(row['session'])
    actual = identity(leader['pid']) if leader is not None else None
    return (actual is not None
            and all(actual[k] == leader[k] for k in ('pid', 'uid', 'start_ticks', 'session', 'pgrp'))
            and actual['exe'] in allowed_leader_executables.get(leader['pid'], set())
            and row['uid'] == leader['uid'] and row['start_ticks'] >= leader['start_ticks'])


def cleanup_unobserved_child(process, sdk, recorder):
    # A reaped child's numeric PID is never sufficient authority to signal.
    if process.poll() is not None:
        return
    current = identity(process.pid)
    if current and current['uid'] == os.getuid() and current['session'] == current['pgrp'] == process.pid and Path(current['exe']).is_relative_to(sdk / 'emulator'):
        recorder.write('planned_operator_cleanup', reason='startup identity incomplete')
        signal_owned(current, signal.SIGKILL)
        process.wait(timeout=5)


def postmortem(output, started_utc):
    commands = {
        'kernel': ['journalctl', '-k', '--since', started_utc, '--no-pager', '-n', '100', '-o', 'short-iso'],
        'oomd': ['journalctl', '-u', 'systemd-oomd', '--since', started_utc, '--no-pager', '-n', '100', '-o', 'short-iso'],
        'oomd-state': ['systemctl', 'show', 'systemd-oomd', '--property=LoadState,ActiveState,SubState'],
    }
    result = {}
    for name, argv in commands.items():
        try:
            result[name] = bounded_capture(argv, output / (name + '.txt'))
            text = read(output / (name + '.txt'))
            if text['status'] != 'read' or any(marker in text.get('text', '').lower() for marker in
                    ('permission denied', 'not seeing messages', 'no journal files', 'failed to open')):
                result[name]['status'] = 'unknown'
        except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
            result[name] = {'status': 'unknown', 'error': type(error).__name__}
    (output / 'journal-status.json').write_text(json.dumps(result, indent=2) + '\n')


def load_verifier(repo):
    spec = importlib.util.spec_from_file_location('probe_existing_shard_verifier', repo / 'scripts/verify_android_instrumentation_shards.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def validate_results(verifier, lane, expected):
    observed, skipped, failures = verifier._read_lane(lane)
    require(not failures and not skipped and len(observed) == len(expected) and set(observed) == set(expected),
            'Instrumentation did not complete its exact successful inventory')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', choices=('36.5.10', '37.1.11'), required=True)
    parser.add_argument('--build', choices=('15081367', '15917651'), required=True)
    args = parser.parse_args()
    require((args.version, args.build) in {('36.5.10', '15081367'), ('37.1.11', '15917651')}, 'Version/build pair differs')
    require(sys.platform == 'linux', 'Host probe requires Linux /proc and cgroups')
    repo = Path(__file__).resolve().parents[1]
    require(subprocess.run(['git', 'merge-base', '--is-ancestor', BASE, 'HEAD'], cwd=repo).returncode == 0, 'Diagnostic source is not based on admittedc164')
    sdk = Path(os.environ['ANDROID_HOME']).resolve(strict=True)
    emulator = sdk / 'emulator/emulator'
    adb = [str(sdk / 'platform-tools/adb'), '-s', 'emulator-5554']
    avd_root = Path(os.environ.get('ANDROID_AVD_HOME', str(Path.home() / '.android/avd'))).resolve(strict=True)
    avd = avd_root / 'test.avd'
    require(avd.is_dir() and (avd / 'config.ini').is_file(), 'Expected ephemeral testAVD missing')
    output = repo / 'android-native/app/build/outputs/qemu-host-diagnostic' / args.version
    output.mkdir(parents=True, exist_ok=False)
    recorder = Recorder(output / 'host-events.jsonl')
    started_utc = datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M:%S UTC')
    version = subprocess.check_output([str(emulator), '-version'], stderr=subprocess.STDOUT, text=True, timeout=10)
    (output / 'emulator-version.txt').write_text(version)
    require(args.version in version and args.build in version, 'Installed emulator is not requested pinned version/build')
    require(subprocess.check_output(adb + ['emu', 'avd', 'name'], text=True, timeout=10).splitlines()[0].strip() == 'test', 'Initial emulator serial is not testAVD')
    require(subprocess.check_output(adb + ['shell', 'getprop', 'ro.kernel.qemu'], text=True, timeout=10).strip() == '1', 'Initial device is not emulator')
    initial_boot = subprocess.check_output(adb + ['shell', 'cat', '/proc/sys/kernel/random/boot_id'], text=True, timeout=10).strip()
    require(not subprocess.check_output(adb + ['shell', 'pm', 'list', 'packages', 'cloud.dcompany.erp'], text=True, timeout=10).strip(), 'Initial guest is not empty of ERP packages')
    candidates = []
    for item in Path('/proc').iterdir():
        if not item.name.isdigit():
            continue
        bound = identity(int(item.name))
        if bound is None or bound['uid'] != os.getuid() or not Path(bound['exe']).is_relative_to(sdk / 'emulator') or not Path(bound['exe']).name.startswith('qemu-system-'):
            continue
        raw = read(item / 'cmdline', 131072)
        if raw['status'] != 'read':
            continue
        words = raw['text'].split('\0')
        if all(flag in words and words.index(flag) + 1 < len(words) and words[words.index(flag) + 1] == value for flag, value in [('-avd', 'test'), ('-port', '5554')]):
            candidates.append(bound)
    require(len(candidates) == 1, 'Cannot uniquely bind the action-owned initial QEMU')
    initial = candidates[0]
    recorder.write('initial_vm_bound', identity=initial, version=args.version, build=args.build)
    require(subprocess.check_output(adb + ['emu', 'avd', 'name'], text=True, timeout=10).splitlines()[0].strip() == 'test', 'Initial serial changed before stop')
    require(subprocess.check_output(adb + ['shell', 'cat', '/proc/sys/kernel/random/boot_id'], text=True, timeout=10).strip() == initial_boot, 'Initial guest changed before stop')
    require(same_process(initial, identity(initial['pid'])), 'Initial QEMU identity changed before specific emukill')
    subprocess.run(adb + ['emu', 'kill'], check=True, timeout=10)
    end = time.monotonic() + 25
    while same_process(initial, identity(initial['pid'])) and time.monotonic() < end:
        time.sleep(.1)
    require(not same_process(initial, identity(initial['pid'])), 'Initial action-owned VM did not stop; refusing duplicate launch')
    argv = [str(emulator), '-port', '5554', '-avd', 'test', '-no-window', '-gpu', 'swiftshader', '-feature', '-Vulkan', '-no-snapshot', '-noaudio', '-no-boot-anim']
    stream = (output / 'emulator-stdout-stderr.txt').open('wb')
    process = subprocess.Popen(argv, stdin=subprocess.DEVNULL, stdout=stream, stderr=subprocess.STDOUT, start_new_session=True)
    bound = observe_child_session(process)
    if bound is None or not (bound['uid'] == os.getuid() and bound['session'] == bound['pgrp'] == process.pid):
        # A child that exits before /proc observation has an actual but unattributed exit.
        # Never signal a PID whose identity could not be established.
        recorder.write('startup_identity_unknown', exit=child_exit(process, False, False))
        cleanup_unobserved_child(process, sdk, recorder)
        stream.close()
        recorder.close()
        raise RuntimeError('Direct child session identity not observed; no QEMU exit attribution')
    sessions = {process.pid: bound}
    leader_executables = {process.pid: {str(emulator.resolve())}}
    known = {}
    targets = []
    last_target_validation = None
    stopping = threading.Event()
    observer_failed = threading.Event()
    sample_lock = threading.Lock()
    planned = False
    verified = False
    qemu_identity = None
    workload_failure = None

    def take_sample():
        nonlocal verified, targets, qemu_identity, last_target_validation
        current_resolution = False
        current = identity(process.pid)
        if current and current['uid'] == bound['uid'] and current['start_ticks'] == bound['start_ticks']:
            if Path(current['exe']).is_relative_to(sdk / 'emulator') and Path(current['exe']).name.startswith('qemu-system-'):
                verified = True
                qemu_identity = current
                leader_executables[process.pid].add(current['exe'])
            known[(current['pid'], current['start_ticks'])] = current
            try:
                member = read(Path('/proc') / str(process.pid) / 'cgroup')
                mount = read('/proc/self/mountinfo', 262144)
                if member['status'] == mount['status'] == 'read':
                    resolved_targets = cgroup_targets(member['text'], mount['text'])
                    if resolved_targets:
                        targets = resolved_targets
                        last_target_validation = time.time()
                        current_resolution = True
            except (OSError, ValueError, RuntimeError) as error:
                recorder.write('cgroup_resolution_unknown', error=type(error).__name__)
        for item in Path('/proc').iterdir():
            if not item.name.isdigit():
                continue
            row = identity(int(item.name))
            if row and row['uid'] == os.getuid() and session_admits(row, sessions, known, leader_executables):
                if row['comm'].startswith(('java', 'qemu', 'emulator', 'gradle')):
                    known[(row['pid'], row['start_ticks'])] = row
        recorder.write('host_sample', processes=[process_resources(v) for v in known.values()],
                       cgroup_resolution='current' if current_resolution else ('last_known' if targets else 'unknown'),
                       cgroup_last_validated_epoch=last_target_validation,
                       cgroups=cgroup_snapshot(targets), meminfo=read('/proc/meminfo'),
                       pressure={k: read('/proc/pressure/' + k) for k in ('memory', 'cpu', 'io')},
                       child_exit=child_exit(process, verified, planned))

    def sample():
        with sample_lock:
            take_sample()

    def watch():
        try:
            while not stopping.is_set():
                sample()
                if process.poll() is not None:
                    return
                stopping.wait(1)
        except BaseException as error:
            observer_failed.set()
            recorder.write('observer_error', error=type(error).__name__)

    thread = threading.Thread(target=watch, daemon=True)
    thread.start()

    def command(label, command_argv, timeout):
        require(process.poll() is None, 'QEMU exited before ' + label)
        path = output / (label + '.log')
        recorder.write('command_start', label=label)
        with path.open('wb') as sink:
            command_process = subprocess.Popen(command_argv, cwd=repo / 'android-native', stdin=subprocess.DEVNULL,
                                               stdout=sink, stderr=subprocess.STDOUT, start_new_session=True)
            command_bound = observe_child_session(command_process)
            if command_bound is not None:
                sessions[command_process.pid] = command_bound
                leader_executables[command_process.pid] = {str(Path('/bin/sh').resolve()), str(Path('/bin/bash').resolve()),
                    str((Path(os.environ['JAVA_HOME']) / 'bin/java').resolve()), str(Path(adb[0]).resolve())}
            deadline = time.monotonic() + timeout
            primary_failure = None
            try:
                require(command_bound is not None or command_process.poll() is not None, 'Command identity not observed')
                while command_process.poll() is None:
                    require(process.poll() is None, 'QEMU exited during ' + label)
                    require(not observer_failed.is_set(), 'Host observer failed; evidence incomplete')
                    require(time.monotonic() < deadline, 'Command deadline reached: ' + label)
                    time.sleep(.1)
                require(command_process.returncode == 0, 'Command failed: ' + label)
            except BaseException as error:
                primary_failure = error
                raise
            finally:
                if command_process.poll() is None:
                    try:
                        current = identity(command_process.pid)
                        require(command_bound is not None and current is not None and all(current[k] == command_bound[k] for k in ('pid', 'uid', 'start_ticks', 'session')),
                                'Command ownership changed before cleanup')
                        allowed = {str(Path('/bin/sh').resolve()), str(Path('/bin/bash').resolve()),
                                   str((Path(os.environ['JAVA_HOME']) / 'bin/java').resolve()), str(Path(adb[0]).resolve())}
                        require(current['exe'] in allowed, 'Command executable is not a proven Gradle/Java/ADB child')
                        signal_owned(current, signal.SIGKILL)
                        command_process.wait(timeout=5)
                    except BaseException as cleanup_error:
                        recorder.write('command_cleanup_error', label=label, error=type(cleanup_error).__name__)
                        if primary_failure is None:
                            raise
                        primary_failure.add_note('Owned command cleanup also failed: ' + type(cleanup_error).__name__)
        recorder.write('command_end', label=label, returncode=command_process.returncode)
        return path

    try:
        end = time.monotonic() + 300
        while True:
            require(process.poll() is None, 'Direct emulator exited before boot')
            result = subprocess.run(adb + ['shell', 'getprop', 'sys.boot_completed'], capture_output=True, text=True, timeout=4)
            if result.returncode == 0 and result.stdout.strip() == '1':
                break
            require(time.monotonic() < end, 'Owned emulator boot deadline reached')
            time.sleep(1)
        require(verified, 'Running direct child was never verified as QEMU; exit cannot be attributed')
        subprocess.run(adb + ['shell', 'input', 'keyevent', '82'], check=True, timeout=5)
        for key in ('window_animation_scale', 'transition_animation_scale', 'animator_duration_scale'):
            subprocess.run(adb + ['shell', 'settings', 'put', 'global', key, '0'], check=True, timeout=5)
        subprocess.run(adb + ['shell', 'cmd', 'overlay', 'enable-exclusive', '--category', 'com.android.internal.systemui.navbar.gestural'], check=True, timeout=5)
        config = {name: read(avd / name, 131072) for name in ('config.ini', 'hardware-qemu.ini')}
        (output / 'actual-avd-config.json').write_text(json.dumps(config, indent=2) + '\n')
        guest_captures = [
            ('guest-memory', ['shell', 'cat', '/proc/meminfo']),
            ('guest-cpu', ['shell', 'cat', '/sys/devices/system/cpu/online']),
            ('guest-size', ['shell', 'wm', 'size']),
            ('guest-density', ['shell', 'wm', 'density']),
            ('guest-renderer', ['shell', 'dumpsys', 'SurfaceFlinger']),
            ('guest-fingerprint', ['shell', 'getprop', 'ro.build.fingerprint']),
            ('guest-sdk', ['shell', 'getprop', 'ro.build.version.sdk']),
            ('guest-abi', ['shell', 'getprop', 'ro.product.cpu.abi']),
            ('guest-boot-id', ['shell', 'cat', '/proc/sys/kernel/random/boot_id']),
        ]
        for label, cmd in guest_captures:
            result = bounded_capture(adb + cmd, output / (label + '.txt'))
            recorder.write('guest_capture', label=label, **result)
            if label in {'guest-sdk', 'guest-abi', 'guest-fingerprint'}:
                require(result['status'] == 'read' and not result['truncated'], 'Guest identity capture failed')
        require((output / 'guest-sdk.txt').read_text().strip() == '35', 'Guest SDK is not35')
        require((output / 'guest-abi.txt').read_text().strip() == 'x86_64', 'Guest ABI is not x86_64')
        require(bool((output / 'guest-fingerprint.txt').read_text().strip()), 'Guest fingerprint missing')
        require((output / 'guest-cpu.txt').read_text().strip() == '0-1', 'Guest CPU online is not0-1')
        recorder.write('runner_limits', limits=read('/proc/self/limits'), core_pattern=read('/proc/sys/kernel/core_pattern'))
        # Requested profile values and actual37 SDK/RAM auto-overrides are evidence, not forced equal.
        recorder.write('configuration_recorded', requested_profile='pixel_c', requested_cores=2,
                       requested_profile_ram_mb=3072, requested_profile_heap_mb=576,
                       effective_ram_may_be_sdk_overridden=True)
        gradle = ['./gradlew', '--no-daemon', '--max-workers=1', '--stacktrace', '-Dorg.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8', '-Pkotlin.compiler.execution.strategy=in-process']
        command('assemble', gradle + [':app:assembleDebug', ':app:assembleDebugAndroidTest'], 900)
        test_deadline = time.monotonic() + 300
        def budget():
            remaining = test_deadline - time.monotonic()
            require(remaining > 0, 'Five-minute test workload deadline reached')
            return remaining
        for label, path in [('app', repo / 'android-native/app/build/outputs/apk/debug/app-debug.apk'), ('test', repo / 'android-native/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')]:
            require(path.is_file(), 'Expected debug APK missing')
            recorder.write('apk', label=label, sha256=hashlib.sha256(path.read_bytes()).hexdigest(), bytes=path.stat().st_size)
            command('install-' + label, adb + ['install', '-r', '-t', str(path)], min(30, budget()))
        verifier = load_verifier(repo)
        runner = 'cloud.dcompany.erp.test/androidx.test.runner.AndroidJUnitRunner'
        discovery = command('board-discovery', adb + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'log', 'true', '-e', 'class', BOARD, runner], min(30, budget()))
        expected = verifier.parse_discovery_log(discovery)
        require(len(expected) == 34 and FIRST in expected and all(x.startswith(BOARD + '#') for x in expected), 'Board discovery is not exact34')
        # Match the existing log-only discovery cleanup before the first UTP lane.
        for package in ('cloud.dcompany.erp.test', 'cloud.dcompany.erp'):
            command('discovery-stop-' + package, adb + ['shell', 'am', 'force-stop', package], min(8, budget()))
            command('discovery-uninstall-' + package, adb + ['uninstall', package], min(15, budget()))
        connected = repo / 'android-native/app/build/outputs/androidTest-results/connected/debug'

        def connected_stage(label, selector, inventory):
            if connected.exists():
                require(not connected.is_symlink() and connected.resolve().is_relative_to(repo / 'android-native/app/build'),
                        'Refusing cleanup outside own generated test outputs')
                shutil.rmtree(connected)
            for package in ('cloud.dcompany.erp.test', 'cloud.dcompany.erp'):
                command(label + '-stop-' + package, adb + ['shell', 'am', 'force-stop', package], min(8, budget()))
            command(label + '-home', adb + ['shell', 'input', 'keyevent', 'KEYCODE_HOME'], min(8, budget()))
            command(label + '-broadcast-idle', adb + ['shell', 'am', 'wait-for-broadcast-idle'], min(8, budget()))
            lane = output / label
            try:
                command(label + '-connected', gradle + ['-Pandroid.testInstrumentationRunnerArguments.class=' + selector,
                                                       ':app:connectedDebugAndroidTest'], budget())
            finally:
                if connected.is_dir():
                    lane.mkdir(parents=True, exist_ok=True)
                    shutil.copytree(connected, lane / 'results')
            validate_results(verifier, lane, inventory)
            recorder.write('inventory_verified', stage=label, count=len(inventory), driver='Gradle/UTP connectedDebugAndroidTest')

        for index in range(3):
            connected_stage('first-case-' + str(index + 1), FIRST, [FIRST])
        connected_stage('board34', BOARD, expected)
        require(process.poll() is None, 'QEMU exited after completed tests')
        require(not observer_failed.is_set(), 'Host observation was incomplete')
        recorder.write('workload_passed', first_case_completions=3, board_completions=34, release_acceptance=False)
    except BaseException as error:
        workload_failure = error
        recorder.write('workload_failed', error=type(error).__name__, message=str(error))
    finally:
        # Capture unsolicited exit and host evidence BEFORE our expected stop marker.
        recorder.write('exit_before_cleanup', observation=child_exit(process, verified, False))
        try:
            sample()
            postmortem(output, started_utc)
        except BaseException as observation_error:
            recorder.write('final_observation_error', error=type(observation_error).__name__)
            if workload_failure is None:
                workload_failure = observation_error
        recorder.write('exit_immediately_before_stop', observation=child_exit(process, verified, planned))
        try:
            if process.poll() is None:
                require(qemu_identity is not None and same_process(qemu_identity, identity(process.pid)), 'Owned QEMU identity changed before cleanup')
                planned = True
                recorder.write('planned_operator_cleanup')
                subprocess.run(adb + ['emu', 'kill'], capture_output=True, timeout=8)
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    signal_owned(qemu_identity, signal.SIGTERM)
                    try:
                        process.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        signal_owned(qemu_identity, signal.SIGKILL)
                        process.wait(timeout=5)
                    raise RuntimeError('Owned QEMU required forced cleanup after planned stop')
        except BaseException as cleanup_error:
            recorder.write('cleanup_error', error=type(cleanup_error).__name__)
            if workload_failure is None:
                workload_failure = cleanup_error
        finally:
            stopping.set()
            thread.join(timeout=10)
            if thread.is_alive():
                observer_failed.set()
                if workload_failure is None:
                    workload_failure = RuntimeError('Host observer did not stop within its bound')
            if observer_failed.is_set() and workload_failure is None:
                workload_failure = RuntimeError('Host observer failed; final evidence incomplete')
            recorder.write('final_child_exit', observation=child_exit(process, verified, planned))
            stream.close()
            if not thread.is_alive():
                recorder.close()
    if workload_failure is not None:
        raise workload_failure


if __name__ == '__main__':
    main()
