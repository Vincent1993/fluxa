# Fluxa 本地实现与验证记录

日期：2026-10-02。工作区：/Users/admin/Documents/Codex/2026-10-02/task/fluxa。
分支 feature/personal-rss-v1，基于主干 ce5b1bc。
在空任务目录创建独立 clone，没有覆盖用户未提交工作，没有修改 Codex memories。
未推送、合并远程 PR、部署或发布；没有使用用户 Mac 浏览器。

## 已完成范围与 PR 核验

读取了仓库说明、适用 AGENTS.md 和实际 PR #2 / #4 / #5 / #6 代码。
保留字体档位、刷新合并、同步状态、持久操作队列和筛选搜索的有用方向。
未直接叠加三个不兼容的 Room v2，也未使用 destructive fallback。
统一为 v3，迁移保留文章正文、标记、来源 / 标签、游标和旧实验操作。

当前默认后端按用户选择使用 NewsBlur 免费托管账号。
用户名 / 密码登录，无 API Key 或 OAuth Client；密码不保存，
Cookie 加密保存且不跟随跨站重定向。
旧 Inoreader fluxa.db 与令牌保留，NewsBlur 新建独立 newsblur.db，不跨后端重放操作。
首次成功登录后缓存绑定用户名，暂不支持安全切换账号。

刷新和逐订阅分页合并 Room 缓存；操作在本地事务中先更新标记并落盘队列。
成功确认后才删除队列；网络失败、限流和业务拒绝保留可恢复状态。
HTTP 200 的负 code 业务拒绝向界面返回原因，不静默丢弃。
本地搜索、全部 / 未读 / 收藏、订阅选择、阅读 WebView、字体和收藏流程完成。

## 最终实际结果

最终完整命令：

```sh
source ../toolchain/env.sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug \
  :app:connectedDebugAndroidTest \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  --continue --no-daemon
```

最后完整执行日志：../toolchain/validation-free-account.log，BUILD SUCCESSFUL（1m 33s）。

| 检查 | 最终结果 | 证据 |
| --- | --- | --- |
| debug APK 构建 | 通过 | assembleDebug，0.2.0 / versionCode 2 |
| JUnit / Robolectric | 26/26 通过，0 跳过 | app/build/reports/tests/testDebugUnitTest/index.html |
| Room 真正打开旧库并验证迁移 schema | 5/5 通过 | MigrationTest，v1、三种 v2、组合 v2 |
| NewsBlur API / 免费分页 / 业务失败恢复 | 5/5 通过 | NewsBlurFlowTest，MockWebServer + 真正 Room |
| 原缓存 / 离线操作 / 游标回归 | 10/10 通过 | RepositoryTest |
| DTO、路径与路由编码 | 3/3 通过 | ApiContractTest |
| 缓存错误保留与本地筛选搜索 | 3/3 通过 | FeedListViewModelTest |
| Android instrumentation | 7/7 通过，0 跳过 | app/build/reports/androidTests/connected/debug/index.html |
| Compose 列表 / 空态 / 订阅入口 | 2/2 通过 | FeedListUiTest |
| 真 Activity / Hilt / Room / WebView 离线流程 | 1/1 通过 | OfflineAppFlowTest |
| AndroidKeyStore、请求范围、登录与测试存储隔离 | 4/4 通过 | SecurityInstrumentedTest |
| Android lint | 通过，0 错误 / 17 警告 | app/build/reports/lint-results-debug.html |
| 独立迁移 SQL 检查 | 5/5 通过 | scripts/verify-migration-sql.py |
| git diff --check | 通过 | 无空白错误 |
| 模拟器安装、启动、目视 UI | 通过 | API 35 AOSP ARM64，Pixel 2 / 411dp，截图见下 |
| 真实 NewsBlur 用户账号登录与服务端同步 | 未执行 | 没有用户凭据；没有创建账号或付费 |
| 真机 / API 26 / 深色模式 / release 构建 | 未执行 | 本次验证范围为 API 35 模拟器与 debug APK |
| GitHub CI | 未执行 | 没有推送或触发远程任务 |

## 免费账号的验证边界

