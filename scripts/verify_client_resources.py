#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-KMediaBridge-Internal

from __future__ import annotations

import argparse
import hashlib
import re
from pathlib import Path


PLATFORMS = {"linux-x86_64", "linux-aarch64", "macos-aarch64", "windows-x86_64"}
RUNTIME_ID = re.compile(r"kmediaffmpeg-8\.1\.2-ass-0\.17\.4-[0-9a-f]{16}")


def props(path: Path) -> dict[str, str]:
    return dict(line.split("=", 1) for line in path.read_text().splitlines() if line and not line.startswith("#"))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--resources", type=Path, required=True)
    args = parser.parse_args()
    native = args.resources.resolve() / "META-INF/kmediabridge/native"
    if not native.is_dir() or {path.name for path in native.iterdir()} != PLATFORMS:
        raise ValueError("desktop KMediaBridge client matrix differs")
    ids: set[str] = set()
    for platform in PLATFORMS:
        directory = native / platform
        manifest = props(directory / "manifest.properties")
        name = manifest.get("library.0.name", "")
        library = directory / name
        if manifest.get("platform") != platform or manifest.get("library.count") != "1":
            raise ValueError("desktop client manifest differs")
        if {path.name for path in directory.iterdir()} != {"manifest.properties", name}:
            raise ValueError("desktop client resource inventory is not closed")
        if hashlib.sha256(library.read_bytes()).hexdigest() != manifest.get("library.0.sha256"):
            raise ValueError("desktop client resource hash differs")
        ids.add(manifest.get("sharedRuntimeId", ""))
    if len(ids) != 1 or not RUNTIME_ID.fullmatch(next(iter(ids))):
        raise ValueError("desktop clients do not bind one valid shared runtime ID")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
