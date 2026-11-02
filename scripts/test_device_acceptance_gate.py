"""Exercise acceptance exit codes with fake adb/apksigner; no device, APK or keys."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class DeviceAcceptanceGateTest(unittest.TestCase):
    def run_flow(self, native_failed=False, suite_failed=False, capture_failed=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            scripts = root / 'scripts'
            scripts.mkdir()
            shutil.copyfile(Path(__file__).with_name('device-upgrade-test.sh'), scripts / 'device-upgrade-test.sh')
            (scripts / 'device-ci-classes.txt').write_text('com.fluxa.app.FixtureTest\n')
            (scripts / 'device-ci-capture.sh').write_text(
                '#!/bin/bash\necho capture >> "$CALL_LOG"\nexit "${CAPTURE_FAILED}"\n')
            sdk = root / 'sdk'
            adb = sdk / 'platform-tools/adb'
            adb.parent.mkdir(parents=True)
            adb.write_text(r'''#!/usr/bin/env python3
import os, sys
args = sys.argv[3:]
with open(os.environ['CALL_LOG'], 'a') as stream:
    stream.write(' '.join(args) + '\n')
if args[:2] == ['shell', 'am']:
    native = any('NativeWebViewPublicationProbeTest' in value for value in args)
    suite = 'com.fluxa.app.FixtureTest' in args
    failed = (native and os.environ['NATIVE_FAILED'] == '1') or (suite and os.environ['SUITE_FAILED'] == '1')
    print('FAILURES!!! Tests run: 1, Failures: 1' if failed else 'OK (1 test)')
elif args[:3] == ['shell', 'settings', 'get']:
    print('null')
elif args[:3] == ['shell', 'pm', 'list']:
    print('com.fluxa.app.debug.test/com.fluxa.app.ReaderAccessibilityRunner')
elif args[:2] == ['shell', 'dumpsys']:
    print('versionCode=4 versionName=fixture')
elif args[:2] == ['shell', 'run-as']:
    sys.exit(1)
''')
            adb.chmod(0o755)
            signer = sdk / 'build-tools/36.0.0/apksigner'
            signer.parent.mkdir(parents=True)
            signer.write_text('#!/bin/bash\necho "Signer #1 certificate SHA-256 digest: PUBLIC_STUB"\n')
            signer.chmod(0o755)
            bin_dir = root / 'bin'
            bin_dir.mkdir()
            timeout = bin_dir / 'timeout'
            timeout.write_text('#!/bin/bash\nshift\nexec "$@"\n')
            timeout.chmod(0o755)
            apks = [root / str(index) for index in range(4)]
            for apk in apks:
                apk.write_text('NON_APK_STUB')
            env = dict(os.environ, ANDROID_HOME=str(sdk), ANDROID_SERIAL='emulator-fixture',
                       CALL_LOG=str(root / 'calls'), NATIVE_FAILED=str(int(native_failed)),
                       SUITE_FAILED=str(int(suite_failed)), CAPTURE_FAILED=str(int(capture_failed)),
                       PATH=str(bin_dir) + os.pathsep + os.environ['PATH'])
            env.pop('FLUXA_WEBVIEW_PROVIDER_DIR', None)
            result = subprocess.run(['bash', str(scripts / 'device-upgrade-test.sh'),
                                     *map(str, apks), str(root / 'reports')],
                                    cwd=root, env=env, capture_output=True, text=True, timeout=10)
            return result, (root / 'calls').read_text()

    def test_native_failure_collects_app_results_but_still_blocks_release(self):
        result, calls = self.run_flow(native_failed=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('com.fluxa.app.FixtureTest', calls)
        self.assertIn('\ncapture\n', calls)
        self.assertIn('release remains blocked', result.stderr)

    def test_suite_failure_still_blocks(self):
        result, calls = self.run_flow(suite_failed=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('\ncapture\n', calls)

    def test_capture_failure_still_blocks(self):
        result, calls = self.run_flow(capture_failed=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('\ncapture\n', calls)

    def test_only_all_required_acceptance_success_returns_zero(self):
        result, calls = self.run_flow()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('\ncapture\n', calls)


if __name__ == '__main__':
    unittest.main()
