# Background refresh validation — 2026-10-02

This ports the useful scheduling feature from legacy PR #4 onto main after PR #7.
It does not change Room schema, erase caches, or restore old Inoreader calls.

| Executed check | Result |
| --- | --- |
| assembleDebug / assemblePreview / assembleRelease | All passed |
| testDebugUnitTest / testPreviewUnitTest / testReleaseUnitTest | Each 34 tests, zero failures/errors/skips |
| lintDebug / lintPreview / lintRelease | Each zero errors / 19 warnings locally; primarily existing dependency freshness warnings |
| connectedDebugAndroidTest | 9 tests, zero failures/errors/skips, isolated API 35 ARM64 AVD |
| Release policy and key-helper guard tests | 15 passed; helper uses fake keytool, no release key created |
| verify-migration-sql.py | All five legacy schema fixtures preserved data |

The worker tests cover default-off and logged-out behavior, background-specific refresh,
unique scheduling, persisted opt-in, restoration/cancellation, finite offline retries,
429/503/401 handling, business rejection, and cancellation propagation.
MockWebServer and Room verify all subscriptions refresh while foreground source selection
is preserved. Device tests cover settings UI, real application WorkManager initialization,
unique task enqueue/cancel, and the existing Activity/Hilt/Room/WebView offline flow.

An initial test run exposed eager encrypted-store construction through the injected worker
factory. The factory now uses lazy repository/auth providers; all existing tests passed
after correction. This also avoids opening account storage at every application startup.

Local logs: ../toolchain/background-all.log and background-final-tests.log.
The first log records all three APK builds plus the 9 device tests; the final log records
the expanded 34-test suites and lint. Reports remain under app/build/reports and
app/build/test-results, with device XML under app/build/outputs/androidTest-results.

No live NewsBlur account was used. No actual one-hour operating-system scheduling,
physical-device battery/network behavior or process-restart background transfer was observed.
The network flow and scheduler were exercised deterministically rather than waiting an hour.
Preview/Stable remain unsigned in credential-free builds; this does not establish a signed
Preview release or same-signature upgrade. Stable publication remains disabled.
