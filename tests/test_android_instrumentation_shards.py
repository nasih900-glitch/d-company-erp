from __future__ import annotations

import json
import os
import subprocess
import tempfile
import unittest
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


if __name__ == "__main__":
    unittest.main()
