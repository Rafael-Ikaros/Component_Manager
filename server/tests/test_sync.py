from __future__ import annotations

from pathlib import Path

from fastapi.testclient import TestClient

from app.build_info import BUILD_REVISION, BUILD_VERSION
from app.config import get_settings
from app.main import create_app


def test_health_endpoint() -> None:
    with TestClient(create_app()) as client:
        response = client.get("/health")

    assert response.status_code == 200
    assert response.json()["status"] == "ok"
    assert response.json()["version"] == BUILD_VERSION
    assert response.json()["revision"] == BUILD_REVISION


def test_admin_api_requires_token(tmp_path: Path, monkeypatch) -> None:
    _configure_env(tmp_path, monkeypatch)

    with TestClient(create_app()) as client:
        response = client.get("/admin-api/dashboard")

    assert response.status_code == 401


def test_sync_requires_token(tmp_path: Path, monkeypatch) -> None:
    _configure_env(tmp_path, monkeypatch)

    with TestClient(create_app()) as client:
        response = client.post("/sync/push", json={"device_id": "desktop-1"})

    assert response.status_code == 401


def test_admin_api_allows_configured_origin(tmp_path: Path, monkeypatch) -> None:
    _configure_env(tmp_path, monkeypatch)

    with TestClient(create_app()) as client:
        response = client.options(
            "/admin-api/dashboard",
            headers={
                "Origin": "http://localhost:5173",
                "Access-Control-Request-Method": "GET",
            },
        )

    assert response.status_code == 200
    assert response.headers["access-control-allow-origin"] == "http://localhost:5173"


def test_push_then_pull_round_trip(tmp_path: Path, monkeypatch) -> None:
    _configure_env(tmp_path, monkeypatch)

    component = _component_payload()
    stock_movement = _movement_payload()

    with TestClient(create_app()) as client:
        push_response = client.post(
            "/sync/push",
            headers={"Authorization": "Bearer test-token"},
            json={
                "device_id": "desktop-1",
                "components": [component],
                "stock_movements": [stock_movement],
            },
        )
        pull_response = client.get(
            "/sync/pull",
            headers={"Authorization": "Bearer test-token"},
            params={"since": "2026-05-07T00:00:00Z"},
        )

    assert push_response.status_code == 200
    assert pull_response.status_code == 200
    body = pull_response.json()
    assert len(body["components"]) == 1
    assert len(body["stock_movements"]) == 1
    assert body["components"][0]["sku"] == "ATMEGA328P-AU"


def test_admin_api_returns_snapshot_data(tmp_path: Path, monkeypatch) -> None:
    _configure_env(tmp_path, monkeypatch)

    with TestClient(create_app()) as client:
        client.post(
            "/sync/push",
            headers={"Authorization": "Bearer test-token"},
            json={
                "device_id": "desktop-1",
                "components": [_component_payload()],
                "stock_movements": [_movement_payload()],
            },
        )
        dashboard_response = client.get(
            "/admin-api/dashboard",
            headers={"Authorization": "Bearer test-token"},
        )
        inventory_response = client.get(
            "/admin-api/inventory",
            headers={"Authorization": "Bearer test-token"},
        )
        sync_response = client.get(
            "/admin-api/sync",
            headers={"Authorization": "Bearer test-token"},
        )
        settings_response = client.get(
            "/admin-api/settings",
            headers={"Authorization": "Bearer test-token"},
        )

    assert dashboard_response.status_code == 200
    assert inventory_response.status_code == 200
    assert sync_response.status_code == 200
    assert settings_response.status_code == 200
    assert dashboard_response.json()["metrics"]["component_count"] == 1
    assert inventory_response.json()["recent_components"][0]["sku"] == "ATMEGA328P-AU"
    assert sync_response.json()["recent_movements"][0]["movement_type"] == "inbound"
    assert settings_response.json()["runtime_configuration"][0]["label"] == "App name"


