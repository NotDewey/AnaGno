import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from tools.bubble_eval.report import aggregate_quality, annotation_digest, build_report, canonical_json, compare_reports, measurement_conditions, percentile, sha256_file, summarize_attempts


class AttemptAccountingTests(unittest.TestCase):
    def make_run(self):
        return {
            "schedule": [
                {"attempt_id": "cold", "sample_id": "page", "mode": "cold_inference", "expected": "valid"},
                {"attempt_id": "warm", "sample_id": "page", "mode": "warm_inference", "expected": "valid"},
            ],
            "attempts": [
                {"attempt_id": "cold", "sample_id": "page", "mode": "cold_inference", "status": "success", "duration_ns": 1000},
                {"attempt_id": "warm", "sample_id": "page", "mode": "warm_inference", "status": "success", "duration_ns": 100},
            ],
        }

    def test_cold_and_warm_are_not_pooled(self):
        summary = summarize_attempts(self.make_run())
        self.assertEqual(summary["modes"]["cold_inference"]["latency"]["p50_ns"], 1000)
        self.assertEqual(summary["modes"]["warm_inference"]["latency"]["p50_ns"], 100)
        self.assertNotIn("cache_hit", summary["modes"])

    def test_missing_attempt_blocks_complete_evidence(self):
        run = self.make_run()
        run["attempts"].pop()
        summary = summarize_attempts(run)
        self.assertEqual(summary["evidence_status"], "BLOCKED")
        self.assertEqual(summary["missing_attempt_ids"], ["warm"])
        self.assertEqual(summary["modes"]["warm_inference"]["missing"], 1)

    def test_failure_is_retained_without_zero_latency(self):
        run = self.make_run()
        run["attempts"][1].update(status="detector_failed", failure_kind="timeout")
        summary = summarize_attempts(run)
        warm = summary["modes"]["warm_inference"]
        self.assertEqual(warm["failure_rate"], 1)
        self.assertIsNone(warm["latency"]["p50_ns"])
        self.assertEqual(summary["full_corpus_fp_comparison"], "BLOCKED")
        self.assertEqual(summary["evidence_status"], "COMPLETE")

    def test_invalid_artifact_is_not_an_empty_detection(self):
        run = self.make_run()
        run["attempts"][0]["status"] = "invalid_evidence"
        summary = summarize_attempts(run)
        self.assertEqual(summary["evidence_status"], "BLOCKED")
        self.assertEqual(summary["modes"]["cold_inference"]["success"], 0)

    def test_robustness_attempts_do_not_change_quality_denominators(self):
        run = self.make_run()
        run["schedule"][0]["expected"] = "invalid_input"
        summary = summarize_attempts(run)
        self.assertNotIn("cold_inference", summary["modes"])
        self.assertEqual(len(summary["robustness_attempts"]), 1)

    def test_summary_is_deterministic_and_does_not_mutate_records(self):
        run = self.make_run()
        original = copy.deepcopy(run)
        self.assertEqual(canonical_json(summarize_attempts(run)), canonical_json(summarize_attempts(run)))
        self.assertEqual(run, original)

    def test_percentile_is_explicit_and_empty_is_unavailable(self):
        self.assertIsNone(percentile([], 0.95))
        self.assertEqual(percentile([10, 20, 30], 0.5), 20)
        self.assertEqual(percentile([10, 20, 30], 0.95), 29)


class QualityAccountingTests(unittest.TestCase):
    def test_failed_valid_page_contributes_misses_without_zero_fp_claim(self):
        pages = [
            {"mode": "warm_inference", "status": "success", "gt_count": 1,
             "tp": 1, "fn": 0, "fp": 2, "matched_iou_sum": 0.75},
            {"mode": "warm_inference", "status": "detector_failed", "gt_count": 3,
             "tp": 0, "fn": 3, "fp": None, "matched_iou_sum": 0},
        ]
        quality = aggregate_quality(pages, mask_mode=True)["warm_inference"]
        self.assertEqual(quality["recall"], 0.25)
        self.assertEqual(quality["failure_inclusive_mask_quality"], 0.1875)
        self.assertEqual(quality["fp_per_successful_page"], 2)
        self.assertEqual(quality["full_corpus_fp_comparison"], "BLOCKED")

    def test_negative_page_is_in_fp_denominator_but_not_recall(self):
        quality = aggregate_quality([
            {"mode": "cold_inference", "status": "success", "gt_count": 0,
             "tp": 0, "fn": 0, "fp": 1, "matched_iou_sum": 0},
        ], mask_mode=True)["cold_inference"]
        self.assertIsNone(quality["recall"])
        self.assertIsNone(quality["failure_inclusive_mask_quality"])
        self.assertEqual(quality["fp_per_successful_page"], 1)

    def test_box_results_never_claim_mask_iou(self):
        quality = aggregate_quality([
            {"mode": "warm_inference", "status": "success", "gt_count": 1,
             "tp": 1, "fn": 0, "fp": 0, "matched_iou_sum": 1},
        ], mask_mode=False)["warm_inference"]
        self.assertIsNone(quality["matched_mean_mask_iou"])
        self.assertIsNone(quality["failure_inclusive_mask_quality"])


