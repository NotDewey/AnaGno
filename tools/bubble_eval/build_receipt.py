"""Local build receipts: snapshot sources before assembly, bind APKs afterward."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys

try:
    from .report import canonical_json, sha256_file
except ImportError:
    from report import canonical_json, sha256_file


ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "com.comicreader.app.bubblevalidation"


def _digest_paths(root: Path, paths: set[Path]) -> str:
    digest = hashlib.sha256()
    for path in sorted(paths, key=lambda item: item.relative_to(root).as_posix()):
        if not path.resolve().is_relative_to(root.resolve()):
            raise ValueError("source input escapes repository")
        name = path.relative_to(root).as_posix().encode("utf-8")
        content = path.read_bytes()
        digest.update(len(name).to_bytes(8, "big") + name)
        digest.update(len(content).to_bytes(8, "big") + content)
    return "sha256:" + digest.hexdigest()


def source_digest(root: Path = ROOT) -> str:
    paths = set()
    for directory in ("tools/bubble_eval", "app/src/androidTest", "gradle/wrapper"):
        paths.update(path for path in (root / directory).rglob("*")
                     if path.is_file() and "__pycache__" not in path.parts)
    paths.update(root / name for name in (
        "app/build.gradle.kts", "build.gradle.kts", "settings.gradle.kts",
        "gradle.properties", "gradlew", "gradlew.bat", "qa/bubble/toolchain.json"))
    return _digest_paths(root, paths)


def _git(root: Path, *arguments: str) -> str:
    try:
        result = subprocess.run(
            ["git", "-C", str(root), *arguments], check=True, capture_output=True,
            text=True, timeout=30,
        )
    except (OSError, subprocess.SubprocessError) as error:
        raise ValueError("Git source identity unavailable") from error
    return result.stdout.strip()


def snapshot_sources(root: Path) -> dict:
    root = root.resolve()
    if _git(root, "status", "--porcelain", "--untracked-files=all", "--", "app/src/main"):
        raise ValueError("Production source differs from HEAD")
    return {
        "schema_version": "1.0", "contract": "v35_2_", "build_variant": "bubbleValidation",
        "production_revision": _git(root, "rev-parse", "HEAD"),
        "production_source_digest": _digest_paths(root, {
            path for path in (root / "app/src/main").rglob("*") if path.is_file()
        }),
        "harness_source_digest": source_digest(root),
    }


def _write(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(canonical_json(value), encoding="utf-8")
    temporary.replace(path)


def write_snapshot(root: Path, output: Path) -> dict:
    snapshot = snapshot_sources(root)
    _write(output, snapshot)
    return snapshot


def _read(path: Path) -> dict:
    try:
        record = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise ValueError(f"Build evidence unavailable or invalid: {path}") from error
    if not isinstance(record, dict):
        raise ValueError("Build evidence must be an object")
    return record


def apk_from_directory(directory: Path, package: str) -> Path:
    directory = directory.resolve()
    metadata = _read(directory / "output-metadata.json")
    elements = metadata.get("elements")
    if (metadata.get("applicationId") != package or not isinstance(elements, list)
            or len(elements) != 1 or not isinstance(elements[0], dict)):
        raise ValueError("Expected one isolated APK in build output metadata")
    relative = elements[0].get("outputFile")
    if (not isinstance(relative, str) or not relative or "\\" in relative or ":" in relative
            or Path(relative).is_absolute() or any(part in {"", ".", ".."} for part in relative.split("/"))):
        raise ValueError("Unsafe APK output path")
    apk = (directory / relative).resolve()
    if not apk.is_relative_to(directory) or not apk.is_file() or apk.suffix != ".apk":
        raise ValueError("APK output is missing or outside its build directory")
    return apk


def create_receipt(root: Path, snapshot_path: Path, app_directory: Path,
                   test_directory: Path, output: Path) -> dict:
    snapshot = _read(snapshot_path)
    if snapshot != snapshot_sources(root):
        raise ValueError("Sources changed since the pre-build snapshot; rebuild before capture")
    app_apk = apk_from_directory(app_directory, PACKAGE)
    test_apk = apk_from_directory(test_directory, PACKAGE + ".test")
    receipt = {
        "schema_version": "1.0", "snapshot": snapshot,
        "app_apk_sha256": sha256_file(app_apk),
        "test_apk_sha256": sha256_file(test_apk),
    }
    _write(output, receipt)
    return receipt


def verify_receipt(path: Path, root: Path, app_apk: Path, test_apk: Path) -> dict:
    receipt = _read(path)
    if set(receipt) != {"schema_version", "snapshot", "app_apk_sha256", "test_apk_sha256"}:
        raise ValueError("Unsupported build receipt fields")
    if receipt["schema_version"] != "1.0" or receipt["snapshot"] != snapshot_sources(root):
        raise ValueError("Build receipt source identity is stale or incompatible")
    if (receipt["app_apk_sha256"] != sha256_file(app_apk)
            or receipt["test_apk_sha256"] != sha256_file(test_apk)):
        raise ValueError("Supplied APKs do not match the build receipt")
    return receipt


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    snapshot = commands.add_parser("snapshot")
    create = commands.add_parser("create")
    for command in (snapshot, create):
        command.add_argument("--root", type=Path, required=True)
        command.add_argument("--output", type=Path, required=True)
    create.add_argument("--snapshot", type=Path, required=True)
    create.add_argument("--apk", "--app-dir", dest="app_directory", type=Path, required=True)
    create.add_argument("--test-apk", "--test-apk-dir", dest="test_directory", type=Path, required=True)
    arguments = parser.parse_args()
    try:
        if arguments.command == "snapshot":
            write_snapshot(arguments.root, arguments.output)
        else:
            create_receipt(arguments.root, arguments.snapshot, arguments.app_directory,
                           arguments.test_directory, arguments.output)
        print(canonical_json({"status": "PASS", "output": str(arguments.output)}), end="")
        return 0
    except (ValueError, OSError) as error:
        print(canonical_json({"status": "BLOCKED", "reason": str(error)}), end="")
        return 2


if __name__ == "__main__":
    sys.exit(main())
