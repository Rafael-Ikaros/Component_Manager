from __future__ import annotations

from datetime import datetime, timezone
import sqlite3

from .schemas import (
    AllocationPayload, ComponentPayload, PushRequest,
    StockMovementPayload, StorageLocationPayload,
)
from .storage import (
    check_component_version, load_allocations, save_allocations,
    save_locations, validate_inventory_push,
)
from .sync_observability import record_conflict, record_entity_source, record_push


def save_components(
    connection: sqlite3.Connection,
    components: list[ComponentPayload],
    *,
    mqtt_topic_prefix: str | None = None,
    device_id: str | None = None,
) -> int:
    accepted = 0
    for component in components:
        try:
            was_accepted = _upsert_component(connection, component, device_id=device_id)
            accepted += int(was_accepted)
            if was_accepted:
                record_entity_source(connection, "component", component.id, device_id)
            if was_accepted and mqtt_topic_prefix is not None:
                from .mqtt import enqueue_component_state

                enqueue_component_state(connection, component.id, mqtt_topic_prefix)
        except sqlite3.IntegrityError as error:
            raise ValueError(
                'An active component with the same SKU already exists.',
            ) from error
    return accepted


def save_stock_movements(
    connection: sqlite3.Connection,
    stock_movements: list[StockMovementPayload],
    *, device_id: str | None = None,
) -> int:
    accepted = 0
    for stock_movement in stock_movements:
        was_accepted = _upsert_stock_movement(connection, stock_movement, device_id=device_id)
        accepted += int(was_accepted)
        if was_accepted:
            record_entity_source(connection, "stock_movement", stock_movement.id, device_id)
    return accepted


def save_sync_payload(
    connection: sqlite3.Connection,
    payload: PushRequest,
    *,
    mqtt_topic_prefix: str | None = None,
) -> tuple[int, int]:
    """Save an entire push in one transaction so retries see no partial state."""
    try:
        with connection:
            # Serialize the LWW read/check/write as well as revision allocation.
            connection.execute("BEGIN IMMEDIATE")
            result = save_sync_payload_in_transaction(
                connection,
                payload,
                mqtt_topic_prefix=mqtt_topic_prefix,
            )
    except sqlite3.IntegrityError as error:
        raise ValueError(_integrity_error_message(error)) from error
    return result


def save_sync_payload_in_transaction(
    connection: sqlite3.Connection,
    payload: PushRequest,
    *,
    mqtt_topic_prefix: str | None = None,
) -> tuple[int, int]:
    """Save a push inside a transaction already owned by the caller."""
    if not connection.in_transaction:
        raise RuntimeError("An active caller-owned transaction is required.")
    validate_inventory_push(connection, payload)
    save_locations(connection, payload.storage_locations)
    accepted_components = save_components(
        connection,
        payload.components,
        mqtt_topic_prefix=mqtt_topic_prefix,
        device_id=payload.device_id,
    )
    accepted_stock_movements = save_stock_movements(
        connection,
        payload.stock_movements,
        device_id=payload.device_id,
    )
    record_push(connection, payload.device_id, accepted_components, accepted_stock_movements)
    invalid_location = connection.execute("""
        SELECT a.location_id FROM component_allocations a
        JOIN storage_locations l ON l.id = a.location_id
        JOIN components c ON c.id = a.component_id
        WHERE c.deleted = 0 AND l.deleted = 1 AND a.quantity > 0 LIMIT 1
    """).fetchone()
    if invalid_location:
        raise ValueError("A location with positive inventory cannot be deleted.")
    return accepted_components, accepted_stock_movements


