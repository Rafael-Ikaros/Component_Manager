from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from tools.versioning import sync_version


class ChangelogVersionBaselineTests(unittest.TestCase):
    def test_dev_heading_keeps_latest_stable_as_metadata_baseline(self) -> None:
        changelog = (
            "## [Unreleased]\nbump: patch\n\n"
            "## [0.7.7-dev.1]\n- Preview.\n\n"
            "## [0.7.6]\n- Stable.\n"
        )
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "CHANGELOG.md"
            path.write_text(changelog, encoding="utf-8")
            with patch.object(sync_version, "CHANGELOG_PATH", path):
                state = sync_version.parse_changelog()

        self.assertEqual("0.7.7-dev.1", state.latest_heading)
        self.assertEqual("0.7.6", str(state.latest_release))
        self.assertFalse(state.unreleased_has_entries)

    def test_explicit_stable_heading_syncs_without_consuming_unreleased(self) -> None:
        state = sync_version.ChangelogState(
            text="## [Unreleased]\n- Draft.\n## [0.7.7]\n- Release.\n",
            latest_heading="0.7.7",
            latest_release=sync_version.SemVer(0, 7, 7),
            unreleased_has_entries=True,
            bump_kind="patch",
            next_version=sync_version.SemVer(0, 7, 8),
        )
        with (
            patch.object(sync_version, "parse_changelog", return_value=state),
            patch.object(
                sync_version,
                "read_current_versions",
                return_value={"android.versionName": "0.7.6"},
            ),
            patch.object(sync_version, "update_android_build") as android,
            patch.object(sync_version, "update_admin_web") as web,
            patch.object(sync_version, "stage_version_files") as stage,
            patch.object(sync_version, "write_text") as write,
        ):
            sync_version.sync_latest_stable_metadata()

        android.assert_called_once_with(sync_version.SemVer(0, 7, 7), increment_code=True)
        web.assert_called_once_with(sync_version.SemVer(0, 7, 7))
        stage.assert_called_once()
        write.assert_not_called()

    def test_stable_sync_leaves_retired_windows_metadata_untouched(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            paths = {
                "CHANGELOG_PATH": root / "docs" / "CHANGELOG.md",
                "ANDROID_BUILD_PATH": root / "android-client" / "app" / "build.gradle.kts",
                "ADMIN_PACKAGE_PATH": root / "admin-web" / "package.json",
                "ADMIN_LOCK_PATH": root / "admin-web" / "package-lock.json",
            }
            contents = {
                "CHANGELOG_PATH": "## [Unreleased]\nbump: patch\n\n## [0.7.7]\n- Stable.\n",
                "ANDROID_BUILD_PATH": 'versionCode = 6\nversionName = "0.7.6"\n',
                "ADMIN_PACKAGE_PATH": '{"version": "0.7.6"}',
                "ADMIN_LOCK_PATH": '{"version": "0.7.6", "packages": {"": {"version": "0.7.6"}}}',
            }
            for name, path in paths.items():
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(contents[name], encoding="utf-8")

            windows = root / "windows-client" / "ComponentVault.WinUI"
            windows.mkdir(parents=True)
            frozen_files = {
                windows / "ComponentVault.WinUI.csproj": (
                    b"<Project><PropertyGroup><Version>0.7.6</Version>"
                    b"<InformationalVersion>0.7.6</InformationalVersion>"
                    b"<AssemblyVersion>0.7.6.0</AssemblyVersion>"
                    b"<FileVersion>0.7.6.0</FileVersion></PropertyGroup></Project>\r\n"
                ),
                windows / "Package.appxmanifest": b'<Package><Identity Version="0.7.6.0"/></Package>\r\n',
                windows / "app.manifest": b'<assembly><assemblyIdentity version="0.7.6.0"/></assembly>\r\n',
            }
            for path, original in frozen_files.items():
                path.write_bytes(original)

            with (
                patch.multiple(sync_version, ROOT=root, **paths),
                patch.multiple(
                    sync_version,
                    create=True,
                    WINDOWS_CSPROJ_PATH=windows / "ComponentVault.WinUI.csproj",
                    WINDOWS_MSIX_PATH=windows / "Package.appxmanifest",
                    WINDOWS_APP_MANIFEST_PATH=windows / "app.manifest",
                ),
                patch.object(sync_version, "find_git_root", return_value=root),
                patch.object(sync_version.subprocess, "run") as git,
            ):
                sync_version.sync_latest_stable_metadata()
                sync_version.check_version_sync()
                self.assertEqual("7", sync_version.read_current_versions()["android.versionCode"])

            for path, original in frozen_files.items():
                self.assertEqual(original, path.read_bytes())
            git.assert_called_once_with(
                ["git", "add", *(path.relative_to(root).as_posix() for path in paths.values())],
                cwd=root,
                check=True,
            )

    def test_dev_heading_cannot_sync_stable_metadata(self) -> None:
        state = sync_version.ChangelogState(
            text="",
            latest_heading="0.7.7-dev.1",
            latest_release=sync_version.SemVer(0, 7, 6),
            unreleased_has_entries=False,
            bump_kind="patch",
            next_version=None,
        )
        with patch.object(sync_version, "parse_changelog", return_value=state):
            with self.assertRaises(sync_version.VersionSyncError):
                sync_version.sync_latest_stable_metadata()

    def test_partial_stable_metadata_repair_does_not_bump_android_code(self) -> None:
        state = sync_version.ChangelogState(
            text="", latest_heading="0.7.7",
            latest_release=sync_version.SemVer(0, 7, 7),
            unreleased_has_entries=False, bump_kind="patch", next_version=None,
        )
        with (
            patch.object(sync_version, "parse_changelog", return_value=state),
            patch.object(
                sync_version, "read_current_versions",
                return_value={"android.versionName": "0.7.7"},
            ),
            patch.object(
                sync_version, "validate_synced_versions",
                side_effect=sync_version.VersionSyncError("web version differs"),
            ),
            patch.object(sync_version, "update_android_build") as android,
            patch.object(sync_version, "update_admin_web"),
            patch.object(sync_version, "stage_version_files"),
        ):
            sync_version.sync_latest_stable_metadata()

        android.assert_called_once_with(sync_version.SemVer(0, 7, 7), increment_code=False)


if __name__ == "__main__":
    unittest.main()
