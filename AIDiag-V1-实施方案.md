# AIDiag V1 实施方案

> 文档版本：V1.7（V1 发布兼容性与安全门禁收紧）
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
- Fault Lab 保留数据库慢查询、HikariCP 耗尽、下游超时、线程池耗尽四类稳定故障；AI-chat 接入使用 R2DBC Pool、模型、文档队列和搜索链路的真实故障模型；
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

每类故障必须有确定性验证器。例如 Fault Lab 的 HikariCP 耗尽需检查 active 接近上限、pending 升高，并同时发现连接超时日志或数据库相关慢 Trace；AI-chat 的连接池规则必须改查 R2DBC Pool 的 allocated/acquired/pending 和获取连接超时，不能复用 Hikari 指标。

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

AI-chat 通过遥测协议接入，不依赖 AIDiag 的业务代码。V1 发布候选基线已收紧为 Java 21、Spring Boot 4.0.7、Spring Cloud 2025.1.2、Spring Cloud Alibaba 2025.1.0.0、Nacos Client 3.1.1、WebFlux、R2DBC、RabbitMQ、Elasticsearch、Redis 和 LangChain4j 1.15.1；不把 Spring AI 引入 AI-chat。两个项目只通过 Prometheus、Alertmanager、Loki、OTLP 和 AIDiag HTTP API 形成协议级边界。

该组合不是“所有组件都取最新”的拼接：Spring Cloud 2025.1.2 是与 Boot 4.0/4.1 对齐的受支持 release train，Spring Cloud Alibaba 2025.1.0.0 负责 Boot 4 / Cloud 2025.1 / Nacos Client 3.1.1 的组合。AI-chat 暂不升到 Boot 4.1，是因为当前 Alibaba 稳定发布线仍以 2025.1 为兼容基线。LangChain4j 核心固定 1.15.1，Spring Boot 4 starter 固定 1.15.1-beta25；beta 仅限 Spring 集成层，必须以全量回归和真实 Provider 契约测试约束风险。

当前云端 Nacos Server 仍为 2.1.1，而新版客户端为 3.1.1。编译和单元测试不能证明该跨代组合可用于生产；正式发布前必须在隔离 Nacos 3 候选上完成配置读取、鉴权、服务注册、断线重连和回滚验证。官方升级说明明确指出 2.1.x～2.4.x 可升级到 3.x，但数据库表结构发生变化，因此不得直接替换现有 Nacos 容器。

2026-07-13 的私有候选已进一步证明该门禁必要：新版客户端连接现有 2.1.1 时把 9 个已有 Data ID 判定为空，而服务端 HTTP API 对同一 Data ID 均返回 200 和非空正文。候选因此缺少 R2DBC 配置并 fail closed，激活脚本恢复旧候选。后续不允许通过本地默认凭据或把 Nacos import 改为 optional 来绕过；必须改用隔离 Nacos 3 候选验证。

截至 2026-07-13，隔离基线改为当前稳定版 Nacos Server 3.2.2，并固定 `v3.2.2` 镜像 digest。官方最低内存要求为 2 GiB；本项目为候选设置 2560 MiB 容器上限、1 GiB heap 和至少 3 GiB 宿主机 `MemAvailable` 前置门禁，避免在无 swap 的主机上与现有中间件争抢到 OOM。3.2 的主服务不再兼容 Nacos 1.x/2.x HTTP OpenAPI，因此正式替换前必须清点旧脚本与非 SDK 调用；当前只让 Nacos Client 3.1.1 连接隔离候选，不对现有服务做原地升级。

数据库迁移采用“完整逻辑备份 + 官方 3.2.2 干净 schema + 白名单数据复制”，而不是复制旧 schema 后执行不透明的增量修改。只迁移 `config_info`、`config_tags_relation`、`group_capacity`、`his_config_info`、`tenant_capacity`、`tenant_info`；旧 `users`、`roles`、`permissions` 一律排除并重新初始化。`config_info_aggr`、`config_info_beta`、`config_info_tag` 已被 3.2 schema 移除，只要源表非空就 fail closed，禁止静默丢数。当前只读审计得到 19 条配置、10 条历史记录、空 namespace 无 `public` 冲突，数据库约 0.44 MiB；这些结果将在执行时再次校验，不能把本次快照当成永久前提。

