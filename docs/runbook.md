# Runbook

## Docker Hub image deployment

普通部署见[快速部署](docker-quickstart.md)，正式版与 dev 切换见[渠道指南](docker-channels.md)。启用网页更新时使用 `docker-compose.hub-web-update.yml`，`.env` 填宿主机项目目录，dev 另填渠道。旧部署保留原项目名、端口、完整 `/data` 和生效的自定义 API 环境变量。[网页更新与旧配置迁移](server-web-update.md#群晖一次性配置)

### 独立 Docker CI 与发布渠道

`docker-ci.yml` 检查镜像构建和 Compose 配置。`server-image.yml` 核对并复用已发布的固定标签，只构建缺失的 API 或管理镜像。隔离项目验证更新后，才推进对应的 `dev / web-dev` 或 `latest / web-latest`。Changelog 发布并行生成原生附件及 Docker 镜像，各自报告结果。Docker 失败后可用 `master` 上的独立工作流补发同一 tag，需要推进渠道时勾选 `promote_channel`。[镜像发布指南](dockerhub.md)

### Optional Watchtower updates for the Hub deployment

定时跟随当前镜像渠道可选择[Watchtower](docker-quickstart.md#watchtower-updates)。
它不会自动跨渠道，且不与网页 updater 同时管理同一 API/Web。群晖安装该组件仍需一次原项目配置。

### 排查镜像与客户端账户接口不一致

见[账户接口排障](docker-quickstart.md#7-客户端提示服务端版本过老或无法确认账户身份)。
先核对实际 API `/health` 版本及认证 `/auth/me`，再检查端口、反向代理和账户密钥。

## Admin console deployment and API token

首次密钥属于管理员，普通账户由管理员创建；会话、权限和删除范围见[账户指南](accounts.md)。
Web/API 需同站点且精确允许 Web origin；网页保存的设置与非空环境变量的优先级见
[配置说明](docker-quickstart.md#3-环境变量和网页配置的优先级)。MQTT 有独立的数据库配置优先级。

升级前一致备份整个 `/data`，包含管理员账户注册表、`users/`、配置及各账户 `.db.photos/`。
回退到不支持账户的旧程序会令普通账户无法登录；换镜像不能撤销数据库迁移。

### API token discovery

按[密钥查找步骤](docker-quickstart.md#4-api-token-在哪里)确定当前生效值。
管理员密钥不是 Docker Hub 或 GitHub Token；普通用户需要自己的账户密钥。
不要为了升级清空现有 API_TOKEN，也不要让旧客户端绕过账户绑定上传。

### Upgrade from the HTTP 422 long-name failure

服务端兼容最多4000字符的旧元件名称；新导入将型号与描述分开。
同时更新服务端和客户端后重试同步，无需清空应用数据；用户名称不会被批量重写。

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

Back up the full `/data` before account/database upgrades, including the account
registry, `users/`, configuration and every `<account-database>.photos/` directory.
Downgrading to a version without account support prevents ordinary-user login;
changing an image is not a database restore and cannot undo schema changes.

Current native navigation and module boundaries are documented in
[architecture.md](architecture.md). Android and Windows About pages
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

从仓库根目录构建并启动源码版 API 与管理台：

```powershell
docker compose up --build
```

The compose file publishes `8787` and stores the SQLite database in the Docker
volume `component_vault_data`.

Admin web is available at:

- `http://localhost:8081/`

## Environment Variables

### Server

- `API_TOKEN`: optional environment-provided administrator account key; ordinary accounts use their own keys
- `DATABASE_PATH`: SQLite file path; the API image defaults to `/data/component_vault.db`
- `CONFIG_PATH`: optional override for `config.json`; defaults beside SQLite
- `LOG_DIR`: optional application log directory; defaults to `logs` beside SQLite
- `APP_HOST`, `APP_PORT`: host and port for direct local development; the
  container command already binds `0.0.0.0:8787`
- `ADMIN_WEB_ORIGINS`: comma-separated origins allowed to call the API from the
  separated admin web app
- `ADMIN_WEB_URL`: optional absolute HTTP(S) URL of the deployed Web console,
  including its path. Non-empty values override the setup page setting; an empty
  value leaves it editable in `/setup` and Web console Settings.
- `LCSC_LOOKUP_CACHE_TTL_SECONDS`: server-side in-memory cache TTL for public
  LCSC lookup responses, default `43200`
- `IMPORT_RULES_REMOTE_URL`: optional JSON URL used to refresh the server-side
  recognition rule pack consumed by `/admin-api/part-lookup`
- `IMPORT_RULES_REFRESH_HOURS`: refresh age threshold for the cached rule pack,
  default `24`
- `ENABLE_WEB_FALLBACK_RESOLVERS`: enables public LCSC product-page fallback
  for `/admin-api/part-lookup`, default `false`. Explicit compatibility
  queries and inventory specification enrichment do not depend on this switch.
- `WEB_INVENTORY_ENABLED`: enables authenticated Web inventory writes, default
  `false`
- `MQTT_ENABLED`, `MQTT_HOST`, `MQTT_PORT`, `MQTT_TLS`, `MQTT_USERNAME`,
  `MQTT_PASSWORD`, `MQTT_TOPIC_PREFIX`, `MQTT_CLIENT_ID`: optional MQTT defaults.
  `MQTT_HOST` is required only when MQTT is enabled. MQTT settings saved from
  the authenticated Web page live in SQLite and override these defaults after
  the API restarts.

Hub Compose passes through the common application overrides shown in `.env.example`.
For advanced MQTT or catalog settings, explicitly add the needed keys under
`api.environment`, such as `IMPORT_RULES_REMOTE_URL: ${IMPORT_RULES_REMOTE_URL:-}`.
Putting an advanced key in the host `.env` alone does not pass it to the API.
When replacing an existing Compose file, keep any custom API environment entries
and `/data` mounts. Use the setup page for application settings saved under `/data`.
Non-empty setup-managed environment values remain authoritative until the override
is removed. [Configuration priority](docker-quickstart.md#3-环境变量和网页配置的优先级)
Persistent API state includes account SQLite files, `config.json`, each account's
`<database-path>.photos/` directory and bounded `/data/logs/server.log` files.
Uvicorn also writes stdout/stderr for Docker/NAS viewers. Photo files need no
additional mount when the full `/data` is retained. Remote product-image URLs
are separate from these synchronized original photos.

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

For an HM-A300E test build, pass the vendor SDK directory through `hprtCpclSdkDir`.
See the [HM-A300E SDK build instructions](hm-a300e.md#构建-sdk-测试包).

按 [Android 构建说明](../android-client/README.md#构建)安装 JDK、Android SDK 和构建工具。使用仓库的 Gradle wrapper；版本以构建文件与 CI 为准。仓库根目录可执行：

```powershell
.\android-client\gradlew.bat -p android-client help --no-daemon
.\android-client\gradlew.bat -p android-client assembleDebug --no-daemon
```

Windows 也可用 `scripts/android-gradle.ps1 assembleDebug`。该脚本优先选择 Android Studio 的 JBR，并调用仓库的 Gradle wrapper。

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

Main build and release workflows:

- `.github/workflows/ci.yml`
  Runs server tests, admin-web build validation, Android debug compilation,
  Windows build validation, and changelog/version validation.
- `.github/workflows/release-from-changelog.yml`
  Watches `docs/CHANGELOG.md` on the default branch, applies version sync if
  needed, pushes the release tag, and then invokes the reusable release
  workflow in the same orchestration chain.
- `.github/workflows/release.yml`
  Runs Android release packaging, admin-web static bundle packaging, and
  Windows self-contained portable ZIP packaging on manual trigger, reusable workflow calls, and
  `v*` tag pushes.
- `.github/workflows/docker-ci.yml`
  Runs API/shared-management image, role startup and Compose checks for relevant changes.
- `.github/workflows/server-image.yml` (`Docker Images`)
  Builds/checks API and shared management images in all three container roles and, when publishing is requested, pushes the
  selected channel and verifies a real isolated Compose update. Missing publishing
  credentials fail this job; native release artifacts are reported independently.

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
curl.exe http://localhost:8787/health
```

### Admin API Check

```powershell
curl.exe http://localhost:8787/admin-api/dashboard `
  -H "Authorization: Bearer <你的账户密钥>"
```

### Auth Check

```powershell
curl.exe -X POST http://localhost:8787/auth/ping `
  -H "Authorization: Bearer <你的账户密钥>"
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

### Native public catalog lookup unavailable

Chinese Android/Windows lookup uses the domestic EasyEDA catalog; English uses
international sources. There is no automatic cross-region fallback or Cookie
requirement. Exact-SKU queries must match the returned C code; a site/network
failure is different from QR decode or payload parsing failure. Retry, inspect
the optional redacted diagnostics, or edit the form manually. Native lookup is
independent of the sync token and needs no supplier credentials.
See [lookup diagnostics](feedback-and-scan-diagnostics.md).

### MQTT inventory subscriptions

MQTT is disabled by default. Configure the API environment variables
`MQTT_ENABLED`, `MQTT_HOST`, `MQTT_PORT`, `MQTT_TLS`, `MQTT_USERNAME`,
`MQTT_PASSWORD`, `MQTT_TOPIC_PREFIX`, and `MQTT_CLIENT_ID` if you need to set
defaults by environment. For Docker, pass the keys explicitly to `api.environment`
and recreate the API container after changes. For local source runs, use
`uvicorn --env-file .env` to read `server/.env` explicitly.

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

也可直接给已通过 CI 的提交推送 `vX.Y.Z-dev.N` tag，触发 `Release Artifacts` 与独立 `Docker Images`。两种入口共用渠道识别：dev 发布固定版本与 dev/web-dev，不替换正式 latest/web-latest；不能仅凭原生附件完成推断 Docker 已完成。

Both channels keep the same Android application ID and signing key. Published
Android version codes are derived from the release tag:
`major × 10000000 + minor × 100000 + patch × 1000 + build`, with `build=N` for
dev.1–dev.998 and `build=999` for stable. This makes a same-version stable APK
installable over its dev builds. The source version remains changelog-driven;
the release workflow injects the artifact's version at build time.

Example: `0.7.4-dev.1` uses code `704001`, and stable `0.7.4` uses `704999`.
Do not change signing keys between channels. See [update behavior](app-updates.md)
and the [verification history](history/verification.md).

#### Client behavior

Android 和 Windows 启动时按当前渠道检查一次，有可用更新时显示提示。设置的“关于”仍可手动检查。默认渠道为正式版，开发渠道也检查公开预发布版本。切换渠道不会自动降级或安装。
The APK and portable ZIP filenames must match the
release workflow. A missing package, HTTP 404, rate limit or network failure is
shown in the UI; users can still open the fixed release page. Applications do
not silently install or import signing certificates. See [application updates](app-updates.md)
for installation and Debug/Release signing boundaries.

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

## 照片数据卷与升级

API 和 Web 应成对更新。启用照片同步前核对 `/auth/ping` 的 `photo_protocol: 1`，并保留完整 `/data` 挂载。各账户原图保存在对应的 `.db.photos/` 目录，应与数据库和配置一起备份。详见[照片同步与恢复](photo-sync.md)。

### Windows 订单与更新日志检查

Windows CI 明确执行以下三个测试项目；发布候选构建执行新增的后两个独立测试项目：

```powershell
dotnet test windows-client/ComponentVault.WinUI.CoreTests/ComponentVault.WinUI.CoreTests.csproj -c Release
dotnet test windows-client/ComponentVault.WinUI/Tests/JlcOrderImport/JlcOrderImport.Tests.csproj -c Release
dotnet test windows-client/ComponentVault.WinUI/Tests/ReleaseNotesMarkdown/ReleaseNotesMarkdown.Tests.csproj -c Release
```

只有改动相关部分时才需在本地运行对应项目。候选构建可通过 `Release Artifacts` 的 `workflow_dispatch` 设置 `source_ref` 为完整提交 SHA、`release_tag` 为拟验证的 dev 标签，并将 `publish_github_release` 设为 `false`；这样只生成验证附件，不创建公开 Release。桌面验收通过后再写具体版本的 CHANGELOG 标题触发发布；仅编辑 Unreleased 不发布。dev 不推送 Docker 稳定标签。

订单更新操作见[订单资料指南](order-metadata-update.md)，历史验证边界见[验证记录](history/verification.md)。


### 从订单更新已有资料

自 0.7.8 起可使用配套 API 和 Web 镜像。源码部署使用 `server/requirements.txt` 安装依赖，包括读取 BIFF `.xls` 的 xlrd 和处理上传的 python-multipart。升级时沿用原 `/data` 挂载。

Web“库存 → 从订单更新资料”支持只读差异预览和字段确认；应用沿用 Web 库存写入开关。故障时区分不支持格式/文件损坏、未匹配 SKU、同 SKU 资料冲突、设备并发版本冲突。成功项不会因其他项失败而回滚；网络结果不明可使用原请求重试，不自动刷新版本覆盖。

### 库存资料检查

API 与 Web 配套更新后，在「库存 → 库存工具 → 库存资料检查」扫描当前账户库存。
分析不写入；应用沿用 `WEB_INVENTORY_ENABLED` 或已保存的 Web 库存操作开关。
先同步各设备，再核对缺项或差异。可以逐条选择，也可在支持的版本中全选本次扫描的可更新资料。确认应用后再同步设备。
任务预览只存在本次服务进程，重启后重新分析；网络失败可重试，版本冲突需重新预览。
开发镜像使用 dev/web-dev，正式镜像使用 latest/web-latest。试用时成对更新并保留原项目和数据；需要源码构建时见[资料更新教程](parameter-enrichment.md)。
