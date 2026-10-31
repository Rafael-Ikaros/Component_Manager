from __future__ import annotations

from threading import Event
import time

from app.admin import catalog_metadata as catalog
from app.accounts import Account
from app.admin.spec_enrichment import EnrichmentJob, EnrichmentJobs
from app.config import get_settings
from test_order_metadata import AUTH, DESCRIPTION, client_for, seed

ROOT = "/admin-api/components/catalog-metadata/jobs"
PUBLIC = {"code": 200, "result": {"productList": [{
    "code": "C1", "id": 71663, "catalogName": "无源晶振", "standard": "SMD3225-4P", "isShowPrice": True,
    "priceList": [{"startNumber": 50, "price": "0.4639"}, {"startNumber": 5, "price": "0.5803"}],
    "device_info": {"attributes": {"Value": "12MHz", "Load Capacitance": "12pF", "Symbol": "excluded"}},
}]}}


def done(client, job_id, headers=AUTH):
    for _ in range(200):
        response = client.get(f"{ROOT}/{job_id}", headers=headers)
        assert response.status_code == 200, response.text
        job = response.json()
        if job["state"] in {"completed", "cancelled"}:
            return job
        time.sleep(0.01)
    raise AssertionError("preview did not finish")


def start(client, headers=AUTH):
    response = client.post(ROOT, headers=headers, json={})
    assert response.status_code == 202, response.text
    return response.json()["id"]


def test_domestic_parser_exact_price_tier_and_notes_preserve_free_bytes():
    result = catalog.parse_catalog("C1", PUBLIC)
    assert result["parameters"] == {"Value": "12MHz", "Load Capacitance": "12pF"}
    assert result["reference_price"]["currency"] == "CNY"
    assert result["reference_price"]["unit_price"] == "0.5803"
    assert result["reference_price"]["minimum_quantity"] == 5
    assert catalog.parse_catalog("C11", PUBLIC) is None
    source = " 我的备注  \r\n商品图片：https://example.test/photo.jpg\r\n参数·旧参数：1\r\n参考单价：9\r\n尾备注"
    merged = catalog.merge_groups(source, {"parameters": result["parameters"], "reference_price": result["reference_price"]})
    assert merged.startswith(" 我的备注  \r\n商品图片：https://example.test/photo.jpg\r\n尾备注\n")
    assert "旧参数" not in merged and "参考单价：0.5803" in merged
    assert catalog.merge_groups(source, {}) == source


def test_preview_readonly_selected_apply_receipt_conflict_and_native_sync(tmp_path, monkeypatch):
    monkeypatch.setattr(catalog, "lookup_catalog", lambda sku: catalog.parse_catalog(sku, PUBLIC))
    with client_for(tmp_path, monkeypatch) as client:
        before = seed(client)
        job_id = start(client)
        job = done(client, job_id)
        assert job["ready"] == 1
        after_preview = client.get("/sync/pull", headers=AUTH).json()
        assert after_preview["components"] == before["components"]
        assert after_preview["stock_movements"] == before["stock_movements"]
        row = client.get(f"{ROOT}/{job_id}/rows", headers=AUTH).json()["items"][0]
        assert set(row["changes"]) == {"category", "package_name", "parameters", "reference_price"}
        assert client.get(f"/admin-api/components/spec-enrichment/jobs/{job_id}", headers=AUTH).status_code == 404
        assert client.post(f"/admin-api/components/spec-enrichment/jobs/{job_id}/retry", headers=AUTH, json={}).status_code == 404
        assert client.post(f"/admin-api/components/spec-enrichment/jobs/{job_id}/clear", headers=AUTH, json={}).status_code == 404
        body = {"items": [{"component_id": row["id"], "fields": ["parameters", "reference_price"], "request_id": "catalog-apply-001"}]}
        applied = client.post(f"{ROOT}/{job_id}/apply", headers=AUTH, json=body)
        assert applied.status_code == 200, applied.text
        assert applied.json()["summary"]["updated"] == 1
        assert client.post(f"{ROOT}/{job_id}/apply", headers=AUTH, json=body).json()["summary"]["unchanged"] == 1
        current = client.get("/sync/pull", headers=AUTH).json()
        old, new = before["components"][0], current["components"][0]
        for key in ("name", "sku", "category", "package_name", "quantity", "min_stock", "location", "allocations"):
            assert new[key] == old[key]
        assert new["description"].startswith("我的备注  \r\n图片：https://example.test/photo.jpg\r\n尾部备注")
        assert "参数：Value：12MHz" in new["description"]
        assert "参考币种：CNY" in new["description"]
        assert current["stock_movements"] == before["stock_movements"]
        body["items"][0].update(fields=["category"], request_id="catalog-apply-002")
        assert client.post(f"{ROOT}/{job_id}/apply", headers=AUTH, json=body).json()["summary"]["conflict"] == 1


