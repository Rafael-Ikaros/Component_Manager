# 在网页中更新服务端

本页适用于官方 Docker Compose 和群晖 Container Manager 项目。
启用 updater 后，管理员可在“设置 → 关于服务端”选择渠道、确认更新并查看进度。
配置可采用[完整单文件模板](../docker-compose.hub-web-update.yml)。

## 是否只换镜像就够

| 操作 | 当前支持情况 |
| --- | --- |
| 0.7.8 保留库存升级到 dev | 支持；在原项目成对更新 API 和 Web，沿用完整 `/data` 和应用配置 |
| 只换 dev 镜像，部署一项不改，就能在网页更新 | 不支持；镜像升级不会自动创建 updater 或补上它需要的挂载 |
| 已启用 updater 后更新到后续兼容正式版或 dev | 支持；网页选择并确认即可，日常无需手改部署 |
| 从 dev 在网页切回当前旧正式 0.7.8 | 不支持；旧目标缺少网页更新能力 |
| 网页检查到新版就自动安装 | 不会；updater 需要管理员确认，定时跟随当前标签应选择 Watchtower |

保留原数据升级不等于零部署配置启用网页更新。第一次可把升级和启用 updater 合并为一次操作。
updater 自身的镜像维护仍通过原项目完成，不承诺所有未来部署结构变化都能自动处理。

## 已有正式版先升级到 dev

不需要重新安装或重新初始化库存。2026-11-02 已核实的正式镜像为
`latest / web-latest`（0.7.8），当前已验证开发镜像为 `dev / web-dev`（0.7.9-dev.7）。
保持原 Compose 项目名称、完整 `/data`、现有环境变量、端口和网络，在 Container Manager
原项目中成对改用并拉取开发镜像，然后重新部署。只更新 Web 或只重启旧 API 都不算完成升级。
如果原文件已经使用本仓库的渠道变量，可设 `COMPONENT_VAULT_DOCKER_CHANNEL=dev`，
并清空仅用于固定镜像的 `COMPONENT_VAULT_IMAGE / COMPONENT_VAULT_WEB_IMAGE`；
原 `API_TOKEN` 等应用配置继续保留。没有采用变量的旧文件则需成对修改 `image`。

升级后登录原 Web，使用原管理员密钥。已有数据会继续使用；配置页不应重新要求创建管理员。
若出现空库存或初始化页，应先检查是否用了新项目名或空数据挂载，不要在空库中继续初始化。

| 你要修改的内容 | 升级到 dev 后的入口 | 是否需要修改部署 |
| --- | --- | --- |
| 账户与密钥、Web 库存开关、允许来源和管理台地址 | Web 设置；首次配置也可用 `/setup` | 网页管理的字段直接保存；非空环境变量接管的字段仍只读 |
| MQTT | Web 设置中的 MQTT 配置 | 保存后按页面提示重启 API 生效 |
| 镜像更新渠道、拉取并替换 API/Web | Web“关于服务端” | 需先启用 updater；仅升级 API/Web 不会自动新增它 |
| Docker 端口、宿主目录、Docker socket、额外容器 | 群晖原项目的 Compose | 需要在宿主部署中保存并重新创建容器 |

