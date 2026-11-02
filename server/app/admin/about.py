"""Read-only deployment identity and explicit release-channel checks."""

from __future__ import annotations

import json
from pathlib import Path
import re
from typing import Literal
from urllib.error import HTTPError, URLError
from urllib.request import Request as UrlRequest, urlopen

from fastapi import APIRouter, Depends, HTTPException

from ..auth import require_token
from ..build_info import BUILD_REVISION, BUILD_VERSION

router = APIRouter(prefix="/admin-api/about", dependencies=[Depends(require_token)])
REPOSITORY = "https://github.com/Rafael-Ikaros/Component_Manager"
RELEASE_API = "https://api.github.com/repos/Rafael-Ikaros/Component_Manager/releases/latest"
DEV_RELEASE_API = "https://api.github.com/repos/Rafael-Ikaros/Component_Manager/releases?per_page=100"


def version_key(value: str) -> tuple[int, int, int, int, int] | None:
    match = re.fullmatch(r"v?(\d+)\.(\d+)\.(\d+)(?:-dev\.(\d+))?", value)
    if not match:
        return None
    major, minor, patch, dev = match.groups()
    return int(major), int(minor), int(patch), int(dev is None), int(dev or 0)


@router.get("")
def about() -> dict:
    return {
        "name": "Component Vault Server",
        "author": "Rafael-Ikaros",
        "version": BUILD_VERSION,
        "running_channel": "dev" if "-dev." in BUILD_VERSION else "stable",
        "revision": BUILD_REVISION,
        "deployment": "container" if Path("/.dockerenv").exists() else "source_or_service",
        "repository_url": REPOSITORY,
        "deployment_guide_url": f"{REPOSITORY}/blob/master/docs/dockerhub.md",
    }


@router.post("/check-update")
def check_update(channel: Literal["stable", "dev"] = "stable") -> dict:
    request = UrlRequest(DEV_RELEASE_API if channel == "dev" else RELEASE_API, headers={
        "Accept": "application/vnd.github+json", "User-Agent": "Component-Vault-Server",
    })
    try:
        with urlopen(request, timeout=10) as response:
            payload = json.load(response)
        releases = payload if channel == "dev" else [payload]
        if not isinstance(releases, list):
            raise ValueError("Invalid release list")
        candidates = []
        for release in releases:
            if not isinstance(release, dict) or release.get("draft"):
                continue
            latest = str(release.get("tag_name", ""))
            target = version_key(latest)
            if target is None:
                continue
            if channel == "stable" and (release.get("prerelease") or target[3] == 0):
                continue
            candidates.append((target, latest))
        if not candidates:
            raise ValueError("No valid release in selected channel")
        target, latest = max(candidates)
    except (HTTPError, URLError, TimeoutError, ValueError, KeyError, TypeError):
        raise HTTPException(502, "Release check unavailable. Try again later.") from None
    current = version_key(BUILD_VERSION)
    return {
        "current_version": BUILD_VERSION,
        "channel": channel,
        "image_tag": "dev" if channel == "dev" else "latest",
        "web_image_tag": "web-dev" if channel == "dev" else "web-latest",
        "latest_version": latest,
        "update_available": target > current if current is not None else None,
        "release_url": f"{REPOSITORY}/releases/tag/{latest}",
        "automatic_update": False,
    }
