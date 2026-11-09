from __future__ import annotations

from pathlib import Path
from threading import Event
import time

from fastapi.testclient import TestClient

from app import part_lookup
from app.admin import spec_enrichment
from app.config import get_settings
from app.database import _connect
from app.main import create_app
from app.schemas import LcscLookupResponse, PartLookupResponse


HEADERS = {"Authorization": "Bearer test-token"}
BASE = "/admin-api/components/spec-enrichment"


def _client(tmp_path: Path, monkeypatch, *, enabled: bool = True) -> TestClient:
    monkeypatch.setenv("API_TOKEN", "test-token")
    monkeypatch.setenv("DATABASE_PATH", str(tmp_path / "inventory.db"))
    monkeypatch.setenv("CONFIG_PATH", str(tmp_path / "config.json"))
    monkeypatch.setenv("LOG_DIR", str(tmp_path / "logs"))
    monkeypatch.setenv("MQTT_ENABLED", "false")
    monkeypatch.setenv("WEB_INVENTORY_ENABLED", str(enabled).lower())
    monkeypatch.setenv("ENABLE_WEB_FALLBACK_RESOLVERS", "false")
    get_settings.cache_clear()
    return TestClient(create_app())


def _component(client: TestClient, sku: str, name: str, description: str) -> dict:
    location = client.post(
        "/admin-api/storage-locations", headers=HEADERS,
        json={"request_id": "location", "id": "A", "name": "Shelf A"},
    )
    assert location.status_code in {201, 409}
    response = client.post(
        "/admin-api/components", headers=HEADERS,
        json={
            "request_id": f"create-{sku}", "sku": sku, "name": name,
            "category": "电容", "package_name": "1206", "description": description,
            "min_stock": 0, "location_id": "A",
        },
    )
    assert response.status_code == 201, response.text
    return response.json()


def _done(client: TestClient, job_id: str) -> dict:
    for _ in range(200):
        response = client.get(f"{BASE}/jobs/{job_id}", headers=HEADERS)
        assert response.status_code == 200, response.text
        job = response.json()
        if job["state"] in {"completed", "cancelled"}:
            return job
        time.sleep(0.01)
    raise AssertionError("enrichment job did not finish")


def test_enrichment_adds_explicit_description_values_without_changing_inventory(
    tmp_path: Path, monkeypatch,
) -> None:
    with _client(tmp_path, monkeypatch) as client:
        before = _component(client, "C22036", "TAJB476K010RNJ", "私人备注：保留原样")
        inbound = client.post(
            f"/admin-api/components/{before['id']}/movements", headers=HEADERS,
            json={
                "request_id": "inbound-c22036", "expected_updated_at": before["updated_at"],
                "movement_type": "inbound", "quantity": 7, "reason": "received",
                "location_id": "A",
            },
        )
        assert inbound.status_code == 200, inbound.text
        before = inbound.json()
        before_pull = client.get("/sync/pull", headers=HEADERS).json()
        before_cursor = before_pull["sync_cursor"]
        before_movements = before_pull["stock_movements"]
        with _connect(get_settings().database_path) as connection:
            before_revision = connection.execute(
                "SELECT sync_revision FROM components WHERE id = ?", (before["id"],)
            ).fetchone()["sync_revision"]
        monkeypatch.setattr(spec_enrichment, "lookup_explicit_sku_metadata", lambda **kwargs:
            PartLookupResponse(
                found=True, sku="C22036", source="lcsc_public_web",
                name="TAJB476K010RNJ", mpn="TAJB476K010RNJ",
                description="47uF ±10% 10V Tantalum Capacitor",
            ))
        preview = client.get(f"{BASE}/candidates", headers=HEADERS).json()
        assert preview == {"total": 1, "items": [{
            "id": before["id"], "sku": "C22036", "name": "TAJB476K010RNJ",
        }]}

        started = client.post(f"{BASE}/jobs", headers=HEADERS, json={})
        assert started.status_code == 202, started.text
        job = _done(client, started.json()["id"])
        assert (job["total"], job["updated"], job["failed"]) == (1, 1, 0)
        after = client.get(f"/admin-api/components/{before['id']}", headers=HEADERS).json()
        assert after["name"] == before["name"]
        assert after["quantity"] == before["quantity"]
        assert after["location"] == before["location"]
        assert after["allocations"] == before["allocations"] == [
            {"location_id": "A", "quantity": 7},
        ]
        assert after["description"].startswith("私人备注：保留原样\n")
        assert "参数·容量：47uF" in after["description"]
        assert "参数·精度：±10%" in after["description"]
        assert "参数·耐压：10V" in after["description"]
        assert after["updated_at"] != before["updated_at"]
        with _connect(get_settings().database_path) as connection:
            row = connection.execute(
                "SELECT sync_revision FROM components WHERE id = ?", (before["id"],)
            ).fetchone()
            assert row["sync_revision"] > before_revision
        delta = client.get(
            "/sync/pull", headers=HEADERS, params={"cursor": before_cursor},
        ).json()
        assert delta["sync_cursor"] > before_cursor
        assert len(delta["components"]) == 1
        assert delta["components"][0]["id"] == before["id"]
        assert "参数·容量：47uF" in delta["components"][0]["description"]
        assert delta["stock_movements"] == []
        assert client.get("/sync/pull", headers=HEADERS).json()["stock_movements"] == before_movements
        assert client.get(f"{BASE}/candidates", headers=HEADERS).json()["total"] == 0