def test_lcsc_lookup_requires_query(tmp_path: Path, monkeypatch) -> None:
    _configure_env(tmp_path, monkeypatch)

    with TestClient(create_app()) as client:
        response = client.get(
            "/admin-api/lcsc/lookup",
            headers={"Authorization": "Bearer test-token"},
        )

    assert response.status_code == 422


def test_lcsc_lookup_returns_proxy_result(tmp_path: Path, monkeypatch) -> None:
    _configure_env(tmp_path, monkeypatch)

    def fetch(request, timeout):
        assert request.full_url == "https://www.lcsc.com/product-detail/C30926.html"
        return _HtmlResponse('''
          <script type="application/ld+json">
            {"@type":"Product","sku":"C30926","mpn":"0603B104K500NT",
             "packageName":"0603","description":"100nF ±10% 50V",
             "additionalProperty":[{"name":"Voltage Rating","value":"50V"}]}
          </script>
        ''')

    monkeypatch.setattr("app.part_lookup.urlopen", fetch)
    with TestClient(create_app()) as client:
        denied = client.get("/admin-api/lcsc/lookup", params={"sku": "C30926"})
        assert denied.status_code == 401
        response = client.get(
            "/admin-api/lcsc/lookup",
            headers={"Authorization": "Bearer test-token"},
            params={"sku": "C30926"},
        )

    assert response.status_code == 200
    body = response.json()
    assert body["found"] is True
    assert body["source"] == "lcsc_public_web"
    assert body["package_name"] == "0603"
    assert body["matched_by"] == "sku"
    assert body["description"] == "100nF ±10% 50V"
    assert body["parameters"]["Voltage Rating"] == "50V"


def test_lcsc_lookup_reports_timeout(tmp_path: Path, monkeypatch) -> None:
    _configure_env(tmp_path, monkeypatch)

    def fetch(request, timeout):
        raise TimeoutError("upstream timed out")

    monkeypatch.setattr("app.part_lookup.urlopen", fetch)
    with TestClient(create_app()) as client:
        response = client.get(
            "/admin-api/lcsc/lookup",
            headers={"Authorization": "Bearer test-token"},
            params={"sku": "C1234"},
        )

    assert response.status_code == 502


def test_lcsc_lookup_rejects_a_different_product(tmp_path: Path, monkeypatch) -> None:
    _configure_env(tmp_path, monkeypatch)
    monkeypatch.setattr(
        "app.part_lookup.urlopen",
        lambda request, timeout: _HtmlResponse(
            '<script type="application/ld+json">{"@type":"Product","sku":"C999","mpn":"Wrong"}</script>'
        ),
    )
    with TestClient(create_app()) as client:
        response = client.get(
            "/admin-api/lcsc/lookup",
            headers={"Authorization": "Bearer test-token"},
            params={"sku": "C1234"},
        )

    assert response.status_code == 200
    assert response.json()["found"] is False


def test_part_lookup_requires_query(tmp_path: Path, monkeypatch) -> None:
    _configure_env(tmp_path, monkeypatch)

    with TestClient(create_app()) as client:
        response = client.get(
            "/admin-api/part-lookup",
            headers={"Authorization": "Bearer test-token"},
        )

    assert response.status_code == 422


def test_part_lookup_uses_local_rules_with_public_fallback_disabled(
    tmp_path: Path,
    monkeypatch,
) -> None:
    _configure_env(tmp_path, monkeypatch)

    with TestClient(create_app()) as client:
        response = client.get(
            "/admin-api/part-lookup",
            headers={"Authorization": "Bearer test-token"},
            params={"sku": "C30926", "mpn": "0603B104K500NT"},
        )

    assert response.status_code == 200
    body = response.json()
    assert body["found"] is True
    assert body["source"] == "local_rules"
    assert body["package_name"] == "0603"
    assert body["category"] == "Capacitor / MLCC"
    assert body["model_family"] == "MLCC"
    assert body["rule_version"] == "2026.05.10.2"


