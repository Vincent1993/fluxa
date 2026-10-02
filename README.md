# Fluxa

自用 Android RSS 阅读客户端，使用 Kotlin、Jetpack Compose、Material 3、Hilt、Room。
当前默认接入 **NewsBlur 托管服务的免费账号**，无需 OAuth Client、API Key 或自建服务器。
使用逐订阅文章接口在本地合并列表，避开免费账号的 River of News 翻页限制。
账号的订阅启用与历史保留范围仍由 NewsBlur 决定，参见 [官方 API](https://www.newsblur.com/api)。

## 第一版能力

- NewsBlur 用户名 / 密码登录；密码不保存，会话 Cookie 保存在 Android 加密存储中。
- 获取启用订阅、按 HTTP / HTTPS RSS 地址添加订阅、按订阅阅读。
- 刷新与分页合并缓存，历史文章不被刷新删除；分页状态按订阅保存。
- 已读和收藏在本地事务中先保存，待同步操作落盘，成功后才删除。
- 离线阅读缓存正文，重启保留标记；恢复网络后刷新或点待同步重试。
- 本地标题、来源、正文搜索，全部 / 未读 / 收藏筛选。
- 阅读页返回、收藏与 16 / 18 / 20 / 24 号字体。

单账号模型：首次成功登录后缓存绑定用户名，再次登录需使用同一账号。
旧 Inoreader 的 `fluxa.db` 与令牌保留，当前 NewsBlur 使用独立的 `newsblur.db`，
不会向新后端重放旧操作。旧实现仅保留为迁移和回归参考，并非当前可选择的后端。

## 构建与安装

需要 JDK 17、Android SDK platform 35 / build-tools 34.0.0。Gradle 8.9 Wrapper 已附带校验哈希。
在 git 忽略的 `local.properties` 设置 SDK 路径：

```properties
sdk.dir=/absolute/path/to/android-sdk
```

```sh
export JAVA_HOME=/absolute/path/to/jdk17/Contents/Home
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.fluxa.app/.MainActivity
# API 26+ 测试设备：
./gradlew :app:connectedDebugAndroidTest
```

本次 Mac 的隔离工具链位于仓库同级的 `../toolchain`，可先 `source ../toolchain/env.sh`。
已有安装升级应使用相同签名；为解决签名冲突而卸载会删除本地数据。

运行流程：

1. 在 NewsBlur 官网创建免费账号，再于 Fluxa 输入用户名和密码登录。
2. 用“添加订阅”输入 RSS 地址，刷新获取文章。
3. 选择订阅或全部文章；阅读、标记已读、收藏。
4. 断网后阅读缓存，标记仍在本地保存；联网后刷新或点待同步重试。
5. 在 NewsBlur 网站核对最终状态。初次安装且未登录时缓存为空。

## 数据与验证

数据库 v3 使用显式迁移，兼容原主干 v1 和 PR #4 / #5 / #6 的不同 v2 schema：
保留正文、标记、来源 / 标签、同步状态及旧实验操作；没有 destructive fallback。
未知 schema 会拒绝打开，避免静默删数据。旧笔记等实验操作保留但不重放。

```sh
python3 scripts/verify-migration-sql.py
```

真实执行结果、报告与 APK 指纹见 [验证记录](docs/validation-2026-10-02.md)。
2026-10-02 的本地验证已通过：debug APK、26/26 单元测试、7/7 API 35 设备测试；
lint 为 0 错误 / 17 警告。真实 NewsBlur 用户账号尚未联调。
包含 Room/Robolectric、MockWebServer 协议与离线重放、ViewModel、
AndroidKeyStore、Compose，以及真实 Activity / Hilt / Room / WebView 的离线重启验证。
测试夹具不代替真实 NewsBlur 用户账号验收。

## 已知边界

- 仅缓存正文 HTML，远程图片和原文链接需要网络。
- 本地搜索仅覆盖已经同步的文章。
- 手动 / 页面进入刷新，无后台轮询；已读以最多 5 条批次重放。
- 首版同步最多 64 个启用订阅；没有 OPML、删除订阅或账号切换。
- 无自动缓存清理；长期使用数据库会增长。
- 会话过期需重新登录，保留离线缓存和待同步操作。
- 真实 NewsBlur 账号的登录、添加订阅与最终服务端状态仍需用户安装后验收。

CI 验证测试、lint 与 debug APK；release job 仅在 tag 或明确 release_tag 时发布。
本次仅本地工作，没有推送、合并 PR、部署或发布。
