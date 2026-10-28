from __future__ import annotations

from io import BytesIO

from fastapi.testclient import TestClient
import xlwt

from app.admin.order_metadata import merge_parameters, parameters_from_description
from app.config import get_settings
from app.main import create_app


AUTH = {"Authorization": "Bearer order-test-token"}
ROOT = "/admin-api/components/order-metadata"
DESCRIPTION = "我的备注  \r\n图片：https://example.test/photo.jpg\r\n参数·阻值：1kΩ\r\n参数·功率：0.1W\r\n尾部备注"


def workbook(rows: list[list[str]]) -> bytes:
    book = xlwt.Workbook()
    sheet = book.add_sheet("订单")
    headers = ["商品编号", "订购数量（修改后）", "商品名称", "厂家型号", "品牌", "商品类型", "封装格式", "额定电流"]
    for index, row in enumerate([headers, *rows]):
        for column, cell in enumerate(row):
            sheet.write(index, column, cell)
    output = BytesIO()
    book.save(output)
    return output.getvalue()


def client_for(tmp_path, monkeypatch, enabled=True):
    monkeypatch.setenv("API_TOKEN", "order-test-token")
    monkeypatch.setenv("DATABASE_PATH", str(tmp_path / "inventory.db"))
    monkeypatch.setenv("WEB_INVENTORY_ENABLED", str(enabled).lower())
    monkeypatch.setenv("MQTT_ENABLED", "false")
    get_settings.cache_clear()
    return TestClient(create_app())


def seed(client, headers=AUTH):
    assert client.post("/admin-api/storage-locations", headers=headers, json={
        "id": "A", "name": "A", "request_id": "seed-location",
    }).status_code == 201
    component = {
        "id": "c1", "sku": "C1", "name": "我的名称", "category": "旧分类", "package_name": "旧封装",
        "description": DESCRIPTION, "quantity": 8, "min_stock": 3,
        "location": "A", "allocations": [{"location_id": "A", "quantity": 8}],
        "updated_at": "2026-10-20T10:00:00Z",
    }
    movement = {
        "id": "m1", "component_id": "c1", "movement_type": "inbound", "quantity": 8,
        "reason": "原始流水", "note": "保留", "location_id": "A",
        "happened_at": "2026-10-20T10:00:00Z", "updated_at": "2026-10-20T10:00:00Z",
    }
    response = client.post("/sync/push", headers=headers, json={
        "device_id": "fixture", "inventory_protocol": 1, "components": [component], "stock_movements": [movement],
    })
    assert response.status_code == 200, response.text
    return client.get("/sync/pull", headers=headers).json()


def preview(client, data, headers=AUTH):
    return client.post(ROOT + "/preview", headers=headers, files={"file": ("order.xls", data, "application/vnd.ms-excel")})


def apply_draft(row, **overrides):
    return {
        "component_id": row["component_id"], "sku": row["sku"],
        "expected_updated_at": row["expected_updated_at"], "request_id": "metadata-request-1",
        "changes": {key: value["after"] for key, value in row["changes"].items()},
        **overrides,
    }


