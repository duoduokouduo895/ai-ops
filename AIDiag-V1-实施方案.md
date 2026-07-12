# AIDiag V1 实施方案

> 文档版本：V1.2（Stage 4 DeepSeek 接入基线）  
> 更新日期：2026-07-13  
> 定位：面向 Java 微服务的只读智能诊断层。第一批真实接入目标为 AI-chat，并以受控 Fault Lab 完成故障评测。

---

## 1. 目标与原则

AIDiag 不替代 Prometheus、Loki、Tempo 或 Grafana。平台负责把告警、指标、日志、Trace 和受控知识整理为可追溯证据，生成经过验证的候选根因和处置建议。

V1 的成功闭环：

~~~
故障 -> 告警可靠接收 -> 证据采集和持久化
     -> 候选根因生成与验证 -> 引用证据的报告 -> 可重复评测
~~~

### 1.1 第一性原则

- 先证据、后模型：没有三类遥测和 Ground Truth，不以 Agent 推理替代诊断。
- 受控工作流：LLM 只能提出候选和请求高层工具；查询、验证、评分和证据引用由程序控制。
- 持久化优先：告警、任务、审计先写 PostgreSQL，再异步执行；重启不得丢任务。
- 最小 Fault Lab：首期仅实现 Order Service -> Payment Service -> PostgreSQL，足以覆盖四类必选故障。
- 默认只读、默认脱敏：V1 没有 Shell、kubectl、重启、改配置、数据库写操作等执行型工具。
- 版本可复现：镜像锁定 patch 和 digest，应用依赖走 BOM，禁止 latest。

---

## 2. V1 边界

### 2.1 必须完成

- Alertmanager 自动创建或更新 Incident；
- Metrics、Logs、Traces 三类证据；
- PostgreSQL 持久化的任务领取、租约、重试和审计；
- 受控 Tool Calling、结构化输出和 evidenceId 校验；
- 数据库慢查询、HikariCP 耗尽、下游超时、线程池耗尽四类稳定故障；
- Top-1、Top-3、诊断耗时、证据完整率等评测；
- AI-chat 可按统一遥测契约接入。

### 2.2 不做

- Kafka、Kubernetes、多 Agent、自动修复、生产执行命令；
- 企业级多租户、RBAC、SSO、完整告警中心；
- 把实时原始日志写入向量库；
- 同时使用 Spring AI 和 LangChain4j；
- 为演示实现完整四服务业务系统或复杂前端。

---

## 3. 总体架构

~~~mermaid
flowchart LR
    subgraph TARGET["被监控服务：AI-chat / Fault Lab"]
        APP["Spring Boot 服务"]
        METRICS["Actuator + Micrometer"]
        LOGS["JSON Structured Logs"]
        AGENT["OpenTelemetry Java Agent"]
        APP --> METRICS
        APP --> LOGS
        APP --> AGENT
    end

    METRICS -->|"/actuator/prometheus"| PROM["Prometheus"]
    PROM --> AM["Alertmanager"]
    AM -->|"Webhook"| ALERT["AIDiag Alert Module"]

    LOGS --> ALLOY["Grafana Alloy"]
    AGENT -->|"OTLP HTTP :4318"| ALLOY
    ALLOY --> LOKI["Loki"]
    ALLOY --> TEMPO["Tempo"]

    ALERT --> DB[("PostgreSQL + pgvector")]
    DB --> WORKER["Diagnosis Worker"]
    WORKER -->|"PromQL"| PROM
    WORKER -->|"LogQL"| LOKI
    WORKER -->|"TraceQL"| TEMPO
    WORKER --> DB

    GRAFANA["Grafana"] --> PROM
    GRAFANA --> LOKI
    GRAFANA --> TEMPO
~~~

### 3.1 遥测管道决策

- 使用 Grafana Alloy 作为唯一遥测网关：采集 Docker JSON 日志、接收 OTLP Trace、转发至 Loki 和 Tempo。
- 不再使用 Promtail；其已于 2026-03-02 EOL。
- V1 不单独部署 OpenTelemetry Collector，避免双 Collector 的重复采集和配置漂移。
- Prometheus 直接 scrape Micrometer 暴露的 Actuator 端点；OTel Java Agent 仅导出 Trace，并关闭其 Metrics/Logs 导出。
- Grafana 直接连接 Prometheus、Loki、Tempo；AIDiag 提供 Incident 和报告 API，不作为 Grafana 的遥测中转。

### 3.2 统一遥测字段

所有服务必须设置：

~~~
service.name
service.version
service.instance.id
deployment.environment.name
service.namespace
~~~

JSON 日志至少包含：

~~~
timestamp, level, serviceName, environment, traceId, spanId,
requestId, threadName, logger, message, exceptionType, exceptionMessage
~~~

traceId、requestId、conversationId 不得成为 Prometheus 或 Loki label；日志标签仅保留 service_name、environment、level 等低基数字段。

---

## 4. 技术栈与版本锁定

以下为 2026-07-11 的实施基线。版本升级只能通过独立 PR、端到端遥测烟测和依赖漏洞检查后合入。

