# 群晖手动部署：不使用 Compose

本指南在 Container Manager 中分别创建 API 和 Web 容器。无需项目 YAML、`.env`、SSH、Updater 或 Watchtower，也不向应用挂载 Docker socket。库存、账户、同步、资料补全和版本检查照常使用；镜像由你在群晖界面手动更新。

这里不要求开启“高权限执行容器”或特权模式。Docker 本身仍由 NAS 管理；本文说明的是不向元件仓库容器授予 Docker 守护进程控制权限。

**已有部署请先保留原数据映射。** 下文创建 `data` 目录的步骤用于新安装。已有 Compose 项目可以继续使用普通模板，不必为了取消网页更新而新建空库存；不要把旧数据卷改挂到一个新的空目录。

## 1. 选择一组配套镜像

在 Container Manager 的“注册表”中搜索 `rafaelikaros/component_manager`，下载下面同一行的两个标签。不同 DSM 版本的菜单名称可能略有差异，容器创建向导见[群晖官方说明](https://kb.synology.com/en-my/DSM/help/ContainerManager/docker_container?version=7)。

| 渠道 | API 镜像 | Web 镜像 |
| --- | --- | --- |
| 正式版 | `rafaelikaros/component_manager:latest` | `rafaelikaros/component_manager:web-latest` |
| 开发版 | `rafaelikaros/component_manager:dev` | `rafaelikaros/component_manager:web-dev` |

开发版用于提前验证新功能。API 和 Web 应使用配套版本，不混用正式和开发镜像。发布版本以 [GitHub Releases](https://github.com/Rafael-Ikaros/Component_Manager/releases) 和 [Docker Hub Tags](https://hub.docker.com/r/rafaelikaros/component_manager/tags) 为准。

浮动标签不会自动替换已运行的容器。若要固定版本，选择实际存在且版本号相同的 API、`web-` 标签，例如 `0.7.9-dev.29` 与 `web-0.7.9-dev.29`。

## 2. 创建 API 容器

新安装先在 File Station 创建一个用于持久化的目录，例如 `/volume1/docker/component-vault/data`。

在“映像”中选择 API 镜像，启动创建容器向导，按下表填写：

| 设置 | 示例值 |
| --- | --- |
| 容器名称 | `component-vault-api`，也可自定 |
| 本地端口 → 容器端口 | `8787` → `8787`，TCP |
| 宿主目录 → 容器路径 | `/volume1/docker/component-vault/data` → `/data`，读写 |
| 网络 | 普通 bridge 网络即可 |
| 自动重启 | 按需启用 |
| 环境变量 | 新安装无需添加应用配置变量 |
| 启动命令 | 保留镜像默认值 |

不挂载 `/var/run/docker.sock`，不开启高权限执行，也不用映射 `8790`。确认后创建并启动容器。

若本地 `8787` 已被占用，可以改本地端口，容器端口仍为 `8787`。下文所有 API 地址使用你实际选择的本地端口。

## 3. 创建 Web 容器

使用同一渠道的 `web-*` 镜像创建第二个容器：

| 设置 | 示例值 |
| --- | --- |
| 容器名称 | `component-vault-web`，也可自定 |
| 本地端口 → 容器端口 | `8081` → `80`，TCP |
| 存储映射 | 无需添加 |
| 网络 | 普通 bridge 网络即可 |
| 自动重启 | 按需启用 |
| 环境变量 | 无需添加 |
| 启动命令 | 保留默认 Web 模式，不填写 `updater` |

不挂 Docker socket，不挂 API 的数据目录，不开启特权模式。Web 页面由浏览器访问 API，不依赖容器叫 `api`，也不需要通过 Compose 建立内部服务名。

本地 `8081` 被占用时可以改端口，容器端口保持 `80`。

## 4. 在网页保存配置

以下 `NAS地址` 换成浏览器实际访问 NAS 的 IP 或主机名；使用自己选择的本地端口。

1. 打开 `http://NAS地址:8787/setup`，生成或填写管理员访问令牌并妥善保存。
2. 将管理 Web 的允许来源设为 `http://NAS地址:8081`，管理 Web 地址也填写这个地址。如需在网页增删、批量更新库存，启用相应的 Web 库存操作选项。
3. 保存配置。它会写入 API 挂载的 `/data/config.json`，不需要再把令牌写进环境变量。
4. 打开 `http://NAS地址:8081/`，API 地址填 `http://NAS地址:8787`，使用刚保存的令牌登录。
5. 确认库存页和设置页可以正常打开；Android 也填写同一个 API 地址及对应账户密钥。

Web、API 请使用相同的 IP 或主机名、相同的 HTTP/HTTPS 协议，端口可以不同。例如不要一边填 NAS 主机名、一边填 IP。Web 的允许来源填写浏览器访问的 `8081` 地址，不是 API 的 `8787` 地址；客户端也不要填写 Web 端口或 `/setup` 路径。

这里的 `localhost` 指正在打开网页的设备，不代表 NAS。手机或电脑浏览器访问时请填写 NAS 的实际地址。通过域名或反向代理访问时，按实际 Web origin 配置，详见[连接与配置说明](docker-quickstart.md#3-环境变量和网页配置的优先级)。

管理员可随后创建普通账户，给各用户分发各自的密钥，详见[账户管理](accounts.md)。不要将管理员密钥发给所有用户。

## 5. 手动更新或切换渠道

1. 暂停客户端和 Web 写入，停止 API 容器，备份完整宿主 `data` 目录。记录现有两个容器的镜像、端口、挂载、网络和自定义环境变量。数据包含账户数据库、配置、照片和 SQLite 辅助文件，不要只备份一个 `.db` 文件。
2. 在 Container Manager 下载所选渠道的配套新 API、Web 镜像。渠道对应第 1 节的同一行。
3. 停止旧 API、Web 容器。使用下载后的新镜像创建替代容器，沿用原 API 的同一个宿主数据目录和原端口。可以先使用新的容器名称，保留旧容器为停止状态，便于核对原配置。不要同时启动两个 API 写同一个数据目录。
4. 启动新 API、Web。访问 `http://NAS地址:8787/health` 核对版本，再登录管理台检查账户和库存。
5. 验证无误后再移除不用的旧容器。保留数据目录和备份，不选择删除持久化数据。

仅点“重新启动”会继续使用原容器的镜像，不等于拉取并应用新版本。DSM 提供“重新创建”或类似功能时，也要在确认页核对使用的新镜像和原 `/data` 映射。

若升级后出现初始化页或空库存，先停止写入、检查 `/data` 是否映射到了原目录，不在新空库中继续配置。镜像降级不会撤销数据库迁移；需要恢复时，使用对应镜像与一致的完整数据备份。备份和恢复边界见[升级说明](docker-quickstart.md#6-升级前备份与回退)。

网页“关于服务端”的渠道选择和“检查更新”只查询版本，不会替换容器。未启用网页一键更新是正常部署方式，不影响业务功能。

## 6. 网页一键更新是可选项

只有希望网页代替你重建 API、Web 容器时，才需要考虑 [Updater 配置](server-web-update.md)。当前 Updater 面向 Compose 项目，需要额外的 Docker socket 与部署目录挂载；直接创建的容器不能仅通过填写 `UPDATER_URL` 变成受支持的网页更新项目。

Docker socket 提供的权限不限于本项目。常见 root 模式下，控制 Docker 守护进程可以获得主机级能力，详见 [Docker 官方说明](https://docs.docker.com/engine/security/)。不希望授予该权限时，继续使用本页的手动更新即可；Watchtower 同样需要 Docker 管理访问，并非无权限替代方式。
