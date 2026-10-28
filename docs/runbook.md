# Runbook

## 0.7.8 正式版部署

0.7.8 汇总前期 dev 的 BOM 预设同步、原图同步及订单资料更新。正式版配套发布 API `rafaelikaros/component_manager:0.7.8`（浮动标签 `latest`）和 Web `:web-0.7.8`（`web-latest`）。先核对发布流水线及 Docker Hub 对应标签，再升级原有项目；不要新建数据卷或清空配置。

停写并备份整个 `/data`，包括各账户数据库、配置与 `.photos` 目录；客户端本地备份还需单独保留 BOM 预设 JSON。保持原 Compose 项目名和挂载，执行：

```sh
docker compose -f docker-compose.hub.yml --profile web pull
docker compose -f docker-compose.hub.yml --profile web up -d
docker compose -f docker-compose.hub.yml --profile web ps
```

仅使用 API 时省略 `--profile web`。旧 `.env` 若固定了镜像版本，应将 API、Web 成对改为新固定版本或 `latest` / `web-latest`。群晖与可选 Watchtower 操作见 [Docker Hub 指南](dockerhub.md)，新版 Web 的订单资料功能仍需启用 Web 库存操作。

## Docker Hub image deployment

Use `docker-compose.hub.yml` to pull a published API image instead of building
the server locally. Copy `.env.example` to the root `.env`; an existing deployment
may continue supplying its `API_TOKEN` there. The Compose defaults use the
floating API `latest` and Web `web-latest` tags. Check their
current versions on Docker Hub before pulling. Override both image variables
to select a fixed, published release, such as `0.7.8` and `web-0.7.8`.
The server image listens on port 8787 and stores SQLite under `/data`; preserve
the Compose project name and `component_vault_data` volume when upgrading.
The admin Web app remains a separate image. Start the optional profile with
`docker compose -f docker-compose.hub.yml --profile web up -d`. API `0.7.4`
and Web `web-0.7.4` were published in the same repository; floating tags can
move with later stable releases. Historical verification details are in the
Docker Hub guide.

Repository maintainers configure Actions variables `DOCKERHUB_USERNAME` and
`DOCKERHUB_IMAGE`, plus secret `DOCKERHUB_TOKEN`. CI always validates a container
without registry credentials. Release automation pushes only when both variables
and the token are configured; otherwise it explicitly skips Docker publishing.
A manual `Server Docker Image` run with `push_image=true` can publish an existing
release afterward; normal CI never pushes an image. Follow the complete
[Docker quickstart and API Token lookup](docker-quickstart.md), or the complete
[Docker Hub setup, verification and upgrade instructions](dockerhub.md).

### Optional Watchtower updates for the Hub deployment