| 范围 | 固定版本 | 使用规则 |
|---|---:|---|
| JDK | Temurin 21.0.11+10 LTS | 云端容器运行时统一 Java 21；本机仅编辑文档时不强制安装 |
| Spring Boot | 4.1.0 | 使用 spring-boot-starter-parent |
| Spring AI | 2.0.0 | 使用 spring-ai-bom |
| Maven Wrapper | 3.9.16 | 提交 Maven Wrapper |
| SpringDoc | 3.0.3 | 使用 Boot 4 对应的 3.x starter |
| Resilience4j | 2.4.0 | 使用 resilience4j-spring-boot4 |
| PostgreSQL | 18.4 | 时间列统一 timestamptz |
| pgvector | 0.8.5 | 仅用于受控 Runbook，不存实时日志 |
| OTel Java Agent | 2.29.0 | OTLP HTTP/protobuf，端口 4318 |
| Prometheus | 3.13.1 | 固定镜像和规则模板 |
| Alertmanager | 0.33.1 | Webhook 内网鉴权 |
| Grafana Alloy | 1.17.1 | 唯一日志和 Trace 网关 |
| Loki | 3.7.3 | V1 单机模式 |
| Tempo | 3.0.2 | V1 单机模式，启用 TraceQL |
| Grafana | 13.1.0 | 数据源和 Dashboard provisioning |

### 4.1 版本规则

- 不手动覆盖 Boot BOM 已管理的 Jackson、Micrometer、Flyway、Hibernate、PostgreSQL Driver 等版本。
- Spring AI 依赖统一由 spring-ai-bom 管理，禁止混用旧 artifactId。
- Docker Compose 使用完整 patch 和 sha256 digest；版本与 digest 记录在 versions.env。
- Maven 启用 Enforcer 的 dependency convergence、禁止 SNAPSHOT，并生成 CycloneDX SBOM。
- 每月检查 patch/CVE；大版本升级前必须通过 Compose 集成测试和一次完整故障实验。

### 4.2 Java 版本决策

Java 21 不是 Spring Boot 4 的最低硬性要求；Spring Boot 4.1 最低支持 Java 17。V1 仍选择 Java 21，原因是它是成熟 LTS，且虚拟线程等能力在后续并发诊断任务中可直接使用。

### 4.3 Stage 4 模型决策

截至 2026-07-13，Stage 4 选择 DeepSeek 官方 API 的 `deepseek-v4-flash`，固定使用官方 OpenAI 兼容端点 `https://api.deepseek.com`。官方文档已将该模型列为可用模型，并声明其支持 OpenAI ChatCompletions、JSON Output、Tool Calls、1M 上下文以及 thinking/non-thinking 双模式，因此它能够承载当前“单轮、只读、结构化建议”的预览场景。

选择该模型不等于已经允许生产启用，Provider 接入必须满足以下约束：

- 使用 Spring AI 2.0.0 管理的 `spring-ai-starter-model-openai`，不得再引入另一套 AI 编排框架；通过 `spring.ai.openai.base-url` 指向 DeepSeek 官方端点。
- 首轮验收显式传递 `thinking.type=disabled`。DeepSeek V4 默认启用 thinking，而当前预览接口超时为 12 秒；在延迟和 `reasoning_content` 兼容性尚未实测前，不依赖默认值。Spring AI 使用 `extraBody` 将该参数平铺到请求顶层。
- 使用原生 `response_format.type=json_object` 并继续保留本地 `AiDiagnosisAdviceValidator`。System Prompt 必须明确要求 JSON，设置较小的 `max_tokens`，并把截断、空白输出、未知字段和无法反序列化统一视为 `REJECTED`/`FALLBACK`。
- 不使用即将于 2026-07-24 23:59（北京时间）弃用的 `deepseek-chat` 或 `deepseek-reasoner` 兼容名称，代码和配置只写 `deepseek-v4-flash`。
- API Key 只能作为云端文件型 Secret 挂载，不进入 Git、`.env.cloud`、Compose environment、日志或诊断审计；启用前还需确定数据出境范围、保留期、单次 token 上限、每日费用上限和 429/5xx 重试策略。
- 先用受限额度账号完成真实 Provider 契约测试，再把 `AIDIAG_STAGE4_ENABLED` 改为 `true`。测试至少覆盖连接/读取超时、JSON Output、超长或截断输出、429、5xx、无余额、无效 Key 和敏感字段不出境。

当前低额度运行边界固定如下：

| 边界 | 默认值 | 实现方式 |
|---|---:|---|
| 每日模型请求 | 20 次/UTC 日 | PostgreSQL 原子预留，多实例并发也不能突破；达到上限后不调用模型 |
| 单次输入 | 最多 16,000 字符 | 只允许脱敏后的 Incident/确定性报告/evidence 摘要/Runbook allowlist，超限直接降级 |
| 单次输出 | 最多 1,200 tokens | 每次请求通过 `OpenAiChatOptions.maxTokens` 强制限制 |
| 自动重试 | 0 次 | Spring AI 全局 `max-attempts=1`，单次请求 `maxRetries=0`，避免 429/5xx 重复计费 |
| 请求超时 | 12 秒 | 业务 Future 与 Provider HTTP 请求同时限制 |
| thinking | 关闭 | 每次请求通过 `extraBody` 显式发送 `thinking.type=disabled` |
| Stage 4 审计保留 | 30 天 | 每日清理过期 `ai_diagnosis_attempt` 和历史预算行，不影响确定性报告 |

