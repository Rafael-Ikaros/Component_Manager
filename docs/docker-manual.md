# 群晖 Container Manager 图文部署

在群晖的 **Container Manager → 映像**中创建 API 和 Web 两个独立容器，全程在 DSM 界面完成，无需 Compose、`.env`、SSH 或 Docker socket。只需要 Android 同步时，创建 API 并设置管理员密钥即可。

本页用 `v0.7.9-dev.30` 的实拍演示。截图里的 `192.168.1.4` 是示例，请换成自己的 NAS 地址。带 `-example` 的名称用于演示向导。

![部署示意。浏览器访问 NAS 的 8081 端口打开 Web 管理台，客户端和管理台都使用 8787 端口访问 API。](images/docker-manual/access-topology.svg)

## 1. 下载对应版本的镜像

在「镜像仓库」搜索 `rafaelikaros/component_manager`，按下表下载标签。`dev` 统一镜像只下载一次，后面用它创建两个容器。

| 要安装的版本 | API 标签 | Web 标签 | Web 的「命令」 |
| --- | --- | --- | --- |
| 开发版 dev.30 起 | `dev` | 使用同一个 `dev` | 填 `web` |
| 固定 dev.30 | `0.7.9-dev.30` | 使用同一个 `0.7.9-dev.30` | 填 `web` |
| 正式版 0.7.8 | `latest` | `web-latest` | 留空 |

**搜索提示无法连接时**，打开「映像 → 操作 → 导入 → 从 URL 添加」。粘贴下面的仓库地址，账号和密码留空，保留默认的证书验证。点击「导入」后，在列表中选择需要的标签。

```text
https://hub.docker.com/r/rafaelikaros/component_manager
```

![群晖「从 URL 添加」窗口。填写 Docker Hub 仓库地址，账号和密码保持空白。](images/docker-manual/00-import-url.jpg)

下载完后在「映像」找到所选标签。下图中只有一个 `dev` 映像，选中它后可以点「运行」。

![DSM 映像列表选中了 rafaelikaros/component_manager 的 dev 标签。](images/docker-manual/01-images.jpg)

## 2. 创建 API 容器

