"""Deterministic Bubble Zoom evidence accounting and baseline comparisons."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import sys
from collections import Counter
from pathlib import Path


def canonical_json(value: object) -> str:
    return json.dumps(value, indent=2, sort_keys=True, allow_nan=False) + "\n"


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def annotation_digest(manifest: dict) -> str:
    identities = sorted(
        (sample["sample_id"], sample["annotation"]["sha256"])
        for sample in manifest["samples"]
    )
    payload = json.dumps(identities, separators=(",", ":"), ensure_ascii=True)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def percentile(values: list[int], fraction: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    position = (len(ordered) - 1) * fraction
    lower = math.floor(position)
    upper = math.ceil(position)
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower)


def summarize_attempts(run: dict) -> dict:
    scheduled = {item["attempt_id"]: item for item in run["schedule"]}
    attempts = {item["attempt_id"]: item for item in run["attempts"]}
    missing = sorted(set(scheduled) - set(attempts))
    extra = sorted(set(attempts) - set(scheduled))
    outcomes = Counter()
    modes = {}
    robustness = []
    for attempt_id, intent in sorted(scheduled.items()):
        attempt = attempts.get(attempt_id)
        if intent["expected"] != "valid":
            robustness.append({"attempt_id": attempt_id, "outcome": attempt})
            continue
        mode = intent["mode"]
        bucket = modes.setdefault(mode, {
            "scheduled": 0, "success": 0, "detector_failed": 0,
            "invalid_evidence": 0, "missing": 0, "durations_ns": [],
            "failed_attempts": [], "memory_observations": [],
        })
        bucket["scheduled"] += 1
        status = attempt["status"] if attempt else "missing"
        if status not in {"success", "detector_failed", "invalid_evidence", "missing"}:
            raise ValueError(f"Unknown attempt status: {status}")
        outcomes[status] += 1
        bucket[status] += 1
        if status == "success" and "duration_ns" in attempt:
            bucket["durations_ns"].append(attempt["duration_ns"])
        if status == "detector_failed":
            bucket["failed_attempts"].append({
                "attempt_id": attempt_id,
                "failure_kind": attempt.get("failure_kind"),
                "duration_ns": attempt.get("duration_ns"),
                "watchdog_ns": attempt.get("runtime", {}).get("watchdog_ns"),
            })
        if attempt and "memory" in attempt:
            bucket["memory_observations"].append({
                "attempt_id": attempt_id, "observation": attempt["memory"],
            })
    for bucket in modes.values():
        durations = bucket.pop("durations_ns")
        bucket["latency"] = {
            "scope": "completed successful attempts only; modes never pooled",
            "count": len(durations),
            "p50_ns": percentile(durations, 0.50),
            "p95_ns": percentile(durations, 0.95),
            "quantile_method": "linear interpolation at (n-1)*q",
        }
        bucket["failure_rate"] = bucket["detector_failed"] / bucket["scheduled"]
    invalid = bool(missing or extra or outcomes["invalid_evidence"])
    return {
        "scheduled_attempts": len(scheduled),
        "recorded_attempts": len(attempts),
        "missing_attempt_ids": missing,
        "unexpected_attempt_ids": extra,
        "evidence_status": "BLOCKED" if invalid else "COMPLETE",
        "full_corpus_fp_comparison": "BLOCKED" if invalid or outcomes["detector_failed"] else "AVAILABLE",
        "modes": modes,
        "robustness_attempts": robustness,
    }


def aggregate_quality(pages: list[dict], *, mask_mode: bool) -> dict:
    buckets = {}
    for page in pages:
        bucket = buckets.setdefault(page["mode"], {
            "gt_count": 0, "tp": 0, "fn": 0, "fp": 0,
            "successful_pages": 0, "failed_pages": 0, "matched_iou_sum": 0.0,
        })
        bucket["gt_count"] += page["gt_count"]
        bucket["tp"] += page["tp"]
        bucket["fn"] += page["fn"]
        bucket["matched_iou_sum"] += page["matched_iou_sum"]
        if page["status"] == "success":
            bucket["successful_pages"] += 1
            bucket["fp"] += page["fp"]
        else:
            bucket["failed_pages"] += 1
    for bucket in buckets.values():
        gt_count = bucket["gt_count"]
        bucket["recall"] = bucket["tp"] / gt_count if gt_count else None
        successful = bucket["successful_pages"]
        bucket["fp_per_successful_page"] = bucket["fp"] / successful if successful else None
        bucket["full_corpus_fp_comparison"] = "BLOCKED" if bucket["failed_pages"] else "AVAILABLE"
        bucket["failure_inclusive_mask_quality"] = (
            bucket["matched_iou_sum"] / gt_count if mask_mode and gt_count else None
        )
        bucket["matched_mean_mask_iou"] = (
            bucket["matched_iou_sum"] / bucket["tp"] if mask_mode and bucket["tp"] else None
        )
    return buckets


def measurement_conditions(run: dict) -> list[dict]:
    attempts = {attempt["attempt_id"]: attempt for attempt in run["attempts"]}
    sessions = {}
    conditions = []
    for intent in run["schedule"]:
        attempt = attempts[intent["attempt_id"]]
        runtime = attempt.get("runtime", {})
        process = attempt.get("process", {})
        identity = (process.get("pid"), process.get("start_id"))
        session = None
        if all(value is not None for value in identity):
            session = sessions.setdefault(identity, len(sessions))
        conditions.append({
            "sample_id": intent["sample_id"], "mode": intent["mode"],
            "timing_scope": runtime.get("timing_scope", "UNSPECIFIED"),
            "watchdog_ns": runtime.get("watchdog_ns"),
            "process_fresh": process.get("fresh"), "session": session,
            "memory_recorded": "memory" in attempt,
        })
    return conditions


def _score_success(
    annotation: dict, annotation_root: Path, detections: list[dict], run_root: Path,
    *, mode: str, unit: str, threshold: float,
) -> dict:
    from .metrics import evaluate_page
    from .png import read_alpha
    from .projection import project_alpha

    width, height = annotation["width"], annotation["height"]

    def load_full_page_mask(relative_path: str):
        mask_width, mask_height, alpha = read_alpha(annotation_root / relative_path)
        if (mask_width, mask_height) != (width, height):
            raise ValueError("Annotation mask dimensions do not match the canonical page")
        mask = project_alpha(alpha, mask_width, mask_height, [0, 0, 1, 1], width, height)
        if not any(mask.data):
            raise ValueError("An annotated positive/ignored region cannot have an empty mask")
        return mask

    targets = []
    for instance in annotation["instances"]:
        target = {"id": instance["id"], "box": instance["box"]}
        if mode == "mask":
            target["mask"] = load_full_page_mask(instance["mask_path"])
        targets.append(target)
    predictions = []
    for detection in detections:
        if not detection.get("mask_path") or not detection.get("mask_sha256"):
            raise ValueError("Public detector evidence requires a checksummed PNG for every prediction")
        mask_width, mask_height, alpha = read_alpha(run_root / detection["mask_path"])
        left, top, right, bottom = detection["bounds"]
        prediction = {
            "id": detection["prediction_id"],
            "box": [left * width, top * height, right * width, bottom * height],
        }
        if mode == "mask":
            prediction["mask"] = project_alpha(
                alpha, mask_width, mask_height, detection["bounds"], width, height,
            )
        predictions.append(prediction)
    ignored = [load_full_page_mask(region["mask_path"]) for region in annotation["ignore_regions"]]
    scores = evaluate_page(
        targets, predictions, width, height, mode=mode, threshold=threshold,
        unit=unit, groups=annotation["groups"], ignore_regions=ignored,
    )
    return {
        "tp": scores["tp"], "fn": scores["fn"], "fp": scores["fp"],
        "matched_iou_sum": float(scores["iou_sum"]),
        "matches": [
            {**match, "iou": float(match["iou"])} for match in scores["matches"]
        ],
        "ignored_prediction_ids": scores["ignored_prediction_ids"],
    }


def build_report(
    manifest_path: Path, run_path: Path, *, mode: str = "mask",
    unit: str = "instance", threshold: float = 0.5,
    policy_path: Path | None = None,
) -> dict:
    from .validate import validate_annotation, validate_manifest, validate_run

    if mode not in {"mask", "box"} or unit not in {"instance", "group"}:
        raise ValueError("Unsupported evaluation mode/unit")
    if threshold not in {0.5, 0.75}:
        raise ValueError("Use a frozen threshold of 0.5 or 0.75")
    manifest_path = Path(manifest_path).resolve()
    run_path = Path(run_path).resolve()
    manifest = validate_manifest(manifest_path)
    run = validate_run(run_path, manifest_path=manifest_path)
    provenance = run["provenance"]
    if provenance["corpus_sha256"] != sha256_file(manifest_path):
        raise ValueError("Run corpus hash does not match the supplied manifest")
    if provenance["annotations_sha256"] != annotation_digest(manifest):
        raise ValueError("Run annotation inventory hash does not match")
    repository = Path(__file__).resolve().parents[2]
    policy_path = Path(policy_path) if policy_path else repository / "qa/bubble/acceptance-policy.json"
    if provenance["policy_sha256"] != sha256_file(policy_path):
        raise ValueError("Run acceptance policy hash does not match")
    model_path = repository / "app/src/main/assets/models/bubble_segment.onnx"
    if provenance["model_sha256"] != sha256_file(model_path):
        raise ValueError("Run model differs from the unchanged bundled model")
    samples = {sample["sample_id"]: sample for sample in manifest["samples"]}
    annotations = {}
    for sample_id, sample in samples.items():
        annotation_path = manifest_path.parent / sample["annotation"]["path"]
        annotations[sample_id] = (
            validate_annotation(annotation_path, sample=sample), annotation_path.parent,
        )
    attempts = {attempt["attempt_id"]: attempt for attempt in run["attempts"]}
    pages = []
    for intent in run["schedule"]:
        if intent["expected"] != "valid":
            continue
        annotation, annotation_root = annotations[intent["sample_id"]]
        required = {"mask": {"mask"}, "box": {"box", "mask"}}[mode]
        if annotation["completeness"] not in required:
            raise ValueError(f"Insufficient annotation completeness for {intent['sample_id']}")
        targets = annotation["instances"] if unit == "instance" else annotation["groups"]
        if unit == "group":
            members = [member for group in targets for member in group["members"]]
            if sorted(members) != sorted(instance["id"] for instance in annotation["instances"]):
                raise ValueError("Group evaluation requires an explicit partition of all instances")
        page = {
            "attempt_id": intent["attempt_id"], "sample_id": intent["sample_id"],
            "mode": intent["mode"], "status": attempts[intent["attempt_id"]]["status"],
            "gt_count": len(targets),
        }
        attempt = attempts[intent["attempt_id"]]
        page.update(_score_success(
            annotation, annotation_root, attempt["detections"], run_path.parent,
            mode=mode, unit=unit, threshold=threshold,
        ))
        if attempt["status"] == "detector_failed":
            page["fp"] = None
        pages.append(page)
    accounting = summarize_attempts(run)
    if not pages:
        raise ValueError("No valid-page attempts are available for detection scoring")
    admission = manifest["_validation"]
    return {
        "schema_version": "1.0", "status": accounting["evidence_status"],
        "work_title": "Bubble Zoom V35.1 testing foundation",
        "provenance": provenance,
        "dataset": {
            "dataset_id": manifest["dataset_id"], "revision": manifest["revision"],
            "kind": manifest["kind"], "corpus_sha256": provenance["corpus_sha256"],
            "annotations_sha256": provenance["annotations_sha256"],
        },
        "evaluation": {
            "scorer_version": "1.0", "mode": mode, "unit": unit,
            "threshold": threshold, "alpha_threshold": 128,
            "projection": "full-page pixel centers, nearest neighbor",
        },
        "measurement_plan": [
            [intent["sample_id"], intent["mode"], intent["expected"]]
            for intent in run["schedule"]
        ],
        "measurement_conditions": measurement_conditions(run),
        "real_baseline_status": (
            "BLOCKED" if admission["baseline_status"] != "READY" or provenance["runtime_kind"] == "host"
            else "EMULATOR_ONLY" if provenance["runtime_kind"] == "emulator"
            else "RECORDED_NOT_APPROVED"
        ),
        "dataset_admission": admission,
        "product_status": "FAIL" if any(page["status"] == "detector_failed" for page in pages) else "NOT_ASSESSED",
        "accounting": accounting, "pages": pages,
        "metrics_by_mode": aggregate_quality(pages, mask_mode=mode == "mask"),
    }


def compare_reports(baseline: dict, candidate: dict) -> dict:
    from .validate import compare_provenance

    compare_provenance(baseline["provenance"], candidate["provenance"])
    for field in ("evaluation", "dataset", "measurement_plan", "measurement_conditions"):
        if baseline[field] != candidate[field]:
            raise ValueError(f"Incompatible {field}")
    if baseline["accounting"]["evidence_status"] != "COMPLETE" or candidate["accounting"]["evidence_status"] != "COMPLETE":
        raise ValueError("Incomplete evidence cannot establish a baseline comparison")
    if set(baseline["accounting"]["modes"]) != set(candidate["accounting"]["modes"]):
        raise ValueError("Incompatible measurement modes")
    deltas = {}
    for mode in baseline["accounting"]["modes"]:
        original = baseline["accounting"]["modes"][mode]
        updated = candidate["accounting"]["modes"][mode]
        if original["scheduled"] != updated["scheduled"]:
            raise ValueError("Incompatible attempt counts")
        mode_deltas = {"failure_rate_delta": updated["failure_rate"] - original["failure_rate"]}
        for name in ("p50_ns", "p95_ns"):
            before = original["latency"][name]
            after = updated["latency"][name]
            mode_deltas[name + "_delta"] = None if before is None or after is None else after - before
        original_quality = baseline["metrics_by_mode"][mode]
        updated_quality = candidate["metrics_by_mode"][mode]
        for name in ("recall", "failure_inclusive_mask_quality", "matched_mean_mask_iou", "fp_per_successful_page"):
            before = original_quality[name]
            after = updated_quality[name]
            blocked_fp = name == "fp_per_successful_page" and any(
                report["accounting"]["full_corpus_fp_comparison"] != "AVAILABLE"
                for report in (baseline, candidate)
            )
            mode_deltas[name + "_delta"] = None if before is None or after is None or blocked_fp else after - before
        deltas[mode] = mode_deltas
    return {
        "status": "DESCRIPTIVE_ONLY",
        "quality_improvement_claim": "BLOCKED until acceptance thresholds and paired evidence are reviewed",
        "full_corpus_fp_comparison": "AVAILABLE" if all(
            report["accounting"]["full_corpus_fp_comparison"] == "AVAILABLE"
            for report in (baseline, candidate)
        ) else "BLOCKED",
        "mode_deltas": deltas,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    evaluate = commands.add_parser("evaluate")
    evaluate.add_argument("--manifest", type=Path, required=True)
    evaluate.add_argument("--run", type=Path, required=True)
    evaluate.add_argument("--policy", type=Path)
    evaluate.add_argument("--mode", choices=("mask", "box"), default="mask")
    evaluate.add_argument("--unit", choices=("instance", "group"), default="instance")
    evaluate.add_argument("--threshold", choices=("0.5", "0.75"), default="0.5")
    compare = commands.add_parser("compare")
    compare.add_argument("--baseline", type=Path, required=True)
    compare.add_argument("--candidate", type=Path, required=True)
    evaluate.add_argument("--output", type=Path)
    compare.add_argument("--output", type=Path)
    arguments = parser.parse_args()
    try:
        if arguments.command == "compare":
            result = compare_reports(
                json.loads(arguments.baseline.read_text(encoding="utf-8")),
                json.loads(arguments.candidate.read_text(encoding="utf-8")),
            )
        else:
            result = build_report(
                arguments.manifest, arguments.run, mode=arguments.mode,
                unit=arguments.unit, threshold=float(arguments.threshold),
                policy_path=arguments.policy,
            )
        output = canonical_json(result)
        if arguments.output:
            arguments.output.write_text(output, encoding="utf-8")
        else:
            print(output, end="")
        return 2 if result.get("status") == "BLOCKED" else 0
    except (ValueError, OSError, KeyError, TypeError) as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
