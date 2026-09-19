"""Regression contract for the retired direct Google Sheets sink."""

from __future__ import annotations

import ast
from pathlib import Path

from app.services.integrations import (
    google_sheets as legacy_sink,
)
from app.services.integrations import (
    google_sheets_mirror,
)

ROOT = Path(__file__).resolve().parents[2]
LEGACY_SINK_PATH = ROOT / "app/services/integrations/google_sheets.py"


def test_legacy_direct_sink_is_an_inert_compatibility_marker() -> None:
    source = LEGACY_SINK_PATH.read_text(encoding="utf-8")
    tree = ast.parse(source)

    assert legacy_sink.LEGACY_DIRECT_SINK_ENABLED is False
    assert legacy_sink.__all__ == ["LEGACY_DIRECT_SINK_ENABLED"]
    assert not any(
        isinstance(node, (ast.Import, ast.ImportFrom)) for node in ast.walk(tree)
    )
    assert not any(
        isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef))
        for node in ast.walk(tree)
    )
    assert not any(isinstance(node, ast.Call) for node in ast.walk(tree))


def test_durable_outbox_is_the_only_backend_delivery_route() -> None:
    assert callable(google_sheets_mirror.enqueue_google_sheets_event)
    assert callable(google_sheets_mirror.enqueue_google_sheets_event_if_enabled)
    assert callable(google_sheets_mirror.GoogleSheetsMirrorDispatcher)

    for relative_path in (
        "app/api/v1/finance/router.py",
        "app/api/v1/memberships/router.py",
        "app/api/v1/pos/router.py",
        "app/api/v1/settings/google_sheets.py",
        "app/main.py",
    ):
        source = (ROOT / relative_path).read_text(encoding="utf-8")
        assert "services.integrations.google_sheets import" not in source
        assert "services.integrations import google_sheets" not in source

    for relative_path in (
        "app/api/v1/finance/router.py",
        "app/api/v1/memberships/router.py",
        "app/api/v1/pos/router.py",
    ):
        source = (ROOT / relative_path).read_text(encoding="utf-8")
        assert "enqueue_google_sheets_event_if_enabled" in source

    runtime_source = (ROOT / "app/main.py").read_text(encoding="utf-8")
    assert "maintain_google_sheets_mirror" in runtime_source
