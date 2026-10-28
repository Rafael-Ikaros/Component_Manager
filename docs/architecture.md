# Architecture Notes

> 标签界面：Android 的单元件标签与批量打印统一进入 `BluetoothLabelPrintScreen`，原独立预览页及诊断弹窗已移除。可编辑 `LabelDesign` 与原元件 `ComponentLabelSeed` 分开保存；前者决定显示文字和纸面位置，后者决定二维码身份。队列 v3 按张保存设计快照和元件/自由标签来源，兼容读取旧 v1/v2 元件队列。自由标签没有库存身份，元件二维码仍从 ComponentLabelSeed 生成。预览、导出和打印共用 M1 纸面渲染器，底层分帧与走纸协议保持不变。开发版功能以对应 Release 说明为准。

## 客户端库存编辑与恢复

客户端按阶段拆分 Windows 库存导入/编辑/库位/保存链路，不更换 WinUI、SQLite 或服务端协议，也不宣称全部客户端已经完成模块化。

- `ViewModels/ComponentEditorViewModel.cs` 管理表单字段、库位选择、新建库位草稿、明确的商品来源及请求校验；准备或取消编辑不写数据库。
- `Views/Components/ComponentEditorDialog.cs` 管理表单控件和商城查询交互；`ComponentsView` 保留页面入口与确认操作。
- `Services/InventoryEditingService.cs` 判断保存/追加库存，SKU 查询来自数据库，不依赖筛选后的可见集合，也不解析说明文字判断导入来源。
- `Services/InventoryStore.cs` 保留原有调用接口，作为数据库、读写及同步持久化的门面，库存写入委托给 `Services/InventoryWrites/`：`InventoryComponentWriter` 管理元件保存和软删除；`InventoryLocationWriter` 管理库位；`InventoryStockWriter` 管理出入库和调拨；`InventoryBatchWriter` 管理批量入库和商城追加；`InventoryBomImportWriter` 管理 BOM 扣料和 Component Hub 导入。共享 SQL 辅助只负责时间戳、分配、库位引用和同步队列写入。
- 各写入操作保留原事务边界：编辑器新建库位与元件保存或追加库存、流水、同步队列处于同一事务；失败回滚，保持总库存及多库位分配语义。独立管理库位仍可独立保存。
- `ViewModels/InventoryViewModel.cs` 管理库存读取、搜索、分类/库位筛选、排序、选中项及详情摘要；`DashboardViewModel` 管理概览统计和低库存/最近流水；`MovementsViewModel` 管理流水、选择、相关记录和出入库提交。刷新前保存选择 ID，忽略控件重建列表时的临时空选择。`MainViewModel` 保留页面绑定入口和跨模块刷新，不再处理同步调度或直接生成各页面统计。
- `Services/SyncCoordinator.cs` 管理连接测试/手动同步的串行门控、自动同步防抖和界面状态。`InventorySyncService` 保留单次网络交换与账户校验顺序；`Services/SyncPersistence/` 分别管理设置/账户绑定、上传快照和结果事务。关闭窗口取消等待中的自动同步；新编辑只替换尚未开始的计划，不打断已开始的同步。
- `Views/Movements/MovementEntryDialog.cs` 只收集出入库/调拨草稿，库位显示名称、提交稳定 ID；页面调用 ViewModel 提交。原库存校验、事务和同步队列继续由库存写入层负责。
- `Services/InventoryDatabase.cs` 负责连接、原有 schema 初始化、迁移和迁移前备份；`Services/InventoryQueries.cs` 负责只读查询和实体映射，事务内部读取继续复用写入连接。未更改数据库路径、表结构、同步协议，也不维护第二套库存数据库。
- Windows `StorageLocationDisplay` 在读取后补充显示名称，Android 界面从共享库位列表解析名称；筛选、保存和同步仍使用稳定 ID。缺失历史名称时回退原值，不修改用户已有标识。同名 Windows 库位筛选项附带 ID 区分。
- Windows 删除库位只检查活动元件的正库存分配；已删除元件的历史分配、流水与同步删除标记继续保留。同步已删除元件的历史分配不会重新激活已删除库位。
- Windows 核心链路拆分已在 dev.14 收尾；功能页面与业务规则继续沿用现有实现，不代表全部客户端已重写。

### Android 功能模块边界

- `InventoryViewModel` 保留 UI 入口、协程生命周期、状态发布和保存后刷新顺序。`InventoryStateProjector` 负责筛选/列表/详情/概览投影；`MovementBatchCoordinator` 负责扫码队列、库存重载后的复核及数量/库位校验。同步和本地操作共用同一个状态重载入口。
- `ComponentSaveCoordinator` 负责导入查重、追加确认和失败重试，应用壳只处理页面与标签入口。`BomImportOperations` 负责文件解析与只读匹配预览，`InventoryBackupOperations` 负责文档 URI 读写；业务提交继续经 Repository。
- `InventoryRepository` 保留配置、读取和预览入口；`InventoryWriteService` 管理元件、库位、出入库、批量入库和转移事务，`InventoryImportCatalog` 管理识别、官方查询、学习映射与查询缓存。学习映射写入复用元件保存的同一个 SQLite 事务。
- `InventorySyncService` 管理网络交换、账户绑定、上传快照、拉取和队列确认；与保存同步配置共用配置锁。账户检查仍在上传前，拉取及队列确认同事务提交，随后保存游标。`InventoryPreferenceKeys` 集中共享键值，不改变已有设置文件或键名。
- 流水的来源/目标库位区分 JSON 空值与文本。同步事务仅修正不存在对应真实库位的历史 `null` 字符串引用，保留流水、库存和原时间；不新增 schema 迁移。
- Android 设置和库存内容消费外层安全区域后处理 IME inset，避免键盘把整页平移到屏外；主导航切换清除输入焦点。库存筛选以当前列表栏的宽度换行，双栏设备不依赖横向滑动寻找库位或排序。
- 编辑/出入库/立创导入共用 `AdaptiveFormSurface`：大屏 Dialog 在自身窗口消费系统栏与 IME inset，最大宽度760dp，顶部操作固定在可见区域，字段列表使用剩余高度；紧凑布局消费 Scaffold inset 后处理 IME。BOM/迁移预览的说明、行和末尾操作共用一个 LazyColumn，配套件在自身列表处理 IME，标签模板尺寸页由底部操作栏避让键盘。通用二级 Scaffold 不重复增加全局 IME padding。
- `InventoryWorkbookService` 管理备份预览、恢复和恢复失败时的图片清理；`BomReleaseCommitter` 和 `ComponentHubImporter` 管理各自提交。`InventorySqlOps` 只提供接受现有连接的分配/队列辅助，不另开事务。数据库文件、schema v5、升级备份和历史数据格式保持不变。

Android 导入页创建库位统一调用主 ViewModel，完成后刷新共享库存状态。完整编辑器在应用根节点使用同一组合位置，紧凑全屏与宽屏对话框切换不另建草稿状态。完整编辑器的文字、数量、库位以及导入来源使用 Compose 保存状态；待提交扫码批次通过 `SavedStateHandle` 保存用户输入，重建时重新读取当前库存，恢复到复核状态，不自动执行库存写入。取消或成功后清理草稿，提交失败保留供修正。已采纳的本地图片随编辑会话恢复；明确取消（包括系统返回）才删除会话新建图片，保存成功保留采用的图片。系统保存状态恢复不等同于长期草稿备份，不保证强制停止或设备重启恢复；提交期间中断应先核对记录，避免手动重复入库/出库。

自动化测试使用隔离数据库；实际界面验证只创建带测试标记的合成元件。编译、定向测试和 CI 结果分别记录在 [current-work.md](current-work.md)，不代表真实业务数据及全量 UI 验收。

## Topology

