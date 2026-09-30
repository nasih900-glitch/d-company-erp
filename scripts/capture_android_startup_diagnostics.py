#!/usr/bin/env python3
"""Capture bounded, read-only host and Android startup diagnostics."""

from __future__ import annotations

import argparse
import json
import os
import platform
import signal
import subprocess
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Sequence


DEFAULT_COMMAND_TIMEOUT_SECONDS = 3.0
DROPBOX_TIMEOUT_SECONDS = 10.0
FAILURE_READ_TIMEOUT_SECONDS = 10.0
DEFAULT_OVERALL_TIMEOUT_SECONDS = 45.0


@dataclass(frozen=True)
class CommandSpec:
    name: str
    argv: tuple[str, ...]
    timeout_seconds: float = DEFAULT_COMMAND_TIMEOUT_SECONDS


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def safe_phase_directory(output_root: Path, phase: str) -> Path:
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
    directory = output_root / f"{stamp}-{phase}"
    directory.mkdir(parents=True, exist_ok=False)
    return directory


def host_commands(system_name: str) -> list[CommandSpec]:
    commands = [
        CommandSpec("host-uptime", ("uptime",)),
        # comm intentionally excludes command arguments, which can contain secrets.
        CommandSpec("host-processes", ("ps", "-Ao", "pid,ppid,pcpu,rss,comm"), 5.0),
    ]
    if system_name == "Darwin":
        commands[0:0] = [
            CommandSpec(
                "host-sysctl",
                ("sysctl", "hw.logicalcpu", "hw.physicalcpu", "hw.memsize", "vm.swapusage"),
                5.0,
            ),
            CommandSpec("host-vm-stat", ("vm_stat",), 5.0),
        ]
    else:
        for name, path in (
            ("host-loadavg", "/proc/loadavg"),
            ("host-meminfo", "/proc/meminfo"),
            ("host-stat", "/proc/stat"),
        ):
            commands.append(CommandSpec(name, ("cat", path)))
    return commands


def guest_commands(adb: str, serial: str) -> list[CommandSpec]:
    prefix = (adb, "-s", serial, "shell")
    commands: list[CommandSpec] = []
    for name, property_name in (
        ("guest-build-fingerprint", "ro.build.fingerprint"),
        ("guest-build-type", "ro.build.type"),
        ("guest-debuggable", "ro.debuggable"),
    ):
        commands.append(CommandSpec(name, (*prefix, "getprop", property_name)))
    commands.append(CommandSpec("guest-shell-uid", (*prefix, "id", "-u")))
    for name, path in (
        ("guest-cpu-online", "/sys/devices/system/cpu/online"),
        ("guest-cpuinfo", "/proc/cpuinfo"),
        ("guest-loadavg", "/proc/loadavg"),
        ("guest-meminfo", "/proc/meminfo"),
        ("guest-stat", "/proc/stat"),
        ("guest-pressure-cpu", "/proc/pressure/cpu"),
        ("guest-pressure-memory", "/proc/pressure/memory"),
        ("guest-pressure-io", "/proc/pressure/io"),
    ):
        commands.append(CommandSpec(name, (*prefix, "cat", path)))
    return commands


def failure_commands(adb: str, serial: str) -> list[CommandSpec]:
    prefix = (adb, "-s", serial)
    return [
        CommandSpec(
            "guest-data-app-anr-dropbox",
            (*prefix, "shell", "dumpsys", "dropbox", "--print", "data_app_anr"),
            DROPBOX_TIMEOUT_SECONDS,
        ),
        CommandSpec(
            "guest-logcat",
            (*prefix, "logcat", "-d", "-v", "threadtime"),
            FAILURE_READ_TIMEOUT_SECONDS,
        ),
        CommandSpec(
            "guest-activity-processes",
            (*prefix, "shell", "dumpsys", "activity", "processes"),
            FAILURE_READ_TIMEOUT_SECONDS,
        ),
    ]


