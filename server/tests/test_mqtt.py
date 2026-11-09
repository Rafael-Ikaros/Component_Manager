from __future__ import annotations

from dataclasses import replace
import json
from pathlib import Path
import sqlite3
import threading

import pytest
from fastapi.testclient import TestClient

from app.config import Settings, get_settings
from app.database import _connect, init_db
from app.mqtt import (
    MqttPublisher,
    mqtt_destination_key,
    seed_component_snapshot,
)
from app.repositories import save_sync_payload
from app.schemas import PushRequest
from app.main import create_app


def _settings(path: Path, **changes) -> Settings:
    base = Settings(
        app_name="test",
        api_token="token",
        database_path=str(path),
        app_host="127.0.0.1",
        app_port=8787,
        admin_web_origins=(),
        lcsc_lookup_cache_ttl_seconds=1,
        import_rules_remote_url="",
        import_rules_refresh_hours=1,
        enable_web_fallback_resolvers=False,
    )
    return replace(base, **changes)


def _component(updated_at: str = "2026-01-01T00:00:00Z", **changes) -> dict:
    value = {
        "id": "part/one",
        "sku": "C1",
        "name": "Part",
        "category": "IC",
        "package_name": "QFN",
        "location": "A1",
        "description": "private QR content",
        "quantity": 3,
        "min_stock": 1,
        "updated_at": updated_at,
        "deleted": False,
    }
    value.update(changes)
    return value


def _payload(component: dict, movements: list[dict] | None = None) -> PushRequest:
    return PushRequest.model_validate(
        {"device_id": "test", "components": [component], "stock_movements": movements or []}
    )


def test_disabled_sync_does_not_create_outbox(tmp_path: Path) -> None:
    settings = _settings(tmp_path / "db.sqlite")
    init_db(settings)
    connection = _connect(settings.database_path)
    save_sync_payload(connection, _payload(_component()))
    assert connection.execute("SELECT COUNT(*) FROM mqtt_outbox").fetchone()[0] == 0
    connection.close()


def test_accepted_component_and_outbox_are_atomic_and_private_fields_are_omitted(tmp_path: Path) -> None:
    settings = _settings(tmp_path / "db.sqlite")
    init_db(settings)
    connection = _connect(settings.database_path)
    save_sync_payload(
        connection,
        _payload(_component()),
        mqtt_topic_prefix="vault",
    )
    row = connection.execute("SELECT * FROM mqtt_outbox").fetchone()
    body = json.loads(row["payload"])
    assert row["event_id"] == "component:part/one:1"
    assert row["topic"] == "vault/components/part%2Fone/state"
    assert body["quantity"] == 3
    assert body["sync_revision"] == 1
    assert "description" not in body

    invalid_movement = {
        "id": "m1", "component_id": "missing", "movement_type": "outbound",
        "quantity": 1, "reason": "test", "happened_at": "2026-01-01T00:00:00Z",
        "updated_at": "2026-01-01T00:00:00Z", "deleted": False,
    }
    with pytest.raises(ValueError):
        save_sync_payload(
            connection,
            _payload(_component(id="rolled-back", sku="C2"), [invalid_movement]),
            mqtt_topic_prefix="vault",
        )
    assert connection.execute("SELECT COUNT(*) FROM components WHERE id='rolled-back'").fetchone()[0] == 0
    assert connection.execute("SELECT COUNT(*) FROM mqtt_outbox").fetchone()[0] == 1
    connection.close()


def test_stale_lww_does_not_enqueue_and_snapshot_seeds_deleted_rows_once(tmp_path: Path) -> None:
    settings = _settings(tmp_path / "db.sqlite")
    init_db(settings)
    connection = _connect(settings.database_path)
    save_sync_payload(connection, _payload(_component("2026-01-02T00:00:00Z")))
    save_sync_payload(
        connection,
        _payload(_component("2026-01-01T00:00:00Z", quantity=99)),
        mqtt_topic_prefix="vault",
    )
    assert connection.execute("SELECT COUNT(*) FROM mqtt_outbox").fetchone()[0] == 0
    connection.execute("UPDATE components SET deleted=1")
    with connection:
        seed_component_snapshot(connection, "vault")
        seed_component_snapshot(connection, "vault")
    rows = connection.execute("SELECT payload FROM mqtt_outbox").fetchall()
    assert len(rows) == 1
    assert json.loads(rows[0][0])["deleted"] is True
    connection.close()


class _PublishInfo:
    def __init__(self, published: bool) -> None:
        self.published = published

    def wait_for_publish(self, timeout: float) -> None:
        del timeout

    def is_published(self) -> bool:
        return self.published


class _Client:
    def __init__(self, published: bool) -> None:
        self.published = published
        self.calls = []
        self.loop_stopped = False
        self.disconnected = False

    def publish(self, topic, payload, qos, retain):
        self.calls.append((topic, payload, qos, retain))
        return _PublishInfo(self.published)

    def loop_stop(self):
        self.loop_stopped = True

    def disconnect(self):
        self.disconnected = True


