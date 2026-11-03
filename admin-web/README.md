# Web 管理台

本目录是 React、Vite、Tailwind CSS 和 shadcn/ui 实现的独立管理台。
用户操作见[使用指南](../docs/README.md)，本文只说明开发和部署边界。

## 功能与认证

- 查看当前账户的库存、出入库记录、同步审计、设备和 BOM 预设。
- 管理员管理普通账户、部署配置、日志和 MQTT；普通账户的数据独立。
- 库存写入默认关闭，管理员启用 `WEB_INVENTORY_ENABLED` 后可编辑、出入库和删除库存。
- 登录通过 `/auth/session` 换取后端 HttpOnly 会话；浏览器不持久保存账户密钥，写请求携带 CSRF Token。
- 镜像更新需额外启用 updater。检查新版本与执行容器更新是两个操作，详见[网页更新](../docs/server-web-update.md)。

## 本地开发

在仓库根目录执行：

```powershell
cd admin-web
cmd /c npm ci
cmd /c npm run dev
```

默认页面 `http://localhost:5173`，默认 API `http://localhost:8787`。
登录时可以修改 API 地址。API 必须允许页面的实际 origin；会话要求同站点的主机名和协议。

```powershell
cmd /c npm run build
```

产物在 `dist/`，发布附件为 `component-vault-admin-web.zip`。
Vite 构建参数只影响源码构建，不会在已构建的 Hub 镜像中随运行时环境变量重新打包。

## 部署与接口

管理台与 API 是两个配套镜像。管理镜像同时包含网页和 Updater，分别启动、独立运行：
默认命令 `web` 运行 Nginx；`command: [updater]` 运行更新服务，不启动 Nginx。
只有 Updater 容器挂载 Docker socket、更新任务卷及原部署目录，Web 无需这些挂载。
两者使用相同的 `COMPONENT_VAULT_WEB_IMAGE`，不再单独发布新的 `updater-*` 镜像。

在仓库根目录运行 `docker build -f admin-web/Dockerfile .` 构建管理镜像；
构建需要 `updater/` 和 `tools/deployment/`，不能以 `admin-web/` 为构建上下文。

Hub 模板的 Web 使用 `web` profile，端口为 `8081:80`；
完整网页更新模板默认启动 Web。普通部署见[快速部署](../docs/docker-quickstart.md)。
接口契约以[集成指南](../docs/integration-guide.md)为准，不以本页维护一份不完整的端点清单。
