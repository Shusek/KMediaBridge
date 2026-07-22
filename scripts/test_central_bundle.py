# SPDX-License-Identifier: LGPL-2.1-or-later

from __future__ import annotations

import importlib.util
from pathlib import Path
import tempfile
import unittest


MODULE_PATH = Path(__file__).with_name("build_central_bundle.py")
SPEC = importlib.util.spec_from_file_location("central_bundle", MODULE_PATH)
central = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(central)


class CentralBundleTest(unittest.TestCase):
    def test_normalize_removes_only_gradle_generated_files(self) -> None:
        with tempfile.TemporaryDirectory() as value:
            staging = Path(value)
            version = "0.5.0-rc.1"
            expected: set[Path] = set()
            for artifact in central.ROOT_ARTIFACTS:
                directory = staging / "io/github/shusek" / artifact / version
                directory.mkdir(parents=True)
                for extension in ("pom", "module"):
                    path = directory / f"{artifact}-{version}.{extension}"
                    path.write_bytes(b"artifact")
                    expected.add(path)
                    for suffix in central.GENERATED_CHECKSUM_SUFFIXES:
                        path.with_name(path.name + suffix).write_bytes(b"generated")
                metadata = directory.parent / "maven-metadata.xml"
                metadata.write_bytes(b"generated")
                for suffix in central.GENERATED_CHECKSUM_SUFFIXES:
                    metadata.with_name(metadata.name + suffix).write_bytes(b"generated")
            central.normalize_staging(staging, version)
            actual = {path for path in staging.rglob("*") if path.is_file()}
            self.assertEqual(expected, actual)

    def test_normalize_rejects_an_unknown_file(self) -> None:
        with tempfile.TemporaryDirectory() as value:
            staging = Path(value)
            version = "0.5.0-rc.1"
            for artifact in central.ROOT_ARTIFACTS:
                directory = staging / "io/github/shusek" / artifact / version
                directory.mkdir(parents=True)
                (directory / f"{artifact}-{version}.pom").write_bytes(b"artifact")
            (staging / "unexpected").write_bytes(b"unknown")
            with self.assertRaisesRegex(ValueError, "outside the release namespace"):
                central.normalize_staging(staging, version)


if __name__ == "__main__":
    unittest.main()
