from __future__ import annotations

from datetime import datetime, timezone
import json
import sqlite3
from uuid import uuid4

from pydantic import BaseModel

from ..costs import backfill_cost, movement_cost
from ..config import Settings
from ..repositories import save_sync_payload_in_transaction
from ..schemas import (
    AdminInventoryCostUpdate,
    AdminComponentBulkDelete,
    AdminComponentCreate,
    AdminComponentDelete,
    AdminComponentUpdate,
    AdminStockMovementCreate,
    AdminStorageLocationCreate,
    AllocationPayload,
    ComponentPayload,
    PushRequest,
    StockMovementPayload,
    StorageLocationPayload,
)
from ..storage import load_allocations


class AdminOperationConflict(ValueError):
    pass


class AdminOperationNotFound(LookupError):
    pass


def list_storage_locations(
    connection: sqlite3.Connection,
) -> list[dict[str, object]]:
    return [
        dict(row)
        for row in connection.execute(
            "SELECT id, name, updated_at FROM storage_locations "
            "WHERE deleted = 0 ORDER BY name COLLATE NOCASE, id"
        )
    ]


def create_storage_location(
    connection: sqlite3.Connection,
    draft: AdminStorageLocationCreate,
) -> dict[str, object]:
    payload_json = _canonical_payload(draft)
    try:
        with connection:
            connection.execute("BEGIN IMMEDIATE")
            retry_target = _retry_target(
                connection, draft.request_id, "create_location", "", payload_json
            )
            if retry_target is not None:
                return _location(connection, retry_target)
            existing = connection.execute(
                "SELECT id FROM storage_locations WHERE id = ?", (draft.id,)
            ).fetchone()
            if existing is not None:
                raise AdminOperationConflict("Storage location ID already exists.")
            now = _now()
            connection.execute(
                "INSERT INTO storage_locations(id, name, updated_at, deleted) "
                "VALUES (?, ?, ?, 0)",
                (draft.id, draft.name.strip(), now),
            )
            _save_receipt(
                connection,
                draft.request_id,
                "create_location",
                draft.id,
                payload_json,
                now,
            )
            return _location(connection, draft.id)
    except sqlite3.IntegrityError as error:
        raise AdminOperationConflict("Storage location could not be created.") from error


def create_component(
    connection: sqlite3.Connection,
    settings: Settings,
    draft: AdminComponentCreate,
) -> dict[str, object]:
    payload_json = _canonical_payload(draft)
    try:
        with connection:
            connection.execute("BEGIN IMMEDIATE")
            retry_target = _retry_target(
                connection, draft.request_id, "create_component", "", payload_json
            )
            if retry_target is not None:
                return _component(connection, retry_target)
            _active_location(connection, draft.location_id)
            component_id = f"web-{uuid4()}"
            now = _now_datetime()
            component = ComponentPayload(
                inventory_unit_cost=None,
                id=component_id,
                sku=draft.sku.strip(),
                name=draft.name.strip(),
                category=draft.category.strip(),
                package_name=draft.package_name.strip(),
                location=draft.location_id,
                description=draft.description,
                quantity=0,
                min_stock=draft.min_stock,
                updated_at=now,
                allocations=[
                    AllocationPayload(location_id=draft.location_id, quantity=0)
                ],
            )
            _save_payload(
                connection,
                settings,
                PushRequest(
                    device_id="admin-web", cost_protocol=1,
                    inventory_protocol=1,
                    components=[component],
                ),
            )
            _save_receipt(
                connection,
                draft.request_id,
                "create_component",
                component_id,
                payload_json,
                _storage_time(now),
            )
            return _component(connection, component_id)
    except sqlite3.IntegrityError as error:
        raise AdminOperationConflict("Component could not be created.") from error
    except ValueError as error:
        if isinstance(error, AdminOperationConflict):
            raise
        raise AdminOperationConflict(str(error)) from error


