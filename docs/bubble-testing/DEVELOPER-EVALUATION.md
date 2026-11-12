# Developer Bubble Zoom Evaluation Mode

This tool collects a **10-page development pilot** from comics explicitly imported
into the separate Anagno Evaluation application. It runs the existing `v35_2_` detector with its existing model and
settings. It does not change the normal reader, Room schema, reading progress,
cached production detections, or comic ownership. Importing a comic does not
establish evaluation or redistribution rights.

## Build and enter

Developer code and its separate **Anagno Evaluation** launcher live entirely in
`app/src/developerEvaluation/`. Gradle includes this source set, activity manifest
and generated build/policy assets in `debug` and the opt-in `bubbleValidation`
variant only. Release has no evaluation activity, classes, assets or entry point.
The store additionally requires a debuggable application.

```sh
./gradlew --offline --no-daemon -PbubbleValidation=true :app:tasks --all
./gradlew --offline --no-daemon :app:assembleDebug
```

The debug application ID is **`com.comicreader.app.evaluation`**. It installs
alongside the original `com.comicreader.app`, with a separate Android UID, Room
database, page cache, URI grants and evaluation storage. The original signature
does not need to match because this is a different application. Release retains
the original application ID and excludes the evaluation feature. The isolated
validation application remains `com.comicreader.app.bubblevalidation`.

Launch **Anagno Evaluation**, choose **Import CBZ/CBR/PDF**, and explicitly select a
CBZ/CBR/PDF through Android's document picker. The evaluation application obtains
its own read permission and imports a reference into its own library. Existing
Anagno imports and permission grants are not shared. Canceling the picker does
not import anything. Revoked, missing or non-persistable access requires the user
to select an accessible source again; it is never bypassed with another app's
private files. See [side-by-side installation](SIDE-BY-SIDE.md) for the guarded
installation procedure, evidence and remaining device/manual blockers.

Never uninstall the original app, clear its data, attempt another update over its
package, copy its database, use shared UIDs or bypass signing/storage protection.

Builds generate `evaluation-build.json` from the actual source revision and
content digests, and copy the frozen evaluation policy. These are local build
identity records, not signed APK attestations. The existing build receipt now
also hashes the developer source set; regenerate receipts after edits.

## Local collection and annotation

1. Launch **Anagno Evaluation**. Explicitly import a source file through SAF if
   this application's own library is empty. Then choose a comic and a one-based page
   number. The app reads the page through existing archive/PDF loading APIs.
   It does not call reader navigation, progress updates, production indexing or
   detection-cache writes. Extraction may populate the usual page-image cache.
2. Capture copies the exact extracted image bytes to private `noBackupFilesDir`
   storage, records their SHA-256 and source identity, primes the unchanged
   detector once and measures the following direct call. Its mask files are
   isolated from production Bubble Zoom masks. Failures remain visible and are
   retained; they are never converted into successful empty detections.
3. Cyan outlines show the real prediction cutout alpha at its normalized page
   position. They are prediction evidence, not accepted ground truth. Use Fit,
   navigation zoom/pan and native-resolution inspection when reviewing edges.
4. Independently draw **every** expected bubble, including correct predictions.
   Choose New instance, then a rectangle or polygon. Commit the body and any
   additional tail/disconnected components as additive contours on the same
   instance. Subtract contours for holes. Neighbouring balloons stay separate.
   Undo/cancel an unfinished contour; remove a contour/instance to redraw it.
5. Mark missed instances and prediction false positives/incorrect boundaries.
   Issue marks are separate review evidence: marking FP never removes the raw
   prediction, and marking a miss never invents a ground-truth region. Verify
   each independently drawn instance after inspecting its complete boundary.
6. Enter the named reviewer, rights holder and actual evaluation-permission
   evidence. Explicitly confirm evaluation/export permission and whole-page
   completeness, including negative areas. An intentionally negative page has
   zero instances and still needs explicit whole-page review. Editing annotation
   geometry or issues invalidates page review. Drafts can remain unreviewed.
