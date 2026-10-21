"""Build54 repair: exercise the real immutable environment dependency chain."""

from __future__ import annotations

import ast
from copy import deepcopy
import hashlib
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace

import pytest

ROOT = Path(__file__).resolve().parents[1]
HELPER = ROOT / "infra/scripts/verify-build54-pending-compatibility.py"
FROZEN = {
    "infra/scripts/verify-build52-pending-compatibility.py": "fd850d3d08341c93166dbf89ead46f57c93c5f12670221afe2fee8f2a5b69dd2",
    "infra/scripts/verify-build53-pending-compatibility.py": "b0cfe1462ba0684b4668040a4113a49997920ea7efec2f1284dcb6cc23930adf",
}


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.fixture
def verifier():
    return load(HELPER, "build54_pending_compatibility")


@pytest.fixture
def environment(verifier, tmp_path):
    args = SimpleNamespace(
        candidate_source_root=ROOT,
        candidate_revision="b" * 40,
        prior_image_id=verifier.PRIOR_BACKEND_IMAGE_ID,
        candidate_image_id="sha256:" + "c" * 64,
    )
    secret = "synthetic-DO-NOT-PRINT-environment-secret"
    common = {
        "PATH": "/usr/local/bin:/usr/bin:/bin",
        "JWT_SECRET": secret,
        "DATABASE_URL": "postgresql+asyncpg://test:synthetic@postgres/test",
        "ANDROID_LATEST_VERSION_CODE": "50",
        "ANDROID_MIN_SUPPORTED_VERSION_CODE": "8",
        "ANDROID_FORCE_UPDATE": "false",
    }
    prior = dict(
        common, APP_VERSION=verifier.PRIOR_VERSION, APP_REVISION=verifier.PRIOR_REVISION
    )
    candidate = dict(
        common,
        APP_VERSION=verifier.CANDIDATE_VERSION,
        APP_REVISION=args.candidate_revision,
    )
    prior_raw = "".join(f"{k}={v}\n" for k, v in prior.items()).encode()
    candidate_raw = "".join(f"{k}={v}\n" for k, v in candidate.items()).encode()
    docs = {
        "prior_env": prior_raw,
        "live_env": prior_raw,
        "candidate_env": candidate_raw,
        "prior_runtime_env": json.dumps(
            {
                "image_id": args.prior_image_id,
                "environment": [f"{k}={v}" for k, v in prior.items()],
            }
        ).encode(),
        "candidate_image_env": json.dumps(
            {
                "image_id": args.candidate_image_id,
                "environment": [f"{k}={v}" for k, v in candidate.items()],
            }
        ).encode(),
        "candidate_compose_config": json.dumps(
            {
                "services": {
                    "backend": {
                        "image": "d-company-erp-backend:" + args.candidate_revision,
                        "environment": {},
                    }
                }
            }
        ).encode(),
    }
    for key, raw in docs.items():
        path = tmp_path / key
        path.write_bytes(raw)
        setattr(args, key, path)
    return args, len(prior), secret


def test_real_dependency_chain_has_primitives_only_on_supported_historical_api(
    verifier,
):
    build50, _, _ = verifier._load_modules(ROOT)
    assert not hasattr(build50._implementation, "_verify_environment")
    assert not hasattr(build50._implementation, "_json_document")
    assert not hasattr(build50._implementation, "_docker_environment")
    historical = build50._load_historical(ROOT)
    assert Path(historical.__file__).name == "verify-build45-pending-compatibility.py"
    assert all(
        callable(getattr(historical, key))
        for key in ("_verify_environment", "_json_document", "_docker_environment")
    )
    # The older whole-environment helper permits reviewed Python-image changes;
    # Build54 must keep the stricter Build52 equality comparison instead.
    tree = ast.parse(HELPER.read_text())
    fn = next(
        x
        for x in tree.body
        if isinstance(x, ast.FunctionDef) and x.name == "_verify_environment"
    )
    assert "_verify_effective_environment" not in ast.unparse(fn)
    assert "build50._load_historical(args.candidate_source_root)" in ast.unparse(fn)