首次云端候选还暴露了两个 3.2.2 默认行为：镜像会在空 AI namespace 中从 `/home/nacos/data/skills-data.zip` 与 `agentspec-data.zip` 导入内置 Skills/AgentSpecs，而 `nacos.config.namespace.compatible.mode` 默认开启，会同步旧空 namespace 与 `public`。这会使只需要 Config/Naming 的 V1 候选产生大量无关 `skill_enc.*` 和 `agentspec__enc.*` 配置，因此不可接受。收紧后的候选以只读 `/dev/null` 遮蔽两个内置包、通过 `JAVA_TOOL_OPTIONS` 在 JVM 启动前关闭 namespace 双向兼容，并在离线迁移阶段一次性把旧空 namespace 规范化为 `public`；启动后强制要求 `config_info` 与 `his_config_info` 行数仍分别等于源库基线，且空 namespace、`skill_enc.*` 与 `agentspec__enc.*` 均为 0。失败候选只停止并保留审计证据，重试必须使用新的数据库、volumes 和 Compose Project，禁止在未确认前清理失败数据。

候选鉴权启用后创建独立管理员与 `aichat-candidate` 用户。AI-chat 用户只授予 `public:DEFAULT_GROUP:config/*` 读权限和 `public:DEFAULT_GROUP:naming/*` 读写权限；候选与生产分别使用 `/opt/ai-chat/runtime/.secrets/candidate` 和 `/opt/ai-chat/runtime/.secrets/production`，避免凭据轮换破坏另一条回滚链。候选 8848/9848 只绑定 loopback，同时通过私有 Docker 网络供 AI-chat 访问。本地实现与静态验证证据见 [`docs/verification/2026-07-13-nacos3-candidate-hardening-local.md`](docs/verification/2026-07-13-nacos3-candidate-hardening-local.md)。

### 9.1 部署与私有网络

V1 推荐把 AI-chat backend 以独立 Compose Project 部署到现有 GCP Docker 主机，并加入 AIDiag 已创建的外部网络 `aidiag-telemetry`。AI-chat 业务入口继续由既有反向代理提供，管理端口、`/actuator/prometheus` 和 Alloy OTLP 4318 不映射到公网。

截至 2026-07-13，`app.dduo.chat` 是 Cloudflare Pages 前端，本地最新前端通过 `VITE_API_BASE_URL=https://api.dduo.chat/api` 访问后端；`api.dduo.chat` 仍解析到旧云服务器 `119.29.109.190`。Stage 5 不先重发前端，而是在 GCP `34.150.80.173` 将新 backend 绑定到 loopback 候选端口（初始为 `127.0.0.1:18082`），通过 SSH 和内部网络完成验收。GCP 已有 Nginx 占用 80/443，可在候选通过后增加 `api.dduo.chat` virtual host。

正式切换前必须预置 TLS、降低 DNS TTL，并保留旧 A 记录。维护窗口内由用户在 DNSPod 把 `api.dduo.chat` 切到 `34.150.80.173`；验收失败时恢复 `119.29.109.190`。不得在候选实例尚未通过业务、数据迁移、Nacos、健康和遥测门禁前修改 DNS，也不得把容器 8080/management port 直接暴露公网。

AI-chat 和 AIDiag 必须保持独立发布目录、独立回滚指针和独立资源上限；不得把两个仓库复制到同一 release，也不得让一次 AIDiag 回滚重启 AI-chat。AI-chat backend 初始内存上限为 1.5～2 GiB，发布前后记录宿主机可用内存、磁盘、容器重启次数和 JVM heap。

部署制品采用“本地 Git 归档源码 -> 校验 SHA-256 -> 云端 Docker 多阶段构建 -> 健康门禁 -> 切换 release”。不要求用户手工执行 Maven、生成 Jar 或上传 Jar；云端宿主机也不需要安装 JDK。不得上传 `target/`、`node_modules/`、本地 `.env`、Nacos 导出 Secret 或 IDE 文件。

在现有 SSH 直连保持可用的前提下，Codex 负责生成归档、上传、云端构建、切换和验收。用户在首次部署前只需确认 AI-chat 的正式域名/反向代理入口、允许的维护窗口，以及现有 Nacos 配置和第三方 Provider Secret 是否继续沿用；只有发现云端缺失且无法从现有运行配置安全确认的 Secret 时，才需要用户重新提供。用户不需要通过宝塔上传 Jar。

安全门禁：当前 Nacos 对允许访问 8848 的客户端仍可匿名读取配置，且数据库、Redis、LangChain4j、OSS 和 JWT 的部分当前 Secret 与 Git 历史中的旧值一致。候选构建可以先进行，但正式 DNS 切换前必须制定逐项轮换与回滚表，至少覆盖 Nacos 访问控制/网络、MySQL、Redis、RabbitMQ、Elasticsearch、模型 Provider、OSS 和 JWT。不得一次性无回滚地批量替换；JWT 轮换会使现有登录态失效，数据库和中间件凭据轮换还会影响旧实例回退，必须在维护窗口逐项验证。

