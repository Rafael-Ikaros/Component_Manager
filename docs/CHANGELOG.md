# Changelog

发布渠道以最新具体版本标题为准；`-dev.N` 为预发布，不触发正式版版本递增。历史与详细测试说明：
[0.7.7-dev.1：库存搜索与标签编辑交互重做](releases/0.7.7-dev.1.md)、
[0.7.6-dev.1：统一标签编辑与蓝牙打印](releases/0.7.6-dev.1.md)、
[0.7.4-dev.8：打印预览生命周期修复](releases/0.7.4-dev.8.md)、
[0.7.4-dev.7：Android M1 标签队列与服务端设置入口](releases/0.7.4-dev.7.md)、
[0.7.4-dev.6：M1 实机分帧与处理通知解析](releases/0.7.4-dev.6.md)、
[0.7.4-dev.5：M1 短走纸对照](releases/0.7.4-dev.5.md)、
[0.7.4-dev.4：M1 右对齐修复与多走纸对照测试](releases/0.7.4-dev.4.md)、
[0.7.4-dev.3：M1 连续测试连接与打印后诊断](releases/0.7.4-dev.3.md)、
[0.7.4-dev.2：M1 查询回复与纸张适配](releases/0.7.4-dev.2.md)、
[0.7.4-dev.1：M1 单张打印与更新通道](releases/0.7.4-dev.1.md)。

## [Unreleased]
bump: patch

<!-- Add unreleased notes below this line. -->

## [0.7.8-dev.24] - 2026-10-28

- Android 平板第一阶段：紧凑库存列表、实际可用宽度下的列表详情、自适应详情摘要及操作；设置改为紧凑分组导航与限宽表单。保留手机卡片、业务回调及离线数据。
- 设置草稿和分组滚动保留至状态恢复，库存列表和多选状态可随详情返回恢复；设置系统返回先回分组或库存，不直接退出。未覆盖 BOM 表格、标签三栏等后续阶段。
- 库存筛选与元件行统一滚动，修复平板横屏键盘展开后固定头部占满列表、首行显示不全的问题；搜索和筛选条件保留，不自动提交库存动作。
- Android 16 平板实测横竖屏呈现、搜索及选择保持、详情返回、设置切组草稿、设置键盘操作和四项左滑菜单打开/取消；最终修正候选确认键盘上方完整元件行可滚动并点选。定向回归及 GitHub CI 通过；未提交库存变更、未进行实体打印或完整 Windows 人工回归。
- 最终完整 Excel 备份逐项对比通过：2 元件、26 件、2 库位、3 分配、1 流水与原图字节一致。验收中曾缺失一份照片副本，已从本轮备份恢复同一原图，后续覆盖安装和离线重启正常；原因尚未定位，作为观察项保留，不宣称修复。
- 本版为 dev 预发布，不更新 Docker 稳定标签；平板窗口重排不改变库存 schema 或同步协议。BOM、标签工具和配套件工作区继续按[平板布局提案](design/tablet-ui-proposal.md)分阶段实施。

## [0.7.8-dev.23] - 2026-10-28

- Android：修复宽屏元件编辑、出入库和立创导入共用表单在键盘弹出后顶部操作移出屏幕；对话框按可用高度滚动并遵守宽度上限。
- Android：BOM 与迁移预览的说明、物料及复核操作统一滚动，避免横屏键盘把搜索和物料区挤出屏幕；批量扫码设置内容支持滚动。
- Android：标签模板自定义尺寸的“编辑并预览”避让键盘；配套件数量与复核操作可滚至键盘上方。
- Android 16 实机：保留数据覆盖安装签名候选，元件编辑和手动流水表单横竖屏保存/取消可见；15 行 BOM 搜索及末尾操作横竖屏可滚至键盘上方；自定义纸张尺寸按钮横竖屏可达，配套件横屏数量和复核可达。仅取消本轮测试草稿，未提交库存扣减或打印。
- 完整 Excel 备份逐项对比通过：2 元件、26 件、2 库位、3 分配、1 流水及 1 张原图保持不变。两项直接 UI 回归、候选 CI 与签名构建通过；本轮未覆盖完整客户端 QA、所有导入格式或实体打印。
- 新增[平板布局提案与交互预览](design/tablet-ui-proposal.md)：库存列表详情、BOM 匹配辅助、标签工作区及设置空间重排留待后续 dev，原型不代表已实现的 Android 页面。本版不更新 Docker 稳定标签。

## [0.7.8-dev.22] - 2026-10-28

- Android：修复键盘弹出时设置/库存页面被平移或操作区遮挡；设置底部操作可滚到键盘上方，主导航切换和选择元件结束当前输入。库存筛选按栏宽换行，库位、排序不再只露出一角。
- Android：修复同步流水的空库位被误存为 `null` 文字而导致完整 Excel 备份失败；下一次成功同步收敛已有无效引用，保留库存、流水和原时间，真实同名库位保留，无需清数据。
- 完成 Issue #25 Android 16 实机同步验收：订单更新中文分类、封装与参数后，手机增量拉取一项；两份手机完整备份逐项确认名称、SKU、备注、图片、数量、最低库存、库位分配及历史流水保持，对照元件不变。图片文件 SHA256 前后一致，断开测试服务后重启仍可离线查看。
- 本轮使用隔离账户和合成数据；横竖屏库存、详情、库位菜单、记录与设置键盘操作已检查，4项直接回归测试及候选 CI 通过。未覆盖全部客户端 UI、所有订单格式或实体打印，不更新 Docker 稳定标签。

## [0.7.8-dev.21] - 2026-10-28

- 新增 Web“从订单更新资料”（Issue #25）：上传立创 BIFF `.xls`，精确匹配已有 C 料号、预览差异，选择中文分类、封装和明确参数后更新。保留名称、图片、自由备注、数量和库位，不新建元件、不增加出入库流水。
- 参数更新兼容旧中英文同义键，空值不清除资料；未匹配与重复资料冲突单独显示。逐项短事务、版本冲突检查和原请求幂等重试，成功项独立保留。
- 资料更新通过现有增量同步传递。隔离环境实际浏览器上传/选字段/提交、窄屏显示以及 Windows 生产同步往返已验证；后端6项、前端3项与Web构建通过。手机端本轮未做实机同步操作。
- 新工具需要 API 与 Web 一同升级，无 schema 迁移或新环境变量。dev 不更新 Docker Hub latest；源码部署、使用和限制见[订单资料更新教程](order-metadata-update.md)。

- Windows：合入库存表格与批量入库交互调整、立创订单 `.xls` 导入和 Markdown 更新日志显示；订单入库仍需明确确认。
- 修复订单行修改 C 料号后沿用旧商品资料；查询与人工确认分离，退款、缺订单及冲突警告在重试和草稿恢复后保留。旧草稿与库存数据继续保留。
- 将独立订单导入、范围选择及 Markdown 测试加入 Windows CI 与发布候选检查；修复 README.md 更新日志链接定位。
- Windows 订单复核改动完成定向测试与启动检查，本轮未完成完整桌面人工操作验收；该限制与手机同步验收边界保留在使用说明。

## [0.7.8-dev.20] - 2026-10-22

- 修复GitHub账户改名后检查更新失败：Android、Windows切换至Rafael-Ikaros/Component_Manager，更新发布页与可信下载URL校验，并同步关于、反馈和服务端默认查询地址。手机已实际读取版本及更新日志。旧客户端需手动覆盖安装本版一次，无需卸载或清数据。
- Android、Windows持久化同步运行和阶段结果；库存提交后立即记录成功，后续预设或照片失败、取消、中断保留已完成结果。重启提示中断，运行时可取消，之后使用同一同步入口重试。
- 同步状态区分本机已保存、待同步、正在同步、完成、部分完成、失败、取消和中断；库存、BOM预设与照片分别标注成功时间。连接/账户范围隔离，传输期间的新本地修改继续待下轮处理，不虚报全部完成。
- 修复Android清理下载照片缓存后整体摘要未更新；BOM预设保存、删除成功也立即刷新统一状态。保留照片原图、预设内容、人工匹配与跳过项，不自动扣库存。
- 手机实测BOM阶段结束进程后重启、继续补图、照片阶段取消、缓存状态刷新和预设删除待同步。原应用保留数据覆盖升级，前后Excel逐行确认元件、库位、分配、流水一致；Windows定向测试及WinUI编译通过，本轮未做完整桌面手工验收。
- 库存主列表偶尔为空仍未定位，本轮原库存和隔离库存均未复现。增加仅包含计数与筛选激活形状的反馈诊断；若复现请在关闭应用前导出反馈。不记录搜索词或元件内容，不把观察项写成已修复。
- 本版为dev预发布，不移动Docker稳定标签；中断恢复采用既有幂等交换，不包含后台自动恢复、人工冲突合并或新的生产批次账本。

## [0.7.8-dev.19] - 2026-10-22

