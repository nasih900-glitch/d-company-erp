from __future__ import annotations

import importlib.util
import subprocess
import sys
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]
RUNNER = ROOT / "scripts" / "verify_image_archive_parser_python312.py"
SPEC = importlib.util.spec_from_file_location("image_archive_parser_python312", RUNNER)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("cannot load Python 3.12 archive compatibility runner")
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


@pytest.mark.parametrize(
    "completed",
    [
        subprocess.CompletedProcess([], -9, "", ""),
        subprocess.CompletedProcess([], 137, "", "Killed"),
        subprocess.CompletedProcess([], 1, "", "unrelated failure"),
        subprocess.CompletedProcess(
            [],
            1,
            "python312_runtime_image_id=sha256:bad\n",
            "Archive identity verification failed for python312: expected diagnostic",
        ),
    ],
)
def test_negative_case_requires_controlled_parser_rejection(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    completed: subprocess.CompletedProcess[str],
) -> None:
    monkeypatch.setattr(MODULE.subprocess, "run", lambda *_args, **_kwargs: completed)

    with pytest.raises(RuntimeError, match="unexpected parser rejection"):
        MODULE._run(
            tmp_path / "archive.tar",
            "sha256:" + "0" * 64,
            expected_diagnostic="expected diagnostic",
        )


def test_negative_case_accepts_only_exit_one_and_expected_diagnostic(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    completed = subprocess.CompletedProcess(
        [],
        1,
        "",
        "Archive identity verification failed for python312: expected diagnostic\n",
    )
    monkeypatch.setattr(MODULE.subprocess, "run", lambda *_args, **_kwargs: completed)

    assert MODULE._run(
        tmp_path / "archive.tar",
        "sha256:" + "0" * 64,
        expected_diagnostic="expected diagnostic",
    ) == {}
