# Samsung A16 explicit-session instrumentation fix

## Reported failure

Command: `./gradlew -PbubbleValidation=true :app:connectedBubbleValidationAndroidTest`.
The saved SM-A165M / Android 16 XML reports one test, one failure and zero skips:
`captureDirectDetector`, `IllegalStateException: Explicit session required` at
the original line 85. UTP supplied `additionalTestOutputDir`, but no `session`.
The exception occurred before detector construction or inference.

The original XML and UTP log were preserved before rerunning under
`app/build/bubble-validation/a16-session-fix/before/`. XML SHA-256:
`a3458717877f1130dc82f4b42de81190d38b7257c8335f6eb00393434c807ac7`.

## Cause and correction

The host capture runner creates a fresh UUID, stages checksummed inputs and
`request.json` in target-private storage, then passes `-e session <uuid>`.
The ordinary connected Gradle task performed none of that staging.

The validation-only Gradle configuration now explicitly enables
`bubbleValidationBootstrap=synthetic_smoke`. With that argument and no supplied
session, a test-only initializer creates a fresh 32-hex session directory, an
original 512 x 512 white PNG, its actual SHA-256 and one detector job. The request
is labeled `synthetic_smoke` / `detector_execution_only_not_baseline`.
The capture test then invokes the unchanged real detector API.

A supplied session always takes precedence, including blank, null or malformed
values: it is validated and never replaced with a smoke session. Missing setup
without the explicit bootstrap argument still fails. Stale outputs, duplicate
IDs, invalid request types, redirected paths, invalid images and mismatched
hashes fail before inference. Valid externally staged requests remain read-only.

Detector and export failures remain terminal failures, not empty successes.
Terminal evidence is preserved before JUnit assertions; smoke checks verify a
successful actual call, positive duration, input/result identity and matching
start/return/process receipts. Every actual returned mask retains the existing
geometry, PNG and hash checks. No expected prediction count is fabricated.

Smoke JSON evidence is emitted in instrumentation status output and the session
bundle is copied to UTP's additional output directory when configured, refusing
overwrite. The session regressions cover staging rejection and evidence-copy
failure. Negative terminal-control records exercise failure assertions only;
they are never passed off as detector observations or used by the capture test.

No production Kotlin, models, algorithms or reader behavior changed. Prior dirty
foundation changes were preserved; no commit, push or merge was performed.

## Validation record

| Check | Evidence/status |
| --- | --- |
| Android and QA investigations | Both independently confirmed missing Gradle session staging; source and saved failure inspected |
| Existing host baseline | 96 Python tests and 18 runner checks passed before the fix |
| Final host regressions | PASS: `python3 -m unittest discover -s tools/bubble_eval/tests -q` (96) and `python3 tools/bubble_eval/run_android.py --self-test` (18), both exit 0; these are not Android test execution |
| Gradle task discovery | `./gradlew --offline --no-daemon -PbubbleValidation=true :app:tasks --all`: PASS, 12s |
| Updated APK and receipt build | PASS: `./gradlew --offline --no-daemon -PbubbleValidation=true :app:writeBubbleValidationReceipt`, 20s, exit 0; instrumentation Kotlin compiled and APK assembled |
| Receipt integrity | PASS: receipt verified against current sources and both APK hashes |
| Samsung A16 connected rerun | BLOCKED: `env ANDROID_SERIAL=RF8Y70641SD ./gradlew --offline --no-daemon -PbubbleValidation=true :app:connectedBubbleValidationAndroidTest`, exit 1 in 11s: `DeviceException: No connected devices!` |
| Reviewer | Code review/build PASS; overall BLOCKED because no connected A16 test could execute; no actionable code defect found |

The native suite contains staging/strictness regressions plus the real detector
capture. No test is ignored, assumed away or silently skipped. A missing device
is a rerun blocker, not a passing instrumentation result. A synthetic smoke pass
would establish detector execution only, not real-page recall, boundary quality,
cache performance, memory behavior or a validated latency baseline.

The connected command was targeted only at the Samsung serial from the saved
failure report. Live `adb devices -l` repeatedly returned an empty list; USB
reconnection/authorization was requested. No native test executed during this
rerun, so the 17 compiled instrumentation cases have no new device verdict.
The preserved pre-fix XML must not be mistaken for a new execution result.

The rerun log and verified receipt are saved under
`app/build/bubble-validation/a16-session-fix/after/`. Current APK hashes:

- App: `cc62fa789a67f36089c0849bbb97f4ec4ea8b28795630fcc1bd9ee5f1920c0aa`
- Test: `e9672655b60da44853b6a167dfa8bfca3ebea12c1ddd8e94c1c3ae30a3a30813`

These hashes identify compiled artifacts, not executed detector results. Connect
and unlock the A16, authorize USB debugging, confirm its serial with ADB, then
rerun the connected task. Preserve the new JUnit XML, zero-skip test counts and
published smoke session artifacts before claiming a device PASS.

Harness changes invalidate old build receipts. The updated receipt was generated
for this fix; regenerate it after any further harness/source changes before
host-runner corpus capture.