def test_part_lookup_uses_public_web_fallback_when_enabled(
    tmp_path: Path,
    monkeypatch,
) -> None:
    _configure_env(
        tmp_path,
        monkeypatch,
        enable_web_fallback_resolvers="true",
    )

    html = """
    <html>
      <head>
        <title>100nF Ceramic Capacitor - LCSC Electronics</title>
        <script type="application/ld+json">
          {
            "@context": "https://schema.org",
            "@type": "Product",
            "sku": "C30926",
            "mpn": "0603B104K500NT",
            "name": "100nF Ceramic Capacitor",
            "packageName": "0603",
            "description": "100nF ±10% 50V",
            "paramLinkedMap": {"Capacitance": "100nF", "Voltage Rating": "50V"},
            "brand": {
              "@type": "Brand",
              "name": "FH"
            }
          }
        </script>
        <script type="application/ld+json">
          {
            "@context": "https://schema.org",
            "@type": "BreadcrumbList",
            "itemListElement": [
              {"position": 1, "name": "Capacitors"},
              {"position": 2, "name": "Ceramic Capacitors"}
            ]
          }
        </script>
      </head>
    </html>
    """.strip()

    monkeypatch.setattr(
        "app.part_lookup.urlopen",
        lambda request, timeout=12: _HtmlResponse(html),
    )

    with TestClient(create_app()) as client:
        response = client.get(
            "/admin-api/part-lookup",
            headers={"Authorization": "Bearer test-token"},
            params={"sku": "C30926", "mpn": "0603B104K500NT"},
        )

    assert response.status_code == 200
    body = response.json()
    assert body["found"] is True
    assert body["source"] == "lcsc_public_web+local_rules"
    assert body["name"] == "FH 0603B104K500NT"
    assert body["description"] == "100nF ±10% 50V"
    assert body["parameters"]["Capacitance"] == "100nF"
    assert body["brand"] == "FH"
    assert body["package_name"] == "0603"
    assert body["category_path"] == "Capacitors / Ceramic Capacitors"


def test_recognition_rules_meta_endpoint(tmp_path: Path, monkeypatch) -> None:
    _configure_env(tmp_path, monkeypatch)

    with TestClient(create_app()) as client:
        response = client.get(
            "/admin-api/recognition-rules/meta",
            headers={"Authorization": "Bearer test-token"},
        )

    assert response.status_code == 200
    body = response.json()
    assert body["version"] == "2026.05.10.2"
    assert body["source"] == "bundled"
    assert body["refreshed"] is False


def test_recognition_rules_refresh_without_remote_url(
    tmp_path: Path,
    monkeypatch,
) -> None:
    _configure_env(tmp_path, monkeypatch)

    with TestClient(create_app()) as client:
        response = client.post(
            "/admin-api/recognition-rules/refresh",
            headers={"Authorization": "Bearer test-token"},
        )

    assert response.status_code == 200
    body = response.json()
    assert body["version"] == "2026.05.10.2"
    assert body["refreshed"] is False


def test_older_component_push_is_ignored(tmp_path: Path, monkeypatch) -> None:
    _configure_env(tmp_path, monkeypatch)

    newer_component = _component_payload(
        quantity=24,
        updated_at="2026-05-07T06:00:00Z",
    )
    older_component = _component_payload(
        name="Older MCU name",
        quantity=3,
        updated_at="2026-05-07T05:00:00Z",
    )

    with TestClient(create_app()) as client:
        first_push = client.post(
            "/sync/push",
            headers={"Authorization": "Bearer test-token"},
            json={"device_id": "desktop-1", "components": [newer_component]},
        )
        second_push = client.post(
            "/sync/push",
            headers={"Authorization": "Bearer test-token"},
            json={"device_id": "desktop-1", "components": [older_component]},
        )
        pull_response = client.get(
            "/sync/pull",
            headers={"Authorization": "Bearer test-token"},
        )

    assert first_push.status_code == 200
    assert second_push.status_code == 200
    body = pull_response.json()
    assert body["components"][0]["name"] == "MCU"
    assert body["components"][0]["quantity"] == 24


