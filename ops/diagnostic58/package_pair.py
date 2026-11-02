"""Freeze one independently identifiable debug APK pair for both experiment arms."""
from pathlib import Path
import hashlib
import json
import shutil
import subprocess
import sys

COMMIT = "6eb61bf097afb7d6e306f3878a64f266adf451a1"
ARCHIVE_SHA = "94f71f7cabc78b1ff4baa26ee42923831c071a99346525d176bba4d95661f290"


def main() -> None:
    source, output = map(lambda x: Path(x).resolve(), sys.argv[1:])
    assert subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=source, text=True).strip() == COMMIT
    assert subprocess.check_output(["git", "status", "--porcelain", "--untracked-files=normal"], cwd=source, text=True).strip() == ""
    archive = subprocess.check_output(["git", "archive", "--format=tar", "HEAD"], cwd=source)
    assert hashlib.sha256(archive).hexdigest() == ARCHIVE_SHA
    generated = source / "android-native/app/build/generated/source/buildConfig/debug/cloud/dcompany/erp/BuildConfig.java"
    build_config = generated.read_text()
    assert 'public static final String API_BASE_URL = "http://127.0.0.1:9/api/v1/";' in build_config
    output.mkdir(mode=0o700, exist_ok=False)
    files = {}
    for name, directory in (
        ("app.apk", "android-native/app/build/outputs/apk/debug"),
        ("test.apk", "android-native/app/build/outputs/apk/androidTest/debug"),
    ):
        root = source / directory
        metadata = json.loads((root / "output-metadata.json").read_text())
        assert len(metadata["elements"]) == 1
        entry = metadata["elements"][0]
        filename = entry["outputFile"]
        assert Path(filename).name == filename and filename.endswith(".apk")
        candidate = root / filename
        assert candidate.is_file() and not candidate.is_symlink()
        destination = output / name
        shutil.copyfile(candidate, destination)
        files[name] = {"sha256": hashlib.sha256(destination.read_bytes()).hexdigest(), "bytes": destination.stat().st_size}
    (output / "PAIR.json").write_text(json.dumps({
        "source": COMMIT, "source_archive_sha256": ARCHIVE_SHA,
        "kind": "rebuilt_debug_experimental_pair_not_failed_run_apk_bytes",
        "debug_api_base_url_override": "http://127.0.0.1:9/api/v1/",
        "generated_build_config_sha256": hashlib.sha256(generated.read_bytes()).hexdigest(),
        "files": files,
    }, indent=2) + "\n")


if __name__ == "__main__":
    main()
