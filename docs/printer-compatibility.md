# 蓝牙标签机：当前进度与测试

0.7.4 已支持 Android M1 日常元件和批量打印。2026-09-25 在 Android 15、40×60 mm 间隙纸上完成无边框标签实测：单张与连续两张均完整、位置一致、无多余空白纸，二维码可扫描到正确元件。该台 M1 的水平校准为 `+0.5 mm`，不代表所有设备都应使用这个值。

当前 Android 已将标签编辑、预览、导出和蓝牙打印整合到“标签编辑与打印”页面（元件详情 → 生成标签，或库存 → 更多 → 标签工具），普通流程已移除独立诊断弹窗。日常操作及开发版验收边界见 [M1 打印指南](m1-printing-feature-matrix.md)。高级排障保留打印报告；Windows 目前继续使用导出流程，其他品牌尚未开放直接打印。

现有 M1 通信采用汉码实机 HCI 对照确认的 1 KiB 分帧预算，并区分异步处理通知。报告中的“已发送”不是实物完整性的证明。其他纸张尺寸、固件和长批量仍需相应设备测试。

以下保留早期适配证据，**不作为当前开发版菜单教程**。旧版诊断入口和固定测试标签已从新的日常界面撤下；原始分析见 [M1 APK 分析记录](m1-apk-analysis.md)。

## 历史：M1 连接测试（不出纸）

1. 安装包含本功能的新版 Android APK，保留现有应用数据。
2. 开启 M1，在手机系统蓝牙设置中完成配对，然后关闭汉码及其他打印 App。
3. 打开 **元件详情 → 标签预览 → 蓝牙标签机诊断 → M1 测试**。
4. 点击“扫描设备”，允许所需权限，在列表中选择已配对的 M1。
   仅 BLE 设备或尚未配对的设备不能启动 SPP 查询，可用页面内入口前往系统设置配对。
   从设置返回后重新扫描，以刷新配对状态。
5. 等待查询结束。页面显示进度并允许停止；关闭页面或切到后台会中止并断开连接。
6. 点击“复制诊断报告”，通过应用反馈发送，补充是否开盖、是否装纸等实际情况。
   失败或超时也请保留报告，已经完成的阶段仍有诊断价值。

此测试先建立 Classic SPP 连接，查询型号，仅在型号确认为 M1 后查询状态。
不会出纸、改变浓度、校准纸张或读取序列号。报告只记录白名单结果、阶段及状态码，
不会包含任意设备名称、蓝牙地址、原始回包或标签内容。
“查询完成”不表示打印功能已经可用；缺纸等状态也不等于蓝牙连接失败。

报告中 `stage=connected` 表示 SPP 已连接，`query_model=matched` 表示回复匹配 M1，
`status_result=received` 表示取得可识别状态。`status_source=query/async` 区分主动查询回复
与异步状态。`model_reply_bytes`、`status_reply_bytes` 只记录字节数；
`no_response` 表示没有回复，`unrecognized_response` 或 `invalid` 表示收到但无法识别。
`status_code=0` 是当前已知状态位均未置位，不等于已完成打印。
只读连接测试报告保留 `print_tested=false`。

## 历史：通用服务信息诊断

1. 安装本次 GitHub Release 的 Android APK，保留原应用数据。
2. 打开打印机，先关闭汉码等其他占用连接的 App。
3. 打开标签预览中的诊断入口，选择“服务信息”，点击“扫描设备”，允许系统蓝牙权限。
4. 扫描持续 10 秒；列表包括附近 BLE 设备与系统已配对设备。Android 11 及以下
   还需要定位权限和开启定位。只支持经典蓝牙的设备需要先在系统设置中配对。
5. 选择自己的 M1。BLE 服务发现最长等待 15 秒；经典蓝牙仅列出系统缓存的服务。
6. 点击“复制诊断报告”，在应用反馈中粘贴，并手动填写打印机型号与是否找到设备。
   报告不包含设备名称、蓝牙地址、标签明文。请勿将其他设备的截图一起提交。

