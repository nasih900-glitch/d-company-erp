from __future__ import annotations

import importlib.util
import json
import os
import socket
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest


ROOT = Path(__file__).resolve().parents[1]
PROBE = ROOT / "infra" / "scripts" / "verify-ci-docker-connection.py"
SPEC = importlib.util.spec_from_file_location("ci_docker_connection", PROBE)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("cannot load CI Docker connection verifier")
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)

CLASSIC_STATUS = [
    ["Backing Filesystem", "extfs"],
    ["Supports d_type", "true"],
    ["Using metacopy", "false"],
    ["Native Overlay Diff", "true"],
    ["userxattr", "false"],
]
CONTAINERD_STATUS = [["driver-type", "io.containerd.snapshotter.v1"]]


def _identity(role: str) -> dict[str, object]:
    shared: dict[str, object] = {
        "docker_host": "unix:///tmp/docker.sock",
        "docker_cli_path": "/tool/docker",
        "docker_cli_sha256": "a" * 64,
        "client_version": "29.6.1",
        "client_api_version": "1.53",
        "client_git_commit": "abc",
        "server_version": "29.6.1",
        "server_api_version": "1.53",
        "server_git_commit": "def",
        "daemon_id": "daemon",
        "docker_root_dir": "/tmp/docker-root",
        "storage_driver": "overlay2",
        "driver_status": CLASSIC_STATUS,
        "image_store": "classic",
        "python_version": "3.12.3",
        "python_executable": "/usr/bin/python3.12",
    }
    if role == "runner":
        shared.update(
            {
                "compose_version": "v2.40.3",
                "buildx_version": "github.com/docker/buildx v0.30.1 abc",
                "builder_driver": "docker",
                "builder_context": "default",
                "builder_endpoint": "default",
                "builder_resolved_host": "unix:///tmp/docker.sock",
            }
        )
    return {"role": role, **shared}


def test_compare_requires_exact_runner_root_connection_identity(tmp_path: Path) -> None:
    runner = tmp_path / "runner.json"
    root = tmp_path / "root.json"
    runner.write_text(json.dumps(_identity("runner")), encoding="utf-8")
    root.write_text(json.dumps(_identity("root")), encoding="utf-8")

    result = MODULE.compare(runner, root)

    assert result["verified"] is True
    assert result["builder_driver"] == "docker"
    assert result["builder_resolved_host"] == "unix:///tmp/docker.sock"


@pytest.mark.parametrize(
    "field",
    [
        "docker_host",
        "docker_cli_sha256",
        "server_version",
        "daemon_id",
        "docker_root_dir",
        "image_store",
        "driver_status",
        "python_version",
    ],
)
def test_compare_rejects_runner_root_drift(tmp_path: Path, field: str) -> None:
    runner_payload = _identity("runner")
    root_payload = _identity("root")
    root_payload[field] = "different"
    runner = tmp_path / "runner.json"
    root = tmp_path / "root.json"
    runner.write_text(json.dumps(runner_payload), encoding="utf-8")
    root.write_text(json.dumps(root_payload), encoding="utf-8")

    with pytest.raises(MODULE.ConnectionError, match=field):
        MODULE.compare(runner, root)


def test_unix_host_requires_a_real_local_socket(tmp_path: Path) -> None:
    socket_path = Path(f"/tmp/dcompany-ci-{os.getpid()}.sock")
    socket_path.unlink(missing_ok=True)
    try:
        with socket.socket(socket.AF_UNIX) as listener:
            listener.bind(str(socket_path))
            host, resolved = MODULE._canonical_host(f"unix://{socket_path}")
            assert host == f"unix://{socket_path.resolve()}"
            assert resolved == socket_path.resolve()
    finally:
        socket_path.unlink(missing_ok=True)

    for invalid in (
        "tcp://127.0.0.1:2375",
        "ssh://runner@example.test",
        "unix://relative.sock",
        f"unix://{socket_path}?tls=1",
    ):
        with pytest.raises((MODULE.ConnectionError, FileNotFoundError)):
            MODULE._canonical_host(invalid)


def test_cli_must_be_executable_and_inside_tool_cache(tmp_path: Path) -> None:
    tool_cache = tmp_path / "tool-cache"
    tool_cache.mkdir()
    cli = tool_cache / "docker"
    cli.write_text("#!/bin/sh\n", encoding="utf-8")
    cli.chmod(0o755)
    assert MODULE._canonical_cli(str(cli), str(tool_cache)) == cli.resolve()

    outside = tmp_path / "docker"
    outside.write_text("#!/bin/sh\n", encoding="utf-8")
    outside.chmod(0o755)
    with pytest.raises(MODULE.ConnectionError, match="outside"):
        MODULE._canonical_cli(str(outside), str(tool_cache))


