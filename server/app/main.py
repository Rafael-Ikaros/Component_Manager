from __future__ import annotations

from contextlib import asynccontextmanager
from datetime import datetime, timezone
import sqlite3
from typing import Annotated

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request, status
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from fastapi.middleware.cors import CORSMiddleware

from .admin.api import router as admin_router
from .admin.about import router as about_router
from .admin.update_agent import router as update_agent_router
from .admin.spec_enrichment import EnrichmentJobs
from .account_api import router as account_router
from .bom_presets import router as bom_preset_router
from .photos import router as photo_router
from .accounts import Account
from .build_info import BUILD_REVISION, BUILD_VERSION
from .auth import require_admin, require_sync_account, require_token
from .application_logging import (
    close_application_logger,
    configure_application_logger,
    log_event,
)
from .config import get_settings
from .database import _connect, get_db, init_db
from .mqtt import MqttPublisher
from .mqtt_configuration import configuration_response, save_mqtt_configuration
from .deployment_configuration import router as deployment_configuration_router
from .repositories import (
    pull_sync_snapshot,
    save_sync_payload,
)
from .sync_observability import record_pull, record_push, record_rejected_managed_conflicts
from .schemas import (
    HealthResponse,
    PullResponse,
    PushRequest,
    PushResponse,
    SyncTokenStatus,
    MqttConfigurationResponse,
    MqttConfigurationUpdate,
)


@asynccontextmanager
async def lifespan(app: FastAPI):
    configure_application_logger()
    settings = get_settings()
    init_db(settings)
    if not settings.mqtt_enabled:
        connection = _connect(settings.database_path)
        try:
            with connection:
                connection.execute(
                    "UPDATE mqtt_state SET snapshot_seeded = 0, snapshot_key = '' WHERE id = 1"
                )
        finally:
            connection.close()
    publisher = MqttPublisher(settings)
    app.state.mqtt_publisher = publisher
    publisher.start()
    log_event("startup", version=BUILD_VERSION, revision=BUILD_REVISION)
    try:
        yield
    finally:
        app.state.spec_enrichment_jobs.cancel_all()
        publisher.stop()
        close_application_logger()


class DynamicCORSMiddleware:
    """Apply current saved origins to each request without restarting."""

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        settings = get_settings()
        middleware = CORSMiddleware(
            self.app,
            allow_origins=list(settings.admin_web_origins),
            allow_credentials=True,
            allow_methods=["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"],
            allow_headers=["Authorization", "Content-Type", "X-API-Token", "X-CSRF-Token", "X-Component-Vault-Account-Id", "X-Component-Vault-Device-Id"],
        )
        await middleware(scope, receive, send)


