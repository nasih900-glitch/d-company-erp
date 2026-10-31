#!/usr/bin/env python3
"""Fail-closed coverage verification for sharded Android instrumentation."""

from __future__ import annotations

import argparse
from collections import Counter
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


PERSISTENCE_STAGES = (
    ("persistence-stage-a", "stageAImportBothSlotsAndPersist"),
    ("persistence-stage-b", "stageBVerifyBothSlotsAfterProcessRestart"),
)


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
    return summary


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    discover = subparsers.add_parser("discover")
    discover.add_argument("--log", type=Path, required=True)
    discover.add_argument("--expected-tests", type=Path, required=True)
    single = subparsers.add_parser("single")
    single.add_argument("--log", type=Path, required=True)
    single.add_argument("--expected-test", required=True)
    verify_parser = subparsers.add_parser("verify")
    verify_parser.add_argument("--expected-tests", type=Path, required=True)
    verify_parser.add_argument("--evidence-root", type=Path, required=True)
    verify_parser.add_argument("--functional-shards", type=int, required=True)
    verify_parser.add_argument("--stress-class", required=True)
    verify_parser.add_argument("--gaming-board-class", required=True)
    verify_parser.add_argument("--persistence-class", required=True)
    verify_parser.add_argument("--expected-skip", action="append", default=[])
    verify_parser.add_argument("--summary", type=Path, required=True)
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
        elif args.command == "single":
            print(parse_single_test_log(args.log, args.expected_test))
        else:
            sys.stdout.write(verify(args))
    except (ET.ParseError, OSError, ValueError) as error:
        print(f"Android instrumentation shard verification failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
