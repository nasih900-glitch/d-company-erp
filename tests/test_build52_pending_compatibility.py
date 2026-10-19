"""Failure-first checks for the exact Build50-to-Build52 transition adapter."""

from __future__ import annotations

from copy import deepcopy
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
from types import SimpleNamespace

import pytest


ROOT = Path(__file__).resolve().parents[1]
HELPER = ROOT / "infra/scripts/verify-build52-pending-compatibility.py"
INSTALLER = ROOT / "infra/scripts/install-on-vm.sh"
TRANSITION_SQL = ROOT / "infra/scripts/verify-build52-transition-state.sql"
RUNTIME_IMAGE_VERIFIER = ROOT / "infra/scripts/verify-production-runtime-images.sh"


def load(path: Path, name: str):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.fixture
def verifier():
    return load(HELPER, "build52_pending_compatibility")


def transition(verifier, *, head: str, oid: str = "16384") -> dict:
    finance = {
        name: (
            {"state": "absent", "row_count": None, "row_sha256": None}
            if head == "0089"
            else {
                "state": "present",
                "row_count": 0,
                "row_sha256": verifier.EMPTY_SHA256,
            }
        )
        for name in verifier.FINANCE_TABLES
    }
    return {
        "schema_version": 1,
        "kind": "build52-quiesced-transition-v1",
        "database_revision": head,
        "database_identity": {
            "system_identifier": "123456789",
            "database_name": "erp",
            "database_oid": oid,
        },
        "table_inventory": [
            {"table_name": name, "row_count": 1, "row_sha256": "a" * 64}
            for name in sorted(verifier.REQUIRED_PRESERVED_TABLES)
        ],
        "sequence_inventory": [
            {
                "sequence_name": "audit_log_id_seq",
                "start_value": 1,
                "min_value": 1,
                "max_value": 9223372036854775807,
                "increment_by": 1,
                "cache_size": 1,
                "cycle": False,
                "owned_by": "audit_log.id",
                "last_value": 41,
                "is_called": True,
            }
        ],
        "reserved_schema": {
            "audit_semantic_columns": (
                [] if head == "0089" else deepcopy(verifier.AUDIT_SEMANTIC_COLUMNS)
            ),
            "collection_source_guards": (
                {"state": "absent", "columns": []}
                if head == "0089"
                else {
                    "state": "present",
                    "columns": deepcopy(verifier.COLLECTION_SOURCE_GUARD_COLUMNS),
                }
            ),
        },
        "finance_history": finance,
    }


def test_release_identity_is_exact_and_independently_frozen(verifier):
    assert verifier.PRIOR_REVISION == "29e32b440ab8dc162b6c8f5d40f8283fad53ce04"
    assert verifier.SAME_TREE_TAG_REVISION == "3093f62971d16e5d80408a436959e7cc5ccbf318"
    assert verifier.PRIOR_TREE == "296f9745c6c1060d0d3ecc7b334f811dffad1964"
    assert (verifier.PRIOR_VERSION, verifier.CANDIDATE_VERSION) == (
        "3.1.42",
        "3.1.44",
    )
    assert (verifier.PRIOR_DATABASE_HEAD, verifier.CANDIDATE_DATABASE_HEAD) == (
        "0089",
        "0091",
    )
    assert verifier.CANDIDATE_RELEASE_FROZEN is True


def test_unfrozen_candidate_is_rejected_before_any_identity_or_source_access(
    verifier, monkeypatch
):
    monkeypatch.setattr(verifier, "CANDIDATE_RELEASE_FROZEN", False)
    with pytest.raises(
        verifier.CompatibilityError, match="has not passed independent freeze"
    ):
        verifier._verify_identity(SimpleNamespace(), SimpleNamespace())


