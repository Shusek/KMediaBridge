#!/usr/bin/env python3
# SPDX-License-Identifier: LGPL-2.1-or-later

"""Fail-closed repository compliance checks that do not inspect credentials."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from urllib.parse import urlsplit


SOURCE_SUFFIXES = {".c", ".h", ".kt", ".kts", ".ps1", ".py", ".sh"}
BINARY_SUFFIXES = {
    ".a",
    ".bc",
    ".dll",
    ".dylib",
    ".exe",
    ".framework",
    ".lib",
    ".so",
    ".wasm",
    ".xcframework",
}
FORBIDDEN_FLAGS = {
    "--enable-gpl",
    "--enable-nonfree",
    "--enable-libx264",
    "--enable-libx265",
    "--enable-libxvid",
    "--enable-libvidstab",
    "--enable-frei0r",
    "--enable-librubberband",
    "--enable-libcdio",
    "--enable-libdavs2",
    "--enable-libxavs",
    "--enable-libxavs2",
    "--enable-smbclient",
}
SHA256 = re.compile(r"^[0-9a-f]{64}$")
GRADLE_WRAPPER_SHA256 = "497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7"


def fail(message: str) -> None:
    raise AssertionError(message)


def load_json(path: Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        fail(f"Could not read valid JSON from {path}: {error}")


def require_public_https_url(label: str, value: str) -> None:
    parsed = urlsplit(value)
    if parsed.scheme != "https" or not parsed.netloc or parsed.username or parsed.password:
        fail(f"{label} must be a public HTTPS URL without embedded credentials: {value!r}")


def verify_headers(root: Path) -> None:
    candidates: list[Path] = [root / "build.gradle.kts", root / "settings.gradle.kts"]
    for directory in (root / "api", root / "ffmpeg", root / "native", root / "scripts"):
        if directory.exists():
            candidates.extend(path for path in directory.rglob("*") if path.suffix in SOURCE_SUFFIXES)

    missing: list[str] = []
    for path in sorted(set(candidates)):
        if not path.is_file():
            continue
        opening = "\n".join(path.read_text(encoding="utf-8").splitlines()[:4])
        if "SPDX-License-Identifier: LGPL-2.1-or-later" not in opening:
            missing.append(str(path.relative_to(root)))
    if missing:
        fail("Missing LGPL SPDX headers: " + ", ".join(missing))


def verify_manifest(root: Path) -> dict:
    manifest_path = root / "compliance/ffmpeg/manifest.json"
    embedded_path = (
        root
        / "ffmpeg/src/commonMain/resources/META-INF/kmediabridge/compliance/manifest.json"
    )
    manifest = load_json(manifest_path)
    embedded = load_json(embedded_path)

    if manifest.get("schemaVersion") != 1:
        fail("Unsupported compliance manifest schema version.")
    if manifest.get("projectLicense") != "LGPL-2.1-or-later":
        fail("The project license declaration must be LGPL-2.1-or-later.")
    if embedded.get("ffmpeg") != manifest.get("ffmpeg"):
        fail("The embedded FFmpeg manifest does not match the release manifest.")
    if embedded.get("distributionStatus") != manifest.get("distributionStatus"):
        fail("Embedded and release distribution statuses differ.")

    ffmpeg = manifest.get("ffmpeg", {})
    if ffmpeg.get("license") not in {"LGPL-2.1-or-later", "LGPL-3.0-or-later"}:
        fail("The pinned FFmpeg source is not declared under an accepted LGPL expression.")
    if ffmpeg.get("linkage") != "dynamic":
        fail("Official FFmpeg payloads must declare a dynamic linking boundary.")
    require_public_https_url("FFmpeg source URL", str(ffmpeg.get("sourceUrl", "")))
    if not SHA256.fullmatch(str(ffmpeg.get("sourceSha256", ""))):
        fail("The FFmpeg source SHA-256 is missing or malformed.")

    arguments = set(ffmpeg.get("configureArguments", []))
    if not {"--disable-gpl", "--disable-nonfree"}.issubset(arguments):
        fail("FFmpeg configuration must explicitly disable GPL and nonfree code.")
    forbidden = sorted(arguments.intersection(FORBIDDEN_FLAGS))
    if forbidden:
        fail("Forbidden FFmpeg configuration: " + ", ".join(forbidden))

    required_boundary = {"--disable-static", "--enable-shared", "--disable-autodetect"}
    if not required_boundary.issubset(arguments):
        fail("The manifest does not enforce the dynamic, fail-closed FFmpeg boundary.")

    recipe = (root / "native/build-ffmpeg-unix.sh").read_text(encoding="utf-8")
    recipe_block_match = re.search(
        r"configure_arguments=\(\n(?P<body>.*?)\n\)",
        recipe,
        re.DOTALL,
    )
    if recipe_block_match is None:
        fail("Could not locate the FFmpeg configure argument array in the native recipe.")
    recipe_arguments = set(re.findall(r'^\s+"(?P<argument>--[^"]+)"$', recipe_block_match.group("body"), re.MULTILINE))
    recipe_arguments.discard("--prefix=$prefix_dir")
    if recipe_arguments != arguments:
        missing_from_recipe = sorted(arguments - recipe_arguments)
        missing_from_manifest = sorted(recipe_arguments - arguments)
        fail(
            "FFmpeg manifest and native recipe differ; "
            f"missing from recipe={missing_from_recipe}, missing from manifest={missing_from_manifest}"
        )
    if f'source_version="{ffmpeg.get("version")}"' not in recipe:
        fail("The native recipe FFmpeg version differs from the manifest.")
    if f'source_sha256="{ffmpeg.get("sourceSha256")}"' not in recipe:
        fail("The native recipe FFmpeg source hash differs from the manifest.")
    return manifest


def verify_payload_boundary(root: Path, manifest: dict) -> None:
    prebuilt = root / "native/prebuilt"
    binaries = (
        []
        if not prebuilt.exists()
        else [path for path in prebuilt.rglob("*") if is_native_payload(path)]
    )
    declared_payloads = manifest.get("nativePayloads", [])

    if not binaries:
        if manifest.get("distributionStatus") != "source-only":
            fail("A source-only tree must declare distributionStatus=source-only.")
        if declared_payloads:
            fail("A source-only tree must not declare native payloads.")
        return

    if manifest.get("distributionStatus") != "binary":
        fail("Native binaries exist but distributionStatus is not binary.")
    declared_by_path = {item.get("path"): item for item in declared_payloads}
    for binary in binaries:
        relative = str(binary.relative_to(root))
        payload = declared_by_path.get(relative)
        if payload is None:
            fail(f"Undeclared native payload: {relative}")
        digest = hashlib.sha256(binary.read_bytes()).hexdigest()
        if payload.get("sha256") != digest:
            fail(f"Native payload hash mismatch: {relative}")
        require_public_https_url(f"Source offer for {relative}", str(payload.get("sourceOfferUrl", "")))
        if not payload.get("correspondingSourcePath"):
            fail(f"Native payload has no corresponding source path: {relative}")


def is_native_payload(path: Path) -> bool:
    name = path.name.lower()
    return path.suffix.lower() in BINARY_SUFFIXES or ".so." in name


def verify_required_files(root: Path, manifest: dict) -> None:
    required = manifest.get("requiredDistributionFiles", [])
    missing = [relative for relative in required if not (root / relative).is_file()]
    if missing:
        fail("Missing distribution files: " + ", ".join(missing))

    license_text = (root / "LICENSE").read_text(encoding="utf-8")
    if "GNU LESSER GENERAL PUBLIC LICENSE" not in license_text or "Version 2.1" not in license_text:
        fail("LICENSE is not the complete GNU LGPL v2.1 text.")

    wrapper = root / "gradle/wrapper/gradle-wrapper.jar"
    wrapper_digest = hashlib.sha256(wrapper.read_bytes()).hexdigest()
    if wrapper_digest != GRADLE_WRAPPER_SHA256:
        fail("The Gradle wrapper JAR differs from the pinned upstream Gradle 9.6.1 file.")
    apache_text = (root / "gradle/wrapper/LICENSE").read_text(encoding="utf-8")
    if "Apache License" not in apache_text or "Version 2.0" not in apache_text:
        fail("The Gradle wrapper Apache License 2.0 text is incomplete.")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    arguments = parser.parse_args()
    root = arguments.root.resolve()

    try:
        verify_headers(root)
        manifest = verify_manifest(root)
        verify_payload_boundary(root, manifest)
        verify_required_files(root, manifest)
    except AssertionError as error:
        print(f"compliance error: {error}", file=sys.stderr)
        return 1

    print("Compliance gate passed: LGPL source boundary is complete; no native FFmpeg payload is distributed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
