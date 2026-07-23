#!/usr/bin/env python3
# SPDX-License-Identifier: LGPL-2.1-or-later

from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class ClientArchitectureTest(unittest.TestCase):
    def test_client_builder_is_shared_runtime_only(self) -> None:
        source = (ROOT / "native/build-client.py").read_text()
        self.assertIn("--runtime-sdk", source)
        self.assertIn("-lkmediaffmpeg_avformat", source)
        self.assertNotIn("./configure", source)
        self.assertNotIn('"android-x86_64"', source)
        self.assertNotIn('"macos-x86_64"', source)
        self.assertIn('manifest.properties").write_bytes(manifest.encode("utf-8"))', source)

    def test_legacy_ffmpeg_recipes_are_absent(self) -> None:
        for name in (
            "build-ffmpeg-android.sh", "build-ffmpeg-unix.sh",
            "build-ffmpeg-windows-x64.sh", "build-subtitle-deps-unix.sh",
        ):
            self.assertFalse((ROOT / "native" / name).exists())

    def test_backend_transitively_exposes_clients(self) -> None:
        source = (ROOT / "ffmpeg/build.gradle.kts").read_text()
        self.assertIn('api(project(":ffmpeg-runtime-android"))', source)
        self.assertIn('api(project(":ffmpeg-runtime-desktop"))', source)

    def test_release_publication_disables_incompatible_configuration_cache(self) -> None:
        source = (ROOT / ".github/workflows/release.yml").read_text()
        self.assertIn("./gradlew --no-daemon --no-configuration-cache", source)

    def test_android_assembler_emits_one_client_per_arm_abi(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = []
            for target in ("android-arm64-v8a", "android-armeabi-v7a"):
                source = root / target
                (source / "runtime").mkdir(parents=True)
                (source / "runtime/libkmediabridge.so").write_bytes(b"client")
                (source / "manifest.properties").write_text("\n".join((
                    f"platform={target}",
                    "sharedRuntimeId=kmediaffmpeg-8.1.2-ass-0.17.4-1172dc3b687757ab",
                    "buildRecipeRevision=0123456789abcdef0123456789abcdef01234567",
                    "sourceOfferUrl=https://example.invalid/source.tar.gz",
                    "sourceSha256=" + "a" * 64,
                    "buildRecipeUrl=https://example.invalid/native",
                )) + "\n")
                inputs.append(source)
            output = root / "output"
            subprocess.run([
                sys.executable, str(ROOT / "scripts/assemble_android_clients.py"),
                "--arm64", str(inputs[0]), "--armv7", str(inputs[1]), "--output", str(output),
            ], check=True)
            self.assertEqual({"arm64-v8a", "armeabi-v7a"}, {path.name for path in (output / "jniLibs").iterdir()})
            for abi in ("arm64-v8a", "armeabi-v7a"):
                self.assertEqual(["libkmediabridge.so"], [path.name for path in (output / "jniLibs" / abi).iterdir()])

    def test_compliance_entrypoint(self) -> None:
        subprocess.run([sys.executable, str(ROOT / "scripts/verify_compliance.py"), "--root", str(ROOT)], check=True)


if __name__ == "__main__":
    unittest.main()
