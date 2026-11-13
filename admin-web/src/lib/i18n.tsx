import { createContext, useContext, useEffect, useMemo, useState, type PropsWithChildren } from "react";
import { loadLanguagePreferenceFrom, resolveLocale, saveLanguagePreferenceFrom, type LanguagePreference, type Locale } from "./locale.ts";

export type { LanguagePreference, Locale } from "./locale.ts";
export { LANGUAGE_KEY } from "./locale.ts";

function readPreference(): LanguagePreference {
  return loadLanguagePreferenceFrom(() => localStorage);
}

export const en = {
  "库存平均单价（CNY）": "Inventory weighted-average unit cost (CNY)",
  "成本未知": "Cost unknown",
  "购买金额依据": "Purchase amount basis",
  "商品总金额": "Product total",
  "购买单价": "Purchase unit price",
  "购买单价（CNY）": "Purchase unit price (CNY)",
  "商品总金额（CNY）": "Product total (CNY)",
  "本次商品金额（CNY）": "This receipt's product amount (CNY)",
  "请核对金额与数量": "Check the amount and quantity",
  "留空表示成本未知，明确填写0表示赠品。不含运费、PCB和加工费。": "Blank means unknown cost; enter 0 explicitly for a free gift. Freight, PCB and assembly are excluded.",
  "补录当前库存成本": "Record current inventory cost",
  "仅对当前剩余库存估值，不修改数量或历史采购记录。": "Values only the remaining stock, without changing quantities or historical purchases.",
  "当前数量": "Current quantity",
  "当前库存总成本（CNY）": "Current total inventory cost (CNY)",
  "确认补录": "Confirm inventory valuation",
  "金额必须为非负数，最多8位小数。": "Enter a non-negative amount with at most 8 decimal places.",
  "当前库存成本已保存，数量与历史采购记录未改变。": "Inventory cost saved. Quantity and historical purchases are unchanged.",
  "更新渠道": "Update channel",
  "此选择用于版本检查和下方更新目标；只有确认更新才会替换容器。渠道偏好保存在此浏览器。": "This selection is used for version checks and the update target below. Containers change only after confirmation. The preference is saved in this browser.",
  "此 API 或更新服务版本尚不支持网页更新，请先在群晖项目中配置配套版本。": "This API or update service does not support Web updates yet. Configure matching versions in the Synology project first.",
  "请关闭确认框，刷新库存核对当前状态后重新选择。": "Close this dialog, refresh inventory to verify its current state, then select again.",
  "版本检测渠道": "Release channel to check",
  "正式版（推荐）": "Stable (recommended)",
  "开发版（含预发布）": "Development (includes prereleases)",
  "检测渠道仅保存在此浏览器，不会替换正在运行的容器。开发渠道也会包含比开发版更新的正式版。": "This preference is saved in this browser and does not replace running containers. The development channel also includes newer stable releases.",
  "Docker 渠道切换": "Switch Docker channel",
  "在原部署目录运行切换助手，选择渠道后自动拉取并重建 API 和 Web。沿用原项目名、端口和数据挂载，不需要手动修改镜像标签。": "Run the switch assistant in the original deployment directory. It pulls and recreates the API and Web services for the selected channel, keeping the project, ports and data mounts. No manual image-tag editing is needed.",
  "将 PROJECT_NAME 替换为原 Compose 项目名。群晖可通过 SSH 运行；不使用 SSH 时按部署教程在 Container Manager 中切换。": "Replace PROJECT_NAME with the original Compose project name. On Synology, run it over SSH or follow the Container Manager instructions in the deployment guide.",
  "Watchtower 跟随容器当前的镜像渠道，不会自行跨渠道。切换前备份完整 /data；切回正式版不能代替数据回滚。": "Watchtower follows each container's current image channel and does not switch channels. Back up all of /data before switching; switching to stable is not a data rollback.",
  "源码构建无法自动比较版本。所选渠道最新版本：": "Cannot compare a source build automatically. Latest in the selected channel: ",
  "当前版本不低于所选渠道最新版本：": "The current version is not older than the latest in the selected channel: ",
  "元件照片": "Component photo", "查看元件照片": "View component photo",
  "照片尚未上传到服务端，请在保存原图的设备上同步。": "The photo is not on the server yet. Sync the device that has the original.",
  "照片加载失败，请重试。": "Could not load the photo. Please retry.",
  "正在加载照片…": "Loading photo…",
  "BOM 预设": "BOM presets",
  "查看当前账户已同步的 BOM 需求和选择。阅读预设不会扣减库存；请在原生客户端核对匹配并配套。": "View synchronized BOM requirements and selections for this account. Reading presets does not deduct stock. Review matches and kit parts in a native client.",
  "无法读取 BOM 预设": "Could not load BOM presets",
  "正在读取 BOM 预设…": "Loading BOM presets…",
  "仅显示有效预设，删除记录保留用于同步。": "Active presets are shown. Deleted records are retained for sync.",
  "暂无已同步的 BOM 预设。请先在 Android 或 Windows 客户端保存并同步预设。": "No synchronized BOM presets yet. Save and sync a preset from Android or Windows first.",
  "项目": "Project",
  "生产套数": "Production sets",
  "来源文件": "Source file",
  "详情": "Details",
  "查看": "View",
  "工作表": "Worksheet",
  "选择状态为保存时的设置，不代表当前库存可用或已配套；缺失的元件映射需在原生客户端修复。": "Selections reflect saved settings and do not indicate stock availability or completed kitting. Repair missing component mappings in a native client.",
  "此预设没有需求行。": "This preset has no requirement lines.",
  "每套数量": "Quantity per set",
  "总需求": "Total required",
  "选择状态": "Selection",
  "跳过": "Excluded",
  "参考位号": "References",
  "自动匹配": "Automatic matching",
  "明确未匹配": "Explicitly unmatched",
  "已选元件 ID": "Selected component ID",
  "概览": "Dashboard", "库存": "Inventory", "同步": "Sync", "设置": "Settings",
  "独立管理台": "Admin console", "库存默认只读，服务端启用后可操作；同时支持 MQTT 配置。": "Inventory is read-only by default. Server settings can enable editing and MQTT configuration.",
  "已连接 API": "Connected API", "令牌有效": "Token valid", "退出登录": "Sign out",
  "语言": "Language", "跟随系统": "Use system language", "简体中文": "Simplified Chinese", "English": "English",
  "Component Vault 管理台": "Component Vault Admin", "管理台提供的功能": "Admin console features", "服务端库存统计与低库存提醒": "Server inventory totals and low-stock alerts", "完整库存检索与同步记录核对": "Search inventory and review sync records", "运行配置与部署状态": "Runtime configuration and deployment status", "供下次服务重启使用的 MQTT Broker 配置": "MQTT broker settings for the next server restart", "推荐的本地配置": "Recommended local setup", "连接 API": "Connect to API", "需要重新登录": "Sign in again", "API 地址": "API address", "粘贴部署时设置的 API_TOKEN": "Paste the deployed API_TOKEN", "隐藏令牌": "Hide token", "显示令牌": "Show token", "已复制": "Copied", "复制令牌": "Copy token", "首次部署？打开服务端配置与日志": "First deployment? Open server setup and logs", "连接失败": "Connection failed", "正在验证令牌…": "Verifying token…", "进入管理台": "Enter admin console", "无法登录管理台。": "Could not sign in to the admin console.", "浏览器未允许复制，请显示令牌后手动复制。": "The browser blocked copying. Show the token and copy it manually.",
  "Dashboard": "Dashboard", "Server-side inventory overview for the current self-hosted sync node.": "Server-side inventory overview for the current self-hosted sync node.", "Dashboard unavailable": "Dashboard unavailable", "Retry": "Retry", "Active components": "Active components", "Tracked SKUs on the server": "Tracked SKUs on the server", "Units on hand": "Units on hand", "Sum of server-side quantities": "Sum of server-side quantities", "Low stock": "Low stock", "Components at or below min_stock": "Components at or below min_stock", "Movements": "Movements", "Recorded inventory transactions": "Recorded inventory transactions", "Recent components": "Recent components", "Latest active component rows visible to the sync service.": "Latest active component rows visible to the sync service.", "No component records are available yet.": "No component records are available yet.", "SKU": "SKU", "Name": "Name", "Category": "Category", "Status": "Status", "Updated": "Updated", "Operational notes": "Operational notes", "Deployment and sync behaviors surfaced for administrators.": "Deployment and sync behaviors surfaced for administrators.",
  "库存核对": "Inventory", "无法确认库存操作开关": "Could not check inventory access", "无法读取库位": "Could not load locations", "重试": "Retry", "料号、名称、分类或库位": "SKU, name, category, or location", "料号、名称、品牌、参数或库位": "SKU, name, brand, parameter, or location", "例如 C30926、连接器或 A-01": "e.g. C30926, connector, or A-01", "库存状态": "Stock status", "全部": "All", "低库存": "Low stock", "库存充足": "In stock", "搜索": "Search", "无法读取库存": "Could not load inventory", "元器件列表": "Components", "正在加载…": "Loading…", "没有符合条件的元器件。请调整搜索词或库存状态。": "No matching components. Change the search or stock filter.", "库存结果": "Inventory results", "分类": "Category", "库位": "Location", "库存 / 最低": "Stock / minimum", "更新时间": "Updated", "料号": "SKU", "名称": "Name", "正在加载库存": "Loading inventory", "上一页": "Previous", "下一页": "Next", "元器件详情": "Component details", "关闭详情": "Close details", "无法读取详情": "Could not load details", "封装": "Package", "元件参数": "Component parameters", "尚未获取明确参数；可在说明中核对原始资料。": "No explicit parameters are available. Check the original description.", "默认库位": "Default location", "库存 / 最低库存": "Stock / minimum stock", "库存模式": "Inventory mode", "独立库位库存": "Per-location inventory", "旧版标量库存": "Legacy total inventory", "说明": "Description", "库位分配": "Location allocations", "未提供独立库位分配": "No per-location allocation", "正在加载详情…": "Loading details…",
  "Sync": "Sync", "Recent stock movement activity and read-only sync posture.": "Recent stock movement activity and read-only sync posture.", "Sync view unavailable": "Sync view unavailable", "Stored stock movement events": "Stored stock movement events", "Components": "Components", "Active synchronized component rows": "Active synchronized component rows", "Current summed quantity on the server": "Current summed quantity on the server", "Recent stock movements": "Recent stock movements", "Latest movement activity received by the sync API.": "Latest movement activity received by the sync API.", "No stock movement activity is available yet.": "No stock movement activity is available yet.", "Component": "Component", "Type": "Type", "Qty": "Qty", "Reason": "Reason", "Happened": "Happened", "Sync posture": "Sync posture", "Current assumptions applied by the server.": "Current assumptions applied by the server.", "Attention items": "Attention items", "Deployment-sensitive checks for the current node.": "Deployment-sensitive checks for the current node.", "Inbound": "Inbound", "Outbound": "Outbound", "Adjustment": "Adjustment", "Transfer": "Transfer",
  "Settings": "Settings", "当前登录使用的 API 令牌": "Current API token", "隐藏": "Hide", "显示": "Show", "打开服务端配置与日志": "Open server setup and logs", "Settings view unavailable": "Settings unavailable", "Runtime configuration": "Runtime configuration", "Access posture": "Access posture", "Next backend additions": "Planned backend additions",
  "当前为只读模式": "Read-only mode", "服务端未启用 Web 库存操作。你仍可搜索、筛选和查看库存详情。": "Server-side Web inventory editing is disabled. You can still search, filter, and view inventory.", "操作未完成": "Action incomplete", "操作完成": "Action completed", "新建元器件": "New component", "收起新建元器件": "Close new component", "创建库位": "Create location", "收起创建库位": "Close create location", "取消": "Cancel", "库位编码": "Location code", "库位名称": "Location name", "例如 A01 或 主仓-01": "e.g. A01 or Main-01", "例如 主货架 A-01": "e.g. Main shelf A-01", "正在创建…": "Creating…", "请先创建一个库位。新元件会以 0 库存建立初始分配。": "Create a location first. The new component starts with zero stock.", "最低库存": "Minimum stock", "初始库位": "Initial location", "说明（可选）": "Description (optional)", "创建元器件": "Create component", "有一笔结果未确认的库存操作": "An inventory action has an uncertain result", "请先恢复上一笔操作。系统会识别这次重试，不会重复增加或扣减库存。你也可以先刷新详情核对最新库存。": "Recover the previous action first. The retry will not duplicate stock changes. You can also refresh details to check current stock.", "恢复上次操作": "Recover previous action", "刷新库存详情": "Refresh stock details", "收起编辑": "Close editor", "编辑资料": "Edit details", "收起入出库": "Close movement", "办理入出库": "Record stock movement", "编辑": "Edit", "正在保存…": "Saving…", "保存资料": "Save details", "库存入出库": "Stock movement", "这是旧版库存记录，入出库会继续沿用当前默认库位。": "This legacy record uses its default location for stock movements.", "操作": "Action", "入库": "Inbound", "出库": "Outbound", "数量": "Quantity", "操作库位": "Movement location", "原因": "Reason", "备注（可选）": "Note (optional)", "总库存预计：": "Expected total: ", "请先处理上次操作": "Resolve previous action first", "确认": "Confirm ", "请选择库位": "Select a location", "库位已创建，可以用于新元件和入库。": "Location created. It can be used for new components and inbound stock.", "元器件资料已保存。": "Component details saved.", "请先恢复并确认上一笔库存操作，再发起新的入出库。": "Recover and confirm the previous movement before starting another.", "操作失败，请稍后重试。": "Action failed. Please try again.", "库存操作": "Stock movement", "登录已失效，请重新验证 API 令牌。": "Session expired. Verify the API token again.",
  "服务端配置": "Server configuration", "使用当前登录的 API Token 修改部署配置。非空环境变量接管的项目需在部署环境中修改。": "Change deployment settings using the current API token. Values set by environment variables must be changed in the deployment environment.", "无法读取服务端配置": "Could not load server configuration", "新 API Token 至少需要 16 个字符。": "New API token must have at least 16 characters.", "服务端配置已保存，当前浏览器已使用新 Token 登录。请更新其他客户端。": "Server settings saved. This browser uses the new token. Update other clients.", "服务端配置已保存并生效。": "Server settings saved and applied.", "服务端已保存新配置，但浏览器未能确认新 Token 登录。请用新 Token 重新登录后检查配置。": "Settings were saved, but this browser could not confirm the new token. Sign in with the new token and check settings.", "保存配置失败。请读取服务端当前配置后重试。": "Could not save settings. Reload server settings before retrying.", "由 API_TOKEN 环境变量控制": "Controlled by API_TOKEN environment variable", "留空以保留当前令牌": "Leave blank to keep the current token", "可留空；反向代理子路径会保留。此地址用于服务端配置页的管理台入口。": "May be blank. Reverse proxy subpaths are preserved. This URL provides the admin link on the server setup page.", "每行一个 origin，不含路径。独立部署的管理台需把实际浏览器来源填在这里。": "One origin per line, without a path. Enter the browser origin for a separately deployed admin console.", "允许已登录的 Web 管理台修改库存": "Allow signed-in Web inventory edits", "环境变量接管：": "Environment overrides: ", "保存中…": "Saving…", "保存服务端配置": "Save server settings",
  "MQTT 运行状态": "MQTT runtime status", "当前服务进程实际使用的连接状态。": "Connection state used by the running server process.", "手动刷新": "Refresh", "运行时启用": "Enabled at runtime", "是": "Yes", "否": "No", "加载中": "Loading", "Broker 连接": "Broker connection", "已连接": "Connected", "未连接": "Disconnected", "待发送事件": "Pending events", "最近成功发布": "Last successful publish", "暂无": "None yet", "运行错误：": "Runtime error: ", "MQTT 连接配置": "MQTT connection settings", "保存的配置会在下次重启服务时生效，保存不会立即连接 Broker。": "Saved settings take effect after the next server restart. Saving does not connect to the broker immediately.", "配置加载失败": "Could not load settings", "启用 MQTT 发布": "Enable MQTT publishing", "Broker 主机": "Broker host", "端口": "Port", "用户名": "Username", "密码": "Password", "留空以保留现有密码": "Leave blank to keep the current password", "未配置": "Not configured", "Topic 前缀": "Topic prefix", "使用 TLS": "Use TLS", "明确清除已保存密码": "Clear saved password", "来源：": "Source: ", "网页已保存配置": "Web-saved settings", "环境变量默认配置": "Environment defaults", "。密码不会显示在页面中。": ". Password is never shown on this page.", "等待服务重启": "Waiting for server restart", "已保存配置与当前运行配置不同，重启 FastAPI 服务后生效。": "Saved settings differ from runtime settings. Restart FastAPI to apply them.", "保存 MQTT 配置": "Save MQTT settings", "保存 MQTT 配置失败。": "Could not save MQTT settings.", "启用 MQTT 时必须填写 Broker 主机。": "Broker host is required when MQTT is enabled.", "端口必须是 1 到 65535 的整数。": "Port must be an integer from 1 to 65535.", "Topic 前缀不能为空，且不能包含 +、# 或控制字符。": "Topic prefix is required and cannot contain +, #, or control characters.", "Client ID 不能为空或包含控制字符。": "Client ID is required and cannot contain control characters.", "请填写域名、IP 或 IPv6 地址，不要填写 URL、路径或用户信息。": "Enter a hostname, IP, or IPv6 address without a URL, path, or user information.",
  "令牌验证失败。": "Token verification failed.", "无法连接 API。": "Could not connect to the API.", "登录会话已失效。": "Session expired.", "无法确认配置是否已保存。请重新登录并读取服务端配置后再决定是否重试。": "Could not confirm whether settings were saved. Sign in and reload server settings before retrying.", "网络请求未能确认结果。请保留当前表单并重试，或刷新库存核对最终状态。": "Network request result is uncertain. Keep the form and retry, or refresh inventory to check the final state.", "Web 库存操作尚未启用。": "Web inventory editing is disabled.", "目标已不存在，请刷新后重试。": "The item no longer exists. Refresh and retry.", "数据已被其他设备更新，请刷新后重新提交。": "Another device updated this item. Refresh and submit again.", "提交内容不完整或格式不正确，请检查表单。": "Check the form for missing or invalid values.", "Unable to load admin data.": "Unable to load admin data.",
  "连接 API 超时，请确认服务器已启动及 API 地址和端口可达。": "The API connection timed out. Check that the server is running and the API address and port are reachable.",
  "无法读取 API。请检查 API 地址、管理台允许来源（CORS），以及 HTTPS 页面是否连接了 HTTP API。": "Cannot read the API. Check the API address, allowed admin origin (CORS), and whether an HTTPS page is connecting to an HTTP API.",
  "API 不支持账户接口。请更新 API 与 Web 两个镜像，或检查是否误填了 Web 管理台地址。": "The API does not support accounts. Update both API and Web images, or check whether the Web console address was entered as the API address.",
  "请求失败，状态码": "Request failed, status ",
  "MQTT 配置已保存。": "MQTT settings saved.",
  "账户管理": "Accounts", "创建用户": "Create user", "普通用户": "User", "管理员": "Administrator", "账户已停用": "Account disabled", "账户已启用": "Account enabled", "启用账户": "Enable account", "停用账户": "Disable account", "重置密钥": "Rotate key", "确认重置密钥": "Confirm key rotation", "取消重置": "Cancel rotation", "重置后旧密钥会立即失效。": "The old key stops working immediately after rotation.", "新密钥仅显示一次，请立即复制并妥善保存。": "The new key is shown once. Copy and store it now.", "复制密钥": "Copy key", "关闭密钥提示": "Dismiss key", "保存名称": "Save name", "账户已创建。": "Account created.", "账户已更新。": "Account updated.", "密钥已重置。": "Key rotated.", "暂无普通用户账户。": "No user accounts yet.", "无法读取账户": "Could not load accounts", "账户操作失败。": "Account action failed.", "请输入用户名。": "Enter a user name.", "当前账户": "Current account", "仅管理员可修改服务端配置和管理账户。": "Only administrators can change server settings and manage accounts.", "此账户的库存由服务端独立保存。": "This account's inventory is stored separately on the server.",
  "当前会话使用账户密钥访问 API。": "This session uses an account key to access the API.", "使用管理员或用户密钥连接自托管同步服务。每个账户拥有独立库存；服务端启用 Web 库存操作后，可以创建库位和元件并办理入出库。": "Connect to the self-hosted sync service with an administrator or user key. Each account has separate inventory. When Web inventory editing is enabled, you can create locations and components and record stock movements.", "管理员可在账户管理中创建用户并获取其密钥。": "Administrators can create users and get their keys in Accounts.",
  "连接地址提示": "Connection address", "通常在 API 地址中填写服务器 IP 和 8787 端口；Web 管理台默认使用 8081 端口。": "Enter the server IP and port 8787 as the API address. The Web admin console uses port 8081 by default.", "验证账户密钥后，加载此账户的库存。": "Verify the account key to load this account's inventory.",
  "正在读取管理会话…": "Loading admin session…",
  "搜索服务器上的有效元器件。服务端开启 Web 库存操作后，可创建库位和元件，并执行可靠的入出库。": "Search active components on the server. When Web inventory editing is enabled, you can create locations and components and record stock movements.",
  "连接自托管 FastAPI 同步服务，使用与同步 API 相同的 Bearer 令牌核对库存与配置 MQTT。库存默认只读；服务端启用 Web 库存操作后，可创建库位、元件并办理入出库。": "Connect to the self-hosted FastAPI sync service with the same bearer token used by the sync API. Inventory is read-only by default; server settings can enable locations, components, and stock movements.",
  "开发时可在": "For development, run the API at ", "运行 API，并在": " and the admin console at ", "运行管理台。": ".",
  "进入管理台前，将通过": "Before entering, your token will be checked through ", "验证令牌。": ".",
  "API Token 由部署者设置。可在服务端配置页显示与复制当前令牌；也可查看 API 容器的": "The deployer sets the API token. Show or copy it on the server setup page, or check the API container's ", "环境变量、部署目录的": " environment variable, the deployment directory's ", "，或映射目录中": ", or the mapped directory's ", "的": " ", "。它不是": ". It is not ", "或": " or ", "。非部署者请联系管理员。": ". Contact the administrator if you do not manage the deployment.",
  "这是浏览器当前会话持有的值。服务端令牌来自": "This is the value held by the current browser session. The server token comes from the ", "环境变量或数据目录的": " environment variable or the data directory's ", "。配置页提供文件位置、有效配置和最近运行日志。": ". The setup page shows file locations, effective settings, and recent logs.",
  "Runtime configuration and access posture read from the same FastAPI process.": "Runtime configuration and access posture read from the same FastAPI process.", "Effective process settings that drive this sync service.": "Effective process settings that drive this sync service.", "Operational URLs and deployment-sensitive settings.": "Operational URLs and deployment-sensitive settings.", "Follow-up capabilities intentionally left outside this first separated admin release.": "Follow-up capabilities intentionally left outside this first separated admin release.",
  "服务端配置页": "Server setup page",
  "新 API Token": "New API token", "Web 管理台实际访问地址": "Admin console URL", "允许访问 API 的管理台来源": "Allowed admin origins",
  "浏览器无法保存恢复信息，请保持本页打开并使用原表单重试。": "The browser could not save recovery data. Keep this page open and retry with the same form.", "共": "Total ", " 项": " items", "第": "Page ", " 页": "", "已创建": "Created", "，初始库存为 0。": " with initial stock of 0.", "完成，当前库存": " complete. Current stock: ", "已通过原请求编号核对操作结果，当前库存": "Checked the original request result. Current stock: ", "。": ".",
  "本页已选：": "Selected on this page: ", "仅选择当前页；翻页或更改筛选会清空选择。": "Selection applies to this page only and clears when changing page or filters.",
  "选择本页": "Select this page", "取消本页选择": "Deselect this page", "清空选择": "Clear selection", "选择元器件": "Select component", "删除所选": "Delete selected", "恢复批量删除": "Recover bulk deletion", "重试原批次": "Retry original batch",
  "确认批量删除": "Confirm bulk deletion", "即将从当前账户删除所选元器件：": "Components to delete from this account: ", "删除会同步到客户端，历史流水仍会保留。本批次全部成功或全部取消。": "Deletion syncs to clients and preserves movement history. The whole batch succeeds or is rolled back.",
  "批量删除未完成": "Bulk deletion did not finish", "批量删除已完成": "Bulk deletion complete", "已删除所选元器件：": "Selected components deleted: ", "请重试原批次以确认结果；重试不会重复删除。": "Retry the original batch to confirm the result; retries do not repeat deletion.", "本次请求未执行删除。请关闭确认框，刷新库存后重新选择。": "This request performed no deletion. Close this dialog, refresh inventory and select again.",
  "删除元器件": "Delete component", "确认删除": "Confirm deletion", "正在删除…": "Deleting…", "删除后，此元件将从当前账户的库存列表中移除，并同步到客户端。历史流水仍会保留。": "This component will be removed from this account's inventory and synced to clients. Movement history will remain.",
  "补全旧元件参数": "Complete legacy component parameters", "预览候选": "Preview candidates", "收起预览": "Hide preview", "仅从官方资料补全已有元件的描述与参数；名称、库存和库位保持不变。范围限当前账户。": "Complete existing descriptions and parameters using official data only. Names, stock and locations stay unchanged. Scope is the current account.",
  "请先在服务端设置中启用 Web 库存操作，再启动参数补全。": "Enable Web inventory editing in server settings before starting parameter completion.", "正在预览候选…": "Loading candidates…", "无法预览候选": "Could not preview candidates", "当前账户待补全": "Candidates in this account: ", "以下显示前 50 项；确认后处理全部候选。": "Preview shows the first 50 items; confirmation processes all candidates.", "正在启动…": "Starting…", "确认补全全部候选": "Confirm completion for all candidates", "暂无需要补全的旧元件。": "No legacy components need parameter completion.", "重新预览": "Refresh preview",
  "补全已完成": "Completion finished", "补全已取消": "Completion cancelled", "正在补全参数…": "Completing parameters…", "补全进度": "Completion progress", "已处理": "Processed ", "已更新": "Updated ", "已跳过": "Skipped ", "失败": "Failed ", "未提供失败原因": "No failure reason provided", "还有其他结果未在此处展开。": "Additional results are omitted here.", "取消任务": "Cancel job", "重试失败项": "Retry failed items", "补全操作未完成": "Completion action did not finish", "补全操作未完成。": "Completion action did not finish.", "无法读取补全进度。": "Could not read completion progress.", "原任务已不可查询；已完成的更新仍会保留。请重新预览候选。": "The previous job is unavailable. Completed updates remain saved; preview candidates again.",
  "启动结果未确认。稍后重试；若已有任务仍在运行，服务端会拒绝重复启动。": "The start result is uncertain. Retry later; the server rejects another start while a job is running.", "当前账户已有参数补全任务在运行。请稍后刷新候选。": "A parameter completion job is already running for this account. Refresh candidates later.",
  "以下显示前 5 个 SKU；确认后处理全部候选。": "The first five SKUs are shown; confirmation processes all candidates.", "这里只显示最多 3 条失败原因；重试会处理全部失败及未处理项。": "Up to three failure reasons are shown; retry includes all failed and unprocessed items.", "重试失败及未处理项": "Retry failed and unprocessed items", "收起结果": "Dismiss result",
  "启动结果未确认，且当前没有可查询的运行中任务。请重新预览剩余候选。": "The start result is uncertain and no running job is available. Preview the remaining candidates again.", "当前账户已有任务但暂未找回进度。请稍后重试查询。": "A job exists for this account, but its progress could not be recovered yet. Retry the lookup later.",

  "会话有效": "Session active",
  "退出失败，请重试。": "Logout failed. Please retry. ",
  "无法建立登录会话，请检查 API 地址与允许来源。": "Unable to establish a session. Check the API address and allowed origins.",
  "服务端不支持会话登录，请更新 API 镜像。": "The API does not support session login. Update the API image.",
  "无法建立登录会话。": "Unable to establish a session.",
  "后端会话认证": "Server session authentication",
  "账户密钥仅用于登录。浏览器通过 HttpOnly 会话 Cookie 访问服务端，不保存原始密钥。": "The account key is used only to sign in. The browser uses an HttpOnly session cookie and does not retain the original key.",
  "管理员密钥可在部署环境的 API_TOKEN 或数据目录 config.json 中查看；普通用户密钥由管理员创建或重置后发放。": "Find the administrator key in API_TOKEN or config.json in the data directory. User keys are issued or reset by the administrator.",
  "如需在其他设备登录，请使用管理员发放的账户密钥。": "Use the account key issued by your administrator to sign in on another device.",
  "服务端更新": "Server update", "当前运行：": "Currently running: ", "开发渠道": "Development channel", "正式渠道": "Stable channel", "未知": "Unknown",
  "目标版本必须支持网页更新；若不支持，会取消切换并保留当前部署。": "The target version must support web updates. Otherwise the switch is cancelled and the current deployment is retained.",
  "目标版本尚不支持网页更新，为保留更新入口已取消切换；请等待支持此功能的正式版。": "The target version does not support web updates. The switch was cancelled to preserve the update controls; wait for a stable release that supports this feature.",
  "正在重新连接更新服务；已知任务会保留。更新重启期间请稍候。": "Reconnecting to the updater. Known jobs are retained; please wait while services restart.",
  "此部署尚未启用网页更新。请在 Container Manager 或 Docker GUI 中按教程一次性添加 updater 服务，并为 API 配置 UPDATER_URL。": "Web updates are not enabled for this deployment. Follow the guide to add the updater service once in Container Manager or your Docker GUI and configure UPDATER_URL for the API.",
  "更新服务配置完成后，即可在这里切换正式或开发渠道并更新 API 与 Web。源码或系统服务部署仍可使用上方版本检查。": "After setup, switch between stable and development channels here and update the API and Web. Source or system-service deployments can still check versions above.",
  "配置网页更新教程": "Web updater setup guide", "目标更新渠道": "Target update channel", "按所选渠道更新本部署的 API 和 Web。开发渠道包含预发布；切回正式渠道可能降级，不能代替数据回滚。": "Update this deployment's API and Web using the selected channel. Development includes prereleases; switching to stable may downgrade and does not restore data.",
  "更新到所选渠道": "Update to selected channel", "确认上次更新请求": "Confirm previous update request", "重试失败更新": "Retry failed update", "重新加载管理台": "Reload admin console", "正在读取更新能力…": "Loading update capability…",
  "更新任务：": "Update job: ", "等待更新": "Queued", "正在更新": "Updating", "更新成功": "Update succeeded", "更新失败": "Update failed", "目标版本：": "Target version: ", "刷新更新状态": "Refresh update status", "确认服务端更新": "Confirm server update", "正在提交…": "Submitting…", "确认更新": "Confirm update",
  "准备更新": "Preparing update", "拉取镜像": "Pulling images", "验证版本": "Validating versions", "重建服务": "Recreating services", "健康检查": "Health checks", "更新完成": "Update complete",
  "正在等待开始更新。": "Waiting to start the update.", "已安排重试，正在等待更新。": "Retry queued; waiting to update.", "正在恢复中断的更新。": "Recovering an interrupted update.", "正在检查当前部署。": "Checking the current deployment.",
  "正在拉取所选渠道的镜像。": "Pulling images for the selected channel.", "正在验证 API 与 Web 的版本是否一致。": "Checking that API and Web release versions match.", "正在重建 API 与 Web 服务。": "Recreating the API and Web services.", "正在等待更新后的服务通过健康检查。": "Waiting for updated services to pass health checks.", "服务已运行所选版本。": "Services are running the selected version.",
  "正在执行更新任务。": "The update task is in progress.", "更新未完成，请查看详情后重试。": "The update did not complete. Review the details and retry.", "技术详情": "Technical details",
  "请先备份完整 /data。更新会重建 API 和 Web，并可能短暂断线；数据迁移后的降级不保证兼容，也不会自动回滚。": "Back up all of /data first. Updates recreate the API and Web and may briefly disconnect. Downgrading after data migrations may be incompatible; no automatic rollback is performed.",
  "更新请求暂未确认。请查询状态或重试原请求；更新期间 API 可能短暂断线。": "The update request is not confirmed yet. Check status or retry the original request; the API may briefly disconnect during updates.",
  "关于服务端": "About the server",
  "作者": "Author",
  "版本": "Version",
  "部署方式": "Deployment",
  "Docker / 容器": "Docker / container",
  "源码或系统服务": "Source or system service",
  "检查正式版更新。容器可使用 Watchtower 更新；其他部署按文档操作。更新前请备份数据目录。": "Check for stable releases. Containers can use Watchtower; see the guide for other deployments. Back up the data directory before updating.",
  "无法检查更新，请稍后重试或打开发布页面。": "Unable to check for updates. Try later or open the releases page.",
  "正在检查…": "Checking…",
  "检查更新": "Check for updates",
  "发布记录": "Releases",
  "部署与更新教程": "Deployment and update guide",
  "重新读取": "Reload",
  "源码构建无法自动比较版本。最新正式版：": "Source build version cannot be compared. Latest stable release: ",
  "发现新版本：": "Update available: ",
  "当前版本不低于最新正式版：": "Current version is at least the latest stable release: ",
  "删除账户": "Delete account",
  "这会永久删除该账户及其服务端库存、流水和同步记录，无法撤销。客户端本地数据不会被远程删除。": "This permanently deletes the account and its server inventory, movements and sync history. Local client data is not remotely deleted.",
  "输入账户名称确认：": "Type the account name to confirm: ",
  "账户和服务端数据已删除。": "Account and server data deleted.",
  "永久删除": "Permanently delete",
  "设备与同步审计": "Devices and sync audit",
  "仅显示当前账户的数据。从此版本开始记录，旧客户端未提供设备标识的拉取会显示为未知设备。": "Only this account is shown. Recording begins with this version; pulls from older clients without a device ID appear as unknown.",
  "同步视图": "Sync view",
  "设备注册表": "Device registry",
  "同步日志": "Sync log",
  "冲突历史": "Conflict history",
  "设备": "Device",
  "首次同步": "First sync",
  "最近同步": "Last sync",
  "上传 / 拉取次数": "Push / pull count",
  "显示最近 100 条同步记录。": "Showing the 100 most recent sync entries.",
  "时间": "Time",
  "方向": "Direction",
  "接收元件 / 流水": "Accepted parts / movements",
  "同步游标": "Sync cursor",
  "未知设备": "Unknown device",
  "上传": "Push",
  "拉取": "Pull",
  "显示最近 100 次被拒绝的冲突写入。这里保存处理备注，客户端仍保留待同步的改动。": "Showing the 100 most recent rejected conflicting writes. This page records resolutions. Pending changes remain on the client.",
  "记录保留服务端版本": "Record that the server version was kept",
  "提交失败": "Submission failed",
  "已记录处理": "Resolution recorded",
  "待处理": "Needs review",
  "服务端保留了当前版本，传入数据未覆盖。": "The server kept its current version. Incoming data was not applied.",
  "服务端版本": "Server version",
  "传入版本": "Incoming version",
  "确认保留服务端": "Keep server version",
  "已在客户端重新提交": "Resubmitted from client",
  "处理备注": "Resolution note",
  "记录：已在客户端重新提交": "Record: resubmitted from client",
  "正在读取…": "Loading…",
  "刷新": "Refresh",
  "暂无记录": "No records yet",
  "结果": "Result", "成功": "Success", "冲突": "Conflict", "拒绝": "Rejected", "服务端数据来源：": "Server data source: ",
} as const;