### 9.2 遥测契约

AI-chat 必须提供：

- Actuator 和 /actuator/prometheus；
- OTel Java Agent，向 Alloy 的 4318 端口上报 Trace；
- 标准 JSON 日志和 traceId/spanId；
- 第 3.2 节资源属性；
- 服务名、环境、指标映射和告警阈值的注册配置。

Prometheus endpoint 必须位于独立 management port，只允许私有 Docker telemetry 网络访问。AI-chat 增加 `micrometer-registry-prometheus` 时不手工覆盖版本，由 Spring Boot BOM 管理；OTel Java Agent 首轮与 AIDiag 对齐为已验证的 2.29.0，并固定 digest/校验和。Metrics 走 Prometheus，Trace 走 OTLP，日志走共享只读文件卷，避免同一信号重复导出。

AI-chat 专属指标：

| 类别 | 指标 |
|---|---|
| 请求体验 | 请求数、错误率、P95、首 Token 时间 TTFT、完整响应耗时、SSE 取消率 |
| 上游模型 | Provider 调用耗时、错误、429/限流、重试次数 |
| 成本与容量 | 输入/输出 Token、活跃流数、Tool Calling 耗时和失败率 |
| 隐私 | 仅记录聚合数值和错误类别，不记录 Prompt、回答和完整会话内容 |

GenAI 语义约定仍在演进。V1 使用稳定内部命名空间（如 aichat.*）作为长期指标契约，并通过适配层映射至 OTel GenAI 语义；不把实验性字段直接写死为数据库或告警契约。

所有指标 tag 必须来自有限枚举；禁止使用 userId、conversationId、Prompt、URL 查询参数、异常全文或任意模型输入作为 tag。发送到 Loki 的运维日志只允许事件名、结果、错误类别、耗时、traceId/spanId 和受控服务属性；现有包含用户或会话标识的业务日志不得未经清理直接采集。OTel Agent 默认不采集请求/响应正文，Stage 5 也不得通过自定义 span attribute 补录 Prompt 或回答。

### 9.3 AIDiag 规则与前端边界

Stage 3 的 Fault Lab 规则不得继续作为 AI-chat 规则入口。`RuleBasedDiagnosisService` 需要收敛为按 `(serviceName, alertName)` 选择的固定规则注册表，至少隔离 `FaultLabDiagnosisRuleProvider` 与 `AiChatDiagnosisRuleProvider`。PromQL/LogQL 只能来自代码或版本化配置中的固定模板，不能把告警 annotation 或任意 label 直接拼入查询。

Stage 5 的主展示面必须位于 AI-chat 进程之外。独立 Grafana + Prometheus + Blackbox 是 break-glass 入口：即使 AI-chat JVM 未启动，也能从外部探测应用健康端点及 MySQL、Redis、RabbitMQ、Elasticsearch、Nacos 的 TCP 可达性。TCP 成功只代表网络和端口可达，不能直接宣称认证、协议或数据一致性正常。

现有 AI-chat `frontend_admin` 中的“智能运维”模块只作为**辅助入口**：它依赖 AI-chat backend 的 ADMIN 鉴权和 BFF，因此只能覆盖“项目仍能启动但部分功能慢或异常”的场景，不能承担启动失败救援。浏览器不得持有 AIDiag Secret；BFF 使用文件型只读凭据访问 AIDiag，配置缺失时仅关闭集成，不得阻止 AI-chat 启动。Stage 4 Operator Secret 仍与只读 API Secret 分离，页面读取报告不触发模型、不执行自动修复。

如果整台 GCP 主机、Docker daemon、主机网络或 Grafana 本身不可用，同机监控平面仍会一起失联。该层故障必须由宿主机外部的 uptime/云监控处理，不能由 Admin 页面或同机容器解决。

---

## 10. 故障实验与评测

| 故障 | 注入方式 | 必须验证的信号 |
|---|---|---|
| AI-chat 数据库慢查询 | 隔离测试 profile 注入查询延迟 | API/DB P95、R2DBC Span、受控慢查询事件 |
| AI-chat R2DBC Pool 耗尽 | 小连接池、并发占用、延迟释放 | acquired/allocated/pending、获取连接超时、DB Trace |
| AI-chat 模型超时 | 测试 Provider 固定延迟，不调用真实付费模型制造故障 | TTFT、流超时、Provider outcome、SSE 错误事件 |
| AI-chat 文档积压 | 暂停隔离消费者或限制消费速率 | outbox oldest age、RabbitMQ backlog、重试/失败事件 |
| AI-chat 搜索不可用 | 隔离测试实例阻断 Elasticsearch | RAG/搜索耗时与错误、Elasticsearch Span、降级日志 |

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

