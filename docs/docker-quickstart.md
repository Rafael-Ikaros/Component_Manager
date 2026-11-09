# Docker 快速部署与 API Token 查找

本指南说明 Docker／群晖的安装、持久化文件、账户密钥和常见故障。
公开镜像可直接拉取。首次配置页面由 API 提供，不需要先启动独立 Web。

API 与管理台保持前后端分离。从 `0.7.9-dev.8` 起，只构建两种镜像：API 和管理镜像。
管理镜像供 Web 与可选 updater 两个独立容器使用；启用网页更新时共三个容器，
未启用时只需 API/Web。旧独立 updater 的迁移见[说明](server-web-update.md#从独立-updater-镜像迁移)。

已有正式版希望升级到 dev 并在网页管理，请直接看[保留数据升级与网页配置](server-web-update.md#已有正式版先升级到-dev)。
API/Web 可以先成对升级，应用设置随后在网页修改；要连镜像更新也放进网页，需一次启用 updater。
新部署需要这项能力时，按[群晖一次性配置](server-web-update.md#群晖一次性配置)直接使用完整单文件模板。共享管理镜像需使用 `0.7.9-dev.8` 或更新兼容版本。

## 1. 准备并启动

下载 [`docker-compose.hub.yml`](../docker-compose.hub.yml)，放进固定部署目录。正式版默认使用 `latest / web-latest`，可以直接启动。选择开发版时，在 Compose 同目录创建名为 `.env` 的文件，只需这一行：

```dotenv
COMPONENT_VAULT_DOCKER_CHANNEL=dev
```

正式版普通部署可省略 `.env`。选择开发版会拉取 `dev / web-dev`。部署前可在 Docker Hub Tags 核对对应版本。需要固定版本时，在 `.env` 中用 `COMPONENT_VAULT_IMAGE` 和 `COMPONENT_VAULT_WEB_IMAGE` 指定配套的 API 和 Web 完整镜像名。首次网页配置从 `0.7.1` 开始提供。

在部署目录中先启动 API：

```sh
docker compose -f docker-compose.hub.yml pull api
docker compose -f docker-compose.hub.yml up -d api
docker compose -f docker-compose.hub.yml ps
```

打开 `http://服务器IP:8787/setup`。首次未配置时，可在这里生成或填写 `API_TOKEN`、设置管理 Web 的允许来源，以及选择是否允许 Web 库存写入。保存后配置写入 `/data/config.json`，匿名配置入口随即关闭；以后需要使用当前 Token 登录配置页，才能修改受支持的字段或查看日志。访问 API 根地址 `http://服务器IP:8787/` 会跳转到该页面。

如需完整管理 Web，再启动可选 profile：

```sh
docker compose -f docker-compose.hub.yml --profile web pull
docker compose -f docker-compose.hub.yml --profile web up -d
```

管理 Web 位于 `http://服务器IP:8081/`。库存数据保存在 `/data/component_vault.db`；不要执行 `docker compose down -v`。

以后使用默认浮动标签更新时，先在 Docker Hub 核对 `latest` 和 `web-latest` 当前对应的版本，再在原部署目录执行 `docker compose -f docker-compose.hub.yml --profile web pull` 和 `docker compose -f docker-compose.hub.yml --profile web up -d`；仅部署 API 时去掉 `--profile web`。单独执行 `restart` 不会拉取新镜像。

<a id="watchtower-updates"></a>

### 可选：Watchtower 自动更新 API 与 Web

Watchtower 适合自动跟随**当前镜像标签**更新，不负责在正式/dev之间切换。
默认模板不启动它。它与网页 updater 二选一，不能同时管理同一 API/Web。

1. 一致备份整个 `/data`，保留原项目名称、目录和数据映射。
2. 核对 API/Web 已选择同一渠道，例如 `latest / web-latest` 或 `dev / web-dev`。
3. 把 [`docker-compose.watchtower.yml`](../docker-compose.watchtower.yml) 放到原部署目录，执行：

   ```sh
   docker compose -f docker-compose.hub.yml -f docker-compose.watchtower.yml --profile web config --quiet
   docker compose -f docker-compose.hub.yml -f docker-compose.watchtower.yml --profile web up -d
   docker compose -f docker-compose.hub.yml -f docker-compose.watchtower.yml --profile web logs --tail=100 watchtower
   ```

只部署 API 时省略 `--profile web`；原部署带 `-p 项目名` 时，每条命令继续使用原项目名。
群晖 GUI 只接受单文件时，在**原项目**合入 override 的 API/Web 标签和 watchtower 服务，保存后重新部署。
不要只填写环境变量代替标签，也不要创建第二个空项目。

模板采用 `nickfedor/watchtower:latest`，以 `WATCHTOWER_LABEL_ENABLE=true` 限定目标，
只更新带 `com.centurylinklabs.watchtower.enable=true` 的 API/Web，约每24小时检查一次。
Docker socket 仅挂入 Watchtower；使用前在其日志核对与宿主 Docker 的连接情况。
更新由停止并重建容器完成，不是 `restart`。Watchtower 自身仍需在原项目维护。

API/Web 更新并非原子操作。更新后核对容器、`/health` 版本、登录和库存读取；
失败时停用自动更新，使用一致备份与兼容的 API/Web 配对恢复，不删除数据卷。
自动更新不包含数据库备份或数据回滚。该模板尚未在所有群晖机型实测。
关闭时移除 Watchtower 服务和目标标签，再部署原项目，保留数据卷。

## 2. 群晖 Container Manager 与文件映射

在群晖 File Station 中准备项目目录，例如 `/volume1/docker/component-manager/`，将 Compose 放在其中。使用开发版时，先把上面的单行 `.env` 放进这个目录。若希望在 File Station 查看持久化文件，先创建 `data/` 子目录，再在 Container Manager 的 **项目** 中选择该目录并导入 Compose，将 API 的 volume 改成宿主目录映射：

```yaml
services:
  api:
    volumes:
      - /volume1/docker/component-manager/data:/data
```

若通过 Container Manager 图形界面同时启用 Web，在项目的 Compose 编辑器中删除 `admin-web` 下的 `profiles:` 和紧随其后的 `- web` 两行，保留 `8081:80` 端口映射，然后部署项目。先在 `http://NAS地址:8787/setup` 设置管理员密钥，将 `http://NAS地址:8081` 填入 Web 允许来源。再打开 `http://NAS地址:8081/`，以 `http://NAS地址:8787` 为 API 地址，使用刚设置的密钥登录。

部署后可在 File Station 看到这些持久文件：

数据目录由你管理，请使用私有目录。`config.json` 含 API Token；新文件遵循容器
的默认 umask 与挂载目录的访问规则，不额外设为仅容器 root 可读。

| 路径 | 内容 |
| --- | --- |
| `data/component_vault.db` | 库存、同步状态，以及从 Web 设置页保存的 MQTT 配置 |
| `data/config.json` | 首次配置页保存的 Token、允许来源和 Web 写入开关 |
| `data/users/<账户ID>.db` | 普通账户各自的库存、预设及同步记录 |
| `data/component_vault.db.photos/`、`data/users/<账户ID>.db.photos/` | 各账户已同步的原图；需随完整 `/data` 一起备份 |
| `data/logs/server.log` | API 滚动日志；大小和保留数量有上限 |
| `data/recognition_rules_cache.json` | 可选的服务端远程识别规则缓存，仅启用并刷新远程规则后生成 |

容器仍会向 stdout/stderr 输出日志，因此也能在 Container Manager 的容器日志页或 `docker compose ... logs` 中查看。`server.log` 保存应用运行事件，最多 1 MiB，保留 3 份轮转文件；容器标准输出还包括启动和运行时诊断。商品远程图片仍以 URL 引用；用户原图存入 `<账户数据库完整路径>.photos/`，如 `/data/component_vault.db.photos/`。使用一个完整 `/data` 挂载即可，见[原图同步](photo-sync.md)。启用 updater 时另用独立状态卷保存更新任务，见[网页更新映射表](server-web-update.md#群晖一次性配置)。

仓库默认继续使用 named volume `component_vault_data`，方便普通 Docker 用户无修改升级。上面的 bind mount 更适合希望用 File Station 查看配置和日志的 NAS 新安装。

已有 named-volume 部署不要直接切换到空的宿主目录，否则会看起来像“库存消失”。迁移前停止写入，用 SQLite backup 取得一致备份，将数据库及需要保留的配置恢复到新的 `/data`，验证库存后再停用旧 volume。改变映射不会自动迁移或删除旧 volume。

## 3. 环境变量和网页配置的优先级

已有部署可继续在 `.env` 或群晖 **api 容器 → 环境变量** 中设置 `API_TOKEN`、`ADMIN_WEB_ORIGINS`、`ADMIN_WEB_URL` 和 `WEB_INVENTORY_ENABLED`。非空环境变量优先于 `/data/config.json`；被环境变量接管的值不能在网页中修改。若希望网页管理某一项，应清空对应环境变量并执行 `up -d` 重建容器，仅执行 `restart` 不会重新读取 `.env`。

解除覆盖前，先备份并确认该项现值已写入持久化配置。`config.json` 使用 `API_TOKEN`、
`ADMIN_WEB_ORIGINS`、`ADMIN_WEB_URL`、`WEB_INVENTORY_ENABLED` 字段；空配置下直接移除旧 Token
可能改变生效密钥，移除旧允许来源可能令浏览器无法登录。单纯升级镜像无需清空这些值。
MQTT 是另一套优先级：已有 Web 保存的数据库配置优先于 MQTT 环境默认值，修改后按页面提示重启 API。

`ADMIN_WEB_ORIGINS` 填浏览器访问 Web 页面的 origin，例如 `http://NAS地址:8081`，不是 API 的 `8787` 地址。后端未配置时仍采用内置 CORS 默认值，Web 库存写入默认关闭。
新管理 Web 的登录状态仅将 **API 地址**保存在浏览器 localStorage；账户密钥只提交给 `POST /auth/session`，由 API 发放有效期 12 小时的 `HttpOnly` 会话 Cookie。登出会撤销会话；Android、Windows 仍使用各自账户密钥的 `Authorization: Bearer`，不依赖浏览器 Cookie。Web 与 API 应使用同一站点的主机名和协议，例如 `http://nas:8081` 与 `http://nas:8787`（不同端口可以）；混用 `nas`/IP、HTTP/HTTPS 或跨站域名可能令 `SameSite=Lax` Cookie 无法随请求发送。

由于 8081 和 8787 是不同 **origin**，须把浏览器实际打开的 Web `scheme://host:port` 精确加入 `ADMIN_WEB_ORIGINS`，例如 `http://nas:8081`，不要填 API origin 或通配符。API 对允许来源启用带凭据 CORS；改动环境变量后在原项目目录执行 `up -d` **重建** API，单独 `restart` 不会读取新 `.env`。若设置保存于 `/data/config.json`，服务端会动态读取。反向代理应同时正确转发 `/auth/session`、`/auth/me` 与 `/admin-api/*`。

当前 API 和管理 Web 支持在设置中编辑应用配置，也可继续使用已认证的 `/setup`。`ADMIN_WEB_URL` 可填写管理台实际完整地址（支持反向代理子路径）；首次保存后先复制管理员密钥，再进入管理台登录。正式使用 `latest / web-latest`，开发使用 `dev / web-dev`；新模板的 updater 与 Web 使用同一个管理镜像，通过 `command: [updater]` 启动。共享镜像需要 `0.7.9-dev.8` 或更新版本，旧正式镜像不包含此启动模式。实际发布以[Docker Hub 核对记录](dockerhub.md)和 `/health` 为准。

嘉立创／立创目录查询使用公开目录，无需 OpenAPI 凭据。Android 和 Windows 直接查询，Web 库存的目录补全通过服务端查询。远程识别规则和 MQTT 等高级部署选项见 [`.env.example`](../.env.example)。

`VITE_DEFAULT_API_BASE_URL` 只在从源码构建 Web 镜像时生效。Hub 预构建 Web 镜像不会因运行时 `.env` 中这个值改变而重新打包。

## 4. API Token 在哪里

首次配置或环境变量中的 `API_TOKEN` 是**管理员账户密钥**，对应原有库存。不要把管理员密钥统一分发给所有用户：在 Web 管理台的“设置 → 账户管理”创建普通账户，把该账户的密钥交给对应用户。用户的 Android、Windows 和 Web 使用同一账户密钥，数据按账户独立保存。

创建或重置普通账户时只显示一次完整密钥，请当场复制。之后管理员可重置密钥；旧密钥立即失效，库存保留。停用账户也不会删除库存。普通用户不能进入服务端配置或账户管理页面，遗失密钥应联系管理员。

升级账户版本时，先更新 API 和 Web 两个镜像，再更新原生客户端。旧版客户端仍能使用原管理员密钥，但普通用户需要支持账户身份检查的新版客户端。一个原生安装目前对应一份本地工作区；更换到其他账户时会停止同步并保留数据，不会自动把旧库存导入新账户。

除原有数据库和配置文件外，普通账户库存位于 `/data/users/<账户ID>.db`，继续使用同一 `/data` 挂载，无需为每个用户添加 volume。备份时须停止写入并保存整个数据目录，包括管理员数据库中的账户注册信息和 `users/`。若仅恢复管理员数据库而遗漏用户文件，受影响账户会提示数据不可用，需要补回文件，不能当作空库存继续使用。

管理台的登录页和设置页可选择“跟随系统 / 简体中文 / English”；`/setup` 页眉也提供语言选择。选择只保存在当前浏览器的网站存储中，两个端口的网页需要分别设置，不影响其他用户或库存内容。

在 `/setup` 保存的密钥位于容器的 `/data/config.json`，字段名是 `API_TOKEN`。如果 `/data` 映射到项目的 `data/` 子目录，可以在 File Station 打开 `data/config.json` 查看。使用 named volume 时可通过下方容器命令查看。若部署时显式设置了非空的 `API_TOKEN` 环境变量，则以该值为准，可在部署目录 `.env` 或 Container Manager 的 **api 容器 → 环境变量** 查找。请妥善保管密钥和配置文件。

已经运行的 Hub Compose 部署也可在部署目录执行：

```sh
docker compose -f docker-compose.hub.yml exec api printenv API_TOKEN
```

源码 Compose 使用 `docker compose exec api printenv API_TOKEN`。这条命令只显示环境变量来源的值。粘贴到客户端时只复制值。

在容器终端查看当前生效的管理员密钥：

```sh
docker compose -f docker-compose.hub.yml exec api python -c 'from app.config import get_settings; print(get_settings().api_token)'
```

这是在自己管理的容器终端查看当前配置，结果不要放入公开反馈。

如果你没有部署或管理这台服务，请向部署者索取当前 Token，不要猜测初始口令。

## 5. 401 与忘记 Token

升级前使用旧默认 Token、已有库存但没有环境变量或配置文件的部署，会继续识别旧的
`change-me`，避免升级时开放匿名初始化。可用旧 Token 登录后设置新的随机 Token。
这是旧数据兼容行为；全新部署不会自动使用默认口令。

`/health` 成功但客户端返回 `401`，表示客户端 Token 与 API 当前生效值不一致。先按上一节确认当前值，再更新客户端。

忘记 Token 时，优先使用仍有效的登录会话进入配置页。若 Token 由环境变量提供，在 `.env` 中写入新随机值后执行 `docker compose -f docker-compose.hub.yml --profile web up -d`；源码 Compose 使用 `docker compose up -d`。环境变量改动不能靠 `docker compose restart` 生效。修改 Token 会让所有客户端的旧 Token 失效，但不会改变数据库 volume。

PowerShell 可用 `[guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')` 生成随机值；Linux/macOS 可用 `openssl rand -hex 32`。

完整的升级、备份、CORS、MQTT 与维护者发布说明见 [Docker Hub 镜像发布与部署教程](dockerhub.md) 和 [Runbook](runbook.md)。

## 6. 升级前备份与回退

暂停写入后备份整个 `/data`，包括管理员数据库、`users/`、`config.json`、各账户 `.db.photos/` 和需要保留的日志。
SQLite 应使用一致备份；复制原文件时先停止 API 写入，不能只复制 `.db` 而遗漏仍有数据的 WAL。
保留原 Compose、`.env`、项目名和挂载来源，配置备份不能代替业务数据备份。

恢复时同时恢复配套的数据库、照片和配置，再核对账户和库存。
切回旧镜像不会撤销新版的数据库迁移；先确认旧版本能读取该数据格式。
普通账户不能用不支持账户的老服务登录。客户端未上传的本地数据和 BOM 预设另行备份。

## 7. 客户端提示“服务端版本过老”或无法确认账户身份

0.7.5 起，原生客户端同步前会读取 `GET /auth/me`，确认服务器和账户身份。
`/auth/ping` 成功只说明密钥可用于旧认证接口，不能证明账户接口已部署。
最新客户端的“测试连接”同时检查库存协议和账户接口，不会执行同步或更换本地绑定。

当前正式与开发镜像均包含 `/auth/me`。Docker Hub 的浮动标签会随对应渠道发布而变化，
不会自动替换 NAS 上已运行的容器；以实际 `/health` 和[发布核对记录](dockerhub.md)为准。
若 `.env` 中固定了更早的 `COMPONENT_VAULT_IMAGE`，拉取 `latest` 也不会改变 Compose
实际使用的固定标签。先检查这两个镜像变量，再在原项目目录更新：

```sh
docker compose -f docker-compose.hub.yml --profile web pull
docker compose -f docker-compose.hub.yml --profile web up -d
docker compose -f docker-compose.hub.yml --profile web images
docker compose -f docker-compose.hub.yml --profile web ps
```

只部署 API 时去掉 `--profile web`。保留原 `/data` 映射和项目名；只执行 `restart`
不会使用刚拉取的新镜像。群晖 Container Manager 应更新项目所引用的 API 镜像后重新部署，
不是只更新 `admin-web` 容器。

按顺序核对：

1. 客户端填写 API 的 `8787` 地址，不是 Web 的 `8081` 地址，也不要附加 `/setup`。
2. 用同一地址访问 `/health`。当前镜像响应包含 `version` 和 `revision`，
   Web 设置的运行配置也显示它们；0.7.5 和更早镜像没有这两个字段，字段缺失不能单独证明没有账户接口。
   从源码直接启动且未注入构建信息时显示 `source` / `unknown`。
3. 重新运行客户端“测试连接”。`/auth/me` 返回 404，表示所访问的服务没有这个路由，
   需要检查 API 容器版本、端口及反向代理；返回 HTML 通常是请求到了 Web 页面或代理错误页。
4. 若直连 `http://NAS地址:8787` 正常、域名异常，检查反向代理是否同时转发 `/auth/*`、
   `/sync/*`、`/admin-api/*` 和 `/health`。不要只转发旧的 `/auth/ping`。
5. 401 是账户密钥无效或账户已停用，不是镜像过老；账户身份不匹配是另一项保护，
   不要通过清空库存、删数据库或伪造身份字段来绕开。

向维护者提供容器日志、镜像标签、`/health` 结果和客户端的具体错误即可。
不要发送 `config.json` 全文、Bearer Token 或带密钥的请求截图。
