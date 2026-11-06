"""Exercise55's full-row, opaque-fleet and actual-runtime preservation decisions."""

from __future__ import annotations

import ast
import hashlib
import importlib.util
import io
import json
import subprocess
import tarfile
from copy import deepcopy
from pathlib import Path
from types import SimpleNamespace
from uuid import UUID

import pytest

ROOT = Path(__file__).resolve().parents[1]


def reviewed_command_triples(verifier):
    # This immutable public Compose block contains variable references, not secrets.
    source = subprocess.check_output(
        ['git', 'show', verifier.PRIOR_REVISION+':docker-compose.prod.yml'], cwd=ROOT, text=True,
    )
    lines = source.splitlines(keepends=True)
    redis = lines.index('  redis:\n')
    block = lines.index('      - |\n', redis)+1
    body = []
    for line in lines[block:]:
        if line.strip() and not line.startswith('        '):
            break
        body.append(line[8:] if line.startswith('        ') else line)
    redis_shell = ''.join(body).replace('$$', '$')
    triples = {
        'backend': [verifier.BACKEND_UVICORN_ENTRYPOINT, [], 'erp'],
        'caddy': [None, ['caddy', 'run', '--config', '/etc/caddy/Caddyfile', '--adapter', 'caddyfile'], ''],
        'frontend': [['/docker-entrypoint.sh'], ['nginx', '-g', 'daemon off;'], ''],
        'postgres': [['docker-entrypoint.sh'], ['postgres'], ''],
        'redis': [['docker-entrypoint.sh'], ['/bin/sh', '-ec', redis_shell], ''],
    }
    for name, triple in triples.items():
        assert verifier.sha(json.dumps(triple, separators=(',', ':')).encode()) == verifier.PRIOR_COMMAND_SHA256[name]
    return triples


@pytest.fixture
def pre_binding_documents(verifier, runtime, monkeypatch):
    """Exercise the complete preflight with synthetic privileged I/O, never live secrets."""
    monkeypatch.setattr(verifier, 'CANDIDATE_RELEASE_FROZEN', True)
    class OwnedSource:
        def __init__(self, name):
            self.name = name

        def is_symlink(self):
            return False

        def is_dir(self):
            return True

        def stat(self):
            return SimpleNamespace(st_uid=0, st_mode=0o40700)

        def resolve(self):
            return Path('/synthetic-owned-source') / self.name

    roots = [OwnedSource('prior'), OwnedSource('candidate')]
    source_files = {
        root.name: {
            'backend/app.py': verifier.sha((root.name+' backend').encode()),
            'frontend/app.ts': verifier.sha((root.name+' frontend').encode()),
        }
        for root in roots
    }

    def inventory(root, scopes):
        return {
            name: digest for name, digest in source_files[root.name].items()
            if scopes != ('backend',) or name.startswith('backend/')
        }

    monkeypatch.setattr(verifier, 'inventory', inventory)
    manifests = []
    for root, protected_key, backend_key in (
        (roots[0], 'PRIOR_PROTECTED_SOURCE_SHA256', 'PRIOR_BACKEND_MANIFEST_SHA256'),
        (roots[1], 'CANDIDATE_PROTECTED_SOURCE_SHA256', 'CANDIDATE_BACKEND_MANIFEST_SHA256'),
    ):
        monkeypatch.setattr(verifier, protected_key, verifier.inventory_hash(inventory(root, ())))
        rows = inventory(root, ('backend',))
        manifest = ''.join(
            f"{digest}\t{name.removeprefix('backend/')}\n" for name, digest in sorted(rows.items())
        ).encode()
        manifests.append(verifier.sha(manifest))
        monkeypatch.setattr(verifier, backend_key, manifests[-1])
    revision = 'b'*40
    image_ids = {name: 'sha256:'+str(index)*64 for index, name in enumerate(verifier.SERVICES, 1)}
    identity = {
        'prior_revision': verifier.PRIOR_REVISION,
        'prior_version': verifier.PRIOR_VERSION,
        'prior_database_head': verifier.PRIOR_DATABASE_HEAD,
        'candidate_revision': revision,
        'candidate_version': verifier.CANDIDATE_VERSION,
        'candidate_database_head': verifier.CANDIDATE_DATABASE_HEAD,
        'prior_source_archive_sha256': verifier.PRIOR_SOURCE_ARCHIVE_SHA256,
        'candidate_source_archive_sha256': 'b'*64,
        'prior_backend_manifest_sha256': manifests[0],
        'candidate_backend_manifest_sha256': manifests[1],
        'prior_images': verifier.PRIOR_IMAGES,
        'candidate_images': image_ids,
    }
    images, services = {}, {}
    for row in runtime['containers']:
        name = row['Config']['Labels']['com.docker.compose.service']
        before = verifier.environment(row['Config']['Env'])
        env = verifier.candidate_backend_environment(before, revision) if name == 'backend' else before
        if name == 'frontend':
            # Independent literal observed in the pinned frontend image; do not
            # derive fixture input from the transition function being tested.
            env = {**before, 'NGINX_VERSION': '1.31.8'}
        config = {'Env': [key+'='+value for key, value in env.items()],
                  'Entrypoint': deepcopy(row['Config']['Entrypoint']), 'Cmd': deepcopy(row['Config']['Cmd']) or None,
                  'Labels': {'org.opencontainers.image.revision': revision,
                             'org.opencontainers.image.version': verifier.CANDIDATE_VERSION}}
        service = {'image': f'd-company-erp-{name}:{revision}', 'entrypoint': None,
                   'command': None, 'user': None, 'environment': {}}
        if name == 'backend':
            config['User'] = 'erp'
            config['Entrypoint'] = ['/entrypoint.sh']
            service['entrypoint'] = verifier.BACKEND_UVICORN_ENTRYPOINT
            service['command'] = []
            service['environment']['CLIENT_UPDATE_MESSAGE'] = env['CLIENT_UPDATE_MESSAGE']
            config['Env'] = [item for item in config['Env'] if not item.startswith('CLIENT_UPDATE_MESSAGE=')]
        elif name == 'redis' and row['Config']['Cmd']:
            config['Cmd'] = ['redis-server']
            service['command'] = deepcopy(row['Config']['Cmd'])
        images[name] = {'Id': image_ids[name], 'Config': config}
        services[name] = service
    prior_env = f'APP_VERSION={verifier.PRIOR_VERSION}\nAPP_REVISION={verifier.PRIOR_REVISION}\nKEEP=$literal\n'.encode()
    candidate_env = prior_env.replace(verifier.PRIOR_VERSION.encode(), verifier.CANDIDATE_VERSION.encode()).replace(verifier.PRIOR_REVISION.encode(), revision.encode())
    documents = {'identity': identity, 'journal': runtime, 'images': images,
                 'attestation': {'services': {name: {'image_id': image_id} for name, image_id in image_ids.items()}},
                 'compose': {'services': services}, 'prior_env': prior_env, 'live_env': prior_env,
                 'candidate_env': candidate_env}

    def private_file(name):
        name = str(name)
        value = documents[name]
        if isinstance(value, bytes):
            return value
        raw = json.dumps(value, separators=(',', ':')).encode()
        return raw.replace(b'$', b'$$') if name == 'compose' else raw

    monkeypatch.setattr(verifier, 'private_file', private_file)
    args = SimpleNamespace(identity='identity', runtime_journal='journal',
                           candidate_image_inspect='images', candidate_compose_config='compose',
                           prior_env='prior_env', live_env='live_env', candidate_env='candidate_env',
                           prior_source_root=roots[0], candidate_source_root=roots[1])
    return args, documents


def test_all_five_pre_bindings_accept_actual_export_default_shapes(verifier, pre_binding_documents):
    args, _ = pre_binding_documents
    verifier.verify_pre_bindings(args)


@pytest.mark.parametrize('runtime', ['reviewed_commands'], indirect=True)
def test_all_five_pre_bindings_preserves_real_frozen_command_pins(verifier, runtime, pre_binding_documents):
    args, _ = pre_binding_documents
    verifier.verify_pre_bindings(args)


@pytest.mark.parametrize('runtime', ['reviewed_commands'], indirect=True)
def test_real_redis_dollar_argument_change_is_rejected(verifier, runtime, pre_binding_documents):
    args, documents = pre_binding_documents
    verifier.verify_pre_bindings(args)
    documents['compose']['services']['redis']['command'][2] += '$'
    with pytest.raises(verifier.CompatibilityError, match='executable/user drifted'):
        verifier.verify_pre_bindings(args)


