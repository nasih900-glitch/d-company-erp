"""Production-profile fail-closed controls; real image proof runs in Compose CI."""
from __future__ import annotations

import importlib.util
import os
from pathlib import Path
import subprocess
import sys
from types import ModuleType

import pytest

ROOT = Path(__file__).resolve().parents[1]


@pytest.fixture
def profile():
    spec = importlib.util.spec_from_file_location('runtime_profile', ROOT / 'infra/docker/verify-backend-runtime-profile.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.fixture
def absent_runtime(profile, monkeypatch, tmp_path):
    def mapped_path(value):
        return tmp_path / str(value).lstrip('/')
    monkeypatch.setattr(profile, 'Path', mapped_path)
    monkeypatch.setattr(profile.sysconfig, 'get_path', lambda _: '/stdlib')
    monkeypatch.setattr(profile.shutil, 'which', lambda _: None)
    monkeypatch.setattr(profile.importlib.util, 'find_spec', lambda _: None)

    def missing(name):
        raise ModuleNotFoundError(f'No module named {name}', name=name)
    monkeypatch.setattr(profile.importlib, 'import_module', missing)
    deps = mapped_path('/etc/dcompany')
    deps.mkdir(parents=True)
    (deps / 'python-original-deps.txt').write_text('so:libsqlite3.so.0\nso:libssl.so.3\n')
    (deps / 'python-runtime-deps.txt').write_text('so:libssl.so.3\n')

    def apk(args, **kwargs):
        if args == ['apk', 'info', '-e', 'sqlite-libs']:
            return subprocess.CompletedProcess(args, 1, '', '')
        assert args == ['apk', 'info', '--depends', '.python-rundeps']
        return subprocess.CompletedProcess(args, 0, '.python-rundeps depends on:\nso:libssl.so.3\n', '')
    monkeypatch.setattr(profile.subprocess, 'run', apk)
    return mapped_path


def test_absence_and_retained_provider_proof_accepts_complete_runtime(profile, absent_runtime):
    profile.verify_absence()


@pytest.mark.parametrize('reintroduced', ['library', 'extension', 'wrapper', 'module', 'package', 'missing_provider'])
def test_absence_proof_rejects_reintroduction_or_dependency_loss(profile, absent_runtime, monkeypatch, reintroduced):
    if reintroduced in ('library', 'extension', 'wrapper'):
        relative = {'library': '/usr/lib/libsqlite3.so.0', 'extension': '/usr/local/lib/_sqlite3.fake.so',
                    'wrapper': '/stdlib/sqlite3'}[reintroduced]
        path = absent_runtime(relative)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.touch()
    elif reintroduced == 'module':
        monkeypatch.setattr(profile.importlib.util, 'find_spec', lambda _: object())
    elif reintroduced == 'package':
        monkeypatch.setattr(profile.subprocess, 'run', lambda *args, **kwargs: subprocess.CompletedProcess(args, 0, '', ''))
    else:
        absent_runtime('/etc/dcompany/python-runtime-deps.txt').write_text('')
    with pytest.raises(RuntimeError):
        profile.verify_absence()


def test_complete_application_import_does_not_hide_broken_child(profile, monkeypatch, tmp_path):
    package = ModuleType('app')
    package.__path__ = [str(tmp_path)]
    (tmp_path / 'broken_runtime.py').write_text("raise RuntimeError('missing native runtime dependency')\n")
    monkeypatch.setitem(sys.modules, 'app', package)
    with pytest.raises(RuntimeError, match='missing native runtime dependency'):
        profile.verify_application_imports()


def test_runtime_wiring_audits_wheels_after_pruning_base_and_uses_real_stack():
    docker = (ROOT / 'infra/docker/backend.Dockerfile').read_text()
    prune = docker.index('RUN sh /tmp/prune-python-sqlite.sh\n')
    wheels = docker.index('COPY --from=builder /install /usr/local')
    audit = docker.index('RUN sh /tmp/prune-python-sqlite.sh --after-wheels')
    assert prune < wheels < audit < docker.index('USER erp')
    gate = (ROOT / 'infra/scripts/verify-production-runtime-images.sh').read_text()
    assert 'exec -T backend alembic current' in gate
    assert 'exec -T backend python -B -c' in gate
    assert "runpy.run_path('/usr/local/bin/verify-backend-runtime-profile.py', run_name='__main__')" in gate
    script = (ROOT / 'infra/docker/prune-python-sqlite.sh').read_text()
    assert '--force-broken' not in script
    assert 'apk del --no-network .python-rundeps sqlite-libs' in script


@pytest.mark.parametrize('before_wheels', [True, False])
def test_scanner_child_failure_propagates_through_actual_find(tmp_path, before_wheels):
    # Execute only the extracted non-destructive scanner, never the APK/module
    # pruning main on a developer machine. Real find runs the failing child.
    script = (ROOT / 'infra/docker/prune-python-sqlite.sh').read_text()
    scanner = script.split('\nif [ "${1:-}"', 1)[0]
    executable = tmp_path / 'scanelf'
    executable.write_text('#!/bin/sh\nprintf "scanner-failure\\n" >&2\nexit 7\n')
    executable.chmod(0o755)
    fixture = tmp_path / 'elfs'
    fixture.mkdir()
    elf = fixture / 'retained.so'
    elf.write_bytes(b'\x7fELF')
    elf.chmod(0o755)
    # BSD find uses -perm rather than GNU -executable; the child-status
    # contract is the same POSIX -exec + operation on both platforms.
    filters = ' -perm -111 -not \\( -name "*tkinter*" \\)' if before_wheels else ''
    completed = subprocess.run(['sh', '-c', scanner + '\nscan_python_elf "$1"' + filters + '\nprintf "UNREACHABLE\\n"',
                                'scanner-test', str(fixture)],
                               env={**os.environ, 'PATH': str(tmp_path) + os.pathsep + os.environ['PATH']},
                               capture_output=True, text=True, timeout=10)
    assert completed.returncode != 0
    assert 'scanner-failure' in completed.stderr
    assert 'UNREACHABLE' not in completed.stdout


@pytest.mark.parametrize('version,expected', [('3.1.57', 7), ('3.1.44', 0), ('3.1.45', 0), ('3.1.46', 0)])
def test_new_profile_is_mandatory_only_for_current_runtime(version, expected, tmp_path):
    source = (ROOT / 'infra/scripts/verify-production-runtime-images.sh').read_text()
    marker = '# This new final-image profile is mandatory for Build64.'
    block = source[source.index(marker):].split('\nfi\n', 1)[0] + '\nfi\n'
    fake = tmp_path / 'compose'
    fake.write_text('#!/bin/sh\nexit 7\n')
    fake.chmod(0o755)
    result = subprocess.run(['bash', '-c', 'set -e\nAPP_VERSION=$1\ncompose=("$2")\n' + block,
                             'runtime-profile-test', version, str(fake)], timeout=10)
    assert result.returncode == expected