- `android-client/` is the Android Jetpack Compose client.
- `windows-client/` is the WinUI 3 Windows desktop client.
- `admin-web/` is a separated React + `shadcn/ui` operations console.
- `client/` remains as a legacy Flutter reference only.
- `server/` is a FastAPI service for account-isolated self-hosted sync and
  inventory admin APIs, optional authenticated Web inventory writes, and MQTT
  configuration writes.
- Android, Windows, and server all use SQLite in the current architecture, and
  each runtime is now implemented against live storage.

The API and admin Web containers are built independently. `server-image.yml`
always builds and starts amd64 validation images, checks API/auth/database
startup, then fetches the Web index and its emitted JS/CSS assets. When Docker
Hub variables and token are configured, releases publish API tags `<version>`
and `latest`, plus Web tags `web-<version>` and `web-latest`, for amd64/arm64.
The 0.7.0 API and Web tags were verified published. Each subsequent release must
still verify its own publication. SQLite persists under `/data`.
`docker-compose.hub.yml` retains the source Compose data volume and exposes the
Web image through the optional `web` profile. Its default API/Web images use
`latest` / `web-latest`; updating requires pulling and recreating the containers.
See [Docker Hub operations](dockerhub.md).

The API provides a separate, small `/setup` page on its own origin for first-run
configuration and authenticated operational logs. It does not replace the React
inventory console. An explicit `admin_web_url` / `ADMIN_WEB_URL` identifies the
actual browser console entry, independently of the CORS origin list. Setup offers
an enter-console action after saving, leaving time to copy the token; credentials
are never appended to the navigation URL. The authenticated Web Settings form
uses the same `/setup/config` endpoints, respects environment-controlled fields,
and updates its browser session after a token change. Without a configured console
URL, `/setup` remains a complete configuration entry.
Initialization persists Token, allowed origins and the Web
write switch to `config.json` beside SQLite. Non-empty environment overrides
remain authoritative. Configuration writes are atomic; initializing twice cannot
replace a configured server without its current token. Logs under `logs/` are
bounded and contain operational events, not request bodies or credentials.

## Account ownership

The original `DATABASE_PATH` remains the administrator inventory. Its account
registry and stable server identity resolve high-entropy user keys to separate
`users/<account-id>.db` files beside it. Authentication, not request input,
chooses a database. Components, active SKU uniqueness, locations, allocations,
movements, tombstones, revision cursors and operation receipts are therefore
isolated together. User key digests are stored; full keys are returned only on
creation or reset. Disabling an account retains its database.

`/auth/me` identifies the server, account and role. Setup, logs, account creation
and global MQTT settings require the administrator. The first iteration keeps
MQTT publishing for administrator inventory only; ordinary writes never enter
that publisher's outbox. Shared public catalog caches contain no private stock.
Authenticated Web CRUD uses the account database and the existing global Web
write switch. The Web shell uses identity to show role-appropriate settings.

Each native installation currently has one local inventory workspace. Before
sync, it verifies the remote identity against a persistent local binding. A
mismatch stops both push and pull without modifying stock or adopting the new
identity. Legacy previously-synced workspaces initially bind only to admin;
ordinary sync requires the matching `X-Component-Vault-Account-Id` header so old
clients cannot accidentally upload an existing workspace to a new user key.
This header is a client compatibility check, not an authentication credential.
See [implementation plan and recovery boundaries](account-isolation-plan.md).

Web language preference uses browser-local `component-vault.language` with
`system`, `zh-CN` or `en`. It is a personal display choice, independent of server
configuration and stock values. Separate Web/API origins store it independently.

## Android Client

Batch scanning acknowledges a capture only after the draft queue accepts it.
`ContinuousCaptureGate` uses monotonic time for a configurable 0.5–5 second
interval, while full packaging payloads remain deduplicated for the session and
the persisted draft. `BatchScannerSettingsStore` persists the interval and
independent sound/vibration preferences outside the inventory schema. Success
copy remains visible for at least 800ms; optional feedback hardware failures
cannot cancel an accepted receipt.

Both clients export label-printing XLSX independently of full inventory backup.
The export contains selected columns and active components only. QR cells carry
the established standard/JLC-compatible long payload and `cvl3` short payload as
Excel text, so leading zeros and delimiters survive third-party label import.
The default all-location export keeps one row per component and total quantity.
An explicit location selection projects the current allocation snapshot into one
row per component/location, using that location's quantity in both cells and QR
payloads. Legacy components without allocations fall back to their stored
location; an empty location is selectable as unassigned. Export is read-only and
captures the selected rows and columns before opening the destination picker.

Catalog routing prefers domestic LCSC for Chinese and international LCSC for
English. Android reads its saved app language; Windows uses its current UI
culture. A successful primary result is not overwritten by a secondary lookup.
Blocked/unavailable/unmatched/invalid responses retain explicit fallback reasons.
International keyword search is unavailable in the current public-page adapter,
so that path explicitly identifies its use of domestic search. No server or
OpenAPI key is introduced by this routing change.

Domestic lookup first parses valid product data before checking verification-page
markers. An app-session gate pauses further domestic attempts after a verification
block (120 seconds), a network failure (30 seconds), or a bounded Retry-After rate
limit. Different SKUs can still use the international fallback; explicit retry
clears the pause. Browser sessions are independent and browser verification does
not guarantee that native requests will be accepted. No challenge bypass is used.

Android label preview includes an opt-in Bluetooth diagnostic dialog with service
discovery and M1 connection modes. It lists nearby BLE and system-paired devices.
M1 mode uses a paired Classic/dual-mode device and an RFCOMM SPP socket to query
the model, then status only after a recognised M1 reply. Socket I/O runs off the UI
thread; cancellation, timeout and leaving the foreground close the socket and
discard obsolete session callbacks. The read-only query action sends no print
commands. A separate, explicitly confirmed test-label action rechecks the model
and status, then sends one fixed 203-dpi raster in complete-row blocks of at most
1024 raw bytes (20 complete-row frames for 320×480, matching the observed Hanma
3.3.4 M1 runtime geometry). Blocks use independently implemented literal-only LZO1X and the
M1 POLI header, preceded by right alignment and followed by a single label feed.
Explicit, non-persistent diagnostic choices omit only that final feed or replace
it with the upstream short-feed command; neither changes the raster or is the
default printing mode. Reports distinguish the requested feed mode from the numbers
of label-feed and short-feed commands successfully written. No vendor binary or JNI is used.
The existing 40 × 10 / 40 × 30 mm templates supply defaults for a device-local
test paper profile with dimensions, rotation and image offsets. It changes the
raster only; no firmware paper-type or calibration commands are sent. Normal
Android component-label printing has a separate foreground print center, reached
from label preview or inventory batch printing. `M1ComponentLabelRenderer` uses
the existing QR payload codec and text field rules with integer QR modules at
203 dpi; this paper raster is used for both preview and transmission. Paper
dimensions, rotation and offsets are local rendering options, not firmware writes.
Actual labels have no outer frame. Offsets translate the raster on the paper axes
without resizing QR modules; positive horizontal values move ink right. Physical
validation on one M1 / Android 15 / 40×60 mm gap stock confirmed scannable labels
and two automatic copies with consistent placement and no extra paper after a
device-specific +0.5 mm horizontal adjustment. Default offsets remain zero.
`LabelPrintController` serializes jobs through the same M1 single-label transport
with normal label feed. Each item stores a component snapshot and copy number;
printing does not change inventory. `LabelPrintQueueStore` uses an app-private
AtomicFile JSON checkpoint before sending; interrupted Sending items recover as
Uncertain. Failed or uncertain items require explicit review/retry/skip before
continuing, and clearing a job does not clear stock. The current queue can contain
at most 500 labels, with up to 99 copies per selected component. Only one current
job is retained. Queues do not sync to the server or appear in shared diagnostics.
Automatic advancement requires a processing notification and a clean model reply
without reported printer errors. This proves protocol readiness, not physical
label completion; missing readiness pauses for review. Leaving the foreground
stops the queue and closes the transport. Query reads separate known asynchronous
status frames and exact `dithering_finish\0` processing notifications from ordinary
model/status replies, preserving split frames across
query boundaries. Processing-event counts do not certify paper placement. Socket write
completion means sent/unconfirmed, not physical print completion. Interrupted
jobs are never automatically resent. M1 test printing retains a successful SPP
connection for the next explicit print in the foreground dialog, releasing it
on cancellation, error, leaving the screen or 60 seconds idle. Each print still
checks model/status; after sending it also queries the model before retaining
the connection. An inconclusive model reply is drained for 250 ms and retried once,
without replaying any print data. Continued uncertainty after completed writes is
sent/unconfirmed, not proof of disconnection; it releases the connection. Actual
I/O failures remain distinct. Classic SPP writes are split into at most 1024 bytes per write.
Reports retain partial stages and status codes, but exclude arbitrary device
names, addresses, serial numbers and raw replies; a model match is recorded only
as the fixed value M1. See [device test steps](printer-compatibility.md) and
[the APK-derived protocol evidence](m1-apk-analysis.md).