def test_early_candidate_runtime_cli_uses_the_same_verified_contract(verifier, pre_binding_documents, monkeypatch, capsys):
    monkeypatch.setattr(verifier.sys, 'argv', [
        'verify', 'candidate-runtime', '--runtime-journal', 'journal',
        '--candidate-image-inspect', 'images', '--candidate-compose-config', 'compose',
        '--candidate-images', 'attestation', '--candidate-revision', 'b'*40,
    ])
    assert verifier.main() == 0
    assert json.loads(capsys.readouterr().out) == {
        'status': 'candidate_operational_runtime_verified', 'service_count': 5,
    }


def test_early_candidate_runtime_cli_refuses_unfrozen_source(verifier, pre_binding_documents, monkeypatch, capsys):
    monkeypatch.setattr(verifier, 'CANDIDATE_RELEASE_FROZEN', False)
    monkeypatch.setattr(verifier.sys, 'argv', [
        'verify', 'candidate-runtime', '--runtime-journal', 'journal',
        '--candidate-image-inspect', 'images', '--candidate-compose-config', 'compose',
        '--candidate-images', 'attestation', '--candidate-revision', 'b'*40,
    ])
    assert verifier.main() == 1
    assert 'freeze is incomplete' in capsys.readouterr().err


@pytest.mark.parametrize('entrypoint', ([], ''))
@pytest.mark.parametrize('command', (None, [], ''))
def test_unreviewed_cleared_entrypoint_without_explicit_executable_fails_closed(verifier, entrypoint, command):
    with pytest.raises(verifier.CompatibilityError, match='requires a nonempty explicit command'):
        verifier.candidate_executable({'Entrypoint':['image-entry'], 'Cmd':['image-command']},
                                      {'entrypoint':entrypoint, 'command':command})


def test_early_runtime_preflight_precedes_scans_and_keeps_stopped_guard():
    source = (ROOT/'infra/scripts/install-on-vm.sh').read_text()
    early = source.index('capture_build55_candidate_runtime early')
    scans = source.index('bash "$HARDENED_SCANNER_TOOL"')
    stopped = source.index('capture_build55_candidate_runtime stopped')
    final_guard = source.index('"$BUILD55_VERIFIER" pre --pre-transition-state')
    assert early < scans < stopped < final_guard
    assert source.index('MAINTENANCE_ACTIVE=true') > early
    assert '${prefix}-candidate-compose.json' in source


@pytest.mark.parametrize('service', ('backend', 'caddy', 'frontend', 'postgres', 'redis'))
@pytest.mark.parametrize('field,value', (
    ('entrypoint', ['different-program']), ('command', ['changed', 'argument']),
    ('user', 'root'), ('user', '0'),
))
def test_complete_pre_bindings_rejects_real_executable_drift(verifier, pre_binding_documents, service, field, value):
    args, documents = pre_binding_documents
    documents['compose']['services'][service][field] = value
    with pytest.raises(verifier.CompatibilityError, match='executable/user drifted'):
        verifier.verify_pre_bindings(args)


@pytest.mark.parametrize('service', ('backend', 'caddy', 'frontend', 'postgres', 'redis'))
def test_complete_pre_bindings_still_rejects_environment_drift(verifier, pre_binding_documents, service):
    args, documents = pre_binding_documents
    documents['compose']['services'][service]['environment']['UNREVIEWED'] = 'added'
    with pytest.raises(verifier.CompatibilityError, match='environment changed'):
        verifier.verify_pre_bindings(args)


@pytest.mark.parametrize('value', ('$', '$$', '$$$$', '${NAME}', '$NAME', 'value $x $$pid $$$$pairs'))
def test_compose_export_is_decoded_once_in_command_and_environment(verifier, value):
    document = {'command': ['sh', '-c', value], 'environment': {'VALUE': value}}
    raw = json.dumps(document).encode().replace(b'$', b'$$')
    assert verifier.decode_compose_export(raw) == document


@pytest.mark.parametrize('raw', (b'{"x":"$"}', b'{"x":"$$$"}', b'{"x":"$$$$$"}', b'{"x":1,"x":2}', b'{"x":NaN}'))
def test_malformed_compose_exports_fail_closed(verifier, raw):
    with pytest.raises(verifier.CompatibilityError):
        verifier.decode_compose_export(raw)


def test_empty_user_inherits_image_but_explicit_user_spelling_is_preserved(verifier):
    image = {'Entrypoint': ['fixed'], 'Cmd': ['default'], 'User': 'erp'}
    for value in (None, ''):
        assert verifier.candidate_executable(image, {'user': value})[-1] == 'erp'
    for value in ('root', '0', 'another-user'):
        assert verifier.candidate_executable(image, {'user': value})[-1] == value


@pytest.mark.parametrize('command', (None, [], ''))
def test_real_daemon_empty_command_inherits_only_with_inherited_entrypoint(verifier, command):
    image = {'Entrypoint':['image-entry'], 'Cmd':['image-command', '$literal$$pair'], 'User':''}
    assert verifier.candidate_executable(image, {'command':command}) == [
        ['image-entry'], ['image-command', '$literal$$pair'], '',
    ]
    assert verifier.candidate_executable(image, {'entrypoint':['explicit-entry'], 'command':command}) == [
        ['explicit-entry'], [], '',
    ]


def test_runtime_canonicalization_never_joins_or_reinterprets_arguments(verifier):
    assert verifier.canonical_executable(None, [], None) == [[], [], '']
    assert verifier.canonical_executable(['fixed'], [''], '') == [['fixed'], [''], '']
    assert verifier.canonical_executable(['fixed'], ['a', 'b'], '') != verifier.canonical_executable(['fixed'], ['a b'], '')
    for value in (False, 0, 'unparsed shell', {'argv': []}):
        with pytest.raises(verifier.CompatibilityError):
            verifier.canonical_executable(value, [], '')


@pytest.mark.parametrize('case', json.loads((ROOT/'tests/fixtures/build57-compose-daemon-oracle.json').read_text())['cases'], ids=lambda case:case['name'])
def test_captured_real_compose_daemon_contract_cannot_regress(verifier, case):
    fixture = json.loads((ROOT/'tests/fixtures/build57-compose-daemon-oracle.json').read_text())
    assert fixture['synthetic_only'] is True
    assert fixture['docker_version'] == '29.6.1'
    assert fixture['compose_version'] == '5.2.0'
    raw = case['compose_export_json'].encode()
    assert hashlib.sha256(raw).hexdigest() == case['compose_export_sha256']
    compose = verifier.decode_compose_export(raw)
    assert set(case['actual_daemon']) == set(verifier.SERVICES)
    for service, actual in case['actual_daemon'].items():
        image = fixture['images'][service]
        config = compose['services'][service]
        assert verifier.candidate_executable(image, config) == actual['executable']
        assert verifier.candidate_image_environment(image['Env'], config.get('environment', {}), service=service) == actual['environment']


@pytest.fixture
def verifier():
    spec = importlib.util.spec_from_file_location(
        "build55_preservation",
        ROOT / "infra/scripts/verify-build55-pending-compatibility.py",
    )
    assert spec is not None
    assert spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.fixture