def update_component(
    connection: sqlite3.Connection,
    settings: Settings,
    component_id: str,
    draft: AdminComponentUpdate,
) -> dict[str, object]:
    payload_json = _canonical_payload(draft)
    try:
        with connection:
            connection.execute("BEGIN IMMEDIATE")
            retry_target = _retry_target(
                connection,
                draft.request_id,
                "update_component",
                component_id,
                payload_json,
            )
            if retry_target is not None:
                return _component(connection, retry_target)
            row = _component_row(connection, component_id)
            _require_version(row, draft.expected_updated_at)
            allocations = (
                load_allocations(connection, component_id)
                if row["inventory_managed"]
                else None
            )
            now = _now_datetime_after(str(row["updated_at"]))
            component = ComponentPayload(
                id=component_id,
                sku=draft.sku.strip(),
                name=draft.name.strip(),
                category=draft.category.strip(),
                package_name=draft.package_name.strip(),
                location=str(row["location"]),
                description=draft.description,
                inventory_unit_cost=row["inventory_unit_cost"],
                quantity=int(row["quantity"]),
                min_stock=draft.min_stock,
                updated_at=now,
                allocations=allocations,
                base_updated_at=(draft.expected_updated_at if allocations is not None else None),
            )
            _save_payload(
                connection,
                settings,
                PushRequest(
                    device_id="admin-web", cost_protocol=1,
                    inventory_protocol=1 if allocations is not None else 0,
                    components=[component],
                ),
            )
            _save_receipt(
                connection,
                draft.request_id,
                "update_component",
                component_id,
                payload_json,
                _storage_time(now),
            )
            return _component(connection, component_id)
    except sqlite3.IntegrityError as error:
        raise AdminOperationConflict("Component could not be updated.") from error
    except ValueError as error:
        if isinstance(error, AdminOperationConflict):
            raise
        raise AdminOperationConflict(str(error)) from error


def delete_component(
    connection: sqlite3.Connection,
    settings: Settings,
    component_id: str,
    draft: AdminComponentDelete,
) -> dict[str, object]:
    payload_json = _canonical_payload(draft)
    try:
        with connection:
            connection.execute("BEGIN IMMEDIATE")
            retry_target = _retry_target(
                connection, draft.request_id, "delete_component", component_id, payload_json
            )
            if retry_target is not None:
                return _deleted_component(connection, retry_target)
            now = _delete_component_in_transaction(
                connection, settings, component_id, draft.expected_updated_at
            )
            _save_receipt(
                connection, draft.request_id, "delete_component", component_id,
                payload_json, _storage_time(now),
            )
            return _deleted_component(connection, component_id)
    except sqlite3.IntegrityError as error:
        raise AdminOperationConflict("Component could not be deleted.") from error
    except ValueError as error:
        if isinstance(error, AdminOperationConflict):
            raise
        raise AdminOperationConflict(str(error)) from error


def bulk_delete_components(
    connection: sqlite3.Connection,
    settings: Settings,
    draft: AdminComponentBulkDelete,
) -> dict[str, object]:
    payload_json = _canonical_payload(draft)
    try:
        with connection:
            connection.execute("BEGIN IMMEDIATE")
            replay = _retry_target(
                connection, draft.request_id, "bulk_delete_components", "", payload_json
            )
            if replay is None:
                for item in draft.items:
                    _delete_component_in_transaction(
                        connection, settings, item.id, item.expected_updated_at
                    )
                _save_receipt(
                    connection, draft.request_id, "bulk_delete_components", "",
                    payload_json, _now(),
                )
            return {"items": [_deleted_component(connection, item.id) for item in draft.items]}
    except sqlite3.IntegrityError as error:
        raise AdminOperationConflict("Components could not be deleted.") from error
    except ValueError as error:
        if isinstance(error, AdminOperationConflict):
            raise
        raise AdminOperationConflict(str(error)) from error


def _delete_component_in_transaction(
    connection: sqlite3.Connection,
    settings: Settings,
    component_id: str,
    expected_updated_at: datetime,
) -> datetime:
    row = _component_row(connection, component_id)
    _require_version(row, expected_updated_at)
    managed = bool(row["inventory_managed"])
    now = _now_datetime_after(str(row["updated_at"]))
    component = ComponentPayload(
        id=component_id,
        sku=str(row["sku"]),
        name=str(row["name"]),
        category=str(row["category"]),
        package_name=str(row["package_name"]),
        location=str(row["location"]),
        description=row["description"],
        inventory_unit_cost=row["inventory_unit_cost"],
        quantity=int(row["quantity"]),
        min_stock=int(row["min_stock"]),
        updated_at=now,
        deleted=True,
        allocations=load_allocations(connection, component_id) if managed else None,
        base_updated_at=expected_updated_at if managed else None,
    )
    _save_payload(
        connection,
        settings,
        PushRequest(
            device_id="admin-web", cost_protocol=1,
            inventory_protocol=1 if managed else 0,
            components=[component],
        ),
    )
    return now


