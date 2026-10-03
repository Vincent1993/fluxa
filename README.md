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
- 可选后台刷新：默认关闭，账户与同步设置中开启；每小时尝试同步全部启用订阅。

单账号模型：首次成功登录后缓存绑定用户名，再次登录需使用同一账号。
旧 Inoreader 的 `fluxa.db` 与令牌保留，当前 NewsBlur 使用独立的 `newsblur.db`，
不会向新后端重放旧操作。旧实现仅保留为迁移和回归参考，并非当前可选择的后端。

## 构建与安装

需要 JDK 17、Android SDK platform 37.0 / build-tools 36.0.0。Gradle 9.4.1 Wrapper 已附带校验哈希。
在 git 忽略的 `local.properties` 设置 SDK 路径：

```properties
sdk.dir=/absolute/path/to/android-sdk
```

```sh
export JAVA_HOME=/absolute/path/to/jdk17/Contents/Home
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.fluxa.app.debug/com.fluxa.app.MainActivity
# API 26+ 测试设备：
./gradlew :app:connectedDebugAndroidTest
```

本次 Mac 的隔离工具链位于仓库同级的 `../toolchain`，可先 `source ../toolchain/env.sh`。
Debug 的包名为 com.fluxa.app.debug；Preview 为 com.fluxa.app.preview；
Stable 为 com.fluxa.app。渠道可并存，缓存和会话独立。同渠道升级需要固定签名。
版本由 version.properties 管理；新发布的 versionCode 必须递增。
渠道、签名和发布操作见 [发布指南](docs/releases.md)，分支规范见 [贡献约定](CONTRIBUTING.md)。

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
2026-10-02 的后台刷新版本本地验证已通过：三渠道 APK 各自构建、各 34/34 单元测试、
9/9 API 35 设备测试；各渠道 lint 为 0 错误 / 19 警告。
详见 [后台刷新验证](docs/background-sync-validation-2026-10-02.md)。真实 NewsBlur 用户账号尚未联调。
Android 17 升级的工具链、三渠道结果、API 26 覆盖升级及 API 37／16 KB 设备 CI
见 [Android 17 验证记录](docs/android-seventeen-validation-2026-10-03.md)。
包含 Room/Robolectric、MockWebServer 协议与离线重放、ViewModel、
AndroidKeyStore、Compose，以及真实 Activity / Hilt / Room / WebView 的离线重启验证。
测试夹具不代替真实 NewsBlur 用户账号验收。

## 已知边界

- 仅缓存正文 HTML，远程图片和原文链接需要网络。
- 本地搜索仅覆盖已经同步的文章。
- 手动 / 页面进入刷新，后台刷新可选。后台任务需不计费网络和充足电量，系统可能延迟；已读以最多 5 条批次重放。
- 首版同步最多 64 个启用订阅；没有 OPML、删除订阅或账号切换。
- 无自动缓存清理；长期使用数据库会增长。
- 会话过期需重新登录，保留离线缓存和待同步操作。
- 真实 NewsBlur 账号的登录、添加订阅与最终服务端状态仍需用户安装后验收。

## GitHub Actions 自动构建

[Android CI](.github/workflows/android-ci.yml) 对 main push、目标为 main 的 PR 和手动触发运行：
Debug / Preview / Stable 三个构建类型各自执行 JUnit / Robolectric、lint、构建和 APK 身份校验。
普通 CI 不读取 NewsBlur 密码、API Key 或签名 secrets。
使用 JDK 17、Gradle 9.4.1 Wrapper、SDK 37.0 / build-tools 36.0.0；Gradle 依赖和发行包使用基础缓存，
只有 main 写缓存，PR 只读。Debug 校验调试签名；Preview / Stable 验证未签名构建，
不把它们作为可安装版本交付。所有 action 使用固定完整 SHA。

获批准推送并运行后，在仓库 Actions → Android CI → 对应运行的 Artifacts 下载：

- `fluxa-debug-apk`：解压得到可安装的 `app-debug.apk`，仅全部验证通过时上传。
- `fluxa-validation-debug/preview/stable`：各渠道 JUnit XML / HTML、lint 与 APK 元数据，
  测试失败时也尝试上传。

产物保留 14 天。首次将新工作流合入默认分支 main 后，Actions 页面才会显示
“Run workflow”手动按钮；现有 PR 可先自动运行。原单渠道记录见 [CI 验证记录](docs/ci-validation-2026-10-02.md)。

CI 使用临时 Debug 签名，不保证与本地 APK 或另一轮 CI 的签名相同，
不能直接覆盖不同签名的已有安装；不要为安装 CI 产物卸载而丢失缓存。
另有 [Prepare Android Release](.github/workflows/android-release.yml)，仅从 main 手动触发：
先验证候选构建，再在受保护环境使用持久密钥签名，最后创建 tag 和 draft Release。
缺少密钥或指纹不匹配会失败；不会把未签名 APK 发布到 Release。
Preview 草稿带 prerelease 标记；公开发布需明确批准。Stable 默认禁用。
当前预览候选为 v0.3.0-preview.1 / versionCode 4，密钥生成与上传需用户安全接管。
配置和完整操作见 [发布指南](docs/releases.md)。
