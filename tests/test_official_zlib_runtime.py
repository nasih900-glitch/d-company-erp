"""Runtime algorithm/CLI compatibility tests, separate from vendor APK acceptance."""
import ctypes
import ctypes.util
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tarfile
import io

import pytest

ROOT = Path(__file__).resolve().parents[1]
VERIFIER = ROOT / 'infra/docker/caddy-zlib/verify-official-zlib.sh'
PROBE = ROOT / 'infra/docker/caddy-zlib/runtime-probe.c'
MEMBER = 'usr/lib/libz.so.1.4'


@pytest.mark.parametrize('kind', ('regular', 'symlink', 'hardlink', 'self-hardlink', 'duplicate', 'missing', 'truncated'))
def test_only_one_regular_apk_member_can_be_read_to_private_stdout_file(tmp_path, kind):
    # Synthetic tar tests exercise member handling only; these archives are not
    # vendor-signed APK evidence and never enter package authentication/admission.
    archive = tmp_path / 'fixture.apk'
    with tarfile.open(archive, 'w:gz') as stream:
        member = tarfile.TarInfo(MEMBER if kind != 'missing' else 'unrelated')
        if kind == 'symlink': member.type, member.linkname = tarfile.SYMTYPE, 'target'
        elif kind in ('hardlink', 'self-hardlink'):
            member.type, member.linkname = tarfile.LNKTYPE, MEMBER if kind == 'self-hardlink' else 'target'
        else: member.size = 7
        stream.addfile(member, io.BytesIO(b'payload') if member.isreg() else None)
        if kind == 'duplicate': stream.addfile(member, io.BytesIO(b'payload'))
    if kind == 'truncated': archive.write_bytes(archive.read_bytes()[:30])
    work = tmp_path / 'work'
    work.mkdir(mode=0o700)
    source = VERIFIER.read_text()
    start = source.index('if [ "$service" = redis ]; then')
    end = source.index('apk_runtime_sha256=', start)
    result = subprocess.run(['sh', '-c', 'set -eu\nservice=redis\nAPK=$1\nwork=$2\nalgorithm=sha256\ndigest=$3\n' + source[start:end],
                             'fixture', str(archive), str(work), hashlib.sha256(b'payload').hexdigest()], text=True, capture_output=True)
    if kind == 'regular':
        assert result.returncode == 0, result.stderr
        assert (work / 'runtime').read_bytes() == b'payload'
    else:
        assert result.returncode != 0, (kind, result.stdout)
        assert not (work / 'runtime').exists()
    assert not (tmp_path / 'usr').exists()


def test_probe_real_host_zlib_roundtrip_and_byte_corruption_detection(tmp_path):
    # Run genuine compression with the host's existing libz. Only this temporary
    # compatibility build substitutes its actual version/path; production remains
    # unconditionally bound to the authenticated1.4 path/version and needs CI proof.
    library = ctypes.CDLL(ctypes.util.find_library('z'))
    library.zlibVersion.restype = ctypes.c_char_p
    version = library.zlibVersion().decode('ascii')
    maps = Path('/proc/self/maps').read_text().splitlines()
    path = next(Path(line.split()[-1]).resolve() for line in maps
                if len(line.split()) >= 6 and '/libz.so.' in line.split()[-1])
    source = PROBE.read_text()
    assert '#define RUNTIME "/usr/lib/libz.so.1.4"' in source
    assert 'strcmp(version(), "1.4")' in source
    fixture = source.replace('#define RUNTIME "/usr/lib/libz.so.1.4"', '#define RUNTIME ' + json.dumps(str(path)))
    fixture = fixture.replace('strcmp(version(), "1.4")', 'strcmp(version(), ' + json.dumps(version) + ')')
    fixture = fixture.replace('runtime_version=1.4', 'runtime_version=' + version)
    for corrupt in (False, True):
        current = fixture
        if corrupt:
            comparison = 'restored_size != sizeof(original) || memcmp(original, restored, sizeof(original))'
            assert current.count(comparison) == 1
            current = current.replace(comparison, '(restored[0] ^= 1U, ' + comparison + ')')
        cfile, binary = tmp_path / 'probe.c', tmp_path / 'probe'
        cfile.write_text(current)
        compiled = subprocess.run(['cc', '-O2', '-Wall', '-Wextra', '-Werror', str(cfile), '-ldl', '-o', str(binary)], text=True, capture_output=True)
        assert compiled.returncode == 0, compiled.stderr
        result = subprocess.run([str(binary)], text=True, capture_output=True)
        if corrupt:
            assert result.returncode == 15 and result.stdout == ''
        else:
            assert result.returncode == 0, result.stderr
            assert f'runtime_version={version}\nruntime_path={path}\nsoname=libz.so.1\nproc_map={path}\n' == result.stdout


def test_service_selector_is_closed_before_package_or_runtime_access():
    result = subprocess.run(['sh', str(VERIFIER), 'unreviewed-profile'], text=True, capture_output=True)
    assert result.returncode != 0
    assert result.stderr == 'Unknown official zlib service profile.\n'


