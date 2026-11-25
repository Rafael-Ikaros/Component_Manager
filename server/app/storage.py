"""Atomic location snapshots and optimistic inventory concurrency checks."""

from __future__ import annotations

from datetime import datetime, timezone
import sqlite3

from .schemas import (
    AllocationPayload, ComponentPayload, PushRequest, StorageLocationPayload,
)


def _time(value: datetime | str) -> datetime:
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00")) if isinstance(value, str) else value
    return parsed.replace(tzinfo=timezone.utc) if parsed.tzinfo is None else parsed.astimezone(timezone.utc)


def load_allocations(
    connection: sqlite3.Connection, component_id: str,
) -> list[AllocationPayload]:
    return [AllocationPayload.model_validate(dict(row)) for row in connection.execute(
        "SELECT location_id, quantity FROM component_allocations "
        "WHERE component_id = ? ORDER BY location_id", (component_id,),
    )]


def check_component_version(
    connection: sqlite3.Connection, existing: sqlite3.Row | None,
    component: ComponentPayload,
) -> bool:
    """Return True for an already accepted identical retry; otherwise check CAS."""
    if component.allocations is None:
        if existing and existing["inventory_managed"]:
            raise ValueError("This inventory requires an updated client with multi-location support.")
        return False
    if not existing:
        # A snapshot imported from another server can create a previously absent ID.
        return False
    stored = dict(existing)
    incoming = component.model_dump(exclude={"allocations", "base_updated_at"})
    same = all(
        (_time(stored[key]) == _time(value) if key == "updated_at"
         else stored[key] == value)
        for key, value in incoming.items()
    )
    if same and existing["inventory_managed"] and (
        {x.location_id: x.quantity for x in load_allocations(connection, component.id)}
        == {x.location_id: x.quantity for x in component.allocations}
    ):
        return True
    if component.base_updated_at is None or (
        _time(component.base_updated_at) != _time(existing["updated_at"])
    ):
        raise ValueError(
            f"Inventory conflict for {component.sku}: the server changed since your last "
            "confirmed snapshot. Local changes have not been overwritten."
        )
    # Version timestamps must identify distinct snapshots even with coarse clocks.
    if (existing["inventory_managed"] or not same) and (
        _time(component.updated_at) == _time(existing["updated_at"])
    ):
        raise ValueError("A changed inventory snapshot must use a new updated_at value.")
    return False


def save_locations(
    connection: sqlite3.Connection, locations: list[StorageLocationPayload],
) -> None:
    for location in locations:
        if not location.id.strip() or location.id != location.id.strip() or not location.name.strip():
            raise ValueError("Location code and name must not be blank or padded.")
        existing = connection.execute(
            "SELECT updated_at FROM storage_locations WHERE id = ?", (location.id,),
        ).fetchone()
        if existing and _time(existing["updated_at"]) > _time(location.updated_at):
            continue
        connection.execute(
            "INSERT INTO storage_locations(id, name, updated_at, deleted) VALUES (?, ?, ?, ?) "
            "ON CONFLICT(id) DO UPDATE SET name=excluded.name, "
            "updated_at=excluded.updated_at, deleted=excluded.deleted",
            (location.id, location.name, _time(location.updated_at).isoformat(), int(location.deleted)),
        )


def save_allocations(connection: sqlite3.Connection, component: ComponentPayload) -> None:
    assert component.allocations is not None
    for allocation in component.allocations:
        location = connection.execute(
            "SELECT deleted FROM storage_locations WHERE id = ?", (allocation.location_id,),
        ).fetchone()
        if location is None or (location["deleted"] and not component.deleted and allocation.quantity > 0):
            raise ValueError(f"Unknown or deleted storage location: {allocation.location_id}")
    connection.execute("DELETE FROM component_allocations WHERE component_id = ?", (component.id,))
    connection.executemany(
        "INSERT INTO component_allocations(component_id, location_id, quantity) VALUES (?, ?, ?)",
        [(component.id, item.location_id, item.quantity) for item in component.allocations],
    )
    connection.execute("UPDATE components SET inventory_managed = 1 WHERE id = ?", (component.id,))


class DuplicateOrderSource(ValueError):
    def __init__(self, source_ref: str, movement_id: str):
        super().__init__("This order item has already been received. No inventory changes were uploaded.")
        self.detail = {"code": "duplicate_order_source", "message": str(self),
                       "source_ref": source_ref, "movement_id": movement_id}


