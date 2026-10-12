# 远端分支整合记录 — 2026-10-12

盘点基线：main `b61c6d97995dd79f3c2ff549da28a6f802471d77`。全量分页确认 14 个分支（含 main）；开放 PR #2、#4、#5、#6、#9、#13、#14。PR #14 最终 head `a0b80c873f0edbe03f1ba7f9c48b22621f4c3eca` 三渠道各 34/34 单测、API36 9/9 设备测试全部通过，随后按授权以 merge commit `2198efdbfbb084d3077a31d8cbce336d272a71a1` 接入 main。

`feature/integrate-remote-branches` 以该 main 为第一父提交，并保留盘点时各远端 head 作为合并父提交。最终 tree 由逐项代码审查后的实现组成，历史进入主线不表示每个旧原型被激活，也不表示 Mac 未提交功能已接入。没有删除任何分支、强推 main、变更保护设置或创建 Release。

| 分支 | 盘点 head | 独有 / 落后提交（原 main 对比） | 内容处理 |
| --- | --- | --- | --- |
| `chore/android-seventeen` | `22750c08c36df59e58c7df1ee82490dc96e64f1a` | 2 / 0 | 移入协调工具链、API37 适配、原生对齐和覆盖升级测试；保留 KSP 与新版 Hilt 入口。 |
| `chore/cloud-ci-device-reports` | `a0b80c873f0edbe03f1ba7f9c48b22621f4c3eca` | 8 / 0 | PR #14 已合入 main；保留严格三渠道、API36、commit/校验和/失败报告。 |
| `chore/compatible-dependency-patches` | `34dc9f5ed8d36ed1dfabaa577f86bec85b7b8ada` | 1 / 1 | PR #12 已以 squash 集成 Moshi 1.15.2；不回退依赖或重复套用补丁。 |
| `codex/add-fields-and-new-tables-to-articleentity` | `b2487c3d83fa89d0057e9068f90d497c43939545` | 1 / 4 | 旧 Inoreader 数据库文件保持独立；不以互不兼容的 Room v2/破坏性 fallback 替换主线 v3。丰富元数据/高亮/标签关系并未宣称为当前客户端功能。 |
| `codex/add-filtering-and-search-to-articledao` | `49a7a97b18f8caa98ad0aea9b7901e82e06939bb` | 1 / 4 | 当前真实订阅选择、未读/收藏和缓存搜索取代硬编码 feed/tech UI；标签/日期原型尚未提供为当前 NewsBlur 功能。 |
| `codex/add-highlight,-note,-archive,-save-actions` | `5f6ae2fda121a9ec67fbcf2baa2b7c54926f9de2` | 2 / 4 | 保留主线事务先落盘的读/收藏队列与旧操作数据；不恢复 clearAll 或失败后才入队。注释/归档/稍后读的 Inoreader 原型未转换为当前 NewsBlur 功能。 |
| `codex/add-syncstate-local-table-and-synchronization-tasks` | `045091cac5d5dc5f4ddcb459022a71b053265be5` | 1 / 4 | 游标、合并缓存和后台同步已在当前主线改写；不恢复自动15分钟启动及旧后端绑定。 |
| `codex/generate-full-android-app-fluxa` | `75c10acd7330cb5758808d0c38e15020b962e78d` | 2 / 4 | 旧 Inoreader 数据库文件保持独立；不以互不兼容的 Room v2/破坏性 fallback 替换主线 v3。丰富元数据/高亮/标签关系并未宣称为当前客户端功能。 |
| `codex/generate-full-android-app-fluxa-g70qrm` | `660fd09dec371dff7c6e50675924a78af302c47a` | 3 / 5 | Android scaffold/字号功能已由主线实现；不恢复有自动发布/unsigned fallback 的旧 workflow 或 Fake 数据绑定。 |
| `dependabot/gradle/compatible-updates-2396bb2c12` | `9b3c24c526ed0cbb91ddab5595eda403d6800add` | 1 / 0 | 升级意图纳入 Android17 协调迁移；Hilt 改 KSP，避免把新版 Hilt/Room 拼在旧 Kotlin/AGP 上。 |
| `dependabot/gradle/gradle-wrapper-9.8.0` | `9b3702b89fff9c41ed87a0c730e3bc653f575496` | 1 / 2 | 冲突解析为已验证的 Gradle 9.4.1：Kotlin 2.4.20 官方完全支持上限为 9.7.0；不声称 9.8 已验证。 |
| `feature/background-sync` | `770060aa42043e10baf2ad996f7488ab3fe9bf58` | 1 / 2 | PR #10 已以 squash 集成；保留默认关闭、每小时、有限重试及正确 WorkerFactory。 |
| `feature/personal-rss-v1` | `e8a6e709c6cf6d596fc583a91717972b4904cc2b` | 4 / 3 | PR #7 已以 squash 集成；保留 NewsBlur、数据隔离、可靠重放及三渠道签名边界。 |

