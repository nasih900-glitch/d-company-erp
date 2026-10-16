from __future__ import annotations

from copy import deepcopy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tarfile

import pytest


ROOT = Path(__file__).resolve().parents[1]
HELPER = ROOT / "infra/scripts/verify-build48-pending-compatibility.py"
HISTORICAL_HELPER = ROOT / "infra/scripts/verify-build47-pending-compatibility.py"
INSTALLER = ROOT / "infra/scripts/install-on-vm.sh"
BUILD48_REVISION = "4058fb29e70789a9bb3fa62b2d580330b25db8a3"


def _load(path: Path, name: str):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.fixture
def helper():
    return _load(HELPER, "build48_pending_compatibility")


def _extract(ref: str, destination: Path) -> bytes:
    raw = subprocess.check_output(["git", "archive", "--format=tar", ref], cwd=ROOT)
    with tarfile.open(fileobj=io.BytesIO(raw), mode="r:") as archive:
        for member in archive.getmembers():
            path = Path(member.name)
            assert not path.is_absolute() and ".." not in path.parts
        archive.extractall(destination, filter="data")
    return raw


def _candidate_snapshot(destination: Path) -> None:
    # Build 48 is immutable history; build 49's dirty workspace must not be
    # allowed to redefine the already-reviewed build47-to-build48 proof.
    _extract(BUILD48_REVISION, destination)


def test_historical_verifier_is_immutable_and_build48_source_delta_is_exact(
    helper, tmp_path
):
    assert (
        hashlib.sha256(HISTORICAL_HELPER.read_bytes()).hexdigest()
        == helper.HISTORICAL_HELPER_SHA256
    )
    prior = tmp_path / "prior"
    candidate = tmp_path / "candidate"
    raw = _extract(helper.PRIOR_REVISION, prior)
    _candidate_snapshot(candidate)

    proof = helper._verify_sources(prior, candidate)

    assert hashlib.sha256(raw).hexdigest() == helper.PRIOR_SOURCE_ARCHIVE_SHA256
    assert proof["reviewed_changed_path_count"] == len(helper.REVIEWED_CHANGES)
    assert proof["protected_path_count"] > 500
    assert not any(
        marker in path
        for path in helper.REVIEWED_CHANGES
        for marker in ("__pycache__", ".pytest_cache", ".coverage")
    )
    assert (
        helper._backend_manifest_sha256(prior)
        == helper.PRIOR_BACKEND_MANIFEST_SHA256
    )
    assert (
        helper._backend_manifest_sha256(candidate)
        == helper.CANDIDATE_BACKEND_MANIFEST_SHA256
    )


@pytest.mark.parametrize("kind", ["directory_link", "dangling_link", "ancestor_link"])
def test_inventory_rejects_every_link_under_a_protected_scope(helper, tmp_path, kind):
    root = tmp_path / "root"
    scope = root / "scope"
    if kind == "ancestor_link":
        outside = tmp_path / "outside"
        (outside / "scope").mkdir(parents=True)
        (outside / "scope/regular").write_text("bound")
        (root / "linked").parent.mkdir(parents=True)
        (root / "linked").symlink_to(outside, target_is_directory=True)
        with pytest.raises(helper.CompatibilityError, match="linked"):
            helper._inventory(root, ("linked/scope",))
        return
    scope.mkdir(parents=True)
    (scope / "regular").write_text("bound")
    link = scope / "unexpected"
    if kind == "directory_link":
        target = tmp_path / "outside"
        target.mkdir()
        link.symlink_to(target, target_is_directory=True)
    else:
        link.symlink_to(tmp_path / "missing")
    with pytest.raises(helper.CompatibilityError, match="linked|non-regular"):
        helper._inventory(root, ("scope",))