def inventories(verifier, monkeypatch):
    names = (
        "alembic_version",
        "audit_log",
        "payments",
        "payment_tender_corrections",
        "daily_tender_reconciliations",
        "client_installations",
        "collection_source_guards",
        "gaming_sessions",
    )
    schema = [
        "column|audit_log|1|id|bigint",
        "index|audit_log|audit_log_pkey|synthetic",
        "function|old_void_guard|old reviewed body",
    ]
    monkeypatch.setattr(
        verifier,
        "PRIOR_TABLE_NAMES_SHA256",
        verifier.sha("\n".join(sorted(names)).encode()),
    )
    monkeypatch.setattr(
        verifier, "PRIOR_SCHEMA_SHA256", verifier.sha("\n".join(schema).encode())
    )
    schema.sort()
    monkeypatch.setattr(
        verifier, "PRIOR_SCHEMA_SHA256", verifier.sha("\n".join(schema).encode())
    )
    old_function = "function|old_void_guard|old reviewed body"
    new_function = "function|retained_void_guard|new reviewed body"
    old_trigger = "trigger_binding|gaming_sessions|old_guard|O|false|false|BEFORE UPDATE OF status"
    new_trigger = "trigger_binding|gaming_sessions|retained_guard|O|true|true|AFTER UPDATE OF status"
    monkeypatch.setattr(verifier, "EXPECTED_0093_SCHEMA_REMOVED_ROWS", (old_function,))
    monkeypatch.setattr(verifier, "EXPECTED_0093_SCHEMA_ADDED_ROWS", (new_function,))
    monkeypatch.setattr(verifier, "EXPECTED_TRIGGER_REMOVED_ROWS", (old_trigger,))
    monkeypatch.setattr(verifier, "EXPECTED_TRIGGER_ADDED_ROWS", (new_trigger,))
    monkeypatch.setattr(
        verifier, "PRIOR_TRIGGER_CATALOG_SHA256", verifier.sha(old_trigger.encode())
    )
    before = {
        "schema_version": 2,
        "kind": "build55-full-transition-v2",
        "database_revision": "0091",
        "database_identity": {
            "database_name": "erp",
            "database_oid": 42,
            "system_identifier": "123456789",
        },
        "tables": {
            name: {"row_count": 3, "row_sha256": verifier.sha(name.encode())}
            for name in names
        },
        "sequences": {
            "audit_log_id_seq": {
                "last_value": 91,
                "is_called": True,
                "start_value": 1,
                "increment_by": 1,
                "min_value": 1,
                "max_value": 2**63 - 1,
                "cache_size": 1,
                "cycle": False,
                "owned_by": "audit_log.id",
            }
        },
        "schema_catalog_row_count": len(schema),
        "schema_catalog_rows": schema,
        "schema_catalog_sha256": verifier.sha("\n".join(schema).encode()),
    }
    before["tables"]["alembic_version"] = {
        "row_count": 1,
        "row_sha256": verifier.sha(b'{"version_num": "0091"}'),
    }
    before["gaming_sessions_legacy_projection"] = {
        "column_present": False,
        "row_count": 3,
        "row_sha256": before["tables"]["gaming_sessions"]["row_sha256"],
        "nonnull_count": 0,
    }
    before.update(
        trigger_catalog_rows=[old_trigger],
        trigger_catalog_row_count=1,
        trigger_catalog_sha256=verifier.sha(old_trigger.encode()),
    )
    after = deepcopy(before)
    after["database_revision"] = "0094"
    after["tables"]["alembic_version"]["row_sha256"] = verifier.sha(
        b'{"version_num": "0094"}'
    )
    after["tables"]["android_update_policy"] = {
        "row_count": 0,
        "row_sha256": verifier.EMPTY_SHA256,
    }
    for name in verifier.NEW_TABLES:
        after["tables"][name] = {"row_count": 0, "row_sha256": verifier.EMPTY_SHA256}
    after["gaming_sessions_legacy_projection"]["column_present"] = True
    after["tables"]["gaming_sessions"]["row_sha256"] = verifier.sha(
        b"full rows with NULL new column"
    )
    new_triggers = sorted([new_trigger, *verifier.EXPECTED_0094_TRIGGER_ADDED_ROWS])
    after.update(
        trigger_catalog_rows=new_triggers,
        trigger_catalog_row_count=len(new_triggers),
        trigger_catalog_sha256=verifier.sha("\n".join(new_triggers).encode()),
    )
    after["schema_catalog_rows"] = sorted(
        [row for row in schema if row != old_function]
        + list(verifier.EXPECTED_POLICY_SCHEMA_ROWS)
        + [new_function]
        + list(verifier.EXPECTED_0094_SCHEMA_ADDED_ROWS)
    )
    after["schema_catalog_row_count"] = len(after["schema_catalog_rows"])
    after["schema_catalog_sha256"] = verifier.sha(
        "\n".join(after["schema_catalog_rows"]).encode()
    )
    return before, after


def test_exact_reviewed0094_delta_keeps_nonempty_legacy_rows(verifier, inventories):
    before, after = inventories
    assert len(verifier.EXPECTED_POLICY_SCHEMA_ROWS) == 18
    assert verifier.sha("\n".join(verifier.EXPECTED_POLICY_SCHEMA_ROWS).encode()) == (
        "22e003318c1585ea140fb16a37c45ecbbd9631e74632b5a3f56e426b5f25170b"
    )
    proof = verifier.verify_pair(before, after, mode="post")
    assert proof["full_audit_finance_rows_preserved"] is True
    assert proof["preserved_legacy_table_count"] == len(before["tables"])


@pytest.mark.parametrize(
    "table",
    [
        "audit_log",
        "payments",
        "payment_tender_corrections",
        "daily_tender_reconciliations",
        "client_installations",
        "collection_source_guards",
    ],
)
def test_changed_full_row_is_rejected_even_when_count_is_unchanged(
    verifier, inventories, table
):
    before, after = inventories
    after["tables"][table]["row_sha256"] = "f" * 64
    with pytest.raises(verifier.CompatibilityError, match="history changed"):
        verifier.verify_pair(before, after, mode="post")


@pytest.mark.parametrize("change", ["sequence", "schema", "seed", "lineage", "alembic"])
def test_no_sequence_schema_seed_or_database_drift_is_admitted(
    verifier, inventories, change
):
    before, after = inventories
    if change == "sequence":
        after["sequences"]["audit_log_id_seq"]["last_value"] += 1
    elif change == "schema":
        after["schema_catalog_rows"].append("trigger|payments|unexpected")
        after["schema_catalog_rows"].sort()
        after["schema_catalog_row_count"] += 1
        after["schema_catalog_sha256"] = verifier.sha(
            "\n".join(after["schema_catalog_rows"]).encode()
        )
    elif change == "seed":
        after["tables"]["android_update_policy"] = {
            "row_count": 1,
            "row_sha256": "a" * 64,
        }
    elif change == "lineage":
        after["database_identity"]["database_oid"] += 1
    else:
        after["tables"]["alembic_version"]["row_count"] = 2
    with pytest.raises(verifier.CompatibilityError):
        verifier.verify_pair(before, after, mode="post")


def test_restore_requires_same_cluster_owned_database_and_never_after_writers(
    verifier, inventories
):
    before, _ = inventories
    restored = deepcopy(before)
    restored["database_identity"].update(
        database_name="code17_restore_verify_owned", database_oid=43
    )
    assert (
        verifier.verify_pair(before, restored, mode="restore")[
            "logical_restore_verified"
        ]
        is True
    )
    restored["database_identity"]["system_identifier"] = "987654321"
    with pytest.raises(verifier.CompatibilityError, match="same cluster"):
        verifier.verify_pair(before, restored, mode="restore")
    with pytest.raises(verifier.CompatibilityError, match="forbidden"):
        verifier.verify_pair(
            before, deepcopy(before), mode="rollback", writers_started=True
        )


@pytest.fixture
def fleet(verifier, monkeypatch):
    rows = []
    for index, (version, name, pending) in enumerate(
        ((37, "3.1.29", 2), (41, "3.1.33", 1), (54, "3.1.46", 4)), 1
    ):
        row = {
            "row_id": str(UUID(int=index)),
            "installation_id": str(UUID(int=index + 10)),
            "company_id": str(UUID(int=30)),
            "terminal_id": str(UUID(int=40)),
            "platform": "android",
            "version_name": name,
            "version_code": version,
            "pending_outbox_count": pending,
            "last_seen_at": "2026-10-21T20:00:00.000000Z",
            "last_successful_sync_at": None,
        }
        parts = [
            row[key]
            for key in (
                "row_id",
                "installation_id",
                "company_id",
                "terminal_id",
                "platform",
                "version_name",
            )
        ]
        row["identity_sha256"] = verifier.sha(
            chr(31)
            .join(parts + [str(version), str(pending), row["last_seen_at"]])
            .encode()
        )
        rows.append(row)
    monkeypatch.setattr(verifier, "ALL_INSTALLATIONS_IDENTITY_SHA256", "a" * 64)
    return {
        "schema_version": 1,
        "database_revision": "0091",
        "pending_outbox_count": 7,
        "nonzero_installations": rows,
        "installation_row_count": 13,
        "nonzero_installation_count": 3,
        "all_installations_identity_sha256": "a" * 64,
        "nonzero_installations_identity_sha256": verifier.sha(
            "\n".join(row["identity_sha256"] for row in rows).encode()
        ),
        "client_installation_guard_trigger_count": 1,
        "client_installation_guard_function_sha256": "b" * 64,
        "client_installation_guard_trigger_definition_sha256": "c" * 64,
        "client_installation_guard_function_definition_sha256": verifier.FLEET_GUARD_SHA256,
    }