- 新增Android、Windows元件原图跨设备同步；Web库存列表和详情可通过登录会话查看。保留已有本地照片、元件资料及离线方式，不需要清库。
- 库存、BOM预设与照片分别记录同步结果；照片失败显示部分完成、失败原因及可重试状态，不把库存成功误报为照片完成。即使没有库存变动，再次同步也可补传或下载照片。
- 设置增加照片可用数、待处理数、上次上传/下载/失败与最近照片完成时间，以及下载缓存清理入口。缓存与原图分离，清缓存保留原图；缺文件保留照片关联。
- 服务端增加账户隔离的原图接口和数据库旁.photos目录，无新增环境变量；删除账户同时删除其照片文件，未引用旧文件按24小时规则淘汰。完整/data备份需包含照片目录，教程已更新。
- Android定向测试修复SDK28数据库helper关闭的类型转换问题。三端各3项直接测试通过；真实Windows→服务端→手机、手机离线替换→Windows/Web往返已验证，图片bytes hash一致，库存和流水未改变。
- GitHub CI和签名候选成功；手机保留数据覆盖安装，升级前后备份逐行核对原元件、库位、分配、流水一致。Windows完成编译和真实传输服务验证，本轮未做完整桌面手工UI或实体打印验收。
- 观察项：独立测试包曾出现重启后主库存列表为空、概览和配套件有库存；诊断包两次重启及原应用签名候选未复现。触发未定位，未作为已修复项。
- 本版仅dev预发布，不更新Docker正式标签；照片互通需要配套新API。使用、缓存和备份边界见[照片同步教程](photo-sync.md)。

## [0.7.8-dev.18] - 2026-10-22

