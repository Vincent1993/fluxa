# Android channels and releases

| Channel | Task | Package | Label | Signing |
| --- | --- | --- | --- | --- |
| Debug | assembleDebug | com.fluxa.app.debug | Fluxa Debug | Local/temporary debug key |
| Preview | assemblePreview | com.fluxa.app.preview | Fluxa Preview | Persistent Preview certificate |
| Stable | assembleRelease | com.fluxa.app | Fluxa | Separate persistent Stable certificate |

namespace remains com.fluxa.app. Debug is debuggable; Preview and Stable are not.
Version names carry the channel and short commit for Debug/Preview; Stable is MAJOR.MINOR.PATCH.
All use the committed VERSION_CODE. Each new published tag requires a code greater than all earlier release tags.
The three apps can coexist and have separate accounts, databases and pending queues.
Preview is not automatically converted into Stable. Upgrades within a channel retain data only with the same package and signing certificate.

## Normal CI

Android CI runs for PRs targeting main, main pushes and manual invocation.
Three jobs each execute unit tests, lint, assembly and actual manifest checks.
Debug signatures are verified. Preview/Stable must be unsigned in secret-free CI:
they are build verification outputs, not installable releases, and are not offered as installable artifacts.
Reports include package, version, debuggable flag, checksum and source commit.
The installable Debug artifact and reports last 14 days.
Branch naming applies to new human PRs; historical PR #1–6 and Dependabot are exempt.

## Owner approval and secure signing setup

Before first publication, approve the exact target: PR #7 into main; initial Preview tag
v0.3.0-preview.1 / code 4; public prerelease in Vincent1993/fluxa.
The workflow creates a draft first. Public publication is a separate explicit action.
Stable publication is disabled until the owner separately configures its signing and sets
the repository variable ENABLE_STABLE_RELEASE=true; no Stable is planned in the first run.

Approve creation of the preview environment, restricted to main. Optional environment review
must allow the owner to approve their own run in a one-person repository.
main/tag protection settings are described in CONTRIBUTING.md and need separate approval.

The owner generates and uploads credentials in their own Mac terminal/browser secure takeover.
The agent must not receive passwords in chat, read private keystore bytes or upload these secrets.
No release key has been created merely by adding these scripts.

After approving generation, use JDK 17 and run interactively:

```sh
bash scripts/prepare-preview-key.sh
```

On this Mac, first enter the checkout and activate the existing isolated JDK:

```sh
cd /Users/admin/Documents/Codex/2026-10-02/task/fluxa
source ../toolchain/env.sh
bash scripts/prepare-preview-key.sh
```

Use your own interactive Terminal; do not pipe or redirect this script or enable terminal recording.
It requires terminal stdin/stdout/stderr, disables shell command tracing and leaves password
prompts connected to the terminal. This prompts for a password without adding it to command arguments.
PKCS12 uses the same store
and key password. It creates .signing/preview.p12, a one-line Base64 file and a public certificate
report plus preview-public.pem (public certificate only), refuses overwrites, and applies private file permissions.
Only the password-free public certificate inspection is redirected to the report. Keep an encrypted backup of
the keystore and password outside this checkout. Losing the key prevents compatible updates.

In GitHub → Settings → Environments → preview, the owner configures:

| Kind | Name | Value |
| --- | --- | --- |
| Secret | ANDROID_KEYSTORE_BASE64 | Contents of .signing/preview-keystore.base64 |
| Secret | ANDROID_KEYSTORE_PASSWORD | Password entered during generation |
| Secret | ANDROID_KEY_ALIAS | fluxa-preview |
| Secret | ANDROID_KEY_PASSWORD | Same PKCS12 password |
| Variable | ANDROID_SIGNING_CERT_SHA256 | SHA256 certificate fingerprint from the public certificate report |

GitHub CLI is an optional owner-operated upload path after their own secure login:

```sh
gh secret set ANDROID_KEYSTORE_BASE64 --repo Vincent1993/fluxa --env preview < .signing/preview-keystore.base64
gh secret set ANDROID_KEYSTORE_PASSWORD --repo Vincent1993/fluxa --env preview
gh secret set ANDROID_KEY_ALIAS --repo Vincent1993/fluxa --env preview
gh secret set ANDROID_KEY_PASSWORD --repo Vincent1993/fluxa --env preview
gh variable set ANDROID_SIGNING_CERT_SHA256 --repo Vincent1993/fluxa --env preview
```

Enter prompted values privately. Do not paste credentials into agent messages or terminals controlled by the agent.
Remove the temporary Base64 transfer file after the owner confirms successful configuration and secure backup.
The signing certificate fingerprint is public metadata; private key bytes and passwords are not.

## Prepare a release

After the approved PR is merged, both workflow_dispatch entries become available from main.
Choose Actions → Prepare Android Release → Run workflow → main, channel preview,
tag v0.3.0-preview.1. Choosing another branch fails before the signing job.
The selected main commit is pinned throughout the run; moving main does not change the candidate.

1. A read-only job checks main ancestry, exact tag/version/changelog and increasing code.
   It tests, lints, builds and records the checksum of the unsigned candidate.
2. The protected environment job downloads that exact candidate and checks its identity/hash.
   Signing uses Android apksigner directly; no build script runs with a private key.
   Missing credentials fail. Generic Android Debug certificates or a mismatched pinned fingerprint fail.
   The temporary keystore is deleted even on failure.
3. Only the draft job gets contents:write. It rechecks main ancestry, version and APK checksum,
   creates a tag pointing to the exact verified commit and creates a draft release.
   Preview is marked prerelease and not latest. APK, SHA256SUMS and release-metadata.json are attached.
   No workflow step publishes the draft.

Only a newly authorized dispatch creates tags/drafts. PRs, branch pushes and Dependabot cannot publish.
Actions are pinned to full commit SHAs. Signing jobs do not write Gradle caches.
The workflow never uploads keystores or unsigned APKs as public Release assets.

## Verify and publish

Download the draft APK and checksum on the Mac. Check SHA-256 and the recorded persistent
certificate fingerprint, install the Preview on an isolated emulator or the owner's device,
and verify launch, offline operations and data-preserving upgrade from the previous Preview.
The first Preview has no previous installed version; later releases require the upgrade check.
Real NewsBlur login/subscription/server-state acceptance requires the owner's account and is
separate from fixture-based tests. Images are not guaranteed offline.

After explicit approval, the owner or authorized agent publishes that existing draft as a
prerelease. The public Release assets provide the lasting download channel; staging artifacts
expire after 7 days and validation reports after 14 days.
Retain release-metadata.json with commit, package, code, checksum and signing fingerprint.

Existing releases are never overwritten automatically. If upload fails after tag creation,
preserve the tag; inspect the partial draft and missing assets, verify the same commit/checksum,
then explicitly authorize recovery. Do not move a published tag or replace an APK under it.
