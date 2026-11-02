# 在网页中更新服务端

适用于官方 Docker Compose / 群晖 Container Manager 项目。一次在原项目中添加可选
`updater` 服务，之后管理员就可在“设置 → 关于服务端”选择渠道、确认更新并查看结果，
无需每次打开 SSH。未启用时仍可正常管理库存、同步和检查版本。

## 当前支持范围

- API、Web 和 updater 需使用包含此功能的配套版本，首次随 0.7.9-dev.6 提供。
- 当前正式版 0.7.8 不包含网页更新入口。在线更新会在替换前检查目标 API/Web 的
  `io.componentvault.server-update=1` 能力标识；不支持的旧版本会被拒绝，现有容器不变。
  待包含此能力的正式版通过验证并发布后，才能在受支持的正式版与 dev 之间在线切换。
- 只更新本项目 API 和已部署的 Web；不管理其它应用，不自动更新 updater 自身，不自动回滚数据库。
- updater 使用 Docker CLI 28.x / 配套 Compose 并保留 API 自动协商，照顾尚未更新 Engine 的 NAS。
  Docker 29 移除了针对较旧 Engine API 的部分客户端兼容处理，见[官方说明](https://docs.docker.com/engine/release-notes/29/)。这不是所有 DSM/Engine 组合都已经实测的承诺。
- 群晖实际按钮以 DSM / Container Manager 版本为准。以下配置是 Compose 项目操作，
  不是单独创建三个互不关联的容器；未在用户 NAS 上验证的步骤应先在测试项目试用。

## 群晖一次性配置

1. 备份整个 API `/data`，记录原项目名、端口、数据来源和原 Compose 文件。保留各账户数据库、
   配置、照片及日志。不要新建同名仓库的数据卷，也不要删除旧卷。
2. 从对应版本源码包取得 `docker-compose.hub.yml` 和 `docker-compose.web-update.yml`。
   在 File Station 打开**原项目目录**，保留原 `.env`；没有 `.env` 时创建它。
   先通过 Container Manager 把 API/Web 更新到支持此功能的 dev 版本。
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
4. 在 Container Manager 的**原项目**配置中加入 `docker-compose.web-update.yml` 的内容。
   若界面只接受单文件，合并其中的 `services.api.environment/labels`、`services.admin-web.labels`、
   `services.updater` 及 `volumes.component_vault_updater_state`，不要替换原端口或数据配置。
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
8. 在 GUI 中保存并重新部署原项目一次，登录 Web 管理台。管理员在关于区看到可用的
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
