from __future__ import annotations

from datetime import datetime
from typing import Literal

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    SecretStr,
    field_validator,
    model_validator,
)

from .build_info import BUILD_REVISION, BUILD_VERSION


class HealthResponse(BaseModel):
    status: str
    server_time: datetime
    inventory_protocol: int = 1
    bom_preset_protocol: int = 1
    photo_protocol: int = 1
    version: str = BUILD_VERSION
    revision: str = BUILD_REVISION


class SyncTokenStatus(BaseModel):
    status: str
    server_time: datetime
    inventory_protocol: int = 1
    bom_preset_protocol: int = 1
    photo_protocol: int = 1


class StorageLocationPayload(BaseModel):
    id: str = Field(min_length=1, max_length=120)
    name: str = Field(min_length=1, max_length=200)
    updated_at: datetime
    deleted: bool = False


class AllocationPayload(BaseModel):
    location_id: str = Field(min_length=1, max_length=120)
    quantity: int = Field(ge=0, le=2147483647, strict=True)


class ComponentPayload(BaseModel):
    id: str = Field(min_length=1)
    sku: str = Field(min_length=1, max_length=120)
    # SQLite stores TEXT without a 200-character boundary. Imported legacy rows
    # can contain a full English description here, so sync accepts them without
    # truncating or rewriting business data.
    name: str = Field(min_length=1, max_length=4000)
    category: str = Field(min_length=1, max_length=120)
    package_name: str = Field(min_length=1, max_length=120)
    location: str = Field(min_length=1, max_length=120)
    description: str | None = None
    quantity: int = Field(ge=0, le=2147483647, strict=True)
    min_stock: int = Field(default=0, ge=0, le=2147483647, strict=True)
    updated_at: datetime
    deleted: bool = False
    allocations: list[AllocationPayload] | None = None
    base_updated_at: datetime | None = None

    @model_validator(mode="after")
    def validate_allocations(self) -> ComponentPayload:
        if self.allocations is not None:
            ids = [item.location_id for item in self.allocations]
            if not ids or len(ids) != len(set(ids)):
                raise ValueError("Allocations require unique non-empty locations.")
            if sum(item.quantity for item in self.allocations) != self.quantity:
                raise ValueError("Allocation quantities must equal component quantity.")
            if self.quantity > 2147483647:
                raise ValueError("Inventory exceeds the supported quantity range.")
        return self


class StockMovementPayload(BaseModel):
    id: str = Field(min_length=1)
    component_id: str = Field(min_length=1)
    movement_type: Literal["inbound", "outbound", "adjustment", "transfer"]
    quantity: int = Field(ge=-2147483647, le=2147483647, strict=True)
    reason: str = Field(min_length=1, max_length=160)
    note: str | None = None
    happened_at: datetime
    updated_at: datetime
    deleted: bool = False
    location_id: str | None = None
    destination_location_id: str | None = None

    @model_validator(mode="after")
    def validate_transfer(self) -> StockMovementPayload:
        if self.movement_type == "transfer" and (
            self.quantity <= 0 or not self.location_id
            or not self.destination_location_id
            or self.location_id == self.destination_location_id
        ):
            raise ValueError("Transfer requires positive quantity and distinct locations.")
        return self


class PushRequest(BaseModel):
    device_id: str = Field(min_length=1)
    components: list[ComponentPayload] = Field(default_factory=list)
    stock_movements: list[StockMovementPayload] = Field(default_factory=list)
    inventory_protocol: Literal[0, 1] = 0
    storage_locations: list[StorageLocationPayload] = Field(default_factory=list)


class PushResponse(BaseModel):
    accepted_components: int
    accepted_stock_movements: int
    server_time: datetime


class PullResponse(BaseModel):
    server_time: datetime
    sync_cursor: int = Field(ge=0)
    components: list[ComponentPayload] = Field(default_factory=list)
    stock_movements: list[StockMovementPayload] = Field(default_factory=list)
    inventory_protocol: int = 1
    storage_locations: list[StorageLocationPayload] = Field(default_factory=list)


class AdminMetricSnapshot(BaseModel):
    component_count: int
    total_units: int
    low_stock_count: int
    movement_count: int


class AdminComponentRecord(BaseModel):
    id: str
    sku: str
    name: str
    category: str
    location: str
    quantity: int
    min_stock: int
    updated_at: datetime
    status: str