The base Hub Compose file does not start an updater. For opt-in updates of
`api` (`latest`) and `admin-web` (`web-latest`), place
[`docker-compose.watchtower.yml`](../docker-compose.watchtower.yml) beside the
existing Hub file and `.env`, and retain the original project name and `/data`
mount. Back up the entire data directory consistently **before** enabling
updates, including `users/` and `config.json`; schedule recurring backups
before the update window. The original
[`containrrr/watchtower`](https://github.com/containrrr/watchtower) is
archived. The override explicitly uses the maintained
[`nicholas-fedor/watchtower` fork](https://github.com/nicholas-fedor/watchtower)
(`nickfedor/watchtower:latest`); check Docker Engine/API compatibility on the
host. It adds enable labels only to the existing API and Web services, sets
`WATCHTOWER_LABEL_ENABLE=true`, and mounts the Docker socket only into the
updater. It does not enable in-app updates or change the database volume.

```sh
docker compose -f docker-compose.hub.yml -f docker-compose.watchtower.yml --profile web config --quiet
docker compose -f docker-compose.hub.yml -f docker-compose.watchtower.yml --profile web up -d
docker compose -f docker-compose.hub.yml -f docker-compose.watchtower.yml --profile web logs --tail=100 watchtower
```

Omit `--profile web` for API-only installations. Reuse `-p <original-project>`
on every command if the deployment used it. On Synology Container Manager,
merge the override labels and `watchtower` service into the **existing** project
Compose definition, then redeploy the project; container restart alone does
not apply labels or a new image. Keep the original bind mount or named volume.
Watchtower checks once per day and recreates selected containers when floating
images change. API and Web updates are independent, not atomic. After a change,
check Compose `ps`, `logs api`, `/health` `version`/`revision`, and
`compose images api admin-web` against the paired Hub tags; then verify browser
login and inventory read. Stop Watchtower and restore a
compatible API/Web pair from the consistent backup if necessary. Automatic
updates have not been verified on a NAS in this change. Full steps are in
[Docker Hub deployment](dockerhub.md#可选-watchtower-自动更新).

### 排查镜像与客户端账户接口不一致

客户端提示服务端过老时，先按 [Docker 排障步骤](docker-quickstart.md#7-客户端提示服务端版本过老或无法确认账户身份)
核对实际容器与客户端 API 地址。镜像构建把发布 tag 与实际 checkout commit 写入
`/health` 的 `version` / `revision`；源码启动明确显示 `source` / `unknown`。
容器 CI 也会验证认证后的 `/auth/me`，避免仅凭旧 `/auth/ping` 成功就放行。
Web 读取 API 设置 15 秒超时，对地址、CORS、HTTPS/HTTP 混用及缺少账户接口提供提示。
版本元数据不改变账户身份或同步协议。

## Admin console deployment and API token

The first setup token is the administrator account key. Keep it for deployment
and account management; issue a distinct ordinary key per user's inventory from
Web Settings. The user's Android, Windows and Web sessions use that same key.
Creation/reset shows a key once; copy it for the user. If lost, reset the key;
disabling or rotating access does not delete stock. There is no public signup.

Back up the entire `/data` directory consistently while writes are stopped,
including the original administrator database, its account registry, `users/`,
configuration and logs. A backup of only `component_vault.db` does not contain
ordinary users' inventory. Keep the same mount on upgrade. Ordinary accounts
need account-aware native clients; older admin clients remain usable. A native
workspace identity mismatch must be resolved with a separate local workspace or
an explicit export/reset process, never automatic inventory deletion.

Web language is selected in login/settings; `/setup` has its own selector.
The preference is saved in each browser origin, not as a server-wide language.

The new Web login sends the account key once to `POST /auth/session`; browser login state in
localStorage retains only the API URL. The API issues a 12-hour `HttpOnly`,
`SameSite=Lax` cookie, and logout revokes the session. Native Android and
Windows clients still authenticate with `Authorization: Bearer <account-key>`.
Serve Web and API from the same site (the same scheme and hostname; ports 8081
and 8787 may differ). Because those ports are different origins, configure the
exact Web `scheme://host:port` in `ADMIN_WEB_ORIGINS`; credentialed CORS does not
work with a wildcard. After changing an environment-provided origin, recreate
the API with `compose up -d`; `restart` does not reload `.env`. A saved origin
in `/data/config.json` is read dynamically. Ensure a reverse proxy forwards
`/auth/session`, `/auth/me`, and `/admin-api/*` to the API.
The admin console is read-only by default. Set `WEB_INVENTORY_ENABLED=true` on
the API only when authenticated browser users should create locations and
components, edit metadata, and record inbound/outbound movements. Writes use
`expected_updated_at` concurrency checks and persistent `request_id` receipts;
quantity and allocations cannot be changed through the metadata form.
Turning the flag off blocks later browser writes but does not reverse completed
inventory movements. Exact request retries are safe; after a 409 stale-version
response, refresh the component before deciding whether to submit a new request ID.

For a new Docker deployment, copy the repository-root `.env.example` to `.env`
and leave `API_TOKEN`, `ADMIN_WEB_ORIGINS`, `ADMIN_WEB_URL`, and `WEB_INVENTORY_ENABLED` empty.
Confirm the versions currently behind `latest` and `web-latest` before deploying;
first-run `/setup` requires API 0.7.1 or later.
Start the API, then open `http://server:8787/setup`; the same-origin first-run
page stores these settings under `/data/config.json`. Existing deployments may
keep non-empty environment values, which take precedence and remain read-only in
the setup UI. To update Hub images, run `docker compose -f docker-compose.hub.yml --profile web pull`
followed by `docker compose -f docker-compose.hub.yml --profile web up -d` when the
Web profile is enabled; omit `--profile web` for API-only deployments. `restart`
alone does not pull new images or reload changed `.env` values. The `ADMIN_WEB_URL`
entry and direct server configuration editing in the Web console are 0.7.4
features. Verify that both images contain 0.7.4 before using them; the floating
tags do not establish that until the stable Docker images are published.
Set the actual Web console URL in the setup page, including any reverse-proxy
subpath. After the first save, copy the API token before selecting **Enter admin
console**; the console asks for that token again. If the Web console is not
deployed, leave its URL empty and continue to use authenticated `/setup`.
Authenticated users can later edit deployment settings from the Web console.
A `.env` in `server/` is not the Compose root environment file. For a direct
server launch, explicitly export the variables or run uvicorn with
`--env-file .env` from `server/`; editing a file alone does not update a running
process. The bundled requirements include support for uvicorn environment files.

The web console is served on port 8081 and the API on port 8787. On another phone
or PC, use the server host address, not `localhost`. An optional
`VITE_DEFAULT_API_BASE_URL` configures the public API default during web build;
without it, the web login suggests its own host with port 8787. HTTPS reverse
proxy deployments should configure their HTTPS API URL and allowed web origin.

### API token discovery

The token is the deployment's `API_TOKEN`, not a GitHub access token and not a
random value generated on every startup. Check the authenticated `/setup` page,
Compose root `.env` / container environment, `/data/config.json`, or the
environment used to launch uvicorn. Both native clients and the web login use the
same value. Only the unconfigured first-run state permits anonymous setup; after
save, viewing or changing settings and logs requires the current token.
After changing the server token, update client settings and sign in again.

### Upgrade from the HTTP 422 long-name failure

Upgrade the server as well as the client. Component names imported by previous
versions may contain product descriptions longer than 200 characters. The server
now accepts names up to 4000 characters, preserving their full contents. New
catalog imports prefer the model and store official product descriptions in the
description field. Existing custom names are not rewritten. Retry synchronization
after upgrading; clearing app data is unnecessary and would discard local data.

## Multi-location inventory upgrade

Upgrade the FastAPI server before native clients. `/health` and authenticated
`/auth/ping` must report `inventory_protocol: 1`. New clients refuse to upload to
servers without that capability, preserving their local queue. All clients that
write a managed component must support the new protocol.

The server keeps `<database filename>.pre-inventory-v1.bak` before upgrading an
existing database. Native clients also retain private pre-upgrade backups. Keep
these when rolling back: older binaries must not write the new inventory format.
Backups may contain private application data and belong with the database.

HTTP 409 inventory conflicts indicate a stale `base_updated_at`, not a transient
network failure. The push rolls back as a whole; do not clear a client's queue to
hide the error. Preserve client Excel/database backups and compare local changes
with the server's current inventory before reconciling. No server merge of offline
stock deltas or global BOM idempotency is implied.

Excel restore and LCSC_android_erp migration instructions are in
[storage-and-backup.md](storage-and-backup.md). These import new records after
preview, and do not reset server credentials or silently replace current stock.

Native UI layout and navigation are documented in
[native-ui-redesign.md](native-ui-redesign.md). Android and Windows About pages
show author Rafael-Ikaros and default to stable Releases, with an opt-in Dev channel.
CI runs the native build checks, including UI timestamp regression coverage;
the publication job identifies its repository through `GH_REPO`.

## Local Backend Development

Use the project-local virtual environment inside `server/.venv`.

```powershell
cd server
.\scripts\bootstrap.ps1
.\scripts\run-dev.ps1
```

Run tests with:

```powershell
cd server
.\.venv\Scripts\python.exe -m pytest
```

`.\scripts\bootstrap.ps1` now repairs a broken or missing Windows
`py -3.12` launcher registration by falling back to a repo-local
`uv`-managed Python 3.12 under `.uv-python/` when `uv` is installed.

Verification snapshot on a healthy local toolchain (`2026-05-08`):

- `.\.venv\Scripts\python.exe -m pytest` -> backend suite passed
- `/admin-api/dashboard` smoke request with token -> `200 OK`

## Local Admin Web Development

The separated admin web console lives in `admin-web/`.

```powershell
cd admin-web
cmd /c npm install
cmd /c npm run dev
```

Default local addresses:

- FastAPI API: `http://localhost:8787`
- Admin web: `http://localhost:5173`

## Docker Deployment

Start the bundled single-user stack with:

```powershell
docker compose up --build
```

The compose file publishes `8787` and stores the SQLite database in the Docker
volume `component_vault_data`.

Admin web is available at:

- `http://localhost:8081/`

## Environment Variables

### Server

- `API_TOKEN`: optional environment-provided shared token for authenticated endpoints
- `DATABASE_PATH`: SQLite file path; Compose fixes it to `/data/component_vault.db`
- `CONFIG_PATH`: optional override for `config.json`; defaults beside SQLite
- `LOG_DIR`: optional application log directory; defaults to `logs` beside SQLite
- `APP_HOST`, `APP_PORT`: host and port for direct local development; the
  container command already binds `0.0.0.0:8787`
- `ADMIN_WEB_ORIGINS`: comma-separated origins allowed to call the API from the
  separated admin web app
- `ADMIN_WEB_URL`: optional absolute HTTP(S) URL of the deployed Web console,
  including its path. Non-empty values override the setup page setting; an empty
  value leaves it editable in `/setup` and Web console Settings.
- `LCSC_OPENAPI_KEY`: optional LCSC OpenAPI key used by
  `/admin-api/lcsc/lookup`
- `LCSC_OPENAPI_SECRET`: optional LCSC OpenAPI secret used by
  `/admin-api/lcsc/lookup`
- `LCSC_OPENAPI_BASE_URL`: optional LCSC OpenAPI base URL, default
  `https://ips.lcsc.com`
- `LCSC_LOOKUP_CACHE_TTL_SECONDS`: server-side in-memory cache TTL for official
  lookup responses, default `43200`
- `IMPORT_RULES_REMOTE_URL`: optional JSON URL used to refresh the server-side
  recognition rule pack consumed by `/admin-api/part-lookup`
- `IMPORT_RULES_REFRESH_HOURS`: refresh age threshold for the cached rule pack,
  default `24`
- `ENABLE_WEB_FALLBACK_RESOLVERS`: enables public LCSC product-page fallback
  for `/admin-api/part-lookup` when OpenAPI credentials are unavailable,
  default `false`
- `WEB_INVENTORY_ENABLED`: enables authenticated Web inventory writes, default
  `false`
- `MQTT_ENABLED`, `MQTT_HOST`, `MQTT_PORT`, `MQTT_TLS`, `MQTT_USERNAME`,
  `MQTT_PASSWORD`, `MQTT_TOPIC_PREFIX`, `MQTT_CLIENT_ID`: optional MQTT defaults.
  `MQTT_HOST` is required only when MQTT is enabled. MQTT settings saved from
  the authenticated Web page live in SQLite and override these defaults after
  the API restarts.

`.env.example` groups the first-run/common values before the advanced LCSC,
remote-rule and MQTT values. Docker Compose passes all server settings that
`server/app/config.py` accepts; users do not need to invent or mount another
configuration file. Persistent API state under `/data` consists of SQLite,
`config.json`, and bounded rolling logs at `/data/logs/server.log`. Uvicorn keeps
writing to stdout/stderr as well, so Docker and NAS log viewers continue to work.
The API does not store
component image files: lookup `official_url` values point to external product
pages, so there is no image directory to map.

The default named volume remains the supported upgrade path. For a fresh install,
operators who specifically need host-visible files may replace the API mapping
with `./data:/data`. Existing deployments must take a consistent SQLite backup
while writes are stopped and restore it into the bind mount before switching;
changing the mount alone exposes an empty database and does not migrate or delete
the old named volume. See the [Docker quickstart](docker-quickstart.md) for the
mapping example and Token lookup commands.

## Versioning Workflow

Repository versions are driven by `docs/CHANGELOG.md`.

Install the Git hook once after cloning:

```powershell
.\scripts\setup-git-hooks.ps1
```

On macOS/Linux:

```sh
sh ./scripts/setup-git-hooks.sh
```

发布流程由 `docs/CHANGELOG.md` 的最新具体版本标题决定：

1. 开发中的内容可以暂存在 `## [Unreleased]`，只修改这里不会发布。
2. 代码通过 CI、准备测试版本时，在其下添加 `## [0.7.7-dev.1] - 日期` 和本次说明；提交到 `master` 后自动构建预发布。
3. dev 经实际使用验证、决定发布正式版时，添加无后缀的标题，例如 `## [0.7.7] - 日期`。先运行 `python tools/versioning/sync_version.py --sync-latest-stable` 同步版本元数据，再提交；GitHub 工作流也会补齐尚未同步的元数据。

本地 pre-commit 只执行 `--validate`，不再把 `Unreleased` 自动转换成正式版本。旧的 `--apply` 仍是显式生成稳定版本的工具，日常提交无需使用。

CI 与发布的处理方式：

- `ci.yml` 校验版本元数据并执行标题解析与版本通道测试。
- `release-from-changelog.yml` 只读取最新具体版本章节；历史 dev 记录、教程链接或正文中的 dev 字样不影响判断。
- 若只修改 `Unreleased` 或已有 Release，则跳过；新版本创建对应 tag，再调用 `release.yml` 构建产物。
- 如果 tag 已创建但构建失败、尚无 Release，在 Actions 手动运行 `Release From Changelog` 可从原 tag 重试；不会将 tag 移到新提交。
- dev 发布为 GitHub prerelease，保持稳定版本元数据和 Docker `latest` 不变。正式标题才同步稳定版本及发布正式镜像。

Manual checks:

Active branch validation:

```powershell
python tools/versioning/sync_version.py --validate
```

Strict released-state validation:

```powershell
python tools/versioning/sync_version.py --check
```

## Client Bootstrap

### Android

The Android client is a Gradle/Compose app in `android-client/`.

Known machine requirements:

- Android SDK installed
- `ANDROID_HOME` or `ANDROID_SDK_ROOT` configured
- Access to Google Maven repositories

Basic check:

```powershell
.\android-client\gradlew.bat -p android-client help --no-daemon
```

Verified working configuration on this host (`2026-05-17`):

- Android SDK: `C:\Users\gdblz\AppData\Local\Android\Sdk`
- JDK: `D:\android_studio\jbr`
- Gradle wrapper: `.\android-client\gradlew.bat` (`8.11.1`)
- project-local Gradle cache: `D:\Project_Folder\Component_warehouse\.gradle-user-home`
- project-local Android user home: `D:\Project_Folder\Component_warehouse\.android-user`
- the repository does not hardcode `org.gradle.java.home`, so Gradle now uses
  `JAVA_HOME` or the toolchain configured by the host/runner
- current Android build baseline: AGP `8.10.1`, Kotlin `2.0.21`,
  Lifecycle `2.9.2`, Android SDK Platform `35`, Build Tools `35.0.0`,
  Java 21 in CI, and Java 17 bytecode target

Create or confirm `android-client/local.properties`:

```properties
sdk.dir=C:\\Users\\gdblz\\AppData\\Local\\Android\\Sdk
```

Build verification:

```powershell
$env:GRADLE_USER_HOME='D:\Project_Folder\Component_warehouse\.gradle-user-home'
$env:JAVA_HOME='D:\android_studio\jbr'
$env:ANDROID_SDK_ROOT='C:\Users\gdblz\AppData\Local\Android\Sdk'
$env:ANDROID_HOME='C:\Users\gdblz\AppData\Local\Android\Sdk'
$env:ANDROID_USER_HOME='D:\Project_Folder\Component_warehouse\.android-user'
& '.\android-client\gradlew.bat' -p android-client help --no-daemon
& '.\android-client\gradlew.bat' -p android-client assembleDebug --no-daemon
& '.\android-client\gradlew.bat' -p android-client testDebugUnitTest --no-daemon
& '.\android-client\gradlew.bat' -p android-client assembleRelease --no-daemon
```

Simpler Windows helper:

```powershell
.\scripts\android-gradle.ps1 assembleDebug
.\scripts\android-gradle.ps1 assembleRelease
```

That helper prefers Android Studio's embedded JBR and then calls the
repository Gradle wrapper, so local Windows builds stay aligned with CI even
when the machine has a newer system Java or another Gradle version on `PATH`.

Verification result on this host:

- `.\android-client\gradlew.bat -p android-client help --no-daemon` ->
  success on `2026-05-17`
- last full `assembleDebug` -> success on `2026-05-16`
- last full `testDebugUnitTest` -> success on `2026-05-16`
- last full `assembleRelease` -> success on `2026-05-16`
- `compileDebugKotlin --no-daemon` after the adaptive-shell refactor ->
  success on `2026-05-17`
- current local `assembleDebug` / `assembleRelease` on this host ->
  blocked on `2026-05-17` because AGP cannot install
  `build-tools;35.0.0` into
  `C:\Users\gdblz\AppData\Local\Android\Sdk`; the SDK directory is not
  writable for the current user
- Android metrics warnings and Kotlin daemon fallback messages may appear on
  this host and do not, by themselves, indicate a Kotlin or Compose source
  failure

Android Studio note:

- The official `Layout Editor` tutorial applies to View/XML layouts.
- This project uses Jetpack Compose, so visual editing should use Compose
  Preview, Interactive Preview, Run Preview, and Live Edit inside Android
  Studio.

Implemented client behaviors:

- local SQLite persistence for components, stock movements, and sync queue
- shared-preference sync settings
- component create/edit/soft delete
- movement entry and quantity recalculation
- manual sync, connection test, and optional auto sync
- JLC text/QR and direct C-number import with bundled offline recognition rules,
  device-only learning and public product lookup; no recognition server is needed
- BOM CSV/XLSX preview and transactional batch release, component-hub JSON
  migration, and official product images; see [BOM and migration](bom-and-migration.md)
- supplier packaging OCR import plus generated JLC-compatible or warehouse QR
  labels
- official adaptive Compose shell and Inventory flow built on
  `NavigationSuiteScaffold`, `currentWindowAdaptiveInfo`,
  `NavigableListDetailPaneScaffold`, and a `SearchBar`-first dense list layout

### Windows

The Windows client is a WinUI 3 project in `windows-client/`.

Basic check:

```powershell
dotnet build windows-client\ComponentVault.WinUI\ComponentVault.WinUI.csproj
dotnet publish windows-client\ComponentVault.WinUI\ComponentVault.WinUI.csproj -c Release /p:PublishProfile=win-x64.pubxml
dotnet publish windows-client\ComponentVault.WinUI\ComponentVault.WinUI.csproj -c Release -r win-x64 -p:PublishProfile= -p:WindowsPackageType=None -p:GenerateAppxPackageOnBuild=false -p:AppxPackageSigningEnabled=false -p:WindowsAppSDKSelfContained=true -p:SelfContained=true -p:PublishSingleFile=false -o windows-client\artifacts\portable-local
```

Windows output paths after publish:

- `windows-client\ComponentVault.WinUI\bin\Release\net9.0-windows10.0.19041.0\win-x64\AppPackages\`
- `windows-client\artifacts\portable-local\`

Implemented client behaviors:

- local SQLite persistence for components, stock movements, and sync queue
- component create/edit/soft delete
- movement entry and quantity recalculation
- manual sync, connection test, and optional auto sync
- WinUI XAML Designer previews are available in Visual Studio 2022 by opening
  the page files under `windows-client\ComponentVault.WinUI\Views\`

## GitHub Actions

The repository includes three GitHub Actions workflows:

- `.github/workflows/ci.yml`
  Runs server tests, admin-web build validation, Android debug compilation,
  Windows build validation, and changelog/version validation.
- `.github/workflows/release-from-changelog.yml`
  Watches `docs/CHANGELOG.md` on the default branch, applies version sync if
  needed, pushes the release tag, and then invokes the reusable release
  workflow in the same orchestration chain.
- `.github/workflows/release.yml`
  Runs Android release packaging, admin-web static bundle packaging, and
  Windows dual-mode packaging on manual trigger, reusable workflow calls, and
  `v*` tag pushes.

### Android Release Secrets

Configure these GitHub repository secrets before running `release.yml`:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

No additional `RELEASE_AUTOMATION_TOKEN` secret is required. Changelog-driven
release automation now runs with the default `GITHUB_TOKEN` plus
`permissions: contents: write`.

The workflow decodes the keystore into a runner-local temp file and exports:

- `ANDROID_KEYSTORE_PATH`

### Release Outputs

`release.yml` produces:

- `component-vault-android-release.apk`
- `component-vault-admin-web.zip`
- `component-vault-windows-portable-x64.zip`

On changelog-driven releases, reusable workflow calls, and `v*` tag runs that
publish a release, the workflow attaches the Windows portable ZIP, the Android APK, and the
`admin-web` static bundle to the
GitHub Release.

### Installing The Windows Release

GitHub Release ships a self-contained WinUI 3 ZIP, without an MSIX package.

Portable zip:

1. If the file came from the GitHub Actions artifact page, extract the outer
   workflow artifact archive first
2. Download or locate `component-vault-windows-portable-x64.zip`
3. Extract it
4. Open the extracted `component-vault-windows-portable-x64` folder
5. Run `ComponentVault.WinUI.exe`

If the executable fails during startup, check:

- `%LOCALAPPDATA%\ComponentVault\logs\startup.log`

Keep the runtime DLLs and resources beside the executable. Updating means closing
the app, extracting the new ZIP into a new application folder, and launching it.
Do not delete `%LOCALAPPDATA%\ComponentVault` when updating. Existing MSIX users
should export a backup from the old installation before uninstalling it; verify
that the ZIP build contains the expected inventory before removing any old data.

### Legacy Flutter

`client/` remains in the repo only as a migration reference and should not be
treated as the primary production UI target.

## Smoke Checks

### Server Health

```powershell
curl http://localhost:8787/health
```

### Admin API Check

```powershell
curl http://localhost:8787/admin-api/dashboard `
  -H "Authorization: Bearer change-me"
```

### Auth Check

```powershell
curl -X POST http://localhost:8787/auth/ping `
  -H "Authorization: Bearer change-me"
```

## Common Failure Modes

### `py` exists but Python is broken

- Symptom: `py -3.12` cannot launch the registered interpreter.
- Fix: re-run `.\scripts\bootstrap.ps1`; it will recreate `server/.venv` and
  fall back to a repo-local `uv`-managed Python 3.12 when `uv` is available.
  If `uv` is not installed, repair or reinstall Python 3.12 first.

### Flutter not found

- Symptom: `flutter` is not recognized.
- Impact: only the legacy reference client is affected.
- Fix: install Flutter only if you need to inspect or compare the old prototype.

### Android SDK not configured

- Symptom: `assembleDebug` fails with `SDK location not found`.
- Impact: Android client compilation and emulator/device workflows cannot
  start.
- Fix: install the Android SDK, set `ANDROID_HOME` or `ANDROID_SDK_ROOT`, or
  create `android-client/local.properties` with `sdk.dir=<absolute-sdk-path>`.

### Gradle is pinned to a local Java path

- Symptom: GitHub Actions or another machine fails with
  `Value '.../jbr' given for org.gradle.java.home Gradle property is invalid`.
- Cause: `android-client/gradle.properties` contains a machine-specific
  `org.gradle.java.home` override.
- Fix: keep that property out of the repository and provide Java through
  `JAVA_HOME`, Android Studio, or the CI runner toolchain instead.

### Android theme resource missing

- Symptom: AAPT fails with `resource style/Theme.Material3.DayNight.NoActionBar
  not found`.
- Cause: the app theme inherits from the Android Material theme, but the
  `com.google.android.material` dependency is missing.
- Fix: keep `com.google.android.material:material` in
  `android-client/app/build.gradle.kts`.

### Admin web cannot reach the API

- Symptom: login succeeds locally in one environment but browser requests fail
  with CORS errors.
- Cause: the browser origin is missing from `ADMIN_WEB_ORIGINS`.
- Fix: add the Web page origin (usually port 8081, not API port 8787) to
  `ADMIN_WEB_ORIGINS`. For Compose, run `up -d` so the API container is recreated
  with the new environment; `docker compose restart` alone does not reload `.env`.

### Android release workflow fails immediately

- Symptom: `release.yml` stops at signing secret validation.
- Cause: one or more Android signing secrets are missing from the GitHub
  repository settings.
- Fix: add all four required Android signing secrets before rerunning the
  release workflow.

### Android `lintVitalAnalyzeRelease` crashes in the Lifecycle detector stack

- Symptom: `assembleRelease` fails in `:app:lintVitalAnalyzeRelease` with
  `Found class ... KaCallableMemberCall, but interface was expected` and the
  stack mentions
  `androidx.lifecycle.lint.NonNullableMutableLiveDataDetector`.
- Cause: a stale AGP/lint/Lifecycle detector combination is running against a
  newer Kotlin analysis API.
- Fix: stay on the repository baseline of AGP `8.10.1`, Gradle wrapper
  `8.11.1`, Lifecycle `2.9.2`, SDK Build Tools `35.0.0`, and Java 21 for CI.
  This repository also disables the unused `NullSafeMutableLiveData` lint
  issue because the app does not use `LiveData`, which removes the crash path
  even before a full upstream lint fix lands.

### Android SDK directory is not writable for Build Tools `35.0.0`

- Symptom: local `assembleDebug` or `assembleRelease` fails before packaging
  with `Failed to install the following SDK components: build-tools;35.0.0`
  and `The SDK directory is not writable`.
- Cause: AGP requires Build Tools `35.0.0`, but the local SDK either does not
  have them installed or cannot update them under the current user account.
- Fix: install or repair `build-tools;35.0.0` through Android Studio SDK
  Manager or `sdkmanager` under a writable SDK path, then rerun the wrapper
  build. Until the SDK is writable, use
  `.\android-client\gradlew.bat -p android-client help --no-daemon` or
  `compileDebugKotlin --no-daemon` only as source-level verification steps.

### Changelog release automation cannot push commit or tag

- Symptom: `release-from-changelog.yml` reaches `git push` and fails with a
  permission or protection error.
- Cause: the workflow token does not have `contents:write`, the repository is
  configured with read-only workflow permissions, the default branch blocks
  workflow pushes, or tag creation is restricted.
- Fix: grant GitHub Actions read/write repository permissions, keep
  `permissions: contents: write` on the workflow, and allow the workflow bot
  to push the synchronized release commit and `v*` tags.

### Changelog release automation does not create a GitHub Release

- Symptom: `release-from-changelog.yml` pushes or finds the tag, but the
  release creation job is skipped or fails afterward.
- Cause: the reusable `release.yml` workflow was not callable, a required
  Android signing secret is missing, or artifact packaging failed in one of the
  platform jobs.
- If all platform builds succeed but publication is skipped, inspect the prepare
  job's resolved tag and publish flag. Reusable workflows inherit the caller's
  event context; supplied release inputs must take precedence over branch-push
  event handling. A tag page without APK/portable ZIP assets is not a completed release.
- Fix: verify that `.github/workflows/release.yml` supports `workflow_call`,
  confirm all Android signing secrets are configured, and rerun the workflow
  after inspecting the failing build job.

### An older Windows MSIX reports certificate error 0x800B010A

Use the current `component-vault-windows-portable-x64.zip` release instead.
The new distribution does not require importing a publisher certificate.
If downloading from Actions, extract the outer artifact and then the inner
application ZIP; run `ComponentVault.WinUI.exe` from the extracted application
folder, not from inside the archive.

### Windows client exits during startup

- Symptom: `ComponentVault.WinUI.exe` appears briefly, or the app shows a
  startup failure dialog.
- Cause: an unhandled startup or UI exception occurred on the target machine.
- Fix: open `%LOCALAPPDATA%\ComponentVault\logs\startup.log`, keep the dialog
  text, and use that exception message for the next debugging pass.

### Windows app or designer cannot resolve `TextFillColorSecondaryBrush`

- Symptom: startup log or XAML Designer reports
  `Cannot find a Resource with the Name/Key TextFillColorSecondaryBrush`.
- Cause: `windows-client\ComponentVault.WinUI\App.xaml` is missing the merged
  `XamlControlsResources` dictionary, so WinUI theme brushes and styles such as
  `TextFillColorSecondaryBrush`, `ControlFillColorSecondaryBrush`, and
  `AccentButtonStyle` are not available.
- Fix: restore `XamlControlsResources` in `App.xaml`, then rebuild the WinUI
  project and reopen the XAML page in Visual Studio.

### Duplicate SKU push rejected

- Symptom: `/sync/push` returns `409 Conflict`.
- Cause: two active components share the same `sku`.
- Fix: rename or soft-delete the conflicting component locally, then sync again.

### Negative stock blocked

- Symptom: recording a movement fails locally.
- Cause: an outbound or adjustment movement would drive stock below zero.
- Fix: correct the quantity or record an inbound adjustment first.

### Upgrading cursor synchronization

Stop the API and back up its SQLite database before deploying the updated
service. Startup adds revision metadata in a transaction; no inventory rows are
deleted. Upgrade clients to use `sync_cursor`; the first upgraded client sync
is full. Older clients using `since` continue to operate with the old late-write
limitation. Newly detected orphan references cause an atomic HTTP 409 instead
of partially applying a push.

Cursors belong to the current server database history. After restoring an older
backup or replacing the database, reset client synchronization to a full pull
before resuming incremental use. Do not copy a cursor from another database.
Changing the saved server URL clears the client cursor.

### Android direct LCSC lookup unavailable

The import preference for direct public lookup needs no API token or backend.
It reads the LCSC international public product page and accepts only an exact
SKU match. If the provider returns a verification screen, changes its structured
markup, or cannot be reached, open the product link or complete the form manually.
The application does not attempt to bypass verification. Turning off the import
preference stops automatic direct queries. Existing backend lookup settings are
separate and remain optional.

### MQTT inventory subscriptions

MQTT is disabled by default. Configure the API environment variables
`MQTT_ENABLED`, `MQTT_HOST`, `MQTT_PORT`, `MQTT_TLS`, `MQTT_USERNAME`,
`MQTT_PASSWORD`, `MQTT_TOPIC_PREFIX`, and `MQTT_CLIENT_ID`; restart the API after
changing them. Root Compose forwards these values from its environment/root
`.env`; `server/.env` requires explicit `uvicorn --env-file .env` when running
locally. The development script does not implicitly load that file.

Alternatively, use the web Settings MQTT form and save. Saved SQLite settings
override MQTT environment defaults on the next restart. The UI separates
current connection state from saved configuration and flags restart-required
changes. Empty passwords preserve the current value; explicit clear removes it.
Configuration APIs never return passwords, but the server database backup
contains broker credentials. Existing saved configurations must be changed
through the form rather than by changing environment defaults alone.

Run one API process/publisher per database. Back up SQLite before upgrading;
startup adds `mqtt_outbox`, `mqtt_state`, and `mqtt_configuration` without
changing stock values.
Broker failures retain the queue on disk, so monitor `pending` and free space.
`GET /admin-api/mqtt/status` is authenticated with the normal API token.
First enable and destination changes seed all current component states;
ordinary restarts resume pending publication. Disable MQTT to stop publication
while retaining normal client synchronization. See [MQTT operations and Home
Assistant configuration](mqtt.md) for recovery after retained data loss and
topic/deletion behavior.

### Native About and release checks

#### Stable and Dev publishing

只保留 `master` 主线。日常功能和界面调整先发 dev，CI 成功不能代替实际 UI 验收。准备发布时，将最新版本标题和说明写入 `docs/CHANGELOG.md`：`X.Y.Z-dev.N` 自动走预发布，无后缀的 `X.Y.Z` 自动走正式发布；详细测试说明可链接到 `docs/releases/`。

也可直接给已通过 CI 的提交推送 `vX.Y.Z-dev.N` tag，沿用 `Release Artifacts` 构建。两种入口共用同一渠道识别和构建流程，dev 均不发布 Docker 镜像、不替换稳定版 `latest`。

Both channels keep the same Android application ID and signing key. Published
Android version codes are derived from the release tag:
`major × 10000000 + minor × 100000 + patch × 1000 + build`, with `build=N` for
dev.1–dev.998 and `build=999` for stable. This makes a same-version stable APK
installable over its dev builds. The source version remains changelog-driven;
the release workflow injects the artifact's version at build time.

Example: `0.7.4-dev.1` uses code `704001`, and stable `0.7.4` uses `704999`.
Do not change signing keys between channels. See [update behavior](app-updates.md)
and [current dev test notes](releases/0.7.4-dev.1.md).

#### Client behavior

Settings / About checks `Rafael-ban/Component_Manager` only after a user click.
The persisted update channel defaults to Stable; choosing Dev also includes
public prereleases. Switching channels never automatically downgrades or installs.
The APK and portable ZIP filenames must match the
release workflow. A missing package, HTTP 404, rate limit or network failure is
shown in the UI; users can still open the fixed release page. Applications do
not silently install or import signing certificates. See [application updates](app-updates.md)
for installation and Debug/Release signing boundaries.

## 旧库存参数补全

包含工具的 API 与 Web 需一起升级。在 Web 库存页预览当前账户缺少参数的 LCSC 元件，再确认批量补全；写入需要管理员启用 Web 库存操作。先同步各客户端，完成后再次同步获取参数；数量、库位及流水不变。取消保留成功项，重启后重新预览剩余候选。开发版不推送 Docker 稳定标签；源码 Compose 与现有数据卷的部署说明、恢复步骤见[旧库存参数补全指南](parameter-enrichment.md)。

## Android 测试超时排查

Actions 的 `android-check` 先运行库存扫码与数量边界的直接测试，报告下载项为
`android-stock-scan-tests`；随后运行原有回归组，报告为 `android-ui-review`。
两组分别设置 10 分钟和 15 分钟上限。任务超时不等于测试通过，先看失败步骤及对应日志，
不要仅依据 APK 构建成功判断完整 CI 通过。

### Web 清理测试元件

启用 Web 库存写入后，在库存详情选择“删除元器件”并核对 SKU 后确认。仅删除当前账户中的该元件，删除结果随正常同步传给客户端，历史流水保留。若版本冲突，刷新详情后再确认，不反复强制覆盖。升级 API 与管理 Web 到同一开发版；开发版不替换 Docker latest。

## BOM 预设与管理台入口

含预设同步功能的 dev 服务会在各账户 SQLite 中自动创建 `bom_presets` 表，无额外环境变量或挂载目录。升级前备份整个 `/data`；保留原账户数据库和配置。管理台新增 `/bom-presets`，只读且沿用登录会话，普通账户只看到自己的预设。需同时更新 API 与 Web 产物，旧 API 不支持时页面会提示读取失败，可先继续使用客户端本地预设。

原生客户端先升级并在同账户下手动同步一次，可在“BOM 预设”核对结果。此页没有扣库存或生产预占操作。完整备份服务端数据库可保留已同步预设；客户端 Excel 库存备份不包含预设，尚未同步的预设需另外保留 `bom-presets.json`。

## dev19 照片数据卷与升级

API/Web 同时升级，客户端 `/auth/ping` 应见 `photo_protocol:1`。本开发版不推进 Docker `latest` 正式标签；测试源码 Compose 构建可沿用本手册既有步骤。无新环境变量和端口，保留整个 `/data` 挂载及账户数据库。

照片保存在 `<账户数据库路径>.photos/`，普通账户各自独立；备份/恢复必须同时包含 SQLite、配置和这些目录，建议停写后复制整个数据卷。只恢复数据库会缺少图片字节，客户端将提示在仍有原图的设备上同步。服务端清理超过24小时未引用文件，删除账户会同步清除其照片目录。客户端清理下载缓存只影响副本，不修改库存或服务器图片。详见[照片教程](photo-sync.md)。

### Windows 订单与更新日志检查

Windows CI 明确执行以下三个测试项目；发布候选构建执行新增的后两个独立测试项目：

```powershell
dotnet test windows-client/ComponentVault.WinUI.CoreTests/ComponentVault.WinUI.CoreTests.csproj -c Release
dotnet test windows-client/ComponentVault.WinUI/Tests/JlcOrderImport/JlcOrderImport.Tests.csproj -c Release
dotnet test windows-client/ComponentVault.WinUI/Tests/ReleaseNotesMarkdown/ReleaseNotesMarkdown.Tests.csproj -c Release
```

只有改动相关部分时才需在本地运行对应项目。候选构建可通过 `Release Artifacts` 的 `workflow_dispatch` 设置 `source_ref` 为完整提交 SHA、`release_tag` 为拟验证的 dev 标签，并将 `publish_github_release` 设为 `false`；这样只生成验证附件，不创建公开 Release。桌面验收通过后再写具体版本的 CHANGELOG 标题触发发布；仅编辑 Unreleased 不发布。dev 不推送 Docker 稳定标签。

订单复核、待验收项和服务端 Issue #25 的实现建议见 [2026-10-28 记录](issues-2026-10-28.md)。


### 从订单更新已有资料（dev.21）

API 生产依赖新增 xlrd（BIFF `.xls`）与 python-multipart，源码部署需重新安装 `server/requirements.txt`；开发测试另外使用 xlwt 生成合成 BIFF。服务端与管理台同时升级，原 `/data` 映射保留，无新增环境变量和 schema 迁移。该功能从 dev.21 开始验证，并纳入 0.7.8 正式镜像；使用已发布的配套稳定镜像或按[订单资料更新教程](order-metadata-update.md)构建源码。

Web“库存 → 从订单更新资料”支持只读差异预览和字段确认；应用沿用 Web 库存写入开关。故障时区分不支持格式/文件损坏、未匹配 SKU、同 SKU 资料冲突、设备并发版本冲突。成功项不会因其他项失败而回滚；网络结果不明可使用原请求重试，不自动刷新版本覆盖。
