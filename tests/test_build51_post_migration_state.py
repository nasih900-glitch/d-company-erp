"""Failure-first checks for the additive Build51 historical projection bridge."""

from __future__ import annotations

from copy import deepcopy
import hashlib
import importlib.util
import json
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.fixture
def verifier():
    return load(
        ROOT / "infra/scripts/verify-build51-post-migration-state.py", "build51_bridge"
    )


def valid(verifier):
    _, _, cleanup = verifier._load_verifiers(ROOT)
    fixtures = load(
        ROOT / "tests/test_code30_2_post_cleanup_installer_guard.py",
        "history_fixture51",
    )
    history = fixtures._valid_document(cleanup)
    history["database_revision"] = "0091"
    return {
        "schema_version": 1,
        "bridge": "build51-history-v1",
        "database_revision": "0091",
        "historical_state": history,
        "audit_projection": {
            "version": 1,
            "removed_columns": verifier.REMOVED_COLUMNS,
            "retained_baseline_semantic_count": 0,
            "pre_receipt_semantic_count": 0,
            "cleanup_receipt_semantic_count": 0,
        },
        "guard_inventory": deepcopy(verifier.EXPECTED_GUARD_INVENTORY),
    }


def test_exact_projection_retains_all_original_golden_hashes(verifier):
    document = valid(verifier)
    report = verifier.verify_raw_state(ROOT, json.dumps(document).encode())
    assert report["database_revision"] == "0091"
    assert report["historical_cleanup_proof"]["historical_cleanup_receipt_id"] == 28204
    assert report["queue_drained"] is False
    assert report["fleet"] is None
    assert len(document["guard_inventory"]["triggers"]) == 34
    assert len(document["guard_inventory"]["functions"]) == 11
    assert (
        hashlib.sha256(
            (ROOT / "infra/scripts/verify-code30-2-post-cleanup-state.py").read_bytes()
        ).hexdigest()
        == "082505999c3967f2a2a39b2fcfb333d2ddc4fbf935f5d863cdace9a032a43943"
    )
    assert (
        hashlib.sha256((ROOT / verifier.BUILD50_HELPER).read_bytes()).hexdigest()
        == verifier.BUILD50_HELPER_SHA256
    )
    assert (
        hashlib.sha256(
            (ROOT / "infra/scripts/verify-code30-2-post-cleanup-state.sql").read_bytes()
        ).hexdigest()
        == "6cb51504a99a489b2fc9e4a501f05124a224d6c637a145c60184c100c2272116"
    )


@pytest.mark.parametrize(
    "revision", ["0089", "0090", "0092", None, 91, True, ["0091", "0090"]]
)
def test_wrong_actual_revision_never_falls_back(verifier, revision):
    document = valid(verifier)
    document["database_revision"] = revision
    with pytest.raises(ValueError):
        verifier.verify_raw_state(ROOT, json.dumps(document).encode())


@pytest.mark.parametrize(
    "key",
    [
        "retained_baseline_semantic_count",
        "pre_receipt_semantic_count",
        "cleanup_receipt_semantic_count",
    ],
)
@pytest.mark.parametrize("value", [1, -1, True, False, 0.0, None, "0"])
def test_semantic_null_counts_are_exact_integer_zero(verifier, key, value):
    document = valid(verifier)
    document["audit_projection"][key] = value
    with pytest.raises(ValueError):
        verifier.verify_raw_state(ROOT, json.dumps(document).encode())


@pytest.mark.parametrize("section", ["envelope", "projection", "inventory", "history"])
def test_missing_extra_or_duplicate_keys_are_rejected_at_every_level(verifier, section):
    document = valid(verifier)
    target = {
        "envelope": document,
        "projection": document["audit_projection"],
        "inventory": document["guard_inventory"],
        "history": document["historical_state"],
    }[section]
    target["extra51"] = 1
    with pytest.raises(ValueError):
        verifier.verify_raw_state(ROOT, json.dumps(document).encode())
    del target["extra51"]
    first = next(iter(target))
    value = target.pop(first)
    with pytest.raises(ValueError):
        verifier.verify_raw_state(ROOT, json.dumps(document).encode())
    target[first] = value
    raw = json.dumps(document, separators=(",", ":"))
    # Duplicate exact JSON object key, even when both values agree.
    fragment = json.dumps(first) + ":" + json.dumps(value, separators=(",", ":"))
    assert fragment in raw
    with pytest.raises(ValueError):
        verifier.verify_raw_state(
            ROOT, raw.replace(fragment, fragment + "," + fragment, 1).encode()
        )


