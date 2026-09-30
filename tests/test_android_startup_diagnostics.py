from __future__ import annotations

import json
import os
import subprocess
import time
from pathlib import Path

from scripts.capture_android_startup_diagnostics import (
    CommandSpec,
    build_commands,
    capture,
    host_commands,
)


ROOT = Path(__file__).resolve().parents[1]
RUNNER = ROOT / "scripts" / "run_android_instrumentation_ci.sh"


def fake_command(tmp_path: Path) -> Path:
    executable = tmp_path / "fake-command"
    executable.write_text(
        "#!/bin/sh\n"
        "case \"${1:-}\" in\n"
        "  sleep) sleep 5 ;;\n"
        "  fail) echo expected-failure; exit 7 ;;\n"
        "  *) echo ok ;;\n"
        "esac\n",
        encoding="utf-8",
    )
    executable.chmod(0o755)
    return executable


def test_capture_enforces_command_and_overall_deadlines(tmp_path: Path) -> None:
    executable = fake_command(tmp_path)
    started = time.monotonic()
    manifest_path = capture(
        phase="discovery-failure",
        output_root=tmp_path / "diagnostics",
        serial="emulator-fake",
        overall_timeout_seconds=0.15,
        commands=(
            CommandSpec("slow", (str(executable), "sleep"), 5.0),
            CommandSpec("after-deadline", (str(executable),), 5.0),
        ),
    )
    elapsed = time.monotonic() - started
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))

    assert elapsed < 1.5
    assert manifest["commands"][0]["timed_out"] is True
    assert manifest["commands"][1]["skipped"] == "overall deadline reached"
    assert manifest["overall_deadline_reached"] is True
    assert "Timed out" in (manifest_path.parent / "00-slow.txt").read_text(encoding="utf-8")


def test_manifest_records_timestamp_return_code_and_output(tmp_path: Path) -> None:
    executable = fake_command(tmp_path)
    manifest_path = capture(
        phase="pre-assembly",
        output_root=tmp_path / "diagnostics",
        serial="emulator-fake",
        overall_timeout_seconds=2.0,
        commands=(CommandSpec("failing-read", (str(executable), "fail")),),
    )
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    command = manifest["commands"][0]

    assert manifest["phase"] == "pre-assembly"
    assert manifest["started_at_utc"].endswith("Z")
    assert manifest["completed_at_utc"].endswith("Z")
    assert command["returncode"] == 7
    assert command["timed_out"] is False
    assert "expected-failure" in (manifest_path.parent / command["output_file"]).read_text(
        encoding="utf-8"
    )


def test_each_command_has_its_own_deadline(tmp_path: Path) -> None:
    executable = fake_command(tmp_path)
    started = time.monotonic()
    manifest_path = capture(
        phase="pre-discovery",
        output_root=tmp_path / "diagnostics",
        serial="emulator-fake",
        overall_timeout_seconds=2.0,
        commands=(
            CommandSpec("slow", (str(executable), "sleep"), 0.1),
            CommandSpec("following-read", (str(executable), "fail"), 1.0),
        ),
    )
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))

    assert time.monotonic() - started < 1.5
    assert manifest["commands"][0]["timed_out"] is True
    assert manifest["commands"][1]["returncode"] == 7
    assert manifest["commands"][1]["timed_out"] is False


def test_missing_command_is_recorded_and_later_reads_continue(tmp_path: Path) -> None:
    executable = fake_command(tmp_path)
    manifest_path = capture(
        phase="pre-discovery",
        output_root=tmp_path / "diagnostics",
        serial="emulator-fake",
        overall_timeout_seconds=2.0,
        commands=(
            CommandSpec("missing-read", (str(tmp_path / "does-not-exist"),)),
            CommandSpec("following-read", (str(executable), "fail")),
        ),
    )
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))

    assert manifest["commands"][0]["returncode"] is None
    assert manifest["commands"][0]["error"].startswith("FileNotFoundError:")
    assert "Could not start command" in (
        manifest_path.parent / manifest["commands"][0]["output_file"]
    ).read_text(encoding="utf-8")
    assert manifest["commands"][1]["returncode"] == 7


def test_commands_are_read_only_and_host_process_snapshot_excludes_arguments() -> None:
    mac_host = host_commands("Darwin")
    process_command = next(command for command in mac_host if command.name == "host-processes")
    assert process_command.argv == ("ps", "-Ao", "pid,ppid,pcpu,rss,comm")
    assert all("args" not in argument for argument in process_command.argv)
    assert any(command.argv[0] == "sysctl" for command in mac_host)
    assert any(command.argv[0] == "vm_stat" for command in mac_host)

    commands = build_commands(
        "discovery-failure",
        "emulator-fake",
        adb="fake-adb",
        system_name="Darwin",
    )
    assert commands[0].name == "guest-data-app-anr-dropbox"
    assert commands[-2].name == "guest-logcat"
    assert commands[-1].name == "guest-activity-processes"
    flattened = "\n".join(" ".join(command.argv) for command in commands)
    for mutation in (" force-stop ", " uninstall ", " install ", " root", " pull ", " push ", " chmod "):
        assert mutation not in f" {flattened} "
    assert "dumpsys dropbox --print data_app_anr" in flattened
    assert "getprop ro.build.fingerprint" in flattened
    assert "cat /proc/pressure/cpu" in flattened


