import unittest
from pathlib import Path
from unittest.mock import patch
import release

VERSION = {"VERSION_NAME": "0.2.0", "VERSION_CODE": "3", "PREVIEW_NUMBER": "1"}
SHA = "a" * 40


class ReleaseChecksTest(unittest.TestCase):
    def test_version_rejects_duplicates_unknowns_and_invalid_codes(self):
        base = "VERSION_NAME=0.2.0\nVERSION_CODE=3\nPREVIEW_NUMBER=1\n"
        self.assertEqual(release.read_version(base), VERSION)
        for invalid in (base + "VERSION_CODE=4\n", base + "SECRET=oops\n",
                        base.replace("VERSION_CODE=3", "VERSION_CODE=0"),
                        base.replace("VERSION_CODE=3", "VERSION_CODE=2100000001"),
                        base.replace("0.2.0", "02.0.0")):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                release.read_version(invalid)

    def test_channels_have_distinct_ids_and_correct_versions(self):
        values = [release.expected(VERSION, c, SHA) for c in release.CHANNELS]
        self.assertEqual(len({v["application_id"] for v in values}), 3)
        self.assertEqual(values[1]["version_name"], "0.2.0-preview.1+aaaaaaa")
        self.assertEqual(values[2]["version_name"], "0.2.0")

    def test_wrong_tag_channel_and_commit_are_rejected(self):
        release.validate_release(VERSION, "preview", "v0.2.0-preview.1", SHA)
        for channel, tag, sha in (("stable", "v0.2.0-preview.1", SHA),
                                  ("preview", "v0.2.0-preview.2", SHA),
                                  ("preview", "v0.2.0-preview.1; echo x", SHA),
                                  ("preview", "v0.2.0-preview.1", "main")):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                release.validate_release(VERSION, channel, tag, sha)

    def test_changelog_selects_exact_section_and_requires_content(self):
        text = "## [Unreleased]\nFuture\n## [0.2.0-preview.1] - date\nPreview\n## [0.1.0]\nOld\n"
        self.assertEqual(release.release_notes(text, "v0.2.0-preview.1"), "Preview\n")
        for wrong in ("## [0.2.0-preview.10]\nOther", "## [0.2.0-preview.1]\n\n"):
            with self.assertRaises(ValueError):
                release.release_notes(wrong, "v0.2.0-preview.1")

    def test_code_must_increase_across_both_published_channels(self):
        release.check_history(VERSION, "v0.2.0-preview.1",
                              [("v0.1.0", {**VERSION, "VERSION_CODE": "2"})])
        for code in ("3", "4"):
            with self.assertRaises(ValueError):
                release.check_history(VERSION, "v0.2.0-preview.1",
                                      [("v0.1.0", {**VERSION, "VERSION_CODE": code})])

    def test_tag_retry_cannot_change_version(self):
        release.check_history(VERSION, "v0.2.0-preview.1", [("v0.2.0-preview.1", VERSION)])
        with self.assertRaises(ValueError):
            release.check_history(VERSION, "v0.2.0-preview.1",
                                  [("v0.2.0-preview.1", {**VERSION, "VERSION_CODE": "2"})])

    def test_feature_branch_cannot_enter_signing_preflight(self):
        with patch.dict(release.os.environ, {"GITHUB_REF": "refs/heads/feature/test"}):
            with self.assertRaises(ValueError):
                release.validate_release(VERSION, "preview", "v0.2.0-preview.1", SHA, True)

    def test_checkout_mismatch_or_non_main_ancestor_is_rejected(self):
        with patch.dict(release.os.environ, {"GITHUB_REF": "refs/heads/main"}):
            wrong_head = release.subprocess.CompletedProcess([], 0, "b" * 40, "")
            with patch.object(release, "command", return_value=wrong_head), self.assertRaises(ValueError):
                release.validate_release(VERSION, "preview", "v0.2.0-preview.1", SHA, True)
            calls = [release.subprocess.CompletedProcess([], 0, SHA, ""),
                     release.subprocess.CalledProcessError(1, ["git", "merge-base"])]
            with patch.object(release, "command", side_effect=calls), self.assertRaises(release.subprocess.CalledProcessError):
                release.validate_release(VERSION, "preview", "v0.2.0-preview.1", SHA, True)

    def test_existing_tag_cannot_point_to_another_commit(self):
        calls = [release.subprocess.CompletedProcess([], 0, SHA, ""),
                 release.subprocess.CompletedProcess([], 0, "", ""),
                 release.subprocess.CompletedProcess([], 0, "v0.2.0-preview.1\n", ""),
                 release.subprocess.CompletedProcess([], 0, "VERSION_NAME=0.2.0\nVERSION_CODE=3\nPREVIEW_NUMBER=1\n", ""),
                 release.subprocess.CompletedProcess([], 0, "b" * 40, "")]
        with patch.dict(release.os.environ, {"GITHUB_REF": "refs/heads/main"}):
            with patch.object(release, "command", side_effect=calls), self.assertRaises(ValueError):
                release.validate_release(VERSION, "preview", "v0.2.0-preview.1", SHA, True)

    def test_unsigned_or_wrong_certificate_cannot_be_published(self):
        manifest = ('A: package="com.fluxa.app.preview"\nA: versionName="0.2.0-preview.1+aaaaaaa"\n'
                    'A: versionCode=3\nA: label="Fluxa Preview"\nA: minSdkVersion=26\nA: targetSdkVersion=37')
        metadata = release.expected(VERSION, "preview", SHA)
        for code, log in [(1, "unsigned"), (0, "Number of signers: 1\nSigner #1 certificate SHA-256 digest: " + "b" * 64),
                          (0, "Number of signers: 1\nSigner #1 certificate SHA-256 digest: " + "c" * 64 + "\nCN=Android Debug")]:
            responses = [release.subprocess.CompletedProcess([], 0, manifest, ""),
                         release.subprocess.CompletedProcess([], code, log, "")]
            with patch.object(release, "command", side_effect=responses), self.assertRaises(ValueError):
                release.verify_apk(Path("unused.apk"), metadata, True, "c" * 64, "/sdk")


if __name__ == "__main__":
    unittest.main()
