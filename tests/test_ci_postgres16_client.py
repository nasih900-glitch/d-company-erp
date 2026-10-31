"""Exercise the real selector against newer wrappers without installing packages."""
from __future__ import annotations

import os
from pathlib import Path
import subprocess
import tempfile
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[1]
SELECTOR = ROOT / "infra/scripts/select-ci-postgres16-client.sh"
CLIENTS = ("psql", "pg_dump", "pg_restore")


class CiPostgres16ClientTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.versioned = self.root / "postgresql/16/bin"
        self.wrappers = self.root / "wrappers"
        self.versioned.mkdir(parents=True)
        self.wrappers.mkdir()
        self.path_file = self.root / "github-path"
        self.path_file.touch()
        for client in CLIENTS:
            self.write_tool(self.versioned, client, "16.15")
            self.write_tool(self.wrappers, client, "18.0")
        self.environment = {
            **os.environ,
            "PATH": str(self.wrappers) + os.pathsep + os.environ["PATH"],
            "GITHUB_PATH": str(self.path_file),
        }

    @staticmethod
    def write_tool(directory: Path, client: str, version: str) -> None:
        path = directory / client
        path.write_text(
            '#!/bin/sh\n[ "$#" -eq 1 ] && [ "$1" = --version ] || exit 73\n'
            f"printf '%s\\n' '{client} (PostgreSQL) {version}'\n",
            encoding="utf-8",
        )
        path.chmod(0o755)

    def select(self, prefix: str = "") -> subprocess.CompletedProcess[str]:
        # Source the actual implementation; the guarded CI entrypoint is not run.
        return subprocess.run(
            ["bash", "-c", 'set -euo pipefail; source "$1"; ' + prefix
             + 'select_postgres16_client "$2"; '
             + 'for client in psql pg_dump pg_restore; do command -v "$client"; done',
             "selector-test", str(SELECTOR), str(self.versioned)],
            env=self.environment, text=True, capture_output=True, check=False,
        )

    def test_versioned_clients_replace_cached_18_wrappers_and_persist_to_next_step(self) -> None:
        result = self.select('psql --version; pg_dump --version; pg_restore --version; ')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("psql (PostgreSQL) 18.0", result.stdout)
        self.assertEqual(
            [str(self.versioned / client) for client in CLIENTS],
            result.stdout.splitlines()[-3:],
        )
        self.assertEqual(str(self.versioned) + "\n", self.path_file.read_text())
        next_environment = {
            **self.environment,
            "PATH": self.path_file.read_text().strip() + os.pathsep + self.environment["PATH"],
        }
        next_step = subprocess.run(
            ["bash", "-c", 'set -e; for client in psql pg_dump pg_restore; do '
             'command -v "$client"; "$client" --version; done'],
            env=next_environment, text=True, capture_output=True, check=False,
        )
        self.assertEqual(0, next_step.returncode, next_step.stderr)
        self.assertNotIn("18.0", next_step.stdout)
        for client in CLIENTS:
            self.assertIn(str(self.versioned / client), next_step.stdout)
            self.assertIn(f"{client} (PostgreSQL) 16.15", next_step.stdout)

    def test_wrong_major_for_any_client_stops_before_path_publication(self) -> None:
        for client in CLIENTS:
            for version in ("18.0", "15.9", "160.1"):
                with self.subTest(client=client, version=version):
                    self.write_tool(self.versioned, client, version)
                    self.path_file.write_text("")
                    result = self.select()
                    self.assertNotEqual(0, result.returncode)
                    self.assertIn(f"Expected PostgreSQL 16 {client}", result.stderr)
                    self.assertEqual("", self.path_file.read_text())
                    self.write_tool(self.versioned, client, "16.15")

    def test_missing_client_cannot_fall_back_to_wrapper18(self) -> None:
        for client in CLIENTS:
            with self.subTest(client=client):
                (self.versioned / client).unlink()
                result = self.select()
                self.assertNotEqual(0, result.returncode)
                self.assertIn("client is missing", result.stderr)
                self.assertEqual("", self.path_file.read_text())
                self.write_tool(self.versioned, client, "16.15")

    def test_shell_function_cannot_win_over_exact_versioned_binary(self) -> None:
        result = self.select('pg_dump() { printf "%s\\n" "pg_dump (PostgreSQL) 16.15"; }; ')
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Unexpected PostgreSQL client path", result.stderr)
        self.assertEqual("", self.path_file.read_text())

    def test_entrypoint_is_fixed_to_pg16_and_refuses_non_ci_execution(self) -> None:
        source = SELECTOR.read_text()
        self.assertIn("select_postgres16_client /usr/lib/postgresql/16/bin", source)
        environment = {**self.environment, "CI": "false", "GITHUB_ACTIONS": "false"}
        result = subprocess.run(
            ["bash", str(SELECTOR)], env=environment, text=True,
            capture_output=True, check=False,
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("restricted to GitHub Actions CI", result.stderr)

    def test_both_workflows_install_and_select_before_contracts_and_database_work(self) -> None:
        for workflow, job in (("ci.yml", "backend"), ("release.yml", "coordinated-release-gates")):
            with self.subTest(workflow=workflow):
                document = yaml.safe_load((ROOT / ".github/workflows" / workflow).read_text())
                self.assertEqual("ubuntu-24.04", document["jobs"][job]["runs-on"])
                steps = document["jobs"][job]["steps"]
                installs = [index for index, step in enumerate(steps)
                            if "apt-get install" in step.get("run", "")
                            and "postgresql-client-16" in step["run"]]
                selectors = [index for index, step in enumerate(steps)
                             if step.get("run") == "bash infra/scripts/select-ci-postgres16-client.sh"]
                self.assertEqual(1, len(installs))
                self.assertEqual(1, len(selectors))
                self.assertLess(installs[0], selectors[0])
                consumers = [index for index, step in enumerate(steps)
                             if any(command in step.get("run", "")
                                    for command in ("pytest", "alembic upgrade"))]
                self.assertTrue(consumers)
                for index in consumers:
                    self.assertLess(selectors[0], index)


if __name__ == "__main__":
    unittest.main()
