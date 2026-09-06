"""Guard production Docker contexts against ignored local secrets and data."""

from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CONTAINER_SCAN_ACTION = (
    ROOT / ".github" / "actions" / "scan-production-images" / "action.yml"
)


class DockerContextSafetyTest(unittest.TestCase):
    def test_production_python_dependencies_are_hash_locked(self) -> None:
        requirement_start = r"[A-Za-z0-9_.-]+(?:\[[A-Za-z0-9_,.-]+\])?=="
        for lock_name, minimum_count in (
            ("requirements.lock", 50),
            ("requirements-ci.lock", 75),
        ):
            lock_text = (ROOT / "backend" / lock_name).read_text(encoding="utf-8")
            requirement_blocks = re.split(rf"\n(?={requirement_start})", lock_text)
            pinned_blocks = [
                block
                for block in requirement_blocks
                if re.match(requirement_start, block)
            ]
            self.assertGreater(len(pinned_blocks), minimum_count)
            for block in pinned_blocks:
                first_line = block.splitlines()[0]
                with self.subTest(lock=lock_name, requirement=first_line.rstrip(" \\")):
                    self.assertIn("--hash=sha256:", block)

        dockerfile = (ROOT / "infra" / "docker" / "backend.Dockerfile").read_text(
            encoding="utf-8"
        )
        self.assertNotIn("pip install --upgrade pip", dockerfile)
        self.assertIn(
            "pip install --prefix=/install --only-binary=:all: --require-hashes "
            "-r requirements.lock",
            dockerfile,
        )
        self.assertNotIn("apt-get", dockerfile)
        self.assertNotIn("curl", dockerfile)
        ci = (ROOT / ".github" / "workflows" / "ci.yml").read_text(encoding="utf-8")
        release = (ROOT / ".github" / "workflows" / "release.yml").read_text(
            encoding="utf-8"
        )
        self.assertIn(
            "pip install --only-binary=:all: --require-hashes",
            ci,
        )
        self.assertIn("-r backend/requirements-ci.lock", ci)
        self.assertIn(
            "python -m pip install --only-binary=:all: --require-hashes "
            "-r backend/requirements-ci.lock",
            release,
        )

    def test_git_ignored_secret_and_local_data_classes_are_also_docker_ignored(self) -> None:
        rules = {
            line.strip()
            for line in (ROOT / ".dockerignore").read_text(encoding="utf-8").splitlines()
            if line.strip() and not line.lstrip().startswith("#")
        }
        required = {
            "**/*.key",
            "**/*.pem",
            "**/*.p12",
            "**/*.p8",
            "**/*.jks",
            "**/*.keystore",
            "**/keystore.properties",
            "**/google-services.json",
            "**/GoogleService-Info.plist",
            "**/*.sqlite",
            "**/*.sqlite3",
            "**/*.db",
            "**/*.dump",
            "**/backups",
            "**/secrets",
            "**/release-artifacts",
        }
        self.assertEqual(set(), required - rules)

    def test_production_base_and_service_images_are_digest_pinned(self) -> None:
        dockerfiles = [
            ROOT / "infra" / "docker" / "backend.Dockerfile",
            ROOT / "infra" / "docker" / "frontend.Dockerfile",
        ]
        for dockerfile in dockerfiles:
            for line in dockerfile.read_text(encoding="utf-8").splitlines():
                if line.startswith("FROM "):
                    image = line.split()[1]
                    with self.subTest(file=dockerfile.name, image=image):
                        self.assertRegex(image, r"@sha256:[0-9a-f]{64}$")

        compose = (ROOT / "docker-compose.prod.yml").read_text(encoding="utf-8")
        external_images = [
            match.group(1)
            for match in re.finditer(r"^\s+image:\s+([^$\s][^\s]*)\s*$", compose, re.MULTILINE)
            if not match.group(1).startswith("d-company-erp-")
        ]
        self.assertGreaterEqual(len(external_images), 4)
        for image in external_images:
            with self.subTest(image=image):
                self.assertRegex(image, r"@sha256:[0-9a-f]{64}$")

        for workflow_name in ("ci.yml", "release.yml"):
            workflow = (ROOT / ".github" / "workflows" / workflow_name).read_text(
                encoding="utf-8"
            )
            for image in re.findall(
                r"(?:image:\s+|docker run(?:\s+--\S+(?:\s+\S+)?)*\s+)"
                r"((?:postgres|redis|caddy):[^\s]+)",
                workflow,
            ):
                with self.subTest(workflow=workflow_name, image=image):
                    self.assertRegex(image, r"@sha256:[0-9a-f]{64}$")

    def test_exact_built_images_have_blocking_pinned_sbom_and_cve_scans(self) -> None:
        action = CONTAINER_SCAN_ACTION.read_text(encoding="utf-8")
        self.assertIn(
            "anchore/sbom-action@e22c389904149dbc22b58101806040fa8d37a610",
            action,
        )
        self.assertIn(
            "anchore/scan-action@27805bf3b4e84b4a5c980df22ed233c00390a439",
            action,
        )
        self.assertEqual(6, action.count("syft-version: v1.42.3"))
        self.assertEqual(6, action.count("grype-version: v0.118.0"))
        self.assertEqual(6, action.count("severity-cutoff: high"))
        self.assertEqual(6, action.count("fail-build: true"))
        self.assertEqual(6, action.count("only-fixed: false"))
        for component in ("backend", "frontend", "caddy", "postgres", "redis", "minio"):
            with self.subTest(component=component):
                self.assertIn(f"{component}_image_id={{{{.Id}}}}", action)
                self.assertIn(f"{component}.spdx.json", action)
                self.assertIn(f"{component}-grype.json", action)
        self.assertNotIn("continue-on-error", action)

        for workflow_name, backend_tag, frontend_tag in (
            ("ci.yml", "erp-backend:ci", "erp-frontend:ci"),
            ("release.yml", "erp-backend:release-gate", "erp-frontend:release-gate"),
        ):
            workflow = (ROOT / ".github" / "workflows" / workflow_name).read_text(
                encoding="utf-8"
            )
            scan = workflow.index("uses: ./.github/actions/scan-production-images")
            self.assertLess(workflow.index(f"-t {backend_tag}"), scan)
            self.assertLess(workflow.index(f"-t {frontend_tag}"), scan)
            self.assertIn(f"backend-image: {backend_tag}", workflow[scan:])
            self.assertIn(f"frontend-image: {frontend_tag}", workflow[scan:])
            for component in ("caddy", "postgres", "redis", "minio"):
                self.assertRegex(
                    workflow[scan:],
                    rf"{component}-image: [^\n]+@sha256:[0-9a-f]{{64}}",
                )
            self.assertIn(
                "--build-arg VITE_API_URL=https://dcompany.duckdns.org/api/v1",
                workflow[:scan],
            )


if __name__ == "__main__":
    unittest.main()
