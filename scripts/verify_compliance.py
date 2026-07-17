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
INTERNAL_LICENSE = "LicenseRef-KMediaBridge-Internal"
LGPL_LICENSE = "LGPL-2.1-or-later"
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


def source_files(directory: Path) -> list[Path]:
    if not directory.exists():
        return []
    return [
        path
        for path in directory.rglob("*")
        if path.is_file()
        and path.suffix in SOURCE_SUFFIXES
        and not {"build", "__pycache__"}.intersection(path.relative_to(directory).parts)
    ]


def verify_headers(root: Path) -> None:
    internal_candidates: list[Path] = []
    for module in ("api", "ffmpeg"):
        module_root = root / module
        internal_candidates.append(module_root / "build.gradle.kts")
        internal_candidates.extend(source_files(module_root / "src"))

    lgpl_candidates: list[Path] = [
        root / "build.gradle.kts",
        root / "settings.gradle.kts",
    ]
    for directory in (
        root / "ffmpeg-runtime-desktop",
        root / "native",
        root / "scripts",
    ):
        lgpl_candidates.extend(source_files(directory))

    problems: list[str] = []
    for expected, candidates in (
        (INTERNAL_LICENSE, internal_candidates),
        (LGPL_LICENSE, lgpl_candidates),
    ):
        marker = f"SPDX-License-Identifier: {expected}"
        for path in sorted(set(candidates)):
            if not path.is_file():
                problems.append(f"{path.relative_to(root)} (missing)")
                continue
            opening = "\n".join(path.read_text(encoding="utf-8").splitlines()[:4])
            if marker not in opening:
                problems.append(f"{path.relative_to(root)} (expected {expected})")
    if problems:
        fail("Missing or incorrect SPDX headers: " + ", ".join(problems))


def verify_manifest(root: Path) -> dict:
    manifest_path = root / "compliance/ffmpeg/manifest.json"
    embedded_path = (
        root
        / "ffmpeg/src/commonMain/resources/META-INF/kmediabridge/compliance/manifest.json"
    )
    manifest = load_json(manifest_path)
    embedded = load_json(embedded_path)

    if manifest.get("schemaVersion") != 2 or embedded.get("schemaVersion") != 2:
        fail("Unsupported compliance manifest schema version.")
    if manifest.get("runtimeLicense") != LGPL_LICENSE:
        fail("The native runtime license declaration must be LGPL-2.1-or-later.")
    if "projectLicense" in manifest or "projectLicense" in embedded:
        fail("Runtime manifests must not describe LGPL as the whole-project license.")
    if embedded.get("ffmpeg") != manifest.get("ffmpeg"):
        fail("The embedded FFmpeg manifest does not match the release manifest.")
    if embedded.get("runtimeLicense") != manifest.get("runtimeLicense"):
        fail("Embedded and release runtime licenses differ.")
    if embedded.get("distributionStatus") != manifest.get("distributionStatus"):
        fail("Embedded and release distribution statuses differ.")

    ffmpeg = manifest.get("ffmpeg", {})
    if ffmpeg.get("license") not in {LGPL_LICENSE, "LGPL-3.0-or-later"}:
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
    runtime_inspector = (root / "scripts/inspect_native_runtime.py").read_text(encoding="utf-8")
    if "_KMB_MAJOR" not in recipe or "LIBAVFORMAT_KMB_" not in runtime_inspector:
        fail("The private ELF FFmpeg symbol-version boundary is missing from the recipe or inspector.")
    release_verifier = (root / "scripts/verify_ffmpeg_release.sh").read_text(encoding="utf-8")
    if "FCF986EA15E6E293A5644F10B4322F04D67658D8" not in release_verifier:
        fail("The official FFmpeg release signing-key fingerprint is not pinned.")
    return manifest


