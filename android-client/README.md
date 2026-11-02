# Android 客户端

本目录是 Jetpack Compose / Material 3 原生客户端。库存先保存到本机 SQLite，
离线可用；服务端同步是可选项。完整能力和操作见[使用指南](../docs/README.md)。

## 功能与代码入口

应用提供首页、库存、记录、设置四个主入口。库存中的“导入与扫码”包含添加元件、
批量嘉立创入库和项目用料；标签模板、预览、编辑及 M1 打印共用编辑器。
中文目录查询使用国内嘉立创 EDA，英文使用国际来源，不依赖识别服务器或 OpenAPI 密钥。

`app/src/main/java/com/componentvault/android/` 下的 UI、数据和同步按功能分工。
当前职责见[Android 模块边界](../docs/architecture.md#android-功能模块边界)，
照片和 BOM 预设通过独立阶段同步；打印队列、采购提醒等本机状态不等同于共享库存。

## 构建

安装 Android SDK 和符合项目要求的 JDK，配置 `ANDROID_HOME` / `ANDROID_SDK_ROOT`，
或复制 `local.properties.example` 为 `local.properties` 并填写本机 `sdk.dir`。
插件、SDK 和 Kotlin 版本以构建文件为准，不提交开发者机器的绝对路径。

在仓库根目录执行：

```powershell
.\android-client\gradlew.bat -p android-client assembleDebug --no-daemon
.\android-client\gradlew.bat -p android-client assembleRelease --no-daemon
```

Windows 也可使用 `scripts/android-gradle.ps1`。签名发布所需的四个 Secrets、工具链和排障见
[运维手册](../docs/runbook.md#android-release-secrets)。构建成功不代表相机、键盘或打印机已经实测。

## 界面开发与设备验证

使用 Android Studio 打开本目录。Compose Preview 位于
`app/src/main/java/com/componentvault/android/ui/screen/preview/`，使用合成数据，
不连接用户库存；可先检查尺寸、主题和语言，再用真实设备检查权限、相机、键盘和返回行为。
Compose 使用 Preview / Live Edit，不使用 XML 拖放设计器。

涉及升级或同步的设备检查须保留应用数据。已记录的验证路径和未覆盖边界见
[验证记录](../docs/history/verification.md)，M1 的纸张与设备限制见[打印指南](../docs/m1-printing-feature-matrix.md)。
