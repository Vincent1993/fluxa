# Dependency PR compatibility audit — 2026-10-02

Baseline main: ad803ad477c81dfc100d09a54c3c796c1f32131b.
Reviewed PR #9 head 9b3702b89fff9c41ed87a0c730e3bc653f575496 and
PR #11 head 6dea76fe26bf7dba8bdcb1a0aa4e830e498e1fee, including actual patches and CI logs.

| PR | Observed failure | Decision |
| --- | --- | --- |
| #9: Gradle 8.9 to 9.8.0 | [CI run](https://github.com/Vincent1993/fluxa/actions/runs/37047027534) fails while configuring KAPT. Kotlin 1.9.24's Kapt3GradleSubplugin calls Configuration.fileCollection(Spec), which is unavailable on the new Gradle. | Keep unmerged. Updating the wrapper alone cannot fix an incompatible plugin implementation; suppressing the error or skipping annotation processing would break Hilt. A coordinated Kotlin/KSP/Compose/build-tool migration needs its own scope. |
| #11: 18 grouped upgrades | [CI run](https://github.com/Vincent1993/fluxa/actions/runs/37049579816) fails applying Hilt 2.60.1: AGP >=9.0.0 is required, but the repository has AGP 8.5.2. | Keep the grouped PR unmerged. Room 2.8 additionally requires Kotlin >=2.0. Renaming the group or bypassing the version check would not make these dependencies compatible. Extract a small compatible patch independently. |

[Hilt's official release notes](https://github.com/google/dagger/releases/tag/dagger-2.59)
state that Hilt 2.59+ requires AGP 9 and its Gradle requirements.
[AGP 9 migration notes](https://developer.android.com/build/releases/agp-9-0-0-release-notes)
describe the built-in Kotlin / new DSL migration, Kotlin >=2 requirements and coordinated KSP changes.
[Room release notes](https://developer.android.com/jetpack/androidx/releases/room)
confirm the Kotlin 2 language requirement beginning with Room 2.7.
These are changes to the compiler, processors and build integration, not a missing CI environment variable.

The independent patch updates only moshi-kotlin 1.15.1 to 1.15.2. Its transitive core Moshi
also resolves to 1.15.2. The [official changelog](https://github.com/square/moshi/blob/master/CHANGELOG.md)
describes an adjustment to generated shrinker rules; this is a maintenance patch, not a
claimed fix for a reproduced Fluxa runtime bug. Current APKs disable minification, so
no immediate shrinker-performance benefit is claimed. The JSON reflection adapter used by
NewsBlur remains in the 1.15 series, avoiding unrelated Kotlin/build-tool/database changes.

Existing protocol and end-to-end fixture tests verify feed decoding, nullable/error
responses, authentication contracts, offline replay and pagination against the resolved patch.
Local execution passed: all three channel builds; 34/34 unit tests per channel;
9/9 API 35 device tests; lint zero errors / 19 warnings per channel.
The resolved runtime dependency comparison found exactly two changes: moshi and
moshi-kotlin 1.15.1 to 1.15.2. Kotlin, Okio and all other resolved artifacts were unchanged.
Log: ../toolchain/dependency-patch-validation.log; dependency baseline:
../toolchain/dependency-before.log. Remote CI and merged-main results are reported
separately after execution; a successful local check alone is not a passed PR check.
No tests were disabled, legacy PRs closed, or security/signing/release settings changed.