def test_actual_loaded_environment_chain_accepts_only_identity_change(
    verifier, environment
):
    args, count, _ = environment
    build50, _, _ = verifier._load_modules(ROOT)
    assert verifier._verify_environment(args, build50) == count
    assert (
        build50._implementation.CANDIDATE_VERSION == "3.1.42"
    )  # private45 context only


@pytest.mark.parametrize(
    "change",
    [
        "live",
        "secret",
        "nonrelease",
        "extra",
        "missing_release",
        "duplicate_release",
        "wrong_version",
        "wrong_revision",
        "quoted_release",
        "export_release",
        "spacing_release",
        "duplicate_json",
        "nonfinite_json",
        "invalid_json",
        "oversize_json",
        "empty_json",
        "wrong_prior_image",
        "wrong_candidate_image",
        "docker_extra_shape",
        "docker_duplicate_key",
        "docker_invalid_key",
        "docker_nonstring",
        "docker_missing_equals",
        "docker_nonlist",
        "wrong_compose_image",
        "env_file",
        "compose_missing",
        "compose_nonstring",
        "compose_bool",
        "effective_secret",
        "effective_extra",
        "effective_missing",
        "effective_version",
        "effective_revision",
        "unreviewed_python_image_change",
    ],
)
def test_real_chain_refuses_environment_boundaries_without_secret_output(
    verifier, environment, change
):
    args, _, secret = environment
    # This positive call is deliberately unmocked before every refusal mutation.
    build50, _, _ = verifier._load_modules(ROOT)
    verifier._verify_environment(args, build50)
    if change in {
        "live",
        "secret",
        "nonrelease",
        "extra",
        "missing_release",
        "duplicate_release",
        "wrong_version",
        "wrong_revision",
        "quoted_release",
        "export_release",
        "spacing_release",
    }:
        field = "live_env" if change == "live" else "candidate_env"
        raw = getattr(args, field).read_bytes()
        if change == "live":
            raw += b"CHANGED=true\n"
        elif change == "secret":
            raw = raw.replace(secret.encode(), b"other-private-value")
        elif change == "nonrelease":
            raw = raw.replace(
                b"ANDROID_FORCE_UPDATE=false", b"ANDROID_FORCE_UPDATE=true"
            )
        elif change == "extra":
            raw += b"UNREVIEWED=true\n"
        elif change == "missing_release":
            raw = raw.replace(b"APP_VERSION=3.1.46\n", b"")
        elif change == "duplicate_release":
            raw += b"APP_REVISION=" + args.candidate_revision.encode() + b"\n"
        elif change == "wrong_version":
            raw = raw.replace(b"3.1.46", b"3.1.45")
        elif change == "wrong_revision":
            raw = raw.replace(args.candidate_revision.encode(), b"a" * 40)
        elif change == "quoted_release":
            raw = raw.replace(b"APP_VERSION=3.1.46", b'APP_VERSION="3.1.46"')
        elif change == "export_release":
            raw = raw.replace(b"APP_VERSION=", b"export APP_VERSION=")
        else:
            raw = raw.replace(b"APP_VERSION=", b" APP_VERSION =")
        getattr(args, field).write_bytes(raw)
    elif change in {
        "duplicate_json",
        "nonfinite_json",
        "invalid_json",
        "oversize_json",
        "empty_json",
    }:
        raw = {
            "duplicate_json": b'{"services":{},"services":{}}',
            "nonfinite_json": b'{"invalid":NaN}',
            "invalid_json": b"{",
            "oversize_json": b" " * (8 * 1024 * 1024 + 1),
            "empty_json": b"",
        }[change]
        args.candidate_compose_config.write_bytes(raw)
    else:
        field = (
            "candidate_compose_config"
            if change
            in {
                "wrong_compose_image",
                "env_file",
                "compose_missing",
                "compose_nonstring",
                "compose_bool",
            }
            else "prior_runtime_env"
            if change == "wrong_prior_image"
            else "candidate_image_env"
        )
        doc = json.loads(getattr(args, field).read_bytes())
        if change in {"wrong_prior_image", "wrong_candidate_image"}:
            doc["image_id"] = "sha256:" + "0" * 64
        elif change == "docker_extra_shape":
            doc["extra"] = True
        elif change == "docker_duplicate_key":
            doc["environment"].append("JWT_SECRET=" + secret)
        elif change == "docker_invalid_key":
            doc["environment"].append("INVALID-KEY=value")
        elif change == "docker_nonstring":
            doc["environment"].append(3)
        elif change == "docker_missing_equals":
            doc["environment"].append("INVALID")
        elif change == "docker_nonlist":
            doc["environment"] = {}
        elif change == "wrong_compose_image":
            doc["services"]["backend"]["image"] = "d-company-erp-backend:latest"
        elif change == "env_file":
            doc["services"]["backend"]["env_file"] = [".env"]
        elif change == "compose_missing":
            doc = {"services": {}}
        elif change in {"compose_nonstring", "compose_bool"}:
            doc["services"]["backend"]["environment"] = {
                "JWT_SECRET": False if change == "compose_bool" else 3
            }
        elif change == "effective_secret":
            doc["environment"] = [
                x.replace(secret, "other-private-value") for x in doc["environment"]
            ]
        elif change == "effective_extra":
            doc["environment"].append("NEW=value")
        elif change == "effective_missing":
            doc["environment"] = [
                x for x in doc["environment"] if not x.startswith("PATH=")
            ]
        elif change == "effective_version":
            doc["environment"] = [
                x.replace("APP_VERSION=3.1.46", "APP_VERSION=3.1.45")
                for x in doc["environment"]
            ]
        elif change == "effective_revision":
            doc["environment"] = [
                x.replace(args.candidate_revision, "a" * 40) for x in doc["environment"]
            ]
        elif change == "unreviewed_python_image_change":
            doc["environment"].append("PYTHON_VERSION=3.14.8")
        else:
            raise AssertionError(change)
        getattr(args, field).write_text(json.dumps(doc))
    with pytest.raises(ValueError) as failure:
        verifier._verify_environment(args, build50)
    assert secret not in str(failure.value)
    assert "other-private-value" not in str(failure.value)


