"""Static safety and scope gates for the disposable Code 26 physical lane."""

from __future__ import annotations

import ast
import hashlib
import importlib.util
import json
import struct
import sys
import zlib
from datetime import UTC, datetime, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PLAN_PATH = (
    ROOT / "android-native/audit-driver/plans/code26-gaming-finance-physical.json"
)
FIXTURE_PATH = ROOT / "backend/scripts/physical_audit_fixture.py"
RUNNER_PATH = ROOT / "scripts/run_code26_physical_business_audit.sh"
ANALYZER_PATH = ROOT / "scripts/analyze_code26_physical_evidence.py"

_SPEC = importlib.util.spec_from_file_location("code26_evidence_analyzer", ANALYZER_PATH)
assert _SPEC and _SPEC.loader
_ANALYZER = importlib.util.module_from_spec(_SPEC)
sys.modules[_SPEC.name] = _ANALYZER
_SPEC.loader.exec_module(_ANALYZER)


def _steps() -> list[dict]:
    return json.loads(PLAN_PATH.read_text(encoding="utf-8"))["steps"]


def _load_fixture_function(name: str):
    """Load one dependency-free fixture helper without importing the backend app."""
    source = FIXTURE_PATH.read_text(encoding="utf-8")
    tree = ast.parse(source, filename=str(FIXTURE_PATH))
    function = next(
        node
        for node in tree.body
        if isinstance(node, ast.FunctionDef) and node.name == name
    )
    module = ast.Module(
        body=[
            ast.ImportFrom(
                module="__future__",
                names=[ast.alias(name="annotations")],
                level=0,
            ),
            function,
        ],
        type_ignores=[],
    )
    namespace: dict[str, object] = {}
    exec(compile(ast.fix_missing_locations(module), str(FIXTURE_PATH), "exec"), namespace)
    return namespace[name]


def test_physical_plan_is_bounded_and_contains_no_embedded_authority() -> None:
    steps = _steps()
    rendered = json.dumps(steps, sort_keys=True, ensure_ascii=False)

    assert len(steps) == 410
    assert {step["action"] for step in steps} <= {
        "launch",
        "restart",
        "click",
        "fill",
        "wait",
        "absent",
        "back",
        "home",
        "scroll",
        "offline",
        "online",
        "capture",
        "idleFrames",
        "idleStability",
        "idleSemanticStability",
        "alarmConstraints",
    }
    assert "http://" not in rendered
    assert "https://" not in rendered
    assert "@dcompany" not in rendered
    assert "@physical-audit.test" not in rendered
    credential_steps = [step for step in steps if "valueKey" in step]
    assert {step["valueKey"] for step in credential_steps} == {
        "users.0.email",
        "users.0.password",
    }
    assert all("value" not in step for step in credential_steps)


def test_physical_plan_submits_all_sixteen_supported_sessions() -> None:
    steps = _steps()
    names = [step["name"] for step in steps]
    starts = [
        step
        for step in steps
        if step["action"] == "click"
        and (
            step.get("text", "").startswith("Start ·")
            or step["name"].endswith("start open-ended")
        )
    ]
    payments = [
        step
        for step in steps
        if step["action"] == "click"
        and (
            step.get("text", "").startswith("CONFIRM ")
            or step.get("textRegex", "").startswith("^CONFIRM ")
        )
    ]

    required_prefixes = {
        "Offline Standard Single",
        "Standard Single 60m + 30m extension",
        "Standard Dual 2-player",
        "Standard Dual 3-player",
        "Standard Dual 4-player",
        "Premium Single",
        "Premium Single 60m extension coverage",
        "Premium Dual 2-player",
        "Premium Dual 3-player",
        "Premium Dual 4-player",
        "Simdrive",
        "Simdrive 30m",
        "Simdrive 60m",
        "VR open-ended transfer",
        "Streaming open-ended",
        "Shisha open-ended",
    }
    assert all(any(name.startswith(prefix + ":") for name in names) for prefix in required_prefixes)
    assert len(starts) == 16
    assert len(payments) == 16
    assert sum(step.get("text", "").startswith("CONFIRM CASH") for step in payments) == 2
    assert sum(
        step.get("text", "").startswith("CONFIRM UPI")
        or step.get("textRegex", "").startswith("^CONFIRM UPI")
        for step in payments
    ) == 14
    assert next(
        step for step in steps if step["name"] == "All sixteen receipts loaded"
    )["textContains"] == "16 most recent loaded"


