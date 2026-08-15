#!/usr/bin/env python3
# SPDX-License-Identifier: LGPL-2.1-or-later

from __future__ import annotations

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class EncoderPolicyTest(unittest.TestCase):
    def test_windows_forces_hardware_before_software_fallback(self) -> None:
        source = (ROOT / "native/src/kmedia_bridge_avc_encoder.h").read_text()

        hardware = source.index('av_dict_set(options, "hw_encoding", "1", 0)')
        fallback = source.index('av_dict_set(options, "hw_encoding", "0", 0)')
        self.assertLess(hardware, fallback)
        self.assertIn('"h264_mf",\n                AV_PIX_FMT_NV12', source)
        self.assertIn('"h264_nvenc",\n                AV_PIX_FMT_NV12', source)

    def test_videotoolbox_disallows_software_before_allowing_it(self) -> None:
        source = (ROOT / "native/src/kmedia_bridge_avc_encoder.h").read_text()

        hardware = source.index('av_dict_set(options, "allow_sw", "0", 0)')
        fallback = source.index('av_dict_set(options, "allow_sw", "1", 0)')
        self.assertLess(hardware, fallback)

    def test_every_video_conversion_uses_the_shared_policy(self) -> None:
        for name in (
            "kmedia_bridge_avfoundation.c",
            "kmedia_bridge_subtitles.c",
            "kmedia_bridge_tonemap.c",
        ):
            source = (ROOT / "native/src" / name).read_text()
            self.assertIn('#include "kmedia_bridge_avc_encoder.h"', source)
            self.assertIn("kmb_avc_encoder_attempts(", source)
            self.assertIn("kmb_avc_encoder_apply_options(", source)

    def test_native_unit_test_is_registered(self) -> None:
        cmake = (ROOT / "native/CMakeLists.txt").read_text()
        self.assertIn("kmedia_bridge_avc_encoder_test", cmake)
        self.assertIn("kmedia_bridge_avc_encoder COMMAND", cmake)


if __name__ == "__main__":
    unittest.main()