class AdminLowStockRecord(BaseModel):
    id: str
    sku: str
    name: str
    location: str
    quantity: int
    min_stock: int
    updated_at: datetime


class AdminMovementRecord(BaseModel):
    id: str
    sku: str
    component_name: str
    movement_type: Literal["inbound", "outbound", "adjustment", "transfer"]
    quantity: int
    reason: str
    note: str | None = None
    happened_at: datetime
    updated_at: datetime


class AdminKeyValueItem(BaseModel):
    label: str
    value: str


class AdminDashboardResponse(BaseModel):
    metrics: AdminMetricSnapshot
    recent_components: list[AdminComponentRecord] = Field(default_factory=list)
    sync_notes: list[str] = Field(default_factory=list)


class AdminInventoryResponse(BaseModel):
    metrics: AdminMetricSnapshot
    low_stock_components: list[AdminLowStockRecord] = Field(default_factory=list)
    recent_components: list[AdminComponentRecord] = Field(default_factory=list)
    inventory_rules: list[AdminKeyValueItem] = Field(default_factory=list)


class AdminComponentListItem(BaseModel):
    photo_key: str | None = None
    photo_available: bool = False
    id: str
    sku: str
    name: str
    category: str
    package_name: str
    location: str
    quantity: int
    min_stock: int
    updated_at: datetime
    low_stock: bool


class AdminComponentListResponse(BaseModel):
    items: list[AdminComponentListItem] = Field(default_factory=list)
    page: int
    page_size: int
    total: int
    page_count: int


class AdminComponentAllocation(BaseModel):
    location_id: str
    quantity: int


class AdminComponentDetail(AdminComponentListItem):
    description: str | None = None
    allocations: list[AdminComponentAllocation] = Field(default_factory=list)
    inventory_managed: bool = False


class AdminStorageLocation(BaseModel):
    id: str
    name: str
    updated_at: datetime


