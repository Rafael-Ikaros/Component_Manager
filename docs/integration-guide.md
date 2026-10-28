# Integration Guide

## Base URL

The self-hosted server exposes HTTP endpoints under the configured base URL.
Examples in this document assume `http://localhost:8787`.

## Authentication

- `GET /health` does not require authentication. It returns `status`, `server_time`,
  `inventory_protocol`, plus build `version` and `revision` in new images. These
  two diagnostic fields are additive; older servers omit them. Protocol and
  `/auth/me` identity checks remain authoritative, not a version-string comparison.
- All sync endpoints require `Authorization: Bearer <API_TOKEN>`.
- `/admin-api/*` accepts native Bearer auth or the authenticated Web session described below.
- The server also accepts `X-API-Token`, but the client uses bearer auth.
- The setup token authenticates the administrator. Ordinary account keys resolve
  independent inventory databases and cannot access setup, logs, accounts or
  global MQTT configuration. Disabled accounts and replaced keys return 401.
- The separated `admin-web/` console exchanges its key through `POST /auth/session`
  before calling role-appropriate `/admin-api/*` routes. Native connection tests
  check both `/auth/ping` inventory capability and `/auth/me` identity without
  persisting an account binding; the binding is verified during sync.
- Native JLC import enrichment queries public catalogs directly without a
  server token. The separate server-side `GET /admin-api/part-lookup` and
  `GET /admin-api/lcsc/lookup` helpers remain authenticated.

## Deployment configuration

From 0.7.1, `GET /` redirects to the built-in `GET /setup` page. It is served on
the API's own origin, so initial setup does not depend on the React admin app.
`GET /setup/status` returns only `{ "configured": true | false }` anonymously.
`GET /setup/config` and `GET /setup/logs` require the current administrator Bearer token.

`POST /setup/config` accepts `api_token`, `admin_web_origins` (an array of HTTP(S)
origins), and `web_inventory_enabled`. A fresh, unconfigured deployment accepts
its first write without a token. Once configured, all further writes require
the current token; a concurrent stale initial write receives 409. Later updates
can leave `api_token` empty to retain it. Non-empty environment overrides lock
the corresponding field; attempts to change it receive 409. Responses never
return the token. Configuration and authentication changes take effect after a
successful atomic save; the browser supplies the new token on later requests.

The configuration response includes effective origins, the Web write switch,
`environment_overrides`, `config_path`, and `log_path`. The logs response contains
up to 200 recent application event lines. It does not include request bodies,
queries or credentials. File locations and recovery steps are in the
[Docker quickstart](docker-quickstart.md).

## Endpoints

### `GET /health`

Use this for smoke checks and container health probes.

```powershell
curl http://localhost:8787/health
```

Response body:

```json
{
  "status": "ok",
  "server_time": "2026-05-07T06:00:00Z"
}
```

### `POST /auth/ping`

Validates that the configured token is accepted.

```powershell
curl -X POST http://localhost:8787/auth/ping `
  -H "Authorization: Bearer change-me"
```

### `POST /sync/push`

Uploads the latest client-side state for synchronized entities.

Account-aware clients first call `GET /auth/me`, whose response contains
`server_id`, `account_id`, `name` and `role` (`admin` or `user`). Validate the
server/account against the local workspace before any push or pull. Send
`X-Component-Vault-Account-Id: <account_id>` on both sync routes. This header is
required for ordinary accounts (409 if missing, 403 if mismatched); legacy
administrator clients may omit it. The server always derives the database from
the key, never from this header. Cursors are scoped to a single account database.

Administrator-only account routes:

- `GET /admin-api/accounts`: array of `{account_id, name, active, role}` for ordinary accounts.
- `POST /admin-api/accounts` with `{name}`: creates an account, returns its metadata and `api_token` once.
- `PATCH /admin-api/accounts/{id}` with `{name}` and/or `{active}`: updates metadata or access, preserving inventory.
- `POST /admin-api/accounts/{id}/rotate-key`: returns `{account_id, api_token}`; the old key immediately stops authenticating.

The implicit administrator is configured through `/setup`, not these account routes.

```powershell
$body = @'
{
  "device_id": "desktop-1",
  "components": [
    {
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
      "deleted": false
    }
  ],
  "stock_movements": [
    {
      "id": "mov-1",
      "component_id": "cmp-1",
      "movement_type": "inbound",
      "quantity": 24,
      "reason": "Initial stock",
      "note": "",
      "happened_at": "2026-05-07T06:00:00Z",
      "updated_at": "2026-05-07T06:00:00Z",
      "deleted": false
    }
  ]
}
'@