- [x] [本地实现] 使用 `spring-ai-bom:2.0.0`、`spring-ai-client-chat` 和 `spring-ai-model` 建立 Provider 无关适配边界；选定 DeepSeek 后，仅增加一个 `spring-ai-starter-model-openai` 作为 OpenAI 兼容传输实现，不引入第二套 AI 编排框架；
- [x] [本地实现] 增加显式触发的 `POST /api/v1/incidents/{incidentId}/ai-diagnosis-preview`、独立 Operator Basic Auth、结构化输出校验和 `ai_diagnosis_attempt` 审计表；
- [x] [本地实现] 仅发送经过长度限制的服务、告警、确定性报告字段与 evidence 摘要；不发送原始遥测、告警 annotation、用户 Prompt 或 Secret；
- [x] [本地实现] 模型输出只能引用给定 evidenceId 和受控 Runbook action；任何无效输出、超时、不可用或异常都记录为 `REJECTED`/`FALLBACK`，且绝不改写 `diagnosis_report.primary_root_cause_id`；
- [x] [云端实施] 在 release `20260712-stage4-4` 以 `AIDIAG_STAGE4_ENABLED=false` 部署基础能力；禁用态接口为 404，审计表为空；
- [x] [模型选型] 选择 DeepSeek 官方 API 与 `deepseek-v4-flash`；首轮按非 thinking、单轮、JSON Output 模式接入，不使用即将弃用的兼容模型名；
- [x] [本地实现] 加入 `spring-ai-starter-model-openai`、DeepSeek 专用 `extraBody`、JSON Output、输出 token 上限、双层超时和零自动重试；基础栈仍通过 `spring.ai.model.chat=none` 保持禁用；
- [x] [本地实现] 增加 PostgreSQL 原子每日 20 次配额、16,000 字符输入上限和 30 天 Stage 4 审计清理；新增 `compose.stage4.yaml`，只在两个文件型 Secret 就绪后叠加启用；
- [x] [共同验收] 以测试 `Stage4ModelClient` 验证超时中断、模型不可用、虚构 evidenceId、越权 Runbook action、禁用态和降级分类；
- [x] [Provider 验收] 在云端 release `20260713-stage4-deepseek-1` 挂载独立文件型 Secret；验证未授权请求为 401 且不消耗配额，授权请求通过真实 DeepSeek API 返回结构化建议并落审计，V8 原子配额测试通过。

验收：模型无效输出、超时、虚构 evidenceId 或越权 Runbook action 时，平台安全降级且可审计。默认关闭时不注册预览 Controller，接口返回 404 且不产生审计记录；启用后若未挂载独立 Operator 密码则统一返回 401，不能调用模型。2026-07-13 的云端证据见 [`docs/verification/2026-07-13-stage4-deepseek-cloud.md`](docs/verification/2026-07-13-stage4-deepseek-cloud.md)。

### 阶段 5：AI-chat Onboarding 与评测

#### Stage 5.0：部署与私有网络基线（P0）

- [x] [本地实现] 为 AI-chat backend 增加 `.dockerignore`、Java 21 多阶段 `Dockerfile`、版本锁定、独立 Compose、健康检查、资源上限和 release/rollback 脚本；
- [x] [本地验证] 从干净 Git 归档完成 Docker build 和应用测试；镜像中不得包含源码、构建缓存、前端 `node_modules` 或 Secret；
- [x] [云端实施] 上传源码归档及 SHA-256，在独立 release 构建 AI-chat；连接现有中间件网络和外部 `aidiag-telemetry`，但不公开 management/OTLP 端口；
- [ ] [安全门禁] 关闭 Nacos 匿名读取或将其收敛到受控网络，生成不回显值的凭据清单，并为每个历史 Secret 定义“新建/切换/验证/撤销/回滚”顺序；
- [ ] [切换准备] 在 GCP Nginx 预置 `api.dduo.chat`、TLS 和候选 upstream，记录 DNSPod 旧 A 记录、TTL、切换命令与回退条件；
- [ ] [共同验收] 验证业务健康、依赖连接、容器资源、重启次数、回滚和 AIDiag/AI-chat 互不连带重启。

门禁：AI-chat 正常业务链保持可用；Prometheus/Alloy 能通过私有网络访问目标；公网无法直接访问 management port 和 4318；回滚不丢业务数据。

