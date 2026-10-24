# Owned validation evidence

Scope: V35.1 testing foundation, production contract `v35_2_`, baseline
`a7faaa2430878cfe353f4a4e084c0f9b760e34fd`. This record covers only the QA
owner's files. Other owners' integration and device results require their own
evidence. Existing user documents were preserved; the matrix was extended.

| Exact command | Observed result | Meaning |
| --- | --- | --- |
| `git rev-parse HEAD` | `a7faaa2430878cfe353f4a4e084c0f9b760e34fd`, exit 0 | Baseline identity confirmed |
| `python3 -m unittest tools.bubble_eval.tests.test_records -v` | 30 tests, `OK`, exit 0 on final owned run | PASS: synthetic-only record admission regressions |
| `python3 -m tools.bubble_eval.validate manifest qa/bubble/manifest.json` | `status=PASS`, `inventory_status=PASS`, `baseline_status=BLOCKED`, exit 0 | PASS: honest empty inventory |
| `python3 -m tools.bubble_eval.validate manifest qa/bubble/manifest.json --require-core` | `status=BLOCKED`, missing categories and 12/24/12 split coverage, exit 2 | Expected admission blocker |
| `./gradlew tasks --all` | `zsh:1: no such file or directory: ./gradlew`, exit 127 | BLOCKED: Android task discovery |
| `command -v gradle` | No path returned | BLOCKED: no system Gradle launcher |

All four JSON Schema files parsed successfully. Owned Python files contain no
one-letter variable names and no inline code comments. Host execution used
Python 3.12 in this environment; the code targets the standard library available
in Python 3.10+, but a separate 3.10 interpreter was not exercised.

No production Kotlin, model, dependency, reader, detector or cache files were
edited by this owner. No branch, commit, push or merge was performed. No physical
Pixel 8 Pro, Samsung A12 or emulator tests were performed by this owner. Their
tests remain BLOCKED. Real baseline quality and performance remain BLOCKED
because no real rights-cleared pages or annotations have been admitted.

Known limits: artifact checksums establish byte integrity, not ownership or
correct annotation. Image decoding/content validity and alpha projection also
require the scorer/Android checks. Standalone run validation cannot verify
schedule chronology or the corpus hash; reporter integration joins these
records. Group metrics need complete grouping. Sampling behavior and existing
OCR/cutout fallback risks are documented in PROTOCOL.md and unchanged.

Next: the lead runs the integrated owned suites, reviews Android export field
compatibility, admits rights-cleared real data, captures the unchanged baseline,
and runs both physical-device protocols before making a product claim.

## Integrated implementation result

This section supersedes the earlier owner's missing-wrapper observation. The
Gradle wrapper is now restored; that does not mean APK compilation succeeded.
No production Kotlin, reader behavior, detector implementation, model bytes,
Android plugin versions, root build script or settings were changed. No commit,
push, merge, model download or physical-device installation was performed.

| Exact command | Final observed result |
| --- | --- |
| `python3 -m unittest discover -s tools/bubble_eval/tests -q` | PASS: 86 tests, exit 0, including end-to-end PNG projection/report CLI and reviewer regression cases |
| `python3 tools/bubble_eval/run_android.py --self-test` | PASS: 11 guarded-runner tests, exit 0; includes missing-export versus watchdog accounting |
| `python3 -m tools.bubble_eval.generate_fixtures --output /private/tmp/anagno-bubble-foundation-fixtures-v1` | PASS, exit 0; seven original synthetic pages, no real accuracy claim |
| `python3 -m tools.bubble_eval.validate manifest /private/tmp/anagno-bubble-foundation-fixtures-v1/manifest.json` | PASS inventory, BLOCKED real baseline, exit 0 |
| `python3 -m tools.bubble_eval.validate manifest qa/bubble/manifest.json --require-core` | Expected BLOCKED, exit 2: empty real corpus |
| `./gradlew --offline --no-daemon --version` | PASS, exit 0: Gradle 8.7, Temurin JVM 21.0.4+7; source compilation target remains 17 |
| `./gradlew --offline --no-daemon tasks --all` | PASS, exit 0, BUILD SUCCESSFUL in 24s |
| `./gradlew --offline --no-daemon -PbubbleValidation=true tasks --all` | PASS, exit 0, BUILD SUCCESSFUL in 14s; isolated assemble/unit/device tasks discovered |
| `./gradlew --offline --no-daemon :app:assembleDebug :app:testDebugUnitTest` | BLOCKED, exit 1 before compilation: `extractDeepPanelModel` cannot resolve uncached `deeppanel-0.0.1.aar` offline |
| `./gradlew --offline --no-daemon -PbubbleValidation=true :app:assembleBubbleValidation :app:assembleBubbleValidationAndroidTest :app:testBubbleValidationUnitTest` | BLOCKED, exit 1 before compilation, same missing DeepPanel dependency |
| `git diff --check` | PASS, exit 0 |
| `git diff --exit-code -- app/src/main build.gradle.kts settings.gradle.kts` | PASS, exit 0: protected production/configuration paths unchanged |

