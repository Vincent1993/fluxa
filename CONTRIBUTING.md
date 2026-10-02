# Working on Fluxa

main 是唯一长期分支。工作从 main 分出，完成后经 PR 和 CI 验证再合入。
使用 feature/<slug>、fix/<slug>、chore/<slug> 或 docs/<slug>，slug 为小写字母、数字和连字符。
例如 feature/offline-images。Conventional Commit 标题有助于整理变更，但无需引入提交工具。

历史 PR #1–6 的 codex/ 分支保留，不自动删除或改名。新 PR 的分支命名由 CI 校验；
Dependabot 的自动依赖 PR 不适用人工工作分支命名。
个人仓库不要求他人审批自己的 PR；建议 main 要求 PR 与以下状态检查：

- Version and release policy
- Tests, lint and APK (debug)
- Tests, lint and APK (preview)
- Tests, lint and APK (stable)

仓库 ruleset 设置需要所有者确认后单独启用：main 禁止强推和删除；
v* tag 禁止更新和删除，不阻止已授权发布流程创建新 tag。
这些是 GitHub 设置，提交本文不会自动启用保护。

## Versions

version.properties 是基础版本的唯一来源：
VERSION_NAME 为 MAJOR.MINOR.PATCH；VERSION_CODE 为递增正整数；
PREVIEW_NUMBER 为当前基础版本的预览序号。
每个新的 Preview 或 Stable 发布都必须在提交中递增 VERSION_CODE，
Preview 还需更新 PREVIEW_NUMBER 和 changelog。重复构建同一提交不另占 code。
发布脚本检查所有既有 v* 发布 tag 的版本，拒绝复用旧 code。
不使用各工作流各自的 run_number 来生成版本号。

Debug、Preview、Stable 分别安装到独立应用数据目录。修复不应删除现有数据库；
新增 Room schema 时补显式迁移和保留数据测试。

发布步骤和用户安全接管操作见 docs/releases.md。正式凭证不用于普通 PR 构建。
