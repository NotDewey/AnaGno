# V35.1 testing foundation

This foundation records evidence for production contract `v35_2_` at baseline
`a7faaa2430878cfe353f4a4e084c0f9b760e34fd`. V35.1 is the work title. The real
inventory in `qa/bubble/manifest.json` and its separate rights ledger are empty
by design. Real accuracy, baseline comparisons, physical-device performance and
reader acceptance are **BLOCKED**. Passing host tests establishes foundation
validity; it does not establish product quality.

## Reproduce host checks

From the repository root, with Python 3.10+ and no third-party Python packages:

```sh
python3 --version
python3 -m unittest tools.bubble_eval.tests.test_records -v
python3 -m unittest discover -s tools/bubble_eval/tests -v
python3 -m tools.bubble_eval.validate manifest qa/bubble/manifest.json
python3 -m tools.bubble_eval.validate manifest qa/bubble/manifest.json --require-core
python3 -m tools.bubble_eval.report --help
```

The inventory command exits 0 with `inventory_status=PASS` and
`baseline_status=BLOCKED`. The core admission command deliberately exits 2 with
`status=BLOCKED`. A failed negative test is a foundation failure; an expected
rejection of invalid evidence is a passing test. The record tests generate only
temporary synthetic assets. Separately maintained `qa/bubble/fixtures/` assets
must retain `kind=synthetic`; they cannot populate the real inventory or satisfy
the real baseline gate.

Build discovery precedes Android checks:

```sh
./gradlew --offline --no-daemon tasks --all
./gradlew --offline --no-daemon :app:assembleDebug :app:testDebugUnitTest
```

The matching Gradle 8.7 wrapper files and distribution checksum are restored.
Task/build success still requires verified local JDK, SDK and dependency inputs;
see RESULTS.md for observed commands and blockers, not merely file presence.
Do not download models or introduce ML dependencies to unblock this foundation.
The isolated opt-in variant uses fixed Android test dependencies. Once an
instrumentation task exists, discover its exact name and runner arguments from
the Android owner's instructions rather than assuming a task or test class.

## Corpus admission and stable identities

The four versioned schemas in `qa/bubble/schemas/` describe dataset, annotation,
rights and run records. `tools/bubble_eval/validate.py` adds semantic validation:
artifact existence and SHA-256, safe relative paths, box ordering and extents,
source-family separation, group membership, schedule joins, status semantics,
and core admission. JSON Schema alone cannot verify local bytes, compare
coordinates to another field, or join two arrays by identifier. No JSON Schema
package is required to run the executable checks.

Keep `sample_id`, instance IDs, group IDs and `source_group` stable across
baseline and candidate. Revision changes describe corpus edits; do not reuse a
sample identity for unrelated artwork. Duplicate page content is rejected.
`source_group` identifies the source family (book/series/edition family), not a
single page. The same family cannot appear in multiple splits. Human admission
must verify that aliases/crops of one source family have not bypassed this rule.

Each sample requires tags, rights ID, original asset dimensions/hash and an
annotation path/hash/completeness. The rights ledger uses:

```json
{
  "schema_version": "1.0",
  "records": [{
    "rights_id": "owned-example",
    "status": "approved",
    "holder": "rights holder",
    "license": "user-owned; explicit evaluation permission",
    "allowed_uses": ["evaluation"],
    "evidence": {"path": "permissions/owned-example.txt", "sha256": "64 lowercase hex characters"}
  }]
}
```

This is an explanatory shape, not an admitted record. Real permissions and
hashes must exist before use. Approval requires a human rights review; a
self-declared JSON status does not establish legal ownership. Evaluation
permission does not imply redistribution permission. Do not commit pages or
permission documents unless their rights cover that use.

All record paths are canonical POSIX relative paths. Dataset asset/annotation
paths resolve beside the manifest. Annotation instance and ignore-region masks
resolve beside the annotation. Run prediction and cache-proof artifacts resolve
beside the run. Rights evidence resolves beside the rights file. Absolute paths,
drive paths, backslashes, empty/dot/traversal segments and escaping symlinks are
rejected. External inputs must be explicitly staged into a private record
directory by the integration owner's guarded staging workflow. Validation does
not copy files or follow a directory outside the record root.

