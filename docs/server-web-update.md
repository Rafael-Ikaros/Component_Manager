# 在网页中更新服务端

Updater 是可选的第三个容器。它面向 **Compose 项目**，管理员可从「设置 → 关于服务端」发起更新。普通部署可以直接检查版本；采用 DSM 独立容器时，按[手动更新](docker-manual.md#5-手动更新或切换渠道)替换镜像。

| 当前部署 | 怎么更新 |
| --- | --- |
| DSM 独立容器 | 在 Container Manager 拉取并替换 API、Web |
| 普通 Compose 项目 | 在原项目拉取并重新部署，或按本页启用 Updater |
| 已启用 Updater 的兼容项目 | 管理员在网页选择渠道并确认更新 |

当前正式版 0.7.8 不支持网页一键更新。它可以保留原数据升级到开发版。更新器需要宿主机的 Docker socket，在典型 root 模式下具备管理主机容器的能力。仅在接受这项权限时启用。[Docker 权限说明](https://docs.docker.com/engine/security/)

## 已有正式版先升级到 dev

从 0.7.8 升级时，在同一个 Compose 项目里更换镜像和启动命令，保留原 `/data`、密钥、端口、网络和其它实际生效的配置。可在这次编辑中同时启用 Updater，也可以按[普通部署步骤](docker-quickstart.md#使用统一镜像)只升级 API 与 Web。

先停 API，备份完整 `/data`，另存原 Compose 和 `.env`。若升级后出现首次配置或空库存，先检查项目名和数据映射。旧部署的非空环境变量会继续优先生效。变更这些覆盖值前，确认持久配置已经保存对应内容。[配置优先级](docker-quickstart.md#3-环境变量和网页配置的优先级)

## 使用统一镜像

dev.30 起使用[统一镜像更新模板](../docker-compose.hub-web-update.yml)。API、Web 和 Updater 共用同一个标签。模板让 API 使用默认 `api` 命令，Web 执行 `web`，Updater 执行 `updater`。

已有项目迁移时，在原模板中保留项目配置并合入统一镜像对应的 `image`、`command` 和 API 布局标签 `io.componentvault.compose-layout: unified`。需要跟随开发渠道时，取消残留的固定 `COMPONENT_VAULT_IMAGE` 和 `COMPONENT_VAULT_WEB_IMAGE` 覆盖。旧项目仍可使用配套 `dev` 与 `web-dev` 兼容标签。当前统一布局切回 0.7.8 需在宿主机使用旧版模板，网页会拒绝不兼容镜像。

## 群晖一次性配置

1. 在 File Station 准备固定的项目目录，如 `/volume1/docker/component_manager`。已有项目继续使用原名称、目录、数据卷和配置。等正在进行的更新任务完成后再修改。
2. 把模板放在原部署目录。新建开发版项目的 `.env` 示例：

   ```dotenv
   COMPOSE_PROJECT_NAME=component_manager
   COMPONENT_VAULT_DEPLOY_DIR=/volume1/docker/component_manager
   COMPONENT_VAULT_DOCKER_CHANNEL=dev
   ```

   将项目名和绝对路径换成实际值。现有 `.env` 应保留仍需要的应用配置。普通部署使用 `/setup` 保存管理员密钥，启用 Updater 无需另外填管理员密钥或 Docker Hub 凭据。

3. 编辑原项目的 YAML，或在「项目」中新建并导入模板。模板默认启动 Web。自定义项目保留 `api`、`admin-web` 服务名及镜像变量形式，加入 `updater`、`UPDATER_URL: http://updater:8790` 和任务卷，并令 Updater 与 API 使用同一网络。模板的 Watchtower 标签应保留为 `false`，避免两个更新程序同时重建同一服务。
4. 核对 Updater 的四个映射：

   | 容器路径 | 来源和作用 |
   | --- | --- |
   | `/var/run/docker.sock` | 宿主同路径，供 Updater 操作 Docker |
   | `/data:ro` | 与 API **同一个**数据目录或卷，读取 API 自动生成的内部令牌 |
   | `/state` | 独立持久卷，保存更新任务 |
   | 原部署目录的绝对路径 | 宿主和容器路径相同，供 Updater 读取原 Compose、备份并更新 `.env` |

   API 使用 bind mount 时，将 Updater 的 `/data` 来源也改为该目录。自定义网络及额外 Compose/env 文件沿用原配置，原部署目录以外的必需文件在 Updater 中保持相同的绝对路径。API 和 Web 不挂 Docker socket，Updater 不发布宿主端口。

5. 拉取镜像后部署原项目，核对三个容器、API 的 `/health`、原库存和网页更新状态。命令行可在同一目录执行 `docker compose -f docker-compose.hub-web-update.yml pull` 和 `docker compose -f docker-compose.hub-web-update.yml up -d`，已有部署使用原项目名及实际配置文件名。[群晖项目管理](https://kb.synology.com/zh-cn/DSM/help/ContainerManager/docker_project?version=7)

曾通过旧 `docker-compose.web-update.yml` 覆盖文件启动时，先保留原文件，在同一项目中改用完整模板并重建。容器仍引用旧文件时，Updater 需要读取它。核对新容器已改用完整模板后，再移除旧覆盖文件。

## 从独立 Updater 镜像迁移

旧 `updater-dev` 容器可以继续服务于与其兼容的项目。升级到 dev.30 起的统一布局时，按[统一镜像步骤](#使用统一镜像)迁移，并用 `updater` 命令重建原 Updater，沿用原 `/state` 和其它挂载。

若必须固定到 dev.29，可用 [dev.29 完整模板](https://github.com/Rafael-Ikaros/Component_Manager/blob/v0.7.9-dev.29/docker-compose.hub-web-update.yml)。旧布局由 API 镜像和配套 `web-*` 镜像组成，Web 使用默认命令，Updater 从同一个 Web 镜像启动并指定 `updater`。共享管理镜像从 dev.8 开始支持 Updater。迁移完成后可从宿主 `.env` 移除旧的 `COMPONENT_VAULT_UPDATER_IMAGE` 覆盖。

## 日常网页操作

1. 管理员进入「设置 → 关于服务端」，选择渠道，点击「检查更新」查看目标版本。
2. 点击「更新到所选渠道」，核对目标和备份提示并确认。Updater 先拉取镜像并检查版本与角色，然后备份 `.env`，重建 API 和已部署的 Web。它自身继续运行。
3. 短暂断线后页面会重新连接。完成时核对 `/health`、登录和库存。更新 Updater 自身时仍从原项目手动部署，等待进行中的任务完成。

拉取或镜像校验失败时，原容器会继续运行。重建开始后若服务一直未恢复，查看 DSM 中 API 与 Updater 的日志。更新任务记录在 `/state`，可重复执行同一更新或在宿主恢复原配置。`.env.before-channel-*` 只备份部署配置，需要恢复库存时使用之前备份的整个 `/data`。

命令行维护仍可用[部署切换助手](docker-channels.md)。目标版本使用旧镜像布局时，请在宿主用相应模板回退，并先核对数据库兼容性。
