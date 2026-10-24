# Windows 立创订单导入回归

在当前 Windows 客户端目录运行：

```powershell
dotnet test Tests\JlcOrderImport\JlcOrderImport.Tests.csproj
```

项目直接链接生产解析器、草稿存储和 SQLite 批量写入实现，不依赖 WinUI。
`LegacyXlsFixture` 在内存生成合成 OLE/BIFF8 工作簿，测试完整的 XLS 读取路径；仓库不含客户订单文件。
覆盖两类表头、购买数量、格式化和非法数量、摘要及第 199 行说明、异常行保留、同订单同料号合并与跨格式防重、容量失败原子性及 SQLite 提交重放。

可选私有样本验证只读取指定文件，不复制样本、不提交库存，也不输出订单或商品信息：

```powershell
$env:LCSC_ORDER_STATEMENT_SAMPLE = '本机物料明细对账单.xls 的完整路径'
$env:LCSC_ORDER_DETAIL_SAMPLE = '同日期范围内订单详情.xls 的完整路径'
dotnet test Tests\JlcOrderImport\JlcOrderImport.Tests.csproj --filter FullyQualifiedName~PrivateSamples --logger 'console;verbosity=detailed'
```

私样验证要求订单详情的商品都出现在对账单中，且订单商品标识与订购数量一致。
两个环境变量没有同时配置时，该可选用例会记录省略说明并返回；其余合成回归照常执行。
测试数据库、草稿与构建输出均位于本测试项目目录，测试数据在结束时清理。