允许发送的数据只有：Incident ID、服务名、告警名、严重级别、确定性候选根因、验证状态、支持分、最多 12 条 evidence 的 ID/类型/来源/脱敏摘要，以及最多 5 条受控 Runbook action。禁止发送原始日志、原始指标/Trace、Alertmanager annotation、用户 Prompt、AI-chat 完整问答、Cookie、Token、连接串和其他 Secret。

选型依据：[DeepSeek API 首次调用](https://api-docs.deepseek.com/)、[模型与价格](https://api-docs.deepseek.com/zh-cn/quick_start/pricing)、[Thinking Mode](https://api-docs.deepseek.com/guides/thinking_mode)、[Spring AI OpenAI Chat](https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html)。

- 云端构建镜像和运行容器必须使用 Temurin Java 21；
- 开发者本机如果只通过远程 Docker 构建、运行和查看日志，不要求安装 Java 21；
- 本机需要运行 Maven、IDE 调试或执行测试时，应安装 Java 21，避免本地与云端运行时不一致；
- 不使用 Java 17 作为项目的编译目标，避免后期混用 JDK 造成行为差异。

---

## 5. Incident、任务与时间模型

### 5.1 告警幂等

Alertmanager 会重试、分组推送多个 alert，并发送 resolved 状态。AIDiag 必须逐条解析 alerts。

alert_event 至少保存：

~~~
receiver, group_key, fingerprint, alert_status, starts_at, ends_at,
labels, annotations, raw_payload, payload_hash, received_at
~~~

幂等键：

~~~
receiver + group_key + fingerprint + alert_status + starts_at
~~~

同一幂等键重复到达只更新接收计数和最后接收时间，不重复创建 Incident 或诊断任务。Alertmanager group 明确为 `resolved` 时，Incident 转为 `RESOLVED`，但不自动进入 `CLOSED`；之后相同关联键再次 firing 可以创建新的 Incident。

### 5.1.1 Stage 2 聚合与鉴权收紧

Stage 2 的一个 Alertmanager group 对应一个**活动 Incident**，而不是每条 alert 各建一单。关联键固定为：

~~~
correlation_key = SHA-256(environment + receiver + group_key)
~~~

数据库使用活动状态的部分唯一索引保证并发 Webhook 最多创建一个 Incident。一个 group 内的每条 alert 都保存为独立 `alert_event` 并关联该 Incident；Incident 关闭后，相同关联键再次 firing 可以创建新的 Incident。

Webhook 仅接受 Alertmanager Basic Auth；后端和 Alertmanager 共享同一个文件型 Secret。认证失败返回 `401`，无效 JSON、缺少必填字段、非法时间或超限 payload 返回 `400/413`，数据库失败返回 `5xx` 以促使 Alertmanager 重试。不得返回“已接受”但实际没有持久化任何数据。

### 5.2 状态机

Incident 与诊断执行状态分离：

~~~
Incident: OPEN -> DIAGNOSING -> DIAGNOSED -> RESOLVED -> CLOSED
                         \-> FAILED

DiagnosisRun: PENDING -> LEASED -> COLLECTING -> ANALYZING -> VERIFYING
              -> GENERATING_REPORT -> COMPLETED
              \-> RETRY_WAIT -> FAILED

Stage 2 交接终态：EVIDENCE_READY
~~~

retry 必须创建新的 diagnosis_run（attempt + 1），不得覆盖既有报告或证据。

`EVIDENCE_READY` 表示告警事件、Incident 和可审计任务交接已可靠完成，**不表示**已经生成报告、验证根因或完成诊断。Stage 3 才允许从该状态进入分析、验证和报告生成。

### 5.3 持久化任务领取

Webhook 在单事务中保存 alert_event、创建或关联 Incident、插入 diagnosis_run(PENDING)，再返回 202 Accepted。

Worker 通过 PostgreSQL 的 FOR UPDATE SKIP LOCKED 领取任务，并维护：

~~~
lease_owner, lease_token, lease_generation,
lease_until, heartbeat_at, attempt, available_at
~~~

Worker 使用 `FOR UPDATE SKIP LOCKED` 原子领取；每次领取都生成新的 `lease_token`。所有心跳、状态推进、审计和 Evidence 写入必须同时匹配 `id + lease_owner + lease_token`，更新行数为零时旧 Worker 立刻停止，防止租约过期后的陈旧写入。`LEASED`、`COLLECTING`、`ANALYZING`、`VERIFYING` 和 `GENERATING_REPORT` 中任一仍持有但已过期的任务，均可被其他 Worker 接管。失败使用有限指数退避，并创建新的 attempt；超过最大 attempts 后标记 FAILED 并保留原因。

### 5.4 两阶段诊断时间窗

原设计中“立即诊断、读取告警后 5 分钟数据、120 秒完成”互相矛盾。V1 改为：

~~~
T0 ~ T0+120s：初始诊断，窗口 [startsAt - 10m, now - ingestionLag]
T0 + 5m：补充诊断，窗口扩展到 [startsAt - 10m, startsAt + 5m]
~~~

初始诊断 SLA 为 120 秒；最终报告等待时间单独记录。

---

## 6. 数据模型

| 表 | 核心职责 |
|---|---|
| incident | 故障事件主体、状态、严重级别和时间线 |
| alert_event | Alertmanager 不可变原始事件及幂等信息 |
| incident_alert | Incident 与 Alert 的关联 |
| incident_affected_service | 受影响服务及角色：ROOT/SYMPTOM/DOWNSTREAM |
| diagnosis_run | 每次诊断 attempt 的状态、租约、模型和耗时 |
| tool_call_audit | 工具请求、摘要、状态、耗时和错误 |
| evidence | 不可变证据快照、查询条件、哈希和保留期 |
| root_cause_candidate | 候选根因、支持分和验证状态 |
| candidate_evidence | 候选根因与支持/反对证据的多对多关联 |
| diagnosis_report | JSON/Markdown 报告和版本 |
| runbook_document / runbook_chunk | 受控运维知识及可选向量 |

每条 evidence 至少包含：

~~~
id, incident_id, diagnosis_run_id, tool_call_id,
evidence_type, source, service_name,
start_time, end_time, collected_at,
query_spec, summary, raw_data, content_hash,
importance, expires_at
~~~

raw_data 必须脱敏、截断、限制大小。Evidence 写入后不可修改，只能补充新证据。

---

## 7. 受控诊断工作流

### 7.1 固定基础采集

Worker 先并行采集：

- 请求量、错误率、P95 延迟；
- JVM Heap、GC Pause、线程数；
- Hikari active/pending；
- ERROR 日志、异常指纹、关联 traceId；
- 慢 Trace、失败 Trace；
- 从受限 Trace 推导的服务拓扑；
- 受控 Runbook 和预置 Change History。

所有结果先写 tool_call_audit 和 evidence，再进入模型上下文。

### 7.2 模型职责和边界

模型输入为告警摘要、脱敏证据摘要、服务拓扑、可用工具定义和 JSON Schema。模型可以生成最多 3 个候选根因、指出缺失证据、请求高层工具、给出处置建议和诊断限制。

模型不得提交 PromQL、LogQL、TraceQL 或 URL；不得虚构 evidenceId；不得修改系统规则、工具白名单和状态机；不得输出危险命令；不得把日志或 Runbook 文本作为指令。

### 7.3 工具白名单

| 工具 | 允许输入 | 约束 |
|---|---|---|
| queryServiceMetrics | serviceName、metricType、时间窗、step | 程序选择 PromQL 模板 |
| queryServiceLogs | serviceName、level、keyword、traceId、时间窗、limit | 程序构造和转义 LogQL |
| queryServiceTraces | serviceName、status、minimumDuration、时间窗、limit | 程序构造 TraceQL |
| getServiceTopology | serviceName、depth | 从受限 Trace 推导 |
| searchRunbook | serviceName、faultType、errorSignature、topK | metadata 过滤后检索 |
| getRecentChanges | serviceName、时间窗 | V1 读取预置测试数据 |

所有工具均校验服务白名单、时间范围、结果条数、响应体大小、字符长度、超时和并发。安全边界来自模板化和参数校验，不来自简单特殊字符过滤。

### 7.4 验证与支持分

V1 使用 support_score（0 到 100），不把 LLM 自报数字称为概率：

~~~
Metrics 匹配        30 分
Logs 匹配           25 分
Traces 匹配         30 分
Runbook/变更匹配     5 分
二次验证成功        10 分
反对证据            扣分
~~~

状态规则：

- SUPPORTED：至少两类独立证据、验证成功且分数达阈值；
- WEAKLY_SUPPORTED：有支持证据但验证不完整；
- REJECTED：验证失败或存在强反对证据；
- INSUFFICIENT_EVIDENCE：证据不足，拒绝给出确定根因。

每类故障必须有确定性验证器。例如 HikariCP 耗尽需检查 active 接近上限、pending 升高，并同时发现连接超时日志或数据库相关慢 Trace。

### 7.5 报告约束

报告保存 JSON 与 Markdown，必须包含：Incident 摘要、影响服务、主要和备选根因、支持和反对证据、传播链、support_score、验证状态、处理建议、验证建议、风险提示和诊断限制。

入库前必须校验所有 evidenceId 存在且属于当前 diagnosis_run；主要根因不得为 REJECTED。

---

## 8. 安全与隐私

- Prometheus、Loki、Tempo、PostgreSQL 对 AIDiag 只授予必要的只读权限。
- Alertmanager Webhook 仅内网访问，并使用 Basic Auth、Bearer Token 或 mTLS。
- 数据源地址来自服务端固定配置，模型不可控制 URL，防止 SSRF。
- 故障注入仅在 demo profile 创建；生产 profile 中不存在相关 Bean 和路由。
- 发送给模型前脱敏密码、Token、Cookie、Authorization、连接串、邮箱、手机号、用户 Prompt 和完整回答。
- 日志、Runbook、异常文本和模型输出均是不可信数据；模型输出必须经过 JSON Schema、evidenceId、枚举、长度和危险建议校验。

限额：

~~~
单次诊断最大工具调用：20
初始诊断总时长：120 秒
单工具超时：10 秒
单次日志上限：200 条
单次 Trace 上限：50 条
LLM 调用与工具调用均需限流和熔断
~~~

---

## 9. AI-chat 接入契约

AI-chat 通过遥测协议接入，不依赖 AIDiag 的业务代码。它必须提供：

- Actuator 和 /actuator/prometheus；
- OTel Java Agent，向 Alloy 的 4318 端口上报 Trace；
- 标准 JSON 日志和 traceId/spanId；
- 第 3.2 节资源属性；
- 服务名、环境、指标映射和告警阈值的注册配置。

AI-chat 专属指标：

| 类别 | 指标 |
|---|---|
| 请求体验 | 请求数、错误率、P95、首 Token 时间 TTFT、完整响应耗时、SSE 取消率 |
| 上游模型 | Provider 调用耗时、错误、429/限流、重试次数 |
| 成本与容量 | 输入/输出 Token、活跃流数、Tool Calling 耗时和失败率 |
| 隐私 | 仅记录聚合数值和错误类别，不记录 Prompt、回答和完整会话内容 |

GenAI 语义约定仍在演进。V1 使用稳定内部命名空间（如 aichat.*）作为长期指标契约，并通过适配层映射至 OTel GenAI 语义；不把实验性字段直接写死为数据库或告警契约。

---

## 10. 故障实验与评测

| 故障 | 注入方式 | 必须验证的信号 |
|---|---|---|
| 数据库慢查询 | 模拟慢 SQL 或查询延迟 | P95、DB Span、慢查询或错误日志 |
| HikariCP 耗尽 | 小连接池、并发占用、延迟释放 | active/pending、连接超时、DB Trace |
| 下游超时 | Payment 固定延迟 | Order 延迟、HTTP 超时、Payment Span |
| 线程池耗尽 | 小线程池、慢任务、有界队列 | active/queue/reject、异常日志、接口延迟 |

每类故障至少 5 次；20 次仅是冒烟基线。正式效果结论还必须加入：无故障对照、不同强度和随机种子、未知/弱信号、双故障组合、规则基线与规则+LLM 对比、Ground Truth、模型/Prompt/工具版本和证据快照。

评测指标：

~~~
Top-1 Root Cause Accuracy
Top-3 Root Cause Recall
Mean / P95 Time To Diagnose
Evidence Completeness
Tool Call Success Rate
Abstention Precision
Token Cost per Diagnosis
~~~

未经实际测量，不得在 README、简历或演示中声称准确率或诊断时长。

---

## 11. 云端 Docker Compose 部署与启动验收

V1 容器：

~~~
aiops-backend
diagnosis-worker（可与 backend 同镜像、不同 profile）
order-service
payment-service
postgresql-pgvector
prometheus
alertmanager
grafana-alloy
loki
tempo
grafana
~~~

所有容器设置健康检查、资源上限、重启策略、独立网络、持久卷和日志滚动。Prometheus、Loki、Tempo、PostgreSQL 仅在内部网络暴露。

### 11.1 部署目标与协作方式

V1 的 Docker Compose 部署目标为云端服务器：

~~~
34.150.80.173
~~~

V1 优先采用“本机 PowerShell + 专用 SSH 密钥直连”协作模式：SSH 私钥仅保留在用户本机受保护的目录，服务器仅保存公钥；代理经 `root@34.150.80.173` 执行部署与只读检查。手动执行、受控文件上传和浏览器模式均为回退方案。

如用户在 Chrome 中已登录正确的 GCP 项目，并明确授权浏览器协作，可使用“浏览器受控操作模式”：认证状态保留在用户浏览器中，代理通过 GCP Console 完成可视化操作。该模式不传递、不保存密码、SSH 私钥或 GCP Service Account Key，也不等同于获得宿主机 Root Shell。

- 本地负责生成源代码、Compose、配置、数据库迁移、验证命令和预期结果，并在已授权的 SSH 会话中执行低风险部署操作；
- 用户负责首次 SSH 公钥授权、Docker 安装、系统权限、防火墙、密钥、云盘，以及所有高风险操作的确认；
- 本地将记录执行命令、exit code、状态和脱敏日志，据此判断是否通过验收或生成下一步操作；
- 不要求提供 SSH 私钥、密码、Token、Cookie 或云平台密钥；
- 不通过 TCP 2375/2376 暴露 Docker Daemon，也不把 Docker Socket 转发至公网；
- 首次部署前，云端需要安装 Docker Engine 和 Docker Compose v2；当前部署账户为 root。Docker 组账户同样接近 root 权限，不能将其当作有效隔离边界；
- 云主机防火墙仅放行必要端口：SSH 22（限制可信来源）、AIDiag API/前端端口、Grafana 端口；Prometheus、Loki、Tempo、PostgreSQL、Alloy OTLP 接收端口仅保留在 Docker 内部网络；
- 如必须公开 AIDiag 或 Grafana，必须在反向代理后使用 HTTPS 和身份认证，不能直接暴露基础设施端口；
- PostgreSQL、Prometheus、Loki、Tempo 和 Grafana 数据卷必须位于云主机持久磁盘，并制定快照或备份策略。

### 11.2 职责边界

| 工作 | 本地实现方 | 用户云端操作 | 共同验收 |
|---|---|---|---|
| 代码、Compose、配置、迁移 | 编写并静态检查 | 获取文件或拉取仓库 | 对比文件版本和配置摘要 |
| Docker/Compose 安装 | 给出与 OS 匹配的步骤 | 确认并执行 | 返回版本输出 |
| 防火墙、域名、HTTPS | 给出最小端口与代理方案 | 在云平台和服务器执行 | 外部连通性与未授权访问检查 |
| 密钥和 .env.cloud | 提供无敏感值模板 | 在云端填写并设置权限 | 仅返回变量名和校验结果 |
| 启停、迁移、回滚 | 给出命令、影响和预期 | 手动执行 | 返回脱敏日志与状态 |
| 故障实验 | 生成脚本与 Ground Truth | 在 demo 环境执行 | 共同核对三类信号和报告 |

### 11.3 SSH 直连与浏览器回退的授权规则

SSH 直连只在用户将 AIDiag 专用公钥加入目标主机 `root` 的 `authorized_keys`、验证目标 SSH Host Key 指纹且明确授权后启用。私钥保留在本机，不进入仓库、对话或远端。删除 `authorized_keys` 中对应公钥即可撤销访问。

浏览器受控操作只在用户已登录 GCP Console、明确要求使用 Chrome，并指定目标项目或目标资源时启用。它适合创建实例、查看日志、配置网络规则、上传/下载文件和使用 Cloud Shell；Docker 容器内的部署仍优先使用云端终端的明确命令。

以下操作必须在执行前再次得到用户确认：

- 创建、升级或删除 VM、磁盘、静态 IP、快照和其他计费资源；
- 修改 VPC 防火墙、SSH、IAM、服务账号或公网访问；
- 删除容器、数据卷、数据库或覆盖生产配置；
- 将 AIDiag、Grafana 或任何基础设施端口公开到互联网。

用户不应向聊天发送密码、SSH 私钥、Cookie、Token 或 GCP Key。浏览器会话退出、权限失效或用户关闭页面后，回退到手动云端操作模式。

### 11.4 云主机资源基线

V1 最低建议为 4 vCPU、8 GB RAM、60 GB SSD 持久磁盘；推荐 8 vCPU、16 GB RAM、100 GB SSD，以便同时运行 Fault Lab、Grafana 栈和诊断服务。资源不足时，优先缩短 Loki/Tempo/Prometheus 保留期，不删除 PostgreSQL 的任务和审计数据。

### 11.5 云端执行门禁

每次云端操作按以下顺序进行：

1. 本地先生成待执行命令、影响说明、预期输出和回滚方式；
2. 用户确认命令只作用于 AIDiag 目录、容器、网络和数据卷；
3. 本地通过 SSH 执行低风险命令并记录 exit code、状态和脱敏关键日志；直连不可用时由用户在云端执行并返回相同信息；
4. 验收通过后才进入下一阶段；
5. 删除数据卷、清理数据库、覆盖配置、修改防火墙或升级大版本等高风险操作必须再次单独确认。

完整步骤见 [云端实施操作手册](./云端实施操作手册.md)。

启动烟测：

1. Order 和 Payment 能完成正常调用链；
2. Prometheus 能抓到 Metrics；
3. Loki 能按 service 和 traceId 查询日志；
4. Tempo 能查询完整跨服务 Trace；
5. 触发一条测试告警后，仅创建一个 Incident；
6. Worker 重启后，租约过期任务能重新领取；
7. 报告中的全部 evidenceId 可以回查。

---

## 12. 实施阶段与验收

### 12.1 阶段 0 Smoke Contract（必须先完成）

阶段 0 只证明单个 AIDiag 后端的三类遥测可端到端写入、查询和关联；不包含 Order/Payment 跨服务调用、Alertmanager 创建 Incident、Worker 租约或诊断报告，它们分别属于阶段 1、2 和 3。

Smoke 功能使用 `POST /internal/smoke/telemetry`，且只在 `AIDIAG_SMOKE_ENABLED=true` 时注册；默认关闭、仅经 127.0.0.1 SSH 隧道访问、没有数据库写入和任意参数。每次调用生成唯一 `smokeRunId`：

| 信号 | 固定内容 | 验收方式 |
|---|---|---|
| Metric | `aidiag_smoke_telemetry_events_total{scenario="stage0"}` | Prometheus 在时间窗内观察到计数递增；`smokeRunId` 不得作为 Metric label |
| Log | JSON 日志包含 event、smokeRunId、traceId、spanId、scenario | Loki 以 service、时间窗和 smokeRunId 查询到唯一测试日志 |
| Trace | HTTP Server Span 带 `aidiag.smoke.run.id` 与事件 | Tempo 以 smokeRunId 查询到 Trace，且其 traceId 等于日志 traceId |

验收脚本每 2～5 秒重试，最长 60 秒；额外验证不存在的 `smokeRunId` 查询不到结果、日志和 Trace 不含 Secret。验收结束后将 `AIDIAG_SMOKE_ENABLED` 恢复为 `false` 并重建后端，接口必须返回 404。仅在上述证据归档后，阶段 0 才可标记完成。

### 阶段 0：契约和基础设施基线

- [x] [本地实现] 固化版本表、镜像 digest、遥测字段、状态机和数据库迁移；
- [x] [本地实现] 编写 Compose、Alloy、Prometheus、Alertmanager 配置和云端命令；
- [x] [云端实施] 按操作手册检查服务器、核验 Docker，并运行 Compose；
- [x] [共同验收] 按第 12.1 节 Smoke Contract 用合成 Metric、Log、Trace 完成端到端烟测。

验收：三类信号可写入、查询并通过 traceId/smokeRunId 关联；没有 latest；云端从空卷可以启动；Docker Daemon 未对公网开放。

### 阶段 1：最小 Fault Lab

- [x] [本地实现] 建设 Order、Payment、PostgreSQL并接入三类遥测；
- [x] [本地实现] 实现四类受控故障和 Ground Truth；
- [x] [云端实施] 更新 demo profile 并运行故障实验；
- [x] [共同验收] 核对预期信号和资源影响。

阶段 1 的部署单元必须与 AIDiag 主库隔离：独立 `faultlab-postgres`、密码文件、命名卷、`faultlab` 内部网络和日志卷；`payment-service` 不发布宿主机端口，故障开关只能在其容器内调用。仅 `order-service` 可通过 `127.0.0.1:18081`（默认值）接收实验请求。故障控制只接受固定故障名，不提供任意 SQL、延迟、线程数、连接数或目标 URL 输入。

验收：正常链路返回 `201 COMPLETED`；每类故障均有固定 Ground Truth、Metric、Log 与 Trace 证据，并在关闭故障后恢复正常链路。完整命令和预期证据以 [`fault-lab/GROUND_TRUTH.md`](fault-lab/GROUND_TRUTH.md) 为准。

### 阶段 2：可靠 Incident 与工具层

- [x] [本地实现] 实现 Webhook 幂等、数据库级 Incident 聚合、持久化任务、租约 fencing、重试和 Stage 2 交接审计；
- [x] [云端实施] 部署新版本，完成 Flyway V2/V3 迁移和服务重启；
- [x] [共同验收] 执行重复 Webhook、真实 Alertmanager 告警和 Worker 异常退出后的租约恢复实验。

验收：重复或并发 Webhook 不重复建单；错误认证和非法 payload 不落库；重启不丢任务；租约过期后的陈旧 Worker 不能写入；任务只能到达 `EVIDENCE_READY`，不得伪造报告或 `DIAGNOSED` 状态。2026-07-12 的部署证据见 [`docs/verification/2026-07-12-stage2-incident-pipeline.md`](docs/verification/2026-07-12-stage2-incident-pipeline.md)。

### 阶段 3：规则诊断与报告

- [x] [本地实现] 实现 Prometheus/Loki 固定模板、四类 Fault Lab 确定性验证器、support_score、报告和 evidenceId 校验；
- [x] [本地实现] 修复线程池 Counter 首次抓取基线问题，改用真实线程池饱和状态告警；应用层与 Flyway V6 均保证证据不足时不写入主要根因；
- [x] [本地实现] 增加四类 Fault Lab 的可重复云端验收脚本；
- [x] [云端实施] 部署收紧后的 Stage 3 规则基线和 Flyway V6 数据库不变量；
- [x] [共同验收] 执行 `bash scripts/verify-stage3.sh`，四类受控故障均完成 firing、`SUPPORTED` 报告、恢复和 resolved 闭环。

验收：不接入 LLM 时，四类受控故障均可生成具有指标和日志证据的 `SUPPORTED` 规则基线报告；正常请求不创建新的 Fault Lab Incident。2026-07-12 的证据见 [`docs/verification/2026-07-12-stage3-stage4-foundation.md`](docs/verification/2026-07-12-stage3-stage4-foundation.md)。

### 阶段 4：Spring AI 增强

- [x] [本地实现] 使用 `spring-ai-bom:2.0.0`、`spring-ai-client-chat` 和 `spring-ai-model` 建立 Provider 无关适配边界；不引入任何模型 Provider starter；
- [x] [本地实现] 增加显式触发的 `POST /api/v1/incidents/{incidentId}/ai-diagnosis-preview`、独立 Operator Basic Auth、结构化输出校验和 `ai_diagnosis_attempt` 审计表；
- [x] [本地实现] 仅发送经过长度限制的服务、告警、确定性报告字段与 evidence 摘要；不发送原始遥测、告警 annotation、用户 Prompt 或 Secret；
- [x] [本地实现] 模型输出只能引用给定 evidenceId 和受控 Runbook action；任何无效输出、超时、不可用或异常都记录为 `REJECTED`/`FALLBACK`，且绝不改写 `diagnosis_report.primary_root_cause_id`；
- [x] [云端实施] 在 release `20260712-stage4-4` 以 `AIDIAG_STAGE4_ENABLED=false` 部署基础能力；禁用态接口为 404，审计表为空；
- [x] [模型选型] 选择 DeepSeek 官方 API 与 `deepseek-v4-flash`；首轮按非 thinking、单轮、JSON Output 模式接入，不使用即将弃用的兼容模型名；
- [x] [本地实现] 加入 `spring-ai-starter-model-openai`、DeepSeek 专用 `extraBody`、JSON Output、输出 token 上限、双层超时和零自动重试；基础栈仍通过 `spring.ai.model.chat=none` 保持禁用；
- [x] [本地实现] 增加 PostgreSQL 原子每日 20 次配额、16,000 字符输入上限和 30 天 Stage 4 审计清理；新增 `compose.stage4.yaml`，只在两个文件型 Secret 就绪后叠加启用；
- [x] [共同验收] 以测试 `Stage4ModelClient` 验证超时中断、模型不可用、虚构 evidenceId、越权 Runbook action、禁用态和降级分类；
- [ ] [Provider 验收] 选定 Provider 后，使用受限测试账号验证真实 SDK 的连接/读取超时、原生结构化输出差异、费用上限和数据出境边界。

验收：模型无效输出、超时、虚构 evidenceId 或越权 Runbook action 时，平台安全降级且可审计。默认关闭时不注册预览 Controller，接口返回 404 且不产生审计记录；启用后若未挂载独立 Operator 密码则统一返回 401，不能调用模型。

### 阶段 5：AI-chat Onboarding 与评测

- [本地实现] 按第 9 章改造 AI-chat，并生成告警和 Dashboard；
- [用户云端] 部署 AI-chat 遥测配置和 AIDiag 更新；
- [共同验收] 运行可重复实验并输出真实统计。

验收：AI-chat 的告警可生成引用其真实遥测证据的诊断报告。

---

## 13. 开工清单

- [x] 确定模型 Provider 和模型名：DeepSeek 官方 API / `deepseek-v4-flash`；
- [x] 确定模型数据出境范围、30 天保留期、每日 20 次/单次 1,200 tokens 费用边界；
- [ ] 使用受限测试账号完成真实 DeepSeek API 契约验收；
- [x] 创建模块化单体骨架；
- [x] 建立 Flyway 初始迁移及状态约束；
- [x] 创建 versions.env、镜像 digest 锁定和 Compose 基础设施；
- [x] 获取云端部署 SSH 用户名、密钥登录方式、可用磁盘容量和防火墙规则；
- [x] 定义遥测、日志、告警 label 与 AI-chat 接入契约；
- [x] 建立合成遥测端到端测试；
- [x] 完成阶段 0 验收；可进入最小 Fault Lab 的业务服务实现。

---

## 14. 后续升级边界

只有在 V1 指标证明存在具体瓶颈或能力缺口时，才考虑 Kafka 事件化、Kubernetes 只读诊断、多 Agent、变更平台接入、知识图谱、人工审批的半自动修复、多租户和 RBAC。每次升级必须说明解决的具体问题、迁移策略和可验证收益。

---

## 15. 版本来源与核验

版本基线按 2026-07-11 的官方发布信息确定。实施时应再次核验 patch 和镜像 digest：

- [Spring Boot 系统要求](https://docs.spring.io/spring-boot/system-requirements.html) 与 [Spring Boot 4.1.0](https://github.com/spring-projects/spring-boot/releases/tag/v4.1.0)；
- [Spring AI 2.0 与 Boot 兼容说明](https://docs.spring.io/spring-ai/reference/getting-started.html)；
- [Promtail EOL 与 Alloy 迁移说明](https://grafana.com/docs/loki/latest/send-data/promtail/)；
- [OpenTelemetry Java Agent 2.29.0](https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/tag/v2.29.0)；
- [PostgreSQL 18.4](https://www.postgresql.org/docs/release/18.4/) 与 [pgvector 0.8.5 变更记录](https://github.com/pgvector/pgvector/blob/master/CHANGELOG.md)；
- [Prometheus 3.13.1](https://github.com/prometheus/prometheus/releases/tag/v3.13.1)、[Alertmanager 0.33.1](https://github.com/prometheus/alertmanager/releases/tag/v0.33.1)；
- [Grafana 13.1.0](https://github.com/grafana/grafana/releases/tag/v13.1.0)、[Loki 3.7.3](https://github.com/grafana/loki/releases/tag/v3.7.3)、[Tempo 3.0.2](https://github.com/grafana/tempo/releases/tag/v3.0.2)、[Alloy 1.17.1](https://github.com/grafana/alloy/releases/tag/v1.17.1)。
