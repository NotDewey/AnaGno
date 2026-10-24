"""Offline record validation for the V35.1 testing foundation (stdlib only).

Public functions raise ValueError on invalid or incomplete evidence. They return
copies of wire records; manifest/annotation copies include private `_validation`
metadata which is never part of a persisted wire record. No detector is invoked.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path, PurePosixPath
import re
import sys

VERSION = "1.0"
CONTRACT = "v35_2_"
SPLITS = {"development", "regression", "holdout"}
MODES = {"cold_inference", "warm_inference", "cache_hit"}
HASH = re.compile(r"[0-9a-f]{64}\Z")
PROVENANCE = (
    "production_revision", "harness_revision", "model_sha256", "corpus_sha256",
    "annotations_sha256", "policy_sha256", "device_fingerprint", "device_model",
    "runtime_kind", "build_variant", "contract",
)
COMPARABLE = tuple(key for key in PROVENANCE if key != "production_revision")
REQUIRED_TAGS = {"oval", "rectangle", "irregular", "tail", "small_text", "no_text",
                 "dense_panels", "negative", "false_positive_regions", "connected",
                 "rotated", "dark", "high_resolution"}


def _fail(message):
    raise ValueError(message)


def _object(value, name):
    if not isinstance(value, dict):
        _fail(f"{name}: expected object")
    return value


def _fields(value, allowed, name):
    _object(value, name)
    unexpected = set(value) - set(allowed.split())
    if unexpected:
        _fail(f"{name}: unknown fields {sorted(unexpected)}")


def _text(value, name):
    if not isinstance(value, str) or not value.strip():
        _fail(f"{name}: expected nonempty string")
    return value


def _integer(value, name, minimum=0):
    if type(value) is not int or value < minimum:
        _fail(f"{name}: expected integer >= {minimum}")
    return value


def _array(value, name):
    if not isinstance(value, list):
        _fail(f"{name}: expected array")
    return value


def _enum(value, allowed, name):
    if not isinstance(value, str) or value not in allowed:
        _fail(f"{name}: expected one of {sorted(allowed)}")
    return value


def _digest(value, name):
    if not isinstance(value, str) or not HASH.fullmatch(value):
        _fail(f"{name}: expected lowercase SHA-256")
    return value


def _pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            _fail(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _read(path):
    try:
        with Path(path).open(encoding="utf-8") as stream:
            record = json.load(stream, object_pairs_hook=_pairs,
                               parse_constant=lambda constant: _fail(f"nonfinite JSON: {constant}"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise ValueError(f"cannot read JSON {path}: {exc}") from exc
    _object(record, str(path))
    if record.get("schema_version") != VERSION:
        _fail(f"{path}: unsupported schema_version")
    return record


def _local(root, value):
    _text(value, "path")
    part = PurePosixPath(value)
    if (part.is_absolute() or "\\" in value or ":" in value or
            any(part in {"", ".", ".."} for part in value.split("/"))):
        _fail(f"unsafe relative path: {value}")
    root = Path(root).resolve()
    resolved = (root / value).resolve()
    if not resolved.is_relative_to(root):
        _fail(f"symlink escapes record directory: {value}")
    if not resolved.is_file():
        _fail(f"missing artifact: {value}")
    return resolved


def _artifact(root, path, digest):
    _digest(digest, "artifact sha256")
    resolved = _local(root, path)
    try:
        with resolved.open("rb") as stream:
            hasher = hashlib.sha256()
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                hasher.update(chunk)
            actual = hasher.hexdigest()
    except OSError as exc:
        raise ValueError(f"cannot read artifact {path}: {exc}") from exc
    if actual != digest:
        _fail(f"checksum mismatch: {path}")
    return resolved


def _box(value, width, height, *, normalized=False):
    _array(value, "bounds")
    if len(value) != 4:
        _fail("bounds: expected [left, top, right, bottom]")
    for coordinate in value:
        if normalized:
            if (type(coordinate) not in (int, float) or
                    (type(coordinate) is float and not math.isfinite(coordinate))):
                _fail("bounds: expected finite numbers")
        else:
            _integer(coordinate, "original pixel coordinate")
    left, top, right, bottom = value
    if not (0 <= left < right <= width and 0 <= top < bottom <= height):
        _fail("bounds outside image or empty/inverted")


def _unique(records, field, name):
    result = {}
    for record in _array(records, name):
        _object(record, name)
        key = _text(record.get(field), field)
        if key in result:
            _fail(f"duplicate {field}: {key}")
        result[key] = record
    return result


def validate_annotation(path, *, sample=None):
    """Validate original half-open pixel boxes and locally checksummed masks.

    Count annotations retain one instance ID per expected bubble; box and mask
    are optional there. Mask completeness requires box + mask on every instance.
    `sample`, when supplied, is a wire manifest sample and binds dimensions/ID.
    """
    record = _read(path)
    _fields(record, "schema_version sample_id width height completeness instances groups ignore_regions", "annotation")
    _text(record.get("sample_id"), "sample_id")
    width = _integer(record.get("width"), "width", 1)
    height = _integer(record.get("height"), "height", 1)
    completeness = _enum(record.get("completeness"), {"count", "box", "mask"}, "completeness")
    if sample is not None:
        if (record["sample_id"] != sample["sample_id"] or
                (width, height) != (sample["asset"]["width"], sample["asset"]["height"]) or
                completeness != sample["annotation"]["completeness"]):
            _fail("annotation does not match manifest sample/dimensions/completeness")
    instances = _unique(record.get("instances"), "id", "instances")
    for instance in instances.values():
        _fields(instance, "id box mask_path mask_sha256", "instance")
        if completeness in {"box", "mask"} or instance.get("box") is not None:
            _box(instance.get("box"), width, height)
        mask, digest = instance.get("mask_path"), instance.get("mask_sha256")
        if completeness == "mask" or mask is not None or digest is not None:
            _artifact(Path(path).parent, mask, digest)
    groups = _unique(record.get("groups"), "id", "groups")
    grouped = set()
    for group in groups.values():
        _fields(group, "id members", "group")
        members = _array(group.get("members"), "group members")
        if not members or any(not isinstance(member, str) or member not in instances for member in members):
            _fail("group references missing instances or is empty")
        if len(members) != len(set(members)):
            _fail("duplicate group member")
        if grouped.intersection(members):
            _fail("instance belongs to multiple groups")
        grouped.update(members)
    if groups and grouped != set(instances):
        _fail("groups must partition every annotated instance")
    for region in _unique(record.get("ignore_regions"), "id", "ignore_regions").values():
        _fields(region, "id mask_path mask_sha256 reason", "ignore region")
        _text(region.get("reason"), "ignore reason")
        _artifact(Path(path).parent, region.get("mask_path"), region.get("mask_sha256"))
    record["_validation"] = {"expected_count": len(instances), "completeness": completeness,
                             "groups_complete": grouped == set(instances)}
    return record


def _rights(path):
    record = _read(path)
    _fields(record, "schema_version records", "rights")
    records = _unique(record.get("records"), "rights_id", "rights records")
    for item in records.values():
        _fields(item, "rights_id status license holder allowed_uses evidence", "rights record")
        if item.get("status") != "approved":
            _fail("rights admission requires status=approved")
        for key in ("license", "holder"):
            _text(item.get(key), f"rights {key}")
        uses = _array(item.get("allowed_uses"), "allowed_uses")
        if ("evaluation" not in uses or any(not isinstance(use, str) or not use for use in uses)
                or len(uses) != len(set(uses))):
            _fail("rights must explicitly allow evaluation")
        evidence = _object(item.get("evidence"), "rights evidence")
        _fields(evidence, "path sha256", "rights evidence")
        _artifact(Path(path).parent, evidence.get("path"), evidence.get("sha256"))
    return records


def validate_manifest(path, *, rights_path=None, require_core=False):
    """Validate inventory and every referenced annotation/asset/rights artifact.

    Default rights file: rights.json beside the manifest. Empty inventories are
    legal; `_validation.baseline_status` stays BLOCKED until real core admission.
    No assets are copied or staged. Paths always resolve beside their own record.
    """
    record = _read(path)
    _fields(record, "schema_version dataset_id revision kind samples", "manifest")
    for key in ("dataset_id", "revision"):
        _text(record.get(key), key)
    kind = _enum(record.get("kind"), {"real", "synthetic"}, "kind")
    rights = _rights(rights_path if rights_path is not None else Path(path).parent / "rights.json")
    samples = _unique(record.get("samples"), "sample_id", "samples")
    families, assets, counts = {}, {}, {}
    observed_tags = set()
    coverage = {split: {"pages": 0, "instances": 0, "negatives": 0, "full_mask_pages": 0} for split in SPLITS}
    for sid, sample in samples.items():
        _fields(sample, "sample_id source_group split tags rights_id asset annotation", "sample")
        family = _text(sample.get("source_group"), "source_group")
        split = _enum(sample.get("split"), SPLITS, "split")
        if family in families and families[family] != split:
            _fail(f"source family crosses splits: {family}")
        families[family] = split
        tags = _array(sample.get("tags"), "tags")
        if any(not isinstance(tag, str) or not tag for tag in tags) or len(tags) != len(set(tags)):
            _fail("tags must be unique nonempty strings")
        observed_tags.update(tags)
        if _text(sample.get("rights_id"), "rights_id") not in rights:
            _fail(f"unadmitted rights for {sid}")
        asset = _object(sample.get("asset"), "asset")
        _fields(asset, "path sha256 width height", "asset")
        for dimension in ("width", "height"):
            _integer(asset.get(dimension), dimension, 1)
        _artifact(Path(path).parent, asset.get("path"), asset.get("sha256"))
        if asset["sha256"] in assets:
            _fail("duplicate page asset (including across source families)")
        assets[asset["sha256"]] = sid
        annotation = _object(sample.get("annotation"), "annotation")
        _fields(annotation, "path sha256 completeness", "annotation reference")
        annotation_path = _artifact(Path(path).parent, annotation.get("path"), annotation.get("sha256"))
        ann = validate_annotation(annotation_path, sample=sample)
        count = ann["_validation"]["expected_count"]
        counts[sid] = count
        split_counts = coverage[split]
        split_counts["pages"] += 1
        split_counts["instances"] += count
        split_counts["negatives"] += count == 0
        split_counts["full_mask_pages"] += ann["completeness"] == "mask"
    goals = {"development": (12, 30), "regression": (24, 90), "holdout": (12, 30)}
    gaps = []
    if kind != "real":
        gaps.append("synthetic evidence cannot establish real baseline")
    if REQUIRED_TAGS - observed_tags:
        gaps.append("missing categories: " + ", ".join(sorted(REQUIRED_TAGS - observed_tags)))
    for split, (pages, instances) in goals.items():
        split_counts = coverage[split]
        if (split_counts["pages"] != pages or split_counts["instances"] < instances or
                split_counts["negatives"] < 2 or split_counts["full_mask_pages"] != pages):
            gaps.append(f"{split}: requires {pages} full-mask pages, >= {instances} instances, >= 2 negatives")
    if require_core and gaps:
        _fail("core admission BLOCKED: " + "; ".join(gaps))
    record["_validation"] = {"inventory_status": "PASS", "baseline_status": "BLOCKED" if gaps else "READY",
                             "expected_counts": counts, "coverage": coverage, "gaps": gaps}
    return record


def _provenance(value):
    provenance = _object(value, "provenance")
    _fields(provenance, " ".join(PROVENANCE), "provenance")
    for key in PROVENANCE:
        _text(provenance.get(key), f"provenance.{key}")
    for key in ("model_sha256", "corpus_sha256", "annotations_sha256", "policy_sha256"):
        _digest(provenance[key], key)
    _enum(provenance["runtime_kind"], {"host", "emulator", "physical"}, "runtime_kind")
    if provenance["contract"] != CONTRACT:
        _fail(f"contract must be {CONTRACT}")
    return provenance


def compare_provenance(base, candidate):
    """Accept provenance objects or full run records; return None if comparable.

    Production revision may differ. Model, harness, policy, corpus, annotations,
    runtime, device and build variant must be identical; compare per-device.
    """
    _object(base, "baseline")
    _object(candidate, "candidate")
    baseline = _provenance(base.get("provenance", base))
    challenger = _provenance(candidate.get("provenance", candidate))
    differences = [key for key in COMPARABLE if baseline[key] != challenger[key]]
    if differences:
        _fail("incompatible provenance: " + ", ".join(differences))


def validate_run(path, *, manifest_path=None, rights_path=None, require_core=False):
    """Validate complete schedule, statuses, timing, and local prediction masks.

    Standalone validation cannot join sample IDs to a dataset; the reporter MUST
    do that, or callers may supply manifest_path for this additional check.
    Schedule-before-launch order requires external capture evidence; JSON alone
    cannot prove chronology. Invalid/partial evidence raises ValueError.
    """
    record = _read(path)
    _fields(record, "schema_version run_id provenance schedule attempts", "run")
    _text(record.get("run_id"), "run_id")
    _provenance(record.get("provenance"))
    schedule = _unique(record.get("schedule"), "attempt_id", "schedule")
    attempts = _unique(record.get("attempts"), "attempt_id", "attempts")
    if not schedule:
        _fail("empty run schedule is not evidence")
    if schedule.keys() != attempts.keys():
        _fail("missing or unscheduled attempts; partial export BLOCKED")
    manifest = validate_manifest(manifest_path, rights_path=rights_path, require_core=require_core) if manifest_path else None
    sample_ids = {sample["sample_id"] for sample in manifest["samples"]} if manifest else None
    for aid, planned in schedule.items():
        _fields(planned, "attempt_id sample_id mode expected", "scheduled attempt")
        _text(planned.get("sample_id"), "scheduled sample_id")
        if sample_ids is not None and planned["sample_id"] not in sample_ids:
            _fail("scheduled sample not in manifest")
        _enum(planned.get("mode"), MODES, "mode")
        expected = _enum(planned.get("expected"), {"valid", "invalid_input"}, "expected")
        attempt = attempts[aid]
        _fields(attempt, "attempt_id sample_id mode status duration_ns detections failure_kind cache_hit_evidence memory process constructor_duration_ns runtime", "attempt")
        if any(attempt.get(key) != planned[key] for key in ("sample_id", "mode")):
            _fail("attempt differs from schedule sample/mode")
        status = _enum(attempt.get("status"), {"success", "detector_failed", "invalid_evidence"}, "status")
        if status == "invalid_evidence":
            _text(attempt.get("failure_kind"), "failure_kind")
            _fail("invalid evidence blocks comparison")
        if status == "success" or "duration_ns" in attempt:
            _integer(attempt.get("duration_ns"), "duration_ns")
        if "constructor_duration_ns" in attempt:
            _integer(attempt["constructor_duration_ns"], "constructor_duration_ns")
        if "memory" in attempt:
            memory = attempt["memory"]
            _fields(memory, "physical_pss_bytes java_allocated_bytes native_allocated_bytes java_peak_allocated_bytes native_peak_allocated_bytes sampling_interval_ns", "memory")
            for key, value in memory.items():
                _integer(value, f"memory.{key}")
        if "process" in attempt:
            process = attempt["process"]
            _fields(process, "pid start_id fresh", "process")
            if "pid" in process:
                _integer(process["pid"], "process.pid", 1)
            if "start_id" in process:
                _text(process["start_id"], "process.start_id")
            if "fresh" in process and type(process["fresh"]) is not bool:
                _fail("process.fresh: expected boolean")
        if "runtime" in attempt:
            runtime = attempt["runtime"]
            _fields(runtime, "timing_scope watchdog_ns thermal_state", "runtime")
            for key in ("timing_scope", "thermal_state"):
                if key in runtime:
                    _text(runtime[key], f"runtime.{key}")
            if "watchdog_ns" in runtime:
                _integer(runtime["watchdog_ns"], "runtime.watchdog_ns", 1)
        detections = _unique(attempt.get("detections"), "prediction_id", "detections")
        if status == "detector_failed":
            _text(attempt.get("failure_kind"), "failure_kind")
            if detections:
                _fail("failed detector cannot carry successful detections")
        elif "failure_kind" in attempt:
            _fail("successful attempt cannot have failure_kind")
        if expected == "invalid_input" and status == "success":
            _fail("invalid input cannot be a successful valid page")
        if planned["mode"] == "cache_hit" and status == "success":
            evidence = _object(attempt.get("cache_hit_evidence"), "cache_hit_evidence")
            _fields(evidence, "cache_key inference_invoked path sha256", "cache_hit_evidence")
            if evidence.get("inference_invoked") is not False:
                _fail("cache hit requires inference_invoked=false")
            _text(evidence.get("cache_key"), "cache_key")
            _artifact(Path(path).parent, evidence.get("path"), evidence.get("sha256"))
        elif "cache_hit_evidence" in attempt:
            _fail("cache evidence only allowed for successful cache_hit")
        for prediction in detections.values():
            _box(prediction.get("bounds"), 1, 1, normalized=True)
            mask, digest = prediction.get("mask_path"), prediction.get("mask_sha256")
            _artifact(Path(path).parent, mask, digest)
            for key in ("id", "comicId", "pageIndex", "order"):
                if key in prediction:
                    _integer(prediction[key], key)
            if "text" in prediction and not isinstance(prediction["text"], str):
                _fail("text: expected string")
            if "isManual" in prediction and type(prediction["isManual"]) is not bool:
                _fail("isManual: expected boolean")
            for key in ("confidence", "textHeightRatio"):
                if key in prediction:
                    number = prediction[key]
                    if type(number) not in (int, float) or not 0 <= number <= 1 or not math.isfinite(number):
                        _fail(f"{key} must be finite in [0,1]")
    return record


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("manifest", "annotation", "run"):
        command_parser = sub.add_parser(name)
        command_parser.add_argument("path", type=Path)
        if name in {"manifest", "run"}:
            command_parser.add_argument("--rights", type=Path)
            command_parser.add_argument("--require-core", action="store_true")
        if name == "run":
            command_parser.add_argument("--manifest", type=Path)
    command_parser = sub.add_parser("compare-provenance")
    command_parser.add_argument("base", type=Path)
    command_parser.add_argument("candidate", type=Path)
    args = parser.parse_args(argv)
    try:
        if args.command == "manifest":
            result = validate_manifest(args.path, rights_path=args.rights, require_core=args.require_core)
        elif args.command == "annotation":
            result = validate_annotation(args.path)
        elif args.command == "run":
            if args.require_core and not args.manifest:
                _fail("--require-core requires --manifest for a run")
            result = validate_run(args.path, manifest_path=args.manifest, rights_path=args.rights, require_core=args.require_core)
        else:
            compare_provenance(_read(args.base), _read(args.candidate))
            result = {"compatible": True}
        print(json.dumps({"status": "PASS", "record": result}, sort_keys=True))
        return 0
    except ValueError as exc:
        print(json.dumps({"status": "BLOCKED", "error": str(exc)}), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