def test_compose_override_is_compared_after_image_environment(verifier, environment):
    args, count, secret = environment
    build50, _, _ = verifier._load_modules(ROOT)
    doc = json.loads(args.candidate_image_env.read_bytes())
    doc["environment"] = [
        x.replace(secret, "image-default") for x in doc["environment"]
    ]
    args.candidate_image_env.write_text(json.dumps(doc))
    compose = json.loads(args.candidate_compose_config.read_bytes())
    compose["services"]["backend"]["environment"] = {"JWT_SECRET": secret}
    args.candidate_compose_config.write_text(json.dumps(compose))
    assert verifier._verify_environment(args, build50) == count


def test_frozen_profiles_are_unchanged_and_same_fleet_is_retained(verifier):
    for path, digest in FROZEN.items():
        assert hashlib.sha256((ROOT / path).read_bytes()).hexdigest() == digest
    build52 = load(
        ROOT / "infra/scripts/verify-build52-pending-compatibility.py", "build52_frozen"
    )
    for key in (
        "PRIOR_REVISION",
        "PRIOR_VERSION",
        "PRIOR_DATABASE_HEAD",
        "PRIOR_SOURCE_ARCHIVE_SHA256",
        "PRIOR_BACKEND_MANIFEST_SHA256",
        "PRIOR_BACKEND_IMAGE_ID",
        "EXPECTED_INSTALLATION_ROWS",
        "EXPECTED_PENDING",
        "EXPECTED_NONZERO",
        "EXPECTED_ALL_INSTALLATIONS_IDENTITY_SHA256",
        "EXPECTED_NONZERO_FLEET_SHA256",
        "EXPECTED_RAW_FLEET_SHA256",
    ):
        assert getattr(verifier, key) == getattr(build52, key)
    assert (verifier.CANDIDATE_VERSION, verifier.CANDIDATE_DATABASE_HEAD) == (
        "3.1.46",
        "0091",
    )
    for key in (
        "_verify_pre_documents",
        "_verify_post_documents",
        "_decode_transition",
        "_verify_transition_pair",
        "_post_report",
    ):

        def code(module):
            return ast.dump(
                next(
                    x
                    for x in ast.parse(Path(module.__file__).read_text()).body
                    if isinstance(x, ast.FunctionDef) and x.name == key
                ),
                include_attributes=False,
            )

        assert code(verifier) == code(build52), key


