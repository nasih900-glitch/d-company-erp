"""Pure contract/security tests for constrained remote assistance."""

from __future__ import annotations

import base64
import inspect
import io
import itertools
import re
import subprocess
from pathlib import Path
from uuid import uuid1, uuid4

import pytest
from PIL import Image
from pydantic import ValidationError as PydanticValidationError
from redis.exceptions import RedisError
from sqlalchemy import CheckConstraint, UniqueConstraint

from app.api.v1.remote_assistance import router as remote_router
from app.api.v1.remote_assistance.router import CommandCreate, RemoteRequestCreate
from app.core.config import Settings
from app.core.errors import (
    AuthError,
    ConflictError,
    RateLimitError,
    ServiceUnavailableError,
    ValidationError,
)
from app.models import (
    RemoteAssistanceCommand,
    RemoteAssistanceDeviceKey,
    RemoteAssistanceGrant,
    RemoteAssistanceSession,
)
from app.services.realtime import resource_for_path, resources_for_path
from app.services.remote_assistance import device_auth, relay
from app.services.remote_assistance.relay import ValidatedJpeg, validate_and_sanitize_jpeg


def _jpeg(width: int, height: int, *, with_exif: bool = False) -> bytes:
    output = io.BytesIO()
    image = Image.new("RGB", (width, height), color=(24, 32, 48))
    exif = Image.Exif()
    if with_exif:
        exif[0x010E] = "must-not-survive"
    image.save(output, format="JPEG", quality=85, exif=exif)
    return output.getvalue()


def test_command_contract_is_closed_and_excludes_financial_control() -> None:
    command = CommandCreate(
        command_id=uuid4(),
        sequence=1,
        type="navigate",
        module="help",
    )
    assert command.module == "help"

    for unsafe in (
        {"type": "navigate", "module": "finance"},
        {"type": "navigate", "module": "dashboard"},
        {"type": "navigate", "module": "gaming"},
        {"type": "navigate", "module": "pos"},
        {"type": "navigate", "module": "shift"},
        {"type": "payment", "module": None},
        {"type": "refund", "module": None},
        {"type": "end_session", "module": None},
        {"type": "sync_now", "module": None},
    ):
        with pytest.raises(PydanticValidationError):
            CommandCreate(command_id=uuid4(), sequence=1, **unsafe)  # type: ignore[arg-type]

    with pytest.raises(PydanticValidationError):
        CommandCreate(
            command_id=uuid4(),
            sequence=1,
            type="refresh",
            module=None,
            amount_minor=1,
        )
    with pytest.raises(PydanticValidationError):
        CommandCreate(command_id=uuid4(), sequence=1, type="navigate")


def test_all_mutation_keys_require_random_uuid_v4() -> None:
    with pytest.raises(PydanticValidationError, match="UUID v4"):
        CommandCreate(command_id=uuid1(), sequence=1, type="refresh")
    with pytest.raises(PydanticValidationError, match="UUID v4"):
        RemoteRequestCreate(
            request_id=uuid1(),
            installation_id=uuid4(),
            grant_kind="one_time",
            grant_ttl_seconds=600,
            session_ttl_seconds=600,
        )


def test_default_online_window_covers_more_than_two_android_heartbeat_intervals() -> None:
    default_window = Settings.model_fields["remote_assistance_device_online_seconds"].default
    assert default_window == 45
    assert default_window > 2 * 20
    assert Settings.model_fields["remote_assistance_frame_decode_min_interval_ms"].default == 2_000
    assert Settings.model_fields["remote_assistance_device_key_pending_seconds"].default == 600
    assert (
        Settings.model_fields["remote_assistance_device_signature_max_skew_seconds"].default == 90
    )
    assert Settings.model_fields["remote_assistance_device_nonce_ttl_seconds"].default == 240
    assert Settings.model_fields["remote_assistance_anytime_grant_max_seconds"].default == 86_400


def test_production_rejects_default_remote_pairing_secret() -> None:
    with pytest.raises(PydanticValidationError, match="REMOTE_ASSISTANCE_PAIRING_SECRET"):
        Settings(
            env="prod",
            jwt_secret="j" * 48,
            remote_assistance_pairing_secret=("CHANGE_ME_REMOTE_PAIRING_SECRET_AT_LEAST_32_CHARS"),
        )

    shared_secret = "one-secret-must-not-span-jwt-and-remote-pairing"
    with pytest.raises(PydanticValidationError, match="must not reuse JWT_SECRET"):
        Settings(
            env="prod",
            jwt_secret=shared_secret,
            remote_assistance_pairing_secret=shared_secret,
        )

    strong_redis = (
        "redis://erp_backend:0123456789abcdef0123456789abcdef"
        "0123456789abcdef0123456789abcdef@redis:6379/0"
    )
    with pytest.raises(PydanticValidationError, match="RELAY_SECRET"):
        Settings(
            env="prod",
            jwt_secret="j" * 48,
            remote_assistance_pairing_secret="p" * 48,
            redis_url=strong_redis,
        )
    with pytest.raises(PydanticValidationError, match="REDIS_URL"):
        Settings(
            env="prod",
            jwt_secret="j" * 48,
            remote_assistance_pairing_secret="p" * 48,
            remote_assistance_relay_secret=("cnJycnJycnJycnJycnJycnJycnJycnJycnJycnJycnI="),
            redis_url="redis://redis:6379/0",
        )