const zh: Partial<Record<keyof typeof en, string>> = {
  "Dashboard": "概览", "Server-side inventory overview for the current self-hosted sync node.": "当前自托管同步节点的服务端库存概览。", "Dashboard unavailable": "无法读取概览", "Retry": "重试", "Active components": "有效元器件", "Tracked SKUs on the server": "服务端跟踪的料号", "Units on hand": "现有库存", "Sum of server-side quantities": "服务端库存总量", "Low stock": "低库存", "Components at or below min_stock": "库存不高于最低值的元件", "Movements": "库存变动", "Recorded inventory transactions": "已记录的库存操作", "Recent components": "最近元件", "Latest active component rows visible to the sync service.": "同步服务可见的最新有效元件。", "No component records are available yet.": "尚无元器件记录。", "SKU": "料号", "Name": "名称", "Category": "分类", "Status": "状态", "Updated": "更新时间", "Operational notes": "运行说明", "Deployment and sync behaviors surfaced for administrators.": "供管理员查看的部署与同步行为。",
  "Sync": "同步", "Recent stock movement activity and read-only sync posture.": "最近库存变动与同步状态。", "Sync view unavailable": "无法读取同步页面", "Stored stock movement events": "已保存的库存变动记录", "Components": "元器件", "Active synchronized component rows": "已同步的有效元件", "Current summed quantity on the server": "当前服务端库存总量", "Recent stock movements": "最近库存变动", "Latest movement activity received by the sync API.": "同步 API 最近接收的变动。", "No stock movement activity is available yet.": "尚无库存变动记录。", "Component": "元器件", "Type": "类型", "Qty": "数量", "Reason": "原因", "Happened": "发生时间", "Sync posture": "同步状态", "Current assumptions applied by the server.": "当前服务端采用的同步规则。", "Attention items": "注意事项", "Deployment-sensitive checks for the current node.": "当前节点需要关注的部署检查。", "Inbound": "入库", "Outbound": "出库", "Adjustment": "调整", "Transfer": "转移",
  "Settings": "设置", "Settings view unavailable": "无法读取设置页面", "Runtime configuration": "运行配置", "Access posture": "访问配置", "Next backend additions": "后续后端功能", "Runtime configuration and access posture read from the same FastAPI process.": "从同一 FastAPI 进程读取运行配置与访问状态。", "Effective process settings that drive this sync service.": "当前同步服务实际使用的进程配置。", "Operational URLs and deployment-sensitive settings.": "运行地址及部署相关配置。", "Follow-up capabilities intentionally left outside this first separated admin release.": "首版独立管理台暂未包含的后续能力。", "Unable to load admin data.": "无法读取管理数据。",
};

