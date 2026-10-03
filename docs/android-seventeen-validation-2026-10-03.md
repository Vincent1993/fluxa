# Android 17 compatibility — 2026-10-03

Baseline: main b61c6d97995dd79f3c2ff549da28a6f802471d77. Candidate: 0.3.0 / code 4,
min API 26, compile/target API 37. Changes are split into compiler/dependency migration
and target-SDK/runtime adaptation commits.

## Compatible stable toolchain

| Component | Version |
| --- | --- |
| AGP / Gradle / JDK | 9.2.1 / 9.4.1 / 17 |
| Kotlin / Compose compiler | 2.4.20 / 2.4.20 |
| Compose BOM | 2026.09.00 |
| KSP / Room / Dagger Hilt | 2.3.12 / 2.8.5 / 2.60.1 |
| AndroidX Hilt / Navigation / WorkManager | 1.4.0 / 2.10.2 / 2.12.0 |
| SDK / Build Tools | 37.0 / 36.0.0 |

This combination stays within [Kotlin's fully supported range](https://kotlinlang.org/docs/gradle-configure-project.html).
[AndroidX Hilt 1.4](https://developer.android.com/jetpack/androidx/releases/hilt)
requires AGP >=9.2 for Compose. [AGP 9.2](https://developer.android.com/build/releases/agp-9-2-0-release-notes)
supports API 37.0 and requires Gradle 9.4.1, Build Tools 36.0.0 and JDK 17.
Built-in Kotlin/new DSL remain enabled; both Hilt and Room use KSP.
AGP 9's Debug-only unit-test default is overridden so CI still tests all three channels.

## Local execution

| Check | Actual result |
| --- | --- |
| Debug / Preview / Stable APK builds | All passed |
| Unit tests, each channel | 35 passed; zero failures/errors/skips |
| Lint, each channel | Zero errors/fatals; 18 warnings |
| Release/key/native guard tests | 18 passed |
| Legacy migration SQL fixtures | All 5 shapes passed |
| Room exported schema v3 | Unchanged, including identity hash |
| API 26 ARM64 complete device suite | 12 passed, including keyboard, system back, native loading, cache and restart |
| API 26 old-to-new same-certificate replacement | Passed without uninstalling; code 3 -> code 4 |
| APK 64-bit ELF LOAD and ZIP alignment | Passed for all channels; native LOAD alignment >=16384 |
| Mac API 37 ARM64 16 KB execution | Blocked at emulator boot; not counted as passed |

Logs and APK metadata are retained outside the repository at ../deliverables/android-seventeen
and ../toolchain/android-seventeen-*.log. Historical failures are retained alongside the
successful reruns; an instrumentation command's zero exit code alone does not indicate success.

The upgrade fixture is compiled in the old baseline worktree with its original Kotlin,
Room and coroutine runtime, then run with `fluxa.upgrade=seed`. The installer replaces the
app using `adb install -r`, and the new test APK runs `fluxa.upgrade=verify`. Assertions cover
body, read/star flags, subscription URL, tags, pagination cursor/token, pending action order,
payload/timestamps, encrypted account/session, sync preference and database version.
The old asynchronous preference write is explicitly flushed before instrumentation exits.
All credentials are fictional. This demonstrates Debug data continuity using an existing test
certificate; it does not demonstrate a persistent Preview/Stable release certificate.

The real API 26 keyboard test reproduced obscured cache controls. `adjustResize` plus
safe/inset padding fixes the failure. Reader controls scroll horizontally in narrow windows;
long titles are bounded so the body retains space. UI tests scroll controls into view and
wait for navigation, rather than assuming every control fits on screen immediately.
Screenshots are diagnostic: legacy emulator display overrides may return no frame.

## API 37 and 16 KB device CI

The approved `android-sdk-arm-dbt-license` was accepted on 2026-10-03 for
system-images;android-37.0;google_apis_ps16k;arm64-v8a revision 7. Its additional Arm
terms limit the relevant instruction-execution features to application development/debugging.
No unrelated licenses or Mac security settings were changed.
The installed Mac emulator 37.2.12 remains offline with QEMU2 memory-mapping errors;
HVF retries did not boot it, and the classic ARM64 engine is unavailable.

[Android device compatibility CI](../.github/workflows/android-device-ci.yml) runs fresh
Linux x86_64 API 26 and API 37.0 emulators. It asserts the actual API and page size
(4096 / 16384), performs the same baseline-to-candidate upgrade, runs the complete device
suite and uploads logs/screenshots. Results must be checked on the exact PR/main commit
before treating Android 17 runtime validation as passed. Static alignment and a build alone
do not replace these device checks.

The client uses public NewsBlur HTTPS; no LAN permission, TLS verification bypass,
background activity launch or new notification/storage permission is added. Existing hourly
unmetered/battery constraints, unique work and finite retry behavior remain tested.
Large-screen/orientation restrictions are not introduced. [Android 17 behavior changes](https://developer.android.com/about/versions/17/behavior-changes-17)
and [16 KB guidance](https://developer.android.com/guide/practices/page-sizes) informed these checks.

Live NewsBlur account acceptance, a physical Android device and persistent signed Preview
distribution remain separate outstanding checks. No signing secrets, security protections,
tags or releases were created by this migration.