def test_production_compose_isolates_and_authenticates_backend_data_plane() -> None:
    root = Path(__file__).resolve().parents[3]
    compose = (root / "docker-compose.prod.yml").read_text(encoding="utf-8")
    generator = (root / "infra/scripts/generate-secrets.sh").read_text(encoding="utf-8")
    assert "user default off" in compose
    assert "user erp_backend on >$${REDIS_PASSWORD} ~dcompany:* -@all" in compose
    for command in (
        "+ping",
        "+eval",
        "+get",
        "+set",
        "+del",
        "+hgetall",
        "+multi",
        "+exec",
    ):
        assert command in compose
    for forbidden in ("+@all", "+flushall", "+config", "+shutdown", "+acl"):
        assert forbidden not in compose
    assert "--aclfile /run/redis-users.acl" in compose
    assert "redis://erp_backend:${REDIS_PASSWORD}@redis:6379/0" in compose
    assert "REDISCLI_AUTH=$$REDIS_PASSWORD redis-cli --user erp_backend ping" in compose
    assert "backend_data:\n    internal: true" in compose
    assert "REMOTE_ASSISTANCE_RELAY_SECRET: ${REMOTE_ASSISTANCE_RELAY_SECRET}" in compose
    assert "infra/scripts/install-on-vm.sh <domain> --maintenance-confirmed" in compose
    assert "docker compose -f docker-compose.prod.yml --env-file .env up -d --build" not in compose
    for placeholder in (
        "CHANGE_ME_48_char_dedicated_pairing_secret",
        "CHANGE_ME_32_byte_base64_relay_key",
        "CHANGE_ME_64_hex_redis_password",
    ):
        assert placeholder in generator


def test_secret_generator_is_independent_and_truthful_on_rerun(tmp_path) -> None:
    root = Path(__file__).resolve().parents[3]
    env_file = tmp_path / "production.env"
    env_file.write_text(
        (root / ".env.production.example").read_text(encoding="utf-8"),
        encoding="utf-8",
    )
    script = root / "infra/scripts/generate-secrets.sh"
    first = subprocess.run(  # noqa: S603 - repository-owned fixed script
        [str(script), str(env_file)],
        cwd=root,
        check=True,
        capture_output=True,
        text=True,
    )
    values = {
        line.split("=", 1)[0]: line.split("=", 1)[1]
        for line in env_file.read_text(encoding="utf-8").splitlines()
        if line and not line.startswith("#") and "=" in line
    }
    assert "Owner password generated and stored" in first.stdout
    assert values["SEED_OWNER_PASSWORD"] not in first.stdout
    assert "SEED_OWNER_PASSWORD=" not in first.stdout
    assert env_file.stat().st_mode & 0o777 == 0o600
    assert len(base64.b64decode(values["REMOTE_ASSISTANCE_RELAY_SECRET"], validate=True)) == 32
    assert re.fullmatch(r"[0-9a-f]{64}", values["REDIS_PASSWORD"])
    independent = {
        values["JWT_SECRET"],
        values["REMOTE_ASSISTANCE_PAIRING_SECRET"],
        values["REMOTE_ASSISTANCE_RELAY_SECRET"],
        values["REDIS_PASSWORD"],
    }
    assert len(independent) == 4

    second = subprocess.run(  # noqa: S603 - repository-owned fixed script
        [str(script), str(env_file)],
        cwd=root,
        check=True,
        capture_output=True,
        text=True,
    )
    assert "Existing owner password retained and not displayed." in second.stdout
    assert values["SEED_OWNER_PASSWORD"] not in second.stdout
    assert "SEED_OWNER_PASSWORD=" not in second.stdout