def create_app() -> FastAPI:
    settings = get_settings()
    app = FastAPI(title="Component Vault Sync", lifespan=lifespan)
    app.state.spec_enrichment_jobs = EnrichmentJobs()

    @app.exception_handler(RequestValidationError)
    async def mqtt_validation_error(
        request: Request,
        error: RequestValidationError,
    ) -> JSONResponse:
        if request.url.path == "/setup/config":
            fields = sorted({
                ".".join(str(part) for part in item.get("loc", ())[1:])
                or "request"
                for item in error.errors()
            })
            return JSONResponse(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                content={
                    "detail": "Deployment configuration validation failed.",
                    "fields": fields,
                },
            )
        if request.url.path != "/admin-api/mqtt/config":
            from fastapi.exception_handlers import request_validation_exception_handler

            return await request_validation_exception_handler(request, error)
        fields = sorted({
            ".".join(str(part) for part in item.get("loc", ())[1:]) or "request"
            for item in error.errors()
        })
        return JSONResponse(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            content={
                "detail": "MQTT configuration validation failed.",
                "fields": fields,
            },
        )
    app.add_middleware(DynamicCORSMiddleware)
    app.include_router(deployment_configuration_router)
    app.include_router(account_router)
    app.include_router(bom_preset_router)
    app.include_router(photo_router)
    app.include_router(admin_router)
    app.include_router(about_router)
    app.include_router(update_agent_router)

    @app.middleware("http")
    async def application_request_log(request: Request, call_next):
        try:
            response = await call_next(request)
        except Exception:
            route = request.scope.get("route")
            log_event(
                "request",
                method=request.method,
                route=getattr(route, "name", "unmatched"),
                status=500,
            )
            raise
        route = request.scope.get("route")
        log_event(
            "request",
            method=request.method,
            route=getattr(route, "name", "unmatched"),
            status=response.status_code,
        )
        return response

    @app.get("/health", response_model=HealthResponse)
    def health() -> HealthResponse:
        return HealthResponse(status="ok", server_time=_utc_now())

    @app.post(
        "/auth/ping",
        response_model=SyncTokenStatus,
        dependencies=[Depends(require_token)],
    )
    def auth_ping() -> SyncTokenStatus:
        return SyncTokenStatus(status="ok", server_time=_utc_now())

    @app.post(
        "/sync/push",
        response_model=PushResponse,
        dependencies=[Depends(require_sync_account)],
    )
    def sync_push(
        payload: PushRequest,
        connection: sqlite3.Connection = Depends(get_db),
        account: Account = Depends(require_sync_account),
    ) -> PushResponse:
        try:
            accepted_components, accepted_stock_movements = save_sync_payload(
                connection,
                payload,
                mqtt_topic_prefix=(settings.mqtt_topic_prefix if settings.mqtt_enabled and account.role == "admin" else None),
            )
        except ValueError as error:
            managed_conflict = str(error).startswith(
                ("Inventory conflict", "A changed inventory snapshot")
            )
            try:
                if managed_conflict:
                    record_rejected_managed_conflicts(connection, payload)
                with connection:
                    record_push(
                        connection, payload.device_id, 0, 0,
                        status="conflict" if managed_conflict else "rejected",
                    )
            except Exception as audit_error:
                log_event("sync_audit_failed", error_type=type(audit_error).__name__)
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail=str(error),
            ) from error
        if settings.mqtt_enabled and account.role == "admin" and accepted_components:
            app.state.mqtt_publisher.notify()
        return PushResponse(
            accepted_components=accepted_components,
            accepted_stock_movements=accepted_stock_movements,
            server_time=_utc_now(),
        )

    @app.get(
        "/admin-api/mqtt/status",
        dependencies=[Depends(require_admin)],
    )
    def mqtt_status() -> dict[str, object]:
        return app.state.mqtt_publisher.status()

    @app.get(
        "/admin-api/mqtt/config",
        response_model=MqttConfigurationResponse,
        dependencies=[Depends(require_admin)],
    )
    def get_mqtt_config(
        connection: sqlite3.Connection = Depends(get_db),
    ) -> MqttConfigurationResponse:
        return configuration_response(connection, settings)

    @app.post(
        "/admin-api/mqtt/config",
        response_model=MqttConfigurationResponse,
        dependencies=[Depends(require_admin)],
    )
    def update_mqtt_config(
        update: MqttConfigurationUpdate,
        connection: sqlite3.Connection = Depends(get_db),
    ) -> MqttConfigurationResponse:
        try:
            return save_mqtt_configuration(connection, settings, update)
        except ValueError as error:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=str(error),
            ) from error

    @app.get(
        "/sync/pull",
        response_model=PullResponse,
        dependencies=[Depends(require_sync_account)],
    )
    def sync_pull(
        since: Annotated[datetime | None, Query()] = None,
        cursor: Annotated[int | None, Query(ge=0)] = None,
        x_component_vault_device_id: Annotated[str | None, Header(max_length=120)] = None,
        connection: sqlite3.Connection = Depends(get_db),
    ) -> PullResponse:
        locations = []
        sync_cursor, components, stock_movements = pull_sync_snapshot(
            connection,
            cursor=cursor,
            since=since,
            locations_out=locations,
        )
        record_pull(connection, x_component_vault_device_id, sync_cursor)
        return PullResponse(
            server_time=_utc_now(),
            sync_cursor=sync_cursor,
            components=components,
            stock_movements=stock_movements,
            storage_locations=locations,
        )

    return app


app = create_app()


def _utc_now() -> datetime:
    return datetime.now(tz=timezone.utc)