7. Reopen saved drafts locally. The pilot accepts at most ten distinct image
   hashes; selecting identical bytes reopens the existing capture without
   overwriting predictions. Expanding or resetting the pilot is not automatic.

Masks preserve the entire original page raster, with integer half-open boxes
around nonzero pixels. Polygons use original pixel-edge coordinates and a
pixel-center, non-antialiased fill. Additive contours are unioned; subtractive
contours are removed independently of order/winding. Irregular bodies, thin
tails and disconnected thought dots are supported. Invalid/self-intersecting,
out-of-bounds or empty geometry is rejected. Safety limits are 16,384 pixels
per axis and 16,777,216 pixels total: oversized pages fail clearly rather than
being silently downscaled for ground truth. Overview previews may be sampled;
native crop inspection preserves original source detail.

For PDF, “original page image” means the exact raster returned by Anagno's
existing PDF importer, not an invented resolution for a vector page. Source
metadata records this distinction and the source page index/archive entry.

## Explicit export and host evaluation

Data stays in app-private **no-backup** storage. There are no network/upload
calls. Export first requires every selected page to have rights, named review,
verified instances and complete terminal detection evidence. The user explicitly
confirms that original images/OCR/evidence will be included, then chooses a ZIP
destination through Android's document picker. A selected provider may itself
be cloud-backed; choose a local provider when offline-only export is required.
Failure may leave an incomplete destination document, which must not be treated
as a valid export. Source comics and local evidence remain intact.

The ZIP includes:

- `manifest.json`, `rights.json`, `run.json`, and exact `policy.json`, compatible
  with the existing semantic validators and schemas.
- `pages/<sample_id>/`: unchanged extracted page bytes; `annotation.json`;
  full-resolution GT PNGs; checksummed raw prediction cutout PNGs; source identity;
  vector draft and issue marks; reviewer/permission evidence; build, schedule,
  priming, start/return and terminal records; measurement observations.
- Root `capture.json`, documenting pilot-only scope and unmeasured endpoints.

Reviewed rights text is an explicit user attestation, not independent legal
verification. Review supporting permission documents before admitting an export
to a shared corpus. URI-derived source families identify local comics; consolidate
aliases/editions into actual families before expanding or dividing splits.

All pilot pages are development data. Group labels remain unassigned, so evaluate
instances; do not infer reading-group quality. All real categories remain
unclaimed until human tagging. A pilot cannot pass the 48-page full-core gate.

After extracting a trusted export into a new local directory:

```sh
python3 -m tools.bubble_eval.validate manifest PILOT/manifest.json
python3 -m tools.bubble_eval.validate run PILOT/run.json --manifest PILOT/manifest.json
python3 -m tools.bubble_eval.report evaluate \
  --manifest PILOT/manifest.json --run PILOT/run.json --policy PILOT/policy.json \
  --mode mask --unit instance --threshold 0.5 --output PILOT/report-mask-050.json
```

Repeat separately for threshold 0.75 and box mode. Preserve failed attempts:
they contribute misses and zero failure-inclusive IoU. FP/page remains conditional
on successful fully annotated pages; a failure blocks a full-corpus FP claim.
Never relabel generated native regression fixtures as real comic pages.

## Measurement interpretation

Every capture records its schedule before inference and input/process-bound
start/return records around the timed public detector call. An explicit priming
call precedes the measured call; the mode is **warm_inference**, even on the
first selected page. Constructor and priming timing are retained separately.
One developer-process detector is retained and serialized across activities to
avoid accumulating native sessions. Reopening is evidence reuse, not a measured
cache-hit benchmark. There is no claimed fresh-process cold measurement.

