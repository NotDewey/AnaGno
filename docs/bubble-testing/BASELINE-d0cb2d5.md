# Real-page baseline readiness at d0cb2d5

**Baseline verdict: BLOCKED.** No real-page accuracy or device-performance
baseline was established. The affected measurements stopped before inference
because no eligible real pages or verified ground truth are available here.

The inspected branch is `chore/codex-agent-workflow`, checkpoint
`d0cb2d5885f8092f12d3dd3d95e9c97b419b1e4f`. The actual detector contract is
`v35_2_`; V35.1 is the evaluation work title. The initial working tree was clean.
No detector, settings, models, application code or evaluation tools changed.

## Independent investigations and corpus inventory

Both requested custom agents launched successfully:

- Vision / Poincare, `01a1a5d7-4078-7ab2-ba7b-3babf7528849`: existing dataset
  eligibility, rights, annotations, geometry and experiment selection.
- QA / Linnaeus, `01a1a5d7-3fe4-7270-b794-06e915b11cbe`: scorer capability,
  saved device evidence, measurement scope and provenance.

Both investigations were read-only. Vision inspected hidden, ignored, untracked
and build areas: 11,271 files outside `.git`, with no traversal errors. No real
comic-page assets or comic/PDF archives were found. The raster inventory was
launcher icons/build copies and the explicitly synthetic blank smoke image.
This conclusion covers the checkout, not external storage or the user's device
library; private device content was not enumerated.

| Required existing material | Available here | Evidence |
| --- | ---: | --- |
| Admitted real pages | 0 | `qa/bubble/manifest.json:6` |
| Rights records | 0 | `qa/bubble/rights.json:3` |
| Real manually verified annotations | 0 found | Empty inventory; no real annotation assets found |
| Real ground-truth instance masks | 0 found | No corpus mask assets found |
| Synthetic fixture definitions | 7 | `qa/bubble/fixtures/specification.json:3`; excluded from real claims |

The training workspace only describes a dataset layout:
`tools/bubble_model/README.md:30` and `bubble_types.yaml:1`; its referenced
`dataset` is not a supplied corpus. The private corpus/run directories ignored
at `.gitignore:27` are absent. No eligible existing local dataset was overlooked
in favor of requesting new pages. No online dataset was downloaded or admitted.

## Measurement disposition

| Requested metric | Result | Missing evidence / existing capability |
| --- | --- | --- |
| Detection recall | BLOCKED; unmeasured | Real pages, complete verified instances and captured predictions; global one-to-one matching exists in `tools/bubble_eval/matching.py:11` |
| False positives/page | BLOCKED; unmeasured | Fully annotated real pages, including negatives; successful-page denominator and separate failures in `tools/bubble_eval/report.py:102` |
| Mask IoU | BLOCKED; unmeasured | Checksummed full-page GT masks and public cutout predictions; matched and failure-inclusive means in `tools/bubble_eval/report.py:124` |
| Boundary quality | BLOCKED; unmeasured | Verified body/tail masks and reviewed overlays; current scorer provides region IoU, not contour-distance or boundary F-score |
| Direct detector latency | BLOCKED for real pages | Frozen real-page schedule and fresh build receipt; timer is the actual `detect` call at `BubbleValidationTest.kt:165` |
| Request-to-visible-image latency | BLOCKED; unmeasured | Defined request/tap start, first correctly rendered bubble endpoint, and trace/video observations; direct runner does not capture UI timing (`run_android.py:303`) |
| Memory / peak allocations | BLOCKED; unmeasured | Separate physical-device PSS samples and Java/native allocation evidence; direct runner marks peak memory BLOCKED (`run_android.py:311`) |

No unmeasured value is zero. Mask IoU is a region-quality measure and cannot be
reported as a separate contour metric. Region IoU plus manual tail/leakage/crop
review can be reported with their explicit limitations; a quantitative boundary
F-score would require separately reviewed test tooling and a frozen tolerance.

## Samsung A16 evidence

Live ADB inspection succeeded after granting access outside the filesystem
sandbox. The connected device reports:

- Model `SM-A165M`, API 36 / Android 16, ABI `arm64-v8a`.
- Fingerprint `samsung/a16ub/a16:16/BP4A.251205.006/A165MUBSBDZI2:user/release-keys`.
- Wireless ADB endpoint `adb-RF8Y70641SD-b45fdT._adb-tls-connect._tcp` at inspection;
  rediscover the endpoint before a future capture.

Saved XML at
`app/build/outputs/androidTest-results/connected/bubbleValidation/TEST-SM-A165M - 16-_app-.xml:2`
reports **17 tests, 0 failures, 0 errors, 0 skips**, timestamp
`2026-11-04T09:10:37`. Its UTP log records the synthetic-bootstrap configuration.
This is saved native-suite PASS evidence, newer than the unavailable-device
attempt described in `A16-SESSION-FIX.md`; it was not rerun during this task.

