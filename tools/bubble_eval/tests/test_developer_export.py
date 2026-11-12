"""Developer ZIP contract regressions. Invented fixtures are NEVER real pilot evidence.

Native roundtrip: ANAGNO_EVALUATION_ZIP=/path/to/synthetic-evaluation.zip
python3 -m unittest tools.bubble_eval.tests.test_developer_export -v
An absent native artifact is reported as skipped/BLOCKED, not a device pass.
"""
import copy
import json
import os
from pathlib import Path, PurePosixPath
import stat
import tempfile
import unittest
import warnings
import zipfile

from tools.bubble_eval.png import read_alpha, write_gray
from tools.bubble_eval.report import (
    annotation_digest, build_report, compare_reports, sha256_file,
)
from tools.bubble_eval.validate import validate_manifest, validate_run

REPOSITORY = Path(__file__).resolve().parents[3]


def validate_export(archive, destination):
    """Extract a bounded fixture/export into a NEW directory and run host admission/scoring.

    This is a test utility, not a production ZIP importer. No extraction of absolute,
    traversal, duplicate or symlink entries; validate every name before any writes.
    """
    destination = Path(destination)
    destination.mkdir()  # Refuse to merge evidence with an existing extraction.
    with zipfile.ZipFile(archive) as bundle:
        entries = bundle.infolist()
        names = set()
        if len(entries) > 2000 or sum(item.file_size for item in entries) > 256_000_000:
            raise ValueError("excessive ZIP fixture")
        for item in entries:
            name = item.filename
            parts = name.rstrip("/").split("/")
            if (not name or PurePosixPath(name).is_absolute() or "\\" in name or ":" in name
                    or any(part in {"", ".", ".."} for part in parts)
                    or name in names or stat.S_ISLNK(item.external_attr >> 16)):
                raise ValueError("unsafe or duplicate ZIP entry")
            names.add(name)
        bundle.extractall(destination)
    manifest = validate_manifest(destination / "manifest.json")
    run = validate_run(destination / "run.json", manifest_path=destination / "manifest.json")
    if not 1 <= len(manifest["samples"]) <= 10:
        raise ValueError("developer pilot requires 1–10 pages")
    report = build_report(destination / "manifest.json", destination / "run.json",
                          policy_path=destination / "policy.json")
    return manifest, run, report


class DeveloperExportTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.stage = self.root / "stage"
        self.stage.mkdir()
        self.manifest = {"schema_version": "1.0", "dataset_id": "synthetic-export-fixture",
                         "revision": "fixture-1", "kind": "synthetic", "samples": []}
        self.rights = {"schema_version": "1.0", "records": []}
        self.run = {"schema_version": "1.0", "run_id": "synthetic-no-detector-calls",
                    "provenance": {"production_revision": "synthetic-fixture",
                                   "harness_revision": "synthetic-export-tests-v1",
                                   "device_fingerprint": "host:synthetic-fixture",
                                   "device_model": "synthetic-fixture", "runtime_kind": "host",
                                   "build_variant": "fixture", "contract": "v35_2_"},
                    "schedule": [], "attempts": []}
        (self.stage / "policy.json").write_bytes((REPOSITORY / "qa/bubble/acceptance-policy.json").read_bytes())
        self.run["provenance"].update(
            model_sha256=sha256_file(REPOSITORY / "app/src/main/assets/models/bubble_segment.onnx"),
            policy_sha256=sha256_file(self.stage / "policy.json"))
        for sid, negative in (("synthetic-body-tail-hole", False), ("synthetic-negative", True)):
            self.add_page(sid, negative)

    def write(self, relative, value):
        path = self.stage / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value, ensure_ascii=True), encoding="utf-8")
        return path

    def reference(self, relative):
        return {"path": relative, "sha256": sha256_file(self.stage / relative)}

    def add_page(self, sid, negative):
        width, height = 97, 65
        prefix = f"pages/{sid}"
        page = self.stage / prefix
        (page / "gt").mkdir(parents=True)
        (page / "predictions").mkdir()
        # Independent pixel-center oracle: union body + diagonal tail, minus hole.
        def included(x, y):
            cx, cy = x + .5, y + .5
            body = 4 <= cx < 80 and 4 <= cy < 40
            tail = 36 <= cy < 60 and abs(cx - 55) < (60 - cy) * .625
            hole = 10 <= cx < 20 and 10 <= cy < 20
            return (body or tail) and not hole
        alpha = bytes(255 * included(x, y) for y in range(height) for x in range(width))
        # Distinct, original generated rasters; negative pages are not duplicate assets.
        write_gray(page / "image.png", width, height, bytes([240 if negative else 255]) * (width * height))
        instances, detections = [], []
        if not negative:
            write_gray(page / "gt/r-body-tail-hole.png", width, height, alpha)
            crop = bytes(alpha[y * width + x] for y in range(4, 60) for x in range(4, 80))
            write_gray(page / "predictions/p000.png", 76, 56, crop)
            instances.append({"id": "r-body-tail-hole", "box": [4, 4, 80, 60],
                              "mask_path": "gt/r-body-tail-hole.png",
                              "mask_sha256": sha256_file(page / "gt/r-body-tail-hole.png")})
            detections.append({"prediction_id": "p000", "bounds": [4/width, 4/height, 80/width, 60/height],
                               "mask_path": f"{prefix}/predictions/p000.png",
                               "mask_sha256": sha256_file(page / "predictions/p000.png")})
        self.write(f"{prefix}/annotation.json", {"schema_version": "1.0", "sample_id": sid,
                   "width": width, "height": height, "completeness": "mask",
                   "instances": instances, "groups": [], "ignore_regions": []})
        (page / "permission.txt").write_text("Original synthetic geometry fixture; test author permits evaluation. No real pilot claim.")
        self.rights["records"].append({"rights_id": sid, "status": "approved", "holder": "synthetic fixture author",
            "license": "original test artwork", "allowed_uses": ["evaluation"],
            "evidence": self.reference(f"{prefix}/permission.txt")})
        self.manifest["samples"].append({"sample_id": sid, "source_group": "synthetic-fixture-family",
            "split": "development", "tags": ["negative"] if negative else ["irregular", "tail"],
            "rights_id": sid, "asset": {**self.reference(f"{prefix}/image.png"), "width": width, "height": height},
            "annotation": {**self.reference(f"{prefix}/annotation.json"), "completeness": "mask"}})
        intent = {"attempt_id": f"a-{sid}", "sample_id": sid, "mode": "warm_inference", "expected": "valid"}
        self.run["schedule"].append(intent)
        self.run["attempts"].append({key: value for key, value in intent.items() if key != "expected"} |
            {"status": "success", "duration_ns": 1, "detections": detections})

    def archive(self):
        self.write("manifest.json", self.manifest)
        self.write("rights.json", self.rights)
        self.run["provenance"].update(corpus_sha256=sha256_file(self.stage / "manifest.json"),
                                      annotations_sha256=annotation_digest(self.manifest))
        self.write("run.json", self.run)
        archive = self.root / "synthetic.zip"
        with zipfile.ZipFile(archive, "w") as bundle:
            for path in sorted(self.stage.rglob("*")):
                if path.is_file():
                    bundle.write(path, path.relative_to(self.stage).as_posix())
        return archive

    def evaluate(self):
        return validate_export(self.archive(), self.root / "extracted")

    def test_zip_roundtrip_masks_counts_negative_and_identical_candidate(self):
        manifest, run, report = self.evaluate()
        self.assertEqual(manifest["kind"], "synthetic")
        self.assertEqual(manifest["_validation"]["expected_counts"],
                         {"synthetic-body-tail-hole": 1, "synthetic-negative": 0})
        self.assertEqual(report["real_baseline_status"], "BLOCKED")
        quality = report["metrics_by_mode"]["warm_inference"]
        self.assertEqual(quality["recall"], 1)
        self.assertEqual(quality["failure_inclusive_mask_quality"], 1)
        self.assertEqual(quality["fp_per_successful_page"], 0)
        comparison = compare_reports(report, copy.deepcopy(report))
        self.assertEqual(comparison["mode_deltas"]["warm_inference"]["recall_delta"], 0)
        self.assertIn("BLOCKED", comparison["quality_improvement_claim"])
        self.assertEqual(run["provenance"]["runtime_kind"], "host")

    def test_failed_page_keeps_fn_and_blocks_full_corpus_fp(self):
        self.run["attempts"][0].update(status="detector_failed", failure_kind="synthetic-negative-control", detections=[])
        del self.run["attempts"][0]["duration_ns"]
        _, _, report = self.evaluate()
        self.assertEqual(report["pages"][0]["fn"], 1)
        self.assertIsNone(report["pages"][0]["fp"])
        self.assertEqual(report["metrics_by_mode"]["warm_inference"]["failure_inclusive_mask_quality"], 0)
        mode = report["accounting"]["modes"]["warm_inference"]
        self.assertEqual(mode["scheduled"], 2)
        self.assertEqual(mode["failure_rate"], .5)
        self.assertEqual(report["accounting"]["full_corpus_fp_comparison"], "BLOCKED")

    def test_partial_or_invalid_evidence_cannot_become_empty_success(self):
        for status in ("missing", "invalid_evidence"):
            with self.subTest(status=status):
                original = copy.deepcopy(self.run["attempts"])
                if status == "missing":
                    self.run["attempts"].pop()
                else:
                    self.run["attempts"][0].update(status=status, failure_kind="synthetic-control")
                archive = self.archive()
                with self.assertRaises(ValueError):
                    validate_export(archive, self.root / status)
                self.run["attempts"] = original

    def test_rights_and_permission_artifact_are_required(self):
        for problem in ("pending", "no-evaluation", "corrupt-proof"):
            original = copy.deepcopy(self.rights)
            record = self.rights["records"][0]
            if problem == "pending":
                record["status"] = "pending"
            elif problem == "no-evaluation":
                record["allowed_uses"] = ["redistribution"]
            else:
                record["evidence"]["sha256"] = "0" * 64
            with self.subTest(problem=problem), self.assertRaises(ValueError):
                validate_export(self.archive(), self.root / problem)
            self.rights = original

    def test_tampered_prediction_png_is_rejected(self):
        (self.stage / "pages/synthetic-body-tail-hole/predictions/p000.png").write_bytes(b"corrupt")
        with self.assertRaisesRegex(ValueError, "checksum mismatch"):
            self.evaluate()

    def test_candidate_must_use_same_corpus_annotations_and_device(self):
        _, _, baseline = self.evaluate()
        for field in ("corpus_sha256", "annotations_sha256", "device_fingerprint", "build_variant"):
            candidate = copy.deepcopy(baseline)
            candidate["provenance"][field] = "0" * 64 if field.endswith("sha256") else "different-fixture"
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, "incompatible provenance"):
                compare_reports(baseline, candidate)

    def test_ten_pages_allowed_eleven_rejected(self):
        # Distinct bytes and stable IDs; the cap is independent of core48 admission.
        for index in range(2, 10):
            self.add_page(f"synthetic-negative-{index}", True)
            path = self.stage / f"pages/synthetic-negative-{index}/image.png"
            write_gray(path, 97, 65, bytes([200 + index]) * (97 * 65))
            self.manifest["samples"][-1]["asset"]["sha256"] = sha256_file(path)
        manifest, _, _ = self.evaluate()
        self.assertEqual(len(manifest["samples"]), 10)
        self.add_page("synthetic-eleventh", True)
        path = self.stage / "pages/synthetic-eleventh/image.png"
        write_gray(path, 97, 65, bytes([199]) * (97 * 65))
        self.manifest["samples"][-1]["asset"]["sha256"] = sha256_file(path)
        with self.assertRaisesRegex(ValueError, "1–10"):
            validate_export(self.archive(), self.root / "eleventh")

    def test_zip_traversal_symlink_and_duplicate_rejected(self):
        for index, name in enumerate(("../escape", "/escape", "a/../escape", "C:/escape", "a\\escape", "link")):
            archive = self.root / f"bad{index}.zip"
            with zipfile.ZipFile(archive, "w") as bundle:
                entry = zipfile.ZipInfo(name)
                if name == "link":
                    entry.create_system = 3
                    entry.external_attr = (stat.S_IFLNK | 0o777) << 16
                bundle.writestr(entry, "outside")
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "unsafe"):
                validate_export(archive, self.root / f"bad{index}")
        self.assertFalse((self.root / "escape").exists())
        duplicate = self.root / "duplicate.zip"
        with zipfile.ZipFile(duplicate, "w") as bundle, warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            bundle.writestr("manifest.json", "first")
            bundle.writestr("manifest.json", "second")
        with self.assertRaisesRegex(ValueError, "duplicate"):
            validate_export(duplicate, self.root / "duplicate")
        self.assertEqual(list((self.root / "duplicate").iterdir()), [])

    @unittest.skipUnless(os.environ.get("ANAGNO_EVALUATION_ZIP"),
                         "BLOCKED: no captured Android synthetic exporter ZIP supplied")
    def test_native_exporter_zip_roundtrip(self):
        manifest, run, report = validate_export(os.environ["ANAGNO_EVALUATION_ZIP"], self.root / "native")
        self.assertEqual(manifest["kind"], "synthetic", "Native regression input must remain a fixture")
        self.assertEqual(report["real_baseline_status"], "BLOCKED")
        self.assertEqual(manifest["_validation"]["expected_counts"],
                         {"synthetic-body-tail-hole": 1, "synthetic-negative": 0,
                          "synthetic-false-positive": 0})
        sample = next(item for item in manifest["samples"] if item["sample_id"] == "synthetic-body-tail-hole")
        annotation_path = self.root / "native" / sample["annotation"]["path"]
        annotation = json.loads(annotation_path.read_text())
        instance = annotation["instances"][0]
        self.assertEqual(instance["box"], [10, 10, 3000, 240])
        width, height, alpha = read_alpha(annotation_path.parent / instance["mask_path"])
        self.assertEqual((width, height), (4097, 257))
        self.assertEqual(alpha[200 * width + 2200], 255, "tail must survive at original resolution")
        self.assertEqual(alpha[50 * width + 150], 0, "subtracted hole must remain empty")
        self.assertEqual(report["accounting"]["modes"]["warm_inference"]["failure_rate"], 1/3)
        control = next(attempt for attempt in run["attempts"]
                       if attempt["sample_id"] == "synthetic-false-positive")
        self.assertEqual(control["status"], "success")
        prediction, = control["detections"]
        self.assertEqual(prediction["prediction_id"], "p000")
        self.assertEqual(prediction["mask_path"], "pages/synthetic-false-positive/predictions/p000.png")
        prediction_path = self.root / "native" / prediction["mask_path"]
        self.assertEqual(sha256_file(prediction_path), prediction["mask_sha256"])
        pw, ph, prediction_alpha = read_alpha(prediction_path)
        self.assertEqual((pw, ph), (8, 6))
        expected_alpha = bytes(255 if 1 <= x <= 6 and 1 <= y <= 4 and (x, y) != (3, 2) else 0
                               for y in range(6) for x in range(8))
        self.assertEqual(prediction_alpha, expected_alpha)
        draft = json.loads((self.root / "native/pages/synthetic-false-positive/draft.json").read_text())
        self.assertEqual(draft["issues"]["p000"], "false_positive")
        fp_page = next(page for page in report["pages"] if page["sample_id"] == "synthetic-false-positive")
        self.assertEqual(fp_page["fp"], 1, "Human issue labels must not erase predictions before scoring")
        self.assertEqual(report["metrics_by_mode"]["warm_inference"]["fp_per_successful_page"], .5)


if __name__ == "__main__":
    unittest.main()