@pytest.mark.parametrize(
    "field,value",
    (
        ("prior_revision", "3093f62971d16e5d80408a436959e7cc5ccbf318"),
        ("prior_version", "3.1.41"),
        ("prior_db_head", "0091"),
        ("candidate_version", "3.1.43"),
        ("candidate_db_head", "0089"),
        ("prior_source_archive_sha256", "0" * 64),
        ("candidate_backend_manifest_sha256", "0" * 64),
        ("prior_image_id", "sha256:" + "0" * 64),
        ("candidate_image_id", "latest"),
        ("candidate_revision", "HEAD"),
    ),
)
def test_identity_gate_accepts_exact_bound_inputs_and_refuses_substitution(
    verifier, field, value
):
    prior = Path("prior")
    candidate = Path("candidate")
    args = SimpleNamespace(
        prior_revision=verifier.PRIOR_REVISION,
        prior_version=verifier.PRIOR_VERSION,
        prior_db_head=verifier.PRIOR_DATABASE_HEAD,
        candidate_version=verifier.CANDIDATE_VERSION,
        candidate_db_head=verifier.CANDIDATE_DATABASE_HEAD,
        prior_source_archive_sha256=verifier.PRIOR_SOURCE_ARCHIVE_SHA256,
        prior_backend_manifest_sha256=verifier.PRIOR_BACKEND_MANIFEST_SHA256,
        candidate_backend_manifest_sha256=verifier.CANDIDATE_BACKEND_MANIFEST_SHA256,
        prior_image_id=verifier.PRIOR_BACKEND_IMAGE_ID,
        candidate_image_id="sha256:" + "b" * 64,
        candidate_revision="b" * 40,
        candidate_source_archive_sha256="b" * 64,
        prior_source_root=prior,
        candidate_source_root=candidate,
    )
    manifests = {
        prior: verifier.PRIOR_BACKEND_MANIFEST_SHA256,
        candidate: verifier.CANDIDATE_BACKEND_MANIFEST_SHA256,
    }
    build50 = SimpleNamespace(_backend_manifest_sha256=manifests.__getitem__)
    verifier._verify_identity(args, build50)
    setattr(args, field, value)
    with pytest.raises(verifier.CompatibilityError):
        verifier._verify_identity(args, build50)


def test_exact_13_row_fleet_pins_do_not_reclassify_unknown_work(verifier):
    assert (
        verifier.EXPECTED_INSTALLATION_ROWS,
        verifier.EXPECTED_PENDING,
        verifier.EXPECTED_NONZERO,
    ) == (13, 2, 2)
    assert verifier.EXPECTED_ALL_INSTALLATIONS_IDENTITY_SHA256 == (
        "ea5bfed358dc2a4a16a3876bb8a45daa75c40833498680e62643b44c90114664"
    )
    assert verifier.EXPECTED_NONZERO_FLEET_SHA256 == (
        "5a1100a5695e997be523eb7a948d06db0fd79fed1a4f3a5f48c3286c86ea5128"
    )
    source = HELPER.read_text(encoding="utf-8")
    for literal in (
        '"queue_drained": False',
        '"tablet_queue_contents_verified": False',
        '"telemetry_rewritten": False',
        '"installation_retired": False',
        '"unknown_preserved_work"',
    ):
        assert literal in source
    assert "allow-pending" not in source


def test_immutable_helpers_are_loaded_by_exact_hash(verifier):
    build50, post50, build51 = verifier._load_modules(ROOT)
    assert build50.PRIOR_VERSION == "3.1.41"
    assert post50.EXPECTED_DATABASE_HEAD == "0089"
    assert build51.EXPECTED_DATABASE_HEAD == "0091"
    for path, digest in (
        (verifier.BUILD50_PENDING_HELPER, verifier.BUILD50_PENDING_HELPER_SHA256),
        (verifier.BUILD50_POST_HELPER, verifier.BUILD50_POST_HELPER_SHA256),
        (verifier.BUILD51_POST_HELPER, verifier.BUILD51_POST_HELPER_SHA256),
    ):
        import hashlib

        assert hashlib.sha256((ROOT / path).read_bytes()).hexdigest() == digest


def test_full_protected_source_inventory_matches_reviewed_worktree(verifier, tmp_path):
    prior = tmp_path / "prior"
    candidate = tmp_path / "candidate"
    prior.mkdir()
    candidate.mkdir()
    for revision, destination in (
        (verifier.PRIOR_REVISION, prior),
        ("HEAD", candidate),
    ):
        archive = destination / "source.tar"
        with archive.open("wb") as output:
            result = subprocess.run(
                ["git", "archive", "--format=tar", revision],
                cwd=ROOT,
                stdout=output,
                check=False,
            )
        assert result.returncode == 0
        subprocess.run(["tar", "-xf", archive, "-C", destination], check=True)
        archive.unlink()
    changed = subprocess.run(
        ["git", "diff", "--name-only", "HEAD", "--"],
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=True,
    ).stdout.splitlines()
    changed.extend(
        (
            "infra/scripts/verify-build52-transition-state.sql",
            "backend/tests/integration/test_build52_transition_state_query.py",
        )
    )
    for relative in sorted(set(changed)):
        source = ROOT / relative
        if not source.is_file():
            continue
        target = candidate / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
    build50, _, _ = verifier._load_modules(ROOT)
    proof = verifier._verify_sources(build50, prior, candidate)
    assert proof["prior_protected_path_count"] == 1217
    assert proof["candidate_protected_path_count"] == 1285
    assert proof["reviewed_changed_path_count"] == 182
    assert proof["same_tree_tag_is_identity"] is False


