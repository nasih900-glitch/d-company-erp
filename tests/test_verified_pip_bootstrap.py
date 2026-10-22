"""The trust correction must happen before pip networking, including venv reuse."""
from __future__ import annotations

import hashlib
import io
import json
import runpy
import tarfile
import importlib.util
import os
from pathlib import Path
import stat
import subprocess
import sys
from types import SimpleNamespace
import ssl

import pytest


ROOT = Path(__file__).resolve().parents[1]
HELPER = ROOT / "infra/scripts/bootstrap-verified-pip.py"
SPEC = importlib.util.spec_from_file_location("verified_pip_bootstrap", HELPER)
bootstrap = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(bootstrap)


def test_official_artifact_and_vendored_bundle_are_exactly_pinned():
    assert bootstrap.HOST == "files.pythonhosted.org"
    assert bootstrap.PATH == "/packages/3f/06/3e4420302363fbb71be3c7f35ce042f54bfca330e3e66b3a2e3c4cca579f/pip-26.2.2-py3-none-any.whl"
    assert bootstrap.SHA256 == "fd7f4dfa8190e014013b9f08f3e973f4e4c26ffd179a204895533a7254813a70"
    assert bootstrap.SIZE == 1_819_850
    assert bootstrap.VENDORED_CA_SHA256 == "50f79f98dac192b20dbf73f35c56f0b0c557ab267ff5199d1117cc1180b70785"


@pytest.fixture
def transport(monkeypatch):
    events = []
    response = SimpleNamespace(status=200, data=b"abc", length="3")

    def read1(size):
        value = response.data[:size]
        response.data = response.data[size:]
        return value

    response.read1 = read1
    response.getheader = lambda name: response.length

    class Connection:
        def __init__(self, host, *, timeout, context):
            events.append(("connect", host, timeout, context))

        def request(self, method, path, headers):
            events.append(("request", method, path, headers))

        def getresponse(self):
            return response

        def close(self):
            events.append(("closed",))

    monkeypatch.setattr(bootstrap.http.client, "HTTPSConnection", Connection)
    monkeypatch.setattr(bootstrap, "SIZE", 3)
    monkeypatch.setattr(bootstrap, "SHA256", hashlib.sha256(b"abc").hexdigest())
    return response, events


def test_transport_uses_fixed_direct_https_and_checks_exact_payload(transport):
    _, events = transport
    assert bootstrap._download_wheel() == b"abc"
    assert events[0][1:3] == ("files.pythonhosted.org", 10)
    assert events[1][1:3] == ("GET", bootstrap.PATH)
    assert events[-1] == ("closed",)


@pytest.mark.parametrize("status", [301, 302, 307, 308, 404])
def test_redirect_or_error_is_not_followed(transport, status):
    response, events = transport
    response.status = status
    with pytest.raises(bootstrap.BootstrapError, match="redirects"):
        bootstrap._download_wheel()
    assert events[-1] == ("closed",)
    assert len([event for event in events if event[0] == "request"]) == 1


@pytest.mark.parametrize("body,length", [(b"abd", "3"), (b"ab", "3"), (b"abcd", "3"), (b"abc", "4")])
def test_wrong_hash_truncation_oversize_or_declared_length_refuses(transport, body, length):
    response, events = transport
    response.data, response.length = body, length
    with pytest.raises(bootstrap.BootstrapError):
        bootstrap._download_wheel()
    assert events[-1] == ("closed",)


def test_missing_content_length_still_requires_actual_size_and_hash(transport):
    response, _ = transport
    response.length = None
    assert bootstrap._download_wheel() == b"abc"


def test_soft_deadline_rejects_late_stream_before_admission(transport, monkeypatch):
    values = iter((0, 31))
    monkeypatch.setattr(bootstrap.time, "monotonic", lambda: next(values))
    with pytest.raises(bootstrap.BootstrapError, match="deadline"):
        bootstrap._download_wheel()


