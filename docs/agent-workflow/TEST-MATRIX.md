# Bubble Zoom benchmark template

## Dataset (populate with rights-cleared, untracked assets)
Minimum categories to cover: simple oval speech, rectangular dialogue, irregular outline, tail/connector, close overlapping bubbles, rotated text, tiny text, text without bubble, bubble without text, narration boxes, SFX, dark/inverted artwork, multicolumn layouts, high-resolution pages.

## Measurements
| Metric | Baseline | Candidate | Acceptance criterion |
| --- | --- | --- | --- |
| Bubble recall | TBD | TBD | No regression; target improvement on known misses |
| False positives per page | TBD | TBD | No material increase |
| Boundary quality (IoU if masks exist) | TBD | TBD | No regression; inspect tails |
| Cold detection latency p50 / p95 | TBD | TBD | No unacceptable device-specific regression |
| Warm detection latency p50 / p95 | TBD | TBD | No unacceptable device-specific regression |
| Peak memory / OOM | TBD | TBD | No OOM in test corpus |
| A12 user-visible smoothness | TBD | TBD | No blocking or stutter regression |
| Pixel 8 Pro user-visible smoothness | TBD | TBD | No blocking or stutter regression |

Define numeric thresholds *after* capturing baseline and agreeing on quality/performance tradeoffs. Preserve before/after annotated examples and exact revision/device/build details. Ensure evaluation scripts do not train on or tune against the final holdout set.

## V35.1 testing foundation execution matrix

This section extends the original template above. Production output contract is
`v35_2_`; baseline source is `a7faaa2430878cfe353f4a4e084c0f9b760e34fd`.
The repeatable protocol and wire/API definitions are in
[PROTOCOL.md](../bubble-testing/PROTOCOL.md). The real inventory and rights
ledger are intentionally empty. Synthetic fixtures are tool tests only.

| Case ID | Layer | Inputs / expected behavior | Evidence required | Current result |
| --- | --- | --- | --- | --- |
| FOUNDATION-RECORDS | Automated host | Reject unsafe paths, hash mismatches, bad geometry, split leakage, incomplete annotations and unadmitted rights | `python3 -m unittest tools.bubble_eval.tests.test_records -v`, exit 0 | PASS: 30 owned record tests; see [results](../bubble-testing/RESULTS.md) |
| FOUNDATION-SCHEDULE | Automated host | Preserve failed valid attempts; reject missing/extra/duplicate attempts and invalid evidence | Same owned record test suite | PASS: tested with temporary synthetic records |
| FOUNDATION-CACHE | Automated host | Require artifact proof and no inference for cache claims; reject warm inference mislabeled as cache | Same owned record test suite | PASS: validation semantics only |
| FOUNDATION-PROVENANCE | Automated host | Only production revision may differ; model/corpus/annotations/policy/device/harness/build/runtime/contract must match | Same owned record test suite | PASS: tested fixed-field incompatibilities |
| CORPUS-INVENTORY | Automated host | Empty real inventory is structurally legal; baseline remains BLOCKED | `python3 -m tools.bubble_eval.validate manifest qa/bubble/manifest.json` | PASS for inventory validity; baseline BLOCKED |
| CORPUS-CORE48 | Automated admission + manual label review | 12/24/12 pages, >=30/90/30 instances, >=2 negatives each, full masks, family-separated splits, all category gates | Validator `--require-core`, rights evidence, human annotation review | BLOCKED: no admitted real pages |
| GEOMETRY-6401 | Automated geometry + Android verification | Maximum dimension 6401 gives integer sample size 2; correct original-page projection | Host geometry result plus Android decode/crop evidence | BLOCKED pending geometry-owner and Android evidence |
| GEOMETRY-6402 | Automated geometry + Android verification | Maximum dimension 6402 gives integer sample size 4; correct original-page projection | Separate host/Android artifacts, stable sample ID | BLOCKED pending geometry-owner and Android evidence |
| ANDROID-BUILD | Build / unit checks | Discover actual tasks then build isolated debug runner | `./gradlew tasks --all`, discovered build/unit task logs | BLOCKED: no Unix wrapper launcher/JAR or Gradle on PATH |
| ANDROID-DETECT | Instrumented emulator/physical | Public detector API exports complete scheduled attempts and checksummed masks | Instrumentation logs, staged run and device/build provenance | BLOCKED: no captured instrumentation evidence in this task |
| BASELINE-QUALITY | Automated real evidence scoring | Recall, FP/successful page, failure rate, mask IoU; failures count all GT as FN/IoU0 | Identical real pages/settings for both runs; per-page failures and comparison report | BLOCKED: real baseline absent |
| PIXEL8-COLD | Manual physical + capture | Fresh-process first inference; constructor and call timing scopes explicit | Pixel 8 Pro fingerprint/build, schedule, process identity, timing/log artifacts | BLOCKED: unperformed |
| A12-COLD | Manual physical + capture | Same cold protocol on Samsung A12 | A12 fingerprint/build and independent artifacts | BLOCKED: unperformed |
| PIXEL8-WARM / A12-WARM | Manual physical + capture | Retained detector with predeclared priming; direct inference measured separately | Per-device attempts, thermal state, p50/p95 and sample counts | BLOCKED: unperformed |
| PIXEL8-CACHE / A12-CACHE | Manual physical repository path | Retrieval without inference; cache revisits preserve behavior | Cache key, lookup/trace artifact, `inference_invoked=false` | BLOCKED: direct detector runner cannot test cache hits |
| PIXEL8-MEMORY / A12-MEMORY | Manual physical profiling | PSS sampled separately from Java/native allocations and peaks | meminfo/profiler evidence, cadence, units, collection overhead | BLOCKED: unperformed |
| PIXEL8-VISUAL / A12-VISUAL | Manual physical render | Overlay/cutout alignment, full tails, connected bubbles, portrait/landscape zoom | Per-case screenshots/video with device/build ID | BLOCKED: unperformed |
| PIXEL8-READER / A12-READER | Manual isolated app | CBZ/CBR/PDF, Guided View, Bubble Zoom, 3D turn, bookmarks/progress, background/resume | Per-case expected/observed behavior plus video/logs | BLOCKED: unperformed |
| A12-SUSTAINED | Manual physical stress | Sustained navigation, failure recovery, memory pressure and cache reuse | Timed session, frame/crash/ANR/memory observations | BLOCKED: unperformed |

Baseline/candidate use identical manifest bytes, annotations, policy, model,
harness, evaluation settings and measurement schedule. Compare each runtime and
device separately. Foundation PASS does not imply product PASS. Failed valid
pages cannot produce a zero-FP full-corpus claim. Unexpected/missing artifacts
block comparison; observed detector failures remain reportable product outcomes.
