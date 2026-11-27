# Docker 正式版与开发版切换

正式版 0.7.9 起使用统一镜像，跟随正式渠道用 `latest`，开发渠道用 `dev`。`web-latest` 和 `web-dev` 继续供旧配套镜像项目启动 Web。固定到 0.7.9 时选择 `0.7.9` 标签。开发版不会推进正式渠道，部署公开镜像无需 Docker Hub Token。

新建开发版项目从同一 `dev` 或固定标签启动 API 和 Web，Web 显式指定 `command: [web]`。旧模板继续使用配套 `web-*` 兼容标签。新模板的部署和迁移见[快速部署](docker-quickstart.md#使用统一镜像)；目标版本仍采用旧镜像布局时，回退使用该版本的部署模板。

普通部署可在群晖界面[手动创建和更新两个容器](docker-manual.md)，也可使用 Compose；均不需要向应用挂载 Docker socket。

启用可选网页更新的 Compose 项目还需独立部署 updater。旧布局从 `0.7.9-dev.8` 起由 Web 和 updater 共用 `web-*` 管理镜像；统一布局从同一镜像启动三个角色。
网页更新只替换 API/Web；切换渠道不会重建正在执行任务的 updater。
旧独立 updater 的迁移和后续维护见[网页更新教程](server-web-update.md#从独立-updater-镜像迁移)。

服务端的渠道选择用于版本检查，以及已启用网页更新时的目标渠道；切换选项本身不安装更新。
直接创建的容器按[手动步骤](docker-manual.md#5-手动更新或切换渠道)成对选用镜像。Compose 用户也可使用下方部署助手。
希望网页代替你重建容器时，可以另行配置[网页更新服务](server-web-update.md)，该选项需要授予 Docker 管理权限。

**已有正式版可以直接升级到 dev，沿用原账户和库存。** 在原项目中成对切换 API/Web，
保留 `/data` 后拉取并重新部署；应用设置随后在 Web 管理。升级不会自动创建 updater，
如希望以后从网页更新，可将首次升级和启用 updater 合并为一次原项目编辑。
两类配置的区别见[从 0.7.8 升级](server-web-update.md#从-078-升级)。

## 使用部署切换助手

适用于官方镜像的 Docker Compose 或群晖 Container Manager **项目**部署。
助手沿用已有容器记录的项目名、工作目录和全部 Compose 文件（包括 Watchtower override），
保留端口、数据挂载及其它服务，只重建 API 与已经部署的 Web。

1. 备份整个 `/data`，包括各账户数据库、`config.json`、照片和日志。`.env` 备份不能代替数据备份；
   切回正式渠道不会撤销开发版的数据修改，也不保证旧版本可读取未来的数据库格式。
2. 从对应版本源码包取得 `tools/deployment/switch-channel.sh`，放到部署主机。
   原 Compose 文件及 `.env` 仍须位于原位置。普通正式版原先可省略 `.env`，首次使用切换助手前，
   在原 Compose 项目目录创建空的 `.env` 即可。群晖可通过 SSH 使用有 Docker 操作权限的账户执行。
3. 运行 `docker compose ls`，记下原项目名。以下以 `component-warehouse` 为例，先预览：

   ```sh
   sh tools/deployment/switch-channel.sh --project-name component-warehouse --channel dev --dry-run
   ```

4. 核对原项目、Compose 文件、渠道及服务，再实际切换：

   ```sh
   sh tools/deployment/switch-channel.sh --project-name component-warehouse --channel dev
   ```

   省略 `--channel` 会提供正式版和开发版终端菜单。可用同一助手切回正式渠道：

   ```sh
   sh tools/deployment/switch-channel.sh --project-name component-warehouse --channel stable
   ```

助手根据原 Compose 项目的布局决定镜像标签。旧模板使用配套 API 和 `web-*` 标签，统一模板共用标签并要求目标镜像支持统一角色。助手先拉取并检查目标镜像，再备份 `.env`、修改配置和重建容器。
镜像拉取或校验失败时，运行容器和 `.env` 保持原状。若渠道尚在发布，待发布完成后重试。
重建中断后可重复执行同一命令；不会执行 `down -v`。

为兼容旧 Hub Compose，助手自动写入渠道键及 API/Web 完整镜像键，保留其它 `.env` 内容。
后续继续用助手切换即可。旧配置保存在同目录 `.env.before-channel-*`，可能含账户密钥，
应与原 `.env` 一样保管。助手仅支持官方镜像、有 Compose 标签且原文件齐全的项目；
单独 `docker run`、自定义镜像或其它 env-file 部署需按原方式处理。

## 群晖不使用 SSH

在 Container Manager 打开原项目，备份数据并记录原项目名、端口和目录。
在原项目目录编辑 `.env`，保留既有应用配置。如果原模板支持目标渠道，把渠道键设为 `dev` 或 `latest`：

```dotenv
COMPONENT_VAULT_DOCKER_CHANNEL=dev
```

已有的 `COMPONENT_VAULT_IMAGE` 和 `COMPONENT_VAULT_WEB_IMAGE` 若非空，清除其中固定旧版本的配置，以便按渠道取镜像。
保留原挂载。统一镜像模板可直接在 `dev` 和 `latest` 之间切换。旧配套镜像模板的 Web 仍用对应的 `web-*` 兼容标签。需要回退到固定的 0.7.8 时，使用[历史模板](https://github.com/Rafael-Ikaros/Component_Manager/blob/v0.7.8/docker-compose.hub.yml)并固定 `0.7.8 / web-0.7.8`，按需恢复当时的数据备份。如果去掉 Updater，同时从 API 配置移除 `UPDATER_URL`。完成编辑后按[群晖项目更新步骤](docker-quickstart.md#在群晖更新现有项目)拉取并重建。使用独立容器时，按[手动更新指南](docker-manual.md#5-手动更新或切换渠道)替换容器。

## Watchtower 与版本确认

沿用 [Watchtower 配置](docker-quickstart.md#watchtower-updates)。它跟随当前容器的镜像渠道更新，
不会自动跨渠道；切换助手保留原 override 中的 Watchtower labels。

在 Web「设置 → 关于服务端」分别查看 Web 版本、API 版本和连接地址。API 的 `/health` 返回其实际构建的 `version` 和 `revision`。页面显示 `dw31` 时，API 的发布版本仍为 `v0.7.9-dev.31`。
GitHub Release 出现时镜像可能尚在构建，应以独立 Docker Images 工作流成功和 Hub 标签可拉取为准。
更新后核对 API 与 Web 的运行版本、登录和库存读取。[版本核对与排障](docker-manual.md#核对-web-和-api-版本)
