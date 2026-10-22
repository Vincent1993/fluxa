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
        "com.fluxa.app.debug.test/${FLUXA_DEVICE_TEST_RUNNER:-androidx.test.runner.AndroidJUnitRunner}" | tee "$reports/$label.log" || return 1
    if grep -Eq '^INSTRUMENTATION_STATUS_CODE: -(3|4)' "$reports/$label.log"; then return 1; fi
    grep -Eq '^OK \([1-9][0-9]* tests?\)' "$reports/$label.log"
}
export_app_evidence() {
    mkdir -p "$reports/app-screenshots"
    while IFS= read -r name; do
        name=${name//$'\r'/}
        [[ "$name" =~ ^[a-zA-Z0-9_.-]+\.(png|json)$ ]] || continue
        "${adb_cmd[@]}" exec-out run-as com.fluxa.app.debug cat "files/app-screenshots/$name" > "$reports/app-screenshots/$name"
        case "$name" in
            02-list.png|13-reader-hero.png|14-reader-image.png|19-reading-settings.png)
                printf 'FLUXA_APP_SCREENSHOT_%s_BASE64=%s\n' "$name" "$(base64 -w 0 "$reports/app-screenshots/$name")" ;;
        esac
    done < <("${adb_cmd[@]}" shell run-as com.fluxa.app.debug ls files/app-screenshots)
}
export_reader_evidence() {
    mkdir -p "$reports/reader-interactions"
    while IFS= read -r name; do
        name=${name//$'\r'/}
        [[ "$name" =~ ^reeder-interaction-2026-10-08-(evidence-[0-9]+-(middle-hidden-tools|reverse-restored-tools)\.png|verified-state\.json)$ ]] || continue
        "${adb_cmd[@]}" exec-out run-as com.fluxa.app.debug cat "files/$name" > "$reports/reader-interactions/$name"
    done < <("${adb_cmd[@]}" shell run-as com.fluxa.app.debug ls files)
}
export_media_evidence() {
    mkdir -p "$reports/reader-media"
    while IFS= read -r name; do
        name=${name//$'\r'/}
        [[ "$name" =~ ^fluxa-stage1c-owned-api36-media-[a-f0-9-]+-[a-z0-9-]+\.png$ ]] || continue
        "${adb_cmd[@]}" exec-out run-as com.fluxa.app.debug cat "files/$name" > "$reports/reader-media/$name"
        case "$name" in
            *-viewer-fit.png|*-viewer-pinch-pan.png)
                printf 'FLUXA_MEDIA_SCREENSHOT_%s_BASE64=%s\n' "$name" "$(base64 -w 0 "$reports/reader-media/$name")" ;;
        esac
    done < <("${adb_cmd[@]}" shell run-as com.fluxa.app.debug ls files)
}
active=
owned_marker=
cleanup() {
    local status=$?
    trap - EXIT
    if [[ "$active" = screenshots ]]; then
        export_app_evidence || status=1
        run_phase screenshot-cleanup com.fluxa.app.AppScreenshotCaptureTest screenshotPhase cleanup || status=1
    elif [[ "$active" = interactions ]]; then
        export_reader_evidence || status=1
        run_phase reader-interactions-cleanup com.fluxa.app.ReaderInteractionsDeviceTest readerInteractionsPhase cleanup || status=1
    fi
    if [[ -n "$owned_marker" ]]; then
        export_media_evidence || status=1
        "${adb_cmd[@]}" shell run-as com.fluxa.app.debug rm "files/$owned_marker.marker" || status=1
    fi
    exit "$status"
}
trap cleanup EXIT
overall=0
active=screenshots
run_phase screenshot-capture com.fluxa.app.AppScreenshotCaptureTest screenshotPhase capture || overall=1
active=
run_phase screenshot-cleanup com.fluxa.app.AppScreenshotCaptureTest screenshotPhase cleanup || exit 1
export_app_evidence || overall=1
test -n "$(find "$reports/app-screenshots" -name '*.png' -print -quit)" || overall=1
# This is the isolated CI AVD. The interaction fixture requires actual offline
# connectivity and asserts it itself before touching any data.
"${adb_cmd[@]}" shell svc wifi disable
"${adb_cmd[@]}" shell svc data disable
"${adb_cmd[@]}" shell cmd connectivity airplane-mode enable
active=interactions
if run_phase reader-interactions-seed com.fluxa.app.ReaderInteractionsDeviceTest readerInteractionsPhase seed; then
    run_phase reader-interactions-verify com.fluxa.app.ReaderInteractionsDeviceTest readerInteractionsPhase verify || overall=1
else
    overall=1
fi
export_reader_evidence || overall=1
active=
run_phase reader-interactions-cleanup com.fluxa.app.ReaderInteractionsDeviceTest readerInteractionsPhase cleanup || exit 1
# These existing probes require an explicit owner on a fresh API 36 device.
# Their synthetic fixtures retain real native touch, geometry and accessibility assertions.
if [[ "$("${adb_cmd[@]}" shell getprop ro.build.version.sdk | tr -d '\r')" = 36 ]]; then
    for probe in media triage; do
        if [[ "$probe" = media ]]; then
            owner=fluxa-stage1c-owned-api36
            class=com.fluxa.app.ReaderMediaStage1cTest
            argument=stage1cOwner
        else
            owner=fluxa-stage3-owned-api36
            class=com.fluxa.app.TriageStage3UiTest
            argument=stage3Owner
        fi
        if "${adb_cmd[@]}" shell run-as com.fluxa.app.debug test -e "files/$owner.marker"; then
            echo "Existing ownership marker: $owner" >&2; exit 1
        fi
        printf '%s\n' "$owner" | "${adb_cmd[@]}" shell run-as com.fluxa.app.debug sh -c "'cat > files/$owner.marker'"
        owned_marker=$owner
        run_phase "reader-$probe" "$class" "$argument" "$owner" || overall=1
        export_media_evidence || overall=1
        "${adb_cmd[@]}" shell run-as com.fluxa.app.debug rm "files/$owned_marker.marker"
        owned_marker=
    done
fi
# Collect already-required independent probes after verified fixture cleanup, but
# never turn a failed/skipped phase into a passing job or permit publication.
exit "$overall"