@pytest.mark.parametrize("kind,count", [("triggers", 34), ("functions", 11)])
def test_every_guard_identity_is_required_once(verifier, kind, count):
    for index in range(count):
        document = valid(verifier)
        document["guard_inventory"][kind].pop(index)
        with pytest.raises(ValueError):
            verifier.verify_envelope(document)
    document = valid(verifier)
    document["guard_inventory"][kind].append(
        deepcopy(document["guard_inventory"][kind][0])
    )
    with pytest.raises(ValueError):
        verifier.verify_envelope(document)


@pytest.mark.parametrize("kind", ["functions", "triggers"])
def test_every_guard_attribute_and_exact_definition_is_bound(verifier, kind):
    for index, row in enumerate(verifier.EXPECTED_GUARD_INVENTORY[kind]):
        for field in row:
            document = valid(verifier)
            document["guard_inventory"][kind][index][field] = "changed"
            with pytest.raises(ValueError):
                verifier.verify_envelope(document)


@pytest.mark.parametrize(
    "mutation", ["baseline", "suffix", "receipt", "created", "pending"]
)
def test_original_historical_evidence_and_opaque_counters_remain_strict(
    verifier, mutation
):
    document = valid(verifier)
    history = document["historical_state"]
    if mutation == "baseline":
        history["audit_integrity"]["retained_baseline_sha256"] = "0" * 64
    elif mutation == "suffix":
        history["audit_integrity"]["pre_receipt_login_success_sha256"] = "0" * 64
    elif mutation == "receipt":
        history["cleanup_receipts"][0]["after"]["backup_sha256"] = "0" * 64
    elif mutation == "created":
        history["cleanup_receipts"][0]["created_at"] = "2026-09-20T14:34:48Z"
    else:
        history["pending_outbox_count"] = 2
        history["nonzero_installation_count"] = 2
    with pytest.raises(ValueError):
        verifier.verify_raw_state(ROOT, json.dumps(document).encode())


def test_pre0089_uses_immutable_old_contract_without_fleet_exception(verifier):
    history = valid(verifier)["historical_state"]
    history["database_revision"] = "0089"
    assert (
        verifier.verify_pre_migration_0089(ROOT, json.dumps(history).encode())[
            "historical_cleanup_receipt_id"
        ]
        == 28204
    )
    for head in ["0090", "0091", "0088"]:
        history["database_revision"] = head
        with pytest.raises(ValueError):
            verifier.verify_pre_migration_0089(ROOT, json.dumps(history).encode())


def test_nonfinite_and_oversized_documents_fail_closed(verifier):
    for raw in [
        b'{"schema_version":NaN}',
        b'{"schema_version":Infinity}',
        b" " * (verifier.MAX_DOCUMENT_BYTES + 1),
    ]:
        with pytest.raises(ValueError):
            verifier.verify_raw_state(ROOT, raw)


def test_installer_routes_actual_schema_before_public_assets_and_preserves_old_helpers():
    source = (ROOT / "infra/scripts/install-on-vm.sh").read_text()
    routing = source.index("# Route by the actual migrated schema")
    assert (
        source.index('--command "SELECT version_num FROM alembic_version"', routing)
        < source.index('< "$BUILD51_POST_MIGRATION_SQL"', routing)
        < source.index("# Read public files through nginx", routing)
    )
    assert '0091) historical_state_sql="$BUILD51_POST_MIGRATION_SQL"' in source
    assert (
        "Unknown post-migration schema cannot select a historical proof reader."
        in source
    )
    assert '"$BUILD51_POST_MIGRATION_VERIFIER" "$BUILD51_POST_MIGRATION_SQL"' in source
    assert "BUILD51_PENDING_CARRIED" not in source


def fresh_document(verifier):
    empty = hashlib.sha256(b"").hexdigest()
    return {
        "schema_version": 1,
        "bridge": "build51-fresh-history-v1",
        "database_revision": "0091",
        "database_identity": {
            "system_identifier": "1234567890",
            "database_oid": "18000",
            "database_name": "erp",
        },
        "origin_prefix": {"cutoff": 5, "row_count": 3, "row_sha256": "1" * 64},
        "preserved_prefix": {"cutoff": 5, "row_count": 3, "row_sha256": "1" * 64},
        "audit_max_id": 5,
        "business_history_row_count": 0,
        "pending_outbox_count": 0,
        "nonzero_installation_count": 0,
        "finance_history": {
            "payment_correction_count": 0,
            "payment_correction_sha256": empty,
            "daily_reconciliation_count": 0,
            "daily_reconciliation_sha256": empty,
        },
        "guard_inventory": deepcopy(verifier.EXPECTED_GUARD_INVENTORY),
    }


