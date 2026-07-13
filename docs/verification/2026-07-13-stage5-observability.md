# Stage 5.1 AI-chat 遥测与隐私验证记录

> 日期：2026-07-13  
> AI-chat release：`20260713-stage5-candidate-9`  
> AIDiag release：`20260713-stage5-observability-1`  
> AI-chat implementation commit：`4aaaf37`

## 结论

Stage 5.1 的本地指标、Trace、脱敏运维日志和隐私测试已经实现；云端 Prometheus、Loki、Tempo 三条管道已经分别打通。候选仍为被动模式，未修改 DNS/Nginx、未执行 Flyway、未启动 RabbitMQ listener 或后台任务。

最终“同一次真实 SSE Chat 请求由 traceId 关联三类信号”的门禁尚未完成。原因是候选连接共享生产数据库和 Redis，真实登录/Guest Chat 会写入用户、会话、消息和 Token 数据；本次没有把部署授权扩大为生产业务数据写入授权。

## 本地实现

- 使用 Spring Boot 3.3 BOM 管理的 `micrometer-registry-prometheus`，不单独覆盖 Micrometer 版本。
- management server 独立运行于容器 `9091`；宿主机只绑定 `127.0.0.1:19091`，Prometheus 通过 `aidiag-telemetry` 网络访问 `ai-chat-candidate:9091`。
- SSE 指标包括 `aichat.chat.requests`、`aichat.chat.ttft`、`aichat.chat.stream.duration` 和 `aichat.chat.streams.active`；标签只允许固定 `outcome` 与布尔 `rag`。
- RabbitMQ transactional outbox 增加 `enqueued/published/retry/recovered` 固定 outcome counter；R2DBC 使用 Actuator/Micrometer 自动指标，ES 异步索引队列沿用已有 bounded counter/gauge。
- OTel Java Agent 固定为 `2.29.0`，只开启 OTLP Trace exporter；Metrics 和 Logs exporter 明确为 `none`，避免重复采集。
- 运维日志由专用 logger 写入 `operations.jsonl`，字段限定为 event、result、durationMs、errorType、traceId、spanId；普通业务日志不进入 AI-chat Loki source。

## 测试结果

- 最终全量本地测试：147 tests，0 failures，0 errors，0 skipped。
- 云端镜像构建：145 tests，0 failures，0 errors，0 skipped。
- 遥测边界测试覆盖普通错误、64 个并发订阅、成功、取消、超时和重复订阅。
- 隐私断言确认 meter tag 与 JSON 运维事件不包含 Prompt、回答、userId、conversationId、Token、Authorization、Cookie 或 Secret。

## 云端验证

- AI-chat health：UP；restart count 0；Java 17.0.19。
- management 端口只绑定 loopback；从本地访问 `34.150.80.173:19091` 在 3 秒内超时，公网不可达。
- Prometheus 查询 `up{job="ai-chat-candidate"}` 返回 `1`，并成功采集 `aichat_chat_streams_active` 与 JVM 指标。
- Tempo 可按 `resource.service.name="ai-chat-candidate"` 查询到 `GET /actuator/health` Trace。
- Alloy 正在 tail `/var/log/ai-chat/operations.jsonl`；Loki 可按 `{service_name="ai-chat-candidate",event="observability_smoke"}` 查询 smoke event。
- AIDiag backend、Alertmanager、Grafana、Loki、Tempo 和数据库未随 AI-chat 候选重启；更新采集配置时只重建 Prometheus 与 Alloy。

## 对抗性审查

1. management health 从业务端口拆分后，原部署脚本仍检查 `18082/actuator/health`，会误判失败。已增加独立 loopback management 映射和端口冲突校验，门禁改查 `19091`。
2. AI-chat 日志目录若使用新 named volume 会被 root 初始化，非 root 应用无法写入。改用部署脚本预创建的 `/opt/ai-chat/logs`，权限固定为 `10001:10001/0750`，Alloy 只读挂载。
3. 初次更新 AIDiag 时 Compose 命令没有显式传入 runtime Secret 目录且未使用 `--no-deps`，Secret bind 校验阻止了依赖容器重建。未产生数据修改；随后使用 `AIDIAG_SECRETS_DIR=/opt/aidiag/runtime/.secrets` 和 `--no-deps`，只重建 Prometheus/Alloy。所有原服务保持运行。
4. Prometheus 未映射宿主机端口，直接检查 `127.0.0.1:9090` 会得到假失败。最终改为通过 telemetry network IP 和容器 health 验证。

## 遗留门禁

- 需要获得一次“允许候选写入临时 Guest/会话/消息数据”的明确确认，或提供不接触生产数据的隔离测试库，才能执行真实 SSE Chat 三信号关联验收。
- 当前 Loki smoke event 只验证采集管道，不代表真实 Chat 运维事件已经在云端产生。
- Nacos 匿名读取和历史 Secret 轮换仍属于 Stage 5.0 未完成安全门禁，正式切流前必须解决。
