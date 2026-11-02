import io
import json

import pytest
from fastapi import HTTPException

from app.admin import about


@pytest.mark.parametrize(("current", "latest", "expected"), [
    ("0.7.6", "v0.7.7", True), ("0.7.7-dev.5", "v0.7.7", True),
    ("0.7.8-dev.1", "v0.7.7", False), ("0.7.7", "v0.7.7", False),
    ("source", "v0.7.7", None),
])
def test_release_comparison(monkeypatch, current, latest, expected):
    monkeypatch.setattr(about, "BUILD_VERSION", current)
    monkeypatch.setattr(about, "urlopen", lambda *args, **kwargs: io.BytesIO(
        json.dumps({"tag_name": latest, "prerelease": False}).encode(),
    ))
    result = about.check_update()
    assert result["update_available"] is expected
    assert result["automatic_update"] is False


def test_invalid_release_is_not_reported_up_to_date(monkeypatch):
    monkeypatch.setattr(about, "urlopen", lambda *args, **kwargs: io.BytesIO(b"{}"))
    with pytest.raises(HTTPException) as error:
        about.check_update()
    assert error.value.status_code == 502


def test_about_contains_author_and_no_self_update():
    result = about.about()
    assert result["author"] == "Rafael-Ikaros"
    assert result["deployment"] in {"container", "source_or_service"}


def test_dev_channel_selects_numeric_latest_and_allows_newer_stable(monkeypatch):
    releases = [
        {"tag_name": "v0.7.9-dev.9", "prerelease": True},
        {"tag_name": "v0.7.9-dev.10", "prerelease": True},
        {"tag_name": "v0.8.0-dev.1", "draft": True},
        {"tag_name": "preview-unrelated", "prerelease": True},
    ]
    requests = []
    def respond(request, **kwargs):
        requests.append(request.full_url)
        return io.BytesIO(json.dumps(releases).encode())
    monkeypatch.setattr(about, "urlopen", respond)
    monkeypatch.setattr(about, "BUILD_VERSION", "0.7.9-dev.9")
    result = about.check_update("dev")
    assert result["latest_version"] == "v0.7.9-dev.10"
    assert result["update_available"] is True
    assert result["image_tag"] == "dev"
    assert result["web_image_tag"] == "web-dev"
    assert requests == [about.DEV_RELEASE_API]
    releases.append({"tag_name": "v0.7.9", "prerelease": False})
    assert about.check_update("dev")["latest_version"] == "v0.7.9"


@pytest.mark.parametrize("release", [
    {"tag_name": "v0.7.9-dev.1", "prerelease": True},
    {"tag_name": "v0.7.9-dev.1", "prerelease": False},
    {"tag_name": "v0.7.9", "draft": True},
])
def test_stable_channel_never_includes_dev_or_draft(monkeypatch, release):
    monkeypatch.setattr(about, "urlopen", lambda *args, **kwargs: io.BytesIO(json.dumps(release).encode()))
    with pytest.raises(HTTPException) as error:
        about.check_update("stable")
    assert error.value.status_code == 502