def _identity(helper):
    return {
        "prior_revision": helper.PRIOR_REVISION,
        "prior_version": helper.PRIOR_VERSION,
        "prior_db_head": helper.PRIOR_DATABASE_HEAD,
        "candidate_revision": "a" * 40,
        "candidate_version": helper.CANDIDATE_VERSION,
        "candidate_db_head": helper.CANDIDATE_DATABASE_HEAD,
        "prior_image_id": "sha256:" + "b" * 64,
        "candidate_image_id": "sha256:" + "c" * 64,
        "prior_backend_manifest_sha256": helper.PRIOR_BACKEND_MANIFEST_SHA256,
        "candidate_backend_manifest_sha256": helper.CANDIDATE_BACKEND_MANIFEST_SHA256,
        "prior_source_archive_sha256": helper.PRIOR_SOURCE_ARCHIVE_SHA256,
        "candidate_source_archive_sha256": "d" * 64,
    }


def test_only_exact_release_schema_and_immutable_artifacts_are_accepted(helper):
    helper._validate_identity(**_identity(helper))
    for field, value in (
        ("prior_revision", "0" * 40),
        ("prior_version", "3.1.38"),
        ("prior_db_head", "0087"),
        ("candidate_version", "3.1.38"),
        ("candidate_db_head", "0087"),
        ("candidate_revision", helper.PRIOR_REVISION),
        ("prior_image_id", "backend:latest"),
        ("candidate_image_id", "sha256:" + "b" * 64),
        ("prior_backend_manifest_sha256", "0" * 64),
        ("candidate_backend_manifest_sha256", "0" * 64),
        ("prior_source_archive_sha256", "0" * 64),
        ("candidate_source_archive_sha256", helper.PRIOR_SOURCE_ARCHIVE_SHA256),
    ):
        identity = _identity(helper)
        identity[field] = value
        with pytest.raises(helper.CompatibilityError):
            helper._validate_identity(**identity)


def _effective_environment(helper):
    prior_id = "sha256:" + "b" * 64
    candidate_id = "sha256:" + "c" * 64
    revision = "a" * 40
    common = {"PATH": "/usr/local/bin", "JWT_SECRET": "synthetic-private-value"}
    reviewed_python = {
        "PYTHON_VERSION": "3.14.8",
        "PYTHON_SHA256": "c2215904f02b175596dc49351585104f4bc20341e1c47378b26a2c274360ce73",
    }
    prior_values = {
        **common,
        **reviewed_python,
        "APP_VERSION": helper.PRIOR_VERSION,
        "APP_REVISION": helper.PRIOR_REVISION,
    }
    prior = {
        "image_id": prior_id,
        "environment": [f"{key}={value}" for key, value in prior_values.items()],
    }
    candidate = {
        "image_id": candidate_id,
        "environment": [
            f"{key}={value}"
            for key, value in {"PATH": common["PATH"], **reviewed_python}.items()
        ],
    }
    compose = {
        "services": {
            "backend": {
                "image": f"d-company-erp-backend:{revision}",
                "environment": {
                    "JWT_SECRET": common["JWT_SECRET"],
                    "APP_VERSION": helper.CANDIDATE_VERSION,
                    "APP_REVISION": revision,
                },
            }
        }
    }
    return prior, candidate, compose, prior_id, candidate_id, revision


def test_effective_environment_allows_only_reviewed_python_image_metadata(helper):
    historical = helper._load_historical(ROOT)
    inputs = _effective_environment(helper)
    original = deepcopy(inputs)
    assert helper._verify_effective_environment(historical, *inputs) == 6
    assert inputs == original


@pytest.mark.parametrize(
    "change",
    [
        "secret",
        "path",
        "python_version",
        "python_sha",
        "extra",
        "missing",
        "duplicate",
        "wrong_image",
        "wrong_compose_image",
        "env_file",
        "masked_image_metadata",
    ],
)
def test_effective_environment_refuses_every_unreviewed_difference(
    helper, change
):
    historical = helper._load_historical(ROOT)
    prior, candidate, compose, prior_id, candidate_id, revision = (
        _effective_environment(helper)
    )
    overrides = compose["services"]["backend"]["environment"]
    if change == "secret":
        overrides["JWT_SECRET"] = "changed-private-value"
    elif change == "path":
        candidate["environment"][0] = "PATH=/different"
    elif change == "python_version":
        candidate["environment"][1] = "PYTHON_VERSION=3.14.9"
    elif change == "python_sha":
        candidate["environment"][2] = "PYTHON_SHA256=" + "0" * 64
    elif change == "extra":
        overrides["NEW_POLICY"] = "true"
    elif change == "missing":
        del overrides["JWT_SECRET"]
    elif change == "duplicate":
        candidate["environment"].append("PATH=/usr/local/bin")
    elif change == "wrong_image":
        candidate["image_id"] = "sha256:" + "d" * 64
    elif change == "wrong_compose_image":
        compose["services"]["backend"]["image"] = "backend:latest"
    elif change == "env_file":
        compose["services"]["backend"]["env_file"] = [".env"]
    else:
        overrides["PYTHON_VERSION"] = "3.14.8"
    with pytest.raises(ValueError) as error:
        helper._verify_effective_environment(
            historical,
            prior,
            candidate,
            compose,
            prior_id,
            candidate_id,
            revision,
        )
    assert "private-value" not in str(error.value)


