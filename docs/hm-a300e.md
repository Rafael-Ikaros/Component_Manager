# 汉印 HM-A300E 蓝牙打印

从 Android 开发版 0.7.9-dev.19 起，公开 APK 可通过经典蓝牙和 CPCL 直接打印 HM-A300E。每张标签发送后在队列里核对实物，再继续下一张。

## 当前范围

- 元件标签和自由标签共用编辑器、纸张预设和打印队列。应用将预览位图编码为 CPCL 单色栅格。
- 纸型可选间隙纸或连续纸。HM-A300E 真机已验证 40 × 60 mm 间隙纸。
- 人工核对期间保留蓝牙连接。队列完成、清除或应用退到后台时关闭连接。高级排障显示连接是否复用和发送结果。
- 通用驱动以人工核对确认出纸。蓝牙发送成功时也需要检查纸面；应用会保留需核对的任务供用户决定是否重试。

带汉印 SDK 的本地对照构建可用于复现旧 HM-A300E 队列。该路径会在发送前执行 SDK 握手和空闲状态查询。

## 型号与资料依据

核对日期：2026-11-08。

| 来源 | 已核对内容 | 使用边界 |
| --- | --- | --- |
| [用户提供的中文产品页](https://www.haningroup.com/cn/products/hma300-shipping-label-printer) | 页面型号是 HM-A300，支持 CPCL | 与 HM-A300E 不完全相同，不混用内存、接口版本和纸卷参数 |
| [HM-A300E 官方用户手册](https://foreverfile.hprtcloud.com/hygj/annex_file/2024_08_23/pDO95c2v8S1Og0ch.pdf) | 印刷页7蓝牙配对、页10菜单、页14规格；CPCL、203 dpi、最大打印宽72 mm、纸宽25–80 mm，黑标选配 | 下载文件共62页，中文规格位于PDF第20页；不是打印指令手册 |
| [HPRT HM-A300E 型号页](https://www.hprt.com/Product/Mobile-Receipt-Printer-HM-A300E.html) | 当前英文页给出 CPCL／ESC-POS 与双模蓝牙 | 网页与旧手册的蓝牙版本等参数有差异，应结合机身铭牌和自检页判断硬件版本 |
| [官方型号 SDK 下载目录](https://download.hprt.com/hprt/files/product_down_file/model/128/classify/47.html) | HM-A300E 的 Android CPCL SDK V1.22.05 用于本次连接和打印适配 | [SDK 下载入口](https://download.hprt.com/hprt/download/product_down_file/file/1162/classify/47.html) |
| [汉印中文下载中心](https://www.hprt.com.cn/XiaZai/) | 检索 HM-A300E，精确型号条目提供新版 Android／iOS／Linux UxPrint SDK、Windows CPCL／ESC SDK及 Windows 驱动 | 搜索还会返回 HM-A300ER、HM-A300ES；不能将它们的驱动或硬件能力归给 HM-A300E |
| [Zebra 原厂 CPCL 参考](https://www.zebra.com/content/dam/support-dam/en/documentation/unrestricted/guide/software/cpcl-link-os-pg-en.pdf) | 标准打印头、CG 原始二进制格式、FORM 与 PRINT | 属于 CPCL 标准参考，不能替代 HM-A300E 固件兼容性验证 |
| [LPrint 的 HM-A300E 适配记录](https://github.com/michaelrsweet/lprint/issues/189)及[上游源码](https://github.com/michaelrsweet/lprint/blob/master/lprint-cpcl.c) | 有该型号 USB 打印成功反馈 | 上游仍标为实验实现，状态函数没有真实完成确认；不将其当作蓝牙或批量通过证据，不整段移植其混合命令 |

## 多端 SDK 与接入选择

截至上述核对日期，中文下载中心的 HM-A300E 精确条目列出以下资料。
各端使用各自的开发包，不是同一个库文件直接跨系统运行。版本号也不能跨 SDK 系列比较新旧。

| 平台 | 官方开发包 | 核对程度与用途 |
| --- | --- | --- |
| Android | [UxPrint v1.0.0.7](https://foreverfile.hprtcloud.com/hygj/annex_file/2026_09_22/0znwk1Eq4zKmYbhC/UxPrint-Android-1.00.07-Universal-20260916.zip) | 已下载并静态阅读集成指南、CPCL／连接 API 与 Kotlin 示例；包中有 AAR、示例和 API 文档。本轮优先验证该平台 |
| Windows | [CPCL SDK v1.3.0.7](https://foreverfile.hprtcloud.com/hygj/annex_file/2026_05_27/Tb5QyN4ei2gSeBuD/Windows_CPCL%20SDK_v1.3.0.7.zip)；另有 [ESC SDK v1.3.0.21](https://foreverfile.hprtcloud.com/hygj/annex_file/2026_08_20/dIwYrqW7dkCWEgKN/Windows_ESC_SDK_v1.3.0.21.zip) | 已静态查看 CPCL 包的 x86／x64、ANSI／Unicode 库、C# 等示例及头文件。后续 WinUI 可评估原生互操作；本版未接入 |
| Linux | [Uxprint SDK v1.0.0.1](https://foreverfile.hprtcloud.com/hygj/annex_file/2026_08_13/XAtsJM65lr8Ydvyu/Linux_uxprint%20SDK_v1.0.0.1.zip) | 已确认精确型号下载条目；包内架构、接口及实际设备兼容性尚未核对 |
| iOS | [UxPrint v1.0.0.2](https://foreverfile.hprtcloud.com/hygj/annex_file/2026_06_18/FdDIjrPGZEXjR2EH/UxPrint-iOS-1.0.0.2-Universal-20260526.zip) | 已确认精确型号下载条目；本项目没有 iOS 客户端，本轮不接入 |

Windows 驱动用于系统打印队列，与供应用开发调用的 SDK 是不同资料。
WinCE SDK 也不能代替桌面 Windows SDK。通用 SDK 中的 USB、网络、蓝牙或多种打印语言接口，
不代表 HM-A300E 的每个硬件版本都具备这些接口或语言；仍以设备自检页和型号手册为准。

新版 Android CPCL 文档第35–39节和示例给出了 `openEndStatus`、`setTaskId`、
`getEndStatus`、`getPrintResult`、`getPrintResultWithoutId`，可用于后续验证任务完成回执。
示例还提供状态查询、图像及压缩选项，并保留 FORM／非 FORM 两种流程。
这些 API 的存在尚不能证明用户设备的固件支持完成通知，也不能直接把写入成功改成打印成功。
Windows CPCL 包的头文件中 `CPCL_GetStatus` 被注释，未核实到等价任务完成接口。

## 构建 SDK 测试包

从官方型号 SDK 下载目录获取 Android CPCL SDK V1.22.05，解压后把 `hprtCpclSdkDir` 指向 `Android_CPCL_V1.22.05/app/libs`。
目录包含 `CPCL_SDK_V1.22.05.jar`、`lzo_V1.0.jar` 与按 ABI 存放的 `libLZO.so`。

```powershell
& '.\android-client\gradlew.bat' -p android-client '-PhprtCpclSdkDir=D:/HPRT/Android_CPCL_V1.22.05/app/libs' assembleDebug
```

2026-11-12 核对了汉印型号下载页、Android CPCL V1.22.05 随附手册、JAR 和同型号下载目录中的 UxPrint Android 包。官方手册提供集成步骤。公开 APK 使用项目内的 CPCL 驱动，SDK 仅用于本地对照构建。[汉印型号下载页](https://www.hprt.com/Product/Mobile-Receipt-Printer-HM-A300E.html)

## 尺寸与命令

纸卷宽度与可打印宽度不同。纸卷可以宽80 mm，打印区域仍不能超过72 mm。
本版编辑器中的宽度表示可打印画布；例如80 mm纸卷也不能据此生成80 mm宽的图像。
应用允许 HM 画布宽25–72 mm、高10–300 mm；300 mm是首版应用限制，不是厂商长度上限。
偏移必须保持内容完整，不能用缩放二维码或裁掉内容的方式适应较窄纸张。

共用渲染器按 203 dpi 换算。40 × 60 mm 的标签生成 320 × 480 像素位图。公开 APK 使用 CPCL `CG` 写入栅格，间隙纸在 `PRINT` 前加 `FORM`，连续纸直接执行 `PRINT`。

本地 SDK 对照构建的发送过程如下。

1. 用 `portOpenBT` 执行蓝牙连接和 SDK 握手，再用 `getPrinterStatus` 检查空闲状态。
2. 按 SDK 说明第 3.17 节调用 `printAreaSize` 和 `printBitmapCPCL`，每次生成一张未压缩的标签。
3. 间隙纸调用 `form` 后执行 `print`，连续纸直接执行 `print`。打印机本机纸型须与实际耗材一致。
4. `print` 返回正值后在队列中核对实物。进入 `print` 后的异常或超时同样保留为待核对。

SDK 的端口和命令缓冲由整个进程共享。应用串行调用 SDK，发送和清理结束后才允许下一次 SDK 操作。
`getPrinterStatus` 用于发送前检查。SDK 的完成通知功能取决于固件，当前队列以人工核对为完成依据。

## 首次使用与验收

1. 在 Android 系统蓝牙中配对，装入标签纸。HM-A300E 可能在设备列表里显示为 `HM-A300-…`。配对 PIN 以设备为准，手册默认值为 `1234`。
2. 从元件详情或标签工具打开编辑器。点纸张尺寸，选择或输入纸宽和走纸长度。
3. 点「打印… → 打印机」，选已配对的设备并确认协议为通用 CPCL。已选设备会保存在本机。按耗材在纸张面板选择间隙标签或连续纸。
4. 在打印设置核对纸型、份数和预览，点「打印」。逐张检查实物，再在队列中标记完成、重打或跳过。
5. 如果预览居中而实际标签偏右，从「纸张 → 打印位置」减小水平偏移。校准按设备和耗材实测，起点为 0 mm。偏移使内容超出画布时，先给文字或二维码留出空间。

队列仍保存在本机 `label-print-queue.json`，新格式为v4。旧v1–v3队列按原 M1 解释；
新队列保存机型和纸型，未知值拒绝加载，不自动套用另一套打印指令。
清除任务仅清除打印队列，不清理库存。

## 真机记录与排障

2026-11-12，小米 9 与 HM-A300E 使用无 SDK 候选包和 40 × 60 mm 间隙纸，首张、约 116 秒后复用原连接的第二张、完成队列后新连接的第三张均各出一张完整标签。零偏移时实物位置一致地略偏右，应用预览的二维码居中。

在同一台设备上用新版界面打印仅含二维码的校准标签，水平偏移设为 −2.5 mm。实物只出一张，二维码完整，左右留白更接近。该值用于此次设备和纸张组合。

排障报告可附纸张宽高、纸型、机型、固件版本和指令语言。蓝牙地址、序列号及业务二维码内容应留在本机。

2026-11-09 在小米 9 安装含 SDK 的 `0.7.9-dev.11` 独立测试包，使用 40×60 mm 间隙纸。
两次任务的 SDK 握手返回 `0`、打印机状态返回 `0`、`print` 返回 `19246`。
首张已由用户确认出纸，照片可见二维码。首张发出约 60 秒后应用关闭通道 1，第二张经通道 2 重新连接并完成 SDK 写入，但未走纸。
`0.7.9-dev.12` 调整了人工核对期间的连接生命周期。初次复测建立了通道 1，
SDK 握手返回 `-3`，未发送打印数据。打印机重启后，首张完整出纸。
间隔超过 60 秒后，第二张沿用通道 1，SDK 状态返回 `0`、`print` 返回 `19246`，用户确认完整出纸。
两张均标记为已打印后，日志确认通道 1 已关闭。本次验收覆盖同一队列的双张打印。