def test_discovery_failure_budget_captures_dropbox_before_stalled_reads(tmp_path: Path) -> None:
    fake_adb = tmp_path / "fake-adb"
    fake_adb.write_text(
        "#!/bin/sh\n"
        "case \" $* \" in\n"
        "  *\" dumpsys dropbox --print data_app_anr \"*) echo retained-anr-trace ;;\n"
        "  *) sleep 5 ;;\n"
        "esac\n",
        encoding="utf-8",
    )
    fake_adb.chmod(0o755)
    commands = build_commands(
        "discovery-failure",
        "emulator-fake",
        adb=str(fake_adb),
        system_name="Linux",
    )
    manifest_path = capture(
        phase="discovery-failure",
        output_root=tmp_path / "diagnostics",
        serial="emulator-fake",
        overall_timeout_seconds=0.15,
        commands=commands,
    )
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))

    first = manifest["commands"][0]
    assert first["name"] == "guest-data-app-anr-dropbox"
    assert first["returncode"] == 0
    assert "retained-anr-trace" in (
        manifest_path.parent / first["output_file"]
    ).read_text(encoding="utf-8")
    assert any(command.get("skipped") == "overall deadline reached" for command in manifest["commands"])


def test_discovery_failure_capture_precedes_cleanup_and_preserves_status(
    tmp_path: Path,
) -> None:
    source = RUNNER.read_text(encoding="utf-8")
    function_start = source.index("discover_instrumentation_tests()")
    function_end = source.index("\n\nclear_connected_outputs()", function_start)
    discovery = source[function_start:function_end]

    capture_index = discovery.index("capture_startup_diagnostics discovery-failure")
    assert capture_index < discovery.index('shell am force-stop "${test_package}"')
    assert capture_index < discovery.index('uninstall "${test_package}"')
    assert 'local discovery_status=0' in discovery
    assert 'return "${discovery_status}"' in discovery

    capture_start = source.index("capture_startup_diagnostics()")
    capture_end = source.index("\n}\n", capture_start)
    assert "return 0" in source[capture_start:capture_end]

    main_start = source.index("# Discover the exact runner inventory once")
    main = source[main_start:]
    assert main.index("capture_startup_diagnostics pre-assembly") < main.index(
        "run_gradle_ci :app:assembleDebug"
    )
    assert main.index("capture_startup_diagnostics pre-discovery") < main.index(
        "discover_instrumentation_tests"
    )

    capture_function = source[capture_start : capture_end + 3]
    discovery_function = source[function_start : function_end]
    fake_bin = tmp_path / "bin"
    fake_bin.mkdir()
    event_log = tmp_path / "events.log"
    fake_python = fake_bin / "python3"
    fake_python.write_text(
        "#!/bin/sh\n"
        "echo capture:$* >> \"${EVENT_LOG}\"\n"
        "exit 19\n",
        encoding="utf-8",
    )
    fake_python.chmod(0o755)
    fake_adb = fake_bin / "adb"
    fake_adb.write_text(
        "#!/bin/sh\n"
        "echo adb:$* >> \"${EVENT_LOG}\"\n"
        "exit 0\n",
        encoding="utf-8",
    )
    fake_adb.chmod(0o755)
    harness = tmp_path / "runner-functions.sh"
    harness.write_text(
        "#!/bin/bash\n"
        "set -uo pipefail\n"
        f"{capture_function}\n"
        f"{discovery_function}\n"
        "startup_diagnostics=fake-helper\n"
        "diagnostics_dir=fake-diagnostics\n"
        "device_serial=emulator-fake\n"
        "shard_evidence_root=\"${TEST_TMP}/shards\"\n"
        "discovered_tests_file=\"${shard_evidence_root}/discovered-tests.txt\"\n"
        "test_package=cloud.dcompany.erp.test\n"
        "app_package=cloud.dcompany.erp\n"
        "test_runner=cloud.dcompany.erp.test/fake.Runner\n"
        "shard_verifier=fake-verifier\n"
        "install_discovery_test_apks() { return 1; }\n"
        "discover_instrumentation_tests\n"
        "status=$?\n"
        "echo status:${status} >> \"${EVENT_LOG}\"\n"
        "exit 0\n",
        encoding="utf-8",
    )
    harness.chmod(0o755)
    environment = os.environ.copy()
    environment.update(
        {
            "EVENT_LOG": os.fspath(event_log),
            "TEST_TMP": os.fspath(tmp_path),
            "PATH": f"{fake_bin}{os.pathsep}{environment['PATH']}",
        }
    )
    completed = subprocess.run(
        [os.fspath(harness)],
        check=False,
        capture_output=True,
        text=True,
        env=environment,
    )
    assert completed.returncode == 0, completed.stderr
    events = event_log.read_text(encoding="utf-8").splitlines()
    capture_event = next(index for index, event in enumerate(events) if event.startswith("capture:"))
    cleanup_event = next(
        index for index, event in enumerate(events) if "force-stop cloud.dcompany.erp.test" in event
    )
    assert capture_event < cleanup_event
    assert events[-1] == "status:1"
    assert "preserving the original CI status" in completed.stderr


def test_runner_remains_valid_shell() -> None:
    completed = subprocess.run(
        ["bash", "-n", os.fspath(RUNNER)],
        check=False,
        capture_output=True,
        text=True,
    )
    assert completed.returncode == 0, completed.stderr
