# 文档导航

第一次使用请从操作指南开始。部署文档面向服务端管理员；开发和历史记录用于维护代码、查阅接口及验证依据。

当前维护 Android、管理 Web 和服务端。Windows 独立客户端已停止更新、CI 构建与发布；涉及 Windows 的操作说明用于查阅历史实现，源码和旧版本附件保留。后续 Windows 功能计划集成到贴片机上位机项目。

## 操作指南

| 需要完成的操作 | 文档 |
| --- | --- |
| 首次下载并安装 Android | [开始使用](../README.md#只在本机管理库存) |
| 检查和下载 Android 更新 | [应用更新](app-updates.md) |
| 连续扫码、C 编码清单、立创商城订单 | [批量入库](batch-jlc-inbound.md) |
| 导入 BOM、匹配库存、配料和确认出库 | [项目用料](bom-and-migration.md) |
| 记录购买价格、补录库存成本及估算 PCBA 材料成本 | [购买价格与材料成本](inventory-cost.md) |
| 管理库位、转移、备份恢复和标签 Excel 导出 | [库存与备份](storage-and-backup.md) |
| 在设备间同步元件照片 | [照片同步](photo-sync.md) |
| 创建、停用、删除用户及分发密钥 | [账户管理](accounts.md) |
| 用商城订单修正已有元件资料 | [从订单更新资料](order-metadata-update.md) |
| 扫描库存资料、跨页全选并应用更新 | [库存资料检查](parameter-enrichment.md) |
| 编辑标签、模板和汉印 M1 打印 | [标签打印](m1-printing-feature-matrix.md) |
| HM-A300E 配对、纸张与通用 CPCL 打印 | [HM-A300E](hm-a300e.md) |
| 反馈错误和附加诊断 | [扫码诊断与反馈](feedback-and-scan-diagnostics.md) |
| 向 Home Assistant、看板发布库存变化 | [MQTT](mqtt.md) |

## 部署和更新

| 需要完成的操作 | 文档 |
| --- | --- |
| 群晖手动创建 API/Web，不使用 Compose、不授予 Docker 控制权限 | [手动部署与更新](docker-manual.md) |
| 使用 Compose 安装、配置账户密钥和排障 | [Docker Compose 部署](docker-quickstart.md) |
| 更换 API 镜像、沿用原库存并核对版本 | [群晖独立容器更新](docker-manual.md#更新-api-容器) · [Compose 更新](docker-quickstart.md#更新-api-和-web) |
| 正式版升级 dev、选择更新渠道 | [渠道切换](docker-channels.md) |
| 在群晖用 Compose 部署 Updater、恢复备份和验证更新 | [群晖 Compose 图文教程](server-web-update.md#群晖一次性配置) |
| 维护者向 Docker Hub 发布镜像、核对发布结果 | [镜像发布](dockerhub.md) |

普通部署不挂 Docker socket；直接创建的容器或 Compose 项目都可以手动更新。已有部署沿用原数据挂载，不必为了更新改用新空项目。网页一键更新是另行授权的可选功能，需要 Docker 管理访问，详见[网页更新教程](server-web-update.md)。

## 开发和技术参考

- [架构](architecture.md)：模块、账户数据库、本地保存与同步边界。
- [接口与格式](integration-guide.md)：认证、同步和管理 API。
- [开发与运维](runbook.md)：构建、版本发布和故障排查。
- [更新日志](CHANGELOG.md)：版本功能与修复。
- [设备兼容性](printer-compatibility.md)：打印机支持范围及诊断含义。

## 历史证据与设计资料

这些文档不作为安装或操作步骤：

- [验证记录](history/verification.md)：发布、实机与隔离数据测试的摘要和原始记录索引。
- [M1 协议与 HCI 分析](m1-apk-analysis.md)：协议来源、复测过程和采集方法。
- [平板布局提案](design/tablet-ui-proposal.md)：交互原型与实现边界。
- [历史发布说明](releases/)：对应版本的交付范围，不代表当前版本的菜单和能力。
