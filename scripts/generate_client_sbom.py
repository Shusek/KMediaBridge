#!/usr/bin/env python3
# SPDX-License-Identifier: LGPL-2.1-or-later

from __future__ import annotations

import argparse
import json
import re
import uuid
from pathlib import Path


SEMVER = re.compile(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?")


def props(path: Path) -> dict[str, str]:
    return dict(line.split("=", 1) for line in path.read_text().splitlines() if line and not line.startswith(("#", "!")))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--runtime-version", required=True)
    parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args()
    if not SEMVER.fullmatch(arguments.version) or not SEMVER.fullmatch(arguments.runtime_version):
        raise ValueError("versions must be immutable SemVer")
    manifest = props(arguments.manifest)
    bridge = {
        "type": "library", "group": "io.github.shusek", "name": "kmedia-bridge-client-desktop",
        "version": arguments.version,
        "bom-ref": f"pkg:maven/io.github.shusek/kmedia-bridge-client-desktop@{arguments.version}",
        "licenses": [{"license": {"name": "LicenseRef-KMediaBridge-Internal"}}],
    }
    runtime = {
        "type": "library", "group": "io.github.shusek", "name": "kmedia-ffmpeg-runtime-desktop",
        "version": arguments.runtime_version,
        "bom-ref": f"pkg:maven/io.github.shusek/kmedia-ffmpeg-runtime-desktop@{arguments.runtime_version}",
        "licenses": [{"license": {"id": "LGPL-2.1-or-later"}}],
        "properties": [{"name": "kmedia:runtimeId", "value": manifest["sharedRuntimeId"]}],
    }
    document = {
        "bomFormat": "CycloneDX", "specVersion": "1.6", "version": 1,
        "serialNumber": "urn:uuid:" + str(uuid.uuid5(uuid.NAMESPACE_URL, f"https://github.com/Shusek/KMediaBridge/{arguments.version}/{manifest['sharedRuntimeId']}")),
        "metadata": {"component": bridge}, "components": [bridge, runtime],
        "dependencies": [{"ref": bridge["bom-ref"], "dependsOn": [runtime["bom-ref"]]}],
    }
    arguments.output.write_text(json.dumps(document, indent=2, sort_keys=True) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