Android catalog cache keys include the preferred source. Only a primary-source
success is persisted; fallback results never suppress a subsequent attempt at
the preferred site. Metadata cache round-trips description, parameters and
datasheet links. Changing app language clears only lookup entries; startup
removes legacy unscoped, malformed and expired entries from that same prefix.

Database initialization failures are displayed before normal inventory controls
are available. Initial inventory reload errors use the same recovery screen.
Retry keeps the original database; diagnostic copy includes exception classes
and relevant class/method/line references, excluding exception messages and file
paths. Automatic startup sync begins only after the initial inventory load.
Pre-upgrade snapshots copy the SQLite database and WAL/SHM under a write lock;
failure stops the upgrade. Result-returning PRAGMAs use `rawQuery`, not `execSQL`.

Both native clients expose a Settings feedback surface. Reports are authored
locally; diagnostic inclusion is opt-in and editable. A bounded process-local
event log accepts fixed event types and numeric fields, never raw barcode
payloads, credentials, inventory or arbitrary exception text. Reports can be
copied/exported; the system browser opens a fixed GitHub new-issue URL for login
and final submission. Long bodies use an explicit copy/paste path rather than
silent truncation. No GitHub token, OAuth secret or feedback server is required.
See [feedback and scan diagnostics](feedback-and-scan-diagnostics.md).

- Built with Jetpack Compose and Material 3.
- Current implementation is a stable-mode local-first client backed by
  on-device SQLite and shared preferences.
- User-facing copy is now routed through Android string resources, with a
  Chinese-first default UI, persisted app-language switching (`zh-CN` and
  `en`), and matching Simplified Chinese (`zh-CN`) resource coverage for the
  primary screens. Compose Preview uses injected `ComponentVaultStrings`
  sample bundles plus content-level preview composables so Preview rendering
  does not depend on Android Studio resolving the runtime `R.string` graph.
- Navigation centers on four adaptive top-level destinations: `Home`,
  `Inventory`, `Records`, and `Settings`. Home presents the summary; settings
  has one main entry with section drill-down and direct sync-section routing.
  Inventory's Import and scan bottom sheet groups single-item lookup,
  batch JLC inbound, separate Project BOM and data migration routes, and manual entry. Settings > Inventory
  and data owns location management and Excel backup/restore. These secondary
  routes consume system Back and return to their parent. Batch review uses a
  bounded LazyColumn with collapsible summaries and fixed confirmation actions;
  editor fields scroll independently inside their dialog. See the
  [native UI layout contract](native-ui-redesign.md) and
  [cross-platform UI audit and phased plan](ui-ux-audit-2026-09-16.md).
- `SecondaryPageScaffold` provides shared app bars, insets and busy-aware Back
  handling for location management, backup, BOM and batch pages. The import
  chooser overlays the existing inventory route and dismisses back to it.
  Locations use a bounded list and separate create/edit dialogs. BOM configuration
  scrolls separately from its compact-summary, bounded-list preview stage.
- Testing a connection uses a transient URL/token and does not save preferences
  or reset the sync cursor. Sync actions require connection changes to be saved
  explicitly. The same rule applies to the Windows settings page.
- `Inventory` is the high-frequency workflow and uses dense search,
  filter, and list-first layouts on phones, plus persistent list-detail panes
  on larger widths. The current shell is built on official Material 3 adaptive
  primitives: `NavigationSuiteScaffold` for top-level navigation,
  window size classes for width handling, and
  `NavigableListDetailPaneScaffold` for Inventory list-detail behavior. The
  Inventory header now treats search as the primary action through
  an inline `OutlinedTextField`, with results visible while typing, fixed horizontal filters and denser inventory rows instead
  of the older stacked filter-card layout. It now also includes quantity-first
  import flows for JLC copied mobile product text, package QR payloads,
  supplier packaging OCR, and generated warehouse labels.
- Package QR scanning now runs through an in-app CameraX surface backed by the
  bundled ML Kit Barcode Scanning API, so first use does not depend on Google
  Play services downloading an external scanner module.
- Supplier text recognition runs through an in-app CameraX surface backed by
  bundled ML Kit Chinese text recognition. The current Android flow now asks
  the user to align packaging text, capture a photo with ImageCapture, build a
  structured OCR result with ordered text lines, and only then parse packaging
  fields into the quantity-first import confirmation form.
- Android OCR now goes through a dedicated engine abstraction with local
  preference storage. `Auto` currently resolves to the bundled ML Kit engine,
  `ML Kit` forces the same offline recognizer explicitly. The unavailable
  `Paddle experimental` choice is hidden and old preferences fall back to Auto.
- Label generation is client-owned and stays schema-compatible: JLC-sourced
  items generate JLC-compatible QR payloads with app extension fields, while
  non-JLC items generate an app-specific warehouse QR payload. Both paths can
  be exported as PNG or PDF for physical bag, bin, or drawer labels. The
  current Android implementation uses three client-side label templates:
  `10x40mm QR`, `30x40mm QR`, and a pure text strip. The narrow label renders
  horizontally with the QR code on the right and now emits a shorter
  lookup-first warehouse payload (`cvl3|sku|qty`) so dense small labels stay
  scan-friendly; legacy compact `cvl2` labels and app-generated
  JLC-compatible labels still round-trip. The `30x40mm` label now uses a
  horizontal physical `40x30mm` page with the QR on the left, a centered
  package-plus-name stack on the right, and bottom-aligned metadata rows, and
  the pure text strip uses single-line fit-to-fill typography. Preview now
  respects each template's physical aspect ratio, exports use the resolved
  label dimensions directly, and a bounded two-zone renderer keeps dynamic
  field text out of the QR safe area. Movement import and label scan flows
  resolve the compact `10x40mm` payload locally by `sku` so no sync API or
  schema change is required for the shorter code path.
- `Movements` uses the same adaptive approach: compact history-first layouts on
  phones and split history/detail arrangements on larger widths. It now also
  supports a scan-first workflow for warehouse labels and original JLC packaging QR codes:
  CameraX + bundled ML Kit barcode scanning returns raw QR content, the client
  resolves the label locally by parsed `sku`, without catalog lookup or automatic component creation. Original packaging quantity is not a stock-out instruction; the review starts at one unit. A successful match closes the
  scanner and opens a dedicated batch review page for `Inbound`, `Outbound`,
  or `Adjustment`, quantities and notes. Adding another scanned item requires
  an explicit action. Returning to history preserves the pending review;
  inventory changes only when the reviewed batch is confirmed. The scanner runs in a dedicated
  small-label mode with higher analysis resolution, ML Kit
  potential-barcode detection, zoom suggestions, and tap-to-focus to improve
  read rates on narrow printed labels.
