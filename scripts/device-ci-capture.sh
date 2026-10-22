#!/usr/bin/env bash
# Explicit fixture-owned flows on the fresh CI emulator; never use a personal device.
set -euo pipefail
reports=$1
: "${ANDROID_HOME:?}" "${ANDROID_SERIAL:?}"
[[ "$ANDROID_SERIAL" = emulator-* ]]
adb_cmd=("$ANDROID_HOME/platform-tools/adb" -s "$ANDROID_SERIAL")
run_phase() {
    local label=$1 class=$2 argument=$3 value=$4
    "${adb_cmd[@]}" shell am instrument -w -e class "$class" -e "$argument" "$value" \
        -e screenshotDedicatedEmulator true -e readerInteractionsScreenshots 1 \
        com.fluxa.app.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "$reports/$label.log"
    ! grep -Eq '^INSTRUMENTATION_STATUS_CODE: -(3|4)' "$reports/$label.log"
    grep -Eq '^OK \([1-9][0-9]* tests?\)' "$reports/$label.log"
}
active=
cleanup() {
    local status=$?
    trap - EXIT
    if [[ "$active" = screenshots ]]; then
        run_phase screenshot-cleanup com.fluxa.app.AppScreenshotCaptureTest screenshotPhase cleanup || status=1
    elif [[ "$active" = interactions ]]; then
        run_phase reader-interactions-cleanup com.fluxa.app.ReaderInteractionsDeviceTest readerInteractionsPhase cleanup || status=1
    fi
    exit "$status"
}
trap cleanup EXIT
active=screenshots
run_phase screenshot-capture com.fluxa.app.AppScreenshotCaptureTest screenshotPhase capture
run_phase screenshot-cleanup com.fluxa.app.AppScreenshotCaptureTest screenshotPhase cleanup
active=
mkdir -p "$reports/app-screenshots"
while IFS= read -r name; do
    name=${name//$'\r'/}
    [[ "$name" =~ ^[a-zA-Z0-9_.-]+\.(png|json)$ ]] || continue
    "${adb_cmd[@]}" exec-out run-as com.fluxa.app.debug cat "files/app-screenshots/$name" > "$reports/app-screenshots/$name"
done < <("${adb_cmd[@]}" shell run-as com.fluxa.app.debug ls files/app-screenshots)
test -n "$(find "$reports/app-screenshots" -name '*.png' -print -quit)"
active=interactions
run_phase reader-interactions-seed com.fluxa.app.ReaderInteractionsDeviceTest readerInteractionsPhase seed
run_phase reader-interactions-verify com.fluxa.app.ReaderInteractionsDeviceTest readerInteractionsPhase verify
run_phase reader-interactions-cleanup com.fluxa.app.ReaderInteractionsDeviceTest readerInteractionsPhase cleanup
active=
