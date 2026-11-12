# Safe side-by-side evaluation installation

The developer debug APK uses **`com.comicreader.app.evaluation`**. The existing
reader remains **`com.comicreader.app`**. No matching signing key, migration,
uninstall, app-data clear or private-data copy is required or permitted. The
validation target remains `com.comicreader.app.bubblevalidation`; release remains
the original reader package with no evaluation entry point.

Android and QA were delegated independent implementation/validation work with
separate file ownership. Existing uncommitted evaluation work was preserved.
Only developer integration/build wiring and tests are changed in this follow-up;
production detector settings, algorithms and reader/database classes are intact.

## Comic access belongs to the new application

1. Open **Anagno Evaluation**, not the original reader.
2. Choose **Import CBZ/CBR/PDF** and select an original CBZ/CBR/PDF in Android's Storage
   Access Framework picker. A read-only URI grant belongs to this application.
   The app retains that read grant and uses existing comic import/extraction APIs
   against its own application context and Room database.
3. Choose the imported comic and page in the evaluation application's own library.
   Run the unchanged detector; annotate/verify using the existing controls.
4. Keep the first pilot to ten distinct pages. A file-access grant is not a
   declaration of evaluation/redistribution rights. Human rights/page review is
   still required before the explicit ZIP export.

Canceling the picker leaves the library/pilot unchanged. Unsupported files,
revoked/unreadable grants and providers that cannot retain read access produce
an actionable error. The app never scans/copies the original reader's database,
adopts its URI grants, requests root, or writes to the chosen source document.
If the original archive exists only inside another app's private storage, stop:
the user must make an authorized original file accessible through SAF. Do not
extract it through a signing/private-storage workaround.

## Guarded installation and preservation evidence

First build without installing anything:

```sh
./gradlew --offline --no-daemon -PbubbleValidation=true :app:tasks --all
./gradlew --offline --no-daemon -PbubbleValidation=true :app:assembleDebug :app:writeBubbleValidationReceipt :app:processReleaseManifest :app:mergeReleaseAssets
```

Before using ADB, inspect the **built APK**, not only Gradle source. Its package
must be exactly `com.comicreader.app.evaluation`. Never install an APK reporting
the original `com.comicreader.app`. The guide's commands are future steps unless
the results section explicitly records execution.

```sh
SDK/build-tools/34.0.0/aapt dump badging app/build/outputs/apk/debug/app-debug.apk
adb devices -l
adb -s SERIAL shell pm path --user 0 com.comicreader.app
adb -s SERIAL shell dumpsys package com.comicreader.app
adb -s SERIAL shell pm path --user 0 com.comicreader.app.evaluation
adb -s SERIAL install --user 0 app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL shell pm path --user 0 com.comicreader.app
adb -s SERIAL shell dumpsys package com.comicreader.app
adb -s SERIAL shell pm path --user 0 com.comicreader.app.evaluation
adb -s SERIAL shell dumpsys package com.comicreader.app.evaluation
adb -s SERIAL shell am start --user 0 -n com.comicreader.app.evaluation/com.comicreader.app.evaluation.BubbleEvaluationActivity
```

`SERIAL` must be the live, authorized SM-A165M device. Do not use a stale wireless
endpoint or target another connected device. This fresh-install command has no
`-r`, downgrade flag or cleanup operation: if the developer package already
exists, it fails safely. Stop and inspect the existing evaluation installation;
do not uninstall it or overwrite its private pilot data to make a test pass.

Preserve before/after original-package records: installed APK paths/content
hashes where readable, UID, version, certificate identity, data-directory path,
and installation/update times. Confirm separate UIDs and data directories for
the two applications. No mutating command may target the original package.
Package/UID preservation plus Android sandbox isolation supports noninterference;
it is not a byte-for-byte audit of inaccessible private comic/database files.
Do not claim such a private-data comparison without actual read-only evidence.

## Tests without automatic package removal

Do **not** run Gradle's connected test task during this no-uninstall task: its
UTP lifecycle can uninstall test packages. Build the APKs, inspect their IDs, and
use manual instrumentation only when the isolated target/test packages can be
safely installed. Never clean up with `adb uninstall`, `pm clear`, or directory
deletion. If already-installed test packages prevent a fresh install, report
BLOCKED rather than replacing them without examining their data/state.

A manual run uses an explicitly fresh output directory in the isolated
validation application's own files directory, for example:

```sh
adb -s SERIAL install --user 0 app/build/outputs/apk/bubbleValidation/app-bubbleValidation.apk
adb -s SERIAL install --user 0 -t app/build/outputs/apk/androidTest/bubbleValidation/app-bubbleValidation-androidTest.apk
adb -s SERIAL shell am instrument --user 0 -w -r -e bubbleValidationBootstrap synthetic_smoke -e additionalTestOutputDir /data/user/0/com.comicreader.app.bubblevalidation/files/FRESH_RUN_DIRECTORY com.comicreader.app.bubblevalidation.test/androidx.test.runner.AndroidJUnitRunner
```

