# AI_CHANGELOG

## v4.0.56

### 🇨🇳 中文

更新渠道迁移至新账号。

- 变更：应用内更新检测的 GitHub 渠道已指向新账号 qaqmin/TiebaLite（原 qaqmin09577 账号被封）；Codeberg 备用渠道不变
- 变更：README 下载入口、徽章与 Issues 链接同步更新至新账号
- 说明：两个旧 GitHub 账号（min09577、qaqmin09577）先后被封，已在 README 维护者近况中记录在案

## v4.0.55

### 🇨🇳 中文

更新检查加固与构建链路修复。

- 优化：更新检查改用 ETag 条件请求，命中 304 不计入 GitHub 匿名限额（60 次/小时/IP），大幅降低用户撞 403 的概率；403/429 仍会自动静默回退备用渠道
- 修复：Gradle clean 任务现在会清理所有子模块的 build 目录（此前只删根目录，历史 APK 会残留并混入后续构建产物）
- 内部：GitHub Actions 支持 tag 推送自动构建并发布 release；Mac 构建机工具包修复产物收集并完成部署同步

## v4.0.54

### 🇨🇳 中文

项目迁移至新 GitHub 账号 qaqmin09577（原 min09577 的 GitHub 账号因不可抗拒原因被封禁、仓库 404）。

- 变更：默认更新渠道切换为 GitHub（qaqmin09577/TiebaLite），Codeberg（min09577/TiebaLite）继续作为备用渠道双轨发布
- 变更：关于页、项目溯源信息（维护者/仓库地址）与更新渠道标签同步更新
- 内部：构建继续由 Mac 远程构建机产出，签名钥匙不变（覆盖更新无损）
- README：补齐近期版本更新说明，新增账号迁移公告

## v4.0.53

### 🇨🇳 中文

正式版发布链路切换为 Codeberg CI 全自动产出：tag 触发构建，成功后自动创建 Release 并上传 CI 构建产物。本版本为首个由系统产出的正式版。

- 变更：正式版发布改由 CI 自动完成（自动构建、自动发布）
- 内部：CI 流水线加固（构建重试、产物落盘校验、gradle 超时兜底）
- 应用功能与 v4.0.52 一致

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