def test_readonly_scan_with_write_gate_and_account_job_isolation(tmp_path, monkeypatch):
    monkeypatch.setattr(catalog, "lookup_catalog", lambda sku: catalog.parse_catalog(sku, PUBLIC))
    with client_for(tmp_path, monkeypatch) as client:
        seed(client)
        alice = client.post("/admin-api/accounts", headers=AUTH, json={"name": "Alice"}).json()
        headers = {"Authorization": f"Bearer {alice['api_token']}", "X-Component-Vault-Account-Id": alice["account_id"]}
        job_id = start(client)
        done(client, job_id)
        assert client.get(f"{ROOT}/{job_id}", headers=headers).status_code == 404
        assert done(client, start(client, headers), headers)["total"] == 0
        monkeypatch.setenv("WEB_INVENTORY_ENABLED", "false")
        get_settings.cache_clear()
        assert client.post(f"{ROOT}/{job_id}/apply", headers=AUTH, json={"items": [{"component_id": "c1", "fields": ["category"], "request_id": "gate-apply-001"}]}).status_code == 403
        assert start(client)


def test_scan_deduplicates_skus_skips_invalid_and_cancellation_stops_after_request(monkeypatch):
    calls = []
    entered, release = Event(), Event()
    def lookup(sku):
        calls.append(sku)
        return catalog.parse_catalog(sku, PUBLIC)
    monkeypatch.setattr(catalog, "lookup_catalog", lookup)
    base = {"id": "a", "sku": "C1", "category": "old", "package_name": "old", "description": "", "name": "part", "updated_at": "2026-01-01T00:00:00Z", "status": "pending"}
    job = EnrichmentJob("id", "admin", "unused", [base.copy(), {**base, "id": "b"}, {**base, "id": "c", "sku": "LOCAL-1"}], preview=True)
    catalog.scan_preview(job)
    assert calls == ["C1"]
    assert job.items[2]["reason"] == "invalid_sku"
    def blocked(sku):
        entered.set()
        assert release.wait(2)
        return None
    monkeypatch.setattr(catalog, "lookup_catalog", blocked)
    manager = EnrichmentJobs()
    account = Account("server", "admin", "Admin", "admin", "unused")
    job = manager.start(account, get_settings(), [base.copy(), {**base, "id": "b", "sku": "C2"}], preview=True)
    assert entered.wait(2)
    job.cancel.set()
    release.set()
    job.thread.join(2)
    assert job.state == "cancelled" and job.items[1]["status"] == "pending"
    manager.cancel_account_and_wait("admin", "unused")
    try:
        manager.start(account, get_settings(), [], preview=True)
        assert False, "deleted account must stay blocked"
    except ValueError:
        pass


def test_retry_keeps_ready_previews_while_reanalyzing_failed_rows(tmp_path, monkeypatch):
    with client_for(tmp_path, monkeypatch) as client:
        seed(client)
        monkeypatch.setattr(catalog, "lookup_catalog", lambda sku: (_ for _ in ()).throw(OSError("offline")))
        job_id = start(client)
        assert done(client, job_id)["failed"] == 1
        monkeypatch.setattr(catalog, "lookup_catalog", lambda sku: catalog.parse_catalog(sku, PUBLIC))
        response = client.post(f"{ROOT}/{job_id}/retry", headers=AUTH, json={})
        assert response.status_code == 202, response.text
        assert done(client, response.json()["id"])["ready"] == 1