def test_biff_preview_apply_preserves_inventory_and_is_pull_visible(tmp_path, monkeypatch):
    with client_for(tmp_path, monkeypatch) as client:
        before = seed(client)
        data = workbook([
            ["C1", "999", "电阻 10kΩ ±1% 0.25W", "不可当参数的型号", "品牌", "贴片电阻", "0603", ""],
            ["C1", "1", "电阻 10kΩ ±1% 0.25W", "不可当参数的型号", "品牌", "贴片电阻", "0603", ""],
            ["C2", "1", "电容 1uF", "型号", "品牌", "电容", "0805", ""],
            ["C2", "2", "电容 2uF", "型号", "品牌", "电容", "0805", ""],
            ["C3", "1", "新件", "型号", "品牌", "电感", "0805", ""],
        ])
        response = preview(client, data)
        assert response.status_code == 200, response.text
        result = response.json()
        assert result["summary"] == {"ready": 1, "conflict": 1, "unmatched": 1, "unchanged": 0}
        row = result["rows"][0]
        assert row["source_rows"] == [2, 3]
        assert row["name"] == "我的名称"
        assert row["changes"]["parameters"]["after"] == {"阻值": "10kΩ", "功率": "0.25W", "精度": "±1%"}
        assert result["rows"][1]["changes"] == {}
        readonly = client.get("/sync/pull", headers=AUTH).json()
        assert {key: value for key, value in readonly.items() if key != "server_time"} == {key: value for key, value in before.items() if key != "server_time"}
        # Parameter selection changes only these keys; deselected power remains byte-identical.
        draft = apply_draft(row)
        draft["changes"]["parameters"].pop("功率")
        applied = client.post(ROOT + "/apply", headers=AUTH, json={"items": [draft]})
        assert applied.status_code == 200, applied.text
        assert applied.json()["summary"]["updated"] == 1
        after = client.get("/sync/pull", headers=AUTH).json()
        old, new = before["components"][0], after["components"][0]
        for key in ("name", "sku", "quantity", "location", "allocations", "min_stock"):
            assert new[key] == old[key]
        assert new["category"] == "贴片电阻"
        assert new["package_name"] == "0603"
        assert new["description"] == DESCRIPTION.replace("参数·阻值：1kΩ", "参数·阻值：10kΩ") + "\r\n参数·精度：±1%\r\n"
        assert after["stock_movements"] == before["stock_movements"]
        assert after["sync_cursor"] > before["sync_cursor"]
        replay = client.post(ROOT + "/apply", headers=AUTH, json={"items": [draft]})
        assert replay.json()["summary"]["unchanged"] == 1
        replay_pull = client.get("/sync/pull", headers=AUTH).json()
        assert {key: value for key, value in replay_pull.items() if key != "server_time"} == {key: value for key, value in after.items() if key != "server_time"}
        stale = {**draft, "request_id": "metadata-stale-1"}
        assert client.post(ROOT + "/apply", headers=AUTH, json={"items": [stale]}).json()["summary"]["conflict"] == 1
        altered = {**draft, "changes": {"package_name": "0805"}}
        assert client.post(ROOT + "/apply", headers=AUTH, json={"items": [altered]}).json()["summary"]["conflict"] == 1
        incremental = client.get(f"/sync/pull?cursor={before['sync_cursor']}", headers=AUTH).json()
        assert incremental["components"][0]["description"] == new["description"]


def test_preview_empty_english_ambiguous_values_and_strict_apply(tmp_path, monkeypatch):
    with client_for(tmp_path, monkeypatch) as client:
        seed(client)
        data = workbook([["C1", "1", "MODEL1234 1kΩ 2kΩ", "100uF", "品牌", "Resistors", "", ""]])
        row = preview(client, data).json()["rows"][0]
        assert row["status"] == "unchanged"
        assert row["changes"] == {}
        assert preview(client, b"not an xls").status_code == 400
        assert preview(client, bytes.fromhex("D0CF11E0A1B11AE1")).status_code == 400
        assert preview(client, b"x" * (16 * 1024 * 1024 + 1)).status_code == 413
        base = apply_draft(row, changes={"category": "电阻"})
        for changes in ({"quantity": 20}, {"description": "覆盖备注"}, {"parameters": {"型号": "R100"}}, {"parameters": {"阻值": ""}}, {"category": "Resistors"}, {"package_name": " "}):
            assert client.post(ROOT + "/apply", headers=AUTH, json={"items": [{**base, "changes": changes}]}).status_code == 422
        assert client.post(ROOT + "/apply", headers=AUTH, json={"items": [{**base, "quantity": 5}]}).status_code == 422
        duplicate = client.post(ROOT + "/apply", headers=AUTH, json={"items": [base, {**base, "request_id": "duplicate-request"}]})
        assert duplicate.json()["summary"]["conflict"] == 2
        assert client.post(ROOT + "/apply", json={"items": [base]}).status_code == 401


