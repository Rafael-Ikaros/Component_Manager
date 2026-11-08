import sqlite3

import pytest
from pydantic import ValidationError

from app.costs import movement_cost
from app.database import init_db
from app.config import get_settings
from app.schemas import ComponentPayload, StockMovementPayload
from test_admin_inventory_writes import (
    HEADERS, _client, _create_component, _create_location, _edit, _movement,
)


def component(**changes):
    return dict(id="cost-1", sku="COST", name="Cost", category="IC", package_name="DIP",
                location="A", quantity=3, min_stock=0, updated_at="2026-01-01T00:00:00Z",
                inventory_unit_cost="0.3333333333333333") | changes


def test_cost_math_preserves_average_and_unknown():
    unit, amount = movement_cost(3, "0.3333333333333333", 1, None, False)
    assert unit == "0.3333333333333333" and amount == unit
    assert movement_cost(2, unit, 2, None, False) == (None, "0.6666666666666666")
    assert movement_cost(2, None, 3, "1", True) == (None, "1")
    assert movement_cost(2, "1", 3, None, True) == (None, None)
    assert movement_cost(0, None, 3, "1", True)[0] == unit
    assert movement_cost(3, "1", 1, "0", True) == ("0.75", "0")
    # Exact midpoint checks make the rounding policy observable.
    assert movement_cost(0, None, 131072, "0.00000001", True)[0] == "0.0000000000000763"


@pytest.mark.parametrize("field,value", [("inventory_unit_cost", 0.1), ("inventory_unit_cost", "-1"),
                                          ("inventory_unit_cost", "1e-3"), ("inventory_unit_cost", "0.00000000000000001")])
def test_component_cost_rejects_float_and_invalid_decimals(field, value):
    with pytest.raises(ValidationError):
        ComponentPayload.model_validate(component(**{field: value}))


def test_purchase_amount_precision_and_direction():
    base = dict(id="m", component_id="c", movement_type="inbound", quantity=1,
                reason="buy", happened_at="2026-01-01", updated_at="2026-01-01")
    for value in (0.1, "0.000000001", "NaN", "-1"):
        with pytest.raises(ValidationError):
            StockMovementPayload.model_validate(base | {"purchase_amount": value})
    with pytest.raises(ValidationError):
        StockMovementPayload.model_validate(base | {"movement_type": "outbound", "purchase_amount": "1"})


def test_migration_keeps_legacy_quantity_and_unknown_cost(tmp_path, monkeypatch):
    path = tmp_path / "old.db"
    with sqlite3.connect(path) as db:
        db.execute("CREATE TABLE components(id TEXT PRIMARY KEY,sku TEXT,name TEXT,category TEXT,package_name TEXT,location TEXT,description TEXT,quantity INTEGER,min_stock INTEGER,updated_at TEXT,deleted INTEGER)")
        db.execute("INSERT INTO components VALUES('old','OLD','Old','IC','DIP','A',NULL,7,0,'2026-01-01T00:00:00Z',0)")
    monkeypatch.setenv("DATABASE_PATH", str(path))
    get_settings.cache_clear()
    init_db(get_settings())
    init_db(get_settings())
    with sqlite3.connect(path) as db:
        assert db.execute("SELECT quantity,inventory_unit_cost FROM components").fetchone() == (7, None)
        assert {r[1] for r in db.execute("PRAGMA table_info(stock_movements)")} >= {"purchase_amount", "cost_amount"}
    get_settings.cache_clear()


def test_sync_cost_capability_old_client_protection_and_atomic_rollback(tmp_path, monkeypatch):
    with _client(tmp_path, monkeypatch, True) as client:
        assert client.get("/health").json()["cost_protocol"] == 1
        push = lambda components, **extra: client.post("/sync/push", headers=HEADERS, json={"device_id": "cost-test", "components": components} | extra)
        missing = component()
        del missing["inventory_unit_cost"]
        assert push([missing], cost_protocol=1).status_code == 409
        assert push([component()], cost_protocol=1).status_code == 200
        assert client.get("/sync/pull", headers=HEADERS).json()["cost_protocol"] == 1
        legacy = missing | {"name": "Edited", "updated_at": "2026-01-02T00:00:00Z"}
        assert push([legacy]).status_code == 200
        pulled = client.get("/sync/pull", headers=HEADERS).json()["components"][0]
        assert pulled["inventory_unit_cost"] == "0.3333333333333333"
        assert push([legacy | {"quantity": 4, "updated_at": "2026-01-03T00:00:00Z"}]).status_code == 409
        # A rejected cost overwrite rolls back every component in the push.
        assert push([component(id="other", sku="OTHER"), legacy | {"quantity": 9}]).status_code == 409
        assert len(client.get("/sync/pull", headers=HEADERS).json()["components"]) == 1


def test_web_cost_inbound_outbound_backfill_and_retry(tmp_path, monkeypatch):
    with _client(tmp_path, monkeypatch, True) as client:
        assert _create_location(client).status_code == 201
        record = _create_component(client).json()
        assert record["inventory_unit_cost"] is None
        root = "/admin-api/components/" + record["id"]
        request = _movement(record, quantity=3, purchase_amount="1")
        response = client.post(root + "/movements", headers=HEADERS, json=request)
        assert response.status_code == 200, response.text
        record = response.json()
        assert record["inventory_unit_cost"] == "0.3333333333333333"
        assert client.post(root + "/movements", headers=HEADERS, json=request).json()["quantity"] == 3
        snapshot = client.get("/sync/pull", headers=HEADERS).json()
        old_component = dict(snapshot["components"][0])
        old_component.pop("inventory_unit_cost")
        old_movements = [{key: value for key, value in movement.items()
                          if key not in {"purchase_amount", "cost_amount"}}
                         for movement in snapshot["stock_movements"]]
        legacy_push = {"device_id": "old-cost-unaware", "inventory_protocol": 1,
                       "components": [old_component], "stock_movements": old_movements}
        replay = client.post("/sync/push", headers=HEADERS, json=legacy_push)
        assert replay.status_code == 200, replay.text
        old_movements[0]["purchase_amount"] = None
        assert client.post("/sync/push", headers=HEADERS, json=legacy_push).status_code == 409
        edited = client.put(root, headers=HEADERS, json=_edit(record))
        assert edited.status_code == 200, edited.text
        record = edited.json()
        assert record["inventory_unit_cost"] == "0.3333333333333333"
        record = client.post(root + "/movements", headers=HEADERS, json=_movement(record,
            request_id="outbound-cost-001", quantity=1, movement_type="outbound")).json()
        assert record["inventory_unit_cost"] == "0.3333333333333333"
        request = {"request_id": "cost-backfill-001", "expected_updated_at": record["updated_at"], "inventory_amount": "2"}
        response = client.post(root + "/cost", headers=HEADERS, json=request)
        assert response.status_code == 200, response.text
        record = response.json()
        assert record["inventory_unit_cost"] == "1"
        assert client.post(root + "/cost", headers=HEADERS, json=request).status_code == 200
        record = client.post(root + "/movements", headers=HEADERS, json=_movement(record,
            request_id="outbound-cost-002", quantity=2, movement_type="outbound")).json()
        assert record["quantity"] == 0 and record["inventory_unit_cost"] is None
        movements = client.get("/sync/pull", headers=HEADERS).json()["stock_movements"]
        assert len(movements) == 4
        assert movements[0]["purchase_amount"] == "1" and movements[0]["cost_amount"] == "1"
        assert movements[2]["quantity"] == 0 and movements[2]["cost_amount"] == "1.3333333333333334"
