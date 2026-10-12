# Android channels and releases

| Channel | Task | Package | Label | Signing |
| --- | --- | --- | --- | --- |
| Debug | assembleDebug | com.fluxa.app.debug | Fluxa Debug | Local/temporary debug key |
| Preview | assemblePreview | com.fluxa.app.preview | Fluxa Preview | Shared persistent release certificate |
| Stable | assembleRelease | com.fluxa.app | Fluxa | Same persistent release certificate as Preview |

namespace remains com.fluxa.app. Debug is debuggable; Preview and Stable are not.
Version names carry the channel and short commit for Debug/Preview; Stable is MAJOR.MINOR.PATCH.
All use the committed VERSION_CODE. Each new published tag requires a code greater than all earlier release tags.
Preview (`com.fluxa.app.preview`, Fluxa Preview) and Stable (`com.fluxa.app`, Fluxa)
use one owner-approved long-term signing identity. Their distinct application IDs allow simultaneous
installation. Private databases, notes, login sessions and pending queues remain application-scoped;
the manifest does not request sharedUserId, and provider authorities include applicationId.
Debug continues to use its temporary development certificate.
If both apps sign into the same NewsBlur account, server-synced read/star state naturally belongs to
that account; sharing a certificate does not merge local data.
Preview does not replace Stable or automatically transfer its local data. Each channel updates its
own installation: Preview keeps com.fluxa.app.preview; Stable keeps com.fluxa.app. Compatible updates
must keep that channel's application ID and the same shared long-term certificate, and satisfy version-code
checks. A different signature must not be bypassed by uninstalling an installation with valuable data.

## Normal CI

Android CI runs for PRs targeting main, main pushes and manual invocation.
Three jobs each execute unit tests, lint, assembly and actual manifest checks.
Debug signatures are verified. Preview/Stable must be unsigned in secret-free CI:
they are build verification outputs, not installable releases, and are not offered as installable artifacts.
Reports include package, version, debuggable flag, checksum and source commit.
The installable Debug artifact and reports last 14 days.
Branch naming applies to new human PRs; historical PR #1–6 and Dependabot are exempt.

## Owner-controlled shared signing setup

The owner has approved **one long-term signing identity shared by Preview and Stable**.
Generation approval does not mean a keystore has already been generated. No new persistent key,
password file or signing configuration was created by this documentation update.

The current Preview candidate is v0.3.0-preview.1 / code 4. Public Releases has no versions, and
current environment/secret configuration remains unverified: this connector does not expose those
endpoints. Missing query access must not be described as missing credentials.

Generation must wait until the owner can securely retrieve and back up the key. The temporary cloud
workspace has keytool and encryption tools, but no verified owner-controlled retrieval path. Do not
create the sole long-term key there or generate/upload it through GitHub Actions. The owner can use
their own Mac locally; key generation itself does not require an internet connection.

For the approved shared-key setup:

1. Select a private directory **outside the repository**, for example
   `~/Library/Application Support/FluxaSigning`. Use restrictive directory/file permissions
   (700 / 600), and stop if the chosen keystore or password-file name already exists.
2. In an owner-controlled terminal or signing UI, generate one encrypted PKCS12 keystore using an
   existing keytool/JDK, with a secure random password kept outside assistant-visible output.
   `fluxa-release` is a proposed alias, not an existing key. Do not create a second key for Stable.
3. Confirm an encrypted owner-controlled backup of the keystore and password before configuring CI.
   Private key bytes/passwords must not enter chat, logs, Git, Library or Actions artifacts. Only the
   public certificate fingerprint and alias may be reported to the assistant.
4. The owner configures the following effective settings for **both** `preview` and `stable` environments,
   using the same keystore, alias, passwords and public certificate fingerprint. Secret transmission
   and submission are performed by the owner in their secure session, never by the assistant.

| Kind | Name | Shared value source |
| --- | --- | --- |
| Secret | ANDROID_KEYSTORE_BASE64 | Owner's same encrypted PKCS12 keystore |
| Secret | ANDROID_KEYSTORE_PASSWORD | Owner's private keystore password |
| Secret | ANDROID_KEY_ALIAS | Same actual alias in that keystore |
| Secret | ANDROID_KEY_PASSWORD | Same private key password |
| Variable | ANDROID_SIGNING_CERT_SHA256 | Same public SHA-256 certificate fingerprint |

The legacy `scripts/prepare-preview-key.sh` writes inside the repository's `.signing` directory.
It is not the prescribed generator for this new outside-repository shared-key setup; do not run it
as part of this handoff. No generator is executed by a PR or ordinary CI.

Public publication remains a separate explicit action. Stable still requires
`ENABLE_STABLE_RELEASE=true`, its exact Stable changelog section, an increasing version code and
acceptance of the installed signed candidate. Sharing a key does not bypass these checks.
Environment restrictions/reviews and main/tag protection are separate settings; this update changes
none of them. Preserve existing keys and settings until the owner has verified their identity.

## Prepare a release

After the integration PR is merged, both workflow_dispatch entries become available from main.
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

Only an explicit release dispatch creates tags/drafts. PRs, branch pushes and Dependabot cannot publish.
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

## Current release readiness

The manual workflow in `.github/workflows/android-release.yml` is independent of PR/main validation.
It does not run for each development commit and never publishes a draft automatically. It prepares
APK, SHA256SUMS, release notes and source/certificate metadata only after persistent signing passes.

Initial Preview plan: after main CI passes and the owner confirms the shared release fingerprint and four
protected-environment secrets, dispatch from main with preview / v0.3.0-preview.1. Verify the downloaded
signed APK and real-account behavior, then explicitly approve publication of that draft. Stable uses the same signing identity, with its own changelog, increasing version code
and installation/upgrade acceptance.

No signing key was generated or read, no secrets uploaded, and no tag or Release created during branch
integration. The connector returned HTTP 400 for environment metadata because its fetch allowlist does
not expose that endpoint. The owner can confirm setup in GitHub Settings → Environments without
sharing any private values in chat.