curl -X POST http://localhost:8787/sync/push `
  -H "Authorization: Bearer change-me" `
  -H "Content-Type: application/json" `
  -d $body
```

Success response:

```json
{
  "accepted_components": 1,
  "accepted_stock_movements": 1,
  "server_time": "2026-05-07T06:00:01Z"
}
```

A push is atomic across components and stock movements. Duplicate active SKU or
missing component references return HTTP 409 and roll back the entire batch.
Legacy LWW compares UTC instants rather than timestamp text. Equal-timestamp arrivals
retain the existing last-arrival policy; retrying an identical payload does not
create duplicate entities, although the sync revision may advance.

### Multi-location inventory protocol 1

`GET /health` and authenticated `POST /auth/ping` advertise `inventory_protocol: 1`.
New native clients check this before uploading. They send the following additional fields:

```json
{
  "device_id": "desktop-1",
  "inventory_protocol": 1,
  "storage_locations": [
    {"id": "A1", "name": "Drawer A1", "updated_at": "2026-09-15T00:00:00Z", "deleted": false}
  ],
  "components": [],
  "stock_movements": []
}
```

Each component includes `allocations: [{"location_id":"A1","quantity":24}]`
and `base_updated_at` (nullable for a new record). Existing component fields are
still required. Allocations are a complete snapshot, including at least one row
for a zero-stock component, and must sum to its quantity. Codes are stable location
IDs; names are editable. All amounts are bounded integers, never supplier stock.

Existing managed records require the incoming base to equal the server's current
`updated_at` as a UTC instant. Exact identical retries are accepted. Conflicts
return HTTP 409 and roll back locations, components, movements, revisions and MQTT
outbox together. Legacy clients cannot overwrite managed records. This is an
optimistic concurrency check, not automatic reconciliation of offline deltas.

Movements may contain `location_id` and `destination_location_id`. New type
`transfer` requires a positive quantity and two different locations. It leaves
the component total unchanged. New movements accompany their component snapshot
in the same push; existing protocol-1 history cannot be rewritten. An unstocked
location can be tombstoned; a location with positive active stock cannot.

Pull responses add `inventory_protocol: 1` and the complete `storage_locations`
list, including tombstones. Locations, allocation snapshots, component/movement
rows and `sync_cursor` come from one read transaction. Existing legacy components
return `allocations: null`; new clients migrate these to a single-bin snapshot.
Clients must retain edits made after the upload snapshot and advance their base
only to the version actually acknowledged, rather than silently adopting a later
unseen remote edit. A failed push must not clear local queues.

### `GET /sync/pull`

Use `cursor=0` for the first full snapshot, then send the previous response's
`sync_cursor`. The non-negative integer cursor is issued by the server; never
convert a timestamp into it. `cursor` takes precedence over legacy `since`.

The response includes `server_time`, `sync_cursor`, `components`, and
`stock_movements`. Both collections and `sync_cursor` are read from one database
snapshot. Changes accepted after that snapshot are returned by the next pull,
even if their client `updated_at` is old because the device was offline.

Omitting both parameters also returns a full snapshot. Legacy `since` is still
accepted and compares UTC instants, but its timestamp semantics cannot discover
all late offline writes. Updated native clients fall back to full pulls when an
older server omits `sync_cursor`. Apply all returned entities successfully before
persisting the new cursor; clear it when changing servers.

```powershell
curl "http://localhost:8787/sync/pull?cursor=0" `
  -H "Authorization: Bearer change-me"
```

