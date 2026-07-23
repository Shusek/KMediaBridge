#!/usr/bin/env python3
# SPDX-License-Identifier: LGPL-2.1-or-later

from __future__ import annotations

import argparse
import xml.etree.ElementTree as ET
from pathlib import Path


EXPECTED_RUNTIME_DEPENDENCIES = {
    "kmedia-bridge-client-android": "kmedia-ffmpeg-runtime-android",
    "kmedia-bridge-client-desktop": "kmedia-ffmpeg-runtime-desktop",
}


def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def dependency_coordinates(pom: Path) -> set[tuple[str, str, str]]:
    coordinates: set[tuple[str, str, str]] = set()
    for dependency in ET.parse(pom).getroot().iter():
        if local_name(dependency.tag) != "dependency":
            continue
        values = {
            local_name(child.tag): (child.text or "").strip()
            for child in dependency
        }
        coordinates.add(
            (values.get("groupId", ""), values.get("artifactId", ""), values.get("version", "")),
        )
    return coordinates


def verify(staging: Path, version: str, runtime_version: str) -> None:
    for artifact, dependency in EXPECTED_RUNTIME_DEPENDENCIES.items():
        pom = staging / "io/github/shusek" / artifact / version / f"{artifact}-{version}.pom"
        if not pom.is_file():
            raise ValueError(f"published POM is missing: {pom}")
        coordinates = dependency_coordinates(pom)
        expected = ("io.github.shusek", dependency, runtime_version)
        if expected not in coordinates:
            raise ValueError(f"{artifact} does not expose the exact shared runtime: {sorted(coordinates)}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--staging", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--runtime-version", required=True)
    arguments = parser.parse_args()
    verify(arguments.staging, arguments.version, arguments.runtime_version)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
