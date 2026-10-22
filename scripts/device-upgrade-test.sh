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
export FLUXA_DEVICE_TEST_RUNNER=androidx.test.runner.AndroidJUnitRunner
export FLUXA_READER_ACCESSIBILITY=false
run_tests() {
    local label=$1
    shift
    "${adb_cmd[@]}" shell am instrument -w "$@" \
        -e readerAccessibilityService "$FLUXA_READER_ACCESSIBILITY" \
        "com.fluxa.app.debug.test/${FLUXA_DEVICE_TEST_RUNNER:-androidx.test.runner.AndroidJUnitRunner}" | tee "$reports/$label.log"
    # Android's instrumentation command can exit zero even when JUnit fails.
    if grep -Eq '^INSTRUMENTATION_STATUS_CODE: -(3|4)' "$reports/$label.log"; then
        echo 'Skipped instrumentation tests are not accepted.' >&2; exit 1
    fi
    grep -Eq '^OK \([1-9][0-9]* tests?\)' "$reports/$label.log"
    printf '%s: ' "$label"
    grep -E '^OK \([1-9][0-9]* tests?\)' "$reports/$label.log"
}
"${adb_cmd[@]}" install --no-streaming "$old_apk"
"${adb_cmd[@]}" install --no-streaming "$old_test"
run_tests upgrade-seed -e class com.fluxa.app.UpgradePreservationTest -e fluxa.upgrade seed
"${adb_cmd[@]}" shell dumpsys package com.fluxa.app.debug |
    grep -E 'versionCode=|versionName=' > "$reports/before-package.txt"
"${adb_cmd[@]}" install --no-streaming -r "$new_apk"
"${adb_cmd[@]}" install --no-streaming -r "$new_test"
export FLUXA_DEVICE_TEST_RUNNER=com.fluxa.app.ReaderAccessibilityRunner
"${adb_cmd[@]}" shell pm list instrumentation > "$reports/instrumentation.txt"
grep -F "com.fluxa.app.debug.test/$FLUXA_DEVICE_TEST_RUNNER" "$reports/instrumentation.txt"
"${adb_cmd[@]}" shell dumpsys package com.fluxa.app.debug |
    grep -E 'versionCode=|versionName=' > "$reports/after-package.txt"
run_tests upgrade-verify -e class com.fluxa.app.UpgradePreservationTest -e fluxa.upgrade verify
# This fresh emulator owns no user accessibility configuration. The test APK
# contains a real client so Chromium observes a connected reader across reloads;
# ordinary UiAutomation suppresses services and can leave a title-only document.
# Never install this fixture into the application APK or alter an existing setup.
prior_services=$("${adb_cmd[@]}" shell settings get secure enabled_accessibility_services | tr -d '\r')
prior_accessibility=$("${adb_cmd[@]}" shell settings get secure accessibility_enabled | tr -d '\r')
[[ -z "$prior_services" || "$prior_services" = null ]] || {
    echo 'Existing accessibility services found; preserve them and use a fresh emulator.' >&2; exit 1;
}
restore_accessibility() {
    local status=$?
    trap - EXIT
    if [[ "$prior_services" = null ]]; then
        "${adb_cmd[@]}" shell settings delete secure enabled_accessibility_services || status=1
    else
        "${adb_cmd[@]}" shell settings put secure enabled_accessibility_services "''" || status=1
    fi
    if [[ "$prior_accessibility" = null ]]; then
        "${adb_cmd[@]}" shell settings delete secure accessibility_enabled || status=1
    else
        "${adb_cmd[@]}" shell settings put secure accessibility_enabled "$prior_accessibility" || status=1
    fi
    exit "$status"
}
trap restore_accessibility EXIT
"${adb_cmd[@]}" shell settings put secure enabled_accessibility_services \
    com.fluxa.app.debug.test/com.fluxa.app.ReaderAccessibilityService
"${adb_cmd[@]}" shell settings put secure accessibility_enabled 1
export FLUXA_READER_ACCESSIBILITY=true
"${adb_cmd[@]}" shell settings put system accelerometer_rotation 0
"${adb_cmd[@]}" shell settings put system user_rotation 0
"${adb_cmd[@]}" shell settings put system font_scale 1.0
run_tests complete-device-suite -e class "$(paste -sd, scripts/device-ci-classes.txt)" \
    -e expectedOrientation portrait -e expectedFontScale 1.0
bash scripts/device-ci-capture.sh "$reports"
for name in validation-login.png validation-reader.png validation-cache.png; do
    # Screenshots are optional diagnostics; JUnit and upgrade assertions above are mandatory.
    if "${adb_cmd[@]}" shell run-as com.fluxa.app.debug test -f "files/$name"; then
        "${adb_cmd[@]}" exec-out run-as com.fluxa.app.debug cat "files/$name" > "$reports/$name"
        # Only fixture screenshots from this isolated emulator; enables exact README evidence.
        if [[ "$name" = validation-reader.png ]]; then
            printf 'FLUXA_READER_SCREENSHOT_BASE64=%s\n' "$(base64 -w 0 "$reports/$name")"
        fi
    else
        printf '%s: no diagnostic frame was produced\n' "$name" >> "$reports/screenshots-unavailable.txt"
    fi
done
