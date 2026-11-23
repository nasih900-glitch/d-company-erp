#!/usr/bin/env python3
"""Fail-closed coverage verification for sharded Android instrumentation."""

from __future__ import annotations

import argparse
import json
import math
from collections import Counter
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

if __package__:
    from .analyze_code26_physical_evidence import _validate_png
else:
    from analyze_code26_physical_evidence import _validate_png


PERSISTENCE_STAGES = (
    ("persistence-stage-a", "stageAImportBothSlotsAndPersist"),
    ("persistence-stage-b", "stageBVerifyBothSlotsAfterProcessRestart"),
)

FRAME_SCENARIOS = {
    "frame-metrics.txt": ("component-gaming-connectivity", 200, None),
    "frame-metrics-component-gaming-timer-1hz.txt": ("component-gaming-timer-1hz", 60, 1000),
    "frame-metrics-component-connectivity-only-3s.txt": ("component-connectivity-only-3s", 30, 3000),
}
FRAME_SCREENSHOTS = (
    "01-gaming-online.png", "02-gaming-server-issue.png", "03-gaming-after-connectivity-stress.png",
    "component-gaming-timer-1hz-before.png", "component-gaming-timer-1hz-after.png",
    "component-connectivity-only-3s-before.png", "component-connectivity-only-3s-after.png",
)


def _frame_file(directory: Path, name: str, maximum: int) -> bytes:
    path = directory / name
    if directory.is_symlink() or path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= maximum:
        raise ValueError(f"Missing, linked or oversized frame evidence: {name}")
    return path.read_bytes()


def _frame_png_dimensions(directory: Path, filename: str) -> tuple[int, int]:
    _frame_file(directory, filename, 32 * 1024 * 1024)
    valid, reason, dimensions = _validate_png(directory / filename)
    if not valid or dimensions is None:
        raise ValueError(f"Invalid frame screenshot {filename}: {reason}")
    return dimensions


def verify_frame_evidence(directory: Path, mode: str, provenance_path: Path) -> str:
    if mode not in ("physical", "hosted-software-telemetry"):
        raise ValueError("Unknown frame acceptance mode")
    provenance = json.loads(provenance_path.read_text(encoding="utf-8"))
    pins = {
        "sourceInventorySha256": provenance["source_inventory_sha256"],
        "targetApkSha256": provenance["target_apk"]["sha256"],
        "testApkSha256": provenance["test_apk"]["sha256"],
        "captureRunId": provenance["capture_run_id"],
    }
    if any(re.fullmatch(r"[0-9a-f]{32}" if name == "captureRunId" else r"[0-9a-f]{64}", value) is None
           for name, value in pins.items()):
        raise ValueError("Invalid frame evidence provenance")
    display = None
    frozen = 0
    emulated = None
    for filename, (scenario, samples, cadence) in FRAME_SCENARIOS.items():
        text = _frame_file(directory, filename, 16384).decode("utf-8")
        rows = re.findall(r"(?:^|\s)([A-Za-z][A-Za-z0-9]*)=([^\s]+)", text)
        fields = dict(rows)
        if len(rows) != len(fields):
            raise ValueError(f"Ambiguous frame evidence fields: {filename}")
        device_emulated = fields.get("emulator")
        if device_emulated not in ("true", "false") or (emulated is not None and device_emulated != emulated):
            raise ValueError("Frame evidence device identities differ")
        emulated = device_emulated
        hardware_acceptance = mode == "physical" and emulated == "false"
        if fields.get("scenario") != scenario or fields.get("frameAcceptance") != mode or \
                fields.get("hardwareAcceptance") != str(hardware_acceptance).lower() or \
                (mode == "hosted-software-telemetry" and emulated != "true"):
            raise ValueError(f"Frame scenario or acceptance mismatch: {filename}")
        if any(fields.get(name) != value for name, value in pins.items()):
            raise ValueError(f"Frame evidence provenance mismatch: {filename}")
        if fields.get("samples") != str(samples) or fields.get("geometryChecks") != str(samples) or \
                fields.get("geometryStable") != "true":
            raise ValueError(f"Incomplete frame updates or geometry: {filename}")
        total, raw_frozen = int(fields["total"]), int(fields["frozen"])
        minimum = samples if cadence is None else math.ceil(samples * .95)
        if total < minimum or not 0 <= raw_frozen <= total or (mode == "physical" and raw_frozen != 0):
            raise ValueError(f"Frame completeness or physical acceptance failed: {filename}")
        if cadence is None:
            if fields.get("droppedReports") != "0" or fields.get("pacing") != "draw-commit-vsync":
                raise ValueError("Stress frame callbacks were dropped or unpaced")
        elif fields.get("cadenceMillis") != str(cadence) or fields.get("geometryMismatches") != "0" or \
                int(fields.get("measuredWallMillis", "0")) < samples * cadence:
            raise ValueError(f"Frame cadence or geometry mismatch: {filename}")
        size = fields.get("display", "")
        if not re.fullmatch(r"[1-9][0-9]{2,3}x[1-9][0-9]{2,3}", size) or (display is not None and size != display):
            raise ValueError("Frame evidence display sizes differ")
        display = size
        frozen += raw_frozen
    if mode == "hosted-software-telemetry":
        renderer = _frame_file(directory, "host-renderer.txt", 16384).decode("utf-8")
        required = (
            r"Android emulator version 37\.2\.13\.0 \(build_id 16740729\)",
            r"emuglConfig_init: vulkan_mode_selected:swiftshader gles_mode_selected:swangle[ \t]*$",
            r"Graphics Adapter Android Emulator OpenGL ES Translator \(ANGLE \([^\r\n]*SwiftShader Device[^\r\n]*\)\)[ \t]*$",
            r"Feature 'Vulkan' \([0-9]+\) is overridden to 'disabled'",
        )
        if any(re.search(pattern, renderer, re.MULTILINE) is None for pattern in required):
            raise ValueError("Hosted frame renderer attestation is missing")
    width, height = map(int, display.split("x"))
    sizes = {name: _frame_png_dimensions(directory, name) for name in FRAME_SCREENSHOTS}
    if width <= height or any(w != width or not 0 < h <= height for w, h in sizes.values()):
        raise ValueError("Frame screenshots differ from the measured landscape viewport")
    dimensions = "".join(f"frame_png={name}:{w}x{h}\n" for name, (w, h) in sizes.items())
    return (f"frame_acceptance={mode}\nhardwareAcceptance={str(hardware_acceptance).lower()}\n"
            f"raw_frozen_frames={frozen}\nframe_screenshots={len(FRAME_SCREENSHOTS)}\n" + dimensions)


