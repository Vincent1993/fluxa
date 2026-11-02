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
analyzer="$ANDROID_HOME/cmdline-tools/22.0/bin/apkanalyzer"
mkdir -p "$reports"
certificate() { "$signer" verify --print-certs "$1" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p'; }

if [[ "$mode" = prepare ]]; then
    [[ ! -e "$provider" ]] || { echo 'Provider directory already exists.' >&2; exit 1; }
    mkdir -p "$provider"
    # Use a later official donor while the actual acceptance AVDs remain API 36/37.
    # No image, package signature, minSDK or Android install checks are bypassed.
    image="$ANDROID_HOME/system-images/android-canary-20261007/google_apis_ps16k/x86_64"
    cp "$image/package.xml" "$reports/webview-source-image.xml"
    python3 - "$image/package.xml" <<'PY'
import sys, xml.etree.ElementTree as E
root = E.parse(sys.argv[1]).getroot()
package = next(p for p in root.iter() if p.tag.endswith('localPackage'))
assert package.attrib['path'] == 'system-images;android-canary-20261007;google_apis_ps16k;x86_64'
revision = next(p for p in package if p.tag.endswith('revision'))
actual_revision = next(p.text for p in revision if p.tag.endswith('major'))
assert actual_revision == '17', f'Expected official SDK image revision 17, got {actual_revision}'
PY
    donor_user="$RUNNER_TEMP/fluxa-webview-source-android"
    donor_avd="$donor_user/avd"
    mkdir -p "$donor_avd"
    ANDROID_USER_HOME="$donor_user" ANDROID_AVD_HOME="$donor_avd" \
        "$ANDROID_HOME/cmdline-tools/22.0/bin/avdmanager" create avd \
        --name fluxa-webview-source --package 'system-images;android-canary-20261007;google_apis_ps16k;x86_64' \
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
        "$analyzer" manifest print "$provider/$name.apk" > "$reports/webview-$name-manifest.xml"
        certificate "$provider/$name.apk" > "$reports/webview-$name-certificate.txt"
    done
    # Separate packages need not share a signer. A Trichrome consumer declares
    # the library name, version and certificate it trusts in its manifest.
    python3 scripts/webview_provider.py "$reports" | tee "$reports/webview-provider.json"
    (cd "$provider" && sha256sum WebViewGoogle.apk TrichromeLibrary.apk > SHA256SUMS)
    cp "$provider/SHA256SUMS" "$reports/webview-provider-SHA256SUMS"
elif [[ "$mode" = install ]]; then
    [[ "${ANDROID_SERIAL:-}" = emulator-5554 ]] || exit 1
    api=$("$adb" -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')
    [[ "$api" = 36 || "$api" = 37 ]] || exit 1
    (cd "$provider" && sha256sum -c SHA256SUMS)
    "$adb" -s "$ANDROID_SERIAL" shell dumpsys webviewupdate > "$reports/webview-before-update.txt"
    paths=()
    while IFS= read -r package_path; do paths+=("$package_path"); done \
        < <("$adb" -s "$ANDROID_SERIAL" shell pm path com.google.android.webview | tr -d '\r' | sed 's/^package://')
    [[ ${#paths[@]} = 1 && "${paths[0]}" = /*.apk ]] || exit 1
    "$adb" -s "$ANDROID_SERIAL" pull "${paths[0]}" "$provider/original-WebView.apk"
    original_cert=$(certificate "$provider/original-WebView.apk")
    [[ -n "$original_cert" && "$original_cert" = "$(certificate "$provider/WebViewGoogle.apk")" ]] || {
        echo 'Provider signatures differ; refusing replacement.' >&2; exit 1;
    }
    # Standard Android signature/version/dependency validation stays enabled.
    if [[ "$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["trichrome_required"])' \
            "$reports/webview-provider.json")" = True ]]; then
        "$adb" -s "$ANDROID_SERIAL" install --no-streaming "$provider/TrichromeLibrary.apk"
    fi
    "$adb" -s "$ANDROID_SERIAL" install --no-streaming -r "$provider/WebViewGoogle.apk"
    "$adb" -s "$ANDROID_SERIAL" shell dumpsys webviewupdate > "$reports/webview-after-update.txt"
    version=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["webview_version"])' \
        "$reports/webview-provider.json")
    # Package installation and WebViewUpdateService's package-change handling are
    # asynchronous. Require the same exact active provider, after it is published;
    # a successful installer or a merely preferred/available package is insufficient.
    ready_deadline=$((SECONDS + 30))
    until grep -F "Current WebView package (name, version): (com.google.android.webview, $version)" \
            "$reports/webview-after-update.txt"; do
        (( SECONDS < ready_deadline )) || {
            cat "$reports/webview-after-update.txt" >&2
            echo 'Installed WebView did not become the active provider within 30 seconds.' >&2
            exit 1
        }
        sleep 1
        "$adb" -s "$ANDROID_SERIAL" shell dumpsys webviewupdate > "$reports/webview-after-update.txt"
    done
else
    echo 'Unsupported provider operation.' >&2; exit 1
fi
