from __future__ import annotations

import fcntl
import hashlib
import importlib.util
import os
import re
import shutil
import stat
import subprocess
import tarfile
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]
INSTALLER = ROOT / "infra" / "scripts" / "install-on-vm.sh"
LOCK_HELPER = ROOT / "infra" / "scripts" / "production_install_lock.py"
HARDENED_SCANNER = ROOT / "infra" / "scripts" / "run-hardened-image-scanners.sh"


def _load_lock_helper():
    spec = importlib.util.spec_from_file_location("production_install_lock", LOCK_HELPER)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_candidate_build_uses_a_private_immutable_git_archive() -> None:
    source = INSTALLER.read_text(encoding="utf-8")

    archive = 'git archive --format=tar "$CURRENT_REVISION"'
    extract = 'tar -xf "$CANDIDATE_SOURCE_ARCHIVE" -C "$CANDIDATE_BUILD_ROOT"'
    reexec = 'exec /bin/bash "$CANDIDATE_BUILD_ROOT/infra/scripts/install-on-vm.sh"'
    docker_step = "# ----- 1. Docker -----"
    assert source.index(archive) < source.index(extract) < source.index(reexec)
    assert source.index(reexec) < source.index(docker_step)
    assert '--project-directory "$CANDIDATE_BUILD_ROOT"' in source
    assert '-f "$RELEASE_COMPOSE_FILE"' in source
    assert 'for candidate_service in caddy postgres backend frontend; do' in source
    build_command = (
        'COMPOSE_PARALLEL_LIMIT=1 "${candidate_compose[@]}" '
        + "\\\n"
        + '    --env-file "$ENV_CANDIDATE" build "$candidate_service"'
    )
    assert build_command in source
    assert 'docker compose -f docker-compose.prod.yml --env-file "$ENV_CANDIDATE" build' not in source
    assert "BUILD_SNAPSHOT_ROOT=/var/lib/dcompany-erp/build-snapshots" in source
    assert "candidate_source_archive_sha256=%s" in source


def test_exact_candidate_images_are_scanned_before_maintenance() -> None:
    source = INSTALLER.read_text(encoding="utf-8")
    scanner_source = HARDENED_SCANNER.read_text(encoding="utf-8")
    invocation = 'bash "$HARDENED_SCANNER_TOOL"'
    source = source.replace(invocation, scanner_source + "\n" + invocation)
    assert '600s \\\n  docker pull "$SYFT_IMAGE"' in source
    assert '600s \\\n  docker pull "$GRYPE_IMAGE"' in source

    attestation = (
        'CANDIDATE_IMAGE_ATTESTATION=$(python3 "$CANDIDATE_PARITY_TOOL" candidate'
    )
    immutable_save = 'docker image save "$candidate_image_id" --output "$image_archive"'
    syft = '"$SYFT_IMAGE" "/scan/image.tar" --from docker-archive --output syft-json'
    grype = '"$GRYPE_IMAGE" "docker-archive:/scan/image.tar"'
    maintenance = 'echo "==> Entering scheduled maintenance and draining application writers…"'
    assert source.index(attestation) < source.index(immutable_save) < source.index(syft)
    assert source.index(syft) < source.index(grype)
    assert source.index(grype) < source.index(maintenance)
    assert "SECURITY_EVIDENCE_ROOT=/var/lib/dcompany-erp/container-security" in source
    assert re.search(r"SYFT_IMAGE='anchore/syft:v1\.42\.3@sha256:[0-9a-f]{64}'", source)
    assert re.search(r"GRYPE_IMAGE='anchore/grype:v0\.118\.0@sha256:[0-9a-f]{64}'", source)
    assert "--fail-on high --output json" in source
    assert 'report.get("descriptor")' in source
    assert 'status.get("built")' in source
    assert "has no vulnerability DB build identity" in source
    assert "/var/run/docker.sock" not in source
    assert source.count(
        'docker image inspect --format \'{{.Id}}\' "$candidate_image_ref"'
    ) >= 2
    assert "--network none --read-only --cap-drop ALL" in source
    assert source.count("--security-opt no-new-privileges") >= 2


def test_git_archive_excludes_ignored_bytes_and_freezes_tracked_bytes(tmp_path: Path) -> None:
    subprocess.run(["git", "init", "-q", str(tmp_path)], check=True)
    subprocess.run(["git", "-C", str(tmp_path), "config", "user.email", "test@example.invalid"], check=True)
    subprocess.run(["git", "-C", str(tmp_path), "config", "user.name", "Release Test"], check=True)
    (tmp_path / ".gitignore").write_text("ignored.py\n", encoding="utf-8")
    tracked = tmp_path / "tracked.py"
    tracked.write_text("reviewed = True\n", encoding="utf-8")
    subprocess.run(["git", "-C", str(tmp_path), "add", ".gitignore", "tracked.py"], check=True)
    subprocess.run(["git", "-C", str(tmp_path), "commit", "-qm", "reviewed"], check=True)

    tracked.write_text("reviewed = False\n", encoding="utf-8")
    (tmp_path / "ignored.py").write_text("unreviewed = True\n", encoding="utf-8")
    archive = tmp_path / "source.tar"
    with archive.open("wb") as stream:
        subprocess.run(
            ["git", "-C", str(tmp_path), "archive", "--format=tar", "HEAD"],
            check=True,
            stdout=stream,
        )

    with tarfile.open(archive) as package:
        names = package.getnames()
        assert "ignored.py" not in names
        extracted = package.extractfile("tracked.py")
        assert extracted is not None
        assert extracted.read() == b"reviewed = True\n"