def test_physical_plan_proves_pause_resume_and_every_extension_option() -> None:
    steps = _steps()
    by_name = {step["name"]: (index, step) for index, step in enumerate(steps)}

    pause_names = [
        "Standard Single: open pause reason",
        "Standard Single: enter physical pause reason",
        "Standard Single: pause with reason",
        "Measure paused session layout stability",
        "Standard Single: resume after stable pause",
    ]
    assert [by_name[name][0] for name in pause_names] == sorted(
        by_name[name][0] for name in pause_names
    )
    assert by_name[pause_names[0]][1] == {
        "name": pause_names[0],
        "action": "click",
        "text": "Pause",
        "timeoutMs": 60000,
        "then": {"text": "Pause reason"},
    }
    assert by_name[pause_names[1]][1]["value"] == "Physical audit pause stability"
    assert by_name[pause_names[2]][1]["index"] == 1
    assert by_name[pause_names[2]][1]["then"] == {
        "descriptionContains": "PS5 Station 1. Paused"
    }
    assert by_name[pause_names[3]][1] == {
        "name": pause_names[3],
        "action": "idleSemanticStability",
        "durationMs": 10000,
        "exactStableSemantics": [
            {
                "id": "ps5_station_1_paused_timer",
                "attribute": "text",
                "fullmatch": "[0-9]{2}:[0-9]{2}:[0-9]{2}",
                "expectedCount": 1,
                "ancestor": {
                    "attribute": "content-desc",
                    "fullmatch": (
                        "PS5 Station 1\\. Paused\\. Standard · Single · "
                        "₹80\\.00 fixed total\\."
                    ),
                },
            }
        ],
    }
    assert by_name[pause_names[4]][1]["then"] == {
        "descriptionContains": "PS5 Station 1. Active"
    }
    # Compose exposes the label as a TextView inside the clickable button
    # ancestor. Requiring the label node itself to be clickable makes a real
    # device wait forever even though the button is visible and operable.
    for name in (pause_names[0], pause_names[2], pause_names[4]):
        assert "clickable" not in by_name[name][1]

    expected_base_codes = {
        "standard-single-session-30m",
        "standard-single-session-60m",
        "standard-dual-session-30m",
        "standard-dual-session-60m",
        "standard-simdrive-session-15m",
        "standard-simdrive-session-30m",
        "standard-simdrive-session-60m",
        "premium-single-session-60m",
        "premium-dual-session-60m",
    }
    expected_extension_codes = {
        "standard-single-extension-30m",
        "standard-single-extension-60m",
        "standard-dual-extension-30m",
        "standard-dual-extension-60m",
        "premium-single-extension-30m",
        "premium-single-extension-60m",
        "premium-dual-extension-30m",
        "premium-dual-extension-60m",
    }
    base_submits = [
        step
        for step in steps
        if "-session-" in step.get("packageCode", "")
    ]
    extension_submits = [
        step
        for step in steps
        if "-extension-" in step.get("packageCode", "")
    ]
    assert len(base_submits) == 13
    assert len(extension_submits) == 9
    assert {step["packageCode"] for step in base_submits} == expected_base_codes
    assert {step["packageCode"] for step in extension_submits} == expected_extension_codes
    assert all(
        step["action"] == "click" and step.get("text", "").startswith("Start ·")
        for step in base_submits
    )
    assert all(
        step["action"] == "click" and step.get("text", "").startswith("Add ·")
        for step in extension_submits
    )


def test_pause_event_interval_matches_authoritative_millisecond_floor() -> None:
    matches = _load_fixture_function(
        "_pause_event_interval_matches_authoritative_duration"
    )
    pause_at = datetime(2026, 9, 8, 0, 0, 0, 123_456, tzinfo=UTC)

    # Persisted timestamps retain microseconds, while paused_duration_ms is the
    # floor of that interval.  Anything within the same millisecond is valid.
    assert matches(
        pause_at,
        pause_at + timedelta(milliseconds=8_000, microseconds=999),
        8_000,
    )
    assert matches(pause_at, pause_at + timedelta(milliseconds=8_000), 8_000)

    # Crossing either floor boundary, reversing the clock, or omitting the
    # authoritative duration must fail closed.
    assert not matches(
        pause_at,
        pause_at + timedelta(milliseconds=8_001),
        8_000,
    )
    assert not matches(
        pause_at,
        pause_at + timedelta(milliseconds=7_999, microseconds=999),
        8_000,
    )
    assert not matches(pause_at, pause_at - timedelta(microseconds=1), 0)
    assert not matches(pause_at, pause_at + timedelta(milliseconds=8_000), -1)


def test_physical_plan_covers_recovery_finance_receipts_and_cleanup() -> None:
    steps = _steps()
    actions = [step["action"] for step in steps]
    rendered = json.dumps(steps, sort_keys=True, ensure_ascii=False)

    assert actions.count("offline") >= 2
    assert actions.count("online") >= 2
    assert actions.count("restart") >= 2
    assert actions.count("alarmConstraints") == 1
    assert actions.count("idleFrames") == 4
    assert actions.count("idleStability") == 4
    assert actions.count("idleSemanticStability") == 1
    for evidence in (
        "Audit Cola",
        "Audit Crisps",
        "Cancellation reason: Entered by mistake",
        "Physical audit pause stability",
        "Standard Single: resume after stable pause",
        "Apply discount",
        "Receipts (16)",
        "Financial controls",
        "Profit and loss",
        "Daily P&L",
        "Closed by Audit Employee 1",
        "counted ₹780.00 · balanced",
    ):
        assert evidence in rendered


