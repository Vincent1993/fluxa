"""Exercise prerelease rejection paths without SDK, credentials, or network."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('test_packager', Path(__file__).with_name('prepare-test-release.py'))
packager = importlib.util.module_from_spec(spec)
spec.loader.exec_module(packager)


class TestPrerelease(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.candidate = self.root / 'candidate'
        self.apk = self.candidate / 'fluxa-debug-apk'
        self.apk.mkdir(parents=True)
        self.sha = 'a' * 40
        self.metadata = dict(commit=self.sha, apk_sha256='b' * 64,
                             application_id='com.fluxa.app.debug', version_name='0.3.0-dev+aaaaaaa',
                             version_code=4, signed=True)
        (self.apk / 'BUILD_COMMIT.txt').write_text(self.sha)
        (self.apk / 'app-debug.apk').write_bytes(b'test-fixture-not-an-apk')
        self.write(self.apk / 'channel-metadata.json', self.metadata)
        for channel in ('debug', 'preview', 'stable'):
            directory = self.candidate / ('fluxa-validation-' + channel)
            directory.mkdir()
            self.write(directory / 'test-summary.json', dict(commit=self.sha, status='passed',
                       tests=1, failures=0, errors=0, skipped=0))
        for api, page in (('36', 4096), ('37.0', 16384)):
            directory = self.candidate / ('fluxa-device-api-' + api)
            directory.mkdir()
            (directory / 'BUILD_COMMIT.txt').write_text(self.sha)
            (directory / 'device.txt').write_text('API=' + api.split('.')[0] + '\nPAGE_SIZE=' + str(page) + '\n')
            for suite in ('upgrade-seed', 'upgrade-verify', 'complete-device-suite'):
                (directory / (suite + '.log')).write_text('OK (1 test)\n')
        self.signature = ('Number of signers: 1\nSigner #1 certificate DN: CN=Android Debug\n'
                          'Signer #1 certificate SHA-256 digest: ' + 'c' * 64 + '\n')

    def write(self, path, value):
        path.write_text(json.dumps(value))

    def run_packager(self):
        env = dict(FLUXA_BUILD_COMMIT=self.sha, GITHUB_SHA=self.sha, GITHUB_RUN_ID='123',
                   GITHUB_REPOSITORY='Vincent1993/fluxa', ANDROID_HOME='/test-fixture')
        with patch.dict(os.environ, env), patch.object(packager.release, 'verify_apk', return_value=self.metadata), \
             patch.object(packager.subprocess, 'check_output', return_value=self.signature), \
             patch('sys.argv', ['packager', str(self.candidate), str(self.root / 'dist')]), \
             contextlib.redirect_stdout(io.StringIO()):
            packager.main()

    def test_packages_evidence_and_labels_ephemeral_signature(self):
        self.run_packager()
        result = json.loads((self.root / 'dist/test-release-metadata.json').read_text())
        self.assertFalse(result['cross_build_upgrade_guaranteed'])
        self.assertEqual(result['signing_type'], 'ephemeral-android-debug')
        self.assertTrue((self.root / 'dist/validation-evidence.zip').is_file())

    def test_rejects_mismatched_source(self):
        (self.apk / 'BUILD_COMMIT.txt').write_text('b' * 40)
        with self.assertRaisesRegex(ValueError, 'APK source mismatch'):
            self.run_packager()

    def test_rejects_altered_apk_metadata(self):
        self.write(self.apk / 'channel-metadata.json', dict(self.metadata, apk_sha256='d' * 64))
        with self.assertRaisesRegex(ValueError, 'metadata mismatch'):
            self.run_packager()

    def test_rejects_skipped_unit_tests(self):
        path = self.candidate / 'fluxa-validation-debug/test-summary.json'
        self.write(path, dict(commit=self.sha, status='passed', tests=2, failures=0, errors=0, skipped=1))
        with self.assertRaisesRegex(ValueError, 'Unit tests are not passing'):
            self.run_packager()

    def test_rejects_failed_device_suite(self):
        (self.candidate / 'fluxa-device-api-36/complete-device-suite.log').write_text('FAILURES!!!\n')
        with self.assertRaisesRegex(ValueError, 'Device suite did not pass'):
            self.run_packager()

    def test_rejects_wrong_device_page_size(self):
        (self.candidate / 'fluxa-device-api-37.0/device.txt').write_text('API=37\nPAGE_SIZE=4096\n')
        with self.assertRaisesRegex(ValueError, 'Device identity mismatch'):
            self.run_packager()

    def test_rejects_unexpected_certificate(self):
        self.signature = self.signature.replace('CN=Android Debug', 'CN=Unexpected')
        with self.assertRaisesRegex(ValueError, 'ordinary Android Debug'):
            self.run_packager()


if __name__ == '__main__':
    unittest.main()
