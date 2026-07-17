#!/usr/bin/env python3
# SPDX-License-Identifier: LGPL-2.1-or-later

"""Turn an inspected native build into one classpath-loadable platform payload."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
from pathlib import Path


PLATFORM_PATTERNS = {
    "macos-aarch64": (
        re.compile(r"lib(?:avutil|avcodec|avformat|avfilter|swscale)-kmb\.\d+\.dylib$"),
        re.compile(r"libkmediabridge\.1\.dylib$"),
    ),
    "macos-x86_64": (
        re.compile(r"lib(?:avutil|avcodec|avformat|avfilter|swscale)-kmb\.\d+\.dylib$"),
        re.compile(r"libkmediabridge\.1\.dylib$"),
    ),
    "linux-x86_64": (
        re.compile(r"lib(?:avutil|avcodec|avformat|avfilter|swscale)-kmb\.so\.\d+$"),
        re.compile(r"libkmediabridge\.so\.1$"),
    ),
    "windows-x86_64": (
        re.compile(r"(?:avutil|avcodec|avformat|avfilter|swscale)-kmb-\d+\.dll$", re.IGNORECASE),
        re.compile(r"kmediabridge\.dll$", re.IGNORECASE),
    ),
}


def digest(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            hasher.update(block)
    return hasher.hexdigest()


def property_line(name: str, value: object) -> str:
    text = str(value).replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")
    return f"{name}={text}"


def library_order(path: Path) -> tuple[int, str]:
    name = path.name.lower()
    if "gcc" in name:
        return 0, name
    if "avutil" in name:
        return 1, name
    if "avcodec" in name:
        return 2, name
    if "avformat" in name:
        return 3, name
    if "swscale" in name:
        return 4, name
    if "avfilter" in name:
        return 5, name
    if "kmediabridge" in name:
        return 6, name
    return 7, name


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dist", type=Path, required=True)
    parser.add_argument("--platform", choices=sorted(PLATFORM_PATTERNS), required=True)
    parser.add_argument("--resources", type=Path, required=True)
    parser.add_argument("--release-version", required=True)
    parser.add_argument("--revision", required=True)
    arguments = parser.parse_args()

    dist = arguments.dist.resolve()
    inspection = json.loads((dist / "compliance/runtime-inspection.json").read_text(encoding="utf-8"))
    subtitle_components = json.loads(
        (Path(__file__).resolve().parent.parent / "compliance/subtitles/manifest.json").read_text(encoding="utf-8")
    )["components"]
    linked_components = subtitle_components if inspection["canBurnSubtitles"] else []
    if inspection.get("abiVersion") != 4:
        raise ValueError("Only native ABI 4 may be packaged")
    if not inspection.get("dynamicLinkingVerified") or not inspection.get("replaceableLoaderPathVerified"):
        raise ValueError("The native runtime did not pass the replaceable dynamic-link inspection")
    if "LGPL" not in str(inspection.get("ffmpegReportedLicense", "")).upper():
        raise ValueError("The built FFmpeg runtime did not report LGPL")

    dependency_pattern, bridge_pattern = PLATFORM_PATTERNS[arguments.platform]
    library_directory = dist / "lib"
    dependencies = [path for path in library_directory.iterdir() if dependency_pattern.fullmatch(path.name)]
    bridge_candidates = [path for path in library_directory.iterdir() if bridge_pattern.fullmatch(path.name)]
    expected_dependency_count = 3
    if inspection["canBurnSubtitles"]:
        expected_dependency_count += 1
    if inspection["canBurnSubtitles"] or inspection.get("canToneMapToSdr"):
        expected_dependency_count += 1
    if len(dependencies) != expected_dependency_count or len(bridge_candidates) != 1:
        raise ValueError(
            f"Expected {expected_dependency_count} FFmpeg libraries and one bridge; "
            f"got {dependencies!r}, {bridge_candidates!r}"
        )

    extra_dependencies: list[Path] = []
    if arguments.platform.startswith("windows"):
        extra_dependencies = sorted(library_directory.glob("libgcc*.dll"))
    selected = sorted(extra_dependencies + dependencies + bridge_candidates, key=library_order)
    destination = (
        arguments.resources.resolve()
        / "META-INF/kmediabridge/native"
        / arguments.platform
    )
    if destination.exists():
        shutil.rmtree(destination)
    destination.mkdir(parents=True)

    entries: list[tuple[str, str, str]] = []
    for source in selected:
        target = destination / source.name
        shutil.copyfile(source.resolve(), target)
        role = "BRIDGE" if bridge_pattern.fullmatch(source.name) else "DEPENDENCY"
        entries.append((source.name, digest(target), role))

    source_name = f"ffmpeg-{inspection['ffmpegVersion']}.tar.xz"
    source_offer = (
        f"https://github.com/Shusek/KMediaBridge/releases/download/"
        f"v{arguments.release_version}/{source_name}"
    )
    lines = [
        property_line("schemaVersion", 1),
        property_line("platform", arguments.platform),
        property_line("abiVersion", inspection["abiVersion"]),
        property_line("ffmpegVersion", inspection["ffmpegVersion"]),
        property_line("ffmpegLicenseSpdx", "LGPL-2.1-or-later"),
        property_line("ffmpegReportedLicense", inspection["ffmpegReportedLicense"]),
        property_line("sourceOfferUrl", source_offer),
        property_line("sourceSha256", inspection["ffmpegSourceSha256"]),
        property_line(
            "buildRecipeUrl",
            f"https://github.com/Shusek/KMediaBridge/tree/{arguments.revision}/native",
        ),
        property_line("buildRecipeRevision", arguments.revision),
        property_line("exactCorrespondingSourceAvailable", "true"),
        property_line("dynamicLinkingVerified", "true"),
        property_line("runtimeFlavor", inspection["runtimeFlavor"]),
        property_line(
            "capability.inputContainers",
            "MATROSKA,WEBM,MP4,FRAGMENTED_MP4,MPEG_TS",
        ),
        property_line("capability.outputs", "CMAF_FRAGMENT_STREAM"),
        property_line("capability.canProbe", "true"),
        property_line("capability.canCopyVideo", "true"),
        property_line("capability.canToneMapToSdr", str(bool(inspection.get("canToneMapToSdr"))).lower()),
        property_line("capability.canConvertDolbyVisionProfile7", "false"),
        property_line("capability.supportsLiveInput", "false"),
        property_line("capability.supportsEncryptedInput", "false"),
        property_line("capability.supportsRemoteInput", "false"),
        property_line(
            "capability.canTranscodeVideo",
            str(inspection["canBurnSubtitles"] or inspection.get("canToneMapToSdr", False)).lower(),
        ),
        property_line("capability.canTranscodeAudio", "false"),
        property_line("capability.canBurnSubtitles", str(inspection["canBurnSubtitles"]).lower()),
        property_line("component.count", len(linked_components)),
        property_line("library.count", len(entries)),
    ]
    for index, component in enumerate(linked_components):
        source_name = Path(component["sourceUrl"]).name
        source_offer = (
            f"https://github.com/Shusek/KMediaBridge/releases/download/"
            f"v{arguments.release_version}/{source_name}"
        )
        lines.extend(
            [
                property_line(f"component.{index}.name", component["name"]),
                property_line(f"component.{index}.version", component["version"]),
                property_line(f"component.{index}.licenseSpdx", component["license"]),
                property_line(f"component.{index}.sourceOfferUrl", source_offer),
                property_line(f"component.{index}.sourceSha256", component["sourceSha256"]),
            ]
        )
    for index, (name, sha256, role) in enumerate(entries):
        lines.extend(
            [
                property_line(f"library.{index}.name", name),
                property_line(f"library.{index}.sha256", sha256),
                property_line(f"library.{index}.role", role),
            ]
        )
    (destination / "manifest.properties").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(destination)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