def test_checkout_mutation_after_snapshot_cannot_change_deployment_commands(
    tmp_path: Path,
) -> None:
    repo = tmp_path / "repo"
    snapshot = tmp_path / "snapshot"
    repo.mkdir()
    snapshot.mkdir()
    subprocess.run(["git", "init", "-q", str(repo)], check=True)
    subprocess.run(
        ["git", "-C", str(repo), "config", "user.email", "test@example.invalid"],
        check=True,
    )
    subprocess.run(
        ["git", "-C", str(repo), "config", "user.name", "Release Test"],
        check=True,
    )
    reviewed_inputs = {
        "docker-compose.prod.yml": "services:\n  backend:\n    image: reviewed\n",
        "infra/scripts/prepare-production-env.sh": "#!/bin/bash\nprintf reviewed-env\\n\n",
        "infra/scripts/check-upgrade-capacity.sh": "#!/bin/bash\nprintf reviewed-capacity\\n\n",
        "ops/runtime_release_parity.py": "print('reviewed-parity')\n",
    }
    for name, content in reviewed_inputs.items():
        path = repo / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
    subprocess.run(["git", "-C", str(repo), "add", "."], check=True)
    subprocess.run(["git", "-C", str(repo), "commit", "-qm", "reviewed"], check=True)

    archive = tmp_path / "source.tar"
    with archive.open("wb") as stream:
        subprocess.run(
            ["git", "-C", str(repo), "archive", "--format=tar", "HEAD"],
            check=True,
            stdout=stream,
        )
    with tarfile.open(archive) as package:
        package.extractall(snapshot, filter="data")

    for name in reviewed_inputs:
        (repo / name).write_text("attacker-controlled\n", encoding="utf-8")

    for name, reviewed in reviewed_inputs.items():
        assert (snapshot / name).read_text(encoding="utf-8") == reviewed
        assert (repo / name).read_text(encoding="utf-8") != reviewed


def test_lock_and_rollback_paths_are_root_private_and_fail_closed() -> None:
    source = INSTALLER.read_text(encoding="utf-8")
    helper_source = LOCK_HELPER.read_text(encoding="utf-8")

    assert 'RUNTIME_PARENT = Path("/run")' in helper_source
    assert 'STATE_PARENT = Path("/var/lib")' in helper_source
    assert 'STATE_DIRECTORY_NAME = "dcompany-erp"' in helper_source
    assert "ensure_private_state_directories()" in helper_source
    assert "production_install_lock.py" in source
    assert 'exec 9<>"$LOCK_FILE"' not in source
    assert "O_NOFOLLOW" in helper_source
    assert source.index("lock_fd_metadata=") < source.index("flock -n 9")
    assert "ROLLBACK_ROOT=/var/lib/dcompany-erp/deployment-rollbacks" in source


def test_lock_helper_rejects_symlink_fifo_and_hardlink_without_truncation(
    tmp_path: Path,
) -> None:
    helper = _load_lock_helper()
    runtime = tmp_path / "runtime"
    runtime.mkdir(mode=0o700)
    lock = runtime / "production-install.lock"
    victim = tmp_path / "victim"
    victim.write_bytes(b"must-survive")
    victim.chmod(0o600)
    arguments = {
        "runtime_parent": tmp_path,
        "runtime_directory_name": runtime.name,
        "lock_file_name": lock.name,
        "expected_uid": os.geteuid(),
        "expected_gid": os.getegid(),
    }

    lock.symlink_to(victim)
    with pytest.raises(helper.ProductionInstallLockError):
        helper.acquire_lock(**arguments)
    assert victim.read_bytes() == b"must-survive"

    lock.unlink()
    os.mkfifo(lock, 0o600)
    with pytest.raises(helper.ProductionInstallLockError):
        helper.acquire_lock(**arguments)

    lock.unlink()
    os.link(victim, lock)
    with pytest.raises(helper.ProductionInstallLockError):
        helper.acquire_lock(**arguments)
    assert victim.read_bytes() == b"must-survive"


def test_lock_helper_holds_nonblocking_exclusive_descriptor(tmp_path: Path) -> None:
    helper = _load_lock_helper()
    runtime = tmp_path / "runtime"
    arguments = {
        "runtime_parent": tmp_path,
        "runtime_directory_name": runtime.name,
        "expected_uid": os.geteuid(),
        "expected_gid": os.getegid(),
    }
    descriptor, metadata = helper.acquire_lock(**arguments)
    try:
        assert stat.S_ISREG(metadata.st_mode)
        probe = os.open(runtime / helper.LOCK_FILE_NAME, os.O_RDWR)
        try:
            with pytest.raises(BlockingIOError):
                fcntl.flock(probe, fcntl.LOCK_EX | fcntl.LOCK_NB)
        finally:
            os.close(probe)
    finally:
        os.close(descriptor)


