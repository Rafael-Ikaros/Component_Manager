from __future__ import annotations

import sqlite3
from dataclasses import replace

from fastapi import APIRouter, Depends, HTTPException, Query, Request, status
from pydantic import BaseModel, Field
from typing import Literal

from ..accounts import Account
from ..auth import require_token, require_admin
from ..config import Settings, get_settings
from ..build_info import BUILD_REVISION, BUILD_VERSION
from ..database import get_db
from ..lcsc import LookupConfigurationError, LookupRequestError, lookup_lcsc_product
from ..part_lookup import (
    RecognitionRulesRefreshError,
    get_recognition_rules_meta,
    lookup_part_metadata,
    refresh_recognition_rules,
)
from ..schemas import (
    AdminComponentDetail,
    AdminComponentCreate,
    AdminComponentDelete,
    AdminComponentDeleteResult,
    AdminComponentUpdate,
    AdminComponentListResponse,
    AdminDashboardResponse,
    AdminInventoryResponse,
    AdminKeyValueItem,
    AdminMetricSnapshot,
    AdminSettingsResponse,
    AdminStockMovementCreate,
    AdminStorageLocation,
    AdminStorageLocationCreate,
    AdminSyncResponse,
    LcscLookupResponse,
    PartLookupResponse,
    RecognitionRulesMetaResponse,
)
from .data import (
    AdminSnapshot,
    load_admin_component,
    load_admin_components,
    load_admin_snapshot,
)
from .operations import (
    AdminOperationConflict,
    AdminOperationNotFound,
    create_component,
    create_storage_location,
    delete_component,
    list_storage_locations,
    record_stock_movement,
    update_component,
)
from .spec_enrichment import EnrichmentJobs, candidates
from .order_metadata import router as order_metadata_router
from ..sync_observability import (
    append_resolution, list_audit, list_conflicts, list_devices,
)

router = APIRouter(
    prefix="/admin-api",
    tags=["admin"],
    dependencies=[Depends(require_token)],
)
router.include_router(order_metadata_router)


@router.get("/dashboard", response_model=AdminDashboardResponse)
def get_dashboard(
    settings: Settings = Depends(get_settings),
    connection: sqlite3.Connection = Depends(get_db),
    account: Account = Depends(require_token),
) -> AdminDashboardResponse:
    snapshot = load_admin_snapshot(connection, settings, role=account.role)
    return AdminDashboardResponse(
        metrics=_metrics(snapshot),
        recent_components=snapshot.recent_components,
        sync_notes=snapshot.sync_notes,
    )


@router.get("/inventory", response_model=AdminInventoryResponse)
def get_inventory(
    settings: Settings = Depends(get_settings),
    connection: sqlite3.Connection = Depends(get_db),
    account: Account = Depends(require_token),
) -> AdminInventoryResponse:
    snapshot = load_admin_snapshot(connection, settings, role=account.role)
    return AdminInventoryResponse(
        metrics=_metrics(snapshot),
        low_stock_components=snapshot.low_stock_components,
        recent_components=snapshot.recent_components,
        inventory_rules=[
            AdminKeyValueItem(
                label="Conflict mode",
                value="Managed inventory checks base_updated_at; legacy snapshots use last-write-wins on updated_at",
            ),
            AdminKeyValueItem(
                label="Soft delete",
                value="Enabled for synchronized rows",
            ),
            AdminKeyValueItem(
                label="Low-stock trigger",
                value="quantity <= min_stock",
            ),
            AdminKeyValueItem(
                label="Inventory browser",
                value="Server-side read verification only",
            ),
        ],
    )


@router.get("/components", response_model=AdminComponentListResponse)
def get_components(
    q: str | None = Query(default=None, max_length=200),
    low_stock: bool | None = Query(default=None),
    page: int = Query(default=1, ge=1, le=1_000_000),
    page_size: int = Query(default=25, ge=1, le=100),
    settings: Settings = Depends(get_settings),
    connection: sqlite3.Connection = Depends(get_db),
) -> AdminComponentListResponse:
    result = load_admin_components(
        connection,
        query=q,
        low_stock=low_stock,
        page=page,
        page_size=page_size,
    )
    page_count = (result.total + result.page_size - 1) // result.page_size
    return AdminComponentListResponse(
        items=result.items,
        page=result.page,
        page_size=result.page_size,
        total=result.total,
        page_count=page_count,
    )


