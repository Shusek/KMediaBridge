#!/usr/bin/env python3
# SPDX-License-Identifier: LGPL-2.1-or-later

"""Inspect staged Maven artifacts and reject undocumented native code."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import zipfile
import xml.etree.ElementTree as ElementTree
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlsplit


NATIVE_SUFFIXES = (".a", ".bc", ".dll", ".dylib", ".exe", ".lib", ".so", ".wasm")
COMPLIANCE_MANIFEST = "META-INF/kmediabridge/compliance/manifest.json"
INTERNAL_LICENSE = "LicenseRef-KMediaBridge-Internal"
INTERNAL_LICENSE_NAME = "KMediaBridge Internal Use Notice and Limited License"
INTERNAL_LICENSE_PATH = "/LICENSES/LicenseRef-KMediaBridge-Internal.txt"
LGPL_LICENSE = "LGPL-2.1-or-later"
LGPL_LICENSE_NAME = "GNU Lesser General Public License v2.1 or later"
LGPL_LICENSE_URL = "https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html"
API_ARTIFACT = "kmedia-bridge-api"
FFMPEG_ARTIFACT = "kmedia-bridge-ffmpeg"
RUNTIME_ARTIFACT = "kmedia-bridge-ffmpeg-runtime-desktop"
REQUIRED_RUNTIME_PLATFORMS = {
    "linux-x86_64",
    "macos-aarch64",
    "macos-x86_64",
    "windows-x86_64",
}
EXPECTED_RUNTIME_FLAVORS = {
    "linux-x86_64": "REMUX_ONLY",
    "macos-aarch64": "SUBTITLE_BURN_IN_SDR",
    "macos-x86_64": "SUBTITLE_BURN_IN_SDR",
    "windows-x86_64": "REMUX_ONLY",
}
EXPECTED_SUBTITLE_COMPONENTS = {
    "FreeType",
    "FriBidi library",
    "HarfBuzz",
    "libunibreak",
    "libass",
}
SHA256 = re.compile(r"^[0-9a-f]{64}$")


@dataclass(frozen=True)
class Pom:
    path: Path
    artifact_id: str
    license_name: str
    license_url: str


def is_native(name: str) -> bool:
    normalized = name.lower()
    return normalized.endswith(NATIVE_SUFFIXES) or ".so." in normalized


def require_public_https(url: str, label: str) -> None:
    parsed = urlsplit(url)
    if parsed.scheme != "https" or not parsed.netloc or parsed.username or parsed.password:
        raise ValueError(f"{label} must be a public HTTPS URL without credentials")


def read_properties(document: str, label: str) -> dict[str, str]:
    properties: dict[str, str] = {}
    for line in document.splitlines():
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator:
            raise ValueError(f"Malformed property in {label}: {line!r}")
        properties[key] = value.replace("\\n", "\n").replace("\\r", "\r").replace("\\\\", "\\")
    return properties


def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def direct_child_text(element: ElementTree.Element, name: str) -> str:
    for child in element:
        if local_name(child.tag) == name:
            return (child.text or "").strip()
    return ""


def parse_pom(path: Path, version: str) -> Pom:
    try:
        document = ElementTree.parse(path).getroot()
    except ElementTree.ParseError as error:
        raise ValueError(f"Malformed POM {path}: {error}") from error

    group_id = direct_child_text(document, "groupId")
    artifact_id = direct_child_text(document, "artifactId")
    pom_version = direct_child_text(document, "version")
    if group_id != "io.github.shusek" or not artifact_id or pom_version != version:
        raise ValueError(
            f"{path.name} has unexpected coordinates "
            f"{group_id}:{artifact_id}:{pom_version}"
        )

    licenses = [child for child in document if local_name(child.tag) == "licenses"]
    license_nodes = (
        [child for child in licenses[0] if local_name(child.tag) == "license"]
        if len(licenses) == 1
        else []
    )
    if len(license_nodes) != 1:
        raise ValueError(f"{path.name} must declare exactly one component license")
    license_name = direct_child_text(license_nodes[0], "name")
    license_url = direct_child_text(license_nodes[0], "url")
    if direct_child_text(license_nodes[0], "distribution") != "repo":
        raise ValueError(f"{path.name} must declare repository license distribution")

    return Pom(
        path=path,
        artifact_id=artifact_id,
        license_name=license_name,
        license_url=license_url,
    )


def collect_poms(repository: Path, version: str) -> list[Pom]:
    if not repository.is_dir():
        raise ValueError(f"repository does not exist: {repository}")
    paths = sorted(repository.rglob("*.pom"))
    if not paths:
        raise ValueError(f"no Maven POMs were staged in {repository}")
    wrong_versions = [path for path in paths if path.parent.name != version]
    if wrong_versions:
        raise ValueError(
            f"{repository} contains POMs outside version {version}: "
            f"{[str(path.relative_to(repository)) for path in wrong_versions]}"
        )
    poms = [parse_pom(path, version) for path in paths]
    artifact_ids = [pom.artifact_id for pom in poms]
    if len(artifact_ids) != len(set(artifact_ids)):
        raise ValueError(f"{repository} contains duplicate Maven artifact IDs")
    return poms


def artifact_family(artifact_id: str) -> str | None:
    if artifact_id == RUNTIME_ARTIFACT:
        return "runtime"
    if artifact_id == API_ARTIFACT or artifact_id.startswith(f"{API_ARTIFACT}-"):
        return "api"
    if artifact_id == FFMPEG_ARTIFACT or artifact_id.startswith(f"{FFMPEG_ARTIFACT}-"):
        return "ffmpeg"
    return None


def verify_internal_pom(pom: Pom) -> None:
    if pom.license_name not in {
        INTERNAL_LICENSE_NAME,
        f"{INTERNAL_LICENSE_NAME} ({INTERNAL_LICENSE})",
    }:
        raise ValueError(
            f"{pom.path.name} does not declare the internal-use license name"
        )
    parsed = urlsplit(pom.license_url)
    if (
        parsed.scheme != "https"
        or not parsed.netloc
        or parsed.username
        or parsed.password
        or not parsed.path.endswith(INTERNAL_LICENSE_PATH)
    ):
        raise ValueError(
            f"{pom.path.name} does not identify {INTERNAL_LICENSE} with its license URL"
        )


def verify_lgpl_pom(pom: Pom) -> None:
    if pom.license_name != LGPL_LICENSE_NAME or pom.license_url != LGPL_LICENSE_URL:
        raise ValueError(f"{pom.path.name} does not declare {LGPL_LICENSE}")


def archive_license_texts(archive: Path) -> list[str]:
    if not zipfile.is_zipfile(archive):
        return []
    with zipfile.ZipFile(archive) as zipped:
        names = [
            name
            for name in zipped.namelist()
            if name == "META-INF/LICENSE" or name.endswith("/META-INF/LICENSE")
        ]
        return [zipped.read(name).decode("utf-8") for name in names]


def archives_for_pom(pom: Pom, version: str) -> list[Path]:
    prefix = f"{pom.artifact_id}-{version}"
    return sorted(
        path
        for path in pom.path.parent.iterdir()
        if path.is_file()
        and path.name.startswith(prefix)
        and path.suffix in {".aar", ".jar", ".klib"}
        and not path.name.endswith(("-sources.jar", "-javadoc.jar"))
    )


def verify_private_repository(repository: Path, version: str) -> tuple[list[Pom], int]:
    poms = collect_poms(repository, version)
    families = {artifact_family(pom.artifact_id) for pom in poms}
    if None in families or families != {"api", "ffmpeg"}:
        raise ValueError(
            "private repository must contain exactly the api and ffmpeg artifact families; "
            f"found {[pom.artifact_id for pom in poms]}"
        )

    licensed_families: set[str] = set()
    archive_count = 0
    for pom in poms:
        verify_internal_pom(pom)
        family = artifact_family(pom.artifact_id)
        for archive in archives_for_pom(pom, version):
            archive_count += 1
            with zipfile.ZipFile(archive) as zipped:
                native_names = [name for name in zipped.namelist() if is_native(name)]
                if native_names:
                    raise ValueError(
                        f"private core archive {archive.name} embeds native payloads"
                    )
            texts = archive_license_texts(archive)
            if texts:
                if any(
                    "Internal Use Notice and Limited License" not in text
                    or "GNU LESSER GENERAL PUBLIC LICENSE" in text
                    for text in texts
                ):
                    raise ValueError(
                        f"{archive.name} carries an incorrect core license document"
                    )
                if family is not None:
                    licensed_families.add(family)
    if licensed_families != {"api", "ffmpeg"}:
        raise ValueError(
            "private api and ffmpeg publications must each embed the internal-use license"
        )
    return poms, archive_count


def verify_runtime_license_archive(repository: Path, version: str) -> Path:
    directory = repository / "io/github/shusek" / RUNTIME_ARTIFACT / version
    archive = directory / f"{RUNTIME_ARTIFACT}-{version}.jar"
    if not zipfile.is_zipfile(archive):
        raise ValueError(f"public runtime main JAR is missing or invalid: {archive}")
    with zipfile.ZipFile(archive) as zipped:
        names = set(zipped.namelist())
        required = {
            "META-INF/LICENSE",
            "META-INF/NOTICE",
            "META-INF/THIRD_PARTY_NOTICES.md",
            "META-INF/kmediabridge/RELINKING.md",
        }
        missing = sorted(required - names)
        if missing:
            raise ValueError(
                f"{archive.name} is missing runtime compliance documents: {missing}"
            )
        license_text = zipped.read("META-INF/LICENSE").decode("utf-8")
        if (
            "GNU LESSER GENERAL PUBLIC LICENSE" not in license_text
            or "Version 2.1" not in license_text
            or "Internal Use Notice and Limited License" in license_text
        ):
            raise ValueError(f"{archive.name} does not embed the complete LGPL v2.1 text")
    return archive


def verify_public_repository(repository: Path, version: str) -> tuple[list[Pom], int]:
    poms = collect_poms(repository, version)
    if {pom.artifact_id for pom in poms} != {RUNTIME_ARTIFACT}:
        raise ValueError(
            "public repository must contain exactly the LGPL runtime and no new core; "
            f"found {[pom.artifact_id for pom in poms]}"
        )
    verify_lgpl_pom(poms[0])
    verify_runtime_license_archive(repository, version)

    native_archives = 0
    for archive in repository.rglob("*"):
        if archive.parent.name != version:
            continue
        if archive.suffix not in {".aar", ".jar", ".klib"} or not zipfile.is_zipfile(archive):
            continue
        with zipfile.ZipFile(archive) as zipped:
            native_names = [name for name in zipped.namelist() if is_native(name)]
            if native_names:
                native_archives += 1
                verify_native_archive(archive, zipped, native_names)
    return poms, native_archives


def verify_native_archive(archive: Path, zipped: zipfile.ZipFile, native_names: list[str]) -> None:
    names = set(zipped.namelist())
    forbidden = [name for name in native_names if name.lower().endswith((".a", ".bc", ".exe", ".lib", ".wasm"))]
    if forbidden:
        raise ValueError(f"{archive.name} carries forbidden static/executable payloads: {forbidden}")
    if COMPLIANCE_MANIFEST not in names:
        raise ValueError(f"{archive.name} carries native code without {COMPLIANCE_MANIFEST}")
    manifest = json.loads(zipped.read(COMPLIANCE_MANIFEST))
    if manifest.get("schemaVersion") != 2 or manifest.get("distributionStatus") != "binary":
        raise ValueError(f"{archive.name} has no binary distribution manifest schema 2")
    if manifest.get("runtimeLicense") != LGPL_LICENSE or "projectLicense" in manifest:
        raise ValueError(f"{archive.name} does not declare the LGPL runtime boundary")
    ffmpeg = manifest.get("ffmpeg", {})
    if ffmpeg.get("license") != LGPL_LICENSE or ffmpeg.get("linkage") != "dynamic":
        raise ValueError(f"{archive.name} does not declare a dynamically linked LGPL FFmpeg runtime")
    require_public_https(str(ffmpeg.get("sourceOfferUrl", "")), "FFmpeg source offer")

    linked_components = manifest.get("linkedComponents", [])
    if {component.get("name") for component in linked_components} != EXPECTED_SUBTITLE_COMPONENTS:
        raise ValueError(f"{archive.name} subtitle component inventory differs")
    for component in linked_components:
        require_public_https(str(component.get("sourceOfferUrl", "")), f"{component.get('name')} source offer")
        if not SHA256.fullmatch(str(component.get("sourceSha256", ""))):
            raise ValueError(f"{archive.name} has an invalid source hash for {component.get('name')}")
    canonical_components = sorted(linked_components, key=lambda item: item["name"])

    platforms = {entry.get("id") for entry in manifest.get("platforms", [])}
    if platforms != REQUIRED_RUNTIME_PLATFORMS:
        raise ValueError(f"{archive.name} platform matrix differs: {sorted(platforms)}")
    for platform in manifest.get("platforms", []):
        platform_id = str(platform.get("id"))
        platform_manifest = platform.get("manifestPath")
        if platform_manifest not in names:
            raise ValueError(f"{archive.name} is missing platform manifest {platform_manifest}")
        properties = read_properties(zipped.read(platform_manifest).decode("utf-8"), str(platform_manifest))
        expected_flavor = EXPECTED_RUNTIME_FLAVORS[platform_id]
        if properties.get("runtimeFlavor") != expected_flavor or platform.get("runtimeFlavor") != expected_flavor:
            raise ValueError(f"{archive.name} has an unexpected runtime flavor for {platform_id}")
        can_burn = expected_flavor == "SUBTITLE_BURN_IN_SDR"
        if (properties.get("capability.canBurnSubtitles") == "true") != can_burn:
            raise ValueError(f"{archive.name} has a contradictory subtitle capability for {platform_id}")
        if platform.get("canBurnSubtitles") is not can_burn:
            raise ValueError(f"{archive.name} aggregate subtitle capability differs for {platform_id}")
        component_count = int(properties.get("component.count", "-1"))
        platform_components = []
        for index in range(component_count):
            component = {
                "name": properties[f"component.{index}.name"],
                "version": properties[f"component.{index}.version"],
                "license": properties[f"component.{index}.licenseSpdx"],
                "sourceOfferUrl": properties[f"component.{index}.sourceOfferUrl"],
                "sourceSha256": properties[f"component.{index}.sourceSha256"],
            }
            require_public_https(component["sourceOfferUrl"], f"{platform_id} {component['name']} source offer")
            platform_components.append(component)
        if can_burn and sorted(platform_components, key=lambda item: item["name"]) != canonical_components:
            raise ValueError(f"{archive.name} platform component evidence differs for {platform_id}")
        if not can_burn and platform_components:
            raise ValueError(f"{archive.name} remux-only platform declares subtitle components")

    declared = {entry.get("path"): entry for entry in manifest.get("nativePayloads", [])}
    if set(native_names) != set(declared):
        raise ValueError(f"{archive.name} native payload inventory differs from the archive")
    for name in native_names:
        payload = declared[name]
        actual = hashlib.sha256(zipped.read(name)).hexdigest()
        if payload.get("sha256") != actual:
            raise ValueError(f"{archive.name} has a payload SHA-256 mismatch for {name}")
        require_public_https(str(payload.get("sourceOfferUrl", "")), f"Source offer for {name}")
        if not payload.get("correspondingSourcePath"):
            raise ValueError(f"{archive.name} has no corresponding source path for {name}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--private-repository", type=Path, required=True)
    parser.add_argument("--public-repository", type=Path, required=True)
    parser.add_argument("--version", required=True)
    arguments = parser.parse_args()
    version = arguments.version
    try:
        private_poms, private_archives = verify_private_repository(
            arguments.private_repository.resolve(),
            version,
        )
        public_poms, native_archives = verify_public_repository(
            arguments.public_repository.resolve(),
            version,
        )
    except (
        ElementTree.ParseError,
        KeyError,
        OSError,
        UnicodeDecodeError,
        ValueError,
        zipfile.BadZipFile,
        json.JSONDecodeError,
    ) as error:
        print(f"publication error: {error}", file=sys.stderr)
        return 1

    print(
        f"Verified {len(private_poms)} private core POMs/{private_archives} archives, "
        f"{len(public_poms)} public LGPL runtime POM, and "
        f"{native_archives} documented native runtime archive(s)."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