def test_network_worker_has_hard_timeout_and_python_isolation(monkeypatch):
    calls = []
    monkeypatch.setattr(bootstrap.subprocess, "run", lambda command, **kwargs: (
        calls.append((command, kwargs)) or SimpleNamespace(stdout=b"abc")))
    assert bootstrap.download_wheel() == b"abc"
    command, kwargs = calls[0]
    assert command[:3] == [sys.executable, "-I", "-B"]
    assert command[-1] == str(HELPER.resolve())
    assert kwargs["timeout"] == 35 and kwargs["check"] is True
    assert kwargs["stdout"] == subprocess.PIPE and kwargs["stderr"] == subprocess.DEVNULL


def test_unconditional_parent_hash_blocks_worker_corruption_before_install(monkeypatch):
    monkeypatch.setattr(bootstrap, "download_wheel", lambda: b"wrong")
    monkeypatch.setattr(bootstrap.subprocess, "run", lambda *args, **kwargs: pytest.fail("Must not execute"))
    with pytest.raises(bootstrap.BootstrapError, match="size/SHA256"):
        bootstrap.bootstrap()


def test_install_is_private_offline_and_fresh_child_verifies_actual_ca_path(transport, monkeypatch):
    monkeypatch.setattr(bootstrap, "download_wheel", lambda: b"abc")
    calls = []

    def run(command, **kwargs):
        calls.append((command, kwargs))
        if "install" in command:
            wheel = Path(command[-1])
            assert wheel.read_bytes() == b"abc"
            assert not wheel.is_symlink()
            assert stat.S_IMODE(wheel.stat().st_mode) == 0o600
            assert stat.S_IMODE(wheel.parent.stat().st_mode) == 0o700
            assert all(flag in command for flag in ("--isolated", "--no-index", "--no-deps", "--no-cache-dir", "--disable-pip-version-check"))
        return SimpleNamespace(returncode=0)

    monkeypatch.setattr(bootstrap.subprocess, "run", run)
    bootstrap.bootstrap()
    assert len(calls) == 2
    assert all(call[0][:3] == [sys.executable, "-I", "-B"] for call in calls)
    assert calls[0][1]["timeout"] == 120 and calls[1][1]["timeout"] == 15
    verification = calls[1][0][-1]
    assert "pip._vendor.certifi" in verification and "Path(c.where()).resolve()" in verification
    assert bootstrap.VENDORED_CA_SHA256 in verification
    assert "2026.10.19" in verification and "26.2.2" in verification


def test_environment_cannot_redirect_pip_or_inject_python_modules(monkeypatch):
    for key in ("PIP_INDEX_URL", "PIP_TRUSTED_HOST", "PYTHONPATH", "PYTHONHOME"):
        monkeypatch.setenv(key, "untrusted")
    env = bootstrap.isolated_environment()
    assert all(key not in env for key in ("PIP_INDEX_URL", "PIP_TRUSTED_HOST", "PYTHONPATH", "PYTHONHOME"))
    assert env["PIP_CONFIG_FILE"] == os.devnull


def test_unprotected_temp_parent_refuses_before_network(tmp_path, monkeypatch):
    tmp_path.chmod(0o777)
    monkeypatch.setenv("TMPDIR", str(tmp_path))
    monkeypatch.setattr(bootstrap, "download_wheel", lambda: pytest.fail("No networking"))
    try:
        with pytest.raises(bootstrap.BootstrapError, match="without protection"):
            bootstrap.bootstrap()
    finally:
        tmp_path.chmod(0o700)


def test_relative_temp_parent_refuses(monkeypatch):
    monkeypatch.setenv("TMPDIR", "relative-untrusted")
    with pytest.raises(bootstrap.BootstrapError, match="absolute"):
        bootstrap.trusted_temp_parent()


def test_verify_only_never_downloads_or_installs(monkeypatch):
    monkeypatch.setattr(sys, "argv", [str(HELPER), "--verify-only"])
    monkeypatch.setattr(bootstrap, "download_wheel", lambda: pytest.fail("No networking"))
    calls = []
    monkeypatch.setattr(bootstrap.subprocess, "run", lambda command, **kwargs: calls.append(command))
    assert bootstrap.main() == 0 and len(calls) == 1
    assert "install" not in calls[0]


