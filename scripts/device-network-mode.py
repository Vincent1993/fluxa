#!/usr/bin/env python3
"""Toggle airplane mode through actual System UI on an isolated test emulator."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--airplane", choices=["0", "1"], required=True)
    desired = parser.parse_args().airplane
    serial = os.environ["ANDROID_SERIAL"]
    if not serial.startswith("emulator-"):
        raise ValueError("Only dedicated emulators are allowed")
    adb = [str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb"), "-s", serial]

    def shell(*args):
        return subprocess.check_output(adb + ["shell", *args], text=True).strip()

    if shell("settings", "get", "global", "airplane_mode_on") != desired:
        shell("cmd", "statusbar", "expand-settings")
        try:
            shell("uiautomator", "dump", "/sdcard/fluxa-reader-network.xml")
            tree = ET.fromstring(shell("cat", "/sdcard/fluxa-reader-network.xml"))
            tiles = [n for n in tree.iter("node") if n.get("clickable") == "true"
                     and n.get("content-desc", "").lower() == "airplane mode"]
            if len(tiles) != 1:
                raise ValueError("Airplane-mode tile is not uniquely available")
            values = [int(v) for v in re.findall(r"\d+", tiles[0].get("bounds", ""))]
            if len(values) != 4 or values[0] >= values[2] or values[1] >= values[3]:
                raise ValueError("Invalid System UI tile bounds")
            shell("input", "tap", str((values[0] + values[2]) // 2), str((values[1] + values[3]) // 2))
        finally:
            shell("cmd", "statusbar", "collapse")
    actual = shell("settings", "get", "global", "airplane_mode_on")
    if actual != desired:
        raise ValueError(f"Airplane mode is {actual}, expected {desired}")
    print(f"airplane_mode_on={actual}")


if __name__ == "__main__":
    main()
