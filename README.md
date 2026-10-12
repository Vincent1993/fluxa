# Fluxa

自用 Android RSS 阅读客户端，使用 Kotlin、Jetpack Compose、Material 3、Hilt、Room。
当前默认接入 **NewsBlur 托管服务的免费账号**，无需 OAuth Client、API Key 或自建服务器。
使用逐订阅文章接口在本地合并列表，避开免费账号的 River of News 翻页限制。
账号的订阅启用与历史保留范围仍由 NewsBlur 决定，参见 [官方 API](https://www.newsblur.com/api)。

## 当前仓库已有能力

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

当前 main 源码需要完整 JDK 17（含 javac）、Android SDK platform 35 / build-tools 34.0.0，
设备安装另需 platform-tools。Gradle 8.9 Wrapper 已附带校验哈希，始终使用 `./gradlew`，
不使用机器预装的系统 Gradle。AGP 8.5.2 / Kotlin 1.9.24 与 Wrapper 配套，不能单独升级 Wrapper。
首次构建需联网下载官方发行包与 Maven 依赖，并按官方提示处理适用的 SDK 许可。
API 36 设备测试是运行时兼容检查，不表示 compileSdk / targetSdk 已升级到 36。
在 git 忽略的 `local.properties` 设置 SDK 路径：

```properties
sdk.dir=/absolute/path/to/android-sdk
```

```sh
# Linux：JDK 根目录；macOS：通常是 JDK 包内的 Contents/Home
export JAVA_HOME=/absolute/path/to/jdk17
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.fluxa.app.debug/com.fluxa.app.MainActivity
# API 26+ 测试设备：
./gradlew :app:connectedDebugAndroidTest
```

此前 Mac 的隔离工具链位于仓库同级的 `../toolchain`，可先 `source ../toolchain/env.sh`。
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

## 源码与验证范围

README 的功能与工具链描述以本仓库已提交源码为准。Mac 上 `feature/github-library`
尚未同步的功能不包含在当前远端验证中；这里的历史通过记录也不验证那些改动。
同步最新源码（含必要未跟踪文件，排除凭据和个人数据）后，需要重新核对依赖与构建配置，
再更新 CI 工具版本并重新执行全部检查。SDK 37 / Gradle 9.4.1 等新版配置须以该源码为依据，
本次 CI 改造不预先升级它们。

## GitHub Actions 自动构建

[Android CI](.github/workflows/android-ci.yml) 对 main push、目标为 main 的 PR 和手动触发运行：
Debug / Preview / Stable 三个构建类型各自执行 JUnit / Robolectric、lint、构建和 APK 身份校验。
另外运行一个独立的 Android 16 / API 36、google_apis x86_64 instrumentation job，
只对 Debug 执行 `connectedDebugAndroidTest`，不重复三渠道设备矩阵。
使用标准 `ubuntu-24.04` GitHub-hosted runner，不引入付费 runner 或外部服务；
任务检查官方稳定镜像清单和现有 KVM 访问，缺失即失败，不降级为跳过测试。
普通 CI 仅有 `contents: read` 权限，不使用 `pull_request_target`，
不读取 NewsBlur 密码、API Key 或签名 secrets，不保存真实账号或应用私有数据。
使用 JDK 17、Gradle 8.9 Wrapper、SDK 35 / build-tools 34.0.0；Gradle 依赖和发行包使用基础缓存，
只有 main 写缓存，PR 只读。Debug 校验调试签名；Preview / Stable 验证未签名构建，
不把它们作为可安装版本交付。所有 action 使用固定完整 SHA。

main push、目标为 main 的 PR（含 draft）自动触发；手动运行可在
[Actions → Android CI](https://github.com/Vincent1993/fluxa/actions/workflows/android-ci.yml)
选择 Run workflow 和分支。PR 检出并验证确切 head commit；合并后 main push 会再次验证。
查看运行的 commit、各 job 结论和日志，再在页面底部 Artifacts 下载（下载通常需要登录 GitHub）：

- `fluxa-debug-apk`：解压得到可安装的 `app-debug.apk`、`BUILD_COMMIT.txt`、
  `SHA256SUMS` 和渠道元数据；仅 Debug 构建任务的单测、lint、APK 身份校验均成功后上传。
  下载可用不等于其他渠道及设备任务已通过，须检查整次运行结论。
  解压后可运行 `sha256sum -c SHA256SUMS`（macOS：`shasum -a 256 -c SHA256SUMS`）。
- `fluxa-validation-debug/preview/stable`：各渠道 JUnit XML / HTML、lint 与 APK 元数据，
  测试失败时也尝试上传；`ci/` 中包含实际构建 commit、工具版本与成功产物的校验和。
- `fluxa-validation-device-api36`：设备测试 XML / HTML、官方 SDK 包清单和 commit，
  失败时也尝试收集已有报告。任务取消或报告生成前失败时可能没有测试报告。

API 36 设备任务是否通过，以具体 commit 的 Actions 结论和报告为准；新增配置本身不是通过证据。
自动化只使用测试夹具，不能替代真实 NewsBlur 联调、真实 TalkBack 的焦点顺序与朗读、
字号缩放、视觉布局、真机电量 / 后台调度的人工验收，也未覆盖 API 26–35 的全部设备。
对当前任务的成功不能声称验证了 Mac 尚未同步的版本。

产物保留 14 天。首次将新工作流合入默认分支 main 后，Actions 页面才会显示
“Run workflow”手动按钮；现有 PR 可先自动运行。原单渠道记录见 [CI 验证记录](docs/ci-validation-2026-10-02.md)。

CI 使用临时 Debug 签名，不保证与本地 APK 或另一轮 CI 的签名相同，
不能直接覆盖不同签名的已有安装；不要为安装 CI 产物卸载而丢失缓存。
另有 [Prepare Android Release](.github/workflows/android-release.yml)，仅从 main 手动触发：
先验证候选构建，再在受保护环境使用持久密钥签名，最后创建 tag 和 draft Release。
缺少密钥或指纹不匹配会失败；不会把未签名 APK 发布到 Release。
Preview 草稿带 prerelease 标记；公开发布需明确批准。Stable 默认禁用。
首次预览目标为 v0.2.0-preview.1 / versionCode 3，密钥生成与上传需用户安全接管。
配置和完整操作见 [发布指南](docs/releases.md)。