### `GET /admin-api/dashboard`

Returns the top-level read-only admin snapshot used by the separated web
console.

```powershell
curl http://localhost:8787/admin-api/dashboard `
  -H "Authorization: Bearer change-me"
```

### `GET /admin-api/inventory`

Returns low-stock watchlist data plus the latest server-side component rows.

### `GET /admin-api/components` and `GET /admin-api/components/{id}`

Both endpoints require the existing bearer token and read inventory.
The overview endpoint above is unchanged.

The list accepts `q` (up to 200 characters; literal substring search across SKU,
name, category and default location), optional `low_stock=true|false`, `page`
(1..1,000,000, default 1), and `page_size` (1..100, default 25). It excludes deleted
components and sorts by `updated_at DESC, id ASC`. Pagination is offset-based;
concurrent inventory changes may shift later pages, so refresh to reconcile them.

The response contains `items`, `page`, `page_size`, `total` and `page_count`.
Each item exposes `id`, `sku`, `name`, `category`, `package_name`, `location`,
`quantity`, `min_stock`, `updated_at` and `low_stock`. An empty result has no items
and `page_count=0`. Out-of-range page numbers can return an empty page.

The detail endpoint returns the same component fields plus `description`,
`inventory_managed`, and `allocations` (`location_id`, `quantity`), or 404 for
missing/deleted components.
Empty allocations mean no independent allocation data was provided, not zero
component quantity. Invalid query values return 422; missing/invalid tokens 401.

```powershell
curl "http://localhost:8787/admin-api/components?q=C70565&page=1&page_size=20" `
  -H "Authorization: Bearer <API_TOKEN>"
```

### `GET /admin-api/sync`

Returns recent stock movement activity and sync posture details for the admin
console.

### `GET /admin-api/settings`

Returns runtime configuration and operational endpoint details used by the
admin console. `web_inventory_enabled` tells the UI whether inventory write
controls may be shown. The default is `false`.

### Optional Web inventory writes

The following endpoints require the normal bearer token. Write endpoints also
require `WEB_INVENTORY_ENABLED=true`; when the flag is false they return HTTP 403:

- `GET /admin-api/storage-locations`: list active locations; this read remains available.
- `POST /admin-api/storage-locations`: create `{request_id,id,name}`.
- `POST /admin-api/components`: create a zero-stock component at an explicitly
  selected `location_id`.
- `PUT /admin-api/components/{id}`: edit metadata without accepting quantity or
  allocation fields.
- `POST /admin-api/components/{id}/movements`: record an `inbound` or `outbound`
  movement.

Create, edit, and movement requests use a caller-generated `request_id` of 8–120
characters. Edit and movement requests also require the component's current
`expected_updated_at`. A successful retry with the same operation, target, and
canonical payload returns the current entity state without repeating the write.
Reusing the ID with different content, a stale expected version, a duplicate SKU,
or insufficient stock returns HTTP 409. Missing targets return 404; malformed or
extra write fields return 422.

New Web components start at quantity zero and contain one zero-quantity allocation
for the chosen active location. The component's compatibility `location` field
stores the location code; use the location list to display its name. Managed
inventory inbound can add an allocation at any active location; outbound requires
an existing allocation with enough quantity. Legacy `inventory_managed=false`
rows retain their scalar quantity and original location text: omit `location_id`
to adjust them in place. The server never guesses a legacy allocation.

The receipt, component snapshot, allocation changes, movement, sync revision, and
MQTT outbox entry commit in one short SQLite transaction. Retries return current
state rather than promising a stored response snapshot.

### `GET /admin-api/part-lookup`

