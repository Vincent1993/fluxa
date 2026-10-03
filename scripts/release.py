#!/usr/bin/env python3
"""Validate versions, trusted release refs and actual APKs. Never handles passwords."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
from native_alignment import inspect_apk

ROOT = Path(__file__).resolve().parents[1]
CHANNELS = {"debug": ("debug", "com.fluxa.app.debug", "Fluxa Debug"),
            "preview": ("preview", "com.fluxa.app.preview", "Fluxa Preview"),
            "stable": ("release", "com.fluxa.app", "Fluxa")}


def command(*args, check=True):
    return subprocess.run(args, cwd=ROOT, text=True, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, check=check)


def read_version(text):
    values = {}
    for line in text.splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        key, value = line.split("=", 1)
        if key in values:
            raise ValueError("Duplicate version property")
        values[key] = value.strip()
    if set(values) != {"VERSION_NAME", "VERSION_CODE", "PREVIEW_NUMBER"}:
        raise ValueError("Unexpected version properties")
    if not re.fullmatch(r"(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)", values["VERSION_NAME"]):
        raise ValueError("VERSION_NAME must be MAJOR.MINOR.PATCH")
    for key in ("VERSION_CODE", "PREVIEW_NUMBER"):
        if not re.fullmatch(r"[1-9]\d*", values[key]):
            raise ValueError(f"Invalid {key}")
    if int(values["VERSION_CODE"]) > 2100000000:
        raise ValueError("VERSION_CODE exceeds Android limit")
    return values


def expected(version, channel, commit="local"):
    if channel not in CHANNELS:
        raise ValueError("Unknown channel")
    if commit != "local" and not re.fullmatch(r"[a-f0-9]{40}", commit):
        raise ValueError("Commit must be a full SHA")
    base = version["VERSION_NAME"]
    tag = "v" + base + ("-preview." + version["PREVIEW_NUMBER"] if channel == "preview" else "")
    name = base
    if channel == "preview":
        name += "-preview." + version["PREVIEW_NUMBER"] + "+" + commit[:7]
    elif channel == "debug":
        name += "-dev+" + commit[:7]
    build_type, package, label = CHANNELS[channel]
    return {"channel": channel, "build_type": build_type, "application_id": package,
            "label": label, "version_name": name, "version_code": int(version["VERSION_CODE"]),
            "tag": tag, "commit": commit}


def release_notes(changelog, tag):
    heading = "## [" + tag.removeprefix("v") + "]"
    match = re.search(r"^" + re.escape(heading) + r"(?:[^\n]*)\n(.*?)(?=^## |\Z)",
                      changelog, flags=re.MULTILINE | re.DOTALL)
    if not match or not match[1].strip():
        raise ValueError("Changelog must contain a non-empty exact release section")
    return match[1].strip() + "\n"


def check_history(current, tag, entries):
    for previous_tag, previous in entries:
        if previous_tag == tag:
            if previous != current:
                raise ValueError("Existing tag version differs from this checkout")
            continue
        if int(previous["VERSION_CODE"]) >= int(current["VERSION_CODE"]):
            raise ValueError("Bump VERSION_CODE beyond all previous published tags")


def validate_release(version, channel, tag, commit, trusted=False):
    if channel not in ("preview", "stable") or expected(version, channel, commit)["tag"] != tag:
        raise ValueError("Tag does not match channel and version.properties")
    if trusted:
        if os.environ.get("GITHUB_REF") != "refs/heads/main":
            raise ValueError("Release workflow must run from main")
        if command("git", "rev-parse", "HEAD").stdout.strip() != commit:
            raise ValueError("Checkout differs from selected workflow commit")
        command("git", "merge-base", "--is-ancestor", commit, "origin/main")
        entries = []
        for ref in command("git", "tag", "--list", "v*").stdout.splitlines():
            if not re.fullmatch(r"v\d+\.\d+\.\d+(?:-preview\.[1-9]\d*)?", ref):
                continue
            old = read_version(command("git", "show", ref + ":version.properties").stdout)
            entries.append((ref, old))
            if ref == tag and command("git", "rev-list", "-n", "1", ref).stdout.strip() != commit:
                raise ValueError("Existing tag targets a different commit")
        check_history(version, tag, entries)
    return expected(version, channel, commit)


def manifest_fields(tree):
    def attr(name):
        found = re.search(r"A: (?:http://schemas.android.com/apk/res/android:)?" +
                          re.escape(name) + r"(?:\([^)]*\))?=(\"[^\"]*\"|[^\s]+)", tree)
        if not found:
            raise ValueError("APK manifest is missing " + name)
        return found[1].strip('"')
    return {"application_id": attr("package"), "version_name": attr("versionName"),
            "version_code": int(attr("versionCode")), "label": attr("label"),
            "debuggable": bool(re.search(r":debuggable\([^)]*\)=(?:true|0xffffffff)", tree)),
            "min_sdk": int(attr("minSdkVersion")), "target_sdk": int(attr("targetSdkVersion"))}


def verify_apk(apk, metadata, signed, certificate, sdk):
    tools = Path(sdk) / "build-tools" / "36.0.0"
    fields = manifest_fields(command(str(tools / "aapt2"), "dump", "xmltree", "--file",
                                     "AndroidManifest.xml", str(apk)).stdout)
    for key in ("application_id", "version_name", "version_code", "label"):
        if fields[key] != metadata[key]:
            raise ValueError("APK metadata differs: " + key)
    if fields["debuggable"] != (metadata["channel"] == "debug"):
        raise ValueError("Wrong APK debuggable flag")
    if fields["min_sdk"] != 26 or fields["target_sdk"] != 35:
        raise ValueError("Unexpected APK API levels")
    verification = command(str(tools / "apksigner"), "verify", "--verbose", "--print-certs",
                           "--min-sdk-version", "26", str(apk), check=False)
    certificate_sha = None
    if signed:
        certificate = (certificate or "").replace(":", "").lower()
        if not re.fullmatch(r"[a-f0-9]{64}", certificate):
            raise ValueError("Pin the persistent signing certificate SHA-256 before releasing")
        if verification.returncode != 0:
            raise ValueError("APK signature verification failed")
        match = re.search(r"Signer #1 certificate SHA-256 digest: ([a-f0-9]{64})", verification.stdout)
        if not match or match[1] != certificate or "Number of signers: 1" not in verification.stdout:
            raise ValueError("APK is not signed by the pinned persistent certificate")
        if "CN=Android Debug" in verification.stdout:
            raise ValueError("A generic Debug key cannot sign a published channel")
        certificate_sha = match[1]
    elif metadata["channel"] != "debug" and verification.returncode == 0:
        raise ValueError("Secret-free CI must produce an unsigned distribution build")
    elif metadata["channel"] == "debug" and verification.returncode != 0:
        raise ValueError("Debug APK signature verification failed")
    native_libraries = inspect_apk(apk)
    command(str(tools / "zipalign"), "-c", "-P", "16", "4", str(apk))
    return {**metadata, **fields, "signed": signed or metadata["channel"] == "debug",
            "native_64bit_libraries": native_libraries, "zip_alignment_16kb": True,
            "signing_certificate_sha256": certificate_sha,
            "apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest(), "apk_bytes": apk.stat().st_size}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["version", "preflight", "apk"])
    parser.add_argument("--channel", choices=list(CHANNELS), default="preview")
    parser.add_argument("--commit", default=os.environ.get("FLUXA_BUILD_COMMIT", "local"))
    parser.add_argument("--tag")
    parser.add_argument("--trusted", action="store_true")
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--signed", action="store_true")
    parser.add_argument("--certificate", default=os.environ.get("ANDROID_SIGNING_CERT_SHA256"))
    parser.add_argument("--output", type=Path)
    parser.add_argument("--notes", type=Path)
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args()
    version = read_version((ROOT / "version.properties").read_text())
    metadata = expected(version, args.channel, args.commit)
    if args.action == "preflight":
        metadata = validate_release(version, args.channel, args.tag, args.commit, args.trusted)
        notes = release_notes((ROOT / "CHANGELOG.md").read_text(), args.tag)
        if args.notes:
            args.notes.write_text(notes)
    elif args.action == "apk":
        if not args.apk or not os.environ.get("ANDROID_HOME"):
            raise ValueError("APK path and ANDROID_HOME are required")
        metadata = verify_apk(args.apk.resolve(), metadata, args.signed, args.certificate, os.environ["ANDROID_HOME"])
    if args.output:
        args.output.write_text(json.dumps(metadata, indent=2) + "\n")
    if args.github_output:
        with args.github_output.open("a") as output:
            for key in ("channel", "build_type", "application_id", "version_code", "version_name", "commit", "tag"):
                output.write(f"{key}={metadata[key]}\n")
    print(json.dumps(metadata, indent=2))


if __name__ == "__main__":
    main()