def test_failure_logs_do_not_copy_raw_exception_or_environment(monkeypatch, capsys):
    monkeypatch.setattr(sys, "argv", [str(HELPER)])
    def fail():
        raise OSError("secret request/context must never appear")
    monkeypatch.setattr(bootstrap, "download_wheel", fail)
    assert bootstrap.main() == 1
    assert "secret request/context" not in capsys.readouterr().err


def test_each_networked_caller_bootstraps_and_ops_reuse_verifies():
    docker = (ROOT / "infra/docker/backend.Dockerfile").read_text()
    assert docker.count("RUN python -I -B /tmp/bootstrap-verified-pip.py") == 2
    assert docker.index("RUN python -I -B /tmp/bootstrap-verified-pip.py") < docker.index("RUN pip install")
    ci = (ROOT / ".github/workflows/ci.yml").read_text()
    assert "python -I -B infra/scripts/bootstrap-verified-pip.py\n          pip install" in ci
    assert "python3 -I -B infra/scripts/bootstrap-verified-pip.py\n          python3 -m pip install" in ci
    release = (ROOT / ".github/workflows/release.yml").read_text()
    assert "python -I -B infra/scripts/bootstrap-verified-pip.py\n          python -m pip install" in release
    ops = (ROOT / "infra/scripts/install-operations-monitor.sh").read_text()
    assert '"$candidate/bin/python" -I -B "$PIP_BOOTSTRAP" --verify-only' in ops
    assert ops.index('"$runtime_temp/bin/python" -I -B "$PIP_BOOTSTRAP"') < ops.index('"$runtime_temp/bin/python" -m pip install')


@pytest.fixture
def system_store(tmp_path, monkeypatch):
    # Use one real decodable root from this test interpreter, never the host
    # store itself as a mutable fixture. No network or certificate generation.
    roots = ssl.create_default_context().get_ca_certs(binary_form=True)
    assert roots
    clean_roots = [root for root in roots if hashlib.sha256(root).hexdigest() != bootstrap.REVOKED_CA_SHA256]
    assert len(clean_roots) >= 2
    bundle_der, der = clean_roots[:2]
    pem = ssl.DER_cert_to_PEM_cert(der).encode("ascii")
    trust = tmp_path / "trust"
    trust.mkdir()
    capath = trust / "certs"
    capath.mkdir()
    cafile = trust / "bundle.crt"
    cafile.write_text(ssl.DER_cert_to_PEM_cert(bundle_der))
    anchor = capath / "1234abcd.0"
    anchor.write_bytes(pem)
    monkeypatch.delenv("SSL_CERT_FILE", raising=False)
    monkeypatch.delenv("SSL_CERT_DIR", raising=False)
    monkeypatch.setattr(bootstrap, "CA_TRUST_DIRECTORIES", (trust,))
    monkeypatch.setattr(bootstrap.ssl, "get_default_verify_paths", lambda: SimpleNamespace(cafile=str(cafile), capath=str(capath)))
    monkeypatch.setattr(bootstrap.ssl, "create_default_context", lambda: SimpleNamespace(get_ca_certs=lambda binary_form: [bundle_der]))
    return trust, capath, cafile, anchor, der, pem


def test_system_ca_checks_bundle_and_lazy_hash_links(system_store):
    trust, capath, _, anchor, _, pem = system_store
    external = trust / "packaged.pem"
    external.write_bytes(pem)
    anchor.unlink()
    anchor.symlink_to(external)
    (capath / "1234abcd.r0").write_bytes(b"a CRL is not a certificate")
    result = bootstrap._verify_system_ca()
    assert result["certificate_inputs"] == 2
    assert result["decoded_certificates"] == 2
    assert result["default_loaded_roots"] > 0


def test_compromised_root_in_lazy_capath_refuses_even_if_default_context_omits_it(system_store, monkeypatch):
    _, _, _, _, der, _ = system_store
    monkeypatch.setattr(bootstrap, "REVOKED_CA_SHA256", hashlib.sha256(der).hexdigest())
    with pytest.raises(bootstrap.BootstrapError, match="compromised"):
        bootstrap._verify_system_ca()


