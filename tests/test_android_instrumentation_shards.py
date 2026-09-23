from __future__ import annotations

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


class AndroidInstrumentationShardsTest(unittest.TestCase):
    def test_runner_isolated_stress_and_resets_each_functional_shard(self) -> None:
        source = RUNNER.read_text(encoding="utf-8")

        self.assertIn("functional_shard_count=4", source)
        self.assertIn("android.testInstrumentationRunnerArguments.numShards", source)
        self.assertIn("android.testInstrumentationRunnerArguments.shardIndex", source)
        self.assertIn("android.testInstrumentationRunnerArguments.notClass=${stress_class}", source)
        self.assertIn("'physical-frame-stress'", source)
        self.assertIn("android.testInstrumentationRunnerArguments.class=${stress_class}", source)
        self.assertLess(source.index("functional-shard-${shard_index}"), source.index("'physical-frame-stress'"))
        self.assertIn("KEYCODE_WAKEUP", source)
        self.assertIn("KEYCODE_HOME", source)
        self.assertIn("wait-for-broadcast-idle", source)
        self.assertIn("preserve_instrumentation_lane", source)
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
            expected = [*(f"example.FunctionalTest#case{index}" for index in range(4)), f"{stress_class}#frame"]
            inventory = root / "tests.txt"
            inventory.write_text("\n".join(expected) + "\n", encoding="utf-8")
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
                "--expected-skip", "example.FunctionalTest#case3",
                "--summary", str(summary),
            ]

            result = subprocess.run(command, text=True, capture_output=True, check=False)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn("executed=5", summary.read_text(encoding="utf-8"))
            duplicate_xml = evidence / "functional-shard-0" / "results" / "TEST.xml"
            duplicate_xml.write_text(
                junit_xml([("example.FunctionalTest", "case0", False), ("example.FunctionalTest", "case1", False)]),
                encoding="utf-8",
            )
            result = subprocess.run(command, text=True, capture_output=True, check=False)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("more than once", result.stderr)


if __name__ == "__main__":
    unittest.main()