The corresponding bundle is under
`app/build/outputs/connected_android_test_additional_output/bubbleValidationAndroidTest/connected/SM-A165M - 16/bubble_validation/b58887f1205743289b650e6fd997f8ea/`.
Its `request.json:2` explicitly labels the input `synthetic_smoke` and
`detector_execution_only_not_baseline`. Terminal, input and process receipts
support execution of the real detector on the blank image. Neither its empty
output nor its single call duration supplies real-page accuracy or UI latency.
No synthetic inference was repeated to fill the real-data gap.

QA and the lead independently checked the current build receipt. It identifies
the earlier `2ef68d9` source revision. `verify_receipt(...)` rejects it against
this checkpoint with **Build receipt source identity is stale or incompatible**
(`tools/bubble_eval/build_receipt.py:126`). Historical APK/hash consistency does
not authorize relabeling that capture as a fresh `d0cb2d5` corpus run. Regenerate
the build-bound receipt through Gradle before future corpus capture. No build
was needed merely to reconfirm the missing real inputs.

## Exact inputs required to unblock capture

1. Provide original real comic/manga page rasters and artwork-specific rights
   evidence for evaluation: rights holder, license/permission, allowed uses,
   evidence file and SHA-256. A source-code license or online availability does
   not establish page eligibility. Keep pages private unless redistribution is
   explicitly covered. Existing external datasets can be assessed if their
   local location and rights evidence are supplied.
2. Provide a versioned manifest with stable sample/source-family IDs, split,
   category tags, rights IDs, asset dimensions/path/hash and annotation
   path/hash/completeness, following `qa/bubble/schemas/dataset.schema.json`.
3. Supply manually checked original-resolution annotations: one stable instance
   ID per bubble, integer half-open `[left, top, right, bottom]` boxes and
   checksummed noninterlaced 8-bit grayscale or RGBA PNG instance masks covering
   the complete body and tail. Include explicit groups where group scoring is
   needed and justified ignore-region masks. Negative pages need explicit empty
   instances, not missing labels. Follow `annotation.schema.json` and
   `PROTOCOL.md:114`. Retain annotator/reviewer identity, review date, disagreements
   and their resolution in separate review evidence; these are not invented
   schema fields or automatically verified by the validator.
4. For the unchanged full-core gate, supply **48 fully mask-annotated pages**:
   development 12 / regression 24 / holdout 12, at least 30 / 90 / 30 instances,
   and at least two negative pages per split. Keep source families disjoint.
   Cover all 13 categories in `qa/bubble/acceptance-policy.json:42`. A smaller
   reviewed pilot may produce explicitly limited subset results; it cannot pass
   the full-core gate. Boxes alone cannot unblock mask/boundary measurements.
5. Freeze page order, repetitions, cold/warm mode, priming, timeout and settings
   before capture. Capture start/return/terminal evidence for every attempt.
   Separately specify the reader action and visible endpoint for UI latency,
   frame/clock method, repetitions and cache state; retain video/trace evidence.
   The current direct capture cannot produce this endpoint by itself.
6. For memory, supply a separate device profiling capture with predeclared PSS
   sampling cadence, raw timestamped `dumpsys meminfo` observations bound to
   attempt/process IDs, and baseline/observed maximum/delta. Collect Java/native
   allocated bytes and peaks separately with the profiler/method and overhead
   documented. A sampled PSS maximum is not an exact allocation peak. Record
   unavailable observations as unmeasured, and do not pool profiled timing with
   unprofiled latency. See `PROTOCOL.md:306` and `acceptance-policy.json:35`.

## Reproduction after inputs arrive

Select `d0cb2d5` as the source baseline for this campaign, preserving the existing
production configuration. Do not reuse earlier revision labels. Commands below
are future capture steps, not claims that capture occurred:

```sh
python3 -m tools.bubble_eval.validate manifest CORPUS/manifest.json --require-core
./gradlew --offline --no-daemon -PbubbleValidation=true :app:tasks --all
./gradlew --offline --no-daemon -PbubbleValidation=true :app:writeBubbleValidationReceipt
python3 tools/bubble_eval/run_android.py \
  --manifest CORPUS/manifest.json --policy qa/bubble/acceptance-policy.json \
  --output NEW_RUN_DIRECTORY --serial CONFIRMED_A16_ENDPOINT \
  --runtime-kind physical --mode cold_inference \
  --apk app/build/outputs/apk/bubbleValidation/app-bubbleValidation.apk \
  --test-apk app/build/outputs/apk/androidTest/bubbleValidation/app-bubbleValidation-androidTest.apk \
  --receipt app/build/bubble-validation/build-receipt.json --install
python3 -m tools.bubble_eval.validate run NEW_RUN_DIRECTORY/run.json --manifest CORPUS/manifest.json
python3 -m tools.bubble_eval.report evaluate \
  --manifest CORPUS/manifest.json --run NEW_RUN_DIRECTORY/run.json \
  --policy qa/bubble/acceptance-policy.json --mode mask --unit instance \
  --threshold 0.5 --output NEW_REPORT.json
```

