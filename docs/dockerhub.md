# Docker Hub 镜像发布与验证

本文供仓库维护者配置 Actions 发布凭据、补发镜像和核对产物。
安装服务端请看[快速部署](docker-quickstart.md)；在网页更新请看[网页更新](server-web-update.md)。
普通部署者无需配置下方 Docker Hub Token。

从 `0.7.9-dev.8` 起，工作流只构建 API 和共享管理镜像。管理镜像默认运行 Web，
以 `command: [updater]` 运行独立更新容器；发布沿用 `web-*` 标签，不再新增 `updater-*` 标签。
构建、双角色启动及发布后的真实更新均须通过 CI；下方 dev.7 摘要记录的是旧三镜像结构。

当前 Docker CI / 发布已独立为 **Docker CI / Docker Images**。正式镜像继续使用 latest/web-latest，开发镜像使用 dev/web-dev；不必手改标签的操作见 [部署切换助手与渠道教程](docker-channels.md)。下面的 0.7.0 / 0.7.8 摘要是历史发布证据，不代表开发渠道当前版本。

2026-11-02 已实际核对 **0.7.9-dev.7**（首次启用网页更新请使用本版或更高兼容版本）：

| 镜像标签 | 固定标签 | 实际清单摘要 |
| --- | --- | --- |
| `dev` | `0.7.9-dev.7` | `sha256:62a22716adde13eeb53f9a38f5f9bdefe3c90556cd380a84ba02812eef59d927` |
| `web-dev` | `web-0.7.9-dev.7` | `sha256:c3439c6899423a8c6bd7cae7cedde99038b7356a22e696f8ab1e87354d2ca0b7` |
| `updater-dev` | `updater-0.7.9-dev.7` | `sha256:7580fc873c774fbfad4e82e41ee46b68c83441b58ff34f508f7ee85d020633c2` |

三组均有 amd64/arm64，实际 amd64 OCI version 为 0.7.9-dev.7、revision 为
`6567a3d0faeeab3f50bfab8d5fa64d10729c4a4d`；API/Web 具有网页更新能力标签。
[发布与 Docker 任务](https://github.com/Rafael-Ikaros/Component_Manager/actions/runs/41392059575)成功，
包括真实临时项目经管理员 API 更新、API/Web 替换与数据/端口/挂载保留验收。
正式 latest/web-latest 仍为下文 0.7.8 摘要，没有被 dev 发布替换；尚无正式 updater-latest。
dev.6 虽已推镜像，发布末尾验收暴露 `.env` 属性丢失，本版已修正，请勿再采用 dev.6 启用更新。
群晖可用[完整单文件配置](../docker-compose.hub-web-update.yml)避免手动合并；真实 NAS 尚未执行验收。

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
| GitHub 账号与仓库 | `Rafael-Ikaros/Component_Manager` |
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

打开 [GitHub 仓库 Actions 配置](https://github.com/Rafael-Ikaros/Component_Manager/settings/secrets/actions)，对应路径为：

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

## 五、独立补发已验证版本的镜像

打开 [Docker Images 工作流](https://github.com/Rafael-Ikaros/Component_Manager/actions/workflows/server-image.yml)：

1. 左侧选择 **Docker Images**，点击 **Run workflow**。
2. **Use workflow from** 选择 `master`。
3. `source_ref` 留空，默认检出下方 tag；也可显式填相同提交或 tag。
4. `release_tag` 填已经存在且通过验证的目标 tag，例如 `v0.7.9-dev.7`。
5. `push_image` 选择 `true`，运行并等待独立任务结束。

`source_ref` 决定源码，`release_tag` 决定版本与渠道；发布前会检查二者指向同一提交。
独立重跑不重建 Android/Windows，不移动现有 Git tag。普通部署者无需执行这组维护者操作。
手动触发方式见 [GitHub 官方说明](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow)。

以 dev.8 为例会推送 API `0.7.9-dev.8 / dev`、管理镜像 `web-0.7.9-dev.8 / web-dev`。
正式版本使用相同固定前缀，但浮动渠道为 `latest / web-latest`。
旧 dev.7 的 `updater-*` 标签保留历史用途，不再随新版本更新。补发历史版本会移动**该渠道**的浮动标签，操作前应确认目标，
不要为了补旧版本附件而无意回退当前渠道。

普通 Docker CI 或 `push_image=false` 只构建检查。请求发布但用户名、镜像名或 Token 缺失时，
Docker job 明确失败。changelog 发布并行执行原生附件与 Docker job，一方成功不能代表另一方成功，
因此 GitHub Release 已出现也仍要核对 Docker job。

## 六、确认发布确实成功

先在本次 Actions run 中确认发布配置、API/管理镜像构建、三个角色的启动及健康检查、登录、两种多平台镜像推送均成功。
发布末尾的 **Verify real Web-triggered Compose update and data preservation** 也须成功；
该项在唯一临时项目中执行真实更新，不操作用户 NAS。

再到 [Docker Hub Tags](https://hub.docker.com/r/rafaelikaros/component_manager/tags) 确认：

- 存在对应 API、管理镜像的固定版本与该渠道浮动标签。
- 同一镜像的固定/浮动标签摘要一致，两种镜像 OCI `version / revision` 对应同一发布源。
- 清单包含 `linux/amd64` 和 `linux/arm64`。

工作流在 `linux/amd64` 实际启动 API、Web、updater，验证健康、认证、任务持久化和 Web 资源。
发布后通过管理员 API 发起真实更新，检查 API/Web 被替换、updater 持续运行，以及数据、挂载、端口保留。
`linux/arm64` 参与构建与发布，当前 workflow 不在 ARM 机器上启动；这也不等于所有群晖机型实测。

本地有 Docker 时可额外检查；维护者通过 Actions 发布并不要求本机安装 Docker：

```sh
docker buildx imagetools inspect rafaelikaros/component_manager:dev
docker buildx imagetools inspect rafaelikaros/component_manager:web-dev
```

## 部署者入口

安装、密钥、挂载及故障排查见[快速部署](docker-quickstart.md)。
正式/dev切换见[渠道指南](docker-channels.md)；网页操作更新见[网页更新](server-web-update.md)。
这些步骤不需要本页的发布凭据。
