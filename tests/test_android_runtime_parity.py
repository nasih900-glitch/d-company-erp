from __future__ import annotations

import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import yaml

from ops import runtime_release_parity as parity

ROOT = Path(__file__).resolve().parents[1]
VERSION = "3.1.14"
REVISION = "a" * 40
IMAGE = "sha256:" + "b" * 64
CONTAINER = "c" * 64


class RuntimeParityTest(unittest.TestCase):
    def replies(self, *, revision=REVISION, healthy=True, count=1):
        def reply(command):
            if "config" in command:
                return json.dumps({"services": {name: {"image": f"erp-{name}:{REVISION}"}
                                               for name in parity.RELEASE_IMAGE_SERVICES}})
            if "ps" in command:
                return "\n".join([CONTAINER] * count)
            if command[:2] == ["docker", "inspect"]:
                return json.dumps([{"Id": CONTAINER, "Image": IMAGE, "State": {
                    "Running": True, "Paused": False, "Restarting": False,
                    "Health": {"Status": "healthy" if healthy else "unhealthy"}}}])
            if command[:3] == ["docker", "image", "inspect"]:
                return json.dumps([{"Id": IMAGE, "Config": {"Labels": {
                    "org.opencontainers.image.version": VERSION,
                    "org.opencontainers.image.revision": revision}}}])
            raise AssertionError(f"Unexpected command: {command}")
        return reply

    def test_running_release_uses_actual_immutable_images_and_health(self):
        with patch.object(parity, "_run", side_effect=self.replies()) as run:
            result = parity.inspect_release_pair("/erp", "/erp/.env", VERSION, REVISION, running=True)
        self.assertEqual(IMAGE, result["services"]["backend"]["image_id"])
        self.assertEqual(CONTAINER, result["services"]["frontend"]["container_id"])
        self.assertEqual(set(parity.RELEASE_IMAGE_SERVICES), set(result["services"]))
        self.assertTrue(any(call.args[0] == ["docker", "image", "inspect", IMAGE]
                            for call in run.call_args_list))

    def test_candidate_resolves_build_refs_not_old_running_compose_images(self):
        with patch.object(parity, "_run", side_effect=self.replies()) as run:
            result = parity.inspect_release_pair("/erp", "/candidate.env", VERSION, REVISION, running=False)
        self.assertEqual(f"erp-backend:{REVISION}", result["services"]["backend"]["image_ref"])
        for call in run.call_args_list:
            self.assertNotIn("ps", call.args[0])
            self.assertNotIn("images", call.args[0])

    def test_isolated_runtime_project_is_passed_as_an_argv_value(self):
        project = "code25-runtime-123-1"
        with patch.object(parity, "_run", side_effect=self.replies()) as run:
            parity.inspect_release_pair(
                "/erp", "/candidate.env", VERSION, REVISION,
                running=False, project_name=project,
            )
        self.assertIn(["docker", "compose", "-p", project], [call.args[0][:4] for call in run.call_args_list])

        for invalid in ("Bad Project", "-option", "a" * 64):
            with self.subTest(invalid=invalid), patch.object(parity, "_run") as run:
                with self.assertRaises(parity.RuntimeParityError):
                    parity.inspect_release_pair(
                        "/erp", "/candidate.env", VERSION, REVISION,
                        running=False, project_name=invalid,
                    )
                run.assert_not_called()

    def test_wrong_revision_is_rejected_without_mutating_commands(self):
        with patch.object(parity, "_run", side_effect=self.replies(revision="d" * 40)) as run:
            with self.assertRaisesRegex(parity.RuntimeParityError, "does not match"):
                parity.inspect_release_pair("/erp", "/erp/.env", VERSION, REVISION, running=True)
        for call in run.call_args_list:
            self.assertFalse(set(call.args[0]) & {"up", "stop", "restart", "build", "exec", "cp"})

    def test_unhealthy_or_multiple_or_missing_containers_fail_closed(self):
        for options in ({"healthy": False}, {"count": 0}, {"count": 2}):
            with self.subTest(options=options), patch.object(parity, "_run", side_effect=self.replies(**options)):
                with self.assertRaises(parity.RuntimeParityError):
                    parity.inspect_release_pair("/erp", "/erp/.env", VERSION, REVISION, running=True)

    def test_post_cutover_images_must_equal_pre_cutover_candidate(self):
        with patch.object(parity, "_run", side_effect=self.replies()):
            with self.assertRaisesRegex(parity.RuntimeParityError, "differs from the verified candidate"):
                parity.inspect_release_pair("/erp", "/erp/.env", VERSION, REVISION,
                    running=True, expected_images={"services": {"backend": {"image_id": "sha256:" + "d" * 64}}})

    def test_internal_pre_ingress_subset_is_explicit_and_validated(self):
        services = ("postgres", "backend", "frontend")
        with patch.object(parity, "_run", side_effect=self.replies()):
            result = parity.inspect_release_pair(
                "/erp", "/erp/.env", VERSION, REVISION, running=True,
                services=services,
            )
        self.assertEqual(set(services), set(result["services"]))

        for invalid in ((), ("backend", "backend"), ("redis",)):
            with self.subTest(invalid=invalid), patch.object(parity, "_run") as run:
                with self.assertRaises(parity.RuntimeParityError):
                    parity.inspect_release_pair(
                        "/erp", "/erp/.env", VERSION, REVISION, running=True,
                        services=invalid,
                    )
                run.assert_not_called()

    def test_invalid_identity_fails_before_any_docker_command(self):
        for version, revision in (("dev", REVISION), ("03.1.14", REVISION), (VERSION, "unknown"), (VERSION, "A" * 40), ("0.0.0", "0" * 40), (VERSION, "0" * 40)):
            with patch.object(parity, "_run") as run:
                with self.assertRaises(parity.RuntimeParityError):
                    parity.inspect_release_pair("/erp", "/erp/.env", version, revision, running=True)
                run.assert_not_called()

    def test_identity_generator_produces_strict_read_only_json(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "identity.json"
            subprocess.run(["sh", str(ROOT / "infra/docker/write-release-identity.sh"), VERSION, REVISION, str(path)], check=True)
            self.assertEqual({"version_name": VERSION, "source_git_sha": REVISION}, json.loads(path.read_text()))
            self.assertEqual(0o444, path.stat().st_mode & 0o777)
            for version, revision in (("dev", REVISION), (VERSION, "unknown"), (VERSION, "A" * 40), (VERSION + "\ninjected", REVISION), (VERSION, REVISION + "\ninjected")):
                invalid = Path(tmp) / "invalid.json"
                result = subprocess.run(["sh", str(ROOT / "infra/docker/write-release-identity.sh"), version, revision, str(invalid)], capture_output=True)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(invalid.exists())

    def test_image_and_installer_contracts(self):
        backend = (ROOT / "infra/docker/backend.Dockerfile").read_text()
        frontend = (ROOT / "infra/docker/frontend.Dockerfile").read_text()
        nginx = (ROOT / "infra/nginx/frontend.conf").read_text()
        installer = (ROOT / "infra/scripts/install-on-vm.sh").read_text()
        self.assertIn("/etc/dcompany/release-identity.json", backend)
        self.assertIn("/usr/share/nginx/html/.well-known/erp-release.json", frontend)
        self.assertIn('test "$VITE_APP_VERSION" = "$APP_VERSION"', frontend)
        self.assertLess(backend.index("write-release-identity.sh \"$APP_VERSION\""), backend.index("USER erp"))
        self.assertIn("location = /.well-known/erp-release.json", nginx)
        self.assertIn('add_header Cache-Control "no-store" always;', nginx)
        self.assertIn('add_header X-Content-Type-Options "nosniff" always;', nginx)
        self.assertNotIn('images -q "$candidate_service"', installer)
        candidate_gate = installer.index(
            'python3 "$CANDIDATE_PARITY_TOOL" candidate'
        )
        self.assertLess(candidate_gate, installer.index("stop -t 30 caddy", candidate_gate))
        self.assertLess(
            installer.index('python3 "$CANDIDATE_PARITY_TOOL" running'),
            installer.index("up -d --no-build --pull never caddy"),
        )
        self.assertIn('--expected-images-json "$CANDIDATE_IMAGE_ATTESTATION"', installer)

    def test_ci_and_release_builds_supply_coordinated_version_and_sha(self):
        for name in ("ci.yml", "release.yml"):
            workflow = (ROOT / ".github/workflows" / name).read_text()
            self.assertIn("APP_REVISION: ${{ github.sha }}", workflow)
            self.assertIn('--build-arg APP_VERSION="$APP_VERSION" --build-arg APP_REVISION="$APP_REVISION"', workflow)
            self.assertIn('--build-arg VITE_APP_VERSION="$APP_VERSION"', workflow)

    def test_dev_compose_uses_explicit_non_release_identity_only(self):
        dev = (ROOT / "docker-compose.yml").read_text()
        prod = (ROOT / "docker-compose.prod.yml").read_text()
        self.assertEqual(2, sum(line.strip() == 'APP_VERSION: "0.0.0"' for line in dev.splitlines()))
        self.assertEqual(2, dev.count('APP_REVISION: "' + "0" * 40 + '"'))
        self.assertNotIn('"' + "0" * 40 + '"', prod)
        self.assertIn('APP_REVISION: ${APP_REVISION:-unknown}', prod)

    def test_production_postgres_healthcheck_waits_for_final_postmaster(self):
        compose = yaml.safe_load((ROOT / "docker-compose.prod.yml").read_text())
        healthcheck = compose["services"]["postgres"]["healthcheck"]["test"]

        # The official image exposes a temporary initialization server on a
        # fresh volume. Compose must not release dependent migrations until the
        # entrypoint has replaced PID 1 with the final postmaster.
        self.assertEqual("CMD-SHELL", healthcheck[0])
        command = healthcheck[1]
        self.assertIn('test -s "$$PGDATA/postmaster.pid"', command)
        self.assertIn(
            'test "$$(sed -n 1p "$$PGDATA/postmaster.pid")" = 1',
            command,
        )
        self.assertLess(
            command.index('test -s "$$PGDATA/postmaster.pid"'),
            command.index("pg_isready -U erp -d erp"),
        )

    def test_backend_runtime_receives_the_same_identity_as_its_image_build(self):
        compose = (ROOT / "docker-compose.prod.yml").read_text()
        backend = compose.split("\n  backend:\n", 1)[1].split("\n  frontend:\n", 1)[0]
        build, runtime = backend.split("    environment:\n", 1)
        for field in ("APP_VERSION", "APP_REVISION"):
            def value(section):
                declarations = [line.strip().split(": ", 1)[1]
                                for line in section.splitlines()
                                if line.strip().startswith(field + ": ")]
                self.assertEqual(1, len(declarations), f"{field} must have one explicit declaration")
                return declarations[0]
            self.assertEqual(value(build), value(runtime))
        settings = (ROOT / "backend/app/core/config.py").read_text()
        self.assertIn("read_backend_build_identity()", settings)
        self.assertIn("if baked != declared:", settings)

    def test_runtime_gate_preserves_redacted_diagnostics_before_cleanup(self):
        source = (
            ROOT / "infra" / "scripts" / "verify-production-runtime-images.sh"
        ).read_text()
        diagnostics = source.split("emit_failure_diagnostics() {", 1)[1].split(
            "\ncleanup() {", 1
        )[0]
        cleanup = source.split("cleanup() {", 1)[1].split("\n}\ntrap cleanup EXIT", 1)[0]

        self.assertIn('"${compose[@]}" ps --all', diagnostics)
        self.assertIn(
            '"${compose[@]}" logs --no-color --timestamps --tail 500',
            diagnostics,
        )
        self.assertIn('logs.replace(value, "[REDACTED]")', diagnostics)
        self.assertIn("dsn_credentials.sub", diagnostics)
        self.assertNotIn("docker inspect", diagnostics)
        self.assertNotIn("compose[@]} config", diagnostics)
        self.assertIn('if [ "$failure_code" -ne 0 ]', cleanup)
        self.assertLess(
            cleanup.index('emit_failure_diagnostics "$failure_code"'),
            cleanup.index('down --volumes --remove-orphans'),
        )


if __name__ == "__main__":
    unittest.main()
