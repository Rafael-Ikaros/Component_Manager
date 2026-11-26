# Docker Compose 部署

这里使用已发布的镜像部署 API 和可选管理台。只想在群晖界面点选镜像和创建容器时，使用[群晖手动部署](docker-manual.md)。普通部署无需 Docker socket，首次管理员密钥从网页设置。

## 1. 准备并启动

先选择渠道，将相应模板下载为 `docker-compose.hub.yml`，放在固定的部署目录里。

| 渠道 | 下载模板 | 新建部署的镜像设置 |
| --- | --- | --- |
| 正式版 0.7.8 | [配套镜像模板](https://github.com/Rafael-Ikaros/Component_Manager/blob/v0.7.9-dev.29/docker-compose.hub.yml) | 默认使用 `latest` 与 `web-latest`，无需 `.env` |
| 开发版 dev.30 起 | [统一镜像模板](../docker-compose.hub.yml) | 同目录的 `.env` 填一行 `COMPONENT_VAULT_DOCKER_CHANNEL=dev` |

### 使用统一镜像

开发版 API 和 Web 默认共用 `rafaelikaros/component_manager:dev`。模板为 Web 设置 `command: [web]`，API 使用镜像默认命令。固定到 dev.31 时，在 `.env` 中填写 `COMPONENT_VAULT_IMAGE=rafaelikaros/component_manager:0.7.9-dev.31`。固定镜像地址优先于渠道变量。旧部署迁移时，清除残留的 `COMPONENT_VAULT_WEB_IMAGE` 覆盖以复用同一个镜像，保留原项目名、网络、端口、自定义环境设置和 `/data` 来源。

### 启动服务

在部署目录执行：

```sh
docker compose -f docker-compose.hub.yml --profile web pull
docker compose -f docker-compose.hub.yml --profile web up -d
docker compose -f docker-compose.hub.yml --profile web ps
```

只想使用客户端同步时，省略 `--profile web`，只启用 API。群晖通过界面启动 Compose 项目时，见[第 2 节](#2-在群晖部署-compose-项目)。

打开 `http://服务器地址:8787/health`，确认 `"status":"ok"`，再到 `http://服务器地址:8787/setup` 生成或填写管理员密钥。如果部署了 Web，在同一页填写管理台地址 `http://服务器地址:8081/` 和允许来源 `http://服务器地址:8081`。保存后，打开管理台，用 `http://服务器地址:8787` 和刚设置的密钥登录。Android 同样使用 API 地址及对应的账户密钥。

新安装的设置保存在 `/data/config.json`。已有部署继续使用原密钥和数据卷。普通用户由管理员创建并分配独立密钥。[账户管理](accounts.md)

### 更新 API 和 Web

先按[第 6 节](#6-升级前备份与回退)备份。继续使用原项目名、原模板和数据挂载。若模板固定了版本，先将其中的固定标签改成目标版本。更新 API 和已部署的 Web 时，在原项目目录执行：

```sh
docker compose -f docker-compose.hub.yml --profile web pull api admin-web
docker compose -f docker-compose.hub.yml --profile web up -d api admin-web
docker compose -f docker-compose.hub.yml --profile web ps
```

只部署 API 时，去掉 `--profile web` 和 `admin-web`。群晖用户可按[在群晖更新现有项目](#在群晖更新现有项目)操作。已配置 [Updater](server-web-update.md) 的项目也可通过网页更新 API 和 Web。

更新后直连 API 的 `/health` 检查 `version` 和 `revision`，再登录 Web，在「关于服务端」核对两个版本与实际连接的 API 地址。dev.31 的页面显示 `dw31`。如果读到旧版本，按[版本排查步骤](docker-manual.md#核对-web-和-api-版本)核对容器和连接目标。需要切换渠道时见[渠道切换](docker-channels.md)。

<a id="watchtower-updates"></a>

### 可选的 Watchtower 自动更新

Watchtower 定期跟随容器当前使用的标签。只在已备份并愿意授予 Docker 守护进程访问权限时使用，同一个 API/Web 不同时交给 Watchtower 和网页 Updater。

把 [Watchtower 覆盖模板](../docker-compose.watchtower.yml) 放到原项目目录，核对 API 和 Web 使用同一渠道。

```sh
docker compose -f docker-compose.hub.yml -f docker-compose.watchtower.yml --profile web config --quiet
docker compose -f docker-compose.hub.yml -f docker-compose.watchtower.yml --profile web up -d
docker compose -f docker-compose.hub.yml -f docker-compose.watchtower.yml --profile web logs --tail=100 watchtower
```

只部署 API 时省略 `--profile web`。使用过 `-p 项目名` 的部署继续使用原项目名。群晖界面使用单文件时，将覆盖模板中的标签和服务合并到原 YAML。模板只让 Watchtower 更新带 `com.centurylinklabs.watchtower.enable=true` 的容器，默认每天检查一次。实际更新后核对 API 版本和 Web 登录。停用时从原项目移除服务和相应标签，保留数据卷。

## 2. 在群晖部署 Compose 项目

1. 在 File Station 创建项目目录，例如 `/volume1/docker/component-manager`。放入第 1 节选定的模板，使用开发版时把 `.env` 放在同一目录。
2. 在 Container Manager 的「项目」创建项目，选择上一步的路径和模板。若要同时启用 Web，在 YAML 编辑器中删除 `admin-web` 下的 `profiles:` 和紧随其后的 `- web`，保留 `8081:80` 和模板的启动命令。
3. 默认使用命名卷 `component_vault_data`。**新安装**需要在 File Station 查看数据时，可先建好 `data` 子目录，把 API 的挂载改成以下内容。已有部署继续使用原挂载，切换到空路径会打开新库存。

   ```yaml
   services:
     api:
       volumes:
         - /volume1/docker/component-manager/data:/data
   ```

4. 部署后在「容器」确认 API 已运行。若启用了 Web，再检查 Web 容器。按第 1 节完成 `/setup` 和 Web 登录。

### 在群晖更新现有项目

1. 停止 API，按[备份步骤](#6-升级前备份与回退)保存原项目的数据。固定版本需要先修改项目目录里的镜像配置，再在「映像」下载目标标签。同一浮动标签有可用更新时，可通过「操作 → 更新」拉取。[群晖映像管理](https://kb.synology.com/en-us/DSM/help/ContainerManager/docker_image?version=7)
2. 进入原项目，保留原项目名、路径、端口和数据挂载。修改过 YAML 时，从「详情 → YAML 配置」保存并重新部署。只更新同名镜像时，在项目的「操作」中选「构建」，完成后启动项目。单点「重新启动」只会重启已有容器。[群晖项目管理](https://kb.synology.com/en-global/DSM/help/ContainerManager/docker_project)
3. 按[版本核对步骤](docker-manual.md#核对-web-和-api-版本)确认 API 和 Web 已运行目标镜像，再检查原账户与库存。

| `/data` 中的文件 | 用途 |
| --- | --- |
| `component_vault.db` | 管理员库存、账户登记信息和 MQTT 配置 |
| `users/<账户ID>.db` | 普通账户库存 |
| `config.json` | 管理员密钥、Web 地址和允许来源等 |
| 数据库文件旁的 `.photos/` 目录 | 对应账户的已同步原图 |
| `logs/server.log` | 滚动运行日志，也可在 DSM 的容器日志页查看输出 |

## 3. 环境变量和网页配置的优先级

新安装优先通过 `/setup` 设置管理员密钥、管理台地址、允许来源和 Web 写入。已有部署若设置非空的 `API_TOKEN`、`ADMIN_WEB_URL`、`ADMIN_WEB_ORIGINS` 或 `WEB_INVENTORY_ENABLED`，对应的网页字段受环境变量控制。重建容器前应确认需要保留的设置已经保存，避免密钥或允许来源失效。

Web 与 API 使用同一主机名和协议，端口可以不同。允许来源填写实际打开 Web 页面的 `协议://主机:端口`，例如 `http://nas:8081`。管理台以 `POST /auth/session` 换取 API 会话 Cookie，浏览器只将 API 地址保存在 localStorage。反向代理部署请保留 `/auth/*`、`/admin-api/*` 和 `/health` 等 API 路径。

MQTT 配置保存在 SQLite，优先于 MQTT 环境默认值，按网页提示重启 API 后生效。嘉立创公开目录查询无需 OpenAPI 凭据。高级环境变量须明确传入 API 容器，单独写在宿主 `.env` 中不会自动传递。[高级配置](runbook.md#environment-variables)

自 dev.31 起，构建版本随镜像发布。`.env` 中的渠道键和镜像地址决定部署目标，`/health` 返回当前运行的 API 版本。

## 4. API Token 在哪里

- 首次配置页保存的管理员密钥在 `/data/config.json` 的 `API_TOKEN` 字段。已设非空环境变量的部署以环境变量为准。
- 普通账户使用自身的密钥。管理员可在 Web 的「账户管理」创建、停用或重置账户，密钥在创建和重置时展示。[账户管理](accounts.md)
- 直接从已运行的 Compose API 容器核对当前生效的管理员密钥，可在私有终端执行以下命令。请勿将输出放入故障反馈。

```sh
docker compose -f docker-compose.hub.yml exec api python -c 'from app.config import get_settings; print(get_settings().api_token)'
```

源码 Compose 省略 `-f docker-compose.hub.yml`。群晖已将 `/data` 映射到宿主目录时，可以在 File Station 查看配置文件。

## 5. 401 与忘记 Token

先确认客户端访问的是 API 的地址，并使用当前账户密钥。管理员忘记密钥时，可通过有权限的容器或宿主数据目录核对生效来源。环境变量管理的密钥需更新原部署配置后重建 API。重置会使旧密钥失效，库存仍保存在原数据目录。

极早期没有配置文件的旧库存可能仍使用旧默认密钥 `change-me`。这是升级兼容，适用于确实沿用旧默认值的部署。全新部署需要首次设置。

## 6. 升级前备份与回退

停 API 后备份完整 `/data`，包括各账户数据库和 SQLite 辅助文件、`config.json`、照片目录。另存 Compose、`.env`、原项目名和挂载来源。使用命名卷的部署继续使用原卷。将原卷改为空宿主目录不会自动迁移数据。

恢复时使用能读取备份格式的版本，检查账户、照片和库存。切换镜像本身不会回退数据库。客户端仍未同步的库存变更和 BOM 预设需要单独备份。

## 7. 客户端提示“服务端版本过老”或无法确认账户身份

1. 核对客户端填的是 API 地址，例如 `http://nas:8787`，不带 `/setup`。先访问该地址的 `/health`，用 `version` 和 `revision` 核对实际部署。
2. 运行客户端「测试连接」。`/auth/me` 返回 404 时，检查 API 版本和反向代理。返回 HTML 时检查是否填成 Web 地址或命中代理错误页。若直连 API 成功，域名失败，核对代理是否转发 `/auth/*`、`/sync/*` 和 `/health`。
3. `401` 时核对账户密钥是否有效、账户是否启用。遇到账户绑定不匹配，先恢复原工作区使用的服务器和账户，或按[账户指南](accounts.md)迁移本地库存。

若镜像标签已更新但实际版本未变，确认项目没有固定到旧镜像，再从原部署目录拉取并重建。独立容器参照[群晖手动更新](docker-manual.md#5-手动更新或切换渠道)。