已核对 NewsBlur 固定提交
[1418221 的服务端代码](https://github.com/samuelclay/NewsBlur/blob/141822122d5ea0d1c8338250fad3dd723da5dbf6/apps/reader/views.py)：

- 免费 river_stories 首页仅少量文章，后续可能返回 HTTP 200、code:0 和空文章。
  当前 API 没有这个端点，测试禁止任何调用。
- 使用普通 reader/feed/{id}，没有 search / query，合法逐订阅分页。
  非 Premium、非试用夹具每页 6 篇，两页共 12 篇，再读空页结束。
  成功响应没有 code 字段，也验证数值 story_timestamp 的解析。
- 搜索在 Room 缓存的标题、来源、正文上完成，没有服务端搜索参数。
- 只提供标记已读，没有把旧文章标回未读的操作。
- 没有 save_feed_chooser 或用不完整列表修改启用订阅。
- HTTP 200、code:-1 的操作拒绝保留本地收藏和队列，并返回提示；
  恢复后成功重放、删除已确认队列。

这些是源代码合同与本地夹具验证，不代表真实免费账号端到端已验收；
也不依赖新账号的 Premium 试用。真实账号状态和服务端政策仍需安装后核对。

## UI 证据与修复

- [登录页](screenshots/login.png)：用户名、密码、缓存入口和注册网站入口。
- [缓存列表](screenshots/cache.png)：同步错误时文章仍可阅读，显示已读、收藏与待同步计数。
- [阅读页](screenshots/reader.png)：24 号字体、文章标题、收藏状态及缓存正文。

目视 QA 发现 WebView 越界绘制遮住字体栏，已用 ViewOutlineProvider.BOUNDS 和 clipToOutline 修复。
最终截图中字体控制区、标题和正文同时可见。
设备流程测试检查打开文章自动已读、收藏、落盘队列和 Activity 重新打开后的状态。
截图是测试夹具内容；没有用户账号或真实私人订阅内容。
本次没有验证强制杀进程后重启，也没有验证远程图片离线下载。

重复运行还暴露了测试存储隔离问题：加密库会取 applicationContext，
ContextWrapper 的 SharedPreferences 覆盖不足以隔离。
改为明确独立的 preferencesName，并增加应用会话不被测试改变的断言。
测试清理同步提交；所有验证在专用模拟器进行。

## 工具链与许可证

用户明确批准“我接受”后，才下载、接受许可并运行工具。
JDK 17 / Gradle / Android SDK / AVD 放在 ../toolchain，
JAVA_HOME、GRADLE_USER_HOME、ANDROID_USER_HOME 指向任务目录。
首轮 Robolectric 使用默认 ~/.m2 缓存下载 Android jar，后续改为仓库 .gradle/robolectric；
没有删除或覆盖已有用户项目、记忆或缓存文件。

- Amazon Corretto 17 macOS ARM64，官方 SHA256：
  0452dc114b8b651324f4416489861b84f3085746e4303ffa5349d6531d7f92e5。
- Gradle 8.9，Wrapper 已提交 SHA256：
  d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab。
- Google command line tools 11076708，官方 SHA1：
  37fb7dd41005b3b4ca6ea48ac27074b6fc4e3236。
- SDK platform 35、build-tools 34.0.0、platform-tools、emulator、
  system-images;android-35;default;arm64-v8a。
- 已实际接受 [Android SDK License Agreement](https://developer.android.com/studio#terms-and-conditions)
  （2019-01-16 版本）；许可证记录位于 ../toolchain/sdk/licenses/android-sdk-license，
  哈希 24333f8a63b6825ea9c5514f83c2829b004d1fee。
- 模拟器 adb 服务需本机沙箱权限升级，批准后成功运行，没有剩余权限阻塞。

初次编译发现原 Gradle Properties 引用被 java 扩展遮蔽及不存在的 XML Material3 主题。
测试发现 Retrofit Path 参数顺序与编码断言错误、JUnit 非 void 返回；均已修复。
保留 AGP 8.5.2 对 compileSdk 35 的兼容警告。
lint 的 17 警告为 14 条依赖新版本提示及 3 条刻意同步提交偏好存储的提示，
没有通过禁用 lint 或加入 baseline 隐藏错误。

## APK 与运行

构建产物：app/build/outputs/apk/debug/app-debug.apk，约 19 MB。
稳定交付副本：../deliverables/fluxa-0.2.0-debug.apk。

SHA256：
`7e9d8ef9b1ac729e9302fb176e8238f2d4405b19b274a313a09e60ecfa5a6555`

```sh
source ../toolchain/env.sh
adb install -r ../deliverables/fluxa-0.2.0-debug.apk
adb shell am start -n com.fluxa.app/.MainActivity
```

在应用登录自己的 NewsBlur 账号，添加 RSS 地址，刷新，再验证已读 / 收藏在网站的最终状态。
不要通过聊天提供密码。debug 签名来自任务目录的 android-user/debug.keystore，
后续更新应保留同一密钥；现有其他签名安装不能直接覆盖，不应卸载来处理签名冲突。

## 剩余产品边界与下一步

下一步是用户安装后完成真实 NewsBlur 账号的订阅、刷新、断网操作、联网重放验收。
本版单账号、最多 64 个启用订阅，本地搜索只覆盖缓存；
不含 OPML、删除订阅、后台同步、完整图片离线下载或自动清理历史缓存。
字体档位在单篇 SavedState 保存，尚无全局阅读偏好。
