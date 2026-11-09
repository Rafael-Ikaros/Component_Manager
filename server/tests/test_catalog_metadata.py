from __future__ import annotations

from threading import Event
import time
from urllib.error import HTTPError

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


def start(client, headers=AUTH, **options):
    response = client.post(ROOT, headers=headers, json=options)
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
    assert merged.startswith(" 我的备注  \r\n商品图片：https://example.test/photo.jpg\r\n参数·旧参数：1\r\n尾备注\n")
    assert "参考单价：0.5803" in merged
    assert catalog.merge_groups(source, {}) == source


def test_preview_readonly_selected_apply_receipt_conflict_and_native_sync(tmp_path, monkeypatch):
    monkeypatch.setattr(catalog, "lookup_catalog", lambda sku: catalog.parse_catalog(sku, PUBLIC))
    with client_for(tmp_path, monkeypatch) as client:
        before = seed(client)
        job_id = start(client, mode="review")
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
        body = {"items": [{"component_id": row["id"], "fields": ["parameters", "reference_price"],
                           "parameter_keys": ["Value"], "request_id": "catalog-apply-001"}]}
        applied = client.post(f"{ROOT}/{job_id}/apply", headers=AUTH, json=body)
        assert applied.status_code == 200, applied.text
        assert applied.json()["summary"]["updated"] == 1
        assert client.post(f"{ROOT}/{job_id}/apply", headers=AUTH, json=body).json()["summary"]["unchanged"] == 1
        current = client.get("/sync/pull", headers=AUTH).json()
        old, new = before["components"][0], current["components"][0]
        for key in ("name", "sku", "category", "package_name", "quantity", "min_stock", "location", "allocations"):
            assert new[key] == old[key]
        assert new["description"].startswith(DESCRIPTION)
        assert "参数：Value：12MHz" in new["description"]
        assert "参数：Load Capacitance" not in new["description"]
        assert "参考币种：CNY" in new["description"]
        assert current["stock_movements"] == before["stock_movements"]
        body["items"][0].update(fields=["category"], request_id="catalog-apply-002")
        body["items"][0].pop("parameter_keys")
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


def test_missing_mode_and_review_compare_units_and_preserve_manual_fields():
    item = {"category": "自定义", "package_name": "0603",
            "description": "参数：Resistance：33R\r\n参数：备注字段：原值\r\n"}
    candidate = {"category": "电阻", "package_name": "0402",
                 "parameters": {"Value": "33Ω", "Tolerance": "1%"}, "reference_price": None}
    missing = catalog._preview_changes(item, candidate, False)
    assert missing == {"parameters": {"before": {}, "after": {"精度": "1%"}}}
    candidate["parameters"]["Value"] = "47Ω"
    review = catalog._preview_changes(item, candidate, True)
    assert review["parameters"]["before"] == {"阻值": "33R"}
    assert "参数：备注字段：原值\r\n" in catalog.merge_groups(item["description"], {"parameters": {"阻值": "47Ω"}})
    assert not catalog._same_parameter("阻值", "1mΩ", "1MΩ")


def test_local_model_lookup_requires_unique_brand_and_package(monkeypatch):
    data = {"code": 0, "result": {"productList": [
        {"number": "C123", "mpn": "RC0603FR-0733RL", "manufacturer": "YAGEO(国巨)", "package": "0603"},
        {"number": "C124", "mpn": "RC0603FR-0733RL", "manufacturer": "OTHER", "package": "0603"},
    ]}}
    monkeypatch.setattr(catalog, "_fetch_catalog", lambda keyword, domestic: data)
    candidate = catalog.lookup_model("RC0603FR-0733RL", "国巨", "0603", False)
    assert candidate["match_brand"] == "YAGEO(国巨)"
    assert catalog.lookup_model("RC0603FR-0733RL", "国巨", "0402", False) is None


def test_conflicting_saved_aliases_are_never_treated_as_missing():
    item = {"category": "电阻", "package_name": "0603",
            "description": "参数：Resistance：33Ω\n参数：阻值：47Ω\n参数：备注字段：原值"}
    candidate = {"category": "电阻", "package_name": "0603",
                 "parameters": {"Value": "22Ω", "Tolerance": "1%"}, "reference_price": None}
    expected = {"parameters": {"before": {}, "after": {"精度": "1%"}}}
    assert catalog._preview_changes(item, candidate, False) == expected
    assert catalog._preview_changes(item, candidate, True) == expected
    assert catalog._identity_conflicts(item, candidate)


def test_full_account_counts_zero_stock_and_requires_confirmation_for_local_sku(tmp_path, monkeypatch):
    monkeypatch.setattr(catalog, "lookup_catalog", lambda sku: catalog.parse_catalog(sku, PUBLIC))
    with client_for(tmp_path, monkeypatch) as client:
        seed(client)
        created = client.post("/admin-api/components", headers=AUTH, json={
            "sku": "LOCAL-33", "name": "我的名称", "category": "自定义", "package_name": "0603",
            "description": "型号：RC0603FR-0733RL\n品牌：YAGEO", "min_stock": 0, "location_id": "A",
            "request_id": "local-model-create",
        })
        assert created.status_code == 201, created.text
        candidate = {"category": "电阻", "package_name": "0603", "parameters": {"Value": "33Ω"},
                     "reference_price": None, "match_model": "RC0603FR-0733RL", "match_brand": "YAGEO",
                     "source_url": "https://item.szlcsc.com/1.html"}
        monkeypatch.setattr(catalog, "lookup_model", lambda *args: candidate)
        job_id = start(client)
        job = done(client, job_id)
        assert job["total"] == 2 and job["ready"] == 2 and job["mode"] == "missing"
        rows = client.get(f"{ROOT}/{job_id}/rows?status=ready", headers=AUTH).json()
        row = next(item for item in rows["items"] if item["sku"] == "LOCAL-33")
        assert row["match_source"] == "mpn" and row["match_model"] == candidate["match_model"]
        assert row["changes"] == {"parameters": {"before": {}, "after": {"阻值": "33Ω"}}}
        selection = {"component_id": row["id"], "fields": ["parameters"], "request_id": "local-model-apply"}
        applied = client.post(f"{ROOT}/{job_id}/apply", headers=AUTH, json={"items": [selection]})
        assert applied.json()["summary"]["failed"] == 1
        selection["confirm_match"] = True
        applied = client.post(f"{ROOT}/{job_id}/apply", headers=AUTH, json={"items": [selection]})
        assert applied.json()["summary"]["updated"] == 1
        item = client.get(f"/admin-api/components/{row['id']}", headers=AUTH).json()
        assert item["sku"] == "LOCAL-33" and item["quantity"] == 0
        assert "参数：阻值：33Ω" in item["description"]


def test_rate_limit_stops_requests_and_keeps_rows_retryable(monkeypatch):
    requests = []
    def limited(sku):
        requests.append(sku)
        raise HTTPError("https://pro.lceda.cn", 429, "Too Many Requests", {}, None)
    monkeypatch.setattr(catalog, "lookup_catalog", limited)
    item = {"category": "", "package_name": "", "status": "pending"}
    job = EnrichmentJob("id", "admin", "unused", [dict(item, sku="C1"), dict(item, sku="C2")], preview=True)
    catalog.scan_preview(job)
    assert requests == ["C1"]
    assert catalog._snapshot(job)["reasons"] == {"upstream_rate_limited": 2}