def test_fixture_and_runner_are_fail_closed_and_disposable() -> None:
    fixture = FIXTURE_PATH.read_text(encoding="utf-8")
    runner = RUNNER_PATH.read_text(encoding="utf-8")

    for fixture_guard in (
        "I_UNDERSTAND_THIS_IS_A_DISPOSABLE_LOCAL_DATABASE",
        "dcompany_physical_audit_",
        "@physical-audit.test",
        "zero companies",
        "expected exactly 16 sessions, 16 orders and 16 payments",
        "journal entries are unbalanced",
        "Audit Cola FIFO batch expected 9",
        "The voided Audit Crisps item created a sale stock movement",
        "GamingPauseEvent",
        "pause/resume receipts must preserve the exact action, reason and version",
        "pause/resume audit history is missing its exact actor, reason or session",
        "pause/resume changed the locked package amount or duration snapshot",
        "pause/resume response clock fields are incomplete",
        "_pause_event_interval_matches_authoritative_duration",
        "authoritative_pause_duration_ms",
        "pause_event.occurred_at",
        "resume_event.occurred_at",
    ):
        assert fixture_guard in fixture
    for tariff_code in (
        "standard-single-session-30m",
        "standard-single-session-60m",
        "standard-dual-session-30m",
        "standard-dual-session-60m",
        "standard-simdrive-session-15m",
        "standard-simdrive-session-30m",
        "standard-simdrive-session-60m",
        "premium-single-session-60m",
        "premium-dual-session-60m",
        "standard-single-extension-30m",
        "standard-single-extension-60m",
        "standard-dual-extension-30m",
        "standard-dual-extension-60m",
        "premium-single-extension-30m",
        "premium-single-extension-60m",
        "premium-dual-extension-30m",
        "premium-dual-extension-60m",
    ):
        assert tariff_code in fixture
    for runner_guard in (
        "An explicit --device emulator|firebase is required.",
        "Refusing physical acceptance from dirty source",
        "source-recheck.json",
        "apk-identities.json",
        "apksigner",
        "financial-api-reconciliation.json",
        "PLAN_PACKAGE_STARTS",
        "PLAN_EXTENSION_SUBMITS",
        "PLAN_SEMANTIC_STABILITY",
        "physicalAudit",
        "exec env GAMING_PAUSE_ENABLED=true",
        "dropdb --force --if-exists",
        "production_credentials_used:false",
        "production_data_mutated:false",
        "release_activated:false",
    ):
        assert runner_guard in runner
    assert "d-company-erp-code25-final" not in runner
    assert "code25-physical-tablet-evidence" not in runner
    assert runner.count("GAMING_PAUSE_ENABLED=true") == 1
    assert "export GAMING_PAUSE_ENABLED" not in runner
    assert '--data-binary "$(jq' not in runner
    assert "rendered_finance_reports_match_reconciliation" in ANALYZER_PATH.read_text(
        encoding="utf-8"
    )


def test_firebase_matrix_lookup_uses_supported_authenticated_testing_api() -> None:
    runner = RUNNER_PATH.read_text(encoding="utf-8")

    assert "firebase test android matrices describe" not in runner
    for contract in (
        "https://testing.googleapis.com/v1/projects/",
        "gcloud auth print-access-token",
        "X-Goog-User-Project",
        "Authorization: Bearer %s",
        "--config -",
        "--fail-with-body",
        '.projectId == $project and .testMatrixId == $matrix',
        '.state == "FINISHED" or .state == "ERROR" or .state == "INVALID"',
        "^[a-z][a-z0-9-]{4,28}[a-z0-9]$",
        "^matrix-[A-Za-z0-9_-]+$",
    ):
        assert contract in runner
    assert '-H "Authorization: Bearer $' not in runner


def test_firebase_result_download_is_scoped_verified_and_fail_closed() -> None:
    runner = RUNNER_PATH.read_text(encoding="utf-8")

    assert "require_command gsutil" in runner
    assert 'GCS_OBJECT_PATH="${GCS_WITHOUT_SCHEME#*/}"' in runner
    assert '"$GCS_OBJECT_PATH" != "$RESULTS_DIR"' in runner
    assert 'GCS_SOURCE="${GCS_PATH%/}"' in runner
    assert 'gsutil -m cp -r "$GCS_SOURCE"' in runner
    assert "firebase-results-files.txt" in runner
    assert "firebase-result-apk-sha256.txt" in runner
    assert "Downloaded Firebase APK hash mismatch" in runner
    assert 'immutable_apk="$ARTIFACT_DIR/$expected_name"' in runner
    assert "Immutable APK copy no longer matches recorded identity" in runner
    hash_block = runner.split('immutable_apk="$ARTIFACT_DIR/$expected_name"', 1)[1]
    hash_block = hash_block.split("downloaded_sha=", 1)[0]
    assert 'shasum -a 256 "$immutable_apk"' in hash_block
    assert 'shasum -a 256 "$expected_apk"' not in hash_block
    assert "gcloud storage cp" not in runner
    assert 'firebase-download.log" 2>&1 || true' not in runner