Looks up hybrid recognition metadata for JLC/LCSC imports and other supplier
payloads. The server first applies bundled or refreshed rule-pack matches using
`sku`, `mpn`, `name`, `brand`, `package_hint`, and `source_type`. If LCSC
credentials are configured and the request includes `sku`, `mpn`, or `name`,
the server may then merge official supplier metadata on top of the local-rule
match. When `ENABLE_WEB_FALLBACK_RESOLVERS=true`, the same endpoint can also
fall back to public LCSC product pages if OpenAPI credentials are unavailable.

Canonical `name` should be treated as unresolved when blank. Clients should not
silently replace it with `mpn` or `sku`; those remain reference fields until
the user or the server confirms a real part name.

Query parameters:

- `sku`: LCSC/JLC part number such as `C30926`
- `mpn`: manufacturer part number such as `0603B104K500NT`
- `name`: optional product title or OCR text fragment
- `brand`: optional vendor or brand hint
- `package_hint`: optional package text such as `0603` or `SOT-23`
- `source_type`: optional source marker such as `JlcQr`, `JlcText`, or `SupplierOcr`

At least one of `sku`, `mpn`, `name`, `brand`, or `package_hint` is required.

```powershell
curl "http://localhost:8787/admin-api/part-lookup?sku=C30926&mpn=0603B104K500NT&source_type=JlcQr" `
  -H "Authorization: Bearer change-me"
```

Typical success response from bundled rules only:

```json
{
  "found": true,
  "source": "local_rules",
  "sku": "C30926",
  "mpn": "0603B104K500NT",
  "package_name": "0603",
  "category": "Capacitor / MLCC",
  "vendor": "Generic",
  "model_family": "MLCC",
  "official_url": "https://www.lcsc.com/product-detail/C30926.html",
  "matched_by": "jlc_generic_mlcc",
  "confidence": "medium",
  "cache_hit": false,
  "rule_version": "2026.05.10.2"
}
```

If official supplier credentials are configured, the `source` field may become
`lcsc_openapi+local_rules`. If public-web fallback is enabled instead, the
`source` field may become `lcsc_public_web+local_rules`. In both cases the
response can fill `name`, `brand`, or `category_path` while keeping local
package or family inference.

### `GET /admin-api/recognition-rules/meta`

Returns information about the active server-side recognition rule pack.

```powershell
curl http://localhost:8787/admin-api/recognition-rules/meta `
  -H "Authorization: Bearer change-me"
```

Typical response:

```json
{
  "version": "2026.05.10.2",
  "updated_at": "2026-05-10T12:00:00Z",
  "source": "bundled",
  "active_path": ".../server/app/recognition_rules.json",
  "override_path": ".../data/recognition_rules_cache.json",
  "remote_url": null,
  "web_fallback_enabled": false,
  "refreshed": false
}
```

### `POST /admin-api/recognition-rules/refresh`

Refreshes the cached server-side rule pack from `IMPORT_RULES_REMOTE_URL` when
that environment variable is configured. If no remote URL is configured, the
endpoint returns the active metadata without changing the bundled rule pack.

```powershell
curl -X POST http://localhost:8787/admin-api/recognition-rules/refresh `
  -H "Authorization: Bearer change-me"
```

### `GET /admin-api/lcsc/lookup`

Looks up official supplier metadata for JLC/LCSC parts directly. This endpoint
is retained as a compatibility proxy when callers need the raw LCSC-focused
result without the server's hybrid local-rule merge behavior.

Query parameters:

- `sku`: LCSC/JLC part number such as `C30926`
- `mpn`: manufacturer part number such as `0603B104K500NT`
- `name`: optional fallback product name for search matching

At least one of `sku`, `mpn`, or `name` is required.

```powershell
curl "http://localhost:8787/admin-api/lcsc/lookup?sku=C30926&mpn=0603B104K500NT" `
  -H "Authorization: Bearer change-me"
```

Typical success response:

```json
{
  "found": true,
  "source": "lcsc_openapi",
  "sku": "C30926",
  "name": "Multilayer Ceramic Capacitors MLCC - SMD/SMT 100nF 50V 0603",
  "mpn": "0603B104K500NT",
  "package_name": "0603",
  "category": "Capacitor",
  "category_path": "Capacitors / Ceramic Capacitors",
  "brand": "FH(Guangdong Fenghua Advanced Tech)",
  "official_url": "https://www.lcsc.com/product-detail/C30926.html",
  "matched_by": "sku",
  "confidence": "exact",
  "cache_hit": false
}
```

Failure behavior:

- `422 Unprocessable Entity`: no query field was provided
- `503 Service Unavailable`: LCSC credentials are not configured on the server
- `502 Bad Gateway`: the upstream LCSC request failed

## Payload Rules

- Every synchronized entity must have `id`, `updated_at`, and `deleted`.
- `updated_at` should be an ISO 8601 UTC timestamp.
- `movement_type` must be `inbound`, `outbound`, or `adjustment`.
- `quantity` and `min_stock` must not be negative for components.
- Active components must have unique `sku`.

## Conflict Behavior

- The service keeps the newer row by comparing `updated_at`.
- Older pushed rows are ignored rather than merged field-by-field.
- Soft deletes replicate like any other entity update.

## Error Cases

- `401 Unauthorized`: bearer token missing or invalid.
- `409 Conflict`: active `sku` uniqueness violation during push.
- `422 Unprocessable Entity`: request body shape or field validation failed.
- `502 Bad Gateway`: upstream supplier lookup or rule-refresh fetch failed.
- `503 Service Unavailable`: LCSC credentials are not configured for the
  compatibility lookup path.

## Android direct public lookup

The import UI can resolve a scanned `C70565` by reading
`https://www.lcsc.com/product-detail/C70565.html` directly. It sends neither the
raw packaging QR payload nor the user's server token. It requires no server
endpoint or OpenAPI credentials. The normal Android import flow no longer calls
the server-assisted recognizer; the legacy server route is retained for older
clients. Windows uses the same public page and exact-SKU rule.

Only matching `Product` JSON-LD is accepted; unrelated recommendations and page
titles alone are insufficient. Manual SKU changes invalidate a pending result
for a different SKU. User-edited fields are preserved, and packaging quantity
is independent of supplier stock. Disable direct lookups in import settings for
an offline-only workflow. Cached results expire after seven days; repeated
failed lookups in the current importer back off for thirty seconds.

Product image URLs use the existing component description field via
`商品图片：<trusted HTTPS URL>`; original category paths are also retained there.
BOM depletion and component-hub migration produce ordinary component and
stock-movement sync entities. Their batch/file idempotency markers are local
only, not new sync objects. See [BOM and migration](bom-and-migration.md) for
formats and the limits of cross-device concurrent stock operations.

## MQTT inventory state

An optional server publisher emits accepted component snapshots to
`<MQTT_TOPIC_PREFIX>/components/<percent-encoded id>/state` with retained QoS 1.
The JSON carries `event_id`, `id`, `sku`, `name`, `category`, `package_name`,
`location`, `quantity`, `min_stock`, `updated_at`, `deleted`, and `sync_revision`.
Managed inventory also publishes `allocations` with per-location quantities.
No description, raw label data, or stock-delta command is included. Use quantity
as state; repeated delivery must not trigger another decrement. Deletions use
`deleted: true` retained tombstones. Offline client changes appear only after
successful sync. Existing sync request and response schemas remain unchanged.

`GET /admin-api/mqtt/status` requires the existing bearer token and returns
`enabled`, `connected`, `pending`, `last_publish_at`, and `error`. Configuration,
delivery semantics, and a Home Assistant sensor example are in [MQTT](mqtt.md).

`GET /admin-api/mqtt/config` returns the editable settings plus
`password_configured`, `source`, `restart_required`, and `message`, never a
password. `POST` to the same authenticated endpoint saves `enabled`, `host`,
`port`, `tls`, `username`, `topic_prefix`, `client_id`, optional `password`, and
`clear_password`. Empty/omitted password preserves the existing password;
`clear_password: true` clears it explicitly. Saved settings take effect on API
restart, override MQTT environment defaults, and do not modify inventory.

