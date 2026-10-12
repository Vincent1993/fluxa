# 远端分支整合记录 — 2026-10-12

盘点基线：main `b61c6d97995dd79f3c2ff549da28a6f802471d77`。全量分页确认 14 个分支（含 main）；开放 PR #2、#4、#5、#6、#9、#13、#14。PR #14 最终 head `a0b80c873f0edbe03f1ba7f9c48b22621f4c3eca` 三渠道各 34/34 单测、API36 9/9 设备测试全部通过，随后按授权以 merge commit `2198efdbfbb084d3077a31d8cbce336d272a71a1` 接入 main。

`feature/integrate-remote-branches` 以该 main 为第一父提交，并保留盘点时各远端 head 作为合并父提交。最终 tree 由逐项代码审查后的实现组成，历史进入主线不表示每个旧原型被激活，也不表示 Mac 未提交功能已接入。没有删除任何分支、强推 main、变更保护设置或创建 Release。

| 分支 | 盘点 head | 独有 / 落后提交（原 main 对比） | 内容处理 |
| --- | --- | --- | --- |
| `chore/android-seventeen` | `22750c08c36df59e58c7df1ee82490dc96e64f1a` | 2 / 0 | 移入协调工具链、API37 适配、原生对齐和覆盖升级测试；保留 KSP 与新版 Hilt 入口。 |
| `chore/cloud-ci-device-reports` | `a0b80c873f0edbe03f1ba7f9c48b22621f4c3eca` | 8 / 0 | PR #14 已合入 main；保留严格三渠道、API36、commit/校验和/失败报告。 |
| `chore/compatible-dependency-patches` | `34dc9f5ed8d36ed1dfabaa577f86bec85b7b8ada` | 1 / 1 | PR #12 已以 squash 集成 Moshi 1.15.2；不回退依赖或重复套用补丁。 |
| `codex/add-fields-and-new-tables-to-articleentity` | `b2487c3d83fa89d0057e9068f90d497c43939545` | 1 / 4 | 安全移植到独立 reader_* 表：元数据、进度、高亮、标签关系。Room v4 非破坏性迁移保全丰富 v2 与现有 v3；旧 fluxa.db 同渠道只读导入。 |
| `codex/add-filtering-and-search-to-articledao` | `49a7a97b18f8caa98ad0aea9b7901e82e06939bb` | 1 / 4 | 移植组合筛选：真实订阅、标签、包含起止日期的本机时区范围、全文关键词及阅读集合；SavedState 恢复与非法日期校验。 |
| `codex/add-highlight,-note,-archive,-save-actions` | `5f6ae2fda121a9ec67fbcf2baa2b7c54926f9de2` | 2 / 4 | 四个 usecase 安全重写：本地笔记、高亮、归档、稍后读，编辑/删除与持久化；仅已读/收藏进入当前后端队列。归档未读文章同时排队 MarkRead。 |
| `codex/add-syncstate-local-table-and-synchronization-tasks` | `045091cac5d5dc5f4ddcb459022a71b053265be5` | 1 / 4 | 游标、合并缓存和后台同步已在当前主线改写；不恢复自动15分钟启动及旧后端绑定。 |
| `codex/generate-full-android-app-fluxa` | `75c10acd7330cb5758808d0c38e15020b962e78d` | 2 / 4 | 安全移植到独立 reader_* 表：元数据、进度、高亮、标签关系。Room v4 非破坏性迁移保全丰富 v2 与现有 v3；旧 fluxa.db 同渠道只读导入。 |
| `codex/generate-full-android-app-fluxa-g70qrm` | `660fd09dec371dff7c6e50675924a78af302c47a` | 3 / 5 | Android scaffold/字号功能已由主线实现；不恢复有自动发布/unsigned fallback 的旧 workflow 或 Fake 数据绑定。 |
| `dependabot/gradle/compatible-updates-2396bb2c12` | `9b3c24c526ed0cbb91ddab5595eda403d6800add` | 1 / 0 | 升级意图纳入 Android17 协调迁移；Hilt 改 KSP，避免把新版 Hilt/Room 拼在旧 Kotlin/AGP 上。 |
| `dependabot/gradle/gradle-wrapper-9.8.0` | `9b3702b89fff9c41ed87a0c730e3bc653f575496` | 1 / 2 | 冲突解析为已验证的 Gradle 9.4.1：Kotlin 2.4.20 官方完全支持上限为 9.7.0；不声称 9.8 已验证。 |
| `feature/background-sync` | `770060aa42043e10baf2ad996f7488ab3fe9bf58` | 1 / 2 | PR #10 已以 squash 集成；保留默认关闭、每小时、有限重试及正确 WorkerFactory。 |
| `feature/personal-rss-v1` | `e8a6e709c6cf6d596fc583a91717972b4904cc2b` | 4 / 3 | PR #7 已以 squash 集成；保留 NewsBlur、数据隔离、可靠重放及三渠道签名边界。 |