The first sandboxed wrapper invocation could not create its existing cache lock.
The approved cache-access rerun succeeded; cache permissions are not the remaining
build blocker. Only JDK 21 and JDK 8 were installed locally. A JDK 17 runtime,
complete offline dependency graph, dependency locks and verification metadata
were not fabricated. Test-dependency resolution and Kotlin instrumentation
compilation remain unverified behind the DeepPanel prerequisite. The cached
distribution's original archive was unavailable for an independent full rehash;
the wrapper JAR checksum, configured distribution checksum and observed runtime
are recorded in toolchain.json.

The reviewer reproduced and the lead fixed group-denominator inconsistency,
incompatible timing-plan comparisons and maskless prediction admission. Missing
non-timeout exports are now invalid evidence rather than fabricated detector
misses. Regression tests cover these cases. All-change reviewer verdict:
PENDING final independent review.

## Changed-file inventory

The following 37 files are implementation changes. Existing untracked agent-kit
files outside this list remain user work and were preserved. The workflow and
test matrix were merged with their pre-existing contents.

```text
.gitignore
.github/workflows/android-validation.yml
app/build.gradle.kts
app/src/androidTest/bubbleValidation/com/comicreader/app/BubbleValidationTest.kt
gradlew
gradlew.bat
gradle/wrapper/gradle-wrapper.jar
gradle/wrapper/gradle-wrapper.properties
docs/agent-workflow/TEST-MATRIX.md
docs/bubble-testing/PROTOCOL.md
docs/bubble-testing/RESULTS.md
qa/bubble/acceptance-policy.json
qa/bubble/manifest.json
qa/bubble/rights.json
qa/bubble/known-defects.json
qa/bubble/toolchain.json
qa/bubble/schemas/annotation.schema.json
qa/bubble/schemas/dataset.schema.json
qa/bubble/schemas/rights.schema.json
qa/bubble/schemas/run.schema.json
qa/bubble/fixtures/known-answers.json
qa/bubble/fixtures/specification.json
tools/bubble_eval/README.md
tools/bubble_eval/requirements.lock
tools/bubble_eval/__init__.py
tools/bubble_eval/generate_fixtures.py
tools/bubble_eval/matching.py
tools/bubble_eval/metrics.py
tools/bubble_eval/png.py
tools/bubble_eval/projection.py
tools/bubble_eval/report.py
tools/bubble_eval/run_android.py
tools/bubble_eval/validate.py
tools/bubble_eval/tests/__init__.py
tools/bubble_eval/tests/test_evaluation.py
tools/bubble_eval/tests/test_records.py
tools/bubble_eval/tests/test_report.py
```

## First real baseline prerequisites

1. Provision the existing approved DeepPanel dependency offline, verify its
   provenance, resolve test dependencies, and retry both APK build commands.
   Review/generate complete dependency locks and verification metadata only
   after successful resolution; do not substitute or download a model.
2. Admit rights-cleared pages and complete reviewed annotations into the empty
   real manifest. Generated rectangles and static-risk records cannot satisfy
   real-data or device gates.
3. Build and verify the isolated APKs, then run explicit-serial captures on
   Pixel 8 Pro and Samsung A12 separately. Preserve all planned attempts,
   process identities, raw artifacts and failures. Direct warm inference is
   not a cache hit.
4. Complete actual-output landmark/reader checks and separate visible-render,
   sampled-memory/allocation, thermal and sustained-reading protocols. No such
   physical-device evidence exists yet. The direct capture runner does not
   measure memory peaks, cache reuse or UI latency.
5. Review the measured baseline before freezing candidate quality/performance
   margins. Keep foundation validity, detector quality and release readiness
   as separate outcomes.
