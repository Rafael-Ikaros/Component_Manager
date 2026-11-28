<p align="center">
  <img src="docs/design/branding/component-vault-logo-ikaros.png" width="180" alt="Ikaros 元件仓库徽章" />
</p>

<h1 align="center">Component Vault</h1>
<p align="center"><strong>元件有处可放，项目有料可用。</strong></p>
<p align="center">面向电子爱好者的元器件库存管理 · Android · 离线可用 · 可选自托管 Web 与同步</p>

<p align="center">
  <a href="https://github.com/Rafael-Ikaros/Component_Manager/releases/latest"><img src="https://img.shields.io/github/v/release/Rafael-Ikaros/Component_Manager?label=Stable&amp;color=0d9488&amp;labelColor=102d3b" alt="最新正式版" /></a>
  <a href="https://github.com/Rafael-Ikaros/Component_Manager/releases"><img src="https://img.shields.io/github/v/release/Rafael-Ikaros/Component_Manager?include_prereleases&amp;label=Dev&amp;color=3b82f6&amp;labelColor=102d3b" alt="最新版本，含预发布" /></a>
  <a href="https://github.com/Rafael-Ikaros/Component_Manager/actions/workflows/ci.yml"><img src="https://github.com/Rafael-Ikaros/Component_Manager/actions/workflows/ci.yml/badge.svg?branch=master" alt="客户端与服务端 CI 状态" /></a>
  <a href="https://github.com/Rafael-Ikaros/Component_Manager/actions/workflows/docker-ci.yml"><img src="https://github.com/Rafael-Ikaros/Component_Manager/actions/workflows/docker-ci.yml/badge.svg?branch=master" alt="Docker 构建检查状态" /></a>
  <a href="LICENSE.txt"><img src="https://img.shields.io/github/license/Rafael-Ikaros/Component_Manager?color=64748b&amp;labelColor=102d3b" alt="GPL-3.0 许可证" /></a>
</p>

<p align="center">
  <a href="#开始使用">开始使用</a> ·
  <a href="#能做什么">功能介绍</a> ·
  <a href="docs/README.md">使用文档</a> ·
  <a href="docs/CHANGELOG.md">更新日志</a> ·
  <a href="https://github.com/Rafael-Ikaros/Component_Manager/issues">问题反馈</a>
</p>

<p align="center">
  <a href="https://github.com/Rafael-Ikaros/Component_Manager/releases/latest/download/component-vault-android-release.apk"><img src="docs/design/readme/download-android.svg" width="250" height="84" alt="下载 Android 正式版 APK" /></a>
  <a href="docs/docker-manual.md"><img src="docs/design/readme/deploy-docker.svg" width="250" height="84" alt="查看群晖界面部署图文教程" /></a>
</p>

---

Component Vault 把包装袋里的元件整理成可查找的库存：扫描入库、记录存放位置，
需要组装时导入 BOM 或逐项选料，复核后再出库。
Android 将数据保存在本机 SQLite，**不用部署服务器，也可以完整管理本地库存**。
需要多设备使用时，再连接自己的服务端。