Keep target/test applications installed afterward. Retain complete instrumentation
output and the Android-exporter ZIP. If retrieving internal test artifacts, use
Android's authorized `run-as` only for this debuggable isolated validation target,
never for the original reader, and read only the fresh test output. This does not
require root or share another application's storage with the evaluation app.
Synthetic tests verify infrastructure only; a
real comic selection must be a user-granted file, and a test annotation must not
be automatically certified as complete human ground truth.

## Current results

Android and QA both launched successfully. Android implemented four owned paths:
`app/build.gradle.kts`, the developer manifest, `BubbleEvaluationActivity.kt`, and
new `EvaluationComicImport.kt`. QA added six native import-callback checks to
`BubbleEvaluationModeTest.kt` and a host installation test file. The lead updated
this guide and the existing developer guide; prior evaluation work was preserved.
No production-main source, detector/model/settings, root plugin version or
database migration changed. No commit, push or merge was performed.

| Exact command / inspection | Observed result |
| --- | --- |
| `./gradlew --offline --no-daemon -PbubbleValidation=true :app:tasks --all` | PASS, 8s; task discovery only |
| `./gradlew --offline --no-daemon -PbubbleValidation=true :app:assembleDebug :app:assembleBubbleValidation :app:assembleBubbleValidationAndroidTest :app:processReleaseManifest :app:mergeReleaseAssets` | PASS, 38s |
| `./gradlew --offline --no-daemon -PbubbleValidation=true :app:assembleDebug :app:writeBubbleValidationReceipt :app:compileReleaseKotlin :app:processReleaseManifest :app:mergeReleaseAssets` | PASS, 17s; includes compilation of the six new native import tests and a fresh source/APK receipt |
| `aapt dump badging` over all three built APKs | PASS: developer `.evaluation`, validation `.bubblevalidation`, and test `.bubblevalidation.test` IDs match the actual APK bytes |
| Developer APK label and permissions | PASS: `Anagno Evaluation`; no `READ_EXTERNAL_STORAGE` permission |
| Release merged manifest, compiled classes and merged assets | PASS: original `com.comicreader.app`, no evaluation entry point/assets, 1,235 release classes with zero evaluation classes; full release packaging remains subject to the previously recorded unavailable offline lint dependency |
| Current-source receipt and embedded developer identity | PASS, verified after assembly against the current sources and APK bytes |
| `env ANAGNO_EVALUATION_ZIP='app/build/outputs/connected_android_test_additional_output/bubbleValidationAndroidTest/connected/SM-A165M - 16/bubble_evaluation/synthetic-evaluation.zip' python3 -B -m unittest discover -s tools/bubble_eval/tests -q` | 108 tests: **107 PASS, 1 SKIP/BLOCKED** because full release APK metadata is absent. This uses the retained earlier Android synthetic ZIP, not a new device run |
| `python3 -B tools/bubble_eval/run_android.py --self-test` | PASS, 18 checks |
| `git diff --check`; protected main-source/root-plugin/settings and staged diff checks | PASS; production paths and index unchanged |
| `adb devices -l` (initial and later checks), `adb mdns services` | No connected device or discoverable paired wireless service; reconnection requested |
| Samsung installation, fresh native execution and before/after original-package checks | **BLOCKED**: SM-A165M unavailable; no installation attempted |
| Selecting/capturing/annotating a real page | **BLOCKED**: requires a connected device and the user's explicit document selection/grant, then human annotation verification |

The compiled suite now contains 33 native tests, including cancellation, file-URI
rejection, unsupported documents, permission/unavailability failures, cancellation
propagation and successful callback ordering. These six new checks use injected
callbacks; they do not open a real provider or grant any permission. Compilation
is not native execution. The earlier 27-test PASS is historical evidence and is
not relabelled as a pass for this import change.

No mutating device command targeted any package. There was no uninstall, data
clear, private-storage overwrite, old-app installation attempt, URI injection,
provider grant automation or signing bypass. Because the device is disconnected,
the original package/data's current state cannot be independently confirmed;
absence of a mutating command is not presented as a private-data byte comparison.

Built artifact verification is saved locally under
`app/build/evaluation-side-by-side/artifact-verification.json`. SHA-256 identities:

```text
Developer APK  cef5233501a92fa689c89bcc9c52ad23cdc3464cf9f622bccecba7774ef9954d
Validation APK 009bd142a2dfbd012237f34aafa2e5cda5894c01a48a35c0062f60ab6c1de54a
Test APK       4c41645edd0e202b637db45e2ef8ecbf1c45fd412a8c1094ea325b68d25beaf1
```

Next action: reconnect/unlock the SM-A165M and authorize debugging. Verify the
original package read-only, fresh-install only the inspected `.evaluation` APK,
and compare package/UID records without touching the original reader. Then the
user must choose an accessible original comic in **Import CBZ/CBR/PDF**. Stop at
that permission boundary instead of probing another app's private library.

Independent Reviewer: **code review PASS; overall acceptance BLOCKED**. No
concrete code findings remain. Reviewer independently reverified the current
source/validation/test APK receipt and reviewed the six import callback tests,
APK identity evidence and explicitly disclosed release-metadata skip. All 33
native tests are compiled, not executed for this follow-up. Device installation,
original-package/data checks, real-provider selection and real-page annotation
remain BLOCKED until device reconnection and explicit user action.