def test_secret_generator_backfills_upgrade_without_rotating_existing_values(
    tmp_path,
) -> None:
    root = Path(__file__).resolve().parents[3]
    env_file = tmp_path / "existing.env"
    original_jwt = "existing-jwt-secret-kept-across-code17-upgrade"
    original_owner = "existing-owner-password-must-not-be-printed"
    original_postgres = "existingPostgresPassword"
    original_minio = "existingMinioPassword"
    legacy = (
        (root / ".env.production.example")
        .read_text(encoding="utf-8")
        .replace("CHANGE_ME.com", "erp.example.com")
        .replace("CHANGE_ME_git_commit_sha", "a" * 40)
        .replace("CHANGE_ME_48_char_base64_secret", original_jwt)
        .replace("CHANGE_ME_strong_owner_password", original_owner)
        .replace("CHANGE_ME_strong_random_password", original_postgres)
        .replace("CHANGE_ME_minio_password", original_minio)
    )
    legacy = "\n".join(
        line
        for line in legacy.splitlines()
        if not line.startswith(
            (
                "REDIS_PASSWORD=",
                "REMOTE_ASSISTANCE_PAIRING_SECRET=",
                "REMOTE_ASSISTANCE_RELAY_SECRET=",
                "DATABASE_URL=",
            )
        )
    )
    env_file.write_text(f"{legacy}\n", encoding="utf-8")
    env_file.chmod(0o644)

    script = root / "infra/scripts/generate-secrets.sh"
    result = subprocess.run(  # noqa: S603 - repository-owned fixed script
        [str(script), str(env_file)],
        cwd=root,
        check=True,
        capture_output=True,
        text=True,
    )
    values = {
        line.split("=", 1)[0]: line.split("=", 1)[1]
        for line in env_file.read_text(encoding="utf-8").splitlines()
        if line and not line.startswith("#") and "=" in line
    }
    assert values["JWT_SECRET"] == original_jwt
    assert values["SEED_OWNER_PASSWORD"] == original_owner
    assert values["POSTGRES_PASSWORD"] == original_postgres
    assert values["DATABASE_URL"] == (
        f"postgresql+psycopg://erp:{original_postgres}@postgres:5432/erp"
    )
    assert values["S3_SECRET_KEY"] == original_minio
    assert re.fullmatch(r"[0-9a-f]{64}", values["REDIS_PASSWORD"])
    assert len(values["REMOTE_ASSISTANCE_PAIRING_SECRET"]) >= 32
    assert len(base64.b64decode(values["REMOTE_ASSISTANCE_RELAY_SECRET"], validate=True)) == 32
    assert original_owner not in result.stdout
    assert env_file.stat().st_mode & 0o777 == 0o600

    backups = list(tmp_path.glob("existing.env.pre-code17.*"))
    assert len(backups) == 1
    assert backups[0].stat().st_mode & 0o777 == 0o600
    assert "REDIS_PASSWORD=" not in backups[0].read_text(encoding="utf-8")

    validator = root / "infra/scripts/validate-production-env.sh"
    subprocess.run(  # noqa: S603 - repository-owned fixed script
        [str(validator), str(env_file)],
        cwd=root,
        check=True,
        capture_output=True,
        text=True,
    )

    invalid_relay = tmp_path / "invalid-relay.env"
    invalid_relay.write_text(
        env_file.read_text(encoding="utf-8").replace(
            values["REMOTE_ASSISTANCE_RELAY_SECRET"],
            values["REMOTE_ASSISTANCE_RELAY_SECRET"][:-1] + "A",
        ),
        encoding="utf-8",
    )
    rejected_relay = subprocess.run(  # noqa: S603 - repository-owned fixed script
        [str(validator), str(invalid_relay)],
        cwd=root,
        check=False,
        capture_output=True,
        text=True,
    )
    assert rejected_relay.returncode != 0
    assert "REMOTE_ASSISTANCE_RELAY_SECRET" in rejected_relay.stderr

    mismatched_db = tmp_path / "mismatched-db.env"
    mismatched_db.write_text(
        env_file.read_text(encoding="utf-8").replace(
            values["DATABASE_URL"],
            "postgresql+psycopg://erp:differentPassword@postgres:5432/erp",
        ),
        encoding="utf-8",
    )
    rejected_db = subprocess.run(  # noqa: S603 - repository-owned fixed script
        [str(validator), str(mismatched_db)],
        cwd=root,
        check=False,
        capture_output=True,
        text=True,
    )
    assert rejected_db.returncode != 0
    assert "DATABASE_URL" in rejected_db.stderr


def test_production_installer_preflights_before_stack_mutation_and_hides_credentials(
    tmp_path,
) -> None:
    root = Path(__file__).resolve().parents[3]
    installer = (root / "infra/scripts/install-on-vm.sh").read_text(encoding="utf-8")
    preparer_call = "bash infra/scripts/prepare-production-env.sh"
    compose_preflight = (
        'docker compose -f docker-compose.prod.yml --env-file "$ENV_CANDIDATE" config --quiet'
    )
    database_backup = "pg_dump -U erp --format=custom erp"
    promote_candidate = 'mv "$ENV_CANDIDATE" .env'
    compose_up = "up -d postgres redis minio backend frontend"
    assert installer.index(preparer_call) < installer.index(compose_preflight)
    assert installer.index(compose_preflight) < installer.index(database_backup)
    assert installer.index(database_backup) < installer.index(promote_candidate)
    assert installer.index(promote_candidate) < installer.index(compose_up)
    assert "if [ ! -f .env ]; then\n  FRESH_INSTALL=true" in installer
    assert "set -x" not in installer
    assert 'echo "$OWNER_PASSWORD"' not in installer
    assert "First-login password:" not in installer
    assert "Existing owner credentials were retained and are not displayed." in installer
    assert ".deployment-rollbacks/pre-code17-" in installer
    assert "pg_restore --list" in installer
    assert "check-upgrade-capacity.sh" in installer
    assert "pg_database_size('erp')" in installer
    assert "code17_restore_verify_" in installer
    assert 'git show "$PRIOR_REVISION:docker-compose.prod.yml"' in installer
    assert 'docker image tag "$image_id" "$rollback_ref"' in installer
    assert 'docker create "$PRIOR_BACKEND_IMAGE"' in installer
    assert 'docker cp "$IMAGE_VERIFY_CONTAINER:/app/."' in installer
    assert 'docker exec -i "$EXISTING_BACKEND_CONTAINER" python' not in installer
    assert 'find "$IMAGE_VERIFY_ROOT/app" -type l' in installer
    assert "PRIOR_DB_HEAD\" != 0060" in installer
    assert "org.opencontainers.image.revision" in installer
    assert "CANDIDATE_APP_VERSION=$(grep '^APP_VERSION='" in installer
    assert 'images -q "$candidate_service"' in installer
    assert "Candidate backend/frontend image version and revision labels verified." in installer
    assert "Expected exactly one existing Postgres container" in installer
    assert "Expected exactly one running backend container" in installer
    assert "Persistent deployment evidence exists but .env is missing" in installer
    assert "docker volume ls" in installer
    assert "docker network ls" in installer
    assert "flock -n 9" in installer
    assert "trap handle_post_ingress_failure EXIT" in installer
    assert "The current database is preserved and the quiesced dump was NOT restored." in installer
    assert "http://localhost:8000/readyz" in installer
    assert '"admin.system" not in me.get("effective_permissions", [])' in installer
    assert "git status --porcelain --untracked-files=normal" in installer
    assert "grep '^APP_REVISION=' .env" not in installer

    invalid_env = tmp_path / "invalid.env"
    invalid_env.write_text(
        "\n".join(
            (
                "ENV=prod",
                "APP_VERSION=3.1.12",
                "APP_REVISION=" + "a" * 40,
                "DOMAIN=erp.example.com",
                'CORS_ORIGINS=["https://erp.example.com"]',
                "PUBLIC_URL=https://erp.example.com",
                "ANDROID_UPDATE_ALLOWED_ORIGIN=https://erp.example.com",
                "JWT_SECRET=" + "j" * 48,
                "REMOTE_ASSISTANCE_PAIRING_SECRET=" + "p" * 48,
                "REMOTE_ASSISTANCE_RELAY_SECRET=not-base64" + "x" * 34,
                "REDIS_PASSWORD=" + "z" * 64,
                "POSTGRES_PASSWORD=" + "q" * 32,
                "DATABASE_URL=postgresql+psycopg://erp:" + "q" * 32 + "@postgres:5432/erp",
                "S3_SECRET_KEY=" + "s" * 32,
                "SEED_OWNER_EMAIL=owner@erp.example.com",
                "SEED_OWNER_PASSWORD=" + "o" * 16,
            )
        ),
        encoding="utf-8",
    )
    validator = root / "infra/scripts/validate-production-env.sh"
    rejected = subprocess.run(  # noqa: S603 - repository-owned fixed script
        [str(validator), str(invalid_env)],
        cwd=root,
        check=False,
        capture_output=True,
        text=True,
    )
    assert rejected.returncode != 0
    assert "REDIS_PASSWORD" in rejected.stderr