def test_lock_metadata_rejects_wrong_owner_or_mode_without_truncation(
    tmp_path: Path,
) -> None:
    helper = _load_lock_helper()
    lock = tmp_path / "lock"
    lock.write_bytes(b"must-survive")
    lock.chmod(0o600)
    descriptor = os.open(lock, os.O_RDWR)
    try:
        with pytest.raises(helper.ProductionInstallLockError):
            helper._validate_lock_file(
                descriptor,
                expected_uid=os.geteuid() + 1,
                expected_gid=os.getegid(),
            )
        lock.chmod(0o640)
        with pytest.raises(helper.ProductionInstallLockError):
            helper._validate_lock_file(
                descriptor,
                expected_uid=os.geteuid(),
                expected_gid=os.getegid(),
            )
    finally:
        os.close(descriptor)
    assert lock.read_bytes() == b"must-survive"


def test_state_helper_rejects_links_and_unsafe_modes_then_creates_private_tree(
    tmp_path: Path,
) -> None:
    helper = _load_lock_helper()
    parent = tmp_path / "var-lib"
    parent.mkdir(mode=0o755)
    state = parent / "state"
    outside = tmp_path / "outside"
    outside.mkdir(mode=0o700)
    arguments = {
        "state_parent": parent,
        "state_directory_name": state.name,
        "state_subdirectory_names": ("rollbacks", "builds", "evidence"),
        "expected_uid": os.geteuid(),
        "expected_gid": os.getegid(),
    }

    state.symlink_to(outside, target_is_directory=True)
    with pytest.raises(helper.ProductionInstallLockError):
        helper.ensure_private_state_directories(**arguments)
    assert not (outside / "rollbacks").exists()

    state.unlink()
    state.mkdir(mode=0o755)
    with pytest.raises(helper.ProductionInstallLockError):
        helper.ensure_private_state_directories(**arguments)

    state.rmdir()
    helper.ensure_private_state_directories(**arguments)
    assert stat.S_IMODE(state.stat().st_mode) == 0o700
    for name in arguments["state_subdirectory_names"]:
        path = state / name
        assert path.is_dir()
        assert not path.is_symlink()
        assert stat.S_IMODE(path.stat().st_mode) == 0o700


def test_each_compose_service_has_one_container_and_immutable_rollback_image() -> None:
    source = INSTALLER.read_text(encoding="utf-8")

    unique = 'if [ "${#SERVICE_IDS[@]}" -ne 1 ]; then'
    first_tag = 'docker image tag "$image_id" "$rollback_ref"'
    assert source.index(unique) < source.index(first_tag)
    assert 'rollback_ref="dcompany-rollback:${SNAPSHOT_ID}-${service}-${image_short}"' in source
    assert 'PRIOR_IMAGE_BY_ORIGINAL_REF[$original_ref]' in source
    assert 'docker image inspect "$rollback_ref"' in source
    assert 'PRIOR_SERVICE_ORIGINAL_REFS[$service]=$original_ref' in source
    assert 'PRIOR_SERVICE_ROLLBACK_REFS[$service]=$rollback_ref' in source
    assert 'docker image tag "$prior_image_id" "$prior_original_ref"' in source
    assert "restore-images.sh" not in source
    assert 'if [ "$restored_image_id" != "${PRIOR_SERVICE_IMAGE_IDS[$service]}" ]; then' in source
    assert 'ROLLBACK_STATE_MANIFEST_DIGEST=$(sha256sum "$ROLLBACK_STATE_MANIFEST"' in source


def test_candidate_attestation_tool_and_compose_are_from_the_commit_snapshot() -> None:
    source = INSTALLER.read_text(encoding="utf-8")

    tool = 'CANDIDATE_PARITY_TOOL="$CANDIDATE_BUILD_ROOT/ops/runtime_release_parity.py"'
    attestation = 'python3 "$CANDIDATE_PARITY_TOOL" candidate'
    snapshot_root = '--root "$CANDIDATE_BUILD_ROOT" --env-file "$ENV_CANDIDATE"'
    final_parity = 'python3 "$CANDIDATE_PARITY_TOOL" running'
    assert source.index(tool) < source.index(attestation) < source.index(snapshot_root)
    assert source.count(final_parity) == 2
    cleanup = 'rm -rf "$CANDIDATE_BUILD_ROOT"'
    assert source.count(cleanup) == 1
    assert source.index(cleanup) < source.index(
        'exec /bin/bash "$CANDIDATE_BUILD_ROOT/infra/scripts/install-on-vm.sh"'
    )
    assert source.count('"${candidate_compose[@]}"') >= 10
    assert source.count('--project-name "$DEPLOY_PROJECT_NAME"') == 3
    assert "up -d --no-build --pull never postgres redis backend frontend" in source
    assert "up -d --no-build --pull never caddy" in source

    operational_source = source.split("cat <<EOF", maxsplit=1)[0]
    executable_relative_script = re.compile(
        r"^(?!\s*(?:#|echo)).*bash infra/scripts/",
        re.MULTILINE,
    )
    assert executable_relative_script.search(operational_source) is None
    executable_relative_compose = re.compile(
        r"^(?!\s*(?:#|echo)).*docker compose -f docker-compose\.prod\.yml",
        re.MULTILINE,
    )
    assert executable_relative_compose.search(operational_source) is None
    assert 'bash "$PREPARE_ENV_TOOL"' in operational_source
    assert 'bash "$CAPACITY_CHECK_TOOL"' in operational_source
    assert '--project-directory "$PRIOR_SOURCE_ROOT"' in operational_source


