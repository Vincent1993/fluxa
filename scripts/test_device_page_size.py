import struct
import subprocess
import unittest
from unittest.mock import call, patch

from device_page_size import device_page_size, main, parse_auxv, parse_getconf


def auxv(*entries):
    return b"".join(struct.pack("<QQ", kind, value) for kind, value in entries)


class PageSizeParsingTest(unittest.TestCase):
    def test_valid_little_endian_elf64(self):
        for value in (1024, 4096, 16384, 65536):
            with self.subTest(value=value):
                self.assertEqual(parse_auxv(auxv((3, 123), (6, value), (0, 0))), value)

    def test_invalid_auxv(self):
        cases = {
            "empty": b"",
            "truncated": auxv((6, 4096))[:-1],
            "trailing byte": auxv((6, 4096)) + b"x",
            "missing": auxv((3, 4096), (0, 0)),
            "duplicate": auxv((6, 4096), (6, 4096)),
            "conflicting": auxv((6, 4096), (6, 16384)),
            "zero": auxv((6, 0)),
            "too small": auxv((6, 512)),
            "not power of two": auxv((6, 4097)),
            "big endian": struct.pack(">QQ", 6, 4096),
            "elf32": struct.pack("<II", 6, 4096),
        }
        for name, data in cases.items():
            with self.subTest(name=name), self.assertRaises(ValueError):
                parse_auxv(data)

    def test_getconf_decimal(self):
        for data, expected in ((b"4096\n", 4096), (b"16384\r\n", 16384)):
            with self.subTest(data=data):
                self.assertEqual(parse_getconf(data), expected)

    def test_invalid_getconf(self):
        for data in (b"", b"0", b"512", b"4097", b"-4096", b"+4096",
                     b"0x1000", b"4096 extra", b"4096\n16384", b"\xff"):
            with self.subTest(data=data), self.assertRaises(ValueError):
                parse_getconf(data)


@patch.dict("os.environ", {"ANDROID_HOME": "/sdk", "ANDROID_SERIAL": "emulator-5554"}, clear=True)
class DevicePageSizeTest(unittest.TestCase):
    adb = ["/sdk/platform-tools/adb", "-s", "emulator-5554"]

    @patch("device_page_size.subprocess.run")
    def test_prefers_getconf(self, run):
        run.return_value = subprocess.CompletedProcess([], 0, b"16384\n")
        self.assertEqual(device_page_size(), 16384)
        run.assert_called_once_with(
            self.adb + ["shell", "getconf", "PAGE_SIZE"],
            capture_output=True, check=False,
        )

    @patch("device_page_size.subprocess.run")
    def test_failed_getconf_falls_back_to_raw_auxv(self, run):
        run.side_effect = [
            subprocess.CompletedProcess([], 127, b"", b"getconf: not found"),
            subprocess.CompletedProcess([], 0, auxv((6, 4096), (0, 0))),
        ]
        self.assertEqual(device_page_size(), 4096)
        self.assertEqual(run.call_args_list, [
            call(self.adb + ["shell", "getconf", "PAGE_SIZE"], capture_output=True, check=False),
            call(self.adb + ["exec-out", "cat", "/proc/self/auxv"], capture_output=True, check=True),
        ])

    @patch("device_page_size.subprocess.run")
    def test_successful_invalid_getconf_does_not_fall_back(self, run):
        run.return_value = subprocess.CompletedProcess([], 0, b"unknown\n")
        with self.assertRaises(ValueError):
            device_page_size()
        self.assertEqual(run.call_count, 1)

    @patch("device_page_size.subprocess.run")
    def test_fallback_failure_propagates(self, run):
        run.side_effect = [
            subprocess.CompletedProcess([], 1, b""),
            subprocess.CalledProcessError(1, self.adb),
        ]
        with self.assertRaises(subprocess.CalledProcessError):
            device_page_size()

    @patch.dict("os.environ", {}, clear=True)
    @patch("device_page_size.subprocess.run")
    def test_requires_explicit_device_and_sdk(self, run):
        with self.assertRaises(ValueError):
            device_page_size()
        run.assert_not_called()

    @patch("device_page_size.device_page_size", return_value=4096)
    @patch("builtins.print")
    def test_main_prints_only_decimal(self, print_mock, probe):
        self.assertEqual(main(), 0)
        print_mock.assert_called_once_with(4096)


if __name__ == "__main__":
    unittest.main()
