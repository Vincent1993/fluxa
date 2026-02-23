# Fluxa（Inoreader OAuth + 基础拉流版）

Fluxa 是一个面向沉浸阅读的 Android RSS 阅读器。

## 已实现

- Kotlin + Compose + Material 3 + Hilt + Navigation Compose
- OAuth2（Inoreader）登录：外部浏览器授权 + Deep Link 回调
- Token 交换与刷新（`authorization_code` / `refresh_token`）
- `EncryptedSharedPreferences` 安全存储 access/refresh token
- Inoreader API 接入：
  - 获取阅读流（reading-list）
  - 标记已读
  - 收藏/取消收藏
- Room 缓存：文章离线读取（先读本地，再网络刷新）
- Feed 列表基础交互：下拉刷新、滑动操作、加载更多
- 阅读页字体设置：S / M / L / XL 快速切换
- 动画增强：列表状态切换、列表项位移动画、标签显隐动画、页面转场动画
- GitHub Actions：构建 release APK，并支持按 tag 发布到 GitHub Release

## 关键配置

在 `app/build.gradle.kts` 中配置：

- `INOREADER_CLIENT_ID`
- `INOREADER_CLIENT_SECRET`
- `INOREADER_REDIRECT_URI`（默认 `fluxa://oauth/callback`）

> 注意：发布版本建议将敏感信息迁移到 CI Secret 或本地 `local.properties` 注入，不直接写入仓库。

## 本地运行

1. 使用 Android Studio 打开项目
2. 配置 Inoreader Client 信息
3. Gradle Sync
4. 运行到 API 26+ 模拟器/真机

## GitHub Actions 发布 APK

工作流：`.github/workflows/build-release-apk.yml`

### Job 说明（已拆分）

1. **CI Verify (PR/Main)**
   - 触发：PR、push 到 main 分支
   - 行为：构建 `debug` APK 并上传 `fluxa-debug-apk`
   - 目的：只做持续集成校验，不执行 Release 发布

2. **Release APK (Tag/Manual)**
   - 触发：push tag `v*` 或手动触发
   - 行为：构建 `release` APK，上传 `fluxa-release-unsigned`
   - 若签名 secrets 完整，则额外产出并上传 `fluxa-release-signed`
   - 若有 tag（或手动输入 `release_tag`），自动发布到 GitHub Release

### 签名 secrets（可选，但建议）

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_PASSWORD`

## 下一步建议

- 增加 token 过期后的全局 401 自动重试拦截
- 完善订阅列表与多 stream 切换
- 增加文章字体档位、排版主题和阅读设置


## CI 运行检查与排错

你可以用以下方式确认 GitHub Actions 能正常工作：

1. 打开 GitHub `Actions` 页面，手动触发 `Android CI And Release`。
2. 若只验证主干质量，使用 PR / push main：会跑 CI Verify，并产出 `fluxa-debug-apk`。
3. 若要发布到 GitHub Release：
   - 推送 tag（如 `v0.1.0`），或
   - 手动触发时填写 `release_tag`（否则仅上传 artifact 不发 Release）。
4. 若配置了完整签名 secrets，会额外产出 `fluxa-release-signed`，并优先发布 signed APK。

### 常见失败原因

- **JDK 版本不一致**：工作流固定 JDK 17。
- **签名变量不完整**：缺少任一签名 secret 时不会生成 signed APK。
- **Release 未生成**：通常是未提供 tag（`v*` 或 `release_tag`）。

### 当前工作流稳健性改进

- 增加并发互斥，避免同一分支重复任务互相覆盖。
- 增加 `timeout`、APK 文件存在性校验、Artifact 缺失即报错。
- Release 发布仅在非 PR 且存在 tag 时触发。
