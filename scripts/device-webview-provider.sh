#!/usr/bin/env bash
# CI-only provider comparison. APKs come unchanged from Google's SDK image.
set -euo pipefail
[[ $# = 3 ]] || { echo 'Usage: prepare|install provider-dir reports-dir' >&2; exit 1; }
mode=$1 provider=$2 reports=$3
[[ "${GITHUB_ACTIONS:-}" = true && "${RUNNER_OS:-}" = Linux ]] || {
    echo 'Only the isolated Linux Actions runner may run this helper.' >&2; exit 1;
}
: "${ANDROID_HOME:?}" "${RUNNER_TEMP:?}"
[[ "$provider" = "$RUNNER_TEMP"/fluxa-webview-provider ]] || exit 1
adb="$ANDROID_HOME/platform-tools/adb"
signer="$ANDROID_HOME/build-tools/36.0.0/apksigner"
aapt="$ANDROID_HOME/build-tools/36.0.0/aapt"
mkdir -p "$reports"
certificate() { "$signer" verify --print-certs "$1" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p'; }

if [[ "$mode" = prepare ]]; then
    [[ ! -e "$provider" ]] || { echo 'Provider directory already exists.' >&2; exit 1; }
    mkdir -p "$provider"
    image="$ANDROID_HOME/system-images/android-37.0/google_apis_ps16k/x86_64"
    cp "$image/package.xml" "$reports/webview-source-image.xml"
    python3 - "$image/package.xml" <<'PY'
import sys, xml.etree.ElementTree as E
root = E.parse(sys.argv[1]).getroot()
package = next(p for p in root.iter() if p.tag.endswith('localPackage'))
assert package.attrib['path'] == 'system-images;android-37.0;google_apis_ps16k;x86_64'
revision = next(p for p in package if p.tag.endswith('revision'))
assert next(p.text for p in revision if p.tag.endswith('major')) == '8'
PY
    donor_user="$RUNNER_TEMP/fluxa-webview-source-android"
    donor_avd="$donor_user/avd"
    mkdir -p "$donor_avd"
    ANDROID_USER_HOME="$donor_user" ANDROID_AVD_HOME="$donor_avd" \
        "$ANDROID_HOME/cmdline-tools/22.0/bin/avdmanager" create avd \
        --name fluxa-webview-source --package 'system-images;android-37.0;google_apis_ps16k;x86_64' \
        --device pixel_2 --path "$donor_avd/fluxa-webview-source.avd" <<< no
    printf 'hw.cpu.ncore=2\nhw.ramSize=2048\nhw.keyboard=no\n' >> "$donor_avd/fluxa-webview-source.avd/config.ini"
    "$adb" start-server
    kvm_gid=$(getent group kvm | cut -d: -f3)
    sudo -n setpriv --reuid "$(id -u)" --regid "$kvm_gid" --clear-groups --no-new-privs \
        -- /usr/bin/env HOME="$HOME" USER="$USER" ANDROID_HOME="$ANDROID_HOME" \
        ANDROID_USER_HOME="$donor_user" ANDROID_AVD_HOME="$donor_avd" \
        "$ANDROID_HOME/emulator/emulator" -avd fluxa-webview-source -port 5556 \
        -no-window -no-audio -no-snapshot -no-boot-anim -gpu swangle -feature -Vulkan -accel on \
        > "$reports/webview-source-emulator.log" 2>&1 &
    donor_pid=$!
    trap 'timeout 10s "$adb" -s emulator-5556 emu kill >/dev/null 2>&1 || true' EXIT
    deadline=$((SECONDS + 600))
    until [[ "$("$adb" -s emulator-5556 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]]; do
        kill -0 "$donor_pid" && (( SECONDS < deadline )) || { echo 'Provider source emulator failed to boot.' >&2; exit 1; }
        sleep 2
    done
    "$adb" -s emulator-5556 shell dumpsys webviewupdate > "$reports/webview-source-state.txt"
    for name in WebViewGoogle TrichromeLibrary; do
        "$adb" -s emulator-5556 exec-out cat "/product/app/$name/$name.apk.gz" > "$provider/$name.apk.gz"
        gzip -dc "$provider/$name.apk.gz" > "$provider/$name.apk"
        "$signer" verify "$provider/$name.apk"
        "$aapt" dump badging "$provider/$name.apk" > "$reports/webview-$name-badging.txt"
    done
    grep -F "package: name='com.google.android.webview'" "$reports/webview-WebViewGoogle-badging.txt"
    grep -F "versionName='145.0.7632.218'" "$reports/webview-WebViewGoogle-badging.txt"
    grep -E "sdkVersion:'([0-9]|[12][0-9]|3[0-6])'" "$reports/webview-WebViewGoogle-badging.txt"
    web_cert=$(certificate "$provider/WebViewGoogle.apk")
    [[ -n "$web_cert" && "$web_cert" = "$(certificate "$provider/TrichromeLibrary.apk")" ]]
    printf '%s\n' "$web_cert" > "$reports/webview-provider-certificate.txt"
    (cd "$provider" && sha256sum WebViewGoogle.apk TrichromeLibrary.apk > SHA256SUMS)
    cp "$provider/SHA256SUMS" "$reports/webview-provider-SHA256SUMS"
elif [[ "$mode" = install ]]; then
    [[ "${ANDROID_SERIAL:-}" = emulator-5554 ]] || exit 1
    [[ "$("$adb" -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')" = 36 ]] || exit 1
    (cd "$provider" && sha256sum -c SHA256SUMS)
    "$adb" -s "$ANDROID_SERIAL" shell dumpsys webviewupdate > "$reports/webview-before-update.txt"
    mapfile -t paths < <("$adb" -s "$ANDROID_SERIAL" shell pm path com.google.android.webview | tr -d '\r' | sed 's/^package://')
    [[ ${#paths[@]} = 1 && "${paths[0]}" = /*.apk ]] || exit 1
    "$adb" -s "$ANDROID_SERIAL" pull "${paths[0]}" "$provider/original-WebView.apk"
    original_cert=$(certificate "$provider/original-WebView.apk")
    [[ -n "$original_cert" && "$original_cert" = "$(certificate "$provider/WebViewGoogle.apk")" ]] || {
        echo 'Provider signatures differ; refusing replacement.' >&2; exit 1;
    }
    # Standard Android signature/version/dependency validation stays enabled.
    "$adb" -s "$ANDROID_SERIAL" install --no-streaming "$provider/TrichromeLibrary.apk"
    "$adb" -s "$ANDROID_SERIAL" install --no-streaming -r "$provider/WebViewGoogle.apk"
    "$adb" -s "$ANDROID_SERIAL" shell dumpsys webviewupdate > "$reports/webview-after-update.txt"
    grep -F 'Current WebView package (name, version): (com.google.android.webview, 145.0.7632.218)' \
        "$reports/webview-after-update.txt"
else
    echo 'Unsupported provider operation.' >&2; exit 1
fi