@pytest.mark.parametrize("suffix", [b"not a certificate", b"-----BEGIN CERTIFICATE-----\ntruncated", b"-----BEGIN PRIVATE KEY-----\nunsupported\n-----END PRIVATE KEY-----"])
def test_malformed_or_unsupported_applicable_ca_input_refuses(system_store, suffix):
    _, _, _, anchor, _, pem = system_store
    anchor.write_bytes(suffix if suffix == b"not a certificate" else pem + suffix)
    with pytest.raises(bootstrap.BootstrapError, match="malformed"):
        bootstrap._verify_system_ca()


def test_escaped_ca_symlink_refuses(system_store, tmp_path):
    _, _, _, anchor, _, pem = system_store
    outside = tmp_path / "outside.pem"
    outside.write_bytes(pem)
    anchor.unlink()
    anchor.symlink_to(outside)
    with pytest.raises(bootstrap.BootstrapError, match="escapes"):
        bootstrap._verify_system_ca()


def test_nonregular_ca_input_refuses_without_blocking(system_store):
    _, _, _, anchor, _, _ = system_store
    anchor.unlink()
    os.mkfifo(anchor)
    with pytest.raises(bootstrap.BootstrapError, match="regular"):
        bootstrap._verify_system_ca()


@pytest.mark.parametrize("bound,value,match", [("MAX_CA_FILE_BYTES", 1, "regular"), ("MAX_CA_TOTAL_BYTES", 1, "byte bound"), ("MAX_CA_CERTIFICATES", 1, "count"), ("MAX_CA_DIRECTORY_ENTRIES", 0, "directory")])
def test_ca_store_resource_bounds_are_fail_closed(system_store, monkeypatch, bound, value, match):
    monkeypatch.setattr(bootstrap, bound, value)
    with pytest.raises(bootstrap.BootstrapError, match=match):
        bootstrap._verify_system_ca()


@pytest.mark.parametrize("key,value", [("SSL_CERT_FILE", "/custom.pem"), ("SSL_CERT_DIR", "/one:/two")])
def test_system_ca_build_check_rejects_ambiguous_overrides(system_store, monkeypatch, key, value):
    monkeypatch.setenv(key, value)
    with pytest.raises(bootstrap.BootstrapError, match="unmodified"):
        bootstrap._verify_system_ca()


def test_ca_worker_is_isolated_bounded_and_never_downloads(monkeypatch):
    calls = []
    monkeypatch.setattr(bootstrap, "download_wheel", lambda: pytest.fail("No networking"))
    monkeypatch.setattr(bootstrap.subprocess, "run", lambda command, **kwargs: calls.append((command, kwargs)))
    bootstrap.verify_system_ca()
    command, kwargs = calls[0]
    assert command[:3] == [sys.executable, "-I", "-B"]
    assert "_verify_system_ca" in command[4]
    assert kwargs["timeout"] == 20 and kwargs["check"] is True


def test_backend_ca_update_precedes_both_pip_network_boundaries_and_preserves_other_images():
    docker = (ROOT / "infra/docker/backend.Dockerfile").read_text()
    stages = docker.split("# --- ")
    builder = next(stage for stage in stages if stage.startswith("Python dependency builder"))
    runtime = next(stage for stage in stages if stage.startswith("runtime"))
    for stage in (builder, runtime):
        assert stage.count("'ca-certificates=20261020-r0'") == 1
        assert stage.count("'ca-certificates-bundle=20261020-r0'") == 1
        assert "grep -Fx 'ca-certificates-20261020-r0'" in stage
        assert "grep -Fx 'ca-certificates-bundle-20261020-r0'" in stage
        assert stage.index("update-ca-certificates") < stage.index("--verify-system-ca") < stage.index("RUN python -I -B /tmp/bootstrap-verified-pip.py &&")
    gate = (ROOT / "infra/scripts/verify-production-runtime-images.sh").read_text()
    block = gate.split('if [ "$APP_VERSION" = 3.1.47 ]; then', 1)[1].split("\nfi", 1)[0]
    assert '"$candidate_images"' in block
    assert '"$TRUST_BACKEND_IMAGE_ID"' in block and '"$TRUST_BACKEND_IMAGE_ID" = ' not in block
    assert "--network none --read-only --cap-drop ALL" in block
    assert "--pull never" in block and "--verify-runtime" in block
    assert "bootstrap-verified-pip.py,dst=" in block and ",readonly" in block


