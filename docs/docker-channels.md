# Docker 正式版与开发版切换

正式版使用 `latest / web-latest`，开发版使用 `dev / web-dev`。两组镜像由独立的
**Docker Images** 工作流发布；开发版不会覆盖正式版标签。普通部署者无需 Docker Hub Token。

从 `0.7.9-dev.8` 起，Web 和 updater 使用同一 `web-*` 管理镜像，仍为两个容器。
网页更新只替换 API/Web；切换渠道不会重建正在执行任务的 updater。
旧独立 updater 的迁移和后续维护见[网页更新教程](server-web-update.md#从独立-updater-镜像迁移)。

服务端的渠道选择用于版本检查和更新目标，只有确认更新后才替换容器。
群晖希望日常不使用 SSH，可一次配置[网页更新服务](server-web-update.md)。不启用该服务时，
仍可用下方部署助手切换，不必手动记忆或编辑镜像标签。

**已有正式版可以直接升级到 dev，沿用原账户和库存。** 在原项目中成对切换 API/Web，
保留 `/data` 后拉取并重新部署；应用设置随后在 Web 管理。升级不会自动创建 updater，
如希望以后从网页更新，可将首次升级和启用 updater 合并为一次原项目编辑。
两类配置的区别见[正式版升级到 dev](server-web-update.md#已有正式版先升级到-dev)。

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

   省略 `--channel` 会提供正式版/开发版终端菜单。切回正式版使用同一助手：

   ```sh
   sh tools/deployment/switch-channel.sh --project-name component-warehouse --channel stable
   ```

助手先拉取镜像，API/Web 的版本及构建提交配套后才备份 `.env`、修改配置和重建容器。
拉取失败或两镜像尚未配套时，运行容器和 `.env` 不变，待发布完成后重试。
重建中断后可重复执行同一命令；不会执行 `down -v`。

为兼容旧 Hub Compose，助手自动写入渠道键及 API/Web 完整镜像键，保留其它 `.env` 内容。
后续继续用助手切换即可。旧配置保存在同目录 `.env.before-channel-*`，可能含账户密钥，
应与原 `.env` 一样保管。助手仅支持官方镜像、有 Compose 标签且原文件齐全的项目；
单独 `docker run`、自定义镜像或其它 env-file 部署需按原方式处理。

## 群晖不使用 SSH

在 Container Manager 打开原项目，备份数据并记录原项目名、端口和目录。
保留原 Compose 文件。若它使用渠道变量，在原项目目录创建或修改 `.env`，
保留既有应用配置，将渠道键设为 `dev` 或 `latest`：

```dotenv
COMPONENT_VAULT_DOCKER_CHANNEL=dev
```

已有的 `COMPONENT_VAULT_IMAGE` 和 `COMPONENT_VAULT_WEB_IMAGE` 若非空，应清空，以便按渠道取镜像。
下载配套镜像并重新部署**原项目**，沿用原挂载。若原 Compose 写死了镜像名，在原文件中成对修改 API 和 Web 的 `image`。
不要新建空项目或删除数据卷。只使用单独容器而非项目时，仍需在 GUI 中为 API、Web 选择配套镜像。

## Watchtower 与版本确认

沿用 [Watchtower 配置](docker-quickstart.md#watchtower-updates)。它跟随当前容器的镜像渠道更新，
不会自动跨渠道；切换助手保留原 override 中的 Watchtower labels。

在服务端“设置 → 关于服务端”查看实际版本，`/health` 也提供 `version / revision`。
GitHub Release 出现时镜像可能尚在构建，应以独立 Docker Images 工作流成功和 Hub 标签可拉取为准。
API/Web 是配套镜像，更新后核对两个容器及登录、库存读取，不把 Watchtower 的“发现新镜像”当作验收。
