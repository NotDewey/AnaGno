"""Evidence admission regressions, using only temporary invented fixtures."""
import copy
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from tools.bubble_eval.validate import (
    COMPARABLE, REQUIRED_TAGS, compare_provenance, validate_annotation,
    validate_manifest, validate_run,
)


class RecordsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.asset = self.artifact("page.pgm", b"P5\n8 8\n255\n" + bytes(64))
        self.mask = self.artifact("masks/one.pgm", b"P5\n8 8\n255\n" + bytes([255]) * 64)
        self.proof = self.artifact("license.txt", b"Invented temporary fixture, evaluation permitted by test author.")
        self.rights = {"schema_version": "1.0", "records": [{
            "rights_id": "test-owned", "status": "approved", "license": "test-owned",
            "holder": "test author", "allowed_uses": ["evaluation"], "evidence": self.proof,
        }]}
        self.write("rights.json", self.rights)
        self.annotation = {"schema_version": "1.0", "sample_id": "synthetic-one",
                           "width": 8, "height": 8, "completeness": "mask",
                           "instances": [{"id": "bubble-one", "box": [0, 0, 8, 8],
                                          "mask_path": self.mask["path"], "mask_sha256": self.mask["sha256"]}],
                           "groups": [{"id": "group-one", "members": ["bubble-one"]}], "ignore_regions": []}
        self.manifest = {"schema_version": "1.0", "dataset_id": "temporary-synthetic",
                         "revision": "fixture-1", "kind": "synthetic", "samples": [{
                             "sample_id": "synthetic-one", "source_group": "invented-family",
                             "split": "development", "tags": ["oval"], "rights_id": "test-owned",
                             "asset": {**self.asset, "width": 8, "height": 8}, "annotation": {},
                         }]}
        self.persist_manifest()
        self.provenance = {"production_revision": "a7faaa2430878cfe353f4a4e084c0f9b760e34fd",
                           "harness_revision": "fixture-harness", "model_sha256": "a" * 64,
                           "corpus_sha256": "b" * 64, "annotations_sha256": "c" * 64,
                           "policy_sha256": "d" * 64, "device_fingerprint": "host:test",
                           "device_model": "test-host", "runtime_kind": "host",
                           "build_variant": "fixture", "contract": "v35_2_"}
        self.run_record = {"schema_version": "1.0", "run_id": "test-run", "provenance": self.provenance,
                           "schedule": [{"attempt_id": "one", "sample_id": "synthetic-one",
                                         "mode": "cold_inference", "expected": "valid"}],
                           "attempts": [{"attempt_id": "one", "sample_id": "synthetic-one",
                                         "mode": "cold_inference", "status": "success", "duration_ns": 0,
                                         "detections": []}]}

    def artifact(self, path, content):
        destination = self.root / path
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(content)
        return {"path": path, "sha256": hashlib.sha256(content).hexdigest()}

    def write(self, path, value):
        destination = self.root / path
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(json.dumps(value), encoding="utf-8")
        return destination

    def persist_manifest(self):
        annotation_path = self.write("annotation.json", self.annotation)
        self.manifest["samples"][0]["annotation"] = {
            "path": "annotation.json", "sha256": hashlib.sha256(annotation_path.read_bytes()).hexdigest(),
            "completeness": self.annotation["completeness"],
        }
        return self.write("manifest.json", self.manifest)

    def validate_current_run(self, **kwargs):
        return validate_run(self.write("run.json", self.run_record), **kwargs)

    def test_synthetic_inventory_is_valid_but_baseline_blocked(self):
        record = validate_manifest(self.root / "manifest.json")
        self.assertEqual(record["_validation"]["expected_counts"], {"synthetic-one": 1})
        self.assertEqual(record["_validation"]["baseline_status"], "BLOCKED")
        self.assertEqual(record["kind"], "synthetic")

    def test_empty_real_inventory_legal_core_blocked(self):
        self.manifest.update(kind="real", samples=[])
        path = self.write("manifest.json", self.manifest)
        self.assertEqual(validate_manifest(path)["_validation"]["inventory_status"], "PASS")
        with self.assertRaisesRegex(ValueError, "core admission BLOCKED"):
            validate_manifest(path, require_core=True)

    def test_core_requires_categories_and_policy_matches(self):
        policy_path = Path(__file__).resolve().parents[3] / "qa/bubble/acceptance-policy.json"
        policy = json.loads(policy_path.read_text())
        self.assertEqual(set(policy["required_tags"]), REQUIRED_TAGS)
        gaps = validate_manifest(self.root / "manifest.json")["_validation"]["gaps"]
        self.assertTrue(any("missing categories" in gap for gap in gaps))

    def test_invalid_pixel_bounds(self):
        for box in ([0, 0, 9, 8], [0, 0, 0, 1], [3, 0, 2, 1], [-1, 0, 2, 1],
                    [False, 0, 2, 1], [0.5, 0, 2, 1]):
            with self.subTest(box=box):
                self.annotation["instances"][0]["box"] = box
                with self.assertRaises(ValueError):
                    validate_annotation(self.write("annotation.json", self.annotation))

    def test_completeness_requires_masks_and_matching_dimensions(self):
        del self.annotation["instances"][0]["mask_path"]
        with self.assertRaises(ValueError):
            validate_manifest(self.persist_manifest())
        self.annotation["completeness"] = "count"
        self.annotation["instances"] = [{"id": "count-one"}]
        self.annotation["groups"] = []
        self.assertEqual(validate_manifest(self.persist_manifest())["_validation"]["expected_counts"]["synthetic-one"], 1)
        self.annotation["width"] = 9
        with self.assertRaisesRegex(ValueError, "does not match"):
            validate_manifest(self.persist_manifest())

    def test_hash_mismatch_and_malformed_hash(self):
        for digest in ("f" * 64, "A" * 64, "00", None):
            with self.subTest(digest=digest):
                self.manifest["samples"][0]["asset"]["sha256"] = digest
                with self.assertRaises(ValueError):
                    validate_manifest(self.write("manifest.json", self.manifest))

    def test_traversal_absolute_windows_and_symlink_paths(self):
        for path in ("../page.pgm", "/tmp/page.pgm", "nested/../page.pgm", "./page.pgm", "C:/page.pgm", "nested\\page.pgm", "a//b"):
            with self.subTest(path=path):
                self.manifest["samples"][0]["asset"]["path"] = path
                with self.assertRaises(ValueError):
                    validate_manifest(self.write("manifest.json", self.manifest))
        with tempfile.TemporaryDirectory() as outside:
            external = Path(outside) / "external.pgm"
            external.write_bytes((self.root / "page.pgm").read_bytes())
            (self.root / "escaped.pgm").symlink_to(external)
            self.manifest["samples"][0]["asset"]["path"] = "escaped.pgm"
            with self.assertRaisesRegex(ValueError, "symlink escapes"):
                validate_manifest(self.write("manifest.json", self.manifest))

    def test_unknown_or_unapproved_rights_rejected(self):
        self.manifest["samples"][0]["rights_id"] = "missing"
        with self.assertRaisesRegex(ValueError, "unadmitted rights"):
            validate_manifest(self.write("manifest.json", self.manifest))
        self.rights["records"][0]["status"] = "pending"
        self.write("rights.json", self.rights)
        with self.assertRaisesRegex(ValueError, "approved"):
            validate_manifest(self.root / "manifest.json")

    def test_rights_need_checksums_and_evaluation_permission(self):
        self.rights["records"][0]["allowed_uses"] = ["redistribution"]
        self.write("rights.json", self.rights)
        with self.assertRaisesRegex(ValueError, "evaluation"):
            validate_manifest(self.root / "manifest.json")
        self.rights["records"][0]["allowed_uses"] = ["evaluation"]
        self.rights["records"][0]["evidence"]["sha256"] = "f" * 64
        self.write("rights.json", self.rights)
        with self.assertRaisesRegex(ValueError, "checksum"):
            validate_manifest(self.root / "manifest.json")

    def test_source_family_cannot_cross_splits(self):
        other = copy.deepcopy(self.manifest["samples"][0])
        other.update(sample_id="other", split="holdout")
        self.manifest["samples"].append(other)
        with self.assertRaisesRegex(ValueError, "source family crosses"):
            validate_manifest(self.write("manifest.json", self.manifest))

    def test_duplicate_page_content_cannot_inflate_corpus(self):
        other = copy.deepcopy(self.manifest["samples"][0])
        other.update(sample_id="other", source_group="other")
        self.manifest["samples"].append(other)
        with self.assertRaisesRegex(ValueError, "duplicate page"):
            validate_manifest(self.write("manifest.json", self.manifest))

    def test_groups_partition_and_ignore_artifacts(self):
        self.annotation["groups"].append({"id": "duplicate-member", "members": ["bubble-one"]})
        with self.assertRaisesRegex(ValueError, "multiple groups"):
            validate_annotation(self.write("annotation.json", self.annotation))
        self.annotation["groups"] = []
        self.annotation["ignore_regions"] = [{"id": "art", "mask_path": self.mask["path"],
                                               "mask_sha256": self.mask["sha256"], "reason": "unscored artwork"}]
        validated = validate_annotation(self.write("annotation.json", self.annotation))
        self.assertFalse(validated["_validation"]["groups_complete"])
        self.annotation["ignore_regions"][0]["mask_path"] = "missing.pgm"
        with self.assertRaisesRegex(ValueError, "missing artifact"):
            validate_annotation(self.write("annotation.json", self.annotation))

    def test_partial_group_partition_is_rejected(self):
        other = copy.deepcopy(self.annotation["instances"][0])
        other["id"] = "second"
        self.annotation["instances"].append(other)
        with self.assertRaisesRegex(ValueError, "partition every"):
            validate_annotation(self.write("annotation.json", self.annotation))

    def test_masks_resolve_relative_to_annotation_not_manifest(self):
        annotation = copy.deepcopy(self.annotation)
        annotation["instances"][0]["mask_path"] = "one.pgm"
        annotation_path = self.write("masks/annotation.json", annotation)
        self.manifest["samples"][0]["annotation"].update(
            path="masks/annotation.json", sha256=hashlib.sha256(annotation_path.read_bytes()).hexdigest())
        self.assertEqual(validate_manifest(self.write("manifest.json", self.manifest))["_validation"]["expected_counts"],
                         {"synthetic-one": 1})
        annotation["instances"][0]["mask_path"] = "../masks/one.pgm"
        with self.assertRaisesRegex(ValueError, "unsafe relative"):
            validate_annotation(self.write("masks/annotation.json", annotation))

    def test_prediction_masks_resolve_relative_to_run(self):
        run = copy.deepcopy(self.run_record)
        run["attempts"][0]["detections"] = [{"prediction_id": "prediction-one", "bounds": [0, 0, 1, 1],
                                             "mask_path": "one.pgm", "mask_sha256": self.mask["sha256"]}]
        self.assertEqual(len(validate_run(self.write("masks/run.json", run))["attempts"][0]["detections"]), 1)
        with self.assertRaisesRegex(ValueError, "missing artifact"):
            validate_run(self.write("run.json", run))

    def test_schema_paths_match_validator_and_optional_fields(self):
        schema_root = Path(__file__).resolve().parents[3] / "qa/bubble/schemas"
        annotation_schema = json.loads((schema_root / "annotation.schema.json").read_text())
        ignore = annotation_schema["properties"]["ignore_regions"]["items"]
        self.assertEqual(set(ignore["required"]), {"id", "mask_path", "mask_sha256", "reason"})
        run_schema = json.loads((schema_root / "run.schema.json").read_text())
        attempt_fields = run_schema["properties"]["attempts"]["items"]["properties"]
        self.assertTrue({"memory", "process", "runtime", "constructor_duration_ns"} <= set(attempt_fields))
        self.assertEqual(attempt_fields["memory"]["additionalProperties"], False)

    def test_successful_empty_attempt_is_valid(self):
        self.assertEqual(self.validate_current_run()["attempts"][0]["detections"], [])

    def test_missing_extra_duplicate_and_changed_attempts(self):
        original = copy.deepcopy(self.run_record)
        for problem in ("missing", "extra", "duplicate", "mode", "sample"):
            with self.subTest(problem=problem):
                self.run_record = copy.deepcopy(original)
                attempt = self.run_record["attempts"][0]
                if problem == "missing":
                    self.run_record["attempts"] = []
                elif problem == "extra":
                    extra = copy.deepcopy(attempt)
                    extra["attempt_id"] = "unscheduled"
                    self.run_record["attempts"].append(extra)
                elif problem == "duplicate":
                    self.run_record["attempts"].append(copy.deepcopy(attempt))
                elif problem == "mode":
                    attempt["mode"] = "warm_inference"
                else:
                    attempt["sample_id"] = "different"
                with self.assertRaises(ValueError):
                    self.validate_current_run()

    def test_failed_valid_detector_retained_with_optional_duration(self):
        attempt = self.run_record["attempts"][0]
        attempt.update(status="detector_failed", failure_kind="watchdog")
        self.assertEqual(self.validate_current_run()["attempts"][0]["status"], "detector_failed")
        del attempt["duration_ns"]
        self.validate_current_run()
        attempt["detections"] = [{"prediction_id": "bad", "bounds": [0, 0, 1, 1]}]
        with self.assertRaises(ValueError):
            self.validate_current_run()

    def test_invalid_status_evidence_and_failure_semantics(self):
        attempt = self.run_record["attempts"][0]
        for status in ("ready", "invalid_evidence", "detector_failed"):
            with self.subTest(status=status):
                attempt["status"] = status
                with self.assertRaises(ValueError):
                    self.validate_current_run()
        attempt.update(status="invalid_evidence", failure_kind="missing-capture")
        with self.assertRaisesRegex(ValueError, "blocks comparison"):
            self.validate_current_run()
        attempt["status"] = "success"
        with self.assertRaisesRegex(ValueError, "cannot have failure_kind"):
            self.validate_current_run()

    def test_invalid_input_requires_failed_attempt(self):
        self.run_record["schedule"][0]["expected"] = "invalid_input"
        with self.assertRaises(ValueError):
            self.validate_current_run()
        self.run_record["attempts"][0].update(status="detector_failed", failure_kind="decode")
        self.validate_current_run()

    def test_duration_nonnegative_integer_not_bool(self):
        for duration in (-1, True, 1.5, None):
            with self.subTest(duration=duration):
                self.run_record["attempts"][0]["duration_ns"] = duration
                with self.assertRaises(ValueError):
                    self.validate_current_run()

    def test_invalid_prediction_bounds_and_missing_mask(self):
        prediction = {"prediction_id": "prediction-one", "bounds": [0, 0, 1, 1]}
        self.run_record["attempts"][0]["detections"] = [prediction]
        with self.assertRaises(ValueError):
            self.validate_current_run()
        prediction.update(mask_path=self.mask["path"], mask_sha256=self.mask["sha256"])
        self.validate_current_run()
        for bounds in ([0, 0, 2, 1], [0, 0, 0, 1], [False, 0, 1, 1],
                       [0, 0, 10 ** 400, 1], [0, 0, float("nan"), 1]):
            with self.subTest(bounds=bounds):
                prediction["bounds"] = bounds
                with self.assertRaises(ValueError):
                    self.validate_current_run()
        prediction.update(bounds=[0, 0, 1, 1], mask_path="missing.png", mask_sha256="a" * 64)
        with self.assertRaisesRegex(ValueError, "missing artifact"):
            self.validate_current_run()

    def test_cache_hit_requires_no_inference_and_artifact(self):
        self.run_record["schedule"][0]["mode"] = "cache_hit"
        attempt = self.run_record["attempts"][0]
        attempt["mode"] = "cache_hit"
        with self.assertRaises(ValueError):
            self.validate_current_run()
        attempt["cache_hit_evidence"] = {"cache_key": "page:v35_2_", "inference_invoked": False,
                                         **self.artifact("cache-log.txt", b"cache hit; detector not called")}
        self.validate_current_run()
        attempt["cache_hit_evidence"]["inference_invoked"] = True
        with self.assertRaises(ValueError):
            self.validate_current_run()
        attempt["cache_hit_evidence"]["inference_invoked"] = False
        attempt["mode"] = self.run_record["schedule"][0]["mode"] = "warm_inference"
        with self.assertRaises(ValueError):
            self.validate_current_run()

    def test_provenance_compatibility_all_fixed_fields(self):
        candidate = copy.deepcopy(self.provenance)
        candidate["production_revision"] = "candidate"
        self.assertIsNone(compare_provenance(self.provenance, candidate))
        for key in COMPARABLE:
            with self.subTest(key=key):
                candidate = copy.deepcopy(self.provenance)
                candidate[key] = "e" * 64 if key.endswith("sha256") else "different"
                with self.assertRaises(ValueError):
                    compare_provenance(self.provenance, candidate)

    def test_missing_provenance_field_and_runtime_are_blocked(self):
        for key in self.provenance:
            with self.subTest(key=key):
                provenance = copy.deepcopy(self.provenance)
                del provenance[key]
                with self.assertRaises(ValueError):
                    compare_provenance(provenance, self.provenance)
        self.provenance["runtime_kind"] = "phone"
        with self.assertRaises(ValueError):
            self.validate_current_run()

    def test_manifest_join_rejects_unknown_scheduled_sample(self):
        self.validate_current_run(manifest_path=self.root / "manifest.json")
        for record in (self.run_record["schedule"][0], self.run_record["attempts"][0]):
            record["sample_id"] = "unknown"
        with self.assertRaisesRegex(ValueError, "not in manifest"):
            self.validate_current_run(manifest_path=self.root / "manifest.json")

    def test_optional_runtime_observations_are_typed(self):
        attempt = self.run_record["attempts"][0]
        attempt.update(memory={"physical_pss_bytes": 1000}, process={"pid": 42, "start_id": "boot:42", "fresh": True},
                       constructor_duration_ns=0, runtime={"timing_scope": "detect", "watchdog_ns": 100})
        self.validate_current_run()
        attempt["memory"]["physical_pss_bytes"] = -1
        with self.assertRaises(ValueError):
            self.validate_current_run()

    def test_duplicate_json_keys_and_unknown_fields_rejected(self):
        path = self.root / "bad.json"
        path.write_text('{"schema_version":"1.0","schema_version":"1.0"}')
        with self.assertRaisesRegex(ValueError, "duplicate JSON"):
            validate_run(path)
        self.run_record["unexpected"] = True
        with self.assertRaisesRegex(ValueError, "unknown fields"):
            self.validate_current_run()

    def test_cli_inventory_pass_core_blocked_and_partial_blocked(self):
        script = Path(__file__).resolve().parents[1] / "validate.py"
        command = [sys.executable, str(script), "manifest", str(self.root / "manifest.json")]
        passed = subprocess.run(command, capture_output=True, text=True, check=False)
        self.assertEqual(passed.returncode, 0, passed.stderr)
        blocked = subprocess.run(command + ["--require-core"], capture_output=True, text=True, check=False)
        self.assertEqual(blocked.returncode, 2)
        self.assertEqual(json.loads(blocked.stderr)["status"], "BLOCKED")
        self.run_record["attempts"] = []
        blocked = subprocess.run([sys.executable, str(script), "run", str(self.write("run.json", self.run_record))],
                                 capture_output=True, text=True, check=False)
        self.assertEqual(blocked.returncode, 2)


if __name__ == "__main__":
    unittest.main()
