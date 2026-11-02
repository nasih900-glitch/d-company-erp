"""Own one disposable CI emulator and retain bounded, non-secret host evidence."""
from __future__ import annotations

from pathlib import Path, PurePosixPath
import argparse
import hashlib
import importlib.util
import json
import os
import platform
import re
import signal
import socket
import stat
import subprocess
import threading
import time
import urllib.request
import uuid
import zipfile

COMMIT = "6eb61bf097afb7d6e306f3878a64f266adf451a1"
PINNED_PAIR_MANIFEST_SHA256 = "7f789501e39a15116c9384782e1920083e523f933e3ed065835e7d113de87fd6"
CLASS = "cloud.dcompany.erp.ui.screens.gaming.GamingBoardUiTest"
RUNNER = "cloud.dcompany.erp.test/androidx.test.runner.AndroidJUnitRunner"
PINS = {
    "36.5.10": ("15081367", 331543272, "ffb7c18282efad1702820c23d5e1fec1127671cceac2589ed03f6c7a94187690"),
    "37.2.13": ("16740729", 349658551, "cbc7c3cfb83220b8a0f9c513fdab557712e74d339ec19ddffa43f23350ae9bdd"),
}


def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def validate_archive(path: Path) -> None:
    with zipfile.ZipFile(path) as archive:
        for entry in archive.infolist():
            parts = PurePosixPath(entry.filename).parts
            assert parts and parts[0] == "emulator" and ".." not in parts
            assert not entry.filename.startswith("/")
            if stat.S_ISLNK(entry.external_attr >> 16):
                target = archive.read(entry).decode()
                assert not target.startswith("/")
                normalized = os.path.normpath(str(PurePosixPath(entry.filename).parent / target))
                assert normalized.startswith("emulator/") and "\x00" not in target


def parse_round(path: Path, expected: set[str]) -> dict:
    fields: dict[str, str] = {}
    starts: list[str] = []
    passed: list[str] = []
    failed: list[dict] = []
    terminal: list[int] = []
    totals: set[int] = set()
    ok_counts: list[int] = []
    errors: list[str] = []
    active = None
    for raw in path.read_text(errors="replace").splitlines():
        line = raw.strip()
        if any(marker in line for marker in ("INSTRUMENTATION_FAILED", "INSTRUMENTATION_ABORTED", "Process crashed", "shortMsg=")):
            errors.append("failure_or_abort_marker")
        if line.startswith("INSTRUMENTATION_STATUS: ") and "=" in line:
            if terminal:
                errors.append("status_after_terminal")
            key, value = line.removeprefix("INSTRUMENTATION_STATUS: ").split("=", 1)
            fields[key] = value
            if key == "numtests":
                totals.add(int(value))
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            if terminal:
                errors.append("status_code_after_terminal")
            code = int(line.split(":", 1)[1])
            identifier = fields.get("class", "") + "#" + fields.get("test", "")
            if code == 1:
                if active is not None or identifier not in expected or identifier in starts:
                    errors.append("invalid_test_start")
                active = identifier
                starts.append(identifier)
            elif code <= 0:
                if active != identifier or identifier not in expected:
                    errors.append("completion_without_matching_start")
                if code == 0:
                    passed.append(identifier)
                else:
                    failed.append({"test": identifier, "code": code})
                active = None
            elif code != 2:
                errors.append("unknown_auxiliary_status")
            fields = {}
        elif line.startswith("INSTRUMENTATION_CODE: "):
            if fields:
                errors.append("unfinished_status_bundle_before_terminal")
            if active is not None or set(passed) != expected:
                errors.append("terminal_before_all_completions")
            terminal.append(int(line.split(":", 1)[1]))
        elif match := re.fullmatch(r"OK \(([0-9]+) tests?\)", line):
            if terminal or active is not None or set(passed) != expected:
                errors.append("misordered_ok_summary")
            ok_counts.append(int(match.group(1)))
    success = not errors and not failed and active is None and terminal == [-1] and totals == {len(expected)} and ok_counts == [len(expected)] and len(starts) == len(set(starts)) == len(expected) and set(starts) == expected and len(passed) == len(set(passed)) == len(expected) and set(passed) == expected
    return {"pass": success, "started": starts, "passed": passed, "failed": failed, "terminal_codes": terminal, "declared_totals": sorted(totals), "ok_counts": ok_counts, "protocol_errors": errors[:128]}


