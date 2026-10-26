"""Validate manifests and public signer digests of verified official SDK APKs."""

import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"
LIBRARY = "com.google.android.trichromelibrary"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def validate(web, library, web_cert, library_cert):
    for cert in (web_cert, library_cert):
        require(re.fullmatch(r"[0-9a-f]{64}", cert), "Expected one verified SHA256 signer")
    require(web.get("package") == "com.google.android.webview", "Unexpected WebView package")
    version = web.get(ANDROID + "versionName", "")
    require(re.fullmatch(r"\d+\.\d+\.\d+\.\d+", version), "Invalid WebView version")
    require(tuple(map(int, version.split("."))) > (145, 0, 7632, 218),
            "New SDK provider must be newer than the failing WebView 145 control")
    require(int(web.find("uses-sdk").get(ANDROID + "minSdkVersion")) <= 36,
            "WebView does not support the API 36 target")
    dependencies = web.findall("application/uses-static-library")
    require(len(dependencies) <= 1, "Unexpected additional WebView static dependencies")
    if dependencies:
        dependency = dependencies[0]
        exported = library.find("application/static-library")
        require(library.get("package") == LIBRARY and exported is not None,
                "Missing Trichrome static library")
        require(int(library.find("uses-sdk").get(ANDROID + "minSdkVersion")) <= 36,
                "Trichrome does not support API 36")
        require(dependency.get(ANDROID + "name") == exported.get(ANDROID + "name") == LIBRARY,
                "Static library name mismatch")
        library_version = dependency.get(ANDROID + "version", "")
        require(re.fullmatch(r"[0-9]+", library_version) and
                library_version == exported.get(ANDROID + "version"),
                "Static library version mismatch")
        digest = dependency.get(ANDROID + "certDigest", "").replace(":", "").lower()
        require(digest == library_cert, "Static library certificate mismatch")
    return {"webview_version": version, "webview_certificate_sha256": web_cert,
            "trichrome_certificate_sha256": library_cert,
            "trichrome_required": bool(dependencies)}


def main():
    reports = Path(sys.argv[1])
    def manifest(name):
        return ET.parse(reports / f"webview-{name}-manifest.xml").getroot()
    def certificate(name):
        return (reports / f"webview-{name}-certificate.txt").read_text().strip()
    result = validate(manifest("WebViewGoogle"), manifest("TrichromeLibrary"),
                      certificate("WebViewGoogle"), certificate("TrichromeLibrary"))
    print(json.dumps(result, sort_keys=True, indent=2))


if __name__ == "__main__":
    main()