class AdminStorageLocationCreate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    request_id: str = Field(min_length=8, max_length=120)
    id: str = Field(min_length=1, max_length=120)
    name: str = Field(min_length=1, max_length=200)

    @field_validator("request_id", "name")
    @classmethod
    def normalize_required_text(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("Value must not be blank.")
        return normalized

    @field_validator("id")
    @classmethod
    def validate_location_code(cls, value: str) -> str:
        if not value.strip() or value != value.strip():
            raise ValueError("Location code must not be blank or padded.")
        return value


class AdminComponentCreate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    request_id: str = Field(min_length=8, max_length=120)
    sku: str = Field(min_length=1, max_length=120)
    name: str = Field(min_length=1, max_length=4000)
    category: str = Field(min_length=1, max_length=120)
    package_name: str = Field(min_length=1, max_length=120)
    description: str | None = None
    min_stock: int = Field(default=0, ge=0, le=2147483647, strict=True)
    location_id: str = Field(min_length=1, max_length=120)

    @field_validator("request_id", "sku", "name", "category", "package_name")
    @classmethod
    def normalize_required_text(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("Value must not be blank.")
        return normalized

    @field_validator("location_id")
    @classmethod
    def validate_location_code(cls, value: str) -> str:
        if not value.strip() or value != value.strip():
            raise ValueError("Location code must not be blank or padded.")
        return value


class AdminComponentUpdate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    request_id: str = Field(min_length=8, max_length=120)
    expected_updated_at: datetime
    sku: str = Field(min_length=1, max_length=120)
    name: str = Field(min_length=1, max_length=4000)
    category: str = Field(min_length=1, max_length=120)
    package_name: str = Field(min_length=1, max_length=120)
    description: str | None = None
    min_stock: int = Field(default=0, ge=0, le=2147483647, strict=True)

    @field_validator("request_id", "sku", "name", "category", "package_name")
    @classmethod
    def normalize_required_text(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("Value must not be blank.")
        return normalized


class AdminComponentDelete(BaseModel):
    model_config = ConfigDict(extra="forbid")

    request_id: str = Field(min_length=8, max_length=120)
    expected_updated_at: datetime

    @field_validator("request_id")
    @classmethod
    def normalize_request_id(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("Value must not be blank.")
        return normalized


class AdminComponentDeleteResult(BaseModel):
    id: str
    updated_at: datetime
    deleted: bool


class AdminComponentDeleteSelection(BaseModel):
    model_config = ConfigDict(extra="forbid")

    id: str = Field(min_length=1, max_length=120)
    expected_updated_at: datetime


class AdminComponentBulkDelete(BaseModel):
    model_config = ConfigDict(extra="forbid")

    request_id: str = Field(min_length=8, max_length=120)
    items: list[AdminComponentDeleteSelection] = Field(min_length=1, max_length=100)

    @field_validator("request_id")
    @classmethod
    def normalize_request_id(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("Value must not be blank.")
        return normalized

    @field_validator("items")
    @classmethod
    def require_unique_components(
        cls, items: list[AdminComponentDeleteSelection]
    ) -> list[AdminComponentDeleteSelection]:
        if len({item.id for item in items}) != len(items):
            raise ValueError("Select each component only once.")
        return items


class AdminComponentBulkDeleteResult(BaseModel):
    items: list[AdminComponentDeleteResult]


class AdminStockMovementCreate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    request_id: str = Field(min_length=8, max_length=120)
    expected_updated_at: datetime
    movement_type: Literal["inbound", "outbound"]
    quantity: int = Field(gt=0, le=2147483647, strict=True)
    reason: str = Field(min_length=1, max_length=160)
    note: str | None = None
    location_id: str | None = Field(default=None, min_length=1, max_length=120)

    @field_validator("request_id", "reason")
    @classmethod
    def normalize_required_text(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("Value must not be blank.")
        return normalized

    @field_validator("location_id")
    @classmethod
    def validate_location_code(cls, value: str | None) -> str | None:
        if value is not None and (not value.strip() or value != value.strip()):
            raise ValueError("Location code must not be blank or padded.")
        return value


class AdminSyncResponse(BaseModel):
    metrics: AdminMetricSnapshot
    recent_movements: list[AdminMovementRecord] = Field(default_factory=list)
    sync_assumptions: list[AdminKeyValueItem] = Field(default_factory=list)
    attention_items: list[str] = Field(default_factory=list)


class AdminSettingsResponse(BaseModel):
    web_inventory_enabled: bool = False
    runtime_configuration: list[AdminKeyValueItem] = Field(default_factory=list)
    access_posture: list[AdminKeyValueItem] = Field(default_factory=list)
    next_backend_additions: list[str] = Field(default_factory=list)


class MqttConfigurationUpdate(BaseModel):
    enabled: bool = False
    host: str = Field(default="", max_length=255)
    port: int = Field(default=1883, ge=1, le=65535)
    tls: bool = False
    username: str = Field(default="", max_length=255)
    password: SecretStr | None = Field(default=None, max_length=1024)
    clear_password: bool = False
    topic_prefix: str = Field(default="component-vault", max_length=255)
    client_id: str = Field(default="component-vault-server", max_length=255)


class MqttConfigurationResponse(BaseModel):
    enabled: bool
    host: str
    port: int
    tls: bool
    username: str
    topic_prefix: str
    client_id: str
    password_configured: bool
    source: Literal["environment", "saved"]
    restart_required: bool
    message: str


class LcscLookupResponse(BaseModel):
    found: bool
    source: str = "lcsc_openapi"
    sku: str | None = None
    name: str | None = None
    mpn: str | None = None
    package_name: str | None = None
    category: str | None = None
    category_path: str | None = None
    brand: str | None = None
    description: str | None = None
    parameters: dict[str, str] = Field(default_factory=dict)
    official_url: str | None = None
    matched_by: Literal["sku", "mpn", "name"] | None = None
    confidence: Literal["exact", "fallback", "none"] = "none"
    cache_hit: bool = False


class PartLookupResponse(BaseModel):
    found: bool
    source: str = "local_rules"
    sku: str | None = None
    name: str | None = None
    mpn: str | None = None
    package_name: str | None = None
    category: str | None = None
    category_path: str | None = None
    brand: str | None = None
    description: str | None = None
    parameters: dict[str, str] = Field(default_factory=dict)
    vendor: str | None = None
    model_family: str | None = None
    official_url: str | None = None
    matched_by: str | None = None
    confidence: str = "none"
    cache_hit: bool = False
    rule_version: str | None = None


class RecognitionRulesMetaResponse(BaseModel):
    version: str
    updated_at: str | None = None
    source: str
    active_path: str
    override_path: str
    remote_url: str | None = None
    web_fallback_enabled: bool = False
    refreshed: bool = False
