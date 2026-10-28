# Component Vault｜元器件库存管理

Component Vault 面向电子元器件的入库、出库、查找与盘点。Android 和 Windows 客户端以本机 SQLite 保存库存，可以离线使用；需要多设备共享时，再连接自托管服务同步。项目还提供独立的 Web 管理台，用于查看服务器库存和管理部署。

## 从这里开始

| 你想做什么 | 入口 |
| --- | --- |
| 下载 Android APK 或 Windows 便携版 | [GitHub Releases](https://github.com/Rafael-Ikaros/Component_Manager/releases/latest)；安装与更新说明见[应用更新](docs/app-updates.md) |
| 在 Docker 或群晖上部署服务 | [Docker 快速部署](docs/docker-quickstart.md) |
| 使用 BOM、批量扫码、库位或备份 | 下方的[使用指南](#使用指南) |
| 开发或集成客户端 | [开发与集成](#开发与集成) |

原生客户端无需服务器即可管理本地库存。准备同步时，先部署 API，在客户端“设置 → 连接与同步”填写服务器地址与账户密钥，再测试连接并同步。首次部署通过 `http://服务器IP:8787/setup` 配置管理员密钥；不要把 Docker Hub 或 GitHub 的登录凭据填进这里。服务端库存保存在持久化的 `/data` 中，升级时保留该数据卷。

日常功能与界面调整先发布 **dev 预发布**，通过实际使用验证后再决定正式发布。应用“设置 → 关于”可选择更新通道；dev 安装包见 [全部 Releases](https://github.com/Rafael-Ikaros/Component_Manager/releases)。发布渠道由更新日志的最新具体版本标题决定：`0.7.7-dev.1` 为预发布，`0.7.7` 为正式版；只修改 `Unreleased` 不发布。dev 不会替换 Docker `latest`，详见[发布流程](docs/runbook.md#versioning-workflow)。

Android 从 `0.7.8-dev.9` 起支持在“设置 → 关于”直接下载更新，查看进度、取消或重试，完成后打开系统安装界面；无需先跳转 GitHub 网页。Windows 仍下载便携 ZIP。具体步骤见[应用更新](docs/app-updates.md)。

## 平台与能力

| 平台 | 技术与用途 |
| --- | --- |
| Android | Jetpack Compose + 本机 SQLite；扫码、查询、库存操作、标签预览与已验证的汉印 M1 打印流程 |
| Windows | WinUI 3 + 本机 SQLite；库存、BOM、导入与同步；正式发布提供便携版 ZIP |
| 管理 Web | React + `shadcn/ui`；查看服务端库存、同步状态和设置；启用 `WEB_INVENTORY_ENABLED` 后可进行受控的库存写入 |
| 服务端 | FastAPI + SQLite；账户认证、同步、管理 API 与可选 MQTT 库存事件 |

库存检索支持型号片段、品牌与参数的多词匹配，例如 `FOJAN 10k` 或 `100uF 1206`；常见型号分隔符与微单位写法会归一。新导入默认名称使用品牌和型号（例如 `FOJAN FRH0603B1002TS`），不拼入电气参数；基本信息独立显示已取得的阻值、容量、电感量、耐压、精度、功率及封装，并参与检索。旧库存从已保存的官方资料读取参数，不批量改名；没有保存过的参数可在 Web 库存页使用“补全旧元件参数”批量查询，再同步回客户端，不能从型号编码猜测。操作步骤见[旧库存参数补全](docs/parameter-enrichment.md)。

主要工作流包括按 SKU 查找元件、入库与出库、库存流水、多库位分配与转移、嘉立创料号查询、BOM 匹配和缺料导出。Android 与 Windows 支持选择多个元件、复核数量后按库位一次批量转移，库存总量不变；Android 左滑直接展开入库、出库、转移、删除四个按钮，可对单个 SKU 快速操作；数量与库位在当前列表弹层内填写，删除仍需确认。客户端支持 Excel 备份与恢复，恢复前会预览冲突。活动元件的 SKU 唯一，库存数量和最低库存不能为负数。

Android 的“记录 → 扫描标签”支持嘉立创原厂包装码：按料号匹配已有库存后进入复核，开始扫码前选择入库或出库，后续复核沿用该方向并可选择库位；默认 1，不直接扣除整包数量。见[扫码出库说明](docs/storage-and-backup.md#扫嘉立创包装出库)。

同步遵循本地优先：原生客户端先写入自己的 SQLite，再按需推送和拉取。BOM 扣料是客户端本地事务；它没有跨设备共享的工程扣料账本。Web 库存写入默认关闭，启用后直接写入服务端 SQLite，并可同步回原生客户端。详见[架构与数据流](docs/architecture.md)和[接口集成](docs/integration-guide.md)。

Android 库存筛选按当前栏宽换行，设置和库存页面在键盘弹出时保留可滚动的操作区域；切换主页面会结束当前键盘输入。同步中的可空流水库位按空值保存，已有误存的 `null` 引用在同步时修正，不删除流水或改变库存。

Android 核心代码按库存显示、扫码复核、导入确认、库存写入、识别与学习、同步、工作簿和 BOM 提交分开维护，保留原页面、数据库和偏好设置。模块职责见[Android 功能模块边界](docs/architecture.md#android-功能模块边界)，阶段进度与实机验收见[重构计划](docs/android-refactor-plan.md)和[工作记录](docs/current-work.md)。

项目 BOM 按“选择文件 → 库存匹配 → 确认出库”操作。嘉立创 EDA 导出的 `Supplier Part`、`Manufacturer Part`、`Quantity` 等列自动识别；需要修正时再展开带样例的列选择。没有料号的测试点等行保留待处理，可手选库存或明确跳过，并在确认前恢复。可选保存为本地项目预设，下次重新核对实时库存后使用。详见[嘉立创 BOM 导入与扣料教程](docs/bom-and-migration.md#嘉立创-eda-导出文件示例)。

Android 项目 BOM 在旋转屏幕后保留当前文件、匹配选择和复核进度；匹配可明确取消，列表区分已匹配、待处理与不出料。采购标记是本机到货提醒：缺料可标为“已购买 / 在途”，导出缺料 CSV 成功后也可批量标记；实际入库后提醒核对数量，不自动认定整单到齐、不自动扣料。该采购清单不随库存同步或 Excel 库存备份传输。

Android 库位管理可生成库位二维码标签，复用现有标签编辑和 M1 打印流程。库存页扫码能打开已有元件或按库位筛选，导入页扫描库位码也可跳到对应库存。库位码只保存稳定 ID，名称与库存取自当前本机数据；多库位元件按实际分配筛选，零库存元件仍可在主库位找到。Windows 暂未增加库位标签扫码和采购提醒。修复进度见 [Issues 核对记录](docs/issues-2026-10-18.md)。

Android 标签工具与元件标签快捷入口共用标签编辑与蓝牙打印页面：先选择标准元件、紧凑元件、元件纯文字、自由文字、自由二维码或混排模板，再选择常用或自定义纸张；新建旋转为 0°，恢复草稿保留已保存的角度。内建模板，可修改显示文字、拖动元素和数值微调，预览、导出与发送共用布局。自由标签不创建库存记录。普通流程移除独立诊断弹窗；操作与实机验证边界见 [M1 标签指南](docs/m1-printing-feature-matrix.md)。

Android 和 Windows 的标签 Excel 导出支持全部库位或多选指定库位。指定库位时按元件与库位分行，数量和二维码使用该库位库存，方便分批打印；操作说明见[标签 Excel 导出](docs/storage-and-backup.md#标签打印专用-excel)。

### 账户与数据隔离

0.7.5 加入账户隔离：首次配置的密钥作为管理员账户，继续使用原有库存数据库；管理员可在 Web 设置中创建普通账户，普通账户使用各自的密钥和独立的库存数据库。客户端绑定已认证的服务器和账户身份后才上传本地数据，避免把原有库存推到其他账户。普通账户不能管理部署、日志、其他账户或 MQTT。操作步骤见[多用户账户教程](docs/accounts.md)，设计与迁移边界见[账户隔离实施计划](docs/account-isolation-plan.md)。安装包与镜像的发布状态以对应 [Release](https://github.com/Rafael-Ikaros/Component_Manager/releases) 为准。

管理台使用 12 小时 HttpOnly 后端会话，账户密钥仅用于登录，不长期保存在浏览器。管理员可删除普通账户及其服务端数据库资料；客户端本地副本不会被远程清除，管理员原库不可删除。同步页面提供按设备登记、上传/拉取审计、冲突双方快照与人工处理记录。旧客户端未发送设备 ID 的拉取显示为未知设备；此前历史不会被补造。

Android 编辑元件的分类可选择或手动输入；Android / Windows 库位提供“选择已有”和“新建”两个入口，界面显示库位名称，内部用稳定库位码关联。Android / Windows 编辑主库位会把该元件的全部库存集中到目标库位；部分转移使用单独的转移操作。记录页默认隐藏已删除元件的流水，打开“包含已删除元件”可追溯历史；不会抹掉已保存流水。

设置 → 关于显示作者、构建版本与正式版更新检查；不在应用内更新服务端。可按 [Watchtower 教程](docs/dockerhub.md#可选-watchtower-自动更新)选择配置容器自动更新，默认部署不启用。

### 开发版新增导入能力（0.7.8-dev.7）

- Android 批量嘉立创入库页可选择立创商城物料明细对账单 `.xls`，按订购数量预览后入库。重复导入同订单同料号会跳过已提交项；该防重记录保存在本机，不是跨设备订单账本。
- 导入与编辑库位时选择名称或创建新名称，内部库位码自动生成；无供应商 SKU 的手动元件可留空，保存时生成 `CV-` 内部料号。
- 分类修正可按相同官方分类路径复用到其他 SKU；不会按品牌推断，也不会覆盖明确的用户字段。未知分类保留原文。
- Android 手动元件可拍照或选图，本地图片参与 Excel 备份，但当前不会随服务器同步图片文件。
- Web 元件详情增加删除确认，需开启 Web 库存写入；删除同步到客户端，历史流水保留。

订单格式与操作见[库存导入说明](docs/storage-and-backup.md#立创订单对账单导入)。以上功能仍需实际使用验证，不视为正式版验收。

## 快速部署 API 与管理 Web

将仓库中的 [`docker-compose.hub.yml`](docker-compose.hub.yml) 和 [`.env.example`](.env.example) 放进固定部署目录，复制 `.env.example` 为 `.env`。新部署让 `API_TOKEN`、`ADMIN_WEB_ORIGINS`、`ADMIN_WEB_URL` 和 `WEB_INVENTORY_ENABLED` 保持空值，再执行：

```sh
docker compose -f docker-compose.hub.yml pull api
docker compose -f docker-compose.hub.yml up -d api
```

打开 `http://服务器IP:8787/setup` 完成首次配置。需要完整管理 Web 时，再运行：

```sh
docker compose -f docker-compose.hub.yml --profile web pull
docker compose -f docker-compose.hub.yml --profile web up -d
```

管理 Web 默认位于 `http://服务器IP:8081/`，API 位于 `http://服务器IP:8787/`。Compose 默认拉取 API `latest` 与 Web `web-latest`；固定版本时应成对选用已发布的版本标签。升级前备份 `/data`，随后 `pull` 和 `up -d`；不要用 `down -v` 删除库存数据卷。群晖目录映射、首次配置、Token 查找与升级步骤见[Docker 快速部署](docs/docker-quickstart.md)，镜像发布与运维细节见[Docker Hub 指南](docs/dockerhub.md)和[运维手册](docs/runbook.md)。

客户端若提示“服务端版本过老”，先核对实际运行的 API 容器和 `/auth/me` 路由，详见 [Docker 排障](docs/docker-quickstart.md#7-客户端提示服务端版本过老或无法确认账户身份)。仅更新 APK、Web 容器或重启旧容器不能更新 API。

## 使用指南

| 任务 | 文档 |
| --- | --- |
| 创建普通用户、分发密钥、隔离库存及完整备份 | [多用户账户教程](docs/accounts.md) |
| BOM 预览、缺料导出、批量扣库及 component-hub 数据迁移 | [BOM 与迁移](docs/bom-and-migration.md) |
| 嘉立创包装扫码、连续采集及批量入库 | [批量入库](docs/batch-jlc-inbound.md) |
| 多库位、库存转移、Excel 备份与恢复 | [库位与备份](docs/storage-and-backup.md) |
| Android 蓝牙标签打印 | [M1 功能与操作](docs/m1-printing-feature-matrix.md)、[设备测试与兼容边界](docs/printer-compatibility.md) |
| 商品查询失败、扫码诊断及问题反馈 | [诊断与反馈](docs/feedback-and-scan-diagnostics.md) |
| MQTT 库存订阅和 Home Assistant | [MQTT 指南](docs/mqtt.md) |
| 安装更新、保留数据与发布文件 | [应用更新](docs/app-updates.md)、[更新日志](docs/CHANGELOG.md) |

## 开发与集成

仓库目录：[`android-client/`](android-client/) 是 Android 客户端，[`windows-client/`](windows-client/) 是 Windows 客户端，[`admin-web/`](admin-web/) 是管理 Web，[`server/`](server/) 是 API 服务。[`client/`](client/) 是旧 Flutter 参考实现，不是当前原生 UI 的开发入口。

客户端模块化从库存编辑链路开始：Windows 将编辑状态、保存决策、库存筛选与详情、概览、出入库台账、同步调度、数据库初始化、只读查询和各类库存写入分开；新建库位与元件保存/追加仍一起提交。主 ViewModel 只连接各功能模块与页面；同步设置/账户绑定、上传快照和结果落库也分开维护。Android 导入页通过主 ViewModel 创建并刷新库位，支持系统重建时恢复编辑字段及待提交扫码复核输入。两端库存界面显示库位名称，保存和同步继续使用原稳定 ID；已删除元件的历史分配不会阻止 Windows 删除空库位。数据库格式、同步协议和原有库存保留；恢复能力与代码边界见[客户端库存编辑与恢复](docs/architecture.md#客户端库存编辑与恢复)。

服务端使用项目内的 `server/.venv`。在 Windows PowerShell 中执行：

```powershell
cd server
.\scripts\bootstrap.ps1
.\scripts\run-dev.ps1
.\.venv\Scripts\python.exe -m pytest
```

管理 Web 可在 `admin-web/` 执行 `npm install`、`npm run dev`、`npm run build`；Android 使用 `gradle -p android-client assembleRelease`；Windows 使用 `dotnet build windows-client\ComponentVault.WinUI\ComponentVault.WinUI.csproj`。平台前置条件、发布构建与排障步骤见[运维手册](docs/runbook.md)。

对接 API 时从[集成指南](docs/integration-guide.md)开始；理解本地写入、同步游标、冲突与账户数据库边界可读[架构说明](docs/architecture.md)。修改版本与发布流程前，请先看[更新日志](docs/CHANGELOG.md)及[版本维护说明](docs/runbook.md#versioning-workflow)。

## 项目与作者

作者：**Rafael-Ikaros**。源代码、问题反馈与发布文件位于 [Rafael-Ikaros/Component_Manager](https://github.com/Rafael-Ikaros/Component_Manager)。

Android CI 的扫码匹配和数量边界检查独立报告，超时与回归日志的查看方法见[测试排查](docs/runbook.md#android-测试超时排查)。

## BOM 预设互通与配套件（dev）

Android 和 Windows 的项目 BOM 预设可通过同一账户的服务端同步，保留人工匹配、跳过项和来源信息。Web 管理台增加只读“BOM 预设”页。保存、打开与同步预设都不会扣库存；临时配套可逐项选料或带入预设，复核数量和库位后明确确认才一次出库。

服务端需支持 `bom_preset_protocol: 1`；旧服务端仍可同步库存，客户端会说明预设未同步。旧预设自动备份迁移，已有库存数据与原库存协议保持不变。Excel 库存备份不包含预设，详情见 [项目 BOM 与配套件教程](docs/bom-and-migration.md#预设跨设备同步与配套件)。照片文件同步见下方 dev19 说明。

## 元件照片跨设备同步（dev19）

Android、Windows 保存的元件照片可在同账户下同步，Web 登录后可查看；原生客户端仍先写本地并支持离线查看已有照片。照片独立于库存与 BOM 预设传输，设置页显示照片状态、失败原因和缓存清理入口。旧服务端或传输失败会明确提示部分完成，不清除本地原图或照片关联。API 的 `/auth/ping` 应返回 `photo_protocol: 1`。

服务端照片保存在账户数据库旁的 `.photos` 目录，沿用整个 `/data` 挂载，无新增环境变量。升级、重试、替换与备份方法见[照片同步教程](docs/photo-sync.md)。此 dev 不更新 Docker 正式标签。

同步恢复增强（dev20）：设置页统一显示库存、BOM 预设与照片的待处理情况，区分同步中、完成、部分完成、失败、取消与中断。库存成功提交后立即记录库存完成时间；后续照片或预设未完成不会抹去已完成结果。关闭应用后遗留的运行状态会提示中断，可通过“立即同步”重试；运行时可取消。离线保存仍先落本机，同步期间的新改动保留为待下轮处理。库存/BOM/照片的成功时间分别标明范围。

当前项目 GitHub 地址为 [Rafael-Ikaros/Component_Manager](https://github.com/Rafael-Ikaros/Component_Manager)。dev20 更新检测与可信下载链接已切换到新地址，修复改名后旧 API 返回 301 导致的检查失败。

### Windows 订单复核（当前 master）

Windows 支持立创订单 `.xls` 导入并生成批量入库草稿；修改 C 料号会清除旧商品资料，需要重新查询或手填后确认。待处理区将“重新查询所选”和“确认资料无误”分开，退款、缺订单等来源警告不会因查询成功而自动解除。旧草稿继续保留，不能确定来源的旧错误需人工确认。

Windows CI 运行核心、订单导入、Markdown 三个测试项目；发布候选额外执行订单导入与 Markdown 检查。测试结果与桌面实际操作验收分别记录。服务端现支持“从订单更新资料”：上传立创 `.xls` 后预览、选择中文分类/封装/明确参数，保留名称、图片、备注、数量和库位。写入需启用 Web 库存操作，完成后设备同步获取；见 [订单资料更新教程](docs/order-metadata-update.md)。
