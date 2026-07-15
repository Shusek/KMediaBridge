#!/usr/bin/env python3
# SPDX-License-Identifier: LGPL-2.1-or-later

"""Create and verify the aggregate binary manifest embedded in the runtime JAR."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


REQUIRED_RUNTIME_PLATFORMS = {
    "linux-x86_64",
    "macos-aarch64",
    "macos-x86_64",
    "windows-x86_64",
}


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
    for manifest_path in manifests:
        properties = read_properties(manifest_path)
        platform = properties["platform"]
        if manifest_path.parent.name != platform:
            raise ValueError(f"Platform directory and manifest differ: {manifest_path}")
        if properties["abiVersion"] != "3" or properties["dynamicLinkingVerified"] != "true":
            raise ValueError(f"Unverified payload: {platform}")
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
                "payloads": platform_payloads,
            }
        )

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
        "nativePayloads": payloads,
    }
    output = resources / "META-INF/kmediabridge/compliance/manifest.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(aggregate, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