## Component-name compatibility and connection fallback

The `name` field on synchronized components accepts 1–4000 characters. This
preserves names imported as long product descriptions by previous versions;
other field constraints are unchanged. A client-only update cannot fix an old
server's 200-character limit: upgrade the server and then retry the queued sync.

Android and Windows can save an optional external/fallback URL for another route
to the same server/database. Each sync probes primary before inventory writes and
tries fallback only for DNS, connection, or timeout failure on `POST /auth/ping`.
An HTTP 401, 409 or 422 is a server response, not a signal to switch addresses.
Push and pull within a cycle always use the chosen endpoint; a failed write is not
replayed against the other address. The next cycle retries primary. No new sync
endpoint or protocol version is introduced.

`API_TOKEN` comes from the server deployment environment. With Docker Compose,
the repository-root `.env` supplies it; for direct uvicorn launches explicitly
export it or use `--env-file .env`. The web console may show/copy the token already
held in its authenticated browser session. No endpoint reveals the server token
without authentication, and GitHub issue-report credentials are unrelated.

## 库存参数检索

`GET /admin-api/components?q=...` 在 SKU、名称、分类、封装、库位和描述中匹配，
空格分词采用 AND，可跨字段；支持 NFKC 大小写、常见型号分隔符、μ/µ/u 和 Ω/ohm 归一。
保留数字之间的小数点，不做可能混淆规格的数字编辑距离推测。说明中的品牌与官方参数也可被检索。
现有分页、总数、低库存和软删除过滤行为保留，查询不修改库存记录。

## 元件参数与名称分离（0.7.7-dev.3）

`/admin-api/lcsc/lookup` 与 `/admin-api/part-lookup` 新增可选 `description` 和 `parameters`（键值对象，缺失为空对象）。参数只取上游明确值；无参数时不从型号解码。默认展示名称使用品牌和型号，长商品说明进入 `description`。

Android 和 Windows 将官方参数保存在既有元件 `description` 的参数行中，保持现有同步和备份格式；无需新增数据库字段。客户端基本信息与 Web 详情解析中英参数行，检索覆盖原值、单位及常见字段别名。已有名称不批量改写，旧记录未保存过的参数仍需要重新联网查询。

## 旧库存参数批量补全 API

所有路径位于 `/admin-api/components/spec-enrichment`，使用现有 Bearer 账户密钥，数据库从认证身份解析。写请求受 `WEB_INVENTORY_ENABLED` 控制，读请求可在只读模式下预览。

| 方法与子路径 | 行为 |
| --- | --- |
| `GET /candidates` | 返回当前账户候选总数与前 50 条 ID、SKU、名称 |
| `POST /jobs` | `{}` 处理全部候选；可传 `component_ids` 选择候选（最多 5000 个 ID）；返回 202 与任务快照 |
| `GET /jobs/active` | 返回当前账户活动任务或 `null`，用于启动超时或页面恢复 |
| `GET /jobs/{id}` | 返回状态、计数与最多 50 条结果摘要，失败项优先 |
| `POST /jobs/{id}/cancel` | 请求取消；当前查询结束后停止 |
| `POST /jobs/{id}/retry` | 新任务 ID，重试失败及取消后未处理项 |
| `POST /jobs/{id}/clear` | 清除已结束任务的内存记录，不删除业务数据 |

状态为 `queued/running/completed/cancelled`；计数含 `total/processed/updated/skipped/failed`。同账户同时只能有一项活动任务，重复启动返回 409；任务跨账户不可访问。联网在事务外执行，提交前检查元件版本；成功写入 description、updated_at 与 sync revision，增量 `/sync/pull` 可获取更新。服务重启后任务 ID 失效，需重新预览剩余候选。具体使用范围见[操作指南](parameter-enrichment.md)。

