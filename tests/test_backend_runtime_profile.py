"""Production-profile fail-closed controls; real image proof runs in Compose CI."""
from __future__ import annotations

import importlib.util
import asyncio
import os
from pathlib import Path
import subprocess
import sys
from types import ModuleType, SimpleNamespace
from unittest.mock import AsyncMock, MagicMock

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


@pytest.fixture
def native_elf_fixture(profile, monkeypatch, tmp_path):
    stdlib = tmp_path / 'lib-dynload'
    stdlib.mkdir()
    extension = stdlib / '_ssl.fake.so'
    extension.write_bytes(b'\x7fELFstdlib-fixture')
    private = tmp_path / 'site-packages/psycopg_binary.libs/libssl-private.so'
    private.parent.mkdir(parents=True)
    private.write_bytes(b'\x7fELFwheel-private-fixture')
    monkeypatch.setattr(profile.sysconfig, 'get_config_var', lambda key: str(stdlib) if key == 'DESTSHARED' else None)
    # Map the old overly broad traversal to this fixture as well, so reverting
    # to that traversal genuinely reaches the unsupported private library.
    monkeypatch.setattr(profile, 'Path', lambda value: tmp_path if value == '/usr/local/lib' else Path(value))
    return extension, private


def test_only_stdlib_entrypoints_are_directly_loaded_not_wheel_private_dependencies(profile, monkeypatch, native_elf_fixture):
    extension, private = native_elf_fixture
    calls = []
    monkeypatch.setattr(profile.ctypes, 'CDLL', lambda path, **kwargs: calls.append(Path(path)))
    profile.verify_cpython_elf()
    assert calls == [extension]
    assert private not in calls


def test_broken_stdlib_elf_is_fatal(profile, monkeypatch, native_elf_fixture):
    def broken(*args, **kwargs):
        raise OSError('required stdlib provider missing')
    monkeypatch.setattr(profile.ctypes, 'CDLL', broken)
    with pytest.raises(OSError, match='required stdlib provider missing'):
        profile.verify_cpython_elf()


@pytest.mark.parametrize('driver', ['asyncpg', 'psycopg'])
def test_supported_driver_import_failure_is_fatal(profile, monkeypatch, driver):
    monkeypatch.setitem(sys.modules, 'asyncpg', ModuleType('asyncpg'))
    psycopg = ModuleType('psycopg')
    psycopg.pq = SimpleNamespace(__impl__='binary')
    monkeypatch.setitem(sys.modules, 'psycopg', psycopg)
    monkeypatch.setitem(sys.modules, driver, None)
    with pytest.raises(ModuleNotFoundError):
        profile.verify_postgres_drivers()


@pytest.mark.parametrize('implementation', ['binary', 'python', 'c'])
def test_psycopg_must_use_the_locked_binary_implementation(profile, monkeypatch, implementation):
    monkeypatch.setitem(sys.modules, 'asyncpg', ModuleType('asyncpg'))
    psycopg = ModuleType('psycopg')
    psycopg.pq = SimpleNamespace(__impl__=implementation)
    monkeypatch.setitem(sys.modules, 'psycopg', psycopg)
    if implementation == 'binary':
        profile.verify_postgres_drivers()
    else:
        with pytest.raises(RuntimeError, match='locked psycopg binary'):
            profile.verify_postgres_drivers()


@pytest.mark.parametrize(('installed', 'configured', 'expected'), [
    ('3.1.57', '3.1.57', '0094'),
    ('3.1.58', '3.1.58', '0094'),
    ('3.1.59', '3.1.59', '0095'),
    ('3.1.58', '3.1.59', None),
    ('3.1.59', '3.1.58', None),
    ('3.1.59', None, None),
    ('3.1.60', '3.1.60', '0095'),
    ('3.1.59', '3.1.60', None),
    ('3.1.60', '3.1.59', None),
    ('3.1.60', None, None),
    ('3.1.61', '3.1.61', '0095'),
    ('3.1.60', '3.1.61', None),
    ('3.1.61', '3.1.60', None),
    ('3.1.61', None, None),
    ('3.1.62', '3.1.62', '0095'),
    ('3.1.61', '3.1.62', None),
    ('3.1.62', '3.1.61', None),
    ('3.1.62', None, None),
    ('3.1.63', '3.1.63', '0095'),
    ('3.1.62', '3.1.63', None),
    ('3.1.63', '3.1.62', None),
    ('3.1.63', None, None),
    ('3.1.64', '3.1.64', '0095'),
    ('3.1.63', '3.1.64', None),
    ('3.1.64', '3.1.63', None),
    ('3.1.64', None, None),
    ('3.1.65', '3.1.65', None),
])
def test_runtime_revision_is_bound_to_installed_and_configured_identity(profile, installed, configured, expected):
    if expected is None:
        with pytest.raises(RuntimeError):
            profile.expected_runtime_revision(installed, configured)
    else:
        assert profile.expected_runtime_revision(installed, configured) == expected