def test_every_old_and_current_build_opaque_pending_row_is_preserved(verifier, fleet):
    verifier.verify_fleet(fleet, "0091")
    assert fleet["pending_outbox_count"] == 7  # No stale2/2/0 hardcoded drain gate.


@pytest.mark.parametrize(
    "change",
    ["pending", "version", "identity", "duplicate", "timestamp", "guard", "counter"],
)
def test_opaque_queue_or_identity_reclassification_is_rejected(verifier, fleet, change):
    if change == "pending":
        fleet["nonzero_installations"][2]["pending_outbox_count"] = 0
    elif change == "version":
        fleet["nonzero_installations"][2]["version_code"] = 37
    elif change == "identity":
        fleet["nonzero_installations"][0]["terminal_id"] = str(UUID(int=99))
    elif change == "duplicate":
        fleet["nonzero_installations"].append(
            deepcopy(fleet["nonzero_installations"][0])
        )
    elif change == "timestamp":
        fleet["nonzero_installations"][0]["last_seen_at"] = (
            "2026-02-31T00:00:00.000000Z"
        )
    elif change == "guard":
        fleet["client_installation_guard_function_definition_sha256"] = "f" * 64
    else:
        fleet["pending_outbox_count"] = 3
    with pytest.raises(verifier.CompatibilityError):
        verifier.verify_fleet(fleet, "0091")


@pytest.fixture
def runtime(verifier, monkeypatch, request):
    records = []
    reviewed = getattr(request, 'param', None) == 'reviewed_commands'
    triples = reviewed_command_triples(verifier) if reviewed else None
    for service in verifier.SERVICES:
        env = ["PATH=/usr/local/bin:/usr/bin:/bin", "IMAGE_LITERAL=keep$$image"]
        if service == "backend":
            env += [
                "APP_VERSION=3.1.46",
                "APP_REVISION=" + verifier.PRIOR_REVISION,
                "PYTHON_VERSION=3.14.8",
                "PYTHON_SHA256=c2215904f02b175596dc49351585104f4bc20341e1c47378b26a2c274360ce73",
                "ANDROID_MIN_SUPPORTED_VERSION_CODE=54",
                "ANDROID_LATEST_VERSION_CODE=54",
                "CLIENT_COMPATIBILITY_POLICY_REVISION=4",
                "CLIENT_UPDATE_MESSAGE=Build54 $inert",
            ]
        elif service == "frontend":
            env.append("NGINX_VERSION=1.31.5")
        config = {
            "Env": env,
            "Entrypoint": verifier.BACKEND_UVICORN_ENTRYPOINT
            if service == "backend"
            else ["fixed"],
            "Cmd": [],
            "User": "erp" if service == "backend" else "",
            "Labels": {
                "com.docker.compose.service": service,
                "com.docker.compose.project.config_files": "/var/lib/dcompany-erp/prior/compose.yml,/var/lib/dcompany-erp/prior/override.yml",
            },
        }
        if reviewed:
            config['Entrypoint'], config['Cmd'], config['User'] = deepcopy(triples[service])
        monkeypatch.setitem(
            verifier.PRIOR_ENVIRONMENT_SHA256,
            service,
            verifier.sha(json.dumps(sorted(env), separators=(",", ":")).encode()),
        )
        if not reviewed:
            monkeypatch.setitem(
                verifier.PRIOR_COMMAND_SHA256,
                service,
                verifier.sha(
                    json.dumps(
                        [config["Entrypoint"], config["Cmd"], config["User"]],
                        separators=(",", ":"),
                    ).encode()
                ),
            )
        records.append({"Image": verifier.PRIOR_IMAGES[service], "Config": config})
    files = [
        {
            "path": "/var/lib/dcompany-erp/prior/" + name,
            "sha256": digest,
            "uid": 0,
            "mode": 0o600,
            "regular_nonlinked": True,
        }
        for name, digest in (
            ("compose.yml", verifier.PRIOR_COMPOSE_SHA256),
            ("override.yml", verifier.PRIOR_OPERATIONAL_OVERRIDE_SHA256),
        )
    ]
    return {"schema_version": 1, "containers": records, "configuration_files": files}


def test_only_reviewed_uvicorn_override_is_reconstructed(verifier, runtime):
    override = verifier.rollback_override(runtime)
    assert (
        override["services"]["backend"]["entrypoint"]
        == verifier.BACKEND_UVICORN_ENTRYPOINT
    )
    assert override["services"]["backend"]["command"] == []
    assert override["services"]["backend"]["environment"] == {
        "CLIENT_UPDATE_MESSAGE": "Build54 $$inert"
    }
    runtime["containers"][0]["Config"]["Entrypoint"] = [
        "sh",
        "-c",
        "arbitrary captured command",
    ]
    with pytest.raises(verifier.CompatibilityError):
        verifier.rollback_override(runtime)


def test_candidate_and_restored_actual_runtime_include_effective_env_and_commands(
    verifier, runtime
):
    actual = {
        row["Config"]["Labels"]["com.docker.compose.service"]: deepcopy(row)
        for row in runtime["containers"]
    }
    identity = {
        "candidate_revision": "b" * 40,
        "candidate_images": {
            name: "sha256:" + str(index) * 64
            for index, name in enumerate(verifier.SERVICES, 1)
        },
    }
    assert (
        verifier.verify_running_runtime(runtime, actual, identity, prior=True)[
            "service_count"
        ]
        == 5
    )
    for name, row in actual.items():
        row["Image"] = identity["candidate_images"][name]
        env = verifier.environment(row["Config"]["Env"])
        if name == "backend":
            env = verifier.candidate_backend_environment(env, "b" * 40)
        elif name == "frontend":
            env['NGINX_VERSION'] = '1.31.8'
        row["Config"]["Env"] = [key + "=" + value for key, value in env.items()]
    assert (
        verifier.verify_running_runtime(runtime, actual, identity)[
            "effective_environment_verified"
        ]
        is True
    )
    actual["backend"]["Config"]["Env"].append("UNREVIEWED=1")
    with pytest.raises(verifier.CompatibilityError, match="environment changed"):
        verifier.verify_running_runtime(runtime, actual, identity)


def test_migration_pins_all_five_ids_and_changes_nothing_else(verifier):
    images = {
        service: "sha256:" + str(index) * 64
        for index, service in enumerate(verifier.SERVICES, 1)
    }
    base = {
        "services": {
            service: {"image": service + ":reviewed", "environment": {"KEEP": "1"}}
            for service in verifier.SERVICES
        }
    }
    pinned = deepcopy(base)
    for service in verifier.SERVICES:
        pinned["services"][service]["image"] = images[service]
    verifier.verify_migration_compose(base, pinned, images)
    pinned["services"]["backend"]["entrypoint"] = ["/entrypoint.sh"]
    with pytest.raises(verifier.CompatibilityError, match="more than"):
        verifier.verify_migration_compose(base, pinned, images)


def test_strict_documents_refuse_duplicates_and_nonfinite_values(verifier):
    for raw in (b'{"x":1,"x":2}', b'{"x":NaN}', b"[]"):
        with pytest.raises(verifier.CompatibilityError):
            verifier.decode(raw)


def test_generic_preservation_is_not_permission_to_admit_fresh_schema(
    verifier, inventories
):
    before, after = inventories
    for document in (before, after):
        document["schema_catalog_rows"].append(
            "column|menu_modifiers|99|fresh_only|text"
        )
        document["schema_catalog_rows"].sort()
        document["schema_catalog_row_count"] += 1
        document["schema_catalog_sha256"] = verifier.sha(
            "\n".join(document["schema_catalog_rows"]).encode()
        )
    assert (
        verifier.verify_pair(before, after, mode="post")[
            "full_audit_finance_rows_preserved"
        ]
        is True
    )
    with pytest.raises(
        verifier.CompatibilityError, match="not the reviewed predecessor"
    ):
        verifier.verify_prior(before)