## 冲突处理与验证

- 构建：使用 `chore/android-seventeen` 已提交的 AGP 9.2.1、Kotlin/Compose 2.4.20、KSP 2.3.12、JDK17、Gradle9.4.1、SDK37.0/build-tools36.0.0。不是把单独依赖 PR 原样叠到旧编译链上。[AGP 官方范围](https://developer.android.com/build/releases/agp-9-2-0-release-notes)与 [Kotlin 官方范围](https://kotlinlang.org/docs/gradle-configure-project.html)支持该选择。
- CI：合并 PR14 的追溯产物、严格结果汇总和 API36 测试，以及 Android17 分支的 API26/API37 16KB、同证书 `adb install -r` 升级检查；只对 Debug 跑设备矩阵。移除该旧设备 workflow 的 chmod，改为降到 runner UID 的 KVM 组进程和显式隔离 AVD 目录。
- 账号/数据：NewsBlur 仍是实际注入的后端；不混用 Inoreader token/cookie，不恢复 destructive fallback，不删除旧数据库，不把旧未知操作发给新服务。
- 发布：保留三包名与持久签名边界、受保护环境及 main 来源校验；未签名 Preview/Stable 不是可安装发布。旧自动 Release workflow 不恢复。
- 历史通过记录不是整合通过记录。整合 PR 和合入后的 main 都需要实际运行 Android CI 与 Android device compatibility，并在最终回复给出精确 SHA、数量和产物。

## 仍未覆盖，不能宣称所有功能完整

Mac `feature/github-library` 的未提交/未跟踪源码仍未同步，范围包括新阅读器、本地 RSS、Stars/GitHub Library 等；本次不能列出完整文件清单或将它们作为远端源码验证。

旧原型以下范围并未被包装成可用 NewsBlur 功能：

- PR #3 / `codex/add-fields-and-new-tables-to-articleentity`：url/author/excerpt、阅读进度、归档/稍后读/打开时间、wordCount、高亮与标签关系；该 PR 当年仅合到 codex 父分支，当前主线独立保存旧 `fluxa.db`，不会自动把它当新 NewsBlur 数据库。
- PR #5：`domain/usecase/AddHighlightUseCase.kt`、`AddNoteUseCase.kt`、`ArchiveArticleUseCase.kt`、`SaveForLaterUseCase.kt`，及旧 ArticleScreen/ViewModel 的固定示例文字按钮、Inoreader 自定义 tag/annotation 网络调用。不能原样发送给 NewsBlur，也不能以一个会清空缓存的仓库实现覆盖主线。
- PR #6：旧 ArticleDao/FeedListViewModel 的标签/日期范围筛选原型与固定示例 source/tag UI。当前主线真实订阅选择、未读/收藏、标题/来源/正文搜索继续可用。

这些原型需要与尚未取得的最新阅读器/本地 RSS 模型一起实现和验证；保留其完整提交历史与旧数据，不宣称已完成移植。真实账号、TalkBack、视觉和真机后台调度仍需独立验收。
