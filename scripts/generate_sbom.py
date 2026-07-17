#!/usr/bin/env python3
# SPDX-License-Identifier: LGPL-2.1-or-later

"""Generate a deterministic CycloneDX SBOM for the source release."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path


INTERNAL_LICENSE = "LicenseRef-KMediaBridge-Internal"
LGPL_LICENSE = "LGPL-2.1-or-later"


def version_from_catalog(catalog: str, name: str) -> str:
    match = re.search(rf"^{re.escape(name)}\s*=\s*\"([^\"]+)\"\s*$", catalog, re.MULTILINE)
    if match is None:
        raise ValueError(f"Missing version {name!r} in Gradle catalog")
    return match.group(1)


def license_choice(license_expression: str) -> dict:
    if license_expression.startswith("LicenseRef-"):
        return {"expression": license_expression}
    return {"license": {"id": license_expression}}


def component(name: str, version: str, license_id: str, scope: str = "required") -> dict:
    return {
        "type": "library",
        "bom-ref": f"pkg:maven/io.github.shusek/{name}@{version}",
        "group": "io.github.shusek",
        "name": name,
        "version": version,
        "scope": scope,
        "licenses": [license_choice(license_id)],
    }


def read_properties(path: Path) -> dict[str, str]:
    properties: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator:
            raise ValueError(f"Malformed property in {path}: {line!r}")
        properties[key] = value
    return properties


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--runtime-resources", type=Path)
    parser.add_argument("--android-runtime", type=Path)
    arguments = parser.parse_args()

    root = Path(__file__).resolve().parent.parent
    manifest = json.loads((root / "compliance/ffmpeg/manifest.json").read_text(encoding="utf-8"))
    if (
        manifest.get("schemaVersion") != 2
        or manifest.get("runtimeLicense") != LGPL_LICENSE
        or "projectLicense" in manifest
    ):
        raise ValueError("FFmpeg compliance evidence must declare the LGPL runtime boundary")
    subtitle_manifest = json.loads(
        (root / "compliance/subtitles/manifest.json").read_text(encoding="utf-8")
    )
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
    if runtime_manifest is not None and (
        runtime_manifest.get("schemaVersion") != 2
        or runtime_manifest.get("runtimeLicense") != LGPL_LICENSE
        or "projectLicense" in runtime_manifest
    ):
        raise ValueError("Binary runtime evidence must declare the LGPL runtime boundary")
    android_manifest_path = (
        arguments.android_runtime / "compliance/manifest.properties"
        if arguments.android_runtime is not None
        else None
    )
    if android_manifest_path is not None and not android_manifest_path.is_file():
        raise ValueError("The Android runtime compliance manifest is missing")
    android_manifest = (
        read_properties(android_manifest_path)
        if android_manifest_path is not None
        else None
    )
    if android_manifest is not None and (
        android_manifest.get("schemaVersion") != "1"
        or android_manifest.get("ffmpegVersion") != ffmpeg["version"]
        or android_manifest.get("ffmpegSourceSha256") != ffmpeg["sourceSha256"]
        or android_manifest.get("ffmpegLicenseSpdx") != LGPL_LICENSE
        or android_manifest.get("dynamicLinkingVerified") != "true"
    ):
        raise ValueError("Android runtime evidence differs from the reviewed FFmpeg boundary")
    android_binaries = (
        sorted((arguments.android_runtime / "jniLibs").glob("*/*.so"))
        if arguments.android_runtime is not None
        else []
    )
    if android_manifest is not None and not android_binaries:
        raise ValueError("Android runtime evidence contains no shared-object payload")
    declared_android_abis = (
        set(android_manifest.get("android.abis", "").split(","))
        if android_manifest is not None
        else set()
    )
    actual_android_abis = {path.parent.name for path in android_binaries}
    if android_manifest is not None and declared_android_abis != actual_android_abis:
        raise ValueError("Android runtime ABI evidence differs from its native payload")

    distribution_status = (
        "binary"
        if runtime_manifest is not None or android_manifest is not None
        else "source-only"
    )
    ffmpeg_source_url = (
        runtime_manifest["ffmpeg"]["sourceOfferUrl"]
        if runtime_manifest is not None
        else (
            android_manifest["ffmpegSourceUrl"]
            if android_manifest is not None
            else ffmpeg["sourceUrl"]
        )
    )
    desktop_native_payload_count = (
        len(runtime_manifest.get("nativePayloads", []))
        if runtime_manifest
        else 0
    )
    android_native_payload_count = len(android_binaries)

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
                "licenses": [
                    {
                        "license": {
                            "name": "Mixed licensing; see component-level declarations"
                        }
                    }
                ],
                "properties": [
                    {
                        "name": "kmediabridge:coreLicense",
                        "value": INTERNAL_LICENSE,
                    },
                    {
                        "name": "kmediabridge:runtimeLicense",
                        "value": LGPL_LICENSE,
                    },
                ],
            }
        },
        "components": [
            component("kmedia-bridge-api", project_version, INTERNAL_LICENSE),
            component("kmedia-bridge-ffmpeg", project_version, INTERNAL_LICENSE, "optional"),
            component(
                "kmedia-bridge-ffmpeg-runtime-desktop",
                project_version,
                LGPL_LICENSE,
                "optional",
            ),
            component(
                "kmedia-bridge-ffmpeg-runtime-android",
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
                "licenses": [{"license": {"id": "Apache-2.0"}}],
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
                        "value": str(
                            desktop_native_payload_count +
                            android_native_payload_count
                        ),
                    },
                    {
                        "name": "kmediabridge:desktopNativePayloadCount",
                        "value": str(desktop_native_payload_count),
                    },
                    {
                        "name": "kmediabridge:androidNativePayloadCount",
                        "value": str(android_native_payload_count),
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

    for library in android_binaries:
        abi = library.parent.name
        bom["components"].append(
            {
                "type": "library",
                "bom-ref": f"urn:kmediabridge:android:{abi}:{library.name}",
                "name": library.name,
                "version": project_version,
                "scope": "optional",
                "hashes": [{"alg": "SHA-256", "content": hashlib.sha256(library.read_bytes()).hexdigest()}],
                "licenses": [{"license": {"id": LGPL_LICENSE}}],
                "properties": [
                    {"name": "kmediabridge:platform", "value": "android"},
                    {"name": "kmediabridge:abi", "value": abi},
                ],
            }
        )

    for native_component in subtitle_manifest["components"]:
        bom["components"].append(
            {
                "type": "library",
                "bom-ref": (
                    f"pkg:generic/{native_component['name'].lower().replace(' ', '-')}@"
                    f"{native_component['version']}"
                ),
                "name": native_component["name"],
                "version": native_component["version"],
                "scope": "optional",
                "hashes": [{"alg": "SHA-256", "content": native_component["sourceSha256"]}],
                "licenses": [{"license": {"id": native_component["license"]}}],
                "externalReferences": [
                    {"type": "distribution", "url": native_component["sourceUrl"]}
                ],
            }
        )

    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text(json.dumps(bom, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(arguments.output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