class BaselineComparisonTests(unittest.TestCase):
    def make_report(self):
        run = AttemptAccountingTests().make_run()
        return {
            "provenance": {
                "production_revision": "baseline", "harness_revision": "sha256:harness",
                "model_sha256": "a" * 64, "corpus_sha256": "b" * 64,
                "annotations_sha256": "c" * 64, "policy_sha256": "d" * 64,
                "device_fingerprint": "test-device", "device_model": "synthetic-device",
                "runtime_kind": "host", "build_variant": "bubbleValidation",
                "contract": "v35_2_",
            },
            "evaluation": {"mode": "mask", "unit": "instance", "threshold": 0.5},
            "dataset": {"kind": "synthetic"},
            "measurement_plan": [["page", "cold_inference", "valid"], ["page", "warm_inference", "valid"]],
            "measurement_conditions": measurement_conditions(run),
            "accounting": summarize_attempts(run),
            "metrics_by_mode": {
                mode: {"recall": 1, "failure_inclusive_mask_quality": 1,
                       "matched_mean_mask_iou": 1, "fp_per_successful_page": 0}
                for mode in ("cold_inference", "warm_inference")
            },
        }

    def test_production_revision_can_differ_but_harness_cannot(self):
        baseline = self.make_report()
        candidate = copy.deepcopy(baseline)
        candidate["provenance"]["production_revision"] = "candidate"
        self.assertEqual(compare_reports(baseline, candidate)["mode_deltas"]["cold_inference"]["recall_delta"], 0)
        candidate["provenance"]["harness_revision"] = "different"
        with self.assertRaises(ValueError):
            compare_reports(baseline, candidate)

    def test_same_attempt_count_with_different_page_plan_is_incompatible(self):
        baseline = self.make_report()
        candidate = copy.deepcopy(baseline)
        candidate["measurement_plan"][0][0] = "another-page"
        with self.assertRaises(ValueError):
            compare_reports(baseline, candidate)

    def test_failed_page_blocks_favorable_fp_delta(self):
        baseline = self.make_report()
        candidate = copy.deepcopy(baseline)
        candidate["accounting"]["full_corpus_fp_comparison"] = "BLOCKED"
        candidate["metrics_by_mode"]["warm_inference"]["fp_per_successful_page"] = 0
        result = compare_reports(baseline, candidate)
        self.assertEqual(result["full_corpus_fp_comparison"], "BLOCKED")
        self.assertIsNone(result["mode_deltas"]["warm_inference"]["fp_per_successful_page_delta"])

    def test_invalid_evidence_cannot_be_compared(self):
        baseline = self.make_report()
        candidate = copy.deepcopy(baseline)
        candidate["accounting"]["evidence_status"] = "BLOCKED"
        with self.assertRaises(ValueError):
            compare_reports(baseline, candidate)

    def test_different_timer_scope_and_watchdog_are_incompatible(self):
        baseline = self.make_report()
        candidate = copy.deepcopy(baseline)
        candidate["measurement_conditions"][0]["timing_scope"] = "includes application startup"
        candidate["measurement_conditions"][0]["watchdog_ns"] = 123
        with self.assertRaisesRegex(ValueError, "measurement_conditions"):
            compare_reports(baseline, candidate)

    def test_process_ids_may_change_but_session_pattern_must_match(self):
        original = AttemptAccountingTests().make_run()
        for attempt in original["attempts"]:
            attempt["process"] = {"pid": 100, "start_id": "first", "fresh": False}
        repeated = copy.deepcopy(original)
        for attempt in repeated["attempts"]:
            attempt["process"] = {"pid": 200, "start_id": "second", "fresh": False}
        self.assertEqual(measurement_conditions(original), measurement_conditions(repeated))
        repeated["attempts"][1]["process"]["start_id"] = "another-process"
        self.assertNotEqual(measurement_conditions(original), measurement_conditions(repeated))