def verify_subtitle_manifest(root: Path) -> dict:
    manifest_path = root / "compliance/subtitles/manifest.json"
    embedded_path = (
        root
        / "ffmpeg/src/commonMain/resources/META-INF/kmediabridge/compliance/subtitle-components.json"
    )
    manifest = load_json(manifest_path)
    embedded = load_json(embedded_path)
    if manifest != embedded:
        fail("The embedded subtitle component manifest differs from compliance evidence.")
    if manifest.get("schemaVersion") != 1:
        fail("Unsupported subtitle component manifest schema.")
    if manifest.get("linkage") != "static-pic-into-replaceable-lgpl-avfilter":
        fail("The subtitle component linkage boundary is not declared correctly.")
    allowed_licenses = {"FTL", "LGPL-2.1-or-later", "MIT", "ISC", "Zlib"}
    components = manifest.get("components", [])
    if {item.get("name") for item in components} != {
        "FreeType",
        "FriBidi library",
        "HarfBuzz",
        "libunibreak",
        "libass",
    }:
        fail("The subtitle component inventory differs from the reviewed set.")
    recipe = (root / "native/build-subtitle-deps-unix.sh").read_text(encoding="utf-8")
    for component in components:
        if component.get("license") not in allowed_licenses:
            fail(f"Unreviewed subtitle component license: {component.get('license')!r}")
        source_url = str(component.get("sourceUrl", ""))
        source_sha256 = str(component.get("sourceSha256", ""))
        require_public_https_url(f"{component.get('name')} source URL", source_url)
        if not SHA256.fullmatch(source_sha256):
            fail(f"{component.get('name')} source SHA-256 is missing or malformed.")
        if source_url not in recipe or source_sha256 not in recipe:
            fail(f"{component.get('name')} source identity differs from the build recipe.")
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

    license_map = (root / "LICENSE").read_text(encoding="utf-8")
    if INTERNAL_LICENSE not in license_map or LGPL_LICENSE not in license_map:
        fail("Root LICENSE must map both the internal core and LGPL runtime scopes.")

    internal_path = root / "LICENSES/LicenseRef-KMediaBridge-Internal.txt"
    lgpl_path = root / "LICENSES/LGPL-2.1-or-later.txt"
    if not internal_path.is_file() or not lgpl_path.is_file():
        fail("The dedicated internal and LGPL license texts must both be present.")

    internal_text = internal_path.read_text(encoding="utf-8")
    if "Internal Use Notice and Limited License" not in internal_text:
        fail("The KMediaBridge internal-use license text is incomplete.")
    lgpl_text = lgpl_path.read_text(encoding="utf-8")
    if "GNU LESSER GENERAL PUBLIC LICENSE" not in lgpl_text or "Version 2.1" not in lgpl_text:
        fail("LICENSES/LGPL-2.1-or-later.txt is not the complete GNU LGPL v2.1 text.")

    for resource in (
        root / "api/src/commonMain/resources/META-INF/LICENSE",
        root / "ffmpeg/src/commonMain/resources/META-INF/LICENSE",
    ):
        resource_text = resource.read_text(encoding="utf-8")
        if (
            "Internal Use Notice and Limited License" not in resource_text
            or "authorized collaborators" not in resource_text
            or "All rights not expressly granted above are reserved." not in resource_text
            or "GNU LESSER GENERAL PUBLIC LICENSE" in resource_text
        ):
            fail(
                f"{resource.relative_to(root)} must contain an internal-use notice "
                "without falsely licensing the core under LGPL."
            )

    wrapper = root / "gradle/wrapper/gradle-wrapper.jar"
    wrapper_digest = hashlib.sha256(wrapper.read_bytes()).hexdigest()
    if wrapper_digest != GRADLE_WRAPPER_SHA256:
        fail("The Gradle wrapper JAR differs from the pinned upstream Gradle 9.6.1 file.")
    apache_text = (root / "gradle/wrapper/LICENSE").read_text(encoding="utf-8")
    if "Apache License" not in apache_text or "Version 2.0" not in apache_text:
        fail("The Gradle wrapper Apache License 2.0 text is incomplete.")


def verify_publication_routes(root: Path) -> None:
    for module in ("api", "ffmpeg"):
        build = (root / module / "build.gradle.kts").read_text(encoding="utf-8")
        forbidden = [
            route
            for route in ("githubPagesMavenRepository", "publishToMavenCentral()")
            if route in build
        ]
        if forbidden:
            fail(
                f"{module} must not define public publication routes: "
                + ", ".join(forbidden)
            )
        for required in (
            "privateMavenRepositoryUrl",
            "privateMavenRepositoryUsername",
            "privateMavenRepositoryPassword",
            "LicenseRef-KMediaBridge-Internal",
        ):
            if required not in build:
                fail(f"{module} private publication is missing {required}.")

    runtime_build = (root / "ffmpeg-runtime-desktop/build.gradle.kts").read_text(
        encoding="utf-8"
    )
    for required in (
        "githubPagesMavenRepository",
        "publishToMavenCentral()",
        "LICENSES/LGPL-2.1-or-later.txt",
    ):
        if required not in runtime_build:
            fail(f"The public LGPL runtime publication is missing {required}.")

    release_workflow = (root / ".github/workflows/release.yml").read_text(
        encoding="utf-8"
    )
    for forbidden in (
        ":api:publishAllPublicationsToGithubPagesRepository",
        ":ffmpeg:publishAllPublicationsToGithubPagesRepository",
    ):
        if forbidden in release_workflow:
            fail(f"Release workflow exposes the proprietary core through {forbidden}.")
    for required in (
        ":api:publishAllPublicationsToPrivateCoreRepository",
        ":ffmpeg:publishAllPublicationsToPrivateCoreRepository",
        ":ffmpeg-runtime-desktop:publishAllPublicationsToGithubPagesRepository",
    ):
        if required not in release_workflow:
            fail(f"Release workflow is missing the expected route {required}.")

    central_workflow = (
        root / ".github/workflows/publish-maven-central.yml"
    ).read_text(encoding="utf-8")
    if (
        ":ffmpeg-runtime-desktop:publishAndReleaseToMavenCentral"
        not in central_workflow
    ):
        fail("Maven Central publication must target only the LGPL runtime project.")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    arguments = parser.parse_args()
    root = arguments.root.resolve()

    try:
        verify_headers(root)
        manifest = verify_manifest(root)
        verify_subtitle_manifest(root)
        verify_payload_boundary(root, manifest)
        verify_required_files(root, manifest)
        verify_publication_routes(root)
    except AssertionError as error:
        print(f"compliance error: {error}", file=sys.stderr)
        return 1

    print(
        "Compliance gate passed: the internal Kotlin core and LGPL native/runtime "
        "boundaries are explicit and complete."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
