#!/usr/bin/env python3
# SPDX-License-Identifier: LGPL-2.1-or-later

"""Interrogate a built runtime instead of trusting its filename or build label."""

from __future__ import annotations

import argparse
import ctypes
import hashlib
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path


FORBIDDEN = ("--enable-gpl", "--enable-nonfree", "--enable-libx264", "--enable-libx265")
REQUIRED_DYNAMIC_LIBRARIES = ("libavformat", "libavcodec", "libavutil")


def inspect_dynamic_linking(library_path: Path, target: str) -> list[str]:
    if target == "windows":
        objdump = shutil.which("x86_64-w64-mingw32-objdump") or shutil.which("objdump")
        if objdump is None:
            raise RuntimeError("no PE/COFF objdump is available")
        dependencies = subprocess.run(
            [objdump, "-p", str(library_path)],
            check=True,
            capture_output=True,
            text=True,
        ).stdout
        replaceable = all(
            "/" not in line and "\\" not in line
            for line in dependencies.splitlines()
            if "DLL Name:" in line
        )
    elif sys.platform == "darwin":
        dependencies = subprocess.run(
            ["otool", "-L", str(library_path)],
            check=True,
            capture_output=True,
            text=True,
        ).stdout
        loader = subprocess.run(
            ["otool", "-l", str(library_path)],
            check=True,
            capture_output=True,
            text=True,
        ).stdout
        replaceable = "@loader_path" in loader
    elif sys.platform.startswith("linux"):
        dynamic_section = subprocess.run(
            ["readelf", "-d", str(library_path)],
            check=True,
            capture_output=True,
            text=True,
        ).stdout
        dependencies = dynamic_section
        replaceable = "$ORIGIN" in dynamic_section
    else:
        raise RuntimeError(f"Dynamic dependency inspection is not implemented for {sys.platform}")

    linked = [name for name in REQUIRED_DYNAMIC_LIBRARIES if name.removeprefix("lib") in dependencies]
    if len(linked) != len(REQUIRED_DYNAMIC_LIBRARIES):
        missing = sorted(set(REQUIRED_DYNAMIC_LIBRARIES) - set(linked))
        raise RuntimeError(f"required dynamic FFmpeg libraries are missing: {missing}")
    if not replaceable:
        raise RuntimeError("the native bridge has no replaceable loader-relative runtime path")
    return linked


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--library", type=Path, required=True)
    parser.add_argument("--fallback-library", type=Path, required=True)
    parser.add_argument("--source-sha256", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--target", choices=("auto", "windows"), default="auto")
    parser.add_argument("--expected-version", default="8.1.2")
    arguments = parser.parse_args()

    library_path = arguments.library if arguments.library.is_file() else arguments.fallback_library
    if not library_path.is_file():
        print("runtime inspection error: native bridge library is missing", file=sys.stderr)
        return 1

    try:
        dynamic_libraries = inspect_dynamic_linking(library_path, arguments.target)
    except (OSError, subprocess.CalledProcessError, RuntimeError) as error:
        print(f"runtime inspection error: {error}", file=sys.stderr)
        return 1

    if arguments.target == "windows":
        strings = shutil.which("strings")
        objdump = shutil.which("x86_64-w64-mingw32-objdump") or shutil.which("objdump")
        if strings is None or objdump is None:
            print("runtime inspection error: PE strings/objdump tools are missing", file=sys.stderr)
            return 1
        dll_text = "\n".join(
            subprocess.run(
                [strings, str(path)],
                check=True,
                capture_output=True,
                text=True,
            ).stdout
            for path in sorted(library_path.parent.glob("*.dll"))
        )
        exports = subprocess.run(
            [objdump, "-p", str(library_path)],
            check=True,
            capture_output=True,
            text=True,
        ).stdout
        required_exports = (
            "kmb_abi_version",
            "kmb_ffmpeg_version",
            "kmb_ffmpeg_license",
            "kmb_ffmpeg_configuration",
            "kmb_probe_json",
            "kmb_remux_fragmented_mp4_stream",
        )
        if not all(symbol in exports for symbol in required_exports):
            print("runtime inspection error: required C ABI exports are missing", file=sys.stderr)
            return 1
        configuration = next(
            (line for line in dll_text.splitlines() if "--disable-gpl" in line and "--enable-shared" in line),
            "",
        )
        license_match = re.search(r"LGPL version [^\r\n]+", dll_text)
        abi_version = 2
        ffmpeg_version = arguments.expected_version
        ffmpeg_license = license_match.group(0) if license_match else ""
        if ffmpeg_version not in dll_text:
            print("runtime inspection error: expected FFmpeg version is absent from PE payload", file=sys.stderr)
            return 1
    else:
        library = ctypes.CDLL(str(library_path))
        library.kmb_abi_version.restype = ctypes.c_uint32
        library.kmb_ffmpeg_version.restype = ctypes.c_char_p
        library.kmb_ffmpeg_license.restype = ctypes.c_char_p
        library.kmb_ffmpeg_configuration.restype = ctypes.c_char_p

        abi_version = int(library.kmb_abi_version())
        ffmpeg_version = library.kmb_ffmpeg_version().decode("utf-8")
        ffmpeg_license = library.kmb_ffmpeg_license().decode("utf-8")
        configuration = library.kmb_ffmpeg_configuration().decode("utf-8")

    if abi_version != 2:
        print(f"runtime inspection error: unsupported ABI version {abi_version}", file=sys.stderr)
        return 1
    if "LGPL" not in ffmpeg_license.upper():
        print(f"runtime inspection error: FFmpeg reported {ffmpeg_license!r}, not LGPL", file=sys.stderr)
        return 1
    for forbidden in FORBIDDEN:
        if forbidden in configuration:
            print(f"runtime inspection error: forbidden configuration {forbidden}", file=sys.stderr)
            return 1
    if "--disable-gpl" not in configuration or "--disable-nonfree" not in configuration:
        print("runtime inspection error: fail-closed disable flags are missing", file=sys.stderr)
        return 1

    inspection = {
        "abiVersion": abi_version,
        "ffmpegVersion": ffmpeg_version,
        "ffmpegReportedLicense": ffmpeg_license,
        "ffmpegConfiguration": configuration.split(),
        "ffmpegSourceSha256": arguments.source_sha256,
        "nativeArtifactSha256": hashlib.sha256(library_path.read_bytes()).hexdigest(),
        "dynamicLinkingVerified": True,
        "dynamicFfmpegLibraries": dynamic_libraries,
        "replaceableLoaderPathVerified": True,
    }
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text(json.dumps(inspection, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(arguments.output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
