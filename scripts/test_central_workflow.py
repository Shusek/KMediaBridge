# SPDX-License-Identifier: LGPL-2.1-or-later

import unittest
from pathlib import Path


class CentralWorkflowTest(unittest.TestCase):
    def test_existing_deployment_is_polled_for_forty_minutes(self) -> None:
        root = Path(__file__).resolve().parents[1]
        workflow = (root / ".github/workflows/publish-maven-central.yml").read_text(
            encoding="utf-8"
        )

        self.assertIn("timeout-minutes: 45", workflow)
        self.assertIn("for attempt in {1..240}; do", workflow)
        self.assertNotIn("for attempt in {1..90}; do", workflow)
        self.assertIn("instead of", workflow)
        self.assertIn("duplicate upload", workflow)


if __name__ == "__main__":
    unittest.main()
