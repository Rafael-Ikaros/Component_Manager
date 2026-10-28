# Docker Hub 镜像发布与部署教程

普通部署者请先看 [Docker 快速部署与 API Token 查找](docker-quickstart.md)。拉取公开镜像不需要 Docker Hub 登录、Docker Hub Token 或 GitHub PAT；快速入门包含生成、查找和忘记 `API_TOKEN` 时的处理方法。

本文保留仓库维护者的发布配置、发布验证，以及部署者的升级、迁移和备份细节。

`v0.6.0` 首次发布时 Docker Hub 任务因凭据未配置而 skipped。`v0.7.0` 正式发布时，仓库已具备发布配置，[发布任务](https://github.com/Rafael-ban/Component_Manager/actions/runs/35722942557) 成功。2026-09-22 已直接核对 Docker Hub：API `0.7.0` 与 Web `web-0.7.0` 均为 active，并包含 `linux/amd64`、`linux/arm64`。前六节只供维护者首次配置、Token 轮换和手动补发；普通部署者不需要这些发布凭据。

已核对的版本 digest：

- API：`sha256:81985ac9cced8b12de193d67818e9fd5d814be4af46c0a42a32a87d174bf98dc`
- Web：`sha256:5e022b6aa65025fc360bdfda8b12a40ac4ef4d83a0a10e29ed69bd14219ef076`

以上 digest 是 `0.7.0` 的历史记录。**2026-10-28 已核对 0.7.8 正式镜像**：[发布任务](https://github.com/Rafael-Ikaros/Component_Manager/actions/runs/40710180699)成功，Docker Hub 的固定与浮动标签分别一致，均包含 `linux/amd64` 和 `linux/arm64`：

- API `0.7.8` / `latest`：`sha256:66727a8b28ea304af2027de96aa1f10148a5b1dcb7b497112c6c35eab3b7eabe`。
- Web `web-0.7.8` / `web-latest`：`sha256:3e007d091436cac675c0f6ca5cb27f98e55467963922a36000d855a0fe11488f`。

0.7.8 包含照片、BOM 预设同步及从订单更新已有资料；API、Web 需成对升级。保留原项目和整个 `/data`，先备份再拉取并重建，不能只重启旧容器。后续浮动标签仍可能更新，部署时请核对 [Docker Hub Tags](https://hub.docker.com/r/rafaelikaros/component_manager/tags)，不能仅凭 GitHub Release 存在推断镜像发布成功。

## 先分清账号、仓库和两种 Token

本项目使用以下固定身份：

| 用途 | 值 |
| --- | --- |
| GitHub 账号与仓库 | `Rafael-ban/Component_Manager` |
| Docker Hub namespace | `rafaelikaros` |
| Docker Hub repository | `component_manager` |
| 完整镜像名 | `rafaelikaros/component_manager` |

GitHub 用户名和 Docker Hub namespace 不要求相同。填写 `DOCKERHUB_IMAGE` 时使用 `rafaelikaros/component_manager`，末尾不要加 `/`，也不要附加版本或 `latest`；API 与 Web 共用这个仓库，通过不同 tag 区分。

还要区分两种完全不同的凭据：

- `DOCKERHUB_TOKEN` 是 Docker Hub Personal Access Token，只供 GitHub Actions 登录 Docker Hub 并推送镜像。
- `API_TOKEN` 是本项目服务端 API 的访问令牌，供 Android、Windows 和 Web 客户端登录服务端。新安装可通过 `/setup` 生成并保存，已有部署也可继续由 `.env` 提供。

在 `python:3.12-slim` 基础镜像信息中看到的 `GPG_KEY` 也不是本项目凭据。它是 Docker 官方 Python 镜像构建源码时使用的 40 位十六进制公钥指纹，不是私钥，也不能当作 `API_TOKEN`。可在 [Docker Library Python 的 3.12 slim-bookworm Dockerfile](https://raw.githubusercontent.com/docker-library/python/master/3.12/slim-bookworm/Dockerfile) 核对其来源。

不要把任何真实 Token 写进源码、Compose 文件、Issue、日志或聊天消息。

## 一、在 Docker Hub 创建公开仓库

1. 登录 [Docker Hub](https://hub.docker.com/)。
2. 打开 **My Hub → Repositories**。
3. 点击 **Create repository**。
4. **Namespace** 选择 `rafaelikaros`。
5. **Repository name** 填 `component_manager`。
6. **Visibility** 选择 **Public**。
7. 点击 **Create** 完成创建。

Public 仓库方便部署者直接执行 `docker pull`，无需额外登录。仓库创建后，页面地址通常为：

`https://hub.docker.com/r/rafaelikaros/component_manager`

此时仓库可能仍没有 tag；创建仓库本身不会上传镜像。Docker 官方说明见 [Create a repository](https://docs.docker.com/docker-hub/repos/create/)。

## 二、生成 Docker Hub Personal Access Token

1. 打开 [Docker 账号页面](https://app.docker.com/)，点击右上角头像。
2. 打开 **Account settings**。
3. 进入 **Personal access tokens**。
4. 点击 **Generate new token**。
5. 名称填 `component-manager-github-actions`。
6. 权限选择 **Read & Write**。
7. 不需要 **Delete** 权限。
8. 到期时间按自己的维护周期选择；到期后需要生成新 Token 并更新 GitHub Secret。
9. 点击生成后立即复制并保存到可信的密码管理器。

Token 只在创建时完整显示一次。不要把它发到聊天中，也不要为了让别人核对而截图。如果 Token 泄露，应立即在 Docker Hub 撤销并重新生成。

Docker 官方说明见 [Personal access tokens](https://docs.docker.com/security/access-tokens/personal-access-tokens/)。

## 三、核对 GitHub Actions Variables

打开 [GitHub 仓库 Actions 配置](https://github.com/Rafael-ban/Component_Manager/settings/secrets/actions)，对应路径为：

**Settings → Secrets and variables → Actions → Variables**

核对下面两个 Repository variable。它们上一轮已经设置，本轮通常只需检查，无需重复创建：

| Name | Value |
| --- | --- |
| `DOCKERHUB_USERNAME` | `rafaelikaros` |
| `DOCKERHUB_IMAGE` | `rafaelikaros/component_manager` |

检查 `DOCKERHUB_IMAGE` 没有尾部 `/`、没有协议、没有 tag。正确值是 `namespace/repository` 两段格式。

GitHub Variables 的官方说明见 [Store information in variables](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-variables)。

## 四、添加 GitHub Actions Secret

仍在 **Settings → Secrets and variables → Actions** 页面：

1. 切换到 **Secrets**。
2. 在 **Repository secrets** 区域点击 **New repository secret**。
3. **Name** 填 `DOCKERHUB_TOKEN`。
4. **Secret** 粘贴刚才生成的 Docker Hub Token。
5. 点击 **Add secret**。

这里使用 Repository secret 即可，不需要创建 GitHub Environment，也不需要配置 Environment secret。保存后 GitHub 不会再次显示 Secret 原文，这是正常行为。

GitHub Secrets 的官方说明见 [Using secrets in GitHub Actions](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)。

## 五、补发 v0.7.0 API 与 Web 镜像

配置完成后，打开 [Server Docker Image 工作流](https://github.com/Rafael-ban/Component_Manager/actions/workflows/server-image.yml)：

1. 左侧选择 **Server Docker Image**。
2. 点击 **Run workflow**。
3. **Use workflow from** 选择 `master`。
4. `source_ref` 填已经存在且经过验证的 `v0.7.0` tag。
5. `release_tag` 填 `v0.7.0`。
6. `push_image` 选择 `true` 或勾选发布选项。
7. 点击 **Run workflow**。

手动运行 workflow 的官方说明见 [Manually running a workflow](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow)。

`source_ref` 决定实际构建哪一份源码，`release_tag` 决定镜像版本 tag。发布时两者应指向同一版本。

这次补发会同时推送：

- `rafaelikaros/component_manager:0.7.0`
- `rafaelikaros/component_manager:latest`
- `rafaelikaros/component_manager:web-0.7.0`
- `rafaelikaros/component_manager:web-latest`

因此，用旧版本做补发时要特别留意：API `latest` 和 Web `web-latest` 都会被改为该旧版本。补发历史版本前先判断是否允许两个浮动标签一起回退。

普通 CI 或手动运行但 `push_image=false` 时，只构建并检查镜像，不会登录或推送。自动 Release 在用户名、镜像名或 Token 不完整时，会保留原生客户端与 Web 发布，并跳过 Docker Hub 镜像任务。但直接手动运行 **Server Docker Image** 且设置 `push_image=true` 时，缺少任一项配置都会明确失败，不会静默跳过。

## 六、确认发布确实成功

先在本次 Actions run 中确认：

1. **Validate publishing configuration** 成功。
2. **Verify API startup, authentication and mounted database** 成功。
3. **Verify web startup and bundled assets** 成功。
4. **Login to Docker Hub** 成功，而不是 skipped。
5. **Publish multi-platform API image** 和 **Publish multi-platform web image** 成功。

再到 Docker Hub 仓库的 **Tags** 页面确认：

- 存在 `0.7.0` 和 `latest`；
- 存在 `web-0.7.0` 和 `web-latest`；
- manifest 包含 `linux/amd64` 和 `linux/arm64`。

工作流会在 `linux/amd64` 上实际启动两个容器，验证 API `/health`、鉴权、挂载数据库，以及 Web 首页生成的 JS/CSS 资源。`linux/arm64` 会参与多平台构建与发布，但当前 workflow 不会在 ARM 机器上实际启动。

如本地已安装 Docker，可选执行：

```sh
docker buildx imagetools inspect rafaelikaros/component_manager:0.7.0
docker buildx imagetools inspect rafaelikaros/component_manager:web-0.7.0
docker pull rafaelikaros/component_manager:0.7.0
docker pull rafaelikaros/component_manager:web-0.7.0
```

发布者完成上述网页配置和 Actions 补发只需要浏览器，本地无需安装 Docker；这些本地命令只是额外核验手段。

## 七、部署端准备

部署机需要 Docker Engine 与 Docker Compose 2，命令应为 `docker compose`。Windows 用户可使用 Docker Desktop，并切换到 Linux containers，因为本项目发布的是 Linux 容器镜像。

先执行 `docker version` 和 `docker compose version`，确认能连接 Docker 服务且 Compose 可用。
当前仓库的 `docker-compose.hub.yml` 默认使用 API `latest` 与 Web `web-latest`；部署前在 Docker Hub Tags 核对它们当前对应的版本。请从当前仓库下载配套的 `docker-compose.hub.yml` 和 `.env.example`。`0.7.0` 没有首次配置页，不能用它验证下文的 `/setup` 流程。

把两个文件放到一个固定部署目录，例如 `component-manager-server/`。将 `.env.example` 复制为同目录的 `.env`。如果目录中已经有 `.env`，先备份和对比，不要直接覆盖现有 Token、CORS 或 MQTT 配置。

新安装将以下配置保持为空，以便通过首次配置页管理支持的项目：

```dotenv
API_TOKEN=
ADMIN_WEB_ORIGINS=
ADMIN_WEB_URL=
WEB_INVENTORY_ENABLED=
```

Compose 默认使用 API `latest` 与 Web `web-latest`。这两个浮动标签随正式镜像发布而更新；部署前核对其当前指向。需要固定版本时，在 `.env` 覆盖 `COMPONENT_VAULT_IMAGE` 和 `COMPONENT_VAULT_WEB_IMAGE`，并成对选择已发布的 API 与 Web 标签。

确认文件名为 `.env` 而非 `.env.txt`。API 启动后打开
`http://服务器IP:8787/setup`，生成或填写 Token、Web 页面 origin 和库存写入开关。
设置保存在 `/data/config.json`；首次保存后，后续查看或修改需要当前 Token。
已有部署可保留 `.env` 中的非空值，它们优先于配置文件且在网页中只读。

`ADMIN_WEB_ORIGINS` 填浏览器访问 Web 管理页时的来源，格式是 `scheme://host:port`，例如 `http://192.168.1.10:8081`。它不是服务端 API URL，不要填 `http://192.168.1.20:8787`，除非浏览器中的 Web 页面本身确实由该 origin 提供。多个 Web 来源用逗号分隔。API 自带的 `/setup` 页面与 API 同源，不依赖这项 CORS 设置。
新管理 Web 的登录状态仅在 localStorage 保留 API URL；账户密钥通过 `POST /auth/session` 换取 API 发放的 12 小时 `HttpOnly`、`SameSite=Lax` Cookie，Web 请求使用凭据模式并携带允许来源。Web 与 API 应同站点（相同协议和主机，不同端口可用），但不同端口仍是不同 origin，必须将实际 Web `scheme://host:port` 精确列入 `ADMIN_WEB_ORIGINS`，不能使用通配符；API 开启了带凭据 CORS。原生 Android/Windows 继续使用 Bearer 密钥。服务器通过 `/auth/session` 认证浏览器会话；“检查更新”与这条登录流程无自动部署关联。

`0.7.4` 的 API 支持管理台实际地址 `ADMIN_WEB_URL`；`0.7.4` 的 Web 管理台支持直接编辑服务端配置。使用前须确认所用的 API 与 Web 镜像都已包含 `0.7.4`：固定标签需核对 `0.7.4` 和 `web-0.7.4` 均已发布，默认的 `latest` 和 `web-latest` 则需核对实际指向；开发预发布不发布 Docker 镜像。已有 `0.7.1` 部署可继续使用已认证的 `/setup` 修改配置。可在 `/setup` 填写管理台完整地址（支持反向代理子路径），首次保存后先复制 API Token，再点“进入管理台”；非空 `ADMIN_WEB_URL` 环境变量优先并锁定网页输入。

## 八、拉取并启动服务端

在 Compose 文件与 `.env` 所在目录执行：

```sh
docker compose -f docker-compose.hub.yml pull
docker compose -f docker-compose.hub.yml up -d
docker compose -f docker-compose.hub.yml ps
docker compose -f docker-compose.hub.yml logs --tail=100 api
```

确认容器处于运行状态后访问：

```sh
curl http://服务器IP:8787/health
```

应返回包含 `"status":"ok"` 和 `"inventory_protocol":1` 的 JSON。`/health` 无需鉴权，所以健康检查成功不能代替 Token 验证。

随后在 Android 或 Windows 客户端中填写：

- 服务端地址：`http://服务器IP:8787`
- API Token：与 `/setup` 中当前生效值完全一致

完成一次带 Token 的连接或同步测试，才能确认鉴权和网络路径都可用。
手机中的地址不能用 `localhost`，应使用手机能访问的服务器 IP 或域名；服务器防火墙需允许对应的 8787 端口。

端口 `8787` 只提供 FastAPI。确认 Web tag 已发布后，可启动可选 profile：

```sh
docker compose -f docker-compose.hub.yml --profile web pull
docker compose -f docker-compose.hub.yml --profile web up -d
```

若在群晖 Container Manager 的项目图形界面操作，可在 Compose 编辑器中删除 `admin-web` 下的 `profiles:` 和 `- web` 两行，保留 `8081:80` 端口映射，再重新部署项目。此操作只让独立的 Web 容器随项目启动；API、Web 仍使用各自镜像，原有 `/data` 映射不变。仅重启 API 容器不会创建 Web 服务。

Web 页面位于 `http://服务器IP:8081/`。若 Web 镜像尚未发布，仍可从 GitHub
Release 下载 `component-vault-admin-web.zip` 并用静态服务器单独部署。无论采用
哪种方式，都要把实际页面 origin 加入 `ADMIN_WEB_ORIGINS`。

Web 库存写入默认关闭。可在已认证的 `/setup` 配置页启用；若该值由 `.env`
接管，则在 `.env` 中设置 `WEB_INVENTORY_ENABLED=true`。环境变量修改后重新执行
`docker compose -f docker-compose.hub.yml --profile web up -d`，让 Compose 按新环境变量重建 API 容器；
仅执行 `docker compose restart` 不会应用 `.env` 的修改。
生效后，已通过共享 Token 登录的浏览器才能新增库位/元件、编辑资料和记录出入库。
关闭开关会停止后续 Web 写入，不会撤销已经提交的库存变动。

## 九、更新、迁移与备份

日常更新继续使用 `.env.example` 中的 `latest` 与 `web-latest`。如果已有 `.env` 固定了旧版本，需要把两个镜像变量一起改为这组浮动标签；更新前核对 Docker Hub Tags 当前指向的版本。需要固定到本次已发布的 `0.7.6` 时，在 `.env` 中设置：

```dotenv
COMPONENT_VAULT_IMAGE=rafaelikaros/component_manager:0.7.6
COMPONENT_VAULT_WEB_IMAGE=rafaelikaros/component_manager:web-0.7.6
```

如果已启用 Web，在原部署目录执行以下命令以同时更新两个服务；只部署 API 时去掉 `--profile web`：

```sh
docker compose -f docker-compose.hub.yml --profile web pull
docker compose -f docker-compose.hub.yml --profile web up -d
docker compose -f docker-compose.hub.yml ps
```

`latest` 与 `web-latest` 便于日常更新，但浮动标签本身不会在容器运行期间自动拉取。未启用下文可选的 Watchtower 时，每次手动更新都要先执行上述 `pull`，再执行 `up -d`；仅执行 `restart` 不会换用新镜像。固定版本 tag 便于审计和回退。保持原部署目录和 Compose project name，才能自然复用原 named volume。
不要执行 `docker compose down -v`，`-v` 会删除库存数据库所在 volume。

从源码版 Compose 迁移到 Hub Compose 时，也应保留原目录和 project name。如果必须更换目录，在所有命令中显式使用原 project name，例如：

```sh
docker compose -p 原项目名 -f docker-compose.hub.yml up -d
```

旧部署若已有静态 Web 服务，启用 Hub Compose 的 `web` profile 前先确认端口与 origin，避免与旧服务同时占用 8081。API-only 部署无需启用 profile。

数据库位于容器 `/data/component_vault.db`，保存在 named volume 中。备份 SQLite 时需要一致 snapshot：优先使用 SQLite backup 机制；若只能复制文件，应先暂停写入或停止容器，再完整复制 `/data` 中的数据库相关文件，避免只复制主 `.db` 而遗漏仍有数据的 WAL 文件。
回退应用版本前还需确认数据库结构兼容，不能假设旧程序能够打开已经被新版迁移的数据库。

## 可选 Watchtower 自动更新

默认 `docker-compose.hub.yml` **不运行自动更新器**。Web “检查更新”只是手动向 GitHub 查询正式版信息，`POST /admin-api/about/check-update` 不拉取镜像、不重建容器。需要自动跟随 Docker Hub 的 API `latest` 和 Web `web-latest` 时，可显式启用仓库根目录的 [`docker-compose.watchtower.yml`](../docker-compose.watchtower.yml)。固定版本标签不会因出现新版本号而自动切换；启用前确认 `.env` 中 `COMPONENT_VAULT_IMAGE` 与 `COMPONENT_VAULT_WEB_IMAGE` 是配套的浮动标签，并在 Docker Hub 核对它们当前实际版本。

[原项目 `containrrr/watchtower`](https://github.com/containrrr/watchtower) 已于 2025-12-17 归档。示例明确选用仍维护的社区 fork [`nicholas-fedor/watchtower`](https://github.com/nicholas-fedor/watchtower)，对应镜像 `nickfedor/watchtower:latest`。该 fork 的说明称默认协商 Docker API；[Docker 官方兼容表](https://docs.docker.com/reference/api/engine/)显示例如 Engine 29.0–29.2 的最低 API 为 1.44，故老 Watchtower 镜像可能连接失败。先用 `docker version` 和 Watchtower 日志核对本机兼容性，不在示例中无理由强制 `DOCKER_API_VERSION`。fork 的[容器选择文档](https://watchtower.nickfedor.com/v1.21.0/getting-started/container-selection/)说明：`WATCHTOWER_LABEL_ENABLE=true` 时，只管理带 `com.centurylinklabs.watchtower.enable=true` 的容器。

启用前先取得**一致备份**：停止写入或用 SQLite backup，并保存整个 `/data`（管理员数据库、`users/`、`config.json` 及其他所需文件）。若使用 named volume，保留原项目名；若群晖已把 `/data` 映射到宿主目录，继续用原映射，不要切换成空目录。自动更新没有内置数据库备份步骤，应另行安排定期备份，且能够在更新前拿到可恢复的备份；做不到时继续使用上一节的手动 `pull` + `up -d`。

将覆盖文件和现有 `docker-compose.hub.yml`、`.env` 放在**同一个原部署目录**，在该目录执行：

```sh
docker compose -f docker-compose.hub.yml -f docker-compose.watchtower.yml --profile web config --quiet
docker compose -f docker-compose.hub.yml -f docker-compose.watchtower.yml --profile web up -d
docker compose -f docker-compose.hub.yml -f docker-compose.watchtower.yml --profile web ps
docker compose -f docker-compose.hub.yml -f docker-compose.watchtower.yml --profile web logs --tail=100 watchtower
```

只运行 API 时省略 `--profile web`；若原部署显式使用 `-p 项目名`，上述**每条**命令也要带同一 `-p`。覆盖文件只给现有 `api`、`admin-web` 服务加启用标签，再启动独立的 Watchtower 服务；Docker socket 仅挂在 Watchtower 容器，API 和 Web 均不挂。Watchtower 默认约每 24 小时检查一次，拉取新镜像后停止并**重建**受管理容器，沿用原配置与数据挂载；这不是 `docker compose restart`。[fork 的运行说明](https://watchtower.nickfedor.com/v1.12.3/quickstart/)与 [Docker Compose `up` 文档](https://docs.docker.com/reference/cli/docker/compose/up/)分别说明更新器和手动重建行为。Watchtower 的 Docker socket 具备管理宿主 Docker 容器的能力，应只在受信任的 NAS/主机上启用。覆盖文件不启用 Watchtower 自更新；需要维护它时在原项目中手动拉取并重建该服务。

群晖 Container Manager 若只能导入一个项目 Compose 文件，应在**原项目**的 Compose 编辑器中把覆盖文件的 `api`、`admin-web` 标签和 `watchtower` 服务合并进去，保存并**重新部署项目**使标签实际进入容器；不要只在容器环境变量界面填写标签，也不要新建第二个项目。沿用现有 `/data` 映射、端口、`.env` 和项目名。若原 GUI 部署已去掉 Web 的 `profiles` 以启动 Web，保持该设置。关闭自动更新时移除 Watchtower 服务和目标标签，再在原项目重新部署；数据卷无需删除。

API 与 Web 是两次独立的容器更新，**不能保证同时切换**。每次更新后核查 `ps` 和 `logs api`，读取同一外部地址的 `/health` 中 `status`、`version`、`revision`，用 `docker compose -f docker-compose.hub.yml -f docker-compose.watchtower.yml --profile web images api admin-web` 核对两个容器当前镜像与 Docker Hub 配套标签（仅部署 API 时只查 `api`），再打开 Web 设置页检查登录与库存读取；若两者版本不配套或有迁移/认证故障，先停用 Watchtower，按上节从一致备份及已确认兼容的一对版本恢复。不要将 Watchtower 日志中“发现新镜像”当作应用升级已验收。本示例仅经配置与文档核对，尚未在真实 NAS 上验证自动更新。

## 十、常见问题

| 现象 | 常见原因 | 处理方式 |
| --- | --- | --- |
| Actions 登录时报 `unauthorized` / authentication failed | Token 错误、过期或已撤销 | 重新生成 Read & Write Token，更新 `DOCKERHUB_TOKEN` |
| 推送时报 `denied` | namespace/repository 不匹配，或 Token 没有 Write 权限 | 核对 `rafaelikaros/component_manager` 与 Token 权限 |
| 拉取时报 `manifest unknown` | tag 尚未发布或拼写错误 | 在 Docker Hub Tags 核对 Compose 当前指定的 API 与 Web tag 是否真实存在 |
| `v0.7.0` 拉不到但 `0.7.0` 存在 | API tag 去掉 Git tag 的 `v`；Web 另加 `web-` | 使用 `:0.7.0` 或 `:web-0.7.0` |
| GitHub Actions 全绿但 Hub 没有镜像 | 跑的是普通 CI、`push_image=false`，或自动 Release 跳过了镜像任务 | 检查 Login/Publish 是否 skipped，按第五节补发 |
| `pull` 超时 | Docker Hub 网络、DNS 或代理问题 | 先用 `docker pull` 单独诊断网络，再重试 Compose |
| Web 请求 API 出现 CORS 错误 | `ADMIN_WEB_ORIGINS` 填成 API 地址或遗漏实际 Web origin | 填浏览器地址栏中 Web 页面的 scheme、host 和 port |
| 换目录后库存为空 | Compose project name 变化，创建了新的空 volume | 回到原目录，或用 `-p 原项目名` 指向原 project |
| `/health` 正常但客户端 401 | 客户端 Token 与 `/setup` 当前生效的 `API_TOKEN` 不一致 | 更新客户端 Token 后重试；无需给 `/health` 加 Token |
| 客户端提示服务端过老或无法确认账户身份 | 仍运行旧容器、API 地址指向 Web 端口、反向代理遗漏 `/auth/me`，或响应并非 JSON | 按 [账户接口排障](docker-quickstart.md#7-客户端提示服务端版本过老或无法确认账户身份) 核对实际容器和地址；0.7.6 镜像可从 `/health` 的 `version` / `revision` 查看构建身份 |

排障时先看 `docker compose ... ps` 和 `logs`，再区分是镜像发布、镜像拉取、容器启动、鉴权还是浏览器 CORS 问题，避免用删除 volume 作为通用重试手段。