@pytest.mark.parametrize(
    ("phase", "tamper"),
    [("pre", "fresh_schema")]
    + [
        (phase, tamper)
        for phase in ("post", "restore", "rollback")
        for tamper in (
            "fresh_schema",
            "sealed_financial_baseline",
            "sealed_fleet_baseline",
        )
    ],
)
def test_every_actual_cli_phase_strictly_admits_its_original_sealed_baseline(
    verifier, inventories, fleet, monkeypatch, capsys, phase, tamper
):
    before, after = inventories
    monkeypatch.setattr(verifier, "CANDIDATE_RELEASE_FROZEN", True)
    monkeypatch.setattr(
        verifier, "PRIOR_DATABASE_IDENTITY", deepcopy(before["database_identity"])
    )
    monkeypatch.setattr(
        verifier, "PRIOR_SCHEMA_ROW_COUNT", before["schema_catalog_row_count"]
    )
    original_before = json.dumps(before).encode()
    original_fleet = json.dumps(fleet).encode()
    if tamper == "fresh_schema":
        before["schema_catalog_rows"].append("column|menu_modifiers|99|fresh_only|text")
        before["schema_catalog_rows"].sort()
        before["schema_catalog_row_count"] += 1
        before["schema_catalog_sha256"] = verifier.sha(
            "\n".join(before["schema_catalog_rows"]).encode()
        )
    elif tamper == "sealed_financial_baseline":
        before["tables"]["payment_tender_corrections"]["row_sha256"] = "f" * 64
    else:
        fleet["nonzero_installations"][2]["pending_outbox_count"] += 1
    documents = {
        "before": json.dumps(before).encode(),
        "fleet": json.dumps(fleet).encode(),
        "after": json.dumps(after).encode(),
        "after-fleet": original_fleet,
    }
    monkeypatch.setattr(verifier, "private_file", lambda path: documents[path.name])
    arguments = [
        "verify55",
        phase,
        "--pre-transition-state",
        "before",
        "--pre-fleet-state",
        "fleet",
    ]
    if phase == "pre":
        for name in (
            "runtime-journal",
            "prior-source-root",
            "candidate-source-root",
            "identity",
            "prior-env",
            "candidate-env",
            "live-env",
            "candidate-image-inspect",
            "candidate-compose-config",
        ):
            arguments += ["--" + name, "not-reached"]
    else:
        arguments += [
            "--after-transition-state",
            "after",
            "--after-fleet-state",
            "after-fleet",
            "--pre-transition-sha256",
            verifier.sha(original_before),
            "--pre-fleet-sha256",
            verifier.sha(original_fleet),
        ]
    monkeypatch.setattr(verifier.sys, "argv", arguments)
    assert verifier.main() == 1
    output = capsys.readouterr()
    assert output.out == ""
    assert "Build55 transition refused" in output.err
    assert "not-reached" not in output.err


def test_live_production_expanded_query_pin_is_distinct_from_old54_query(verifier):
    assert (
        verifier.PRIOR_SCHEMA_SHA256
        == "f3be2f415f6180433cd117aaa5b9e5b65a46e8122ac265bcc10521570fe6c26d"
    )
    assert verifier.PRIOR_SCHEMA_ROW_COUNT == 4485
    assert verifier.PRIOR_DATABASE_IDENTITY == {
        "database_name": "erp",
        "database_oid": 16384,
        "system_identifier": "7695211338147233825",
    }


def test_main_installer_orders_preservation_before_any_writer_and_never_reopens_credentials():
    source = (ROOT / "infra/scripts/install-on-vm.sh").read_text()
    assert "grep -qx 'APP_VERSION=3.1.58'" in source
    assert "APP_VERSION=3.1.47" not in source
    profile = source.index(
        'if [ "$BUILD55_EXACT_TRANSITION" = true ]; then\n    BUILD55_PRE_TRANSITION='
    )
    historical = source.index("BUILD51_CURRENT_BASELINE=", profile)
    assert profile < historical
    assert source.index('"$BUILD55_VERIFIER" post') < source.index(
        "BUILD55_APP_WRITERS_STARTED=true"
    )
    assert source.index('"$BUILD55_VERIFIER" migration-runtime') < source.index(
        '"${candidate_compose[@]}" --env-file .env run --pull never --rm --no-deps --entrypoint python backend'
    )
    assert '"$BUILD55_VERIFIER" running-runtime --prior' in source
    assert "build55-runtime-before-ingress-verification.json" in source
    assert 'build55-prior-images.override.json) >> "$ROLLBACK_STATE_MANIFEST"' in source
    assert '"$BUILD55_AUTH_TOOL" internal-probe' in source
    assert '< "$BUILD55_ACCEPTANCE_CREDENTIAL_FILE"' not in source
    assert 'sha256sum "$BUILD55_ACCEPTANCE_CREDENTIAL_FILE"' not in source


@pytest.mark.parametrize("version", ["3.1.46", "3.1.47", "3.1.48", "3.1.49", "3.1.50", "3.1.51", "3.1.52", "3.1.53", "3.1.54", "3.1.55", "3.1.56", "3.1.57", "3.1.59", "3.1.60", "3.1.61", "3.1.62", "3.1.63", "3.1.64"])
def test_current_profile_rejects_withheld_or_other_release_identity(verifier, monkeypatch, version):
    from types import SimpleNamespace

    assert version != verifier.CANDIDATE_VERSION
    identity = {
        "prior_revision": verifier.PRIOR_REVISION,
        "prior_version": verifier.PRIOR_VERSION,
        "prior_database_head": verifier.PRIOR_DATABASE_HEAD,
        "candidate_revision": "b" * 40,
        "candidate_version": version,
        "candidate_database_head": verifier.CANDIDATE_DATABASE_HEAD,
        "prior_source_archive_sha256": verifier.PRIOR_SOURCE_ARCHIVE_SHA256,
        "candidate_source_archive_sha256": "c" * 64,
        "prior_backend_manifest_sha256": verifier.PRIOR_BACKEND_MANIFEST_SHA256,
        "candidate_backend_manifest_sha256": verifier.CANDIDATE_BACKEND_MANIFEST_SHA256,
        "prior_images": verifier.PRIOR_IMAGES,
        "candidate_images": {service: "sha256:" + "d" * 64 for service in verifier.SERVICES},
    }
    monkeypatch.setattr(verifier, "private_file", lambda _path: json.dumps(identity).encode())
    with pytest.raises(verifier.CompatibilityError, match="unsupported or unfrozen release identity"):
        verifier.verify_pre_bindings(SimpleNamespace(identity="synthetic-identity-only"))


def test_installer_backend_manifest_uses_verifier_posix_name_order(tmp_path, verifier):
    files = {
        "alembic.ini": b"predecessor migration configuration",
        "alembic/env.py": b"migration environment",
        "app.py": b"application entry",
        "app/worker.py": b"worker entry",
    }
    for name, data in files.items():
        path = tmp_path / "backend" / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    source = (ROOT / "infra/scripts/install-on-vm.sh").read_text()
    identity = (
        source.split("<<'PYIDENTITY'", 1)[1]
        .split("\n", 1)[1]
        .split("\nPYIDENTITY", 1)[0]
    )
    parsed = ast.parse(identity)
    loop = next(node for node in parsed.body if isinstance(node, ast.For))
    digest = next(
        node
        for node in parsed.body
        if isinstance(node, ast.Assign)
        and any(
            isinstance(target, ast.Name) and target.id == "candidate_manifest"
            for target in node.targets
        )
    )
    namespace = {"root": tmp_path, "manifest_rows": [], "hashlib": hashlib}
    exec(
        compile(
            ast.Module(body=[loop, digest], type_ignores=[]),
            "installer-PYIDENTITY",
            "exec",
        ),
        namespace,
    )
    inventory = verifier.inventory(tmp_path, ("backend",))
    canonical = "".join(
        f"{digest}\t{name.removeprefix('backend/')}\n"
        for name, digest in sorted(inventory.items())
    )
    old_component_order = "".join(
        f"{hashlib.sha256(path.read_bytes()).hexdigest()}\t{path.relative_to(tmp_path / 'backend').as_posix()}\n"
        for path in sorted((tmp_path / "backend").rglob("*"))
        if path.is_file()
    )
    assert namespace["candidate_manifest"] == verifier.sha(canonical.encode())
    assert verifier.sha(old_component_order.encode()) != namespace["candidate_manifest"]


