from copy import deepcopy
import sqlite3

from app.config import get_settings
from app.database import init_db

import pytest

from test_storage_sync import HEADERS, LATER, TIME, client, component, snapshot

NEXT = "2026-09-15T00:02:00Z"
SOURCE = "lcsc-order:v1:" + "a" * 64
OTHER = "lcsc-order:v1:" + "b" * 64


def send(client, items, movements=(), *, order_protocol=1, extra_locations=(), headers=HEADERS):
    return client.post("/sync/push", headers=headers, json={
        "device_id": "synthetic", "inventory_protocol": 1, "cost_protocol": 1,
        "order_source_protocol": order_protocol,
        "components": items, "stock_movements": list(movements),
        "storage_locations": [dict(id=code, name=code, updated_at=TIME)
                              for code in ("A", *extra_locations)],
    })


def part(quantity=10, stamp=TIME, base=None, cost="2", **fields):
    return component(quantity=quantity, updated_at=stamp, base_updated_at=base,
                     inventory_unit_cost=cost, allocations=[{"location_id": "A", "quantity": quantity}], **fields)


def inbound(id="m1", stamp=LATER, source=SOURCE, amount="30", **fields):
    value = dict(id=id, component_id="c1", movement_type="inbound", quantity=10,
                 reason="Synthetic order", happened_at=stamp, updated_at=stamp,
                 location_id="A", purchase_amount=amount, cost_amount=amount)
    if source is not None:
        value["source_ref"] = source
    return value | fields


def seeded(client, movement=None):
    assert send(client, [part()]).status_code == 200
    first = part(20, LATER, TIME, "2.5")
    entry = movement or inbound()
    assert send(client, [first], [entry]).status_code == 200
    return first, entry


def test_retries_preserve_source_and_cannot_clear_or_change_it(client):
    first, entry = seeded(client)
    assert client.post("/auth/ping", headers=HEADERS).json()["order_source_protocol"] == 1
    assert send(client, [first], [entry]).status_code == 200
    legacy = {key: value for key, value in entry.items() if key != "source_ref"}
    assert send(client, [first], [legacy], order_protocol=0).status_code == 200
    for source in (None, OTHER):
        assert send(client, [], [entry | {"source_ref": source}]).status_code == 409
    pulled = snapshot(client)
    assert pulled["order_source_protocol"] == 1
    assert len(pulled["stock_movements"]) == 1
    assert pulled["stock_movements"][0]["source_ref"] == SOURCE


@pytest.mark.parametrize("base", [TIME, LATER])
def test_same_order_conflict_rejects_whole_push_even_with_valid_base(client, base):
    seeded(client)
    before = snapshot(client)
    response = send(client, [part(30, NEXT, base, "2.6666666666666667"), part(id="c2", sku="C2")],
                    [inbound("m2", NEXT, amount="31")], extra_locations=("ROLLBACK",))
    assert response.status_code == 409
    assert response.json()["detail"] == {
        "code": "duplicate_order_source", "message": "This order item has already been received. No inventory changes were uploaded.",
        "source_ref": SOURCE, "movement_id": "m1",
    }
    after = snapshot(client)
    for key in ("sync_cursor", "components", "stock_movements", "storage_locations"):
        assert after[key] == before[key]
    assert send(client, [part(25, NEXT, LATER, "2.8")], [inbound("m3", NEXT, OTHER, "20", quantity=5)]).status_code == 200
    after_restock = snapshot(client)
    assert after_restock["components"][0]["inventory_unit_cost"] == "2.8"
    assert [item["purchase_amount"] for item in after_restock["stock_movements"]] == ["30", "20"]


def test_source_conflicts_within_batch_across_component_ids_and_after_soft_delete(client):
    first = part(10, LATER, cost="3")
    second = part(10, LATER, cost="3", id="c2")
    a = inbound(deleted=True)
    b = inbound("m2", component_id="c2")
    assert send(client, [first, second], [a, b]).json()["detail"]["code"] == "duplicate_order_source"
    assert snapshot(client)["components"] == []
    assert send(client, [first], [a]).status_code == 200
    assert send(client, [second], [b]).json()["detail"]["code"] == "duplicate_order_source"
    assert send(client, [second], [inbound("m2", source=OTHER, component_id="c2")]).status_code == 409