def test_digest_pinned_redis_is_present_and_attested_before_cutover() -> None:
    source = INSTALLER.read_text(encoding="utf-8")

    frozen_config = (
        '"${candidate_compose[@]}" --env-file "$ENV_CANDIDATE" '
        "config --format json"
    )
    digest_guard = (
        '[[ "$CANDIDATE_REDIS_IMAGE_REF" =~ '
        '^redis:7-alpine@sha256:[0-9a-f]{64}$ ]]'
    )
    pull = 'docker pull "$CANDIDATE_REDIS_IMAGE_REF"'
    image_id = (
        "CANDIDATE_REDIS_IMAGE_ID=$(docker image inspect --format '{{.Id}}'"
    )
    pre_maintenance_recheck = (
        '"$CANDIDATE_REDIS_IMAGE_REF")" != "$CANDIDATE_REDIS_IMAGE_ID"'
    )
    maintenance = 'echo "==> Entering scheduled maintenance and draining application writers…"'
    cutover = "up -d --no-build --pull never postgres redis backend frontend"
    running_ids = (
        'done < <("${candidate_compose[@]}" --env-file .env ps -q redis)'
    )
    running_image = "docker inspect --format '{{.Image}}'"

    assert frozen_config in source
    assert digest_guard in source
    assert source.index(frozen_config) < source.index(digest_guard)
    assert source.index(digest_guard) < source.index(pull) < source.index(image_id)
    assert source.index(image_id) < source.index(pre_maintenance_recheck)
    assert source.index(pre_maintenance_recheck) < source.index(maintenance)
    assert source.index(maintenance) < source.index(cutover)
    assert source.index(cutover) < source.index(running_ids)
    assert source.index(running_ids) < source.rindex(running_image)
    assert r"redis_image_ref=%s\nredis_image_id=%s" in source


def test_rollback_keeps_ingress_closed_until_core_services_are_ready_and_attested() -> None:
    source = INSTALLER.read_text(encoding="utf-8")

    postgres_restore = (
        '"${prior_compose[@]}" up -d --no-build --pull never \\\n'
        '        --force-recreate postgres'
    )
    core_restore = (
        '"${prior_compose[@]}" up -d --no-build --pull never --force-recreate \\\n'
        '          "${PRIOR_NON_POSTGRES_SERVICES[@]}"'
    )
    skip_caddy = '[ "$service" = caddy ] && continue'
    backend_ready = 'until "${prior_compose[@]}" exec -T backend python -c'
    start_caddy = (
        '"${prior_compose[@]}" up -d --no-build --pull never \\\n'
        '        --force-recreate caddy'
    )
    attest_caddy = (
        'if [ "$restored_caddy_image_id" != "${PRIOR_SERVICE_IMAGE_IDS[caddy]:-}" ]; then'
    )

    assert source.index(postgres_restore) < source.index(core_restore)
    assert source.index(core_restore) < source.index(skip_caddy)
    assert source.index(skip_caddy) < source.index(backend_ready)
    assert source.index(backend_ready) < source.index(start_caddy)
    assert source.index(start_caddy) < source.index(attest_caddy)


def test_rollback_restores_prior_postgres_before_using_the_database_backup() -> None:
    source = INSTALLER.read_text(encoding="utf-8")
    rollback = source[
        source.index("handle_install_failure() {") : source.index(
            "handle_post_ingress_failure() {"
        )
    ]

    verify_state = 'sha256sum -c rollback-state.sha256'
    restore_environment = (
        'install -m 0600 "$UPGRADE_SNAPSHOT/.env" '
        '"$REPO_DIR/.env.rollback.$$"'
    )
    restore_images = 'rollback_image_id=$(docker image inspect --format \'{{.Id}}\''
    start_prior_postgres = (
        '"${prior_compose[@]}" up -d --no-build --pull never \\\n'
        '        --force-recreate postgres'
    )
    attest_prior_postgres = (
        'if [ "$restored_postgres_image_id" != '
        '"${PRIOR_SERVICE_IMAGE_IDS[postgres]}" ]; then'
    )
    wait_prior_postgres = (
        'until docker exec "$rollback_postgres" pg_isready -U erp -d postgres'
    )
    restore_database = (
        'docker exec -i "$rollback_postgres" pg_restore -U erp -d erp'
    )
    start_other_services = 'PRIOR_NON_POSTGRES_SERVICES=()'

    assert rollback.index(verify_state) < rollback.index(restore_environment)
    assert rollback.index(restore_environment) < rollback.index(restore_images)
    assert rollback.index(restore_images) < rollback.index(start_prior_postgres)
    assert rollback.index(start_prior_postgres) < rollback.index(attest_prior_postgres)
    assert rollback.index(attest_prior_postgres) < rollback.index(wait_prior_postgres)
    assert rollback.index(wait_prior_postgres) < rollback.index(restore_database)
    assert rollback.index(restore_database) < rollback.index(start_other_services)
    assert "CURRENT_POSTGRES_IDS" not in rollback
    assert "restore-images.sh" not in rollback


