#!/usr/bin/env python3
"""Read-only byte/ZIP diagnostics for a separately rebuilt immutable release."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import struct
import subprocess
import zipfile

SOURCE = '21b162e39737fca27f85176523c717c798ca8ac1'
TREE = '36a7e902fd986c70045d4139424b0f4e3ff20d08'
RUN = 41727809348
ARTIFACT = 13279607444
PRIOR_TRANSPORT_SHA = '4dffba81ad21054fbd2d5ceb553098785aaf341fbc8f8d8fa9f744d252260864'
PRODUCER = {
    'play-unsigned.apk': '089e4edff5d695f63dc59899ce6f375eadd7ffa8ebcb315871426fd4bef6f633',
    'direct-unsigned.apk': 'b8c3d9e109904e5e548961841cc1e4b5142913da4b06c2e2d22e9f8e56951872',
    'play-unsigned.aab': '4c0f71de1e5de1a62a195392ddd374d37708fdb8d3b4bd525fa95566b756df01',
    'play-output-metadata.json': '26a0f303b3125816b5c7831b32eccf9da9e9bfc263c61e1ca317eeb49ef3056a',
    'direct-output-metadata.json': '840a74f67b8ea90631ef07fef44b08c90d37388678833ea4603b3b402007fbe3',
    'play-AndroidManifest.xml': 'e7f9c5e3405ae8ec9ec62ece0199d52ee034c662dcc789434caca4e533a9919e',
    'direct-AndroidManifest.xml': '7843047334cd945d29ac5032b28871543015adce01bcd131ecb83c87344ce9f6',
    'UNSIGNED_SHA256SUMS': '0004dfcdb5339b10f90567119f1c79a408775a11108281be940440c3cb41884a',
}
PAYLOADS = tuple(name for name in PRODUCER if name != 'UNSIGNED_SHA256SUMS')
ARCHIVES = ('play-unsigned.apk', 'direct-unsigned.apk', 'play-unsigned.aab')


def require(value, message):
    if not value:
        raise RuntimeError(message)


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def save(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def verify_producer(directory, expected=PRODUCER):
    require(directory.is_dir() and not directory.is_symlink(), 'Producer directory missing or linked')
    rows = list(directory.iterdir())
    require({p.name for p in rows} == set(expected) and len(rows) == len(expected), 'Producer inventory is not exact8')
    for path in rows:
        require(path.is_file() and not path.is_symlink(), 'Producer member is not regular')
        require(sha(path) == expected[path.name], 'Independently fixed producer hash differs: ' + path.name)


def verify_identity(root, evidence):
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
    tree = subprocess.check_output(['git', 'rev-parse', 'HEAD^{tree}'], cwd=root, text=True).strip()
    require(head == SOURCE and tree == TREE, 'Actual build root is not immutable21b')
    artifact = json.loads((evidence / 'producer-artifact-api.json').read_text())
    run = json.loads((evidence / 'producer-run-api.json').read_text())
    require(artifact['id'] == ARTIFACT and artifact['name'] == 'android-native-unsigned'
            and artifact['expired'] is False and artifact['workflow_run']['id'] == RUN
            and artifact['workflow_run']['head_sha'] == SOURCE, 'Actual artifact API identity differs')
    require(run['id'] == RUN and run['head_sha'] == SOURCE and run['head_branch'] == 'v3.1.54'
            and run['event'] == 'push' and run['run_attempt'] == 1, 'Actual canonical run identity differs')
    return {'source': head, 'tree': tree, 'artifact_id': ARTIFACT, 'canonical_run': RUN,
            'canonical_attempt': 1, 'prior_independently_downloaded_transport_sha256': PRIOR_TRANSPORT_SHA,
            'transport_rehashed_by_this_diagnostic': False,
            'api_artifact_digest': artifact.get('digest'), 'api_size_in_bytes': artifact.get('size_in_bytes')}


def metadata_apk(directory):
    path = directory / 'output-metadata.json'
    value = json.loads(path.read_text())
    elements = value.get('elements')
    require(type(elements) is list and len(elements) == 1, 'APK metadata does not have exactly one element')
    name = elements[0].get('outputFile')
    require(type(name) is str and Path(name).name == name and name.endswith('.apk'), 'Unsafe APK filename')
    return directory / name


def rebuilt_paths(root):
    build = root / 'android-native/app/build'
    result = {
        'play-output-metadata.json': build / 'outputs/apk/release/output-metadata.json',
        'direct-output-metadata.json': build / 'outputs/apk/directRelease/output-metadata.json',
        'play-unsigned.aab': build / 'outputs/bundle/release/app-release.aab',
        'play-AndroidManifest.xml': build / 'intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml',
        'direct-AndroidManifest.xml': build / 'intermediates/merged_manifests/directRelease/processDirectReleaseManifest/AndroidManifest.xml',
    }
    for variant, name in [('release', 'play-unsigned.apk'), ('directRelease', 'direct-unsigned.apk')]:
        try:
            result[name] = metadata_apk(build / 'outputs/apk' / variant)
        except (OSError, ValueError, RuntimeError, KeyError, TypeError):
            result[name] = None
    return result


def archive_entries(path):
    """Central-directory order and raw local compressed bytes, without normalization."""
    entries = []
    with zipfile.ZipFile(path) as archive, path.open('rb') as raw:
        infos = archive.infolist()
        require(len(infos) <= 100000 and sum(i.file_size for i in infos) <= 2 * 1024**3, 'ZIP observation bound exceeded')
        for index, item in enumerate(infos):
            digest = hashlib.sha256()
            with archive.open(item) as stream:
                while chunk := stream.read(1024 * 1024): digest.update(chunk)
            raw.seek(item.header_offset)
            header = raw.read(30)
            require(len(header) == 30 and header[:4] == b'PK\x03\x04', 'Local ZIP header missing')
            filename_size, extra_size = struct.unpack('<HH', header[26:30])
            data_offset = item.header_offset + 30 + filename_size + extra_size
            raw.seek(data_offset)
            compressed = hashlib.sha256()
            remaining = item.compress_size
            while remaining:
                chunk = raw.read(min(remaining, 1024 * 1024))
                require(bool(chunk), 'Compressed ZIP entry truncated')
                compressed.update(chunk); remaining -= len(chunk)
            entries.append({'index': index, 'name': item.filename, 'date_time': item.date_time,
                            'size': item.file_size, 'compressed_size': item.compress_size,
                            'crc32': item.CRC, 'compression': item.compress_type,
                            'flags': item.flag_bits, 'header_offset': item.header_offset,
                            'compressed_data_offset': data_offset, 'sha256': digest.hexdigest(),
                            'compressed_sha256': compressed.hexdigest(), 'extra_hex': item.extra.hex(),
                            'comment_hex': item.comment.hex(), 'create_system': item.create_system,
                            'create_version': item.create_version, 'extract_version': item.extract_version,
                            'external_attr': item.external_attr, 'internal_attr': item.internal_attr})
        return {'archive_sha256': sha(path), 'comment_hex': archive.comment.hex(), 'entries': entries}


def native_inputs(root):
    """Retain post-build native enumeration; not a claim of observed AGP traversal."""
    result, visited = [], 0
    start = root / 'android-native/app/build/intermediates'
    for directory, dirs, files in os.walk(start, followlinks=False):
        visited += len(dirs) + len(files)
        require(visited <= 200000, 'Generated-input inventory bound exceeded')
        path = Path(directory)
        if not {'release', 'directRelease'}.intersection(path.parts): continue
        selected = []
        for name in files:  # Intentionally retain filesystem enumeration order.
            if not (re.fullmatch(r'classes(?:[0-9]+)?\.dex', name) or name.endswith(('.prof', '.profm', '.dm'))): continue
            leaf = path / name; info = leaf.lstat()
            require(stat.S_ISREG(info.st_mode) and not leaf.is_symlink(), 'Generated DEX/profile is not regular')
            selected.append({'name': name, 'sha256': sha(leaf), 'bytes': info.st_size,
                             'inode': info.st_ino, 'device': info.st_dev,
                             'mtime_ns': info.st_mtime_ns, 'ctime_ns': info.st_ctime_ns,
                             'birthtime': getattr(info, 'st_birthtime', None)})
        if selected:
            result.append({'directory': str(path.relative_to(root)), 'native_directory_order': files,
                           'native_subdirectory_order': dirs[:], 'selected': selected})
    return {'post_build_enumeration_only': True, 'ctime_is_not_creation_time': True, 'directories': result}


def collect(producer, rebuilt, output):
    """Copy all available payloads first; compare every payload before returning HOLD."""
    keep = output / 'rebuilt'; keep.mkdir(parents=True, exist_ok=True)
    comparisons = []
    for name in PAYLOADS:
        source = rebuilt.get(name)
        row = {'name': name, 'producer_sha256': sha(producer / name), 'rebuilt_present': False, 'identical': False}
        if source is not None and source.is_file() and not source.is_symlink():
            shutil.copyfile(source, keep / name)
            row.update(rebuilt_present=True, rebuilt_sha256=sha(keep / name), rebuilt_bytes=(keep / name).stat().st_size)
            row['identical'] = row['producer_sha256'] == row['rebuilt_sha256']
        comparisons.append(row)
    save(output / 'ALL_SEVEN_COMPARISONS.json', comparisons)
    observations, failures = {}, []
    for name in ARCHIVES:
        for label, directory in [('producer', producer), ('rebuilt', keep)]:
            try:
                value = archive_entries(directory / name)
                save(output / (label + '-' + name + '-entries.json'), value)
                observations[label + '/' + name] = value
            except (OSError, ValueError, RuntimeError, zipfile.BadZipFile) as error:
                failures.append({'archive': label + '/' + name, 'error': type(error).__name__, 'message': str(error)})
    content = []
    for name in ARCHIVES:
        before, after = observations.get('producer/' + name), observations.get('rebuilt/' + name)
        if before is None or after is None: continue
        left = {x['name']: x for x in before['entries']}; right = {x['name']: x for x in after['entries']}
        changed = []
        for entry in sorted(set(left) | set(right)):
            a, b = left.get(entry), right.get(entry)
            if a != b:
                changed.append({'name': entry, 'producer': a, 'rebuilt': b,
                                'same_uncompressed_content': a is not None and b is not None and a['sha256'] == b['sha256']})
        content.append({'archive': name, 'changed_entries': changed,
                        'producer_dex_profiles': [x for x in before['entries'] if x['name'].endswith(('.dex', '.prof', '.profm'))],
                        'rebuilt_dex_profiles': [x for x in after['entries'] if x['name'].endswith(('.dex', '.prof', '.profm'))]})
    save(output / 'ZIP_ENTRY_COMPARISONS.json', content)
    result = {'status': 'EXACT_SEVEN_PAYLOAD_MATCH' if all(x['identical'] for x in comparisons) and not failures else 'HOLD_MISMATCH_OR_INCOMPLETE',
              'comparisons': comparisons, 'archive_observation_errors': failures,
              'normalization_or_signing_performed': False, 'release_acceptance': False}
    save(output / 'RESULT.json', result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--producer', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--verify-only', action='store_true')
    args = parser.parse_args(); root = Path.cwd(); args.output.mkdir(parents=True, exist_ok=True)
    provenance = verify_identity(root, args.output)
    verify_producer(args.producer)
    save(args.output / 'PRODUCER_IDENTITY.json', {**provenance, 'independently_fixed_extracted_hashes': PRODUCER})
    if args.verify_only:
        require(not subprocess.check_output(['git', 'status', '--porcelain'], cwd=root, text=True).strip(), 'Build root is not clean')
        return 0
    result = collect(args.producer, rebuilt_paths(root), args.output)
    try: save(args.output / 'R8_DEX_PROFILE_NATIVE_INPUTS.json', native_inputs(root))
    except (OSError, ValueError, RuntimeError) as error:
        save(args.output / 'R8_INPUT_OBSERVATION_ERROR.json', {'error': type(error).__name__, 'message': str(error)})
        return 1
    return 0 if result['status'] == 'EXACT_SEVEN_PAYLOAD_MATCH' else 1


if __name__ == '__main__':
    raise SystemExit(main())