Memory observations are before/after process PSS and Java/native allocated-byte
snapshots. They are not sampled/exact peaks or detector-only allocation volume.
Request-to-visible-image latency, thermal characterization and memory peaks
remain explicitly unmeasured. UI work, export and constructor time are outside
the direct-call timer. There is no external watchdog in this interactive tool;
a terminated/incomplete invocation remains invalid evidence and blocks export,
rather than acquiring a fabricated timeout or duration. For unattended latency
campaigns use the existing guarded host capture runner with its watchdog.

## Verification and pilot acceptance

Automated regression inputs are original **synthetic** geometry controls. Native
tests exercise real Android rasterization/PNG export, masks with bodies/tails/holes,
coordinate transforms, verification/rights gates, the ten-page cap, and complete
attempt accounting. Their ZIP stays `kind=synthetic`. Host tests validate that
actual Android ZIP against `tools/bubble_eval` and retain the real-baseline block.
They establish infrastructure behavior, not comic detection accuracy.

On the SM-A165M, validate launch/library choice without changing reading progress;
then original-resolution inspection, viewport alignment, draw/undo/subtract,
rotation/reopen, failure visibility and cancellation/export to a local document.
The actual ten-page pilot requires user-selected pages with evaluation permission
and human-reviewed annotations. No automation is permitted to certify invented
comic ground truth. Save page-specific expected/observed behavior and screenshots
before claiming those manual cases pass.

## Previous implementation validation

The Android, Vision and QA custom agents all launched and implemented disjoint
files. Android supplied the entry point/build wiring and canvas, Vision the
annotation raster contract, QA the native and host regressions, and the lead the
local store/export integration. Reviewer independently inspected the changes.
No agent configuration or launch failures occurred.

Reviewer findings were corrected before final validation: original detail is
available through bounded asynchronous native-region decoding; actual viewport
touches use the tested mapping helper; edge/unit-scale rounding has explicit
regressions; and detector ownership/serialization is process-scoped in developer
code. Each native crop is at most 4,194,304 pixels; stale viewports are discarded
and their bitmaps recycled.