希望网页接管应用设置时，不要直接清空现有 `API_TOKEN` 等变量：应先确认相同值已保存在
持久化配置中，再逐项解除环境变量覆盖。具体优先级见[配置说明](docker-quickstart.md#3-环境变量和网页配置的优先级)。
不要在容器临时目录修改代码、安装包或新增 `.env` 来代替升级；容器重建会丢失这些改动。
应用设置保存在挂载的 `/data/config.json`，MQTT 保存在数据库，部署设置来自宿主 Compose 与 `.env`。

**最少一次操作的做法**：第一次从正式版升级时，就在同一次原项目编辑中加入下方 updater，
成对拉取 API/Web 和 updater 后一起部署。无需先升级一次、配置一次再重建第二次。
后续使用网页更新时只替换 API/Web，保留原项目、挂载和应用配置。
Docker 的镜像/挂载变更通过重建应用；Compose 保留已有挂载卷，见
[官方 Compose 说明](https://docs.docker.com/reference/cli/docker/compose/up/)。

## 当前支持范围

- API、Web 和 updater 需使用包含此功能的配套版本，使用 0.7.9-dev.7 或更高兼容版本。
  dev.6 的发布验收暴露了更新后 `.env` 所有者/权限丢失的问题，请勿用于首次启用。
- 当前正式版 0.7.8 不包含网页更新入口。在线更新会在替换前检查目标 API/Web 的
  `io.componentvault.server-update=1` 能力标识；不支持的旧版本会被拒绝，现有容器不变。
  待包含此能力的正式版通过验证并发布后，才能在受支持的正式版与 dev 之间在线切换。
- 只更新本项目 API 和已部署的 Web；不管理其它应用，不自动更新 updater 自身，不自动回滚数据库。
- updater 使用 Docker CLI 28.x / 配套 Compose 并保留 API 自动协商，照顾尚未更新 Engine 的 NAS。
  Docker 29 移除了针对较旧 Engine API 的部分客户端兼容处理，见[官方说明](https://docs.docker.com/engine/release-notes/29/)。这不是所有 DSM/Engine 组合都已经实测的承诺。
- 群晖实际按钮以 DSM / Container Manager 版本为准。以下配置是 Compose 项目操作，
  不是单独创建三个互不关联的容器；未在用户 NAS 上验证的步骤应先在测试项目试用。

## 群晖一次性配置

这一次操作是让 Docker 授予更新组件管理本项目容器的能力。旧 API 本身没有 Docker 管理权限，
无法通过普通网页请求给自己补上挂载。无需开放群晖 SSH，也不需填写 Docker Hub 密钥。
新部署可直接采用完整模板，从一开始就启用；已有部署需在原项目保存并重新部署一次。

1. 备份整个 API `/data`，记录原项目名、端口、数据来源和原 Compose 文件。保留各账户数据库、
   配置、照片及日志。不要新建同名仓库的数据卷，也不要删除旧卷。
2. 从对应版本源码包取得 `docker-compose.hub-web-update.yml`。
   在 File Station 打开**原项目目录**，保留原 `.env`；没有 `.env` 时创建它。
3. `.env` 加入下面设置，目录与项目名替换为自己的值；其它值保留：

   ```dotenv
   COMPOSE_PROJECT_NAME=原项目名
   COMPONENT_VAULT_DEPLOY_DIR=/volume1/docker/component-vault
   COMPONENT_VAULT_DOCKER_CHANNEL=dev
   COMPONENT_VAULT_IMAGE=
   COMPONENT_VAULT_WEB_IMAGE=
   COMPONENT_VAULT_UPDATER_IMAGE=
   ```

   原完整镜像覆盖非空时优先于渠道键，应清空或指定配套的支持版本。开发阶段 updater 使用
   `rafaelikaros/component_manager:updater-dev`，正式渠道未来使用 `updater-latest`。
4. 在 Container Manager 的**原项目**中编辑 Compose，使用完整的 `docker-compose.hub-web-update.yml` 内容，
   按原配置保留端口、API 数据来源及其它自定义设置，再保存为该项目原本使用的文件名。
   完整模板默认同时启动 Web，不用再开启 `web` profile，也不用手动合并三套服务。
   **现有部署的端口、数据卷或自定义网络与模板不一致时，不要直接覆盖这些部分。**
   对已经深度定制的项目，也可继续只合并 `docker-compose.web-update.yml` 中的更新服务配置。
   API/Web 的 `image` 应保留 Hub 模板的变量形式；写死旧镜像会令在线更新停止并提示修正。
5. 核对四个必要映射：

   | updater 中的路径 | 来源 | 用途 |
   | --- | --- | --- |
   | `/var/run/docker.sock` | 主机同路径 | 通过 Docker/Compose 重建本项目服务 |
   | `/data:ro` | **API 实际使用的同一数据目录或卷** | 读取自动生成的内部认证文件 |
   | `/state` | 独立持久卷 | 保存更新任务，API 重启不丢失进度 |
   | 原部署目录的相同绝对路径 | 例如 `/volume1/docker/component-vault` → 同路径 | 读取原 Compose 并保存渠道配置 |

   示例默认 API 使用 named volume `component_vault_data`。现有 API 如果使用
   `/volume1/docker/component-vault/data:/data`，updater 也应改为同一来源且只读，
   不能另挂一个空 named volume。API/updater 的 `UPDATE_TOKEN_FILE` 均为
   `/data/.updater-token`，由 API 自动创建，**不需手动填写或复制密钥**。
6. updater 必须加入 API 所在的同一网络；默认 Hub 的 default network 已满足。
   自定义网络的部署在 updater 也声明该网络，保留 `api` / `admin-web` 服务名。
   原部署目录以外若还有 Compose override、env/config 文件，需以相同绝对路径映射到 updater。
7. 若使用 Watchtower，API/Web 的 `com.centurylinklabs.watchtower.enable` 设为 `false`，
   避免两个更新服务同时重建；其它应用仍可沿用 Watchtower。
8. 在 GUI 中保存、拉取新的配套镜像并重新部署原项目一次，不删除数据卷。登录 Web 管理台，管理员在关于区看到可用的
   “服务端更新”后，日常升级只需网页操作。

Docker socket 具有主机 Docker 管理能力，仅挂载到 updater；API、Web 不挂载 socket，
updater 不映射主机端口。实际更新接口仅管理员可调用，普通账户没有更新入口。

## 日常网页操作

1. 选择正式版或开发版，可先“检查更新”。选择本身不会替换容器。
2. 点击“更新到所选渠道”，核对当前版本、目标渠道及备份提示，再确认。
3. 更新服务先拉取镜像、检查版本/提交与入口能力，再备份 `.env` 并重建原服务。
   页面显示等待、拉取、校验、重建和健康检查；重启时可能短暂断线。
4. 页面会自动重连；刷新后也可恢复任务。成功后重新加载管理台，核对实际版本及库存。

拉取或能力检查失败时原运行容器不变，页面给出明确原因。开始重建后不能保证两服务
同时切换；失败时核对 Container Manager 的 API/updater 日志，再重试同一任务。
任务只保留最近 100 个已结束记录。updater 自身重启后会重新排队未完成任务，
不承诺所有手动改动或半完成部署均能自动修复。

若新 API 始终不能启动，业务网页也可能无法连接；作业和日志仍保存在 updater，
此时可在 Container Manager 查看/恢复原配置。不要把切回旧镜像当作数据库恢复。
`.env.before-channel-*` 只是配置备份，完整业务数据恢复需要之前的 `/data` 备份。

## 命令行仍可选用

不启用网页更新的部署仍可使用[渠道切换助手](docker-channels.md)。CLI 手动降级不强制
保留网页更新能力，管理员应理解切回旧版本会失去更新入口及可能存在的数据兼容问题。