def test_duplicate_active_sku_returns_conflict(tmp_path: Path, monkeypatch) -> None:
    _configure_env(tmp_path, monkeypatch)

    first_component = _component_payload(id="cmp-1", sku="NE555P")
    conflicting_component = _component_payload(id="cmp-2", sku="NE555P")

    with TestClient(create_app()) as client:
        first_push = client.post(
            "/sync/push",
            headers={"Authorization": "Bearer test-token"},
            json={"device_id": "desktop-1", "components": [first_component]},
        )
        second_push = client.post(
            "/sync/push",
            headers={"Authorization": "Bearer test-token"},
            json={"device_id": "desktop-1", "components": [conflicting_component]},
        )

    assert first_push.status_code == 200
    assert second_push.status_code == 409


def test_soft_deleted_component_is_returned_in_pull(tmp_path: Path, monkeypatch) -> None:
    _configure_env(tmp_path, monkeypatch)

    active_component = _component_payload(updated_at="2026-05-07T06:00:00Z")
    deleted_component = _component_payload(
        updated_at="2026-05-07T07:00:00Z",
        deleted=True,
    )

    with TestClient(create_app()) as client:
        client.post(
            "/sync/push",
            headers={"Authorization": "Bearer test-token"},
            json={"device_id": "desktop-1", "components": [active_component]},
        )
        delete_response = client.post(
            "/sync/push",
            headers={"Authorization": "Bearer test-token"},
            json={"device_id": "desktop-1", "components": [deleted_component]},
        )
        pull_response = client.get(
            "/sync/pull",
            headers={"Authorization": "Bearer test-token"},
            params={"since": "2026-05-07T06:30:00Z"},
        )

    assert delete_response.status_code == 200
    body = pull_response.json()
    assert len(body["components"]) == 1
    assert body["components"][0]["deleted"] is True


def _configure_env(
    tmp_path: Path,
    monkeypatch,
    *,
    enable_web_fallback_resolvers: str = "false",
) -> None:
    monkeypatch.setenv("API_TOKEN", "test-token")
    monkeypatch.setenv("DATABASE_PATH", str(tmp_path / "sync.db"))
    monkeypatch.setenv(
        "ADMIN_WEB_ORIGINS",
        "http://localhost:5173,http://127.0.0.1:5173,http://localhost:8081",
    )
    monkeypatch.setenv("LCSC_LOOKUP_CACHE_TTL_SECONDS", "0")
    monkeypatch.setenv("IMPORT_RULES_REMOTE_URL", "")
    monkeypatch.setenv("IMPORT_RULES_REFRESH_HOURS", "24")
    monkeypatch.setenv(
        "ENABLE_WEB_FALLBACK_RESOLVERS",
        enable_web_fallback_resolvers,
    )
    get_settings.cache_clear()


class _HtmlResponse:
    def __init__(self, body: str) -> None:
        self.body = body.encode("utf-8")
        self.status = 200

    def read(self) -> bytes:
        return self.body

    def __enter__(self) -> "_HtmlResponse":
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        return None


def _component_payload(**overrides: object) -> dict[str, object]:
    payload: dict[str, object] = {
        "id": "cmp-1",
        "sku": "ATMEGA328P-AU",
        "name": "MCU",
        "category": "Microcontroller",
        "package_name": "TQFP-32",
        "location": "Drawer A1",
        "description": "Main controller",
        "quantity": 24,
        "min_stock": 5,
        "updated_at": "2026-05-07T06:00:00Z",
        "deleted": False,
    }
    payload.update(overrides)
    return payload


def _movement_payload(**overrides: object) -> dict[str, object]:
    payload: dict[str, object] = {
        "id": "mov-1",
        "component_id": "cmp-1",
        "movement_type": "inbound",
        "quantity": 24,
        "reason": "Initial stock",
        "note": "",
        "happened_at": "2026-05-07T06:00:00Z",
        "updated_at": "2026-05-07T06:00:00Z",
        "deleted": False,
    }
    payload.update(overrides)
    return payload
