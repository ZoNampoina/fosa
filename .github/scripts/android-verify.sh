#!/usr/bin/env bash
# Keep the emulator alive until actual UI captures have been collected, including on failure.
set +e
gradle connectedDebugAndroidTest
fosa_test_result=$?
mkdir -p app/build/reports/androidTests/diagnostics
adb logcat -d -v threadtime > app/build/reports/androidTests/diagnostics/logcat.txt
if [ "$fosa_test_result" -ne 0 ]; then
  adb shell dumpsys activity lastanr > app/build/reports/androidTests/diagnostics/last-anr.txt
fi
adb pull /sdcard/Download/FOSA-screenshots ../../android-screenshots
exit "$fosa_test_result"
