"""Test takeover guards using a fake keytool; creates no cryptographic keys."""
import base64
import errno
import json
import os
from pathlib import Path
import pty
import select
import shutil
import subprocess
import tempfile
import time
import unittest

FIXTURE = b"NON_SECRET_STUB_FILE"
FAKE_KEYTOOL = """#!/usr/bin/env python3
import json, sys
from pathlib import Path
args = sys.argv[1:]
with Path("calls.jsonl").open("a") as log:
    log.write(json.dumps(args) + "\\n")
if "-genkeypair" in args:
    Path(args[args.index("-keystore")+1]).write_bytes(b"NON_SECRET_STUB_FILE")
elif "-exportcert" in args:
    Path(args[args.index("-file")+1]).write_text("PUBLIC_CERTIFICATE_STUB")
elif "-printcert" in args:
    print("SHA256: PUBLIC_FINGERPRINT_STUB")
else:
    raise SystemExit(2)
"""


class PrepareKeyGuardsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        (self.root / "scripts").mkdir()
        (self.root / "bin").mkdir()
        self.script = self.root / "scripts/prepare-preview-key.sh"
        shutil.copyfile(Path(__file__).with_name("prepare-preview-key.sh"), self.script)
        fake = self.root / "bin/keytool"
        fake.write_text(FAKE_KEYTOOL)
        fake.chmod(0o755)
        self.env = {**os.environ, "PATH": str(self.root / "bin") + os.pathsep + os.environ["PATH"]}

    def tearDown(self):
        self.temp.cleanup()

    def terminal_run(self, trace=False):
        master, slave = pty.openpty()
        process = subprocess.Popen(["bash"] + (["-x"] if trace else []) + [str(self.script)],
                                   stdin=slave, stdout=slave, stderr=slave, env=self.env)
        os.close(slave)
        output = bytearray()
        deadline = time.monotonic() + 10
        try:
            while time.monotonic() < deadline:
                if select.select([master], [], [], 0.2)[0]:
                    try:
                        data = os.read(master, 65536)
                    except OSError as error:
                        if error.errno == errno.EIO:
                            break
                        raise
                    if not data:
                        break
                    output.extend(data)
            try:
                status = process.wait(timeout=max(0.1, deadline - time.monotonic()))
            except subprocess.TimeoutExpired:
                process.kill()
                self.fail("Stub helper timed out")
            return status, bytes(output)
        finally:
            os.close(master)
            if process.poll() is None:
                process.kill()
                process.wait()

    def test_terminal_flow_never_prints_stub_private_bytes_or_base64(self):
        status, output = self.terminal_run(trace=True)
        self.assertEqual(status, 0, output.decode())
        self.assertNotIn(FIXTURE, output)
        self.assertNotIn(base64.b64encode(FIXTURE), output)
        signing = self.root / ".signing"
        self.assertEqual(signing.stat().st_mode & 0o777, 0o700)
        for file in signing.iterdir():
            self.assertEqual(file.stat().st_mode & 0o777, 0o600)
        calls = [json.loads(line) for line in (self.root / "calls.jsonl").read_text().splitlines()]
        self.assertEqual([c[0] for c in calls], ["-genkeypair", "-exportcert", "-printcert"])
        self.assertNotIn("-keystore", calls[-1])
        self.assertTrue(all("-storepass" not in c and "-keypass" not in c for c in calls))

    def test_existing_files_are_not_overwritten(self):
        self.assertEqual(self.terminal_run()[0], 0)
        before = {p.name: p.read_bytes() for p in (self.root / ".signing").iterdir()}
        self.assertNotEqual(self.terminal_run()[0], 0)
        self.assertEqual(before, {p.name: p.read_bytes() for p in (self.root / ".signing").iterdir()})

    def test_noninteractive_run_refuses_before_creating_files(self):
        result = subprocess.run(["bash", str(self.script)], stdin=subprocess.DEVNULL,
                                capture_output=True, env=self.env)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.root / ".signing").exists())

    def test_redirected_output_refuses_before_creating_files(self):
        master, slave = pty.openpty()
        try:
            result = subprocess.run(["bash", str(self.script)], stdin=slave, stdout=subprocess.PIPE,
                                    stderr=slave, env=self.env)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse((self.root / ".signing").exists())
        finally:
            os.close(master)
            os.close(slave)

    def test_redirected_error_refuses_before_creating_files(self):
        master, slave = pty.openpty()
        try:
            result = subprocess.run(["bash", str(self.script)], stdin=slave, stdout=slave,
                                    stderr=subprocess.PIPE, env=self.env)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse((self.root / ".signing").exists())
        finally:
            os.close(master)
            os.close(slave)


if __name__ == "__main__":
    unittest.main()