def record_stock_movement(
    connection: sqlite3.Connection,
    settings: Settings,
    component_id: str,
    draft: AdminStockMovementCreate,
) -> dict[str, object]:
    payload_json = _canonical_payload(draft)
    try:
        with connection:
            connection.execute("BEGIN IMMEDIATE")
            retry_target = _retry_target(
                connection,
                draft.request_id,
                "stock_movement",
                component_id,
                payload_json,
            )
            if retry_target is not None:
                return _component(connection, retry_target)
            row = _component_row(connection, component_id)
            _require_version(row, draft.expected_updated_at)
            delta = draft.quantity if draft.movement_type == "inbound" else -draft.quantity
            next_quantity = int(row["quantity"]) + delta
            if next_quantity < 0:
                raise AdminOperationConflict(
                    "Outbound quantity exceeds available inventory."
                )
            if next_quantity > 2147483647:
                raise AdminOperationConflict("Inventory exceeds the supported quantity range.")
            managed = bool(row["inventory_managed"])
            allocations = load_allocations(connection, component_id) if managed else None
            if managed:
                if not draft.location_id:
                    raise AdminOperationConflict(
                        "location_id is required for managed inventory."
                    )
                _active_location(connection, draft.location_id)
                allocations = _apply_delta(allocations or [], draft.location_id, delta)
            elif draft.location_id is not None:
                raise AdminOperationConflict(
                    "Legacy inventory uses its existing location; omit location_id."
                )
            now = _now_datetime_after(str(row["updated_at"]))
            unit_cost, cost_amount = movement_cost(int(row["quantity"]), row["inventory_unit_cost"],
                                                   draft.quantity, draft.purchase_amount, draft.movement_type == "inbound")
            component = ComponentPayload(
                inventory_unit_cost=unit_cost,
                id=component_id,
                sku=str(row["sku"]),
                name=str(row["name"]),
                category=str(row["category"]),
                package_name=str(row["package_name"]),
                location=str(row["location"]),
                description=row["description"],
                quantity=next_quantity,
                min_stock=int(row["min_stock"]),
                updated_at=now,
                allocations=allocations,
                base_updated_at=(draft.expected_updated_at if managed else None),
            )
            movement = StockMovementPayload(
                id=f"web-movement-{uuid4()}",
                component_id=component_id,
                movement_type=draft.movement_type,
                quantity=draft.quantity,
                purchase_amount=draft.purchase_amount,
                cost_amount=cost_amount,
                reason=draft.reason.strip(),
                note=draft.note,
                happened_at=now,
                updated_at=now,
                location_id=draft.location_id if managed else None,
            )
            _save_payload(
                connection,
                settings,
                PushRequest(
                    device_id="admin-web", cost_protocol=1,
                    inventory_protocol=1 if managed else 0,
                    components=[component],
                    stock_movements=[movement],
                ),
            )
            _save_receipt(
                connection,
                draft.request_id,
                "stock_movement",
                component_id,
                payload_json,
                _storage_time(now),
            )
            return _component(connection, component_id)
    except sqlite3.IntegrityError as error:
        raise AdminOperationConflict("Stock movement could not be saved.") from error
    except ValueError as error:
        if isinstance(error, AdminOperationConflict):
            raise
        raise AdminOperationConflict(str(error)) from error


def update_inventory_cost(
    connection: sqlite3.Connection, settings: Settings, component_id: str,
    draft: AdminInventoryCostUpdate,
) -> dict[str, object]:
    payload_json = _canonical_payload(draft)
    with connection:
        connection.execute("BEGIN IMMEDIATE")
        retry_target = _retry_target(connection, draft.request_id, "inventory_cost", component_id, payload_json)
        if retry_target is not None:
            return _component(connection, retry_target)
        row = _component_row(connection, component_id)
        _require_version(row, draft.expected_updated_at)
        if row["quantity"] == 0:
            raise AdminOperationConflict("Receive stock before recording its inventory cost.")
        unit, delta = backfill_cost(row["quantity"], row["inventory_unit_cost"], draft.inventory_amount)
        managed = bool(row["inventory_managed"])
        now = _now_datetime_after(row["updated_at"])
        component = ComponentPayload(
            id=row["id"], sku=row["sku"], name=row["name"], category=row["category"],
            package_name=row["package_name"], location=row["location"], description=row["description"],
            quantity=row["quantity"], min_stock=row["min_stock"], inventory_unit_cost=unit,
            updated_at=now, allocations=load_allocations(connection, component_id) if managed else None,
            base_updated_at=draft.expected_updated_at if managed else None,
        )
        movement = StockMovementPayload(
            id=f"web-movement-{uuid4()}", component_id=component_id, movement_type="adjustment",
            quantity=0, cost_amount=delta,
            reason="Inventory cost backfill" if row["inventory_unit_cost"] is None else "Inventory cost revaluation",
            note="cost_amount is the initial inventory valuation" if row["inventory_unit_cost"] is None else "cost_amount is the inventory valuation difference",
            happened_at=now, updated_at=now,
        )
        _save_payload(connection, settings, PushRequest(device_id="admin-web", cost_protocol=1,
                      inventory_protocol=1 if managed else 0, components=[component], stock_movements=[movement]))
        _save_receipt(connection, draft.request_id, "inventory_cost", component_id, payload_json, _storage_time(now))
        return _component(connection, component_id)


