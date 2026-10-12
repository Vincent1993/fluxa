# Changelog

## [Unreleased]

- Stable 发布需单独验收、递增 VERSION_CODE、补充版本条目并配置正式签名。

## [0.3.0-preview.1] - 2026-10-02

- 协调升级 AGP、Gradle、Kotlin／Compose、Hilt／Room KSP 与 AndroidX 稳定版本。
- compile／target API 37（Android 17），继续支持 API 26 起的设备。
- 登录页避开系统栏和键盘，列表消费布局边距，阅读字号在窄窗口可横向滚动。
- 沿用 Room schema v3 和显式迁移；添加同包同测试签名覆盖升级的数据保留验证。
- 保留默认关闭的后台刷新、会话加密、离线正文及待同步操作。

已知边界：真实 NewsBlur 账号尚未联调；图片不保证离线；无 OPML 或自动缓存清理。
Preview 持久签名及首次公开发布仍需单独安全配置和授权。

## [0.2.0-preview.1] - 2026-10-02

- 接入 NewsBlur 免费托管账号，逐订阅分页获取并合并文章缓存。
- 添加订阅、缓存阅读、本地搜索、未读与收藏筛选、字体档位。
- 已读和收藏先落盘，网络失败保留待同步队列，恢复后重放。
- 显式 Room 迁移保留旧数据；正文阅读限制 WebView 权限。
- Debug、Preview、Stable 独立安装；Preview 使用持久签名且关闭调试。
- 版本、tag、changelog、APK 包名和签名指纹纳入发布校验。

已知边界：真实 NewsBlur 账号尚未联调；单账号；图片不保证离线；
没有后台同步、OPML 或自动缓存清理。Preview 数据不会自动转入 Stable。