| Exact command / evidence | Result |
| --- | --- |
| `./gradlew --offline --no-daemon -PbubbleValidation=true :app:tasks --all` | PASS; actual tasks discovered |
| `./gradlew --offline --no-daemon -PbubbleValidation=true :app:assembleDebug :app:writeBubbleValidationReceipt :app:testDebugUnitTest :app:compileReleaseKotlin :app:processReleaseManifest :app:mergeReleaseAssets` | PASS in 32s; unit-test task `NO-SOURCE`, not an executed test suite |
| Final `./gradlew --offline --no-daemon -PbubbleValidation=true :app:assembleDebug :app:writeBubbleValidationReceipt` | PASS in 21s after the final rounding fix/regression |
| `env ANDROID_SERIAL=adb-RF8Y70641SD-b45fdT._adb-tls-connect._tcp ./gradlew --offline --no-daemon -PbubbleValidation=true :app:connectedBubbleValidationAndroidTest` | Final PASS in 1m31s: **27 tests, 0 failures, 0 errors, 0 skipped**, including 10 new evaluation-mode cases and 17 existing cases |
| `env ANAGNO_EVALUATION_ZIP='app/build/outputs/connected_android_test_additional_output/bubbleValidationAndroidTest/connected/SM-A165M - 16/bubble_evaluation/synthetic-evaluation.zip' python3 -B -m unittest discover -s tools/bubble_eval/tests -q` | PASS: **106 tests, no skips**, including actual Android-exporter ZIP validation/scoring |
| `python3 -B tools/bubble_eval/run_android.py --self-test` | PASS: **18 checks** |
| Receipt/current-source/APK check and embedded debug/validation identities | PASS; final APKs bind the current source snapshot |
| Compiled release exclusion inspection | PASS: 1,235 release class files, no evaluation classes; no evaluation activity in merged release manifest or evaluation assets in merged release assets |
| Full `:app:assembleRelease` in the initial multi-variant build | **BLOCKED** at `:app:lintVitalAnalyzeRelease`: `com.android.tools.lint:lint-gradle:31.5.2` unavailable offline. Release Kotlin compilation completed. No lint task was disabled, and no final release APK exclusion claim is made |
| `adb -s CONFIRMED_A16_ENDPOINT install -r app/build/outputs/apk/debug/app-debug.apk` | **BLOCKED**: `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, existing `com.comicreader.app` signatures do not match. Existing installation/data were preserved; no uninstall or data clear attempted |
| `git diff --check` and protected `app/src/main` / root plugin/settings diff checks | PASS |

Device: Samsung **SM-A165M**, API 36 / Android 16, `arm64-v8a`, fingerprint
`samsung/a16ub/a16:16/BP4A.251205.006/A165MUBSBDZI2:user/release-keys`.
Wireless ADB endpoints may change; rediscover rather than hardcoding the endpoint
for later measurements. A primary-user package query succeeded; an earlier
unscoped package query also tried the device's inaccessible user 150, so subsequent
queries explicitly used user 0. No secondary profile was accessed or changed.

The final native suite exercises original-resolution irregular/tail/hole PNGs,
the actual canvas fit/zoom/pan transform, one-pixel native source inspection,
both unit-scale rounding cases, isolated activity launch, draft persistence,
rights/review/failure gates, the 10-page cap, and a deterministic exporter ZIP.
That ZIP contains three explicitly **synthetic** samples: a failed-call negative
control with one annotated instance, a reviewed empty page, and an FP control
whose cutout bytes remain intact despite its false-positive annotation. It is
test infrastructure evidence, never a manually annotated real pilot.

Final XML, UTP log, synthetic ZIP and receipt are preserved under
`app/build/evaluation-mode/final27/`. The earlier 26-test intermediate run and
pre-task 17-test evidence were separately retained under `first-pass26/` and
`before/`. Final evidence SHA-256:

```text
JUnit XML     d0c01eef280300c88189ed7e189046bb3238ee4ecfb8e35744c78ce2c52b3a0a
Synthetic ZIP 6ed4246ec6de5ca29f0ee8b333b30a544eda340be1dc4df15107debe4624263a
Receipt       4259410febb1a1d6b3ab4da9f1c1a0e4fee9c0ae6593e58cd725c8bd8574c375
UTP log       72e87769d7fb9719f939b53ac507515cfd4902887912167c4aeca7fa62fd3eb1
```

The following blockers describe the previous implementation; the matching-key
requirement is superseded by the separate application ID and SAF flow described
above. Current installation and validation status is in [SIDE-BY-SIDE.md](SIDE-BY-SIDE.md).

Previous remaining acceptance: the actual ten-page comic pilot, rights/annotation review,
manual import-to-capture/edit/export flows, rotation/reopen and visible crop
alignment on those pages remain unperformed until the user supplies/selects
eligible inputs and verifies labels. Validation against the existing imported
library also requires a debug build signed compatibly with its installed app;
the attempted update was rejected before installation. Supply the matching
signing setup through the normal development environment; do not uninstall the
reader or clear its data as a workaround. Request-to-visible latency, peaks, sustained
performance, and separate Pixel 8 Pro/A12 acceptance remain unmeasured. The lint
dependency must be provisioned through the approved build environment before
full offline release assembly can complete.

Changed files: the developer activity, canvas, geometry and store; developer-only
manifest; app Gradle source/asset wiring; existing build-receipt tool and its
source-binding regression; new native evaluation tests and host export tests;
and this guide. The pre-existing `BASELINE-d0cb2d5.md` was preserved. Production
detector/model/reader files and root plugin versions remain unchanged. No commit,
push or merge was performed.

Final independent Reviewer verdict: **code and automated-test review PASS;
overall acceptance BLOCKED** by existing-app signing, offline release lint, and
the real/manual pilot prerequisites. Reviewer independently verified the final
27-test native XML and ZIP hashes and ran 19 focused host checks with no skips.
No concrete code findings remain. Passing synthetic checks establishes neither
real-page accuracy nor a performance improvement.