def test_physical_driver_simulates_unplugged_battery_and_restores_it() -> None:
    driver = (
        ROOT
        / "android-native/audit-driver/src/androidTest/java/cloud/dcompany/erp/"
        "auditdriver/BusinessWorkflowDeviceTest.kt"
    ).read_text(encoding="utf-8")

    assert driver.count("waitUntil(timeout) { powerSaveModeEnabled() }") == 1
    assert driver.count("waitUntil(timeout) { !powerSaveModeEnabled() }") == 2
    assert 'private fun powerSaveModeEnabled(): Boolean' in driver
    for contract in (
        "import android.os.PowerManager",
        'File(output, "battery-original.txt")',
        'File(output, "power-original.txt")',
        'checkedShell("battery-unplug.txt", "dumpsys battery unplug")',
        'File(output, "battery-simulated-unplugged.txt")',
        'File(output, "power-simulated-unplugged.txt")',
        'File(output, "battery-saver-enabled-state.txt")',
        'File(output, "battery-saver-disabled-state.txt")',
        'capturedCleanupShell("battery-reset.txt", "dumpsys battery reset")',
        'safeWriteEvidence("battery-restored.txt", restoredBatteryState)',
        'safeWriteEvidence("power-restored.txt", restoredPowerState)',
        'safeWriteEvidence("battery-constraints.json", batteryEvidence.toString(2))',
        '"original_battery_powered"',
        'originalBatteryPowered != null && originalPowerManagerPowered != null',
        'originalBatteryPowered == originalPowerManagerPowered',
        '!batteryEvidence.getBoolean("original_simulation_active")',
        'evidence.put("battery_unplug_command_accepted", true)',
        'evidence.put("battery_saver_enable_command_accepted", true)',
        'evidence.put("battery_saver_disable_command_accepted", true)',
        'batterySimulationActive(battery) && batteryPoweredState(battery) == false',
        'batteryPoweredState(battery) == originalBatteryPowered',
        'powerManagerIsPowered(power) == originalPowerManagerPowered',
        'powerManagerIsPowered(power) == false',
        'evidence.getBoolean("battery_restored_original_power_state")',
        'evidence.getBoolean("battery_simulation_cleared")',
        'evidence.getBoolean("battery_cleanup_evidence_written")',
        'private fun batteryPoweredState(state: String): Boolean?',
        'private fun batterySimulationActive(state: String): Boolean',
        'private fun powerManagerIsPowered(state: String): Boolean?',
        '.getSystemService(PowerManager::class.java)',
        '.isPowerSaveMode',
        'private fun safeWriteEvidence(filename: String, content: String): Boolean',
        'runCatching { File(output, filename).writeText(content) }.isSuccess',
    ):
        assert contract in driver
    battery_block = driver.split('evidence.put("battery_saver_enabled", false)', 1)[1]
    battery_block = battery_block.split('val alarmAfter', 1)[0]
    assert "try {" in battery_block
    assert "} finally {" in battery_block
    assert battery_block.index("dumpsys battery unplug") < battery_block.index(
        "cmd power set-mode 1"
    )
    assert battery_block.index("cmd power set-mode 1") < battery_block.index("} finally {")
    assert battery_block.index("} finally {") < battery_block.index(
        "battery-saver-final-disable.txt"
    )
    assert battery_block.index("battery-saver-final-disable.txt") < battery_block.index(
        "battery-reset.txt"
    )
    assert driver.count('device.executeShellCommand("dumpsys battery reset")') == 1
    assert driver.count('"dumpsys battery reset"') == 2
    assert driver.count(
        'device.executeShellCommand("settings get global low_power").trim()'
    ) == 1
    power_save_method = driver.split(
        "private fun powerSaveModeEnabled(): Boolean =", 1
    )[1].split("private fun alarmRegistered", 1)[0]
    assert "PowerManager::class.java" in power_save_method
    assert "settings get global low_power" not in power_save_method
    assert 'getBoolean("original_powered")' not in battery_block
    assert 'getBoolean("battery_restored_powered")' not in battery_block

    # Cleanup commands are independent: a failure in an earlier restore must not
    # skip the final battery reset, notification restore or credential removal.
    outer_cleanup = driver.split("// Restore the disposable device", 1)[1]
    outer_cleanup = outer_cleanup.split("private fun requireSafeInputPath", 1)[0]
    for command in (
        "cmd connectivity airplane-mode disable",
        "svc wifi enable",
        "cmd deviceidle unforce",
        "cmd power set-mode 0",
        "dumpsys battery reset",
        "pm grant $appPackage android.permission.POST_NOTIFICATIONS",
        "wm dismiss-keyguard",
        "device.unfreezeRotation()",
        'validatedCredentialPath?.let { device.executeShellCommand("rm $it") }',
    ):
        assert command in outer_cleanup
    assert outer_cleanup.count("runCatching") >= 8


def _safe_label(label: str) -> str:
    import re

    return re.sub(r"[^A-Za-z0-9._-]", "-", label)[:110]


