# AI-chat 接入 AIDiag 指南

> 当前生产域名：前端 `https://app.dduo.me`，API `https://api.dduo.me`。旧 `dduo.chat` 仅作为迁移期历史域名，不应继续写入新配置。

## 1. 接入目标

AI-chat 与 AIDiag 有两条独立连接：

1. **遥测连接**：AI-chat 输出 Metrics、脱敏 Logs 和 Traces，AIDiag 观测栈被动采集；
2. **只读查询连接**：AI-chat Admin BFF 使用服务器端文件 Secret 查询 Incident 和报告。

聊天、登录、知识库和模型调用主链不依赖 AIDiag。AIDiag 不可用时，AI-chat 业务仍应继续运行；只有 Admin 运维页面降级为 503。

## 2. 是否需要上传 Jar

推荐不手工上传单个 Jar，而是上传或拉取一个不可变 release 源码包，由云端 Docker 多阶段构建：

```text
release source + Dockerfile + compose.cloud.yaml + deploy scripts
  -> docker build
  -> candidate
  -> 回归
  -> production activation
```

这样测试、依赖、OTel Agent、用户权限、healthcheck 和镜像 digest 属于同一个发布身份。只有在云端无法构建且已有受信任 CI 产物时，才考虑上传带 SHA-256/SBOM 的 Jar；不能把“复制 Jar 后直接 java -jar”作为正式流程。

## 3. AI-chat 侧已经实现的代码

### Metrics

| 类 | 指标 |
|---|---|
| `ChatStreamTelemetry` | `aichat.chat.requests`、TTFT、stream duration、active streams |
| `R2dbcPoolTelemetry` | pending、acquired、allocated、max |
| `DocumentProcessingOutboxService` | enqueued、published、retry、recovered、pending |
| `KnowledgeSearchTelemetry` | search outcome |

标签只使用固定 outcome、rag 和 service identity，不使用 prompt、userId、conversationId 或异常正文。

### Logs

`logback-spring.xml` 将 `aichat.operations` 写入独立 `operations.jsonl`。生产使用 `/var/log/ai-chat/production-operations.jsonl`，候选使用 `/var/log/ai-chat/operations.jsonl`。Alloy 只读挂载该目录。

### Traces

Docker 镜像内置 OTel Java Agent；`compose.cloud.yaml` 设置：

```yaml
OTEL_EXPORTER_OTLP_ENDPOINT: http://alloy:4318
OTEL_EXPORTER_OTLP_PROTOCOL: http/protobuf
OTEL_TRACES_EXPORTER: otlp
OTEL_METRICS_EXPORTER: none
OTEL_LOGS_EXPORTER: none
```

Metrics 只走 Prometheus，Logs 只走文件 + Alloy，避免同一信号重复导出。

### Admin BFF

`AdminOperationsController -> AidiagClient -> AIDiag Read API`。浏览器只持有正常 ADMIN 登录态，不接触 AIDiag Basic Auth Secret。

## 4. 网络拓扑

AI-chat production 容器需要加入：

- `middleware-net`：MySQL、Redis、RabbitMQ、Elasticsearch；
- `nacos-net`：Nacos；
- `aidiag-telemetry`：Prometheus、Alloy、AIDiag backend。

应用端口和 management 端口只绑定宿主机 loopback：

```yaml
ports:
  - "127.0.0.1:18083:8080"
  - "127.0.0.1:19092:9091"
```

公网只通过 Nginx `443 -> 127.0.0.1:18083` 进入。Prometheus 从 Docker telemetry 网络访问 `ai-chat-production:9091`，不需要开放 management 公网端口。

## 5. Secret

需要的 AIDiag 只读 Secret：

```text
/opt/aidiag/runtime/.secrets/aidiag_read_api_secret
```

约束：

- 至少 32 字节随机值；
- AIDiag backend 和 AI-chat production 各自以只读 file Secret 挂载同值；
- 不写入 `.env`、Compose environment、命令行、日志或 Git；
- 与 Grafana、Webhook、Stage 4 operator、DeepSeek Key 完全分离。

## 6. 生产非密钥配置

以 AI-chat 仓库的 `deploy/production.env.example` 为模板。与 AIDiag 直接相关的关键项是：

```dotenv
AICHAT_SERVICE_NAME=ai-chat-production
AICHAT_NETWORK_ALIAS=ai-chat-production
AICHAT_OPS_LOG_FILE=/var/log/ai-chat/production-operations.jsonl
AICHAT_PUBLIC_HEALTH_URL=https://api.dduo.me/healthz
AIDIAG_SECRETS_DIR=/opt/aidiag/runtime/.secrets
```

运行时还会注入：

```dotenv
AIDIAG_INTEGRATION_ENABLED=true
AIDIAG_BASE_URL=http://aidiag-backend:8080
AIDIAG_READ_API_USERNAME=aidiag-reader
AIDIAG_SERVICE_NAME=ai-chat-production
```

`AICHAT_PUBLIC_HEALTH_URL` 只接受无 username/password、query、fragment 的 HTTPS URL。它由 AIDiag 激活脚本写入受控 file-SD，用于区分“JVM 内部健康”和“DNS/TLS/Nginx 公网入口故障”。

## 7. 候选接入步骤

### 步骤 1：本地门禁

在 `E:\Java\Ai-chat` 使用 Java 21：

```powershell
$env:JAVA_HOME = "$HOME\.jdks\jdk-21.0.11.10-hotspot"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd verify
Set-Location frontend
npm test
npm run build
```

