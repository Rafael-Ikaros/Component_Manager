from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from threading import Event
import time

from fastapi import HTTPException
from fastapi.testclient import TestClient

from app.admin import spec_enrichment
from app.accounts import authenticate
from app.config import get_settings
from app.database import _connect, get_db
from app.main import create_app
from app.schemas import LcscLookupResponse


ADMIN = {"Authorization": "Bearer test-admin-key"}


def _client(tmp_path: Path, monkeypatch) -> TestClient:
    monkeypatch.setenv("API_TOKEN", "test-admin-key")
    monkeypatch.setenv("DATABASE_PATH", str(tmp_path / "admin.db"))
    monkeypatch.setenv("CONFIG_PATH", str(tmp_path / "config.json"))
    monkeypatch.setenv("LOG_DIR", str(tmp_path / "logs"))
    monkeypatch.setenv("MQTT_ENABLED", "false")
    monkeypatch.setenv("WEB_INVENTORY_ENABLED", "true")
    get_settings.cache_clear()
    return TestClient(create_app())


def test_cookie_session_requires_csrf_and_revokes_on_key_rotation(
    tmp_path: Path, monkeypatch,
) -> None:
    with _client(tmp_path, monkeypatch) as client:
        account = client.post(
            "/admin-api/accounts", headers=ADMIN, json={"name": "Alice"},
        ).json()
        login = client.post("/auth/session", json={"api_key": account["api_token"]})
        assert login.status_code == 200
        assert "httponly" in login.headers["set-cookie"].lower()
        assert "samesite=lax" in login.headers["set-cookie"].lower()
        session = client.get("/auth/session").json()
        assert session["identity"]["account_id"] == account["account_id"]
        assert session["csrf_token"] == login.json()["csrf_token"]
        assert client.get("/admin-api/dashboard").status_code == 200
        assert client.post("/admin-api/components/spec-enrichment/jobs", json={}).status_code == 403
        assert client.post(
            "/admin-api/components/spec-enrichment/jobs", json={},
            headers={"X-CSRF-Token": session["csrf_token"]},
        ).status_code == 202
        rotated = client.post(
            f"/admin-api/accounts/{account['account_id']}/rotate-key", headers=ADMIN,
        )
        assert rotated.status_code == 200
        assert client.get("/auth/session").status_code == 401
        assert client.get("/admin-api/dashboard").status_code == 401
        assert client.get("/admin-api/dashboard", headers=ADMIN).status_code == 200


def test_admin_cookie_accesses_setup_then_logout_and_expiry(
    tmp_path: Path, monkeypatch,
) -> None:
    with _client(tmp_path, monkeypatch) as client:
        login = client.post("/auth/session", json={"api_key": "test-admin-key"})
        assert login.status_code == 200
        csrf = login.json()["csrf_token"]
        assert client.get("/setup/config").status_code == 200
        assert client.get("/setup/logs").status_code == 200
        settings_update = {
            "api_token": "test-admin-key",
            "admin_web_origins": ["http://localhost:5173"],
            "web_inventory_enabled": True,
        }
        assert client.post("/setup/config", json=settings_update).status_code == 403
        assert client.post(
            "/setup/config", json=settings_update,
            headers={"X-CSRF-Token": csrf},
        ).status_code == 200
        assert client.delete("/auth/session").status_code == 403
        assert client.delete("/auth/session", headers={"X-CSRF-Token": csrf}).status_code == 200
        assert client.get("/auth/session").status_code == 401
        client.post("/auth/session", json={"api_key": "test-admin-key"})
        with _connect(get_settings().database_path) as connection:
            connection.execute("UPDATE web_sessions SET expires_at = '2000-01-01T00:00:00+00:00'")
        assert client.get("/auth/session").status_code == 401