def test_exact_installer_dispatch_and_order_remain_fail_closed():
    source = (ROOT / "infra/scripts/install-on-vm.sh").read_text()
    assert '[ "$CANDIDATE_APP_VERSION" = 3.1.46 ]; }' in source
    assert "EXACT_BUILD50_SUCCESSOR=Build54" in source
    assert 'EXACT_BUILD50_VERIFIER="$BUILD54_PENDING_COMPATIBILITY_VERIFIER"' in source
    assert source.count("BUILD54_PENDING_CARRIED_UNRESOLVED=true") == 1
    assert source.count("BUILD54_POST_PROOF_SUCCEEDED=true") == 1
    assert source.count("BUILD54_MIGRATION_RUNTIME_VERIFIED=true") == 1
    pre = source.index('python3 "$EXACT_BUILD50_VERIFIER" pre')
    backup = source.index('DATABASE_BACKUP="$UPGRADE_SNAPSHOT/database.dump"', pre)
    migration = source.index(
        "--entrypoint python backend -m alembic upgrade head", backup
    )
    post = source.index('python3 "$EXACT_BUILD50_VERIFIER" post', migration)
    writers = source.index(
        "up -d --no-build --pull never postgres redis backend frontend", post
    )
    ingress = source.index("up -d --no-build --pull never caddy", writers)
    assert pre < backup < migration < post < writers < ingress
    assert (
        "3.1.46) EXACT_MIGRATION_BUILD=Build54 ;;"
        in (ROOT / "infra/scripts/verify-production-runtime-images.sh").read_text()
    )


def test_profile_derivation_changes_only_declared_release_context_and_loader():
    old = ast.parse(
        (ROOT / "infra/scripts/verify-build52-pending-compatibility.py").read_text()
    )
    new = ast.parse(HELPER.read_text())
    old_functions = {x.name: x for x in old.body if isinstance(x, ast.FunctionDef)}
    new_functions = {x.name: x for x in new.body if isinstance(x, ast.FunctionDef)}
    assert new_functions.keys() == old_functions.keys()

    class HonestIdentity(ast.NodeTransformer):
        def visit_Constant(self, node):
            if isinstance(node.value, str):
                node.value = node.value.replace("Build54", "Build52").replace(
                    "build54 compatibility refused", "build52 compatibility refused"
                )
            return node

    for name, before in old_functions.items():
        after = deepcopy(new_functions[name])
        if name == "_load_modules":
            assert isinstance(
                after.body[0], ast.For
            )  # extra frozen52/53 byte verification
            after.body = after.body[1:]
        elif name == "_verify_environment":
            before = deepcopy(before)
            before.body[0] = ast.parse(
                "implementation = build50._load_historical(args.candidate_source_root)"
            ).body[0]
        after = HonestIdentity().visit(after)
        assert ast.dump(before, include_attributes=False) == ast.dump(
            after, include_attributes=False
        ), name


@pytest.mark.parametrize(
    "path",
    list(FROZEN)
    + [
        "infra/scripts/verify-build50-pending-compatibility.py",
        "infra/scripts/verify-build50-post-migration-state.py",
        "infra/scripts/verify-build51-post-migration-state.py",
    ],
)
@pytest.mark.parametrize("mutation", ["bytes", "symlink"])
def test_actual_dependency_loader_rejects_tampered_profiles(
    verifier, tmp_path, path, mutation
):
    import shutil

    source = tmp_path / "source"
    shutil.copytree(ROOT / "infra/scripts", source / "infra/scripts")
    target = source / path
    if mutation == "bytes":
        target.write_bytes(target.read_bytes() + b"\n")
    else:
        external = tmp_path / "external.py"
        external.write_bytes(target.read_bytes())
        target.unlink()
        target.symlink_to(external)
    with pytest.raises(ValueError):
        verifier._load_modules(source)