The real core goal is exactly 48 pages, all fully mask annotated:

| Split | Pages | Minimum instances | Minimum negative pages |
| --- | ---: | ---: | ---: |
| development | 12 | 30 | 2 |
| regression | 24 | 90 | 2 |
| holdout | 12 | 30 | 2 |

Core admission also requires every category in
`qa/bubble/acceptance-policy.json`: oval, rectangle, irregular, tail, small text,
no-text bubbles, dense panels, negative pages, false-positive regions, connected
bubbles, rotated content, dark art, and high-resolution pages. Tags are labels,
so reviewers must verify their evidence. A negative page has zero annotated
instances, not an omitted annotation. Preserve count and box annotations for
development if useful, but they cannot satisfy the full-mask core gate.
Reserve holdout families before tuning; never tune thresholds on holdout.

## Annotation and geometry contract

Annotation `instances` contain one stable ID per expected bubble, even for
`completeness=count`. Thus `len(instances)` is the expected bubble count. For
`box`, every instance has an integer `[left, top, right, bottom]` in original
full-page pixels with half-open edges. For `mask`, every instance also has
`mask_path` and `mask_sha256`. Annotation masks represent the original page
raster; prediction cutout alpha is projected onto that raster using public
normalized bounds. Label the complete body and tail; do not count a reading
group as a single instance.

Host PNG decoding supports noninterlaced 8-bit grayscale coverage and RGBA
cutout alpha. Every captured prediction must retain a checksummed PNG, including
box evaluations. Box-only mathematical fixtures are supported by the low-level
scorer, but maskless records are not complete public-detector evidence.

`groups` is optional in substance (an empty array is legal), but if nonempty it
must partition all instances: no unknown members, duplicates or overlapping
membership. Missing grouping blocks group metrics on nonempty pages. Empty
negative pages need no groups. Every ignore region has
`{id, mask_path, mask_sha256, reason}`. Masks resolve beside the annotation.
Positive matching takes precedence over ignoring unmatched predictions; pin
the overlap rule and threshold in the scoring policy. Ignore regions do not
remove positive GT or reduce expected counts.

Verify decode, letterbox removal, normalized bounds, cutout projection and
render cropping separately. The current integer sampling loop gives sample
size **2 at maximum dimension 6401** and **4 at 6402** (`6401 / 2` truncates to
3200, `6402 / 2` to 3201). Keep these as separate stable geometry cases, including
odd portrait/landscape dimensions. Round-trip full-page corners, thin tails,
edge boxes, non-square pages and crop padding. Host geometry math establishes
the test convention; Android decoding and actual displayed alignment require
captured Android/device evidence.

## Run capture and provenance

Write and archive the schedule **before** launching any detector calls. Hash
that immutable schedule and retain launch logs; final JSON by itself cannot
prove launch order. Each planned attempt has a unique ID, sample ID, mode and
expected input validity. Retries require their own predeclared attempt IDs.
Every scheduled attempt must appear once; extras and missing attempts block
comparison. Preserve the original schedule when a watchdog kills the process.
Record failure evidence or mark the export incomplete; never fabricate success.

Modes remain separate:

| Mode | Required preparation and timing scope |
| --- | --- |
| cold_inference | Fresh app process; first direct detector call. Record constructor separately; lazy model/session and decode work inside the detector call remain included. |
| warm_inference | Same retained detector after explicit predeclared priming; another direct detect. Priming attempts are identified and excluded by the frozen plan. |
| cache_hit | Verified repository/cache retrieval with zero detector calls; direct detect cannot measure this mode. |

Successful cache claims require
`cache_hit_evidence={cache_key,inference_invoked:false,path,sha256}` with a local
checksummed lookup/trace artifact. A warm direct detect is never a cache hit.
The current direct-detector instrumentation must reject cache mode.

Run provenance requires exactly these fields:

| Field | Definition |
| --- | --- |
| production_revision | Actual source revision being measured; capture dirty source state separately. |
| harness_revision | Immutable harness revision/content identity, identical for both runs. |
| model_sha256 | SHA-256 of the actual bundled model; must match in this no-model-change scope. |
| corpus_sha256 | SHA-256 of exact manifest bytes; formatting changes alter this identity. |
| annotations_sha256 | SHA-256 of UTF-8 compact JSON of sorted `(sample_id, annotation.sha256)` pairs, `separators=(",", ":")`, `ensure_ascii=True`. |
| policy_sha256 | SHA-256 of exact frozen policy file bytes; reporter verifies it when a policy file is supplied. |
| device_fingerprint | Actual host/Android OS build fingerprint; no invented physical-device identity. |
| device_model | Actual model, separately distinguishing Pixel 8 Pro and Samsung A12 variants. |
| runtime_kind | Exactly `host`, `emulator`, or `physical`. |
| build_variant | Exact build variant for the captured run. |
| contract | Exactly `v35_2_`. |

`compare_provenance` allows only `production_revision` to differ. All other
fields above must match. Compare one device/runtime/build cohort at a time.
Retain APK SHA-256, OS/API/ABI, package/application ID, app version, runner
arguments, settings, thermal/battery state and timing plan in captured evidence.

Optional attempt observations use `constructor_duration_ns` (nonnegative integer),
`memory`, `process`, and `runtime`. Memory accepts nonnegative integer
`physical_pss_bytes`, `java_allocated_bytes`, `native_allocated_bytes`,
`java_peak_allocated_bytes`, `native_peak_allocated_bytes`, and
`sampling_interval_ns`. Process accepts positive integer `pid`, nonempty string
`start_id`, and boolean `fresh`. Runtime accepts nonempty strings `timing_scope`
and `thermal_state`, and positive integer `watchdog_ns`. Missing observations
mean unmeasured, never zero. These field names must match the Android export;
an exporter using other names needs an explicit coordinated schema revision.

Public Python API:

```python
validate_manifest(path) -> dict
validate_annotation(path, *, sample=None) -> dict
validate_run(path) -> dict
compare_provenance(base, candidate) -> None
```

All reject invalid evidence with `ValueError`. Provenance comparison accepts
provenance objects or full run records. Optional manifest arguments are
`rights_path=None, require_core=False`; optional run arguments are
`manifest_path=None, rights_path=None, require_core=False`. Standalone run
validation does not join sample IDs to a manifest; the reporter must perform
that join, or the caller supplies `manifest_path`. Annotation `sample` binds its
ID, dimensions and completeness to a manifest sample.

Wire records are preserved in returned copies. Manifest `_validation` contains
`inventory_status`, `baseline_status` (`READY` means admission only),
`expected_counts`, `coverage`, `gaps`. Annotation `_validation` contains
`expected_count`, `completeness`, `groups_complete`. Run validation adds no
fields. Do not persist `_validation` in wire JSON. A successful validation is
not proof of chronology, rights ownership, Android execution or detection quality.

Report comparison also requires identical timer scopes, watchdog limits,
normalized process/session patterns, and profiling-presence conditions. Actual
PIDs may differ across runs; changing session reuse or including application
startup in only one timer is incompatible. Unspecified timing evidence cannot
establish approved device-performance claims.

```sh
python3 -m tools.bubble_eval.validate annotation /path/to/annotation.json
python3 -m tools.bubble_eval.validate run /path/to/run.json --manifest /path/to/manifest.json
python3 -m tools.bubble_eval.validate compare-provenance /path/to/base-run.json /path/to/candidate-run.json
python3 -m tools.bubble_eval.report evaluate --manifest /path/to/manifest.json --run /path/to/run.json --policy qa/bubble/acceptance-policy.json --mode mask --unit instance --threshold 0.5 --output /path/to/report.json
python3 -m tools.bubble_eval.report compare --baseline /path/to/base-report.json --candidate /path/to/candidate-report.json --output /path/to/comparison.json
```

Keep mask/box evaluations, instance/group units and IoU thresholds 0.50/0.75
separate. Never pool modes or infer mask quality from boxes alone. Missing or
corrupt prediction artifacts on a claimed success are invalid evidence. A
successful empty detection list is a legitimate result. `detector_failed` on a
verified valid page remains in evidence: every GT instance contributes FN and
zero failure-inclusive IoU. A completed failure may retain nonnegative integer
`duration_ns`; a killed call can omit duration, with failure kind preserved.
`invalid_evidence` or a partial export blocks comparison.

