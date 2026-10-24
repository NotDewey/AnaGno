# Bubble Zoom testing foundation

This is test infrastructure for the V35.1 audit snapshot, not a replacement
detector. The snapshot's actual output contract is `v35_2_`. Production detector,
OCR, reader, database and model files must remain unchanged.

The host tools use Python 3.10+ and its standard library. No model or Python
package downloads are required. Run from the repository root:

```sh
python3 -m unittest discover -s tools/bubble_eval/tests -v
python3 tools/bubble_eval/run_android.py --self-test
python3 -m tools.bubble_eval.report --help
```

The complete protocol, corpus admission gates, build prerequisites and separate
Pixel 8 Pro/Samsung A12 checklists live in
[`docs/bubble-testing/PROTOCOL.md`](../../docs/bubble-testing/PROTOCOL.md).
An empty real-data manifest is intentional: no real ground truth or accuracy
baseline has been invented. Generated synthetic fixtures validate the tools;
they do not establish comic-page detection accuracy.

Generate the original fixture bundle into a new or empty directory, then validate
it as synthetic evidence:

```sh
python3 -m tools.bubble_eval.generate_fixtures --output /tmp/anagno-fixtures
python3 -m tools.bubble_eval.validate manifest /tmp/anagno-fixtures/manifest.json
```

The restored wrapper starts Gradle 8.7 offline. The observed runtime is Temurin
21.0.4 and the unchanged Java compilation target is 17. APK builds remain blocked
by the unavailable offline DeepPanel AAR; test dependency resolution and Android
instrumentation compilation have not been verified. No dependency locks or
verification metadata were fabricated for this incomplete graph. Exact commands
and the complete changed-file inventory are recorded in RESULTS.md.

## Recorded results

Every run has an immutable predeclared `schedule` and explicit `attempts`.
`cold_inference` means the first detector call in a fresh process;
`warm_inference` means another direct call using a retained detector. Neither is
a cache-hit measurement. The direct-detector runner must reject `cache_hit`.
Any separately imported cache-hit record needs verified cache evidence.

Successful empty output is a valid result. A detector failure on a verified
valid page contributes misses to failure-inclusive quality. Missing or corrupt
evidence blocks comparison. Failed pages cannot improve false-positive claims:
FP/page is conditional on successful fully annotated pages, with failure rate
reported separately, and full-corpus FP comparison is blocked by any failure.

Public normalized cutout bounds and PNG alpha are projected onto the annotated
full-page raster. Box and mask matching, individual bubbles and reading groups,
and thresholds 0.50 and 0.75 are separate evaluations. No OCR-text deduplication
is performed by the evaluator.

```sh
python3 -m tools.bubble_eval.report evaluate \
  --manifest /path/to/corpus/manifest.json \
  --run /path/to/run/run.json \
  --policy qa/bubble/acceptance-policy.json \
  --mode mask --unit instance --threshold 0.5 --output /path/to/report.json

python3 -m tools.bubble_eval.report compare \
  --baseline /path/to/baseline-report.json \
  --candidate /path/to/candidate-report.json --output /path/to/comparison.json
```

Output is deterministic for identical captured inputs. Raw timestamps and
measurements are preserved in run evidence; inference itself is not assumed to
be bitwise deterministic. Percentiles use linear interpolation at `(n-1)*q` and
include sample counts. Timeouts are failed/censored observations, not zero-time
successes. Timing scope, process start identity, device configuration, and
profiling overhead must be recorded before interpreting any performance result.

## Evidence boundaries

Host tests establish evaluator arithmetic, geometry conventions, validation and
accounting. Emulator instrumentation additionally executes Android/native code.
Physical Pixel 8 Pro and Samsung A12 runs are required independently for device
latency, sampled memory, visible rendering and sustained-reading evidence.
Sampled process PSS is not exact peak allocation, and detector-call duration is
not tap-to-visible-image duration.

Baseline and candidate reports require compatible corpus, annotations, policy,
model, harness, scorer settings, measurement schedule, runtime and device/build
configuration. They do not establish an improvement until the frozen acceptance
policy and paired evidence have been reviewed. Foundation validity and detector
quality are separate outcomes.