截至 2026-07-13，前三项已由私有候选 release `20260713-stage5-candidate-8` 完成，候选仅监听 `127.0.0.1:18082`，未修改 DNS/Nginx，且关闭 Flyway、RabbitMQ listener/自动声明、定时任务、ES 同步、Nacos discovery 与模型 warmup。当前只证明“候选容器与依赖连接基线可运行”，不代表安全门禁、公开切换准备或完整业务验收已经通过；证据见 [`docs/verification/2026-07-13-stage5-candidate.md`](docs/verification/2026-07-13-stage5-candidate.md)。

#### Stage 5.1：AI-chat 遥测与隐私收紧（P0）

- [x] [本地实现] 增加 BOM 管理的 Prometheus registry、独立 management port 和受网络隔离的 `/actuator/prometheus`；
- [x] [本地实现] 为 SSE 主链增加请求结果、TTFT、完整耗时、活跃流、取消/超时和 Provider outcome；为 R2DBC、RabbitMQ outbox、Elasticsearch/RAG 增加必要的低基数指标；
- [x] [本地实现] 接入固定 OTel Java Agent 2.29.0，只向 Alloy 导出 Trace；增加脱敏 JSON 运维日志和 traceId/spanId，禁止采集 Prompt、回答、用户/会话标识和 Secret；
- [x] [测试] 覆盖成功、错误、取消、超时、重复订阅和高并发；验证 tag 基数有界、请求正文不进入 metric/log/span；
- [x] [云端验收] Prometheus 连续抓取成功，Loki 可按 service_name/event 查询，Tempo 可定位一次真实测试请求的 Trace。

门禁：三类信号可由同一 traceId/时间窗关联；日志与 Trace 抽样检查不含隐私数据；遥测失败不阻断聊天主链。

截至 2026-07-13，本地实现与隐私测试完成，云端私有候选 `20260713-stage5-candidate-12` 和 AIDiag release `20260713-stage5-observability-1` 已打通 Prometheus、Loki 与 Tempo。最终使用一次临时 Guest 真实 SSE Chat 验证：Prometheus 记录 success、Loki 运维事件与 Tempo Trace 可由同一 traceId 关联，抽样不含 Prompt、回答、用户/会话标识或 Secret；测试 Guest、Token、会话、消息、ES 派生文档和准入键随后完成精确清理。证据见 [`docs/verification/2026-07-13-stage5-observability.md`](docs/verification/2026-07-13-stage5-observability.md)。

#### Stage 5.2：AIDiag AI-chat 诊断规则（必要 P0 + Trace P1）

- [x] [P0 本地实现] 将硬编码 Fault Lab 规则拆为固定规则注册表，保留现有 Fault Lab 行为并增加 AI-chat Provider；
- [x] [P0 本地实现] 首批只实现 `AiChatModelTimeout`、`AiChatR2dbcPoolExhaustion`、`AiChatDocumentBacklog`、`AiChatSearchUnavailable` 四类规则；每类固定 PromQL、LogQL、阈值、Ground Truth、候选根因和受控 Runbook action；
- [x] [P0 测试] 验证未知 service/alert、缺失指标、缺失日志、非法 label、超大响应和重复告警只产生可审计的证据不足结果，不伪造主要根因；
- [x] [必要 P1] 增加 Tempo Trace evidence collector，限制单次 50 条、10 秒超时和响应大小；Trace 不可用时必须降级为 Metric/Log 报告；
- [x] [云端实施] 增加 AI-chat Prometheus scrape、告警规则、Alloy 日志源和 Grafana Dashboard，并发布 AIDiag 新 release。

门禁：现有 Stage 3 四类 Fault Lab 回归继续通过；AI-chat 告警可以自动建单并引用真实 Metric/Log evidence，Trace P1 失败时不影响 P0 报告。

截至 2026-07-13，本地实现和私有云端发布已完成：AI-chat candidate `20260713-stage5-candidate-13`、AIDiag release `20260713-stage5-diagnosis-1`、4 条 AI-chat 告警和 7 Panel Dashboard 正常运行，Prometheus 抓取为 1，4 条固定 TraceQL 均由 Tempo 接受。AIDiag 镜像构建测试 27 项通过、10 项按条件跳过；随后用独立临时数据库重跑 10 项 PostgreSQL 集成测试并全部通过，AI-chat 152 项测试通过。唯一合成告警验证了 Alertmanager 自动建单、1 Incident / 1 run、证据不足不写主要根因及 resolved 生命周期。该合成验收不代表真实诊断准确率；真实 Metric/Log 联合证据和四类故障样本必须在 Stage 5.3 隔离实验中完成。详细证据见 [`docs/verification/2026-07-13-stage5-2-local.md`](docs/verification/2026-07-13-stage5-2-local.md)。

