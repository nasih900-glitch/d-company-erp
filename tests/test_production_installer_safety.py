from __future__ import annotations

import re
import subprocess
import tarfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
INSTALLER = ROOT / "infra" / "scripts" / "install-on-vm.sh"


def test_candidate_build_uses_a_private_immutable_git_archive() -> None:
    source = INSTALLER.read_text(encoding="utf-8")

    archive = 'git archive --format=tar "$CURRENT_REVISION"'
    extract = 'tar -xf "$CANDIDATE_SOURCE_ARCHIVE" -C "$CANDIDATE_BUILD_ROOT"'
    build = 'docker compose --project-directory "$CANDIDATE_BUILD_ROOT"'
    assert source.index(archive) < source.index(extract) < source.index(build)
    assert '--env-file "$ENV_CANDIDATE" build backend frontend' in source
    assert 'docker compose -f docker-compose.prod.yml --env-file "$ENV_CANDIDATE" build' not in source
    assert "BUILD_SNAPSHOT_ROOT=/var/lib/dcompany-erp/build-snapshots" in source


def test_exact_candidate_images_are_scanned_before_maintenance() -> None:
    source = INSTALLER.read_text(encoding="utf-8")

    attestation = "CANDIDATE_IMAGE_ATTESTATION=$(python3 ops/runtime_release_parity.py candidate"
    immutable_save = 'docker image save "$candidate_image_id" --output "$image_archive"'
    syft = '"$SYFT_IMAGE" "/scan/image.tar" --from docker-archive --output syft-json'
    grype = '"$GRYPE_IMAGE" "sbom:/scan/image.syft.json"'
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


def test_lock_and_rollback_paths_are_root_private_and_fail_closed() -> None:
    source = INSTALLER.read_text(encoding="utf-8")

    assert "LOCK_DIR=/var/lock/d-company-erp" in source
    assert 'install -d -o root -g root -m 0700 "$LOCK_DIR"' in source
    assert '[ -L "$LOCK_FILE" ] || [ ! -f "$LOCK_FILE" ]' in source
    assert "0:0:600:1:regular file" in source
    assert 'exec 9<>"$LOCK_FILE"' in source
    assert source.index("lock_fd_metadata=") < source.index("flock -n 9")
    assert "ROLLBACK_ROOT=/var/lib/dcompany-erp/deployment-rollbacks" in source


def test_each_compose_service_has_one_container_and_immutable_rollback_image() -> None:
    source = INSTALLER.read_text(encoding="utf-8")

    unique = 'if [ "${#SERVICE_IDS[@]}" -ne 1 ]; then'
    first_tag = 'docker image tag "$image_id" "$rollback_ref"'
    assert source.index(unique) < source.index(first_tag)
    assert 'rollback_ref="dcompany-rollback:${SNAPSHOT_ID}-${service}-${image_short}"' in source
    assert 'docker image tag %q %q\\n\' "$image_id" "$original_ref"' in source
    assert 'if [ "$restored_image_id" != "${PRIOR_SERVICE_IMAGE_IDS[$service]}" ]; then' in source


def test_rollback_keeps_ingress_closed_until_core_services_are_ready_and_attested() -> None:
    source = INSTALLER.read_text(encoding="utf-8")

    core_restore = (
        '"${prior_compose[@]}" up -d --no-build --force-recreate \\\n'
        '        postgres redis minio backend frontend'
    )
    skip_caddy = '[ "$service" = caddy ] && continue'
    backend_ready = 'until "${prior_compose[@]}" exec -T backend python -c'
    start_caddy = '"${prior_compose[@]}" up -d --no-build --force-recreate caddy'
    attest_caddy = (
        'if [ "$restored_caddy_image_id" != "${PRIOR_SERVICE_IMAGE_IDS[caddy]:-}" ]; then'
    )

    assert source.index(core_restore) < source.index(skip_caddy)
    assert source.index(skip_caddy) < source.index(backend_ready)
    assert source.index(backend_ready) < source.index(start_caddy)
    assert source.index(start_caddy) < source.index(attest_caddy)