## Web session and sync observability

- `POST /auth/session` accepts `{ "api_key": "..." }` and returns `identity`, `csrf_token`, `expires_at`; it sets the HttpOnly `cv_session` cookie (12 hours). Browser calls use `credentials: include`; persist only the API address. `GET /auth/session` restores identity/CSRF; `DELETE /auth/session` revokes the session. Cookie-authenticated POST/PUT/PATCH/DELETE requires `X-CSRF-Token`. Rotating the account key, disabling/deleting the account, or expiry invalidates its sessions. Native Bearer/X-API-Token stays supported.
- `DELETE /admin-api/accounts/{account_id}` is administrator-only and deletes an ordinary account with its server database. Administrator deletion is rejected. Busy database returns 409 with account disabled so deletion can be retried; missing account returns 404. It does not erase local client copies.
- `GET /admin-api/sync/devices` returns `{items:[...]}` with device ID, first/last seen and push/pull counts. It registers on activity, not by trusting a client-provided database path. This is an activity registry, not hardware attestation.
- `GET /admin-api/sync/audit?limit=100` returns per-device direction, observed_at, status, accepted entity counts and pull cursor. For GET `/sync/pull`, clients may add `X-Component-Vault-Device-Id`; absent IDs remain null. Existing sync payloads and account-binding requirements are unchanged.
- `GET /admin-api/sync/conflicts?limit=100` returns detected conflict snapshots, submitted device_id, known server_device_id, kind, timestamp and resolutions. `POST /admin-api/sync/conflicts/{id}/resolve` accepts `{resolution:"server_kept"|"client_resubmitted",note?:string}` (note at most 1000 chars); it records a human decision only. Refresh/edit/sync from a client to actually change inventory.
- `GET /admin-api/about` reports author, build version/revision and container/source-or-service detection. `POST /admin-api/about/check-update` checks the latest stable GitHub release. `update_available:null` means source/unknown version cannot be compared; failure is 502 rather than a false “up to date”. No self-update endpoint is provided.

All device/audit/conflict APIs use the authenticated account inventory database. Old activity is not reconstructed. About/update checks require authentication but do not change deployment.

## 删除 Web 库存元件（0.7.8-dev.7）

`DELETE /admin-api/components/{component_id}`，请求 JSON：`{"request_id":"唯一操作ID","expected_updated_at":"详情返回的更新时间"}`。需要有效账户认证，并启用 Web 库存写入；Cookie 会话仍需 CSRF 头。返回 `{id, deleted, updated_at}`。相同请求 ID 与载荷可安全重试，版本过期返回冲突，重新读取后再确认。服务端保存删除墓碑并同步给客户端，保留流水；不会访问其他账户的库存。

## BOM 预设协议 1

`/health` 与 `/auth/ping` 返回 `bom_preset_protocol: 1` 表示支持；缺少能力的旧服务端只同步库存。客户端先完成原账户绑定与库存同步，再携带同一 Bearer Token 和 `X-Component-Vault-Account-Id` 调用 `POST /sync/bom-presets`：

```json
{"device_id":"my-device","presets":[{"id":"preset-id","name":"示例","updated_at":"2026-10-22T01:00:00Z","deleted":true,"content":null}]}
```

响应 `{server_time,bom_preset_protocol:1,presets:[...]}` 返回账户完整预设快照（含墓碑）。按 ID 合并，较新 UTC 时间覆盖；相同时间以服务器为准，客户端同步期间的新本地修改应保留待下次交换。活动预设必须提供 content，删除记录可以为 null。

content 的字段为 `schema_version:1,project_name,production_sets,source_name,file_sha256,available_sheets,selected_sheet,warnings,lines`；每行 `id,sku,name,model,package_name,quantity_per_set,selection,excluded,source_rows`。source_rows 保留工作表、原始行号、单套数量和字段字典。每套数量/套数为正整数且乘积不超过 int32；最多 5000 行。selection 为 null/空字符串/元件 ID 时分别表示自动/明确未匹配/人工指定。不得丢失空字符串或重算收到的行 ID。完整三端样例见 `test-data/bom-preset-v1.json`。

