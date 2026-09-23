# AI_CHANGELOG

## v4.0.52

### 🇨🇳 中文

版本编号由 v4.0.0-ai.N 体验版系列切换为 v4.0.N 语义化版本系列。

- 新增：应用内更新支持 Codeberg / GitHub 双渠道，关于页可自选渠道（默认 Codeberg）
- 新增：启动时的更新检查失败会静默回退到备用渠道
- 变更：stable 通道改为按 releases 列表筛选最新正式版，兼容 Codeberg 对预发布返回 404 的行为
- 说明：Codeberg 渠道的 release 未提供 SHA256 摘要资产时，安装包进入"未校验"安装态

## v4.0.0-ai.51

### 🇨🇳 中文

Codeberg CI 首个全绿后的正式预发布版本。

- CI 修复：为 runner 安装 Android SDK（此前缺失导致 `compileDebugJavaWithJavac` 依赖解析报 "Cannot query the value of this provider"，元凶为 `androidJdkImage` 空 provider）
- 修复：移除 ButterKnife 残留调用（kapt→KSP 迁移遗漏，javac 找不到符号）
- CI 增强：jitpack.io TLS 间歇失败加 3 次重试；`actions/checkout` 改纯 `git clone`（根绝拉 action 被掐致 job 秒挂）；发布步骤改 Codeberg 原生 Release API
- 其余功能与 v4.0.0-ai.50 一致