## 冲突处理与验证

- 构建：使用 `chore/android-seventeen` 已提交的 AGP 9.2.1、Kotlin/Compose 2.4.20、KSP 2.3.12、JDK17、Gradle9.4.1、SDK37.0/build-tools36.0.0。不是把单独依赖 PR 原样叠到旧编译链上。[AGP 官方范围](https://developer.android.com/build/releases/agp-9-2-0-release-notes)与 [Kotlin 官方范围](https://kotlinlang.org/docs/gradle-configure-project.html)支持该选择。
- CI：合并 PR14 的追溯产物、严格结果汇总和 API36 测试，以及 Android17 分支的 API36/API37 16KB、同证书 `adb install -r` 升级检查；只对 Debug 跑设备矩阵。移除该旧设备 workflow 的 chmod，改为降到 runner UID 的 KVM 组进程和显式隔离 AVD 目录。
- 账号/数据：NewsBlur 仍是实际注入的后端；不混用 Inoreader token/cookie，不恢复 destructive fallback，不删除旧数据库，不把旧未知操作发给新服务。
- 发布：保留三包名与持久签名边界、受保护环境及 main 来源校验；未签名 Preview/Stable 不是可安装发布。旧自动 Release workflow 不恢复。
- 历史通过记录不是整合通过记录。整合 PR 和合入后的 main 都需要实际运行 Android CI 与 Android device compatibility，并在最终回复给出精确 SHA、数量和产物。

## 实际功能移植与边界

PR #3、#5、#6 的功能已在当前 NewsBlur 架构重新实现，不通过伪造后端能力实现：

- `data/reader` 独立保存 url/author/excerpt/wordCount、阅读进度、归档/稍后读/打开时间、笔记、高亮、标签及关系。刷新文章保持本地阅读状态。高亮采用净化 HTML 与转义 DOM，脚本关闭。
- `domain/usecase/ReadingActions.kt` 提供 AddNoteUseCase、AddHighlightUseCase、ArchiveArticleUseCase、SaveForLaterUseCase，输入来自用户；笔记、高亮、标签、阅读进度、归档和稍后读仅本机，不发送不受 NewsBlur 支持的网络操作。
- `FeedListViewModel` 组合真实订阅、标签、日期范围、关键词、未读/收藏/稍后读/归档/注释筛选。日期按本机时区且包含起止两天，非法范围不更改有效筛选。
- Room v4 捕获旧丰富 v2 字段后再规范核心表；v3 升级创建独立表，并将旧实验操作复制为本地记录、保留原操作数据。同安装渠道的旧 `fluxa.db` 使用只读连接保全导入，不读取其账号信息，不重放旧后端队列。重复导入不会重复缓存或覆盖本地修改。
- Reeder 方向的平面列表、暖色阅读器、纯图标动作。列表无显式操作按钮：右划三段为已读/收藏/稍后读，左划三段为归档/稍后读/已读，阈值 48/120/220 dp；另提供无障碍自定义动作。阅读器可调整字号、编辑/删除笔记高亮与标签、保存滚动进度。
- 测试覆盖真实数据持久化、丰富 v2 与导出 v3 迁移、只读导入幂等、刷新保全、筛选边界、HTML 净化/跨行内标签高亮，以及设备上的动作持久化和实际手势。执行通过与否以最终 commit 的 CI 为准。

API37 曾在未进入升级断言前发生 SurfaceFlinger `hasReadColorBufferDma` 崩溃。runner 的旧 Command-line Tools 误将小版本 SDK 写成 `target=android-0` 是 [runner-images 上游报告](https://github.com/actions/runner-images/issues/14484) 中的同症状原因；现固定 Command-line Tools 22.0 并断言真实 AVD target，保留失败报告。API26 旧 run 的成功记录保留，后续升级矩阵专注 API36 与 API37/16KB。

## 尚未覆盖

Mac `feature/github-library` 的未提交/未跟踪源码仍未同步，包括新阅读器、本地 RSS、Stars/GitHub Library 等。本次实现以现有远端分支为依据，不能称为 Mac 最新版本验证。新增 reader 模块和 usecase 提供明确接口，取得 Mac 快照后需逐项比较并整合重叠功能。真实账号、TalkBack 人工使用、视觉及真机后台调度仍需独立验收。