“已取得服务信息”仅说明服务发现完成。“服务信息”模式不写入特征值、不发送查询或打印命令，
不会出纸。停止、关闭对话框或应用进入后台都会清理扫描与连接；可重新扫描。
服务 UUID 能帮助确定后续接入方式，仍不能单独证明打印协议兼容。

## M1 SPP 实机查询通过（2026-09-23）

用户在同一台 M1 上使用 Component Vault 0.7.3 (26)、Android SDK 37 完成测试，
提交的报告为 `status=Complete`，已验证以下链路：

| 字段 | 实测结果与边界 |
| --- | --- |
| `transport=spp`、`paired=true`、`device_type=3` | 已配对双模设备通过 Classic SPP 测试 |
| `stage=connected` | RFCOMM socket 建立成功 |
| `query_model=matched`、`model=M1`、`model_reply_bytes=2` | 型号回复匹配 M1 |
| `status_reply_bytes=17`、`status_source=query` | 收到主动查询回复；当前解析器只解释其中已知的状态字段 |
| `status_result=received`、`status_code=0` | 已识别状态位均未置位，没有上报当前解析器已知的异常 |
| `print_tested=false` | 未发送打印命令，尚未验证图像、尺寸、走纸或完成回执 |

这次实测将该设备从“服务发现成功”推进到“SPP 型号与状态查询成功”。
17 字节的回复长度不表示已经理解其全部字段，也不应把状态 0 当作打印完成。
不将单台设备的结果自动推广到其他 M1 固件或其他型号。
在此基础上继续进行 LZO 编码离线验证和单张测试标签，无需因本次成功查询而额外采集 HCI 日志。

## 历史：0.7.4-dev.2 至 dev.5 单张测试标签

Dev.4 两次“不追加走纸”实测不再多出空白纸，且第二次复用连接，但用户确认底部内容缺失。
Dev.5 新增 **短走纸（试验）** 作为第三种对照，未默认启用；是否能完整出纸及连续定位仍待验证。
当时的复测步骤见 [Dev.5 说明](releases/0.7.4-dev.5.md)。不调整纸型或学习参数。

**后续实测：短走纸未通过验收。** 用户确认内容完整，但同一幅图被间隙分开，
二维码跨到下一张标签；不能将该模式用于正常元件标签打印。
当时改为 [汉码与本应用的实际通信对比](m1-protocol-capture.md)，不继续调整固定走纸值。

Dev.3 用户已验证再次出纸，但在 40×60 mm 间隙纸上仍报告偏左、多出空白纸；同卷纸在汉码中正常。
Dev.4 按上游 M1 配置改为右对齐，并提供默认关闭、每次确认重置的“不追加走纸”对照选项。
该选项只省略末尾走纸，纸张可能停留在机内；不能视为正常打印模式或多走纸已修复。
具体步骤见 [Dev.4 复测说明](releases/0.7.4-dev.4.md)。

`0.7.4-dev.2` 新增“纸张与位置”：可以保存本地纸张宽高、旋转和图像偏移，
并预览结果。宽指沿打印头方向，高指走纸方向，请填写实物尺寸。
初始值来自已有 QR 模板，动态文字模板则使用默认 QR 纸张值；默认值不是自动识别出的纸张尺寸。
偏移会裁切画布外内容，首次校对请使用零偏移。本功能不执行设备纸张学习、固件页型配置或校准。

1. 在元件详情的标签预览中选择已有的 **10×40 mm QR** 或 **30×40 mm QR** 模板。
   打印栅格对应纸张宽 40 mm、高 10 / 30 mm，203 dpi；不使用预览图的高分辨率直接发送。
