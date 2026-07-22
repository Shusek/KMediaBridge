#!/usr/bin/env python3
# SPDX-License-Identifier: LGPL-2.1-or-later

from __future__ import annotations

import argparse
import hashlib
import re
import time
import zipfile
from pathlib import Path


SEMVER = re.compile(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?")
ROOT_ARTIFACTS = {"kmedia-bridge-api", "kmedia-bridge-ffmpeg", "kmedia-bridge-client-android", "kmedia-bridge-client-desktop"}


def digest(path: Path, algorithm: str) -> str:
    value = hashlib.new(algorithm)
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--staging", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--epoch", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args()
    if not SEMVER.fullmatch(arguments.version):
        raise ValueError("version must be immutable SemVer")
    staging = arguments.staging.resolve()
    if not staging.is_dir() or staging.is_symlink() or any(path.is_symlink() for path in staging.rglob("*")):
        raise ValueError("staging must be a real symlink-free directory")
    primary = {path for path in staging.rglob("*") if path.is_file() and not path.name.endswith((".asc", ".md5", ".sha1"))}
    if not primary or any("maven-metadata.xml" in path.name for path in primary):
        raise ValueError("staging is empty or contains mutable Maven metadata")
    roots = set()
    for path in primary:
        relative = path.relative_to(staging)
        if relative.parts[:3] != ("io", "github", "shusek") or arguments.version not in relative.parts:
            raise ValueError(f"artifact lies outside the release namespace/version: {relative}")
        roots.add(relative.parts[3])
        signature = path.with_name(path.name + ".asc")
        if not signature.is_file() or signature.stat().st_size == 0:
            raise ValueError(f"signature is missing: {relative}")
    if not ROOT_ARTIFACTS.issubset(roots):
        raise ValueError(f"root publications are missing: {sorted(ROOT_ARTIFACTS - roots)}")
    signed = primary | {path.with_name(path.name + ".asc") for path in primary}
    allowed = set(signed)
    for path in sorted(signed):
        for algorithm in ("md5", "sha1"):
            sidecar = path.with_name(path.name + "." + algorithm)
            sidecar.write_text(digest(path, algorithm), encoding="ascii")
            allowed.add(sidecar)
    actual = {path for path in staging.rglob("*") if path.is_file()}
    if actual != allowed:
        raise ValueError("staging contains unexpected unsigned/checksum files")
    if arguments.output.exists():
        raise ValueError("output exists")
    timestamp = tuple(time.gmtime(max(arguments.epoch, 315532800))[:6])
    with zipfile.ZipFile(arguments.output, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in sorted(actual):
            info = zipfile.ZipInfo(path.relative_to(staging).as_posix(), timestamp)
            info.external_attr = 0o100644 << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, path.read_bytes())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