def test_rollback_keeps_the_live_signed_apk_directory_across_legacy_compose() -> None:
    source = INSTALLER.read_text(encoding="utf-8")

    override = '"$UPGRADE_SNAPSHOT/docker-compose.rollback-live-data.yml"'
    live_root = 'ANDROID_RELEASE_ROOT="$REPO_DIR/releases/android"'
    bind_source = (
        'source: "${ANDROID_RELEASE_ROOT:?ANDROID_RELEASE_ROOT is required}"'
    )
    bind_target = "target: /srv/releases/android"
    manifest = (
        "sha256sum .env docker-compose.prior.yml \\\n"
        "      docker-compose.rollback-live-data.yml"
    )
    prior_compose = (
        '-f "$UPGRADE_SNAPSHOT/docker-compose.prior.yml"\n'
        '        -f "$UPGRADE_SNAPSHOT/docker-compose.rollback-live-data.yml"'
    )

    assert source.count(override) >= 3
    assert live_root in source
    assert bind_source in source
    assert bind_target in source
    assert manifest in source
    assert prior_compose in source
    assert source.index(override) < source.index(manifest)
    assert source.index(live_root) < source.index(prior_compose)


def test_existing_environment_crosses_the_frozen_source_boundary_by_absolute_path(
    tmp_path: Path,
) -> None:
    """Execute the installer's real prepare step from a separate source snapshot."""

    repository = tmp_path / "existing repository with spaces"
    frozen_root = tmp_path / "frozen source with spaces"
    repository.mkdir()
    (frozen_root / "infra" / "scripts").mkdir(parents=True)
    for relative_path in (
        Path(".env.production.example"),
        Path("infra/scripts/generate-secrets.sh"),
        Path("infra/scripts/prepare-production-env.sh"),
        Path("infra/scripts/validate-production-env.sh"),
    ):
        destination = frozen_root / relative_path
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / relative_path, destination)

    domain = "erp.example.org"
    revision = "a" * 40
    frozen_template = (frozen_root / ".env.production.example").read_text(
        encoding="utf-8"
    )
    frozen_version_match = re.search(
        r"^APP_VERSION=(\d+\.\d+\.\d+)$", frozen_template, re.MULTILINE
    )
    assert frozen_version_match is not None
    frozen_version = frozen_version_match.group(1)
    credentials = {
        "POSTGRES_PASSWORD": "p" * 48,
        "JWT_SECRET": "j" * 64,
        "REDIS_PASSWORD": "1" * 64,
        "REMOTE_ASSISTANCE_PAIRING_SECRET": "q" * 64,
        "REMOTE_ASSISTANCE_RELAY_SECRET": (
            "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE="
        ),
        "S3_SECRET_KEY": "m" * 48,
        "SEED_OWNER_PASSWORD": "source-only-secret-must-not-enter-snapshot",
    }
    existing_environment = (
        frozen_template
        .replace("CHANGE_ME_git_commit_sha", "b" * 40)
        .replace(f"APP_VERSION={frozen_version}", "APP_VERSION=3.1.14")
        .replace("CHANGE_ME.com", domain)
        .replace("CHANGE_ME_strong_random_password", credentials["POSTGRES_PASSWORD"])
        .replace("CHANGE_ME_48_char_base64_secret", credentials["JWT_SECRET"])
        .replace("CHANGE_ME_64_hex_redis_password", credentials["REDIS_PASSWORD"])
        .replace(
            "CHANGE_ME_48_char_dedicated_pairing_secret",
            credentials["REMOTE_ASSISTANCE_PAIRING_SECRET"],
        )
        .replace(
            "CHANGE_ME_32_byte_base64_relay_key",
            credentials["REMOTE_ASSISTANCE_RELAY_SECRET"],
        )
        .replace("CHANGE_ME_minio_password", credentials["S3_SECRET_KEY"])
        .replace(
            "CHANGE_ME_strong_owner_password", credentials["SEED_OWNER_PASSWORD"]
        )
    )
    source_environment = repository / ".env"
    source_environment.write_text(existing_environment, encoding="utf-8")
    source_environment.chmod(0o600)
    original_source_bytes = source_environment.read_bytes()
    original_source_mode = stat.S_IMODE(source_environment.stat().st_mode)
    assert b"APP_VERSION=3.1.14" in original_source_bytes
    assert f"APP_REVISION={'b' * 40}".encode() in original_source_bytes

    installer_source = INSTALLER.read_text(encoding="utf-8")
    prepare_call = installer_source.index('bash "$PREPARE_ENV_TOOL"')
    prepare_start = installer_source.rindex("if [ -f .env ]; then", 0, prepare_call)
    prepare_end_marker = (
        '  "$SOURCE_ENV" "$ENV_CANDIDATE" "$DOMAIN" "$CURRENT_REVISION"\n'
    )
    prepare_end = installer_source.index(prepare_end_marker, prepare_start) + len(
        prepare_end_marker
    )
    installer_prepare_step = installer_source[prepare_start:prepare_end]
    candidate_environment = repository / ".env.candidate"
    result = subprocess.run(
        ["bash", "-c", "set -euo pipefail\n" + installer_prepare_step],
        cwd=repository,
        env={
            **os.environ,
            "PREPARE_ENV_TOOL": str(
                frozen_root / "infra" / "scripts" / "prepare-production-env.sh"
            ),
            "ENV_CANDIDATE": str(candidate_environment),
            "DOMAIN": domain,
            "CURRENT_REVISION": revision,
            "REPO_DIR": str(repository),
        },
        capture_output=True,
        text=True,
    )

    assert result.returncode == 0, result.stderr
    assert source_environment.read_bytes() == original_source_bytes
    assert stat.S_IMODE(source_environment.stat().st_mode) == original_source_mode
    candidate_source = candidate_environment.read_text(encoding="utf-8")
    assert f"APP_REVISION={revision}" in candidate_source
    assert f"APP_VERSION={frozen_version}" in candidate_source
    assert stat.S_IMODE(candidate_environment.stat().st_mode) == 0o600
    candidate_lines = set(candidate_source.splitlines())
    for key, credential in credentials.items():
        assert f"{key}={credential}" in candidate_lines
        assert credential not in result.stdout
        assert credential not in result.stderr
    assert not (frozen_root / ".env").exists()
    frozen_files = [path for path in frozen_root.rglob("*") if path.is_file()]
    for credential in credentials.values():
        assert all(
            credential.encode() not in path.read_bytes() for path in frozen_files
        )

    fresh_repository = tmp_path / "fresh repository with spaces"
    fresh_repository.mkdir()
    fresh_candidate = fresh_repository / ".env.candidate"
    fresh_result = subprocess.run(
        ["bash", "-c", "set -euo pipefail\n" + installer_prepare_step],
        cwd=fresh_repository,
        env={
            **os.environ,
            "PREPARE_ENV_TOOL": str(
                frozen_root / "infra" / "scripts" / "prepare-production-env.sh"
            ),
            "ENV_CANDIDATE": str(fresh_candidate),
            "DOMAIN": domain,
            "CURRENT_REVISION": revision,
            "REPO_DIR": str(fresh_repository),
        },
        capture_output=True,
        text=True,
    )
    assert fresh_result.returncode == 0, fresh_result.stderr
    assert fresh_candidate.is_file()
    assert stat.S_IMODE(fresh_candidate.stat().st_mode) == 0o600
    assert not (fresh_repository / ".env").exists()
    assert not (frozen_root / ".env").exists()