前端 `npm run build` 会额外扫描生产 chunk 的静态 import graph；出现循环依赖会直接失败。

### 步骤 2：准备不可变 release

- release 名包含时间和 Git short SHA；
- 压缩包计算 SHA-256；
- 云端解压到 `/opt/ai-chat/releases/<release>`，不能覆盖旧 release；
- source、Dockerfile、Compose、deploy scripts 必须来自同一 commit。

### 步骤 3：准备候选配置和 Secret

- 复制 `deploy/candidate.env.example` 到 runtime 私有目录；
- candidate service name/alias 使用 `ai-chat-candidate`；
- Flyway、Rabbit listener、调度、ES sync、Nacos discovery 和模型预热按候选门禁关闭；
- 只绑定 loopback candidate 端口；
- 不修改 DNS/Nginx。

### 步骤 4：构建并启动 candidate

使用 AI-chat 仓库 `deploy/cloud-activate.sh` 的固定入口，不手写一套 Compose 命令。先执行 preflight/dry checks，再启动 candidate。

### 步骤 5：验证三信号

在 GCP 上检查：

```bash
docker compose ps
curl -fsS http://127.0.0.1:19091/actuator/health
bash /opt/aidiag/current/scripts/cloud-verify-ai-chat-monitoring.sh --candidate-only
```

还要验证 Prometheus 中的 `up{job="ai-chat-candidate"}=1`，Loki 存在脱敏事件，Tempo 可按 `service.name=ai-chat-candidate` 查询 Trace。

### 步骤 6：隐私回归

执行一次临时 Guest/用户 SSE Chat 后确认：

- meter labels 没有 Prompt、回答和 ID；
- operations log 没有 Authorization/Cookie/Secret；
- Trace 没有聊天正文；
- 测试用户、会话、消息、Redis、ES 派生数据被按精确 ID 清理。

## 8. 生产晋升步骤

1. 固定维护窗口，停止旧 writer 并生成 PID/端口/中间件连接消失证据；
2. 生成数据库备份并校验 SHA-256 与 gzip；
3. 完成 production identity、Nacos/Elasticsearch 稳定别名和 IK 验证；
4. 设置唯一 cutover id，并生成 approval evidence；
5. 先执行 AIDiag production monitoring activation，创建 8 个生产目标；
6. 再启动 AI-chat production；
7. 验证内部 metrics、application probe、五个依赖 probe 和公网 ingress probe；
8. 预置并 reload Nginx/TLS；
9. 用 `--resolve` 验证新站，最后才修改 DNS；
10. 运行登录、刷新、Guest/用户 SSE、管理端、知识库、OSS、ES+IK、RAG 全链回归；
11. 执行 firing → Incident → report → resolved；
12. 观察稳定窗口，归档 release、image digest、SBOM 和证据。

生产切换和回滚的详细命令以 [`release/V1-production-cutover-runbook.md`](release/V1-production-cutover-runbook.md) 为准。

## 9. AIDiag 侧四类规则

| Alert | Metric | Log marker | 主要含义 |
|---|---|---|---|
| `AiChatModelTimeout` | chat timeout count | `ai_chat_stream_finished:timeout` | 模型流达到受控超时 |
| `AiChatR2dbcPoolExhaustion` | pool pending | `r2dbc_pool_acquire:timeout` | 获取数据库连接等待 |
| `AiChatDocumentBacklog` | outbox pending | outbox retry event | 文档处理投递积压 |
| `AiChatSearchUnavailable` | search errors | knowledge search error | ES/RAG 搜索连续失败 |

新增或修改指标名、标签、log event、service name 时，必须同步修改 AI-chat telemetry test、AIDiag rule provider、Prometheus rule、Dashboard 和 contract lab，不能只改其中一处。

## 10. 验证失败时如何判断

| 现象 | 先检查 |
|---|---|
| 公网 ingress 失败，内部 application 成功 | DNS、证书、443、防火墙、Nginx upstream |
| application 与 metrics 都失败 | JVM、容器 health、启动失败日志、management port |
| application 成功，MySQL probe 失败 | Docker network、MySQL 容器/端口；不能直接断言账号错误 |
| Prometheus 有 Metric，Loki 无 Log | operations file 路径、UID 权限、Alloy mount/source |
| Loki 有 traceId，Tempo 无 Trace | OTEL endpoint、Alloy receiver/exporter、Tempo ingestion |
| Admin 运维 API 503 | AIDiag health、read Secret、service name、3 秒 timeout |
| Incident 存在但无主要根因 | 查看报告 limitations；Metric/Log 任一缺失时这是正确弃权 |

## 11. 当前注意事项

- 本次本地审计加固在获得明确确认前不会自动部署 GCP；
- Tika 已从显式 2.9.4 覆盖收敛为 LangChain4j 传递管理的 3.2.3，并通过 PDF、DOCX、文本解析回归；该源码变更仍需随下一次受控 AI-chat release 发布；
- AI-chat 的同一 `clientMessageId` 重试可避免重复数据库消息，但尚未实现完整的模型响应 replay；
- 模型回答已返回但最终 AI 消息持久化失败时，当前优先保证响应可用性，后续应增加补偿/告警；
- 登录防暴力破解和 Turnstile 仍应作为后续安全项；
- 整台 GCP 主机故障需要异机 Uptime/GCP Monitoring，不能只依赖同机 Grafana。