@pytest.mark.parametrize('suffix', (' -> usr/lib/libz.so.1.4', ' link to usr/lib/libz.so.1.4'))
def test_busybox_regular_prefix_cannot_hide_self_target_link(tmp_path, suffix):
    # Both known verbose link representations can end with the selected path.
    # Exercise the exact shell guard independently of the host tar's rendering.
    fake_bin = tmp_path / 'bin'
    fake_bin.mkdir()
    marker = tmp_path / 'extraction-attempted'
    fake_tar = fake_bin / 'tar'
    fake_tar.write_text('#!/bin/sh\ncase "$1" in\n'
                        '-tzf) printf "%s\\n" "usr/lib/libz.so.1.4" ;;\n'
                        '-tvzf) printf "%s\\n" "$VERBOSE_LINE" ;;\n'
                        '-xzOf) echo attempted > "$EXTRACTION_MARKER"; echo payload ;;\n'
                        '*) exit 99 ;;\nesac\n')
    fake_tar.chmod(0o755)
    work = tmp_path / 'work'
    work.mkdir(mode=0o700)
    source = VERIFIER.read_text()
    block = source[source.index('if [ "$service" = redis ]; then'):source.index('apk_runtime_sha256=')]
    result = subprocess.run(['sh', '-c', 'set -eu\nservice=redis\nAPK=unused\nwork=$1\nalgorithm=sha256\ndigest=$2\n' + block, 'fixture', str(work), hashlib.sha256(b'payload').hexdigest()],
                            env={**os.environ, 'PATH': str(fake_bin) + os.pathsep + os.environ['PATH'],
                                 'VERBOSE_LINE': '-rw-r--r-- 0/0 0 1970-01-01 00:00:00 ' + MEMBER + suffix,
                                 'EXTRACTION_MARKER': str(marker)}, text=True, capture_output=True)
    assert result.returncode != 0
    assert not marker.exists() and not (work / 'runtime').exists()


@pytest.mark.parametrize('service', ('backend', 'frontend', 'postgres', 'caddy'))
@pytest.mark.parametrize('kind', ('regular', 'failure', 'missing', 'directory', 'symlink', 'parent-symlink', 'hardlink', 'corrupt'))
def test_apk3_profile_uses_only_format_aware_extract_and_closed_payload(tmp_path, service, kind):
    # Command/payload-shape fixture only, not a vendor-signed archive or1.4 proof.
    fake_bin = tmp_path / 'bin'
    fake_bin.mkdir()
    event_log = tmp_path / 'apk-args.json'
    marker = tmp_path / 'tar-fallback-attempted'
    work = tmp_path / 'work'
    work.mkdir(mode=0o700)
    archive, runtime = tmp_path / 'fixture.apk', tmp_path / 'mapped-runtime'
    archive.write_bytes(b'authenticated-archive-command-fixture')
    runtime.write_bytes(b'payload')
    fake_apk = fake_bin / 'apk'
    fake_apk.write_text(
        '#!/usr/bin/env python3\n'
        'import json, os, pathlib, sys\n'
        'args = sys.argv[1:]\n'
        'assert args[:6] == ["extract", "--no-network", "--repositories-file", "/dev/null", "--no-chown", "--destination"]\n'
        'assert len(args) == 8 and args[7] == os.environ["FIXTURE_APK"]\n'
        'destination = pathlib.Path(args[6])\n'
        'assert destination.is_dir() and list(destination.iterdir()) == []\n'
        'pathlib.Path(os.environ["EVENT_LOG"]).write_text(json.dumps(args))\n'
        'kind = os.environ["EXTRACT_KIND"]\n'
        'if kind == "failure": raise SystemExit(23)\n'
        'if kind == "missing": raise SystemExit(0)\n'
        'parent = destination / "usr/lib"\n'
        'if kind == "parent-symlink":\n'
        '    outside = destination.parent / "outside"; outside.mkdir()\n'
        '    (destination / "usr").mkdir(); parent.symlink_to(outside, target_is_directory=True)\n'
        'else: parent.mkdir(parents=True)\n'
        'payload = parent / "libz.so.1.4"\n'
        'if kind == "directory": payload.mkdir()\n'
        'elif kind == "symlink": payload.symlink_to(os.environ["FIXTURE_RUNTIME"])\n'
        'elif kind == "hardlink": os.link(os.environ["FIXTURE_RUNTIME"], payload)\n'
        'else: payload.write_bytes(b"wrong-bytes" if kind == "corrupt" else b"payload")\n'
    )
    fake_apk.chmod(0o755)
    fake_tar = fake_bin / 'tar'
    fake_tar.write_text('#!/bin/sh\necho attempted > "$FALLBACK_MARKER"\nexit 99\n')
    fake_tar.chmod(0o755)
    source = VERIFIER.read_text()
    start = source.index('if [ "$service" = redis ]; then')
    end = source.index("\nprintf ", start)
    block = source[start:end]
    script = 'set -eu\nservice=$1\nwork=$2\nAPK=$3\nRUNTIME=$4\nalgorithm=sha256\ndigest=$5\n' + block
    result = subprocess.run(['sh', '-c', script, 'fixture', service, str(work), str(archive), str(runtime),
                             hashlib.sha256(b'payload').hexdigest()],
                            env={**os.environ, 'PATH': str(fake_bin) + os.pathsep + os.environ['PATH'],
                                 'EXTRACT_KIND': kind, 'EVENT_LOG': str(event_log), 'FALLBACK_MARKER': str(marker),
                                 'FIXTURE_APK': str(archive), 'FIXTURE_RUNTIME': str(runtime)},
                            text=True, capture_output=True)
    assert event_log.exists()
    assert not marker.exists()
    if kind == 'regular':
        assert result.returncode == 0, result.stderr
        assert (work / 'extracted/usr/lib/libz.so.1.4').read_bytes() == b'payload'
    else:
        assert result.returncode == (23 if kind == 'failure' else 1), (kind, result.stderr)
