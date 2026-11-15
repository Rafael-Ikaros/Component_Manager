from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from tools.versioning import changelog_release
from tools.versioning.changelog_release import (
    parse_latest_release,
    release_section_changed,
)


class ChangelogReleaseTests(unittest.TestCase):
    def test_latest_heading_controls_channel_despite_historical_dev_links(self) -> None:
        text = (
            "# Changelog\n"
            "Historical [0.7.4-dev.1](releases/0.7.4-dev.1.md)\n"
            "## [Unreleased]\n"
            "bump: patch\n\n"
            "- Work still in progress.\n\n"
            "## [0.7.7-dev.1] - 2026-09-27\n\n"
            "- Release candidate.\n\n"
            "## [0.7.6] - 2026-09-26\n\n"
            "- Previous stable.\n"
        )
        release = parse_latest_release(text)
        self.assertEqual("v0.7.7-dev.1", release.tag)
        self.assertTrue(release.prerelease)
        self.assertFalse(release.android_only)
        self.assertIn("Release candidate", release.section)
        self.assertNotIn("Work still in progress", release.section)
        self.assertNotIn("Previous stable", release.section)

    def test_unreleased_only_edit_does_not_change_release_section(self) -> None:
        previous = parse_latest_release(
            "## [Unreleased]\nbump: patch\n\n## [0.7.6]\n- Stable.\n"
        )
        current = parse_latest_release(
            "## [Unreleased]\nbump: patch\n- New draft.\n\n"
            "## [0.7.6]\n- Stable.\n"
        )
        self.assertFalse(release_section_changed(current, previous))
        self.assertTrue(release_section_changed(current, None))

    def test_stable_heading_and_section_edit_are_detected(self) -> None:
        previous = parse_latest_release(
            "## [Unreleased]\n## [0.7.6]\n- Previous.\n"
        )
        current = parse_latest_release(
            "## [Unreleased]\n## [0.7.7]\n- Stable.\n"
            "## [0.7.6]\n- Previous.\n"
        )
        self.assertFalse(current.prerelease)
        self.assertTrue(release_section_changed(current, previous))

    def test_rejects_invalid_latest_heading(self) -> None:
        with self.assertRaises(ValueError):
            parse_latest_release("## [Unreleased]\n## [0.7.7-dev.0]\n")

    def test_android_only_marker_applies_to_latest_release_section(self) -> None:
        release = parse_latest_release(
            "## [Unreleased]\n## [0.7.9-dev.27]\n<!-- release: android-only -->\n- Android.\n"
        )
        self.assertTrue(release.android_only)
        later = parse_latest_release(
            "## [Unreleased]\n## [0.7.9-dev.28]\n- All targets.\n" + release.section
        )
        self.assertFalse(later.android_only)

    def test_stable_release_cannot_be_android_only(self) -> None:
        with self.assertRaisesRegex(ValueError, "must be prereleases"):
            parse_latest_release("## [Unreleased]\n## [0.7.9]\n<!-- release: android-only -->\n")

    def test_only_unreleased_is_a_normal_no_release_state(self) -> None:
        current = parse_latest_release(
            "# Changelog\n## [Unreleased]\nbump: patch\n- Draft work.\n"
        )
        self.assertIsNone(current)
        self.assertFalse(release_section_changed(current, None))

    def test_first_release_after_only_unreleased_is_changed(self) -> None:
        previous = parse_latest_release("## [Unreleased]\n- Draft work.\n")
        current = parse_latest_release(
            "## [Unreleased]\n## [0.7.7-dev.1]\n- Preview.\n"
        )
        self.assertTrue(release_section_changed(current, previous))

    def test_cli_outputs_skip_for_only_unreleased(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            changelog = Path(directory) / "CHANGELOG.md"
            output = Path(directory) / "output.txt"
            changelog.write_text("## [Unreleased]\n- Draft work.\n", encoding="utf-8")
            with (
                patch.object(changelog_release, "CHANGELOG_PATH", changelog),
                patch("sys.argv", ["changelog_release", "--github-output", str(output)]),
            ):
                self.assertEqual(0, changelog_release.main())
            self.assertEqual(
                "has_release=false\nsection_changed=false\n",
                output.read_text(encoding="utf-8"),
            )


if __name__ == "__main__":
    unittest.main()
