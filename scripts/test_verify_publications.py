#!/usr/bin/env python3
# SPDX-License-Identifier: LGPL-2.1-or-later

"""Regression tests for Maven publication archive inspection."""

import io
import tempfile
import unittest
import zipfile
from pathlib import Path

from verify_publications import ANDROID_RUNTIME_DOCUMENTS, read_android_compliance_resources


class AndroidAarComplianceResourcesTest(unittest.TestCase):
    def test_reads_standard_java_resources_from_nested_classes_jar(self) -> None:
        classes_buffer = io.BytesIO()
        expected = {}
        with zipfile.ZipFile(classes_buffer, "w") as classes:
            for index, name in enumerate(sorted(ANDROID_RUNTIME_DOCUMENTS)):
                expected[name] = f"document-{index}".encode()
                classes.writestr(name, expected[name])

        with tempfile.TemporaryDirectory() as temporary_directory:
            aar_path = Path(temporary_directory) / "runtime.aar"
            with zipfile.ZipFile(aar_path, "w") as aar:
                aar.writestr("classes.jar", classes_buffer.getvalue())
                aar.writestr("jni/arm64-v8a/libkmediabridge.so", b"native")

            with zipfile.ZipFile(aar_path) as aar:
                self.assertEqual(expected, read_android_compliance_resources(aar))

    def test_prefers_resources_already_present_in_outer_archive(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            aar_path = Path(temporary_directory) / "runtime.aar"
            expected = {name: b"outer" for name in ANDROID_RUNTIME_DOCUMENTS}
            with zipfile.ZipFile(aar_path, "w") as aar:
                for name, contents in expected.items():
                    aar.writestr(name, contents)

            with zipfile.ZipFile(aar_path) as aar:
                self.assertEqual(expected, read_android_compliance_resources(aar))


if __name__ == "__main__":
    unittest.main()