def pull_sync_snapshot(
    connection: sqlite3.Connection,
    *,
    cursor: int | None,
    since: datetime | None,
    locations_out: list[StorageLocationPayload] | None = None,
) -> tuple[int, list[ComponentPayload], list[StockMovementPayload]]:
    """Read both entity types and the returned cursor from one DB snapshot."""
    connection.execute("BEGIN")
    try:
        sync_cursor = int(
            connection.execute(
                "SELECT current_revision FROM sync_state WHERE id = 1"
            ).fetchone()[0]
        )
        if cursor is not None:
            component_rows = connection.execute(
                "SELECT * FROM components "
                "WHERE sync_revision > ? AND sync_revision <= ? "
                "ORDER BY sync_revision ASC",
                (cursor, sync_cursor),
            ).fetchall()
            movement_rows = connection.execute(
                "SELECT * FROM stock_movements "
                "WHERE sync_revision > ? AND sync_revision <= ? "
                "ORDER BY sync_revision ASC",
                (cursor, sync_cursor),
            ).fetchall()
        else:
            component_rows = connection.execute("SELECT * FROM components").fetchall()
            movement_rows = connection.execute(
                "SELECT * FROM stock_movements"
            ).fetchall()
        components = [
            _row_to_component(
                row, load_allocations(connection, row["id"])
                if row["inventory_managed"] else None,
            )
            for row in component_rows
        ]
        if locations_out is not None:
            locations_out.extend(
                StorageLocationPayload.model_validate(dict(row))
                for row in connection.execute("SELECT * FROM storage_locations ORDER BY id")
            )
    finally:
        connection.rollback()

    movements = [_row_to_stock_movement(row) for row in movement_rows]
    if cursor is None and since is not None:
        since_utc = _as_utc(since)
        components = [
            item for item in components if _as_utc(item.updated_at) > since_utc
        ]
        movements = [
            item for item in movements if _as_utc(item.updated_at) > since_utc
        ]
    return sync_cursor, components, movements


def _upsert_component(
    connection: sqlite3.Connection,
    component: ComponentPayload,
    *, device_id: str | None = None,
) -> bool:
    new_updated_at = _to_storage_time(component.updated_at)
    existing = connection.execute(
        "SELECT * FROM components WHERE id = ?",
        (component.id,),
    ).fetchone()
    if check_component_version(connection, existing, component):
        return False
    if component.allocations is None and existing:
        old_time = _parse_storage_time(existing["updated_at"])
        new_time = _as_utc(component.updated_at)
        fields = ("sku", "name", "category", "package_name", "location", "description",
                  "quantity", "min_stock", "deleted", "inventory_unit_cost")
        incoming = component.model_dump()
        different = any(
            (bool(existing[key]) != bool(incoming[key]) if key == "deleted"
             else existing[key] != incoming[key]) for key in fields
        )
        if different and old_time >= new_time:
            record_conflict(
                connection, entity_type="component", entity_id=component.id,
                device_id=device_id,
                kind="stale_lww" if old_time > new_time else "equal_timestamp_lww",
                winner="server" if old_time > new_time else "incoming",
                server_value=dict(existing), incoming_value=component.model_dump(mode="json"),
            )
        if old_time > new_time:
            return False

    sync_revision = _next_sync_revision(connection)

    connection.execute(
        """
        INSERT INTO components (
            id,
            sku,
            name,
            category,
            package_name,
            location,
            description,
            quantity,
            min_stock,
            updated_at,
            deleted,
            sync_revision
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(id) DO UPDATE SET
            sku = excluded.sku,
            name = excluded.name,
            category = excluded.category,
            package_name = excluded.package_name,
            location = excluded.location,
            description = excluded.description,
            quantity = excluded.quantity,
            min_stock = excluded.min_stock,
            updated_at = excluded.updated_at,
            deleted = excluded.deleted,
            sync_revision = excluded.sync_revision
        """,
        (
            component.id,
            component.sku,
            component.name,
            component.category,
            component.package_name,
            component.location,
            component.description,
            component.quantity,
            component.min_stock,
            new_updated_at,
            int(component.deleted),
            sync_revision,
        ),
    )
    connection.execute("UPDATE components SET inventory_unit_cost = ? WHERE id = ?",
                       (component.inventory_unit_cost, component.id))
    if component.allocations is not None:
        save_allocations(connection, component)
    return True