def validate_inventory_push(connection: sqlite3.Connection, payload: PushRequest) -> None:
    for component in payload.components:
        existing = connection.execute("SELECT * FROM components WHERE id = ?", (component.id,)).fetchone()
        if payload.cost_protocol == 1:
            if "inventory_unit_cost" not in component.model_fields_set:
                raise ValueError("Cost protocol 1 requires an explicit inventory_unit_cost, including null.")
        else:
            if existing and existing["inventory_unit_cost"] is not None and component.quantity != existing["quantity"]:
                raise ValueError("This inventory has cost data; upgrade to a client with cost support before changing quantity.")
            component.inventory_unit_cost = existing["inventory_unit_cost"] if existing else None
    if payload.cost_protocol == 0:
        for movement in payload.stock_movements:
            previous = connection.execute("SELECT purchase_amount, cost_amount FROM stock_movements WHERE id = ?", (movement.id,)).fetchone()
            for field in ("purchase_amount", "cost_amount"):
                stored = previous[field] if previous else None
                if field in movement.model_fields_set and getattr(movement, field) != stored:
                    raise ValueError("Changing cost amounts requires cost_protocol=1.")
                setattr(movement, field, stored)
    for items in (payload.components, payload.stock_movements, payload.storage_locations):
        ids = [item.id for item in items]
        if len(ids) != len(set(ids)):
            raise ValueError("Duplicate entity IDs in one sync request are not allowed.")
    if payload.inventory_protocol != 1:
        if payload.storage_locations or any(c.allocations is not None for c in payload.components):
            raise ValueError("Multi-location data requires inventory_protocol=1.")
    elif any(c.allocations is None for c in payload.components):
        raise ValueError("Inventory protocol 1 requires a complete allocation snapshot.")

    components = {c.id: c for c in payload.components}
    sources: dict[str, str] = {}
    for movement in payload.stock_movements:
        stored = connection.execute("SELECT source_ref FROM stock_movements WHERE id = ?", (movement.id,)).fetchone()
        previous = stored["source_ref"] if stored else None
        if "source_ref" not in movement.model_fields_set:
            movement.source_ref = previous
        elif stored and movement.source_ref != previous:
            raise ValueError("An existing inventory movement's order source cannot be changed.")
        source = movement.source_ref
        if source is None:
            continue
        if movement.movement_type != "inbound" or movement.quantity <= 0:
            raise ValueError("An order source requires a positive inbound movement.")
        if not stored and (payload.order_source_protocol != 1 or payload.inventory_protocol != 1):
            raise ValueError("Order sources require order_source_protocol=1 and inventory_protocol=1.")
        if source in sources:
            raise DuplicateOrderSource(source, sources[source])
        sources[source] = movement.id
        owner = connection.execute("SELECT id FROM stock_movements WHERE source_ref = ?", (source,)).fetchone()
        if owner and owner["id"] != movement.id:
            raise DuplicateOrderSource(source, owner["id"])

    checked_retries: set[str] = set()
    location_ids = {row[0] for row in connection.execute("SELECT id FROM storage_locations")}
    location_ids.update(item.id for item in payload.storage_locations)
    for movement in payload.stock_movements:
        parent = connection.execute(
            "SELECT inventory_managed FROM components WHERE id = ?", (movement.component_id,),
        ).fetchone()
        existing = connection.execute(
            "SELECT * FROM stock_movements WHERE id = ?", (movement.id,),
        ).fetchone()
        if parent and parent["inventory_managed"] and payload.inventory_protocol != 1:
            raise ValueError("Managed inventory movements require an updated client.")
        if movement.movement_type == "transfer" and payload.inventory_protocol != 1:
            raise ValueError("Transfers require inventory_protocol=1.")
        if payload.inventory_protocol == 1 and any(
            code is not None and code not in location_ids
            for code in (movement.location_id, movement.destination_location_id)
        ):
            raise ValueError("A movement references an unknown storage location.")
        if payload.inventory_protocol == 1 and not existing:
            component = components.get(movement.component_id)
            if component is None:
                raise ValueError("A new inventory movement requires its component snapshot in the same push.")
            # A changed snapshot can legitimately contain a net-zero batch. An exact
            # retry may also bring back older, unkeyed history (e.g. Excel recovery),
            # but must not acquire another stock change at its own version.
            historical = (movement.source_ref is None and component.base_updated_at is not None
                          and _time(component.base_updated_at) == _time(component.updated_at)
                          and _time(movement.updated_at) < _time(component.updated_at))
            if (payload.order_source_protocol == 1 and movement.quantity != 0 and not historical
                    and component.id not in checked_retries):
                current = connection.execute("SELECT * FROM components WHERE id = ?", (component.id,)).fetchone()
                if check_component_version(connection, current, component):
                    raise ValueError("A changed inventory snapshot is required for a new stock movement; refresh and review before retrying.")
                checked_retries.add(component.id)
        if existing and payload.inventory_protocol == 1:
            for key, value in movement.model_dump().items():
                equal = (
                    _time(existing[key]) == _time(value)
                    if key in ("updated_at", "happened_at") else existing[key] == value
                )
                if not equal:
                    raise ValueError("An existing inventory movement cannot be rewritten.")

    # Component snapshots are authoritative. A push may coalesce a manual stock
    # correction and several transfers, or restore old history, so replaying only
    # the uploaded movements cannot reconstruct its allocation snapshot. The
    # component validator enforces sum/uniqueness; CAS checks its starting version.
