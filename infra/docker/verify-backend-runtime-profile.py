"""Fail-closed proof of the actual PostgreSQL-only production image.

Run from /app as the configured non-root image user, with the isolated runtime
verification stack's normal environment. This does not create or modify data.
"""
from __future__ import annotations

import asyncio
import ctypes
import dbm
import importlib
import importlib.util
import json
import os
from pathlib import Path
import pkgutil
import shutil
import subprocess
import sysconfig
import tempfile


def verify_absence() -> None:
    for name in ('sqlite3', '_sqlite3'):
        if importlib.util.find_spec(name) is not None:
            raise RuntimeError(f'Unexpected runtime module: {name}')
        try:
            importlib.import_module(name)
        except ModuleNotFoundError as error:
            if error.name != name:
                raise
        else:
            raise RuntimeError(f'Unexpected successful import: {name}')
    for root in (Path('/lib'), Path('/usr/lib'), Path('/usr/local/lib')):
        if any(root.rglob('libsqlite3*')) or any(root.rglob('_sqlite3*')):
            raise RuntimeError(f'Unexpected SQLite runtime file under {root}')
    if (Path(sysconfig.get_path('stdlib')) / 'sqlite3').exists():
        raise RuntimeError('Unexpected SQLite standard-library wrapper')
    if shutil.which('sqlite3') is not None:
        raise RuntimeError('Unexpected SQLite CLI')
    result = subprocess.run(['apk', 'info', '-e', 'sqlite-libs'], capture_output=True, text=True, check=False)
    if result.returncode != 1:
        raise RuntimeError('SQLite package absence was not confirmed')
    original = set(Path('/etc/dcompany/python-original-deps.txt').read_text().splitlines())
    retained = set(Path('/etc/dcompany/python-runtime-deps.txt').read_text().splitlines())
    if 'so:libsqlite3.so.0' not in original or retained != original - {'so:libsqlite3.so.0'}:
        raise RuntimeError('Original non-SQLite runtime dependency closure changed')
    actual = subprocess.run(['apk', 'info', '--depends', '.python-rundeps'], capture_output=True, text=True, check=True)
    if {row for row in actual.stdout.splitlines() if row.startswith('so:')} != retained:
        raise RuntimeError('Installed runtime dependency group differs from retained closure')


def verify_native_modules() -> None:
    for name in ('bz2', 'lzma', 'compression.zstd', '_ctypes', 'dbm.gnu', 'dbm.ndbm',
                 'readline', 'curses', 'curses.panel', 'uuid', 'ssl', 'hashlib', 'zlib'):
        importlib.import_module(name)
    with tempfile.TemporaryDirectory() as directory:
        filename = str(Path(directory) / 'fallback')
        with dbm.open(filename, 'c') as database:
            database[b'probe'] = b'postgres-runtime'
        with dbm.open(filename, 'r') as database:
            if database[b'probe'] != b'postgres-runtime':
                raise RuntimeError('Native dbm fallback failed')
        if dbm.whichdb(filename) not in ('dbm.gnu', 'dbm.ndbm'):
            raise RuntimeError('Unexpected dbm provider')
    subprocess.run([sysconfig.get_config_var('BINDIR') + '/python', '-m', 'pip', 'check'], check=True)
    verify_cpython_elf()


def verify_cpython_elf() -> None:
    loaded = set()
    # CPython extensions are direct loader entrypoints. Wheel-private libraries
    # must instead be reached through their owning extension's RPATH/loader.
    for library in Path(sysconfig.get_config_var('DESTSHARED')).glob('*.so'):
        # The upstream base intentionally excludes Tk from its runtime closure.
        if 'tkinter' in library.name or not library.is_file():
            continue
        resolved = library.resolve()
        with resolved.open('rb') as stream:
            is_elf = stream.read(4) == b'\x7fELF'
        if resolved in loaded or not is_elf:
            continue
        ctypes.CDLL(str(resolved), mode=os.RTLD_NOW)
        loaded.add(resolved)


def verify_postgres_drivers() -> None:
    import asyncpg
    import psycopg

    if psycopg.pq.__impl__ != 'binary':
        raise RuntimeError('Expected the locked psycopg binary implementation')


def verify_application_imports() -> int:
    application = importlib.import_module('app')
    modules = sorted(module.name for module in pkgutil.walk_packages(application.__path__, 'app.'))
    for module in modules:
        importlib.import_module(module)
    return len(modules)


def expected_runtime_revision(runtime_version: str, configured_version: str | None) -> str:
    if configured_version != runtime_version:
        raise RuntimeError('Configured and installed application versions differ')
    expected = {'3.1.57': '0094', '3.1.58': '0094', '3.1.59': '0095', '3.1.60': '0095', '3.1.61': '0095', '3.1.62': '0095', '3.1.63': '0095'}.get(runtime_version)
    if expected is None:
        raise RuntimeError('No PostgreSQL runtime proof is defined for this application version')
    return expected


async def verify_postgres(expected_revision: str) -> None:
    from alembic.migration import MigrationContext
    from sqlalchemy import create_engine, text
    from app.core.config import get_settings
    from app.core.db import async_engine

    try:
        async with async_engine.connect() as connection:
            if await connection.scalar(text('SELECT 1')) != 1:
                raise RuntimeError('asyncpg PostgreSQL probe failed')
    finally:
        await async_engine.dispose()
    url = str(get_settings().database_url).replace('+asyncpg', '+psycopg')
    if url.startswith('postgresql://'):
        url = url.replace('postgresql://', 'postgresql+psycopg://', 1)
    engine = create_engine(url)
    try:
        with engine.connect() as connection:
            if connection.scalar(text('SELECT 1')) != 1:
                raise RuntimeError('psycopg PostgreSQL probe failed')
            if MigrationContext.configure(connection).get_current_revision() != expected_revision:
                raise RuntimeError('Unexpected production migration revision')
    finally:
        engine.dispose()


def main() -> None:
    if os.geteuid() == 0 or Path.cwd() != Path('/app'):
        raise RuntimeError('Run the final image proof as its non-root user from /app')
    version = importlib.import_module('app').__version__
    expected_revision = expected_runtime_revision(version, os.environ.get('APP_VERSION'))
    verify_absence()
    verify_native_modules()
    verify_postgres_drivers()
    module_count = verify_application_imports()
    asyncio.run(verify_postgres(expected_revision))
    print(json.dumps({'status': 'PASS', 'profile': 'postgresql-without-sqlite',
                      'application_version': version, 'database_revision': expected_revision,
                      'application_modules_imported': module_count, 'uid': os.geteuid()}))


if __name__ == '__main__':
    main()