def test_candidate_environment_labels_fresh_and_upgrade_as_current_source(
    tmp_path,
) -> None:
    root = Path(__file__).resolve().parents[3]
    preparer = root / "infra/scripts/prepare-production-env.sh"
    current_revision = "b" * 40

    fresh_candidate = tmp_path / "fresh.env"
    fresh = subprocess.run(  # noqa: S603 - repository-owned fixed script
        [
            str(preparer),
            "-",
            str(fresh_candidate),
            "ERP.Example.Com",
            current_revision,
        ],
        cwd=root,
        check=True,
        capture_output=True,
        text=True,
    )
    fresh_values = {
        line.split("=", 1)[0]: line.split("=", 1)[1]
        for line in fresh_candidate.read_text(encoding="utf-8").splitlines()
        if line and not line.startswith("#") and "=" in line
    }
    assert fresh_values["APP_REVISION"] == current_revision
    assert fresh_values["DOMAIN"] == "erp.example.com"
    assert fresh_values["SEED_OWNER_PASSWORD"] not in fresh.stdout

    prior_revision = "a" * 40
    upgrade_source = tmp_path / "upgrade-source.env"
    upgrade_source.write_text(
        fresh_candidate.read_text(encoding="utf-8")
        .replace(
            f"APP_REVISION={current_revision}",
            f"APP_REVISION={prior_revision}",
        )
        .replace("APP_VERSION=3.1.12", "APP_VERSION=3.1.3"),
        encoding="utf-8",
    )
    upgrade_candidate = tmp_path / "upgrade.env"
    upgrade = subprocess.run(  # noqa: S603 - repository-owned fixed script
        [
            str(preparer),
            str(upgrade_source),
            str(upgrade_candidate),
            "erp.example.com",
            current_revision,
        ],
        cwd=root,
        check=True,
        capture_output=True,
        text=True,
    )
    upgrade_values = {
        line.split("=", 1)[0]: line.split("=", 1)[1]
        for line in upgrade_candidate.read_text(encoding="utf-8").splitlines()
        if line and not line.startswith("#") and "=" in line
    }
    assert prior_revision != current_revision
    assert upgrade_values["APP_REVISION"] == current_revision
    assert upgrade_values["APP_VERSION"] == "3.1.12"
    for retained in (
        "JWT_SECRET",
        "POSTGRES_PASSWORD",
        "S3_SECRET_KEY",
        "SEED_OWNER_PASSWORD",
        "REDIS_PASSWORD",
        "REMOTE_ASSISTANCE_PAIRING_SECRET",
        "REMOTE_ASSISTANCE_RELAY_SECRET",
    ):
        assert upgrade_values[retained] == fresh_values[retained]
        assert upgrade_values[retained] not in upgrade.stdout

    domain_migration = subprocess.run(  # noqa: S603 - repository-owned fixed script
        [
            str(preparer),
            str(upgrade_source),
            str(tmp_path / "wrong-domain.env"),
            "typo.example.com",
            current_revision,
        ],
        cwd=root,
        check=False,
        capture_output=True,
        text=True,
    )
    assert domain_migration.returncode != 0
    assert "Normal upgrades must use the existing DOMAIN exactly" in domain_migration.stderr

    stale_version = tmp_path / "stale-version.env"
    stale_version.write_text(
        upgrade_candidate.read_text(encoding="utf-8").replace(
            "APP_VERSION=3.1.12", "APP_VERSION=3.1.3"
        ),
        encoding="utf-8",
    )
    stale_validation = subprocess.run(  # noqa: S603 - repository-owned fixed script
        [str(root / "infra/scripts/validate-production-env.sh"), str(stale_version)],
        cwd=root,
        check=False,
        capture_output=True,
        text=True,
    )
    assert stale_validation.returncode != 0
    assert "APP_VERSION must match the coordinated release" in stale_validation.stderr


