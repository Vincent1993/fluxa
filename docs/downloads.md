# 下载与升级

[GitHub Releases](https://github.com/Vincent1993/fluxa/releases) 是签名发行包的长期下载入口，目前尚无正式签名版本。每个发行版将附 APK、SHA256SUMS、更新说明和来源信息。

## 开发测试版

打开 [Android CI](https://github.com/Vincent1993/fluxa/actions/workflows/android-ci.yml)，选择最新 main 且整次运行成功的记录，下载 Artifacts 中的 `fluxa-debug-apk`。解压后检查 SHA256SUMS，再安装 app-debug.apk。下载通常需登录 GitHub，产物保留 14 天。

Debug 使用测试签名，不保证不同构建之间证书相同。如果系统提示签名不匹配，不要为了安装而卸载有数据的现有应用；保留原安装并等待同签名渠道的版本。Debug、Preview 与 Stable 是独立安装渠道，数据不会自动互相迁移。

## 签名发行版

发布流程先对确切 main commit 构建、单测、lint 和校验，再使用 Preview 与 Stable 共用的长期证书签名，最后准备 draft Release。版本只有在签名配置及验收确认后才公开，不会把未签名产物称为安装包，也不会用临时 Debug 证书替代正式签名。

完整流程与配置见 [发布指南](releases.md)。尚未取得的 Mac 未提交功能不包含在当前远端构建中。

## Preview 与正式版同时安装

Preview 的包名为 `com.fluxa.app.preview`，名称为 **Fluxa Preview**；正式版为 `com.fluxa.app`，名称为 **Fluxa**。两者采用同一份长期签名，但可以同时安装，私有缓存、笔记和登录态分别保存，不自动互相迁移。若登录同一个 NewsBlur 账号，已读／收藏等服务端状态仍属于该共同账号。

更新时各自覆盖自己的渠道，并沿用相同包名和长期证书。共享证书不会使 Preview 覆盖正式版。长期密钥尚未生成，首次签名版本仍需用户安全备份及签名候选验收。
