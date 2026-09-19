# AI_CHANGELOG

## v4.0.0-ai.51

### 🇨🇳 中文

Codeberg CI 首个全绿后的正式预发布版本。

- CI 修复：为 runner 安装 Android SDK（此前缺失导致 `compileDebugJavaWithJavac` 依赖解析报 "Cannot query the value of this provider"，元凶为 `androidJdkImage` 空 provider）
- 修复：移除 ButterKnife 残留调用（kapt→KSP 迁移遗漏，javac 找不到符号）
- CI 增强：jitpack.io TLS 间歇失败加 3 次重试；`actions/checkout` 改纯 `git clone`（根绝拉 action 被掐致 job 秒挂）；发布步骤改 Codeberg 原生 Release API
- 其余功能与 v4.0.0-ai.50 一致
