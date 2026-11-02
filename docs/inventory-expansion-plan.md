# 国内目录、多库位与备份迁移实施记录

> 历史实施记录：保留同步字段、Excel 合同及当时验证边界，不作为当前查询来源或发布状态说明。
> 当前操作见[库位与备份](storage-and-backup.md)，当前查询与模块约定见[架构说明](architecture.md)。

2026-09-15。用户已授权 Android、Windows 同时实施；提交 GitHub 后由 CI 构建。
汉印 M1 蓝牙打印明确排到最后，本阶段不声明设备兼容。

## 实施顺序

1. 国内立创中文查询、关键词候选、参数详情；精确 C 编号失败可回退国际公开页。
2. 独立库位、一料多库位、部分/全部转移；迁移旧库存并同步服务端。
3. Excel 备份恢复、LCSC_android_erp schemaVersion=1 迁移；预览后整批写入。
4. 定向回归、文档和更新日志、GitHub CI / Release。
5. 后续：M1 协议与真机打印验证。

## 数据约定

- 元器件有效 SKU 保持唯一。库存分配按元器件和库位唯一，分配数量非负，其和等于元器件总量。
- 库位编码作为稳定 ID（不可修改），名称允许修改；空库位也能独立存在。原 location 值迁移为编码。
- 转移必须在本地事务内完成，两端与流水同时写入；总量不变、不增加已出库数量。
- 元器件同步包含整个分配快照，不能分别 LWW 合并多个库位而破坏总量。
- 多库位协议使用 inventory_protocol=1；客户端先探测服务端能力，旧服务端不能静默丢弃新字段。
- 组件 base_updated_at 表示客户端最后确认的服务端版本；多库位写入以此检查并发，冲突保留本地队列并明确报错，禁止静默覆盖。
- 库位元数据可继续 LWW。库存总量和分配快照以组件为单位检查并发。

### 同步字段（实施契约）

- GET /health 和 POST /auth/ping 返回 inventory_protocol: 1；新客户端每次同步前探测，不支持则停止同步并保留队列。
- PushRequest 添加 inventory_protocol: 1、storage_locations 数组。
- storage_locations 元素为 {id, name, updated_at, deleted}；id 即稳定库位编码，最长 120，name 最长 200。
- ComponentPayload 添加 allocations: [{location_id, quantity}] 与 base_updated_at: string|null。allocations 为完整快照，至少一项（允许零库存）。旧客户端省略 allocations。
- 旧客户端不能覆盖已启用 allocations 的组件。新组件 base_updated_at=null；旧本地库升级时暂以组件 updated_at 作为基线，首次同步版本不符须明确冲突。
- 相同内容重试可幂等成功，其他更新需 base_updated_at 与服务端当前 updated_at 时间相等。请求中同 ID 不得重复。
- PullResponse 添加 inventory_protocol: 1、storage_locations（本次事务快照中的全量库位元数据，含 tombstone）；components 保留增量游标并携带 allocations。库位与元器件必须同一数据库快照读取。
- 收到远端组件后保存其 updated_at 作为新基线；推送成功后有新的本地编辑仍须保留队列，但更新已推送版本的基线。未推送的本地变更不能被 pull 覆盖。
- StockMovementPayload 添加 location_id 与 destination_location_id（可空），movement_type 扩展 transfer。转移量为正、两个库位不同，不参与消耗统计。常规出入库带 location_id；旧记录允许为空。
- 两端使用独立 storage_locations 与 component_allocations 表；组件仍保存 quantity 供现有列表/统计，任何分配写入与总量更新在同一事务。

## 迁移与恢复边界

- 原始数据库升级前在应用私有目录保存一致快照；不在真实库存上试验迁移。
- 本项目 Excel 备份有明确格式标识，包含元器件、库位、分配与流水；不包含服务器凭据。
- 恢复/迁入先预览：严格整数、外键、重复 SKU/库位、分配合计；默认不覆盖已有元器件。
- LCSC_android_erp 文件：meta、storage_locations、components、inventory_items；来源无历史流水，生成明确的初始库存记录，不能伪造历史。
- 保留源字段、可用内嵌商品图片；导出不得通过联网补全修改库存。图片资源写入和数据库导入须有失败清理。
- 数据库升级后的回退依赖升级前备份；不可用旧二进制直接写入新格式库存。

### 双端 Excel 合同

工作表顺序为 meta、components、storage_locations、allocations、stock_movements。
meta 无表头，以 key/value 两列记录 format=component-vault、schemaVersion=1、exportedAt=UTC ISO。

- components: id,sku,name,category,package_name,location,description,quantity,min_stock,updated_at,deleted,base_updated_at,image_preview
- storage_locations: id,name,updated_at,deleted
- allocations: component_id,location_id,quantity
- stock_movements: id,component_id,movement_type,quantity,reason,note,happened_at,updated_at,deleted,location_id,destination_location_id

ID/SKU/库位编码为字符串，数量为严格整数，deleted 为 Excel Boolean，null 留空。
内嵌图片位于 components 对应行 image_preview 列，one-cell anchor，PNG/JPEG，每组件最多一张。
LCSC_android_erp 迁移使用其原始 camelCase 表头、epoch millis 时间，不与本项目格式混淆。

## 状态

- [代码已完成] 双端国内目录、关键词与参数界面；结构变化/WAF 与真正空结果分别处理。
- [代码已完成] 多库位、转移、BOM 分配预览及协议 1；核对并修复上传期间本地修改的基线保护。
- [代码已完成] 双端 Excel 备份、迁移、嵌入图片持久化与再导出；恢复默认为新记录合并。
- [本地验证] 服务端 51 项相关测试通过；Windows CoreTests 59 项通过。Android 未进行本地 Gradle 构建，按用户要求交由 CI。
- [CI 已通过] 提交 92e0b83 的 [CI 34960705646](https://github.com/Rafael-ban/Component_Manager/actions/runs/34960705646)：version-check、server-test、admin-web-build、android-check、windows-build 全部成功。覆盖 Android assembleDebug 与定向单测、Windows CoreTests / MSIX / portable 发布构建。
- [已发布] 0.4.0 的 [Release 工作流](https://github.com/Rafael-ban/Component_Manager/actions/runs/34961742716) 已成功构建并发布 Android、Windows 和管理端产物。
- [互通补丁] 0.4.1 统一备份容量与导出自检，并补充 Excel 整数小数形式、科学计数法兼容；Windows CoreTests 61 项通过，补丁 CI 与发布状态以 GitHub 为准。
- [验证边界] 未使用真实库存进行恢复，没有手机相机、设备安装或原生界面真机验收；汉印 M1 蓝牙打印依用户要求留到最后。

公开商城可能返回验证页，普通访问失败时给出浏览器入口，不伪造数据，不复制验证 Cookie 算法。

参考来源：[LCSC_android_erp](https://github.com/BrokenClient/LCSC_android_erp/tree/7a40b842b8dfa94fd2168c38a9ddcc3411997b83)。
迁移契约依据该提交的 InventoryBackupManager，保留 source code 映射与历史缺失边界。