@router.get("/components/{component_id}", response_model=AdminComponentDetail)
def get_component_detail(
    component_id: str,
    settings: Settings = Depends(get_settings),
    connection: sqlite3.Connection = Depends(get_db),
) -> AdminComponentDetail:
    component = load_admin_component(connection, component_id)
    if component is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Component not found.",
        )
    return AdminComponentDetail.model_validate(component)


class SpecEnrichmentStart(BaseModel):
    component_ids: list[str] | None = Field(default=None, max_length=5000)


class ConflictResolution(BaseModel):
    resolution: Literal["server_kept", "client_resubmitted"]
    note: str | None = Field(default=None, max_length=1000)


@router.get("/sync/devices")
def get_sync_devices(
    connection: sqlite3.Connection = Depends(get_db),
) -> dict[str, object]:
    return {"items": list_devices(connection)}


@router.get("/sync/audit")
def get_sync_audit(
    limit: int = Query(default=100, ge=1, le=500),
    connection: sqlite3.Connection = Depends(get_db),
) -> dict[str, object]:
    return {"items": list_audit(connection, limit)}


@router.get("/sync/conflicts")
def get_sync_conflicts(
    limit: int = Query(default=100, ge=1, le=500),
    connection: sqlite3.Connection = Depends(get_db),
) -> dict[str, object]:
    return {"items": list_conflicts(connection, limit)}


@router.post("/sync/conflicts/{conflict_id}/resolve")
def post_sync_conflict_resolution(
    conflict_id: int, draft: ConflictResolution,
    connection: sqlite3.Connection = Depends(get_db),
    account: Account = Depends(require_token),
) -> dict[str, object]:
    if not append_resolution(
        connection, conflict_id, draft.resolution, draft.note, account.account_id,
    ):
        raise HTTPException(status_code=404, detail="Conflict not found.")
    return {"conflict_id": conflict_id, "resolution": draft.resolution}


@router.get("/components/spec-enrichment/candidates")
def get_spec_enrichment_candidates(
    connection: sqlite3.Connection = Depends(get_db),
) -> dict[str, object]:
    total, items = candidates(connection, limit=50)
    return {"total": total, "items": items}


@router.post("/components/spec-enrichment/jobs", status_code=status.HTTP_202_ACCEPTED)
def post_spec_enrichment_job(
    request: Request,
    draft: SpecEnrichmentStart,
    settings: Settings = Depends(get_settings),
    connection: sqlite3.Connection = Depends(get_db),
    account: Account = Depends(require_token),
) -> dict[str, object]:
    _require_web_inventory(settings)
    _, available = candidates(connection)
    if draft.component_ids is not None:
        selected = set(draft.component_ids)
        if len(selected) != len(draft.component_ids):
            raise HTTPException(status_code=422, detail="Duplicate component IDs.")
        available = [item for item in available if item["id"] in selected]
        if len(available) != len(selected):
            raise HTTPException(status_code=422, detail="Selected components are not eligible.")
    manager: EnrichmentJobs = request.app.state.spec_enrichment_jobs
    try:
        job = manager.start(account, settings, available)
    except ValueError as error:
        raise HTTPException(status_code=409, detail=str(error)) from error
    return job.snapshot()


@router.get("/components/spec-enrichment/jobs/active")
def get_active_spec_enrichment_job(
    request: Request,
    account: Account = Depends(require_token),
) -> dict[str, object] | None:
    manager: EnrichmentJobs = request.app.state.spec_enrichment_jobs
    job = manager.active(account)
    return job.snapshot() if job is not None else None


@router.get("/components/spec-enrichment/jobs/{job_id}")
def get_spec_enrichment_job(
    job_id: str,
    request: Request,
    account: Account = Depends(require_token),
) -> dict[str, object]:
    manager: EnrichmentJobs = request.app.state.spec_enrichment_jobs
    job = manager.get(job_id, account)
    if job is None:
        raise HTTPException(status_code=404, detail="Enrichment job not found.")
    return job.snapshot()


