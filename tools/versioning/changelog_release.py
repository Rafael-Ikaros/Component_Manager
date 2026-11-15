#!/usr/bin/env python3
"""Select a release from the latest concrete changelog section."""
from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path

from tools.versioning.release_channel import parse_release_tag
from tools.versioning.sync_version import CHANGELOG_HEADER_RE, CHANGELOG_PATH

ANDROID_ONLY_MARKER = "<!-- release: android-only -->"


@dataclass(frozen=True)
class ChangelogRelease:
    version: str
    tag: str
    prerelease: bool
    section: str
    android_only: bool = False


def parse_latest_release(text: str) -> ChangelogRelease | None:
    """Return the first release after Unreleased, or None while only drafting."""
    lines = text.splitlines(keepends=True)
    headers = [
        (index, match.group("version"))
        for index, line in enumerate(lines)
        if (match := CHANGELOG_HEADER_RE.fullmatch(line.strip())) is not None
    ]
    if not headers or headers[0][1] != "Unreleased":
        raise ValueError("changelog needs an Unreleased heading")
    if len(headers) < 2:
        return None

    start, version = headers[1]
    if version == "Unreleased":
        raise ValueError("changelog contains a duplicate Unreleased heading")
    tag = f"v{version}"
    channel = parse_release_tag(tag)
    end = headers[2][0] if len(headers) > 2 else len(lines)
    section = "".join(lines[start:end]).strip()
    android_only = any(line.strip() == ANDROID_ONLY_MARKER for line in lines[start + 1:end])
    if android_only and not channel.prerelease:
        raise ValueError("Android-only releases must be prereleases")
    return ChangelogRelease(version, tag, channel.prerelease, section, android_only)


def release_section_changed(
    current: ChangelogRelease | None, previous: ChangelogRelease | None
) -> bool:
    if current is None:
        return False
    return previous is None or (
        current.version != previous.version or current.section != previous.section
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--github-output", type=Path, required=True)
    parser.add_argument("--previous-changelog", type=Path)
    args = parser.parse_args()

    try:
        current = parse_latest_release(CHANGELOG_PATH.read_text(encoding="utf-8"))
        previous = (
            parse_latest_release(args.previous_changelog.read_text(encoding="utf-8"))
            if args.previous_changelog is not None
            else None
        )
    except (OSError, ValueError) as exc:
        parser.error(str(exc))

    with args.github_output.open("a", encoding="utf-8", newline="\n") as output:
        if current is None:
            output.write("has_release=false\n")
            output.write("section_changed=false\n")
            return 0
        changed = release_section_changed(current, previous)
        output.write("has_release=true\n")
        output.write(f"version={current.version}\n")
        output.write(f"tag={current.tag}\n")
        output.write(f"prerelease={'true' if current.prerelease else 'false'}\n")
        output.write(f"android_only={'true' if current.android_only else 'false'}\n")
        output.write(f"section_changed={'true' if changed else 'false'}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
