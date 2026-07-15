#!/usr/bin/env python3
# SPDX-License-Identifier: LGPL-2.1-or-later

"""Inspect staged Maven artifacts and reject undocumented native code."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import zipfile
from pathlib import Path
from urllib.parse import urlsplit


NATIVE_SUFFIXES = (".a", ".bc", ".dll", ".dylib", ".exe", ".lib", ".so", ".wasm")
COMPLIANCE_MANIFEST = "META-INF/kmediabridge/compliance/manifest.json"
REQUIRED_RUNTIME_PLATFORMS = {
    "linux-x86_64",
    "macos-aarch64",
    "macos-x86_64",
    "windows-x86_64",
}


def is_native(name: str) -> bool:
    normalized = name.lower()
    return normalized.endswith(NATIVE_SUFFIXES) or ".so." in normalized


def require_public_https(url: str, label: str) -> None:
    parsed = urlsplit(url)
    if parsed.scheme != "https" or not parsed.netloc or parsed.username or parsed.password:
        raise ValueError(f"{label} must be a public HTTPS URL without credentials")


def verify_native_archive(archive: Path, zipped: zipfile.ZipFile, native_names: list[str]) -> None:
    names = set(zipped.namelist())
    forbidden = [name for name in native_names if name.lower().endswith((".a", ".bc", ".exe", ".lib", ".wasm"))]
    if forbidden:
        raise ValueError(f"{archive.name} carries forbidden static/executable payloads: {forbidden}")
    if COMPLIANCE_MANIFEST not in names:
        raise ValueError(f"{archive.name} carries native code without {COMPLIANCE_MANIFEST}")
    manifest = json.loads(zipped.read(COMPLIANCE_MANIFEST))
    if manifest.get("schemaVersion") != 2 or manifest.get("distributionStatus") != "binary":
        raise ValueError(f"{archive.name} has no binary distribution manifest schema 2")
    if manifest.get("projectLicense") != "LGPL-2.1-or-later":
        raise ValueError(f"{archive.name} does not declare the project LGPL license")
    ffmpeg = manifest.get("ffmpeg", {})
    if ffmpeg.get("license") != "LGPL-2.1-or-later" or ffmpeg.get("linkage") != "dynamic":
        raise ValueError(f"{archive.name} does not declare a dynamically linked LGPL FFmpeg runtime")
    require_public_https(str(ffmpeg.get("sourceOfferUrl", "")), "FFmpeg source offer")

    platforms = {entry.get("id") for entry in manifest.get("platforms", [])}
    if platforms != REQUIRED_RUNTIME_PLATFORMS:
        raise ValueError(f"{archive.name} platform matrix differs: {sorted(platforms)}")
    for platform in manifest.get("platforms", []):
        platform_manifest = platform.get("manifestPath")
        if platform_manifest not in names:
            raise ValueError(f"{archive.name} is missing platform manifest {platform_manifest}")

    declared = {entry.get("path"): entry for entry in manifest.get("nativePayloads", [])}
    if set(native_names) != set(declared):
        raise ValueError(f"{archive.name} native payload inventory differs from the archive")
    for name in native_names:
        payload = declared[name]
        actual = hashlib.sha256(zipped.read(name)).hexdigest()
        if payload.get("sha256") != actual:
            raise ValueError(f"{archive.name} has a payload SHA-256 mismatch for {name}")
        require_public_https(str(payload.get("sourceOfferUrl", "")), f"Source offer for {name}")
        if not payload.get("correspondingSourcePath"):
            raise ValueError(f"{archive.name} has no corresponding source path for {name}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", type=Path, required=True)
    arguments = parser.parse_args()
    repository = arguments.repository.resolve()

    if not repository.is_dir():
        print(f"publication error: repository does not exist: {repository}", file=sys.stderr)
        return 1

    poms = list(repository.rglob("*.pom"))
    if not poms:
        print("publication error: no Maven POMs were staged", file=sys.stderr)
        return 1

    pom_text = "\n".join(path.read_text(encoding="utf-8") for path in poms)
    for artifact in (
        "kmedia-bridge-api",
        "kmedia-bridge-ffmpeg",
        "kmedia-bridge-ffmpeg-runtime-desktop",
    ):
        if f"<artifactId>{artifact}</artifactId>" not in pom_text:
            print(f"publication error: missing POM for {artifact}", file=sys.stderr)
            return 1
    if "GNU Lesser General Public License v2.1 or later" not in pom_text:
        print("publication error: Maven POMs do not declare LGPL-2.1-or-later", file=sys.stderr)
        return 1

    native_archives = 0
    try:
        for archive in repository.rglob("*"):
            if archive.suffix not in {".aar", ".jar", ".klib"} or not zipfile.is_zipfile(archive):
                continue
            with zipfile.ZipFile(archive) as zipped:
                native_names = [name for name in zipped.namelist() if is_native(name)]
                if native_names:
                    native_archives += 1
                    verify_native_archive(archive, zipped, native_names)
    except (KeyError, OSError, ValueError, zipfile.BadZipFile, json.JSONDecodeError) as error:
        print(f"publication error: {error}", file=sys.stderr)
        return 1

    print(
        f"Verified {len(poms)} Maven POMs and {native_archives} documented native runtime archive(s)."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