def _upsert_stock_movement(
    connection: sqlite3.Connection,
    stock_movement: StockMovementPayload,
    *, device_id: str | None = None,
) -> bool:
    new_updated_at = _to_storage_time(stock_movement.updated_at)
    existing = connection.execute(
        "SELECT * FROM stock_movements WHERE id = ?",
        (stock_movement.id,),
    ).fetchone()
    if existing:
        old_time = _parse_storage_time(existing["updated_at"])
        new_time = _as_utc(stock_movement.updated_at)
        fields = ("component_id", "movement_type", "quantity", "reason", "note",
                  "location_id", "destination_location_id", "deleted", "purchase_amount", "cost_amount")
        incoming = stock_movement.model_dump()
        different = any(
            (bool(existing[key]) != bool(incoming[key]) if key == "deleted"
             else existing[key] != incoming[key]) for key in fields
        )
        if different and old_time >= new_time:
            record_conflict(
                connection, entity_type="stock_movement", entity_id=stock_movement.id,
                device_id=device_id,
                kind="stale_lww" if old_time > new_time else "equal_timestamp_lww",
                winner="server" if old_time > new_time else "incoming",
                server_value=dict(existing), incoming_value=stock_movement.model_dump(mode="json"),
            )
        if old_time > new_time:
            return False

    sync_revision = _next_sync_revision(connection)

    connection.execute(
        """
        INSERT INTO stock_movements (
            id,
            component_id,
            movement_type,
            quantity,
            reason,
            note,
            happened_at,
            updated_at,
            deleted,
            sync_revision
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(id) DO UPDATE SET
            component_id = excluded.component_id,
            movement_type = excluded.movement_type,
            quantity = excluded.quantity,
            reason = excluded.reason,
            note = excluded.note,
            happened_at = excluded.happened_at,
            updated_at = excluded.updated_at,
            deleted = excluded.deleted,
            sync_revision = excluded.sync_revision
        """,
        (
            stock_movement.id,
            stock_movement.component_id,
            stock_movement.movement_type,
            stock_movement.quantity,
            stock_movement.reason,
            stock_movement.note,
            _to_storage_time(stock_movement.happened_at),
            new_updated_at,
            int(stock_movement.deleted),
            sync_revision,
        ),
    )
    connection.execute("UPDATE stock_movements SET purchase_amount = ?, cost_amount = ? WHERE id = ?",
                       (stock_movement.purchase_amount, stock_movement.cost_amount, stock_movement.id))
    connection.execute(
        "UPDATE stock_movements SET location_id = ?, destination_location_id = ? "
        "WHERE id = ?",
        (stock_movement.location_id, stock_movement.destination_location_id,
         stock_movement.id),
    )
    return True


def _row_to_component(
    row: sqlite3.Row, allocations: list[AllocationPayload] | None = None,
) -> ComponentPayload:
    return ComponentPayload.model_validate(
        {
            "id": row["id"],
            "sku": row["sku"],
            "name": row["name"],
            "category": row["category"],
            "package_name": row["package_name"],
            "location": row["location"],
            "description": row["description"],
            "quantity": row["quantity"],
            "min_stock": row["min_stock"],
            "updated_at": row["updated_at"],
            "deleted": bool(row["deleted"]),
            "allocations": allocations,
            "inventory_unit_cost": row["inventory_unit_cost"],
        }
    )


def _row_to_stock_movement(row: sqlite3.Row) -> StockMovementPayload:
    return StockMovementPayload.model_validate(
        {
            "id": row["id"],
            "component_id": row["component_id"],
            "movement_type": row["movement_type"],
            "quantity": row["quantity"],
            "reason": row["reason"],
            "note": row["note"],
            "happened_at": row["happened_at"],
            "updated_at": row["updated_at"],
            "deleted": bool(row["deleted"]),
            "location_id": row["location_id"],
            "destination_location_id": row["destination_location_id"],
            "purchase_amount": row["purchase_amount"],
            "cost_amount": row["cost_amount"],
        }
    )


def _to_storage_time(value: datetime) -> str:
    return _as_utc(value).isoformat(timespec="microseconds").replace("+00:00", "Z")


def _as_utc(value: datetime) -> datetime:
    if value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc)


def _parse_storage_time(value: str) -> datetime:
    return _as_utc(datetime.fromisoformat(value.replace("Z", "+00:00")))


def _next_sync_revision(connection: sqlite3.Connection) -> int:
    connection.execute(
        "UPDATE sync_state SET current_revision = current_revision + 1 WHERE id = 1"
    )
    return int(
        connection.execute(
            "SELECT current_revision FROM sync_state WHERE id = 1"
        ).fetchone()[0]
    )


def _integrity_error_message(error: sqlite3.IntegrityError) -> str:
    message = str(error).lower()
    if "foreign key" in message:
        return "A stock movement references a component that does not exist."
    if "sku" in message or "unique" in message:
        return "An active component with the same SKU already exists."
    return "The sync payload violates a database constraint."