def test_preview_is_read_only_when_web_writes_disabled(tmp_path, monkeypatch):
    with client_for(tmp_path, monkeypatch, enabled=False) as client:
        data = workbook([["C1", "1", "电容 10uF 16V", "型号", "品牌", "电容", "0603", ""]])
        response = preview(client, data)
        assert response.status_code == 200
        assert response.json()["summary"]["unmatched"] == 1
        draft = {"component_id": "c1", "sku": "C1", "expected_updated_at": "2026-10-20T10:00:00Z", "request_id": "disabled-request", "changes": {"category": "电容"}}
        assert client.post(ROOT + "/apply", headers=AUTH, json={"items": [draft]}).status_code == 403


def test_accounts_cannot_preview_or_apply_another_inventory_and_receipts_are_isolated(tmp_path, monkeypatch):
    with client_for(tmp_path, monkeypatch) as client:
        seed(client)
        account = client.post("/admin-api/accounts", headers=AUTH, json={"name": "Alice"}).json()
        alice = {"Authorization": f"Bearer {account['api_token']}", "X-Component-Vault-Account-Id": account["account_id"]}
        data = workbook([["C1", "1", "电阻 10kΩ", "型号", "品牌", "电阻", "0603", ""]])
        admin_row = preview(client, data).json()["rows"][0]
        alice_row = preview(client, data, alice).json()["rows"][0]
        assert alice_row["status"] == "unmatched"
        draft = apply_draft(admin_row)
        assert client.post(ROOT + "/apply", headers=alice, json={"items": [draft]}).json()["summary"]["failed"] == 1
        seed(client, alice)
        for headers in (AUTH, alice):
            row = preview(client, data, headers).json()["rows"][0]
            assert client.post(ROOT + "/apply", headers=headers, json={"items": [apply_draft(row)]}).json()["summary"]["updated"] == 1
        assert client.get("/sync/pull", headers=alice).json()["components"][0]["category"] == "电阻"


def test_merge_parameters_preserves_unselected_bytes_and_duplicate_selected_lines():
    source = " \r\n参数·容量：1uF\r\nimage:[opaque]\n参数·容量：2uF\n参数·耐压：16V\r\n"
    assert merge_parameters(source, {"容量": "10uF"}) == source.replace("容量：1uF", "容量：10uF").replace("容量：2uF", "容量：10uF")


def test_english_legacy_parameter_alias_is_replaced_and_preview_before_is_real(tmp_path, monkeypatch):
    legacy = "备注\r\n参数·Resistance(Ω)：1000\r\n参数：Voltage rating：16V\r\n图片：[opaque]\r\n"
    assert parameters_from_description(legacy) == {"阻值": "1000Ω", "耐压": "16V"}
    merged = merge_parameters(legacy, {"阻值": "10kΩ"})
    assert merged == legacy.replace("参数·Resistance(Ω)：1000", "参数·阻值：10kΩ")
    with client_for(tmp_path, monkeypatch) as client:
        before = seed(client)
        component = before["components"][0]
        component.update(description=legacy, updated_at="2026-10-21T10:00:00Z", base_updated_at=component["updated_at"])
        assert client.post("/sync/push", headers=AUTH, json={"device_id": "fixture", "inventory_protocol": 1, "components": [component]}).status_code == 200
        row = preview(client, workbook([["C1", "1", "电阻 10kΩ", "型号", "品牌", "电阻", "0603", ""]])).json()["rows"][0]
        assert row["changes"]["parameters"]["before"] == {"阻值": "1000Ω"}
        assert client.post(ROOT + "/apply", headers=AUTH, json={"items": [apply_draft(row)]}).json()["summary"]["updated"] == 1
        current = client.get("/sync/pull", headers=AUTH).json()["components"][0]
        assert current["description"] == merged