def fresh_origin(verifier, document):
    return verifier.make_origin(
        document,
        project="erp-test",
        source_git_sha="a" * 40,
        source_archive_sha256="b" * 64,
        admission_confirmed=True,
    )


def test_proven_fresh_birth_then_retry_and_oid_recreated_rollback(verifier):
    state = fresh_document(verifier)
    origin = fresh_origin(verifier, state)
    state["preserved_prefix"] = {"cutoff": 8, "row_count": 6, "row_sha256": "2" * 64}
    state["audit_max_id"] = 8
    state["business_history_row_count"] = 4
    state["finance_history"]["payment_correction_count"] = 1
    state["finance_history"]["payment_correction_sha256"] = "3" * 64
    verifier.verify_fresh_preservation(state, origin, project="erp-test")
    after = deepcopy(state)
    after["audit_max_id"] = 9
    after["database_identity"]["database_oid"] = "19001"
    assert (
        verifier.verify_fresh_preservation(
            after, origin, project="erp-test", baseline=state
        )
        == after
    )
    # Fresh semantic bootstrap IDs may be below28202. This is a separate
    # full-row proof, never a projection or a missing-receipt legacy fallback.
    with pytest.raises(ValueError):
        verifier.verify_envelope(after)


@pytest.mark.parametrize(
    "change",
    [
        "system",
        "database",
        "origin",
        "prefix",
        "finance_count",
        "finance_hash",
        "pending",
        "guard",
        "business",
    ],
)
def test_fresh_retry_refuses_changed_identity_history_or_pending(verifier, change):
    state = fresh_document(verifier)
    origin = fresh_origin(verifier, state)
    after = deepcopy(state)
    if change == "system":
        after["database_identity"]["system_identifier"] = "987654321"
    elif change == "database":
        after["database_identity"]["database_name"] = "other"
    elif change == "origin":
        after["origin_prefix"]["row_sha256"] = "4" * 64
    elif change == "prefix":
        after["preserved_prefix"]["row_sha256"] = "4" * 64
    elif change == "finance_count":
        after["finance_history"]["daily_reconciliation_count"] = 1
    elif change == "finance_hash":
        after["finance_history"]["payment_correction_sha256"] = "4" * 64
    elif change == "pending":
        after["pending_outbox_count"] = 1
        after["nonzero_installation_count"] = 1
    elif change == "guard":
        after["guard_inventory"]["triggers"].pop()
    else:
        after["business_history_row_count"] = 1
    with pytest.raises(ValueError):
        verifier.verify_fresh_preservation(
            after, origin, project="erp-test", baseline=state
        )


@pytest.mark.parametrize(
    "change",
    ["admission", "business", "empty_audit", "partial_prefix", "old_head", "finance"],
)
def test_birth_receipt_cannot_reclassify_a_used_or_unproven_installation(
    verifier, change
):
    state = fresh_document(verifier)
    admission = True
    if change == "admission":
        admission = False
    elif change == "business":
        state["business_history_row_count"] = 1
    elif change == "empty_audit":
        state["origin_prefix"] = {
            "cutoff": 0,
            "row_count": 0,
            "row_sha256": hashlib.sha256(b"").hexdigest(),
        }
        state["preserved_prefix"] = deepcopy(state["origin_prefix"])
        state["audit_max_id"] = 0
    elif change == "partial_prefix":
        state["audit_max_id"] = 6
    elif change == "old_head":
        state["database_revision"] = "0089"
    else:
        state["finance_history"]["payment_correction_count"] = 1
    with pytest.raises(ValueError):
        verifier.make_origin(
            state,
            project="erp-test",
            source_git_sha="a" * 40,
            source_archive_sha256="b" * 64,
            admission_confirmed=admission,
        )


def test_private_origin_is_exclusive_fsynced_unlinked_and_never_auto_reclassified(
    verifier, tmp_path
):
    import os

    parent = tmp_path.resolve()
    parent.chmod(0o700)
    path = parent / "fresh-origin.json"
    origin = fresh_origin(verifier, fresh_document(verifier))
    options = {"expected_uid": os.geteuid(), "expected_gid": os.getegid()}
    verifier.write_origin(path, origin, **options)
    assert verifier.read_origin(path, **options) == origin
    with pytest.raises(FileExistsError):
        verifier.write_origin(path, origin, **options)
    assert verifier.read_origin(path, **options) == origin
    link = parent / "linked.json"
    link.symlink_to(path)
    with pytest.raises(ValueError):
        verifier.read_origin(link, **options)
    link.unlink()
    os.link(path, link)
    with pytest.raises(ValueError):
        verifier.read_origin(path, **options)
    link.unlink()
    path.chmod(0o644)
    with pytest.raises(ValueError):
        verifier.read_origin(path, **options)
    path.chmod(0o600)
    path.write_text('{"partial":')
    with pytest.raises(ValueError):
        verifier.read_origin(path, **options)
    path.unlink()
    with pytest.raises(FileNotFoundError):
        verifier.read_origin(path, **options)


