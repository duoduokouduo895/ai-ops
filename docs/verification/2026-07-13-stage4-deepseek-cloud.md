# Stage 4 DeepSeek 云端验收

验收日期：2026-07-13（Asia/Shanghai）

发布目录：`/opt/aidiag/releases/20260713-stage4-deepseek-1`

## 发布与数据库

- 云端镜像构建通过：31 个单元/上下文测试通过，10 个 PostgreSQL 测试按环境门禁跳过。
- 隔离的云端 PostgreSQL 集成测试通过：10 个测试，0 failure、0 error、0 skipped。
- Flyway 从 V7 迁移至 V8，`ai_model_daily_budget` 已创建。
- 发布脚本完成切换，`/opt/aidiag/current` 指向本次发布目录；后端健康接口返回 `UP`。
- AIDiag backend、PostgreSQL、Alertmanager、Prometheus、Payment Service 和 Order Service 最终均为 healthy；其余遥测容器正常运行。

## Secret 与鉴权边界

- DeepSeek Key 和 Operator Secret 仅存在于 `/opt/aidiag/runtime/.secrets`，由 Compose 以只读文件挂载进后端；没有写入 Git、环境变量或验收输出。
- 容器内两个 Secret 均可读，权限为 `0640`，所有者为 `root:10001`。
- 不带 Operator Basic Auth 调用预览接口返回 HTTP 401；调用前后 UTC 日配额均为 0，证明鉴权失败不会预留费用。

## 真实 Provider 契约

- 使用已有确定性报告执行一次授权预览，HTTP 返回 200，结果状态为 `COMPLETED`。
- 审计 attempt 为 `e323ffa8-3564-4436-a75f-9285717c4425`，Provider 为 `deepseek`，模型为 `deepseek-v4-flash`，没有 failure code。
- 响应包含经本地校验器接受的结构化 advice，`automatedRemediation=false`。
- UTC 预算行从 0 原子增加为 1；北京时间 2026-07-13 对应的 UTC 使用日期为 2026-07-12。
- AI 建议只新增 1 条 `ai_diagnosis_attempt`；对应 Incident 的确定性 `diagnosis_report` 仍为 1 条，没有改写主根因或自动执行操作。

## 对抗性审查与未主动制造的故障

- 启动日志未发现应用 error/exception；仅出现 Java agent/CDS 兼容性 warning，不影响运行。
- 真实 429、5xx、无余额和截断输出没有在生产测试账号上主动制造；超时、不可用、非法 JSON、虚构 evidenceId 和越权 Runbook action 已由自动化测试覆盖并进入 `FALLBACK`/`REJECTED`。
- 基础 Compose 仍默认关闭 Stage 4。发布脚本只有在 overlay 与两个 Secret 同时存在时才启用；回滚到不含 overlay 的历史发布会恢复禁用态。
- API Key 曾由用户在对话中明文提供。云端存储已收紧，但仍应在本次验收后到 Provider 控制台轮换，以消除对话历史暴露风险。