def test_upgrade_capacity_preflight_rejects_either_constrained_filesystem() -> None:
    root = Path(__file__).resolve().parents[3]
    checker = root / "infra/scripts/check-upgrade-capacity.sh"
    accepted = subprocess.run(  # noqa: S603 - repository-owned fixed script
        [str(checker), "1048576", "2097152", "2097152"],
        cwd=root,
        check=False,
        capture_output=True,
        text=True,
    )
    assert accepted.returncode == 0, accepted.stderr

    for snapshot_kib, postgres_kib in (("1024", "2097152"), ("2097152", "1024")):
        rejected = subprocess.run(  # noqa: S603 - repository-owned fixed script
            [str(checker), "1048576", snapshot_kib, postgres_kib],
            cwd=root,
            check=False,
            capture_output=True,
            text=True,
        )
        assert rejected.returncode != 0
        assert "Insufficient free space" in rejected.stderr


def test_production_entrypoint_does_not_mask_seed_failures() -> None:
    root = Path(__file__).resolve().parents[3]
    entrypoint = (root / "infra/docker/backend-entrypoint.sh").read_text(encoding="utf-8")
    assert "python -m scripts.seed\n" in entrypoint
    assert "python -m scripts.seed_india\n" in entrypoint
    assert "python -m scripts.ensure_roles\n" in entrypoint
    assert "|| echo" not in entrypoint


def test_production_environment_rejects_every_managed_secret_collision(tmp_path) -> None:
    root = Path(__file__).resolve().parents[3]
    preparer = root / "infra/scripts/prepare-production-env.sh"
    validator = root / "infra/scripts/validate-production-env.sh"
    baseline = tmp_path / "baseline.env"
    subprocess.run(  # noqa: S603 - repository-owned fixed script
        [str(preparer), "-", str(baseline), "erp.example.com", "c" * 40],
        cwd=root,
        check=True,
        capture_output=True,
        text=True,
    )
    secret_keys = (
        "JWT_SECRET",
        "REMOTE_ASSISTANCE_PAIRING_SECRET",
        "REMOTE_ASSISTANCE_RELAY_SECRET",
        "REDIS_PASSWORD",
        "POSTGRES_PASSWORD",
        "S3_SECRET_KEY",
        "SEED_OWNER_PASSWORD",
    )
    baseline_lines = baseline.read_text(encoding="utf-8").splitlines()

    for left, right in itertools.combinations(secret_keys, 2):
        if "REMOTE_ASSISTANCE_RELAY_SECRET" in {left, right}:
            common = base64.b64encode(b"r" * 32).decode("ascii")
        elif "REDIS_PASSWORD" in {left, right}:
            common = "d" * 64
        else:
            common = "sharedSecretValue" * 3
        replacements = {left: common, right: common}
        lines = []
        for line in baseline_lines:
            key = line.split("=", 1)[0] if "=" in line else ""
            if key in replacements:
                lines.append(f"{key}={replacements[key]}")
            elif key == "DATABASE_URL" and "POSTGRES_PASSWORD" in replacements:
                lines.append(
                    f"DATABASE_URL=postgresql+psycopg://erp:{common}@postgres:5432/erp"
                )
            else:
                lines.append(line)
        collision = tmp_path / f"collision-{left}-{right}.env"
        collision.write_text("\n".join(lines) + "\n", encoding="utf-8")
        rejected = subprocess.run(  # noqa: S603 - repository-owned fixed script
            [str(validator), str(collision)],
            cwd=root,
            check=False,
            capture_output=True,
            text=True,
        )
        assert rejected.returncode != 0, (left, right)
        if {left, right} != {
            "REMOTE_ASSISTANCE_RELAY_SECRET",
            "REDIS_PASSWORD",
        }:
            assert "independent" in rejected.stderr, (left, right, rejected.stderr)


def test_jpeg_validation_accepts_landscape_and_portrait_and_strips_metadata() -> None:
    for width, height in ((960, 540), (256, 540)):
        clean = validate_and_sanitize_jpeg(
            _jpeg(width, height, with_exif=True),
            declared_width=width,
            declared_height=height,
        )
        assert clean.width == width
        assert clean.height == height
        with Image.open(io.BytesIO(clean.content)) as decoded:
            assert decoded.format == "JPEG"
            assert decoded.size == (width, height)
            assert not decoded.getexif()


def test_jpeg_validation_rejects_spoofed_geometry_and_non_jpeg() -> None:
    with pytest.raises(ValidationError, match="do not match"):
        validate_and_sanitize_jpeg(
            _jpeg(960, 540),
            declared_width=959,
            declared_height=540,
        )
    with pytest.raises(ValidationError, match="complete JPEG"):
        validate_and_sanitize_jpeg(
            b"not-an-image",
            declared_width=960,
            declared_height=540,
        )
    with pytest.raises(ValidationError, match="outside"):
        validate_and_sanitize_jpeg(
            _jpeg(200, 540),
            declared_width=200,
            declared_height=540,
        )


