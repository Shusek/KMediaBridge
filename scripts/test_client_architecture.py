#!/usr/bin/env python3
# SPDX-License-Identifier: LGPL-2.1-or-later

from __future__ import annotations

import importlib.util
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BUILD_SPEC = importlib.util.spec_from_file_location("kmediabridge_build_client", ROOT / "native/build-client.py")
BUILD_CLIENT = importlib.util.module_from_spec(BUILD_SPEC)
assert BUILD_SPEC.loader is not None
BUILD_SPEC.loader.exec_module(BUILD_CLIENT)


class ClientArchitectureTest(unittest.TestCase):
    def test_pull_request_ci_builds_and_loads_the_windows_client(self) -> None:
        workflow = (ROOT / ".github/workflows/ci.yml").read_text()

        self.assertIn("target: windows-x86_64", workflow)
        self.assertIn("runner: windows-2025", workflow)
        self.assertIn("Load the one shared runtime and bridge in one Windows process", workflow)
        self.assertIn(
            "-PkmediaBridgeTestRuntimeSdk=$env:RUNNER_TEMP\\runtime\\sdk\\windows-x86_64",
            workflow,
        )

    def test_windows_full_features_are_bound_to_the_shared_runtime_manifest(self) -> None:
        self.assertEqual(
            {"toneMap": False, "subtitleBurn": False, "avcAacTranscode": False},
            BUILD_CLIENT.target_features("windows-x86_64", {}),
        )
        self.assertEqual(
            {"toneMap": True, "subtitleBurn": True, "avcAacTranscode": True},
            BUILD_CLIENT.target_features(
                "windows-x86_64",
                {
                    "feature.hdrToSdrToneMap": "true",
                    "feature.subtitleBurnIn": "true",
                    "feature.avcAacTranscode": "true",
                },
            ),
        )

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

    def test_rc_release_requires_both_verified_android_clients(self) -> None:
        workflow = (ROOT / ".github/workflows/release.yml").read_text()
        builder = (ROOT / "native/build-client.py").read_text()
        verifier = (ROOT / "scripts/verify_client_output.py").read_text()

        self.assertNotIn("android_armv7_native_graph_verified", workflow)
        self.assertIn("android-arm64-v8a", workflow)
        self.assertIn("android-armeabi-v7a", workflow)
        self.assertIn("needs: [readiness, client]", workflow)
        self.assertIn('--arm64 "$RUNNER_TEMP/clients/client-android-arm64-v8a"', workflow)
        self.assertIn('--armv7 "$RUNNER_TEMP/clients/client-android-armeabi-v7a"', workflow)
        self.assertIn('if [[ "$VERSION" != *-rc.* ]]; then', workflow)
        self.assertIn('test "$ARM_MATRIX" = true', workflow)
        self.assertIn("verify_client_output.py", builder)
        self.assertIn(
            "KMediaBridge client is not linked only to the shared runtime ABI",
            verifier,
        )

    def test_transitive_runtime_pom_verifier_supports_maven_namespaces(self) -> None:
        version = "0.5.0-rc.1"
        runtime_version = "0.1.0-rc.8"
        with tempfile.TemporaryDirectory() as temporary:
            staging = Path(temporary)
            for artifact, runtime_artifact in (
                ("kmedia-bridge-client-android", "kmedia-ffmpeg-runtime-android"),
                ("kmedia-bridge-client-desktop", "kmedia-ffmpeg-runtime-desktop"),
            ):
                directory = staging / "io/github/shusek" / artifact / version
                directory.mkdir(parents=True)
                (directory / f"{artifact}-{version}.pom").write_text(
                    """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <dependencies><dependency>
    <groupId>io.github.shusek</groupId>
    <artifactId>{runtime_artifact}</artifactId>
    <version>{runtime_version}</version>
  </dependency></dependencies>
</project>
""".format(runtime_artifact=runtime_artifact, runtime_version=runtime_version),
                    encoding="utf-8",
                )
            subprocess.run(
                [
                    sys.executable,
                    str(ROOT / "scripts/verify_transitive_runtime_poms.py"),
                    "--staging",
                    str(staging),
                    "--version",
                    version,
                    "--runtime-version",
                    runtime_version,
                ],
                check=True,
            )

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
                    "sharedRuntimeId=kmediaffmpeg-9.0.1-ass-0.17.5-cbb827ab5d596906",
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