def _capture_args(tmp_path: Path, *, role: str = "runner", store: str = "classic") -> SimpleNamespace:
    root = tmp_path / "docker-root"
    root.mkdir(exist_ok=True)
    return SimpleNamespace(
        docker_host="unix:///tmp/docker.sock",
        docker_cli="/tool/docker",
        tool_cache_root="/tool",
        expected_store=store,
        expected_root=str(root),
        role=role,
    )


def _fake_run_factory(
    tmp_path: Path,
    *,
    version: str = "29.6.1",
    containerd: bool = False,
    status: object | None = None,
    compose_version: str = "v2.40.3",
    buildx_version: str = "github.com/docker/buildx v0.30.1 abcdef0",
    builder_driver: str = "docker",
    builder_endpoint: str = "default",
):
    def fake_run(_cli: Path, _host: str, _role: str, *arguments: str) -> str:
        command = arguments[:2]
        if command == ("version", "--format"):
            return json.dumps(
                {
                    "Client": {"Version": version, "ApiVersion": "1.53", "GitCommit": "abc"},
                    "Server": {"Version": version, "ApiVersion": "1.53", "GitCommit": "def"},
                }
            )
        if command == ("info", "--format"):
            return json.dumps(
                {
                    "ID": "daemon",
                    "DockerRootDir": str(tmp_path / "docker-root"),
                    "Driver": "overlayfs" if containerd else "overlay2",
                    "DriverStatus": status
                    if status is not None
                    else (CONTAINERD_STATUS if containerd else CLASSIC_STATUS),
                }
            )
        if arguments[:3] == ("compose", "version", "--short"):
            return compose_version
        if command == ("buildx", "version"):
            return buildx_version
        if command == ("buildx", "inspect"):
            return (
                f"Name: default\nDriver: {builder_driver}\nNodes:\n"
                f"Name: default\nEndpoint: {builder_endpoint}\n"
            )
        if command == ("context", "show"):
            return "default"
        raise AssertionError(arguments)

    return fake_run


