import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

from webview_provider import ANDROID, LIBRARY, validate


class ProviderManifestTest(unittest.TestCase):
    def setUp(self):
        self.web_cert = "a" * 64
        self.library_cert = "b" * 64
        self.web = ET.fromstring(f'''<manifest xmlns:android="{ANDROID[1:-1]}"
            package="com.google.android.webview" android:versionName="156.0.8078.48">
            <uses-sdk android:minSdkVersion="32"/><application/></manifest>''')
        self.library = ET.fromstring(f'''<manifest xmlns:android="{ANDROID[1:-1]}"
            package="{LIBRARY}"><uses-sdk android:minSdkVersion="29"/>
            <application><static-library android:name="{LIBRARY}" android:version="123"/>
            </application></manifest>''')

    def dependency(self):
        return ET.SubElement(self.web.find("application"), "uses-static-library", {
            ANDROID + "name": LIBRARY, ANDROID + "version": "123",
            ANDROID + "certDigest": ":".join(["BB"] * 32)})

    def validate(self):
        return validate(self.web, self.library, self.web_cert, self.library_cert)

    def use_analyzer_attributes(self):
        # cmdline-tools 22.0 `apkanalyzer manifest print` omits the Android namespace.
        for root in (self.web, self.library):
            for element in root.iter():
                for name in list(element.attrib):
                    if name.startswith(ANDROID):
                        element.set(name[len(ANDROID):], element.attrib.pop(name))

    def test_analyzer_standalone_manifest_preserves_version_and_sdk_checks(self):
        self.web.set(ANDROID + "versionName", "156.0.8078.48")
        self.use_analyzer_attributes()
        result = self.validate()
        self.assertEqual("156.0.8078.48", result["webview_version"])
        self.assertFalse(result["trichrome_required"])
        self.web.find("uses-sdk").set("minSdkVersion", "37")
        with self.assertRaisesRegex(ValueError, "API 36"):
            self.validate()

    def test_analyzer_dependency_still_requires_matching_library_certificate(self):
        dependency = self.dependency()
        self.use_analyzer_attributes()
        self.assertTrue(self.validate()["trichrome_required"])
        dependency.set("certDigest", self.web_cert)
        with self.assertRaisesRegex(ValueError, "certificate mismatch"):
            self.validate()

    def test_conflicting_attribute_formats_are_rejected(self):
        self.web.set("versionName", "145.0.7632.218")
        with self.assertRaisesRegex(ValueError, "Conflicting Android attribute: versionName"):
            self.validate()

    def test_standalone_webview_does_not_install_unrelated_library(self):
        self.assertFalse(self.validate()["trichrome_required"])

    def test_declared_library_signature_can_differ_from_webview_signature(self):
        self.dependency()
        self.assertTrue(self.validate()["trichrome_required"])

    def test_wrong_library_signature_is_rejected(self):
        self.dependency().set(ANDROID + "certDigest", self.web_cert)
        with self.assertRaisesRegex(ValueError, "certificate mismatch"):
            self.validate()

    def test_wrong_library_name_and_version_are_rejected(self):
        dependency = self.dependency()
        for key, value in (("name", "other.library"), ("version", "122")):
            old = dependency.get(ANDROID + key)
            dependency.set(ANDROID + key, value)
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, "mismatch"):
                self.validate()
            dependency.set(ANDROID + key, old)

    def test_multiple_or_missing_certificate_digests_are_rejected(self):
        for cert in ("", "not-a-digest", self.web_cert + "\n" + self.web_cert):
            with self.subTest(cert=cert), self.assertRaisesRegex(ValueError, "one verified"):
                validate(self.web, self.library, cert, self.library_cert)

    def test_incompatible_api_and_old_version_are_rejected(self):
        self.web.find("uses-sdk").set(ANDROID + "minSdkVersion", "37")
        with self.assertRaisesRegex(ValueError, "API 36"):
            self.validate()
        self.web.find("uses-sdk").set(ANDROID + "minSdkVersion", "32")
        for old_version in ("145.0.7632.218", "149.0.7827.5"):
            self.web.set(ANDROID + "versionName", old_version)
            with self.subTest(version=old_version), self.assertRaisesRegex(ValueError, "newer"):
                self.validate()


