# Android CI 本地准备与验证

日期：2026-10-02。基于应用提交 1486be7，在 feature/personal-rss-v1 分支准备。
目标仓库 origin=https://github.com/Vincent1993/fluxa.git，
只读核实 visibility=public、default_branch=main；远程旧主干仍为 ce5b1bc。

## 修复内容

删除旧 build-release-apk.yml，替换为 android-ci.yml；避免保留无效旧工作流同时运行。
旧远程文件在步骤 if 直接引用 secrets，GitHub 无法解析，
并且仅构建 Release、没有稳定的可安装 Debug 产物。
已只读核实两次运行均 failure、0 jobs、0 artifacts：
[22295073596](https://github.com/Vincent1993/fluxa/actions/runs/22295073596)、
[25372453035](https://github.com/Vincent1993/fluxa/actions/runs/25372453035)。

新流水线：

- push 到任何分支、PR 目标 main、workflow_dispatch。
- 仅 contents:read；checkout 不保留凭据；无签名 secrets、Release 或合并步骤。
- Ubuntu 24.04、Temurin JDK 17、项目 Gradle 8.9 Wrapper，
  使用发行包 SHA256 和 setup-gradle 的 Wrapper 验证。
- 安装 platform 35 与 build-tools 34.0.0，复用官方 runner 提供的 SDK。
- 单元测试、lint 与 Debug 构建使用 --continue，失败时仍生成其他可用报告。
- 全部成功才上传 fluxa-debug-apk；报告 fluxa-validation 在失败时也尝试上传。
- 产物保留 14 天；APK 已压缩，因此上传关闭重复压缩。
- 基础 Gradle 缓存：仅 main 可写，其他分支及 PR 只读；不引入商业缓存或额外 secrets。

## 官方稳定 action 核验

已通过 GitHub 官方仓库的 releases/latest、commit 和 action.yml 核验版本与输入，
全部固定完整提交 SHA，便于审查和防止标签漂移：

| Action | 核验稳定版本 | SHA |
| --- | --- | --- |
| actions/checkout | v7.0.1 | 3d3c42e5aac5ba805825da76410c181273ba90b1 |
| actions/setup-java | v6.0.1 | de7274f081f381c8f8158605e0321c36c376e2e6 |
| gradle/actions/setup-gradle | v6.4.0 | 3f5f9adaf7d9fecd50b5935e54106014257a94e6 |
| actions/upload-artifact | v7.0.1 | 043fb46d1a93c77aae656e7c1c64a875d1fc6a0a |

依据：[GitHub workflow 语法](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax)、
[Ubuntu 24.04 runner](https://github.com/actions/runner-images/blob/main/images/ubuntu/Ubuntu2404-Readme.md)、
[Gradle 缓存文档](https://github.com/gradle/actions/blob/v6.4.0/docs/setup-gradle.md)。
使用已缓存的 Context7 CLI 核对文档，没有安装新工具。

## 本地执行结果

- Ruby Psych 解析 YAML：通过。
- 事件、最小权限、无 Release / secrets、SHA 格式：通过。
- 对照每个 action 的真实 action.yml 校验所有 with 输入：通过。
- 每个 run 步骤 bash -n：通过。
- SDK 已安装组件核验：35 / 34.0.0 均在隔离目录中。
- git diff --check：通过。
- 流水线使用的确切命令实际成功（19 秒，6 执行 / 62 up-to-date）：

```sh
source ../toolchain/env.sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  --continue --no-daemon --stacktrace
```

本次未修改应用逻辑。单元任务复用已有 26/26 成功结果，APK 任务复用已通过的构建；
lint 再次执行为 0 错误 / 17 警告。不是新跑了一遍全部测试。
本机未安装 actionlint，未安装新验证工具；以上本地检查不能替代 GitHub 的工作流解析与 Linux 运行。
此前 7/7 Android UI / 安全测试为 API 35 模拟器本地验证，新 CI 不运行模拟器。

## 远程验收与使用

用户已批准推送独立 feature 分支、创建 draft PR 并运行 CI / 保存 artifacts。
本记录为推送前核验；远程结果以最终提交对应的 Actions run 为准，不合并 main 或创建 Release。
首次将新 workflow 合入默认分支 main 后才显示 Run workflow 手动按钮，
分支 push 与 PR 则可以先自动运行。

Actions → Android CI → 成功运行 → Artifacts → fluxa-debug-apk，下载 ZIP 后解压 app-debug.apk。
Debug 签名是临时密钥，不保证跨运行或与本地版本相同；不要卸载已有应用来处理签名冲突，以免丢数据。
