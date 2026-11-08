# 文档导航

第一次使用请从操作指南开始。部署文档面向服务端管理员；开发和历史记录用于维护代码、查阅接口及验证依据。

## 操作指南

| 需要完成的操作 | 文档 |
| --- | --- |
| 安装客户端、检查和下载更新 | [安装与更新](app-updates.md) |
| 连续扫码、订单 Excel 批量入库 | [嘉立创批量入库](batch-jlc-inbound.md) |
| 导入 BOM、匹配库存、配料和确认出库 | [项目用料](bom-and-migration.md) |
| 记录购买价格、补录库存成本及估算 PCBA 材料成本 | [购买价格与材料成本](inventory-cost.md) |
| 管理库位、转移、备份恢复和标签 Excel 导出 | [库存与备份](storage-and-backup.md) |
| 在设备间同步元件照片 | [照片同步](photo-sync.md) |
| 创建、停用、删除用户及分发密钥 | [账户管理](accounts.md) |
| 用商城订单修正已有元件资料 | [从订单更新资料](order-metadata-update.md) |
| 批量查询中文资料和补全参数 | [资料补全](parameter-enrichment.md) |
| 编辑标签、模板和汉印 M1 打印 | [标签打印](m1-printing-feature-matrix.md) |
| 反馈错误和附加诊断 | [扫码诊断与反馈](feedback-and-scan-diagnostics.md) |
| 向 Home Assistant、看板发布库存变化 | [MQTT](mqtt.md) |

## 部署和更新

| 需要完成的操作 | 文档 |
| --- | --- |
| Docker／群晖首次安装、Token 查找和排障 | [快速部署](docker-quickstart.md) |
| 正式版升级 dev、选择更新渠道 | [渠道切换](docker-channels.md) |
| 配置网页更新及了解一次性配置要求 | [网页更新](server-web-update.md) |
| 维护者向 Docker Hub 发布镜像、核对发布结果 | [镜像发布](dockerhub.md) |

**仅把 0.7.8 的 API/Web 镜像换成 dev，不会自动启用网页更新。** 保留数据升级和安装更新组件是两件事；
首次可在原项目中一起完成。之后管理员可在网页更新到支持该能力的正式版或 dev。
具体限制见[升级说明](server-web-update.md#已有正式版先升级到-dev)。

## 开发和技术参考

- [架构](architecture.md)：模块、账户数据库、本地保存与同步边界。
- [接口与格式](integration-guide.md)：认证、同步和管理 API。
- [开发与运维](runbook.md)：构建、版本发布和故障排查。
- [更新日志](CHANGELOG.md)：版本功能与修复。
- [设备兼容性](printer-compatibility.md)：打印机支持范围及诊断含义。

## 历史证据与设计资料

这些文档不作为安装或操作步骤：

- [验证记录](history/verification.md)：发布、实机与隔离数据测试的摘要和原始记录索引。
- [M1 APK 协议分析](m1-apk-analysis.md)、[HCI 取证方法](m1-protocol-capture.md)：协议来源及历史调查。
- [历史 UI 验证及截图](ui-consistency-implementation.md)：2026-09-16 的界面验证。
- [平板布局提案](design/tablet-ui-proposal.md)：含尚未实施的布局原型。
- [历史发布说明](releases/)：对应版本的交付范围，不代表当前版本的菜单和能力。
