# 设置 / 关于与联网更新

Android 和 Windows 的“设置 → 关于”显示应用名称、作者 Rafael-Ikaros、当前安装版本、
项目地址和 GPLv3 开源许可，并提供独立的“检查更新”区域。Android 从主导航的“设置”
进入，Windows 从侧栏底部的“设置”进入。检查不需要登录 GitHub、自建服务器或 API 密钥，
也不会向 GitHub 发送库存同步令牌。

## 用户操作

1. 打开“设置 → 关于”，选择“更新通道”，然后点击“检查更新”。默认是正式版。
2. 正式版只检查稳定 Release；Dev 测试版检查公开预发布和正式版，选择更高版本。
   例如 `0.7.4-dev.2` 高于 `0.7.4-dev.1`，但低于 `0.7.4` 正式版。
3. 发现更高版本时查看发布日期和更新日志，点击本平台下载按钮。
4. Android 在应用内显示 APK 下载进度，后台下载由系统下载管理器执行；离开页面或重新打开应用后可继续查看。支持取消、失败重试和删除安装包。
5. Android 下载完成后点击“安装更新”，首次使用按提示允许元件仓库安装应用，再在系统安装界面确认。Windows 仍通过浏览器下载便携 ZIP。

已是最新、本地版本更高、没有 Release、限流、网络错误和缺少安装包会分别提示。
没有更新时仍可打开发布页查看。0.7.8 起两端每次启动按当前正式/Dev 通道检查一次，仅发现更高可用版本时弹窗；已是最新或检查失败保持静默。弹窗复用现有说明和下载入口，手动检查仍保留完整结果提示，不增加后台周期检查。
Android 发布说明使用文本显示；Windows 使用 Markdown 渲染标题、列表及链接。发布说明不执行其中的 HTML 或脚本。

通道选择保存在本机，不会修改库存或同步服务端。选择 Dev 不会立即安装，也不会换应用身份。
切回正式版后，如果当前 dev 高于最新正式版，应用不会自动降级；可继续使用当前版本，
等待同一版本或更高的正式版发布后再升级。不要通过卸载清数据来切换通道。
0.7.3 等旧版尚无通道选择，需要先从 GitHub 手动安装一次支持此设置的新版本。

## 发布来源与文件名

版本源固定为 [Rafael-Ikaros/Component_Manager Releases](https://github.com/Rafael-Ikaros/Component_Manager/releases)，
正式通道 API 为 `https://api.github.com/repos/Rafael-Ikaros/Component_Manager/releases/latest`；
Dev 通道读取公开 Release 列表。
读取 `tag_name`、`published_at`、`body`、`html_url` 和 `assets`。本项目使用
`vMAJOR.MINOR.PATCH` 正式发布标签和 `vMAJOR.MINOR.PATCH-dev.N` 测试标签。
草稿及无法识别的版本不作为可安装更新；预发布只对主动选择 Dev 的用户显示。
API 的公开资源读取契约见 [GitHub 官方文档](https://docs.github.com/en/rest/releases/releases#get-the-latest-release)。

| 平台 | Release 资产 |
| --- | --- |
| Android | `component-vault-android-release.apk` |
| Windows 便携版 | `component-vault-windows-portable-x64.zip` |

这些名称与 `.github/workflows/release.yml` 一致。只有正确名称且属于同一仓库、
同一 tag 的 HTTPS GitHub 下载地址才会提供下载操作。缺失资产不会被源代码 ZIP
替代，也不会通过猜测地址伪装成可安装更新。

## 安装注意事项

Android 正式版和发布的 Dev APK 使用同一发布签名、保留相同应用身份。开发 Debug APK 与
正式 Release APK 的签名通常不同，不能直接覆盖；不要为更新随意卸载当前应用，
卸载可能清除本地库存。只有点击“安装更新”时才引导开启安装权限，不静默安装或自动卸载旧版。

从 `0.7.8-dev.9` 起 Android 提供应用内下载；旧版本需先通过原来的下载入口安装一次。
安装包保存在应用专属下载目录，取消、替换下载或升级后再次进入更新页面时清理旧包；
也可使用“删除安装包”手动清理。无需存储权限，不接触库存数据库。
内置下载仍需联网访问 GitHub 及其附件下载域名，不是下载镜像或网络代理。
如系统下载管理器被禁用、空间不足或网络不可达，会提示失败；仍保留发布页面作为备用入口。

Windows 发布自包含便携 ZIP，已停止发布 MSIX，不需要导入包签名证书。
先关闭程序再解压更新文件；覆盖程序文件不等于自动备份库存。

更新界面和解析逻辑可通过本地测试验证；实际下载、签名覆盖安装和设备数据保留仍需
使用正式 Release 在目标设备验证。本轮开发没有执行安装包下载或覆盖安装。