@pytest.mark.skipif(
    not shutil.which("bash")
    or int(
        subprocess.run(
            ["bash", "-c", "printf %s \"${BASH_VERSINFO[0]}\""],
            check=True,
            capture_output=True,
            text=True,
        ).stdout
        or "0"
    )
    < 4,
    reason="installer requires Bash 4 associative arrays; exercised by Linux CI",
)
def test_candidate_postgres_start_failure_uses_attested_prior_runtime(
    tmp_path: Path,
) -> None:
    """Fault-inject a missing candidate Postgres and exercise the real trap."""

    source = INSTALLER.read_text(encoding="utf-8")
    rollback_function = source[
        source.index("handle_install_failure() {") : source.index(
            "handle_post_ingress_failure() {"
        )
    ]
    repo = tmp_path / "repo"
    snapshot = tmp_path / "snapshot"
    repo.mkdir()
    snapshot.mkdir()
    (repo / ".env").write_text("RELEASE=candidate\n", encoding="utf-8")
    state_contents = {
        ".env": "RELEASE=prior\n",
        "docker-compose.prior.yml": "services: {}\n",
        "docker-compose.rollback-live-data.yml": (
            "services:\n"
            "  caddy:\n"
            "    volumes:\n"
            "      - type: bind\n"
            "        source: ${ANDROID_RELEASE_ROOT:?}\n"
            "        target: /srv/releases/android\n"
            "        read_only: true\n"
        ),
        "revision.txt": "prior_revision=" + "1" * 40 + "\n",
        "container-images.txt": "attested prior images\n",
        "backend-source.sha256": "attested source\n",
        "backend-source.paths": "app/main.py\n",
    }
    for name, content in state_contents.items():
        (snapshot / name).write_text(content, encoding="utf-8")
    prior_archive_source = tmp_path / "prior-archive-source"
    prior_archive_source.mkdir()
    (prior_archive_source / "docker-compose.prod.yml").write_text(
        "services: {}\n",
        encoding="utf-8",
    )
    prior_source_archive = snapshot / "prior-source.tar"
    with tarfile.open(prior_source_archive, "w") as package:
        package.add(
            prior_archive_source / "docker-compose.prod.yml",
            arcname="docker-compose.prod.yml",
        )
    state_manifest = snapshot / "rollback-state.sha256"
    protected_state_names = [*state_contents, prior_source_archive.name]
    state_manifest.write_text(
        "".join(
            f"{hashlib.sha256((snapshot / name).read_bytes()).hexdigest()}  {name}\n"
            for name in protected_state_names
        ),
        encoding="utf-8",
    )
    state_manifest_digest = hashlib.sha256(state_manifest.read_bytes()).hexdigest()
    database_dump = snapshot / "database.dump"
    database_dump.write_bytes(b"verified-backup")
    database_digest = hashlib.sha256(database_dump.read_bytes()).hexdigest()
    (snapshot / "database.dump.sha256").write_text(
        f"{database_digest}  {database_dump}\n",
        encoding="utf-8",
    )
    trace = tmp_path / "trace.log"

    harness = (
        rollback_function
        + r'''
docker() {
  printf '%s\n' "$*" >> "$TRACE"
  if [ "$1" = image ] && [ "$2" = inspect ]; then
    target="${*: -1}"
    case "$target" in
      dcompany-rollback:test-postgres)
        if [ "${TAMPER_ALIAS:-}" = postgres ]; then
          printf 'sha256:%064d\n' 9
        else
          printf '%s\n' "${PRIOR_SERVICE_IMAGE_IDS[postgres]}"
        fi
        ;;
      dcompany-rollback:test-redis|dcompany-prior-redis)
        printf '%s\n' "${PRIOR_SERVICE_IMAGE_IDS[redis]}" ;;
      dcompany-rollback:test-backend|dcompany-prior-backend)
        printf '%s\n' "${PRIOR_SERVICE_IMAGE_IDS[backend]}" ;;
      dcompany-rollback:test-frontend|dcompany-prior-frontend)
        printf '%s\n' "${PRIOR_SERVICE_IMAGE_IDS[frontend]}" ;;
      dcompany-rollback:test-caddy|dcompany-prior-caddy)
        printf '%s\n' "${PRIOR_SERVICE_IMAGE_IDS[caddy]}" ;;
      dcompany-prior-postgres)
        printf '%s\n' "${PRIOR_SERVICE_IMAGE_IDS[postgres]}" ;;
      *) return 93 ;;
    esac
    return 0
  fi
  if [ "$1" = image ] && [ "$2" = tag ]; then
    return 0
  fi
  if [ "$1" = compose ]; then
    case " $* " in
      *" ps -q postgres "*)
        grep -q '^RELEASE=prior$' "$REPO_DIR/.env" || return 91
        printf 'prior-postgres\n'
        ;;
      *" ps -q redis "*) printf 'prior-redis\n' ;;
      *" ps -q backend "*) printf 'prior-backend\n' ;;
      *" ps -q frontend "*) printf 'prior-frontend\n' ;;
      *" ps -q caddy "*) printf 'prior-caddy\n' ;;
    esac
    return 0
  fi
  if [ "$1" = inspect ]; then
    case "${*: -1}" in
      prior-postgres) printf '%s\n' "${PRIOR_SERVICE_IMAGE_IDS[postgres]}" ;;
      prior-redis) printf '%s\n' "${PRIOR_SERVICE_IMAGE_IDS[redis]}" ;;
      prior-backend) printf '%s\n' "${PRIOR_SERVICE_IMAGE_IDS[backend]}" ;;
      prior-frontend) printf '%s\n' "${PRIOR_SERVICE_IMAGE_IDS[frontend]}" ;;
      prior-caddy) printf '%s\n' "${PRIOR_SERVICE_IMAGE_IDS[caddy]}" ;;
      *) return 92 ;;
    esac
    return 0
  fi
  if [ "$1" = exec ] && [[ " $* " == *" SELECT version_num FROM alembic_version "* ]]; then
    printf '0070\n'
  fi
  return 0
}

trap ':' EXIT
ENV_CANDIDATE=""
CANDIDATE_BUILD_ROOT=""
VERIFY_DATABASE=""
EXISTING_POSTGRES_CONTAINER=""
PROMOTION_COMPLETE=true
MAINTENANCE_ACTIVE=false
UPGRADE_SNAPSHOT="$SNAPSHOT"
REPO_DIR="$REPOSITORY"
PRIOR_PROJECT_NAME=dcompany
PRIOR_DB_HEAD=0070
ROLLBACK_STATE_MANIFEST_DIGEST="$STATE_DIGEST"
DATABASE_BACKUP_SHA256="$DATABASE_DIGEST"
PRIOR_INTERNAL_SERVICES=(postgres redis backend frontend)
EXPECTED_SERVICES=(postgres redis backend frontend caddy)
declare -A PRIOR_SERVICE_IMAGE_IDS=(
  [postgres]=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
  [redis]=sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
  [backend]=sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc
  [frontend]=sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd
  [caddy]=sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee
)
declare -A PRIOR_SERVICE_ORIGINAL_REFS=(
  [postgres]=dcompany-prior-postgres
  [redis]=dcompany-prior-redis
  [backend]=dcompany-prior-backend
  [frontend]=dcompany-prior-frontend
  [caddy]=dcompany-prior-caddy
)
declare -A PRIOR_SERVICE_ROLLBACK_REFS=(
  [postgres]=dcompany-rollback:test-postgres
  [redis]=dcompany-rollback:test-redis
  [backend]=dcompany-rollback:test-backend
  [frontend]=dcompany-rollback:test-frontend
  [caddy]=dcompany-rollback:test-caddy
)
cd "$REPO_DIR"
false
handle_install_failure
'''
    )
    result = subprocess.run(
        ["bash", "-c", harness],
        cwd=repo,
        env={
            **os.environ,
            "TRACE": str(trace),
            "SNAPSHOT": str(snapshot),
            "REPOSITORY": str(repo),
            "STATE_DIGEST": state_manifest_digest,
            "DATABASE_DIGEST": database_digest,
        },
        capture_output=True,
        text=True,
    )

    # The original simulated release failure is preserved after a successful
    # rollback; the trace proves recovery used the prior runtime first.
    assert result.returncode == 1, result.stderr
    assert (repo / ".env").read_text(encoding="utf-8") == "RELEASE=prior\n"
    commands = trace.read_text(encoding="utf-8").splitlines()
    start_postgres = next(
        index
        for index, command in enumerate(commands)
        if "docker-compose.prior.yml" in command
        and "up -d --no-build --pull never --force-recreate postgres" in command
    )
    postgres_ready = next(
        index for index, command in enumerate(commands) if "pg_isready" in command
    )
    restore_database = next(
        index for index, command in enumerate(commands) if "pg_restore" in command
    )
    start_backend = next(
        index
        for index, command in enumerate(commands)
        if "up -d --no-build --pull never --force-recreate redis backend frontend"
        in command
    )
    restore_images = next(
        index
        for index, command in enumerate(commands)
        if "image inspect --format {{.Id}} dcompany-rollback:test-postgres"
        in command
    )
    assert restore_images < start_postgres
    assert start_postgres < postgres_ready < restore_database < start_backend
    assert "Prior release and final quiesced database backup restored." in result.stderr

    # A substituted rollback alias is rejected before any prior runtime starts.
    (repo / ".env").write_text("RELEASE=candidate\n", encoding="utf-8")
    trace.unlink()
    tampered = subprocess.run(
        ["bash", "-c", harness],
        cwd=repo,
        env={
            **os.environ,
            "TRACE": str(trace),
            "SNAPSHOT": str(snapshot),
            "REPOSITORY": str(repo),
            "STATE_DIGEST": state_manifest_digest,
            "DATABASE_DIGEST": database_digest,
            "TAMPER_ALIAS": "postgres",
        },
        capture_output=True,
        text=True,
    )
    assert tampered.returncode == 1
    assert "AUTOMATIC ROLLBACK FAILED" in tampered.stderr
    assert not any(" up -d " in command for command in trace.read_text().splitlines())


