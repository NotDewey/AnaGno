"""Guarded, stdlib-only direct-detector capture for isolated supplied APKs.

Example: python3 tools/bubble_eval/run_android.py --manifest qa/bubble/manifest.json
--policy qa/bubble/acceptance-policy.json --output qa/bubble/runs/device-cold
--serial SERIAL --runtime-kind physical --mode cold_inference --apk APP.apk
--test-apk TEST.apk --install

Each cold attempt has a separate force-stopped instrumentation launch. Warm runs
perform one untimed warmup then reuse one detector serially. Direct detection
cannot measure repository cache hits, UI latency, memory peaks or thermal state.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch
import uuid
import zipfile

try:
    from .report import annotation_digest, canonical_json, sha256_file
    from .validate import validate_manifest, validate_run
except ImportError:
    from report import annotation_digest, canonical_json, sha256_file
    from validate import validate_manifest, validate_run


PACKAGE = "com.comicreader.app.bubblevalidation"
TEST_PACKAGE = PACKAGE + ".test"
COMPONENT = TEST_PACKAGE + "/androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS = "com.comicreader.app.BubbleValidationTest#captureDirectDetector"
ROOT = Path(__file__).resolve().parents[2]
MODEL = ROOT / "app/src/main/assets/models/bubble_segment.onnx"


def local(root: Path, value: str) -> Path:
    if (not isinstance(value, str) or not value or "\\" in value or ":" in value
            or any(part in {"", ".", ".."} for part in value.split("/"))):
        raise ValueError(f"unsafe relative path: {value!r}")
    root = root.resolve()
    result = (root / value).resolve()
    if not result.is_relative_to(root):
        raise ValueError(f"path escapes artifact directory: {value}")
    return result


def atomic_json(path: Path, record: dict) -> None:
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(canonical_json(record), encoding="utf-8")
    temporary.replace(path)


def source_digest(root: Path = ROOT) -> str:
    paths = set()
    for directory in ("tools/bubble_eval", "app/src/androidTest", "gradle/wrapper"):
        paths.update(path for path in (root / directory).rglob("*")
                     if path.is_file() and "__pycache__" not in path.parts)
    paths.update(root / name for name in (
        "app/build.gradle.kts", "build.gradle.kts", "settings.gradle.kts",
        "gradle.properties", "gradlew", "gradlew.bat", "qa/bubble/toolchain.json"))
    digest = hashlib.sha256()
    for path in sorted(paths, key=lambda item: item.relative_to(root).as_posix()):
        name = path.relative_to(root).as_posix().encode("utf-8")
        content = path.read_bytes()
        digest.update(len(name).to_bytes(8, "big") + name)
        digest.update(len(content).to_bytes(8, "big") + content)
    return "sha256:" + digest.hexdigest()


def command(argv: list[str], *, timeout: float = 30, data: bytes | None = None) -> bytes:
    result = subprocess.run(argv, input=data, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, timeout=timeout, check=False)
    if result.returncode:
        raise RuntimeError(f"{argv[0]} exited {result.returncode}: "
                           + result.stderr.decode("utf-8", errors="replace")[-2000:])
    return result.stdout


class Device:
    def __init__(self, adb: str, serial: str, timeout: float):
        if not serial or serial.startswith("-") or any(character.isspace() for character in serial):
            raise ValueError("explicit non-option serial required")
        self.prefix = [adb, "-s", serial]
        self.timeout = timeout

    def call(self, *args: str, data: bytes | None = None, timeout: float = 30) -> bytes:
        return command(self.prefix + list(args), timeout=timeout, data=data)

    def shell(self, *args: str) -> str:
        return self.call("shell", *args).decode("utf-8").strip()

    def private(self, *args: str, data: bytes | None = None) -> bytes:
        return self.call("exec-out", "run-as", PACKAGE, *args, data=data)

    def stop(self) -> None:
        self.shell("am", "force-stop", PACKAGE)
        self.shell("am", "force-stop", TEST_PACKAGE)
        if self.shell("sh", "-c", f"'pidof {PACKAGE} || true'"):
            raise ValueError("isolated target process survived force-stop")

    def stage(self, session: str, request: dict, assets: list[tuple[str, Path]]) -> None:
        directory = f"files/bubble_validation/{session}"
        self.private("mkdir", "-p", directory + "/inputs")
        for name, source in assets:
            self.private("tee", f"{directory}/inputs/{name}", data=source.read_bytes())
        self.private("tee", f"{directory}/request.json",
                     data=canonical_json(request).encode("utf-8"))

    def read(self, session: str, relative: str) -> bytes:
        local(Path("/artifact-root"), relative)
        return self.private("cat", f"files/bubble_validation/{session}/{relative}")


def fail(intent: dict, kind: str, status: str = "invalid_evidence") -> dict:
    return {key: intent[key] for key in ("attempt_id", "sample_id", "mode")} | {
        "status": status, "detections": [], "failure_kind": kind}


def timing_metadata(timeout: float) -> dict:
    return {
        "timing_scope": "monotonic direct detect; setup/export excluded; watchdog bounds instrumentation launch",
        "watchdog_ns": int(timeout * 1_000_000_000),
    }


def verify_prediction(prediction: dict, output: Path, device: Device, session: str) -> None:
    bounds = prediction["bounds"]
    if (len(bounds) != 4 or any(type(value) not in (int, float) or not math.isfinite(value)
                              or not 0 <= value <= 1 for value in bounds)
            or bounds[0] >= bounds[2] or bounds[1] >= bounds[3]):
        raise ValueError("invalid output geometry")
    relative = prediction["mask_path"]
    target = local(output, relative)
    payload = device.read(session, relative)
    if not payload.startswith(b"\x89PNG\r\n\x1a\n"):
        raise ValueError("mask is not a PNG")
    if hashlib.sha256(payload).hexdigest() != prediction["mask_sha256"]:
        raise ValueError("mask SHA-256 mismatch")
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists():
        raise ValueError("duplicate artifact path")
    target.write_bytes(payload)


def capture_group(device: Device, output: Path, intents: list[dict], samples: dict,
                  manifest_dir: Path) -> list[dict]:
    session = uuid.uuid4().hex
    mode = intents[0]["mode"]
    jobs, assets = [], []
    for index, intent in enumerate(intents):
        sample = samples[intent["sample_id"]]
        source = local(manifest_dir, sample["asset"]["path"])
        if sha256_file(source) != sample["asset"]["sha256"]:
            raise ValueError("asset changed after manifest validation")
        name = f"{index}.page"
        assets.append((name, source))
        jobs.append({"attempt_id": intent["attempt_id"], "sample_id": intent["sample_id"],
                     "path": f"inputs/{name}", "sha256": sha256_file(source)})
    device.stop()
    device.stage(session, {"mode": mode, "jobs": jobs}, assets)
    log = output / f"launch-{session}.txt"
    timed_out = False
    try:
        result = device.call("shell", "am", "instrument", "-w", "-r", "-e", "class", TEST_CLASS,
                             "-e", "session", session, COMPONENT, timeout=device.timeout)
        log.write_bytes(result)
    except subprocess.TimeoutExpired as exc:
        timed_out = True
        log.write_bytes((exc.stdout or b"") + b"\nHOST WATCHDOG EXPIRED\n")
        device.stop()
    except RuntimeError as exc:
        log.write_text(str(exc), encoding="utf-8")
    warm_process = None
    if mode == "warm_inference":
        try:
            warmup = json.loads(device.read(session, "warmup.json"))
            if warmup.get("completed") is not True:
                raise ValueError("warmup incomplete")
            warm_process = warmup["process"]
            atomic_json(output / f"warmup-{session}.json", warmup)
        except (RuntimeError, ValueError, KeyError):
            device.stop()
            return [{**fail(intent, "warmup_evidence_missing"), "runtime": timing_metadata(device.timeout)}
                    for intent in intents]
    records = []
    for intent in intents:
        try:
            record = json.loads(device.read(session, intent["attempt_id"] + ".json"))
        except (RuntimeError, ValueError):
            records.append(fail(intent, "host_timeout" if timed_out else "instrumentation_no_result",
                                "detector_failed" if timed_out else "invalid_evidence"))
            continue
        try:
            for key in ("attempt_id", "sample_id", "mode"):
                if record[key] != intent[key]:
                    raise ValueError("attempt identity mismatch")
            process = record["process"]
            if not isinstance(process["pid"], int) or process["pid"] < 1 or not process["start_id"]:
                raise ValueError("process identity missing")
            if mode == "warm_inference" and process != warm_process:
                raise ValueError("warm process changed")
            for prediction in record["detections"]:
                verify_prediction(prediction, output, device, session)
            record["runtime"] = timing_metadata(device.timeout)
            records.append(record)
        except (ValueError, KeyError, TypeError, RuntimeError) as exc:
            records.append(fail(intent, "invalid_export_" + type(exc).__name__))
    device.stop()
    for record in records:
        record.setdefault("runtime", timing_metadata(device.timeout))
    return records


def verify_apk(apk: Path, package: str, aapt: str) -> None:
    info = command([aapt, "dump", "badging", str(apk)]).decode("utf-8")
    match = re.search(r"^package: name='([^']+)'", info, re.MULTILINE)
    if not match or match.group(1) != package:
        raise ValueError(f"APK must target isolated package {package}")


def execute(args: argparse.Namespace) -> int:
    if args.mode == "cache_hit":
        raise ValueError("BLOCKED: direct detector cannot measure cache_hit")
    manifest = validate_manifest(args.manifest)
    if not manifest["samples"]:
        raise ValueError("BLOCKED: empty manifest; real baseline has no ground truth")
    samples = {sample["sample_id"]: sample for sample in manifest["samples"]}
    if command(["git", "-C", str(ROOT), "status", "--porcelain", "--", "app/src/main"]).strip():
        raise ValueError("production source differs from HEAD")
    production = command(["git", "-C", str(ROOT), "rev-parse", "HEAD"]).decode().strip()
    verify_apk(args.apk, PACKAGE, args.aapt)
    verify_apk(args.test_apk, TEST_PACKAGE, args.aapt)
    with zipfile.ZipFile(args.apk) as archive:
        apk_model = hashlib.sha256(archive.read("assets/models/bubble_segment.onnx")).hexdigest()
    if apk_model != sha256_file(MODEL):
        raise ValueError("APK model does not match local production asset")
    device = Device(args.adb, args.serial, args.timeout)
    if device.call("get-state").strip() != b"device":
        raise ValueError("explicit device is not ready")
    emulator = device.shell("getprop", "ro.kernel.qemu") == "1"
    if emulator != (args.runtime_kind == "emulator"):
        raise ValueError("runtime_kind conflicts with device property")
    fingerprint = device.shell("getprop", "ro.build.fingerprint")
    model = device.shell("getprop", "ro.product.model")
    if not fingerprint or not model:
        raise ValueError("device provenance unavailable")
    args.output.mkdir(parents=True, exist_ok=False)
    schedule = [{"attempt_id": f"a{index:06d}", "sample_id": sample_id,
                 "mode": args.mode, "expected": "valid"}
                for index, sample_id in enumerate(sorted(samples))]
    harness_identity = "sha256:" + hashlib.sha256(canonical_json({
        "source_digest": source_digest(),
        "test_apk_sha256": sha256_file(args.test_apk)}).encode("utf-8")).hexdigest()
    run = {"schema_version": "1.0", "run_id": uuid.uuid4().hex,
           "provenance": {"production_revision": production, "harness_revision": harness_identity,
                          "model_sha256": apk_model, "corpus_sha256": sha256_file(args.manifest),
                          "annotations_sha256": annotation_digest(manifest),
                          "policy_sha256": sha256_file(args.policy),
                          "device_fingerprint": fingerprint, "device_model": model,
                          "runtime_kind": args.runtime_kind, "build_variant": "bubbleValidation",
                          "contract": "v35_2_"}, "schedule": schedule, "attempts": []}
    atomic_json(args.output / "schedule.json", {"schema_version": "1.0", "schedule": schedule})
    atomic_json(args.output / "run.json", run)
    atomic_json(args.output / "capture.json", {
        "schema_version": "1.0", "app_apk_sha256": sha256_file(args.apk),
        "test_apk_sha256": sha256_file(args.test_apk), "serial": args.serial,
        "harness_source_digest": source_digest(),
        "dataset_kind": manifest["kind"], "measurement": "direct public BubbleDetector.detect",
        "watchdog_ns": int(args.timeout * 1_000_000_000),
        "warmup_calls_per_session": 1 if args.mode == "warm_inference" else 0,
        "boundaries": {"UI_journey": "BLOCKED", "peak_memory": "BLOCKED", "cache_hit": "BLOCKED"}})
    try:
        if args.install:
            for apk in (args.apk, args.test_apk):
                installed = device.call("install", "-r", str(apk), timeout=args.timeout)
                if b"Success" not in installed:
                    raise ValueError("APK install did not report Success")
        for package, apk in ((PACKAGE, args.apk), (TEST_PACKAGE, args.test_apk)):
            installed_path = device.shell("pm", "path", package)
            if not installed_path.startswith("package:") or "\n" in installed_path:
                raise ValueError("single installed supplied APK required")
            installed = device.call("exec-out", "cat", installed_path[len("package:"):])
            if hashlib.sha256(installed).hexdigest() != sha256_file(apk):
                raise ValueError("installed APK differs from supplied APK")
        groups = [[item] for item in schedule] if args.mode == "cold_inference" else [schedule]
        cold_identities = set()
        for group in groups:
            try:
                records = capture_group(device, args.output, group, samples, args.manifest.parent)
            except (RuntimeError, ValueError, OSError, subprocess.TimeoutExpired) as exc:
                records = [fail(intent, "capture_" + type(exc).__name__) for intent in group]
            for record in records:
                record.setdefault("runtime", timing_metadata(args.timeout))
                if args.mode == "cold_inference" and "process" in record:
                    process = record["process"]
                    identity = (process["pid"], process["start_id"])
                    if identity in cold_identities:
                        record = fail(record, "cold_process_reused")
                    cold_identities.add(identity)
                run["attempts"].append(record)
            atomic_json(args.output / "run.json", run)
    except (RuntimeError, ValueError, OSError, subprocess.TimeoutExpired) as exc:
        completed = {attempt["attempt_id"] for attempt in run["attempts"]}
        run["attempts"].extend(fail(intent, "host_setup_" + type(exc).__name__)
                               for intent in schedule if intent["attempt_id"] not in completed)
        for record in run["attempts"]:
            record.setdefault("runtime", timing_metadata(args.timeout))
        atomic_json(args.output / "run.json", run)
    validate_run(args.output / "run.json", manifest_path=args.manifest)
    blocked = any(attempt["status"] != "success" for attempt in run["attempts"])
    print(canonical_json({"status": "BLOCKED" if blocked else "PASS",
                          "run": str(args.output / "run.json"), "kind": manifest["kind"]}), end="")
    return 2 if blocked else 0


class GuardTests(unittest.TestCase):
    def capture_fixture(self, mode="cold_inference"):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        asset = root / "page.png"
        asset.write_bytes(b"synthetic test bytes")
        intent = {"attempt_id": "a1", "sample_id": "synthetic", "mode": mode, "expected": "valid"}
        samples = {"synthetic": {"asset": {"path": "page.png", "sha256": sha256_file(asset)}}}
        device = Mock()
        device.timeout = 1
        return root, intent, samples, device

    def test_watchdog_keeps_failed_attempt(self):
        root, intent, samples, device = self.capture_fixture()
        device.call.side_effect = subprocess.TimeoutExpired("adb", 1, output=b"partial")
        device.read.side_effect = RuntimeError("no result")
        result = capture_group(device, root, [intent], samples, root)
        self.assertEqual("detector_failed", result[0]["status"])
        self.assertEqual("host_timeout", result[0]["failure_kind"])
        self.assertNotIn("duration_ns", result[0])
        self.assertEqual(1_000_000_000, result[0]["runtime"]["watchdog_ns"])
        self.assertEqual(3, device.stop.call_count)
        self.assertIn(b"HOST WATCHDOG", next(root.glob("launch-*.txt")).read_bytes())

    def test_completed_output_survives_late_watchdog(self):
        root, intent, samples, device = self.capture_fixture()
        device.call.side_effect = subprocess.TimeoutExpired("adb", 1)
        record = {key: intent[key] for key in ("attempt_id", "sample_id", "mode")}
        record.update(status="success", duration_ns=5, detections=[],
                      process={"pid": 42, "start_id": "100", "fresh": True})
        device.read.return_value = json.dumps(record).encode()
        result = capture_group(device, root, [intent], samples, root)
        self.assertEqual("success", result[0]["status"])
        self.assertEqual(5, result[0]["duration_ns"])

    def test_missing_export_without_watchdog_is_invalid_evidence(self):
        root, intent, samples, device = self.capture_fixture()
        device.call.return_value = b"instrumentation returned but no exported result"
        device.read.side_effect = RuntimeError("result cannot be collected")
        result = capture_group(device, root, [intent], samples, root)
        self.assertEqual("invalid_evidence", result[0]["status"])
        self.assertEqual("instrumentation_no_result", result[0]["failure_kind"])

    def test_warm_requires_matching_process_evidence(self):
        root, intent, samples, device = self.capture_fixture("warm_inference")
        device.call.return_value = b"done"
        record = {key: intent[key] for key in ("attempt_id", "sample_id", "mode")}
        record.update(status="success", duration_ns=5, detections=[],
                      process={"pid": 43, "start_id": "101", "fresh": False})
        device.read.side_effect = [json.dumps({"completed": True, "duration_ns": 2,
            "process": {"pid": 42, "start_id": "100", "fresh": False}}).encode(),
            json.dumps(record).encode()]
        result = capture_group(device, root, [intent], samples, root)
        self.assertEqual("invalid_evidence", result[0]["status"])

    def test_corrupt_mask_blocks_export(self):
        root, intent, samples, device = self.capture_fixture()
        device.read.return_value = b"not PNG"
        prediction = {"bounds": [0, 0, 1, 1], "mask_path": "masks/one.png", "mask_sha256": "a" * 64}
        with self.assertRaisesRegex(ValueError, "PNG"):
            verify_prediction(prediction, root, device, "a" * 32)
        self.assertFalse((root / "masks/one.png").exists())

    def test_installed_package_guard(self):
        with patch(__name__ + ".command", return_value=b"package: name='com.comicreader.app'"):
            with self.assertRaisesRegex(ValueError, "isolated package"):
                verify_apk(Path("production.apk"), PACKAGE, "aapt")

    def test_path_guard(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for value in ("../x", "/x", "a/../x", "a//b", "a\\b", "C:x", "./x", ""):
                with self.assertRaises(ValueError):
                    local(root, value)
            (root / "escape").symlink_to(root.parent, target_is_directory=True)
            with self.assertRaises(ValueError):
                local(root, "escape/x")

    def test_cache_hit_rejected_before_adb(self):
        with patch(__name__ + ".command") as mocked:
            with self.assertRaisesRegex(ValueError, "cache_hit"):
                execute(argparse.Namespace(mode="cache_hit"))
            mocked.assert_not_called()

    def test_empty_manifest_blocks_before_adb(self):
        with patch(__name__ + ".validate_manifest", return_value={"samples": []}):
            with patch(__name__ + ".command") as mocked:
                with self.assertRaisesRegex(ValueError, "empty manifest"):
                    execute(argparse.Namespace(mode="cold_inference", manifest=Path("empty")))
                mocked.assert_not_called()

    def test_failure_preserves_schedule_identity(self):
        intent = {"attempt_id": "a1", "sample_id": "s1", "mode": "cold_inference", "expected": "valid"}
        result = fail(intent, "host_timeout", "detector_failed")
        self.assertEqual("detector_failed", result["status"])
        self.assertEqual([], result["detections"])
        self.assertNotIn("duration_ns", result)
        self.assertEqual("s1", result["sample_id"])

    def test_source_digest_tracks_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "app/src/androidTest").mkdir(parents=True)
            for name in ("app/build.gradle.kts", "build.gradle.kts", "settings.gradle.kts",
                         "gradle.properties", "gradlew", "gradlew.bat", "qa/bubble/toolchain.json"):
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("initial")
            first = source_digest(root)
            (root / "app/src/androidTest/Test.kt").write_text("changed")
            self.assertNotEqual(first, source_digest(root))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--policy", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--serial")
    parser.add_argument("--runtime-kind", choices=("physical", "emulator"))
    parser.add_argument("--mode", choices=("cold_inference", "warm_inference", "cache_hit"))
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--test-apk", type=Path)
    parser.add_argument("--install", action="store_true")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--aapt", default="aapt")
    parser.add_argument("--timeout", type=float, default=180)
    args = parser.parse_args(argv)
    if args.self_test:
        result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(GuardTests))
        return 0 if result.wasSuccessful() else 1
    required = ("manifest", "policy", "output", "serial", "runtime_kind", "mode", "apk", "test_apk")
    if any(getattr(args, field) is None for field in required):
        parser.error("required: " + ", ".join("--" + field.replace("_", "-") for field in required))
    if not math.isfinite(args.timeout) or not 1 <= args.timeout <= 3600:
        parser.error("--timeout must be finite in [1,3600] seconds")
    try:
        return execute(args)
    except (ValueError, OSError, RuntimeError, KeyError, zipfile.BadZipFile,
            subprocess.TimeoutExpired) as exc:
        print(canonical_json({"status": "BLOCKED", "reason": str(exc)}), end="")
        return 2


if __name__ == "__main__":
    sys.exit(main())