#### Stage 5.3：隔离故障实验与端到端验收（P0）

- [x] [本地实现] 提供只在隔离 `stage5-test` profile 启用、需要独立 Secret 的故障开关和清理脚本；禁止在真实用户实例直接注入数据库、中间件或 Provider 故障；
- [x] [共同验收] 对四类 AI-chat contract 场景分别完成 firing、Incident、证据、确定性报告、恢复和 resolved；每类完成 5 次生命周期冒烟；
- [x] [共同验收] 手动触发一次 Stage 4 DeepSeek 建议，确认 advice 引用既有 evidenceId、每日配额增加一次、`automatedRemediation=false` 且不改写确定性报告；
- [x] [结果归档] 记录短时无故障基线、失败样本、告警到建单耗时、告警到报告耗时、证据完整性和模型调用计数；实际货币费用无法从当前 API 响应取得，明确保留为未测量项；
- [x] [展示门禁] AI-chat Grafana Dashboard 已交付；端到端闭环通过后可另立前端子阶段扩展现有 `frontend_admin`，不在 Stage 5 P0 内新建第三套前端。

截至 2026-07-13，隔离服务 `ai-chat-stage5-lab` 已完成 4 类场景 × 5 轮，共 20 个 Incident；20/20 自动建单、生成 `SUPPORTED` 确定性报告并恢复为 `RESOLVED`。每份报告均引用 1 条 Metric、1 条 Log 和 1 条 Trace，告警到建单为 10.006～10.160 秒，告警到报告为 40.210～44.468 秒。Stage 4 预览只增加 1 次预算计数，引用 3 条 evidence，未执行自动修复，也未改写确定性报告。动态检查 92 条实验日志未发现 Prompt、用户/会话标识、Secret 或查询正文。

该结果是**隔离遥测契约闭环验证**，不是生产准确率：实验服务没有真的耗尽 AI-chat R2DBC 连接池，也没有中断真实 Elasticsearch、RabbitMQ 或模型 Provider；同一轮的证据时间窗存在重叠，5 轮只能证明生命周期可重复。下一阶段必须采用 shadow mode 收集非重叠真实样本，统计误报、漏报、弃权和恢复时延后，才能讨论生产准确率。详细证据见 [`docs/verification/2026-07-13-stage5-3.md`](docs/verification/2026-07-13-stage5-3.md)。

#### Stage 5.4：独立故障入口与只读展示（P0 + 必要 P1）

- [x] [P0 独立入口] 部署固定版本 Blackbox Exporter，从 AI-chat JVM 外部探测应用 health 及 MySQL、Redis、RabbitMQ、Elasticsearch、Nacos TCP 可达性；探针仅加入必要私有 Docker 网络，不开放宿主机端口；
- [x] [P0 告警展示] 增加 `AiChatProcessUnavailable`、`AiChatDependencyUnreachable` 和独立 Grafana Dashboard；未知外部故障只允许证据不足或无确定性规则，不能根据端口失败伪造内部根因；
- [x] [P0 只读边界] Incident 列表、详情和 shadow summary 增加 Basic Auth、审计、精确筛选和分页上限；只读 Secret 与 Stage 4 Operator Secret 分离并以文件挂载；
- [x] [P1 辅助页面] 在既有 `frontend_admin` 增加智能运维页面，由 ADMIN BFF 访问 AIDiag；配置错误采用 fail-open integration，即只关闭运维集成、不阻断 AI-chat 主应用启动；
- [x] [发布收紧] Compose 使用不可变 release 的真实路径挂载配置；激活失败回滚时从旧 release 重建共享标签镜像，避免“软链接已回滚、镜像未回滚”；
- [ ] [P1 启动证据] 安全采集 Docker stdout/stderr，覆盖 JVM 在文件 logger 初始化前失败的日志；不得直接把未受控 Docker socket 暴露给业务容器；
- [ ] [P1 主机外监控] 增加位于 GCP 主机之外的 HTTPS/主机可用性检查，覆盖主机、Docker daemon、同机监控平面整体失联。

门禁：AI-chat 停止时，Grafana/Prometheus/Blackbox 仍存活且应用探针变为 0；任一中间件网络不可达时仅对应 component 变为 0；未认证只读 API 返回 401，越界分页返回 400；Admin 集成失败不得影响聊天主链。当前独立入口云端验收见 [`docs/verification/2026-07-13-stage5-4.md`](docs/verification/2026-07-13-stage5-4.md)。

#### Stage 5.5：V1 发布收紧（P0）