- Android、Windows 统一 BOM 预设格式并通过同账户服务端同步；保留旧预设、来源行、套数、人工匹配、明确未匹配及跳过项，删除也能同步。旧文件迁移前自动备份。
- 新增两端“配套件”：从库存逐项选料或从 BOM 预设带入，调整数量、核对库位扣减计划，明确确认后一次出库。打开、保存、同步预设均不扣库存；同次提交防重复扣减。
- Web 管理台新增只读 BOM 预设列表与详情。库存同步成功而预设同步失败时单独提示并保留本地资料；旧服务端仍可继续同步库存。
- 修复实机发现的重新选料后不易找到搜索入口，以及跨端时间戳精度不同导致预设更新被忽略；编辑已加载预设保留名称和 ID，新建同名歧义不随意覆盖。
- Android 已保留数据覆盖安装签名候选，验证旧预设迁移、跳过项、选料、数量加减、旋转保持和库位复核；独立测试包完成跨端同步与实际扣料。升级前后备份逐行核对原有库存、库位、分配、流水均未变。
- GitHub CI 与签名候选构建成功；Windows 通过编译、直接测试和真实同步服务往返，Web 通过实际浏览器验证。本轮未进行 Windows 桌面手工 UI 或实体打印验收。
- 本版为 dev 预发布，不更新 Docker 正式标签。预设同步需要配套的新服务端；配套草稿仅保留当前会话，照片文件同步仍未实现。使用与备份边界见 [BOM 与配套件教程](bom-and-migration.md#预设跨设备同步与配套件)。

## [0.7.8-dev.17] - 2026-10-22

- 修复 Android 暂存 BOM 返回后仍显示旧库存：恢复预览时重新匹配当前本地库存，保留人工选择、跳过项与生产套数。
- 修复 Android、Windows 元件转移后仍显示旧库位：详情仅展示有库存的分配，零库存时显示主库位；历史分配和同步数据不删除。
- 补齐 dev.16 的 Android 实机验收：BOM 自动识别、取消匹配、跳过、暂存、旋转、复核扣料；采购在途与到货提醒、导出后批量标记；真实相机扫描库位二维码后筛选库存。
- 修复提交 201d861 的 CI 与签名候选构建成功；Android 17 保留数据覆盖安装 dev.17，已复测 BOM 数量自动刷新及旧库位隐藏。清理专用测试数据后为 26 项、810 件，前后备份逐行确认原有库存、库位、分配和流水未变。
- 本版为 dev 预发布，不更新 Docker 正式标签。Windows 展示修复已通过定向测试与 CI，本轮未重新进行 Windows 手工 UI 或实体打印验收。详情见 [Issues 与实机记录](issues-2026-10-22.md)。
## [0.7.8-dev.16] - 2026-10-18

- Android BOM 会话保留旋转中的文件、预览和选择；加入取消匹配、匹配状态提示和退出暂存确认。
- BOM 增加本机在途提醒；缺料 CSV 导出成功后可选择批量标记，导入到货后提示核对，不自动扣料。提醒暂不跨设备同步或纳入 Excel 备份。
- 库位管理可生成标签，库存扫码可识别库位并筛选；多库位分配纳入筛选。出入库和 BOM 确认显示库位名称，失效的上次导入库位自动清理。
- 标签编辑器显示空元素边界，位置面板支持选择元素；编辑面板完全展开，避免坐标字段落在屏幕外。补充 CSV 文件选择 MIME 兼容。
- 代码提交 9a1475d 的 CI 与签名候选构建通过。Android 17 实机保留数据升级到 708016，升级前后为 26 项元件、810 件、29 条流水；空二维码/文字的位置面板、四个尺寸字段、关闭返回和重启已检查。
- 验证边界：BOM、在途提醒、库位扫码尚未逐项实机验收；未做实体打印或生产同步。本版为继续验证用 dev 预发布，不更新 Docker 正式标签。
## [0.7.8-dev.15] - 2026-10-18

- Android 核心模块化：库存写入、同步、供应商识别与学习、Excel 备份恢复、BOM/迁移提交分别独立，Repository 保留原入口，数据库位置、schema、已有数据和事务边界不变。
- 主 ViewModel 分离库存筛选/详情投影、扫码批次校验、备份文件 I/O 和 BOM 解析；应用壳通过独立协调器处理保存与重复料号追加确认，保留导航和草稿恢复行为。
- 同步和本地写入共用状态重载；共享 SQL 操作复用调用者事务，连接测试与同步保留账户绑定检查和串行执行。
- GitHub CI 通过。签名候选包在 Android 17 实机验证保留数据升级、导入保存、数量加号、出入库、编辑库位时全量转移、BOM 匹配扣料、相机入口返回、重启持久化、备份导出和隔离服务器连接测试。
- 清理专用测试元件及库位后，活动库存恢复 26 项、810 件；导出备份逐行确认原有元件、库位、分配和流水未变。未进行生产服务端完整往返同步或实体标签识读，本版不代表全功能 QA。

本版为 dev 重构验证版，不更新 Docker 正式标签。测试边界、已知问题与验收证据见 [当前工作记录](current-work.md)。

## [0.7.8-dev.14] - 2026-10-18

- Windows 核心模块化收尾：将主 ViewModel 中的概览、出入库台账、库存详情摘要和同步调度分离，保留原页面入口、功能和已有数据。
- 同步持久化分为配置/账户绑定、上传快照和结果应用；保留账户检查、LWW、并发编辑保护、队列确认和原事务边界，不需要清理或迁移数据库。
- 连接测试、手动同步和自动同步继续串行；连续编辑合并等待中的自动同步，窗口关闭时取消尚未开始的自动同步计划。
- 出入库表单独立维护，库位显示名称；流水刷新保持选中记录。写入成功但界面刷新失败时明确提示已保存，避免误导重复提交。
- 本地 37 项相关测试和 WinUI 编译通过。发布前使用 GitHub 构建的 Windows ZIP 实际验证新建、入库、出库、概览/流水/详情刷新、同步失败恢复、隔离服务端连接测试及测试数据清理。

本版为 dev 验证版，不更新 Docker 正式标签。原有库存与同步配置保留；实机验收范围、CI 和数据核对结果见 [当前工作记录](current-work.md)。

## [0.7.8-dev.13] - 2026-10-18

- Windows 库存写入继续模块化：按元件、库位、出入库/调拨、批量入库和 BOM/迁移导入拆分职责，保留原事务边界、同步队列及调用接口。
- 修复 Windows 删除元件后无法删除空库位：仅活动元件的正库存阻止库位删除，已删除元件的历史分配与流水继续保留。
- 修复 Windows 同步已删除元件的历史分配时，可能重新激活已删除库位的问题。
- Android 与 Windows 库存列表、详情和筛选等界面显示库位名称；保存和同步仍使用原稳定 ID，不需要清理或迁移原有库存。Windows 支持按库位名称搜索，同名库位筛选项可区分。
- 发布前已使用本次 GitHub 构建的签名 APK 和 Windows 自包含 ZIP 验证直接修改链路：保留数据覆盖升级、库位名称显示、名称筛选、Windows 编辑保存，以及删除测试元件后删除库位。

本版为 dev 重构验证版，不更新 Docker 正式标签。自动化验证覆盖本轮写入与同步删除标记场景；未进行真实服务端往返同步或全功能回归。详细验收和测试数据清理记录见 docs/current-work.md。

## [0.7.8-dev.12] - 2026-10-18

- Windows 库存模块继续拆分：独立库存 ViewModel 管理搜索、分类/库位筛选、排序与选中项，主 ViewModel 保留页面入口和跨模块协调。
- Windows 数据层独立数据库连接/初始化/迁移模块及只读查询模块，保留现有数据库路径、表结构、写入事务和同步协议，不要求清理或重新导入数据。
- Windows 列表刷新保留当前选择；筛选选项重建不再被控件的临时空选择清除，保存后继续沿用筛选状态。
- 修复 Android 编辑元件时横竖屏切换导致草稿显示不同的问题：全屏与对话框共用同一个编辑状态位置。
- 增加库存筛选/选择恢复和 Android 自适应编辑草稿的定向回归；本地 Windows 实际界面已验证新增、编辑、搜索和筛选保存链路，手机已用于复现及对照验证。

本版为 dev 重构验证版，不更新 Docker 正式标签。概览、流水、同步协调及库存写入仍待后续模块化；本次没有进行全功能回归。发布包的实机验证结果记录在 docs/current-work.md。


## [0.7.8-dev.11] - 2026-10-18

- Windows 库存编辑第一阶段模块化：独立编辑对话框、编辑状态和保存服务，保留现有页面功能、SQLite 数据与同步协议。
- 修复已有 SKU 被列表筛选隐藏时无法正确追加入库；商城来源使用明确的 SKU 标识，修改名称、分类或说明不会误改变导入类型。
- 新建库位与元件保存或追加入库同事务提交，失败一并回滚；在事务内拒绝重复活动库位名称，取消追加不留下新库位。
- Windows 保存失败保留编辑输入；已保存但刷新失败时明确提示，避免重复追加。修复保存后 SKU 首尾空格影响选中项，以及连接测试与同步并发时的忙碌状态问题。
- Android 导入页新建库位统一刷新共享库存状态，避免返回后仍显示旧库位列表。
- Android 编辑字段、导入来源及已采纳图片支持系统保存状态恢复；待提交扫码复核输入恢复后仍需确认，不自动修改库存。保存成功或取消后清理；系统返回与编辑器取消使用同一图片清理路径。
- 增加库存保存回滚、编辑来源、保存后刷新失败及 Android 草稿/图片恢复的定向回归测试。

本版为 dev 验证版，未执行真实库存写入或完整设备 UI 验收。Android 恢复不等同于强制停止或设备重启后的长期草稿备份；提交中断后请先核对流水。Docker 正式标签不变。

## [0.7.8-dev.10] - 2026-10-18

- 修复 Android“记录 → 扫描标签”点击闪退：出入库选择弹窗纳入应用语言配置作用域，避免读取按钮文字时抛出 `ComponentVaultStrings not provided`。
- 增加从应用记录页打开、关闭扫码方向选择弹窗的回归测试，保持原有出入库选择及扫码复核流程。

本版为 dev 修复版，不更新 Docker 正式标签。

## [0.7.8-dev.9] - 2026-10-16

- Android“设置 → 关于”支持应用内下载更新 APK，显示进度与已下载大小；支持后台下载、返回页面恢复状态、取消、失败重试和删除安装包。
- 下载完成后可直接打开 Android 系统安装界面；首次使用按需引导授予安装应用权限。保留正式/dev 更新通道及发布页面备用入口。
- 下载仍使用本项目 GitHub Release 附件，无需账户或 API 密钥，不改变本地库存；Windows 更新流程不变。

本版为 dev 预发布，不更新 Docker 正式标签。

## [0.7.8-dev.8] - 2026-10-16

- 修复 Android 立创商城订单 XLS 导入：表尾合并单元格中的“说明”不再被当作缺少订单号或 C 料号的商品，解决真实对账单第 199 行报错。
- 仅跳过不含商品字段的说明行；真正缺少料号的商品仍提示核对，避免漏导入。
- 增加合并说明行回归用例；私有订单样本仅用于本地解析测试，不随源码或发布附件分发。

本版仅修复订单解析，其他需求核对留待后续；保持 dev 预发布，不更新 Docker 正式标签。

## [0.7.8-dev.7] - 2026-10-15

### 库存与导入修复

- Android / Windows 编辑元件主库位时，将该元件全部库存移到目标库位，清零其他库位分配，保留总量；部分转移仍使用指定数量的转移操作。
- Android / Windows 导入与编辑明确区分选择已有库位和新建库位；只显示名称，内部生成稳定码，同名库位提示选择已有。
- Android 分类修正按相同官方分类路径跨 SKU 复用，保留用户/已学习字段的优先级；补充常见英文分类的中文名称。
- 无供应商 SKU 的手动元件允许留空，保存时生成唯一 CV- 内部编号。
- 记录扫码先选入库/出库，后续扫码复核沿用方向；库位显示名称及可用数量，确认前检查该库位库存，不直接扣除包装数量。
- Web 库存详情增加元件删除确认，沿用账户隔离、写入开关、并发版本检查及幂等回执；删除同步到客户端，流水保留。

### Android 新增能力

- 批量嘉立创入库支持商城物料明细对账单 BIFF .xls：读取型号、品牌、分类、封装及订购数量，过滤配送费和汇总，预览确认后累加库存；按订单号和 SKU 在本机跨导入会话防重。原始私有订单文件不随仓库发布。
- 手动元件支持图库选图、拍照预览与保存，本地图优先展示，Excel 备份包含图片；当前服务器不传输图片文件。
- 未匹配 BOM 的 R/C/L 行可按明确的同类型、同封装与电气参数推荐候选，必须人工选择；缺失或不明确的关键参数不推荐，不从型号编码推断。

本版为 dev 预发布，不变更 Docker 正式标签。CI 构建/测试不替代真实设备、纸张或业务数据验收。Windows 本次同步库位数据修复与名称选择界面，订单导入、照片与电气替代推荐仍为 Android 能力。
## [0.7.8-dev.6] - 2026-10-09

- 修复 Android 导入学习映射：保存用户确认的型号形式名称；联网补全保留已学习或手动指定的分类、封装等字段，缺失信息继续正常补充。

- 库存分类、仓位和排序筛选统一限制菜单宽高，长选项最多两行，超出条目在菜单内滚动，避免分类菜单遮满页面。

### 嘉立创包装二维码出库

- Android 的记录扫码支持嘉立创原厂包装二维码，复用已有料号解析并精确匹配本地未删除元件；匹配后进入现有复核页面，可选择出库并修改数量。
- 保留仓库长短二维码支持。嘉立创包装数量不自动成为出库数量，首次扫描仍默认 1；未匹配时提示先入库，不联网创建元件，不直接扣库存。
- 本次扫码与数量边界测试单独执行，便于区分直接功能验证和已有 Android 大组回归；不变更同步协议、数据库或 Docker 正式版标签。

## [0.7.8-dev.5] - 2026-10-08

### Android 数量快捷调整

- dev.4 在 CI 中因 Compose import 编译错误未完成发布；本版修正该问题并重新构建。

- 出入库数量增加减一、加一按钮，覆盖库存快捷操作、手动记录及扫码复核，少量操作无需唤起键盘，仍保留手动输入。
- 按操作类型保留数量下限、库存限制和原有提交校验；按钮处理空输入及整数边界，不改变库存写入和同步流程。
- 继续通过 dev 渠道交付，正式版及 Docker 正式标签不变。

## [0.7.8-dev.3] - 2026-10-08

### 按库位导出标签 Excel

- Android 与 Windows 的标签打印 Excel 导出新增“全部库位／指定库位”选择，支持多选库位及导出行数预览；未选择范围、字段或没有匹配数据时禁用导出。
- 指定库位时按“元件＋库位”分行，数量和二维码使用该库位的库存；全部库位模式保留原来一元件一行的行为。兼容无分配记录的旧库存及未指定库位，不修改库存或完整备份格式。
- 以开发版交付，Docker 正式版标签保持不变。

## [0.7.8-dev.2] - 2026-09-28

### 项目 BOM 预设实机修复

- 修复 Android 从已保存预设返回配置并修改套数后，“匹配库存”被错误禁用的问题。预设无需原 Excel 文件或表头检测结果即可重新匹配。
- 区分“已加载预设”与正在处理的状态提示，列摘要使用“物料信息列”，不再把 `Comment` 说明列称作型号列。
- dev.1 已通过双端 CI；手机使用用户提供的嘉立创 XLSX 识别 25 行、跳过测试点、保存预设并重启恢复成功。此次按实机发现的问题修正，继续以 dev 版本交付验证。

## [0.7.8-dev.1] - 2026-09-28

### 项目 BOM 小版本修复（开发版）

- Android 与 Windows 重做项目 BOM 流程：先选文件及生产套数，再匹配库存，最后复核出库。自动识别优先，列映射改为可展开的选择器，并显示源数据样例。
- 修复嘉立创 EDA BOM 中测试点等无料号、无型号行阻断整份导入的问题。保留原始行，支持手选已有元件或显式跳过、恢复；跳过项不计入扣库，最终确认显示跳过数量。
- 自动识别 `Supplier Part`、`Manufacturer Part`、`Quantity`、`Footprint`、`Comment` 和 `Designator`，避免把原表序号当作型号，把说明当作用量。
- 项目 BOM 可选保存为本地预设，保留清单、项目设置、匹配选择和跳过项；再次使用时重新读取当前库存并创建新生产批次。支持删除预设，不保存旧库存快照，不自动扣库。
- 保留重复需求聚合、缺料检查、事务扣库和批次幂等；本次不变更服务端接口或数据库结构。开发版不移动 Docker 正式版标签，正式小版本以实机复核后的结果为准。

## [0.7.7] - 2026-09-27

### 库存、标签与多用户管理

- 汇总 dev.1–dev.7：库存搜索覆盖型号、品牌与电气参数；参数在基本信息和检索中独立显示，不追加到元件名称。Android 左滑直接提供入库、出库、转移和删除，保留确认与库存约束。
- Web 库存支持旧元件官方参数批量补全，先预览再执行，可取消、重试及清理任务；服务端修改通过正常同步返回原生客户端。
- 管理员在 Web 设置的账户管理中可删除普通用户及其服务端数据库资料。删除需输入账户名，管理员原库受保护；原生客户端本地库存不会远程清空。
- Web 改用 12 小时后端会话，刷新恢复、退出撤销，浏览器不再保存原始 API 密钥。原生客户端账户密钥认证保持不变。
- 同步页新增设备注册表、按设备的上传/拉取审计、冲突双方快照及处理记录。处理记录是人工说明，不自动重放旧库存快照。
- Web 设置增加关于：作者 Rafael-Ikaros、服务端版本、部署类型与 GitHub 正式版检测。取消应用内自更新；部署文档提供可选 Watchtower 标签配置和群晖操作步骤。
- Android 修正详情状态与主导航隔离；分类和仓位可从下拉选择，也可手动填写；补齐 Material 3 明暗主题容器色，统一筛选菜单、下拉框和面板。
- 基本信息增加常见官方参数字段的中文显示；记录页默认隐藏已删除元件流水，提供“包含已删除元件”开关，历史数据保留。
- 标签工具先选六种模板与常用/自定义尺寸，再进入现有编辑器和蓝牙打印流程。新建标签默认 0°，恢复已有任务保留角度；内容编辑、拖动与预览复用同一套编辑器。

### 验证与升级

- [最终实现 CI](https://github.com/Rafael-ban/Component_Manager/actions/runs/36328947035) 六项全部通过。签名 dev.7 已覆盖安装到连接手机，核对明暗主题、编辑下拉、历史开关、标签模板、自定义 35×25 mm、0° 预览、二维码编辑/拖动及返回流程；原有 26 个元件、810 库存和 26 条流水保持不变。
- 本轮没有修改 M1 打印协议，也未重新出纸测试；硬件验证仍限于此前通过的 40×60 mm 间隙纸。Windows 通过 CI 构建，Watchtower 尚未在真实 NAS 上执行自动更新。
- API 与 Web 镜像需配套升级；先备份整个数据目录，再拉取并重建容器。正式发布成功后使用 `latest` / `web-latest`，具体步骤见 [Docker Hub 部署指南](dockerhub.md)。

## [0.7.7-dev.7] - 2026-09-27

### 实机验收候选

- 包含下方 dev.6 的账户、同步审计、Web 会话与 Android 界面改进；修复该候选构建期间发现的 Compose 回调与界面测试问题。
- 补齐 Material 3 明暗主题的容器色，分类、仓位、排序菜单及底部面板不再混用默认紫色。
- 新选纸张使用 0°；从元件详情新建标签也不再继承旧空队列的 90°，保留纸张尺寸与校准偏移。恢复已有任务继续保留原设置。
- [实现 CI](https://github.com/Rafael-ban/Component_Manager/actions/runs/36327571227) 六项检查全部通过。此包用于连接手机覆盖安装验收，不移动 Docker 稳定标签；正式发布仍以实机验收结果为准。

## [0.7.7-dev.6] - 2026-09-27

### 账户、同步与界面验收版

- 管理员可删除普通账户及其服务端数据库资料；保护管理员原库，取消该账户后台补全后清理，繁忙时保留停用状态供重试。
- Web 使用 12 小时 HttpOnly 后端会话，刷新可恢复，退出/密钥轮换撤销；旧浏览器保存的原始密钥清除，原生客户端 Bearer 不变。
- 同步页增加当前账户设备注册表、上传/拉取审计、冲突双方快照与人工处理记录。新原生客户端拉取附设备 ID，旧历史不补造。
- 设置增加服务端关于、作者 Rafael-Ikaros、构建版本与正式版检测。取消应用内自更新，提供可选 Watchtower 配置；默认不自动更新容器。
- Android 修正跨页详情状态隔离、分类/仓位可选可手填、筛选菜单主题与部分官方参数中文；记录页默认隐藏已删元件，开关可查看保留历史。
- 标签工具复用原编辑器，先选六种模板之一与常用/自定义纸张，再编辑预览。新建默认 0°，恢复草稿保留保存角度；M1 走纸协议未改，其他纸张仍需设备验证。
- 本版用于 GitHub CI 与实机验收；尚不作为正式版发布，不移动 Docker 稳定标签。

## [0.7.7-dev.5] - 2026-09-27

### 旧库存参数批量补全

- Web 库存页增加当前账户的旧元件参数补全：先预览，再按 SKU 批量查询，支持进度、取消、失败/未处理项重试和活动任务恢复。默认无需配置立创 API；不会从型号编码猜值。
- 只追加官方描述与参数，名称、数量、库位和流水不变；写入使用现有同步版本，手机与 Windows 正常同步即可取得资料。查询期间元件变化则暂停该条覆盖并列为可重试失败。
- 工具复用 Web 库存写开关与账户隔离，已完成任务可清理；重启后重新预览剩余候选。已有参数行不自动重写。详见[操作教程](parameter-enrichment.md)与[版本说明](releases/0.7.7-dev.5.md)。
- 实现 [CI](https://github.com/Rafael-ban/Component_Manager/actions/runs/36309810660) 全部通过。沿用 dev.4 四按钮左滑；开发版不更新 Docker `latest` / `web-latest`，服务端工具先提供源码构建与对应 Web 附件。

## [0.7.7-dev.4] - 2026-09-27

### 左滑直达操作

- 按用户提供的效果图，Android 库存左滑直接横排展开入库、出库、转移和删除，移除 dev.3 中间的“操作”菜单。点击按钮后在当前列表填写表单或确认，无需进入独立页面。
- 操作按钮保持至少 48 dp 宽，仍保留可见卡片；右滑或点击卡片收起，多选时关闭左滑操作，删除仍需确认。
- [GitHub CI](https://github.com/Rafael-ban/Component_Manager/actions/runs/36308927220) 全部通过，覆盖四入口、表单、触控尺寸与收起行为。沿用 dev.3 参数功能，不修改业务事务或 Docker 稳定标签；详见 [版本说明](releases/0.7.7-dev.4.md)。

## [0.7.7-dev.3] - 2026-09-27

### 元件参数与库存快捷操作

- Android 库存卡片左滑显示“操作”，在当前列表上打开单 SKU 的入库、出库、转移和删除菜单；数量与库位表单使用弹窗，删除继续二次确认，无需进入独立页面。出库限制为所选库位可用库存，提交期间阻止重复操作，失败保留输入。
- 新导入元件默认名称保留品牌和型号，不再把阻值、容量等规格拼进名称；既有名称不批量改写。Android、Windows、Web 基本信息单独展示已取得的电气参数，封装继续独立显示。
- 库存检索加入参数及中英字段别名，支持 `10kΩ`、`100nF`、`耐压50V` 等；兼容单位写在字段名中的资料。没有保存过的参数需重新查询，不从型号编码猜测。
- 服务端官方查询响应补充描述与参数，Android 接收后保留到现有说明字段；不改变库存同步格式与数据库结构。
- 实现提交的 [GitHub CI](https://github.com/Rafael-ban/Component_Manager/actions/runs/36305250193) 全部通过。本次为开发预发布，不替换 Docker `latest` 或正式版；使用与验证边界见 [版本说明](releases/0.7.7-dev.3.md)。

## [0.7.7-dev.2] - 2026-09-27

### 实机交互修正

- Android 库存左滑改为最多展开 80 dp 的删除操作区，避免整张卡片滑出；使用删除图标与危险色，右滑或点卡片收回，删除仍需确认。关闭时不露出底部颜色，多选时关闭滑动删除。
- 搜索结果统计仅显示当前命中数量，修正无结果时仍展示全部分类、库位数量的误导。
- 标签内容面板将“完成”固定在顶部，避免键盘遮挡；空文字、二维码显示本地化名称，不再暴露内部 ID。
- 修复代码的 [GitHub CI](https://github.com/Rafael-ban/Component_Manager/actions/runs/36298916089) 全部通过；dev.1 实机已复现上述问题，dev.2 安装后的验证见 [版本说明](releases/0.7.7-dev.2.md)。不改变打印协议、库存事务或 Docker 稳定标签。

## [0.7.7-dev.1] - 2026-09-27

### 界面修正

- Android 库存搜索改为输入框下即时显示结果，修复展开搜索层遮住列表的问题；支持清空查询和明确的无结果状态。本轮保留已有多字段匹配规则。
- 库存批量操作改为先选择再转移，长转移表单可滚动、复核提交固定可见；左滑显示取消和删除，实际删除仍需确认。Windows 使用原生命令栏和列表多选。
- 标签工具改用两列模板卡片，继续复用元件标签编辑器。编辑页以适屏画布为中心，内容、位置、纸张和元件选择按需展开；打印准备和队列共用原有流程。
- 修复空二维码预览提示，以及小窗口里编辑控件挤掉画布的问题；内容输入限高并可滚动，长元件名不再挤掉纸张入口。

### 发布与验证

- GitHub 自动读取本日志最新具体版本标题：带 `-dev.N` 发布为 prerelease，无后缀发布正式版；只改 `Unreleased` 不发布。提交钩子仅检查版本，不再自动生成稳定版。
- 增加标题识别、稳定版本同步和界面交互回归测试。dev 不推送 Docker 稳定镜像、不替换 `latest`；未修改 M1 传输协议和库存事务。
- 本版本用于实际使用验证，不视为正式版 UI 验收。详见 [0.7.7-dev.1 测试说明](releases/0.7.7-dev.1.md)。

## [0.7.6] - 2026-09-27

<!-- Add unreleased notes below this line. -->

- 开发中：账户删除与数据清理、Web 后端会话、设备注册表、同步审计、冲突历史及处理记录；设置关于和正式版检测。
- 开发中：Android 库存导航、参数中文、筛选主题、分类/仓位建议、已删除元件历史开关；标签模板与选纸流程。
- 部署文档增加可选 Watchtower labels 配置；取消应用内服务端自更新。须 CI 与实机验收后再发布正式版。

### 新增与改进

- Android、Windows 与 Web 库存支持型号片段、品牌、官方参数和多个关键词组合搜索，兼容大小写、常见分隔符及 `µ/u`、`Ω/ohm` 写法；数值不做编辑距离猜测，避免混淆不同规格。
- 嘉立创新导入的电阻、电容、电感优先使用官方型号、主参数和精度组成名称；旧库存从已保存的官方说明显示规格摘要，保留手动名称，两端兼容读取对方保存的参数格式。
- Android 和 Windows 库存支持多选批量转移到另一库位，逐项调整数量、提交前复核，整批校验后事务提交；数量或库存状态变化时提示重新确认，不产生部分转移。
- Android 库存左滑显示删除按钮，点击后仍需确认；批量选择时不启用左滑删除。
- Android 标签工具增加元件、文字、二维码和自由标签入口，复用同一编辑器、蓝牙队列、预览及 PNG/PDF 导出。元件详情保留生成标签快捷入口，支持文字修改、元素拖动与位置尺寸调整；自由二维码可编辑内容，元件二维码保持库存身份一致。
- 自由标签不创建库存元件，打印队列 v3 兼容读取 v1/v2，保留中断后待核对机制。新增入口未改变已实测的 M1 传输和走纸协议。

### 同步与部署诊断

- Android 和 Windows 测试连接同时验证库存协议及账户身份，不在测试阶段绑定账户；区分接口缺失、反向代理返回 HTML、身份字段缺失与本地保存失败，减少笼统的“服务端过老”提示。
- Web API 读取增加可重试的超时反馈，保留 HTTP 状态诊断，并说明网络、跨域及 HTTPS 页面连接 HTTP API 的问题。
- Docker API 镜像写入实际构建版本和提交号，可从健康接口、启动日志与管理员运行信息查看。CI 增加真实容器账户身份检查，保留 API 与 Web 双架构镜像发布。
- 完善群晖与 Compose 更新教程：拉取新镜像后重建 API/Web 容器并保留完整 `/data`；仅重启旧容器不会更新镜像。旧 0.7.5 健康接口没有构建字段，不能据此单独判断其缺少账户能力。

### 验证与范围

- 使用五种真实包装袋对应的公开官方商品参数核对容量、阻值、电感和精度；测试仅保存最小公开商品字段，不包含包装订单号或原始二维码。
- 新增搜索、批量转移、规格回填、连接身份、自由二维码和旧队列兼容回归；Android 构建与相关测试由 GitHub CI 执行。
- 本次标签工具入口与自由标签位于 Android；Windows 继续使用现有标签功能，尚未加入 Windows 蓝牙直连和多品牌打印适配。


## [0.7.5] - 2026-09-25

<!-- Add unreleased notes below this line. -->

- 开发中：账户删除与数据清理、Web 后端会话、设备注册表、同步审计、冲突历史及处理记录；设置关于和正式版检测。
- 开发中：Android 库存导航、参数中文、筛选主题、分类/仓位建议、已删除元件历史开关；标签模板与选纸流程。
- 部署文档增加可选 Watchtower labels 配置；取消应用内服务端自更新。须 CI 与实机验收后再发布正式版。

### 新增

- 服务端支持管理员创建普通账户、分发独立密钥、改名、启停和重置密钥；各账户使用独立 SQLite 库存。首次配置的管理员密钥与原有库存保持不变。
- Android 和 Windows 同步前校验服务器与账户身份，阻止将已绑定的本地库存误上传到其他账户；同一账户轮换密钥和切换内外网地址仍可继续同步。
- Web 管理台按账户显示库存与操作权限，普通用户也可使用服务端已启用的网页库存写入；部署、日志、账户管理和 MQTT 配置仅管理员可用。
- 管理 Web 与首次配置页加入简体中文、English 和跟随系统选项，切换后保留语言偏好；统一导航、设置和操作反馈文案。

### 文档与升级

- README 改为中文入门导航，增加部署、群晖、BOM、批量扫码、备份、打印与多用户教程跳转；清理三份已被现行文档覆盖的旧阶段计划并修复链接。
- 升级时先更新 API 与 Web，再更新原生客户端。备份完整 `/data`，包含管理员数据库、账户注册表和 `users/` 普通用户数据库。
- 原生客户端目前每个安装只维护一个本地账户工作区；本版不提供直接切换多账户工作区。普通账户 MQTT 事件不发布到管理员主题。


## [0.7.4] - 2026-09-25

<!-- Add unreleased notes below this line. -->

- 开发中：账户删除与数据清理、Web 后端会话、设备注册表、同步审计、冲突历史及处理记录；设置关于和正式版检测。
- 开发中：Android 库存导航、参数中文、筛选主题、分类/仓位建议、已删除元件历史开关；标签模板与选纸流程。
- 部署文档增加可选 Watchtower labels 配置；取消应用内服务端自更新。须 CI 与实机验收后再发布正式版。

- 新增 Android 汉印 M1 日常蓝牙标签打印：从元件标签预览或库存批量入口选择元件和份数，支持紧凑二维码、完整二维码、纯文字、纸张尺寸、旋转及整图偏移。实际标签不打印外围边框，预览与发送使用同一份 203 dpi 位图。
- 新增本地持久化打印队列，支持逐张进度、当前张完成后暂停、立即停止，以及核对后重打或跳过。发送前保存检查点，中断后的发送中标签转为待核对，不自动重复打印；打印不改变库存。
- 修复 M1 连续打印跨标签、多出纸与打印后回复误判。根据汉码实机通信按最多 1024 原始字节分帧，分离处理通知与型号回复，普通打印统一使用正常标签结束命令。
- 修复切换标签预览时位图提前回收导致的 Android 闪退，并防止已离开的打印页面覆盖新页面恢复的队列。
- 调整记录页扫码流程：识别一个有效元件后停止相机，进入独立批量复核页面；继续添加需主动再次扫码，返回历史保留待复核内容，确认提交后才修改库存。批量嘉立创入库仍保留连续扫描。
- 增加正式版 / Dev 更新通道选择，正式版保持默认；预发布单独发布，版本号允许从 Dev 覆盖升级到对应正式版。
- 服务端首次配置可保存 Web 管理台地址，复制 Token 后进入管理台；管理台设置页可直接修改服务端 Token、允许来源、管理台地址与 Web 库存写入开关。非空环境变量仍优先，数据库和现有 Token 行为保留。
- 更新 Docker Hub / 群晖部署指南，默认 API 使用 `latest`、Web 使用 `web-latest`，保留 `0.7.4` / `web-0.7.4` 固定标签。开发预发布不推送 Docker 镜像，正式镜像以发布工作流及 Docker Hub 实际 tag 为准。
- 实机验收：Android 15、汉印 M1、40×60 mm 间隙纸的无边框实际元件标签可正常扫描；该测试设备水平向右 0.5 mm 校准后，单张和自动连续两张均完整、位置一致、无额外空白纸。此校准值不作为其他设备的默认值；其他品牌、Windows 蓝牙及浓度/纸张学习等设备设置仍待独立适配。


## [0.7.3] - 2026-09-22

<!-- Add unreleased notes below this line. -->

- 开发中：账户删除与数据清理、Web 后端会话、设备注册表、同步审计、冲突历史及处理记录；设置关于和正式版检测。
- 开发中：Android 库存导航、参数中文、筛选主题、分类/仓位建议、已删除元件历史开关；标签模板与选纸流程。
- 部署文档增加可选 Watchtower labels 配置；取消应用内服务端自更新。须 CI 与实机验收后再发布正式版。

- Add an opt-in Android M1 connection test in label-preview Bluetooth diagnostics.
  Paired Classic/dual-mode printers use SPP to query the model and, only after an
  M1 match, status. Keep the original BLE service-information mode and provide a
  system Bluetooth pairing shortcut. This test does not print or change printer
  settings; M1 label output and other printer models still need device validation.
- Bound connection and query stages, close sockets on stop, timeout or leaving
  the foreground, and prevent cancelled or replaced sessions from publishing
  stale results. Preserve partial diagnostic stages and distinguish no response
  from an unrecognised reply.
- Add M1 query and lifecycle regressions to GitHub CI. Shared reports contain
  only fixed model-match results, response lengths and status codes; device
  addresses, arbitrary names, serial numbers and raw replies are excluded.
- Document the verified Hanma 3.3.4-cn / 3.4.6-cn M1 SPP and POLI/LZO paths,
  plus the installation and feedback steps for this read-only connection test.


## [0.7.2] - 2026-09-22

<!-- Add unreleased notes below this line. -->

- 开发中：账户删除与数据清理、Web 后端会话、设备注册表、同步审计、冲突历史及处理记录；设置关于和正式版检测。
- 开发中：Android 库存导航、参数中文、筛选主题、分类/仓位建议、已删除元件历史开关；标签模板与选纸流程。
- 部署文档增加可选 Watchtower labels 配置；取消应用内服务端自更新。须 CI 与实机验收后再发布正式版。

- Fix duplicate storage-location creation on Android and Windows: creating an
  existing code now reports an error instead of renaming the original location
  or reviving a deleted one. Trim surrounding whitespace before checking.
- Separate location creation from explicit editing. Editing requires an active
  existing code and preserves inventory allocations; failed saves remain in the
  editor. Existing data and database schemas are unchanged. Previously overwritten
  names must be corrected manually using their original location codes.
- Add SQLite regressions for duplicate creation, deleted-code protection and
  inventory preservation in native clients; verify the server's existing HTTP 409
  behavior preserves locations, component quantities, allocations and movements.


## [0.7.1] - 2026-09-22

<!-- Add unreleased notes below this line. -->

- 开发中：账户删除与数据清理、Web 后端会话、设备注册表、同步审计、冲突历史及处理记录；设置关于和正式版检测。
- 开发中：Android 库存导航、参数中文、筛选主题、分类/仓位建议、已删除元件历史开关；标签模板与选纸流程。
- 部署文档增加可选 Watchtower labels 配置；取消应用内服务端自更新。须 CI 与实机验收后再发布正式版。

- Fix domestic LCSC lookup false positives by parsing valid product data before
  verification-page markers. Distinguish verification blocks, rate limits and
  network failures; pause repeated domestic requests across batch SKUs while
  retaining international fallback and an explicit domestic retry action.
- Add a built-in server setup page at port 8787, independent of the optional
  inventory Web image. First-run configuration saves API Token, allowed Web
  origins and the inventory-write switch atomically to config.json. Later
  changes require the current token, and non-empty environment overrides remain
  authoritative. Preserve old inventory and legacy token behavior on upgrade.
- Add authenticated viewing of bounded, timestamped application logs, persisted
  beside the database and also sent to container output. Logs exclude credentials,
  request bodies, query strings and barcode contents.
- Improve API Token discovery with show/copy actions in native settings and Web
  login, plus direct links from the Web console to server configuration and logs.
- Reconcile implementation and deployment documentation. Add a Synology / Docker
  quickstart covering image selection, first-run setup, File Station mappings,
  config/database/log locations, effective Token recovery, environment overrides
  and upgrades. Explain the public Python-image GPG_KEY fingerprint and separate
  ordinary deployment from Docker Hub publishing credentials.
- Complete the first comparison of NIIMBOT, Phomemo and official HPRT M1 resources.
  Add opt-in Android Bluetooth service diagnostics in label preview to collect
  device-test evidence. This does not send print commands or claim M1 printing
  support; direct printing still requires a verified protocol and device tests.
- Extend CI with Compose profile/variable validation and container first-run,
  authentication, mounted configuration/logs and restart-persistence checks.


## [0.7.0] - 2026-09-22

<!-- Add unreleased notes below this line. -->

- 开发中：账户删除与数据清理、Web 后端会话、设备注册表、同步审计、冲突历史及处理记录；设置关于和正式版检测。
- 开发中：Android 库存导航、参数中文、筛选主题、分类/仓位建议、已删除元件历史开关；标签模板与选纸流程。
- 部署文档增加可选 Watchtower labels 配置；取消应用内服务端自更新。须 CI 与实机验收后再发布正式版。

- Expand Chinese display of official LCSC categories, including current LDO
  category names. Known English categories in existing Android and Windows
  inventory now share localized display, search and filter behavior with new
  imports. Preserve original official paths and unknown/custom category values.
- Add Windows primary/external server addresses with transport-only failover,
  safe migration of old settings and visible endpoint results. Each sync keeps
  one endpoint; authentication errors and interrupted writes never switch sites.
- Add Android and Windows BOM column mapping for SKU, model, quantity, package,
  name and reference designators, plus an Excel-compatible UTF-8 shortage CSV
  export. Mapping and export do not modify inventory; existing confirmed BOM
  deductions retain their transaction and duplicate-submission protection.
- Add optional Web inventory writes, disabled by default with
  WEB_INVENTORY_ENABLED=false. Authenticated browsers can create locations and
  components, edit component details, and record inbound/outbound stock with
  location selection, version-conflict checks and persisted retry receipts.
- Adapt Web inventory operations to desktop and mobile with compact expandable
  forms, stock previews and persistent success/error feedback. Recover uncertain
  movements after a lost response or page reload without duplicating stock.
  Support LAN HTTP browsers without crypto.randomUUID and Chinese location codes.
- Extend Docker CI with a separate Web image and startup/asset checks. API and
  Web images use version/latest and web-version/web-latest tags in the same
  Docker Hub repository; Compose offers an optional web profile. The 0.7.0
  release published both API and Web images for linux/amd64 and linux/arm64.
- Reconcile implementation/deployment documentation, including the detailed
  Docker Hub setup guide. Bluetooth printing remains deferred; its next stage
  will compare open-source protocols and multi-brand adapters before device work.


## [0.6.0] - 2026-09-22

<!-- Add unreleased notes below this line. -->

- 开发中：账户删除与数据清理、Web 后端会话、设备注册表、同步审计、冲突历史及处理记录；设置关于和正式版检测。
- 开发中：Android 库存导航、参数中文、筛选主题、分类/仓位建议、已删除元件历史开关；标签模板与选纸流程。
- 部署文档增加可选 Watchtower labels 配置；取消应用内服务端自更新。须 CI 与实机验收后再发布正式版。

- Added Android batch-scan settings: a persistent 0.5–5 second capture interval
  (default 1.5 seconds), independent success sound/vibration switches and visible
  success/duplicate/cooldown feedback. Only newly accepted packages trigger
  feedback; a code remaining in view cannot repeatedly add stock.
- Added a separate label-printing XLSX export to Android and Windows backup
  settings, with selectable name, SKU, model, package, category, location,
  quantity and long/short QR text columns. Existing backup/restore is unchanged.
- Route catalog lookup by UI language: Chinese prefers domestic LCSC and English
  prefers international LCSC, with visible fallback reasons. Keyword search
  explicitly identifies its domestic source. Android clears lookup caches on
  language changes and does not persist fallback results over the preferred site.
- Preserve descriptions, parameters and datasheet links in catalog caches;
  remove expired, malformed and obsolete unscoped entries without affecting inventory.
- Added a reusable Docker image workflow with API startup, authentication and
  mounted-database checks, plus amd64/arm64 Docker Hub publishing support for
  rafaelikaros/component_manager. Publishing remains explicitly skipped until
  DOCKERHUB_TOKEN is configured; existing releases can be published manually later.
- Added docker-compose.hub.yml and deployment instructions for pulling the API
  image with persistent /data storage. The admin web app remains separate.


## [0.5.4] - 2026-09-17

<!-- Add unreleased notes below this line. -->

- 开发中：账户删除与数据清理、Web 后端会话、设备注册表、同步审计、冲突历史及处理记录；设置关于和正式版检测。
- 开发中：Android 库存导航、参数中文、筛选主题、分类/仓位建议、已删除元件历史开关；标签模板与选纸流程。
- 部署文档增加可选 Watchtower labels 配置；取消应用内服务端自更新。须 CI 与实机验收后再发布正式版。

- Fixed Windows WinUI 3 startup failing on a missing theme color resource; use
  the Windows App SDK background brush, including system high-contrast support.
  Apply localized window titles after initialization and remove the obsolete
  localized Content override from the composite sync button.
  Include compiled window/page XBF resources and the merged application PRI in
  portable publish output; attach navigation handlers after initialization.
- Publish Windows as a self-contained x64 ZIP only. Removed MSIX, temporary
  signing certificates and certificate-install scripts from release artifacts;
  CI now starts the real app and checks the extracted release ZIP before upload.
- Prefer the manufacturer part model for new JLC/LCSC imports and retain long
  official product descriptions separately, preserving custom component names.
- Raised the server component-name limit from 200 to 4000 characters so earlier
  long-name imports can synchronize without truncation or clearing local data.
  Android now summarizes HTTP 422 validation errors without dumping input data.
- Added optional Android external server routing: probe the preferred address
  first, fall back on transport failures, and keep one endpoint for each sync.
- Clarified deployment API_TOKEN configuration and added show/copy controls for
  the web session token. Docker Compose now reads API_TOKEN from the root .env.
  Web release builds suggest the server host instead of a baked-in localhost.
- Completed BOM exact SKU/model matching and manual inventory search on Android
  and Windows, including editing matches after quantity aggregation.
- Deferred optional Web inventory writes and Windows LAN/WAN routing to the next
  version; the web console remains read-only for inventory in this release.


## [0.5.3] - 2026-09-16

<!-- Add unreleased notes below this line. -->

- 开发中：账户删除与数据清理、Web 后端会话、设备注册表、同步审计、冲突历史及处理记录；设置关于和正式版检测。
- 开发中：Android 库存导航、参数中文、筛选主题、分类/仓位建议、已删除元件历史开关；标签模板与选纸流程。
- 部署文档增加可选 Watchtower labels 配置；取消应用内服务端自更新。须 CI 与实机验收后再发布正式版。

- Preserved Android's Import and scan bottom sheet over the inventory page;
  separated Project BOM and data migration choices without replacing the entry
  workflow with a new page.
- Reorganized BOM and migration into scrollable file/configuration, preview and
  confirmation stages, with concise task titles and predictable Back behavior.
- Unified Android location, backup, BOM and batch page chrome; location editing
  now uses scrollable dialogs with visible validation and retryable save errors.
- Testing Android and Windows connection drafts no longer silently saves them
  or resets sync state; changed connection settings must be saved before syncing.
- Fixed Windows secondary navigation and inventory state retention, prevented
  duplicate sync dialogs, and added narrow-window layouts for core workflows.
- Added authenticated read-only inventory search, pagination and component
  details to the admin console and server, including expired-login recovery.
- Added UI interaction regressions, rendered Android screenshots and browser
  verification records; documented remaining real-device and high-DPI checks.


## [0.5.2] - 2026-09-16

<!-- Add unreleased notes below this line. -->

- 开发中：账户删除与数据清理、Web 后端会话、设备注册表、同步审计、冲突历史及处理记录；设置关于和正式版检测。
- 开发中：Android 库存导航、参数中文、筛选主题、分类/仓位建议、已删除元件历史开关；标签模板与选纸流程。
- 部署文档增加可选 Watchtower labels 配置；取消应用内服务端自更新。须 CI 与实机验收后再发布正式版。

- Simplified Android inventory actions into an Import and scan menu, with batch
  JLC inbound grouped there and location management / Excel backup moved to Settings.
- Fixed long batch inbound review and edit layouts with scrollable content,
  collapsible summaries and fixed confirmation actions.
- Added visible lookup/search progress and restored system Back behavior on
  backup, location and BOM secondary pages without exiting the app.
- Added Compose interaction regressions for import entry grouping, secondary-page
  Back and editing the last entry in a 100-package batch draft.
- Documented a full Android, Windows and admin-web UI audit with prioritized
  follow-up work; Windows/admin-web redesign recommendations remain planned.


## [0.5.1] - 2026-09-15

<!-- Add unreleased notes below this line. -->

- 开发中：账户删除与数据清理、Web 后端会话、设备注册表、同步审计、冲突历史及处理记录；设置关于和正式版检测。
- 开发中：Android 库存导航、参数中文、筛选主题、分类/仓位建议、已删除元件历史开关；标签模板与选纸流程。
- 部署文档增加可选 Watchtower labels 配置；取消应用内服务端自更新。须 CI 与实机验收后再发布正式版。

- Fixed Android upgrades from existing databases failing before startup because
  the pre-upgrade backup executed a result-returning PRAGMA with execSQL.
- Added a data-preserving startup failure page with retry and redacted diagnostics;
  inventory is never silently reset after an initialization failure.
- Added SQLite/WAL migration coverage that verifies retained inventory and a
  readable pre-upgrade backup, plus startup diagnostic privacy regression tests.


## [0.5.0] - 2026-09-15

<!-- Add unreleased notes below this line. -->

- 开发中：账户删除与数据清理、Web 后端会话、设备注册表、同步审计、冲突历史及处理记录；设置关于和正式版检测。
- 开发中：Android 库存导航、参数中文、筛选主题、分类/仓位建议、已删除元件历史开关；标签模板与选纸流程。
- 部署文档增加可选 Watchtower labels 配置；取消应用内服务端自更新。须 CI 与实机验收后再发布正式版。

- Added visible import error dialogs and explicit duplicate-SKU inbound
  confirmation showing current quantity, added quantity and resulting total,
  while preserving existing component metadata and ordinary edit semantics.

- Added Android continuous JLC QR capture and Windows scanner/text batch
  collection, private resumable drafts, package-level deduplication, reviewed
  inbound totals and a separate pending page for failures/manual correction.
- Added atomic batch inbound with location allocation updates, movement history,
  sync queue writes and local receipts preventing repeated submissions.
- Fixed repeated parsing of the same QR resetting enriched fields without
  restarting lookup; retained edits, debounced retries and rejected stale results.
- Corrected international lookup diagnostics to distinguish matched products,
  empty results and cache hits instead of reporting every parsed response as success.


## [0.4.2] - 2026-09-15

<!-- Add unreleased notes below this line. -->

- 开发中：账户删除与数据清理、Web 后端会话、设备注册表、同步审计、冲突历史及处理记录；设置关于和正式版检测。
- 开发中：Android 库存导航、参数中文、筛选主题、分类/仓位建议、已删除元件历史开关；标签模板与选纸流程。
- 部署文档增加可选 Watchtower labels 配置；取消应用内服务端自更新。须 CI 与实机验收后再发布正式版。

- Unified JLC text/QR parsing and improved Android multi-code selection so
  decoded packaging payloads are not rejected by the text-only entry point.
- Added Android and Windows feedback forms with editable, opt-in diagnostic
  reports, copy/text export and GitHub browser login for final Issue submission.
- Added bounded process-local scan/catalog diagnostic events without raw
  packaging payloads, order data, credentials or arbitrary exception messages.
- Bluetooth printer integration remains deferred while scan reliability and
  issue reporting are addressed.


## [0.4.1] - 2026-09-15

<!-- Add unreleased notes below this line. -->

- 开发中：账户删除与数据清理、Web 后端会话、设备注册表、同步审计、冲突历史及处理记录；设置关于和正式版检测。
- 开发中：Android 库存导航、参数中文、筛选主题、分类/仓位建议、已删除元件历史开关；标签模板与选纸流程。
- 部署文档增加可选 Watchtower labels 配置；取消应用内服务端自更新。须 CI 与实机验收后再发布正式版。

- Unified Android and Windows inventory workbook size/row limits, separating
  full inventory backups from the smaller BOM import limit. Export validates its
  result before publishing the file so it can be read back by the clients.
- Improved Excel numeric-cell interoperability for integral decimals and
  scientific notation while retaining strict inventory integer validation.


## [0.4.0] - 2026-09-15

<!-- Add unreleased notes below this line. -->

- 开发中：账户删除与数据清理、Web 后端会话、设备注册表、同步审计、冲突历史及处理记录；设置关于和正式版检测。
- 开发中：Android 库存导航、参数中文、筛选主题、分类/仓位建议、已删除元件历史开关；标签模板与选纸流程。
- 部署文档增加可选 Watchtower labels 配置；取消应用内服务端自更新。须 CI 与实机验收后再发布正式版。

- Added Chinese LCSC public catalog search, keyword candidates, structured
  parameter details and exact C-number fallback to the international storefront.
- Added independent storage locations, multiple locations per component, explicit
  location movements, partial/full transfers and visible BOM allocation plans
  on Android and Windows. Transfers do not increase consumption statistics.
- Added a shared Excel backup/restore format and LCSC_android_erp schemaVersion=1
  migration with preview, new-record merging, strict inventory validation and
  persistent embedded product images. Export does not query or change inventory.
- Added inventory sync protocol 1 with atomic allocation snapshots, checked
  base versions, capability negotiation, legacy-client write protection and
  pre-upgrade database backups. Concurrent local edits remain queued.
- Extended MQTT component state with location allocations and the admin console
  movement display with transfers. Bluetooth printer integration remains deferred.


## [0.3.13] - 2026-09-15

- Fixed the Android settings section list's missing localized string binding
  found by GitHub CI. Includes the native UI redesign from 0.3.12, whose
  Android release build did not complete.


## [0.3.12] - 2026-09-15

- Redesigned Android and Windows native navigation, home summaries, inventory
  and movement workspaces, settings, and secondary editing/import surfaces.
- Moved inventory usage rings into compact quantity/image columns, removed
  duplicate settings headings and actions, and formatted display dates locally.
- Reorganized native About pages with author Rafael-Ikaros, installed version,
  project/license links, and the existing GitHub Release update workflow.
- Documented the native layout contract and official reference projects.
- Added CI coverage for local timestamp formatting and explicitly scoped
  release publication to the current GitHub repository.


## [0.3.11] - 2026-09-15

- Added native inventory donut indicators and detail statistics using current
  stock plus all valid recorded outbound movements, including history beyond
  the recent 200-row list. Added a larger Android component-detail product image.
- Added an optional server-only MQTT inventory-state publisher for Home
  Assistant and dashboards, with transactional SQLite outbox, retained QoS 1
  messages, deletion tombstones, retry after PUBACK failure, destination-aware
  initial snapshots and authenticated publisher status.
- Added MQTT environment/Compose configuration, Home Assistant setup examples,
  and regression coverage for outbound totals and MQTT delivery boundaries.
- Completed native Settings / About with installed versions, GPLv3 and project
  links, manual GitHub stable-release checks, release notes, and validated
  platform download links with explicit no-update, missing-asset and error states.
- Added authenticated MQTT configuration in web Settings, masked credentials,
  persistent settings applied on server restart, and connection/queue status.
- Fixed CI branch-push triggers and reusable release input resolution so
  changelog-driven builds publish their requested GitHub Release assets.


## [0.3.10] - 2026-09-15

- Added Android and Windows project BOM CSV/XLSX preview, exact inventory matching,
  shortage checks and transactional batch depletion with local retry idempotency.
- Added component-hub JSON migration with source metadata, Chinese category
  mapping, duplicate preview/explicit skip and atomic initial-stock movements.
- Added trusted product images on native inventory rows, Windows direct C-number
  lookup, official-category priority and Chinese domestic-store links.
- Fixed outbound movement signs in native lists/details without changing stored
  magnitudes; Android now captures OCR photos with rotation instead of preview
  screenshots, prioritizes direct C-number entry, and hides server recognition
  and the unavailable Paddle option.

- Added service-issued sync cursors and legacy database migration to deliver
  late offline changes; fixed timestamp comparisons, foreign-key enforcement,
  and atomic push rollback.
- Preserved native-client queue edits made during sync, reset cursors on server
  changes, and implemented debounced Windows automatic synchronization.
- Added Android direct LCSC public product lookup from scanned C-numbers without
  server/API-key configuration, with exact SKU checks, local caching, import
  preference, and manual/product-page fallback.
- Added synchronization, public-catalog, BOM and migration regression tests,
  plus a sourced component-hub comparison and migration guide.
- CI now runs Android inventory regressions (sync, catalog, BOM, migration,
  movement signs and OCR preferences) and Windows core tests.
- Excluded local screenshot attachments and temporary Gradle verification files
  from version control.


## [0.3.9] - 2026-05-19

- Reworked Android stock movements into a scan-then-review batch workflow:
  successful warehouse-label scans now stay in a continuous session, merge
  duplicate scans into one queue row, and let each queued component choose its
  own inbound, outbound, or adjustment details before one local commit.
- Re-armed the Android in-app label scanner between successful movement scans
  without dropping the existing auto-zoom and tap-to-focus path, and changed
  the scanner exit flow to return to the pending review queue instead of
  forcing a one-scan-one-entry sheet.
- Added Android movement batch validation, localized batch-review copy, and
  refreshed movement previews so the new queue editing states render in both
  runtime and Compose Preview.

## [0.3.8] - 2026-05-16

<!-- Add unreleased notes below this line. -->

- 开发中：账户删除与数据清理、Web 后端会话、设备注册表、同步审计、冲突历史及处理记录；设置关于和正式版检测。
- 开发中：Android 库存导航、参数中文、筛选主题、分类/仓位建议、已删除元件历史开关；标签模板与选纸流程。
- 部署文档增加可选 Watchtower labels 配置；取消应用内服务端自更新。须 CI 与实机验收后再发布正式版。

- Tightened Android small-label warehouse scanning with a dedicated movement
  scan mode that raises CameraX analysis resolution, enables bundled ML Kit
  potential-barcode detection plus zoom suggestions, and keeps narrow printed
  labels on a pure auto-zoom plus tap-to-focus path instead of manual zoom
  controls.
- Switched the Android `10x40mm QR` warehouse label to a shorter
  lookup-first payload (`cvl3|sku|qty`) so the app resolves full component
  metadata locally by `sku`, while preserving backward compatibility for older
  compact `cvl2` labels and app-generated JLC-compatible labels.
- Hardened Android import parsing so supplier `vendor` values can populate
  `brand`, model-like tokens are no longer learned or saved as canonical
  component names, and explicit or server-resolved names take precedence over
  raw model codes during JLC and OCR import refinement.
- Refactored Android inventory and movement create flows so the main inventory
  add action opens a unified `Import` / `Manual add` chooser, matched label
  scans complete `Inbound` / `Outbound` / `Adjustment` edits inside the same
  bottom sheet, save failures remain inline, and successful saves reselect the
  affected component before optional label preview.
- Reworked the Android shell and Inventory workspace onto official Material 3
  adaptive primitives, including `NavigationSuiteScaffold`,
  `currentWindowAdaptiveInfo`, `NavigableListDetailPaneScaffold`, and a
  search-first `SearchBar` header, while tightening inventory row density and
  shifting the primary flow away from stacked filter cards.
- Upgraded the Android release build chain to a newer AGP and Lifecycle
  combination, aligned CI and release workflows to the matching Gradle
  8.11.1 plus SDK Build Tools 35.0.0 baseline, migrated off the deprecated
  `kotlinOptions` DSL, and removed empty proxy properties to address the
  GitHub Actions `lintVitalAnalyzeRelease` crash path triggered by Lifecycle
  lint binary incompatibility.


## [0.3.7] - 2026-05-13

- Added Android scan-first stock movement entry for generated warehouse and
  app-generated JLC-compatible labels, including local label parsing by `sku`,
  match-status feedback (`invalid`, `not found`, `ambiguous`, `matched`), and
  quick `Inbound`, `Outbound`, and `Adjustment` actions that open a
  component-locked movement form without requiring server lookup.

## [0.3.6] - 2026-05-13

- Reworked Android inventory labels into collision-safe physical templates
  with user-selectable `10x40mm QR`, `30x40mm QR`, and pure text strip modes,
  compact offline warehouse QR payloads for the narrow label, optional
  companion text-label export, direct label-sized export, size-specific QR
  placement rules, larger label typography, fixed physical preview ratios,
  a horizontal `30x40mm` layout with QR-left and centered package/name
  stacking, and refreshed Compose preview states for long-text and Chinese
  label cases.
- Fixed Android import semantics so unresolved supplier parts no longer save
  raw `model` or `sku` values into the canonical component `name`; the import
  form now shows them as reference labels and allows manual adoption or full
  editor refinement.
- Enabled real server-side public LCSC web fallback for
  `GET /admin-api/part-lookup` behind `ENABLE_WEB_FALLBACK_RESOLVERS`, so
  self-hosted deployments without OpenAPI credentials can still enrich JLC QR
  imports by SKU.
- Reworded the Android local deletion status copy to clarify that a component
  was marked deleted on the current device.
- Reworked changelog-driven GitHub release automation into a single orchestration
  chain that uses the default `GITHUB_TOKEN`, pushes the matching release tag,
  and then directly calls the reusable release workflow instead of relying on a
  second workflow being triggered by tag pushes.


## [0.3.5] - 2026-05-11

- Switched Android JLC package QR scanning from Google Code Scanner to an
  in-app CameraX scanner backed by bundled ML Kit barcode scanning, removing
  the runtime dependency on downloading the Barcode UI module before first use.
- Added Android supplier packaging OCR import through bundled ML Kit Chinese
  text recognition, with quantity-first confirmation and optional full-editor
  refinement before saving inventory.
- Added Android inventory label preview plus PNG/PDF export, generating
  JLC-compatible QR payloads for JLC-sourced parts and warehouse QR payloads
  for non-JLC parts so labels can round-trip back into import flows.
- Added server-side hybrid recognition endpoints:
  `GET /admin-api/part-lookup`,
  `GET /admin-api/recognition-rules/meta`, and
  `POST /admin-api/recognition-rules/refresh`, while keeping
  `GET /admin-api/lcsc/lookup` as a compatibility proxy for direct official
  supplier metadata requests.
- Reworked Android JLC import enrichment into a local-first flow with
  bundled offline recognition rules, device-only learned mappings stored in
  SQLite, SKU-first and MPN-fallback reuse, field-origin review in the import
  form, and separate settings for local recognition, learning, and optional
  server lookup.
- Rebuilt Android runtime string resources and preview string bundles after the
  import-enrichment changes, and re-verified `assembleDebug` and
  `assembleRelease` on `2026-05-10`.
- Added a Windows-side Android Gradle helper script that prefers Android
  Studio's embedded JBR so local `assembleRelease` remains stable when the
  system default Java runtime is newer than the Android lint toolchain
  supports.
- Refactored Android supplier packaging OCR into a capture-first flow with a
  unified OCR contract, structured line extraction, packaging-field parsing,
  and richer import notes instead of flattening every live frame directly into
  raw text.
- Added Android OCR engine preference storage and settings UI with `Auto`,
  `ML Kit offline`, and `Paddle experimental` modes, while keeping the
  current build honest by treating Paddle as an unavailable future native
  integration instead of a fake fallback.
- Renamed the Android app surface to `元件仓库`, tightened the inventory home
  top bar from the previous medium/two-row app bar to a single-row layout,
  and repaired the broken `values-zh-rCN` resource file so Preview and runtime
  Chinese resources resolve again.
- Added persisted Android in-app language switching with first-launch default
  `zh-CN`, backed by `AppCompatDelegate.setApplicationLocales`, a settings
  selector for `中文` / `English`, and manifest locale metadata for Android
  per-app language support.
- Improved Android JLC QR parsing and local recognition so vendor numbering
  schemes can infer package, model-family, and category more reliably, while
  avoiding the old fallback that incorrectly copied raw model codes into the
  package field when no package was actually recognized.

## [0.3.0] - 2026-05-09

- Removed the repository-pinned Android JDK path from `android-client/gradle.properties`
  so GitHub Actions and other non-Windows environments can use their own
  configured Java runtime.
- Added default-branch changelog release automation that syncs version files,
  pushes a release commit when needed, and creates the matching `v*` tag for
  `release.yml`.
- Fixed Windows MSIX install guidance and release packaging so the generated
  installer imports the test signing certificate into
  `Cert:\LocalMachine\TrustedPeople` instead of the current-user store.
- Restored the WinUI application resource merge so Windows startup and Visual
  Studio XAML Designer previews can resolve theme resources such as
  `TextFillColorSecondaryBrush`.
- Stabilized Android Compose Preview by moving preview rendering onto static
  string bundles and content-level preview composables instead of direct
  preview-time `R.string` resolution.
- Reworked the Android inventory flow around scroll-safe `Scaffold` inset
  handling, pinned JLC import actions, and quantity-first import confirmation
  so phone previews and runtime scrolling behave like a native list-first
  inventory app instead of a clipped card stack.
- Added Android JLC/LCSC-style text import and package QR import through
  Google Code Scanner, mapping JLC item numbers into local `sku`, pre-filling
  most fields, and storing extra import metadata in the existing
  `description` field without changing sync APIs or the SQLite schema.
- Expanded Android settings with separate sync-on-launch vs sync-after-write
  controls, import defaults, scanner preferences, and an in-app About section
  for `0.3.0`.
- Rebuilt the Windows WinUI shell around an inventory-first desktop workflow
  with `Inventory`, `Movements`, `Overview`, and `Settings`, plus denser
  list/detail pages, grouped sync settings, and refreshed XAML designer sample
  data.

## [0.2.0] - 2026-05-08

- Added changelog-driven automatic version syncing for Android, admin-web, and Windows.
- Added repository-managed Git hook bootstrap plus CI version consistency checks.

## [0.1.0]

- Initial released baseline before changelog-driven version automation.
