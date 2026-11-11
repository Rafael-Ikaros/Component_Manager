<p align="center">
  <img src="docs/design/branding/component-vault-logo-ikaros.png" width="180" alt="Ikaros 元件仓库徽章" />
</p>

<h1 align="center">Component Vault</h1>
<p align="center"><strong>元件有处可放，项目有料可用。</strong></p>
<p align="center">面向电子爱好者的元器件库存管理 · Android / Windows · 离线可用 · 可选自托管同步</p>

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
  <a href="https://github.com/Rafael-Ikaros/Component_Manager/releases/latest/download/component-vault-windows-portable-x64.zip"><img src="docs/design/readme/download-windows.svg" width="250" height="84" alt="下载 Windows 自包含 ZIP" /></a>
  <a href="docs/docker-quickstart.md"><img src="docs/design/readme/deploy-docker.svg" width="250" height="84" alt="查看 Docker 与群晖部署教程" /></a>
</p>

---

Component Vault 把包装袋里的元件整理成可查找的库存：扫描入库、记录存放位置，
需要组装时导入 BOM 或逐项选料，复核后再出库。
Android 和 Windows 将数据保存在本机 SQLite，**不用部署服务器，也可以完整管理本地库存**。
需要多设备使用时，再连接自己的服务端。

> [!NOTE]
> 上方下载按钮提供正式版。下文也介绍 dev 功能，包含国内 EDA 查询、购买价格与材料成本、料盘识别和网页更新。正式版范围见[发布说明](docs/releases/0.7.8.md)，开发版见 [GitHub Releases](https://github.com/Rafael-Ikaros/Component_Manager/releases)。

## 能做什么

| 扫描与整理 | 查找与使用 |
| --- | --- |
| **嘉立创扫码入库**：扫描包装、输入 C 料号或关键词查询；支持连续采集和订单 Excel 批量入库。 | **型号与参数检索**：组合搜索品牌、型号片段、阻值、容量、封装等信息。 |
| **多库位库存**：一个元件可存放在多个库位，支持入库、出库、部分及批量转移。 | **项目用料**：导入 BOM、加载预设或手动选料，匹配库存、查看缺料，确认后一次出库。 |
| **标签与备份**：在 Android 编辑器中选择或保存纸张尺寸，编辑文字与位置，导出或通过蓝牙打印。支持汉印 M1 和 HM-A300E。支持 Excel 备份恢复。 | **跨设备同步**：同账户同步库存、BOM 预设和原图；Web 可管理账户、设备与同步情况。 |

### 扫描入库，保留你熟悉的型号

中文查询使用**国内嘉立创 EDA**，英文使用国际来源，不要求 Cookie，不自动跨区回退。
Android 可在「添加元件」拍摄或选择料盘标签照片。应用在本机读取型号，
再按完整型号查询目录。标签与目录的品牌、封装或参数有差异时，需核对差异后确认回填。
入库前请填写实际数量和库位。
名称保留品牌和型号，阻值、容量、电感量、精度、耐压等放在独立参数信息中，并参与搜索。
参考价格会注明币种、采购数量档和查询时间，不代表实际购入成本。

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
MQTT 可把已同步的库存变化发布给 Home Assistant 或看板。
跨设备离线库存变更不会无条件自动合并，详见[同步边界](docs/architecture.md)。

## 开始使用

### 只在本机管理库存

1. 下载上方 Android APK 或 Windows ZIP；Windows 解压完整目录后运行程序。
2. 从库存的“导入与扫码”添加元件，选择已有库位或创建库位。
3. 按需使用项目用料、标签和备份。同步可以暂不配置。

正式版适合日常使用；希望提前体验新功能，可选择[开发版](https://github.com/Rafael-Ikaros/Component_Manager/releases)。
客户端在“设置 → 关于”选择自己的更新通道。安装和保留数据更新见[应用更新指南](docs/app-updates.md)。

### 让手机、电脑和网页共用库存

按照[Docker／群晖快速部署](docs/docker-quickstart.md)启动 API 与 Web。
普通正式版部署可直接启动，开发版在 `.env` 中只需指定渠道。管理员密钥与 Web 设置在 `/setup` 保存，MQTT 在管理台设置。
API 使用独立镜像；从 `0.7.9-dev.8` 起，Web 与可选 Updater 共用管理镜像、分别运行在两个容器中。
首次访问 `http://服务器IP:8787/setup` 设置管理员密钥，再在管理台创建普通账户。
客户端填写 API 地址及对应账户密钥；同一账户的设备共享库存，不同账户相互隔离。

> [!IMPORTANT]
> **0.7.8 可以保留数据升级 dev，但只换 API/Web 镜像不会自动启用网页更新。**
> 需要网页切换更新渠道时，使用[网页更新单文件模板](docker-compose.hub-web-update.yml)，并在 `.env` 指定项目目录。群晖项目名由 Container Manager 传给 Compose。
> 第一次升级与启用组件可以一起完成，见[服务端更新教程](docs/server-web-update.md)。

普通部署默认不会自动安装新镜像。网页 updater 由管理员确认后执行；
定时跟随当前渠道可选择[Watchtower](docs/docker-quickstart.md#watchtower-updates)，同一服务只使用一种更新方式。

## 找到你需要的指南

| 任务 | 文档 |
| --- | --- |
| 连续扫码、订单导入 | [批量入库](docs/batch-jlc-inbound.md) |
| BOM、预设与配料 | [项目用料](docs/bom-and-migration.md) |
| 采购金额、库存均价与 PCBA 材料成本 | [购买价格与材料成本](docs/inventory-cost.md) |
| 库位、转移、Excel 备份和标签 Excel | [库存与备份](docs/storage-and-backup.md) |
| 元件快捷打印、标签编辑、纸张预设与设备校准 | [标签打印](docs/m1-printing-feature-matrix.md) |
| HM-A300E 配对、纸张设置与兼容范围 | [HM-A300E](docs/hm-a300e.md) |
| 照片、账户和服务端接入 | [完整文档导航](docs/README.md) |

## 开发与参与

| 模块 | 技术 |
| --- | --- |
| [Android](android-client/) | Kotlin · Jetpack Compose · Material 3 |
| [Windows](windows-client/) | C# · WinUI 3 · SQLite |
| [管理 Web](admin-web/) | React · Vite · shadcn/ui |
| [服务端](server/) | Python · FastAPI · 按账户隔离的 SQLite |

[架构说明](docs/architecture.md) · [接口契约](docs/integration-guide.md) · [构建与发布](docs/runbook.md) · [验证记录](docs/history/verification.md)

Docker 发布复用已验证的固定版本，只补发缺失的 API 或管理镜像。通过 Compose 更新验证后再推进对应渠道。[镜像发布指南](docs/dockerhub.md)

反馈问题请提供应用／容器版本、复现步骤和实际报错。应用内反馈可选择附加诊断摘要；
不要提交账户密钥或完整私人数据库。开发中的功能先发布 dev，验证后再决定正式发布。

本项目使用 [GPL-3.0 许可证](LICENSE.txt)。

<p align="center">由 <a href="https://github.com/Rafael-Ikaros">Rafael-Ikaros</a> 维护 · Ikaros 与你的元件仓库</p>