def test_capture_rejects_version_store_and_environment_drift(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(MODULE, "_python_identity", lambda: ((3, 12), "3.12.3", "/usr/bin/python3.12"))
    monkeypatch.setattr(MODULE, "_canonical_host", lambda _host: ("unix:///tmp/docker.sock", Path("/tmp/docker.sock")))
    monkeypatch.setattr(MODULE, "_canonical_cli", lambda *_args: Path("/tool/docker"))
    monkeypatch.setenv("DOCKER_HOST", "unix:///tmp/docker.sock")

    monkeypatch.setattr(MODULE, "_run", _fake_run_factory(tmp_path, version="29.6.0"))
    with pytest.raises(MODULE.ConnectionError, match="29.6.1"):
        MODULE.capture(_capture_args(tmp_path))

    monkeypatch.setattr(MODULE, "_run", _fake_run_factory(tmp_path, containerd=True))
    with pytest.raises(MODULE.ConnectionError, match="classic"):
        MODULE.capture(_capture_args(tmp_path))

    monkeypatch.setenv("DOCKER_CONTEXT", "unexpected")
    with pytest.raises(MODULE.ConnectionError, match="DOCKER_CONTEXT"):
        MODULE.capture(_capture_args(tmp_path))


@pytest.mark.parametrize("store", ["classic", "containerd"])
def test_capture_retains_exact_successful_store_and_plugin_identity(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, store: str
) -> None:
    cli = tmp_path / "tool" / "docker"
    cli.parent.mkdir()
    cli.write_text("docker-cli", encoding="utf-8")
    monkeypatch.setattr(MODULE, "_python_identity", lambda: ((3, 12), "3.12.3", "/usr/bin/python3.12"))
    monkeypatch.setattr(MODULE, "_canonical_host", lambda _host: ("unix:///tmp/docker.sock", Path("/tmp/docker.sock")))
    monkeypatch.setattr(MODULE, "_canonical_cli", lambda *_args: cli)
    monkeypatch.setenv("DOCKER_HOST", "unix:///tmp/docker.sock")
    monkeypatch.setattr(
        MODULE,
        "_run",
        _fake_run_factory(tmp_path, containerd=store == "containerd"),
    )

    result = MODULE.capture(_capture_args(tmp_path, store=store))

    assert result["storage_driver"] == ("overlayfs" if store == "containerd" else "overlay2")
    assert result["driver_status"] == (
        CONTAINERD_STATUS if store == "containerd" else CLASSIC_STATUS
    )
    assert result["compose_version"] == "v2.40.3"
    assert result["buildx_version"] == "github.com/docker/buildx v0.30.1 abcdef0"


def test_compose_version_contract_accepts_a_bounded_future_numeric_major() -> None:
    assert MODULE._bounded_version(
        "v3.0.0", MODULE.COMPOSE_VERSION_RE, "Compose plugin", 128
    ) == "v3.0.0"


@pytest.mark.parametrize(
    ("store", "containerd", "status", "message"),
    [
        ("classic", False, None, "malformed DriverStatus"),
        ("classic", False, [["Backing Filesystem", "extfs"]], "classic store"),
        (
            "classic",
            False,
            [*CLASSIC_STATUS[:-1], ["unexpected", "false"]],
            "keys or order",
        ),
        (
            "classic",
            False,
            [CLASSIC_STATUS[0], ["Supports d_type", "yes"], *CLASSIC_STATUS[2:]],
            "boolean",
        ),
        (
            "containerd",
            True,
            [["unrelated", "io.containerd.snapshotter.v1"]],
            "containerd store",
        ),
    ],
)
def test_capture_rejects_missing_or_unexpected_exact_driver_status(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    store: str,
    containerd: bool,
    status: object,
    message: str,
) -> None:
    monkeypatch.setattr(MODULE, "_python_identity", lambda: ((3, 12), "3.12.3", "/usr/bin/python3.12"))
    monkeypatch.setattr(MODULE, "_canonical_host", lambda _host: ("unix:///tmp/docker.sock", Path("/tmp/docker.sock")))
    monkeypatch.setattr(MODULE, "_canonical_cli", lambda *_args: Path("/tool/docker"))
    monkeypatch.setenv("DOCKER_HOST", "unix:///tmp/docker.sock")
    fake = _fake_run_factory(tmp_path, containerd=containerd, status=status)
    if status is None:
        def missing_status(cli: Path, host: str, role: str, *arguments: str) -> str:
            value = fake(cli, host, role, *arguments)
            if arguments[:2] == ("info", "--format"):
                payload = json.loads(value)
                payload["DriverStatus"] = None
                return json.dumps(payload)
            return value
        monkeypatch.setattr(MODULE, "_run", missing_status)
    else:
        monkeypatch.setattr(MODULE, "_run", fake)

    with pytest.raises(MODULE.ConnectionError, match=message):
        MODULE.capture(_capture_args(tmp_path, store=store))


@pytest.mark.parametrize(
    ("overrides", "message"),
    [
        ({"compose_version": ""}, "Compose plugin"),
        ({"compose_version": "Docker Compose version unknown"}, "Compose plugin"),
        ({"buildx_version": "v0.30.1"}, "Buildx plugin"),
        ({"builder_driver": "docker-container"}, "local docker driver"),
        ({"builder_endpoint": "unix:///tmp/other.sock"}, "reviewed socket"),
    ],
)
def test_capture_rejects_tooling_and_endpoint_drift(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    overrides: dict[str, str],
    message: str,
) -> None:
    cli = tmp_path / "tool" / "docker"
    cli.parent.mkdir()
    cli.write_text("docker-cli", encoding="utf-8")
    monkeypatch.setattr(MODULE, "_python_identity", lambda: ((3, 12), "3.12.3", "/usr/bin/python3.12"))
    monkeypatch.setattr(MODULE, "_canonical_host", lambda host: (host, Path(host.removeprefix("unix://"))))
    monkeypatch.setattr(MODULE, "_canonical_cli", lambda *_args: cli)
    monkeypatch.setenv("DOCKER_HOST", "unix:///tmp/docker.sock")
    monkeypatch.setattr(MODULE, "_run", _fake_run_factory(tmp_path, **overrides))

    with pytest.raises(MODULE.ConnectionError, match=message):
        MODULE.capture(_capture_args(tmp_path))


def test_runner_commands_keep_ordinary_docker_configuration(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    cli = tmp_path / "docker"
    cli.write_text("binary", encoding="utf-8")
    monkeypatch.setenv("DOCKER_HOST", "unix:///tmp/docker.sock")
    monkeypatch.setenv("DOCKER_CONFIG", "/ordinary/config")
    monkeypatch.setenv("BUILDX_BUILDER", "ordinary-builder")
    observed: dict[str, str] = {}

    def fake_subprocess(*_args: object, **kwargs: object) -> SimpleNamespace:
        observed.update(kwargs["env"])
        return SimpleNamespace(returncode=0, stdout="ok\n", stderr="")

    monkeypatch.setattr(MODULE.subprocess, "run", fake_subprocess)

    assert MODULE._run(cli, "unix:///tmp/docker.sock", "runner", "info") == "ok"
    assert observed["DOCKER_CONFIG"] == "/ordinary/config"
    assert observed["BUILDX_BUILDER"] == "ordinary-builder"


def test_capture_requires_python312_before_docker_commands(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(MODULE, "_python_identity", lambda: ((3, 13), "3.13.7", "/usr/bin/python3.13"))
    monkeypatch.setattr(
        MODULE,
        "_run",
        lambda *_args: (_ for _ in ()).throw(AssertionError("docker must not run")),
    )

    with pytest.raises(MODULE.ConnectionError, match="Python 3.12"):
        MODULE.capture(_capture_args(tmp_path))


def test_composite_gates_every_scanner_on_verified_connection_and_hardened_scan() -> None:
    action = (
        ROOT / ".github" / "actions" / "scan-production-images" / "action.yml"
    ).read_text(encoding="utf-8")
    scanner_steps = action.split("    - name: Generate backend image SBOM", 1)[1]
    connection_guard = (
        "if: ${{ !cancelled() && steps.docker-connection.outcome == 'success' }}"
    )
    hardened_guard = (
        "if: ${{ !cancelled() && steps.hardened-scan.outcome == 'success' }}"
    )
    assert action.count(connection_guard) == 7
    guarded_steps = {
        "Select the independently verified backend fix statement",
        "Select separately verified service execution-path statements",
        "Scan backend image", "Scan frontend image", "Scan Caddy image",
        "Scan PostgreSQL image", "Scan Redis image", "Validate exact zlib evidence",
    }
    steps = {block.splitlines()[0]: block for block in action.split("    - name: ")[1:]}
    assert action.count(hardened_guard) == len(guarded_steps) == 8
    assert {name for name, block in steps.items() if hardened_guard in block} == guarded_steps
    assert all(steps[name].count(hardened_guard) == 1 for name in guarded_steps)

    services = {
        "backend": "backend",
        "frontend": "frontend",
        "Caddy": "caddy",
        "PostgreSQL": "postgres",
        "Redis": "redis",
    }
    for display_name, input_name in services.items():
        sbom = action.split(f"    - name: Generate {display_name} image SBOM", 1)[
            1
        ].split("    - name:", 1)[0]
        scan = action.split(f"    - name: Scan {display_name} image", 1)[1].split(
            "    - name:", 1
        )[0]
        assert sbom.count(connection_guard) == 1
        assert f"image: ${{{{ inputs.{input_name}-image }}}}" in sbom
        assert scan.count(hardened_guard) == 1
        assert f"image: ${{{{ inputs.{input_name}-image }}}}" in scan
        vex_input = (
            f"vex: ${{{{ runner.temp }}}}/container-security/"
            f"{input_name}-zlib.openvex.json"
        )
        assert vex_input not in scan
        vex_lines = [line.strip() for line in scan.splitlines() if line.lstrip().startswith("vex:")]
        expected_vex = {
            "backend": "vex: ${{ steps.backend-hashlib-vex.outputs.scan_path }}",
            "frontend": "vex: ${{ steps.service-openssl-vex.outputs.frontend_path }}",
            "caddy": "vex: ${{ steps.service-openssl-vex.outputs.caddy_path }}",
            "postgres": "vex: ${{ steps.service-openssl-vex.outputs.postgres_path }}",
        }
        assert vex_lines == ([expected_vex[input_name]] if input_name in expected_vex else [])

    hardened_scan = action.split(
        "    - name: Exercise hardened archive scanner on all candidate images", 1
    )[1].split("    # Keep SPDX SBOMs", 1)[0]
    assert hardened_scan.count(connection_guard) == 1
    assert "id: hardened-scan" in hardened_scan

    verified_fix = steps["Select the independently verified backend fix statement"]
    assert verified_fix.count(hardened_guard) == 1
    assert "id: backend-hashlib-vex" in verified_fix
    evidence_checks = (
        'sha256sum -c -',
        'validate_evidence_hash runtime_proof "$evidence_dir/backend-hashlib-runtime.json"',
        'validate_evidence_hash raw_grype "$evidence_dir/backend-grype-unfiltered.json"',
        'validate_evidence_hash vex "$vex"',
        '>> "$GITHUB_OUTPUT"',
    )
    checks = [verified_fix.index(check) for check in evidence_checks]
    assert checks == sorted(checks)

    verified_services = steps["Select separately verified service execution-path statements"]
    assert "id: service-openssl-vex" in verified_services
    assert "for service in caddy frontend postgres; do" in verified_services
    assert "runtime_proof:openssl-runtime.json raw_grype:grype-unfiltered.json vex:openssl-reviewed.openvex.json" in verified_services
    assert verified_services.index("sha256sum -c -") < verified_services.index('>> "$GITHUB_OUTPUT"')
    assert 'validate_evidence_hash assessed_grype "$evidence_dir/backend-grype-openssl-filtered.json"' in verified_fix
    assert 'combined_vex:backend-reviewed.openvex.json' in verified_fix

    zlib_validation = action.split(
        "    - name: Validate exact zlib evidence", 1
    )[1].split("    - name: Retain container security evidence", 1)[0]
    assert zlib_validation.count(hardened_guard) == 1
    assert "services=(backend frontend caddy postgres redis)" in zlib_validation
    assert action.count("vex: ${{ runner.temp }}/container-security/") == 0
    assert "-zlib.openvex.json" not in action
    assert '"$service=$evidence_dir/$service-grype.json"' in zlib_validation
    assert '"${#archive_platforms[@]}" -ne 1' in zlib_validation
    assert '"${archive_platforms[0]}" != linux/amd64' in zlib_validation
    assert '[ "$service" = backend ] && [ -n "$VERIFIED_HASHLIB_VEX" ]' in zlib_validation
    assert (
        'python3 "$verifier" \\\n'
        '            "--$service-fixed-sbom" "$evidence_dir/$service.syft.json" \\\n'
        '            "$image" "${config_ids[0]}" \\\n'
        '            "$service=$evidence_dir/$service-grype.json" \\\n'
        '            "--runtime-proof=$evidence_dir/$service-zlib-runtime.json" \\\n'
        '            "--archive-platform=${archive_platforms[0]}@${config_ids[0]}" "${hashlib_vex[@]}" "${openssl_vex[@]}" "${openssl_vendor[@]}" >> "$validation"'
        in zlib_validation
    )
    for service in ("caddy", "frontend", "postgres"):
        assert f"{service}) service_vex=$VERIFIED_{service.upper()}_VEX ;;" in zlib_validation
    assert 'openssl_vex=("--openssl-assessment=$service_vex")' in zlib_validation
    assert 'openssl_vex=("--openssl-assessment=$VERIFIED_OPENSSL_VEX")' in zlib_validation
    assert "sudo -E" not in action
    assert '"DOCKER_HOST=$EXPECTED_DOCKER_HOST"' in action
    assert "/var/run/docker.sock" not in action
    assert scanner_steps.count("continue-on-error") == 0


def test_vendor_fixed_evidence_is_retained_and_selected_only_after_its_hash_checks():
    action = (ROOT / ".github/actions/scan-production-images/action.yml").read_text()
    for service in ("backend", "frontend", "caddy", "postgres"):
        for suffix in ("grype-vendor-unfiltered.json", "openssl-vendor-runtime.raw.json",
                       "openssl-vendor-runtime.json", "openssl-vendor-fixed.openvex.json"):
            assert action.count(service + "-" + suffix) >= 2
    assert action.count("backend-vendor-reviewed.openvex.json") >= 3
    assert 'validate_vendor_hashes backend' in action
    assert 'validate_vendor_hashes "$service"' in action
    assert 'backend_openssl_vendor_combined_vex_sha256=' in action
    assert 'openssl_vendor=("--openssl-vendor-fixed=$VERIFIED_VENDOR_BACKEND_VEX")' in action
    assert 'openssl_vendor=("--openssl-vendor-fixed=$service_vex")' in action
    assert '[ ! -e "$vex" ] && [ ! -L "$vex" ]' in action
    assert '[ "$service" = caddy ] || [ "$service" = frontend ] || [ "$service" = postgres ]' in action
    assert '"DCOMPANY_OPENSSL_BACKPORT_CONTEXT=$backport_scanner_context"' in action
    assert 'openssl-backport-export' in action
    assert '"$archive" "$evidence_dir/$package-3.5.9-r1.apk"' in action
    assert 'sudo rmdir "$scanner_evidence/openssl-vendor-apks"' in action
