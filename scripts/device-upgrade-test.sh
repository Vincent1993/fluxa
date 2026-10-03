#!/usr/bin/env bash
# Fresh, isolated emulator only. Existing app data is never removed to bypass signing.
set -euo pipefail
[[ $# = 5 ]] || { echo "Usage: $0 old.apk old-test.apk new.apk new-test.apk reports"; exit 1; }
: "${ANDROID_SERIAL:?Select a dedicated emulator}"
: "${ANDROID_HOME:?Android SDK is required}"
[[ "$ANDROID_SERIAL" = emulator-* ]] || { echo "Use an isolated emulator, not a personal device."; exit 1; }
adb_cmd=("$ANDROID_HOME/platform-tools/adb" -s "$ANDROID_SERIAL")
[[ -z "$("${adb_cmd[@]}" shell pm path com.fluxa.app.debug)" ]] || {
    echo "This emulator already has Fluxa Debug; preserve it and select a fresh AVD."; exit 1;
}
old_apk=$1
old_test=$2
new_apk=$3
new_test=$4
reports=$5
mkdir -p "$reports"
for apk in "$old_apk" "$old_test" "$new_apk" "$new_test"; do
    [[ -f "$apk" ]] || { echo "Missing APK: $apk"; exit 1; }
done
certificate() {
    "$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --print-certs "$1" |
        sed -n 's/^Signer #1 certificate SHA-256 digest: //p'
}
old_cert=$(certificate "$old_apk")
new_cert=$(certificate "$new_apk")
[[ -n "$old_cert" && "$old_cert" = "$new_cert" ]] || {
    echo "Old and new APK must use the same test certificate."; exit 1;
}
printf '%s\n' "$new_cert" > "$reports/test-certificate-sha256.txt"
run_tests() {
    local label=$1
    shift
    "${adb_cmd[@]}" shell am instrument -w "$@" \
        com.fluxa.app.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "$reports/$label.log"
    # Android's instrumentation command can exit zero even when JUnit fails.
    grep -Eq '^OK \([1-9][0-9]* tests?\)' "$reports/$label.log"
}
"${adb_cmd[@]}" install "$old_apk"
"${adb_cmd[@]}" install "$old_test"
run_tests upgrade-seed -e class com.fluxa.app.UpgradePreservationTest -e fluxa.upgrade seed
"${adb_cmd[@]}" shell dumpsys package com.fluxa.app.debug |
    grep -E 'versionCode=|versionName=' > "$reports/before-package.txt"
"${adb_cmd[@]}" install -r "$new_apk"
"${adb_cmd[@]}" install -r "$new_test"
"${adb_cmd[@]}" shell dumpsys package com.fluxa.app.debug |
    grep -E 'versionCode=|versionName=' > "$reports/after-package.txt"
run_tests upgrade-verify -e class com.fluxa.app.UpgradePreservationTest -e fluxa.upgrade verify
run_tests complete-device-suite
for name in validation-login.png validation-reader.png validation-cache.png; do
    "${adb_cmd[@]}" exec-out run-as com.fluxa.app.debug cat "files/$name" > "$reports/$name"
done
