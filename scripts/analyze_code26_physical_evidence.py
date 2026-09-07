"""Fail-closed analysis for the Code 26 tablet business acceptance lane.

The device driver records a deterministic ledger plus named screenshot and
accessibility evidence for every planned action. Live-timer frame windows and
static stability windows both record start/mid/end snapshots; only live timers
claim Android ``gfxinfo framestats`` evidence. This module
validates those exact artifacts against the copied plan; unrelated PNG/XML
files and a longer, stale ``steps.json`` cannot make a run pass.

Pixel-perfect screenshot comparison is deliberately not claimed. Stable
layout is checked from accessibility bounds so changing timer digits are
tolerated while a banner/card jump is rejected. The Firebase lane still
requires its video for human visual review, and Redmi reboot/OEM alarm delivery
and actual lock-screen/notification-denial delivery remain explicitly declared
target-device gates.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import struct
import xml.etree.ElementTree as ET
import zlib
from collections import defaultdict
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Any

CRASH_PATTERNS = (
    re.compile(
        r"FATAL EXCEPTION(?s:.{0,800})"
        r"cloud\.dcompany\.erp\.(?:physicalaudit|auditdriver)",
        re.IGNORECASE,
    ),
    re.compile(
        r"cloud\.dcompany\.erp\.(?:physicalaudit|auditdriver)"
        r"(?s:.{0,800})FATAL EXCEPTION",
        re.IGNORECASE,
    ),
    re.compile(
        r"ANR in cloud\.dcompany\.erp\.(?:physicalaudit|auditdriver)",
        re.IGNORECASE,
    ),
    re.compile(
        r"Process cloud\.dcompany\.erp\.(?:physicalaudit|auditdriver).*has died",
        re.IGNORECASE,
    ),
    re.compile(
        r"Fatal signal \d+(?s:.{0,1200})"
        r"cloud\.dcompany\.erp\.(?:physicalaudit|auditdriver)",
        re.IGNORECASE,
    ),
)

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")
NUMBERISH = re.compile(r"\d+(?:[.,:]\d+)*")
SEVERE_FRAME_MS = 250.0
P95_FRAME_LIMIT_MS = 50.0
JANK_FRAME_MS = 50.0
MAX_JANK_RATIO = 0.05
LAYOUT_MOVE_RATIO = 0.20
LAYOUT_COUNT_DELTA_RATIO = 0.20
LAYOUT_MATCH_RATIO = 0.70
LAYOUT_MOVE_MIN_SCREEN_RATIO = 0.015
LAYOUT_LARGE_MOVE_SCREEN_RATIO = 0.08


def _percentile(values: list[float], percentile: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    # Nearest-rank is conservative at the release boundary. The former floor
    # implementation could hide the slowest five percent of a small sample.
    index = max(0, min(len(ordered) - 1, math.ceil(len(ordered) * percentile) - 1))
    return round(ordered[index], 3)


def _canonical_json(value: Any) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8")


def _load_unique_json(root: Path, name: str, expected_type: type) -> tuple[
    Path | None,
    Any | None,
    list[str],
    list[str],
]:
    parsed: list[tuple[Path, Any, str]] = []
    errors: list[str] = []
    for path in sorted(root.rglob(name)):
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
            errors.append(f"{path}: unreadable JSON ({type(exc).__name__})")
            continue
        if not isinstance(payload, expected_type):
            errors.append(f"{path}: expected {expected_type.__name__}")
            continue
        digest = hashlib.sha256(_canonical_json(payload)).hexdigest()
        parsed.append((path, payload, digest))
    if not parsed:
        errors.append(f"no valid {name} was found")
        return None, None, errors, []
    if len({item[2] for item in parsed}) != 1:
        errors.append(f"conflicting copies of {name} were found")
    path, payload, _digest = parsed[0]
    return path, payload, errors, [str(item[0]) for item in parsed]


def _safe_label(label: str) -> str:
    return re.sub(r"[^A-Za-z0-9._-]", "-", label)[:110]


def _step_base(index: int, name: str) -> str:
    return _safe_label(f"{index:03d}-{name}")


def _artifact_candidates(root: Path, filename: str) -> list[Path]:
    return sorted(path for path in root.rglob(filename) if path.is_file())


def _copy_conflict(candidates: list[Path]) -> str | None:
    """Reject stale copies that share a name but do not share exact bytes."""
    if len(candidates) < 2:
        return None
    try:
        digests = {
            hashlib.sha256(path.read_bytes()).hexdigest()
            for path in candidates
        }
    except OSError as exc:
        return f"could not hash duplicate artifacts ({type(exc).__name__})"
    if len(digests) != 1:
        return "conflicting copies of the named artifact were found"
    return None


def _validate_png(path: Path) -> tuple[bool, str | None, tuple[int, int] | None]:
    try:
        payload = path.read_bytes()
    except OSError as exc:
        return False, f"unreadable ({type(exc).__name__})", None
    if len(payload) < 45 or payload[:8] != PNG_SIGNATURE:
        return False, "invalid PNG signature/length", None
    offset = len(PNG_SIGNATURE)
    width = height = 0
    saw_ihdr = False
    saw_idat = False
    saw_iend = False
    while offset + 12 <= len(payload):
        length = struct.unpack(">I", payload[offset : offset + 4])[0]
        chunk_type = payload[offset + 4 : offset + 8]
        data_start = offset + 8
        data_end = data_start + length
        crc_end = data_end + 4
        if crc_end > len(payload):
            return False, "truncated PNG chunk", None
        chunk_data = payload[data_start:data_end]
        expected_crc = struct.unpack(">I", payload[data_end:crc_end])[0]
        actual_crc = zlib.crc32(chunk_type + chunk_data) & 0xFFFFFFFF
        if actual_crc != expected_crc:
            return False, "PNG chunk CRC mismatch", None
        if not saw_ihdr:
            if chunk_type != b"IHDR" or length != 13:
                return False, "PNG does not begin with a valid IHDR", None
            width, height = struct.unpack(">II", chunk_data[:8])
            bit_depth, colour_type, compression, filtering, interlace = chunk_data[8:]
            if (
                width <= 0
                or height <= 0
                or bit_depth not in {1, 2, 4, 8, 16}
                or colour_type not in {0, 2, 3, 4, 6}
                or compression != 0
                or filtering != 0
                or interlace not in {0, 1}
            ):
                return False, "invalid PNG IHDR fields", None
            saw_ihdr = True
        elif chunk_type == b"IDAT":
            saw_idat = saw_idat or length > 0
        elif chunk_type == b"IEND":
            if length != 0:
                return False, "invalid PNG IEND", None
            saw_iend = True
            offset = crc_end
            break
        offset = crc_end
    if not (saw_ihdr and saw_idat and saw_iend) or offset != len(payload):
        return False, "PNG is missing IDAT/IEND or has trailing data", None
    return True, None, (width, height)


@dataclass(frozen=True)
class LayoutSnapshot:
    width: int
    height: int
    anchors: dict[tuple[str, str, str, str, int], tuple[int, int, int, int]]


def _normalise_semantics(value: str) -> str:
    collapsed = " ".join(value.split()).strip().lower()
    return NUMBERISH.sub("#", collapsed)


def _parse_layout(path: Path) -> tuple[LayoutSnapshot | None, str | None]:
    try:
        root = ET.parse(path).getroot()
    except (OSError, ET.ParseError) as exc:
        return None, f"unreadable hierarchy ({type(exc).__name__})"

    grouped: dict[tuple[str, str, str, str], list[tuple[int, int, int, int]]] = defaultdict(list)
    max_right = 0
    max_bottom = 0
    for node in root.iter():
        match = BOUNDS.fullmatch(node.attrib.get("bounds", ""))
        if not match:
            continue
        left, top, right, bottom = (int(part) for part in match.groups())
        if right <= left or bottom <= top:
            continue
        max_right = max(max_right, right)
        max_bottom = max(max_bottom, bottom)
        text = _normalise_semantics(node.attrib.get("text", ""))
        description = _normalise_semantics(node.attrib.get("content-desc", ""))
        resource_id = node.attrib.get("resource-id", "").strip()
        class_name = node.attrib.get("class", node.tag).strip()
        # Empty layout containers are implementation details. Visible semantic
        # controls and labels are the operator-facing anchors whose movement
        # constitutes a real layout jump.
        if not (text or description or resource_id):
            continue
        grouped[(class_name, resource_id, text, description)].append(
            (left, top, right, bottom)
        )

    anchors: dict[tuple[str, str, str, str, int], tuple[int, int, int, int]] = {}
    for key, rectangles in grouped.items():
        for occurrence, rectangle in enumerate(sorted(rectangles)):
            anchors[(*key, occurrence)] = rectangle
    if max_right <= 0 or max_bottom <= 0:
        return None, "hierarchy has no valid screen bounds"
    if len(anchors) < 5:
        return None, f"hierarchy has only {len(anchors)} semantic layout anchors"
    return LayoutSnapshot(max_right, max_bottom, anchors), None


def _hierarchy_semantics(path: Path) -> tuple[list[str] | None, str | None]:
    try:
        root = ET.parse(path).getroot()
    except (OSError, ET.ParseError) as exc:
        return None, f"unreadable hierarchy ({type(exc).__name__})"
    values: list[str] = []
    for node in root.iter():
        for attribute in ("text", "content-desc"):
            value = " ".join(node.attrib.get(attribute, "").split()).strip()
            if value:
                values.append(value)
    if not values:
        return None, "hierarchy has no visible text or descriptions"
    return values, None


def _layout_comparison(before: LayoutSnapshot, after: LayoutSnapshot) -> dict[str, Any]:
    before_keys = set(before.anchors)
    after_keys = set(after.anchors)
    common = sorted(before_keys & after_keys)
    minimum_count = min(len(before_keys), len(after_keys))
    match_ratio = len(common) / minimum_count if minimum_count else 0.0
    count_delta_ratio = (
        abs(len(before_keys) - len(after_keys)) / max(len(before_keys), len(after_keys))
        if before_keys or after_keys
        else 1.0
    )
    screen_min = max(1, min(before.width, before.height, after.width, after.height))
    move_threshold = max(12.0, screen_min * LAYOUT_MOVE_MIN_SCREEN_RATIO)
    large_move_threshold = screen_min * LAYOUT_LARGE_MOVE_SCREEN_RATIO
    moved = 0
    large_moves = 0
    max_move = 0.0
    for key in common:
        left_a, top_a, right_a, bottom_a = before.anchors[key]
        left_b, top_b, right_b, bottom_b = after.anchors[key]
        displacement = max(
            abs((left_a + right_a) / 2.0 - (left_b + right_b) / 2.0),
            abs((top_a + bottom_a) / 2.0 - (top_b + bottom_b) / 2.0),
            abs((right_a - left_a) - (right_b - left_b)),
            abs((bottom_a - top_a) - (bottom_b - top_b)),
        )
        max_move = max(max_move, displacement)
        if displacement > move_threshold:
            moved += 1
        if displacement > large_move_threshold:
            large_moves += 1
    moved_ratio = moved / len(common) if common else 1.0
    stable = (
        match_ratio >= LAYOUT_MATCH_RATIO
        and count_delta_ratio <= LAYOUT_COUNT_DELTA_RATIO
        and moved_ratio <= LAYOUT_MOVE_RATIO
        and large_moves == 0
    )
    return {
        "stable": stable,
        "before_anchors": len(before_keys),
        "after_anchors": len(after_keys),
        "matched_anchors": len(common),
        "match_ratio": round(match_ratio, 4),
        "count_delta_ratio": round(count_delta_ratio, 4),
        "moved_anchors": moved,
        "moved_ratio": round(moved_ratio, 4),
        "large_moves": large_moves,
        "max_move_px": round(max_move, 3),
        "move_threshold_px": round(move_threshold, 3),
        "large_move_threshold_px": round(large_move_threshold, 3),
    }


@dataclass(frozen=True)
class FrameParse:
    valid: bool
    durations_ms: list[float]
    error: str | None = None


def _frame_durations(path: Path) -> FrameParse:
    try:
        lines = path.read_text(encoding="utf-8", errors="strict").splitlines()
    except (OSError, UnicodeDecodeError) as exc:
        return FrameParse(False, [], f"unreadable ({type(exc).__name__})")
    durations: list[float] = []
    header: list[str] | None = None
    saw_data_line = False
    for raw_line in lines:
        line = raw_line.strip()
        if line.startswith("Flags,"):
            header = [part.strip() for part in line.split(",")]
            if "IntendedVsync" not in header or "FrameCompleted" not in header:
                return FrameParse(False, [], "framestats header lacks required timestamps")
            continue
        if header is None or not line or line.startswith("---"):
            continue
        values = [part.strip() for part in line.split(",")]
        if not values[0].lstrip("-").isdigit():
            continue
        if len(values) != len(header):
            return FrameParse(False, [], "framestats row has the wrong column count")
        saw_data_line = True
        row = dict(zip(header, values, strict=True))
        try:
            intended = int(row["IntendedVsync"])
            completed = int(row["FrameCompleted"])
        except ValueError:
            return FrameParse(False, [], "framestats row has non-integer timestamps")
        if intended > 0 and completed >= intended:
            durations.append((completed - intended) / 1_000_000.0)
    if header is None:
        return FrameParse(False, [], "framestats header was not found")
    if saw_data_line and not durations:
        return FrameParse(False, [], "framestats rows contained no valid completed frame")
    return FrameParse(True, durations)


def _junit_counts(root: Path) -> tuple[int, int, int, int, list[str]]:
    candidates = [path for path in root.rglob("*.xml") if "test" in path.name.lower()]
    parsed: list[tuple[Path, ET.Element]] = []
    for path in candidates:
        try:
            root_element = ET.parse(path).getroot()
        except (OSError, ET.ParseError):
            continue
        if root_element.tag in {"testsuite", "testsuites"}:
            parsed.append((path, root_element))
    if not parsed:
        return 0, 0, 0, 0, []
    tests = failures = errors = skipped = 0
    for _path, root_element in parsed:
        suites = [root_element]
        if root_element.tag == "testsuites":
            suites = list(root_element.findall("testsuite"))
        tests += sum(int(suite.attrib.get("tests", "0") or 0) for suite in suites)
        failures += sum(int(suite.attrib.get("failures", "0") or 0) for suite in suites)
        errors += sum(int(suite.attrib.get("errors", "0") or 0) for suite in suites)
        skipped += sum(
            int(suite.attrib.get("skipped", suite.attrib.get("disabled", "0")) or 0)
            for suite in suites
        )
    return tests, failures, errors, skipped, [str(path) for path, _root in parsed]


def _scan_runtime_failures(root: Path) -> list[dict[str, str]]:
    findings: list[dict[str, str]] = []
    for path in root.rglob("*"):
        if not path.is_file() or path.suffix.lower() not in {".txt", ".log", ".xml"}:
            continue
        try:
            content = path.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        for pattern in CRASH_PATTERNS:
            match = pattern.search(content)
            if match:
                findings.append(
                    {"path": str(path), "match": " ".join(match.group(0).split())[:500]}
                )
                break
    return findings


def _required_json_evidence(
    root: Path,
    name: str,
    validator: Callable[[dict[str, Any]], list[str]],
) -> tuple[dict[str, Any] | None, list[str]]:
    _path, payload, errors, _paths = _load_unique_json(root, name, dict)
    if isinstance(payload, dict):
        errors.extend(validator(payload))
    return payload, errors


def _alarm_errors(payload: dict[str, Any]) -> list[str]:
    required_true = (
        "alarm_registered_before",
        "notification_denied",
        "notification_regranted",
        "screen_locked",
        "screen_woken",
        "doze_entered",
        "doze_exited",
        "battery_saver_enabled",
        "battery_saver_disabled",
        "alarm_registered_after",
    )
    missing = [key for key in required_true if payload.get(key) is not True]
    return ["alarm constraint checks not proven: " + ", ".join(missing)] if missing else []


def _finance_errors(payload: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if payload.get("passed") is not True or payload.get("failures") not in ([], None):
        errors.append("authenticated Finance/Reports reconciliation did not pass")
    expected = payload.get("expected")
    observed = payload.get("observed")
    if not isinstance(expected, dict) or not isinstance(observed, dict):
        return errors + ["Finance/Reports reconciliation lacks expected/observed values"]

    required_expected = (
        "revenue_minor",
        "cogs_minor",
        "cash_minor",
        "upi_minor",
        "discount_minor",
        "orders",
    )
    for key in required_expected:
        value = expected.get(key)
        if isinstance(value, bool) or not isinstance(value, int) or value < 0:
            errors.append(f"invalid expected Finance/Reports value: {key}")
    if errors:
        return errors
    if expected["orders"] != 12:
        errors.append("physical fixture reconciliation must contain exactly 12 paid orders")

    finance = observed.get("finance")
    daily = observed.get("daily")
    monthly = observed.get("monthly")
    if not all(isinstance(row, dict) for row in (finance, daily, monthly)):
        return errors + ["Finance, daily and monthly observed payloads are required"]
    assert isinstance(finance, dict) and isinstance(daily, dict) and isinstance(monthly, dict)
    revenue = expected["revenue_minor"]
    cogs = expected["cogs_minor"]
    profit = revenue - cogs
    expected_checks = (
        (finance.get("accounting_basis") == "operational_receipt", "Finance basis"),
        (finance.get("revenue_minor") == revenue, "Finance revenue"),
        (finance.get("cogs_minor") == cogs, "Finance COGS"),
        (finance.get("gross_profit_minor") == profit, "Finance gross profit"),
        (finance.get("expenses_minor") == 0, "Finance expenses"),
        (finance.get("depreciation_minor") == 0, "Finance depreciation"),
        (finance.get("net_profit_minor") == profit, "Finance net profit"),
        (daily.get("orders_count") == expected["orders"], "Daily order count"),
        (daily.get("gross_revenue_minor") == revenue, "Daily gross revenue"),
        (daily.get("net_revenue_minor") == revenue, "Daily net revenue"),
        (daily.get("cogs_minor") == cogs, "Daily COGS"),
        (daily.get("gross_profit_minor") == profit, "Daily gross profit"),
        (daily.get("net_profit_minor") == profit, "Daily net profit"),
        (
            isinstance(daily.get("revenue"), dict)
            and daily["revenue"].get("discounts_and_points_redeemed_minor")
            == expected["discount_minor"],
            "Daily discount",
        ),
        (
            isinstance(daily.get("payments_received"), dict)
            and daily["payments_received"].get("cash_minor") == expected["cash_minor"],
            "Daily cash",
        ),
        (
            isinstance(daily.get("payments_received"), dict)
            and daily["payments_received"].get("upi_minor") == expected["upi_minor"],
            "Daily UPI",
        ),
        (
            isinstance(daily.get("payments_received"), dict)
            and daily["payments_received"].get("total_minor") == revenue,
            "Daily payment total",
        ),
        (daily.get("net_payments_received_minor") == revenue, "Daily net payments"),
        (monthly.get("orders_count") == expected["orders"], "Monthly order count"),
        (monthly.get("gross_revenue_minor") == revenue, "Monthly gross revenue"),
        (monthly.get("net_revenue_minor") == revenue, "Monthly net revenue"),
        (monthly.get("cogs_minor") == cogs, "Monthly COGS"),
        (monthly.get("gross_profit_minor") == profit, "Monthly gross profit"),
        (monthly.get("net_profit_minor") == profit, "Monthly net profit"),
        (
            isinstance(monthly.get("revenue"), dict)
            and monthly["revenue"].get("discounts_and_points_redeemed_minor")
            == expected["discount_minor"],
            "Monthly discount",
        ),
        (
            isinstance(monthly.get("payments_received"), dict)
            and monthly["payments_received"].get("cash_minor") == expected["cash_minor"],
            "Monthly cash",
        ),
        (
            isinstance(monthly.get("payments_received"), dict)
            and monthly["payments_received"].get("upi_minor") == expected["upi_minor"],
            "Monthly UPI",
        ),
        (
            isinstance(monthly.get("payments_received"), dict)
            and monthly["payments_received"].get("total_minor") == revenue,
            "Monthly payment total",
        ),
        (monthly.get("net_payments_received_minor") == revenue, "Monthly net payments"),
        (
            daily.get("branch_id") not in (None, "")
            and monthly.get("branch_id") == daily.get("branch_id"),
            "Daily/monthly branch parity",
        ),
    )
    errors.extend(
        f"authenticated reconciliation mismatch: {label}"
        for condition, label in expected_checks
        if not condition
    )
    return errors


def _external_limits(root: Path) -> tuple[list[str], list[str]]:
    _path, payload, errors, _paths = _load_unique_json(
        root, "external-device-gates.json", dict
    )
    required = {
        "redmi_reboot_alarm_delivery",
        "redmi_lock_screen_alarm_delivery",
        "redmi_notification_denial_recovery",
        "redmi_oem_battery_optimisation",
        "signed_in_place_upgrade",
    }
    observed: set[str] = set()
    if isinstance(payload, dict) and isinstance(payload.get("required_external"), list):
        observed = {str(item) for item in payload["required_external"]}
    missing = sorted(required - observed)
    if missing:
        errors.append("undeclared external device gates: " + ", ".join(missing))
    return sorted(observed), errors


def _source_errors(payload: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if payload.get("clean") is not True:
        errors.append("source was not recorded clean")
    if payload.get("version_code") != 26 or payload.get("version_name") != "3.1.15":
        errors.append("source is not Code 26 / 3.1.15")
    for key in ("commit", "tree"):
        if not re.fullmatch(r"[0-9a-f]{40}", str(payload.get(key, ""))):
            errors.append(f"source {key} is not an immutable git object id")
    return errors


def _source_recheck_errors(
    payload: dict[str, Any], source: dict[str, Any] | None
) -> list[str]:
    errors: list[str] = []
    if payload.get("unchanged") is not True or payload.get("clean_after") is not True:
        errors.append("source was changed or dirty after the run")
    if source is not None and (
        payload.get("before_commit") != source.get("commit")
        or payload.get("after_commit") != source.get("commit")
        or payload.get("before_tree") != source.get("tree")
        or payload.get("after_tree") != source.get("tree")
    ):
        errors.append("source recheck does not match captured source identity")
    return errors


def _apk_errors(
    payload: dict[str, Any], source: dict[str, Any] | None, root: Path
) -> list[str]:
    errors: list[str] = []
    rows = payload.get("apks")
    if not isinstance(rows, list) or len(rows) != 3:
        return ["exactly three audit APK identities are required"]
    source_commit = source.get("commit") if source else None
    source_tree = source.get("tree") if source else None
    if payload.get("source_commit") != source_commit:
        errors.append("APK manifest source commit differs from source identity")
    if payload.get("source_tree") != source_tree:
        errors.append("APK manifest source tree differs from source identity")
    filenames: set[str] = set()
    packages: set[str] = set()
    erp_rows: list[dict[str, Any]] = []
    for row in rows:
        if not isinstance(row, dict):
            errors.append("APK identity row is not an object")
            continue
        filenames.add(str(row.get("file", "")))
        packages.add(str(row.get("package_name", "")))
        if not re.fullmatch(r"[0-9a-f]{64}", str(row.get("sha256", ""))):
            errors.append(f"invalid APK SHA-256 for {row.get('file')}")
        if not re.fullmatch(
            r"[0-9a-fA-F]{64}", str(row.get("signer_certificate_sha256", ""))
        ):
            errors.append(f"invalid signer certificate SHA-256 for {row.get('file')}")
        if row.get("signature_verified") is not True or row.get("copied_hash_verified") is not True:
            errors.append(f"unverified APK identity for {row.get('file')}")
        if row.get("source_commit") != source_commit:
            errors.append(f"APK row source commit differs for {row.get('file')}")
        filename = str(row.get("file", ""))
        if Path(filename).name != filename or not filename.endswith(".apk"):
            errors.append(f"unsafe APK evidence filename: {filename}")
        else:
            apk_candidates = _artifact_candidates(root, filename)
            if not apk_candidates:
                errors.append(f"copied APK evidence is missing: {filename}")
            else:
                conflict = _copy_conflict(apk_candidates)
                if conflict:
                    errors.append(f"{filename}: {conflict}")
                for apk_path in apk_candidates:
                    try:
                        actual_sha = hashlib.sha256(apk_path.read_bytes()).hexdigest()
                    except OSError as exc:
                        errors.append(f"{filename}: unreadable APK ({type(exc).__name__})")
                        continue
                    if actual_sha != row.get("sha256"):
                        errors.append(f"copied APK SHA-256 differs for {filename}")
            signer_logs = _artifact_candidates(root, f"apksigner-{filename}.txt")
            if not signer_logs:
                errors.append(f"apksigner evidence is missing: {filename}")
            else:
                expected_digest = str(row.get("signer_certificate_sha256", "")).lower()
                conflict = _copy_conflict(signer_logs)
                if conflict:
                    errors.append(f"apksigner-{filename}.txt: {conflict}")
                for log_path in signer_logs:
                    try:
                        content = log_path.read_text(
                            encoding="utf-8", errors="strict"
                        ).lower()
                    except (OSError, UnicodeDecodeError) as exc:
                        errors.append(
                            f"apksigner evidence is unreadable for {filename} "
                            f"({type(exc).__name__})"
                        )
                        continue
                    if "verifies" not in content or expected_digest not in content:
                        errors.append(f"apksigner evidence does not match {filename}")
            aapt_logs = _artifact_candidates(root, f"aapt-{filename}.txt")
            if not aapt_logs:
                errors.append(f"aapt manifest evidence is missing: {filename}")
            else:
                conflict = _copy_conflict(aapt_logs)
                if conflict:
                    errors.append(f"aapt-{filename}.txt: {conflict}")
                for log_path in aapt_logs:
                    try:
                        content = log_path.read_text(encoding="utf-8", errors="strict")
                    except (OSError, UnicodeDecodeError) as exc:
                        errors.append(
                            f"aapt evidence is unreadable for {filename} "
                            f"({type(exc).__name__})"
                        )
                        continue
                    identity_tokens = (
                        f"name='{row.get('package_name')}'",
                        f"versionCode='{row.get('version_code')}'",
                        f"versionName='{row.get('version_name')}'",
                    )
                    if not all(token in content for token in identity_tokens):
                        errors.append(f"aapt manifest evidence does not match {filename}")
        if row.get("package_name") == "cloud.dcompany.erp.physicalaudit":
            erp_rows.append(row)
    if len(filenames) != 3:
        errors.append("APK identity filenames are not unique")
    required_packages = {
        "cloud.dcompany.erp.physicalaudit",
        "cloud.dcompany.erp.auditdriver",
        "cloud.dcompany.erp.auditdriver.test",
    }
    if packages != required_packages:
        errors.append("APK package identities do not match ERP/driver/test set")
    if len(erp_rows) != 1:
        errors.append("one physicalAudit ERP APK identity is required")
    elif (
        erp_rows[0].get("version_code") != "26"
        or erp_rows[0].get("version_name") != "3.1.15-physical-audit"
    ):
        errors.append("physicalAudit APK is not Code 26 / 3.1.15-physical-audit")
    return errors


def _rupees(minor: int) -> str:
    negative = minor < 0
    paise = abs(minor)
    whole, fraction = divmod(paise, 100)
    digits = str(whole)
    if len(digits) > 3:
        head, tail = digits[:-3], digits[-3:]
        pairs: list[str] = []
        while len(head) > 2:
            pairs.insert(0, head[-2:])
            head = head[:-2]
        if head:
            pairs.insert(0, head)
        digits = ",".join(pairs + [tail])
    return f"{'-' if negative else ''}₹{digits}.{fraction:02d}"


def _unique_plan_step(plan_steps: list[dict[str, Any]], name: str) -> tuple[int | None, str | None]:
    matches = [
        index
        for index, step in enumerate(plan_steps, start=1)
        if step.get("name") == name
    ]
    if len(matches) != 1:
        return None, f"expected exactly one plan step named {name!r}; found {len(matches)}"
    return matches[0], None


def _financial_ui_checks(
    root: Path,
    plan_steps: list[dict[str, Any]],
    finance_reconciliation: dict[str, Any] | None,
    finance_errors: list[str],
) -> tuple[dict[str, Any], list[str]]:
    """Bind rendered native Finance/Reports values to authenticated API totals.

    Reports cash/UPI and COGS cards are below the initial tablet viewport, so
    those remain API-reconciliation evidence. The initial Reports viewport
    proves revenue, paid-order count and net profit. Finance's visible P&L card
    proves revenue, COGS, gross profit and operating profit.
    """
    evidence: dict[str, Any] = {"finance": [], "reports": []}
    errors: list[str] = []
    if finance_errors or not isinstance(finance_reconciliation, dict):
        return evidence, ["rendered finance checks require valid API reconciliation"]
    expected = finance_reconciliation.get("expected")
    observed = finance_reconciliation.get("observed")
    if not isinstance(expected, dict) or not isinstance(observed, dict):
        return evidence, ["rendered finance checks lack reconciled expected/observed data"]
    finance = observed.get("finance")
    daily = observed.get("daily")
    if not isinstance(finance, dict) or not isinstance(daily, dict):
        return evidence, ["rendered finance checks lack Finance/daily observations"]

    finance_index, finance_step_error = _unique_plan_step(
        plan_steps, "Finance profit and loss layout rendered"
    )
    finance_idle_index, finance_idle_error = _unique_plan_step(
        plan_steps, "Measure Finance settled layout stability"
    )
    reports_index, reports_step_error = _unique_plan_step(
        plan_steps, "Reports revenue panel rendered"
    )
    reports_idle_index, reports_idle_error = _unique_plan_step(
        plan_steps, "Measure Reports settled layout stability"
    )
    errors.extend(
        error
        for error in (
            finance_step_error,
            finance_idle_error,
            reports_step_error,
            reports_idle_error,
        )
        if error
    )
    if errors:
        return evidence, errors
    assert all(
        index is not None
        for index in (finance_index, finance_idle_index, reports_index, reports_idle_index)
    )

    finance_files = [
        _step_base(finance_index, "Finance profit and loss layout rendered") + ".xml",
        *[
            "idle-"
            + _step_base(finance_idle_index, "Measure Finance settled layout stability")
            + f"-{phase}.xml"
            for phase in ("start", "mid", "end")
        ],
    ]
    reports_files = [
        _step_base(reports_index, "Reports revenue panel rendered") + ".xml",
        *[
            "idle-"
            + _step_base(reports_idle_index, "Measure Reports settled layout stability")
            + f"-{phase}.xml"
            for phase in ("start", "mid", "end")
        ],
    ]
    finance_tokens = (
        "Profit and loss",
        "Net revenue",
        "Less: cost of goods sold",
        "Gross profit",
        "Operating profit",
        _rupees(int(finance["revenue_minor"])),
        _rupees(abs(int(finance["cogs_minor"]))),
        _rupees(int(finance["gross_profit_minor"])),
        _rupees(int(finance["net_profit_minor"])),
    )
    reports_tokens = (
        "Revenue",
        "Orders",
        "Net profit",
        _rupees(int(daily["net_revenue_minor"])),
        _rupees(int(daily["net_profit_minor"])),
    )
    report_order_value = str(expected["orders"])

    for surface, filenames, tokens in (
        ("finance", finance_files, finance_tokens),
        ("reports", reports_files, reports_tokens),
    ):
        for filename in filenames:
            candidates = _artifact_candidates(root, filename)
            if not candidates:
                errors.append(f"missing rendered {surface} hierarchy: {filename}")
                continue
            conflict = _copy_conflict(candidates)
            if conflict:
                errors.append(f"{filename}: {conflict}")
            for path in candidates:
                semantics, parse_error = _hierarchy_semantics(path)
                if parse_error or semantics is None:
                    errors.append(f"{filename}: {parse_error or 'missing semantics'}")
                    continue
                folded = [value.casefold() for value in semantics]
                missing = [
                    token
                    for token in tokens
                    if not any(token.casefold() in value for value in folded)
                ]
                if surface == "reports" and report_order_value not in semantics:
                    missing.append(f"exact order count {report_order_value}")
                if missing:
                    errors.append(
                        f"{filename}: rendered values missing: " + ", ".join(missing)
                    )
                evidence[surface].append(
                    {
                        "path": str(path),
                        "required_tokens": list(tokens)
                        + ([f"exact order count {report_order_value}"] if surface == "reports" else []),
                        "matched": not missing,
                    }
                )
    evidence["scope"] = {
        "finance_rendered": ["revenue", "COGS", "gross profit", "operating profit"],
        "reports_rendered": ["revenue", "paid order count", "net profit"],
        "api_only_below_fold": ["Reports cash", "Reports UPI", "Reports COGS"],
    }
    return evidence, errors


def analyze(root: Path, *, expected_steps: int, expected_frames: int, lane: str) -> dict:
    plan_path, plan_payload, plan_errors, plan_paths = _load_unique_json(
        root, "audit-plan.json", dict
    )
    plan_steps = plan_payload.get("steps", []) if isinstance(plan_payload, dict) else []
    if not isinstance(plan_steps, list) or not all(isinstance(step, dict) for step in plan_steps):
        plan_errors.append("audit plan steps must be a list of objects")
        plan_steps = []
    if len(plan_steps) != expected_steps:
        plan_errors.append(f"audit plan has {len(plan_steps)} steps; expected {expected_steps}")
    step_bases = [
        _step_base(index, str(step.get("name", "")))
        for index, step in enumerate(plan_steps, start=1)
    ]
    if len(set(step_bases)) != len(step_bases):
        plan_errors.append("audit plan produces colliding safe artifact names")
    if any(not str(step.get("name", "")).strip() for step in plan_steps):
        plan_errors.append("every audit plan step requires a non-empty name")
    stability_steps = [
        (index, step)
        for index, step in enumerate(plan_steps, start=1)
        if step.get("action") in {"idleFrames", "idleStability"}
    ]
    frame_steps = [
        (index, step)
        for index, step in stability_steps
        if step.get("action") == "idleFrames"
    ]
    alarm_steps = [step for step in plan_steps if step.get("action") == "alarmConstraints"]
    if len(frame_steps) != expected_frames:
        plan_errors.append(
            f"audit plan has {len(frame_steps)} frame windows; expected {expected_frames}"
        )
    if len(alarm_steps) != 1:
        plan_errors.append("audit plan must contain exactly one alarmConstraints step")

    step_path, steps_payload, step_errors, step_paths = _load_unique_json(
        root, "steps.json", list
    )
    steps = steps_payload if isinstance(steps_payload, list) else []
    ledger_failures: list[dict[str, Any]] = []
    for index, expected in enumerate(plan_steps, start=1):
        if index > len(steps):
            ledger_failures.append({"step": index, "reason": "missing ledger row"})
            continue
        observed = steps[index - 1]
        if not isinstance(observed, dict):
            ledger_failures.append({"step": index, "reason": "ledger row is not an object"})
            continue
        if observed.get("step") != index:
            ledger_failures.append(
                {"step": index, "reason": f"recorded step number {observed.get('step')!r}"}
            )
        if observed.get("name") != expected.get("name"):
            ledger_failures.append(
                {
                    "step": index,
                    "reason": "name differs from immutable plan",
                    "expected": expected.get("name"),
                    "observed": observed.get("name"),
                }
            )
        if observed.get("status") != "passed":
            ledger_failures.append(
                {
                    "step": index,
                    "reason": "step did not pass",
                    "status": observed.get("status"),
                    "error_type": observed.get("error_type"),
                }
            )
    if len(steps) != len(plan_steps):
        ledger_failures.append(
            {
                "reason": "ledger length differs from plan",
                "planned": len(plan_steps),
                "observed": len(steps),
            }
        )

    artifact_failures: list[dict[str, Any]] = []
    screenshot_paths: list[str] = []
    hierarchy_paths: list[str] = []
    for index, step in enumerate(plan_steps, start=1):
        base = _step_base(index, str(step.get("name", "")))
        for suffix in (".png", ".xml"):
            filename = base + suffix
            candidates = _artifact_candidates(root, filename)
            if not candidates:
                artifact_failures.append(
                    {"step": index, "artifact": filename, "reason": "missing"}
                )
                continue
            conflict = _copy_conflict(candidates)
            if conflict:
                artifact_failures.append(
                    {"step": index, "artifact": filename, "reason": conflict}
                )
            validations = [
                _validate_png(path)[:2] if suffix == ".png" else _parse_layout(path)
                for path in candidates
            ]
            if not validations or not all(result[0] for result in validations):
                artifact_failures.append(
                    {
                        "step": index,
                        "artifact": filename,
                        "reason": "; ".join(str(result[1]) for result in validations),
                    }
                )
            elif suffix == ".png":
                screenshot_paths.extend(str(path) for path in candidates)
            else:
                hierarchy_paths.extend(str(path) for path in candidates)

    frame_paths: list[str] = []
    frame_durations: list[float] = []
    frame_failures: list[dict[str, Any]] = []
    dynamic_frame_failures: list[dict[str, Any]] = []
    layout_windows: list[dict[str, Any]] = []
    idle_artifact_failures: list[dict[str, Any]] = []
    for index, step in frame_steps:
        base = _step_base(index, str(step.get("name", "")))
        frame_name = f"frames-{base}.txt"
        frame_candidates = _artifact_candidates(root, frame_name)
        parsed = FrameParse(False, [], "missing")
        if not frame_candidates:
            frame_failures.append({"step": index, "artifact": frame_name, "reason": "missing"})
        else:
            conflict = _copy_conflict(frame_candidates)
            if conflict:
                frame_failures.append(
                    {"step": index, "artifact": frame_name, "reason": conflict}
                )
            parsed_results = [_frame_durations(path) for path in frame_candidates]
            invalid = [result.error for result in parsed_results if not result.valid]
            if invalid:
                frame_failures.append(
                    {
                        "step": index,
                        "artifact": frame_name,
                        "reason": "; ".join(str(reason) for reason in invalid),
                    }
                )
            zero_samples = [
                str(path)
                for path, result in zip(frame_candidates, parsed_results, strict=True)
                if result.valid and not result.durations_ms
            ]
            if zero_samples:
                frame_failures.append(
                    {
                        "step": index,
                        "artifact": frame_name,
                        "reason": "zero completed frame samples: " + ", ".join(zero_samples),
                    }
                )
            parsed = next((result for result in parsed_results if result.valid), parsed_results[0])
            frame_paths.extend(str(path) for path in frame_candidates)
            frame_durations.extend(parsed.durations_ms)
        dynamic = any(
            token in str(step.get("name", "")).lower() for token in ("timer", "active")
        )
        if dynamic and not parsed.durations_ms:
            dynamic_frame_failures.append(
                {"step": index, "artifact": frame_name, "reason": "dynamic window has zero frames"}
            )

    for index, step in stability_steps:
        base = _step_base(index, str(step.get("name", "")))
        layouts: dict[str, LayoutSnapshot] = {}
        phase_dimensions: dict[str, tuple[int, int]] = {}
        for phase in ("start", "mid", "end"):
            phase_base = _safe_label(f"idle-{base}-{phase}")
            png_name = phase_base + ".png"
            xml_name = phase_base + ".xml"
            png_candidates = _artifact_candidates(root, png_name)
            xml_candidates = _artifact_candidates(root, xml_name)
            if not png_candidates:
                idle_artifact_failures.append(
                    {"step": index, "artifact": png_name, "reason": "missing"}
                )
            else:
                conflict = _copy_conflict(png_candidates)
                if conflict:
                    idle_artifact_failures.append(
                        {"step": index, "artifact": png_name, "reason": conflict}
                    )
                png_results = [_validate_png(path) for path in png_candidates]
                valid_dimensions = [result[2] for result in png_results if result[2] is not None]
                if not png_results or not all(result[0] for result in png_results):
                    idle_artifact_failures.append(
                        {"step": index, "artifact": png_name, "reason": "malformed PNG"}
                    )
                else:
                    phase_dimensions[phase] = valid_dimensions[0]
                    screenshot_paths.extend(str(path) for path in png_candidates)
            if not xml_candidates:
                idle_artifact_failures.append(
                    {"step": index, "artifact": xml_name, "reason": "missing"}
                )
            else:
                conflict = _copy_conflict(xml_candidates)
                if conflict:
                    idle_artifact_failures.append(
                        {"step": index, "artifact": xml_name, "reason": conflict}
                    )
                parsed_layouts = [_parse_layout(path) for path in xml_candidates]
                layout = parsed_layouts[0][0] if parsed_layouts else None
                if (
                    layout is None
                    or not parsed_layouts
                    or not all(value is not None and error is None for value, error in parsed_layouts)
                ):
                    idle_artifact_failures.append(
                        {
                            "step": index,
                            "artifact": xml_name,
                            "reason": "; ".join(
                                str(error) for _value, error in parsed_layouts if error
                            ) or "malformed hierarchy",
                        }
                    )
                else:
                    layouts[phase] = layout
                    hierarchy_paths.extend(str(path) for path in xml_candidates)
        if len(set(phase_dimensions.values())) > 1:
            idle_artifact_failures.append(
                {"step": index, "reason": "idle screenshot dimensions changed across phases"}
            )
        comparisons: list[dict[str, Any]] = []
        if all(phase in layouts for phase in ("start", "mid", "end")):
            for before, after in (("start", "mid"), ("mid", "end")):
                comparison = _layout_comparison(layouts[before], layouts[after])
                comparison.update({"before": before, "after": after})
                comparisons.append(comparison)
        layout_windows.append(
            {
                "step": index,
                "name": step.get("name"),
                "comparisons": comparisons,
                "stable": len(comparisons) == 2 and all(row["stable"] for row in comparisons),
            }
        )

    severe_frames = [duration for duration in frame_durations if duration > SEVERE_FRAME_MS]
    jank_frames = [duration for duration in frame_durations if duration > JANK_FRAME_MS]
    frozen_frames = [duration for duration in frame_durations if duration > 700.0]
    frame_p95 = _percentile(frame_durations, 0.95)
    jank_ratio = len(jank_frames) / len(frame_durations) if frame_durations else 1.0

    junit_tests, junit_failures, junit_errors, junit_skips, junit_paths = _junit_counts(root)
    instrumentation_ok = any(
        "OK (1 test)" in path.read_text(encoding="utf-8", errors="ignore")
        for path in root.rglob("instrumentation.txt")
    )
    videos = []
    for path in sorted(root.rglob("*.mp4")):
        try:
            with path.open("rb") as handle:
                prefix = handle.read(12)
            if path.is_file() and path.stat().st_size >= 1_024 and prefix[4:8] == b"ftyp":
                videos.append(path)
        except OSError:
            continue
    runtime_failures = _scan_runtime_failures(root)
    alarm_evidence, alarm_errors = _required_json_evidence(
        root, "alarm-constraints.json", _alarm_errors
    )
    finance_reconciliation, finance_errors = _required_json_evidence(
        root, "financial-api-reconciliation.json", _finance_errors
    )
    financial_ui_evidence, financial_ui_errors = _financial_ui_checks(
        root,
        plan_steps,
        finance_reconciliation,
        finance_errors,
    )
    external_required, external_errors = _external_limits(root)
    source_identity, source_errors = _required_json_evidence(
        root, "source-identity.json", _source_errors
    )
    source_recheck, source_recheck_errors = _required_json_evidence(
        root,
        "source-recheck.json",
        lambda payload: _source_recheck_errors(payload, source_identity),
    )
    apk_identities, apk_errors = _required_json_evidence(
        root,
        "apk-identities.json",
        lambda payload: _apk_errors(payload, source_identity, root),
    )

    gates = {
        "copied_plan_valid": not plan_errors,
        "complete_exact_step_ledger": not step_errors and not ledger_failures,
        "exact_per_step_artifacts": not artifact_failures,
        "idle_phase_artifacts_complete": not idle_artifact_failures,
        "all_frame_windows_parseable": not frame_failures and len(frame_paths) >= expected_frames,
        "frame_samples_present": bool(frame_durations),
        "dynamic_frame_samples_present": not dynamic_frame_failures,
        "no_severe_jank": not severe_frames,
        "frame_p95_within_50_ms": frame_p95 is not None and frame_p95 <= P95_FRAME_LIMIT_MS,
        "jank_over_50_ms_within_5_percent": bool(frame_durations)
        and jank_ratio <= MAX_JANK_RATIO,
        "no_accessibility_layout_jump": bool(layout_windows)
        and all(window["stable"] for window in layout_windows),
        "physical_alarm_constraints_exercised": not alarm_errors,
        "external_target_device_gates_declared": not external_errors,
        "authenticated_finance_reports_reconciled": not finance_errors,
        "rendered_finance_reports_match_reconciliation": not financial_ui_errors,
        "immutable_clean_source_rechecked": not source_errors and not source_recheck_errors,
        "apk_hash_signer_identity_verified": not apk_errors,
        "no_crash_or_anr": not runtime_failures,
        "instrumentation_green": (
            junit_tests >= 1 and junit_failures == 0 and junit_errors == 0
            if lane == "firebase"
            else instrumentation_ok
        ),
        "firebase_video_present": lane != "firebase" or bool(videos),
    }
    step_durations = [
        float(step.get("duration_ms", 0))
        for step in steps
        if isinstance(step, dict) and isinstance(step.get("duration_ms", 0), (int, float))
    ]
    return {
        "passed": all(gates.values()),
        "scope": "automated physical lane; target Redmi acceptance remains external",
        "gates": gates,
        "plan": {
            "path": str(plan_path) if plan_path else None,
            "copies": plan_paths,
            "errors": plan_errors,
            "steps": len(plan_steps),
            "frame_windows": len(frame_steps),
            "layout_stability_windows": len(stability_steps),
        },
        "steps": {
            "path": str(step_path) if step_path else None,
            "copies": step_paths,
            "expected": expected_steps,
            "observed": len(steps),
            "failures": step_errors + ledger_failures,
            "total_duration_ms": round(sum(step_durations), 3),
            "p95_duration_ms": _percentile(step_durations, 0.95),
            "max_duration_ms": round(max(step_durations), 3) if step_durations else None,
        },
        "junit": {
            "tests": junit_tests,
            "failures": junit_failures,
            "errors": junit_errors,
            "skipped": junit_skips,
            "paths": junit_paths,
        },
        "frames": {
            "windows": len(frame_paths),
            "samples": len(frame_durations),
            "jank_over_50_ms": len(jank_frames),
            "jank_ratio": round(jank_ratio, 4),
            "severe_over_250_ms": len(severe_frames),
            "frozen_over_700_ms": len(frozen_frames),
            "p95_ms": frame_p95,
            "max_ms": round(max(frame_durations), 3) if frame_durations else None,
            "paths": frame_paths,
            "failures": frame_failures,
            "dynamic_failures": dynamic_frame_failures,
        },
        "layout_stability": {
            "windows": layout_windows,
            "artifact_failures": idle_artifact_failures,
            "method": "start/mid/end accessibility bounds; numeric text normalised",
        },
        "artifacts": {
            "screenshots": sorted(set(screenshot_paths)),
            "accessibility_hierarchies": sorted(set(hierarchy_paths)),
            "per_step_failures": artifact_failures,
            "videos": [str(path) for path in videos],
        },
        "alarm_constraints": {"evidence": alarm_evidence, "errors": alarm_errors},
        "finance_reconciliation": {
            "evidence": finance_reconciliation,
            "errors": finance_errors,
        },
        "rendered_finance_reports": {
            "evidence": financial_ui_evidence,
            "errors": financial_ui_errors,
        },
        "source_identity": {
            "evidence": source_identity,
            "recheck": source_recheck,
            "errors": source_errors + source_recheck_errors,
        },
        "apk_identities": {"evidence": apk_identities, "errors": apk_errors},
        "external_device_acceptance_required": external_required,
        "external_device_gate_errors": external_errors,
        "runtime_failures": runtime_failures,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("evidence_root", type=Path)
    parser.add_argument("--expected-steps", type=int, required=True)
    parser.add_argument("--expected-frame-windows", type=int, required=True)
    parser.add_argument("--lane", choices=("emulator", "firebase"), required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    result = analyze(
        args.evidence_root.resolve(),
        expected_steps=args.expected_steps,
        expected_frames=args.expected_frame_windows,
        lane=args.lane,
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2, sort_keys=True))
    if not result["passed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