def test_frozen_candidate_backend_manifest_matches_released65_git_archive_wire(tmp_path, verifier):
    # This verifier's candidate constants describe released Build65, even
    # when this contract runs on a later checkout.
    released65 = "9e31d0daaa5fbf8b46647a213f6e7beab1ca087a"
    archive = subprocess.check_output(["git", "archive", released65], cwd=ROOT)
    with tarfile.open(fileobj=io.BytesIO(archive)) as source:
        source.extractall(tmp_path, filter="data")
    installer = (tmp_path / "infra/scripts/install-on-vm.sh").read_text()
    identity = installer.split("<<'PYIDENTITY'", 1)[1].split("\n", 1)[1].split("\nPYIDENTITY", 1)[0]
    parsed = ast.parse(identity)
    loop = next(node for node in parsed.body if isinstance(node, ast.For))
    digest = next(
        node for node in parsed.body
        if isinstance(node, ast.Assign)
        and any(isinstance(target, ast.Name) and target.id == "candidate_manifest" for target in node.targets)
    )
    namespace = {"root": tmp_path, "manifest_rows": [], "hashlib": hashlib}
    exec(compile(ast.Module(body=[loop, digest], type_ignores=[]), "installer-PYIDENTITY-real-source", "exec"), namespace)
    backend = verifier.inventory(tmp_path, ("backend",))
    canonical = "".join(
        f"{digest}\t{name.removeprefix('backend/')}\n" for name, digest in sorted(backend.items())
    ).encode()
    assert backend
    assert namespace["candidate_manifest"] == verifier.sha(canonical)
    assert namespace["candidate_manifest"] != verifier.inventory_hash(backend)
    assert verifier.CANDIDATE_RELEASE_FROZEN is True
    assert verifier.CANDIDATE_BACKEND_MANIFEST_SHA256 == namespace["candidate_manifest"]

    protected = verifier.inventory(tmp_path, verifier.PROTECTED_SCOPES)
    assert protected
    assert verifier.CANDIDATE_PROTECTED_SOURCE_SHA256 == verifier.inventory_hash(protected)

def test_installer_and_extractable_legacy_guard_have_complete_shell_syntax(tmp_path):
    installer = ROOT / "infra/scripts/install-on-vm.sh"
    source = installer.read_text()
    start = source.index("  # Preserve the original legacy-path guard")
    end = source.index(
        '  echo "==> Creating final quiesced pre-upgrade PostgreSQL backup', start
    )
    fragment = tmp_path / "legacy-pending-guard.sh"
    fragment.write_text(source[start:end])
    for path in (installer, fragment):
        result = subprocess.run(
            ["bash", "-n", str(path)], capture_output=True, text=True
        )
        assert result.returncode == 0, result.stderr


@pytest.mark.parametrize(
    ("exact55", "legacy42", "expected_success"),
    ((True, False, True), (False, True, True), (False, False, False)),
)
def test_installer_legacy_guard_is_separate_from_the_exact55_proof(
    exact55, legacy42, expected_success
):
    source = (ROOT / "infra/scripts/install-on-vm.sh").read_text()
    start = source.index("  # Preserve the original legacy-path guard")
    end = source.index(
        '  echo "==> Creating final quiesced pre-upgrade PostgreSQL backup', start
    )
    prefix = (
        "set -eu\npending_outbox_count=3\n"
        f"BUILD55_EXACT_TRANSITION={str(exact55).lower()}\n"
        "CODE30_2_POST_CLEANUP_STALE_OUTBOX_ACCEPTED=false\n"
        "CODE30_2_STALE_OUTBOX_BRIDGE_USED=false\n"
        f"BUILD42_PENDING_CARRIED_UNRESOLVED={str(legacy42).lower()}\n"
    )
    result = subprocess.run(
        ["bash", "-c", prefix + source[start:end]], capture_output=True, text=True
    )
    assert (result.returncode == 0) is expected_success, result.stderr
    assert "syntax error" not in result.stderr


