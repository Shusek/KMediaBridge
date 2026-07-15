#!/usr/bin/env python3
# SPDX-License-Identifier: LGPL-2.1-or-later

"""Inspect staged Maven artifacts and reject undocumented native code."""

from __future__ import annotations

import argparse
import sys
import zipfile
from pathlib import Path


NATIVE_SUFFIXES = (".a", ".bc", ".dll", ".dylib", ".exe", ".lib", ".so", ".wasm")
COMPLIANCE_MANIFEST = "META-INF/kmediabridge/compliance/manifest.json"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", type=Path, required=True)
    arguments = parser.parse_args()
    repository = arguments.repository.resolve()

    if not repository.is_dir():
        print(f"publication error: repository does not exist: {repository}", file=sys.stderr)
        return 1

    poms = list(repository.rglob("*.pom"))
    if not poms:
        print("publication error: no Maven POMs were staged", file=sys.stderr)
        return 1

    pom_text = "\n".join(path.read_text(encoding="utf-8") for path in poms)
    for artifact in ("kmedia-bridge-api", "kmedia-bridge-ffmpeg"):
        if f"<artifactId>{artifact}</artifactId>" not in pom_text:
            print(f"publication error: missing POM for {artifact}", file=sys.stderr)
            return 1
    if "GNU Lesser General Public License v2.1 or later" not in pom_text:
        print("publication error: Maven POMs do not declare LGPL-2.1-or-later", file=sys.stderr)
        return 1

    for archive in repository.rglob("*"):
        if archive.suffix not in {".aar", ".jar", ".klib"} or not zipfile.is_zipfile(archive):
            continue
        with zipfile.ZipFile(archive) as zipped:
            names = zipped.namelist()
            native_names = [
                name
                for name in names
                if name.lower().endswith(NATIVE_SUFFIXES) or ".so." in name.lower()
            ]
            if native_names and COMPLIANCE_MANIFEST not in names:
                print(
                    f"publication error: {archive.name} carries native code without {COMPLIANCE_MANIFEST}",
                    file=sys.stderr,
                )
                return 1

    print(f"Verified {len(poms)} Maven POMs; no undocumented native payload was found.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