class BoundedDrain:
    """Drain a child pipe after the evidence cap so logging cannot block it."""
    def __init__(self, pipe, path: Path, limit: int) -> None:
        self.pipe, self.path, self.limit = pipe, path, limit
        self.observed_bytes = 0
        self.retained_bytes = 0
        self.error_type = None
        self.thread = threading.Thread(target=self.run, daemon=True)

    def run(self) -> None:
        try:
            with self.path.open("xb") as stream:
                while block := self.pipe.read(65536):
                    self.observed_bytes += len(block)
                    keep = block[:max(0, self.limit-self.retained_bytes)]
                    if keep and self.error_type is None:
                        try:
                            stream.write(keep)
                            self.retained_bytes += len(keep)
                        except OSError as exc:
                            self.error_type = type(exc).__name__
        except Exception as exc:
            self.error_type = type(exc).__name__
            # Keep draining even when the output file cannot be opened.
            try:
                while block := self.pipe.read(65536):
                    self.observed_bytes += len(block)
            except Exception:
                pass

    def summary(self) -> dict:
        return {"observed_bytes": self.observed_bytes, "retained_bytes": self.retained_bytes, "truncated": self.observed_bytes > self.retained_bytes, "error_type": self.error_type, "drain_finished": not self.thread.is_alive()}


def process_identity(pid: int) -> dict:
    root = Path("/proc") / str(pid)
    fields = (root / "stat").read_text().rsplit(")", 1)[1].split()
    return {"pid": pid, "start_ticks": int(fields[19]), "executable": os.readlink(root / "exe"), "uid": root.stat().st_uid}


