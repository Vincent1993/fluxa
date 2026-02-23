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

触发方式：
- PR
- push 到 `main`
- push tag（`v*`）
- 手动触发（可传 `release_tag`）

签名 secrets（可选，但建议）：
- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_PASSWORD`

当存在 tag（或手动提供 `release_tag`）时，会把 APK 上传到 GitHub Release。

## 下一步建议

- 增加 token 过期后的全局 401 自动重试拦截
- 完善订阅列表与多 stream 切换
- 增加文章字体档位、排版主题和阅读设置
