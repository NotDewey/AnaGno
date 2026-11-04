"""Build binding regressions using only temporary source and APK-shaped files."""

import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from tools.bubble_eval.build_receipt import (
    PACKAGE, apk_from_directory, create_receipt, snapshot_sources,
    verify_receipt, write_snapshot,
)


class BuildReceiptTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        for name in ("app/build.gradle.kts", "build.gradle.kts", "settings.gradle.kts",
                     "gradle.properties", "gradlew", "gradlew.bat", "qa/bubble/toolchain.json",
                     "app/src/main/Page.kt", "app/src/androidTest/Capture.kt",
                     "tools/bubble_eval/capture.py", "gradle/wrapper/gradle-wrapper.properties"):
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("original temporary test source\n", encoding="utf-8")
        self.app_directory = self.root / "app/build/outputs/apk/bubbleValidation"
        self.test_directory = self.root / "app/build/outputs/apk/androidTest/bubbleValidation"
        self.app_apk = self.apk_directory(self.app_directory, PACKAGE)
        self.test_apk = self.apk_directory(self.test_directory, PACKAGE + ".test")
        self.snapshot_path = self.root / "app/build/bubble-validation/source-snapshot.json"
        self.receipt_path = self.snapshot_path.with_name("build-receipt.json")
        git = patch("tools.bubble_eval.build_receipt._git", side_effect=self.git_identity)
        git.start()
        self.addCleanup(git.stop)

    def git_identity(self, root, *arguments):
        return "2ef68d9" + "a" * 33 if arguments[0] == "rev-parse" else ""

    def apk_directory(self, directory, package):
        directory.mkdir(parents=True)
        apk = directory / "capture.apk"
        apk.write_bytes(b"synthetic APK-shaped test artifact; not installable")
        (directory / "output-metadata.json").write_text(json.dumps({
            "applicationId": package, "elements": [{"outputFile": apk.name}],
        }), encoding="utf-8")
        return apk

    def create(self):
        return create_receipt(self.root, self.snapshot_path, self.app_directory,
                              self.test_directory, self.receipt_path)

    def verify(self):
        return verify_receipt(self.receipt_path, self.root, self.app_apk, self.test_apk)

    def test_build_receipt_binds_both_apks_to_prebuild_sources(self):
        snapshot = write_snapshot(self.root, self.snapshot_path)
        receipt = self.create()
        self.assertEqual(receipt, self.verify())
        self.assertEqual(snapshot, receipt["snapshot"])
        self.assertEqual("v35_2_", snapshot["contract"])

    def test_missing_snapshot_cannot_label_existing_apks(self):
        with self.assertRaisesRegex(ValueError, "Build evidence"):
            self.create()
        self.assertFalse(self.receipt_path.exists())

    def test_source_changes_during_build_prevent_receipt(self):
        write_snapshot(self.root, self.snapshot_path)
        (self.root / "app/src/main/Page.kt").write_text("changed during build")
        with self.assertRaisesRegex(ValueError, "Sources changed"):
            self.create()
        self.assertFalse(self.receipt_path.exists())

    def test_harness_changes_after_build_reject_old_apks(self):
        write_snapshot(self.root, self.snapshot_path)
        self.create()
        (self.root / "tools/bubble_eval/capture.py").write_text("new harness")
        with self.assertRaisesRegex(ValueError, "stale"):
            self.verify()

    def test_current_revision_cannot_relabel_old_build(self):
        write_snapshot(self.root, self.snapshot_path)
        self.create()
        with patch("tools.bubble_eval.build_receipt._git", side_effect=lambda root, *arguments:
                   "b" * 40 if arguments[0] == "rev-parse" else ""):
            with self.assertRaisesRegex(ValueError, "stale"):
                self.verify()

    def test_either_apk_hash_mismatch_blocks_capture(self):
        for artifact in (self.app_apk, self.test_apk):
            with self.subTest(artifact=artifact.name, directory=artifact.parent):
                write_snapshot(self.root, self.snapshot_path)
                self.create()
                original = artifact.read_bytes()
                artifact.write_bytes(original + b"modified")
                with self.assertRaisesRegex(ValueError, "APKs"):
                    self.verify()
                artifact.write_bytes(original)

    def test_dirty_production_cannot_get_snapshot(self):
        with patch("tools.bubble_eval.build_receipt._git", return_value=" M app/src/main/Page.kt"):
            with self.assertRaisesRegex(ValueError, "Production source"):
                snapshot_sources(self.root)

    def test_wrong_package_or_multiple_outputs_are_rejected(self):
        metadata = self.app_directory / "output-metadata.json"
        for record in (
            {"applicationId": "com.comicreader.app", "elements": [{"outputFile": "capture.apk"}]},
            {"applicationId": PACKAGE, "elements": [{"outputFile": "capture.apk"}] * 2},
        ):
            metadata.write_text(json.dumps(record), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "one isolated APK"):
                apk_from_directory(self.app_directory, PACKAGE)

    def test_output_path_cannot_escape_build_directory(self):
        metadata = self.app_directory / "output-metadata.json"
        for relative in ("../capture.apk", "/capture.apk", "C:artifact.apk", "./capture.apk"):
            metadata.write_text(json.dumps({"applicationId": PACKAGE,
                                            "elements": [{"outputFile": relative}]}), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "Unsafe"):
                apk_from_directory(self.app_directory, PACKAGE)


if __name__ == "__main__":
    unittest.main()