def test_root_ci_and_release_both_require_the_full_contract_suite():
    for workflow in ("ci.yml", "release.yml"):
        text = (ROOT / ".github/workflows" / workflow).read_text()
        assert "run: python -m pytest tests" in text


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("prior_revision", "3093f62971d16e5d80408a436959e7cc5ccbf318"),
        ("prior_version", "3.1.41"),
        ("prior_db_head", "0091"),
        ("candidate_version", "3.1.44"),
        ("candidate_version", "3.1.45"),
        ("candidate_version", "3.1.47"),
        ("candidate_db_head", "0092"),
        ("prior_source_archive_sha256", "0" * 64),
        ("prior_backend_manifest_sha256", "0" * 64),
        ("candidate_backend_manifest_sha256", "0" * 64),
        ("prior_image_id", "sha256:" + "0" * 64),
        ("candidate_image_id", "latest"),
        ("candidate_revision", "HEAD"),
        ("candidate_source_archive_sha256", "0"),
    ],
)
def test_build54_identity_refuses_substitution(verifier, field, value):
    prior, candidate = Path("prior"), Path("candidate")
    args = SimpleNamespace(
        prior_source_root=prior,
        candidate_source_root=candidate,
        prior_revision=verifier.PRIOR_REVISION,
        prior_version=verifier.PRIOR_VERSION,
        prior_db_head=verifier.PRIOR_DATABASE_HEAD,
        candidate_version="3.1.46",
        candidate_db_head="0091",
        prior_source_archive_sha256=verifier.PRIOR_SOURCE_ARCHIVE_SHA256,
        candidate_source_archive_sha256="b" * 64,
        prior_backend_manifest_sha256=verifier.PRIOR_BACKEND_MANIFEST_SHA256,
        candidate_backend_manifest_sha256=verifier.CANDIDATE_BACKEND_MANIFEST_SHA256,
        prior_image_id=verifier.PRIOR_BACKEND_IMAGE_ID,
        candidate_image_id="sha256:" + "b" * 64,
        candidate_revision="b" * 40,
    )
    manifests = {
        prior: verifier.PRIOR_BACKEND_MANIFEST_SHA256,
        candidate: verifier.CANDIDATE_BACKEND_MANIFEST_SHA256,
    }
    # Identity predicate only; environment-path tests above use actual loaders.
    manifest = SimpleNamespace(_backend_manifest_sha256=manifests.__getitem__)
    verifier._verify_identity(args, manifest)
    setattr(args, field, value)
    with pytest.raises(ValueError):
        verifier._verify_identity(args, manifest)


def test_build54_keeps_product_code_and_schemas_equal_to_build53():
    import subprocess

    changed = set(
        subprocess.run(
            [
                "git",
                "diff",
                "--name-only",
                "8506c7abdf15c92029226849f42b1b71fd6878d6",
                "5ad146ae076f5178acc2c8e4cb840ecbb0d0343a",
                "--",
                "backend/app",
                "frontend/src",
                "android-native/app/src/main",
                "android-native/app/schemas",
                "backend/alembic",
            ],
            cwd=ROOT,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.splitlines()
    )
    assert changed == {"backend/app/__init__.py"}
    before = subprocess.run(
        [
            "git",
            "show",
            "8506c7abdf15c92029226849f42b1b71fd6878d6:backend/app/__init__.py",
        ],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    after = subprocess.run(
        [
            "git",
            "show",
            "5ad146ae076f5178acc2c8e4cb840ecbb0d0343a:backend/app/__init__.py",
        ],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    assert after == before.replace(
        '"3.1.45"', '"3.1.46"'
    )