def test_transition_preserves_full_existing_inventory_and_empty_new_finance(verifier):
    pre = transition(verifier, head="0089")
    post = transition(verifier, head="0091")
    result = verifier._verify_transition_pair(pre, post, rollback=False)
    assert result == {
        "preserved_table_count": 5,
        "database_lineage_preserved": True,
        "logical_restore_verified": False,
    }
    restored = transition(verifier, head="0089", oid="16385")
    result = verifier._verify_transition_pair(pre, restored, rollback=True)
    assert result["logical_restore_verified"] is True


@pytest.mark.parametrize(
    "mutation",
    [
        "wrong_pre_head",
        "wrong_post_head",
        "database",
        "system",
        "oid",
        "audit",
        "fleet",
        "business",
        "finance_row",
        "finance_hash",
        "sequence",
        "guard",
        "audit_schema",
        "missing_table",
    ],
)
def test_transition_mutations_fail_before_ingress(verifier, mutation):
    pre = transition(verifier, head="0089")
    post = transition(verifier, head="0091")
    if mutation == "wrong_pre_head":
        pre["database_revision"] = "0090"
    elif mutation == "wrong_post_head":
        post["database_revision"] = "0092"
    elif mutation == "database":
        post["database_identity"]["database_name"] = "other"
    elif mutation == "system":
        post["database_identity"]["system_identifier"] = "987654321"
    elif mutation == "oid":
        post["database_identity"]["database_oid"] = "16385"
    elif mutation in {"audit", "fleet", "business"}:
        selected = {
            "audit": "audit_log",
            "fleet": "client_installations",
            "business": "orders",
        }[mutation]
        next(row for row in post["table_inventory"] if row["table_name"] == selected)[
            "row_sha256"
        ] = "b" * 64
    elif mutation == "finance_row":
        post["finance_history"]["payment_tender_corrections"]["row_count"] = 1
    elif mutation == "finance_hash":
        post["finance_history"]["daily_tender_reconciliations"]["row_sha256"] = "b" * 64
    elif mutation == "sequence":
        post["sequence_inventory"][0]["last_value"] += 1
    elif mutation == "guard":
        post["reserved_schema"]["collection_source_guards"] = {
            "state": "absent",
            "columns": [],
        }
    elif mutation == "audit_schema":
        post["reserved_schema"]["audit_semantic_columns"].pop()
    else:
        post["table_inventory"].pop()
    with pytest.raises(ValueError):
        verifier._verify_transition_pair(pre, post, rollback=False)


def test_transition_json_rejects_duplicate_nonfinite_and_unbounded_input(verifier):
    valid = json.dumps(transition(verifier, head="0089"), separators=(",", ":"))
    duplicate = valid.replace(
        '"schema_version":1', '"schema_version":1,"schema_version":1'
    )
    for raw in (
        duplicate.encode(),
        b'{"schema_version":NaN}',
        b" " * (verifier.MAX_DOCUMENT_BYTES + 1),
    ):
        with pytest.raises(ValueError):
            verifier._decode_transition(raw)


def test_installer_has_one_exact_pre_post_rollback_route_before_ingress():
    source = INSTALLER.read_text(encoding="utf-8")
    assert source.count('python3 "$BUILD52_PENDING_COMPATIBILITY_VERIFIER" pre') == 1
    assert source.count('python3 "$BUILD52_PENDING_COMPATIBILITY_VERIFIER" post') == 1
    assert (
        source.count('python3 "$BUILD52_PENDING_COMPATIBILITY_VERIFIER" rollback') == 1
    )
    pre = source.index('python3 "$BUILD52_PENDING_COMPATIBILITY_VERIFIER" pre')
    backup = source.index("==> Creating final quiesced pre-upgrade PostgreSQL backup")
    post = source.index('python3 "$BUILD52_PENDING_COMPATIBILITY_VERIFIER" post')
    migration = source.index("--entrypoint python backend -m alembic upgrade head")
    normal_writers = source.index(
        "up -d --no-build --pull never postgres redis backend frontend"
    )
    public_assets = source.index('python3 "$WEB_PUBLIC_ASSET_VERIFIER"', post)
    caddy = source.index("up -d --no-build --pull never caddy", post)
    assert pre < backup < migration < post < normal_writers < public_assets < caddy
    assert source.count("BUILD52_PENDING_CARRIED_UNRESOLVED=true") == 1
    assert source.count("BUILD52_POST_PROOF_SUCCEEDED=true") == 1
    assert source.index("run --pull never --rm --no-deps", migration - 150) < migration
    assert 'image: "$CANDIDATE_BACKEND_IMAGE_ID"' in source
    assert 'expected["services"]["backend"]["image"] = sys.argv[3]' in source
    assert "migration override changed more than the backend image" in source
    assert "Attested Build52 backend image is missing before migration." in source
    assert (
        "BUILD52 normal writers require the in-process quiesced post proof." in source
    )
    assert '[ "$PRIOR_DB_HEAD" = 0089 ]' in source
    assert '[ "$PRIOR_REVISION" = 29e32b440ab8dc162b6c8f5d40f8283fad53ce04 ]' in source


