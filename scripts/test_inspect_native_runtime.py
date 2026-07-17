#!/usr/bin/env python3
# SPDX-License-Identifier: LGPL-2.1-or-later

"""Regression tests for native dependency identity inspection."""

import unittest

from inspect_native_runtime import inspect_ffmpeg_dependency_identities


class WindowsDependencyIdentityTest(unittest.TestCase):
    def test_accepts_private_dll_names_and_ignores_imported_symbols(self) -> None:
        objdump_output = """
            DLL Name: avutil-kmb-60.dll
            00000000  avutil_version
            DLL Name: avcodec-kmb-62.dll
            00000000  avcodec_send_packet
            DLL Name: avformat-kmb-62.dll
            00000000  avformat_open_input
            DLL Name: KERNEL32.dll
        """

        self.assertEqual(
            ["libavformat", "libavcodec", "libavutil"],
            inspect_ffmpeg_dependency_identities(objdump_output, "windows"),
        )

    def test_rejects_a_generic_ffmpeg_dll_identity(self) -> None:
        objdump_output = """
            DLL Name: avutil-kmb-60.dll
            DLL Name: avcodec-kmb-62.dll
            DLL Name: avformat-62.dll
        """

        with self.assertRaisesRegex(RuntimeError, "private -kmb identities"):
            inspect_ffmpeg_dependency_identities(objdump_output, "windows")


if __name__ == "__main__":
    unittest.main()