class _RelayClient:
    def __init__(self, result: object = None, error: Exception | None = None) -> None:
        self.result = result
        self.error = error

    async def eval(self, *_args: object) -> object:
        if self.error is not None:
            raise self.error
        return self.result

    async def ping(self) -> object:
        if self.error is not None:
            raise self.error
        return self.result

    async def aclose(self) -> None:
        return None


class _DeviceProofClient:
    def __init__(self, *, result: bool = True, error: Exception | None = None) -> None:
        self.result = result
        self.error = error

    async def set(self, *_args: object, **_kwargs: object) -> bool:
        if self.error is not None:
            raise self.error
        return self.result

    async def aclose(self) -> None:
        return None


@pytest.mark.asyncio
async def test_device_proof_nonce_fails_closed_and_rejects_replay(monkeypatch) -> None:
    monkeypatch.setattr(
        device_auth,
        "request_path_redis_client",
        lambda _url: _DeviceProofClient(error=RedisError("offline")),
    )
    with pytest.raises(ServiceUnavailableError, match="authentication is temporarily unavailable"):
        await device_auth.claim_device_nonce(
            company_id=uuid4(),
            key_id=uuid4(),
            nonce=uuid4(),
            purpose="request",
        )

    monkeypatch.setattr(
        device_auth,
        "request_path_redis_client",
        lambda _url: _DeviceProofClient(result=False),
    )
    with pytest.raises(AuthError, match="nonce was already used"):
        await device_auth.claim_device_nonce(
            company_id=uuid4(),
            key_id=uuid4(),
            nonce=uuid4(),
            purpose="request",
        )


@pytest.mark.asyncio
async def test_frame_relay_fails_closed_and_enforces_rate_and_sequence(monkeypatch) -> None:
    frame = ValidatedJpeg(content=_jpeg(640, 480), width=640, height=480)

    monkeypatch.setattr(
        relay,
        "request_path_redis_binary_client",
        lambda _url: _RelayClient(error=RedisError("offline")),
    )
    with pytest.raises(ServiceUnavailableError, match="sharing stopped"):
        await relay.store_latest_frame(
            company_id=uuid4(),
            session_id=uuid4(),
            frame_id=uuid4(),
            sequence=1,
            frame=frame,
        )

    monkeypatch.setattr(
        relay,
        "request_path_redis_binary_client",
        lambda _url: _RelayClient(result=[0, 750]),
    )
    with pytest.raises(RateLimitError) as rate_limited:
        await relay.store_latest_frame(
            company_id=uuid4(),
            session_id=uuid4(),
            frame_id=uuid4(),
            sequence=1,
            frame=frame,
        )
    assert rate_limited.value.details["limit_per_second"] == 1

    monkeypatch.setattr(
        relay,
        "request_path_redis_binary_client",
        lambda _url: _RelayClient(result=[2, 7]),
    )
    with pytest.raises(ConflictError) as replayed:
        await relay.store_latest_frame(
            company_id=uuid4(),
            session_id=uuid4(),
            frame_id=uuid4(),
            sequence=7,
            frame=frame,
        )
    assert replayed.value.details == {"latest_sequence": 7}


@pytest.mark.asyncio
async def test_relay_availability_check_fails_closed(monkeypatch) -> None:
    monkeypatch.setattr(
        relay,
        "request_path_redis_binary_client",
        lambda _url: _RelayClient(error=RedisError("offline")),
    )
    with pytest.raises(ServiceUnavailableError, match="relay is not ready"):
        await relay.ensure_relay_available()


@pytest.mark.asyncio
async def test_predecode_admission_is_rate_limited_and_fails_closed(monkeypatch) -> None:
    monkeypatch.setattr(
        relay,
        "request_path_redis_binary_client",
        lambda _url: _RelayClient(result=[0, 1_750]),
    )
    with pytest.raises(RateLimitError) as rate_limited:
        await relay.admit_frame_upload(
            company_id=uuid4(),
            session_id=uuid4(),
            frame_id=uuid4(),
            sequence=1,
        )
    assert rate_limited.value.details == {
        "minimum_interval_ms": 2_000,
        "retry_after_seconds": 2,
    }

    monkeypatch.setattr(
        relay,
        "request_path_redis_binary_client",
        lambda _url: _RelayClient(error=RedisError("offline")),
    )
    with pytest.raises(ServiceUnavailableError, match="sharing stopped"):
        await relay.admit_frame_upload(
            company_id=uuid4(),
            session_id=uuid4(),
            frame_id=uuid4(),
            sequence=1,
        )


@pytest.mark.asyncio
async def test_rate_admission_cannot_be_bypassed_by_reusing_frame_uuid(monkeypatch) -> None:
    calls: list[tuple[object, ...]] = []

    class _CapturingRelayClient(_RelayClient):
        async def eval(self, *args: object) -> object:
            calls.append(args)
            return [1, 123]

    client = _CapturingRelayClient()
    monkeypatch.setattr(
        relay,
        "request_path_redis_binary_client",
        lambda _url: client,
    )
    company_id = uuid4()
    session_id = uuid4()
    frame_id = uuid4()
    frame = ValidatedJpeg(content=_jpeg(640, 480), width=640, height=480)
    await relay.store_latest_frame(
        company_id=company_id,
        session_id=session_id,
        frame_id=frame_id,
        sequence=1,
        frame=frame,
    )
    await relay.store_latest_frame(
        company_id=company_id,
        session_id=session_id,
        frame_id=frame_id,
        sequence=2,
        frame=frame,
    )

    # eval args: script, key-count, four keys, then rate window, limit,
    # sequence, and the hashed rate-window member.
    assert calls[0][9] != calls[1][9]
    script = str(calls[0][0])
    assert script.index("local prior") < script.index("local cutoff")