@router.post("/components/spec-enrichment/jobs/{job_id}/cancel")
def post_spec_enrichment_cancel(
    job_id: str,
    request: Request,
    settings: Settings = Depends(get_settings),
    account: Account = Depends(require_token),
) -> dict[str, object]:
    _require_web_inventory(settings)
    manager: EnrichmentJobs = request.app.state.spec_enrichment_jobs
    job = manager.get(job_id, account)
    if job is None:
        raise HTTPException(status_code=404, detail="Enrichment job not found.")
    job.cancel.set()
    return job.snapshot()


@router.post("/components/spec-enrichment/jobs/{job_id}/retry", status_code=status.HTTP_202_ACCEPTED)
def post_spec_enrichment_retry(
    job_id: str,
    request: Request,
    settings: Settings = Depends(get_settings),
    account: Account = Depends(require_token),
) -> dict[str, object]:
    _require_web_inventory(settings)
    manager: EnrichmentJobs = request.app.state.spec_enrichment_jobs
    job = manager.get(job_id, account)
    if job is None:
        raise HTTPException(status_code=404, detail="Enrichment job not found.")
    snapshot = job.snapshot()
    if snapshot["state"] not in {"completed", "cancelled"}:
        raise HTTPException(status_code=409, detail="Wait for the job to finish.")
    retry_items = [
        {"id": item["id"], "sku": item["sku"], "name": item["name"]}
        for item in job.items if item["status"] in {"failed", "pending"}
    ]
    if not retry_items:
        raise HTTPException(status_code=409, detail="No failed or pending items to retry.")
    try:
        return manager.start(account, settings, retry_items).snapshot()
    except ValueError as error:
        raise HTTPException(status_code=409, detail=str(error)) from error


@router.post("/components/spec-enrichment/jobs/{job_id}/clear")
def post_spec_enrichment_clear(
    job_id: str,
    request: Request,
    settings: Settings = Depends(get_settings),
    account: Account = Depends(require_token),
) -> dict[str, bool]:
    _require_web_inventory(settings)
    manager: EnrichmentJobs = request.app.state.spec_enrichment_jobs
    try:
        found = manager.clear(job_id, account)
    except ValueError as error:
        raise HTTPException(status_code=409, detail=str(error)) from error
    if not found:
        raise HTTPException(status_code=404, detail="Enrichment job not found.")
    return {"cleared": True}


@router.get(
    "/storage-locations",
    response_model=list[AdminStorageLocation],
)
def get_storage_locations(
    connection: sqlite3.Connection = Depends(get_db),
) -> list[AdminStorageLocation]:
    return [
        AdminStorageLocation.model_validate(item)
        for item in list_storage_locations(connection)
    ]


@router.post(
    "/storage-locations",
    response_model=AdminStorageLocation,
    status_code=status.HTTP_201_CREATED,
)
def post_storage_location(
    draft: AdminStorageLocationCreate,
    settings: Settings = Depends(get_settings),
    connection: sqlite3.Connection = Depends(get_db),
) -> AdminStorageLocation:
    _require_web_inventory(settings)
    return _run_admin_operation(
        AdminStorageLocation,
        lambda: create_storage_location(connection, draft),
    )


@router.post(
    "/components",
    response_model=AdminComponentDetail,
    status_code=status.HTTP_201_CREATED,
)
def post_component(
    draft: AdminComponentCreate,
    settings: Settings = Depends(get_settings),
    connection: sqlite3.Connection = Depends(get_db),
    account: Account = Depends(require_token),
) -> AdminComponentDetail:
    _require_web_inventory(settings)
    return _run_admin_operation(
        AdminComponentDetail,
        lambda: create_component(connection, _account_settings(settings, account), draft),
    )


@router.put(
    "/components/{component_id}",
    response_model=AdminComponentDetail,
)
def put_component(
    component_id: str,
    draft: AdminComponentUpdate,
    settings: Settings = Depends(get_settings),
    connection: sqlite3.Connection = Depends(get_db),
    account: Account = Depends(require_token),
) -> AdminComponentDetail:
    _require_web_inventory(settings)
    return _run_admin_operation(
        AdminComponentDetail,
        lambda: update_component(connection, _account_settings(settings, account), component_id, draft),
    )


