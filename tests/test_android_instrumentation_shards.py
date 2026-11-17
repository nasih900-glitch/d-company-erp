from __future__ import annotations

import json
import os
import subprocess
import tempfile
import unittest
from xml.sax.saxutils import escape

from scripts.verify_android_instrumentation_shards import background_lifecycle_projection, capture_failure_projection, entry_back_failure_projection, failure_diagnostics, rail_failure_projection
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RUNNER = ROOT / "scripts" / "run_android_instrumentation_ci.sh"
VERIFIER = ROOT / "scripts" / "verify_android_instrumentation_shards.py"


def discovery_event(class_name: str, test_name: str, code: int) -> str:
    return "\n".join(
        (
            f"INSTRUMENTATION_STATUS: class={class_name}",
            "INSTRUMENTATION_STATUS: current=1",
            "INSTRUMENTATION_STATUS: id=AndroidJUnitRunner",
            "INSTRUMENTATION_STATUS: numtests=2",
            f"INSTRUMENTATION_STATUS: test={test_name}",
            f"INSTRUMENTATION_STATUS_CODE: {code}",
        )
    )


def junit_xml(testcases: list[tuple[str, str, bool]]) -> str:
    cases = []
    for class_name, test_name, skipped in testcases:
        outcome = "<skipped />" if skipped else ""
        cases.append(
            f'<testcase classname="{class_name}" name="{test_name}">{outcome}</testcase>'
        )
    return f'<testsuite tests="{len(cases)}">{"".join(cases)}</testsuite>'


def single_test_log(class_name: str, test_name: str) -> str:
    return "\n".join((
        discovery_event(class_name, test_name, 1).replace("numtests=2", "numtests=1"),
        discovery_event(class_name, test_name, 0).replace("numtests=2", "numtests=1"),
        "OK (1 test)", "INSTRUMENTATION_CODE: -1", "",
    ))