def test_enrichment_skips_without_explicit_values_and_rejects_model_tokens(
    tmp_path: Path, monkeypatch,
) -> None:
    assert spec_enrichment._explicit_description_parameters(
        "TAJB476K010RNJ_47uFX10V", "Capacitor",
    ) == {}
    with _client(tmp_path, monkeypatch) as client:
        _component(client, "C222", "TAJB476K010RNJ", "private note")
        monkeypatch.setattr(spec_enrichment, "lookup_explicit_sku_metadata", lambda **kwargs:
            PartLookupResponse(found=True, sku="C222", description="TAJB476K010RNJ"))
        started = client.post(f"{BASE}/jobs", headers=HEADERS, json={})
        job = _done(client, started.json()["id"])
        assert (job["updated"], job["skipped"]) == (0, 1)
        assert "explicit parameters" in job["items"][0]["reason"]


def test_enrichment_detects_concurrent_edit_and_can_retry(
    tmp_path: Path, monkeypatch,
) -> None:
    with _client(tmp_path, monkeypatch) as client:
        before = _component(client, "C333", "Capacitor", "my note")
        attempts = 0

        def lookup(**kwargs):
            nonlocal attempts
            attempts += 1
            if attempts == 1:
                with _connect(get_settings().database_path) as connection:
                    connection.execute(
                        "UPDATE components SET description = ?, updated_at = ? WHERE id = ?",
                        ("concurrent note", "2026-09-27T12:00:00Z", before["id"]),
                    )
            return PartLookupResponse(
                found=True, sku="C333", parameters={"Capacitance": "22pF"},
            )

        monkeypatch.setattr(spec_enrichment, "lookup_explicit_sku_metadata", lookup)
        first = client.post(f"{BASE}/jobs", headers=HEADERS, json={}).json()
        failed = _done(client, first["id"])
        assert failed["failed"] == 1
        detail = client.get(f"/admin-api/components/{before['id']}", headers=HEADERS).json()
        assert detail["description"] == "concurrent note"
        with monkeypatch.context() as patch:
            def busy(*args, **kwargs):
                raise ValueError("An enrichment job is already running for this account.")

            patch.setattr(client.app.state.spec_enrichment_jobs, "start", busy)
            assert client.post(
                f"{BASE}/jobs/{first['id']}/retry", headers=HEADERS,
            ).status_code == 409
        retried = client.post(f"{BASE}/jobs/{first['id']}/retry", headers=HEADERS)
        assert retried.status_code == 202, retried.text
        assert retried.json()["id"] != first["id"]
        assert _done(client, retried.json()["id"])["updated"] == 1


def test_enrichment_uses_catalog_category_when_existing_category_is_unknown(
    tmp_path: Path, monkeypatch,
) -> None:
    with _client(tmp_path, monkeypatch) as client:
        component = _component(client, "C777", "Capacitor", "my note")
        with _connect(get_settings().database_path) as connection:
            connection.execute(
                "UPDATE components SET category = '未分类' WHERE id = ?", (component["id"],)
            )
        monkeypatch.setattr(spec_enrichment, "lookup_explicit_sku_metadata", lambda **kwargs:
            PartLookupResponse(
                found=True, sku="C777", category="Ceramic Capacitor",
                description="22pF ±5% 50V Ceramic Capacitor",
            ))
        job = client.post(f"{BASE}/jobs", headers=HEADERS, json={}).json()
        assert _done(client, job["id"])["updated"] == 1
        detail = client.get(f"/admin-api/components/{component['id']}", headers=HEADERS).json()
        assert detail["category"] == "未分类"
        assert "参数·容量：22pF" in detail["description"]
        assert "参数·耐压：50V" in detail["description"]


def test_enrichment_uses_public_sku_detail_with_default_lookup_settings(
    tmp_path: Path, monkeypatch,
) -> None:
    with _client(tmp_path, monkeypatch) as client:
        component = _component(client, "C888", "TAJB476K010RNJ", "old note")
        settings = get_settings()
        assert not settings.enable_web_fallback_resolvers
        calls: list[str] = []

        def public_detail(sku: str, matched_by: str):
            calls.append(sku)
            assert matched_by == "sku"
            return LcscLookupResponse(
                found=True, source="lcsc_public_web", sku=sku,
                description="47uF ±10% 10V Tantalum Capacitor",
                category="Capacitor",
            )

        monkeypatch.setattr(part_lookup, "_lookup_public_web_detail", public_detail)
        started = client.post(f"{BASE}/jobs", headers=HEADERS, json={})
        assert started.status_code == 202
        assert _done(client, started.json()["id"])["updated"] == 1
        assert calls == ["C888"]
        detail = client.get(f"/admin-api/components/{component['id']}", headers=HEADERS).json()
        assert "参数·容量：47uF" in detail["description"]