@pytest.mark.asyncio
async def test_relay_encrypts_frames_and_rejects_ciphertext_or_metadata_tampering(
    monkeypatch,
) -> None:
    calls: list[tuple[object, ...]] = []

    class _CapturingRelayClient(_RelayClient):
        async def eval(self, *args: object) -> object:
            calls.append(args)
            return [1, 123]

    monkeypatch.setattr(
        relay,
        "request_path_redis_binary_client",
        lambda _url: _CapturingRelayClient(),
    )
    company_id = uuid4()
    session_id = uuid4()
    frame_id = uuid4()
    original = _jpeg(640, 480)
    frame = ValidatedJpeg(content=original, width=640, height=480)
    await relay.store_latest_frame(
        company_id=company_id,
        session_id=session_id,
        frame_id=frame_id,
        sequence=1,
        frame=frame,
    )

    args = calls[0]
    envelope = args[11]
    assert isinstance(envelope, bytes)
    assert not envelope.startswith(b"\xff\xd8")
    raw_metadata = {
        b"frame_id": str(frame_id).encode("ascii"),
        b"sequence": b"1",
        b"width": b"640",
        b"height": b"480",
        b"received_at": str(args[16]).encode("ascii"),
        b"version": str(args[17]).encode("ascii"),
        b"ttl_seconds": str(args[12]).encode("ascii"),
    }
    decoded = relay._decode_metadata(raw_metadata)
    assert (
        relay._open_frame(
            company_id=company_id,
            session_id=session_id,
            envelope=envelope,
            metadata=decoded,
        )
        == original
    )

    tampered_envelope = envelope[:-1] + bytes((envelope[-1] ^ 1,))
    with pytest.raises(ServiceUnavailableError, match="integrity verification"):
        relay._open_frame(
            company_id=company_id,
            session_id=session_id,
            envelope=tampered_envelope,
            metadata=decoded,
        )

    metadata_tampering = {
        b"frame_id": str(uuid4()).encode("ascii"),
        b"sequence": b"2",
        b"width": b"641",
        b"height": b"481",
        b"received_at": b"2026-08-30T12:00:00.000000Z",
        b"version": b"2",
        b"ttl_seconds": b"4",
    }
    for field, altered in metadata_tampering.items():
        tampered_metadata = {**raw_metadata, field: altered}
        with pytest.raises(ServiceUnavailableError):
            relay._open_frame(
                company_id=company_id,
                session_id=session_id,
                envelope=envelope,
                metadata=relay._decode_metadata(tampered_metadata),
            )


def test_frame_decode_is_explicitly_offloaded_from_async_request_loop() -> None:
    source = inspect.getsource(remote_router.upload_frame)
    ordered_stages = (
        "await authenticate_device_request(",
        "await session.commit()",
        "await admit_frame_upload(",
        "await _read_bounded_body(",
        "verify_actual_content(device_proof, content)",
        "await to_thread.run_sync(",
        "final_now = datetime.now(UTC)",
        "await store_latest_frame(",
    )
    positions = [source.index(stage) for stage in ordered_stages]
    assert positions == sorted(positions)
    assert "asyncio.timeout(settings.remote_assistance_frame_read_timeout_seconds)" in source
    assert "limiter=_FRAME_DECODER_LIMITER" in source


def test_every_post_enrollment_device_route_has_possession_verification() -> None:
    direct_proof_routes = (
        remote_router.device_key_status,
        remote_router.device_state,
        remote_router.upload_frame,
    )
    json_proof_routes = (
        remote_router.device_heartbeat,
        remote_router.decide_grant,
        remote_router.device_revoke_grant,
        remote_router.device_end_session,
        remote_router.command_result,
    )
    assert "authenticate_enrollment_request" in inspect.getsource(remote_router.enroll_device_key)
    for route in direct_proof_routes:
        assert "authenticate_device_request" in inspect.getsource(route)
    for route in json_proof_routes:
        assert "_authenticate_device_json" in inspect.getsource(route)


def test_models_contain_no_screenshot_or_frame_blob_column() -> None:
    for model in (RemoteAssistanceGrant, RemoteAssistanceSession, RemoteAssistanceCommand):
        column_names = set(model.__table__.columns.keys())
        assert "frame" not in column_names
        assert "screenshot" not in column_names
        assert "content" not in column_names