def test_delete_account_waits_for_enrichment_and_removes_only_its_database(
    tmp_path: Path, monkeypatch,
) -> None:
    entered, release = Event(), Event()
    with _client(tmp_path, monkeypatch) as client:
        alice = client.post("/admin-api/accounts", headers=ADMIN, json={"name": "Alice"}).json()
        bob = client.post("/admin-api/accounts", headers=ADMIN, json={"name": "Bob"}).json()
        alice_headers = {"Authorization": f"Bearer {alice['api_token']}"}
        created = client.post(
            "/admin-api/storage-locations", headers=alice_headers,
            json={"request_id": "alice-location", "id": "A", "name": "Shelf"},
        )
        assert created.status_code == 201
        stale_principal = authenticate(get_settings(), alice["api_token"])
        assert stale_principal is not None
        created = client.post(
            "/admin-api/components", headers=alice_headers,
            json={
                "request_id": "alice-component", "sku": "C1234", "name": "Cap",
                "category": "电容", "package_name": "0603", "location_id": "A",
            },
        )
        assert created.status_code == 201

        def blocked_lookup(**kwargs):
            entered.set()
            assert release.wait(3)
            return LcscLookupResponse(
                found=True, sku="C1234", parameters={"Capacitance": "22pF"},
            )

        monkeypatch.setattr(spec_enrichment, "lookup_explicit_sku_metadata", blocked_lookup)
        job = client.post(
            "/admin-api/components/spec-enrichment/jobs", headers=alice_headers, json={},
        )
        assert job.status_code == 202
        assert entered.wait(2)
        with ThreadPoolExecutor(max_workers=2) as pool:
            deleting = pool.submit(
                client.delete, f"/admin-api/accounts/{alice['account_id']}",
                headers=ADMIN,
            )
            # Simulate a request that authenticated before deletion but opens its DB later.
            def open_after_deletion():
                assert deleted_event.wait(5)
                try:
                    next(get_db(account=stale_principal, settings=get_settings()))
                except HTTPException as error:
                    return error.status_code
                return 200

            deleted_event = Event()
            stale_request = pool.submit(open_after_deletion)
            time.sleep(0.05)
            assert not deleting.done()
            release.set()
            deleted = deleting.result(timeout=5)
            deleted_event.set()
            assert stale_request.result(timeout=5) == 401
        assert deleted.status_code == 200, deleted.text
        assert not (tmp_path / "users" / f"{alice['account_id']}.db").exists()
        assert (tmp_path / "users" / f"{bob['account_id']}.db").exists()
        assert client.get("/auth/me", headers=alice_headers).status_code == 401
        assert client.delete("/admin-api/accounts/admin", headers=ADMIN).status_code == 403


def test_device_audit_and_detectable_lww_conflict(tmp_path: Path, monkeypatch) -> None:
    with _client(tmp_path, monkeypatch) as client:
        base = {
            "id": "part-1", "sku": "C2345", "name": "Cap", "category": "电容",
            "package_name": "0603", "location": "A", "quantity": 1,
            "min_stock": 0, "deleted": False,
        }
        first = client.post(
            "/sync/push", headers=ADMIN,
            json={"device_id": "phone-1", "components": [
                base | {"updated_at": "2026-09-27T00:00:00Z"},
            ]},
        )
        assert first.status_code == 200, first.text
        newer = client.post(
            "/sync/push", headers=ADMIN,
            json={"device_id": "phone-1", "components": [
                base | {"name": "New name", "updated_at": "2026-09-27T01:00:00Z"},
            ]},
        )
        assert newer.status_code == 200
        assert client.get("/admin-api/sync/conflicts", headers=ADMIN).json()["items"] == []
        stale = client.post(
            "/sync/push", headers=ADMIN,
            json={"device_id": "desktop-1", "components": [
                base | {"name": "Stale name", "updated_at": "2026-09-27T00:30:00Z"},
            ]},
        )
        assert stale.status_code == 200
        assert stale.json()["accepted_components"] == 0
        assert client.get("/admin-api/components/part-1", headers=ADMIN).json()["name"] == "New name"
        conflicts = client.get("/admin-api/sync/conflicts", headers=ADMIN).json()["items"]
        assert len(conflicts) == 1
        assert conflicts[0]["kind"] == "stale_lww"
        assert conflicts[0]["device_id"] == "desktop-1"
        assert conflicts[0]["server_device_id"] == "phone-1"
        assert conflicts[0]["server_value"]["name"] == "New name"
        assert conflicts[0]["incoming_value"]["name"] == "Stale name"
        resolved = client.post(
            f"/admin-api/sync/conflicts/{conflicts[0]['id']}/resolve", headers=ADMIN,
            json={"resolution": "server_kept", "note": "Reviewed"},
        )
        assert resolved.status_code == 200
        resolution = client.get("/admin-api/sync/conflicts", headers=ADMIN).json()["items"][0]["resolutions"][0]
        assert resolution["note"] == "Reviewed"
        assert resolution["actor_account_id"] == "admin"
        pull = client.get(
            "/sync/pull",
            params={"cursor": 0},
            headers={**ADMIN, "X-Component-Vault-Device-Id": "phone-1"},
        )
        assert pull.status_code == 200
        assert client.get("/sync/pull", headers=ADMIN).status_code == 200
        devices = client.get("/admin-api/sync/devices", headers=ADMIN).json()["items"]
        assert {item["device_id"] for item in devices} == {"phone-1", "desktop-1"}
        assert next(item for item in devices if item["device_id"] == "phone-1")["pull_count"] == 1
        audit = client.get("/admin-api/sync/audit", headers=ADMIN).json()["items"]
        assert any(item["direction"] == "pull" and item["device_id"] is None for item in audit)
        assert any(item["direction"] == "push" and item["status"] == "success" for item in audit)
        dashboard = client.get("/admin-api/dashboard", headers=ADMIN).json()
        assert any("Device activity" in note for note in dashboard["sync_notes"])
        sync_page = client.get("/admin-api/sync", headers=ADMIN).json()
        assert any(
            item["label"] == "Device registry" and "Available" in item["value"]
            for item in sync_page["sync_assumptions"]
        )
        assert any("Device activity" in note for note in sync_page["attention_items"])


