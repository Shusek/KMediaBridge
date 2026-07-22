#!/usr/bin/env python3
# SPDX-License-Identifier: LGPL-2.1-or-later

"""Fail-closed checks for the client-only KMediaBridge distribution boundary."""

from __future__ import annotations

import argparse
import subprocess
from pathlib import Path


SOURCE_SUFFIXES = {".c", ".h", ".kt", ".kts", ".py", ".sh", ".yml", ".yaml"}
EXCLUDED = {".git", ".gradle", ".idea", ".kotlin", "build", "node_modules"}
FORBIDDEN_BINARIES = {".a", ".lib", ".o", ".obj", ".so", ".dll", ".dylib"}
LEGACY_RECIPES = {
    "build-ffmpeg-android.sh",
    "build-ffmpeg-unix.sh",
    "build-ffmpeg-windows-x64.sh",
    "build-subtitle-deps-unix.sh",
}
FORBIDDEN_ARCHITECTURE_TEXT = (
    "libavcodec-kmb",
    "libavformat-kmb",
    "libavutil-kmb",
    "libavfilter-kmb",
    "kmedia-bridge-ffmpeg-runtime-android",
    "kmedia-bridge-ffmpeg-runtime-desktop",
    "android-x86",
    "macos-x86_64",
)


def fail(message: str) -> None:
    raise ValueError(message)


def tracked_paths(root: Path) -> list[Path]:
    output = subprocess.run(
        ["git", "-C", str(root), "ls-files", "-co", "--exclude-standard", "-z"],
        check=True,
        capture_output=True,
    ).stdout
    return [root / value.decode("utf-8") for value in output.split(b"\0") if value]


def verify_sources(root: Path) -> None:
    for path in tracked_paths(root):
        relative = path.relative_to(root)
        if any(part in EXCLUDED for part in relative.parts):
            continue
        if any(part.lower().startswith(".env") for part in relative.parts):
            fail(f"secret-bearing path must not be tracked: {relative}")
        if path.suffix.lower() in FORBIDDEN_BINARIES:
            fail(f"native binary must be supplied only by a release artifact: {relative}")
        if path.is_file() and path.suffix in SOURCE_SUFFIXES:
            opening = "\n".join(path.read_text(encoding="utf-8").splitlines()[:5])
            if "SPDX-License-Identifier:" not in opening:
                fail(f"missing SPDX header: {relative}")


def verify_boundary(root: Path) -> None:
    for name in LEGACY_RECIPES:
        if (root / "native" / name).exists():
            fail(f"legacy private FFmpeg recipe is still present: {name}")
    builder = (root / "native/build-client.py").read_text(encoding="utf-8")
    if "--runtime-sdk" not in builder or "sharedRuntimeId" not in builder:
        fail("client builder does not enforce the immutable shared-runtime SDK contract")
    if "./configure" in builder or "build-ffmpeg" in builder:
        fail("client builder must not build a private FFmpeg")
    required_targets = {
        "android-arm64-v8a", "android-armeabi-v7a", "linux-x86_64",
        "linux-aarch64", "macos-aarch64", "windows-x86_64",
    }
    if not required_targets.issubset(set(value.strip(' \"') for value in builder.replace("\n", " ").split(","))):
        # Exact target tokens are checked separately to keep formatting irrelevant.
        for target in required_targets:
            if f'"{target}"' not in builder:
                fail(f"client builder target is missing: {target}")
    for forbidden in ("android-x86", "android-x86_64", "macos-x86_64"):
        if f'"{forbidden}"' in builder:
            fail(f"unsupported target is present in client builder: {forbidden}")

    for relative, coordinate in (
        ("ffmpeg-runtime-android/build.gradle.kts", "kmedia-ffmpeg-runtime-android"),
        ("ffmpeg-runtime-desktop/build.gradle.kts", "kmedia-ffmpeg-runtime-desktop"),
    ):
        source = (root / relative).read_text(encoding="utf-8")
        if coordinate not in source or "strictly(ffmpegRuntimeVersion)" not in source:
            fail(f"{relative} does not pin the exact shared runtime")
    backend = (root / "ffmpeg/build.gradle.kts").read_text(encoding="utf-8")
    if 'api(project(":ffmpeg-runtime-android"))' not in backend or 'api(project(":ffmpeg-runtime-desktop"))' not in backend:
        fail("the public backend must transitively expose its platform client")


def verify_public_text(root: Path) -> None:
    paths = [root / "README.md", *sorted((root / "docs").glob("*.md")), *sorted((root / ".github/workflows").glob("*.yml"))]
    combined = "\n".join(path.read_text(encoding="utf-8") for path in paths if path.is_file())
    for token in FORBIDDEN_ARCHITECTURE_TEXT:
        if token in combined:
            fail(f"public documentation/workflow still references legacy architecture: {token}")
    ci = (root / ".github/workflows/ci.yml").read_text(encoding="utf-8").lower()
    if "emulator" in ci:
        fail("hosted CI must not claim an accelerated ARM Android emulator")
    release = (root / ".github/workflows/release.yml").read_text(encoding="utf-8")
    for field in ("android_arm_matrix_verified", "tested_commit", "tested_runtime_id", "runtime_report_sha256"):
        if field not in release:
            fail(f"release workflow omits local ARM attestation field: {field}")
    build_marker = "- name: Build one bridge client against the exact SDK"
    upload_marker = "- uses: actions/upload-artifact@"
    if build_marker not in release or upload_marker not in release.split(build_marker, 1)[1]:
        fail("release workflow does not retain the closed client build step")
    build_step = release.split(build_marker, 1)[1].split(upload_marker, 1)[0]
    if "RUNTIME_VERSION: ${{ inputs.runtime_version }}" not in build_step:
        fail("release client build step does not receive the exact shared runtime version")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    root = parser.parse_args().root.resolve()
    for path in (root / "LICENSE", root / "LICENSES/LGPL-2.1-or-later.txt", root / "LICENSES/LicenseRef-KMediaBridge-Internal.txt"):
        if not path.is_file():
            fail(f"required license is missing: {path.relative_to(root)}")
    verify_sources(root)
    verify_boundary(root)
    verify_public_text(root)
    print("KMediaBridge shared-runtime compliance verified")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