`GET /admin-api/bom-presets` 复用 Web 会话或账户 Token，返回同一结构，只能查看自己的账户。所有这些操作均不扣库存。预设失败应显示部分完成并保留本地文件，不重放已成功库存扣料。

## 元件原图同步协议（photo_protocol=1）

库存元数据按既有协议提交并应用后，客户端独立同步当前有效元件的 `本地图片：<sha256>.(png|jpg)` 引用。关联仍属于元件 description / updated_at，不增加照片版本列，不因文件未下载而删除关联。

- `GET /sync/photos`：返回 `{photo_protocol:1,max_photo_bytes:5242880,photos:[{key,available,size_bytes}]}`；只含当前账户有效元件引用的唯一 key。
- `PUT /sync/photos/{key}`：raw PNG/JPEG，Content-Type 与扩展名一致，5 MiB 上限，内容 SHA256 与 key 一致；先写临时文件，再原子归档。当前账户必须存在该引用，重复上传幂等。
- `GET /sync/photos/{key}`：原图二进制。以上接口复用 Bearer 与 `X-Component-Vault-Account-Id`，不接受客户端数据库路径。
- `GET /admin-api/component-photos/{key}`：复用 Web 登录会话读取；列表/详情附带 `photo_key` 和 `photo_available`。不把令牌放入图片 URL。

`/health`、`/auth/ping` 增加 `photo_protocol:1`。旧服务端不支持时保留本地图片并显示照片未同步。照片交换不改库存数量、库存游标或出入库流水；成功库存不因照片失败重放。每次从当前元件引用与两端文件可用情况推导剩余工作，支持无库存增量时恢复。


## 订单资料更新（dev.21）

认证、账户数据库隔离和 Web 会话 CSRF 规则沿用现有 `/admin-api/*`。没有改动 `/sync/push`、`/sync/pull` 格式；应用资料更新后增加既有同步版本。

- `POST /admin-api/components/order-metadata/preview`：multipart 字段 `file`，BIFF `.xls`，最大 16 MiB、500 个唯一 C 料号；只读，Web 写入关闭时仍可预览。返回 `filename`、`rows` 和 `summary`；行包含 `component_id`、`sku`、保留的 `name`、`expected_updated_at`、`source_rows`、`messages`、`status` 以及 `changes`。
- `status` 为 `ready / unchanged / unmatched / conflict`。`changes.category`、`changes.package_name` 为 `{before, after}`；`changes.parameters` 为新旧参数字典，只包含发生变化的明确参数键。冲突与未匹配项不提供可应用字段。
- `POST /admin-api/components/order-metadata/apply`：JSON `{items: [...]}`，每项带 `component_id`、`sku`、`expected_updated_at`、`request_id`、`changes`。changes 只允许 `category`、`package_name`、`parameters`；额外字段被拒绝。分类值需含中文，空值不用于清除已有资料。应用需启用 `WEB_INVENTORY_ENABLED`。
- 返回 `items` 与 `summary`，状态为 `updated / unchanged / conflict / failed`。每项独立提交，同请求 ID 与相同内容重放返回 `unchanged`，不再次增加同步版本；同 ID 改内容会冲突。失败项可复用原 ID 重试，版本冲突需重新预览。

请求示例（值为专用测试示例）：

```json
{"items":[{"component_id":"fixture-component","sku":"C100","expected_updated_at":"2026-10-28T00:00:00Z","request_id":"order-metadata-example-1","changes":{"category":"贴片电阻","parameters":{"阻值":"10kΩ","精度":"±1%"}}}]}
```

名称、SKU、数量、最低库存、库位、分配、图片及非参数备注均不属于可更新字段；不新建元件，不创建出入库流水。详见[教程](order-metadata-update.md)。
