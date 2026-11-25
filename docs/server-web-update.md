# 在网页中更新服务端

**网页一键更新是可选功能。** 普通部署、库存操作、客户端同步和检查版本都不需要 Updater，也不需要给应用挂载 Docker socket。不使用 Compose 或不愿授予 Docker 管理权限时，请使用[群晖手动部署与更新](docker-manual.md)。

本页适用于官方 Docker Compose 和群晖 Container Manager 项目。
启用 updater 后，管理员可在“设置 → 关于服务端”选择渠道、确认更新并查看进度。
**统一镜像尚未发布。** 当前使用 `0.7.9-dev.29` 时，下载 [dev.29 的完整单文件模板](https://github.com/Rafael-Ikaros/Component_Manager/blob/v0.7.9-dev.29/docker-compose.hub-web-update.yml)。该版本 API 使用独立镜像，Web 与 updater 共用 `web-*` 管理镜像，仍各自运行在独立容器。Web 使用默认命令，updater 使用 `command: [updater]`。当前正式 `latest` 缺少网页更新能力。统一版本的配置见[后续说明](#统一镜像版本发布后)。

## 是否只换镜像就够

| 操作 | 当前支持情况 |
| --- | --- |
| 0.7.8 保留库存升级到 dev | 支持；在原项目成对更新 API 和 Web，沿用完整 `/data` 和应用配置 |
| 只换 dev 镜像，部署一项不改，就能在网页更新 | 不支持；镜像升级不会自动创建 updater 或补上它需要的挂载 |
| 已启用 updater 后更新到采用同一部署结构的兼容版 | 支持；网页选择并确认。迁移到统一 Compose 模板时需先修改原项目 |
| 从 dev 在网页切回 0.7.8 | 不支持；旧目标缺少网页更新能力 |
| 网页检查到新版就自动安装 | 不会；updater 需要管理员确认，定时跟随当前标签应选择 Watchtower |

保留原数据升级不等于零部署配置启用网页更新。第一次可把升级和启用 updater 合并为一次操作。
updater 自身的镜像维护仍通过原项目完成，不承诺所有未来部署结构变化都能自动处理。

## 已有正式版先升级到 dev

已有库存可以直接沿用。当前正式版使用 `latest / web-latest`。以下步骤适用于升级到仍采用旧结构的 dev，使用 `dev / web-dev` 或同版本的固定标签。使用统一版本时按[迁移步骤](#统一镜像版本发布后)配置。
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

- 旧版共享管理镜像模板需使用 `0.7.9-dev.8` 或更新兼容版本；`web-0.7.9-dev.7`
  及旧正式镜像不能通过 `command: [updater]` 运行更新服务。已部署的独立 dev.7 updater
  可以继续使用，采用新模板时按下方迁移步骤操作。
- 0.7.8 不包含网页更新入口。在线更新会在替换前检查目标 API/Web 的
  `io.componentvault.server-update=1` 能力标识；不支持的旧版本会被拒绝，现有容器不变。
  正式版与 dev 均支持此能力时，可以在线切换。
- 只更新本项目 API 和已部署的 Web；不管理其它应用，不自动更新 updater 自身，不自动回滚数据库。
- updater 使用 Docker CLI 28.x / 配套 Compose 并保留 API 自动协商，照顾尚未更新 Engine 的 NAS。
  Docker 29 移除了针对较旧 Engine API 的部分客户端兼容处理，见[官方说明](https://docs.docker.com/engine/release-notes/29/)。这不是所有 DSM/Engine 组合都已经实测的承诺。
- 群晖请在 Container Manager 的“项目”中导入 Compose 文件。

## 群晖一次性配置

这一次操作是让 Docker 授予更新组件管理本项目容器的能力。旧 API 本身没有 Docker 管理权限，
无法通过普通网页请求给自己补上挂载。无需开放群晖 SSH，也不需填写 Docker Hub 密钥。
新部署可直接采用完整模板，从一开始就启用；已有部署需在原项目保存并重新部署一次。

1. **新建项目**时，在 File Station 准备固定项目目录，例如 `/volume1/docker/component_manager`。
   如果要使用宿主目录保存数据，先建好其中的 `data/` 子目录。将完整模板放入项目目录，
   在 Container Manager 创建项目时选择同一目录并导入模板。**已有项目**应在原目录修改原项目，
   先备份整个 API `/data`，记录原项目名、端口、数据来源和原 Compose 文件。
2. 在部署目录中放置 `.env`。新建 dev 项目填写目录和渠道，已有项目在原 `.env` 上修改：

   ```dotenv
   COMPONENT_VAULT_DEPLOY_DIR=/volume1/docker/component_manager
   COMPONENT_VAULT_DOCKER_CHANNEL=dev
   ```

   群晖使用 Container Manager 中的项目名，命令行使用 `docker compose -p 原项目名`。旧 `.env` 中已有相同的 `COMPOSE_PROJECT_NAME` 可以保留。
   使用旧模板固定在 dev.29 时，令 `COMPONENT_VAULT_IMAGE` 为 `rafaelikaros/component_manager:0.7.9-dev.29`，令 `COMPONENT_VAULT_WEB_IMAGE` 为 `rafaelikaros/component_manager:web-0.7.9-dev.29`，后者同时供 Web 和 updater 使用。这两个配置优先于渠道变量。
   共享管理镜像的模板已停止读取 `COMPONENT_VAULT_UPDATER_IMAGE`，旧 `.env` 中此项可移除。
3. 使用与所选镜像版本对应的模板，保存为 `docker-compose.hub-web-update.yml`。dev.29 使用页首模板，统一版本按[下方配置](#统一镜像版本发布后)选择。
   已有项目沿用原文件名、端口、API 数据来源及其它自定义设置。MQTT 或目录查询曾使用环境变量时，保留原 `api.environment` 中相应的条目。
   完整模板默认同时启动 Web，不用再开启 `web` profile，也不用手动合并三套服务。
   **现有部署的端口、数据卷或自定义网络与模板不一致时，不要直接覆盖这些部分。**
   已有自定义 Compose 可以从完整模板合入 `updater` 服务和对应的顶层 `component_vault_updater_state` 卷声明，并复制 `api.environment.UPDATER_URL` 与 API/Web 的 Watchtower 排除标签。
   API/Web 的 `image` 应保留 Hub 模板的变量形式；写死旧镜像会令在线更新停止并提示修正。
4. 核对四个必要映射：

   | updater 中的路径 | 来源 | 用途 |
   | --- | --- | --- |
   | `/var/run/docker.sock` | 主机同路径 | 通过 Docker/Compose 重建本项目服务 |
   | `/data:ro` | **API 实际使用的同一数据目录或卷** | 读取自动生成的内部认证文件 |
   | `/state` | 独立持久卷 | 保存更新任务，API 重启不丢失进度 |
   | 原部署目录的相同绝对路径 | 例如 `/volume1/docker/component_manager` → 同路径 | 读取原 Compose 并保存渠道配置 |

   模板默认使用 named volume `component_vault_data`。若新建项目选择宿主 `data/`，
   或现有 API 已使用 `/volume1/docker/component_manager/data:/data`，保留该 API 映射，updater 使用 `/volume1/docker/component_manager/data:/data:ro`，
   不能另挂一个空 named volume。默认数据库布局下，API 自动创建 `/data/.updater-token`，updater 从同一数据卷读取。
   已定制数据库或令牌路径的项目继续在 API 和 updater 保留同一个 `UPDATE_TOKEN_FILE`。无需手动填写或复制密钥。
5. updater 必须加入 API 所在的同一网络；默认 Hub 的 default network 已满足。
   自定义网络的部署在 updater 也声明该网络，保留 `api` / `admin-web` 服务名。
   原部署目录以外若还有 Compose override、env/config 文件，需以相同绝对路径映射到 updater。
6. 若使用 Watchtower，API/Web 的 `com.centurylinklabs.watchtower.enable` 设为 `false`，
   避免两个更新服务同时重建；其它应用仍可沿用 Watchtower。
7. 在 Container Manager 的「映像」中更新项目使用的标签；尚未下载的标签从「注册表」下载。使用旧模板时确认 API 和管理镜像的版本一致。[群晖映像管理](https://kb.synology.com/zh-cn/DSM/help/ContainerManager/docker_image?version=7)
8. 在「项目 → 原项目 → YAML 配置」保存并部署。对照原设置核对 API/Web 端口、`/data` 和 updater 的 `/state`；只点「重新启动」会继续运行已有容器。更新已有项目时，先确认没有进行中的网页更新任务；换用新模板或更新 updater 自身需要重新部署 updater。避免使用「清除」或「删除」项目。[群晖项目管理](https://kb.synology.com/zh-cn/DSM/help/ContainerManager/docker_project?version=7)
9. 在原 API 地址打开 `/health` 核对版本，再登录管理台检查库存与「设置 → 关于服务端」的更新状态。新安装应先在 `/setup` 保存管理员密钥和 Web 地址，应用设置会写入 `/data/config.json`。

旧项目若使用 `docker-compose.web-update.yml` 作为第二份配置，请在原项目中改用完整模板并重新部署。命令行部署将原来两个 `-f` 合为 `-f docker-compose.hub-web-update.yml`，继续使用原项目名；有自定义配置时按第 3 步合并。Updater 读取容器记录的 Compose 文件路径，重新部署前应在原路径保留旧文件。部署目录直接使用仓库检出时，请在拉取前准备好迁移配置。

Docker socket 具有主机 Docker 管理能力，仅挂载到 updater；API、Web 不挂载 socket，
updater 不映射主机端口。实际更新接口仅管理员可调用，普通账户没有更新入口。
updater 在后续切换时读取原 Compose 文件，并在同一目录备份和更新 `.env` 中的镜像设置。

这里的 Docker socket 权限不是“仅允许修改本项目”的权限。常见 root 模式下，控制 Docker 守护进程可获得主机级能力，见[官方说明](https://docs.docker.com/engine/security/)。更新器只操作指定项目是本程序的逻辑约束，不是 Docker 为该 socket 提供的项目级隔离。不接受这一授权时，保留普通 API/Web 并手动更新；无需为了使用业务功能启用更新器。

## 统一镜像版本发布后

使用对应版本的 [`docker-compose.hub-web-update.yml`](../docker-compose.hub-web-update.yml)。同一个完整镜像标签应用于 API、Web 和 updater；API 使用默认的 `api` 启动命令，Web 和 updater 分别由模板显式指定 `command: [web]` 和 `command: [updater]`。新建开发部署使用已发布的统一 `dev`；等统一正式版发布后才使用 `latest`。要固定版本，只设置 `COMPONENT_VAULT_IMAGE`。原项目残留的非空 `COMPONENT_VAULT_WEB_IMAGE` 会覆盖 Web 和 updater 镜像，迁移时移除该覆盖值。

已有部署应先等待网页更新任务结束、备份完整 `/data`，再把统一模板的镜像、命令及 API 布局标识合入原项目。沿用原项目名、管理员密钥、应用环境变量、端口、网络、`/data`、updater 的只读数据入口、`/state` 和项目目录等挂载。核对实际配置并重建三个服务。继续使用旧模板的部署仍通过配套的 `web-*` 标签获得默认 Web 入口。

网页切换渠道前会校验目标镜像具备统一角色。当前稳定版及 dev.29 等旧镜像须使用对应旧版部署结构；回退时在宿主机恢复原 Compose 配置，并按需要恢复一致的数据备份。

## 从独立 Updater 镜像迁移

已运行 `updater-dev` 的旧部署可以先保留原 updater，继续更新兼容的 API/Web。
若升级到使用共享管理镜像的旧布局（例如 dev.29），在原项目中将 updater 的镜像改为与 Web 相同的变量，并加入启动命令：

```yaml
services:
  updater:
    image: ${COMPONENT_VAULT_WEB_IMAGE:-rafaelikaros/component_manager:web-${COMPONENT_VAULT_DOCKER_CHANNEL:-latest}}
    command: [updater]
    # 其余 environment、volumes、网络和重启设置保留原值。
```

保留项目名、原 `.env`、`/data` 来源、`/state` 任务卷及所有原挂载，然后在 Container Manager
拉取支持共享镜像的版本并重新部署。只换镜像而不设置命令，会启动网页而不是更新服务。
迁移时应等待已有更新任务结束。不要删除任务卷、重建空库存或使用旧 Web 镜像运行 updater。

日常网页更新只重建 API/Web，updater 容器继续运行，因此两者运行中的镜像版本可能暂时不同。
更新 updater 自身仍需在原项目中重新部署；不要在它执行任务时替换它。

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

Compose 项目可使用[渠道切换助手](docker-channels.md)更新。若统一模板的目标渠道仍采用旧镜像布局，使用该版本的部署模板手动回退。降级前核对数据格式兼容性；缺少网页更新能力的目标版本需通过宿主机维护。