def _png(path: Path, *, width: int = 2560, height: int = 1600) -> None:
    # Valid one-bit grayscale PNG with the requested tablet dimensions. The
    # highly repetitive payload remains tiny while exercising chunk/CRC checks.
    def chunk(kind: bytes, payload: bytes) -> bytes:
        return (
            struct.pack(">I", len(payload))
            + kind
            + payload
            + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)
        )

    ihdr = struct.pack(">IIBBBBB", width, height, 1, 0, 0, 0, 0)
    row = b"\0" + (b"\0" * ((width + 7) // 8))
    path.write_bytes(
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(row * height))
        + chunk(b"IEND", b"")
    )


def _hierarchy(
    path: Path,
    *,
    shift_y: int = 0,
    timer: str = "00:01",
    timer_copies: int = 1,
    timer_ancestor_description: str | None = None,
    unrelated_timer: str | None = None,
) -> None:
    rows = [
        ("Screen", 0, 0, 2560, 1600),
        ("Gaming", 80, 100 + shift_y, 400, 180 + shift_y),
        ("Stop session", 500, 500 + shift_y, 900, 590 + shift_y),
        ("Add drinks", 950, 500 + shift_y, 1350, 590 + shift_y),
        ("Online", 1900, 100 + shift_y, 2200, 180 + shift_y),
        ("Audit Employee", 2200, 100 + shift_y, 2520, 180 + shift_y),
    ]
    nodes = "".join(
        f'<node class="android.view.View" text="{text}" content-desc="{text}" '
        f'bounds="[{left},{top}][{right},{bottom}]" />'
        for text, left, top, right, bottom in rows
    )
    timer_nodes = "".join(
        f'<node class="android.widget.TextView" text="{timer}" content-desc="" '
        f'bounds="[{500 + copy * 380},{300 + shift_y}]'
        f'[{850 + copy * 380},{390 + shift_y}]" />'
        for copy in range(timer_copies)
    )
    if timer_ancestor_description is None:
        nodes += timer_nodes
    else:
        nodes += (
            '<node class="android.view.View" text="" '
            f'content-desc="{timer_ancestor_description}" '
            f'bounds="[450,{250 + shift_y}][1300,{430 + shift_y}]">'
            f"{timer_nodes}</node>"
        )
    if unrelated_timer is not None:
        nodes += (
            f'<node class="android.widget.TextView" text="{unrelated_timer}" '
            f'content-desc="" bounds="[1700,{300 + shift_y}]'
            f'[2050,{390 + shift_y}]" />'
        )
    path.write_text(f"<hierarchy>{nodes}</hierarchy>", encoding="utf-8")


def _semantic_hierarchy(path: Path, values: list[str]) -> None:
    rows = [("Screen", 0, 0, 2560, 1600)]
    for index, value in enumerate(values):
        column = index % 3
        row = index // 3
        left = 100 + column * 800
        top = 120 + row * 150
        rows.append((value, left, top, left + 680, top + 100))
    nodes = "".join(
        f'<node class="android.view.View" text="{text}" content-desc="{text}" '
        f'bounds="[{left},{top}][{right},{bottom}]" />'
        for text, left, top, right, bottom in rows
    )
    path.write_text(f"<hierarchy>{nodes}</hierarchy>", encoding="utf-8")


def _frames(path: Path, durations_ms: list[float]) -> None:
    lines = ["Flags,IntendedVsync,FrameCompleted"]
    intended = 1_000_000_000
    for duration in durations_ms:
        lines.append(f"0,{intended},{intended + int(duration * 1_000_000)}")
        intended += 20_000_000
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def _synthetic_evidence(root: Path) -> tuple[list[dict], dict[str, Path]]:
    artifacts = root / "artifacts"
    pulled = root / "device-pull"
    runtime = root / "runtime"
    artifacts.mkdir(parents=True)
    pulled.mkdir()
    runtime.mkdir()
    plan = [
        {"name": "Exercise alarm constraints", "action": "alarmConstraints"},
        {"name": "Measure active timer frames", "action": "idleFrames"},
        {"name": "Finance profit and loss layout rendered", "action": "wait"},
        {"name": "Measure Finance settled layout stability", "action": "idleStability"},
        {"name": "Reports revenue panel rendered", "action": "wait"},
        {"name": "Measure Reports settled layout stability", "action": "idleStability"},
        {
            "name": "Measure paused timer semantic stability",
            "action": "idleSemanticStability",
            "exactStableSemantics": [
                {
                    "id": "ps5_station_1_paused_timer",
                    "attribute": "text",
                    "fullmatch": "[0-9]{2}:[0-9]{2}:[0-9]{2}",
                    "expectedCount": 1,
                    "ancestor": {
                        "attribute": "content-desc",
                        "fullmatch": (
                            "PS5 Station 1\\. Paused\\. Standard · Single · "
                            "₹80\\.00 fixed total\\."
                        ),
                    },
                }
            ],
        },
    ]
    (artifacts / "audit-plan.json").write_text(
        json.dumps({"steps": plan}), encoding="utf-8"
    )
    (pulled / "steps.json").write_text(
        json.dumps(
            [
                {"step": index, "name": step["name"], "status": "passed", "duration_ms": 10}
                for index, step in enumerate(plan, start=1)
            ]
        ),
        encoding="utf-8",
    )
    for index, step in enumerate(plan, start=1):
        base = f"{index:03d}-{_safe_label(step['name'])}"
        _png(pulled / f"{base}.png")
        _hierarchy(pulled / f"{base}.xml")
    frame_paths: dict[str, Path] = {}
    for index, step in enumerate(plan, start=1):
        if step["action"] not in {
            "idleFrames",
            "idleStability",
            "idleSemanticStability",
        }:
            continue
        idle_base = f"{index:03d}-{_safe_label(step['name'])}"
        for phase, timer in (("start", "00:01"), ("mid", "00:02"), ("end", "00:03")):
            _png(pulled / f"idle-{idle_base}-{phase}.png")
            _hierarchy(
                pulled / f"idle-{idle_base}-{phase}.xml",
                timer="00:10:00" if step["action"] == "idleSemanticStability" else timer,
                timer_ancestor_description=(
                    "PS5 Station 1. Paused. Standard · Single · ₹80.00 fixed total."
                    if step["action"] == "idleSemanticStability"
                    else None
                ),
            )
        if step["action"] == "idleFrames":
            frame_path = pulled / f"frames-{idle_base}.txt"
            _frames(frame_path, [10.0] * 40)
            frame_paths["active"] = frame_path

    revenue = 316_252
    cogs = 5_000
    profit = revenue - cogs
    finance_values = [
        "Profit and loss",
        "Net revenue",
        "Less: cost of goods sold",
        "Gross profit",
        "Operating profit",
        "₹3,162.52",
        "₹50.00",
        "₹3,112.52",
    ]
    reports_values = [
        "Revenue",
        "Orders",
        "Net profit",
        "₹3,162.52",
        "16",
        "₹3,112.52",
    ]
    _semantic_hierarchy(
        pulled / "003-Finance-profit-and-loss-layout-rendered.xml", finance_values
    )
    _semantic_hierarchy(pulled / "005-Reports-revenue-panel-rendered.xml", reports_values)
    for phase in ("start", "mid", "end"):
        _semantic_hierarchy(
            pulled / f"idle-004-Measure-Finance-settled-layout-stability-{phase}.xml",
            finance_values,
        )
        _semantic_hierarchy(
            pulled / f"idle-006-Measure-Reports-settled-layout-stability-{phase}.xml",
            reports_values,
        )
    (pulled / "alarm-constraints.json").write_text(
        json.dumps(
            {
                "alarm_registered_before": True,
                "notification_denied": True,
                "notification_regranted": True,
                "screen_locked": True,
                "screen_woken": True,
                "doze_entered": True,
                "doze_exited": True,
                "battery_saver_enabled": True,
                "battery_saver_disabled": True,
                "alarm_registered_after": True,
            }
        ),
        encoding="utf-8",
    )
    (artifacts / "financial-api-reconciliation.json").write_text(
        json.dumps(
            {
                "passed": True,
                "failures": [],
                "expected": {
                    "revenue_minor": revenue,
                    "cogs_minor": cogs,
                    "cash_minor": 28_000,
                    "upi_minor": 288_252,
                    "discount_minor": 2_000,
                    "orders": 16,
                },
                "observed": {
                    "finance": {
                        "accounting_basis": "operational_receipt",
                        "revenue_minor": revenue,
                        "cogs_minor": cogs,
                        "gross_profit_minor": profit,
                        "expenses_minor": 0,
                        "depreciation_minor": 0,
                        "net_profit_minor": profit,
                    },
                    "daily": {
                        "branch_id": "branch-a",
                        "orders_count": 16,
                        "gross_revenue_minor": revenue,
                        "net_revenue_minor": revenue,
                        "revenue": {"discounts_and_points_redeemed_minor": 2_000},
                        "payments_received": {
                            "cash_minor": 28_000,
                            "upi_minor": 288_252,
                            "total_minor": revenue,
                        },
                        "net_payments_received_minor": revenue,
                        "cogs_minor": cogs,
                        "gross_profit_minor": profit,
                        "net_profit_minor": profit,
                    },
                    "monthly": {
                        "branch_id": "branch-a",
                        "orders_count": 16,
                        "gross_revenue_minor": revenue,
                        "net_revenue_minor": revenue,
                        "revenue": {"discounts_and_points_redeemed_minor": 2_000},
                        "payments_received": {
                            "cash_minor": 28_000,
                            "upi_minor": 288_252,
                            "total_minor": revenue,
                        },
                        "net_payments_received_minor": revenue,
                        "cogs_minor": cogs,
                        "gross_profit_minor": profit,
                        "net_profit_minor": profit,
                    },
                },
            }
        ),
        encoding="utf-8",
    )
    (artifacts / "external-device-gates.json").write_text(
        json.dumps(
            {
                "required_external": [
                    "redmi_reboot_alarm_delivery",
                    "redmi_lock_screen_alarm_delivery",
                    "redmi_notification_denial_recovery",
                    "redmi_oem_battery_optimisation",
                    "signed_in_place_upgrade",
                ]
            }
        ),
        encoding="utf-8",
    )
    commit = "a" * 40
    tree = "b" * 40
    (artifacts / "source-identity.json").write_text(
        json.dumps(
            {
                "commit": commit,
                "tree": tree,
                "clean": True,
                "version_code": 26,
                "version_name": "3.1.15",
            }
        ),
        encoding="utf-8",
    )
    (artifacts / "source-recheck.json").write_text(
        json.dumps(
            {
                "before_commit": commit,
                "after_commit": commit,
                "before_tree": tree,
                "after_tree": tree,
                "clean_after": True,
                "unchanged": True,
            }
        ),
        encoding="utf-8",
    )
    apk_rows = []
    for file_name, package, code, version in (
        (
            "app-physicalAudit.apk",
            "cloud.dcompany.erp.physicalaudit",
            "26",
            "3.1.15-physical-audit",
        ),
        ("audit-driver-debug.apk", "cloud.dcompany.erp.auditdriver", "1", "1-test-only"),
        (
            "audit-driver-debug-androidTest.apk",
            "cloud.dcompany.erp.auditdriver.test",
            "",
            "",
        ),
    ):
        apk_payload = f"synthetic {file_name}".encode()
        (artifacts / file_name).write_bytes(apk_payload)
        apk_sha = hashlib.sha256(apk_payload).hexdigest()
        signer_sha = "d" * 64
        (runtime / f"apksigner-{file_name}.txt").write_text(
            f"Verifies\nSigner #1 certificate SHA-256 digest: {signer_sha}\n",
            encoding="utf-8",
        )
        (runtime / f"aapt-{file_name}.txt").write_text(
            f"package: name='{package}' versionCode='{code}' versionName='{version}'\n",
            encoding="utf-8",
        )
        apk_rows.append(
            {
                "file": file_name,
                "sha256": apk_sha,
                "signer_certificate_sha256": signer_sha,
                "package_name": package,
                "version_code": code,
                "version_name": version,
                "source_commit": commit,
                "signature_verified": True,
                "copied_hash_verified": True,
            }
        )
    (artifacts / "apk-identities.json").write_text(
        json.dumps({"source_commit": commit, "source_tree": tree, "apks": apk_rows}),
        encoding="utf-8",
    )
    (runtime / "instrumentation.txt").write_text("OK (1 test)\n", encoding="utf-8")
    return plan, frame_paths


def _analyze(root: Path, plan: list[dict], *, lane: str = "emulator") -> dict:
    return _ANALYZER.analyze(
        root,
        expected_steps=len(plan),
        expected_frames=sum(step["action"] == "idleFrames" for step in plan),
        lane=lane,
    )


def test_evidence_analyzer_accepts_complete_named_stable_fixture(tmp_path: Path) -> None:
    plan, _frame_path = _synthetic_evidence(tmp_path)
    result = _analyze(tmp_path, plan)
    assert result["passed"], result
    assert result["gates"]["exact_idle_semantics_stable"] is True
    semantic_window = next(
        window
        for window in result["layout_stability"]["windows"]
        if window["name"] == "Measure paused timer semantic stability"
    )
    assert semantic_window["exact_semantics"][0]["values"] == {
        "start": ["00:10:00"],
        "mid": ["00:10:00"],
        "end": ["00:10:00"],
    }


def test_evidence_analyzer_rejects_changed_paused_timer_semantic(
    tmp_path: Path,
) -> None:
    plan, _frame_path = _synthetic_evidence(tmp_path)
    paused_base = f"007-{_safe_label(plan[6]['name'])}"
    _hierarchy(
        tmp_path / "device-pull" / f"idle-{paused_base}-mid.xml",
        timer="00:10:01",
        timer_ancestor_description=(
            "PS5 Station 1. Paused. Standard · Single · ₹80.00 fixed total."
        ),
    )
    result = _analyze(tmp_path, plan)
    assert not result["passed"]
    assert result["gates"]["no_accessibility_layout_jump"] is True
    assert result["gates"]["exact_idle_semantics_stable"] is False
    assert "raw semantic values changed" in json.dumps(
        result["layout_stability"]["exact_semantic_failures"]
    )


def test_evidence_analyzer_rejects_missing_paused_timer_semantic(
    tmp_path: Path,
) -> None:
    plan, _frame_path = _synthetic_evidence(tmp_path)
    paused_base = f"007-{_safe_label(plan[6]['name'])}"
    _hierarchy(
        tmp_path / "device-pull" / f"idle-{paused_base}-mid.xml",
        timer="Timer paused",
        timer_ancestor_description=(
            "PS5 Station 1. Paused. Standard · Single · ₹80.00 fixed total."
        ),
    )
    result = _analyze(tmp_path, plan)
    assert not result["passed"]
    assert result["gates"]["exact_idle_semantics_stable"] is False
    assert "matched 0 values; expected 1" in json.dumps(
        result["layout_stability"]["exact_semantic_failures"]
    )


def test_evidence_analyzer_rejects_duplicate_paused_timer_semantic(
    tmp_path: Path,
) -> None:
    plan, _frame_path = _synthetic_evidence(tmp_path)
    paused_base = f"007-{_safe_label(plan[6]['name'])}"
    _hierarchy(
        tmp_path / "device-pull" / f"idle-{paused_base}-mid.xml",
        timer="00:10:00",
        timer_copies=2,
        timer_ancestor_description=(
            "PS5 Station 1. Paused. Standard · Single · ₹80.00 fixed total."
        ),
    )
    result = _analyze(tmp_path, plan)
    assert not result["passed"]
    assert result["gates"]["exact_idle_semantics_stable"] is False
    assert "matched 2 values; expected 1" in json.dumps(
        result["layout_stability"]["exact_semantic_failures"]
    )


def test_evidence_analyzer_rejects_invalid_exact_semantic_schema(
    tmp_path: Path,
) -> None:
    plan, _frame_path = _synthetic_evidence(tmp_path)
    plan_path = tmp_path / "artifacts" / "audit-plan.json"
    payload = json.loads(plan_path.read_text(encoding="utf-8"))
    payload["steps"][6]["exactStableSemantics"][0]["attribute"] = "value"
    plan_path.write_text(json.dumps(payload), encoding="utf-8")
    result = _analyze(tmp_path, plan)
    assert not result["passed"]
    assert result["gates"]["copied_plan_valid"] is False
    assert result["gates"]["exact_idle_semantics_stable"] is False


def test_evidence_analyzer_rejects_unrelated_timer_outside_paused_station(
    tmp_path: Path,
) -> None:
    plan, _frame_path = _synthetic_evidence(tmp_path)
    paused_base = f"007-{_safe_label(plan[6]['name'])}"
    _hierarchy(
        tmp_path / "device-pull" / f"idle-{paused_base}-mid.xml",
        timer="Timer paused",
        timer_ancestor_description=(
            "PS5 Station 1. Paused. Standard · Single · ₹80.00 fixed total."
        ),
        unrelated_timer="00:10:00",
    )
    result = _analyze(tmp_path, plan)
    assert not result["passed"]
    assert result["gates"]["exact_idle_semantics_stable"] is False
    assert "matched 0 values; expected 1" in json.dumps(
        result["layout_stability"]["exact_semantic_failures"]
    )


def test_evidence_analyzer_rejects_invalid_exact_semantic_regex(
    tmp_path: Path,
) -> None:
    plan, _frame_path = _synthetic_evidence(tmp_path)
    plan_path = tmp_path / "artifacts" / "audit-plan.json"
    payload = json.loads(plan_path.read_text(encoding="utf-8"))
    payload["steps"][6]["exactStableSemantics"][0]["fullmatch"] = "["
    plan_path.write_text(json.dumps(payload), encoding="utf-8")
    result = _analyze(tmp_path, plan)
    assert not result["passed"]
    assert result["gates"]["copied_plan_valid"] is False
    assert result["gates"]["exact_idle_semantics_stable"] is False


def test_evidence_analyzer_rejects_zero_sample_frames(tmp_path: Path) -> None:
    plan, frame_paths = _synthetic_evidence(tmp_path)
    _frames(frame_paths["active"], [])
    result = _analyze(tmp_path, plan)
    assert not result["passed"]
    assert result["gates"]["all_frame_windows_parseable"] is False
    assert result["gates"]["dynamic_frame_samples_present"] is False


def test_evidence_analyzer_rejects_malformed_frame_dump(tmp_path: Path) -> None:
    plan, frame_paths = _synthetic_evidence(tmp_path)
    frame_paths["active"].write_text(
        "no Android framestats header here\n", encoding="utf-8"
    )
    result = _analyze(tmp_path, plan)
    assert not result["passed"]
    assert result["gates"]["all_frame_windows_parseable"] is False


def test_evidence_analyzer_rejects_six_hundred_ms_severe_jank(tmp_path: Path) -> None:
    plan, frame_paths = _synthetic_evidence(tmp_path)
    _frames(frame_paths["active"], [10.0] * 39 + [600.0])
    result = _analyze(tmp_path, plan)
    assert not result["passed"]
    assert result["gates"]["no_severe_jank"] is False
    assert result["frames"]["severe_over_250_ms"] == 1


def test_evidence_analyzer_rejects_missing_or_misnamed_step_artifact(tmp_path: Path) -> None:
    plan, _frame_path = _synthetic_evidence(tmp_path)
    expected = tmp_path / "device-pull" / "001-Exercise-alarm-constraints.png"
    expected.rename(expected.with_name("unrelated.png"))
    result = _analyze(tmp_path, plan)
    assert not result["passed"]
    assert result["gates"]["exact_per_step_artifacts"] is False


def test_evidence_analyzer_rejects_truncated_named_screenshot(tmp_path: Path) -> None:
    plan, _frame_paths = _synthetic_evidence(tmp_path)
    screenshot = tmp_path / "device-pull" / "001-Exercise-alarm-constraints.png"
    screenshot.write_bytes(b"\x89PNG\r\n\x1a\n")
    result = _analyze(tmp_path, plan)
    assert not result["passed"]
    assert result["gates"]["exact_per_step_artifacts"] is False


def test_evidence_analyzer_rejects_idle_layout_jump(tmp_path: Path) -> None:
    plan, _frame_path = _synthetic_evidence(tmp_path)
    idle_base = f"002-{_safe_label(plan[1]['name'])}"
    _hierarchy(
        tmp_path / "device-pull" / f"idle-{idle_base}-mid.xml",
        shift_y=180,
        timer="00:02",
    )
    result = _analyze(tmp_path, plan)
    assert not result["passed"]
    assert result["gates"]["no_accessibility_layout_jump"] is False


def test_evidence_analyzer_rejects_rendered_finance_value_mismatch(tmp_path: Path) -> None:
    plan, _frame_paths = _synthetic_evidence(tmp_path)
    _hierarchy(
        tmp_path / "device-pull" / "003-Finance-profit-and-loss-layout-rendered.xml"
    )
    result = _analyze(tmp_path, plan)
    assert not result["passed"]
    assert result["gates"]["rendered_finance_reports_match_reconciliation"] is False


def test_evidence_analyzer_rejects_pass_flag_without_exact_financial_values(
    tmp_path: Path,
) -> None:
    plan, _frame_paths = _synthetic_evidence(tmp_path)
    reconciliation = tmp_path / "artifacts" / "financial-api-reconciliation.json"
    reconciliation.write_text(
        json.dumps({"passed": True, "failures": []}), encoding="utf-8"
    )
    result = _analyze(tmp_path, plan)
    assert not result["passed"]
    assert result["gates"]["authenticated_finance_reports_reconciled"] is False


def test_evidence_analyzer_independently_rejects_monthly_payment_mismatch(
    tmp_path: Path,
) -> None:
    plan, _frame_paths = _synthetic_evidence(tmp_path)
    reconciliation = tmp_path / "artifacts" / "financial-api-reconciliation.json"
    payload = json.loads(reconciliation.read_text(encoding="utf-8"))
    payload["observed"]["monthly"]["payments_received"]["upi_minor"] += 1
    reconciliation.write_text(json.dumps(payload), encoding="utf-8")
    result = _analyze(tmp_path, plan)
    assert not result["passed"]
    assert result["gates"]["authenticated_finance_reports_reconciled"] is False


def test_evidence_analyzer_rejects_conflicting_duplicate_named_artifact(
    tmp_path: Path,
) -> None:
    plan, _frame_paths = _synthetic_evidence(tmp_path)
    duplicate = tmp_path / "duplicate"
    duplicate.mkdir()
    _png(duplicate / "001-Exercise-alarm-constraints.png", width=1920, height=1200)
    result = _analyze(tmp_path, plan)
    assert not result["passed"]
    assert result["gates"]["exact_per_step_artifacts"] is False


def test_evidence_analyzer_rejects_missing_target_redmi_alarm_gate(tmp_path: Path) -> None:
    plan, _frame_paths = _synthetic_evidence(tmp_path)
    gates_path = tmp_path / "artifacts" / "external-device-gates.json"
    payload = json.loads(gates_path.read_text(encoding="utf-8"))
    payload["required_external"].remove("redmi_lock_screen_alarm_delivery")
    gates_path.write_text(json.dumps(payload), encoding="utf-8")
    result = _analyze(tmp_path, plan)
    assert not result["passed"]
    assert result["gates"]["external_target_device_gates_declared"] is False


def test_evidence_analyzer_rejects_apk_bytes_not_matching_recorded_sha(tmp_path: Path) -> None:
    plan, _frame_paths = _synthetic_evidence(tmp_path)
    apk = tmp_path / "artifacts" / "app-physicalAudit.apk"
    apk.write_bytes(apk.read_bytes() + b"tampered")
    result = _analyze(tmp_path, plan)
    assert not result["passed"]
    assert result["gates"]["apk_hash_signer_identity_verified"] is False


def test_evidence_analyzer_rejects_native_process_crash(tmp_path: Path) -> None:
    plan, _frame_paths = _synthetic_evidence(tmp_path)
    (tmp_path / "artifacts" / "logcat.txt").write_text(
        "Fatal signal 11 (SIGSEGV), code 1, fault addr 0x0\n"
        ">>> cloud.dcompany.erp.physicalaudit <<<\n",
        encoding="utf-8",
    )
    result = _analyze(tmp_path, plan)
    assert not result["passed"]
    assert result["gates"]["no_crash_or_anr"] is False


def test_firebase_analyzer_rejects_empty_video_evidence(tmp_path: Path) -> None:
    plan, _frame_paths = _synthetic_evidence(tmp_path)
    (tmp_path / "artifacts" / "test_result_1.xml").write_text(
        '<testsuite tests="1" failures="0" errors="0" skipped="0" />',
        encoding="utf-8",
    )
    (tmp_path / "artifacts" / "video.mp4").write_bytes(b"")
    result = _analyze(tmp_path, plan, lane="firebase")
    assert not result["passed"]
    assert result["gates"]["firebase_video_present"] is False
