#!/usr/bin/env python3
# SPDX-License-Identifier: LGPL-2.1-or-later

"""Regression tests for the mixed-license publication boundary."""

from __future__ import annotations

import tempfile
import unittest
import zipfile
from pathlib import Path

try:
    from scripts import generate_sbom, verify_publications
except ImportError:
    import generate_sbom
    import verify_publications


VERSION = "0.4.0-test"
GROUP_PATH = Path("io/github/shusek")


def write_pom(
    repository: Path,
    artifact_id: str,
    license_name: str,
    license_url: str,
) -> Path:
    directory = repository / GROUP_PATH / artifact_id / VERSION
    directory.mkdir(parents=True, exist_ok=True)
    pom = directory / f"{artifact_id}-{VERSION}.pom"
    pom.write_text(
        f"""\
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>io.github.shusek</groupId>
  <artifactId>{artifact_id}</artifactId>
  <version>{VERSION}</version>
  <licenses>
    <license>
      <name>{license_name}</name>
      <url>{license_url}</url>
      <distribution>repo</distribution>
    </license>
  </licenses>
</project>
""",
        encoding="utf-8",
    )
    return pom


def write_jar(repository: Path, artifact_id: str, entries: dict[str, str]) -> Path:
    directory = repository / GROUP_PATH / artifact_id / VERSION
    directory.mkdir(parents=True, exist_ok=True)
    archive = directory / f"{artifact_id}-{VERSION}.jar"
    with zipfile.ZipFile(archive, "w") as zipped:
        for name, content in entries.items():
            zipped.writestr(name, content)
    return archive


class LicenseSplitTest(unittest.TestCase):
    def stage_repositories(self, directory: Path) -> tuple[Path, Path]:
        private = directory / "private"
        public = directory / "public"
        internal_name = (
            f"{verify_publications.INTERNAL_LICENSE_NAME} "
            f"({verify_publications.INTERNAL_LICENSE})"
        )
        internal_url = (
            "https://github.com/Shusek/KMediaBridge/blob/main"
            f"{verify_publications.INTERNAL_LICENSE_PATH}"
        )
        internal_notice = (
            "KMediaBridge Internal Use Notice and Limited License\n"
            "authorized collaborators\n"
            "All rights not expressly granted above are reserved.\n"
        )
        for artifact in (
            verify_publications.API_ARTIFACT,
            verify_publications.FFMPEG_ARTIFACT,
        ):
            write_pom(private, artifact, internal_name, internal_url)
            write_jar(private, artifact, {"META-INF/LICENSE": internal_notice})

        write_pom(
            public,
            verify_publications.RUNTIME_ARTIFACT,
            verify_publications.LGPL_LICENSE_NAME,
            verify_publications.LGPL_LICENSE_URL,
        )
        write_jar(
            public,
            verify_publications.RUNTIME_ARTIFACT,
            {
                "META-INF/LICENSE": (
                    "GNU LESSER GENERAL PUBLIC LICENSE\nVersion 2.1\n"
                ),
                "META-INF/NOTICE": "Runtime notice\n",
                "META-INF/THIRD_PARTY_NOTICES.md": "Third-party notices\n",
                "META-INF/kmediabridge/RELINKING.md": "Relinking instructions\n",
            },
        )
        return private, public

    def test_accepts_private_core_and_public_runtime(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            private, public = self.stage_repositories(Path(temporary))
            private_poms, _ = verify_publications.verify_private_repository(
                private,
                VERSION,
            )
            public_poms, _ = verify_publications.verify_public_repository(
                public,
                VERSION,
            )

        self.assertEqual(len(private_poms), 2)
        self.assertEqual(
            [pom.artifact_id for pom in public_poms],
            [verify_publications.RUNTIME_ARTIFACT],
        )

    def test_rejects_core_in_public_repository(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            private, public = self.stage_repositories(Path(temporary))
            del private
            write_pom(
                public,
                verify_publications.API_ARTIFACT,
                verify_publications.INTERNAL_LICENSE_NAME,
                (
                    "https://github.com/Shusek/KMediaBridge/blob/main"
                    f"{verify_publications.INTERNAL_LICENSE_PATH}"
                ),
            )
            with self.assertRaisesRegex(ValueError, "no new core"):
                verify_publications.verify_public_repository(public, VERSION)

    def test_sbom_uses_spdx_expression_for_internal_license(self) -> None:
        self.assertEqual(
            generate_sbom.license_choice(generate_sbom.INTERNAL_LICENSE),
            {"expression": "LicenseRef-KMediaBridge-Internal"},
        )
        self.assertEqual(
            generate_sbom.license_choice(generate_sbom.LGPL_LICENSE),
            {"license": {"id": "LGPL-2.1-or-later"}},
        )


if __name__ == "__main__":
    unittest.main()