def _fleet_document(helper, historical, monkeypatch):
    rows = [
        {
            "row_id": "00000000-0000-0000-0000-000000000001",
            "installation_id": "11111111-1111-1111-1111-111111111111",
            "company_id": "22222222-2222-2222-2222-222222222222",
            "terminal_id": None,
            "platform": "android",
            "version_name": "3.1.29",
            "version_code": 37,
            "pending_outbox_count": 1,
            "last_successful_sync_at": "2026-09-20T10:00:00.000000Z",
            "last_seen_at": "2026-10-13T10:00:00.000000Z",
        },
        {
            "row_id": "00000000-0000-0000-0000-000000000002",
            "installation_id": "33333333-3333-3333-3333-333333333333",
            "company_id": "44444444-4444-4444-4444-444444444444",
            "terminal_id": "55555555-5555-5555-5555-555555555555",
            "platform": "android",
            "version_name": "3.1.33",
            "version_code": 41,
            "pending_outbox_count": 1,
            "last_successful_sync_at": "2026-10-10T10:00:00.000000Z",
            "last_seen_at": "2026-10-13T11:00:00.000000Z",
        },
    ]
    expected = {}
    for row, classification in zip(
        rows, ("historical_cleanup_evidence", "unknown_preserved_work"), strict=True
    ):
        digest = hashlib.sha256(historical._row_projection(row).encode()).hexdigest()
        row["identity_sha256"] = digest
        expected[digest] = (classification, row["version_name"], row["version_code"])
    fleet_digest = hashlib.sha256(
        "\n".join(row["identity_sha256"] for row in rows).encode()
    ).hexdigest()
    monkeypatch.setattr(historical, "EXPECTED_FLEET_ROWS", expected)
    monkeypatch.setattr(historical, "EXPECTED_NONZERO_FLEET_SHA256", fleet_digest)
    monkeypatch.setattr(
        historical, "EXPECTED_ALL_INSTALLATIONS_IDENTITY_SHA256", "a" * 64
    )
    verifier = historical._load_historical_verifier(ROOT)
    return {
        "schema_version": 1,
        "database_revision": "0088",
        "installation_row_count": 11,
        "pending_outbox_count": 2,
        "nonzero_installation_count": 2,
        "all_installations_identity_sha256": "a" * 64,
        "nonzero_installations_identity_sha256": fleet_digest,
        "nonzero_installations": rows,
        "client_installation_guard_trigger_count": 1,
        "client_installation_guard_function_sha256": verifier.EXPECTED_CLIENT_INSTALLATION_GUARD_FUNCTION_SHA256,
        "client_installation_guard_function_definition_sha256": verifier.EXPECTED_CLIENT_INSTALLATION_GUARD_FUNCTION_DEFINITION_SHA256,
        "client_installation_guard_trigger_definition_sha256": verifier.EXPECTED_CLIENT_INSTALLATION_GUARD_TRIGGER_DEFINITION_SHA256,
    }