@router.delete(
    "/components/{component_id}",
    response_model=AdminComponentDeleteResult,
)
def delete_component_route(
    component_id: str,
    draft: AdminComponentDelete,
    settings: Settings = Depends(get_settings),
    connection: sqlite3.Connection = Depends(get_db),
    account: Account = Depends(require_token),
) -> AdminComponentDeleteResult:
    _require_web_inventory(settings)
    return _run_admin_operation(
        AdminComponentDeleteResult,
        lambda: delete_component(
            connection, _account_settings(settings, account), component_id, draft
        ),
    )


@router.post(
    "/components/{component_id}/movements",
    response_model=AdminComponentDetail,
)
def post_component_movement(
    component_id: str,
    draft: AdminStockMovementCreate,
    settings: Settings = Depends(get_settings),
    connection: sqlite3.Connection = Depends(get_db),
    account: Account = Depends(require_token),
) -> AdminComponentDetail:
    _require_web_inventory(settings)
    return _run_admin_operation(
        AdminComponentDetail,
        lambda: record_stock_movement(connection, _account_settings(settings, account), component_id, draft),
    )


@router.get("/sync", response_model=AdminSyncResponse)
def get_sync(
    settings: Settings = Depends(get_settings),
    connection: sqlite3.Connection = Depends(get_db),
    account: Account = Depends(require_token),
) -> AdminSyncResponse:
    snapshot = load_admin_snapshot(connection, settings, role=account.role)
    return AdminSyncResponse(
        metrics=_metrics(snapshot),
        recent_movements=snapshot.recent_movements,
        sync_assumptions=[
            AdminKeyValueItem(
                label="Conflict mode",
                value="Managed inventory checks base_updated_at; legacy snapshots use last-write-wins",
            ),
            AdminKeyValueItem(label="Soft delete", value="Enabled"),
            AdminKeyValueItem(label="Device registry", value="Available with per-device push and pull activity"),
            AdminKeyValueItem(
                label="Authenticated routes",
                value="/auth/ping, /sync/push, /sync/pull, /admin-api/*",
            ),
        ],
        attention_items=_attention_items(settings, role=account.role),
    )


@router.get("/settings", response_model=AdminSettingsResponse)
def get_settings_overview(
    settings: Settings = Depends(get_settings),
    account: Account = Depends(require_token),
) -> AdminSettingsResponse:
    if account.role == "user":
        return AdminSettingsResponse(
            web_inventory_enabled=settings.web_inventory_enabled,
            runtime_configuration=[
                AdminKeyValueItem(label="App name", value=settings.app_name),
                AdminKeyValueItem(label="Server version", value=BUILD_VERSION),
                AdminKeyValueItem(label="Build revision", value=BUILD_REVISION),
            ],
            access_posture=[],
            next_backend_additions=[],
        )
    return AdminSettingsResponse(
        web_inventory_enabled=settings.web_inventory_enabled,
        runtime_configuration=[
            AdminKeyValueItem(label="App name", value=settings.app_name),
            AdminKeyValueItem(label="Server version", value=BUILD_VERSION),
            AdminKeyValueItem(label="Build revision", value=BUILD_REVISION),
            AdminKeyValueItem(label="Host", value=settings.app_host),
            AdminKeyValueItem(label="Port", value=str(settings.app_port)),
            AdminKeyValueItem(label="Database path", value=settings.database_path),
            AdminKeyValueItem(
                label="Admin web origins",
                value=", ".join(settings.admin_web_origins),
            ),
            AdminKeyValueItem(
                label="API token status",
                value=(
                    "Default token in use"
                    if settings.api_token == "change-me"
                    else "Custom token configured"
                ),
            ),
            AdminKeyValueItem(
                label="LCSC lookup status",
                value=(
                    "Configured"
                    if settings.lcsc_openapi_key and settings.lcsc_openapi_secret
                    else "Not configured"
                ),
            ),
            AdminKeyValueItem(
                label="Recognition rules remote URL",
                value=settings.import_rules_remote_url or "Not configured",
            ),
            AdminKeyValueItem(
                label="Web fallback resolvers",
                value="Enabled" if settings.enable_web_fallback_resolvers else "Disabled",
            ),
        ],
        access_posture=[
            AdminKeyValueItem(
                label="Health URL",
                value=f"http://{settings.app_host}:{settings.app_port}/health",
            ),
            AdminKeyValueItem(
                label="Auth URL",
                value=f"http://{settings.app_host}:{settings.app_port}/auth/ping",
            ),
            AdminKeyValueItem(
                label="Admin API",
                value=f"http://{settings.app_host}:{settings.app_port}/admin-api/dashboard",
            ),
            AdminKeyValueItem(
                label="Sync API",
                value=f"http://{settings.app_host}:{settings.app_port}/sync/pull",
            ),
            AdminKeyValueItem(
                label="Token risk",
                value=(
                    "Replace before deployment"
                    if settings.api_token == "change-me"
                    else "Custom token present"
                ),
            ),
        ],
        next_backend_additions=[],
    )


