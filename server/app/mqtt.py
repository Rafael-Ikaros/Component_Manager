from __future__ import annotations

from dataclasses import asdict, dataclass
from datetime import datetime, timezone
import json
import sqlite3
import threading
import time
from typing import Any, Callable
from urllib.parse import quote

from .config import Settings
from .database import _connect


@dataclass
class MqttStatus:
    enabled: bool
    connected: bool = False
    pending: int = 0
    last_publish_at: str | None = None
    error: str | None = None


def component_state(row: sqlite3.Row) -> dict[str, Any]:
    return {
        "event_id": f"component:{row['id']}:{row['sync_revision']}",
        "id": row["id"],
        "sku": row["sku"],
        "name": row["name"],
        "category": row["category"],
        "package_name": row["package_name"],
        "location": row["location"],
        "quantity": row["quantity"],
        "min_stock": row["min_stock"],
        "inventory_unit_cost": row["inventory_unit_cost"] if "inventory_unit_cost" in row.keys() else None,
        "updated_at": row["updated_at"],
        "deleted": bool(row["deleted"]),
        "sync_revision": row["sync_revision"],
    }


def enqueue_component_state(
    connection: sqlite3.Connection,
    component_id: str,
    topic_prefix: str,
) -> None:
    row = connection.execute(
        "SELECT * FROM components WHERE id = ?", (component_id,)
    ).fetchone()
    if row is None:
        return
    state = component_state(row)
    if "inventory_managed" in row.keys() and row["inventory_managed"]:
        state["allocations"] = [dict(item) for item in connection.execute(
            "SELECT location_id, quantity FROM component_allocations "
            "WHERE component_id = ? ORDER BY location_id", (component_id,),
        )]
    connection.execute(
        """
        INSERT OR IGNORE INTO mqtt_outbox (
            event_id, component_id, sync_revision, topic, payload, created_at
        ) VALUES (?, ?, ?, ?, ?, ?)
        """,
        (
            state["event_id"],
            component_id,
            state["sync_revision"],
            f"{topic_prefix}/components/{quote(component_id, safe='')}/state",
            json.dumps(state, ensure_ascii=False, separators=(",", ":")),
            datetime.now(timezone.utc).isoformat(),
        ),
    )


def mqtt_destination_key(settings: Settings) -> str:
    """Identify the retained-state destination without including credentials."""
    return json.dumps(
        {
            "host": settings.mqtt_host.casefold(),
            "port": settings.mqtt_port,
            "tls": settings.mqtt_tls,
            "prefix": settings.mqtt_topic_prefix,
        },
        sort_keys=True,
        separators=(",", ":"),
    )


def seed_component_snapshot(
    connection: sqlite3.Connection,
    prefix: str,
    destination_key: str | None = None,
) -> None:
    destination_key = destination_key or prefix
    seeded = connection.execute(
        "SELECT snapshot_seeded, snapshot_key FROM mqtt_state WHERE id = 1"
    ).fetchone()
    if seeded and seeded[0] and seeded[1] == destination_key:
        return
    connection.execute("DELETE FROM mqtt_outbox")
    for row in connection.execute("SELECT id FROM components ORDER BY sync_revision"):
        enqueue_component_state(connection, row["id"], prefix)
    connection.execute(
        "UPDATE mqtt_state SET snapshot_seeded = 1, snapshot_key = ? WHERE id = 1",
        (destination_key,),
    )