@pytest.mark.parametrize(
    "mutation", ["added", "missing", "changed", "wrong_total", "wrong_revision", "wrong_digest"]
)
def test_fleet_is_exactly_bound_and_two_opaque_preserved_counters(
    helper, monkeypatch, mutation
):
    historical = helper._load_historical(ROOT)
    document = _fleet_document(helper, historical, monkeypatch)
    rows = document["nonzero_installations"]
    if mutation == "added":
        rows.append(deepcopy(rows[-1]))
    elif mutation == "missing":
        rows.pop()
    elif mutation == "changed":
        rows[-1]["pending_outbox_count"] = 2
    elif mutation == "wrong_total":
        document["installation_row_count"] -= 1
    elif mutation == "wrong_revision":
        document["database_revision"] = "0087"
    else:
        document["all_installations_identity_sha256"] = "b" * 64
    with pytest.raises(historical.CompatibilityError):
        historical._verify_fleet(document, historical._load_historical_verifier(ROOT))


def test_valid_fleet_report_preserves_unknown_work_without_private_ids(
    helper, monkeypatch
):
    historical = helper._load_historical(ROOT)
    document = _fleet_document(helper, historical, monkeypatch)
    result = historical._verify_fleet(
        document, historical._load_historical_verifier(ROOT)
    )
    assert result["installation_row_count"] == 11
    assert result["raw_pending_outbox_count"] == 2
    assert [row["classification"] for row in result["installations"]] == [
        "historical_cleanup_evidence",
        "unknown_preserved_work",
    ]
    rendered = json.dumps(result)
    assert all(
        row["installation_id"] not in rendered
        for row in document["nonzero_installations"]
    )


def _copy_contract_paths(root: Path, paths: tuple[str, ...]) -> None:
    for relative in paths:
        target = root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes((ROOT / relative).read_bytes())


def test_loyalty_compatibility_contract_is_bound_and_mutations_fail(helper, tmp_path):
    paths = (
        "backend/app/services/pos/loyalty_program.py",
        "backend/app/api/v1/pos/router.py",
        "backend/alembic/versions/0087_versioned_loyalty_program.py",
        "backend/app/services/pos/points.py",
    )
    root = tmp_path / "candidate"
    _copy_contract_paths(root, paths)
    proof = helper._verify_loyalty_compatibility(root)
    assert proof["production_program_revision_required"] == 0
    assert proof["revisionless_fresh_requests_after_customization"] == "refused"

    cases = (
        (paths[0], "if expected is None and program.revision == 0:", "if expected is None:"),
        (paths[1], "if existing_response:", "if False and existing_response:"),
        (paths[2], 'down_revision = "0086"', 'down_revision = "0085"'),
        (
            paths[2],
            "SELECT gen_random_uuid(), c.id, 0, CAST(:rules AS jsonb)",
            "SELECT gen_random_uuid(), c.id, 1, CAST(:rules AS jsonb)",
        ),
        (paths[2], "redemption_kind='legacy_frozen'", "redemption_kind='generic'"),
        (paths[3], '"legacy_frozen"', '"generic"'),
    )
    for relative, before, after in cases:
        target = root / relative
        original = target.read_text()
        target.write_text(original.replace(before, after))
        with pytest.raises(helper.CompatibilityError):
            helper._verify_loyalty_compatibility(root)
        target.write_text(original)


def test_anonymous_participant_compatibility_is_bound_and_mutations_fail(
    helper, tmp_path
):
    paths = (
        "backend/alembic/versions/0088_anonymous_gaming_participants.py",
        "backend/app/models/gaming.py",
        "backend/app/api/v1/gaming/router.py",
        "android-native/app/src/main/java/cloud/dcompany/erp/core/sync/SyncEngine.kt",
    )
    root = tmp_path / "candidate"
    _copy_contract_paths(root, paths)
    proof = helper._verify_guest_compatibility(root)
    assert proof == {
        "verification_scope": "source_contract_only",
        "behavioral_evidence_required": True,
        "legacy_join_wire_bytes_preserved": True,
        "anonymous_customer_and_loyalty_identity_absent": True,
        "stable_participant_identity_required": True,
        "saved_customer_identity_canonical": True,
        "unsupported_android_guest_response_refused_before_commit": True,
        "downgrade_refused_after_anonymous_identity_storage": True,
    }

    cases = (
        (paths[0], "NEW.customer_id IS NOT NULL AND NOT EXISTS", "FALSE AND NOT EXISTS"),
        (
            paths[1], 'UniqueConstraint("settlement_id", "participant_identity_id"',
            'UniqueConstraint("settlement_id", "customer_id"',
        ),
        (
            paths[2], "_anonymous_participant_client_guard(request, response)",
            "# removed response compatibility guard",
        ),
        (
            paths[3], "action.participantIdentityId == null",
            "action.participantIdentityId != null",
        ),
    )
    for relative, before, after in cases:
        target = root / relative
        original = target.read_text()
        assert before in original
        target.write_text(original.replace(before, after))
        with pytest.raises(helper.CompatibilityError):
            helper._verify_guest_compatibility(root)
        target.write_text(original)