Primary FP/page uses successful fully annotated valid-page attempts only.
Report failure rate separately using every scheduled valid-page attempt.
Any failed page blocks a full-corpus FP claim; never turn a failed page into
zero FP. Invalid-input robustness attempts are reported separately from valid
corpus quality. Negative pages still count in the successful-page denominator.

## Physical Pixel 8 Pro and Samsung A12 protocol

Execute this protocol independently on both physical models. Current status:
**BLOCKED — no captured device execution in this foundation**.

1. Use an isolated debug/test application or dedicated test-device profile with
   a separate database/files/cache directory. Verify its application ID before
   installing or clearing anything. Never clear the user's reading library.
   Record APK/model hashes, build revision, Android fingerprint/API/ABI, device
   serial alias, available storage, battery/charging and thermal state.
2. Put the same rights-cleared manifest, annotations, policy and settings on both
   builds. Stage explicitly and verify hashes after transfer. Disable network
   during reader detection checks. Retain permission evidence privately.
3. Predeclare page order, repetitions, mode, timeout and priming. Record monotonic
   nanosecond timing around the same public call. Use a separate external
   watchdog (for example an agreed 120-second budget for initial capture),
   preserving ANR/crash/logcat evidence and scheduled intent on termination.
   A coroutine timeout alone cannot reliably stop native inference. The budget
   is an execution limit, not an accepted latency target.
4. Force-stop/relaunch the isolated app between cold attempts; retain process
   start identity. For warm attempts retain the same detector/process and
   document all priming. Cache checks need independent cache evidence and no
   direct detector invocation. Verify valid-empty and invalid-input paths.
5. Capture process PSS on each physical device using a fixed documented sampling
   cadence and `dumpsys meminfo` evidence. PSS is resident process memory shared
   proportionally, not Java/native allocation volume. Record managed/native
   allocation observations separately, with collection method and instrumentation
   overhead. A sampled maximum is not the exact allocation peak; short-lived
   peaks may be missed. Do not substitute host memory for A12/Pixel measurements.
6. Record tap-to-visible-bubble time separately from detector-call latency.
   Capture video/screenshots for full-page overlay, tails, zoom crop, portrait
   and landscape, display scaling, adjacent/connected bubbles and group order.
   On A12 include sustained page navigation, background/resume, low-memory
   behavior, cache revisits and recovery after failure. Preserve working fast
   paths and cache safeguards; this foundation changes none of them.
7. Manually verify isolated import/read/navigation of CBZ, CBR and PDF, Guided
   View, Bubble Zoom, 3D page turn, bookmarks and reading progress. Each result
   needs device/build ID, case ID, expected/observed behavior and evidence path.
   A launch log alone does not pass visual correctness or sustained reading.
8. Run baseline/candidate on identical pages/settings and the same device in a
   paired/counterbalanced order after comparable cool-down. Keep raw attempts,
   failure classifications, per-page results and settings. Any unavailable
   device/scenario stays BLOCKED with its reason.

## Statistical scope, defects and reporting

Report page count, independent source-family count, attempt count and successful
timing count. Repeated calls on one page do not create independent accuracy
samples. A 48-page corpus is a bounded regression gate, not population-wide
proof. Report p50/p95 per mode/device with the quantile method and sample count;
small-sample p95 is descriptive. Include failures and censored watchdogs rather
than hiding them in successful latency summaries. Use paired page differences
and source-family clustered uncertainty for later inference; host checks alone
do not estimate physical-device confidence intervals. Freeze numerical product
thresholds only after baseline capture and review.

Known static risks requiring evidence include integer sampling at 6401/6402,
full-resolution crop mapping/rounding, OCR failures degrading silently to empty
regions in `BubbleDetector.detect`, and rejected cutouts reducing returned
bubble counts. Current masks are presentation cutouts, so treating their entire
rectangle as the balloon would overstate IoU. These are inspection risks, not
confirmed measured regressions. Baseline device latency, boundary quality,
memory, thermal behavior, sustained reading and cache correctness remain
unmeasured. This work fixes none of those production risks.

Record each case as PASS, FAIL or BLOCKED with command, exit status, device/build
identity where relevant, artifact hashes and observed failure classification.
Keep foundation validity, real corpus admission, detector quality and manual
device acceptance as separate outcomes. A valid foundation can expose a failing
product. Never mark a physical-device test PASS without captured evidence.
