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
        self.web.set(ANDROID + "versionName", "149.0.7827.5")
        self.use_analyzer_attributes()
        result = self.validate()
        self.assertEqual("149.0.7827.5", result["webview_version"])
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
        self.web.set(ANDROID + "versionName", "145.0.7632.218")
        with self.assertRaisesRegex(ValueError, "newer"):
            self.validate()


if __name__ == "__main__":
    unittest.main()