def test_actual_runtime_verification_checks_all_fixed_versions_and_ca_packages(monkeypatch):
    calls = []
    monkeypatch.setattr(bootstrap, "verify_system_ca", lambda: calls.append("ca"))
    monkeypatch.setattr(bootstrap, "verify_installation", lambda: calls.append("pip"))
    def run(command, **kwargs):
        calls.append(command)
        return SimpleNamespace(stdout="ca-certificates-20261020-r0\nca-certificates-bundle-20261020-r0\n")
    monkeypatch.setattr(bootstrap.subprocess, "run", run)
    bootstrap.verify_runtime()
    assert calls[:2] == ["ca", "pip"]
    assert "13.0.0" in calls[2][-1] and "2026.10.19" in calls[2][-1]
    assert all(codec in calls[2][-1] for codec in ("PNG", "RGBA", "JPEG", "WEBP", "Image.open", "decoded.load()"))
    assert calls[3] == ["apk", "info", "-v"]
    monkeypatch.setattr(bootstrap.subprocess, "run", lambda *args, **kwargs: SimpleNamespace(stdout="ca-certificates-20260909-r0\n"))
    with pytest.raises(bootstrap.BootstrapError, match="package identity"):
        bootstrap.verify_runtime()


@pytest.mark.parametrize("loaded", [[], [b"compromised test root"]])
def test_default_loaded_roots_are_also_checked(system_store, monkeypatch, loaded):
    monkeypatch.setattr(bootstrap.ssl, "create_default_context", lambda: SimpleNamespace(get_ca_certs=lambda binary_form: loaded))
    if loaded:
        monkeypatch.setattr(bootstrap, "REVOKED_CA_SHA256", hashlib.sha256(loaded[0]).hexdigest())
    with pytest.raises(bootstrap.BootstrapError, match="Default SSL context"):
        bootstrap._verify_system_ca()


def test_lazy_ca_canonical_der_cannot_hide_target_behind_trailing_bytes(system_store, monkeypatch):
    _, _, _, anchor, der, _ = system_store
    # OpenSSL accepts this encoding but canonicalizes away the trailing bytes.
    # It must fail even when default get_ca_certs has not loaded lazy capath.
    anchor.write_text(ssl.DER_cert_to_PEM_cert(der + b"\x00garbage"))
    monkeypatch.setattr(bootstrap, "REVOKED_CA_SHA256", hashlib.sha256(der).hexdigest())
    monkeypatch.setattr(bootstrap.ssl, "create_default_context", lambda: SimpleNamespace(get_ca_certs=lambda binary_form: [b"unrelated loaded root"]))
    with pytest.raises(bootstrap.BootstrapError, match="canonical|compromised"):
        bootstrap._verify_system_ca()


@pytest.fixture
def preservation():
    return SimpleNamespace(**runpy.run_path(str(ROOT / "infra/scripts/verify-build55-pending-compatibility.py")))


def test_exact_immutable54_archive_keeps_original_protected_scope(preservation):
    archive = subprocess.run(
        ["git", "archive", "--format=tar", preservation.PRIOR_REVISION],
        cwd=ROOT, check=True, stdout=subprocess.PIPE, timeout=30,
    ).stdout
    assert hashlib.sha256(archive).hexdigest() == preservation.PRIOR_SOURCE_ARCHIVE_SHA256
    with tarfile.open(fileobj=io.BytesIO(archive), mode="r:") as source:
        prior = {
            entry.name: hashlib.sha256(source.extractfile(entry).read()).hexdigest()
            for entry in source.getmembers() if entry.isfile()
            and any(entry.name == scope or entry.name.startswith(scope + "/")
                    for scope in preservation.PRIOR_PROTECTED_SCOPES)
        }
    assert preservation.inventory_hash(prior) == preservation.PRIOR_PROTECTED_SOURCE_SHA256
    assert "infra/scripts/install-operations-monitor.sh" not in prior
    assert preservation.PROTECTED_SCOPES == (*preservation.PRIOR_PROTECTED_SCOPES,
        "infra/scripts/bootstrap-verified-pip.py", "infra/scripts/install-operations-monitor.sh")


