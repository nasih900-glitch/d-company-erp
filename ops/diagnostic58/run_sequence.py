"""Compare both SDKs on one host; two workflow arms reverse their order."""
from pathlib import Path
import argparse
import json
import os
import subprocess
import sys


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sequence", choices=("old-new", "new-old"), required=True)
    parser.add_argument("--source", required=True)
    parser.add_argument("--pair", required=True)
    args = parser.parse_args()
    root = Path(os.environ["RUNNER_TEMP"]).resolve() / "emulator-observations"
    root.mkdir(mode=0o700, exist_ok=False)
    order = ("36.5.10", "37.2.13") if args.sequence == "old-new" else ("37.2.13", "36.5.10")
    reports = {}
    for offset, version in enumerate(order):
        completed = subprocess.run([sys.executable, str(Path(__file__).with_name("emulator_compare.py")), "--source", args.source, "--pair", args.pair, "--version", version, "--port", str(5564+offset*10), "--output", str(root / version)], check=False)
        receipt = root / version / "RESULT.json"
        reports[version] = {"harness_exit": completed.returncode, "result": json.loads(receipt.read_text()) if receipt.is_file() else {"status": "NO_COMPLETED_RECEIPT"}}
        if not reports[version]["result"].get("cleanup_owned_process_exited"):
            for remaining in order:
                reports.setdefault(remaining, {"harness_exit": None, "result": {"status": "NOT_RUN_AFTER_UNVERIFIED_OWNED_CLEANUP"}})
            break
    left, right = (reports[version]["result"] for version in ("36.5.10", "37.2.13"))
    controls = {key: left.get(key) is not None and left.get(key) == right.get(key) for key in ("host", "system_image_manifest_sha256", "guest_build_fingerprint", "source")}
    distinct_ports = [reports[version]["result"].get("console_port") for version in order] == [5564, 5574]
    summary = {"distinct_console_ports_verified": distinct_ports, "status": "DIAGNOSTIC_ONLY_NOT_RELEASE_ACCEPTANCE", "order": list(order), "same_host_pair_controls": controls, "versions": reports, "limitations": ["Order is balanced across two hosts; only each within-host pair is a direct SDK comparison", "Rebuilt loopback debug pair; raw AndroidJUnitRunner differs from tagged UTP", "Version dependence does not establish the named upstream graphics defect"]}
    (root / "SEQUENCE.json").write_text(json.dumps(summary, indent=2) + "\n")
    return 0 if distinct_ports and all(controls.values()) and all(item["harness_exit"] == 0 for item in reports.values()) else 1


if __name__ == "__main__":
    raise SystemExit(main())