class CapturedOutputIntegrationTests(unittest.TestCase):
    def setUp(self):
        from tools.bubble_eval.png import write_gray, write_rgba

        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        write_gray(self.root / "page.png", 4, 4, bytes([255] * 16))
        write_gray(self.root / "truth.png", 4, 4, bytes([
            0, 0, 0, 0, 0, 255, 255, 0, 0, 255, 255, 0, 0, 0, 0, 0,
        ]))
        write_rgba(self.root / "prediction.png", 2, 2, bytes([128, 64, 32, 255] * 4))
        (self.root / "rights-evidence.txt").write_text("Original generated geometric unit-test fixture.\n", encoding="utf-8")
        self.write_json("rights.json", {"schema_version": "1.0", "records": [{
            "rights_id": "original", "status": "approved", "license": "original-test-fixture",
            "holder": "test generator", "allowed_uses": ["evaluation"],
            "evidence": {"path": "rights-evidence.txt", "sha256": sha256_file(self.root / "rights-evidence.txt")},
        }]})
        self.write_json("annotation.json", {
            "schema_version": "1.0", "sample_id": "generated", "width": 4, "height": 4,
            "completeness": "mask", "instances": [{
                "id": "body", "box": [1, 1, 3, 3], "mask_path": "truth.png",
                "mask_sha256": sha256_file(self.root / "truth.png"),
            }], "groups": [{"id": "reading-target", "members": ["body"]}], "ignore_regions": [],
        })
        self.manifest = {
            "schema_version": "1.0", "dataset_id": "generated-unit-test", "revision": "1",
            "kind": "synthetic", "samples": [{
                "sample_id": "generated", "source_group": "generated-unit-test", "split": "development",
                "tags": ["rectangle"], "rights_id": "original",
                "asset": {"path": "page.png", "sha256": sha256_file(self.root / "page.png"), "width": 4, "height": 4},
                "annotation": {"path": "annotation.json", "sha256": sha256_file(self.root / "annotation.json"), "completeness": "mask"},
            }],
        }
        self.write_json("manifest.json", self.manifest)
        repository = Path(__file__).resolve().parents[3]
        self.repository = repository
        self.policy = repository / "qa/bubble/acceptance-policy.json"
        self.run = {
            "schema_version": "1.0", "run_id": "unit-test-output-only",
            "provenance": {
                "production_revision": "test", "harness_revision": "sha256:test",
                "model_sha256": sha256_file(repository / "app/src/main/assets/models/bubble_segment.onnx"),
                "corpus_sha256": sha256_file(self.root / "manifest.json"),
                "annotations_sha256": annotation_digest(self.manifest), "policy_sha256": sha256_file(self.policy),
                "device_fingerprint": "generated-test", "device_model": "host-fixture",
                "runtime_kind": "host", "build_variant": "bubbleValidation", "contract": "v35_2_",
            },
            "schedule": [{"attempt_id": "first", "sample_id": "generated", "mode": "warm_inference", "expected": "valid"}],
            "attempts": [{
                "attempt_id": "first", "sample_id": "generated", "mode": "warm_inference", "status": "success",
                "duration_ns": 123, "detections": [{
                    "prediction_id": "output-0", "bounds": [0.25, 0.25, 0.75, 0.75],
                    "mask_path": "prediction.png", "mask_sha256": sha256_file(self.root / "prediction.png"),
                }],
            }],
        }
        self.write_json("run.json", self.run)

    def write_json(self, name, record):
        (self.root / name).write_text(canonical_json(record), encoding="utf-8")

    def evaluate(self, **options):
        return build_report(self.root / "manifest.json", self.root / "run.json", policy_path=self.policy, **options)

    def test_actual_png_export_projection_and_scoring(self):
        report = self.evaluate()
        quality = report["metrics_by_mode"]["warm_inference"]
        self.assertEqual(quality["recall"], 1)
        self.assertEqual(quality["matched_mean_mask_iou"], 1)
        self.assertEqual(quality["fp_per_successful_page"], 0)
        self.assertEqual(report["real_baseline_status"], "BLOCKED")
        self.assertEqual(json.loads(canonical_json(report)), report)

    def test_valid_page_failure_is_in_end_to_end_quality(self):
        self.run["schedule"].append({"attempt_id": "second", "sample_id": "generated", "mode": "warm_inference", "expected": "valid"})
        self.run["attempts"].append({"attempt_id": "second", "sample_id": "generated", "mode": "warm_inference",
                                     "status": "detector_failed", "failure_kind": "timeout", "detections": []})
        self.write_json("run.json", self.run)
        report = self.evaluate()
        self.assertEqual(report["metrics_by_mode"]["warm_inference"]["recall"], 0.5)
        self.assertEqual(report["metrics_by_mode"]["warm_inference"]["failure_inclusive_mask_quality"], 0.5)
        self.assertEqual(report["product_status"], "FAIL")

    def test_corrupt_output_is_invalid_evidence_not_a_zero_score(self):
        (self.root / "prediction.png").write_bytes(b"broken")
        with self.assertRaises(ValueError):
            self.evaluate()

    def test_wrong_inventory_hash_is_blocked(self):
        self.run["provenance"]["corpus_sha256"] = "0" * 64
        self.write_json("run.json", self.run)
        with self.assertRaises(ValueError):
            self.evaluate()

    def test_empty_positive_annotation_is_not_scored_as_a_detector_miss(self):
        from tools.bubble_eval.png import write_gray

        write_gray(self.root / "truth.png", 4, 4, bytes(16))
        annotation = json.loads((self.root / "annotation.json").read_text(encoding="utf-8"))
        annotation["instances"][0]["mask_sha256"] = sha256_file(self.root / "truth.png")
        self.write_json("annotation.json", annotation)
        self.manifest["samples"][0]["annotation"]["sha256"] = sha256_file(self.root / "annotation.json")
        self.write_json("manifest.json", self.manifest)
        self.run["provenance"]["corpus_sha256"] = sha256_file(self.root / "manifest.json")
        self.run["provenance"]["annotations_sha256"] = annotation_digest(self.manifest)
        self.write_json("run.json", self.run)
        with self.assertRaisesRegex(ValueError, "empty mask"):
            self.evaluate()

    def test_groups_and_boxes_are_distinct_evaluations(self):
        report = self.evaluate(mode="box", unit="group")
        self.assertEqual(report["metrics_by_mode"]["warm_inference"]["recall"], 1)
        self.assertIsNone(report["metrics_by_mode"]["warm_inference"]["matched_mean_mask_iou"])

    def test_missing_explicit_groups_block_group_metrics(self):
        annotation = json.loads((self.root / "annotation.json").read_text(encoding="utf-8"))
        annotation["groups"] = []
        self.write_json("annotation.json", annotation)
        self.manifest["samples"][0]["annotation"]["sha256"] = sha256_file(self.root / "annotation.json")
        self.write_json("manifest.json", self.manifest)
        self.run["provenance"]["corpus_sha256"] = sha256_file(self.root / "manifest.json")
        self.run["provenance"]["annotations_sha256"] = annotation_digest(self.manifest)
        self.write_json("run.json", self.run)
        with self.assertRaisesRegex(ValueError, "partition"):
            self.evaluate(unit="group")

    def test_maskless_predictions_are_rejected_in_box_mode_too(self):
        detection = self.run["attempts"][0]["detections"][0]
        del detection["mask_path"]
        del detection["mask_sha256"]
        self.write_json("run.json", self.run)
        with self.assertRaises(ValueError):
            self.evaluate(mode="box")

    def test_evaluate_and_compare_commands_write_canonical_json(self):
        report_path = self.root / "report.json"
        evaluated = subprocess.run([
            sys.executable, "-m", "tools.bubble_eval.report", "evaluate",
            "--manifest", str(self.root / "manifest.json"), "--run", str(self.root / "run.json"),
            "--policy", str(self.policy), "--output", str(report_path),
        ], cwd=self.repository, capture_output=True, text=True, timeout=15)
        self.assertEqual(evaluated.returncode, 0, evaluated.stderr)
        report = json.loads(report_path.read_text(encoding="utf-8"))
        self.assertEqual(report["real_baseline_status"], "BLOCKED")
        self.assertEqual(report["metrics_by_mode"]["warm_inference"]["recall"], 1)
        compared = subprocess.run([
            sys.executable, "-m", "tools.bubble_eval.report", "compare",
            "--baseline", str(report_path), "--candidate", str(report_path),
        ], cwd=self.repository, capture_output=True, text=True, timeout=15)
        self.assertEqual(compared.returncode, 0, compared.stderr)
        self.assertEqual(json.loads(compared.stdout)["mode_deltas"]["warm_inference"]["recall_delta"], 0)


if __name__ == "__main__":
    unittest.main()
