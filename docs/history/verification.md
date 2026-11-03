# 验证记录

本文汇总已记录的构建、发布与设备验证结果，供维护者追溯。
它不是用户操作指南，也不表示所有平台、设备和异常情况均已验证。
本次整理未重新执行历史测试，现行功能和接口以[使用指南](../README.md)、[架构](../architecture.md)和[集成指南](../integration-guide.md)为准。

## 出入库默认原因：0.7.9-dev.9

提交 `c862d73` 的[主 CI](https://github.com/Rafael-Ikaros/Component_Manager/actions/runs/41598351655)通过。
小米 9 独立测试包竖屏检查：入库、出库、调整、调拨各自的默认原因正确；自定义原因在切换类型后保留，
退出并重新打开表单恢复默认值。未提交测试流水，原应用及现有业务数据未覆盖；临时测试包已移除，旋转设置恢复原值。
Windows Release 编译通过，未做 Windows 桌面界面验收。dev.9 发布由更新日志触发；安装包与镜像的实际发布结果须另外核对，不能用实现提交的 CI 代替。

## 共享管理镜像：0.7.9-dev.8

2026-11-03 的发布源为 `fd1d5b8f5742074c685426caf137af94bed0741a`。
[主 CI](https://github.com/Rafael-Ikaros/Component_Manager/actions/runs/41592805978)通过，
[实现提交的 Docker CI](https://github.com/Rafael-Ikaros/Component_Manager/actions/runs/41592531861)及
[镜像发布任务](https://github.com/Rafael-Ikaros/Component_Manager/actions/runs/41592806006/job/125773235473)通过。

- 两种镜像产物：API 和共享管理镜像；Web、updater 以同一镜像的不同命令独立启动。
- 真实容器检查了对应健康检查、另一角色端口未监听、Web 无挂载、更新接口认证及任务持久化。
- 真实临时 Compose 项目核对初始 Web/updater 镜像 ID 相同；管理员更新后 API/Web 容器替换，
  updater 容器不变，合成库存、库位、原数据挂载、端口及宿主配置权限保留。
- Hub 回读 API/管理镜像的固定和浮动摘要一致，均有 amd64/arm64；实际 amd64 OCI 版本和提交正确，
  管理镜像入口、默认 Web 命令和角色健康检查正确。正式标签与历史 `updater-dev` 摘要未改变。

门禁运行在隔离 amd64 runner，未操作真实群晖、ARM 设备或用户已有库存；不涉及原生业务 UI 改动。
镜像摘要见[Docker Hub 核对](../dockerhub.md)，迁移步骤见[网页更新](../server-web-update.md#从独立-updater-镜像迁移)。

## Docker 网页更新：0.7.9-dev.7

0.7.9-dev.7 的发布源为 `6567a3d0faeeab3f50bfab8d5fa64d10729c4a4d`。

| 检查 | 记录的结果 |
| --- | --- |
| [主 CI](https://github.com/Rafael-Ikaros/Component_Manager/actions/runs/41392059523) | 服务端、Web、Android、Windows 和版本检查成功 |
| [Docker CI](https://github.com/Rafael-Ikaros/Component_Manager/actions/runs/41392059508) | 三镜像构建、启动、权限回归及完整 Compose 模板检查成功 |
| [发布流程](https://github.com/Rafael-Ikaros/Component_Manager/actions/runs/41392059575) | 客户端附件和三镜像发布成功；发布后的真实更新门禁通过 |
| 真实更新门禁 | 独立临时 Compose 项目通过管理员 API 提交更新；API/Web 容器替换，updater 保持运行；合成库存、库位、数据挂载、端口及宿主 `.env` 可读性保留 |
| 浏览器 | 隔离 API 与模拟 updater 验证确认、旧目标拒绝、断线重连和刷新恢复；这项不等同于真实 Docker 更新 |

dev.6 曾在真实更新门禁中因 `.env` 所有者/权限丢失失败，dev.7 保留文件属性后通过。
不建议用 dev.6 启用更新。没有在用户群晖上运行该升级；ARM 镜像已构建发布，门禁运行在 amd64。
当前镜像标签和摘要见[发布核对](../dockerhub.md)，使用步骤见[网页更新](../server-web-update.md)。

## 客户端、资料与同步

| 版本／阶段 | 记录的直接验证 | 覆盖边界 |
| --- | --- | --- |
| 0.7.9-dev.1 项目用料 | Windows 与小米 9 在隔离数据上检查统一入口、BOM 和手动选料清单、复核及确认 | 不包含 PCB 地图或新的跨设备扣料账本 |
| 0.7.9-dev.2／dev.3 显示修复 | 小米 9 检查键盘上方空白释放、导入入口、单行筛选及横向触达 | 不等于全部设备、字体大小和页面完成 QA |
| 0.7.9-dev.4 国内 EDA | C70565/C393939 实际联网查询；隔离 Web 预览与选择更新；手机同步后核对保护字段 | Windows 价格区没有桌面目视验收；公开页变化仍可能失败 |
| 0.7.8-dev.21／dev.22 订单资料 | 隔离服务端预览并更新资料，Windows 与 Android 同步；手机备份字段及原图字节核对 | 仅所选分类、封装和参数改变，名称、备注、数量、阈值、库位、分配、图片与流水保留；不代表所有订单格式适用 |
| 0.7.8-dev.19／dev.20 照片和恢复 | Windows、手机、Web 原图哈希比对；离线保存、失败重试、阶段取消和中断后重试 | 未穷举并发和中断组合；曾观察主列表为空但未定位，不能声称已找到根因 |
| 0.7.8-dev.18 BOM 预设 | Windows→隔离服务端→Android→Windows 往返，人工匹配和跳过项保留；删除传播 | 预设交换不扣库存；整条 LWW，无人工合并；库存 Excel 不含独立预设 |
| 0.7.8-dev.18 配料 | 专用数据明确确认后仅扣一次；重复提交不再扣减 | 未提交真实库存扣料；临时会话不保证杀进程恢复 |
| 0.7.8-dev.15 Android 模块化 | [CI 39102369086](https://github.com/Rafael-Ikaros/Component_Manager/actions/runs/39102369086)、签名候选、保留数据安装及备份比较 | 本机一项升级测试遇 Windows 文件锁，未通过改动迁移逻辑绕过；Linux CI 成功 |

0.7.8 正式版的[发布流程 40710180699](https://github.com/Rafael-Ikaros/Component_Manager/actions/runs/40710180699)成功，
公开 APK 保留数据安装、版本与计数核对通过。完整版本范围见[0.7.8 发布说明](../releases/0.7.8.md)。
这些检查不能代替生产账户或所有硬件的验收。

## Issue 验证与历史实现

- Issue #9、#19—23：记录了标签元素选择、BOM 旋转/匹配/暂存、在途提醒、库位二维码和零数量库位显示修复。
  `201d861` 的[CI 39648182713](https://github.com/Rafael-Ikaros/Component_Manager/actions/runs/39648182713)及
  [候选 39648183144](https://github.com/Rafael-Ikaros/Component_Manager/actions/runs/39648183144)成功。
  dev.17 手机使用 DEV22 专用数据复测，原记录备份保持；二维码由操作者对准镜头后验证筛选。
  Android 本机 Gradle 等待被停止，不计通过；没有实体打印或生产服务端同步验收。
- Issue #25 的前置 Windows 调查基于 `6122294`（PR #24）。早期桌面自动化三次初始化超时，
  当时不计 WinUI 操作验收；后续订单资料更新和手机验证按上表分别记录，不用后来的结果改写早期证据。
- 多库位/Excel 初版提交 `92e0b83` 的[CI 34960705646](https://github.com/Rafael-Ikaros/Component_Manager/actions/runs/34960705646)及
  [0.4.0 发布 34961742716](https://github.com/Rafael-Ikaros/Component_Manager/actions/runs/34961742716)成功；0.4.1 修正 Excel 互通。
  当时未用真实库存恢复，未做手机相机/安装/原生 UI 验收，M1 打印另行验证。

## 打印与 UI

- M1：2026-09-25 的实机 HCI 对照确认完整行分帧；随后一台 M1、Android 15、40×60 mm 间隙纸上
  单张及连续两张完整、位置一致、无额外空白纸，二维码匹配正确元件。
  该台水平调整为 +0.5 mm，不能推广为所有设备默认值。协议依据见[M1 分析](../m1-apk-analysis.md)，
  使用限制见[打印指南](../m1-printing-feature-matrix.md)。
- UI：保留[2026-09-16 报告和截图](../ui-consistency-implementation.md)。
  其中 Robolectric 绘制、浏览器验证、Windows 构建各有独立边界，不能当作相机、高 DPI 或完整无障碍验收。
- 平板：0.7.8-dev.24 只实施库存和设置首阶段；[提案](../design/tablet-ui-proposal.md)中的其它工作区不是现有功能。

## 原始记录索引

原始记录保留在 Git 历史，以下固定提交可用于复核细节，不能当作当前使用步骤：

- [阶段工作记录](https://github.com/Rafael-Ikaros/Component_Manager/blob/9ded500/docs/current-work.md)
- [dev.16／17 手机记录](https://github.com/Rafael-Ikaros/Component_Manager/blob/9ded500/docs/issues-2026-10-22.md)
- [订单更新验证](https://github.com/Rafael-Ikaros/Component_Manager/blob/9ded500/docs/order-metadata-update.md)
- [批量扫码故障分析](https://github.com/Rafael-Ikaros/Component_Manager/blob/9ded500/docs/batch-jlc-inbound.md)
- [早期库存与 Excel 实施记录](https://github.com/Rafael-Ikaros/Component_Manager/blob/9ded500/docs/inventory-expansion-plan.md)

私有照片、订单、数据库及原始蓝牙日志不在此索引中。