export type MessageKey = keyof typeof en;
export type Translator = (key: MessageKey) => string;

interface I18nContextValue { preference: LanguagePreference; locale: Locale; setPreference: (value: LanguagePreference) => void; t: Translator }
const I18nContext = createContext<I18nContextValue | null>(null);

export function I18nProvider({ children }: PropsWithChildren) {
  const [preference, setPreference] = useState<LanguagePreference>(readPreference);
  const locale = resolveLocale(preference, navigator.language);
  useEffect(() => {
    document.documentElement.lang = locale;
    document.title = locale === "zh-CN" ? "Component Vault 管理台" : "Component Vault Admin";
  }, [locale]);
  const value = useMemo<I18nContextValue>(() => ({
    preference, locale,
    setPreference(next) {
      setPreference(next);
      saveLanguagePreferenceFrom(() => localStorage, next);
    },
    t: (key) => locale === "en" ? en[key] : zh[key] ?? key,
  }), [locale, preference]);
  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n() {
  const value = useContext(I18nContext);
  if (!value) throw new Error("useI18n must be used within I18nProvider");
  return value;
}

export function currentTranslation(key: MessageKey): string {
  return resolveLocale(readPreference(), navigator.language) === "en" ? en[key] : zh[key] ?? key;
}

export function formatCount(value: number, locale: Locale): string { return new Intl.NumberFormat(locale).format(value); }

// Only translate known server-authored UI metadata. Inventory names, SKUs, reasons and paths remain unchanged.
const serverZh: Record<string, string> = {
  "Device activity, push and pull audit, and detected conflicts are available on the Sync page.": "可在同步页面查看设备活动、上传与拉取审计，以及已检测到的冲突。",
  "Managed inventory checks base_updated_at; legacy snapshots use last-write-wins on updated_at": "库位库存检查 base_updated_at；旧版快照按 updated_at 最后写入胜出",
  "Managed inventory checks base_updated_at; legacy snapshots use last-write-wins": "库位库存检查 base_updated_at；旧版快照采用最后写入胜出",
  "Available with per-device push and pull activity": "已提供设备上传和拉取活动",
  "Device activity, sync audit, and detected conflicts are available on the Sync page.": "可在同步页面查看设备活动、同步审计与已检测到的冲突。",
  "The administrator account cannot be deleted.": "不能删除管理员账户。",
  "Account database is busy; retry deletion.": "账户数据库正在使用中；账户已停用，请稍后重试删除。",

  "Database file has not been created yet.": "数据库文件尚未创建。",
  "Run the API once or push data from a client to populate records.": "运行 API 或从客户端推送数据以创建记录。",
  "Managed inventory uses base_updated_at conflict checks; legacy snapshots use last-write-wins.": "库位库存使用 base_updated_at 检查冲突；旧版快照采用最后写入胜出。",
  "Device registry is not implemented yet; sync visibility is derived from server-side inventory state.": "设备注册表尚未实现；同步状态由服务端库存决定。",
  "API token is still the default value. Replace it before deployment.": "API Token 仍为默认值，请在部署前更换。",
  "Custom API token is configured for authenticated routes.": "已为需认证的接口配置自定义 API Token。",
  "Authenticated web inventory writes are enabled.": "已启用经过认证的 Web 库存写入。",
  "Web inventory writes are disabled; clients own inventory writes.": "Web 库存写入已禁用；由客户端写入库存。",
  "Sync visibility is derived from the latest accepted server-side rows.": "同步状态来自服务端最近接受的记录。",
  "Conflict mode": "冲突模式", "Last write wins": "最后写入胜出", "Soft delete": "软删除", "Enabled": "已启用", "Disabled": "已禁用", "Device registry": "设备注册表", "Not implemented": "尚未实现", "Authenticated routes": "需认证的接口",
  "Server version": "服务端版本", "Build revision": "构建提交",
  "App name": "应用名称", "Host": "主机", "Port": "端口", "Database path": "数据库路径", "Admin web origins": "管理台来源", "API token status": "API Token 状态", "Default token in use": "使用默认令牌", "Custom token configured": "已配置自定义令牌", "LCSC lookup status": "立创查询状态", "Configured": "已配置", "Not configured": "未配置", "Recognition rules remote URL": "远程识别规则地址", "Web fallback resolvers": "Web 备用解析器", "Health URL": "健康检查地址", "Auth URL": "认证地址", "Admin API": "管理 API", "Sync API": "同步 API", "Token risk": "令牌状态", "Replace before deployment": "部署前更换", "Custom token present": "已配置自定义令牌",
  "Per-device sync audit log": "按设备记录的同步审计日志", "Conflict history and resolution records": "冲突历史与解决记录", "Backend session auth for the web admin": "Web 管理台后端会话认证",
  "active": "有效", "inactive": "停用", "deleted": "已删除", "Low stock": "低库存", "Healthy": "库存充足",
  "Account name is required.": "请输入账户名称。", "Account name already exists.": "账户名称已存在。", "Account not found.": "账户不存在。", "Specify name or active.": "请输入名称或启用状态。",
  "Administrator account required.": "需要管理员账户。",
};

export function translateServerText(value: string, locale: Locale): string {
  if (locale !== "zh-CN") return value;
  const health = /^API health endpoint remains available at (.+)\.$/.exec(value);
  if (health) return `API 健康检查地址：${health[1]}。`;
  return serverZh[value] ?? value;
}

export function currentServerTranslation(value: string): string {
  return translateServerText(value, resolveLocale(readPreference(), navigator.language));
}
