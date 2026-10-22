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
original_font_scale=$("${adb_cmd[@]}" shell settings get system font_scale)
original_accelerometer_rotation=$("${adb_cmd[@]}" shell settings get system accelerometer_rotation)
original_user_rotation=$("${adb_cmd[@]}" shell settings get system user_rotation)
# adb shell output may contain carriage returns.
original_font_scale=${original_font_scale//$'\r'/}
original_accelerometer_rotation=${original_accelerometer_rotation//$'\r'/}
original_user_rotation=${original_user_rotation//$'\r'/}
active_label=
save_flow_logs() {
    # Only this test tag is captured; snapshots may include earlier test phases.
    "${adb_cmd[@]}" logcat -d -v threadtime -s FluxaDeviceFlow:V '*:S' \
        > "$reports/$1-device-flow.log"
}
restore_setting() {
    if [[ "$2" = null ]]; then
        "${adb_cmd[@]}" shell settings delete system "$1" > /dev/null
    else
        "${adb_cmd[@]}" shell settings put system "$1" "$2"
    fi
}
cleanup() {
    local status=$?
    trap - EXIT
    # Attempt every restoration, without masking the original failure status.
    if [[ -n "$active_label" ]]; then
        save_flow_logs "$active_label" || { [[ $status != 0 ]] || status=1; }
    fi
    restore_setting font_scale "$original_font_scale" || { [[ $status != 0 ]] || status=1; }
    restore_setting user_rotation "$original_user_rotation" || { [[ $status != 0 ]] || status=1; }
    restore_setting accelerometer_rotation "$original_accelerometer_rotation" || { [[ $status != 0 ]] || status=1; }
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
run_tests() {
    local label=$1
    shift
    active_label=$label
    "${adb_cmd[@]}" shell am instrument -w "$@" \
        com.fluxa.app.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "$reports/$label.log"
    # Android's instrumentation command can exit zero even when JUnit fails.
    grep -Eq '^OK \([1-9][0-9]* tests?\)' "$reports/$label.log"
    save_flow_logs "$label"
    active_label=
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
"${adb_cmd[@]}" shell settings put system accelerometer_rotation 0
"${adb_cmd[@]}" shell settings put system user_rotation 0
"${adb_cmd[@]}" shell settings put system font_scale 1.0
run_tests complete-device-suite -e expectedOrientation portrait -e expectedFontScale 1.0
for name in validation-login.png validation-reader.png validation-cache.png; do
    "${adb_cmd[@]}" exec-out run-as com.fluxa.app.debug cat "files/$name" > "$reports/$name"
done
"${adb_cmd[@]}" shell settings put system user_rotation 1
"${adb_cmd[@]}" shell settings put system font_scale 1.5
run_tests landscape-large-font \
    -e class com.fluxa.app.AdaptiveLayoutDeviceTest,com.fluxa.app.OfflineAppFlowTest \
    -e expectedOrientation landscape -e expectedFontScale 1.5