Use separate directories for warm captures and predeclared repeat runs. Evaluate
IoU 0.50 and 0.75, box/mask and instance/group separately where labels permit.
Report TP/FN/FP per page, failure rate, matched mean IoU and failure-inclusive
IoU. Report direct-call p50/p95 with sample counts per device/mode; retain failed
and censored calls. Warm calls are not cache hits. Preserve raw schedules, logs,
APKs/receipt/model/corpus/policy hashes and device/thermal conditions. Visible
latency remains a separate captured reader journey. A16 evidence cannot satisfy
the independent Pixel 8 Pro and A12 acceptance gates.
Archive the separate memory series/allocation profiles alongside their process
and attempt identities. Compare memory only under matching sampling/profiling
conditions; the direct-detector export alone cannot establish this baseline.

## Failure classification and next experiment

There are **no measured common real-page failure types yet**. Use the following
taxonomy during review, recording sample/instance/prediction IDs, stage, screenshot
and frequency before calling any failure common:

- Missed proposals or OCR coverage: small/stylized/rotated/non-Latin text and
  textless bubbles (`DialogueTextAnalyzer.kt:23`).
- False positives: signs, SFX, captions outside the agreed target, artwork/gutters.
- Duplicate suppression or grouping errors: repeated dialogue in distinct
  balloons, connected instances merged or split (`BubbleDetector.kt:1272,1730`).
- Boundary errors: lost tails, clipped body, holes/text damage, artwork leakage.
- Coordinate/render errors: sampled/letterboxed proposals, original-resolution
  cropping, normalized placement and zoom alignment (`BubbleDetector.kt:5276,5316,5348`).
- Runtime failures: timeouts, invalid/missing exports or incorrect visible output;
  preserve these separately from successful-page FP accounting.

The next detector experiment should be an isolated **OCR-guided enclosure recovery**
comparison on the development split, only if unchanged-baseline evidence shows
readable dialogue missed by proposal generation. The current detector already
uses OCR and ONNX segmentation; adding OCR wholesale is not a new experiment.
Keep existing weights/settings fixed, change one recovery decision in a future
approved experiment, and compare identical captures for recall, FP/page, IoU,
tail/leakage review and device latency. Main risks are accepting signs/SFX/art,
merging adjacent balloons, missing textless bubbles and increased processing
cost. If boundary damage or duplicate suppression dominates instead, choose that
measured stage first. No algorithm change or improvement is approved here.

## Checks performed for this report

| Command / check | Observed result |
| --- | --- |
| `python3 -B -m unittest discover -s tools/bubble_eval/tests -q` | PASS, 96 tests, exit 0 |
| `python3 -B tools/bubble_eval/run_android.py --self-test` | PASS, 18 checks, exit 0 |
| `python3 -m tools.bubble_eval.validate manifest qa/bubble/manifest.json` | Inventory PASS, baseline BLOCKED, exit 0 |
| Same validation with `--require-core` | BLOCKED, exit 2; all split/category coverage missing |
| Live `adb devices -l` and explicit-device `getprop` | PASS for device availability/identity only |
| Current-source `verify_receipt(...)` | BLOCKED, stale source identity |
| New real-page inference / scorer reports / UI benchmarks | Not run: required real inputs absent |

Evidence SHA-256 identities at inspection:

```text
manifest.json  9af5f8122323f913e35094cb535d38531d4a2182b0f74dc0bfd70aad4333ec42
rights.json    b07b9c4c0a7d3cd2c4701e90e50942a6020860e32047a27fcdfa26d58f6ebb6a
saved A16 XML 3a0d403066e3242d3d77533bedf840016047154af48d3555fdbfeadcc01e4148
stale receipt bdb88156ab9363f3a1f97dbb5d49415af22ea2a289b09d90271924798185333d
```

Only this report was added. No production edits, model/dependency downloads,
device installs, commits, pushes or merges were performed.

Independent Reviewer / Beauvoir (`01a1a5dc-2dc4-79d0-a56b-23f1259cf98d`):
**Report PASS; real-page baseline BLOCKED.** The initial review found an omitted
memory disposition; the report now specifies separate PSS/allocation evidence
and profiling limits. Correction review found no outstanding findings. Rights,
real annotations and fresh capture evidence remain prerequisites, not waived
requirements.
