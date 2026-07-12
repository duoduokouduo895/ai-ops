# Stage 4 DeepSeek 本地接入验证

验证日期：2026-07-13

## 已验证

- Spring AI 2.0.0 的 `spring-ai-starter-model-openai` 可与 Spring Boot 4.1.0、Java 21 完成依赖解析和编译。
- 基础配置使用 `spring.ai.model.chat=none`，没有模型 Key 时应用上下文仍能启动。
- 每次 Provider 请求固定模型 `deepseek-v4-flash`、JSON Object 输出、`thinking.type=disabled`、`maxTokens=1200`、HTTP timeout 12 秒和 SDK retry 0。
- Stage 4 每 UTC 日最多原子预留 20 次模型调用；超限时在调用模型前安全降级。
- 输入只包含受控字段且最多 16,000 字符；Stage 4 请求/响应审计默认保留 30 天。
- 基础 Compose 和叠加 `compose.stage4.yaml` 的配置均已在云端 Docker Compose v5.1.4 通过 `config --quiet`。

## 尚未验证

- V8 迁移与 PostgreSQL 原子配额测试尚需在隔离的云端集成测试容器执行。
- DeepSeek 真实 API 的正常 JSON 响应、401、429、5xx、无余额、截断输出和实际延迟尚未验收。
- `AIDIAG_STAGE4_ENABLED` 仍必须保持 `false`，直至文件型 Key 和 Operator Secret 已挂载并完成真实 Provider 验收。

## 安全边界

DeepSeek API Key 不写入 Git、`.env.cloud`、Compose environment、文档、测试输出或应用日志。真实 Provider 验收只能通过 `compose.stage4.yaml` 挂载云端文件型 Secret。