- `Inventory` now routes primary create flows through a unified add-entry sheet
  so the main action consistently branches into `Import` or `Manual add`.
  Component create/import forms stay open on save failure, surface repository
  errors inline, and reselect the saved component after a successful local
  reload so compact detail flows do not lose context.
- `Home` is the summary landing surface and routes users into inventory or
  movement workflows. Transient action feedback uses a snackbar.
- `Settings` is organized as grouped sync forms and status blocks instead of
  large summary-card stacks, with local-only import defaults and an About
  section. On compact widths it uses a summary/list home plus full-screen
  section drill-down, and on larger widths it uses a persistent section list
  plus detail pane.
- The app writes locally first, queues changed entities, and optionally syncs
  to the FastAPI service.
- JLC import metadata, OCR-derived metadata, and label round-trip hints are
  mapped into existing component fields and appended to `description` so these
  workflows do not require sync API or schema changes.
- Android local storage now also includes an `import_learning_mappings` SQLite
  table for device-only JLC import learning keyed by source SKU with MPN
  fallback reuse.
- JLC text and QR imports are seeded by parser output, then enriched by
  bundled offline recognition rules, device-only learned mappings, and finally
  optional direct LCSC public product metadata. The local rule
  pack now also recognizes more vendor numbering schemes so package and
  category inference can come from model families instead of only raw
  keywords. Canonical component names now stay blank until explicit source
  text, learned mappings, or public product metadata confirms them, so raw model codes
  are no longer written into the saved `name` field by fallback. Local parsing
  now also treats supplier `vendor` fields as a `brand` fallback and rejects
  model-like tokens when persisting learned names. User edits in the import
  confirmation form remain authoritative.
- Android keeps a local cache for repeated public lookups. Server lookup routes
  remain compatibility endpoints for older clients when credentials are available;
  they are no longer part of the normal Android import flow.
- Android build verification completed successfully on `2026-05-08` on the
  current host machine after local SDK and JDK configuration. The current
  Android build baseline is AGP `8.10.1`, Gradle wrapper `8.11.1`, Java 17
  bytecode, SDK Build Tools `35.0.0`, Kotlin `2.0.21`, and Lifecycle `2.9.2`.
  The last full Android `assembleDebug` and `assembleRelease` verification on
  this host completed on `2026-05-16`. After the build-chain refresh,
  `.\android-client\gradlew.bat -p android-client help --no-daemon`
  completed successfully on `2026-05-17`; current local APK assembly is
  blocked on this host because the Android SDK directory is not writable, so
  AGP cannot auto-install `build-tools;35.0.0`. Kotlin and Compose source
  compilation are not the blocker in the current state.
  Preview-focused `Phone`, `Medium`, `Tablet`, `Locale`, `Theme`,
  `Accessibility`, `Shell`, and `Dialogs` surfaces are now isolated under
  `ui/screen/preview/`.

## Versioning

- `docs/CHANGELOG.md` is the repository-wide version source.
- The local pre-commit hook validates version state; it does not consume
  `Unreleased` or generate a stable version automatically.
- On changelog pushes to the default branch, `release-from-changelog.yml` reads
  the latest concrete version heading via `tools/versioning/changelog_release.py`.
  A `-dev.N` suffix selects prerelease; a stable heading synchronizes Android,
  admin-web and Windows metadata to that exact version. Changes limited to
  `Unreleased` do not publish. Existing releases are skipped; a manual run can
  retry a failed build from its existing tag when no release was created.
- Android uses semver for `versionName` plus a monotonically increasing
  `versionCode`.
- Windows package and assembly metadata use the same semver mapped to four-part
  versions as `major.minor.patch.0`.
- The release path is:
  `docs/CHANGELOG.md latest heading -> changelog_release.py -> stable metadata sync if needed -> version tag -> reusable release.yml build and GitHub Release publish`