def _save_payload(
    connection: sqlite3.Connection,
    settings: Settings,
    payload: PushRequest,
) -> None:
    save_sync_payload_in_transaction(
        connection,
        payload,
        mqtt_topic_prefix=(
            settings.mqtt_topic_prefix if settings.mqtt_enabled else None
        ),
    )


def _retry_target(
    connection: sqlite3.Connection,
    request_id: str,
    operation: str,
    target_id: str,
    payload_json: str,
) -> str | None:
    receipt = connection.execute(
        "SELECT operation, target_id, payload_json FROM admin_operation_receipts "
        "WHERE request_id = ?",
        (request_id,),
    ).fetchone()
    if receipt is None:
        return None
    if (
        receipt["operation"] != operation
        or (target_id and receipt["target_id"] != target_id)
        or receipt["payload_json"] != payload_json
    ):
        raise AdminOperationConflict(
            "request_id was already used for a different operation or payload."
        )
    return str(receipt["target_id"])


def _save_receipt(
    connection: sqlite3.Connection,
    request_id: str,
    operation: str,
    target_id: str,
    payload_json: str,
    created_at: str,
) -> None:
    connection.execute(
        "INSERT INTO admin_operation_receipts "
        "(request_id, operation, target_id, payload_json, created_at) "
        "VALUES (?, ?, ?, ?, ?)",
        (request_id, operation, target_id, payload_json, created_at),
    )


def _canonical_payload(model: BaseModel) -> str:
    data = model.model_dump(mode="json")
    return json.dumps(data, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _component_row(
    connection: sqlite3.Connection, component_id: str
) -> sqlite3.Row:
    row = connection.execute(
        "SELECT * FROM components WHERE id = ? AND deleted = 0", (component_id,)
    ).fetchone()
    if row is None:
        raise AdminOperationNotFound("Component not found.")
    return row


def _component(
    connection: sqlite3.Connection, component_id: str
) -> dict[str, object]:
    row = _component_row(connection, component_id)
    result = dict(row)
    result["low_stock"] = int(row["quantity"]) <= int(row["min_stock"])
    result["inventory_managed"] = bool(row["inventory_managed"])
    result["allocations"] = [
        item.model_dump() for item in load_allocations(connection, component_id)
    ]
    return result


def _deleted_component(
    connection: sqlite3.Connection, component_id: str
) -> dict[str, object]:
    row = connection.execute(
        "SELECT id, updated_at, deleted FROM components WHERE id = ?", (component_id,)
    ).fetchone()
    if row is None or not row["deleted"]:
        raise AdminOperationConflict("Component deletion is no longer current.")
    return dict(row)


def _location(connection: sqlite3.Connection, location_id: str) -> dict[str, object]:
    row = connection.execute(
        "SELECT id, name, updated_at FROM storage_locations "
        "WHERE id = ? AND deleted = 0",
        (location_id,),
    ).fetchone()
    if row is None:
        raise AdminOperationNotFound("Storage location not found.")
    return dict(row)


def _active_location(
    connection: sqlite3.Connection, location_id: str
) -> sqlite3.Row:
    row = connection.execute(
        "SELECT id, name FROM storage_locations WHERE id = ? AND deleted = 0",
        (location_id,),
    ).fetchone()
    if row is None:
        raise AdminOperationNotFound("Storage location not found.")
    return row


def _require_version(row: sqlite3.Row, expected: datetime) -> None:
    if _as_utc(expected) != _parse_time(str(row["updated_at"])):
        raise AdminOperationConflict(
            "Inventory changed since it was loaded. Refresh and retry."
        )


def _apply_delta(
    allocations: list[AllocationPayload], location_id: str, delta: int
) -> list[AllocationPayload]:
    result = [item.model_copy() for item in allocations]
    for item in result:
        if item.location_id != location_id:
            continue
        if item.quantity + delta < 0:
            raise AdminOperationConflict(
                "Outbound quantity exceeds inventory at this location."
            )
        item.quantity += delta
        return result
    if delta < 0:
        raise AdminOperationConflict(
            "Outbound quantity exceeds inventory at this location."
        )
    result.append(AllocationPayload(location_id=location_id, quantity=delta))
    return result


def _now_datetime_after(existing: str) -> datetime:
    now = _now_datetime()
    stored = _parse_time(existing)
    if now <= stored:
        from datetime import timedelta

        return stored + timedelta(microseconds=1)
    return now


def _now_datetime() -> datetime:
    return datetime.now(timezone.utc)


def _now() -> str:
    return _storage_time(_now_datetime())


def _storage_time(value: datetime) -> str:
    return _as_utc(value).isoformat(timespec="microseconds").replace("+00:00", "Z")


def _parse_time(value: str) -> datetime:
    return _as_utc(datetime.fromisoformat(value.replace("Z", "+00:00")))


def _as_utc(value: datetime) -> datetime:
    if value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc)