def test_managed_conflict_records_both_snapshots_without_partial_write(
    tmp_path: Path, monkeypatch,
) -> None:
    with _client(tmp_path, monkeypatch) as client:
        base = {
            "id": "managed-1", "sku": "C3456", "name": "Cap", "category": "电容",
            "package_name": "0603", "location": "A", "quantity": 10,
            "min_stock": 0, "deleted": False,
            "allocations": [{"location_id": "A", "quantity": 10}],
        }

        def push(component: dict):
            return client.post(
                "/sync/push", headers=ADMIN,
                json={
                    "device_id": "phone-1", "inventory_protocol": 1,
                    "components": [component],
                    "storage_locations": [{
                        "id": "A", "name": "Shelf", "updated_at": "2026-09-27T00:00:00Z",
                    }],
                },
            )

        assert push(base | {"updated_at": "2026-09-27T00:00:00Z"}).status_code == 200
        assert push(base | {
            "quantity": 9, "allocations": [{"location_id": "A", "quantity": 9}],
            "updated_at": "2026-09-27T01:00:00Z",
            "base_updated_at": "2026-09-27T00:00:00Z",
        }).status_code == 200
        stale = push(base | {
            "quantity": 8, "allocations": [{"location_id": "A", "quantity": 8}],
            "updated_at": "2026-09-27T02:00:00Z",
            "base_updated_at": "2026-09-27T00:00:00Z",
        })
        assert stale.status_code == 409
        assert client.get("/admin-api/components/managed-1", headers=ADMIN).json()["quantity"] == 9
        conflicts = client.get("/admin-api/sync/conflicts", headers=ADMIN).json()["items"]
        assert len(conflicts) == 1
        assert conflicts[0]["kind"] == "managed_version_mismatch"
        assert conflicts[0]["server_value"]["quantity"] == 9
        assert conflicts[0]["server_value"]["allocations"] == [{"location_id": "A", "quantity": 9}]
        assert conflicts[0]["incoming_value"]["quantity"] == 8
        audit = client.get("/admin-api/sync/audit", headers=ADMIN).json()["items"]
        assert any(item["direction"] == "push" and item["status"] == "conflict" for item in audit)
        same_timestamp = push(base | {
            "quantity": 7, "allocations": [{"location_id": "A", "quantity": 7}],
            "updated_at": "2026-09-27T01:00:00Z",
            "base_updated_at": "2026-09-27T01:00:00Z",
        })
        assert same_timestamp.status_code == 409
        conflicts = client.get("/admin-api/sync/conflicts", headers=ADMIN).json()["items"]
        assert conflicts[0]["kind"] == "managed_same_timestamp_change"
        assert client.get("/admin-api/components/managed-1", headers=ADMIN).json()["quantity"] == 9