> [!NOTE]
> 上方下载按钮提供 Android 正式版。正式版范围与升级须知见[发布说明](docs/CHANGELOG.md#079---2026-11-27)，开发版见 [GitHub Releases](https://github.com/Rafael-Ikaros/Component_Manager/releases)。
> 部分开发版仅提供 Android APK。
>
> **Windows 独立客户端已停止维护。** 自 2026-11-25 起不再构建、更新或发布 Windows 安装包，版本工具也不再改写其版本号。源码和[历史版本附件](https://github.com/Rafael-Ikaros/Component_Manager/releases)保留，后续 Windows 功能计划集成到贴片机上位机项目。

## 能做什么

| 扫描与整理 | 查找与使用 |
| --- | --- |
| **嘉立创扫码入库**：扫描包装、输入 C 料号或关键词查询；支持连续采集和订单 Excel 批量入库。 | **型号与参数检索**：组合搜索品牌、型号片段、阻值、容量、封装等信息。 |
| **多库位库存**：一个元件可存放在多个库位，支持入库、出库、部分及批量转移。 | **项目用料**：导入 BOM、加载预设或手动选料，匹配库存、查看缺料，确认后一次出库。 |
| **标签与备份**：在 Android 编辑器中选择或保存纸张尺寸，调整文字、字体、字号与位置，导出或通过蓝牙打印。支持汉印 M1、HM-A300E、德佟 P1，开发版另支持 SUPVAN T50 Pro。可通过 Excel 备份和恢复库存。 | **跨设备同步**：同账户同步库存、BOM 预设和原图；Web 可管理账户、设备与同步情况。 |

### 扫描入库，保留你熟悉的型号

中文查询使用**国内嘉立创 EDA**，英文使用国际来源，不要求 Cookie，不自动跨区回退。
Android 可在「添加元件」拍摄或选择料盘标签照片。应用在本机读取型号，
再按完整型号查询目录。标签与目录的品牌、封装或参数有差异时，需核对差异后确认回填。
入库前请填写实际数量和库位。
名称保留品牌和型号，阻值、容量、电感量、精度、耐压等放在独立参数信息中，并参与搜索。
参考价格会注明币种、采购数量档和查询时间，不代表实际购入成本。

Android `0.8.0-dev` 开发版的库存列表用紧凑行突出数量和低库存，「全部、低库存、分类、库位、排序」可在同一行横向滑动。
手机上从列表底部的「添加 / 导入」进入原有导入菜单。详情集中展示照片、数量与规格，可直接打开入库、出库或调拨表单；实际库存成本与商城参考价分开显示。
设置首页显示本地元件数、连接配置及更新通道，「外观」可独立切换主题和强调色。
「仓库与数据」保留服务端连接、库位和备份入口，也可打开原有标签与打印工具。

Web 管理台在手机上使用底部导航。库存列表优先显示名称、料号、库位和数量，点整行查看详情。「多选」可批量删除当前页选中的元件，
删除会同步到同账户设备并保留历史流水。「库存工具」下的[库存资料检查](docs/parameter-enrichment.md)
可扫描当前账户、复核目录差异并跨页全选可更新资料。应用前可以逐字段取消，厂商型号匹配须单独确认。数量、库位、名称和用户备注保持原值。

### 项目用料，确认后才扣库存

**导入 BOM／手动选料 → 匹配库存 → 核对数量与库位 → 确认出库。**

同一清单可以混合导入行和手动选料，也可保存为预设供下次使用。
导入、预览、保存和同步预设均不会扣库存；只有明确确认出库才提交。
操作步骤与替代候选条件见[项目用料指南](docs/bom-and-migration.md)。

实际购买价格与商城参考价分别记录。入库可填写单价或商品总金额，项目用料按当前库存加权平均价
估算单套与整批材料成本；历史无价库存明确显示未知，不当作零成本。首版使用人民币商品金额，
不含运费、PCB 和加工费，详见[购买价格与材料成本](docs/inventory-cost.md)。

### 离线使用，按需连接自己的服务端

每台原生客户端先保存本地修改，再按需同步。服务端为不同账户使用独立数据库，
可管理用户、设备、同步审计和冲突记录；Web 库存写入由管理员决定是否开启。
MQTT 可把管理员库存状态发布给 Home Assistant 或看板。
跨设备离线库存变更不会无条件自动合并。支持订单来源协议的 Android、Windows 和服务端可按同账户的立创订单号与源料号防重；历史无来源流水无法自动识别。详见[同步边界](docs/architecture.md)。
Android 遇到两条不同元件记录共用同一 SKU 时会提示核对，保留本地库存和同步进度。
同一元件在两端同时发生出入库时，Android 可在「设置 → 服务器连接 → 复核同步冲突」查看服务端库存。核对数量和库位并确认后，才会重新应用所列出入库。

## 开始使用

### 只在本机管理库存

1. 下载上方 Android APK 并安装。
2. 从库存的“导入与扫码”添加元件，选择已有库位或创建库位。
3. 按需使用项目用料、标签和备份。同步可以暂不配置。

正式版适合日常使用；希望提前体验新功能，可选择[开发版](https://github.com/Rafael-Ikaros/Component_Manager/releases)。
Android 客户端在「设置 → 关于与更新 → 应用更新」选择更新渠道（旧版为「关于」）。安装和保留数据更新见[应用更新指南](docs/app-updates.md)。

### 让手机与网页共用库存

可在群晖界面[直接创建 API、Web 容器](docs/docker-manual.md)，无需 Compose、`.env` 或 SSH；也可使用[普通 Compose 模板](docs/docker-quickstart.md)。两种普通部署方式都不向应用挂载 Docker socket，不要求特权模式。
管理员密钥与 Web 设置在 `/setup` 保存，MQTT 在管理台设置。
正式版 0.7.9 起使用统一镜像。从 `rafaelikaros/component_manager` 选择正式渠道 `latest`、开发渠道 `dev` 或对应的固定版本标签，分别创建 API 和 Web 容器。API 使用默认命令，Web 指定 `web`。已有配套镜像部署可继续用 `web-latest` 或 `web-dev` 启动 Web。库存、同步和检查更新均不依赖 Updater。
首次访问 `http://服务器IP:8787/setup` 设置管理员密钥，再在管理台创建普通账户。
客户端填写 API 地址及对应账户密钥；同一账户的设备共享库存，不同账户相互隔离。

> [!NOTE]
> **网页一键更新是可选项。** 手动升级时，先停 API 并备份完整数据目录，再从新镜像重建并沿用原 `/data` 映射。升级后在「关于服务端」分别核对 Web 和 API 版本。[群晖独立容器更新 API](docs/docker-manual.md#更新-api-容器) · [Compose 更新 API 和 Web](docs/docker-quickstart.md#更新-api-和-web)
> 希望在网页上重建容器时，按[群晖 Compose 与 Updater 图文教程](docs/server-web-update.md#群晖一次性配置)配置。Updater 单独运行，需要 Docker 管理权限。

若更新后仍显示旧版本，可直连 API 的 `/health`，再核对管理台的 API 地址和容器使用的映像 ID。dev.31 页面显示 `dw31`，镜像固定标签为 `0.7.9-dev.31`。[版本核对与排障](docs/docker-manual.md#核对-web-和-api-版本)

普通部署默认不会自动安装新镜像。可选的网页 Updater 由管理员确认后执行；
定时跟随当前渠道可选 [Watchtower](docs/docker-quickstart.md#watchtower-updates)，它同样需要 Docker 管理访问。同一服务只使用一种更新方式。

## 找到你需要的指南

| 任务 | 文档 |
| --- | --- |
| 连续扫码、C 编码清单、立创商城订单导入 | [批量入库](docs/batch-jlc-inbound.md) |
| BOM、预设与配料 | [项目用料](docs/bom-and-migration.md) |
| 采购金额、库存均价与 PCBA 材料成本 | [购买价格与材料成本](docs/inventory-cost.md) |
| 库位、转移、Excel 备份和标签 Excel | [库存与备份](docs/storage-and-backup.md) |
| 元件快捷打印、标签编辑、纸张预设与设备校准 | [标签打印](docs/m1-printing-feature-matrix.md) |
| 打印机支持范围、蓝牙协议与实测结果 | [打印机兼容性](docs/printer-compatibility.md) |
| 照片、账户和服务端接入 | [完整文档导航](docs/README.md) |

## 开发与参与

| 模块 | 技术 |
| --- | --- |
| [Android](android-client/) | Kotlin · Jetpack Compose · Material 3 |
| [Windows 历史源码](windows-client/) | C# · WinUI 3 · SQLite；停止维护，不再参与 CI 或发布 |
| [管理 Web](admin-web/) | React · Vite · shadcn/ui |
| [服务端](server/) | Python · FastAPI · 按账户隔离的 SQLite |

[架构说明](docs/architecture.md) · [接口契约](docs/integration-guide.md) · [构建与发布](docs/runbook.md) · [验证记录](docs/history/verification.md)

Docker 发布复用已验证的固定版本，只补发缺失的 API 或管理镜像。通过 Compose 更新验证后再推进对应渠道。[镜像发布指南](docs/dockerhub.md)

反馈问题请提供应用／容器版本、复现步骤和实际报错。应用内反馈可选择附加诊断摘要；
不要提交账户密钥或完整私人数据库。开发中的功能先发布 dev，验证后再决定正式发布。

本项目使用 [GPL-3.0 许可证](LICENSE.txt)。

<p align="center">由 <a href="https://github.com/Rafael-Ikaros">Rafael-Ikaros</a> 维护 · Ikaros 与你的元件仓库</p>
