from concurrent.futures import ThreadPoolExecutor
import io
import json
from pathlib import Path
from urllib.error import HTTPError, URLError

import pytest
from fastapi.testclient import TestClient

from app.admin import update_agent
from app.config import get_settings
from app.main import create_app


ADMIN = {"Authorization": "Bearer updater-test-admin"}
JOB = {
    "id": "job-001", "channel": "dev", "status": "queued", "stage": "queued",
    "message": "Waiting", "created_at": "2026-11-02T00:00:00Z",
    "updated_at": "2026-11-02T00:00:00Z", "target_version": "0.7.9-dev.5",
}


@pytest.fixture
def client(tmp_path: Path, monkeypatch):
    monkeypatch.setenv("API_TOKEN", "updater-test-admin")
    monkeypatch.setenv("DATABASE_PATH", str(tmp_path / "sync.db"))
    monkeypatch.setenv("CONFIG_PATH", str(tmp_path / "config.json"))
    monkeypatch.setenv("LOG_DIR", str(tmp_path / "logs"))
    monkeypatch.setenv("MQTT_ENABLED", "false")
    monkeypatch.delenv("UPDATER_URL", raising=False)
    monkeypatch.delenv("UPDATE_TOKEN_FILE", raising=False)
    get_settings.cache_clear()
    with TestClient(create_app()) as value:
        yield value
    get_settings.cache_clear()


def test_update_api_requires_admin_and_is_disabled_without_configuration(client, tmp_path):
    assert client.get("/admin-api/server-update/status").status_code == 401
    account = client.post("/admin-api/accounts", headers=ADMIN, json={"name": "User"}).json()
    user = {"Authorization": f"Bearer {account['api_token']}"}
    for method, path, payload in (
        ("GET", "/status", None), ("GET", "/jobs/job-001", None),
        ("POST", "/jobs", {"channel": "dev", "request_id": "request-001"}),
        ("POST", "/jobs/job-001/retry", {}),
    ):
        response = client.request(method, "/admin-api/server-update" + path, headers=user, json=payload)
        assert response.status_code == 403
    status = client.get("/admin-api/server-update/status", headers=ADMIN)
    assert status.status_code == 200
    assert status.json()["enabled"] is False
    assert status.json()["active_job"] is None
    assert not (tmp_path / ".updater-token").exists()
    for path, payload in (("/jobs", {"channel": "dev", "request_id": "request-001"}), ("/jobs/job-001/retry", {})):
        assert client.post("/admin-api/server-update" + path, headers=ADMIN, json=payload).status_code == 503


def test_update_proxy_uses_internal_token_and_preserves_jobs(client, monkeypatch, tmp_path):
    monkeypatch.setenv("UPDATER_URL", "http://updater:8790/")
    calls = []

    def respond(request, timeout):
        calls.append((request, timeout))
        payload = {"active_job": JOB, "latest_job": JOB} if request.full_url.endswith("/status") else {**JOB, "secret": "never-forward"}
        return io.BytesIO(json.dumps(payload).encode())

    monkeypatch.setattr(update_agent, "urlopen", respond)
    status = client.get("/admin-api/server-update/status", headers=ADMIN)
    assert status.status_code == 200
    assert status.json()["enabled"] is True
    assert status.json()["active_job"] == JOB
    token = (tmp_path / ".updater-token").read_text()
    assert len(token) >= 32 and token != "updater-test-admin"
    started = client.post("/admin-api/server-update/jobs", headers=ADMIN, json={
        "channel": "dev", "request_id": "request-001",
    })
    assert started.status_code == 202
    assert started.json() == JOB
    assert client.get("/admin-api/server-update/jobs/job-001", headers=ADMIN).json() == JOB
    assert client.post("/admin-api/server-update/jobs/job-001/retry", headers=ADMIN).status_code == 202
    assert [(request.full_url, request.method, timeout) for request, timeout in calls] == [
        ("http://updater:8790/status", "GET", 3),
        ("http://updater:8790/jobs", "POST", 5),
        ("http://updater:8790/jobs/job-001", "GET", 3),
        ("http://updater:8790/jobs/job-001/retry", "POST", 5),
    ]
    assert all(request.get_header("Authorization") == f"Bearer {token}" for request, _ in calls)
    assert json.loads(calls[1][0].data) == {"channel": "dev", "request_id": "request-001"}
    assert (tmp_path / ".updater-token").read_text() == token
    assert token not in status.text + started.text
    assert not list(tmp_path.glob(".updater-token-*"))


