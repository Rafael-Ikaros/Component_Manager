# 汉印 HM-A300E：Android 实验适配

本页记录型号依据、当前实现与真机验收步骤，供后续补充资料使用。
适配复用现有标签编辑器，不增加独立打印页面，不改变库存数据。

## 当前范围

- Android 已配对的 Classic Bluetooth／SPP 连接；不使用 M1 私有型号、状态或打印指令。
- 元件标签和自由标签共用原预览、二维码内容及队列；图片转换为标准 CPCL 单色栅格。
- 可选间隙纸或连续纸。黑标传感器在所提供手册中列为选配，本版不开放黑标模式。
- 完整数据写出仍是“已发送、待实物核对”，不是设备确认打印完成。
  每张后停止后续发送，核对实物后才继续。缺纸、开盖和完成回执尚未解析。
- 当前没有 Windows 直连、BLE 写入、自动纸张学习、永久参数修改或固件更新功能。

这是代码候选，尚未由 HM-A300E 真机验证。单张、连续定位和异常恢复均需实际设备验收。
候选提交 `36efa94` 已通过[主 CI](https://github.com/Rafael-Ikaros/Component_Manager/actions/runs/42495936800)，
构建和自动检查不等同于实际出纸验收。

## 型号与资料依据

核对日期：2026-11-08。

| 来源 | 已核对内容 | 使用边界 |
| --- | --- | --- |
| [用户提供的中文产品页](https://www.haningroup.com/cn/products/hma300-shipping-label-printer) | 页面型号是 HM-A300，支持 CPCL | 与 HM-A300E 不完全相同，不混用内存、接口版本和纸卷参数 |
| [HM-A300E 官方用户手册](https://foreverfile.hprtcloud.com/hygj/annex_file/2024_08_23/pDO95c2v8S1Og0ch.pdf) | 印刷页7蓝牙配对、页10菜单、页14规格；CPCL、203 dpi、最大打印宽72 mm、纸宽25–80 mm，黑标选配 | 下载文件共62页，中文规格位于PDF第20页；不是打印指令手册 |
| [HPRT HM-A300E 型号页](https://www.hprt.com/Product/Mobile-Receipt-Printer-HM-A300E.html) | 当前英文页给出 CPCL／ESC-POS 与双模蓝牙 | 网页与旧手册的蓝牙版本等参数有差异，应结合机身铭牌和自检页判断硬件版本 |
| [官方型号 SDK 下载目录](https://download.hprt.com/hprt/files/product_down_file/model/128/classify/47.html) | Android SDK CPCL for HM-A300E V1.22.05；[下载入口](https://download.hprt.com/hprt/download/product_down_file/file/1162/classify/47.html)；已静态核对附带说明及命令结构 | 不执行或集成厂商 JAR，静态核对不能代替真机验收 |
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

当前候选仍使用独立 CPCL 栅格编码与现有 SPP 连接，不集成或分发下载的厂商二进制。
先用现有候选验证单张和第二张定位，再结合新版 SDK 核对状态、完成回执与流控；
确认可用后，才决定接入方式和自动批量继续的条件。下载的包保留在本机临时资料目录，未提交仓库。

## 尺寸与命令

纸卷宽度与可打印宽度不同。纸卷可以宽80 mm，打印区域仍不能超过72 mm。
本版编辑器中的宽度表示可打印画布；例如80 mm纸卷也不能据此生成80 mm宽的图像。
应用允许 HM 画布宽25–72 mm、高10–300 mm；300 mm是首版应用限制，不是厂商长度上限。
偏移必须保持内容完整，不能用缩放二维码或裁掉内容的方式适应较窄纸张。

共用渲染器按现有203 dpi换算，72 mm画布为575像素；CPCL 每行按整字节存储，
补齐到72字节，最后不足一字节的位补白。不会把二维码拉伸成576像素。
协议最大宽576 dots、应用最大高2400 dots不代表厂商缓冲区容量。

发送结构如下：

```text
! 0 200 200 <图像高度dots> 1\r\n
PAGE-WIDTH <图像宽度dots>\r\n
CG <每行字节数> <高度> 0 0 <准确长度的原始图像字节>\r\n
FORM\r\n
PRINT\r\n
```

连续纸省略 FORM。头部200参数沿用 CPCL 原厂示例，不将其直接等同于渲染器物理分辨率。
CG 中的零字节和换行字节属于图像数据，不能按文本删除或重新编码。
发送不修改打印机保存的纸型，打印机本机纸型仍需与实际耗材一致。

旧版 Android CPCL SDK V1.22.05 说明的3.1、3.4、3.17节，以及 `CommandData.getPrintAreaSizeCommand`、
`PrinterHelper.printBitmapCPCL` 的静态字节码，与上述未压缩栅格及结束命令结构相符。
SDK 的3.32节明确 `getPrinterStatus` 是非实时查询，打印时无效；它不等于每张打印完成回执。
本版尚未接入该查询，也不据其存在自动连续发整批。

## 首次使用与验收

1. 确认机身型号为 HM-A300E，装入准备测试的纸张，在系统蓝牙中完成配对。
   若要求 PIN，手册默认值为1234，以设备实际设置为准；关闭其它占用打印机连接的应用。
2. 进入现有“标签编辑与打印”的打印区域，选择 HM-A300E CPCL，再选择实际纸型。
   机型与纸型在创建队列前选择；已有队列绑定原机型，需先核对并清除旧任务才能切换。
3. 在纸张区域输入实际可打印宽度和标签高度。首次使用0度旋转、零偏移，不沿用 M1 的校准值。
   需要调整打印机本机菜单时，先断开蓝牙连接，按手册操作。
4. 先建立一张带文字和二维码的标签任务，确认预览完整，再发送。
5. 查看是否实际出纸、文字和二维码是否完整落在同一张纸内、是否多走空白纸，并扫码核对内容。
   “已发送”不能代替这些检查。
6. 确认第一张后，再验证第二张的位置与第一张一致。发送中断、断线或应用退出后的待核对项，
   不会自动重发；先看实物，再选择完成、重试或跳过。

队列仍保存在本机 `label-print-queue.json`，新格式为v4。旧v1–v3队列按原 M1 解释；
新队列保存机型和纸型，未知值拒绝加载，不自动套用另一套打印指令。
清除任务仅清除打印队列，不清理库存。

## 后续补充资料与真机记录

优先补充：纸张宽×高、间隙／连续／黑标类型，机身准确型号，以及自检页上的固件与指令语言。
设备蓝牙地址、序列号和私人标签内容不必公开。后续结合已取得的新旧 SDK 文档，
优先实测型号查询、状态位、打印完成通知与流控，再决定是否开放自动连续打印。

真机验收尚未完成，不将通用 CPCL 成功、SPP 连接成功或本地测试通过推广为设备兼容保证。