def build_commands(phase: str, serial: str, *, adb: str, system_name: str) -> list[CommandSpec]:
    if phase == "discovery-failure":
        failure_reads = failure_commands(adb, serial)
        return (
            failure_reads[:1]
            + host_commands(system_name)
            + guest_commands(adb, serial)
            + failure_reads[1:]
        )
    return host_commands(system_name) + guest_commands(adb, serial)


def _terminate_process_group(process: subprocess.Popen[bytes]) -> None:
    if process.poll() is not None:
        return
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    try:
        process.wait(timeout=1.0)
    except subprocess.TimeoutExpired:
        pass


def run_command(
    spec: CommandSpec,
    output_path: Path,
    *,
    overall_deadline: float,
) -> dict[str, object]:
    started_at = utc_now()
    started = time.monotonic()
    remaining = overall_deadline - started
    result: dict[str, object] = {
        "name": spec.name,
        "command": list(spec.argv),
        "output_file": output_path.name,
        "started_at_utc": started_at,
        "timeout_seconds": min(spec.timeout_seconds, max(remaining, 0.0)),
        "returncode": None,
        "timed_out": False,
    }
    if remaining <= 0:
        output_path.write_text("Skipped: overall diagnostic deadline reached.\n", encoding="utf-8")
        result["skipped"] = "overall deadline reached"
        result["elapsed_seconds"] = 0.0
        return result

    timeout_seconds = min(spec.timeout_seconds, remaining)
    with output_path.open("wb") as output:
        try:
            process = subprocess.Popen(
                spec.argv,
                stdin=subprocess.DEVNULL,
                stdout=output,
                stderr=subprocess.STDOUT,
                start_new_session=True,
            )
        except OSError as error:
            output.write(f"Could not start command: {error}\n".encode("utf-8", errors="replace"))
            result["error"] = f"{type(error).__name__}: {error}"
        else:
            try:
                result["returncode"] = process.wait(timeout=timeout_seconds)
            except subprocess.TimeoutExpired:
                result["timed_out"] = True
                _terminate_process_group(process)
                result["returncode"] = process.returncode
                output.write(
                    f"\nTimed out after {timeout_seconds:.3f} seconds.\n".encode("utf-8")
                )
    result["elapsed_seconds"] = round(time.monotonic() - started, 3)
    return result


def capture(
    *,
    phase: str,
    output_root: Path,
    serial: str,
    adb: str = "adb",
    system_name: str | None = None,
    overall_timeout_seconds: float = DEFAULT_OVERALL_TIMEOUT_SECONDS,
    commands: Sequence[CommandSpec] | None = None,
) -> Path:
    phase_directory = safe_phase_directory(output_root, phase)
    started_at = utc_now()
    overall_deadline = time.monotonic() + overall_timeout_seconds
    specs = list(commands) if commands is not None else build_commands(
        phase,
        serial,
        adb=adb,
        system_name=system_name or platform.system(),
    )
    results = []
    for index, spec in enumerate(specs):
        output_path = phase_directory / f"{index:02d}-{spec.name}.txt"
        results.append(run_command(spec, output_path, overall_deadline=overall_deadline))
    manifest = {
        "phase": phase,
        "serial": serial,
        "started_at_utc": started_at,
        "completed_at_utc": utc_now(),
        "overall_timeout_seconds": overall_timeout_seconds,
        "overall_deadline_reached": time.monotonic() >= overall_deadline,
        "commands": results,
    }
    manifest_path = phase_directory / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return manifest_path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--phase",
        required=True,
        choices=("pre-assembly", "pre-discovery", "discovery-failure"),
    )
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--overall-timeout", type=float, default=DEFAULT_OVERALL_TIMEOUT_SECONDS)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.overall_timeout <= 0:
        raise SystemExit("--overall-timeout must be positive")
    manifest = capture(
        phase=args.phase,
        output_root=args.output_dir,
        serial=args.serial,
        adb=args.adb,
        overall_timeout_seconds=args.overall_timeout,
    )
    print(manifest)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