def test_updater_token_creation_is_atomic_and_preserves_existing(client, tmp_path):
    with ThreadPoolExecutor(max_workers=8) as workers:
        tokens = list(workers.map(lambda _: update_agent._updater_token(), range(16)))
    assert len(set(tokens)) == 1
    assert tokens[0] == (tmp_path / ".updater-token").read_text()
    assert not list(tmp_path.glob(".updater-token-*"))


def test_default_token_path_follows_database_directory_not_config(client, monkeypatch, tmp_path):
    monkeypatch.setenv("CONFIG_PATH", str(tmp_path / "configuration" / "config.json"))
    token = update_agent._updater_token()
    assert (tmp_path / ".updater-token").read_text() == token
    assert not (tmp_path / "configuration" / ".updater-token").exists()


def test_explicit_update_token_file_overrides_database_and_config_paths(client, monkeypatch, tmp_path):
    token_path = tmp_path / "shared" / "updater-auth"
    monkeypatch.setenv("UPDATE_TOKEN_FILE", str(token_path))
    token = update_agent._updater_token()
    assert token_path.read_text() == token
    assert update_agent._updater_token() == token
    assert not (tmp_path / ".updater-token").exists()
    assert not list(token_path.parent.glob(".updater-token-*"))


def test_proxy_preserves_target_update_capability_failure(client, monkeypatch):
    monkeypatch.setenv("UPDATER_URL", "http://updater:8790")
    failed = {**JOB, "status": "failed", "message": "TARGET_WEB_UPDATE_UNSUPPORTED: target lacks update support"}
    monkeypatch.setattr(update_agent, "urlopen", lambda *args, **kwargs: io.BytesIO(json.dumps(failed).encode()))
    response = client.get("/admin-api/server-update/jobs/job-001", headers=ADMIN)
    assert response.status_code == 200
    assert response.json()["status"] == "failed"
    assert "TARGET_WEB_UPDATE_UNSUPPORTED" in response.json()["message"]


@pytest.mark.parametrize(("failure", "expected"), [
    (URLError("offline"), 503), (TimeoutError(), 503),
    (HTTPError("http://updater:8790/jobs", 409, "Conflict", {}, None), 409),
    (HTTPError("http://updater:8790/jobs", 404, "Missing", {}, None), 404),
])
def test_updater_errors_are_actionable_and_do_not_expose_credentials(client, monkeypatch, failure, expected):
    monkeypatch.setenv("UPDATER_URL", "http://updater:8790")

    def unavailable(*args, **kwargs):
        raise failure

    monkeypatch.setattr(update_agent, "urlopen", unavailable)
    result = client.post("/admin-api/server-update/jobs", headers=ADMIN, json={
        "channel": "dev", "request_id": "request-001",
    })
    assert result.status_code == expected
    assert "Bearer" not in result.text and "updater-test-admin" not in result.text


def test_session_updates_keep_csrf_and_payload_boundaries(client):
    login = client.post("/auth/session", json={"api_key": "updater-test-admin"})
    assert login.status_code == 200
    payload = {"channel": "dev", "request_id": "request-001"}
    assert client.post("/admin-api/server-update/jobs", json=payload).status_code == 403
    csrf = {"X-CSRF-Token": login.json()["csrf_token"]}
    assert client.post("/admin-api/server-update/jobs", headers=csrf, json=payload).status_code == 503
    for invalid in ({**payload, "url": "http://another-service"}, {**payload, "channel": "latest"}):
        assert client.post("/admin-api/server-update/jobs", headers=csrf, json=invalid).status_code == 422