**准备数据目录。** 新安装用 File Station 在 `docker` 共享文件夹下创建 `component-vault/data`。如果在升级，先[备份并记录旧挂载](#5-手动更新或切换渠道)，创建新容器时选择原目录。

1. 选中 API 映像，点「运行」。容器名称填 `component-vault-api`，可以勾选自动重新启动。「通过 Web Station 设置网页门户」保持未勾选，然后点「下一步」。

   ![API 常规设置。映像使用 dev、启用自动重新启动、Web Station 网页门户未勾选。](images/docker-manual/02-api-general.jpg)

2. 在「高级设置」将本地端口和容器端口都填 `8787`，类型选 `TCP`。在「存储空间设置」点「添加文件夹」，选准备好的数据目录，装载路径填 `/data`，权限选「读取/写入」。

   ![API 高级设置。8787 映射到 8787，选中的宿主目录以读写方式挂载到 /data。](images/docker-manual/03-api-ports-data.jpg)

3. 新安装保留镜像的环境变量。网络选 `bridge`，高权限执行保持关闭。在「执行命令」中保留 Entrypoint，把「命令」留空。图里灰色的 `api` 是默认命令提示。

   ![API 使用 bridge 网络和镜像自带的入口。命令框为空，灰色提示为 api。](images/docker-manual/04-api-command.jpg)

4. 点「下一步」。在摘要核对端口和 `/data` 后，勾选「向导完成后运行此容器」，点击「完成」。打开 `http://NAS地址:8787/health`，应看到 `"status":"ok"`。版本从同一响应的 `version` 查看。

## 3. 创建 Web 容器

1. 回到「映像」再点一次「运行」。开发版选刚才用过的 `dev`，正式版 0.7.8 选 `web-latest`。容器名称可填 `component-vault-web`，保持 Web Station 和高权限执行关闭。
2. 在「高级设置」把本地端口填 `8081`，**容器端口填 `80`**，类型选 `TCP`。如果预填了 `8787` 或 `8790`，改成 `80` 或删除预填行后新增。Web 的存储空间留空。

   ![Web 高级设置。8081 映射到 80，没有任何文件或文件夹挂载。](images/docker-manual/05-web-ports.jpg)

3. 网络选 `bridge`，Entrypoint 保留默认。**统一镜像在「命令」框填 `web`。** 正式版 `web-latest` 的命令框留空，沿用镜像自己的默认值。

   ![统一镜像的 Web 容器命令框已明确填入 web，镜像入口保持不变。](images/docker-manual/06-web-command.jpg)

4. 在摘要核对端口和命令，完成向导并启动。回到「容器」，两个容器应显示绿色运行状态，「项目」一栏显示 `-`。打开 `http://NAS地址:8081/`，应看到管理台登录页。

   ![实际运行的 component-vault-api 与 component-vault-web 均显示绿色，项目一栏为短横线。](images/docker-manual/07-running-containers.jpg)

宿主机端口已被占用时，换一个空闲的**本地端口**。容器端口保持 API `8787`、Web `80`，下一节的浏览器地址也要换成实际使用的本地端口。

## 4. 设置管理员并登录

1. 打开 `http://NAS地址:8787/setup`，在「首次设置」生成或填写管理员 API Token，并保存到自己的密码管理器。新安装可以通过此页完成设置，容器环境变量无需新增 `API_TOKEN`。
2. 展开「Web 管理台（可选）」，**下面两个字段都要填**。服务端从第二项判断是否允许网页发起登录请求。

   | 字段 | 示例 |
   | --- | --- |
   | Web 管理台访问地址 | `http://NAS地址:8081/` |
   | 允许访问 API 的 Web 来源 | `http://NAS地址:8081`，只填协议、主机和端口 |

   ![群晖实机保存后的服务端配置。Web 管理台地址和允许来源使用 Web 的 8081 端口。](images/docker-manual/09-web-config.jpg)

3. 需要在网页编辑、删除库存或办理出入库时，可开启「允许已登录的 Web 用户修改库存」。点击「保存并完成设置」。已有部署可以在 `/setup` 用当前管理员密钥登录后修改；配置了非空环境变量的字段以环境变量为准。
4. 打开 Web 登录页 `http://NAS地址:8081/`。**登录页的 API 地址填 `http://NAS地址:8787`**，输入刚保存的密钥。登录后应看到管理员身份和库存概览，新安装的库存统计为零。Android 同样填写 API 地址和对应账户密钥。

   ![实际登录后的 Web 概览显示管理员会话有效，有效元器件、库存和流水为零。](images/docker-manual/10-web-overview.jpg)

Web 和 API 使用同一主机名和协议，例如都用 IP，或都用同一域名。两个端口可以不同。普通用户使用各自的密钥，由管理员在[账户管理](accounts.md)中创建。

## 5. 手动更新或切换渠道

![手动更新示意。先备份，再使用新镜像重新创建容器，沿用原数据目录，最后验证版本和库存。](images/docker-manual/backup-and-recreate.svg)

1. 记下旧 API 和 Web 的镜像、端口、数据挂载和自定义环境变量。可从容器详情导出容器设置，妥善保管可能包含密钥的导出文件。
2. 暂停写入并停止旧 API，备份整份宿主数据目录。备份应包含数据库和存在的 SQLite 辅助文件、`config.json`、`users/` 和各账户的照片目录。容器设置导出与数据备份分别保存。
3. 下载目标版本，停止旧 Web，再从新镜像创建 API 和 Web。保持原来的端口、API 的 `/data` 映射和有效配置，命令按第 1 节的目标版本填写。若保留已停止的旧容器，给新容器取不同名称。单纯点「重新启动」仍会使用旧镜像。
4. 打开 `/health` 核对版本，登录 Web 检查账户和库存。升级后如果出现首次设置页或空库存，先暂停操作，核对 `/data` 是否挂回原目录。检查通过后方可清理旧容器和无引用的镜像，保留数据目录与备份。

如果目标版本无法读取新版数据库，使用对应旧镜像和升级前的一致备份恢复。[备份与回退](docker-quickstart.md#6-升级前备份与回退)

## 分阶段排障

| 现象 | 核对步骤 |
| --- | --- |
| 「镜像仓库」搜索失败 | 试第 1 节的「从 URL 添加」。下载也失败时检查 NAS 网络及原始拉取错误。「映像」里已有同名标签时，核对它是否是本次下载的 Image ID。 |
| Web 容器启动后退出 | 检查所用标签、Entrypoint、启动命令和日志。用统一镜像时核对命令是 `web`，容器端口是 `80`。 |
| 日志提示 `exec /usr/local/bin/componentvault-entrypoint: no such file or directory` | 记录映像的 Image ID、容器实际入口和挂载目标。可用同一镜像、空闲宿主端口和第 3 节配置另建一个 Web 容器对照，无需挂载 API 数据。 |
| Web 页能打开，登录提示无法建立会话 | 在同一浏览器打开 API 的 `/health`。随后到 `/setup` 登录，确认「允许访问 API 的 Web 来源」有浏览器实际打开的 Web 地址，例如 `http://NAS地址:8081`。两个字段分别保存，填了管理台地址还需填允许来源。 |
| 更新后仍显示原版本 | 在 `/health` 查看 `version`，核对新容器使用的 Image ID。只下载镜像或重启旧容器都不会完成替换。 |

反馈问题时附上 DSM 版本、CPU 架构、映像 Image ID、实际 Entrypoint/命令和完整错误行。截图请遮盖 Token、Cookie 和敏感环境变量。

## 6. 网页一键更新是可选项

管理台的「检查更新」可用于查看版本。按本页创建的独立容器在 DSM 中手动更新。希望从网页重建容器时，使用 Compose 项目和[可选 Updater](server-web-update.md)，由 Updater 使用 Docker socket 管理本项目的容器。
