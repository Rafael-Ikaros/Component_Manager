"""Per-account device activity and detected synchronization conflicts."""

from __future__ import annotations

from datetime import datetime, timezone
import json
import sqlite3

from .schemas import PushRequest
from .storage import load_allocations


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="microseconds").replace("+00:00", "Z")


def _json(value: object) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, default=str)


def record_push(
    connection: sqlite3.Connection, device_id: str,
    accepted_components: int, accepted_movements: int,
    *, status: str = "success",
) -> None:
    timestamp = _now()
    connection.execute(
        "INSERT INTO sync_devices(device_id,first_seen_at,last_seen_at,last_push_at,push_count) "
        "VALUES(?,?,?,?,1) ON CONFLICT(device_id) DO UPDATE SET "
        "last_seen_at=excluded.last_seen_at,last_push_at=excluded.last_push_at,"
        "push_count=sync_devices.push_count+1",
        (device_id, timestamp, timestamp, timestamp),
    )
    connection.execute(
        "INSERT INTO sync_audit(device_id,direction,status,observed_at,accepted_components,"
        "accepted_stock_movements) VALUES(?,'push',?,?,?,?)",
        (device_id, status, timestamp, accepted_components, accepted_movements),
    )


def record_pull(
    connection: sqlite3.Connection, device_id: str | None, cursor: int,
) -> None:
    timestamp = _now()
    with connection:
        if device_id:
            connection.execute(
                "INSERT INTO sync_devices(device_id,first_seen_at,last_seen_at,last_pull_at,pull_count) "
                "VALUES(?,?,?,?,1) ON CONFLICT(device_id) DO UPDATE SET "
                "last_seen_at=excluded.last_seen_at,last_pull_at=excluded.last_pull_at,"
                "pull_count=sync_devices.pull_count+1",
                (device_id, timestamp, timestamp, timestamp),
            )
        connection.execute(
            "INSERT INTO sync_audit(device_id,direction,observed_at,cursor) "
            "VALUES(?,'pull',?,?)", (device_id, timestamp, cursor),
        )


def record_conflict(
    connection: sqlite3.Connection, *, entity_type: str, entity_id: str,
    device_id: str | None, kind: str, winner: str,
    server_value: object, incoming_value: object,
) -> None:
    source = connection.execute(
        "SELECT device_id FROM sync_entity_sources WHERE entity_type = ? AND entity_id = ?",
        (entity_type, entity_id),
    ).fetchone()
    connection.execute(
        "INSERT INTO sync_conflicts(entity_type,entity_id,device_id,server_device_id,kind,winner,"
        "server_value,incoming_value,detected_at) VALUES(?,?,?,?,?,?,?,?,?)",
        (entity_type, entity_id, device_id, source["device_id"] if source else None, kind, winner,
         _json(server_value), _json(incoming_value), _now()),
    )


def record_entity_source(
    connection: sqlite3.Connection, entity_type: str,
    entity_id: str, device_id: str | None,
) -> None:
    connection.execute(
        "INSERT INTO sync_entity_sources(entity_type,entity_id,device_id,updated_at) "
        "VALUES(?,?,?,?) ON CONFLICT(entity_type,entity_id) DO UPDATE SET "
        "device_id=excluded.device_id,updated_at=excluded.updated_at",
        (entity_type, entity_id, device_id, _now()),
    )


def record_rejected_managed_conflicts(
    connection: sqlite3.Connection, payload: PushRequest,
) -> None:
    """Persist CAS rejections after the failed push transaction has rolled back."""
    with connection:
        for component in payload.components:
            if component.allocations is None or component.base_updated_at is None:
                continue
            row = connection.execute(
                "SELECT * FROM components WHERE id = ?", (component.id,),
            ).fetchone()
            if row is None:
                continue
            server_time = datetime.fromisoformat(str(row["updated_at"]).replace("Z", "+00:00"))
            base_time = component.base_updated_at
            if base_time.tzinfo is None:
                base_time = base_time.replace(tzinfo=timezone.utc)
            base_matches = base_time.astimezone(timezone.utc) == server_time.astimezone(timezone.utc)
            if base_matches:
                incoming_time = component.updated_at
                if incoming_time.tzinfo is None:
                    incoming_time = incoming_time.replace(tzinfo=timezone.utc)
                if incoming_time.astimezone(timezone.utc) != server_time.astimezone(timezone.utc):
                    continue
                fields = ("sku", "name", "category", "package_name", "location",
                          "description", "quantity", "min_stock", "inventory_unit_cost")
                changed = any(row[key] != getattr(component, key) for key in fields)
                changed = changed or (
                    {item.location_id: item.quantity for item in load_allocations(connection, component.id)}
                    != {item.location_id: item.quantity for item in component.allocations}
                )
                if not changed:
                    continue
            record_conflict(
                connection, entity_type="component", entity_id=component.id,
                device_id=payload.device_id,
                kind="managed_same_timestamp_change" if base_matches else "managed_version_mismatch",
                winner="server", server_value=dict(row) | {
                    "allocations": [
                        item.model_dump(mode="json")
                        for item in load_allocations(connection, component.id)
                    ],
                },
                incoming_value=component.model_dump(mode="json"),
            )


def list_devices(connection: sqlite3.Connection) -> list[dict[str, object]]:
    return [dict(row) for row in connection.execute(
        "SELECT * FROM sync_devices ORDER BY last_seen_at DESC LIMIT 500"
    )]


def list_audit(connection: sqlite3.Connection, limit: int) -> list[dict[str, object]]:
    return [dict(row) for row in connection.execute(
        "SELECT * FROM sync_audit ORDER BY id DESC LIMIT ?", (limit,),
    )]


def list_conflicts(connection: sqlite3.Connection, limit: int) -> list[dict[str, object]]:
    rows = connection.execute(
        "SELECT * FROM sync_conflicts ORDER BY id DESC LIMIT ?", (limit,),
    ).fetchall()
    result = []
    for row in rows:
        item = dict(row)
        item["server_value"] = json.loads(item["server_value"])
        item["incoming_value"] = json.loads(item["incoming_value"])
        item["resolutions"] = [dict(entry) for entry in connection.execute(
            "SELECT id,resolution,actor_account_id,note,created_at FROM sync_conflict_resolutions "
            "WHERE conflict_id = ? ORDER BY id", (item["id"],),
        )]
        result.append(item)
    return result


def append_resolution(
    connection: sqlite3.Connection, conflict_id: int,
    resolution: str, note: str | None, actor_account_id: str,
) -> bool:
    with connection:
        exists = connection.execute(
            "SELECT 1 FROM sync_conflicts WHERE id = ?", (conflict_id,),
        ).fetchone()
        if exists is None:
            return False
        connection.execute(
            "INSERT INTO sync_conflict_resolutions(conflict_id,resolution,actor_account_id,note,created_at) "
            "VALUES(?,?,?,?,?)", (conflict_id, resolution, actor_account_id, note, _now()),
        )
    return True
