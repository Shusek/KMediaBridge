#!/usr/bin/env python3
# SPDX-License-Identifier: LGPL-2.1-or-later

"""Inspect staged Maven artifacts and reject undocumented native code."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
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
EXPECTED_RUNTIME_FLAVORS = {
    "linux-x86_64": "REMUX_ONLY",
    "macos-aarch64": "SUBTITLE_BURN_IN_SDR",
    "macos-x86_64": "SUBTITLE_BURN_IN_SDR",
    "windows-x86_64": "REMUX_ONLY",
}
EXPECTED_SUBTITLE_COMPONENTS = {
    "FreeType",
    "FriBidi library",
    "HarfBuzz",
    "libunibreak",
    "libass",
}
SHA256 = re.compile(r"^[0-9a-f]{64}$")


def is_native(name: str) -> bool:
    normalized = name.lower()
    return normalized.endswith(NATIVE_SUFFIXES) or ".so." in normalized


def require_public_https(url: str, label: str) -> None:
    parsed = urlsplit(url)
    if parsed.scheme != "https" or not parsed.netloc or parsed.username or parsed.password:
        raise ValueError(f"{label} must be a public HTTPS URL without credentials")


def read_properties(document: str, label: str) -> dict[str, str]:
    properties: dict[str, str] = {}
    for line in document.splitlines():
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator:
            raise ValueError(f"Malformed property in {label}: {line!r}")
        properties[key] = value.replace("\\n", "\n").replace("\\r", "\r").replace("\\\\", "\\")
    return properties


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

    linked_components = manifest.get("linkedComponents", [])
    if {component.get("name") for component in linked_components} != EXPECTED_SUBTITLE_COMPONENTS:
        raise ValueError(f"{archive.name} subtitle component inventory differs")
    for component in linked_components:
        require_public_https(str(component.get("sourceOfferUrl", "")), f"{component.get('name')} source offer")
        if not SHA256.fullmatch(str(component.get("sourceSha256", ""))):
            raise ValueError(f"{archive.name} has an invalid source hash for {component.get('name')}")
    canonical_components = sorted(linked_components, key=lambda item: item["name"])

    platforms = {entry.get("id") for entry in manifest.get("platforms", [])}
    if platforms != REQUIRED_RUNTIME_PLATFORMS:
        raise ValueError(f"{archive.name} platform matrix differs: {sorted(platforms)}")
    for platform in manifest.get("platforms", []):
        platform_id = str(platform.get("id"))
        platform_manifest = platform.get("manifestPath")
        if platform_manifest not in names:
            raise ValueError(f"{archive.name} is missing platform manifest {platform_manifest}")
        properties = read_properties(zipped.read(platform_manifest).decode("utf-8"), str(platform_manifest))
        expected_flavor = EXPECTED_RUNTIME_FLAVORS[platform_id]
        if properties.get("runtimeFlavor") != expected_flavor or platform.get("runtimeFlavor") != expected_flavor:
            raise ValueError(f"{archive.name} has an unexpected runtime flavor for {platform_id}")
        can_burn = expected_flavor == "SUBTITLE_BURN_IN_SDR"
        if (properties.get("capability.canBurnSubtitles") == "true") != can_burn:
            raise ValueError(f"{archive.name} has a contradictory subtitle capability for {platform_id}")
        if platform.get("canBurnSubtitles") is not can_burn:
            raise ValueError(f"{archive.name} aggregate subtitle capability differs for {platform_id}")
        component_count = int(properties.get("component.count", "-1"))
        platform_components = []
        for index in range(component_count):
            component = {
                "name": properties[f"component.{index}.name"],
                "version": properties[f"component.{index}.version"],
                "license": properties[f"component.{index}.licenseSpdx"],
                "sourceOfferUrl": properties[f"component.{index}.sourceOfferUrl"],
                "sourceSha256": properties[f"component.{index}.sourceSha256"],
            }
            require_public_https(component["sourceOfferUrl"], f"{platform_id} {component['name']} source offer")
            platform_components.append(component)
        if can_burn and sorted(platform_components, key=lambda item: item["name"]) != canonical_components:
            raise ValueError(f"{archive.name} platform component evidence differs for {platform_id}")
        if not can_burn and platform_components:
            raise ValueError(f"{archive.name} remux-only platform declares subtitle components")

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
    parser.add_argument("--version", required=True)
    arguments = parser.parse_args()
    repository = arguments.repository.resolve()
    version = arguments.version

    if not repository.is_dir():
        print(f"publication error: repository does not exist: {repository}", file=sys.stderr)
        return 1

    poms = [path for path in repository.rglob("*.pom") if path.parent.name == version]
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
            if archive.parent.name != version:
                continue
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
