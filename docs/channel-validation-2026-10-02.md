# Channel validation — 2026-10-02

Local preparation for the Preview release pipeline. Version source: 0.2.0 / code 3 / preview 1.
No persistent release key was generated or uploaded as part of these checks.

| Check | Result |
| --- | --- |
| assembleDebug, assemblePreview, assembleRelease | Passed; 153 tasks, 126 executed, 27 up-to-date |
| testDebugUnitTest | 26 tests, zero failures/errors/skips |
| testPreviewUnitTest | 26 tests, zero failures/errors/skips |
| testReleaseUnitTest | 26 tests, zero failures/errors/skips |
| lintDebug / lintPreview / lintRelease | Each: zero errors, 17 warnings on this Mac |
| connectedDebugAndroidTest | 7/7 passed in isolated API 35 ARM64 AVD |
| Actual APK manifest checks | All three distinct packages, labels and expected versions; only Debug debuggable |
| APK signing boundary | Debug signature verified; Preview/Stable unsigned in credential-free builds |
| Missing signing configuration | Required-signing build refused before producing a signed artifact |
| Release policy tests | 10/10: invalid versions/tags, stale code, wrong source/tag commit, unsigned/wrong/debug certificate |
| Key takeover helper guards | Stub keytool only: terminal/output restrictions, no private-byte output, private permissions, overwrite refusal; no real key created |
| Workflow checks | YAML parsed; shell and embedded Python syntax checked; all action SHAs pinned |
| Workflow permissions | Only manual release trigger; ordinary CI read-only; secrets confined to signer, writer confined to draft job |

These checks validate code and unsigned distribution builds. They do not establish that a
signed Preview can be installed. Persistent signing, secure user credential setup, main merge,
remote release dispatch, draft artifact download/install and public publication are subsequent
gated steps. GitHub CI must confirm the final pushed commit independently.

Actions download-artifact v8.0.1 is pinned to 3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c;
its official action.yml inputs and default digest-mismatch=error were checked.
Existing checkout, setup-java, setup-gradle and upload-artifact pins remain unchanged.
No actionlint tool was installed; syntax/schema checks are not a substitute for remote execution.

The local logs are outside the repository in ../toolchain/channel-build.log,
channel-tests.log, channel-device-tests.log and missing-signing.log.
Real NewsBlur account acceptance and a data-preserving Preview-to-Preview upgrade have not run.
Stable stays disabled and is not planned for this first publication.