def test_equal_snapshot_does_not_admit_new_stock_change_but_allows_valuation_and_changed_net_zero(client):
    first, _ = seeded(client)
    before = snapshot(client)
    replay = inbound("different", source=None)
    response = send(client, [first], [replay])
    assert response.status_code == 409
    assert snapshot(client)["sync_cursor"] == before["sync_cursor"]
    assert send(client, [first], [replay | {"movement_type": "adjustment", "quantity": 0,
                                           "purchase_amount": None}]).status_code == 200
    unchanged_quantity = deepcopy(first)
    unchanged_quantity.update(base_updated_at=LATER, updated_at=NEXT)
    out = inbound("out", NEXT, None, None, movement_type="outbound", cost_amount="25")
    assert send(client, [unchanged_quantity], [inbound("new", NEXT, None, "25"), out]).status_code == 200
    assert snapshot(client)["components"][0]["inventory_unit_cost"] == "2.5"


@pytest.mark.parametrize("amount", [None, "0"])
def test_source_does_not_depend_on_amount(client, amount):
    entry = inbound(amount=amount)
    assert send(client, [part(10, LATER, cost=amount)], [entry]).status_code == 200
    movement = snapshot(client)["stock_movements"][0]
    assert movement["purchase_amount"] == amount
    assert movement["source_ref"] == SOURCE


def test_order_source_is_unique_per_account(client):
    for name in ("Synthetic A", "Synthetic B"):
        created = client.post("/admin-api/accounts", headers=HEADERS, json={"name": name})
        assert created.status_code == 201, created.text
        account = created.json()
        headers = {"Authorization": f"Bearer {account['api_token']}",
                   "X-Component-Vault-Account-Id": account["account_id"]}
        assert send(client, [part(10, LATER, cost="3")], [inbound()], headers=headers).status_code == 200
        pulled = client.get("/sync/pull", headers=headers).json()
        assert [movement["source_ref"] for movement in pulled["stock_movements"]] == [SOURCE]
    assert snapshot(client)["stock_movements"] == []


def test_migrates_source_column_without_rewriting_legacy_stock(client):
    seeded(client, inbound(source=None))
    settings = get_settings()
    with sqlite3.connect(settings.database_path) as db:
        db.execute("DROP INDEX idx_movement_source_ref")
        db.execute("ALTER TABLE stock_movements DROP COLUMN source_ref")
    for _ in range(2):
        init_db(settings)
    with sqlite3.connect(settings.database_path) as db:
        assert db.execute("SELECT quantity, inventory_unit_cost FROM components").fetchone() == (20, "2.5")
        assert db.execute("SELECT id, quantity, purchase_amount, cost_amount, source_ref FROM stock_movements").fetchone() == (
            "m1", 10, "30", "30", None)
        assert any(row[1] == "idx_movement_source_ref" and row[2] and row[4]
                   for row in db.execute("PRAGMA index_list(stock_movements)"))


def test_exact_snapshot_can_restore_older_unkeyed_history(client):
    first, _ = seeded(client)
    historical = inbound("legacy-history", TIME, None, "20")
    assert send(client, [first], [historical]).status_code == 409
    restored = first | {"base_updated_at": first["updated_at"]}
    assert send(client, [restored], [historical]).status_code == 200
    assert snapshot(client)["components"][0]["quantity"] == 20


def test_metadata_edit_does_not_turn_new_stock_movement_into_history(client):
    assert send(client, [part()]).status_code == 200
    edited = part(20, NEXT, TIME, "2.5", name="Edited after receipt")
    assert send(client, [edited], [inbound(source=None)]).status_code == 200
    before = snapshot(client)
    assert send(client, [edited], [inbound("second-offline", source=None)]).status_code == 409
    after = snapshot(client)
    assert after["sync_cursor"] == before["sync_cursor"]
    assert after["stock_movements"] == before["stock_movements"]