@pytest.mark.parametrize("tamper", [None, "helper", "candidate_ops", "prior_backend"])
def test_identity_binding_uses_role_specific_closures(tmp_path, monkeypatch, tamper):
    module = runpy.run_path(str(ROOT / "infra/scripts/verify-build55-pending-compatibility.py"))
    prior, candidate = tmp_path / "prior", tmp_path / "candidate"
    for folder in (prior, candidate):
        (folder / "backend").mkdir(parents=True)
        (folder / "backend/example.py").write_text("immutable source\n")
        (folder / "infra/scripts").mkdir(parents=True)
        (folder / "infra/scripts/install-operations-monitor.sh").write_text("old ops\n")
    (candidate / "infra/scripts/bootstrap-verified-pip.py").write_text("new fixed bootstrap\n")
    # Prior ops was not part of the immutable54 closure. Expanding only the
    # candidate must not silently redefine that original accepted fingerprint.
    prior_hash = module["inventory_hash"](module["inventory"](prior, module["PRIOR_PROTECTED_SCOPES"]))
    candidate_hash = module["inventory_hash"](module["inventory"](candidate, module["PROTECTED_SCOPES"]))
    backend_hash = hashlib.sha256((hashlib.sha256(b"immutable source\n").hexdigest() + "\texample.py\n").encode()).hexdigest()
    namespace = module["verify_pre_bindings"].__globals__
    monkeypatch.setitem(namespace, "PRIOR_PROTECTED_SOURCE_SHA256", prior_hash)
    monkeypatch.setitem(namespace, "CANDIDATE_PROTECTED_SOURCE_SHA256", candidate_hash)
    monkeypatch.setitem(namespace, "PRIOR_BACKEND_MANIFEST_SHA256", backend_hash)
    monkeypatch.setitem(namespace, "CANDIDATE_BACKEND_MANIFEST_SHA256", backend_hash)
    revision = "1" * 40
    identity = {
        "prior_revision": module["PRIOR_REVISION"], "prior_version": module["PRIOR_VERSION"],
        "prior_database_head": module["PRIOR_DATABASE_HEAD"],
        "candidate_revision": revision, "candidate_version": module["CANDIDATE_VERSION"],
        "candidate_database_head": module["CANDIDATE_DATABASE_HEAD"],
        "prior_source_archive_sha256": module["PRIOR_SOURCE_ARCHIVE_SHA256"],
        "candidate_source_archive_sha256": "2" * 64,
        "prior_backend_manifest_sha256": backend_hash, "candidate_backend_manifest_sha256": backend_hash,
        "prior_images": module["PRIOR_IMAGES"],
        "candidate_images": {name: "sha256:" + format(index + 100, "064x") for index, name in enumerate(module["SERVICES"])},
    }
    class ReachedEnvironmentBoundary(Exception):
        pass
    def private_file(path):
        if path == "identity":
            return json.dumps(identity).encode()
        raise ReachedEnvironmentBoundary
    monkeypatch.setitem(namespace, "private_file", private_file)
    original_stat = Path.stat
    def owned_snapshot_stat(path, *args, **kwargs):
        actual = original_stat(path, *args, **kwargs)
        if path in (prior, candidate):
            return SimpleNamespace(st_mode=stat.S_IFDIR | 0o700, st_uid=0)
        return actual
    monkeypatch.setattr(Path, "stat", owned_snapshot_stat)
    if tamper:
        changed = {"helper": candidate / "infra/scripts/bootstrap-verified-pip.py",
                   "candidate_ops": candidate / "infra/scripts/install-operations-monitor.sh",
                   "prior_backend": prior / "backend/example.py"}[tamper]
        changed.write_text("tampered\n")
    args = SimpleNamespace(identity="identity", prior_source_root=prior,
                           candidate_source_root=candidate, prior_env="prior_env")
    if tamper:
        with pytest.raises(module["CompatibilityError"], match="protected source changed"):
            module["verify_pre_bindings"](args)
    else:
        with pytest.raises(ReachedEnvironmentBoundary):
            module["verify_pre_bindings"](args)