def test_legacy_minio_is_captured_for_rollback_then_retired_without_volume_deletion() -> None:
    source = INSTALLER.read_text(encoding="utf-8")

    capture = '--filter "label=com.docker.compose.service=minio"'
    snapshot = 'for container_id in "${CONTAINER_IDS[@]}"; do'
    retire = 'docker rm "$LEGACY_MINIO_CONTAINER"'
    promote = 'mv "$ENV_CANDIDATE" .env'
    start = 'up -d --no-build --pull never postgres redis backend frontend'
    assert source.index(capture) < source.index(snapshot)
    assert source.index(promote) < source.index(retire) < source.index(start)
    assert 'EXPECTED_SERVICES+=(minio)' in source
    assert 'PRIOR_INTERNAL_SERVICES+=("$service")' in source
    assert 'docker volume rm' not in source


def test_rollback_never_tries_to_create_an_invalid_digest_tag() -> None:
    source = INSTALLER.read_text(encoding="utf-8")

    digest_guard = 'if [[ "$prior_original_ref" != *@sha256:* ]]'
    mutable_retag = 'docker image tag "$prior_image_id" "$prior_original_ref"'
    inspect_original = "restored_original_id=$(docker image inspect --format '{{.Id}}'"
    assert digest_guard in source
    assert source.index(digest_guard) < source.index(mutable_retag)
    assert inspect_original in source


def test_isolated_runtime_gate_routes_backend_and_web_through_caddy() -> None:
    source = (
        ROOT / "infra" / "scripts" / "verify-production-runtime-images.sh"
    ).read_text(encoding="utf-8")

    start_caddy = (
        "DOMAIN='http://ci-runtime.local' \"${compose[@]}\" "
        "up -d --no-build caddy"
    )
    routed_ready = "-H 'Host: ci-runtime.local'"
    full_parity = '--expected-images-json "$candidate_images" >/dev/null'
    assert start_caddy in source
    assert source.count(routed_ready) == 2
    assert "Caddy did not route the backend readiness endpoint." in source
    assert "'<div id=\"root\"></div>'" in source
    assert source.count(full_parity) == 2
    assert source.index(start_caddy) < source.rindex(full_parity)