def test_models_lock_idempotency_and_terminal_evidence_at_the_database_layer() -> None:
    grant_unique_names = {
        constraint.name
        for constraint in RemoteAssistanceGrant.__table__.constraints
        if isinstance(constraint, UniqueConstraint)
    }
    session_unique_names = {
        constraint.name
        for constraint in RemoteAssistanceSession.__table__.constraints
        if isinstance(constraint, UniqueConstraint)
    }
    assert {
        "uq_remote_assistance_grants_company_decision_id",
        "uq_remote_assistance_grants_company_revocation_id",
    } <= grant_unique_names
    assert {
        "uq_remote_assistance_sessions_company_start_id",
        "uq_remote_assistance_sessions_company_end_id",
    } <= session_unique_names

    grant_checks = {
        constraint.name: str(constraint.sqltext)
        for constraint in RemoteAssistanceGrant.__table__.constraints
        if isinstance(constraint, CheckConstraint)
    }
    session_checks = {
        constraint.name: str(constraint.sqltext)
        for constraint in RemoteAssistanceSession.__table__.constraints
        if isinstance(constraint, CheckConstraint)
    }
    assert "status = 'revoked'" in grant_checks["ck_remote_assistance_grants_revocation_evidence"]
    assert "status = 'active'" in session_checks["ck_remote_assistance_sessions_start_evidence"]
    assert (
        "ended_by_user_id IS NOT NULL"
        in session_checks["ck_remote_assistance_sessions_end_evidence"]
    )


def test_device_key_model_locks_scope_lifecycle_and_action_ids() -> None:
    unique_names = {
        constraint.name
        for constraint in RemoteAssistanceDeviceKey.__table__.constraints
        if isinstance(constraint, UniqueConstraint)
    }
    assert {
        "uq_remote_assistance_device_keys_company_enrollment_id",
        "uq_remote_assistance_device_keys_company_approval_id",
        "uq_remote_assistance_device_keys_company_revocation_id",
        "uq_remote_assistance_device_keys_company_fingerprint",
    } <= unique_names
    index_names = {index.name for index in RemoteAssistanceDeviceKey.__table__.indexes}
    assert {
        "uq_remote_assistance_device_keys_installation_active",
        "uq_remote_assistance_device_keys_installation_pending",
    } <= index_names
    checks = {
        constraint.name: str(constraint.sqltext)
        for constraint in RemoteAssistanceDeviceKey.__table__.constraints
        if isinstance(constraint, CheckConstraint)
    }
    assert "status = 'revoked'" in checks["ck_remote_assistance_device_keys_revocation_evidence"]
    assert (
        "approved_by_user_id IS NOT NULL"
        in checks["ck_remote_assistance_device_keys_approval_evidence"]
    )


def test_remote_assistance_writes_emit_realtime_wakeup_and_audit_invalidation() -> None:
    path = "/api/v1/remote-assistance/device/grants/grant-id/decision"
    assert resource_for_path(path) == "remote_assistance"
    assert resources_for_path(path) == ("remote_assistance", "audit")


def test_official_production_docs_do_not_bypass_code17_safety_boundaries() -> None:
    root = Path(__file__).resolve().parents[3]
    free_deploy = (root / "docs" / "FREE_DEPLOY.md").read_text(encoding="utf-8")
    live_deploy = (root / "docs" / "DEPLOY_LIVE.md").read_text(encoding="utf-8")
    distribution = (root / "docs" / "DISTRIBUTION.md").read_text(encoding="utf-8")
    cloud_deploy = (root / "docs" / "CLOUD_DEPLOY.md").read_text(encoding="utf-8")

    for guide in (free_deploy, live_deploy):
        assert "--maintenance-confirmed" in guide
        assert "scripts.reset_owner_password" in guide
        assert "--role owner --password" not in guide
        assert "up -d --build" not in guide
    assert "or read the protected audit log" in live_deploy
    assert "Audit-log authority remains restricted to the protected owner" in live_deploy
    assert "up -d --build" not in distribution
    assert "Code17 status: unsupported planning document" in cloud_deploy


def test_localhost_sandbox_and_retired_quickstarts_fail_closed() -> None:
    root = Path(__file__).resolve().parents[3]
    compose = (root / "docker-compose.yml").read_text(encoding="utf-8")
    env_example = (root / ".env.example").read_text(encoding="utf-8")
    mac_installer = (root / "Install D Company ERP (Mac).command").read_text(
        encoding="utf-8"
    )
    mac_guide = (root / "docs" / "RUN_IT_REAL.md").read_text(encoding="utf-8")
    deployment = (root / "docs" / "DEPLOYMENT.md").read_text(encoding="utf-8")
    agent_guide = (root / "AGENTS.md").read_text(encoding="utf-8")
    quickstarts = tuple(
        (root / name).read_text(encoding="utf-8")
        for name in ("QUICKSTART_DO.md", "QUICKSTART_FREE.md")
    )

    for port in (5432, 6379, 9000, 9001, 8000, 5173):
        assert f'"127.0.0.1:{port}:' in compose
    assert "localhost-only developer sandbox" in compose
    assert "LOCAL DEVELOPMENT ONLY" in env_example
    assert "business@retrocafe.online" not in env_example
    assert "OWNER_PASSWORD_DISPLAY" not in mac_installer
    assert "Owner pwd:" not in mac_installer
    assert "docker compose down -v" not in mac_installer
    assert "http://localhost:8000/readyz" in mac_installer
    assert "scripts.reset_owner_password" in mac_guide
    assert "Never delete volumes to recover a credential" in mac_guide
    assert "unsupported planning document" in deployment
    assert "docker compose -f docker-compose.prod.yml --env-file .env up -d" not in agent_guide
    assert "rsync -avz --quiet" not in agent_guide
    assert "--maintenance-confirmed" in agent_guide
    for guide in quickstarts:
        assert "retired for Code17" in guide
        assert "up -d --build" not in guide
