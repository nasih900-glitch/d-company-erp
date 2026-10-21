"""Exercise55's full-row, opaque-fleet and actual-runtime preservation decisions."""

from __future__ import annotations

import importlib.util
import json
from copy import deepcopy
from pathlib import Path
from uuid import UUID

import pytest

ROOT = Path(__file__).resolve().parents[1]


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
    )
    schema = [
        "column|audit_log|1|id|bigint",
        "index|audit_log|audit_log_pkey|synthetic",
    ]
    monkeypatch.setattr(
        verifier,
        "PRIOR_TABLE_NAMES_SHA256",
        verifier.sha("\n".join(sorted(names)).encode()),
    )
    monkeypatch.setattr(
        verifier, "PRIOR_SCHEMA_SHA256", verifier.sha("\n".join(schema).encode())
    )
    before = {
        "schema_version": 1,
        "kind": "build55-full-transition-v1",
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
    after = deepcopy(before)
    after["database_revision"] = "0092"
    after["tables"]["alembic_version"]["row_sha256"] = verifier.sha(
        b'{"version_num": "0092"}'
    )
    after["tables"]["android_update_policy"] = {
        "row_count": 0,
        "row_sha256": verifier.EMPTY_SHA256,
    }
    after["schema_catalog_rows"] = sorted(
        schema + list(verifier.EXPECTED_POLICY_SCHEMA_ROWS)
    )
    after["schema_catalog_row_count"] = len(after["schema_catalog_rows"])
    after["schema_catalog_sha256"] = verifier.sha(
        "\n".join(after["schema_catalog_rows"]).encode()
    )
    return before, after


def test_exact_additive_policy_schema_keeps_full_nonempty_legacy_rows(
    verifier, inventories
):
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
def runtime(verifier, monkeypatch):
    records = []
    for service in verifier.SERVICES:
        env = ["PATH=/usr/local/bin:/usr/bin:/bin"]
        if service == "backend":
            env += [
                "APP_VERSION=3.1.46",
                "APP_REVISION=" + verifier.PRIOR_REVISION,
                "ANDROID_MIN_SUPPORTED_VERSION_CODE=54",
                "ANDROID_LATEST_VERSION_CODE=54",
                "CLIENT_COMPATIBILITY_POLICY_REVISION=4",
                "CLIENT_UPDATE_MESSAGE=Build54 $inert",
            ]
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
        monkeypatch.setitem(
            verifier.PRIOR_ENVIRONMENT_SHA256,
            service,
            verifier.sha(json.dumps(sorted(env), separators=(",", ":")).encode()),
        )
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
        if name == "backend":
            env = verifier.environment(row["Config"]["Env"])
            env.update(APP_VERSION="3.1.47", APP_REVISION="b" * 40)
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
    with pytest.raises(verifier.CompatibilityError, match="more than"):
        verifier.verify_pair(before, after, mode="post")
