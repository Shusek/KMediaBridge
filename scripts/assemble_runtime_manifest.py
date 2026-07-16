#!/usr/bin/env python3
# SPDX-License-Identifier: LGPL-2.1-or-later

"""Create and verify the aggregate binary manifest embedded in the runtime JAR."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path
from urllib.parse import urlsplit


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


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_properties(path: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        name, separator, value = line.partition("=")
        if not separator:
            raise ValueError(f"Malformed property in {path}: {line!r}")
        result[name] = value.replace("\\n", "\n").replace("\\r", "\r").replace("\\\\", "\\")
    return result


def require_public_https(value: str, label: str) -> None:
    parsed = urlsplit(value)
    if parsed.scheme != "https" or not parsed.netloc or parsed.username or parsed.password:
        raise ValueError(f"{label} must be a public HTTPS URL without credentials")


def read_components(properties: dict[str, str], platform: str) -> list[dict[str, str]]:
    count = int(properties["component.count"])
    components: list[dict[str, str]] = []
    for index in range(count):
        component = {
            "name": properties[f"component.{index}.name"],
            "version": properties[f"component.{index}.version"],
            "license": properties[f"component.{index}.licenseSpdx"],
            "sourceOfferUrl": properties[f"component.{index}.sourceOfferUrl"],
            "sourceSha256": properties[f"component.{index}.sourceSha256"],
        }
        require_public_https(component["sourceOfferUrl"], f"{platform} {component['name']} source offer")
        if not SHA256.fullmatch(component["sourceSha256"]):
            raise ValueError(f"{platform} {component['name']} has an invalid source SHA-256")
        components.append(component)
    return components


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--resources", type=Path, required=True)
    parser.add_argument("--version", required=True)
    arguments = parser.parse_args()

    resources = arguments.resources.resolve()
    native_root = resources / "META-INF/kmediabridge/native"
    manifests = sorted(native_root.glob("*/manifest.properties"))
    if not manifests:
        raise ValueError("No platform payload manifests were found")
    discovered_platforms = {manifest.parent.name for manifest in manifests}
    if discovered_platforms != REQUIRED_RUNTIME_PLATFORMS:
        raise ValueError(
            "Native runtime platform matrix differs: "
            f"expected {sorted(REQUIRED_RUNTIME_PLATFORMS)}, got {sorted(discovered_platforms)}"
        )

    platforms: list[dict] = []
    payloads: list[dict] = []
    ffmpeg_version: str | None = None
    source_sha256: str | None = None
    source_offer_url: str | None = None
    subtitle_components: list[dict[str, str]] | None = None
    for manifest_path in manifests:
        properties = read_properties(manifest_path)
        platform = properties["platform"]
        if manifest_path.parent.name != platform:
            raise ValueError(f"Platform directory and manifest differ: {manifest_path}")
        if properties["abiVersion"] != "4" or properties["dynamicLinkingVerified"] != "true":
            raise ValueError(f"Unverified payload: {platform}")
        runtime_flavor = properties["runtimeFlavor"]
        if runtime_flavor != EXPECTED_RUNTIME_FLAVORS[platform]:
            raise ValueError(
                f"Unexpected runtime flavor for {platform}: "
                f"expected {EXPECTED_RUNTIME_FLAVORS[platform]}, got {runtime_flavor}"
            )
        can_burn_subtitles = properties["capability.canBurnSubtitles"] == "true"
        if can_burn_subtitles != (runtime_flavor == "SUBTITLE_BURN_IN_SDR"):
            raise ValueError(f"Subtitle capability and runtime flavor differ for {platform}")
        platform_components = read_components(properties, platform)
        if can_burn_subtitles:
            names = {component["name"] for component in platform_components}
            if names != EXPECTED_SUBTITLE_COMPONENTS:
                raise ValueError(f"Subtitle component inventory differs for {platform}: {sorted(names)}")
            canonical_components = sorted(platform_components, key=lambda item: item["name"])
            if subtitle_components is None:
                subtitle_components = canonical_components
            elif subtitle_components != canonical_components:
                raise ValueError("Subtitle-capable platform payloads use different component sources")
        elif platform_components:
            raise ValueError(f"Remux-only payload {platform} declares subtitle components")
        ffmpeg_version = ffmpeg_version or properties["ffmpegVersion"]
        source_sha256 = source_sha256 or properties["sourceSha256"]
        source_offer_url = source_offer_url or properties["sourceOfferUrl"]
        if (
            ffmpeg_version != properties["ffmpegVersion"]
            or source_sha256 != properties["sourceSha256"]
            or source_offer_url != properties["sourceOfferUrl"]
        ):
            raise ValueError("Platform payloads do not use the same exact FFmpeg source")
        count = int(properties["library.count"])
        platform_payloads: list[str] = []
        for index in range(count):
            name = properties[f"library.{index}.name"]
            expected = properties[f"library.{index}.sha256"]
            library_path = manifest_path.parent / name
            actual = digest(library_path)
            if actual != expected:
                raise ValueError(f"Payload hash mismatch: {library_path}")
            relative = library_path.relative_to(resources).as_posix()
            platform_payloads.append(relative)
            payloads.append(
                {
                    "path": relative,
                    "sha256": actual,
                    "platform": platform,
                    "role": properties[f"library.{index}.role"].lower(),
                    "sourceOfferUrl": properties["sourceOfferUrl"],
                    "correspondingSourcePath": "native",
                }
            )
        platforms.append(
            {
                "id": platform,
                "manifestPath": manifest_path.relative_to(resources).as_posix(),
                "runtimeFlavor": runtime_flavor,
                "canBurnSubtitles": can_burn_subtitles,
                "payloads": platform_payloads,
            }
        )

    if subtitle_components is None:
        raise ValueError("No subtitle-capable platform payload was found")

    aggregate = {
        "schemaVersion": 2,
        "projectLicense": "LGPL-2.1-or-later",
        "distributionStatus": "binary",
        "artifact": "kmedia-bridge-ffmpeg-runtime-desktop",
        "version": arguments.version,
        "ffmpeg": {
            "version": ffmpeg_version,
            "license": "LGPL-2.1-or-later",
            "linkage": "dynamic",
            "sourceOfferUrl": source_offer_url,
            "sourceSha256": source_sha256,
        },
        "platforms": platforms,
        "linkedComponents": subtitle_components,
        "nativePayloads": payloads,
    }
    output = resources / "META-INF/kmediabridge/compliance/manifest.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(aggregate, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