@router.get("/part-lookup", response_model=PartLookupResponse)
def get_part_lookup(
    sku: str | None = Query(default=None, max_length=120),
    mpn: str | None = Query(default=None, max_length=200),
    name: str | None = Query(default=None, max_length=300),
    brand: str | None = Query(default=None, max_length=200),
    package_hint: str | None = Query(default=None, max_length=120),
    source_type: str | None = Query(default=None, max_length=80),
    settings: Settings = Depends(get_settings),
) -> PartLookupResponse:
    if not any(
        value and value.strip()
        for value in (sku, mpn, name, brand, package_hint)
    ):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="At least one lookup field is required.",
        )

    return lookup_part_metadata(
        settings=settings,
        sku=sku,
        mpn=mpn,
        name=name,
        brand=brand,
        package_hint=package_hint,
        source_type=source_type,
    )


@router.get(
    "/recognition-rules/meta",
    response_model=RecognitionRulesMetaResponse,
)
def get_rules_meta(
    settings: Settings = Depends(get_settings),
) -> RecognitionRulesMetaResponse:
    return get_recognition_rules_meta(settings)


@router.post(
    "/recognition-rules/refresh",
    response_model=RecognitionRulesMetaResponse,
)
def post_rules_refresh(
    settings: Settings = Depends(get_settings),
) -> RecognitionRulesMetaResponse:
    try:
        return refresh_recognition_rules(settings)
    except RecognitionRulesRefreshError as error:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail=str(error),
        ) from error


@router.get("/lcsc/lookup", response_model=LcscLookupResponse)
def get_lcsc_lookup(
    sku: str | None = Query(default=None, max_length=120),
    mpn: str | None = Query(default=None, max_length=200),
    name: str | None = Query(default=None, max_length=300),
    settings: Settings = Depends(get_settings),
) -> LcscLookupResponse:
    if not any(value and value.strip() for value in (sku, mpn, name)):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="At least one of sku, mpn, or name is required.",
        )

    try:
        return lookup_lcsc_product(
            settings=settings,
            sku=sku,
            mpn=mpn,
            name=name,
        )
    except LookupConfigurationError as error:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail=str(error),
        ) from error
    except LookupRequestError as error:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail=str(error),
        ) from error


def _metrics(snapshot: AdminSnapshot) -> AdminMetricSnapshot:
    return AdminMetricSnapshot(
        component_count=snapshot.component_count,
        total_units=snapshot.total_units,
        low_stock_count=snapshot.low_stock_count,
        movement_count=snapshot.movement_count,
    )


def _account_settings(settings: Settings, account: Account) -> Settings:
    return settings if account.role == "admin" else replace(settings, mqtt_enabled=False)


def _attention_items(settings: Settings, *, role: str = "admin") -> list[str]:
    if settings.api_token == "change-me":
        token_item = (
            "API token is still the default value. Replace it before deployment."
        )
    else:
        token_item = "Custom API token is configured for authenticated routes."

    return [
        *([token_item] if role == "admin" else []),
        (
            "Authenticated web inventory writes are enabled."
            if settings.web_inventory_enabled
            else "Web inventory writes are disabled; clients own inventory writes."
        ),
        "Device activity, sync audit, and detected conflicts are available on the Sync page.",
    ]


def _require_web_inventory(settings: Settings) -> None:
    if not settings.web_inventory_enabled:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Web inventory writes are disabled.",
        )


def _run_admin_operation(model, operation):
    try:
        return model.model_validate(operation())
    except AdminOperationNotFound as error:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=str(error),
        ) from error
    except AdminOperationConflict as error:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=str(error),
        ) from error