- Dev tags (`vX.Y.Z-dev.N`) build the same `master` code as prereleases, injecting
  artifact version names and monotonically ordered Android version codes. They
  do not advance the stable changelog or publish Docker Hub images. Windows
  displays the informational version, including the dev suffix, while keeping
  numeric assembly metadata. See [release channels](runbook.md#stable-and-dev-publishing).
- CI explicitly includes branch pushes alongside pull requests. The reusable
  release workflow prioritizes supplied inputs because its event context comes
  from the caller; it must not require an event name of `workflow_call`.

## Windows Client

- Built with WinUI 3 and Windows App SDK.
- Current implementation is a native desktop client backed by local SQLite in
  the user's local app data directory.
- User-facing copy is now organized around Chinese-first WinUI pages while
  retaining Windows-native layout and interaction patterns.
- Release distribution uses a self-contained unpackaged WinUI 3 ZIP; no MSIX
  signing or certificate installation is required. CI checks actual window
  startup as well as compilation, including the archived release layout.
- Navigation uses `NavigationView` with `Home`, `Inventory`, `Records`, and
  `Project BOM` in the main list and one `Settings` footer destination.
- `Home` is the summary landing page. `Inventory` uses a dense desktop workspace
  with search, fixed filters, a continuous list, and a persistent inspector.
- `Movements` is organized as a history-first ledger with compact metrics and a
  right-side audit inspector.
- `Overview` is a summary surface with KPI blocks, low-stock watch entries,
  recent activity, and sync posture instead of acting as the primary edit page.
- `Settings` is organized as grouped sync forms plus diagnostics rather than
  mirrored summary cards. Native About pages identify the author as
  `Rafael-Ikaros`, show installed versions and GPLv3, and retain GitHub Release
  checks and platform download/fallback actions.
- The implemented behavior matches Android at the business level and follows
  Windows-native layout conventions.
- Windows Frame history retains the cached inventory page when entering batch
  inbound. Busy batch operations block Back and sidebar navigation; leaving an
  idle batch persists edits. Dashboard, movements and batch controls reflow at
  narrower window widths. Sync progress and result dialogs have an action guard.
- Startup diagnostics now log fatal launch/runtime exceptions under
  `%LOCALAPPDATA%\ComponentVault\logs\startup.log` and surface the log path in
  a native Windows error dialog.
- Windows build verification completed successfully on `2026-05-09`, and the
  portable publish path was verified successfully on `2026-05-08`.

## Legacy Flutter Reference

- The old Flutter client preserves field naming, workflow intent, and prior UI
  exploration.
- It should be used only as a migration reference, not as the active client UI
  implementation target.

## Target Client Persistence

Native batch JLC inbound uses private atomic JSON drafts with stable package-row
IDs and exact trimmed-payload deduplication. Capture does not perform network
lookup. Existing active SKUs reuse local metadata; new SKUs use bounded serial
catalog lookup. A separate pending screen retains failed or incomplete rows.
Reviewed rows commit component totals, allocations, inbound movements, sync
queue entries and local row receipts in one SQLite transaction. Android uses
`batch_jlc_receipts` (database v5), Windows uses `batch_inbound_receipts`.
Receipts reconcile drafts after interrupted commits and prevent local replay;
they do not synchronize between devices. Draft payloads and receipts are absent
from server/MQTT payloads and inventory Excel exports. See
[batch inbound contract](batch-jlc-inbound.md).

### components

- Primary inventory entity rendered by overview summaries and inventory lists.
- Active records must have unique `sku`.
- `quantity` and `min_stock` are constrained to non-negative values.

### stock_movements

- Immutable-style movement log for inbound, outbound, and adjustment events.
- Each row references a component by `component_id`.
- Movement writes are also responsible for updating the current component
  quantity.

### sync_queue

- Local-only queue of entities pending upload.
- Stores `entity_type`, `entity_id`, `entity_updated_at`, and `created_at`.
- Acknowledged only against the uploaded entity version, or when a remote
  update supersedes that version. New local edits made during network requests
  remain queued.

## Server

- FastAPI exposes a small token-protected sync API.
- FastAPI exposes token-protected inventory snapshots at `/admin-api/*` and
  MQTT configuration GET/POST at `/admin-api/mqtt/config`.
- The server now exposes `GET /admin-api/part-lookup` as a token-protected
  hybrid recognition endpoint that applies bundled or refreshed rule packs
  first and then optionally merges official LCSC metadata when credentials are
  configured or public LCSC product-page metadata when
  `ENABLE_WEB_FALLBACK_RESOLVERS=true`.
- The server also exposes `GET /admin-api/recognition-rules/meta` and
  `POST /admin-api/recognition-rules/refresh` so operators can inspect or
  refresh the active rule pack without changing the sync schema.
- `GET /admin-api/lcsc/lookup` remains available as a token-protected direct
  compatibility proxy to the LCSC OpenAPI, with short in-memory response
  caching and configurable credentials through environment variables.
- SQLite is used for a single-user self-hosted deployment.
- Legacy incoming entities and location metadata use last-write-wins on `updated_at`;
  managed inventory uses a checked `base_updated_at` and a complete allocation snapshot.
- `GET /health` is public.
- `POST /auth/ping`, `POST /sync/push`, and `GET /sync/pull` require the shared
  bearer token.
- Duplicate active `sku` values are rejected with a conflict response.
- CORS is enabled for the configured admin web origins.

## Server Admin Web

- `admin-web/` provides a separated `shadcn/ui` admin console for inventory
  administrators.
- Initial pages cover:
  - dashboard metrics
  - searched and paginated active inventory
  - component details and optional inventory operations
  - recent stock movements
  - runtime and sync posture details
- Inventory reads `GET /admin-api/components` with bounded page/page-size,
  optional low-stock filtering, literal substring matching across SKU, name,
  category and default location, and deterministic
  `updated_at DESC, id ASC` ordering. `GET /admin-api/components/{id}` returns
  reliable component fields and location allocations. Both exclude deleted
  components; the original `/admin-api/inventory` overview remains unchanged.
- Inventory and sync pages emphasize low-stock watchlists and sync posture.
  With `WEB_INVENTORY_ENABLED=false` inventory stays read-only. When enabled,
  authenticated users can create locations and zero-stock components, edit
  metadata, and record inbound/outbound movements.
- The admin web app authenticates with the same shared bearer token already
  used by sync clients.
- Admin writes use `expected_updated_at` compare-and-swap and a persistent
  `admin_operation_receipts` row keyed by `request_id`. Receipt, component,
  allocation, movement, sync revision, and MQTT outbox changes share one short
  transaction. Exact retries return current entity state; reusing a request ID
  for different operation, target, or payload returns HTTP 409.
- It reflects live server SQLite content through dedicated `/admin-api/*`
  snapshot endpoints.
- Inventory query and page state live in the URL. A 401 clears the invalid
  local session, explains why login is required, and restores only an
  allowlisted internal Dashboard, Inventory, Sync or Settings path after token
  validation; inventory query parameters are retained.
- Desktop and narrow/mobile layouts expose the same operations with responsive
  forms, explicit pending/error states, and refreshed component versions after writes.

## Sync Flow

1. The user changes inventory on the client.
2. The client commits the change to local SQLite first.
3. The client appends the changed entity to `sync_queue`.
4. Manual sync or auto sync pushes queued entities to `/sync/push`.
5. The client then pulls remote changes from `/sync/pull`.
6. Remote rows overwrite older local rows by comparing `updated_at`.
7. Matching or older queue entries are cleared after the remote snapshot lands.

## Conflict Model

- Managed inventory uses optimistic `base_updated_at` checks; mismatches reject
  the entire push. Legacy records and location metadata retain UTC timestamp LWW.
- Soft deletes are synchronized as normal entity updates with `deleted = true`.
- Local validation prevents negative stock and duplicate active `sku`.
- Server validation repeats the critical uniqueness and schema checks.

## Entities

### Component

- `id`
- `sku`
- `name`
- `category`
- `package_name`
- `location`
- `description`
- `quantity`
- `min_stock`
- `updated_at`
- `deleted`

### Stock Movement

- `id`
- `component_id`
- `movement_type`
- `quantity`
- `reason`
- `note`
- `happened_at`
- `updated_at`
- `deleted`

## Settings Surface

The native clients persist these sync settings locally:

- `device_id`
- `server_base_url`
- `fallback_server_base_url` (Windows; optional)
- `api_token`
- `auto_sync_enabled`
- `last_synced_at`
- `last_sync_message`

The Android client also persists local-only app behavior settings:

- `default_import_location`
- `last_import_location`
- `default_import_min_stock`
- `remember_last_import_location`
- `sync_after_local_changes`
- `enable_local_auto_recognition`
- `prefer_aggressive_auto_recognition`
- `enable_local_import_learning`
- `enable_server_jlc_lookup`
- `ocr_engine_mode`
- `app_language`

## Cursor sync and direct catalog enrichment (2026-09-15)

The server adds `sync_revision` to each synchronized entity table and maintains
one global counter in `sync_state`. Startup migrates and backfills existing
rows in a transaction without deleting data. Existing orphan movement rows are
retained during migration; all newly opened connections enforce foreign keys.
Push acquires a write transaction before the LWW read/check/write and commits
both entity families and revision allocation atomically. Pull reads the counter
and both families within a single SQLite snapshot. Timestamps use parsed UTC
instants; client modification time is not the replication cursor.

Windows stores nullable `last_sync_cursor` in `sync_settings` in the same
transaction as applied data. Android stores `sync_cursor` in preferences after
its data transaction: interruption before cursor persistence may repeat a pull
but cannot skip unapplied data. Both clients start with a full pull, discard
cursors on server changes, and use full pulls with old servers lacking cursors.
A server switch while a request is pending prevents the old response from
applying. Restoring or replacing a database is outside the cursor's identity
boundary; reset clients to a full pull after such maintenance.

Windows automatic sync debounces startup/local edits and serializes execution
with manual sync. New edits can request one later run; failed requests do not
schedule an unbounded retry loop.

Android `LcscPublicCatalog` extracts exact-SKU Product JSON-LD from a fixed
HTTPS LCSC product URL; it does not execute page JavaScript. `LcscPublicLookup`
provides bounded per-importer caching/backoff, and `InventoryRepository` retains
successful metadata for seven days in its existing local cache. Local parser,
rules, and learned mappings remain usable offline. The public lookup preserves
user overrides and never copies offers,
prices, or supplier stock into the local stock model. Direct query preference
is independent of synchronization and defaults to enabled.

## Project BOM and component-hub migration (2026-09-15)

Android 与 Windows 的 BOM 入口采用文件识别、库存匹配、确认出库的顺序。
列映射默认折叠，异常时调整；选择器同时显示表头与源数据样例。嘉立创
`Comment` 仅作为行说明，`Supplier Part` 与 `Manufacturer Part` 分别用于料号和型号。
无料号、无型号的非空行保留独立源行身份，必须手选库存或显式跳过。
跳过集合属于当前预览状态，原始解析数据保留以便恢复；跳过行不进入聚合扣料计划。
最终出库继续使用原有库存事务与幂等标记；dev18 的预设交换另用独立账户表与接口，不改变库存协议。
可选项目预设以客户端本地 JSON 保存解析后的单套清单和匹配/跳过选择，支持删除。
重新使用时创建新批次并从当前数据库生成库存计划；预设不保存库存快照，dev18 起通过独立预设接口同步，详见下方“BOM 预设同步与配套件”。

Both native clients provide bounded CSV/XLSX readers, exact-SKU and
model/package matching, preview, explicit confirmation and transactional stock
depletion. Duplicate demands are aggregated by final component ID. Each commit
rechecks the previewed version and available quantity before writing any rows;
stock movements, components, pending sync queue entries and the release marker
commit together. Windows uses `bom_consumption_batches`; Android schema version
3 adds `bom_releases`. Both add `component_hub_imports` for local file-fingerprint
idempotency. Android SQLiteOpenHelper upgrades these tables without replacing
existing components or history.

Release and migration markers remain device-local. Inventory sync retains the
existing component and movement protocol; dev18 exchanges reusable BOM presets
through a separate account-scoped endpoint, without synchronizing a production
batch ledger. Managed snapshots reject conflicting concurrent writes rather than
merging offline decrements. A production batch should be released
on one device and synchronized before using another device for the same stock.

Migration accepts the upstream `{components: [...]}` backup envelope, maps
`productCode`, `stock`, `threshold`, `encapStandard`, category and location, and
preserves source metadata in descriptions. It generates fresh local IDs;
positive starting stock also receives an inbound movement. Duplicates block by
default or are explicitly skipped. Input files and existing components are not
overwritten. See [BOM and migration guide](bom-and-migration.md) for supported
columns, file limits, unavailable XLS support and migration scope.

Both clients use Chinese public catalog records, exact-SKU fallback Product JSON-LD,
and trusted image hosts for catalog
enrichment. Official classifications precede heuristic guesses; recognized
English paths have Chinese display mappings and the original category path is
retained. Images use the portable `商品图片：URL` description convention. Lazy
image lookup changes presentation caches only, never component stock snapshots.
Normal Android imports no longer call the server recognition endpoint; old
server routes remain for older clients. OCR captures ImageCapture photos with
sensor orientation; the unavailable Paddle choice is no longer exposed.

Movement quantities remain stored as magnitudes for inbound/outbound and signed
deltas for adjustments. UI `quantityChange` / `QuantityChange` derives the sign
from the movement type, fixing outbound history without rewriting stored data.
Transfers carry source and destination location IDs and have zero quantity change.

## Multi-location inventory and Excel exchange

The native databases add `storage_locations`, `component_allocations`, and a
component `base_updated_at`. Android upgrades to schema version 4; Windows uses
column/table migration guards. An upgrade backup is retained before migration.
Existing location strings become stable location IDs. Active SKU uniqueness is
unchanged. Allocation quantities are non-negative and sum to component quantity;
all writers (forms, movements, BOM, migration and pull) maintain this invariant.

Native location creation and editing are distinct operations: creation rejects
an existing primary key (including tombstones), while editing only renames an
active location. Each check and write shares a transaction; neither operation
replaces a location row or modifies allocations. Server admin creation already
rejects duplicate IDs. Sync continues to apply location metadata using LWW;
this local creation guard does not change the sync protocol or database schema.

The server stores the same allocation relation and an `inventory_managed` flag.
The wire protocol carries full allocations inside each component snapshot and
complete location metadata on pull. This prevents independent per-bin LWW writes
from violating component totals. New clients require `inventory_protocol=1`,
and server CAS checks reject stale inventory bases atomically. New local edits
during a sync retain their queue entry and use only the acknowledged base version.

Both clients share the five-sheet `component-vault` Excel schemaVersion=1 format,
plus a reader for LCSC_android_erp's distinct schemaVersion=1. Preview validates
data before a transaction imports new records; existing IDs/SKUs are not overwritten.
Own history is retained; foreign workbooks lacking history produce explicit initial
inventory movements. Local images can be embedded without network access during export.
See [storage and backup guide](storage-and-backup.md) and the exact shared columns
in [implementation record](inventory-expansion-plan.md).

## Inventory statistics and MQTT publication

Each native client runs a full-history SQLite aggregation of non-deleted
`outbound` magnitudes, grouped by component ID once per refresh. The recent
movement list's 200-row limit does not affect these totals. Donuts and detail
statistics use current quantity plus recorded outbound quantity as the
denominator, with zero-safe ratios and 64-bit totals. Adjustments change current
quantity but are not counted as outbound; missing pre-migration history is not
reconstructed. Android detail reuses the trusted product image cache at a larger
display size; Windows detail retains its existing product image.

The FastAPI lifespan owns an optional single MQTT publisher. Accepted component
upserts and their exact state snapshots enter `mqtt_outbox` within the same
`BEGIN IMMEDIATE` transaction. Stale LWW writes and rolled-back pushes produce
no messages. A background worker reads the oldest revision, publishes retained
QoS 1, then deletes only after PUBACK. Retries preserve event IDs; consumers
must tolerate duplicates. Broker I/O happens outside request handling and uses
separate SQLite connections. Client writes and sync payloads are unchanged.

`mqtt_state` tracks snapshot initialization and the destination identity
(host/port/TLS/prefix, excluding credentials). First enable, re-enable after a
disabled startup, or changing destination seeds current components including
tombstones. A destination change replaces pending old-destination events with
current snapshots. Existing broker retained messages are not removed.
The publisher supports one API process, not multiple Uvicorn workers.
The authenticated `/admin-api/mqtt/status` reports connection and queue state;
it does not expose credentials. The existing admin-web Settings page adds
publisher status and a connection form, without adding a route. GET/POST
`/admin-api/mqtt/config` read/save a single `mqtt_configuration` SQLite row.
Saved MQTT settings override environment defaults at the next process startup;
saving does not hot-swap the publisher. Configuration responses omit passwords
and report whether a restart is required. Optional inventory writes are
independently gated by `WEB_INVENTORY_ENABLED` and do not change MQTT settings.
See [MQTT](mqtt.md).

Native Settings / About use standalone GitHub Release parsers and anonymous
HTTP clients, independent of inventory sync and its bearer token. A device-local
update-channel preference defaults to Stable; Dev also considers prereleases.
Semantic version comparisons, channel filtering, bounded responses, timeouts and
repository-specific asset URL validation determine the update UI. Android uses
DownloadManager for user-initiated APK downloads, with one persisted task in
SharedPreferences and an app-specific download directory. The About pane resumes
progress display, cancellation, retry and explicit cleanup after recreation.
Installation uses a granted content URI and the Android system installer after
an explicit click; unknown-source permission is requested only at installation.
Completed upgrades clean the old task when the update pane is opened. Windows
retains browser ZIP downloads. No automatic version polling, silent installation
or inventory database changes are introduced.
See [application updates](app-updates.md) for platform-specific installation.

## Catalog-name compatibility and native connection routing

Catalog metadata keeps the model and official description separately. New imports
prefer the model for the editable component name; user-customized names are not
rewritten. `ComponentPayload.name` accepts 1–4000 characters so earlier imports
containing long descriptions can synchronize without truncation. SQLite stores
names as TEXT, so this API compatibility change needs no database rebuild.

Android stores an optional external URL in its existing preferences. Each sync
first probes the configured primary address with `/auth/ping`; when a distinct
fallback is present the primary connect/read timeout is 3 seconds. Only transport
failures trigger the external probe. HTTP authentication/validation and malformed
response errors remain visible. Once selected, the endpoint is fixed for that
push/pull cycle; a failure preserves the local queue. The next cycle retries the
primary. Routing does not modify the saved primary URL or reset the sync cursor.
Both addresses must refer to the same service/database. Windows now follows the
same boundary: it probes primary first, falls back only for DNS, connection, or
timeout failures, fixes the endpoint for the complete push/pull run, and never
switches and replays a failed write. Its optional address is added to older
settings databases with an empty default, and sync status shows the endpoint used.

BOM automatic matching requires exact SKU when provided, otherwise exact model
and optional package. Ambiguous matches require selection. Android preserves raw
matching rows separately from aggregated commit rows; Windows retains selection
groups for original rows. Editing a match remains possible after several rows
have been assigned to the same inventory item. Stock validation and deductions
use aggregated quantities, preserving the existing transactional commit path.
Both clients let the user map SKU, model, package, quantity, name, and reference
source columns before preview. Missing results can be exported as UTF-8 CSV with
an Excel UTF-8 BOM; export is read-only and contains SKU, model, required,
available, missing quantity, and match status.

## 库存搜索、官方规格与构建诊断

Android、Windows 和 Web 库存检索覆盖名称、SKU、分类、封装、库位与描述（含品牌和
官方参数）。空格词采用 AND；归一大小写、常见型号分隔符、微单位及欧姆写法，保留小数点。
Web 在 SQLite 查询函数中执行过滤，总数与分页使用同一条件，不复制库存到额外搜索索引。
原生客户端新增导入的 R/C/L 名称使用短型号、官方主值和精度；完整官方资料仍保存在描述，
已有自定义名称不批量回填。旧库存摘要只读取已保存的官方字段，不推测型号编码对应的规格。

Docker API 镜像通过 build args 写入发布 tag 和 checkout revision，`/health` 与管理设置
显示构建信息。身份校验仍以 `/auth/me` 的服务器/账户 ID 为准，不用版本字符串代替身份。
Android 和 Windows 的连接测试执行协议与账户接口检查而不保存绑定；正式同步保留原绑定保护。


Android 与 Windows 的批量库位转移使用现有 allocations 和 transfer 流水，不增加库存 schema。
原生端在一个本地事务内先核对所有来源、目标、复核版本与数量，再写全部分配、流水及同步队列。
源库存不足或复核过期时不提交部分结果。Android 左滑直接展开入库、出库、转移、删除四个按钮；单按钮宽 48–56 dp，保留可见卡片主体。点卡片或右滑收起，不以完整滑动触发操作，多选时关闭滑动。入/出弹层通过 InventoryViewModel.recordMovement 复用本地事务及同步队列；转移复用单项 BatchTransferDialog 和原有事务。提交期间禁止重复点击，失败保留输入；删除沿用软删除并二次确认。
Android 以“选择”进入批量操作，转移表单滚动、复核提交固定可见；Windows 使用原生列表多选与上下文命令栏。

标签工具入口只选择起始模板，再交给已有编辑器。自由标签由独立数据与 LabelDesign 组成，
与库存 seed 二选一；没有为通用标签创建虚拟元件。队列恢复保留旧任务，中断项仍需人工核对。
编辑器以适屏画布为中心，选中元素后编辑内容；纸张、元素位置和元件选择使用按需面板。打印准备与队列属于同一编辑器，继续复用既有渲染和 M1 传输流程。

## 元件参数与名称分离（0.7.7-dev.3）

`/admin-api/lcsc/lookup` 与 `/admin-api/part-lookup` 新增可选 `description` 和 `parameters`（键值对象，缺失为空对象）。参数只取上游明确值；无参数时不从型号解码。默认展示名称使用品牌和型号，长商品说明进入 `description`。

Android 和 Windows 将官方参数保存在既有元件 `description` 的参数行中，保持现有同步和备份格式；无需新增数据库字段。客户端基本信息与 Web 详情解析中英参数行，检索覆盖原值、单位及常见字段别名。已有名称不批量改写，旧记录未保存过的参数仍需要重新联网查询。

## 旧库存参数维护工具

Web 库存页的 `SpecEnrichmentPanel` 使用 `/admin-api/components/spec-enrichment/*`，按已认证账户预览、启动、恢复、取消和重试参数补全。服务端 `EnrichmentJobs` 逐项查询明确的 LCSC SKU；优先配置的 API，否则查询公开详情，独立于自动识别 fallback 开关。仅保存官方明确参数及描述，不改名称、数量、库位或流水。

查询不持有写事务；回写以 updated_at/SKU/删除状态复核，复用 `save_sync_payload_in_transaction` 和原有账户同步 revision，因此原生客户端可通过增量同步获取。任务进度为进程内状态，每账户只保留最近完成任务及活动任务，提供清除入口；重启不自动重跑，已完成资料持久化，其余由候选重新选择。完整行为和部署边界见[旧库存参数补全](parameter-enrichment.md)。

## 会话、设备与审计（0.7.7）

- 管理员库中的 `web_sessions` 保存随机会话令牌的摘要、对应账户/密钥摘要和 12 小时有效期。浏览器持有 HttpOnly、SameSite=Lax Cookie；HTTPS 时加 Secure。Web/API 需同站点，允许来源仍精确匹配。非只读 cookie 请求验证 CSRF；原生 Bearer 认证不变。
- 账户数据库新增 `sync_devices`、`sync_audit`、`sync_conflicts`、`sync_conflict_resolutions`、`sync_entity_sources`，启动时幂等迁移。登记从新版本开始；推送携带 device_id，拉取可附 `X-Component-Vault-Device-Id`。两端原生客户端已附此请求头。
- 成功写入和审计在短事务中提交；失败的 CAS 推送回滚后保存冲突快照及失败审计。legacy 旧版本/同时间戳异值被丢弃也留记录。没有共同基线的 LWW 不能完整检测所有并发覆盖，不把正常新版本覆盖伪造为冲突。
- 处理冲突接口只追加“保留服务端/已从客户端重新提交”的处理记录、账户、时间和备注，不盲目重放旧快照。需要实际修改时重新拉取、编辑、同步。
- 删除普通账户先撤销访问并取消该账户参数补全，待任务停止后删除其数据库与 SQLite sidecar，再删除注册记录和会话。失败保留停用状态可重试；管理员原库与其他用户数据不动。已有本地客户端副本不会远程删除。
- 设置关于仅检测更新，容器更新交给部署者选择的 Watchtower；应用本身不挂宿主 Docker socket。
- Android 标签工具复用既有编辑器/队列；新增模板与选纸步骤，自定义尺寸遵循当前 M1 打印头范围。保存草稿的旋转不强制归零，只有新建纸张为 0°。记录页过滤是展示层行为，不修改流水或同步墓碑。

### Android 库存扫码测试

CI 在原有回归组前单独运行 `MovementLabelResolutionTest`、`QuantityStepperTest`、`ImportLearningPersistenceTest` 和 `ImportMappingPriorityTest`，
分别保存 `android-stock-scan-tests` 日志和测试报告。直接检查最多运行 10 分钟，
原有回归组最多 15 分钟，避免再次静默等待到 GitHub 的 6 小时任务上限。
直接检查成功、完整 CI 成功和实机验收分别报告，不互相替代。

## 0.7.8-dev.7 库存修复与导入

- Android / Windows 编辑元件时，主库位变更会在原有短事务中清零其他 allocations，并将 draft.quantity 写入目标 allocation；总量与各库位之和保持一致。显式部分转移语义不变，不新增 schema。
- Android 库位选择只显示 name，内部持久化 id；选择与创建分开，创建生成内部码且拒绝重复名称。无供应商 SKU 的编辑草稿保存前生成 CV-UUID，不改变已有 SKU。
- Android CategoryLearningStore 按官方完整分类路径保存用户修正，跨 SKU 复用；User/Learned 字段仍优先。清空学习记录同时清理该映射，不依品牌猜分类。
- 立创订单 BIFF .xls 使用 JExcelAPI 解析，提取 C 料号、品牌、型号、商品分类、封装、订购数量。配送费/汇总不入库。订单号与 SKU 构成稳定 order- 回执键，复用 batch_jlc_receipts 跨本机导入会话去重；不传输原始订单表，不构建跨设备订单账本。
- 手工照片复用 InventoryImageStore 的本地图片标记与 Excel 图片工作簿能力。父编辑器管理暂存图片生命周期，取消清理新图，成功保存保留引用；服务器同步 description 标记但不传图片二进制。
- Web DELETE /admin-api/components/{id} 复用账户数据库、Web 写入开关、版本检查和请求回执，在事务内保存删除墓碑；下次同步传播删除，流水保留。


## Android BOM 会话、采购提醒与库位标签

- `BomImportSession` 使用 Activity 的 ViewModel 生命周期保留旋转期间的文件字节、匹配决策、预览及提交 ID；大文件不放入 Bundle。显式离开提供保留本次进度或放弃，永久复用仍使用项目 BOM 预设；不保证强制停止或进程被系统回收后的临时进度恢复。
- `BomProcurementStore` 将本机项目的采购提醒写入 `filesDir/bom-procurement.json`；以项目来源和需求标识幂等保存。它不参与服务端同步、库存 Excel 备份或库存预占。标记仅为 `InTransit` 和 `ArrivedNeedsReview`，到货后由用户核对数量并清除提醒。
- 实际单件导入、追加和批量/订单导入事务成功后才检查采购提醒；跳过重复包装和失败入库不产生到货。匹配按精确 SKU，缺少 SKU 时要求精确型号和封装，避免模糊匹配误提醒。提醒保存失败不会将已成功的库存写入误报成失败。
- `LocationQrCodec` 使用独立 `cvl-loc1|` 格式，只携带编码后的本地稳定库位 ID，不携带服务端路径、密钥或查询地址。扫描后验证当前有效库位并设置筛选，不改变库存。元件码仍交原有元件解析器处理。
- 库位管理的打印操作直接打开原有自由二维码标签编辑器；元素选框只在编辑画布显示，不加到实际标签渲染。蓝牙传输和走纸协议保持不变。
- 库位筛选考虑正库存分配；库存为零时保留主库位归属供补货查询。加载库位时清理已失效的“上次导入库位”引用，保留用户配置的默认仓位。

## BOM 预设同步与配套件

原生客户端 `BomPresetStore` 使用 `{version:2,presets:[...]}` 原子文件，旧格式成功解码后先备份再迁移。公共 content `schema_version:1` 保留稳定预设/需求 ID、工作表与源行、每套用量、套数、人工选择和跳过项。`selection=null` 表示自动匹配，空字符串表示明确不匹配，非空字符串为库存 component ID。Android 合并源行与 Windows 独立源行转换时，按需求行用量计算一次，不能按 source_rows 再累加。

服务端为每个已认证账户数据库增加 `bom_presets` 表。独立 `POST /sync/bom-presets` 在原库存同步及身份绑定验证后执行，短事务按 ID 与 UTC updated_at 执行 last-write-wins（LWW），返回完整快照及墓碑；同时间服务器记录胜出。预设冲突不逐字段合并，也没有人工冲突合并界面。客户端保留请求发出后的本地编辑，等待下次同步。预设失败单独显示，不回滚已完成的库存交换；库存协议仍为 1。服务器健康和身份检查新增 `bom_preset_protocol:1` 能力字段。

`GET /admin-api/bom-presets` 仅返回当前账户，管理台 `/bom-presets` 只读。预设交换不会写库存数量、流水或库存 revision。账户数据删除沿用既有独立数据库清理。

Android `KittingScreen` / Windows `KittingView` 在本地保留当前会话草稿，复用已有 BOM planner/原子提交器和 releaseId 去重。未确认只做匹配与库位计划，不预占库存；确认时重新校验数量、版本和分配。应用结束后的草稿持久恢复未实现。预设不包含图片，不改变现有 Excel 库存备份范围。


dev18 直接验收记录：真实 Windows 服务将共享预设发送到隔离 FastAPI，Android 手机同步并保留人工选择和跳过项，再由 Windows 拉取核对。手机明确确认一次仅扣减 6 和 2，重试没有额外扣减；Windows 删除预设后手机列表清空，库存仍为 14/18。Web 浏览器确认只读列表、详情、每套 3 × 2 = 6、明确未匹配、跳过及中英文切换。该记录覆盖本次路径，不代表全部设备或异常场景回归。

## dev19 元件照片与分项同步状态

照片沿用已有内容摘要标记及元件版本关联，库存 SQLite/schema 不迁移。原生库存同步完成后独立交换照片，BOM 预设仍为独立 phase；已成功阶段不回滚，失败阶段显示部分完成并由下一次同步重试。照片状态持久化成功项、计数与最近成功时间，中断后不能误报完成。

服务端 `app/photos.py` 从认证账户数据库推导引用，文件为 `<database_path>.photos/<key>`，不引入通用附件系统或外部存储。整文件临时写入+原子替换，网络 I/O 不占库存事务。原生客户端原图与下载缓存分离，展示和 Excel 通过同一解析入口读取二者；清缓存不删除原图，失效下载与临时文件可回收。Web `ComponentPhoto` 使用会话获取 Blob 并回收 Object URL，图片缺失、失败、重试均有明确状态。

使用与备份边界见[元件照片跨设备同步](photo-sync.md)。本次不提供照片人工冲突合并或离线 Web；供应商公开图片保持既有独立规则。


## dev20 同步运行状态与中断恢复

- Android 使用既有 SharedPreferences，Windows 使用数据库旁的 `sync-run-state.json` 记录本轮状态、阶段、库存/BOM 的范围成功时间与已确认预设版本。它们是进度元数据，不改变库存 schema、服务器 API 或本地库存优先的方式。
- 应用启动只将遗留 Running 恢复为 Interrupted；普通状态读取不能把当前仍在执行的任务误报中断。取消沿用本轮取消令牌并终止当前网络请求；重试复用原幂等交换和现有队列，不回滚成功库存，也不自动重新出库。
- 库存提交在附件阶段前完成。库存 pending 来自本地队列，BOM pending 比较当前 ID/updated_at 与服务器已确认版本，照片按当前引用和原有 manifest 推导；请求期间的新本地改动保持待处理。旧服务器/账户/配置范围的完成时间不显示成当前配置成功。
- UI 摘要和设置复用同一状态来源，分别标明库存/BOM/照片最近完成时间。人工冲突合并、自动后台恢复和跨设备生产批次账本仍不在范围内。
- 库存偶尔为空尚无证实的源码根因；仅在数据发布和实际库存列表渲染时加入脱敏计数诊断，记录数目及筛选激活形状，不记录搜索词、元件、库位名或凭据。诊断沿用当前进程的有界反馈缓冲，复现后应在关闭应用前导出反馈。

## Windows 订单复核与资料更新边界（2026-10-28）

`BatchInboundReviewState` 独立处理订单资料身份、来源警告和草稿恢复。`BatchScanItem` 追加 `MetadataSku`、`SourceWarning`、`SourceWarningConfirmed` 可选状态；旧 JSON 草稿按已有资料和错误恢复，不清空用户草稿、不修改 SQLite schema。可编辑行修改 SKU 时清空旧名称/分类/封装/描述并撤销旧确认，查询只补齐资料，明确的人工确认才解除来源警告。

`.github/workflows/ci.yml` 明确运行 `ComponentVault.WinUI.CoreTests`、`Tests/JlcOrderImport`、`Tests/ReleaseNotesMarkdown`；`release.yml` 发布候选增加后两个独立测试，各项目测试职责不同；生产项目排除 `Tests/**`。Markdown 根目录 `README.md` 链接按仓库根目录解析，既有 `dockerhub.md` 等文档链接保持 docs 语义。

Issue #25 已实现为 `server/app/admin/order_metadata.py` 与 Web 库存页 `OrderMetadataPanel`。预览使用 xlrd 读取 BIFF `.xls`；按精确 SKU 匹配认证账户库存，无原始文件持久化。应用接口仅接受分类、封装和参数字典，逐项短事务重读当前行、校验 `expected_updated_at`，复用现有幂等收据及同步写入。保留名称、数量、库位、分配、图片和未选备注；参数只替换选定键的中英文同义参数行。没有新增库存表或同步协议版本；原生客户端按现有增量同步取得修改，受管库存并发冲突语义不变。详见 [使用与边界](order-metadata-update.md)。