def test_build55_backend_write_surface_is_bounded_to_reviewed_corrections(verifier):
    assert verifier.PRIOR_REVISION == "5ad146ae076f5178acc2c8e4cb840ecbb0d0343a"
    # Compare the historical release trees after the active worktree advances.
    changed = set(
        subprocess.run(
            [
                "git",
                "diff",
                "--name-only",
                verifier.PRIOR_REVISION,
                "9e31d0daaa5fbf8b46647a213f6e7beab1ca087a",
                "--",
                "backend/app",
                "backend/alembic",
            ],
            cwd=ROOT,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.splitlines()
    )
    assert changed == {
        "backend/alembic/versions/0092_android_update_policy.py",
        "backend/alembic/versions/0093_gaming_void_retained_addons.py",
        "backend/alembic/versions/0094_staff_work_policy.py",
        "backend/app/api/v1/finance/router.py",
        "backend/app/api/v1/router.py",
        "backend/app/api/v1/staff/work_time.py",
        "backend/app/api/v1/staff/router.py",
        "backend/app/api/v1/settings/google_sheets.py",
        "backend/app/services/integrations/google_sheets_mirror.py",
        "backend/app/services/integrations/google_sheets_staff.py",
        "backend/app/core/db.py",
        "backend/app/models/staff_work.py",
        "backend/app/services/alerts/business.py",
        "backend/app/services/audit/recorder.py",
        "backend/app/services/email/mailer.py",
        "backend/app/services/pos/points.py",
        "backend/app/services/staff_time.py",
        "backend/app/services/staff_pay_estimate.py",
        "backend/app/__init__.py",
        "backend/app/api/v1/analytics/router.py",
        "backend/app/api/v1/client_updates/router.py",
        "backend/app/api/v1/gaming/router.py",
        "backend/app/api/v1/pos/router.py",
        "backend/app/api/v1/public/router.py",
        "backend/app/api/v1/reports/router.py",
        "backend/app/core/middleware.py",
        "backend/app/core/permissions.py",
        "backend/app/core/roles.py",
        "backend/app/main.py",
        "backend/app/models/__init__.py",
        "backend/app/models/client_update.py",
        "backend/app/models/gaming.py",
        "backend/app/services/analytics/home_read_model.py",
        "backend/app/services/audit/catalogue.py",
        "backend/app/services/client_updates/policy.py",
        "backend/app/services/gaming/void.py",
        "backend/app/services/pos/pricing.py",
        "backend/app/services/reports/overview.py",
        "backend/app/services/reports/overview_csv.py",
    }


def _catalogue_hash(verifier, document):
    document["schema_catalog_rows"].sort()
    document["schema_catalog_row_count"] = len(document["schema_catalog_rows"])
    document["schema_catalog_sha256"] = verifier.sha(
        "\n".join(document["schema_catalog_rows"]).encode()
    )


def _restored_schema_pair(verifier, inventories):
    before, _after = inventories
    before["schema_catalog_rows"] += [
        live for live, _ in verifier.RESTORE_CONSTRAINT_PAIRS
    ]
    _catalogue_hash(verifier, before)
    restored = deepcopy(before)
    restored["database_identity"].update(
        database_name="code17_restore_verify_fixture", database_oid=43
    )
    replacements = dict(verifier.RESTORE_CONSTRAINT_PAIRS)
    restored["schema_catalog_rows"] = [
        replacements.get(row, row) for row in before["schema_catalog_rows"]
    ]
    _catalogue_hash(verifier, restored)
    return before, restored


def test_restore_accepts_only_five_exact_reviewed_deparser_pairs_and_retains_raw_evidence(
    verifier, inventories
):
    before, restored = _restored_schema_pair(verifier, inventories)
    assert len(verifier.RESTORE_CONSTRAINT_PAIRS) == 5
    original, restored_raw = deepcopy(before), deepcopy(restored)
    assert before["schema_catalog_sha256"] != restored["schema_catalog_sha256"]
    assert (
        verifier.verify_pair(before, restored, mode="restore")[
            "logical_restore_verified"
        ]
        is True
    )
    assert before == original
    assert restored == restored_raw


@pytest.mark.parametrize(
    "change",
    [
        "changed_check",
        "missing",
        "duplicate",
        "index",
        "column",
        "function",
        "forged_hash",
        "finance",
    ],
)
def test_restore_equivalence_cannot_hide_any_other_schema_or_financial_change(
    verifier, inventories, change
):
    before, restored = _restored_schema_pair(verifier, inventories)
    live, exact_restored = verifier.RESTORE_CONSTRAINT_PAIRS[0]
    if change == "changed_check":
        restored["schema_catalog_rows"].remove(exact_restored)
        restored["schema_catalog_rows"].append(exact_restored + " AND changed")
    elif change == "missing":
        restored["schema_catalog_rows"].remove(exact_restored)
    elif change == "duplicate":
        restored["schema_catalog_rows"].append(live)
    elif change in {"index", "column", "function"}:
        restored["schema_catalog_rows"].append(change + "|audit_log|unreviewed")
    elif change == "finance":
        restored["tables"]["daily_tender_reconciliations"]["row_sha256"] = "f" * 64
    _catalogue_hash(verifier, restored)
    if change == "forged_hash":
        restored["schema_catalog_sha256"] = "f" * 64
    with pytest.raises(verifier.CompatibilityError):
        verifier.verify_pair(before, restored, mode="restore")


def test_live_post_migration_never_normalizes_restore_aliases(verifier, inventories):
    before, after = inventories
    before["schema_catalog_rows"] += [
        live for live, _ in verifier.RESTORE_CONSTRAINT_PAIRS
    ]
    after["schema_catalog_rows"] += [
        restored for _, restored in verifier.RESTORE_CONSTRAINT_PAIRS
    ]
    _catalogue_hash(verifier, before)
    _catalogue_hash(verifier, after)
    with pytest.raises(verifier.CompatibilityError, match="exact reviewed"):
        verifier.verify_pair(before, after, mode="post")


@pytest.mark.parametrize(
    "change",
    [
        "legacy_session",
        "nonnull_link",
        "missing_column",
        "receipt_seed",
        "mapping_seed",
        "extra_table",
        "removed_function",
        "altered_function",
        "trigger_columns",
        "trigger_enabled",
        "trigger_deferral",
        "trigger_hash",
        "projection_count",
    ],
)
def test_0093_projection_and_exact_schema_reject_drift(verifier, inventories, change):
    before, after = inventories
    if change == "legacy_session":
        after["gaming_sessions_legacy_projection"]["row_sha256"] = "f" * 64
    elif change == "nonnull_link":
        after["gaming_sessions_legacy_projection"]["nonnull_count"] = 1
    elif change == "missing_column":
        after["gaming_sessions_legacy_projection"]["column_present"] = False
    elif change == "projection_count":
        after["gaming_sessions_legacy_projection"]["row_count"] += 1
    elif change in {"receipt_seed", "mapping_seed"}:
        name = (
            "gaming_void_receipts"
            if change == "receipt_seed"
            else "gaming_void_addon_lines"
        )
        after["tables"][name] = {"row_count": 1, "row_sha256": "a" * 64}
    elif change == "extra_table":
        after["tables"]["unreviewed"] = {
            "row_count": 0,
            "row_sha256": verifier.EMPTY_SHA256,
        }
    elif change in {"removed_function", "altered_function"}:
        row = verifier.EXPECTED_0093_SCHEMA_ADDED_ROWS[0]
        after["schema_catalog_rows"].remove(row)
        if change == "altered_function":
            after["schema_catalog_rows"].append(row + " changed")
        _catalogue_hash(verifier, after)
    elif change == "trigger_hash":
        after["trigger_catalog_sha256"] = "f" * 64
    else:
        row = after["trigger_catalog_rows"][0]
        row = (
            row.replace("UPDATE OF status", "UPDATE OF amount_minor")
            if change == "trigger_columns"
            else (
                row.replace("|O|", "|D|")
                if change == "trigger_enabled"
                else row.replace("|true|true|", "|false|false|")
            )
        )
        after["trigger_catalog_rows"] = [row]
        after["trigger_catalog_sha256"] = verifier.sha(row.encode())
    with pytest.raises(verifier.CompatibilityError):
        verifier.verify_pair(before, after, mode="post")


def test_0093_unreviewed_schema_delta_stays_closed(verifier, inventories, monkeypatch):
    before, after = inventories
    monkeypatch.setattr(verifier, "EXPECTED_0093_SCHEMA_REMOVED_ROWS", ())
    with pytest.raises(verifier.CompatibilityError, match="unreviewed"):
        verifier.verify_pair(before, after, mode="post")


def test_candidate_backend_admits_only_exact_reviewed_python_patch(verifier):
    before = {
        "APP_VERSION": "3.1.46", "APP_REVISION": verifier.PRIOR_REVISION,
        "PYTHON_VERSION": "3.14.8",
        "PYTHON_SHA256": "c2215904f02b175596dc49351585104f4bc20341e1c47378b26a2c274360ce73",
        "PATH": "/usr/local/bin:/usr/bin:/bin", "KEEP": "unchanged",
    }
    original = deepcopy(before)
    candidate = verifier.candidate_backend_environment(before, "b" * 40)
    assert before == original
    assert candidate == {
        **before, "APP_VERSION": "3.1.58", "APP_REVISION": "b" * 40,
        "PYTHON_VERSION": "3.14.10",
        "PYTHON_SHA256": "de414a57119abef52784d8993e341d53aed635fc2b05c2607d9f98a2c5b72032",
        "LANG": "C.UTF-8",
    }
    for key, value in (
        ("PYTHON_VERSION", "3.14.10"), ("PYTHON_SHA256", "0" * 64),
        ("APP_VERSION", "3.1.45"), ("APP_REVISION", "0" * 40),
        ("LANG", "C.UTF-8"),
    ):
        with pytest.raises(verifier.CompatibilityError, match="prior backend"):
            verifier.candidate_backend_environment({**before, key: value}, "b" * 40)
    with pytest.raises(verifier.CompatibilityError):
        verifier.candidate_backend_environment(before, "not-a-revision")


@pytest.mark.parametrize("key", ["PATH", "PYTHON_VERSION", "PYTHON_SHA256", "LANG"])
def test_candidate_compose_cannot_mask_python_image_metadata(verifier, key):
    with pytest.raises(verifier.CompatibilityError, match="masks Python"):
        verifier.candidate_image_environment(["KEEP=1"], {key: "spoofed"}, service="backend")


def frontend_metadata_contract():
    return json.loads(
        (ROOT / "tests/fixtures/build59-nginx-metadata-transition.json").read_bytes()
    )


def candidate_runtime_records(verifier, documents):
    actual = {}
    for old in documents["journal"]["containers"]:
        name = old["Config"]["Labels"]["com.docker.compose.service"]
        row = deepcopy(old)
        row["Image"] = documents["identity"]["candidate_images"][name]
        env = verifier.environment(documents["images"][name]["Config"]["Env"])
        env.update(documents["compose"]["services"][name]["environment"])
        row["Config"]["Env"] = [key + "=" + value for key, value in env.items()]
        actual[name] = row
    return actual


def test_exact_frontend_metadata_transition_accepts_early_and_running(
    verifier, pre_binding_documents
):
    args, docs = pre_binding_documents
    contract = frontend_metadata_contract()
    assert contract["prior_nginx_version"] == "1.31.5"
    assert contract["candidate_nginx_version"] == "1.31.8"
    old = next(
        row
        for row in docs["journal"]["containers"]
        if row["Config"]["Labels"]["com.docker.compose.service"] == "frontend"
    )
    before = verifier.environment(old["Config"]["Env"])
    original = deepcopy(before)
    assert verifier.candidate_service_environment("frontend", before, "b" * 40) == {
        **before,
        "NGINX_VERSION": contract["candidate_nginx_version"],
    }
    assert before == original
    verifier.verify_pre_bindings(args)
    actual = candidate_runtime_records(verifier, docs)
    assert verifier.verify_running_runtime(docs["journal"], actual, docs["identity"])[
        "effective_environment_verified"
    ]


def test_frozen58_rejects_the_independent_real_frontend_transition(
    verifier, pre_binding_documents
):
    _, docs = pre_binding_documents
    source = subprocess.check_output(
        [
            "git",
            "show",
            "6eb61bf097afb7d6e306f3878a64f266adf451a1:infra/scripts/verify-build55-pending-compatibility.py",
        ],
        cwd=ROOT,
    )
    legacy = SimpleNamespace()
    exec(compile(source, "immutable58_runtime_guard", "exec"), legacy.__dict__)
    legacy.PRIOR_ENVIRONMENT_SHA256 = dict(verifier.PRIOR_ENVIRONMENT_SHA256)
    legacy.PRIOR_COMMAND_SHA256 = dict(verifier.PRIOR_COMMAND_SHA256)
    images = deepcopy(docs["images"])
    for name, image in images.items():
        image["Config"]["Labels"]["org.opencontainers.image.version"] = (
            legacy.CANDIDATE_VERSION
        )
        if name == "backend":
            env = verifier.environment(image["Config"]["Env"])
            env["APP_VERSION"] = legacy.CANDIDATE_VERSION
            image["Config"]["Env"] = [key + "=" + value for key, value in env.items()]
    with pytest.raises(
        legacy.CompatibilityError, match="effective runtime environment changed"
    ):
        legacy.verify_candidate_runtime(
            docs["journal"],
            images,
            docs["compose"],
            "b" * 40,
            docs["identity"]["candidate_images"],
        )
    actual = candidate_runtime_records(verifier, docs)
    env = verifier.environment(actual["backend"]["Config"]["Env"])
    env["APP_VERSION"] = legacy.CANDIDATE_VERSION
    actual["backend"]["Config"]["Env"] = [
        key + "=" + value for key, value in env.items()
    ]
    with pytest.raises(
        legacy.CompatibilityError, match="effective runtime environment changed"
    ):
        legacy.verify_running_runtime(docs["journal"], actual, docs["identity"])


@pytest.mark.parametrize("version", [None, "1.31.4", "1.31.8", "1.31.9"])
def test_frontend_transition_requires_exact_prior_metadata(verifier, version):
    before = {"KEEP": "unaltered"}
    if version is not None:
        before["NGINX_VERSION"] = version
    with pytest.raises(verifier.CompatibilityError, match="prior frontend"):
        verifier.candidate_service_environment("frontend", before, "b" * 40)


@pytest.mark.parametrize(
    "change",
    [
        "old_version",
        "future_version",
        "missing_version",
        "extra",
        "delete",
        "business_value",
    ],
)
@pytest.mark.parametrize("phase", ["early", "running"])
def test_frontend_patch_never_admits_unrelated_environment_drift(
    verifier, pre_binding_documents, phase, change
):
    _, docs = pre_binding_documents
    target = docs["images"]["frontend"]["Config"]["Env"]
    env = verifier.environment(target)
    if change == "old_version":
        env["NGINX_VERSION"] = "1.31.5"
    elif change == "future_version":
        env["NGINX_VERSION"] = "1.31.9"
    elif change == "missing_version":
        env.pop("NGINX_VERSION")
    elif change == "extra":
        env["UNREVIEWED"] = "not admitted"
    elif change == "delete":
        env.pop("IMAGE_LITERAL")
    else:
        env["IMAGE_LITERAL"] = "changed business setting"
    docs["images"]["frontend"]["Config"]["Env"] = [
        key + "=" + value for key, value in env.items()
    ]
    with pytest.raises(verifier.CompatibilityError):
        if phase == "early":
            verifier.verify_candidate_runtime(
                docs["journal"],
                docs["images"],
                docs["compose"],
                "b" * 40,
                docs["identity"]["candidate_images"],
            )
        else:
            verifier.verify_running_runtime(
                docs["journal"],
                candidate_runtime_records(verifier, docs),
                docs["identity"],
            )


@pytest.mark.parametrize("mask", ["environment", "env_file"])
def test_frontend_compose_cannot_mask_even_the_correct_metadata(
    verifier, pre_binding_documents, mask
):
    _, docs = pre_binding_documents
    config = docs["compose"]["services"]["frontend"]
    if mask == "environment":
        config["environment"]["NGINX_VERSION"] = "1.31.8"
    else:
        config["env_file"] = ["unreviewed.env"]
    with pytest.raises(verifier.CompatibilityError, match="masks Nginx"):
        verifier.verify_candidate_runtime(
            docs["journal"],
            docs["images"],
            docs["compose"],
            "b" * 40,
            docs["identity"]["candidate_images"],
        )


def test_frontend_image_metadata_rejects_duplicate_keys(verifier):
    with pytest.raises(verifier.CompatibilityError, match="duplicate"):
        verifier.verify_frontend_image_metadata(
            ["NGINX_VERSION=1.31.8", "NGINX_VERSION=1.31.8"]
        )


@pytest.mark.parametrize("service", ["backend", "caddy", "postgres", "redis"])
def test_nginx_patch_cannot_change_another_services_environment(
    verifier, pre_binding_documents, service
):
    _, docs = pre_binding_documents
    docs["images"][service]["Config"]["Env"].append("NGINX_VERSION=1.31.8")
    with pytest.raises(verifier.CompatibilityError, match="environment changed"):
        verifier.verify_candidate_runtime(
            docs["journal"],
            docs["images"],
            docs["compose"],
            "b" * 40,
            docs["identity"]["candidate_images"],
        )


def test_prior_runtime_still_requires_unchanged_frontend_metadata(verifier, runtime):
    actual = {
        row["Config"]["Labels"]["com.docker.compose.service"]: deepcopy(row)
        for row in runtime["containers"]
    }
    env = verifier.environment(actual["frontend"]["Config"]["Env"])
    env["NGINX_VERSION"] = "1.31.8"
    actual["frontend"]["Config"]["Env"] = [
        key + "=" + value for key, value in env.items()
    ]
    with pytest.raises(verifier.CompatibilityError, match="environment changed"):
        verifier.verify_running_runtime(runtime, actual, {}, prior=True)


@pytest.mark.parametrize("version,checksum", [
    ("3.14.8", "c2215904f02b175596dc49351585104f4bc20341e1c47378b26a2c274360ce73"),
    ("3.14.10", "0" * 64),
    ("3.14.11", "de414a57119abef52784d8993e341d53aed635fc2b05c2607d9f98a2c5b72032"),
])
def test_candidate_runtime_rejects_old_or_mismatched_python(verifier, runtime, version, checksum):
    actual = {
        row["Config"]["Labels"]["com.docker.compose.service"]: deepcopy(row)
        for row in runtime["containers"]
    }
    identity = {"candidate_revision": "b" * 40, "candidate_images": {
        name: "sha256:" + str(index) * 64
        for index, name in enumerate(verifier.SERVICES, 1)
    }}
    for name, row in actual.items():
        row["Image"] = identity["candidate_images"][name]
        if name == "backend":
            env = verifier.candidate_backend_environment(verifier.environment(row["Config"]["Env"]), "b" * 40)
            env.update(PYTHON_VERSION=version, PYTHON_SHA256=checksum)
            row["Config"]["Env"] = [key + "=" + value for key, value in env.items()]
    with pytest.raises(verifier.CompatibilityError, match="environment changed"):
        verifier.verify_running_runtime(runtime, actual, identity)



def test_staff_0094_catalogue_is_exactly_the_reviewed_postgresql_delta(verifier):
    # Literal independent extraction from real PostgreSQL16 migration rehearsal.
    assert verifier.CANDIDATE_DATABASE_HEAD == "0094"
    assert len(verifier.EXPECTED_0094_SCHEMA_ADDED_ROWS) == 35
    assert verifier.sha("\n".join(verifier.EXPECTED_0094_SCHEMA_ADDED_ROWS).encode()) == "6a5d31bb634cfbd9a91eca9ec45cb8ba067bb3093f6f403b142c29041db5d863"
    assert len(verifier.EXPECTED_0094_TRIGGER_ADDED_ROWS) == 2
    assert verifier.sha("\n".join(verifier.EXPECTED_0094_TRIGGER_ADDED_ROWS).encode()) == "70df46e74d8961c87e408744632bd7e31f8a3dc2427e5e9c49bbe19ea87f40f4"


@pytest.mark.parametrize("change", ["seeded_policy", "missing_table", "changed_constraint", "missing_truncate_guard"])
def test_staff_0094_drift_refuses_activation(verifier, inventories, change):
    before, after = inventories
    if change == "seeded_policy":
        after["tables"]["staff_work_policies"] = {"row_count": 1, "row_sha256": "a" * 64}
    elif change == "missing_table":
        del after["tables"]["staff_work_policies"]
    elif change == "changed_constraint":
        row = next(row for row in after["schema_catalog_rows"] if row.startswith("constraint|staff_work_policies|ck_staff_work_policy_draft_only|"))
        after["schema_catalog_rows"].remove(row)
        after["schema_catalog_rows"].append(row.replace("draft", "active"))
        _catalogue_hash(verifier, after)
    else:
        row = next(row for row in after["trigger_catalog_rows"] if "staff_work_policy_no_truncate" in row)
        after["trigger_catalog_rows"].remove(row)
        after["trigger_catalog_row_count"] = len(after["trigger_catalog_rows"])
        after["trigger_catalog_sha256"] = verifier.sha("\n".join(after["trigger_catalog_rows"]).encode())
    with pytest.raises(verifier.CompatibilityError):
        verifier.verify_pair(before, after, mode="post")