def test_enrichment_public_network_failure_is_retryable_failed_item(
    tmp_path: Path, monkeypatch,
) -> None:
    with _client(tmp_path, monkeypatch) as client:
        _component(client, "C889", "Cap", "old note")

        def public_failure(sku: str, matched_by: str):
            raise OSError("network unavailable")

        monkeypatch.setattr(part_lookup, "_lookup_public_web_detail", public_failure)
        started = client.post(f"{BASE}/jobs", headers=HEADERS, json={})
        job = _done(client, started.json()["id"])
        assert (job["updated"], job["skipped"], job["failed"]) == (0, 0, 1)
        assert "Public LCSC lookup failed" in job["items"][0]["reason"]


def test_enrichment_gate_and_account_isolation(tmp_path: Path, monkeypatch) -> None:
    with _client(tmp_path, monkeypatch, enabled=False) as client:
        assert client.get(f"{BASE}/candidates", headers=HEADERS).status_code == 200
        assert client.post(f"{BASE}/jobs", headers=HEADERS, json={}).status_code == 403
        assert client.get(f"{BASE}/candidates").status_code == 401


def test_enrichment_candidate_preview_is_bounded(tmp_path: Path, monkeypatch) -> None:
    with _client(tmp_path, monkeypatch) as client:
        with _connect(get_settings().database_path) as connection:
            connection.executemany(
                "INSERT INTO components(id,sku,name,category,package_name,location,"
                "description,quantity,min_stock,updated_at,deleted) "
                "VALUES(?,?,?,?,?,?,?,?,?,?,0)",
                [(f"id-{i}", f"C{i + 1000}", f"Part {i}", "电阻", "0603", "A",
                  "", 0, 0, "2026-09-27T00:00:00Z") for i in range(60)],
            )
        preview = client.get(f"{BASE}/candidates", headers=HEADERS).json()
        assert preview["total"] == 60
        assert len(preview["items"]) == 50


def test_enrichment_cancel_then_retry_pending_items(tmp_path: Path, monkeypatch) -> None:
    entered, release = Event(), Event()
    with _client(tmp_path, monkeypatch) as client:
        _component(client, "C444", "Cap A", "first")
        _component(client, "C445", "Cap B", "second")

        def lookup(**kwargs):
            entered.set()
            assert release.wait(2)
            return PartLookupResponse(
                found=True, sku=kwargs["sku"],
                parameters={"Capacitance": "22pF"},
            )

        monkeypatch.setattr(spec_enrichment, "lookup_explicit_sku_metadata", lookup)
        started = client.post(f"{BASE}/jobs", headers=HEADERS, json={})
        assert started.status_code == 202
        assert entered.wait(2)
        active = client.get(f"{BASE}/jobs/active", headers=HEADERS).json()
        assert active["id"] == started.json()["id"]
        assert client.post(f"{BASE}/jobs", headers=HEADERS, json={}).status_code == 409
        assert client.post(
            f"{BASE}/jobs/{started.json()['id']}/cancel", headers=HEADERS,
        ).status_code == 200
        release.set()
        stopped = _done(client, started.json()["id"])
        assert stopped["state"] == "cancelled"
        assert stopped["processed"] == 1
        assert client.get(f"{BASE}/jobs/active", headers=HEADERS).json() is None
        retried = client.post(
            f"{BASE}/jobs/{started.json()['id']}/retry", headers=HEADERS,
        )
        assert retried.status_code == 202
        assert _done(client, retried.json()["id"])["updated"] == 1
        assert client.get(f"{BASE}/jobs/{started.json()['id']}", headers=HEADERS).status_code == 404
        assert client.post(
            f"{BASE}/jobs/{retried.json()['id']}/clear", headers=HEADERS,
        ).json() == {"cleared": True}
        assert client.get(f"{BASE}/jobs/{retried.json()['id']}", headers=HEADERS).status_code == 404


def test_enrichment_job_is_private_to_current_account(
    tmp_path: Path, monkeypatch,
) -> None:
    with _client(tmp_path, monkeypatch) as client:
        _component(client, "C555", "Admin cap", "admin note")
        account = client.post(
            "/admin-api/accounts", headers=HEADERS, json={"name": "Other"},
        )
        assert account.status_code == 201
        other_headers = {"Authorization": f"Bearer {account.json()['api_token']}"}
        assert client.get(f"{BASE}/candidates", headers=other_headers).json()["total"] == 0
        monkeypatch.setattr(spec_enrichment, "lookup_explicit_sku_metadata", lambda **kwargs:
            PartLookupResponse(found=True, sku="C555", parameters={"Capacitance": "22pF"}))
        job_id = client.post(f"{BASE}/jobs", headers=HEADERS, json={}).json()["id"]
        assert client.get(f"{BASE}/jobs/{job_id}", headers=other_headers).status_code == 404
        assert client.post(
            f"{BASE}/jobs/{job_id}/cancel", headers=other_headers,
        ).status_code == 404
        assert _done(client, job_id)["updated"] == 1