def test_installer_fresh_admission_backup_and_creation_are_protected_before_ingress():
    source = (ROOT / "infra/scripts/install-on-vm.sh").read_text()
    admission = source.index("  BUILD51_FRESH_ADMISSION=true")
    assert (
        source.index("Persistent deployment evidence exists but .env is missing.")
        < admission
    )
    create = source.index("--create-fresh-origin --fresh-admission-confirmed")
    assert (
        source.index("Fresh owner authentication and protected access accepted.")
        < create
        < source.index("Backend acceptance passed; reopening public ingress")
    )
    assert "sha256sum fresh-lineage-origin.json" in source
    assert (
        'cmp -s "$BUILD51_FRESH_ORIGIN" "$UPGRADE_SNAPSHOT/fresh-lineage-origin.json"'
        in source
    )
    assert '--baseline-fresh-state "$BUILD51_FRESH_BASELINE"' in source
    assert "no new50-to51 opaque pending-work exception" in source


def test_current_preservation_never_grants_legacy_pending_admission(verifier):
    before = fresh_document(verifier)
    before["pending_outbox_count"] = 1
    before["nonzero_installation_count"] = 1
    verifier.verify_current_preservation(before)
    after = deepcopy(before)
    after["audit_max_id"] = 6
    assert verifier.verify_current_preservation(after, before) == after
    with pytest.raises(ValueError, match="pending"):
        verifier.verify_fresh_state(before)
    after["finance_history"]["daily_reconciliation_sha256"] = "6" * 64
    with pytest.raises(ValueError, match="finance_history"):
        verifier.verify_current_preservation(after, before)


def test_origin_fifo_cannot_block_the_verifier_and_unsafe_paths_refuse(
    verifier, tmp_path
):
    import os
    import subprocess
    import sys

    parent = tmp_path.resolve()
    parent.chmod(0o700)
    fifo = parent / "origin-fifo"
    os.mkfifo(fifo, 0o600)
    code = """import importlib.util,os,sys
spec=importlib.util.spec_from_file_location("bridge",sys.argv[1]);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
module.read_origin(sys.argv[2],expected_uid=os.geteuid(),expected_gid=os.getegid())"""
    result = subprocess.run(
        [
            sys.executable,
            "-c",
            code,
            str(ROOT / "infra/scripts/verify-build51-post-migration-state.py"),
            str(fifo),
        ],
        capture_output=True,
        text=True,
        timeout=2,
        check=False,
    )
    assert result.returncode != 0
    assert "unsafe ownership, mode, links or size" in result.stderr
    folder = parent / "directory-origin"
    folder.mkdir(mode=0o700)
    options = {"expected_uid": os.geteuid(), "expected_gid": os.getegid()}
    with pytest.raises(ValueError):
        verifier.read_origin(folder, **options)
    path = parent / "regular.json"
    verifier.write_origin(
        path, fresh_origin(verifier, fresh_document(verifier)), **options
    )
    with pytest.raises(ValueError):
        verifier.read_origin(
            path, expected_uid=os.geteuid() + 1, expected_gid=os.getegid()
        )
    with pytest.raises(ValueError):
        verifier.read_origin(
            path, expected_uid=os.geteuid(), expected_gid=os.getegid() + 1
        )
    parent.chmod(0o755)
    with pytest.raises(ValueError):
        verifier.read_origin(path, **options)
    parent.chmod(0o700)


@pytest.mark.parametrize(
    "extra", [["--baseline-fresh-state", "absent.json"], ["--show-preserve-cutoff"]]
)
def test_creation_refuses_conflicting_baseline_or_cutoff_options(
    verifier, tmp_path, monkeypatch, capsys, extra
):
    import sys

    monkeypatch.setattr(
        sys,
        "argv",
        [
            "verify51",
            "--fresh-origin",
            str(tmp_path / "origin.json"),
            "--create-fresh-origin",
            "--project",
            "erp-test",
            *extra,
        ],
    )
    assert verifier.main() == 1
    assert "origin creation cannot select baseline" in capsys.readouterr().err
    assert not (tmp_path / "origin.json").exists()