2. 装入相应尺寸的间隙标签纸，关闭汉码等其他打印 App。
3. 打开 **蓝牙标签机诊断 → M1 测试 → 扫描设备**，找到已配对的 M1。
4. “读取型号与状态”仍然不会出纸。点击单独的 **打印一张 M1 测试标签**，核对尺寸后点击“打印一张”。
5. 每次仅发送一张固定的 `M1 TEST`、尺寸文字、边框和二维码，不使用所选元件的数据，
   不跟随标签预览的份数或附带文字标签设置。
6. 显示“数据已发送”后，检查是否只出一张、边框/文字是否完整、走纸是否停在标签间隙，
   测试二维码应可读出 `M1-TEST`。请反馈实际效果与诊断报告。

发送前会重新查询型号并要求状态码为 0。停止、切到后台或超时会关闭连接；
已经发出的数据无法撤回，应用不会自动重发。出现中断时先检查打印机是否已经出纸，
再决定是否重新测试。`SentUnconfirmed` 只代表数据发送结束，不是打印机完成回执。
打印后型号回复不明确时最多重试一次只读查询；持续不明确记录警告并释放连接，
不会仅凭未知回复内容认定连接断开。报告中的 `feed_mode` 表示本次选择，
`form_feed_commands` 表示已完成写入的末尾走纸指令数。
`short_feed_commands` 单独表示已完成写入的短走纸指令数；短走纸模式不会再追加标签走纸。

LZO literal-only 编码规则已用独立 `lzokay 1.1.8` 解码器验证边界与随机样本，
不依赖厂商 APK 的 JNI；它产生合法 LZO1X 字节流，但不压缩数据体积。
离线编码验证不能替代实际出纸验收。其他品牌暂不套用此协议。

## M1 初始服务报告结论（2026-09-22）

用户通过 Component Vault 0.7.1 (24)、Android SDK 37 提交了成功的服务发现报告。
这是该台设备的实测结果，不自动推广到所有 M1 固件或其他型号。

| 报告字段 | 已确定的含义 | 尚未验证的内容 |
| --- | --- | --- |
| `status=Complete`、`probe=services_only` | App 已完成 BLE 连接和 GATT 服务发现 | 未执行打印或测试写入 |
| `device_type=3`、`paired=true` | Android 将设备识别为 Classic/BLE 双模，系统已配对 | 汉码实际打印使用哪一种连接 |
| `cached_service=00001101-…` | 缓存中存在 SPP 服务 UUID，可作为 RFCOMM 候选 | 本次诊断未建立 RFCOMM 连接 |
| `FF00 / FF02`、`properties=12` | `0x0C = WRITE + WRITE_NO_RESPONSE`，设备声明允许这两种写入 | 包格式、握手、分包、流控、最大负载 |
| `FF00 / FF01`、`properties=16` | 支持通知，可作为接收候选 | 通知订阅是否成功、通知数据的具体用途 |
| `FF00 / FF03`、`properties=16` | 同样只声明通知能力 | 不能将其当作另一条写入通道 |
| `1800`、`1801 / 2A05` | 标准 Generic Access / Generic Attribute 服务；`2A05` 的 32 是 Indicate | 这些不是标签打印命令或“打印完成”回执 |