def process_cgroup(pid: int) -> Path | None:
    for row in (Path("/proc") / str(pid) / "cgroup").read_text().splitlines():
        hierarchy, controllers, relative = row.split(":", 2)
        if hierarchy == "0" and not controllers:
            candidate = (Path("/sys/fs/cgroup") / relative.lstrip("/")).resolve()
            assert candidate.is_relative_to(Path("/sys/fs/cgroup"))
            return candidate
    return None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--pair", type=Path, required=True)
    parser.add_argument("--version", choices=PINS, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--port", type=int, choices=(5564, 5574), required=True)
    args = parser.parse_args()
    assert platform.system() == "Linux" and os.environ.get("GITHUB_ACTIONS") == "true"
    def arm_deadline(_signal, _frame):
        raise TimeoutError("Diagnostic arm exceeded its 25-minute deadline")
    signal.signal(signal.SIGALRM, arm_deadline)
    signal.alarm(1500)
    temporary = Path(os.environ["RUNNER_TEMP"]).resolve()
    output = args.output.resolve()
    assert output.is_relative_to(temporary) and not output.exists()
    output.mkdir(mode=0o700)
    work = temporary / ("dcompany-emulator-experiment-" + uuid.uuid4().hex)
    work.mkdir(mode=0o700)
    source, pair = args.source.resolve(), args.pair.resolve()
    assert subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=source, text=True).strip() == COMMIT
    assert subprocess.check_output(["git", "status", "--porcelain", "--untracked-files=normal"], cwd=source, text=True).strip() == ""
    assert digest(pair / "PAIR.json") == PINNED_PAIR_MANIFEST_SHA256
    receipt = json.loads((pair / "PAIR.json").read_text())
    assert receipt["source"] == COMMIT and set(receipt["files"]) == {"app.apk", "test.apk"}
    assert receipt["debug_api_base_url_override"] == "http://127.0.0.1:9/api/v1/"
    for name, item in receipt["files"].items():
        candidate = pair / name
        assert candidate.is_file() and not candidate.is_symlink()
        assert candidate.stat().st_size == item["bytes"] and digest(candidate) == item["sha256"]
    (output / "PAIR.json").write_bytes((pair / "PAIR.json").read_bytes())
    build, size, checksum = PINS[args.version]
    archive = work / "emulator.zip"
    url = f"https://dl.google.com/android/repository/emulator-linux_x64-{build}.zip"
    download_deadline = time.monotonic() + 180
    with urllib.request.urlopen(url, timeout=30) as response, archive.open("xb") as stream:
        total = 0
        while block := response.read(1024 * 1024):
            total += len(block)
            assert total <= size and time.monotonic() < download_deadline
            stream.write(block)
    assert total == size and digest(archive) == checksum
    validate_archive(archive)
    sdk_tools = work / "binary"
    sdk_tools.mkdir()
    subprocess.run(["unzip", "-q", str(archive), "-d", str(sdk_tools)], check=True, timeout=60)
    binary = sdk_tools / "emulator/emulator"
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"]).resolve()
    adb = sdk / "platform-tools/adb"
    avd_root = work / "avd"
    avd_root.mkdir()
    env = {key: os.environ[key] for key in ("HOME", "PATH", "LANG") if key in os.environ}
    env.update(ANDROID_HOME=str(sdk), ANDROID_SDK_ROOT=str(sdk), ANDROID_AVD_HOME=str(avd_root), TMPDIR=str(work))
    serial = f"emulator-{args.port}"
    try:
        for port in (args.port, args.port + 1):
            with socket.socket() as check:
                check.bind(("127.0.0.1", port))
    except OSError as exc:
        try:
            socket_probe = subprocess.run(["ss", "-Htanp", f"sport = :{args.port} or sport = :{args.port+1}"], env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=5, check=False)
            (output / "port-state.txt").write_bytes(socket_probe.stdout[:32768])
        except (OSError, subprocess.SubprocessError) as probe_error:
            (output / "port-state.txt").write_text("Socket-state observation unavailable: " + type(probe_error).__name__)
        (output / "RESULT.json").write_text(json.dumps({"status": "PORT_PREFLIGHT_FAILED_NO_GUEST_STARTED", "source": COMMIT, "version": args.version, "console_port": args.port, "errno": exc.errno, "cleanup_owned_process_exited": True, "no_emulator_guest_was_launched": True}, indent=2) + "\n")
        signal.alarm(0)
        return 1
    avd_name = "dcompany_diag_" + uuid.uuid4().hex[:12]
    avdmanager = sdk / "cmdline-tools/latest/bin/avdmanager"
    subprocess.run([str(avdmanager), "create", "avd", "-n", avd_name, "-k", "system-images;android-35;default;x86_64", "-d", "pixel_c"], input="no\n", text=True, env=env, check=True, timeout=90)
    subprocess.run(["python3", str(source / "scripts/configure_ci_android_avd.py"), avd_name], env=env, check=True, timeout=15)
    config = avd_root / f"{avd_name}.avd/config.ini"
    rows = config.read_text().splitlines()
    values = {"hw.cpu.ncore": "2", "hw.keyboard": "no"}
    config.write_text("\n".join([line for line in rows if line.split("=", 1)[0].strip() not in values] + [f"{key}={value}" for key, value in values.items()]) + "\n")
    (output / "avd-config.ini").write_bytes(config.read_bytes())
    for label, executable in (("launcher", binary), ("qemu", binary.parent / "qemu/linux-x86_64/qemu-system-x86_64")):
        try:
            linkage = subprocess.run(["ldd", str(executable)], env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=15, check=False)
            (output / f"emulator-{label}-linkage.txt").write_bytes(linkage.stdout[:65536])
        except (OSError, subprocess.SubprocessError) as exc:
            (output / f"emulator-{label}-linkage.txt").write_text("Linkage observation unavailable: " + type(exc).__name__)
    try:
        version_probe = subprocess.run([str(binary), "-version"], env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=15, check=False)
        version_output, version_exit = version_probe.stdout, version_probe.returncode
        version_error = None
    except (OSError, subprocess.SubprocessError) as exc:
        version_output = getattr(exc, "output", None) or b""
        version_exit = None
        version_error = type(exc).__name__
    (output / "emulator-version.txt").write_bytes(version_output[:65536])
    if version_exit != 0:
        # No -avd command has been issued; subprocess.run reaps its version probe.
        preflight = {"status": "SDK_VERSION_PREFLIGHT_FAILED_NO_GUEST_STARTED", "source": COMMIT, "version": args.version, "build": build, "archive_sha256": checksum, "console_port": args.port, "version_exit": version_exit, "version_error_type": version_error, "version_output_truncated": len(version_output) > 65536, "cleanup_owned_process_exited": True, "no_emulator_guest_was_launched": True, "pair_manifest_sha256": PINNED_PAIR_MANIFEST_SHA256}
        (output / "RESULT.json").write_text(json.dumps(preflight, indent=2) + "\n")
        signal.alarm(0)
        return 1
    result = {"source": COMMIT, "version": args.version, "build": build, "archive_sha256": checksum, "archive_bytes": size, "primary_archive_url": url, "status": "RUNNING", "console_port": args.port, "rounds": [], "limitations": ["Rebuilt debug bytes, not retained bytes from failed release run", "Raw AndroidJUnitRunner; release uses UTP", "Untraced host process; no physical-device or performance admission"]}
    process = None
    logcat = None
    drains = {}
    lifecycle = []
    clients = []
    stop = threading.Event()

    def shell(*command: str, timeout: int = 30) -> str:
        return subprocess.check_output([str(adb), "-s", serial, *command], env=env, stderr=subprocess.STDOUT, timeout=timeout).decode(errors="replace")

    def observe_resources() -> None:
        with (output / "host-observations.jsonl").open("x") as stream:
            while not stop.is_set():
                sample: dict = {"monotonic": time.monotonic(), "epoch": time.time(), "popen_pid": process.pid, "returncode": process.poll()}
                owned_alive = False
                if sample["returncode"] is None:
                    try:
                        current = process_identity(process.pid)
                        owned_alive = current["start_ticks"] == lifecycle[0]["identity"]["start_ticks"] and current["uid"] == os.getuid() and Path(current["executable"]).is_relative_to(sdk_tools.resolve())
                        sample["identity_verified"] = owned_alive
                    except (OSError, ValueError, IndexError) as exc:
                        sample["identity_unavailable"] = type(exc).__name__
                for filename in (("status", "stat") if owned_alive else ()):
                    try:
                        data = (Path("/proc") / str(process.pid) / filename).read_text()
                        if filename == "status":
                            sample["process"] = {line.split(":", 1)[0]: line.split(":", 1)[1].strip() for line in data.splitlines() if line.split(":", 1)[0] in {"Name", "State", "Threads", "VmRSS", "VmHWM", "VmSize", "RssAnon", "RssFile", "RssShmem"}}
                        else:
                            sample["start_ticks"] = int(data.rsplit(")", 1)[1].split()[19])
                    except (OSError, ValueError, IndexError) as exc:
                        sample[filename + "_unavailable"] = type(exc).__name__
                sample["memory"] = {line.split(":", 1)[0]: line.split(":", 1)[1].strip() for line in Path("/proc/meminfo").read_text().splitlines() if line.split(":", 1)[0] in {"MemTotal", "MemAvailable", "SwapTotal", "SwapFree"}}
                try:
                    group = process_cgroup(process.pid) if owned_alive else None
                    sample["cgroup_path"] = None if group is None else str(group)
                    for name in ("memory.events", "memory.current", "memory.peak", "memory.max"):
                        try:
                            sample[name] = None if group is None else (group / name).read_text()[:4096]
                        except OSError as exc:
                            sample[name] = type(exc).__name__
                except OSError as exc:
                    sample["cgroup_unavailable"] = type(exc).__name__
                if stream.tell() < 32 * 1024 * 1024:
                    stream.write(json.dumps(sample) + "\n")
                    stream.flush()
                else:
                    result["host_observations_truncated"] = True
                stop.wait(0.25)

    def observe() -> None:
        try:
            observe_resources()
        except BaseException as exc:
            result["host_observer_error_type"] = type(exc).__name__

    try:
        result["host"] = {"system": platform.system(), "kernel": platform.release(), "machine": platform.machine(), "cpu_models": sorted({line.split(":", 1)[1].strip() for line in Path("/proc/cpuinfo").read_text().splitlines() if line.startswith("model name")}), "runner_image": {key: os.environ.get(key) for key in ("RUNNER_OS", "RUNNER_ARCH", "ImageOS", "ImageVersion")}}
        image_root = sdk / "system-images/android-35/default/x86_64"
        image_files = []
        for item in sorted(image_root.rglob("*")):
            assert not item.is_symlink(), "System-image closure cannot silently omit symlinks"
            if item.is_file() and not item.is_symlink() and item.name != "package.xml":
                image_files.append({"path": item.relative_to(image_root).as_posix(), "bytes": item.stat().st_size, "sha256": digest(item)})
        assert image_files and any(item["path"] == "system.img" for item in image_files)
        result["system_image_manifest_sha256"] = hashlib.sha256(json.dumps(image_files, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
        (output / "system-image-files.json").write_text(json.dumps(image_files, indent=2) + "\n")
        process = subprocess.Popen([str(binary), "-avd", avd_name, "-port", str(args.port), "-no-window", "-gpu", "swiftshader", "-feature", "-Vulkan", "-no-snapshot", "-noaudio", "-no-boot-anim", "-cores", "2"], env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, start_new_session=True)
        lifecycle.append({"event": "owned_launcher_started", "monotonic": time.monotonic(), "epoch": time.time(), "identity": process_identity(process.pid)})
        drains["emulator"] = BoundedDrain(process.stdout, output / "emulator.log", 32 * 1024 * 1024)
        drains["emulator"].thread.start()
        observer = threading.Thread(target=observe, daemon=True)
        observer.start()
        deadline = time.monotonic() + 300
        while time.monotonic() < deadline:
            assert process.poll() is None, "Owned emulator exited before boot completed"
            try:
                if shell("shell", "getprop", "sys.boot_completed", timeout=5).strip() == "1":
                    break
            except (subprocess.SubprocessError, OSError):
                pass
            time.sleep(2)
        else:
            raise TimeoutError("Owned emulator did not boot within 300 seconds")
        cmdline = (Path("/proc") / str(process.pid) / "cmdline").read_bytes().split(b"\0")
        result["parent_pid_is_qemu"] = b"qemu-system" in Path(os.fsdecode(cmdline[0])).name.encode()
        assert result["parent_pid_is_qemu"], "Cannot assign emulator exit status without verified launcher exec"
        qemu_identity = process_identity(process.pid)
        assert qemu_identity["start_ticks"] == lifecycle[0]["identity"]["start_ticks"] and qemu_identity["uid"] == os.getuid()
        assert Path(qemu_identity["executable"]).is_relative_to(sdk_tools.resolve())
        result["qemu_identity"] = qemu_identity
        lifecycle.append({"event": "qemu_exec_verified", "monotonic": time.monotonic(), "epoch": time.time(), "identity": qemu_identity})
        assert shell("shell", "getprop", "ro.kernel.qemu").strip() == "1"
        assert shell("shell", "cat", "/sys/devices/system/cpu/online").strip() == "0-1"
        assert "Physical size: 2560x1600" in shell("shell", "wm", "size")
        assert "Physical density: 320" in shell("shell", "wm", "density")
        shell("shell", "cmd", "overlay", "enable-exclusive", "--category", "com.android.internal.systemui.navbar.gestural")
        active = [line.strip() for line in shell("shell", "cmd", "overlay", "list", "--user", "0").splitlines() if line.strip().startswith("[x] com.android.internal.systemui.navbar.")]
        assert active == ["[x] com.android.internal.systemui.navbar.gestural"]
        for setting in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
            shell("shell", "settings", "put", "global", setting, "0")
        shell("shell", "settings", "put", "system", "accelerometer_rotation", "0")
        shell("shell", "settings", "put", "system", "user_rotation", "0")
        shell("shell", "input", "keyevent", "82")
        shell("shell", "svc", "wifi", "disable")
        shell("shell", "svc", "data", "disable")
        flinger = shell("shell", "dumpsys", "SurfaceFlinger")
        (output / "surface-flinger.txt").write_text(flinger[:2 * 1024 * 1024])
        renderer = [line.strip() for line in flinger.splitlines() if "GLES:" in line]
        assert len(renderer) == 1 and "swiftshader" in renderer[0].lower()
        result["actual_renderer"] = renderer[0]
        result["requested_graphics"] = "-gpu swiftshader -feature -Vulkan"
        result["vulkan_validation_limit"] = "Requested disable is recorded; no claim of proving every driver-internal Vulkan call"
        result["guest_build_fingerprint"] = shell("shell", "getprop", "ro.build.fingerprint").strip()
        (output / "guest-build-fingerprint.txt").write_text(result["guest_build_fingerprint"])
        for name in ("app.apk", "test.apk"):
            shell("install", "-r", "-t", str(pair / name), timeout=120)
        logcat = subprocess.Popen([str(adb), "-s", serial, "logcat", "-v", "threadtime"], env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        drains["logcat"] = BoundedDrain(logcat.stdout, output / "guest-logcat.txt", 64 * 1024 * 1024)
        drains["logcat"].thread.start()
        discovery = output / "discovery.log"
        discovery.write_text(shell("shell", "am", "instrument", "-w", "-r", "-e", "log", "true", "-e", "class", CLASS, RUNNER, timeout=90))
        spec = importlib.util.spec_from_file_location("frozen_shard_parser", source / "scripts/verify_android_instrumentation_shards.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        expected = set(module.parse_discovery_log(discovery))
        assert len(expected) == 34 and all(identifier.startswith(CLASS + "#") for identifier in expected)
        result["expected_tests"] = sorted(expected)
        for index in range(1, 2):
            for package in ("cloud.dcompany.erp", "cloud.dcompany.erp.test"):
                shell("shell", "am", "force-stop", package)
                assert shell("shell", "pm", "clear", package).strip() == "Success"
            path = output / f"round-{index}.log"
            client = subprocess.Popen([str(adb), "-s", serial, "shell", "am", "instrument", "-w", "-r", "-e", "class", CLASS, RUNNER], env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            clients.append(client)
            drain = BoundedDrain(client.stdout, path, 32 * 1024 * 1024)
            drains[f"round-{index}"] = drain
            drain.thread.start()
            try:
                client.wait(timeout=600)
            finally:
                if client.poll() is None:
                    client.terminate()
                    try:
                        client.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        client.kill()
                        client.wait(timeout=5)
                drain.thread.join(timeout=5)
            assert not drain.summary()["truncated"] and drain.summary()["drain_finished"] and drain.error_type is None
            report = parse_round(path, expected)
            report.update(index=index, adb_exit_code=client.returncode, emulator_returncode=process.poll())
            result["rounds"].append(report)
            (output / "RESULT.json").write_text(json.dumps(result, indent=2) + "\n")
            if not report["pass"] or client.returncode or process.poll() is not None:
                raise AssertionError("Incomplete or failed diagnostic round; retained without retries")
        result["status"] = "COMPLETE_DIAGNOSTIC_ROUND_PASS_NOT_RELEASE_ACCEPTANCE"
    except Exception as exc:
        result["status"] = "FAIL_RETAINED"
        result["failure_type"] = type(exc).__name__
        result["failure_message"] = str(exc)[:2000]
    finally:
        # Allow the separately bounded owned cleanup to finish after an arm timeout.
        signal.alarm(0)
        lifecycle.append({"event": "expected_owned_cleanup_begins", "monotonic": time.monotonic(), "epoch": time.time(), "emulator_returncode": None if process is None else process.poll()})
        result["returncode_before_owned_cleanup"] = lifecycle[-1]["emulator_returncode"]
        result["exit_attribution_limit"] = "An exit observed only after expected cleanup began is not attributed to a spontaneous emulator crash"
        cleanup_errors = []
        def attempt(action, operation):
            try:
                return operation()
            except BaseException as exc:
                cleanup_errors.append({"action": action, "error_type": type(exc).__name__})
                return None

        for index, client in enumerate(clients):
            if client.poll() is None:
                attempt(f"instrumentation-{index}-terminate", client.terminate)
                attempt(f"instrumentation-{index}-wait", lambda: client.wait(timeout=5))
            if client.poll() is None:
                attempt(f"instrumentation-{index}-kill", client.kill)
                attempt(f"instrumentation-{index}-final-wait", lambda: client.wait(timeout=5))
        if logcat is not None:
            if logcat.poll() is None:
                attempt("logcat_terminate", logcat.terminate)
                attempt("logcat_wait", lambda: logcat.wait(timeout=5))
            if logcat.poll() is None:
                attempt("logcat_kill", logcat.kill)
                attempt("logcat_final_wait", lambda: logcat.wait(timeout=5))
        if process is not None:
            if process.poll() is None:
                lifecycle.append({"event": "owned_emu_kill_requested", "monotonic": time.monotonic(), "epoch": time.time()})
                attempt("owned_emu_kill", lambda: shell("emu", "kill", timeout=5))
                attempt("owned_emulator_wait", lambda: process.wait(timeout=15))
            if process.poll() is None:
                def signal_owned_group(which):
                    current = process_identity(process.pid)
                    assert lifecycle and current["start_ticks"] == lifecycle[0]["identity"]["start_ticks"] and current["uid"] == os.getuid()
                    assert Path(current["executable"]).is_relative_to(sdk_tools.resolve()) and os.getpgid(process.pid) == process.pid
                    lifecycle.append({"event": "owned_process_group_" + signal.Signals(which).name, "monotonic": time.monotonic(), "epoch": time.time()})
                    os.killpg(process.pid, which)
                attempt("owned_sigterm", lambda: signal_owned_group(signal.SIGTERM))
                attempt("owned_sigterm_wait", lambda: process.wait(timeout=10))
                if process.poll() is None:
                    attempt("owned_sigkill", lambda: signal_owned_group(signal.SIGKILL))
                    attempt("owned_final_wait", lambda: process.wait(timeout=10))
            result["owned_process_returncode_after_cleanup"] = process.poll()
        stop.set()
        if "observer" in locals():
            attempt("observer_join", lambda: observer.join(timeout=5))
            result["observer_finished"] = not observer.is_alive()
        for name, drain in drains.items():
            attempt(name + "_drain_join", lambda: drain.thread.join(timeout=5))
        result["log_collectors"] = {name: drain.summary() for name, drain in drains.items()}
        result["lifecycle"] = lifecycle
        result["cleanup_errors"] = cleanup_errors
        try:
            kernel = subprocess.check_output(["sudo", "-n", "dmesg", "--time-format", "raw"], stderr=subprocess.STDOUT, timeout=5).decode(errors="replace")
            boot_monotonic = lifecycle[0]["monotonic"] if lifecycle else time.monotonic()
            selected = []
            cleanup_start = lifecycle[-1]["monotonic"] if lifecycle else time.monotonic()
            cleanup_events = [event for event in lifecycle if event["event"] == "expected_owned_cleanup_begins"]
            if cleanup_events:
                cleanup_start = cleanup_events[0]["monotonic"]
            for line in kernel.splitlines():
                stamp = re.match(r"^\[\s*([0-9.]+)\]", line)
                if stamp and float(stamp.group(1)) >= boot_monotonic and any(token in line.lower() for token in ("out of memory", "oom-kill", "killed process", "qemu", "emulator", "segfault")):
                    selected.append({"line": line[:2000], "monotonic": float(stamp.group(1)), "owned_pid_numeric_candidate": process is not None and re.search(rf"(?<![0-9]){process.pid}(?![0-9])", line) is not None, "before_expected_cleanup": float(stamp.group(1)) < cleanup_start})
            result["kernel_attribution"] = "Monotonic launch window filtered. A matching number can be a memory metric; numeric candidates do not establish process attribution without manual semantic review."
            (output / "kernel-selected.json").write_text(json.dumps(selected[-100:], indent=2) + "\n")
        except (subprocess.SubprocessError, OSError) as exc:
            result["kernel_observation_unavailable"] = type(exc).__name__
        result["cleanup_owned_process_exited"] = (process is None or process.poll() is not None) and (logcat is None or logcat.poll() is not None) and all(client.poll() is not None for client in clients)
        if result["status"].startswith("COMPLETE_DIAGNOSTIC") and (result.get("host_observer_error_type") or result.get("host_observations_truncated") or not result.get("observer_finished") or any(value["truncated"] or value["error_type"] or not value["drain_finished"] for value in result.get("log_collectors", {}).values())):
            result["status"] = "DIAGNOSTIC_CAPTURE_INCOMPLETE_REVIEW_REQUIRED"
        (output / "RESULT.json").write_text(json.dumps(result, indent=2) + "\n")
    return 0 if result["status"].startswith("COMPLETE_DIAGNOSTIC") and result["cleanup_owned_process_exited"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
