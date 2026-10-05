#!/usr/bin/env bash
# Keep the emulator alive until actual UI captures have been collected, including on failure.
set +e
gradle connectedDebugAndroidTest
fosa_test_result=$?
adb pull /sdcard/Android/data/com.arizona.fosa/files/screenshots ../../android-screenshots
exit "$fosa_test_result"
