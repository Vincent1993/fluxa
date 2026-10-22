"""Report the selected Android device's page size without assuming a default."""

import os
from pathlib import Path
import re
import struct
import subprocess
import sys


def validate_page_size(value):
    if value < 1024 or value & (value - 1):
        raise ValueError("page size must be a positive power of two >= 1024")
    return value


def parse_getconf(data):
    text = data.strip()
    if not re.fullmatch(rb"[0-9]+", text):
        raise ValueError("getconf did not return a decimal page size")
    return validate_page_size(int(text))


def parse_auxv(data):
    # CI images are exclusively x86_64/arm64-v8a: ELF64, little-endian.
    if not data or len(data) % 16:
        raise ValueError("auxv must contain nonempty, complete ELF64 entries")
    values = [value for kind, value in struct.iter_unpack("<QQ", data) if kind == 6]
    if len(values) != 1:
        raise ValueError("auxv must contain exactly one AT_PAGESZ entry")
    return validate_page_size(values[0])


def device_page_size():
    home = os.environ.get("ANDROID_HOME")
    serial = os.environ.get("ANDROID_SERIAL")
    if not home or not serial:
        raise ValueError("ANDROID_HOME and ANDROID_SERIAL must be set")
    adb = [str(Path(home) / "platform-tools" / "adb"), "-s", serial]
    result = subprocess.run(
        adb + ["shell", "getconf", "PAGE_SIZE"], capture_output=True, check=False
    )
    if result.returncode == 0:
        # A successful but invalid response is an error, not a fallback trigger.
        return parse_getconf(result.stdout)
    result = subprocess.run(
        adb + ["exec-out", "cat", "/proc/self/auxv"],
        capture_output=True,
        check=True,
    )
    return parse_auxv(result.stdout)


def main():
    try:
        value = device_page_size()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print(f"device page size: {error}", file=sys.stderr)
        return 1
    print(value)
    return 0


if __name__ == "__main__":
    sys.exit(main())
