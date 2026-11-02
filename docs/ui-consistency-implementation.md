# UI 一致性历史验收记录（2026-09-16）

本文保留当时的 CI、截图与验收边界，不是当前菜单、认证方式或部署教程。
旧审查/布局计划已移除，现行界面与功能入口见 [README](../README.md) 和
[架构说明](architecture.md)，后续实际设备验收见 [工作记录](current-work.md)。
下面的“本轮”“待验收”均指 2026-09-16，不代表目前仍未实现，也不代表后续已完成全部 QA。

## 实施范围

本轮保持各平台原生技术栈：Android 使用 Material 3 Compose，Windows
使用 WinUI 3，管理台使用 React、Tailwind CSS 与现有 shadcn/ui 组件。
没有改变同步 payload、库存写入规则或数据库业务约束。

### Android

- “导入与扫码”保留库存页上的底部弹层，将单件查询、批量嘉立创入库、项目
  BOM、数据迁移和手动录入分开；BOM 与 component-hub 迁移不再共用一个
  模糊入口。关闭弹层仍回到原库存界面，保留原来的入口操作方式。
- BOM 按“配置 -> 紧凑摘要与有界预览 -> 确认”分步，配置与预览各自具备
  明确滚动边界。
- 库位、备份、BOM 和批量页面共用
  SecondaryPageScaffold，统一标题、系统 Back、窗口 inset 和忙碌返回规则。
- 库位列表占用剩余空间，新增与编辑进入可滚动对话框。
- 连接测试使用 URL/令牌草稿，不保存凭据、应用偏好或同步 cursor；正式
  同步要求先显式保存发生变化的连接配置。

### Windows

- NavigationView 为批量入库提供 Frame Back 历史；库存页使用导航缓存，
  返回后保留筛选和页面状态。批量作业忙碌时阻止 Back 与侧栏切换，空闲
  离开前保存草稿。
- 测试连接使用临时配置；保存仍是唯一持久化入口。连接草稿变化后必须先
  保存再正式同步。
- 顶部同步按钮复用全局忙碌状态，显示进度并阻止重复同步结果对话框。
- Dashboard、Movements 与批量操作区增加窄窗口重排。

Windows 构建在 GitHub Actions 35013684763 中成功。该结果证明当前
WinUI 项目可以编译，不代替 800/1280 宽度以及 100%/150%/200% DPI 的
真实视觉验收；本轮没有完成这组高 DPI 实机检查。

### 管理台与服务端

- 保留 /admin-api/inventory 概览契约，新增经过 Bearer token 保护的
  GET /admin-api/components 搜索分页列表和
  GET /admin-api/components/{id} 只读详情。
- 列表对 SKU、名称、分类、默认库位进行字面子串搜索，可筛选低库存，
  排除软删除，按 updated_at DESC, id ASC 稳定排序。页大小限制为
  1..100，页码限制为 1..1,000,000。
- 管理台库存页提供中文搜索、状态筛选、URL 中的查询与页码、分页、详情、
  加载/空结果/失败状态。窄屏降低表格列密度；低库存同时使用文字与颜色。
- 详情打开后滚动并聚焦标题，关闭后把焦点交回原 SKU。资源请求使用代次
  保护，迟到响应不能覆盖新查询。
- 401 会说明登录已失效，并只允许返回 Dashboard、Inventory、Sync 或
  Settings 内部路径；重新验证令牌后恢复原库存查询。

使用隔离服务端和 45 条样例记录完成了实际浏览器验证：登录后恢复库存
路径、进入第 2 页、搜索最后一条 SKU、打开详情、空结果，以及
390px 宽度下无整页横向溢出。该运行没有记录 JavaScript 错误。

截图：[桌面库存页](ui-review/2026-09-16/admin-inventory-desktop.png)、
[390px 库存页](ui-review/2026-09-16/admin-inventory-mobile.png)。图片全部使用
隔离样例数据。另已验证低库存筛选、迟到响应不覆盖新查询、401 登录恢复，
以及关闭详情后焦点返回对应 SKU。

## 自动化证据与边界

- 服务端新增聚焦测试覆盖鉴权、超过 12 条记录、搜索、分页、软删除、
  详情 404、页码/页大小上限和 SQL 通配符字面搜索。
- 管理台 TypeScript 检查通过；实际浏览器验证使用隔离的 45 条样例。
- GitHub Actions 运行
  [35013684763](https://github.com/Rafael-ban/Component_Manager/actions/runs/35013684763)
  中，server、admin-web、Windows 和 version 检查成功；Android 应用编译
  与配置的 112 项回归成功，0 失败、0 跳过，包含恢复底部弹层后的入口、
  选项滚动和返回检查。
- Android CI 已生成并检查导入入口、BOM/迁移初始页、库位列表深浅色和
  新增库位对话框的真实像素截图。检查中发现并修正了 BOM 顶部标题过长、
  重复任务切换入口的问题。新增/编辑库位两种对话框均有有效截图。
- 截图来自 Robolectric native graphics 的 View 绘制，不是 Android 真机
  或 Android Studio Compose Preview 的验收。本轮没有完成 Android
  真机相机、输入法、大字号或 Windows 高 DPI 实机验收。

导入入口保留原底部弹层行为；页面截图、库位交互与对话框截图分别验证。
库位交互使用 Robolectric 默认设备配置，页面截图使用 360×640dp 配置，
对话框截图使用默认设备的独立窗口。自定义 density 下的 Dialog idle 异常
没有被认定为已解决的设备兼容性问题，需要后续真机检查。

后续验收应分别进行 Android 真机/大字号/
主题与 Windows 多 DPI 检查。管理台仍可继续做完整读屏器验证；已完成的
浏览器流程不等同于全站无障碍认证。

## 界面截图

| 页面 | 截图 |
| --- | --- |
| 导入与扫码底部弹层 | [入口](ui-review/2026-09-16/import-workflow-chooser-light.png) |
| 项目 BOM / 数据迁移 | [BOM](ui-review/2026-09-16/bom-file-stage-light.png)、[迁移](ui-review/2026-09-16/migration-file-stage-light.png) |
| 库位列表 | [浅色](ui-review/2026-09-16/storage-locations-list-light.png)、[深色](ui-review/2026-09-16/storage-locations-list-dark.png) |
| 库位表单 | [新增](ui-review/2026-09-16/storage-locations-create-dialog-light.png)、[编辑](ui-review/2026-09-16/storage-locations-edit-dialog-light.png) |

所有图片使用测试数据；截图不覆盖相机、系统输入法和真实设备 DPI 行为。

## 部署与回退

管理台新版库存页依赖本轮新增的只读列表/详情 API，应将管理台和服务端
配套更新；没有新增数据库表、环境变量或库存写入接口。回退时一起恢复
原管理台 bundle 与服务端代码。客户端本轮 UI 改动可按提交回退，不需要
重置本地库存。既有多库位数据库版本的回退限制仍以 runbook 为准，不能
使用不认识该数据格式的老客户端写入数据库。

后续 UI 工作仍包括 Windows 真窗口高 DPI 检查、Android 真机大字号与
输入法遮挡检查，以及管理台其他页面的逐项一致性检查。此次完成范围
不表示整个产品的每个页面都已完成视觉验收。
