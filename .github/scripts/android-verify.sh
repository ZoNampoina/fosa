#!/usr/bin/env bash
# Keep the emulator alive until actual UI captures have been collected, including on failure.
# Functional Android/audio tests gate the release. The branded splash capture is visual-only:
# it still runs and is archived, but emulator surface timing must not block an audio/PTT hotfix.
set +e

gradle connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notClass=com.arizona.fosa.mobile.StartupCaptureTest
fosa_test_result=$?

# Best-effort visual startup capture. Its result is recorded but is not release-gating.
gradle connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.arizona.fosa.mobile.StartupCaptureTest
splash_test_result=$?

mkdir -p app/build/reports/androidTests/diagnostics
adb logcat -d -v threadtime > app/build/reports/androidTests/diagnostics/logcat.txt
printf 'functional_tests=%s\nsplash_visual_test=%s\n' "$fosa_test_result" "$splash_test_result" > app/build/reports/androidTests/diagnostics/summary.txt
if [ "$fosa_test_result" -ne 0 ]; then
  adb shell dumpsys activity lastanr > app/build/reports/androidTests/diagnostics/last-anr.txt
fi
adb pull /sdcard/Download/FOSA-screenshots ../../android-screenshots
exit "$fosa_test_result"