@pytest.mark.parametrize(('driver', 'failure', 'version', 'actual_revision'), [
    ('asyncpg', 'wrong_result', '3.1.58', '0094'),
    ('asyncpg', 'query_error', '3.1.58', '0094'),
    ('psycopg', 'wrong_result', '3.1.58', '0094'),
    ('psycopg', 'query_error', '3.1.58', '0094'),
    (None, None, '3.1.58', '0094'), (None, None, '3.1.59', '0095'),
    (None, 'revision', '3.1.58', '0095'), (None, 'revision', '3.1.59', '0094'),
    (None, None, '3.1.60', '0095'), (None, 'revision', '3.1.60', '0094'),
    (None, None, '3.1.61', '0095'), (None, 'revision', '3.1.61', '0094'),
    (None, None, '3.1.62', '0095'), (None, 'revision', '3.1.62', '0094'),
    (None, None, '3.1.63', '0095'), (None, 'revision', '3.1.63', '0094'),
    (None, None, '3.1.64', '0095'), (None, 'revision', '3.1.64', '0094'),
])
def test_each_postgres_query_must_succeed_and_engines_are_disposed(
    profile, monkeypatch, driver, failure, version, actual_revision
):
    import sqlalchemy
    from alembic.migration import MigrationContext
    async_connection = SimpleNamespace(scalar=AsyncMock(return_value=1))
    async_context = MagicMock()
    async_context.__aenter__ = AsyncMock(return_value=async_connection)
    async_context.__aexit__ = AsyncMock(return_value=False)
    async_engine = SimpleNamespace(connect=lambda: async_context, dispose=AsyncMock())
    sync_connection = SimpleNamespace(scalar=MagicMock(return_value=1))
    sync_context = MagicMock()
    sync_context.__enter__.return_value = sync_connection
    sync_context.__exit__.return_value = False
    engine = SimpleNamespace(connect=lambda: sync_context, dispose=MagicMock())
    config = ModuleType('app.core.config')
    config.get_settings = lambda: SimpleNamespace(database_url='postgresql+psycopg://synthetic')
    database = ModuleType('app.core.db')
    database.async_engine = async_engine
    monkeypatch.setitem(sys.modules, 'app.core.config', config)
    monkeypatch.setitem(sys.modules, 'app.core.db', database)
    monkeypatch.setattr(sqlalchemy, 'create_engine', lambda _: engine)
    monkeypatch.setattr(MigrationContext, 'configure', lambda _: SimpleNamespace(get_current_revision=lambda: actual_revision))
    expected_revision = profile.expected_runtime_revision(version, version)
    if driver:
        scalar = async_connection.scalar if driver == 'asyncpg' else sync_connection.scalar
        if failure == 'wrong_result':
            scalar.return_value = 0
        else:
            scalar.side_effect = RuntimeError('query failed at ' + driver)
        with pytest.raises(RuntimeError, match=driver):
            asyncio.run(profile.verify_postgres(expected_revision))
    elif failure == 'revision':
        with pytest.raises(RuntimeError, match='Unexpected production migration revision'):
            asyncio.run(profile.verify_postgres(expected_revision))
    else:
        asyncio.run(profile.verify_postgres(expected_revision))
    async_engine.dispose.assert_awaited_once()
    assert str(async_connection.scalar.call_args.args[0]) == 'SELECT 1'
    if driver != 'asyncpg':
        engine.dispose.assert_called_once()
        assert str(sync_connection.scalar.call_args.args[0]) == 'SELECT 1'


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


@pytest.mark.parametrize('version,expected', [('3.1.57', 7), ('3.1.58', 7), ('3.1.59', 7), ('3.1.60', 7), ('3.1.61', 7), ('3.1.62', 7), ('3.1.63', 7), ('3.1.64', 7), ('3.1.44', 0), ('3.1.45', 0), ('3.1.46', 0)])
def test_new_profile_is_mandatory_only_for_current_runtime(version, expected, tmp_path):
    source = (ROOT / 'infra/scripts/verify-production-runtime-images.sh').read_text()
    marker = '# The final-image profile is mandatory for Builds 64 through 71.'
    block = source[source.index(marker):].split('\nfi\n', 1)[0] + '\nfi\n'
    fake = tmp_path / 'compose'
    fake.write_text('#!/bin/sh\nexit 7\n')
    fake.chmod(0o755)
    result = subprocess.run(['bash', '-c', 'set -e\nAPP_VERSION=$1\ncompose=("$2")\n' + block,
                             'runtime-profile-test', version, str(fake)], timeout=10)
    assert result.returncode == expected