def test_migration_runtime_probe_belongs_to_the_exact_build52_branch():
    source = INSTALLER.read_text(encoding="utf-8")
    old_start = '    elif [ "$CANDIDATE_APP_VERSION" = 3.1.36 ]'
    new_start = '    elif [ "$CANDIDATE_APP_VERSION" = 3.1.44 ]'
    next_start = '    elif [ "$CANDIDATE_APP_VERSION" = 3.1.42 ]'
    historical = subprocess.run(
        [
            "git",
            "show",
            "a44397d53d94fb4c83b3fc9ffe696dbf4dce3168:infra/scripts/install-on-vm.sh",
        ],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    # A placement error here previously made Build52 always roll back while
    # inadvertently adding its runtime probe to the immutable Build44 path.
    assert (
        source[source.index(old_start) : source.index(new_start)]
        == historical[historical.index(old_start) : historical.index(next_start)]
    )
    branch = source[source.index(new_start) : source.index(next_start)]
    probe = branch.index("--entrypoint python")
    verified = branch.index("BUILD52_MIGRATION_RUNTIME_VERIFIED=true")
    admission = branch.index('python3 "$BUILD52_PENDING_COMPATIBILITY_VERIFIER" pre')
    accepted = branch.index("BUILD52_PENDING_CARRIED_UNRESOLVED=true")
    assert probe < verified < admission < accepted
    assert source.count("BUILD52_MIGRATION_RUNTIME_VERIFIED=true") == 1
    assert (
        source[: source.index(new_start)].count(
            "BUILD52_MIGRATION_RUNTIME_VERIFIED=true"
        )
        == 0
    )


def test_transition_query_is_read_only_and_projects_only_additive_audit_columns():
    sql = TRANSITION_SQL.read_text(encoding="utf-8")
    assert "BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY" in sql
    assert "ROLLBACK;" in sql
    assert "\\gexec" in sql
    assert "CREATE TEMP" not in sql
    assert "DO $" not in sql
    assert "UPDATE " not in sql
    assert "DELETE FROM" not in sql
    assert "INSERT INTO" not in sql
    assert "FROM %I.%I" in sql
    assert "sequence_inventory" in sql
    assert "collection_source_guards" in sql
    for column in (
        "action_code",
        "category",
        "stage",
        "outcome",
        "operation_id",
        "source_reference",
        "actor_kind",
        "target_user_id",
        "business_details",
        "catalogue_version",
    ):
        assert column in sql


def test_runtime_image_gate_exercises_build52_one_off_migration_before_writers():
    source = RUNTIME_IMAGE_VERIFIER.read_text(encoding="utf-8")
    component = source.index('if [ "$APP_VERSION" = 3.1.44 ]; then')
    predecessor = source.index(
        "--entrypoint python backend -m alembic upgrade 0089", component
    )
    prior_verified = source.index(
        'if [ "$PRIOR_MIGRATION_HEAD" != 0089 ]; then', predecessor
    )
    migration = source.index(
        "--entrypoint python backend -m alembic upgrade head", component
    )
    normal_start = source.index(
        "up -d --no-build --wait --wait-timeout 180 \\\n  postgres redis backend frontend",
        component,
    )
    candidate_verified = source.index(
        'if [ "$MIGRATED_DATABASE_HEAD" != 0091 ]; then', migration
    )
    assert (
        component
        < predecessor
        < prior_verified
        < migration
        < candidate_verified
        < normal_start
    )
    assert '"${migration_compose[@]}" run --pull never --rm --no-deps' in source
    assert 'image: "$CANDIDATE_BACKEND_IMAGE_ID"' in source
    assert 'expected["services"]["backend"]["image"] = sys.argv[3]' in source
    assert source.count("assert_no_normal_application_runtime") == 5
    assert "SELECT version_num FROM alembic_version" in source
    assert 'if [ "$MIGRATED_DATABASE_HEAD" != 0091 ]; then' in source
    assert 'rm -f "$CANDIDATE_ENV" "$ROUTED_READY" "$DIAGNOSTIC_LOG" \\' in source