def parse_single_test_log(path: Path, expected: str) -> str:
    """Require one real start/completion pair and a successful terminal result."""
    status: dict[str, str] = {}
    started: list[str] = []
    completed: list[str] = []
    terminal: list[int] = []
    success_counts: list[int] = []
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if line.startswith(("INSTRUMENTATION_FAILED:", "INSTRUMENTATION_ABORTED:")):
            raise ValueError("Instrumentation reported a failed/aborted process")
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, separator, value = line.removeprefix("INSTRUMENTATION_STATUS: ").partition("=")
            if separator:
                status[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE:"):
            code = int(line.split(":", 1)[1])
            identity = f"{status.get('class', '')}#{status.get('test', '')}"
            if terminal or status.get("numtests") != "1" or identity != expected:
                raise ValueError(f"Unexpected single-test status identity/count: {identity}")
            if code == 1 and not started and not completed:
                started.append(identity)
            elif code == 0 and started == [identity] and not completed:
                completed.append(identity)
            else:
                raise ValueError(f"Failed, skipped, duplicated or unordered test status: {code}")
            status = {}
        elif line.startswith("INSTRUMENTATION_CODE:"):
            if started != [expected] or completed != [expected] or success_counts != [1]:
                raise ValueError("Instrumentation terminated before its successful test result")
            terminal.append(int(line.split(":", 1)[1]))
        elif match := re.fullmatch(r"OK \((\d+) tests?\)", line):
            success_counts.append(int(match[1]))
    if started != [expected] or completed != [expected]:
        raise ValueError(f"Single-test start/completion proof is missing for {expected}")
    if status or terminal != [-1] or success_counts != [1]:
        raise ValueError("Single-test instrumentation lacked one terminal success")
    return expected


def parse_discovery_log(path: Path) -> list[str]:
    """Parse AndroidJUnitRunner's real log-only start/completion status pairs."""

    class_name = ""
    test_name = ""
    observed: list[str] = []
    declared_counts: set[int] = set()
    success_counts: set[int] = set()
    saw_terminal_code = False
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if line.startswith("INSTRUMENTATION_STATUS: class="):
            class_name = line.split("=", 1)[1].strip()
        elif line.startswith("INSTRUMENTATION_STATUS: test="):
            test_name = line.split("=", 1)[1].strip()
        elif line.startswith("INSTRUMENTATION_STATUS: numtests="):
            declared_counts.add(int(line.split("=", 1)[1].strip()))
        elif line == "INSTRUMENTATION_STATUS_CODE: 0":
            if not class_name or not test_name:
                raise ValueError("Discovery completion status lacked class/test identity")
            observed.append(f"{class_name}#{test_name}")
        elif line.startswith("OK (") and line.endswith(" tests)"):
            success_counts.add(int(line.removeprefix("OK (").removesuffix(" tests)")))
        elif line == "INSTRUMENTATION_CODE: -1":
            saw_terminal_code = True

    if len(declared_counts) != 1:
        raise ValueError(f"Discovery reported inconsistent test totals: {sorted(declared_counts)!r}")
    declared = next(iter(declared_counts))
    counts = Counter(observed)
    duplicates = sorted(identity for identity, count in counts.items() if count != 1)
    if declared <= 0 or len(observed) != declared or len(counts) != declared:
        raise ValueError(
            f"Discovery inventory mismatch: declared={declared}, "
            f"completed={len(observed)}, unique={len(counts)}"
        )
    if duplicates:
        raise ValueError("Discovery repeated identities: " + ", ".join(duplicates))
    if success_counts != {declared} or not saw_terminal_code:
        raise ValueError("Discovery did not end with AndroidJUnitRunner success")
    return sorted(counts)


def _test_id(testcase: ET.Element) -> str:
    class_name = testcase.get("classname", "").strip()
    test_name = testcase.get("name", "").strip()
    if not class_name or not test_name:
        raise ValueError("Instrumentation testcase is missing classname or name")
    return f"{class_name}#{test_name}"


def _read_expected(path: Path) -> set[str]:
    tests = {
        line.strip()
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    }
    if not tests:
        raise ValueError(f"No discovered instrumentation tests in {path}")
    return tests


def _read_lane(lane: Path) -> tuple[list[str], set[str], list[str]]:
    xml_files = sorted((lane / "results").rglob("*.xml"))
    if not xml_files:
        raise ValueError(f"No instrumentation XML was preserved for {lane.name}")

    observed: list[str] = []
    skipped: set[str] = set()
    failures: list[str] = []
    for xml_file in xml_files:
        root = ET.parse(xml_file).getroot()
        for testcase in root.iter("testcase"):
            identity = _test_id(testcase)
            observed.append(identity)
            if testcase.find("skipped") is not None:
                skipped.add(identity)
            for outcome in ("failure", "error"):
                node = testcase.find(outcome)
                if node is not None:
                    detail = (node.text or node.get("message") or outcome).strip()
                    failures.append(f"{lane.name}: {identity}: {detail.splitlines()[0]}")
    return observed, skipped, failures


# Diagnostics never participate in coverage admission. Print no arbitrary XML text,
# properties, system-out, exception messages, host errors, process args or paths.
_DIAGNOSTIC_ID = re.compile(r"cloud\.dcompany\.erp\.[A-Za-z0-9_.$]{1,200}#[A-Za-z0-9_$]{1,200}")
_DIAGNOSTIC_FRAME = re.compile(
    r"\s*at ((?:cloud\.dcompany\.erp|androidx\.compose|androidx\.test|org\.junit|"
    r"java|android|kotlin|kotlinx\.coroutines)\.[A-Za-z0-9_.$<>]{1,250})"
    r"\(([A-Za-z0-9_$.-]{1,120}\.(?:kt|java)):(\d{1,7})\)\s*"
)
_DIAGNOSTIC_EXCEPTION = re.compile(
    r"(?:Caused by: |Suppressed: )?((?:java\.lang|androidx\.compose\.ui\.test|"
    r"androidx\.test|org\.junit)\.[A-Za-z0-9_.$]{1,160})(?::.*)?"
)
_DIAGNOSTIC_HOST = re.compile(
    r"(?:phase=(?:before|after) utc=\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ"
    r"|(?:MemAvailable|SwapFree):\s+\d{1,20} kB"
    r"|memory\.(?:current|max)=(?:\d{1,20}|max)"
    r"|cgroup_(?:oom|oom_kill|oom_group_kill) \d{1,20}"
    r"|process\s+\d{1,10}\s+\d{1,10}\s+\d{1,20}\s+"
    r"(?:qemu|emulator)[A-Za-z0-9_.-]{0,40})"
)


_RAIL_TEST = "cloud.dcompany.erp.ui.WorkspaceScaffoldUiTest#largeTextEdgeScrollSuppressesTransitRoutesAndCommitsStableSettings"
_RAIL_PREFIX = "Suppressed: java.lang.AssertionError: DCOMPANY_RAIL_FAILURE_V1="
_RAIL_DESTINATIONS = frozenset("Dashboard Pos Gaming Tables Reservations Kitchen Shift Customers Menu Staff Inventory Reports Analytics Finance Events Memberships Refunds AuditLog AccessControl Settings SupportInbox Help".split())


def rail_failure_projection(line: str, identity: str) -> str | None:
    """Accept only the selected fixture's closed numeric/enum observer schema."""
    if identity != _RAIL_TEST or not line.strip().startswith(_RAIL_PREFIX) or len(line) > 12_000:
        return None
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("duplicate diagnostic key")
            result[key] = value
        return result
    def obj(value, schema):
        return isinstance(value, dict) and set(value) == set(schema) and all(check(value[key]) for key, check in schema.items())
    def nullable(check):
        return lambda value: value is None or check(value)
    def integer(value):
        return type(value) is int and 0 <= value <= 10**16
    def number(value):
        return type(value) in (int, float) and math.isfinite(value) and abs(value) <= 10**12
    def boolean(value):
        return type(value) is bool
    def enum(options):
        return lambda value: isinstance(value, str) and value in options
    destination = enum(_RAIL_DESTINATIONS)
    event = {
        "sequence": integer, "uptime_ms": integer,
        "kind": enum("CLAIMED OWNER_SNAPSHOT_OBSERVED CANDIDATE_STARTED CANDIDATE_RESET TARGET_BOUNDS_MISS AUTO_SCROLL_RESET COMMIT_REQUESTED REQUEST_ACCEPTED REQUEST_REJECTED STOPPED".split()),
        "phase": enum(("Pressing", "Scrubbing", "Ended")),
        "candidate": nullable(destination), "target": nullable(destination), "committed": destination,
        "observed": nullable(destination), "scroll_delta": number,
        "stop": nullable(enum("OWNER_ROUTE_MISMATCH REQUEST_REJECTED UNEXPECTED_ACKNOWLEDGEMENT MULTIPLE_POINTERS POINTER_MISSING PRECLAIM_ROUTE_CHANGED POINTER_UP PRECLAIM_CONSUMED PRECLAIM_MOVED VIEWPORT_MISSING POINTER_EXITED MODEL_OR_EFFECT_DISPOSED".split())),
    }
    input_event = {"action": lambda value: type(value) is int and 0 <= value <= 12,
                   "event_ms": integer, "down_ms": integer, "wall_ms": integer,
                   "x": number, "y": number, "pointers": lambda value: type(value) is int and 0 <= value <= 16}
    bounds = {"bounds": lambda value: isinstance(value, list) and len(value) == 4 and all(number(n) for n in value),
              "attached": boolean, "placed": boolean}
    sample = {"status": enum(("READY",)), "route": destination,
              **{key: boolean for key in ("focused", "resumed", "attached", "laid_out", "layout_requested", "pending_compose_layout", "touch_exploration", "accessibility_enabled")},
              "decor_width": integer, "decor_height": integer,
              **{key: nullable(lambda value: obj(value, bounds)) for key in ("root", "dashboard", "viewport", "settings")},
              "scroll": nullable(lambda value: obj(value, {"value": nullable(number), "max": nullable(number), "reverse": boolean}))}
    schema = {
        "stage": enum(("settings-visibility", "settings-exposed-capture", "settings-stable", "settings-settled-capture", "route-commit")),
        "failure_wall_ms": integer, "failure_uptime_ms": integer, "pointer_held": boolean,
        "last_predicate_class": lambda value: isinstance(value, str) and (value == "NONE" or re.fullmatch(r"(?:java\.lang|androidx\.compose\.ui\.test|androidx\.test)\.[A-Za-z0-9_.$]{1,160}", value) is not None),
        "last_predicate_kind": enum(("NONE", "DISPLAY_ASSERTION", "COMPOSE_TIMEOUT", "OTHER")),
        "trace": lambda value: obj(value, {"stored": lambda n: type(n) is int and 0 <= n <= 512,
                                           "dropped": integer, "latest": nullable(lambda v: obj(v, event)),
                                           "last_stopped": nullable(lambda v: obj(v, event))}),
        "input": lambda value: obj(value, {"count": integer, "first": nullable(lambda v: obj(v, input_event)),
                                          "latest": nullable(lambda v: obj(v, input_event))}),
        "sample": lambda value: obj(value, sample) or obj(value, {"status": enum(("TIMEOUT", "ERROR", "POST_REJECTED"))}),
    }
    try:
        value = json.loads(line.strip().removeprefix(_RAIL_PREFIX), object_pairs_hook=unique)
        if obj(value, schema):
            return "rail_failure=" + json.dumps(value, separators=(",", ":"), sort_keys=True)
    except (ValueError, TypeError, RecursionError):
        pass
    return None


_CAPTURE_PREFIX = "Suppressed: java.lang.AssertionError: DCOMPANY_CAPTURE_FAILURE_V1="
_CAPTURE_SCREENSHOTS = {
    "workspaceChromeAndGamingCardsHaveBoundedFramesAndStableGeometry": frozenset((
        "01-gaming-online.png", "02-gaming-server-issue.png", "03-gaming-after-connectivity-stress.png")),
    "gamingTimersAtProductionCadenceHaveStableGeometry": frozenset((
        "component-gaming-timer-1hz-before.png", "component-gaming-timer-1hz-after.png")),
    "connectivityAtRecoveryCadenceHasStableGeometry": frozenset((
        "component-connectivity-only-3s-before.png", "component-connectivity-only-3s-after.png")),
}


def capture_failure_projection(line: str, identity: str) -> str | None:
    """Only the component fixture's closed AFTER-failure window facts may leave XML."""
    class_name, _, method = identity.partition("#")
    if (class_name != "cloud.dcompany.erp.ui.PhysicalComponentFrameStressUiTest"
            or method not in _CAPTURE_SCREENSHOTS
            or not line.strip().startswith(_CAPTURE_PREFIX) or len(line) > 4096):
        return None

    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("duplicate diagnostic key")
            result[key] = value
        return result

    def obj(value, schema):
        return type(value) is dict and set(value) == set(schema) and all(check(value[key]) for key, check in schema.items())

    boolean = lambda value: type(value) is bool
    integer = lambda value: type(value) is int and 0 <= value <= 100_000
    enum = lambda options: lambda value: type(value) is str and value in options
    window = {"lifecycle": enum(("DESTROYED", "INITIALIZED", "CREATED", "STARTED", "RESUMED")),
              "visibility": enum(("VISIBLE", "INVISIBLE", "GONE")),
              "window_visibility": enum(("VISIBLE", "INVISIBLE", "GONE")),
              **{key: boolean for key in ("finishing", "destroyed", "attached", "shown", "focused", "hardware_accelerated")},
              "width": integer, "height": integer}
    schema = {
        "schema_version": lambda value: type(value) is int and value == 1,
        "stage": enum(("capture_failure",)), "surface": enum(("physical_component_harness",)),
        "screenshot": enum(_CAPTURE_SCREENSHOTS[method]),
        "failure_kind": enum(("COMPOSE_TIMEOUT", "ASSERTION", "OTHER")),
        "after_failure": lambda value: value is True,
        "atomic_observation": lambda value: value is False,
        "caller_is_main": boolean, "post_accepted": boolean, "wait_finished": boolean,
        "wait_budget_ms": lambda value: type(value) is int and value == 150,
        "status": enum(("COMPLETED", "WAIT_EXPIRED", "POST_REJECTED", "COLLECTION_FAILED")),
        "window": lambda value: value is None or obj(value, window),
    }
    try:
        value = json.loads(line.strip().removeprefix(_CAPTURE_PREFIX), object_pairs_hook=unique)
        if not obj(value, schema):
            return None
        if ((value["status"] == "COMPLETED") != (value["window"] is not None)
                or (value["wait_finished"] and not value["post_accepted"])
                or (value["status"] == "POST_REJECTED" and value["post_accepted"])
                or (value["status"] == "WAIT_EXPIRED" and (not value["post_accepted"] or value["wait_finished"]))):
            return None
        return "capture_failure=" + json.dumps(value, separators=(",", ":"), sort_keys=True)
    except (ValueError, TypeError, RecursionError):
        return None


_BACKGROUND_TEST = "cloud.dcompany.erp.ui.theme.WorkspaceBackgroundUiTest#settingsCardIsReachableAtCompactFontScaleInDayAndNight"
_BACKGROUND_PREFIX = "Suppressed: java.lang.AssertionError: DCOMPANY_BACKGROUND_LIFECYCLE_FAILURE_V1="
_BACKGROUND_BODY_PHASES = (
    "RULE_ENTER", "OWNER_BOUND", "CONTENT_READY",
    "DAY_GALLERY_DONE", "DAY_NORMAL_DONE", "DAY_BUSY_DONE", "DAY_CANCELLING_DONE", "DAY_ERROR_DONE",
    "NIGHT_GALLERY_DONE", "NIGHT_NORMAL_DONE", "NIGHT_BUSY_DONE", "NIGHT_CANCELLING_DONE", "NIGHT_ERROR_DONE",
    "LEGACY_CUSTOM_SUBMITTED", "FINAL_ASSERT_PASSED", "BODY_RETURNED", "RULE_RETURNED",
)
_BACKGROUND_CALLBACK_PHASES = frozenset((
    "ACTIVITY_STARTED", "ACTIVITY_RESUMED", "ACTIVITY_PAUSED", "ACTIVITY_STOPPED",
    "ACTIVITY_PRE_DESTROY", "ACTIVITY_DESTROYED",
))


def background_lifecycle_projection(line: str, identity: str) -> str | None:
    """Closed exact-case facts only; absent/partial records are not crash survival proof."""
    if identity != _BACKGROUND_TEST or not line.strip().startswith(_BACKGROUND_PREFIX) or len(line) > 12_000:
        return None

    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("duplicate diagnostic key")
            result[key] = value
        return result

    def integer(value, maximum):
        return type(value) is int and 0 <= value <= maximum

    try:
        value = json.loads(line.strip().removeprefix(_BACKGROUND_PREFIX), object_pairs_hook=unique)
        if (type(value) is not dict or set(value) != {"schema_version", "surface", "atomic_observation", "dropped", "events"}
                or type(value["schema_version"]) is not int or value["schema_version"] != 1
                or value["surface"] != "workspace_background_settings" or value["atomic_observation"] is not False
                or not integer(value["dropped"], 1_000_000) or type(value["events"]) is not list
                or len(value["events"]) > 32 or (value["dropped"] and len(value["events"]) != 32)):
            return None
        last_time = last_phase = -1
        for index, event in enumerate(value["events"]):
            if (type(event) is not dict or set(event) != {"sequence", "uptime_ms", "phase", "lifecycle", "finishing", "destroyed"}
                    or not integer(event["sequence"], 32) or event["sequence"] != index + 1
                    or not integer(event["uptime_ms"], 86_400_000) or event["uptime_ms"] < last_time
                    or type(event["phase"]) is not str or last_phase == len(_BACKGROUND_BODY_PHASES) - 1):
                return None
            phase = event["phase"]
            if phase == "RULE_RETURNED":
                # This marker is pass-channel logcat only, never a caught failure.
                return None
            if index == 0 and phase != "RULE_ENTER":
                return None
            if phase in _BACKGROUND_BODY_PHASES:
                rank = _BACKGROUND_BODY_PHASES.index(phase)
                if rank <= last_phase or any(event[key] is not None for key in ("lifecycle", "finishing", "destroyed")):
                    return None
                last_phase = rank
            elif phase in _BACKGROUND_CALLBACK_PHASES:
                if (type(event["lifecycle"]) is not str or event["lifecycle"] not in {"DESTROYED", "INITIALIZED", "CREATED", "STARTED", "RESUMED"}
                        or type(event["finishing"]) is not bool or type(event["destroyed"]) is not bool):
                    return None
            else:
                return None
            last_time = event["uptime_ms"]
        return "background_lifecycle_failure=" + json.dumps(value, separators=(",", ":"), sort_keys=True)
    except (ValueError, TypeError, RecursionError):
        return None


_ENTRY_BACK_TEST = "cloud.dcompany.erp.ui.screens.ExecutiveEntryPresentationUiTest#freshLoginBackUsesTheSameBoundedCosmeticContinuation"
_ENTRY_BACK_PREFIX = "Suppressed: java.lang.AssertionError: DCOMPANY_ENTRY_BACK_FAILURE_V1="


def entry_back_failure_projection(line: str, identity: str) -> str | None:
    """Cached after-failure facts; late callbacks/acks do not establish acceptance."""
    if identity != _ENTRY_BACK_TEST or not line.strip().startswith(_ENTRY_BACK_PREFIX) or len(line) > 4096:
        return None

    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("duplicate diagnostic key")
            result[key] = value
        return result

    def integer(value, maximum):
        return type(value) is int and 0 <= value <= maximum

    def nullable_integer(value, maximum):
        return value is None or integer(value, maximum)

    def boolean(value):
        return value is None or type(value) is bool

    def dispatch(value):
        return value is None or (type(value) is dict and set(value) == {
            "has_enabled_callbacks", "lifecycle", "finishing", "destroyed", "window_focus",
        } and all(boolean(value[key]) for key in ("has_enabled_callbacks", "finishing", "destroyed", "window_focus"))
            and (value["lifecycle"] is None or type(value["lifecycle"]) is str and value["lifecycle"] in {
                "DESTROYED", "INITIALIZED", "CREATED", "STARTED", "RESUMED"}))

    try:
        value = json.loads(line.strip().removeprefix(_ENTRY_BACK_PREFIX), object_pairs_hook=unique)
        if (type(value) is not dict or set(value) != {
                "schema_version", "surface", "stage", "atomic_observation", "before_dispatch", "after_dispatch",
                "auto_advance", "clock_before_ms", "clock_first_poll_ms", "clock_last_poll_ms",
                "poll_virtual_elapsed_ms", "poll_wall_elapsed_ms", "polls", "ack_state", "ack_count",
                "callback_seen", "callback_on_main"}
                or type(value["schema_version"]) is not int or value["schema_version"] != 1
                or value["surface"] != "fresh_login_back" or value["stage"] != "after_failure"
                or value["atomic_observation"] is not False
                or not all(dispatch(value[key]) for key in ("before_dispatch", "after_dispatch"))
                or not all(boolean(value[key]) for key in ("auto_advance", "callback_seen", "callback_on_main"))
                or not all(nullable_integer(value[key], 10**12) for key in ("clock_before_ms", "clock_first_poll_ms", "clock_last_poll_ms"))
                or not all(nullable_integer(value[key], 86_400_000) for key in ("poll_virtual_elapsed_ms", "poll_wall_elapsed_ms"))
                or not integer(value["polls"], 1_000_000)):
            return None
        first, last = value["clock_first_poll_ms"], value["clock_last_poll_ms"]
        expected = last - first if first is not None and last is not None and 0 <= last - first <= 86_400_000 else None
        if value["poll_virtual_elapsed_ms"] != expected:
            return None
        if (value["polls"] == 0 and any(value[key] is not None for key in (
                "clock_first_poll_ms", "clock_last_poll_ms", "poll_virtual_elapsed_ms", "poll_wall_elapsed_ms"))
                or value["polls"] == 1 and (first != last or value["poll_wall_elapsed_ms"] not in (None, 0))):
            return None
        state, count = value["ack_state"], value["ack_count"]
        if not (state == "UNAVAILABLE" and count is None or type(count) is int and (
                state == "EMPTY" and count == 0 or state in ("EXACT_94", "OTHER_SINGLE") and count == 1
                or state == "MULTIPLE" and 2 <= count <= 1_000_000)):
            return None
        return "entry_back_failure=" + json.dumps(value, separators=(",", ":"), sort_keys=True)
    except (ValueError, TypeError, RecursionError):
        return None


def failure_diagnostics(lane: Path) -> str:
    """Bounded console projection of preserved evidence, never a test verdict."""
    lines = ["Android instrumentation failure diagnostics (not coverage proof)"]
    # Keep both host samples visible even if a large stack reaches the output budget.
    host = lane / "host-health.txt"
    try:
        if host.is_symlink():
            raise ValueError
        with host.open("rb") as source:
            data = source.read(32 * 1024 + 1)
        if len(data) > 32 * 1024:
            raise ValueError
        accepted = [line for line in data.decode("ascii").splitlines()
                    if _DIAGNOSTIC_HOST.fullmatch(line)]
        lines.extend("host " + line for line in accepted[:40])
    except (OSError, UnicodeError, ValueError):
        lines.append("host=unavailable-or-oversized")
    files_seen = cases_seen = bytes_seen = 0
    results = lane / "results"
    if not results.is_dir() or results.is_symlink():
        lines.append("xml=unavailable")
    else:
        for xml_file in results.rglob("*.xml"):
            files_seen += 1
            if files_seen > 64 or cases_seen >= 8:
                lines.append("xml=limit-reached")
                break
            try:
                # Refuse symlink files/parents and cap before reading/parsing.
                relative = xml_file.relative_to(results)
                if any((results / parent).is_symlink() for parent in (relative, *relative.parents)):
                    raise ValueError
                size = xml_file.stat().st_size
                bytes_seen += size
                if size > 8 * 1024 * 1024 or bytes_seen > 16 * 1024 * 1024:
                    raise ValueError
                with xml_file.open("rb") as source:
                    data = source.read(8 * 1024 * 1024 + 1)
                if len(data) > 8 * 1024 * 1024 or b"<!DOCTYPE" in data or b"<!ENTITY" in data:
                    raise ValueError
                root = ET.fromstring(data)
            except (ET.ParseError, OSError, ValueError):
                lines.append("xml=omitted-invalid-or-oversized")
                continue
            for testcase in root.iter("testcase"):
                failure = testcase.find("failure")
                if failure is None:
                    failure = testcase.find("error")
                if failure is None:
                    continue
                if cases_seen >= 8:
                    lines.append("failures=limit-reached")
                    break
                cases_seen += 1
                identity = f"{testcase.get('classname', '')}#{testcase.get('name', '')}"
                if not _DIAGNOSTIC_ID.fullmatch(identity):
                    lines.append("test=omitted-invalid-identity")
                    continue
                lines.append("failed_test=" + identity)
                duration = testcase.get("time", "")
                if re.fullmatch(r"\d{1,7}(?:\.\d{1,6})?", duration):
                    lines.append("test_seconds=" + duration)
                frames = 0
                rail_emitted = False
                capture_emitted = False
                background_emitted = False
                entry_back_emitted = False
                for line in (failure.text or "").splitlines():
                    if not rail_emitted and (projection := rail_failure_projection(line, identity)):
                        lines.append(projection)
                        rail_emitted = True
                    elif not capture_emitted and (projection := capture_failure_projection(line, identity)):
                        lines.append(projection)
                        capture_emitted = True
                    elif not background_emitted and (projection := background_lifecycle_projection(line, identity)):
                        lines.append(projection)
                        background_emitted = True
                    elif not entry_back_emitted and (projection := entry_back_failure_projection(line, identity)):
                        lines.append(projection)
                        entry_back_emitted = True
                    elif match := _DIAGNOSTIC_FRAME.fullmatch(line):
                        if frames >= 48:
                            if frames == 48:
                                lines.append("stack=limit-reached")
                                frames += 1
                            continue
                        lines.append(f"  at {match[1]}({match[2]}:{match[3]})")
                        frames += 1
                    elif match := _DIAGNOSTIC_EXCEPTION.fullmatch(line.strip()):
                        # Type only; the exception's message may contain private UI data.
                        if frames >= 48:
                            continue
                        lines.append("exception_type=" + match[1])
                        frames += 1
        lines.append(f"failed_cases_sampled={cases_seen}")
    # One final byte budget also bounds repeated frame/identity combinations.
    output = "\n".join(lines) + "\n"
    return output[:30_000] + ("\ndiagnostics=output-limit-reached\n" if len(output) > 30_000 else "")


def verify(args: argparse.Namespace) -> str:
    expected = _read_expected(args.expected_tests)
    stress_prefix = f"{args.stress_class}#"
    expected_stress = {test for test in expected if test.startswith(stress_prefix)}
    if not expected_stress:
        raise ValueError(f"Discovery did not include dedicated stress class {args.stress_class}")
    gaming_prefix = f"{args.gaming_board_class}#"
    expected_gaming = {test for test in expected if test.startswith(gaming_prefix)}
    if not expected_gaming:
        raise ValueError(
            f"Discovery did not include dedicated gaming class {args.gaming_board_class}"
        )
    persistence_prefix = f"{args.persistence_class}#"
    expected_persistence = {
        f"{args.persistence_class}#{stage}" for _, stage in PERSISTENCE_STAGES
    }
    discovered_persistence = {test for test in expected if test.startswith(persistence_prefix)}
    if discovered_persistence != expected_persistence:
        raise ValueError("Discovery did not include the exact dedicated persistence stages")

    gaming_lane = args.evidence_root / "gaming-board"
    functional_lanes = [
        args.evidence_root / f"functional-shard-{index}"
        for index in range(args.functional_shards)
    ]
    stress_lane = args.evidence_root / "physical-frame-stress"
    all_observed: list[str] = []
    functional_observed: list[str] = []
    stress_observed: list[str] = []
    skipped: set[str] = set()
    failures: list[str] = []
    persistence_observed = [
        parse_single_test_log(
            args.evidence_root / lane / "instrumentation.log",
            f"{args.persistence_class}#{stage}",
        )
        for lane, stage in PERSISTENCE_STAGES
    ]
    all_observed.extend(persistence_observed)

    gaming_observed, gaming_skipped, gaming_failures = _read_lane(gaming_lane)
    all_observed.extend(gaming_observed)
    skipped.update(gaming_skipped)
    failures.extend(gaming_failures)

    for lane in functional_lanes:
        lane_observed, lane_skipped, lane_failures = _read_lane(lane)
        functional_observed.extend(lane_observed)
        all_observed.extend(lane_observed)
        skipped.update(lane_skipped)
        failures.extend(lane_failures)

    stress_observed, stress_skipped, stress_failures = _read_lane(stress_lane)
    all_observed.extend(stress_observed)
    skipped.update(stress_skipped)
    failures.extend(stress_failures)

    problems: list[str] = []
    counts = Counter(all_observed)
    duplicates = sorted(test for test, count in counts.items() if count != 1)
    missing = sorted(expected - counts.keys())
    unexpected = sorted(counts.keys() - expected)
    stress_in_functional = sorted(
        test for test in functional_observed if test.startswith(stress_prefix)
    )
    gaming_in_functional = sorted(
        test for test in functional_observed if test.startswith(gaming_prefix)
    )
    gaming_only = set(gaming_observed)
    stress_only = set(stress_observed)

    if failures:
        problems.append("Failed/error instrumentation tests:\n  " + "\n  ".join(failures))
    if duplicates:
        problems.append("Tests executed more than once:\n  " + "\n  ".join(duplicates))
    if missing:
        problems.append("Discovered tests not executed:\n  " + "\n  ".join(missing))
    if unexpected:
        problems.append("Undiscovered tests appeared in results:\n  " + "\n  ".join(unexpected))
    if stress_in_functional:
        problems.append(
            "Physical frame stress leaked into functional shards:\n  "
            + "\n  ".join(stress_in_functional)
        )
    if gaming_in_functional:
        problems.append(
            "Dedicated gaming board tests leaked into functional shards:\n  "
            + "\n  ".join(gaming_in_functional)
        )
    persistence_leaks = sorted(
        test for test in (*functional_observed, *gaming_observed, *stress_observed)
        if test.startswith(persistence_prefix)
    )
    if persistence_leaks:
        problems.append(
            "Dedicated persistence tests leaked into other lanes:\n  "
            + "\n  ".join(persistence_leaks)
        )
    if gaming_only != expected_gaming:
        problems.append(
            "Dedicated gaming board coverage differed from discovery: "
            f"expected={sorted(expected_gaming)!r}, observed={sorted(gaming_only)!r}"
        )
    if stress_only != expected_stress:
        problems.append(
            "Dedicated physical frame stress coverage differed from discovery: "
            f"expected={sorted(expected_stress)!r}, observed={sorted(stress_only)!r}"
        )

    expected_skips = set(args.expected_skip)
    missing_skips = sorted(expected_skips - skipped)
    unexpected_skips = sorted(skipped - expected_skips)
    if missing_skips:
        problems.append("Expected assumption skips did not occur:\n  " + "\n  ".join(missing_skips))
    if unexpected_skips:
        problems.append("Unexpected instrumentation skips:\n  " + "\n  ".join(unexpected_skips))

    summary = "\n".join(
        [
            f"discovered={len(expected)}",
            f"executed={len(all_observed)}",
            f"gaming_board={len(gaming_observed)}",
            f"functional={len(functional_observed)}",
            f"physical_frame_stress={len(stress_observed)}",
            f"persistence={len(persistence_observed)}",
            f"skipped={len(skipped)}",
            *[f"skip={identity}" for identity in sorted(skipped)],
        ]
    ) + "\n"
    args.summary.parent.mkdir(parents=True, exist_ok=True)
    args.summary.write_text(summary, encoding="utf-8")

    if problems:
        raise ValueError("\n".join(problems))
    frame_summary = verify_frame_evidence(stress_lane / "frame-evidence", args.frame_acceptance, args.provenance)
    args.summary.write_text(summary + frame_summary, encoding="utf-8")
    return summary + frame_summary


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    discover = subparsers.add_parser("discover")
    discover.add_argument("--log", type=Path, required=True)
    discover.add_argument("--expected-tests", type=Path, required=True)
    single = subparsers.add_parser("single")
    single.add_argument("--log", type=Path, required=True)
    single.add_argument("--expected-test", required=True)
    diagnostics = subparsers.add_parser("failure-diagnostics")
    diagnostics.add_argument("--lane", type=Path, required=True)
    frame_parser = subparsers.add_parser("frame-evidence")
    frame_parser.add_argument("--evidence-dir", type=Path, required=True)
    frame_parser.add_argument("--frame-acceptance", choices=("physical", "hosted-software-telemetry"), default="physical")
    frame_parser.add_argument("--provenance", type=Path, required=True)
    verify_parser = subparsers.add_parser("verify")
    verify_parser.add_argument("--expected-tests", type=Path, required=True)
    verify_parser.add_argument("--evidence-root", type=Path, required=True)
    verify_parser.add_argument("--functional-shards", type=int, required=True)
    verify_parser.add_argument("--stress-class", required=True)
    verify_parser.add_argument("--gaming-board-class", required=True)
    verify_parser.add_argument("--persistence-class", required=True)
    verify_parser.add_argument("--expected-skip", action="append", default=[])
    verify_parser.add_argument("--summary", type=Path, required=True)
    verify_parser.add_argument("--frame-acceptance", choices=("physical", "hosted-software-telemetry"), default="physical")
    verify_parser.add_argument("--provenance", type=Path, required=True)
    args = parser.parse_args(argv)
    if args.command == "verify" and args.functional_shards < 1:
        parser.error("--functional-shards must be positive")
    return args


def main(argv: list[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    try:
        if args.command == "discover":
            identities = parse_discovery_log(args.log)
            args.expected_tests.parent.mkdir(parents=True, exist_ok=True)
            args.expected_tests.write_text("\n".join(identities) + "\n", encoding="utf-8")
            print(f"discovered={len(identities)}")
        elif args.command == "failure-diagnostics":
            sys.stdout.write(failure_diagnostics(args.lane))
        elif args.command == "single":
            print(parse_single_test_log(args.log, args.expected_test))
        elif args.command == "frame-evidence":
            sys.stdout.write(verify_frame_evidence(args.evidence_dir, args.frame_acceptance, args.provenance))
        else:
            sys.stdout.write(verify(args))
    except (ET.ParseError, OSError, ValueError, KeyError, TypeError) as error:
        print(f"Android instrumentation shard verification failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