class MqttPublisher:
    def __init__(
        self,
        settings: Settings,
        client_factory: Callable[..., Any] | None = None,
        retry_seconds: float = 1.0,
    ) -> None:
        self.settings = settings
        self._client_factory = client_factory
        self._retry_seconds = retry_seconds
        self._stop = threading.Event()
        self._wake = threading.Event()
        self._connected = threading.Event()
        self._thread: threading.Thread | None = None
        self._client: Any = None
        self._lock = threading.Lock()
        self._status = MqttStatus(enabled=settings.mqtt_enabled)

    def start(self) -> None:
        if not self.settings.mqtt_enabled or self._thread is not None:
            return
        connection = _connect(self.settings.database_path)
        try:
            with connection:
                seed_component_snapshot(
                    connection,
                    self.settings.mqtt_topic_prefix,
                    mqtt_destination_key(self.settings),
                )
        finally:
            connection.close()
        self._thread = threading.Thread(target=self._run, name="mqtt-outbox", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        self._wake.set()
        thread = self._thread
        if thread is not None:
            thread.join(timeout=6)
            if thread.is_alive():
                self._set_status(error="MQTT worker did not stop within the bounded wait.")
                return
        self._thread = None

    def notify(self) -> None:
        self._wake.set()

    def status(self) -> dict[str, Any]:
        pending = 0
        try:
            connection = _connect(self.settings.database_path)
            try:
                pending = int(connection.execute("SELECT COUNT(*) FROM mqtt_outbox").fetchone()[0])
            finally:
                connection.close()
        except Exception:
            pass
        with self._lock:
            result = asdict(self._status)
        result["pending"] = pending
        return result

    def _make_client(self) -> Any:
        if self._client_factory is not None:
            return self._client_factory()
        import paho.mqtt.client as mqtt

        return mqtt.Client(
            callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
            client_id=self.settings.mqtt_client_id,
        )

    def _run(self) -> None:
        while not self._stop.is_set():
            client = None
            failure: str | None = None
            try:
                client = self._make_client()
                self._client = client
                client.on_connect = self._on_connect
                client.on_disconnect = self._on_disconnect
                if self.settings.mqtt_username:
                    client.username_pw_set(
                        self.settings.mqtt_username,
                        self.settings.mqtt_password or None,
                    )
                if self.settings.mqtt_tls:
                    client.tls_set()
                self._connected.clear()
                client.connect_async(self.settings.mqtt_host, self.settings.mqtt_port, 60)
                client.loop_start()
                if not self._wait_until_connected(5):
                    if self._stop.is_set():
                        continue
                    raise ConnectionError("MQTT connection acknowledgement timed out.")
                while not self._stop.is_set():
                    if not self._publish_one(client):
                        self._wake.wait(self._retry_seconds)
                        self._wake.clear()
            except Exception as error:
                failure = str(error)
            finally:
                if client is not None:
                    try:
                        client.disconnect()
                        client.loop_stop()
                    except Exception:
                        pass
                self._client = None
            if failure is not None:
                self._set_status(connected=False, error=failure)
                self._stop.wait(self._retry_seconds)

    def _wait_until_connected(self, timeout: float) -> bool:
        deadline = time.monotonic() + timeout
        while not self._stop.is_set():
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return False
            if self._connected.wait(min(0.1, remaining)):
                return True
        return False

    def _on_connect(
        self, client: Any, userdata: Any, flags: Any, reason_code: Any, properties: Any
    ) -> None:
        del client, userdata, flags, properties
        if reason_code == 0:
            self._connected.set()
            self._set_status(connected=True, error=None)
        else:
            self._set_status(connected=False, error=f"MQTT connection refused: {reason_code}")

    def _on_disconnect(
        self,
        client: Any,
        userdata: Any,
        disconnect_flags: Any,
        reason_code: Any,
        properties: Any,
    ) -> None:
        del client, userdata, disconnect_flags, properties
        self._connected.clear()
        self._set_status(
            connected=False,
            error=None if reason_code == 0 else f"MQTT disconnected: {reason_code}",
        )

    def _publish_one(self, client: Any) -> bool:
        connection = _connect(self.settings.database_path)
        try:
            row = connection.execute(
                "SELECT event_id, topic, payload FROM mqtt_outbox "
                "ORDER BY sync_revision LIMIT 1"
            ).fetchone()
            if row is None:
                return False
            info = client.publish(row["topic"], row["payload"], qos=1, retain=True)
            info.wait_for_publish(timeout=5)
            if not info.is_published():
                self._set_status(error="MQTT PUBACK was not received.")
                raise ConnectionError("MQTT PUBACK timed out; reconnecting before retry.")
            with connection:
                connection.execute(
                    "DELETE FROM mqtt_outbox WHERE event_id = ?", (row["event_id"],)
                )
            self._set_status(
                last_publish_at=datetime.now(timezone.utc).isoformat(), error=None
            )
            return True
        finally:
            connection.close()

    def _set_status(self, **changes: Any) -> None:
        with self._lock:
            for name, value in changes.items():
                setattr(self._status, name, value)
