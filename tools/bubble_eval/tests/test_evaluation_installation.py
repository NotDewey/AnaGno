"""Host installation guards; no device operations, document selection or permission grants.

Build metadata checks are skipped/BLOCKED when artifacts are absent. They prove
declared build identities, not an installed package or private-library preservation.
"""
import json
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[3]


class EvaluationInstallationTests(unittest.TestCase):
    def test_built_application_ids_keep_all_three_packages_distinct(self):
        expected = {
            "debug": "com.comicreader.app.evaluation",
            "bubbleValidation": "com.comicreader.app.bubblevalidation",
            "androidTest/bubbleValidation": "com.comicreader.app.bubblevalidation.test",
            "release": "com.comicreader.app",
        }
        for variant, package in expected.items():
            with self.subTest(variant=variant):
                metadata = ROOT / "app/build/outputs/apk" / variant / "output-metadata.json"
                if not metadata.is_file():
                    self.skipTest(f"BLOCKED: {variant} output metadata absent; build not run by QA")
                record = json.loads(metadata.read_text(encoding="utf-8"))
                self.assertEqual(package, record["applicationId"])
                self.assertTrue(record["elements"], "Metadata must describe a built APK")
                for element in record["elements"]:
                    self.assertTrue((metadata.parent / element["outputFile"]).is_file())

    def test_release_does_not_include_developer_source_set(self):
        # Source wiring guard only. Lead must inspect compiled manifest/classes/assets
        # separately; source checks cannot establish release APK exclusion.
        gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
        owners = re.findall(
            r'sourceSets\.getByName\("([^"]+)"\)\s*\{([^{}]*)\}', gradle)
        developer_owners = {name for name, body in owners if "src/developerEvaluation/" in body}
        self.assertEqual({"debug", "bubbleValidation"}, developer_owners)
        for variant in ("main", "release"):
            source = ROOT / "app/src" / variant
            for path in source.rglob("*"):
                if path.suffix in {".kt", ".java", ".xml"}:
                    self.assertNotIn("com.comicreader.app.evaluation", path.read_text(encoding="utf-8"),
                                     f"Developer evaluation reference in {path.relative_to(ROOT)}")


if __name__ == "__main__":
    unittest.main()