- [x] [本地兼容性] AI-chat 统一 Java 21 编译/构建/运行时，升级到 Boot 4.0.7、Cloud 2025.1.2、Alibaba 2025.1.0.0、Nacos Client 3.1.1 和 LangChain4j 1.15.1；完成 166 项回归和可执行 Jar 构建；
- [x] [本地安全] 删除未来可能误开放的匿名 `/api/test/**` seam，扩大运行日志隐私测试，禁止认证详情、用户输入、异常原文、RabbitMQ nack reason 和知识库 ID 列表进入日志；
- [x] [发布结构] 候选与生产共用受强校验的 Compose 模板，候选强制关闭 Flyway/listener/scheduler 等写任务，生产强制开启并需要维护窗口确认；Nacos 凭据使用文件型 Secret；
- [x] [Nacos 本地实现] 锁定 Nacos 3.2.2 镜像 digest，完成资源/冲突门禁、完整备份、官方干净 schema、白名单迁移、独立鉴权、最小权限 RBAC、Secret 隔离、验证与保留数据的停止/恢复脚本；
- [x] [Nacos 云端基础验收] 在独立端口、数据库、volumes 和 Compose Project 创建 Nacos 3.2.2 候选；配置/历史行数稳定为 `19/10`，9 项必需配置非空，匿名读取 403，候选用户仅具配置读与 naming 读写权限；源 Nacos 2.1.1 保持运行且 restart count 为 0；证据见 [`docs/verification/2026-07-13-nacos3-candidate-cloud.md`](docs/verification/2026-07-13-nacos3-candidate-cloud.md)；
- [x] [Nacos 应用验收] AI-chat 使用 mode-specific `spring.application.name` 注册为 `ai-chat-candidate`，恰有 1 个健康实例；受控停止隔离 Nacos 时应用依靠本地配置缓存保持健康且零重启，Nacos 恢复后自动重新注册；客户端 Secret 未进入日志。现有 Nacos 2.1.1 未停止或替换；
- [x] [Elasticsearch 兼容门禁] 创建独立 Elasticsearch 9.3.2 + IK 9.3.2 候选，锁定官方 amd64 镜像 digest 与 IK ZIP SHA-256；候选使用独立 config/data volumes、鉴权与索引级最小权限，端口仅绑定 loopback/私网。IK 中文 Mapping/检索、业务索引、dense_vector KNN 与权限负向测试全部通过，现有 Elasticsearch 8.13.4 保持运行且零重启；
- [x] [候选门禁] AI-chat release `20260713-v1-nacos3-es9-ik-candidate-r4` 通过 166 项镜像内测试、健康/资源/只读根文件系统、Nacos 注册、真实 Guest SSE、MySQL 持久化、ES9 写入/删除、IK Mapping、Prometheus/Loki/Tempo 关联和精确清理门禁；Nginx/DNS 与生产写任务保持不变；
- [ ] [可观测门禁] 启动早期日志已通过受控文件采集到 Loki；仍需完成主机外 HTTPS/主机可用性检查。Grafana 继续保持 loopback 或经 TLS/鉴权反向代理访问；
- [ ] [Secret 门禁] 轮换曾经进入聊天记录或 Git 历史的 DeepSeek、Nacos、JWT、数据库、中间件和 OSS 凭据，并逐项验证旧实例回滚策略；
- [ ] [生产切换] 在维护窗口停止旧实例写任务，执行生产 Flyway/启动/业务验收，预置 Nginx/TLS 后再切换 DNS；失败时按记录恢复旧 upstream/A 记录；
- [ ] [发布归档] 归档 SBOM、镜像 digest、测试/Compose/健康/业务/遥测证据、已知限制和回滚演练结果，创建 V1 tag 后才对外声明第一版发布。

截至 2026-07-13，V1 的业务闭环、Nacos 3.2.2 应用级候选、Elasticsearch 9.3.2 + IK 候选和新版 AI-chat 私有候选均已通过。当前剩余发布阻塞是历史 Secret 逐项轮换、主机外监控、生产 Nginx/TLS/维护窗口切换、最终回滚演练与发布归档；在这些门禁完成前可以展示私有候选和 Grafana，但不能把新版 AI-chat 标记为正式生产发布。详细证据见 [`docs/verification/2026-07-13-elasticsearch9-ik-candidate-cloud.md`](docs/verification/2026-07-13-elasticsearch9-ik-candidate-cloud.md)。

总验收：AI-chat 的真实告警可生成引用其真实遥测证据的确定性诊断报告；数据采集不包含 Prompt、回答、用户/会话标识或 Secret；模型失败不影响规则报告；AIDiag 故障不阻断 AI-chat 主业务；部署和回滚均不要求手工上传 Jar。

