"""Execute the exact physical-runner scan without launching a device or backend."""

import os
import shutil
import subprocess
from pathlib import Path

import pytest


RUNNER = Path(__file__).resolve().parents[1] / "scripts/run_code26_physical_business_audit.sh"


def _scan_block() -> str:
    source = RUNNER.read_text(encoding="utf-8")
    start = source.index("RUNTIME_SCAN_RC=0\n")
    end = source.index('\nset +e\n"$PYTHON" "$SCRIPT_DIR/analyze_code26_physical_evidence.py"', start)
    return source[start:end]


@pytest.mark.parametrize("scan_exit,expected_clean", [(0, "false"), (1, "true"), (2, "false")])
def test_runtime_scan_distinguishes_matches_no_matches_and_read_errors(
    tmp_path: Path, scan_exit: int, expected_clean: str,
) -> None:
    artifacts = tmp_path / "artifacts"
    runtime = tmp_path / "runtime"
    binary = tmp_path / "bin"
    for directory in (artifacts, runtime, binary):
        directory.mkdir()
    stub = binary / "rg"
    stub.write_text(
        '#!/bin/sh\nprintf "scan output\\n"\nprintf "scan diagnostic\\n" >&2\n'
        f"exit {scan_exit}\n",
        encoding="utf-8",
    )
    stub.chmod(0o755)
    result = subprocess.run(
        ["bash", "-c", 'set -euo pipefail\n' + _scan_block() +
         '\nprintf "%s:%s\\n" "$RUNTIME_SCAN_RC" "$RUNTIME_SCAN_CLEAN"'],
        env={**os.environ, "ARTIFACT_DIR": str(artifacts), "RUNTIME_DIR": str(runtime),
             "PATH": str(binary) + os.pathsep + os.environ["PATH"]},
        capture_output=True, text=True, check=True,
    )
    assert result.stdout.strip() == f"{scan_exit}:{expected_clean}"
    assert (artifacts / "runtime-failure-scan.txt").read_text() == "scan output\n"
    assert (runtime / "runtime-failure-scan-stderr.txt").read_text() == "scan diagnostic\n"
    assert ("acceptance fails closed" in result.stderr) is (scan_exit > 1)


def test_runtime_scan_ignores_its_own_outputs_without_hiding_real_logs(tmp_path: Path) -> None:
    assert shutil.which("rg"), "Physical release verification requires ripgrep"
    artifacts = tmp_path / "artifacts"
    runtime = tmp_path / "runtime"
    artifacts.mkdir()
    runtime.mkdir()
    log = runtime / "device.log"
    log.write_text("Normal application startup\n", encoding="utf-8")
    environment = {**os.environ, "ARTIFACT_DIR": str(artifacts), "RUNTIME_DIR": str(runtime)}
    command = ['bash', '-c', 'set -euo pipefail\n' + _scan_block() +
               '\nprintf "%s:%s\\n" "$RUNTIME_SCAN_RC" "$RUNTIME_SCAN_CLEAN"']
    clean = subprocess.run(command, env=environment, capture_output=True, text=True, check=True)
    assert clean.stdout.strip() == "1:true"
    log.write_text("ANR in cloud.dcompany.erp.physicalaudit\n", encoding="utf-8")
    failed = subprocess.run(command, env=environment, capture_output=True, text=True, check=True)
    assert failed.stdout.strip() == "0:false"
    assert "ANR in cloud.dcompany.erp.physicalaudit" in (artifacts / "runtime-failure-scan.txt").read_text()