class AndroidInstrumentationShardsTest(unittest.TestCase):
    def test_runner_isolates_gaming_board_and_stress_and_resets_each_lane(self) -> None:
        source = RUNNER.read_text(encoding="utf-8")

        self.assertIn("functional_shard_count=4", source)
        self.assertIn("android.testInstrumentationRunnerArguments.numShards", source)
        self.assertIn("android.testInstrumentationRunnerArguments.shardIndex", source)
        self.assertIn("android.testInstrumentationRunnerArguments.notClass=${stress_class},${gaming_board_class},${persistence_class}", source)
        self.assertIn("'gaming-board'", source)
        self.assertIn("android.testInstrumentationRunnerArguments.class=${gaming_board_class}", source)
        self.assertIn("'physical-frame-stress'", source)
        self.assertIn("android.testInstrumentationRunnerArguments.class=${stress_class}", source)
        self.assertLess(source.index("'gaming-board'"), source.index("functional-shard-${shard_index}"))
        self.assertLess(source.index("functional-shard-${shard_index}"), source.index("'physical-frame-stress'"))
        self.assertIn("KEYCODE_WAKEUP", source)
        self.assertIn("KEYCODE_HOME", source)
        self.assertIn("wait-for-broadcast-idle", source)
        self.assertIn("preserve_instrumentation_lane", source)
        self.assertIn('sample_host_health "${lane_dir}" before', source)
        self.assertIn('sample_host_health "${lane_dir}" after', source)
        self.assertIn('memory.events', source)
        self.assertIn('python3 "${shard_verifier}" discover', source)
        self.assertIn('python3 "${shard_verifier}" verify', source)

    def test_realistic_log_only_status_pairs_discover_each_test_once(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            log = root / "discovery.log"
            inventory = root / "tests.txt"
            events = []
            for class_name, test_name in (("example.SecondTest", "beta"), ("example.FirstTest", "alpha")):
                events.append(discovery_event(class_name, test_name, 1))
                events.append(discovery_event(class_name, test_name, 0))
            log.write_text(
                "\n".join((*events, "OK (2 tests)", "INSTRUMENTATION_CODE: -1", "")),
                encoding="utf-8",
            )

            result = subprocess.run(
                ["python3", str(VERIFIER), "discover", "--log", str(log), "--expected-tests", str(inventory)],
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(
                ["example.FirstTest#alpha", "example.SecondTest#beta"],
                inventory.read_text(encoding="utf-8").splitlines(),
            )

            log.write_text("\n".join((*events[:-1], "OK (2 tests)", "INSTRUMENTATION_CODE: -1")), encoding="utf-8")
            result = subprocess.run(
                ["python3", str(VERIFIER), "discover", "--log", str(log), "--expected-tests", str(inventory)],
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertNotEqual(0, result.returncode)

    def test_verifier_accepts_exact_union_and_rejects_duplicates(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            evidence = root / "evidence"
            stress_class = "example.PhysicalComponentFrameStressUiTest"
            gaming_class = "example.GamingBoardUiTest"
            persistence_class = "example.WorkspaceBackgroundPersistenceUiTest"
            stages = (
                ("persistence-stage-a", "stageAImportBothSlotsAndPersist"),
                ("persistence-stage-b", "stageBVerifyBothSlotsAfterProcessRestart"),
            )
            expected = [
                *(f"example.FunctionalTest#case{index}" for index in range(4)),
                f"{gaming_class}#board",
                f"{stress_class}#frame",
                *(f"{persistence_class}#{stage}" for _, stage in stages),
            ]
            inventory = root / "tests.txt"
            inventory.write_text("\n".join(expected) + "\n", encoding="utf-8")
            for lane, stage in stages:
                directory = evidence / lane
                directory.mkdir(parents=True)
                (directory / "instrumentation.log").write_text(single_test_log(persistence_class, stage))
            gaming_results = evidence / "gaming-board" / "results"
            gaming_results.mkdir(parents=True)
            (gaming_results / "TEST.xml").write_text(
                junit_xml([(gaming_class, "board", False)]), encoding="utf-8"
            )
            for index in range(4):
                results = evidence / f"functional-shard-{index}" / "results"
                results.mkdir(parents=True)
                (results / "TEST.xml").write_text(
                    junit_xml([("example.FunctionalTest", f"case{index}", index == 3)]),
                    encoding="utf-8",
                )
            stress_results = evidence / "physical-frame-stress" / "results"
            stress_results.mkdir(parents=True)
            (stress_results / "TEST.xml").write_text(
                junit_xml([(stress_class, "frame", False)]), encoding="utf-8"
            )
            summary = root / "summary.txt"
            command = [
                "python3", str(VERIFIER), "verify",
                "--expected-tests", str(inventory),
                "--evidence-root", str(evidence),
                "--functional-shards", "4",
                "--stress-class", stress_class,
                "--gaming-board-class", gaming_class,
                "--persistence-class", persistence_class,
                "--expected-skip", "example.FunctionalTest#case3",
                "--summary", str(summary),
            ]

            result = subprocess.run(command, text=True, capture_output=True, check=False)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn("executed=8", summary.read_text(encoding="utf-8"))
            self.assertIn("gaming_board=1", summary.read_text(encoding="utf-8"))
            self.assertIn("persistence=2", summary.read_text(encoding="utf-8"))
            stage_b = evidence / "persistence-stage-b" / "instrumentation.log"
            original_stage_b = stage_b.read_text()
            for malformed in (
                "",
                single_test_log(persistence_class, stages[0][1]),
                original_stage_b.replace("INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_STATUS_CODE: -4"),
                original_stage_b + original_stage_b,
            ):
                with self.subTest(persistence_log=malformed[-80:]):
                    stage_b.write_text(malformed)
                    rejected = subprocess.run(command, text=True, capture_output=True, check=False)
                    self.assertNotEqual(0, rejected.returncode)
            stage_b.write_text(original_stage_b)
            duplicate_xml = evidence / "functional-shard-0" / "results" / "TEST.xml"
            duplicate_xml.write_text(
                junit_xml([("example.FunctionalTest", "case0", False), ("example.FunctionalTest", "case1", False)]),
                encoding="utf-8",
            )
            result = subprocess.run(command, text=True, capture_output=True, check=False)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("more than once", result.stderr)

            duplicate_xml.write_text(
                junit_xml([("example.FunctionalTest", "case0", False),
                           (gaming_class, "board", False)]),
                encoding="utf-8",
            )
            result = subprocess.run(command, text=True, capture_output=True, check=False)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("Dedicated gaming board tests leaked", result.stderr)

            duplicate_xml.write_text(
                junit_xml([("example.FunctionalTest", "case0", False)]),
                encoding="utf-8",
            )
            (gaming_results / "TEST.xml").write_text(
                junit_xml([("example.FunctionalTest", "case1", False)]), encoding="utf-8"
            )
            result = subprocess.run(command, text=True, capture_output=True, check=False)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("Dedicated gaming board coverage differed", result.stderr)

    def test_single_parser_rejects_failed_skipped_duplicate_and_malformed_terminals(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "raw.log"
            valid = single_test_log("example.PersistenceTest", "stageA")
            command = ["python3", str(VERIFIER), "single", "--log", str(path),
                       "--expected-test", "example.PersistenceTest#stageA"]
            path.write_text(valid)
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            for malformed in (
                valid.replace("STATUS_CODE: 0", "STATUS_CODE: -2"),
                valid.replace("STATUS_CODE: 0", "STATUS_CODE: -4"),
                valid.replace("STATUS_CODE: 1", "STATUS_CODE: 0"),
                valid.replace("numtests=1", "numtests=2"),
                valid.replace("test=stageA", "test=stageB"),
                valid.replace("OK (1 test)", "OK (2 tests)"),
                valid.replace("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: 0"),
                valid.replace("INSTRUMENTATION_CODE: -1", ""),
                valid + "INSTRUMENTATION_FAILED: Process crashed\n",
                valid + "INSTRUMENTATION_CODE: -1\n",
                valid + valid,
            ):
                with self.subTest(log=malformed[-100:]):
                    path.write_text(malformed)
                    result = subprocess.run(command, capture_output=True, text=True)
                    self.assertNotEqual(0, result.returncode)

    def test_persistence_chain_installs_once_then_keeps_data_between_stages(self) -> None:
        source = RUNNER.read_text()
        start = source.index("run_persistence_chain()")
        end = source.index("\n\ninstall_alarm_test_apks()", start)
        function = source[start:end]
        harness = "\n".join((
            "set -euo pipefail",
            'install_discovery_test_apks() { echo install-and-initial-clear; }',
            'run_persistence_stage() { echo "$1 $2"; }',
            function,
            "run_persistence_chain",
        ))
        result = subprocess.run(["bash", "-c", harness], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([
            "install-and-initial-clear",
            "stageAImportBothSlotsAndPersist persistence-stage-a",
            "stageBVerifyBothSlotsAfterProcessRestart persistence-stage-b",
        ], result.stdout.splitlines())
        reset = source[source.index("reset_emulator_for_instrumentation_lane()"):
                       source.index("\n\nread_deep_idle_enabled()")]
        self.assertIn('shell am force-stop "${test_package}"', reset)
        self.assertIn('shell am force-stop "${app_package}"', reset)
        self.assertNotIn("pm clear", reset)
        self.assertNotIn("uninstall", reset)
        stage = source[source.index("run_persistence_stage()") : start]
        self.assertIn('reset_emulator_for_instrumentation_lane', stage)
        self.assertIn('dcompany.persistenceRunId "${persistence_run_id}"', stage)
        self.assertIn('"${raw_runner_args[@]}"', stage)
        self.assertIn('timeout=300', stage)
        self.assertIn('"${shard_verifier}" single', stage)

    def test_real_stage_launcher_passes_same_chain_pins_in_separate_invocations(self) -> None:
        source = RUNNER.read_text()
        stage = source[source.index("run_persistence_stage()"):
                       source.index("\n\nrun_persistence_chain()")]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bin_path = root / "bin"
            bin_path.mkdir()
            adb = bin_path / "adb"
            adb.write_text(
                "#!/usr/bin/env python3\n"
                "import json, os, sys\n"
                "with open(os.environ['EVENT_LOG'], 'a') as log:\n"
                "    log.write(json.dumps({'argv': sys.argv[1:], 'pid': os.getpid()}) + '\\n')\n"
                "identity = sys.argv[sys.argv.index('class') + 1]\n"
                "class_name, test_name = identity.split('#')\n"
                "for code in (1, 0):\n"
                "    print('INSTRUMENTATION_STATUS: class=' + class_name)\n"
                "    print('INSTRUMENTATION_STATUS: test=' + test_name)\n"
                "    print('INSTRUMENTATION_STATUS: numtests=1')\n"
                "    print('INSTRUMENTATION_STATUS_CODE: ' + str(code))\n"
                "print('OK (1 test)\\nINSTRUMENTATION_CODE: -1')\n"
            )
            adb.chmod(0o755)
            log = root / "events.jsonl"
            environment = {**os.environ, "EVENT_LOG": str(log),
                           "PATH": str(bin_path) + os.pathsep + os.environ["PATH"]}
            harness = "\n".join((
                "set -euo pipefail",
                f'shard_evidence_root="{root}/evidence"',
                f'shard_verifier="{VERIFIER}"',
                "device_serial=emulator-fake",
                "test_runner=example.test/Runner",
                "persistence_class=example.PersistenceTest",
                "persistence_run_id=" + "c" * 32,
                "raw_runner_args=(-e dcompany.sourceInventorySha256 " + "a" * 64
                + " -e dcompany.targetApkSha256 " + "b" * 64
                + " -e dcompany.testApkSha256 " + "d" * 64
                + " -e dcompany.captureRunId " + "e" * 32 + " -e dcompany.testDisplayId 0)",
                "sample_host_health() { :; }",
                "verify_instrumentation_provenance() { :; }",
                "reset_emulator_for_instrumentation_lane() { :; }",
                stage,
                "run_persistence_stage stageAImportBothSlotsAndPersist persistence-stage-a",
                "run_persistence_stage stageBVerifyBothSlotsAfterProcessRestart persistence-stage-b",
            ))
            result = subprocess.run(["bash", "-c", harness], capture_output=True, text=True,
                                    env=environment)
            self.assertEqual(0, result.returncode, result.stderr)
            calls = [json.loads(line) for line in log.read_text().splitlines()]
            self.assertEqual(2, len(calls))
            self.assertNotEqual(calls[0]["pid"], calls[1]["pid"])
            argument_maps = []
            for call in calls:
                argv = call["argv"]
                self.assertEqual(["-s", "emulator-fake", "shell", "am", "instrument", "-w", "-r"], argv[:7])
                arguments = {}
                for index, value in enumerate(argv):
                    if value == "-e":
                        arguments[argv[index + 1]] = argv[index + 2]
                argument_maps.append(arguments)
            self.assertEqual("example.PersistenceTest#stageAImportBothSlotsAndPersist", argument_maps[0].pop("class"))
            self.assertEqual("example.PersistenceTest#stageBVerifyBothSlotsAfterProcessRestart", argument_maps[1].pop("class"))
            self.assertEqual(argument_maps[0], argument_maps[1])
            self.assertEqual("c" * 32, argument_maps[0]["dcompany.persistenceRunId"])
            self.assertEqual("0", argument_maps[0]["dcompany.testDisplayId"])


class AndroidFailureDiagnosticsTest(unittest.TestCase):
    def write_failure(self, lane: Path, stack: str) -> Path:
        results = lane / "results"
        results.mkdir(parents=True, exist_ok=True)
        xml = results / "TEST-fixture.xml"
        xml.write_text(
            '<testsuite><properties><property name="secret" value="PRIVATE_PROPERTY" /></properties>'
            '<testcase classname="cloud.dcompany.erp.ui.WorkspaceScaffoldUiTest" '
            'name="largeTextEdgeScrollSuppressesTransitRoutesAndCommitsStableSettings" '
            'time="5.123"><failure type="private-type" message="PRIVATE_MESSAGE">'
            + escape(stack) + '</failure><system-out>PRIVATE_STDOUT</system-out>'
            '</testcase></testsuite>', encoding="utf-8",
        )
        return xml

    def test_console_projection_retains_exact_app_callsite_and_known_health_only(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            lane = Path(directory)
            self.write_failure(lane, "\n".join((
                "androidx.compose.ui.test.ComposeTimeoutException: PRIVATE_UI_TEXT",
                "\tat androidx.compose.ui.test.AndroidComposeUiTestEnvironment.waitUntil(ComposeUiTest.android.kt:441)",
                "\tat cloud.dcompany.erp.ui.WorkspaceScaffoldUiTest.waitForStableRailTargetOutsideEdgeZone(WorkspaceScaffoldUiTest.kt:2448)",
                "Caused by: java.lang.AssertionError: PRIVATE_CAUSE",
                "\tat private.customer.Secret.read(Customer.java:13)",
                "\tat cloud.dcompany.erp.ui.Test.method(PRIVATE_PATH/Customer.kt:1)",
            )))
            (lane / "host-health.txt").write_text("\n".join((
                "phase=before utc=2026-11-12T10:00:00Z", "MemAvailable:  1234 kB",
                "SwapFree:  0 kB", "memory.current=12345", "memory.max=max",
                "cgroup_oom 0", "cgroup_oom_kill 0", "cgroup_oom_group_kill 0",
                "process   12  1  98765 qemu-system-x86", "phase=after utc=2026-11-12T10:01:00Z",
                "process 12 1 98765 qemu-system-x86 --private-credential", "PRIVATE_HOST_ERROR",
            )))
            output = failure_diagnostics(lane)
            self.assertIn("WorkspaceScaffoldUiTest.kt:2448", output)
            self.assertIn("ComposeUiTest.android.kt:441", output)
            self.assertIn("exception_type=java.lang.AssertionError", output)
            self.assertIn("test_seconds=5.123", output)
            self.assertIn("host phase=before", output)
            self.assertIn("host phase=after", output)
            self.assertIn("host cgroup_oom_kill 0", output)
            self.assertIn("98765 qemu-system-x86", output)
            for secret in ("PRIVATE", "private.customer", "private-type", "--private-credential"):
                self.assertNotIn(secret, output)

    def test_output_is_bounded_and_invalid_xml_never_echoes_text(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            lane = Path(directory)
            frame = "at cloud.dcompany.erp.ui.Test.method(Test.kt:12)\n"
            xml = self.write_failure(lane, frame * 10_000)
            output = failure_diagnostics(lane)
            self.assertEqual(48, output.count("  at "))
            self.assertIn("stack=limit-reached", output)
            self.assertLess(len(output.encode("ascii")), 32_000)
            for invalid in ('<bad PRIVATE_XML', '<!DOCTYPE x [<!ENTITY x "PRIVATE_ENTITY">]><testsuite />'):
                xml.write_text(invalid)
                output = failure_diagnostics(lane)
                self.assertIn("xml=omitted-invalid-or-oversized", output)
                self.assertNotIn("PRIVATE", output)
            xml.write_text("x" * (8 * 1024 * 1024 + 1))
            self.assertIn("xml=omitted-invalid-or-oversized", failure_diagnostics(lane))
            xml.unlink()
            outside = lane / "outside.xml"
            outside.write_text("PRIVATE_SYMLINK")
            xml.symlink_to(outside)
            self.assertIn("xml=omitted-invalid-or-oversized", failure_diagnostics(lane))

    def test_failure_sample_count_and_host_size_are_bounded(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            lane = Path(directory)
            xml = self.write_failure(lane, "java.lang.AssertionError: PRIVATE_MESSAGE")
            case = xml.read_text().split("<testcase", 1)[1].split("</testcase>", 1)[0]
            xml.write_text("<testsuite>" + ("<testcase" + case + "</testcase>") * 100 + "</testsuite>")
            (lane / "host-health.txt").write_text("PRIVATE_HOST" * 3000)
            output = failure_diagnostics(lane)
            self.assertEqual(8, output.count("failed_test="))
            self.assertIn("failures=limit-reached", output)
            self.assertIn("host=unavailable-or-oversized", output)
            self.assertNotIn("PRIVATE", output)

    def test_invalid_identity_or_duration_is_not_printed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            lane = Path(directory)
            xml = self.write_failure(lane, "java.lang.AssertionError: PRIVATE")
            xml.write_text(xml.read_text().replace('time="5.123"', 'time="PRIVATE_TIME"'))
            self.assertNotIn("PRIVATE", failure_diagnostics(lane))
            xml.write_text(xml.read_text().replace('name="largeTextEdgeScrollSuppressesTransitRoutesAndCommitsStableSettings"',
                                                 'name="PRIVATE name with spaces"'))
            output = failure_diagnostics(lane)
            self.assertIn("test=omitted-invalid-identity", output)
            self.assertNotIn("PRIVATE", output)

    def test_failed_lane_retains_artifacts_and_original_status_if_diagnostics_fail_or_timeout(self) -> None:
        source = RUNNER.read_text()
        functions = source[source.index("clear_connected_outputs() {"):source.index("run_persistence_stage() {")]
        for mode in ("real", "failed", "timeout", "successful-lane"):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                verifier = VERIFIER
                if mode in ("failed", "timeout", "successful-lane"):
                    verifier = root / "diagnostic.py"
                    verifier.write_text("import time\ntime.sleep(30)\n" if mode == "timeout" else
                                        "print('PRIVATE_DIAGNOSTIC_ERROR')\nraise SystemExit(7)\n")
                fixture = root / "fixture"
                self.write_failure(fixture, "java.lang.AssertionError: PRIVATE\n"
                                   "at cloud.dcompany.erp.ui.Test.capture(Test.kt:123)")
                harness = "\n".join((
                    "set -Eeuo pipefail", f'android_root="{root}/android"',
                    'connected_results_dir="${android_root}/app/build/outputs/androidTest-results/connected/debug"',
                    'connected_report_dir="${android_root}/app/build/reports/androidTests/connected/debug"',
                    f'shard_evidence_root="{root}/lanes"', f'shard_verifier="{verifier}"',
                    "gradle_runner_args=()", functions,
                    'sample_host_health() { echo "phase=$2 utc=2026-11-12T10:00:00Z" >> "$1/host-health.txt"; }',
                    "verify_instrumentation_provenance() { return 0; }",
                    "reset_emulator_for_instrumentation_lane() { return 0; }",
                    'run_gradle_ci() { mkdir -p "$connected_results_dir" "$connected_report_dir"; '
                    f'cp "{fixture}/results/TEST-fixture.xml" "$connected_results_dir/"; '
                    'echo report > "$connected_report_dir/index.html"; '
                    + ("return 0; }" if mode == "successful-lane" else "return 7; }"),
                    "run_connected_instrumentation_lane functional-shard-0",
                ))
                result = subprocess.run(["bash", "-c", harness], text=True, capture_output=True, timeout=15)
                self.assertEqual(0 if mode == "successful-lane" else 1, result.returncode, result.stderr)
                retained = root / "lanes/functional-shard-0"
                self.assertTrue((retained / "results/TEST-fixture.xml").is_file())
                self.assertTrue((retained / "report/index.html").is_file())
                health = (retained / "host-health.txt").read_text()
                self.assertIn("phase=before", health)
                self.assertIn("phase=after", health)
                self.assertNotIn("PRIVATE", result.stdout + result.stderr)
                if mode == "real":
                    self.assertIn("Test.kt:123", result.stdout)
                elif mode != "successful-lane":
                    self.assertEqual(1, result.stdout.count("original lane failure retained"))
                else:
                    self.assertNotIn("diagnostics", result.stdout)


class CaptureFailureProjectionTest(unittest.TestCase):
    identity = "cloud.dcompany.erp.ui.PhysicalComponentFrameStressUiTest#workspaceChromeAndGamingCardsHaveBoundedFramesAndStableGeometry"
    prefix = "Suppressed: java.lang.AssertionError: DCOMPANY_CAPTURE_FAILURE_V1="

    def observation(self):
        return {
            "schema_version": 1, "stage": "capture_failure", "surface": "physical_component_harness",
            "screenshot": "01-gaming-online.png", "failure_kind": "COMPOSE_TIMEOUT",
            "after_failure": True, "atomic_observation": False, "caller_is_main": False,
            "wait_budget_ms": 150, "post_accepted": True, "wait_finished": True, "status": "COMPLETED",
            "window": {"lifecycle": "RESUMED", "finishing": False, "destroyed": False,
                       "visibility": "VISIBLE", "window_visibility": "VISIBLE",
                       "attached": True, "shown": True, "focused": True, "width": 2560,
                       "height": 1600, "hardware_accelerated": True},
        }

    def project(self, value, identity=None):
        return capture_failure_projection(self.prefix + json.dumps(value), identity or self.identity)

    def test_closed_record_survives_failed_xml_only_without_private_text(self):
        value = self.observation()
        self.assertEqual(value, json.loads(self.project(value).removeprefix("capture_failure=")))
        with tempfile.TemporaryDirectory() as directory:
            lane = Path(directory)
            results = lane / "results"
            results.mkdir()
            text = "private failure message\n" + ("\t" + self.prefix + json.dumps(value) + "\n") * 2
            (results / "capture.xml").write_text(
                '<testsuite><testcase classname="' + self.identity.split('#')[0] + '" name="' + self.identity.split('#')[1]
                + '"><failure>' + escape(text) + '</failure><system-out>PRIVATE</system-out></testcase></testsuite>')
            output = failure_diagnostics(lane)
            self.assertEqual(1, output.count("capture_failure="))
            self.assertNotIn("private failure message", output)
            self.assertNotIn("PRIVATE", output)
            (results / "capture.xml").write_text(
                '<testsuite><testcase classname="' + self.identity.split('#')[0] + '" name="' + self.identity.split('#')[1]
                + '"><system-out>' + escape(text) + '</system-out></testcase></testsuite>')
            self.assertNotIn("capture_failure=", failure_diagnostics(lane))

    def test_each_filename_is_bound_to_its_test(self):
        filenames = {
            "workspaceChromeAndGamingCardsHaveBoundedFramesAndStableGeometry": ["01-gaming-online.png", "02-gaming-server-issue.png", "03-gaming-after-connectivity-stress.png"],
            "gamingTimersAtProductionCadenceHaveStableGeometry": ["component-gaming-timer-1hz-before.png", "component-gaming-timer-1hz-after.png"],
            "connectivityAtRecoveryCadenceHasStableGeometry": ["component-connectivity-only-3s-before.png", "component-connectivity-only-3s-after.png"],
        }
        for method, allowed in filenames.items():
            for owner, names in filenames.items():
                for name in names:
                    value = self.observation()
                    value["screenshot"] = name
                    projected = self.project(value, self.identity.split('#')[0] + '#' + method)
                    self.assertEqual(owner == method, projected is not None)

    def test_unavailable_samples_and_deadline_race_are_honest(self):
        for status, accepted in (("POST_REJECTED", False), ("WAIT_EXPIRED", True), ("COLLECTION_FAILED", True)):
            value = self.observation()
            value.update(status=status, window=None, post_accepted=accepted, wait_finished=False)
            self.assertIsNotNone(self.project(value))
        value = self.observation()
        value["wait_finished"] = False  # completion won CAS just as await expired
        self.assertIsNotNone(self.project(value))

    def test_private_malformed_and_inconsistent_records_are_rejected(self):
        mutations = (
            lambda v: v.update(message="PRIVATE"), lambda v: v["window"].update(tree="PRIVATE"),
            lambda v: v.update(screenshot="/private/01-gaming-online.png"),
            lambda v: v.update(failure_kind="private.CustomerException"),
            lambda v: v.update(schema_version=True), lambda v: v.update(wait_budget_ms=151),
            lambda v: v.update(wait_finished=1), lambda v: v.update(after_failure=False),
            lambda v: v.update(atomic_observation=True), lambda v: v["window"].update(width=True),
            lambda v: v["window"].update(height=-1), lambda v: v["window"].update(width=100001),
            lambda v: v["window"].update(lifecycle="PRIVATE"), lambda v: v.update(window=None),
            lambda v: v["window"].update(visibility=0), lambda v: v["window"].update(window_visibility="PRIVATE"),
            lambda v: v.update(status="POST_REJECTED"), lambda v: v.update(post_accepted=False),
            lambda v: v.pop("stage"), lambda v: v.update(status="WAIT_EXPIRED", window=None),
        )
        for mutation in mutations:
            value = self.observation()
            mutation(value)
            self.assertIsNone(self.project(value))
        valid = self.prefix + json.dumps(self.observation())
        for line in (valid + "x" * 4096, valid.replace('"width": 2560', '"width": 2560, "width": 1'),
                     self.prefix + "[]", self.prefix + "{" * 2000, valid + "trailing"):
            self.assertIsNone(capture_failure_projection(line, self.identity))
        self.assertIsNone(capture_failure_projection(valid, self.identity.replace("PhysicalComponentFrameStressUiTest", "Private")))

    def test_capture_adapter_does_not_change_capture_or_wait_for_ui(self):
        source = (ROOT / "android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/PhysicalSmoothnessUiTest.kt").read_text()
        method = source.split("private fun writeScreenshot(", 1)[1].split("private fun writeMetrics(", 1)[0]
        self.assertEqual(1, method.count("captureToImage()"))
        self.assertIn("capture = { compose.onRoot().captureToImage() }", method)
        self.assertIn("auditActivity = compose.activity", source.split("private fun setAuditContent(", 1)[1])
        adapter = (ROOT / "android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/CaptureFailureDiagnostics.kt").read_text()
        for forbidden in ("runOnMainSync", "runOnIdle", "onActivity", "waitForIdle", "invalidate(", "captureToImage", "failure.message", "failure.stackTrace", "setDrawingEnabled"):
            self.assertNotIn(forbidden, adapter)
        self.assertIn("stackTrace = emptyArray()", adapter)
        build = (ROOT / "android-native/app/build.gradle.kts").read_text()
        self.assertEqual(2, build.count('java.srcDir("src/testShared/java")'))
        self.assertNotIn('sourceSets.getByName("main").java.srcDir("src/testShared/java")', build)


class BackgroundLifecycleProjectionTest(unittest.TestCase):
    identity = "cloud.dcompany.erp.ui.theme.WorkspaceBackgroundUiTest#settingsCardIsReachableAtCompactFontScaleInDayAndNight"
    prefix = "Suppressed: java.lang.AssertionError: DCOMPANY_BACKGROUND_LIFECYCLE_FAILURE_V1="

    def observation(self):
        return {"schema_version": 1, "surface": "workspace_background_settings", "atomic_observation": False,
                "dropped": 0, "events": [
                    {"sequence": 1, "uptime_ms": 0, "phase": "RULE_ENTER", "lifecycle": None, "finishing": None, "destroyed": None},
                    {"sequence": 2, "uptime_ms": 20, "phase": "OWNER_BOUND", "lifecycle": None, "finishing": None, "destroyed": None},
                    {"sequence": 3, "uptime_ms": 200, "phase": "ACTIVITY_PRE_DESTROY", "lifecycle": "CREATED", "finishing": True, "destroyed": False},
                ]}

    def project(self, value):
        return background_lifecycle_projection(self.prefix + json.dumps(value), self.identity)

    def test_closed_partial_record_survives_failure_xml_once_without_private_text(self):
        value = self.observation()
        self.assertEqual(value, json.loads(self.project(value).removeprefix("background_lifecycle_failure=")))
        with tempfile.TemporaryDirectory() as directory:
            lane = Path(directory)
            (lane / "results").mkdir()
            text = "private failure text\n" + (self.prefix + json.dumps(value) + "\n") * 2
            body = '<testcase classname="' + self.identity.split('#')[0] + '" name="' + self.identity.split('#')[1] + '">'
            path = lane / "results/background.xml"
            path.write_text('<testsuite>' + body + '<failure>' + escape(text) + '</failure><system-out>PRIVATE</system-out></testcase></testsuite>')
            output = failure_diagnostics(lane)
            self.assertEqual(1, output.count("background_lifecycle_failure="))
            self.assertNotIn("private failure text", output)
            self.assertNotIn("PRIVATE", output)
            path.write_text('<testsuite>' + body + '<system-out>' + escape(text) + '</system-out></testcase></testsuite>')
            self.assertNotIn("background_lifecycle_failure=", failure_diagnostics(lane))

    def test_extra_private_malformed_and_incoherent_facts_are_rejected(self):
        mutations = (
            lambda v: v.update(message="PRIVATE"), lambda v: v.update(schema_version=True),
            lambda v: v.update(atomic_observation=True), lambda v: v.update(dropped=True),
            lambda v: v.update(dropped=1), lambda v: v["events"][0].update(sequence=True),
            lambda v: v["events"][1].update(sequence=1), lambda v: v["events"][1].update(uptime_ms=-1),
            lambda v: v["events"][1].update(uptime_ms=86_400_001),
            lambda v: v["events"][2].update(uptime_ms=19), lambda v: v["events"][2].update(lifecycle="PRIVATE"),
            lambda v: v["events"][2].update(finishing=1), lambda v: v["events"][0].update(lifecycle="RESUMED"),
            lambda v: v["events"][1].update(phase="RULE_ENTER"), lambda v: v["events"][0].update(phase="BODY_RETURNED"),
            lambda v: v["events"][1].update(phase="RULE_RETURNED"), lambda v: v["events"][2].update(message="PRIVATE"),
            lambda v: v["events"][2].update(phase="RULE_RETURNED", lifecycle=None, finishing=None, destroyed=None),
            lambda v: v.update(events=v["events"] * 11),
        )
        for mutate in mutations:
            value = self.observation()
            mutate(value)
            self.assertIsNone(self.project(value))

    def test_exact_identity_prefix_and_duplicate_key_boundary(self):
        valid = self.prefix + json.dumps(self.observation())
        self.assertIsNone(background_lifecycle_projection(valid, self.identity.replace("settingsCard", "otherCard")))
        self.assertIsNone(background_lifecycle_projection(valid.replace("_V1=", "_V2="), self.identity))
        self.assertIsNone(background_lifecycle_projection(valid.replace('"dropped": 0', '"dropped": 0, "dropped": 0'), self.identity))
        self.assertIsNone(background_lifecycle_projection(valid.replace('"sequence": 1', '"sequence": 1, "sequence": 1'), self.identity))
        self.assertIsNone(background_lifecycle_projection(valid + "x" * 12_000, self.identity))
        self.assertIsNone(background_lifecycle_projection(self.prefix + "{" * 2000, self.identity))

    def test_outer_rule_and_markers_do_not_add_ui_operations_or_duplicate_compose_rule(self):
        source = (ROOT / "android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/theme/WorkspaceBackgroundUiTest.kt").read_text()
        self.assertNotIn('@get:Rule val compose', source)
        self.assertEqual(1, source.count('RuleChain.outerRule(lifecycleTrace).around(compose)'))
        rule = (ROOT / "android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/theme/BackgroundLifecycleTraceRule.kt").read_text()
        for forbidden in ("runOnMainSync", "runOnIdle", "onActivity", "waitForIdle", "invalidate(", "failure.message", "failure.stackTrace", "setContent("):
            # Lifecycle callback names are legitimate; reject dispatch APIs specifically.
            if forbidden == "onActivity":
                self.assertNotIn('.onActivity {', rule)
            else:
                self.assertNotIn(forbidden, rule)
        self.assertIn('stackTrace = emptyArray()', rule)
        self.assertLess(rule.index('trace?.close()'), rule.index('unregisterActivityLifecycleCallbacks'))
        method = source.split('fun settingsCardIsReachableAtCompactFontScaleInDayAndNight()', 1)[1].split('    @Test', 1)[0]
        self.assertLess(method.index('assertDoesNotExist()'), method.index('BackgroundPhase.BODY_RETURNED'))


class EntryBackFailureProjectionTest(unittest.TestCase):
    identity = "cloud.dcompany.erp.ui.screens.ExecutiveEntryPresentationUiTest#freshLoginBackUsesTheSameBoundedCosmeticContinuation"
    prefix = "Suppressed: java.lang.AssertionError: DCOMPANY_ENTRY_BACK_FAILURE_V1="

    def observation(self):
        dispatch = {"has_enabled_callbacks": True, "lifecycle": "RESUMED", "finishing": False,
                    "destroyed": False, "window_focus": True}
        return {"schema_version": 1, "surface": "fresh_login_back", "stage": "after_failure", "atomic_observation": False,
                "before_dispatch": dict(dispatch), "after_dispatch": dict(dispatch), "auto_advance": True,
                "clock_before_ms": 100, "clock_first_poll_ms": 100, "clock_last_poll_ms": 180,
                "poll_virtual_elapsed_ms": 80, "poll_wall_elapsed_ms": 4990, "polls": 6,
                "ack_state": "EMPTY", "ack_count": 0, "callback_seen": False, "callback_on_main": None}

    def project(self, value):
        return entry_back_failure_projection(self.prefix + json.dumps(value), self.identity)

    def test_exact_closed_failure_record_is_emitted_once_without_private_text_or_system_out(self):
        value = self.observation()
        self.assertEqual(value, json.loads(self.project(value).removeprefix("entry_back_failure=")))
        with tempfile.TemporaryDirectory() as directory:
            lane = Path(directory)
            (lane / "results").mkdir()
            case = '<testcase classname="' + self.identity.split('#')[0] + '" name="' + self.identity.split('#')[1] + '">'
            text = "private failure\n" + (self.prefix + json.dumps(value) + "\n") * 2
            path = lane / "results/entry.xml"
            path.write_text('<testsuite>' + case + '<failure>' + escape(text) + '</failure><system-out>PRIVATE</system-out></testcase></testsuite>')
            output = failure_diagnostics(lane)
            self.assertEqual(1, output.count("entry_back_failure="))
            self.assertNotIn("private failure", output)
            self.assertNotIn("PRIVATE", output)
            path.write_text('<testsuite>' + case + '<system-out>' + escape(text) + '</system-out></testcase></testsuite>')
            self.assertNotIn("entry_back_failure=", failure_diagnostics(lane))

    def test_ack_categories_and_unknown_dispatch_are_closed_and_bounded(self):
        for state, count in (("EMPTY", 0), ("EXACT_94", 1), ("OTHER_SINGLE", 1), ("MULTIPLE", 2), ("MULTIPLE", 1_000_000), ("UNAVAILABLE", None)):
            value = self.observation()
            value.update(ack_state=state, ack_count=count, before_dispatch=None, after_dispatch=None,
                         callback_seen=True, callback_on_main=True)
            self.assertIsNotNone(self.project(value))  # A late exact ack remains failure metadata.
        for state, count in (("EMPTY", 1), ("EXACT_94", 94), ("MULTIPLE", 1), ("MULTIPLE", 1_000_001), ("UNAVAILABLE", 0), ("EMPTY", False), ("private_nonce", 1)):
            value = self.observation()
            value.update(ack_state=state, ack_count=count)
            self.assertIsNone(self.project(value))

    def test_poll_boundaries_null_invalid_elapsed_and_no_poll_failure_are_supported(self):
        value = self.observation()
        value.update(polls=0, clock_first_poll_ms=None, clock_last_poll_ms=None,
                     poll_virtual_elapsed_ms=None, poll_wall_elapsed_ms=None)
        self.assertIsNotNone(self.project(value))
        value.update(polls=1, clock_first_poll_ms=20, clock_last_poll_ms=20,
                     poll_virtual_elapsed_ms=0, poll_wall_elapsed_ms=0)
        self.assertIsNotNone(self.project(value))
        value.update(polls=3, clock_first_poll_ms=30, clock_last_poll_ms=20, poll_virtual_elapsed_ms=None)
        self.assertIsNotNone(self.project(value))
        value.update(clock_first_poll_ms=0, clock_last_poll_ms=86_400_000, poll_virtual_elapsed_ms=86_400_000)
        self.assertIsNotNone(self.project(value))
        value.update(clock_last_poll_ms=86_400_001, poll_virtual_elapsed_ms=None)
        self.assertIsNotNone(self.project(value))

    def test_private_unknown_malformed_or_incoherent_fields_never_escape(self):
        mutations = (
            lambda v: v.update(message="PRIVATE"), lambda v: v["before_dispatch"].update(uri="PRIVATE"),
            lambda v: v.update(schema_version=True), lambda v: v.update(atomic_observation=True),
            lambda v: v.update(polls=True), lambda v: v.update(polls=1_000_001),
            lambda v: v.update(clock_before_ms=-1), lambda v: v.update(clock_last_poll_ms=10**12 + 1),
            lambda v: v.update(poll_virtual_elapsed_ms=True), lambda v: v.update(poll_virtual_elapsed_ms=81),
            lambda v: v.update(poll_wall_elapsed_ms=float("inf")), lambda v: v.update(callback_seen=1),
            lambda v: v["after_dispatch"].update(lifecycle="private.Exception"),
            lambda v: v["before_dispatch"].update(window_focus="PRIVATE"), lambda v: v.update(auto_advance=1),
            lambda v: v.update(polls=0), lambda v: v.update(polls=1),
        )
        for mutation in mutations:
            value = self.observation()
            mutation(value)
            self.assertIsNone(self.project(value))
        valid = self.prefix + json.dumps(self.observation())
        for line in (valid + 'trailing', valid + 'x' * 4096, self.prefix + '{' * 2000,
                     valid.replace('"polls": 6', '"polls": 6, "polls": 6'),
                     valid.replace('"window_focus": true', '"window_focus": true, "window_focus": true')):
            self.assertIsNone(entry_back_failure_projection(line, self.identity))
        self.assertIsNone(entry_back_failure_projection(valid, self.identity.replace("freshLoginBack", "otherBack")))
        self.assertIsNone(entry_back_failure_projection(valid.replace('_V1=', '_V2='), self.identity))

    def test_observation_preserves_single_dispatch_predicate_assertions_and_failure_only_cached_reads(self):
        source = (ROOT / 'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/ExecutiveEntryPresentationUiTest.kt').read_text()
        method = source.split('fun freshLoginBackUsesTheSameBoundedCosmeticContinuation()', 1)[1].split('    @Test', 1)[0]
        self.assertEqual(1, method.count('onBackPressedDispatcher.onBackPressed()'))
        self.assertEqual(1, method.count('acknowledgements == listOf(94L)'))
        self.assertEqual(1, method.count('assertEquals(listOf(94L), acknowledgements)'))
        self.assertEqual(1, method.count('waitForFiniteEntryAcknowledgement {'))
        adapter = source.split('private fun waitForFiniteEntryAcknowledgement(', 1)[1].split('    private fun mainEntryRoot()', 1)[0]
        self.assertIn('advanceTimeBy(milliseconds, ignoreFrameDuration = true)', adapter)
        self.assertIn('clock = object : RailWaitClock', adapter)
        self.assertIn('set(value) { compose.mainClock.autoAdvance = value }', adapter)
        self.assertIn('ComposeTimeoutException("Condition still not satisfied after 5000 ms")', adapter)
        self.assertIn('captureWithFailureObservation(', method)
        self.assertIn('runCatching { EntryBackFailureObservation() }.getOrNull()', method)
        self.assertLess(method.index('observation?.callbackEntered()'), method.index('assertEquals("Continuation must run on main"'))
        self.assertLess(method.index('assertEquals("Back dispatch must run on main"'), method.index('observation?.beforeDispatch'))
        helper = (ROOT / 'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/EntryBackFailureObservation.kt').read_text()
        attach = helper.split('fun attach(', 1)[1].split('private fun clockMs(', 1)[0]
        for forbidden in ('currentTime', 'nanoTime', 'dispatchState(', 'runOnIdle', 'waitForIdle', 'advanceTimeBy', 'failure.message', 'failure.stackTrace'):
            self.assertNotIn(forbidden, attach)
        self.assertIn('stackTrace = emptyArray()', attach)
        self.assertNotIn('waitForWallPacedRailVisibility', method + helper)


class RailFailureProjectionTest(unittest.TestCase):
    identity = "cloud.dcompany.erp.ui.WorkspaceScaffoldUiTest#largeTextEdgeScrollSuppressesTransitRoutesAndCommitsStableSettings"
    prefix = "Suppressed: java.lang.AssertionError: DCOMPANY_RAIL_FAILURE_V1="

    def observation(self):
        return {
            "stage": "settings-visibility", "failure_wall_ms": 12345, "failure_uptime_ms": 300,
            "pointer_held": True, "last_predicate_class": "java.lang.AssertionError",
            "last_predicate_kind": "DISPLAY_ASSERTION",
            "trace": {"stored": 512, "dropped": 7, "latest": None,
                      "last_stopped": {"sequence": 513, "uptime_ms": 200, "kind": "STOPPED",
                                       "phase": "Ended", "candidate": None, "target": None,
                                       "committed": "Dashboard", "observed": "Dashboard",
                                       "scroll_delta": 0, "stop": "PRECLAIM_CONSUMED"}},
            "input": {"count": 2,
                      "first": {"action": 0, "event_ms": 100, "down_ms": 100, "wall_ms": 12300,
                                "x": 50.0, "y": 20.0, "pointers": 1},
                      "latest": {"action": 2, "event_ms": 148, "down_ms": 100, "wall_ms": 12320,
                                 "x": 50.0, "y": 300.0, "pointers": 1}},
            "sample": {"status": "READY", "route": "Dashboard", "focused": True, "resumed": True,
                       "attached": True, "laid_out": True, "layout_requested": False,
                       "pending_compose_layout": False, "touch_exploration": False,
                       "accessibility_enabled": False, "decor_width": 2560, "decor_height": 1600,
                       "root": {"bounds": [0, 0, 2560, 720], "attached": True, "placed": True},
                       "dashboard": None, "viewport": None, "settings": None,
                       "scroll": {"value": 0.0, "max": 500.0, "reverse": False}},
        }

    def test_closed_observation_survives_xml_projection_without_message(self):
        value = self.observation()
        projected = rail_failure_projection(self.prefix + json.dumps(value), self.identity)
        self.assertEqual(value, json.loads(projected.removeprefix("rail_failure=")))
        with tempfile.TemporaryDirectory() as directory:
            lane = Path(directory)
            AndroidFailureDiagnosticsTest().write_failure(lane,
                "java.lang.AssertionError: PRIVATE\n" + "at cloud.dcompany.erp.ui.Test.method(Test.kt:1)\n" * 100 + "\t" + self.prefix + json.dumps(value))
            output = failure_diagnostics(lane)
            self.assertIn('"stop":"PRECLAIM_CONSUMED"', output)
            self.assertIn('"dropped":7', output)
            self.assertIn('"event_ms":148', output)
            self.assertNotIn("PRIVATE", output)
        for status in ("TIMEOUT", "ERROR", "POST_REJECTED"):
            value["sample"] = {"status": status}
            self.assertIsNotNone(rail_failure_projection(self.prefix + json.dumps(value), self.identity))

    def test_unknown_private_or_unbounded_fields_are_never_projected(self):
        mutations = (
            lambda value: value.update({"message": "PRIVATE"}),
            lambda value: value["sample"].update({"semantics_tree": "PRIVATE"}),
            lambda value: value["input"]["latest"].update({"x": float("inf")}),
            lambda value: value["trace"].update({"stored": 513}),
            lambda value: value.update({"pointer_held": "PRIVATE"}),
            lambda value: value.update({"last_predicate_class": "private.customer.Exception"}),
            lambda value: value["trace"]["last_stopped"].update({"stop": "PRIVATE"}),
        )
        for mutation in mutations:
            value = self.observation()
            mutation(value)
            self.assertIsNone(rail_failure_projection(self.prefix + json.dumps(value), self.identity))
        valid = self.prefix + json.dumps(self.observation())
        self.assertIsNone(rail_failure_projection(valid, "cloud.dcompany.erp.ui.Other#test"))
        self.assertIsNone(rail_failure_projection(valid.replace('"pointer_held": true', '"pointer_held": true, "pointer_held": false'), self.identity))
        self.assertIsNone(rail_failure_projection(valid + "x" * 12_000, self.identity))

    def test_observer_keeps_original_gesture_and_failure_boundary(self):
        source = (ROOT / "android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/WorkspaceScaffoldUiTest.kt").read_text()
        method = source.split("fun largeTextEdgeScrollSuppressesTransitRoutesAndCommitsStableSettings()", 1)[1].split("    @Test", 1)[0]
        self.assertIn('pointer.slideTo(Offset(dashboard.x, viewport.bottom - 3f))', method)
        self.assertIn('waitForWallPacedRailVisibility(', method)
        self.assertIn('compose.mainClock.advanceTimeBy(milliseconds, ignoreFrameDuration = true)', method)
        self.assertIn(') {\n                runCatching {\n                    compose.onNodeWithTag("workspace-rail-Settings").assertIsDisplayed()\n                    true\n                }.onFailure { lastPredicateFailure = it }.getOrDefault(false)', method)
        self.assertIn('compose.waitUntil(3_000) { destinations.lastOrNull() == Destination.Settings }', method)
        self.assertLess(method.index('railFailureObservation('), method.index('throw failure'))
        self.assertLess(method.index('throw failure'), method.index('pointer.cancelIfDown()'))
        helper = source.split('private fun railFailureObservation(', 1)[1].split('private inner class HeldPointer', 1)[0]
        self.assertIn('ready.await(250, java.util.concurrent.TimeUnit.MILLISECONDS)', helper)
        self.assertIn('handler.removeCallbacks(callback)', helper)
        self.assertNotIn('runOnMainSync', helper)
        self.assertNotIn('compose.runOnIdle', helper)
        self.assertNotIn('predicateFailure.message', helper)
        self.assertIn('motionEventSpy(motionEventObserver)', source)
        paced_method = method.replace('compose.mainClock.advanceTimeBy(milliseconds, ignoreFrameDuration = true)', '')
        for operation in ('performScrollTo', 'Thread.sleep', 'advanceTimeBy'):
            self.assertNotIn(operation, paced_method + helper)


class RequiredLaneOperationsTest(unittest.TestCase):
    """Execute shipped helpers in the conditional contexts that disable errexit."""

    def run_lane(self, mode: str = "ok", *, action: str = "lane") -> tuple[int, list[str], dict[str, str]]:
        source = RUNNER.read_text()
        reset = source[source.index("reassert_tablet_viewport() {"):source.index("read_deep_idle_enabled() {")]
        connected = source[source.index("clear_connected_outputs() {"):source.index("run_persistence_stage() {")]
        persistence = source[source.index("run_persistence_stage() {"):source.index("run_persistence_chain() {")]
        chain = source[source.index("run_persistence_chain() {"):source.index("install_alarm_test_apks() {")]
        alarm = source[source.index("install_alarm_test_apks() {"):source.index("trap cleanup_alarm_environment EXIT")]
        loop_start = source.rindex('if [[ "${status}" -eq 0 ]]; then', 0, source.index('  for ((shard_index'))
        loop = source[loop_start:source.index('\n\nif [[ "${status}" -eq 0 ]]; then', loop_start + 1)]
        alarm_start = source.index('  elif ! {\n    reset_emulator_for_instrumentation_lane')
        alarm_group = source[alarm_start + len('  elif ! '):source.index('; then', alarm_start)]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            setup = r'''
set -Eeuo pipefail
android_root="$ROOT/android"
connected_results_dir="$android_root/app/build/outputs/androidTest-results/connected/debug"
connected_report_dir="$android_root/app/build/reports/androidTests/connected/debug"
shard_evidence_root="$ROOT/lanes"
device_serial=emulator-test; app_package=app; test_package=test
gradle_runner_args=(); raw_runner_args=(); persistence_run_id=fixed
persistence_class=Fixture; test_runner=FixtureRunner; alarm_setup_report="$ROOT/alarm.log"
trace() { printf '%s\n' "$1" >> "$ROOT/trace"; }
op() { trace "$1"; [[ "$MODE" != "$1" ]]; }
adb() {
 local args="$*" tag=unknown
 case "$args" in
  *'force-stop test') tag=stop-test;; *'force-stop app') tag=stop-app;;
  *KEYCODE_WAKEUP) tag=wake;; *KEYCODE_HOME) tag=home;;
  *'statusbar collapse') tag=collapse;;
  *'dumpsys window') tag=launcher
    if [[ "$MODE" == launcher-exhaust || "$MODE" == sleep ]] ||
       { [[ "$MODE" == launcher-transient ]] && [[ ! -f "$ROOT/seen" ]]; }; then
      echo 'mCurrentFocus=Window{other}'; : > "$ROOT/seen"
    else echo 'mCurrentFocus=Window{launcher}'; fi;;
  *wait-for-broadcast-idle) tag=broadcast;;
  *'wm size 2560x1600') tag=size-set;; *'wm density 320') tag=density-set;;
  *'put system accelerometer_rotation 0') tag=accelerometer-set;;
  *'put system user_rotation 0') tag=rotation-set;;
  *'wm size') tag=size-read; echo "Physical size: $([[ "$MODE" == wrong-size ]] && echo 1920x1080 || echo 2560x1600)";;
  *'wm density') tag=density-read; echo "Physical density: $([[ "$MODE" == wrong-density ]] && echo 321 || echo 320)";;
  *'get system user_rotation') tag=rotation-read; [[ "$MODE" == wrong-rotation ]] && echo 1 || echo 0;;
  *instrument*) tag=instrument;;
  *'deviceidle enabled deep') tag=idle-read; echo 1;;
  *install*) tag=install;; *'pm clear app') tag=clear-app;;
  *'pm grant '*) tag=grant;; *appops*) tag=appops;;
 esac
 op "$tag"
}
sleep() { op sleep; }
mkdir() {
 local tag=mkdir-lane
 [[ "$*" == */results ]] && tag=mkdir-results
 [[ "$*" == */report ]] && tag=mkdir-report
 op "$tag" || return 7
 command mkdir "$@"
}
rm() { op rm; }
cp() {
 local tag=cp-results
 [[ "$1 $2" == *reports* ]] && tag=cp-report
 trace "$tag"
 command cp "$@" || return 7
 [[ "$MODE" != "$tag" ]]
}
tee() {
 local tag=tee-gradle
 [[ "$1" == */reset.log || "$1" == */alarm.log ]] && tag=tee-reset
 command tee "$@"
 op "$tag"
}
'''
            overrides = r'''
sample_host_health() { op health; }
summarize_failed_instrumentation_lane() { op diagnostics; }
verify_instrumentation_provenance() {
 local tag=provenance-before
 if [[ -f "$ROOT/provenance" ]]; then tag=provenance-after; fi
 : > "$ROOT/provenance"; op "$tag"
}
run_gradle_ci() {
 trace "gradle:$*"
 command mkdir -p "$connected_results_dir" "$connected_report_dir"
 [[ "$MODE" == missing-results ]] || echo fresh > "$connected_results_dir/fresh.xml"
 [[ "$MODE" == missing-report ]] || echo fresh > "$connected_report_dir/index.html"
 [[ "$MODE" != missing-results ]] || command rmdir "$connected_results_dir"
 [[ "$MODE" != missing-report ]] || command rmdir "$connected_report_dir"
 [[ "$MODE" != gradle ]]
}
install_discovery_test_apks() { op discovery-install; }
read_deep_idle_enabled() { adb -s "$device_serial" shell dumpsys deviceidle enabled deep; }
resolve_apk_from_metadata() { printf '%s\n' "$ROOT/apk"; }
app_metadata="$ROOT/meta"; test_metadata="$ROOT/meta"
: > "$ROOT/meta"; : > "$ROOT/apk"; deep_idle_initial_state=1
case "$MODE" in
 invalid-results) connected_results_dir='';; outside-results) connected_results_dir="$ROOT/outside";;
 traversal-results) connected_results_dir="$android_root/app/build/../outside";;
 root-results) connected_results_dir="$android_root/app/build";;
 invalid-report) connected_report_dir="$android_root/app/build/reports/wrong";;
 rm) command mkdir -p "$connected_results_dir" "$connected_report_dir"
     echo stale > "$connected_results_dir/old.xml";;
esac
'''
            if action == "loop":
                invocation = 'status=0; functional_shard_count=4; stress_class=S; gaming_board_class=G\n' + loop + '\nexit "$status"'
            elif action == "persistence":
                invocation = 'if ! run_persistence_stage stageA persistence-stage-a; then exit 1; fi'
            elif action == "persistence-chain":
                invocation = 'if ! run_persistence_chain; then exit 1; fi'
            elif action == "alarm":
                invocation = 'if ! ' + alarm_group + '; then exit 1; fi'
            elif action == "alarm-install":
                invocation = 'if ! install_alarm_test_apks; then exit 1; fi'
            else:
                invocation = 'if ! run_connected_instrumentation_lane functional-shard-0 fixed-argument; then exit 1; fi'
            result = subprocess.run(["bash", "-c", '\n'.join((setup, reset, connected, persistence, chain, alarm, overrides, invocation))],
                                    env={**os.environ, "ROOT": str(root), "MODE": mode}, capture_output=True, text=True, timeout=10)
            trace = (root / "trace").read_text().splitlines() if (root / "trace").exists() else []
            files = {str(p.relative_to(root)): p.read_text() for p in (root / "lanes").rglob("*") if p.is_file()}
            self.assertNotIn("unbound variable", result.stderr)
            return result.returncode, trace, files

    def test_required_reset_operations_stop_nested_conditional_parent(self) -> None:
        operations = ("stop-test", "stop-app", "wake", "home", "launcher", "broadcast", "size-set",
                      "density-set", "accelerometer-set", "rotation-set", "size-read", "density-read", "rotation-read")
        for index, mode in enumerate(operations):
            with self.subTest(mode=mode):
                status, trace, files = self.run_lane(mode)
                self.assertEqual(1, status)
                self.assertIn(mode, trace)
                self.assertFalse(any(t.startswith("gradle:") for t in trace))
                self.assertFalse(any(t in trace for t in operations[index + 1:]))
                self.assertFalse(any(p.endswith("fresh.xml") for p in files))

    def test_invalid_clear_paths_and_failed_rm_never_relabel_old_results(self) -> None:
        for mode in ("invalid-results", "outside-results", "traversal-results", "root-results", "invalid-report", "rm"):
            with self.subTest(mode=mode):
                status, trace, files = self.run_lane(mode)
                self.assertEqual(1, status)
                self.assertEqual(mode == "rm", "rm" in trace)
                self.assertNotIn("stop-test", trace)
                self.assertFalse(any(t.startswith("gradle:") for t in trace))
                self.assertFalse(any("/results/" in p for p in files))

    def test_wrong_geometry_and_bounded_launcher_fail_closed(self) -> None:
        for mode in ("wrong-size", "wrong-density", "wrong-rotation", "launcher-exhaust", "sleep"):
            with self.subTest(mode=mode):
                status, trace, _ = self.run_lane(mode)
                self.assertEqual(1, status)
                self.assertFalse(any(t.startswith("gradle:") for t in trace))
                if mode == "launcher-exhaust":
                    self.assertEqual(40, trace.count("launcher"))
                    self.assertEqual(40, trace.count("sleep"))
        for mode in ("ok", "launcher-transient", "collapse", "health"):
            with self.subTest(mode=mode):
                status, trace, files = self.run_lane(mode)
                self.assertEqual(0, status)
                self.assertEqual(1, sum(t.startswith("gradle:") for t in trace))
                self.assertIn("lanes/functional-shard-0/results/fresh.xml", files)

    def test_parent_and_preservation_failures_are_sticky_but_salvage_is_independent(self) -> None:
        for mode in ("mkdir-lane", "provenance-before", "tee-reset", "gradle", "tee-gradle", "provenance-after",
                     "mkdir-results", "mkdir-report", "cp-results", "cp-report", "missing-results", "missing-report"):
            with self.subTest(mode=mode):
                status, trace, files = self.run_lane(mode)
                self.assertEqual(1, status)
                if mode in ("mkdir-lane", "provenance-before", "tee-reset"):
                    self.assertFalse(any(t.startswith("gradle:") for t in trace))
                else:
                    self.assertTrue(any(t.startswith("gradle:") for t in trace))
                if mode in ("mkdir-results", "cp-results", "missing-results"):
                    self.assertIn("cp-report", trace)
                    self.assertIn("lanes/functional-shard-0/report/index.html", files)
                if mode in ("mkdir-report", "cp-report", "missing-report"):
                    self.assertIn("cp-results", trace)
                    self.assertIn("lanes/functional-shard-0/results/fresh.xml", files)

    def test_actual_functional_loop_stops_before_next_lane(self) -> None:
        status, trace, _ = self.run_lane("stop-test", action="loop")
        self.assertEqual(1, status)
        self.assertEqual(1, trace.count("stop-test"))
        self.assertFalse(any(t.startswith("gradle:") for t in trace))
        status, trace, _ = self.run_lane(action="loop")
        self.assertEqual(0, status)
        calls = [t for t in trace if t.startswith("gradle:")]
        self.assertEqual(4, len(calls))
        for index, call in enumerate(calls):
            self.assertIn(f"shardIndex={index}", call)
            self.assertIn("numShards=4", call)

    def test_persistence_and_alarm_conditional_parents_stop_after_reset_failure(self) -> None:
        for action in ("persistence", "persistence-chain", "alarm"):
            for mode in ("stop-test", "tee-reset"):
                with self.subTest(action=action, mode=mode):
                    status, trace, files = self.run_lane(mode, action=action)
                    self.assertEqual(1, status)
                    self.assertNotIn("instrument", trace)
                    if mode == "stop-test" or action != "alarm":
                        self.assertNotIn("install", trace)
                    if action == "persistence-chain":
                        self.assertEqual(1, trace.count("discovery-install"))
                        self.assertFalse(any("persistence-stage-b" in p for p in files))
        status, trace, _ = self.run_lane("mkdir-lane", action="persistence")
        self.assertEqual(1, status)
        self.assertNotIn("stop-test", trace)
        status, trace, _ = self.run_lane("idle-read", action="alarm-install")
        self.assertEqual(1, status)
        self.assertIn("idle-read", trace)


if __name__ == "__main__":
    unittest.main()