def test_installer_has_separate_exact_build48_path_and_preserves_build47_branch(helper):
    source = INSTALLER.read_text()
    build48 = source.index('[ "$CANDIDATE_APP_VERSION" = 3.1.40 ]')
    build47 = source.index('[ "$CANDIDATE_APP_VERSION" = 3.1.39 ]', build48)
    build46 = source.index('[ "$CANDIDATE_APP_VERSION" = 3.1.38 ]', build47)
    assert build48 < build47 < build46
    block = source[build48:build47]
    assert helper.PRIOR_REVISION in block
    assert '[ "$PRIOR_APP_VERSION" = 3.1.39 ]' in block
    assert '[ "$PRIOR_DB_HEAD" = 0088 ]' in block
    assert "--candidate-db-head 0088" in block
    assert "BUILD48_PENDING_CARRIED_UNRESOLVED=true" in block
    assert "BUILD47_PENDING_CARRIED_UNRESOLVED=true" not in block
    assert "queues remain opaque" in block
    # No historical acceptance branch is widened or rewritten.
    released = subprocess.check_output(
        ["git", "show", f"{helper.PRIOR_REVISION}:infra/scripts/install-on-vm.sh"],
        cwd=ROOT, text=True,
    )
    old_start = released.index('[ "$CANDIDATE_APP_VERSION" = 3.1.39 ]')
    old_end = released.index('[ "$CANDIDATE_APP_VERSION" = 3.1.38 ]', old_start)
    assert source[build47:build46] == released[old_start:old_end]
    assert source.count('[ "$CANDIDATE_APP_VERSION" = 3.1.40 ]') == 1
    assert source.count("BUILD47_PENDING_CARRIED_UNRESOLVED=true") == 1
    assert source.count("BUILD48_PENDING_CARRIED_UNRESOLVED=true") == 1
    assert f'BUILD48_PENDING_COMPATIBILITY_VERIFIER="$CANDIDATE_BUILD_ROOT/{HELPER.relative_to(ROOT)}"' in source
    guard = source[source.index("# Preserve the original legacy-path guard"):source.index('echo "==> Creating final quiesced')]
    assert '${BUILD48_PENDING_CARRIED_UNRESOLVED:-false}' in guard
    assert '[ "$accepted_pending_paths" -ne 1 ]' in guard


def test_business_and_schema_bytes_remain_published_build47(helper):
    allowed = {
        "backend/app/__init__.py", "backend/pyproject.toml",
        "backend/tests/unit/test_release_contracts.py",
        "backend/tests/unit/test_remote_assistance_contract.py",
        "android-native/app/build.gradle.kts",
    }
    runtime_changes = {
        path for path in helper.REVIEWED_CHANGES
        if path.startswith(("backend/", "android-native/"))
    }
    assert runtime_changes == allowed
    for path in allowed:
        before = subprocess.check_output(
            ["git", "show", f"{helper.PRIOR_REVISION}:{path}"], cwd=ROOT,
        ).decode()
        after = subprocess.check_output(
            ["git", "show", f"{BUILD48_REVISION}:{path}"], cwd=ROOT,
        ).decode()
        expected = before.replace("3.1.39", "3.1.40")
        if path == "android-native/app/build.gradle.kts":
            expected = expected.replace("versionCode = 47", "versionCode = 48")
        assert after == expected


