# Windows 客户端

本目录是 WinUI 3 桌面客户端，以本机 SQLite 管理库存，可选连接自托管服务同步。
库存、导入编辑、库位、出入库、BOM 项目用料与同步按模块维护；职责见
[客户端架构](../docs/architecture.md#客户端库存编辑与恢复)，用户操作见[使用指南](../docs/README.md)。

在库存中打开“批量嘉立创入库”，可使用扫码枪或导入立创商城 `.xls` 物料明细对账单。项目用料可从侧栏直接打开，支持 CSV / XLSX BOM、库存匹配、预设和领料复核。

## 构建与分发

使用 Windows、符合项目目标的 .NET SDK 及 WinUI 构建工具。当前目标框架以
`ComponentVault.WinUI/ComponentVault.WinUI.csproj` 为准。

在仓库根目录执行：

```powershell
dotnet build windows-client\ComponentVault.WinUI\ComponentVault.WinUI.csproj
dotnet publish windows-client\ComponentVault.WinUI\ComponentVault.WinUI.csproj -c Release /p:PublishProfile=win-x64.pubxml
```

分发产物是自包含 x64 ZIP，解压完整目录后运行 `ComponentVault.WinUI.exe`，不分发 MSIX。
更新时关闭旧应用、解压新版本并保留 `%LOCALAPPDATA%\ComponentVault`，不删除业务数据库。
启动失败时查看 `%LOCALAPPDATA%\ComponentVault\logs\startup.log`。
安装、更新与旧 MSIX 迁移见[应用更新](../docs/app-updates.md)。

## 界面和业务维护

页面位于 `ComponentVault.WinUI/Views/`；ViewModel 协调页面与独立库存、写入和同步服务。
使用 Visual Studio 的 XAML 编辑与运行时 Hot Reload。设计样本位于 `Design/`，
Preview 或编译不能代替真实窗口、高 DPI、键盘及鼠标交互检查。
应用资源须合并 `XamlControlsResources`；主题资源和启动问题见[运维排障](../docs/runbook.md)。

相关变更只运行对应测试项目；订单导入的合成/私有样本验证说明见
[订单测试说明](ComponentVault.WinUI/Tests/JlcOrderImport/README.md)。
已记录的构建和设备证据见[验证记录](../docs/history/verification.md)。
