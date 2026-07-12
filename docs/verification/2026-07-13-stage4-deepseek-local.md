# Stage 4 DeepSeek 本地接入验证

验证日期：2026-07-13

## 已验证

- Spring AI 2.0.0 的 `spring-ai-starter-model-openai` 可与 Spring Boot 4.1.0、Java 21 完成依赖解析和编译。
- 基础配置使用 `spring.ai.model.chat=none`，没有模型 Key 时应用上下文仍能启动。
- 每次 Provider 请求固定模型 `deepseek-v4-flash`、JSON Object 输出、`thinking.type=disabled`、`maxTokens=1200`、HTTP timeout 12 秒和 SDK retry 0。
- Stage 4 每 UTC 日最多原子预留 20 次模型调用；超限时在调用模型前安全降级。
- 输入只包含受控字段且最多 16,000 字符；Stage 4 请求/响应审计默认保留 30 天。
- 基础 Compose 和叠加 `compose.stage4.yaml` 的配置均已在云端 Docker Compose v5.1.4 通过 `config --quiet`。

## 后续由云端验收覆盖

- V8 迁移、PostgreSQL 原子配额、未授权请求和 DeepSeek 正常 JSON 响应已于云端验收通过，证据见 [`2026-07-13-stage4-deepseek-cloud.md`](./2026-07-13-stage4-deepseek-cloud.md)。
- 429、5xx、无余额和截断输出未在真实账号上主动制造；这些分支由测试替身验证安全降级，避免为验收故意消耗额度或制造 Provider 故障。
- 基础 `compose.yaml` 继续默认禁用 Stage 4；仅部署脚本检测到 `compose.stage4.yaml` 和两个 Secret 时才启用。

## 安全边界

DeepSeek API Key 不写入 Git、`.env.cloud`、Compose environment、文档、测试输出或应用日志。真实 Provider 验收只能通过 `compose.stage4.yaml` 挂载云端文件型 Secret。