class ProviderInstallReadinessTest(unittest.TestCase):
    """Run the actual installer helper with fake SDK tools and non-APK fixtures."""

    def run_install(self, mode):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            provider = root / 'fluxa-webview-provider'
            provider.mkdir()
            for name in ('WebViewGoogle.apk', 'TrichromeLibrary.apk'):
                (provider / name).write_bytes(b'NON_APK_STUB')
            digest = hashlib.sha256(b'NON_APK_STUB').hexdigest()
            (provider / 'SHA256SUMS').write_text(''.join(
                digest + '  ' + name + '\n' for name in ('WebViewGoogle.apk', 'TrichromeLibrary.apk')))
            reports = root / 'reports'
            reports.mkdir()
            (reports / 'webview-provider.json').write_text(json.dumps({
                'webview_version': '149.0.7827.5', 'trichrome_required': False}))
            sdk = root / 'sdk'
            adb = sdk / 'platform-tools/adb'
            adb.parent.mkdir(parents=True)
            adb.write_text(r'''#!/usr/bin/env python3
import os, pathlib, sys
args = sys.argv[3:]
root = pathlib.Path(os.environ['RUNNER_TEMP'])
with (root / 'calls').open('a') as stream:
    stream.write(' '.join(args) + '\n')
if args[:2] == ['shell', 'getprop']:
    print('36')
elif args[:3] == ['shell', 'pm', 'path']:
    print('package:/data/app/WebViewGoogle.apk')
elif args[0] == 'pull':
    pathlib.Path(args[-1]).write_bytes(b'NON_APK_STUB')
elif args[0] == 'install':
    if os.environ['TEST_MODE'] == 'install_failure':
        print('Failure [INSTALL_FAILED_TEST]')
        sys.exit(1)
    (root / 'installed').touch()
    print('Success')
elif args[:2] == ['shell', 'dumpsys']:
    count_file = root / 'observations'
    count = int(count_file.read_text()) + 1 if count_file.exists() else 1
    count_file.write_text(str(count))
    version = '149.0.7827.5' if os.environ['TEST_MODE'] == 'eventual' and count >= 3 else '133.0.6943.137'
    print('Current WebView package (name, version): (com.google.android.webview, ' + version + ')')
    if (root / 'installed').exists():
        print('Preferred WebView package (name, version): (com.google.android.webview, 149.0.7827.5)')
''')
            adb.chmod(0o755)
            signer = sdk / 'build-tools/36.0.0/apksigner'
            signer.parent.mkdir(parents=True)
            signer.write_text('#!/bin/bash\necho "Signer #1 certificate SHA-256 digest: PUBLIC_STUB"\n')
            signer.chmod(0o755)
            bin_dir = root / 'bin'
            bin_dir.mkdir()
            checksum = bin_dir / 'sha256sum'
            checksum.write_text('''#!/usr/bin/env python3
import hashlib, pathlib, sys
for line in pathlib.Path(sys.argv[2]).read_text().splitlines():
    digest, name = line.split()
    assert hashlib.sha256(pathlib.Path(name).read_bytes()).hexdigest() == digest
''')
            checksum.chmod(0o755)
            env = dict(os.environ, GITHUB_ACTIONS='true', RUNNER_OS='Linux',
                       RUNNER_TEMP=str(root), ANDROID_HOME=str(sdk),
                       ANDROID_SERIAL='emulator-5554', TEST_MODE=mode,
                       PATH=str(bin_dir) + os.pathsep + os.environ['PATH'])
            result = subprocess.run(['bash', str(Path(__file__).with_name('device-webview-provider.sh')),
                                     'install', str(provider), str(reports)], env=env,
                                    capture_output=True, text=True, timeout=40)
            return result, (root / 'calls').read_text()

    def test_installer_success_waits_for_actual_active_version(self):
        result, calls = self.run_install('eventual')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(calls.count('shell dumpsys webviewupdate'), 3)
        self.assertEqual(calls.count('install --no-streaming -r'), 1)

    def test_preferred_version_without_active_switch_fails(self):
        result, _ = self.run_install('never')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('did not become the active provider', result.stderr)

    def test_installation_failure_is_not_retried_or_accepted(self):
        result, calls = self.run_install('install_failure')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(calls.count('install --no-streaming -r'), 1)
        self.assertEqual(calls.count('shell dumpsys webviewupdate'), 1)


if __name__ == "__main__":
    unittest.main()