---

## 13. 开工清单

- [x] 确定模型 Provider 和模型名：DeepSeek 官方 API / `deepseek-v4-flash`；
- [x] 确定模型数据出境范围、30 天保留期、每日 20 次/单次 1,200 tokens 费用边界；
- [x] 使用受限测试账号完成真实 DeepSeek API 契约验收；
- [x] 创建模块化单体骨架；
- [x] 建立 Flyway 初始迁移及状态约束；
- [x] 创建 versions.env、镜像 digest 锁定和 Compose 基础设施；
- [x] 获取云端部署 SSH 用户名、密钥登录方式、可用磁盘容量和防火墙规则；
- [x] 定义遥测、日志、告警 label 与 AI-chat 接入契约；
- [x] 建立合成遥测端到端测试；
- [x] 完成阶段 0 验收；可进入最小 Fault Lab 的业务服务实现。
- [x] 完成 AI-chat 独立 Docker 构建、私有 telemetry 网络和回滚基线；
- [x] 完成 AI-chat 遥测隐私审查后再允许 Loki/Tempo 采集；
- [x] 完成四类 AI-chat 规则、隔离故障验收和 Stage 5.4 独立故障入口；Admin 运维页只作为辅助入口，不与 AI-chat 共用救援职责。
- [ ] 完成 Stage 5.5 剩余的 Secret 轮换、主机外监控、生产切换与发布归档门禁后发布 V1；Elasticsearch 9 + IK、Nacos 应用验收和新版私有候选已完成，生产步骤已固化到 [`docs/release/V1-production-cutover-runbook.md`](docs/release/V1-production-cutover-runbook.md)。

---

## 14. 后续升级边界

只有在 V1 指标证明存在具体瓶颈或能力缺口时，才考虑 Kafka 事件化、Kubernetes 只读诊断、多 Agent、变更平台接入、知识图谱、人工审批的半自动修复、多租户和 RBAC。每次升级必须说明解决的具体问题、迁移策略和可验证收益。

---

## 15. 版本来源与核验

版本基线按 2026-07-11 的官方发布信息确定。实施时应再次核验 patch 和镜像 digest：

- [Spring Boot 系统要求](https://docs.spring.io/spring-boot/system-requirements.html) 与 [Spring Boot 4.1.0](https://github.com/spring-projects/spring-boot/releases/tag/v4.1.0)；
- [Spring Cloud 版本兼容矩阵](https://spring.io/projects/spring-cloud/)、[Spring Cloud Alibaba 2025.1.0.0](https://github.com/alibaba/spring-cloud-alibaba/releases/tag/2025.1.0.0) 与 [LangChain4j 1.15.1](https://github.com/langchain4j/langchain4j/releases/tag/1.15.1)；
- [Nacos 3.2.2](https://github.com/alibaba/nacos/releases/tag/3.2.2)、[Nacos 3.2 发布历史](https://nacos.io/en/download/release-history/) 与 [Nacos 3 升级说明](https://nacos.io/en/docs/latest/manual/admin/upgrading/)；
- [Spring AI 2.0 与 Boot 兼容说明](https://docs.spring.io/spring-ai/reference/getting-started.html)；
- [Promtail EOL 与 Alloy 迁移说明](https://grafana.com/docs/loki/latest/send-data/promtail/)；
- [OpenTelemetry Java Agent 2.29.0](https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/tag/v2.29.0)；
- [PostgreSQL 18.4](https://www.postgresql.org/docs/release/18.4/) 与 [pgvector 0.8.5 变更记录](https://github.com/pgvector/pgvector/blob/master/CHANGELOG.md)；
- [Prometheus 3.13.1](https://github.com/prometheus/prometheus/releases/tag/v3.13.1)、[Alertmanager 0.33.1](https://github.com/prometheus/alertmanager/releases/tag/v0.33.1)；
- [Blackbox Exporter 0.28.0](https://github.com/prometheus/blackbox_exporter/releases/tag/v0.28.0) 与 [Prometheus multi-target exporter 模式](https://prometheus.io/docs/guides/multi-target-exporter/)；
- [Grafana 13.1.0](https://github.com/grafana/grafana/releases/tag/v13.1.0)、[Loki 3.7.3](https://github.com/grafana/loki/releases/tag/v3.7.3)、[Tempo 3.0.2](https://github.com/grafana/tempo/releases/tag/v3.0.2)、[Alloy 1.17.1](https://github.com/grafana/alloy/releases/tag/v1.17.1)。
