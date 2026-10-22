#!/usr/bin/env bash
# Dedicated, signed-out emulator only. Never uninstall or clear application data.
set -euo pipefail
[[ $# = 3 || $# = 4 ]] || { echo "Usage: $0 app.apk test.apk reports [both|portrait|landscape]"; exit 1; }
: "${ANDROID_HOME:?Android SDK required}"
: "${ANDROID_SERIAL:?Choose a dedicated emulator}"
[[ "$ANDROID_SERIAL" = emulator-* ]] || { echo "Use an isolated emulator."; exit 1; }
app_apk=$1
test_apk=$2
reports=$3
requested_mode=${4:-both}
case "$requested_mode" in
    both) modes=(portrait landscape) ;;
    portrait|landscape) modes=("$requested_mode") ;;
    *) echo "Choose both, portrait or landscape."; exit 1 ;;
esac
adb_cmd=("$ANDROID_HOME/platform-tools/adb" -s "$ANDROID_SERIAL")
mkdir -p "$reports"
certificate() {
    "$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --print-certs "$1" |
        sed -n 's/^Signer #1 certificate SHA-256 digest: //p'
}
[[ -f "$app_apk" && -f "$test_apk" ]] || { echo "Missing APK."; exit 1; }
app_cert=$(certificate "$app_apk")
test_cert=$(certificate "$test_apk")
[[ -n "$app_cert" && "$app_cert" = "$test_cert" ]] || { echo "Test certificate mismatch."; exit 1; }
printf '%s\n' "$app_cert" > "$reports/test-certificate-sha256.txt"
original_font=$("${adb_cmd[@]}" shell settings get system font_scale | tr -d '\r')
original_rotation=$("${adb_cmd[@]}" shell settings get system user_rotation | tr -d '\r')
original_auto=$("${adb_cmd[@]}" shell settings get system accelerometer_rotation | tr -d '\r')
original_airplane=$("${adb_cmd[@]}" shell settings get global airplane_mode_on | tr -d '\r')
[[ "$original_airplane" =~ ^[01]$ ]] || {
    echo "Cannot safely restore current airplane-mode toggle."; exit 1;
}
restore() {
    if [[ "$2" = null ]]; then "${adb_cmd[@]}" shell settings delete system "$1" > /dev/null
    else "${adb_cmd[@]}" shell settings put system "$1" "$2"; fi
}
run_phase() {
    local phase=$1 orientation=$2 font=$3 label=$4
    local rotation=0
    [[ "$orientation" = portrait ]] || rotation=1
    "${adb_cmd[@]}" shell am force-stop com.fluxa.app.debug || return $?
    # A prior instrumentation/Activity rotation can alter the device lock on API26.
    # Prepare every fresh process; the test still checks the actual Activity config.
    "${adb_cmd[@]}" shell settings put system accelerometer_rotation 0 || return $?
    "${adb_cmd[@]}" shell settings put system user_rotation "$rotation" || return $?
    "${adb_cmd[@]}" shell settings put system font_scale "$font" || return $?
    "${adb_cmd[@]}" shell settings get system user_rotation > "$reports/$label-rotation-before.txt" || return $?
    "${adb_cmd[@]}" shell am instrument -w \
        -e class com.fluxa.app.ReaderExperienceDeviceTest\#phasedReaderExperience -e readerPhase "$phase" \
        -e expectedOrientation "$orientation" -e expectedFontScale "$font" \
        com.fluxa.app.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "$reports/$label.log" || return $?
    "${adb_cmd[@]}" logcat -d -v threadtime -s FluxaReaderExperience:V FluxaDeviceFlow:V '*:S' \
        > "$reports/$label-device.log" || return $?
    grep -Eq '^OK \(1 test\)' "$reports/$label.log" || return 1
    ! grep -Eq 'INSTRUMENTATION_STATUS_CODE: -3|AssumptionViolated|FAILURES' "$reports/$label.log"
}
cleanup() {
    local status=$?
    trap - EXIT
    "${adb_cmd[@]}" shell am force-stop com.fluxa.app.debug || true
    if "${adb_cmd[@]}" shell run-as com.fluxa.app.debug ls files/reader-experience-original.json > /dev/null 2>&1; then
        run_phase cleanup portrait 1.0 interrupted-cleanup || { [[ $status != 0 ]] || status=1; }
    fi
    restore font_scale "$original_font" || { [[ $status != 0 ]] || status=1; }
    restore user_rotation "$original_rotation" || { [[ $status != 0 ]] || status=1; }
    restore accelerometer_rotation "$original_auto" || { [[ $status != 0 ]] || status=1; }
    python3 scripts/device-network-mode.py --airplane "$original_airplane" || { [[ $status != 0 ]] || status=1; }
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
"${adb_cmd[@]}" shell dumpsys package com.fluxa.app.debug > "$reports/package-before.txt"
"${adb_cmd[@]}" install -r "$app_apk"
"${adb_cmd[@]}" install -r "$test_apk"
"${adb_cmd[@]}" shell dumpsys package com.fluxa.app.debug > "$reports/package-after.txt"
python3 scripts/device-network-mode.py --airplane 1
offline=false
for attempt in {1..40}; do
    "${adb_cmd[@]}" shell dumpsys connectivity > "$reports/connectivity-offline.txt"
    if grep -Eq '^Active default network: (none|null)$' "$reports/connectivity-offline.txt"; then
        offline=true; break
    fi
    sleep 1
done
[[ "$offline" = true ]] || { echo "Device still has an active network; refusing offline acceptance claim."; exit 1; }
"${adb_cmd[@]}" shell settings put system accelerometer_rotation 0
for mode in "${modes[@]}"; do
    if [[ "$mode" = portrait ]]; then rotation=0; font=1.0; else rotation=1; font=1.5; fi
    "${adb_cmd[@]}" shell settings put system user_rotation "$rotation"
    "${adb_cmd[@]}" shell settings put system font_scale "$font"
    run_phase seed "$mode" "$font" "$mode-seed"
    "${adb_cmd[@]}" exec-out run-as com.fluxa.app.debug cat files/reader-experience-progress.json \
        > "$reports/$mode-progress.json"
    run_phase verify "$mode" "$font" "$mode-verify"
    for name in frozen-boundary mid-list-unread-anchor inline-image inline-image-restored inline-image-reflow inline-image-reflow-return inline-quote inline-quote-restored inline-quote-reflow inline-quote-reflow-return inline-code inline-code-restored inline-code-reflow inline-code-reflow-return inline-table inline-table-restored inline-table-reflow inline-table-reflow-return long-scroll rotated dark-theme sepia-reflow process-verified; do
        "${adb_cmd[@]}" exec-out run-as com.fluxa.app.debug cat "files/reader-experience-$name.png" \
            > "$reports/$mode-$name.png"
    done
    run_phase cleanup "$mode" "$font" "$mode-cleanup"
done