def test_historical_library_refuses_replacement_or_link(helper, tmp_path):
    target = tmp_path / helper.HISTORICAL_HELPER
    target.parent.mkdir(parents=True)
    target.write_bytes(HISTORICAL_HELPER.read_bytes() + b"\n")
    with pytest.raises(helper.CompatibilityError, match="historical build47"):
        helper._load_historical(tmp_path)
    target.unlink()
    target.symlink_to(HISTORICAL_HELPER)
    with pytest.raises(helper.CompatibilityError, match="historical build47"):
        helper._load_historical(tmp_path)


def test_installer_verifies_public_assets_before_tariff_or_ingress():
    source = INSTALLER.read_text()
    parity = source.index('echo "==> Verifying healthy PostgreSQL/Redis/backend/frontend')
    gate = source.index('python3 "$WEB_PUBLIC_ASSET_VERIFIER" --container "$CANDIDATE_FRONTEND_CONTAINER"')
    tariff = source.index('echo "==> Auditing the owner-approved Gaming Centre tariff')
    ingress = source.index("up -d --no-build --pull never caddy", gate)
    assert parity < gate < tariff < ingress
    block = source[parity:gate]
    assert 'ps -q frontend' in block
    assert '^[0-9a-f]{64}$' in block
    assert '^[a-z' not in block
    assert "'{{.Image}}'" in block
    assert '"$CANDIDATE_FRONTEND_IMAGE_ID"' in block
    assert 'WEB_PUBLIC_ASSET_VERIFIER="$CANDIDATE_BUILD_ROOT/ops/verify_web_public_assets.py"' in source
    assert '"$BUILD48_PENDING_COMPATIBILITY_VERIFIER"' in source
    assert '"$BUILD49_PENDING_COMPATIBILITY_VERIFIER" "$WEB_PUBLIC_ASSET_VERIFIER"' in source


@pytest.mark.parametrize("case", ["accepted", "short_id", "multiple_ids", "wrong_image", "http_failure"])
def test_installer_asset_gate_refuses_identity_or_http_failures(tmp_path, case):
    source = INSTALLER.read_text()
    start = source.index('CANDIDATE_FRONTEND_CONTAINER=$("${candidate_compose[@]}"')
    end = source.index('# Business prices must never change', start)
    block = source[start:end]
    checker = tmp_path / "check.py"
    checker.write_text(
        "import sys\n"
        "assert sys.argv[1:] == ['--container', 'a' * 64]\n"
        "print('ASSET_CHECK_CALLED')\n"
        + ("raise SystemExit(1)\n" if case == "http_failure" else "")
    )
    identity = "a" * 64
    if case == "short_id":
        identity = "a" * 12
    elif case == "multiple_ids":
        identity += "\n" + "b" * 64
    image = "sha256:" + ("d" if case == "wrong_image" else "c") * 64
    import os
    environment = {**os.environ, "TEST_CONTAINER_ID": identity, "TEST_IMAGE_ID": image}
    environment["WEB_PUBLIC_ASSET_VERIFIER"] = str(checker)
    environment["CANDIDATE_IMAGE_ATTESTATION"] = json.dumps({
        "services": {"frontend": {"image_id": "sha256:" + "c" * 64}}
    })
    # The stub implements only Compose lookup and inspect; the actual checker
    # entry point runs with Python and controls whether execution may continue.
    script = """set -eu
candidate_compose=(docker compose)
docker() {
  if [ "$1" = compose ]; then printf '%s\n' "$TEST_CONTAINER_ID"
  elif [ "$1" = inspect ]; then printf '%s\n' "$TEST_IMAGE_ID"
  else return 90
  fi
}
""" + block + "\necho CONTINUED_AFTER_GATE\n"
    result = subprocess.run(["bash", "-c", script], env=environment, text=True, capture_output=True)
    if case == "accepted":
        assert result.returncode == 0
        assert "ASSET_CHECK_CALLED" in result.stdout
        assert "CONTINUED_AFTER_GATE" in result.stdout
    else:
        assert result.returncode != 0
        assert "CONTINUED_AFTER_GATE" not in result.stdout
        assert ("ASSET_CHECK_CALLED" in result.stdout) == (case == "http_failure")