属性位依据 Android 官方
[BluetoothGattCharacteristic](https://developer.android.com/reference/android/bluetooth/BluetoothGattCharacteristic)，
服务编号依据 Bluetooth SIG [Assigned Numbers](https://www.bluetooth.com/specifications/assigned-numbers/)。
当时的服务发现探测不读取特征值、不枚举描述符，也未订阅通知，因此这份报告不包含打印协议字节。
`WRITE` 的蓝牙层响应也不能直接解释成“标签已打印”。

### 开源实现对照

- [fichero-printer 的 D11s 协议](https://github.com/0xMH/fichero-printer/blob/main/docs/PROTOCOL.md)
  也有 FF00 / FF02 写、FF01 和 FF03 通知，但其命令是在 **AiYin D11s** 上验证的，
  包含型号相关的启停和位图帧。相同 UUID 不能证明 M1 使用该协议。
- [labelife 的 TSPL 文档](https://github.com/thermal-label/labelife/blob/main/docs/protocol/tspl.md)
  将其部分型号的 FF03 用作写入通道，与本次 M1 报告不同；该页自身也标明了硬件验证限制。
  可作为对照资料，不能直接移植成 M1 驱动。
- [flutter-label-printer](https://github.com/gogovan/flutter-label-printer)
  曾验证 HPRT HM-A300L（CPCL）和 N41BT（TSPL），没有 M1 支持证据。
  汉印同品牌不同机型不能视为同一协议。
- 汉印官方 [HM-T260/T360 参数](https://www.hprt.com.cn/ChanPin/173.html)
  明确列出“汉码打印协议”，说明还需要检查厂商自己的协议路径；该页面不能证明 M1 同样使用它。

本轮未复制这些项目代码，也未引入其依赖。后续复用时应逐个核实许可证和适用机型。

### 历史：APK 分析后的取证计划

用户已提供清单版本为 3.3.4-cn 和 3.4.6-cn 的汉码 APK。
两版随包 M1 配置均通过 `print_mode=5` 选择 `ESC_POLI`，实际调用 LZO 分包位图路径；
3.4.6 的交互连接流程明确使用 Classic SPP，和报告中的缓存服务相符。
下一步先验证 SPP 名称/状态查询，再验证单张标签；若与静态证据不符，
再采集汉码正常连接并打印测试标签时的 HCI 记录。无需为继续研究重复提交 APK。
安装包是否加壳与蓝牙负载是否加密是不同问题，不能从“APK 未加密”推出通信明文。
仅重复运行当前服务诊断不会提供缺失的命令字节。

采集步骤、Windows 导出命令和实机验收顺序见 [M1 协议取证指南](m1-protocol-capture.md)。

## 核实过的资料

| 来源 | 可借鉴内容 | M1 支持证据 |
| --- | --- | --- |
| [HPRT M1 官方页面](https://www.hprt.com/Product/Label-Maker-M1.html) | 203 dpi、蓝牙连接和产品资料入口 | 支持蓝牙；未声明具体传输协议 |
| [M1 专属 SDK 目录](https://download.hprt.com/hprt/files/product_down_file/model/290/classify/47.html) | 厂商 SDK 获取入口 | 2026-09-22 查询时没有可下载条目 |
| [汉码官方下载页](https://hm.hprt.com/download/)（来自[汉印 APP 目录](https://www.hprt.com.cn/APP/)） | Android App 获取入口 | 官方直链未确定；另已静态分析用户提供的 3.3.4-cn / 3.4.6-cn，见 APK 分析记录 |
| [niimblue](https://github.com/MultiMote/niimblue)（MIT） | NIIMBOT 的连接、协议与标签工作流 | 无汉印 M1 支持记录 |
| [phomymo](https://github.com/transcriptionstream/phomymo)（MIT） | Phomemo 的多协议与多机型适配 | 无汉印 M1 支持记录 |
| [phomemo-tools](https://github.com/vivier/phomemo-tools)（GPL-3.0） | Linux/CUPS 与部分 Phomemo 协议 | 无汉印 M1 支持记录 |

后续按具有明确证据的品牌协议逐个适配，不把“蓝牙打印”视为通用协议。
M1 后续已有会话与 40×60 mm 出纸验证，范围见本文开头及打印指南；其他设备、纸张和长批量仍需对应验证；取得厂商 SDK 后也需核对其分发许可。
不能把 HT300/HT330、通用 ESC/POS、TSPL 或其他品牌驱动直接当作 M1 驱动。
Android 实现参考官方 [权限](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)、
[扫描](https://developer.android.com/develop/connectivity/bluetooth/ble/find-ble-devices) 与
[GATT 连接](https://developer.android.com/develop/connectivity/bluetooth/ble/connect-gatt-server) 契约。