def test_puback_controls_deletion_and_publish_is_qos1_retained(tmp_path: Path) -> None:
    settings = _settings(tmp_path / "db.sqlite", mqtt_enabled=True, mqtt_host="broker")
    init_db(settings)
    connection = _connect(settings.database_path)
    save_sync_payload(connection, _payload(_component()), mqtt_topic_prefix="vault")
    connection.close()
    publisher = MqttPublisher(settings)

    failed = _Client(False)
    with pytest.raises(ConnectionError, match="PUBACK"):
        publisher._publish_one(failed)
    assert publisher.status()["pending"] == 1
    succeeded = _Client(True)
    assert publisher._publish_one(succeeded) is True
    assert publisher.status()["pending"] == 0
    assert succeeded.calls[0][2:] == (1, True)


class _LifecycleClient(_Client):
    def __init__(self, published: bool) -> None:
        super().__init__(published)
        self.started = threading.Event()

    def connect_async(self, host, port, keepalive):
        del host, port, keepalive

    def loop_start(self):
        self.on_connect(self, None, None, 0, None)
        self.started.set()


def test_stop_lets_worker_release_client_resources(tmp_path: Path) -> None:
    settings = _settings(
        tmp_path / "db.sqlite", mqtt_enabled=True, mqtt_host="broker"
    )
    init_db(settings)
    client = _LifecycleClient(True)
    publisher = MqttPublisher(settings, client_factory=lambda: client, retry_seconds=0.01)
    publisher.start()
    assert client.started.wait(1)

    publisher.stop()

    assert publisher._thread is None
    assert client.loop_stopped
    assert client.disconnected


def test_same_prefix_on_different_broker_reseeds_snapshot(tmp_path: Path) -> None:
    path = tmp_path / "db.sqlite"
    first = _settings(
        path,
        mqtt_enabled=True,
        mqtt_host="broker-a",
        mqtt_topic_prefix="vault",
        mqtt_password="do-not-store",
    )
    second = replace(first, mqtt_host="broker-b")
    init_db(first)
    connection = _connect(str(path))
    save_sync_payload(connection, _payload(_component()))
    with connection:
        seed_component_snapshot(
            connection, first.mqtt_topic_prefix, mqtt_destination_key(first)
        )
        connection.execute("DELETE FROM mqtt_outbox")
        seed_component_snapshot(
            connection, first.mqtt_topic_prefix, mqtt_destination_key(first)
        )
    assert connection.execute("SELECT COUNT(*) FROM mqtt_outbox").fetchone()[0] == 0

    with connection:
        seed_component_snapshot(
            connection, second.mqtt_topic_prefix, mqtt_destination_key(second)
        )
    assert connection.execute("SELECT COUNT(*) FROM mqtt_outbox").fetchone()[0] == 1
    stored_key = connection.execute(
        "SELECT snapshot_key FROM mqtt_state WHERE id=1"
    ).fetchone()[0]
    assert "broker-b" in stored_key
    assert first.mqtt_password not in stored_key
    connection.close()


def test_invalid_enabled_configuration_is_rejected(monkeypatch) -> None:
    get_settings.cache_clear()
    monkeypatch.setenv("MQTT_ENABLED", "true")
    monkeypatch.delenv("MQTT_HOST", raising=False)
    with pytest.raises(ValueError, match="MQTT_HOST"):
        get_settings()
    get_settings.cache_clear()


def test_legacy_database_migrates_and_authenticated_status_hides_secrets(
    tmp_path: Path, monkeypatch
) -> None:
    path = tmp_path / "legacy.sqlite"
    connection = sqlite3.connect(path)
    connection.execute(
        """CREATE TABLE components (
        id TEXT PRIMARY KEY, sku TEXT NOT NULL, name TEXT NOT NULL,
        category TEXT NOT NULL, package_name TEXT NOT NULL, location TEXT NOT NULL,
        description TEXT, quantity INTEGER NOT NULL, min_stock INTEGER NOT NULL,
        updated_at TEXT NOT NULL, deleted INTEGER NOT NULL DEFAULT 0)"""
    )
    connection.execute(
        """CREATE TABLE stock_movements (
        id TEXT PRIMARY KEY, component_id TEXT NOT NULL, movement_type TEXT NOT NULL,
        quantity INTEGER NOT NULL, reason TEXT NOT NULL, note TEXT,
        happened_at TEXT NOT NULL, updated_at TEXT NOT NULL,
        deleted INTEGER NOT NULL DEFAULT 0)"""
    )
    connection.commit()
    connection.close()
    monkeypatch.setenv("DATABASE_PATH", str(path))
    monkeypatch.setenv("API_TOKEN", "mqtt-test")
    monkeypatch.setenv("MQTT_PASSWORD", "never-return-this")
    monkeypatch.setenv("MQTT_ENABLED", "false")
    get_settings.cache_clear()
    with TestClient(create_app()) as client:
        denied = client.get("/admin-api/mqtt/status")
        response = client.get(
            "/admin-api/mqtt/status",
            headers={"Authorization": "Bearer mqtt-test"},
        )
    assert denied.status_code == 401
    assert response.status_code == 200
    assert response.json()["enabled"] is False
    assert "never-return-this" not in response.text
    migrated = sqlite3.connect(path)
    assert migrated.execute(
        "SELECT name FROM sqlite_master WHERE name='mqtt_outbox'"
    ).fetchone()
    migrated.close()
    get_settings.cache_clear()
