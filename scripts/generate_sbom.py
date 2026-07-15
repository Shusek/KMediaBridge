#!/usr/bin/env python3
# SPDX-License-Identifier: LGPL-2.1-or-later

"""Generate a deterministic CycloneDX SBOM for the source release."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path


def version_from_catalog(catalog: str, name: str) -> str:
    match = re.search(rf"^{re.escape(name)}\s*=\s*\"([^\"]+)\"\s*$", catalog, re.MULTILINE)
    if match is None:
        raise ValueError(f"Missing version {name!r} in Gradle catalog")
    return match.group(1)


def component(name: str, version: str, license_id: str, scope: str = "required") -> dict:
    return {
        "type": "library",
        "bom-ref": f"pkg:maven/io.github.shusek/{name}@{version}",
        "group": "io.github.shusek",
        "name": name,
        "version": version,
        "scope": scope,
        "licenses": [{"license": {"id": license_id}}],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--runtime-resources", type=Path)
    arguments = parser.parse_args()

    root = Path(__file__).resolve().parent.parent
    manifest = json.loads((root / "compliance/ffmpeg/manifest.json").read_text(encoding="utf-8"))
    catalog = (root / "gradle/libs.versions.toml").read_text(encoding="utf-8")
    project_version = arguments.version
    kotlin_version = version_from_catalog(catalog, "kotlin")
    coroutines_version = version_from_catalog(catalog, "coroutines")
    serialization_version = version_from_catalog(catalog, "serialization")
    jna_version = version_from_catalog(catalog, "jna")
    wrapper_properties = (root / "gradle/wrapper/gradle-wrapper.properties").read_text(encoding="utf-8")
    gradle_match = re.search(r"gradle-([0-9.]+)-bin\.zip", wrapper_properties)
    if gradle_match is None:
        raise ValueError("Could not determine the pinned Gradle wrapper version")
    gradle_version = gradle_match.group(1)
    wrapper_sha256 = hashlib.sha256((root / "gradle/wrapper/gradle-wrapper.jar").read_bytes()).hexdigest()
    ffmpeg = manifest["ffmpeg"]
    runtime_manifest_path = (
        arguments.runtime_resources / "META-INF/kmediabridge/compliance/manifest.json"
        if arguments.runtime_resources is not None
        else None
    )
    runtime_manifest = (
        json.loads(runtime_manifest_path.read_text(encoding="utf-8"))
        if runtime_manifest_path is not None and runtime_manifest_path.is_file()
        else None
    )
    distribution_status = "binary" if runtime_manifest is not None else "source-only"
    ffmpeg_source_url = (
        runtime_manifest["ffmpeg"]["sourceOfferUrl"]
        if runtime_manifest is not None
        else ffmpeg["sourceUrl"]
    )

    bom = {
        "bomFormat": "CycloneDX",
        "specVersion": "1.6",
        "serialNumber": "urn:uuid:ac9e0474-c62c-5fc0-95fb-2350ff7a5535",
        "version": 1,
        "metadata": {
            "component": {
                "type": "application",
                "bom-ref": "pkg:github/Shusek/KMediaBridge",
                "name": "KMediaBridge",
                "version": project_version,
                "licenses": [{"license": {"id": "LGPL-2.1-or-later"}}],
            }
        },
        "components": [
            component("kmedia-bridge-api", project_version, "LGPL-2.1-or-later"),
            component("kmedia-bridge-ffmpeg", project_version, "LGPL-2.1-or-later", "optional"),
            component(
                "kmedia-bridge-ffmpeg-runtime-desktop",
                project_version,
                "LGPL-2.1-or-later",
                "optional",
            ),
            {
                "type": "framework",
                "bom-ref": f"pkg:maven/org.jetbrains.kotlin/kotlin-stdlib@{kotlin_version}",
                "group": "org.jetbrains.kotlin",
                "name": "kotlin-stdlib",
                "version": kotlin_version,
                "scope": "required",
                "licenses": [{"license": {"id": "Apache-2.0"}}],
            },
            {
                "type": "library",
                "bom-ref": f"pkg:maven/org.jetbrains.kotlinx/kotlinx-serialization-json@{serialization_version}",
                "group": "org.jetbrains.kotlinx",
                "name": "kotlinx-serialization-json",
                "version": serialization_version,
                "scope": "optional",
                "licenses": [{"license": {"id": "Apache-2.0"}}],
            },
            {
                "type": "library",
                "bom-ref": f"pkg:maven/net.java.dev.jna/jna@{jna_version}",
                "group": "net.java.dev.jna",
                "name": "jna",
                "version": jna_version,
                "scope": "optional",
                "licenses": [{"license": {"id": "LGPL-2.1-or-later"}}],
                "externalReferences": [
                    {"type": "vcs", "url": "https://github.com/java-native-access/jna"}
                ],
            },
            {
                "type": "library",
                "bom-ref": f"pkg:maven/org.jetbrains.kotlinx/kotlinx-coroutines-core@{coroutines_version}",
                "group": "org.jetbrains.kotlinx",
                "name": "kotlinx-coroutines-core",
                "version": coroutines_version,
                "scope": "required",
                "licenses": [{"license": {"id": "Apache-2.0"}}],
            },
            {
                "type": "library",
                "bom-ref": f"pkg:generic/ffmpeg@{ffmpeg['version']}",
                "name": "FFmpeg",
                "version": ffmpeg["version"],
                "scope": "optional" if runtime_manifest is not None else "excluded",
                "hashes": [{"alg": "SHA-256", "content": ffmpeg["sourceSha256"]}],
                "licenses": [{"license": {"id": ffmpeg["license"]}}],
                "externalReferences": [{"type": "distribution", "url": ffmpeg_source_url}],
                "properties": [
                    {"name": "kmediabridge:distributionStatus", "value": distribution_status},
                    {
                        "name": "kmediabridge:nativePayloadCount",
                        "value": str(len(runtime_manifest.get("nativePayloads", []))) if runtime_manifest else "0",
                    },
                ],
            },
            {
                "type": "application",
                "bom-ref": f"pkg:generic/gradle-wrapper@{gradle_version}",
                "name": "Gradle Wrapper",
                "version": gradle_version,
                "scope": "required",
                "hashes": [{"alg": "SHA-256", "content": wrapper_sha256}],
                "licenses": [{"license": {"id": "Apache-2.0"}}],
                "externalReferences": [
                    {
                        "type": "vcs",
                        "url": f"https://github.com/gradle/gradle/tree/v{gradle_version}/gradle/wrapper",
                    }
                ],
                "properties": [{"name": "kmediabridge:purpose", "value": "build-tool"}],
            },
        ],
    }

    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text(json.dumps(bom, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(arguments.output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
