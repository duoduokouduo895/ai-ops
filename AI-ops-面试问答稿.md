# AI-ops / AIDiag 项目面试问答稿

2026-10-03

## 使用说明

共 40 题，沿用 `AI-chat-面试问答稿.md` 的分级和回答深度：S 级 12 题必须能脱稿讲，A 级 16 题要能讲出要点，B 级 12 题能完整回答、不冷场。内容围绕告警幂等接收、数据库任务调度、证据驱动诊断、AI 受限建议和只读控制台展开。

| 级别 | 题数 | 要求 | 建议投入 |
| --- | --- | --- | --- |
| S 必问 | 12 | 脱稿讲，能接住第 2、3 层追问 | 第 1 周全部时间 |
| A 高频追问 | 16 | 说出核心点和一个具体细节 | 第 2 周 |
| B 加分/压力 | 12 | 有一两句完整回答，不冷场 | 面试前一晚过一遍 |

**答题模板（每题都按这个顺序说）**：结论一句话 → 怎么做的 → 为什么这样选 → 踩过的坑或不足。先回答当前问题，再等面试官追问。

**怎么背**：先对着代码看一遍，再合上文档用自己的话复述。SQL、状态机和证据判定至少各挑一个例子，能讲清输入、处理和结果。

**依据与边界**：本文核对的是 AI-ops 当前 `main` 工作区，基准提交为 `b2241b7`，包含尚未提交的 Access 改造。本文编写时未运行应用测试、未连接服务器；测试用例存在、历史记录通过、本次运行通过是三种不同证据。旧资料里的 `Stage3DiagnosisWorker`、`Stage4AiDiagnosisService` 等类已改名，本文使用现有路径。

## S 级：必问题（12 题）

这 12 题覆盖项目主链。AI-ops 是仓库名，AIDiag 是其中的诊断应用；AI-chat 是主要被监控对象。下文 Java 代码路径省略公共前缀 `src/main/java/com/dduo/aiops/`；前端、测试和配置路径从仓库根目录开始。

### S1 用一分钟介绍一下这个项目

**参考回答**：

这是一个基于 Java 21、Spring Boot 和 PostgreSQL 的可观测与故障诊断平台，主要用于监控我的 AI-chat 服务。

它解决的问题是：服务出现模型超时、数据库连接池排队、文档积压或检索失败时，只有一条告警还不够，还需要把指标、日志和调用链整理成能复核的报告。

主要链路有四部分：

一是接收 Alertmanager 告警，通过数据库唯一约束做幂等，把相关告警聚合成 Incident；

二是把诊断任务持久化到 PostgreSQL，用行锁、租约和 token 防止多个 Worker 提交冲突结果，并支持重启恢复；

三是查询 Prometheus、Loki 和 Tempo，用固定规则判断证据是否足够，再生成确定性报告；

四是通过 Spring AI 接入 DeepSeek，人工触发受限建议，配合 Vue 3 只读控制台查看事件和证据。AI 不覆盖规则结论，也不执行修复。

### S2 从 AI-chat 出故障到控制台看到报告，完整链路是什么？

**参考回答**：

1. AI-chat 暴露指标、输出运维日志并上报 Trace。Prometheus 抓指标，Alloy 把日志送到 Loki、把 Trace 送到 Tempo。
2. Prometheus 的规则满足条件后形成告警，Alertmanager 负责分组和投递，通过 Webhook 通知 AIDiag。
3. AIDiag 在一个事务里保存告警事件、关联 Incident，并创建初始诊断任务，接口返回 `202 Accepted`。
4. 后台 Worker 领取任务，再根据服务和告警名匹配固定规则，查询遥测、保存证据和生成报告。
5. 控制台通过只读 API 展示结论、候选原因、证据和时间线。AI 预览属于单独的人工触发分支。

**为什么这样设计**：告警接收不等待遥测查询和模型调用；诊断服务故障也不会阻塞 AI-chat 的聊天请求。

**代码**：`alerts/AlertmanagerWebhookController.java`、`work/DiagnosisRunWorker.java`、`diagnosis/DiagnosisWorker.java`；采集配置见 `infra/prometheus/prometheus.yml`、`infra/alloy/config.alloy`

### S3 AlertEvent、Incident、DiagnosisRun、Report 有什么区别？

**参考回答**：这四个对象分别回答四个问题。

- **AlertEvent**：收到过什么告警消息，保存状态、时间、脱敏内容和重复接收次数。
- **Incident**：现在正在跟踪哪一次故障，把相关告警放在一起，记录影响服务和恢复状态。
- **DiagnosisRun**：为这个故障执行过哪一次诊断，记录任务状态、租约、尝试次数和失败信息。
- **Report**：这次诊断得出了什么结论，有哪些证据、限制和建议。

一个 Incident 可以关联多条告警，也可以因为重试产生多个 Run。诊断完成不代表故障恢复：Run 可以是 `COMPLETED`，Incident 仍然没有 `RESOLVED`。

**代码**：`src/main/resources/db/migration/V1__initialize_aidiag_schema.sql`、`diagnosis/DiagnosisRepository.java`

### S4 Alertmanager 会重复投递，怎么保证幂等？

**参考回答**：幂等最终由 PostgreSQL 唯一约束保证，唯一键是 `receiver + group_key + fingerprint + alert_status + starts_at`。

保存时执行 `INSERT ... ON CONFLICT DO NOTHING RETURNING id`。插入成功才走新事件处理；冲突时只增加 `receive_count`、更新 `last_received_at`，不会再次创建初始诊断任务。并发请求也必须经过同一个约束，比先在 Java 里查一遍再插入可靠。

**追问准备**：`fingerprint` 标识告警身份，`status` 区分 firing 和 resolved，`starts_at` 区分不同发生周期。`payload_hash` 是报文摘要，不是幂等键。这里保证的是重复投递的幂等处理，不是整个链路只执行一次。

**代码**：`alerts/AlertmanagerWebhookService.java` 的 `insertEvent`、`incrementDuplicate`；唯一约束见 `V1__initialize_aidiag_schema.sql`

### S5 相关告警怎么聚合？怎么避免生成两个活动 Incident？

**参考回答**：根据 `environment + receiver + groupKey` 计算 SHA-256，作为 `correlation_key`。同一关联键下的 firing 告警归到同一个活动 Incident。

数据库用部分唯一索引，只对 `OPEN / DIAGNOSING / DIAGNOSED / FAILED` 这些活动状态限制唯一。写入使用对应的 `ON CONFLICT`，已有 Incident 就更新最近告警时间和严重程度。恢复后的 Incident 不再占用活动唯一键，下一次故障可以建立新记录。

告警事件、Incident、关联记录和初始 Run 在同一个事务里提交，避免只保存了一半。Run 还有 `(incident_id, attempt)` 唯一约束，防止重复创建第 1 次诊断。

**注意**：这主要依赖 Alertmanager 的分组键，不是自动发现任意跨服务故障的因果关系；`DIAGNOSED`、`FAILED` 也不等于已经恢复。

**代码**：`alerts/AlertmanagerWebhookService.java` 的 `upsertActiveIncident`、`ensureInitialRun`；`src/main/resources/db/migration/V2__harden_incident_ingestion_and_leases.sql`

### S6 为什么用 PostgreSQL 做任务调度，不直接用 Redis 或 RabbitMQ？

**参考回答**：这个项目已经用 PostgreSQL 保存 Incident 和诊断记录，任务量也不是海量。直接把任务表作为持久队列，可以在接收告警的同一个事务里创建任务，少处理一次数据库和外部队列之间的一致性问题，也少维护一个组件。

任务状态、领取者、租约、重试时间和失败原因都在表里，重启后重新扫描就能恢复。核心持久化用 `JdbcTemplate` 写明确的 SQL，方便直接控制行锁和条件更新。

**诚实补充**：这不是通用消息中间件的替代品。任务量变大后要测数据库轮询和锁竞争；需要广播、复杂路由或很高吞吐时，再评估专用队列。

**代码**：`work/DiagnosisRunRepository.java`、`src/main/resources/db/migration/V1__initialize_aidiag_schema.sql`

### S7 多个 Worker 同时取任务，怎么避免抢到同一条？

**参考回答**：领取时用一个短事务，先通过 `FOR UPDATE SKIP LOCKED` 找到一条可执行任务，再通过 `UPDATE ... RETURNING` 写入领取状态、owner、token 和租约时间。

`FOR UPDATE` 锁住被选中的行，`SKIP LOCKED` 让其他 Worker 跳过已经被锁住的任务，继续找下一条。领取完成就提交事务，遥测 HTTP 查询在事务外执行，不会拿着行锁等网络。

**追问准备**：`SKIP LOCKED` 解决的是领取时的竞争；任务执行过程中防止旧 Worker 写回结果，还需要租约和 token，不能只靠这把行锁。

**代码**：`work/DiagnosisRunRepository.java`、`diagnosis/DiagnosisRepository.java` 的 `claimNext`

### S8 租约和 fencing token 是什么？为什么两者都要有？

**参考回答**：租约表示“这段时间任务由我处理”，默认 30 秒；续租把到期时间往后延。Worker 崩溃后不再续租，其他 Worker 就能领取过期任务。

但旧 Worker 可能只是暂停，之后又恢复，所以每次领取还会生成新的 `lease_token`。心跳、状态迁移和报告提交都检查 owner、token、租约仍未过期。旧 Worker 拿着旧 token 回来，就不能把迟到结果写进数据库。

**追问准备**：当前 token 是 UUID，用相等比较隔离旧领取者；`lease_generation` 会递增，但不是直接拿来比较的 fencing 条件。租约也不能保证遥测查询绝不重复，只能阻止失效 Worker 提交结果。

**代码**：`work/DiagnosisRunRepository.java` 的 `heartbeat`、`recordStageTwoHandoff`；`diagnosis/DiagnosisRepository.java` 的 `finalizeReport`

### S9 诊断任务的状态机是什么？EVIDENCE_READY 表示已经取证完成吗？

**参考回答**：当前成功主链是：

```text
PENDING → LEASED → COLLECTING → EVIDENCE_READY
        → ANALYZING → VERIFYING → COMPLETED
```

前半段由 `DiagnosisRunWorker` 负责持久任务领取和交接，后半段由 `DiagnosisWorker` 负责真正的遥测查询、规则分析和报告落库。

`EVIDENCE_READY` 是沿用的阶段交接名称，此时没有完成 Prometheus、Loki、Tempo 取证。交接会释放前一阶段租约，再由后一阶段重新领取；默认还留出 30 秒交接延迟。失败则记录 `FAILED`，按条件创建 `RETRY_WAIT` 的新 Run。

**注意**：表里还允许 `GENERATING_REPORT` 状态，但当前提交实现从 `VERIFYING` 直接进入 `COMPLETED`，不要把枚举里的每个状态都说成必经步骤。Stage 3 需要配置启用。

**代码**：`work/DiagnosisRunWorker.java`、`diagnosis/DiagnosisWorker.java`、`src/main/resources/application.yml`

### S10 规则诊断具体怎么做？以模型超时为例讲一下

**参考回答**：先按 `(serviceName, alertName)` 精确匹配服务端规则。比如 `AiChatModelTimeout`，会查三类证据：

1. Prometheus 查询近 5 分钟的 timeout 请求增量，规则阈值是至少 1 次。
2. Loki 查询 `event=ai_chat_stream_finished`、`result=timeout` 的日志，有匹配记录才算日志证据成立。
3. Tempo 查询对应服务耗时超过 10 秒的 Trace，作为补充证据。

查询围绕 Incident 的时间窗口执行。Metric 和 Log 都成立才判为 `SUPPORTED`；只有部分证据则降为弱支持或证据不足。没有匹配规则时直接生成证据不足的报告，不让模型临时编查询。

**诚实补充**：这能支持“模型流触发了超时边界”这个候选，不能直接证明一定是 Provider 宕机；网络、超时预算等还需要进一步排查。

**代码**：`diagnosis/AiChatDiagnosisRuleProvider.java`、`diagnosis/DiagnosisRuleRegistry.java`、`diagnosis/RuleBasedDiagnosisService.java`

### S11 证据不足时怎么办？怎么防止把猜测展示成主要根因？

**参考回答**：证据不足也会生成报告，但保留候选和限制，`primary_root_cause_id` 为空。当前规则要求 Metric 和 Log 同时成立，候选才能标记 `SUPPORTED` 并作为主要根因。

保护有三层：业务代码只提升满足条件的候选；数据库触发器检查主要根因必须属于同一个 Run 且为 `SUPPORTED`；控制台投影再次检查，未证实候选不会作为 `rootCause` 返回。

**为什么这样设计**：生成报告代表这次分析结束，不代表已经找到原因。把“缺证据”明确展示出来，比给出一个看似肯定的结论更适合运维决策。

**代码**：`diagnosis/DiagnosisRepository.java`、`console/api/ReportProjection.java`；`src/main/resources/db/migration/V6__enforce_supported_primary_root_cause.sql`

### S12 Spring AI 和 DeepSeek 在项目里做什么？是不是 AI 自动诊断和修复？

**参考回答**：AI 负责对已有确定性报告和证据摘要生成辅助解释，不参与后台规则诊断的主流程。

调用入口是人工触发的 `POST /api/v1/incidents/{incidentId}/ai-diagnosis-preview`，需要独立的操作员鉴权，并且先有规则报告。服务通过 Spring AI 的 `ChatClient` 调用 OpenAI 兼容的 DeepSeek 接口，结果映射成结构化对象，再做本地校验。

每次尝试单独写入 `ai_diagnosis_attempt`，不会改写 `diagnosis_report`，也没有执行修复命令的路径。Stage 4 默认关闭，需要配置模型、凭据和开关；浏览器只读控制台不提供这个 POST 操作。

**代码**：`advisory/AiDiagnosisPreviewController.java`、`advisory/AiAdvisoryService.java`、`advisory/SpringAiAdvisoryModelClient.java`

## A 级：高频追问（16 题）

这些题用来接住“为什么”和“出了异常怎么办”。参数均是当前代码或配置默认值，实际运行环境可以覆盖。

### A1 为什么既检查 owner，又检查 token 和 lease_until？

**参考回答**：owner 说明由谁领取，token 区分这个 Worker 的哪一次领取，`lease_until` 说明这次领取现在是否还有效。只检查 owner 不够，同一个进程重新领取或标识重复时，旧执行也可能混进来。

写入条件类似 `id = ? AND lease_owner = ? AND lease_token = ? AND lease_until > CURRENT_TIMESTAMP`，还会检查当前状态。影响 0 行就视为失去租约，停止写入。时间判断用数据库时间，避免各个实例本机时钟不一致直接影响所有权判断。

**代码**：`work/DiagnosisRunRepository.java`、`diagnosis/DiagnosisRepository.java`

### A2 Worker 执行到一半宕机了，重启后怎么恢复？

**参考回答**：任务在数据库里，进程退出不会丢。前一阶段会重领租约过期的 `LEASED / COLLECTING`，后一阶段会重领过期的分析状态，生成新 token，再从对应阶段重新执行。

这不是恢复 Java 方法上次执行到的那一行，可能重复一部分遥测查询。结果落库时会重新检查租约，证据、候选、报告和 Run 完成状态在一个事务里提交，避免留下半份报告。

Stage 3 在各次遥测查询前以及报告提交前续租。如果一次查询结束后已经失去租约，就停止后续处理。

**代码**：`work/DiagnosisRunRepository.java` 的 `claimNext`、`diagnosis/RuleBasedDiagnosisService.java` 的 `requireCurrentLease`、`diagnosis/DiagnosisRepository.java` 的 `finalizeReport`

### A3 重试怎么设计？为什么不一直重试？

**参考回答**：发生任务执行异常时，先把当前 Run 标记为 `FAILED`，保留失败码和原因；没到次数上限就创建新的 `AUTOMATIC_RETRY` Run，用 `previous_run_id` 关联上一次，状态为 `RETRY_WAIT`。

默认总尝试次数最多 3 次，固定等待 5 秒后再执行，不是指数退避。到上限后停止这条自动重试路径，避免依赖持续故障时无限消耗资源，也保留每次失败的审计记录。

**注意**：租约过期后的重新领取不等于创建新 attempt；遥测查询失败也常常是生成有局限的报告，不一定触发整个任务重试。

**代码**：`work/DiagnosisRunRepository.java` 的 `scheduleRetry`、`work/DiagnosisRunWorker.java`、`diagnosis/DiagnosisWorker.java`

### A4 resolved 怎么处理？旧恢复消息会不会关闭新故障？

**参考回答**：resolved 先作为独立告警事件保存，并关联当前活动 Incident。只有整个告警组为 resolved 时，才尝试关闭对应关联键下的活动 Incident；混合组里某一条恢复，不代表整组恢复。

关闭还会比较恢复时间和 `last_alert_at`，只允许恢复时间不早于最近告警时间的消息关闭它。这样较早的 resolved 迟到时，不会把后来的 firing 故障直接关掉。不同 environment 的关联键分别处理。

**追问准备**：`RESOLVED` 说明告警状态恢复，不证明此前报告中的候选就是真正根因，也不是 AI 执行了修复。

**代码**：`alerts/AlertmanagerWebhookService.java` 的 `isResolvedGroup`；测试见 `src/test/java/com/dduo/aiops/work/DiagnosisPipelinePostgresIntegrationTests.java`

### A5 如果恢复通知丢了，Incident 一直没关闭怎么办？

**参考回答**：有一个配置启用的对账 Worker，定期查询 Alertmanager 当前活动告警，再和数据库里的活动 Incident 比较。

它不会看到一次“没有告警”就关闭事件。默认要求连续 3 次有效观察都没有，并且距离最近告警至少超过 10 分钟，才允许恢复状态；Alertmanager 不可达、返回错误或报文无效时，不把它当成告警已经消失。比较身份也包含 environment，避免不同环境串在一起。

对账还可以把存在于 Alertmanager、但数据库没有对应活动事件的 firing 重新送入原有幂等接收流程。`enabled` 和 `apply-transitions` 默认都为 false，观察模式只记录对账情况，不修改故障和任务状态。

**代码**：`alerts/AlertStateReconciliationWorker.java`；配置见 `src/main/resources/application.yml`

### A6 Metric、Log、Trace 各自解决什么问题？Prometheus 和 Grafana 有什么区别？

**参考回答**：Metric 看“异常有多少、持续多久”，比如 timeout 次数、连接池排队数；Log 看“发生了哪类事件”，比如流超时或投递重试；Trace 看“请求在哪段调用耗时或失败”，用于补充定位。

Prometheus 负责指标采集、查询和告警规则计算；Grafana 负责把数据展示成图表；Alertmanager 负责告警分组、通知和恢复通知。Loki 存日志，Tempo 存 Trace，Alloy 负责相应采集和转发。

**追问准备**：当前规则把 Metric 和 Log 作为基线，Trace 是补充证据。Tempo 查询失败时仍然可以根据前两类证据出报告，并写明 Trace 的缺失。

**代码**：`diagnosis/RuleBasedDiagnosisService.java`；配置见 `infra/prometheus/`、`infra/alertmanager/`、`infra/alloy/`

### A7 遥测查询会不会被告警内容注入，或者一次拉太多数据？

**参考回答**：诊断查询是服务端固定白名单，告警只用于匹配服务和规则，不提供任意 PromQL、LogQL、TraceQL 或目标 URL。label、annotation 也不直接拼成查询片段。

诊断采集器还有边界：单次响应最多 128 KiB，默认查询超时 8 秒，Prometheus step 为 30 秒，Loki 最多 200 条，Tempo 最多 50 条摘要。超过边界就记录采集失败或证据缺口，不继续扩大读取。

**注意**：这些是诊断取证的限制；控制台信号查询使用另一个有界客户端，限制不同，不能把一个数字说成所有查询的统一配置。

**代码**：`diagnosis/TelemetryEvidenceCollector.java`、`diagnosis/AiChatDiagnosisRuleProvider.java`、`telemetry/PrometheusClient.java`

### A8 Evidence 保存了什么？是不是把所有原始日志和 Trace 都复制一份？

**参考回答**：保存的是证据类型、来源、时间范围、查询条件、摘要、内容哈希以及对应的 Run 和调用审计，方便回看这份报告是根据什么生成的。

`raw_data` 这个名字容易误解：Metric 保存统计值和最多 120 点的曲线包络，Log 保存匹配条数和受控事件标记，Trace 保存有界摘要，不是完整遥测档案。控制台还做安全投影，不把任意数据库字段直接返回浏览器。

**追问准备**：另一个字段 `alert_event.raw_payload` 当前确实保存了脱敏后的 Webhook JSON，并保留原始报文字节的 SHA-256；不要说“所有原始报文都只存哈希”。脱敏也是风险控制，不能保证任何未知敏感格式都能识别。

**代码**：`diagnosis/TelemetryEvidenceCollector.java`、`diagnosis/DiagnosisRepository.java`、`alerts/AlertmanagerWebhookService.java`、`console/api/ConsoleIncidentQueryService.java`

### A9 support_score 是置信度吗？75 分是不是 75% 准确？

**参考回答**：不是概率，是规则支持分。Metric 命中加 30，Log 命中加 25，Trace 命中加 20，没有用统计样本校准成准确率。

是否 `SUPPORTED` 看的是 Metric 和 Log 是否同时成立，不是简单看总分。两者成立、Trace 缺失时是 55 分，仍可支持；只有 Trace 是 20 分，但只能算弱支持。

**诚实补充**：`SUPPORTED` 说明满足了这条规则要求的证据，不代表所有其他原因都排除了，更不能说生产诊断准确率是 75%。

**代码**：`diagnosis/RuleBasedDiagnosisService.java`

### A10 PostgreSQL 在这个项目里有哪些用法？为什么不全部用 JPA？

**参考回答**：结构化业务字段用普通列，告警、证据和报告中的可变结构用 JSONB；事件时间用 `TIMESTAMPTZ`；幂等靠唯一约束，活动事件聚合靠部分唯一索引，任务领取靠行锁和条件更新。表结构由 Flyway 的版本化迁移管理。

主链选择 `JdbcTemplate`，是因为抢占任务、带租约提交和 `ON CONFLICT` 这些操作需要明确的 SQL 语义，直接写更容易审查。POM 虽然引入了 JPA，但不能因此说业务主链都是 Entity 加 JPA Repository。

**注意**：pgvector 和 Runbook 向量表属于预留，当前诊断主链没有实现“向量检索 Runbook 再由 Agent 自主排障”。

**代码**：`work/DiagnosisRunRepository.java`、`diagnosis/DiagnosisRepository.java`、`src/main/resources/db/migration/`、`pom.xml`

### A11 发给模型的数据怎么脱敏？Prompt 包含什么？

**参考回答**：发给模型的是一个受控 DTO：服务、告警名、严重程度、规则候选、验证状态、支持分，以及已有证据的 ID、类型、来源和摘要。不会直接传整份日志、Trace、告警 annotations 或 Secret。

摘要会处理 Bearer、常见密钥赋值、API Key、邮箱、手机号等格式，再限制文本长度。默认最多带 12 条证据，发送的业务上下文 JSON 最多 16,000 字符。

**诚实补充**：这是“字段选择 + 脱敏 + 长度限制”，不是任意数据都安全的证明；16,000 字符也不是模型的完整上下文 token 上限，系统提示词另行加入。

**代码**：`advisory/AiDiagnosisPrompt.java`、`advisory/AiAdvisoryService.java` 的 `prompt`、`sanitizedEvidenceSummary`

### A12 怎么限制 AI 幻觉和越权建议？

**参考回答**：先让模型只返回固定 JSON 结构，再校验字段长度、假设数量、敏感内容。输出引用的证据 ID 必须在本次提供的白名单里，推荐动作也必须逐项匹配服务端允许的 Runbook 动作，不能临时生成一个执行命令。

模型结果只进入建议记录，不能改变主要根因，也不接入自动执行路径。提示词还明确把输入字段当作数据，不能当作指令。

**注意**：当前校验允许证据 ID 列表为空，也不判断每个假设在语义上是否真的被证据支持。所以准确表述是限制结构、引用和动作范围，不能说消除了幻觉或保证推理正确。

**代码**：`advisory/AiDiagnosisAdviceValidator.java`、`advisory/SpringAiAdvisoryModelClient.java`

### A13 模型超时、输出不合规或成本超预算怎么办？

**参考回答**：默认模型调用超时 12 秒、输出最多 1,200 tokens，每个 UTC 日最多预占 20 次调用。每日次数在 PostgreSQL 用带条件的 UPSERT 原子预占，多个实例不能靠各自内存计数绕过。

模型不可用、超时或调用失败记为 `FALLBACK`；能解析但违反本地校验的输出记为 `REJECTED`；有效建议记为 `COMPLETED`。每种情况都有独立审计，规则报告继续可读。模型客户端关闭了自动重试，避免一次操作意外放大调用次数。

**追问准备**：次数在调用前占用，失败调用也会消耗名额。当前是调用次数和单次输出上限，不是按真实账单金额控制的每日费用预算。

**代码**：`advisory/AiAdvisoryService.java`、`advisory/AiAdvisoryRepository.java` 的 `reserveDailyRequest`、`advisory/SpringAiAdvisoryModelClient.java`

### A14 控制台展示什么？为什么做成只读？

**参考回答**：Vue 3 加 TypeScript 的控制台主要有四个页面：Inbox 看未恢复事件和处理链状态，AI-chat 看黄金信号，Casefile 看报告、证据和时间线，Services 看服务与依赖探针。

只读是为了让查看故障和执行处置保持不同的权限边界。浏览器使用专门的投影 API，只拿必要字段；主要根因必须是同一 Run 的 `SUPPORTED` 候选，其他候选明确显示为未证实。

事件列表按 `(last_alert_at, id)` 做游标分页，默认每页 20 条，上限 100 条。Metric 证据有曲线，日志和 Trace 展示受控摘要，避免把内部完整报文直接送到浏览器。

**代码**：`console/api/ConsoleApiController.java`、`console/api/ConsoleIncidentQueryService.java`、`console/api/ReportProjection.java`；页面见 `frontend-console/src/views/`

### A15 控制台如何鉴权？为什么不能只信 Cloudflare 的邮箱头？

**参考回答**：当前 Access 模式让用户通过 Cloudflare 账号登录，前端经同域 Pages Function 请求 API。代理只转发固定读取路由到固定 origin，再由 Java 后端验证 `Cf-Access-Jwt-Assertion`。

后端从配置的团队 JWKS 获取公钥，验证 RS256 签名、issuer、audience、有效时间和必要身份声明。普通邮箱头可以被伪造，不能作为身份凭据；旧 Session Cookie 也不会在 Access 模式里代替 JWT。

**追问准备**：边缘登录保护和源站 JWT 校验各有作用。Session 模式仍保留用于回滚；Webhook 和 AI 预览分别使用自己的 Basic Auth，不共用浏览器登录凭据。

**代码**：`console/security/ConsoleAccessAuthenticationFilter.java`、`console/security/ConsoleAccessTokenValidator.java`、`console/security/ConsoleSecurityConfiguration.java`、`frontend-console/functions/lib/consoleProxy.ts`

### A16 黄金信号和图表怎么实现？怎么避免控制台拖慢 Prometheus？

**参考回答**：后端按服务目录匹配固定信号配置，包含外部可用性、请求结果、首 Token P95、模型超时、活跃 SSE 流、连接池排队、Outbox 积压和检索失败。浏览器只选择服务和 `1h / 6h / 24h`，不能提交任意 PromQL。

窗口和 step 成对固定，每条时序最多 121 点；每个信号最多展示 6 条序列，同服务同窗口缓存 15 秒。查询客户端还有 4 秒超时、512 KiB 响应和 50 条序列上限。图表用 SVG 实现，并提供逐点读数和表格视图。

**追问准备**：图上的阈值与告警规则通过契约测试核对；缺数据、查询失败、真实值为 0 分别表示。Services 页也检查样本是否新鲜，缺样或过期显示 `UNKNOWN`，不能把“没有采到”显示为“正常”。

**代码**：`monitoring/ServiceSignalsService.java`、`monitoring/SignalRange.java`、`monitoring/SignalProfiles.java`、`catalog/CatalogHealthService.java`、`frontend-console/src/components/TimeSeriesChart.vue`；阈值测试见 `src/test/java/com/dduo/aiops/monitoring/MonitoringSignalContractTests.java`

## B 级：加分题和压力题（12 题）

B 级题重点是思考过程和真实经历。下面的测试、缺陷和发布边界能在仓库找到依据；个人参与程度按你的实际经历调整，没有测过的数据不要补一个数字。

### B1 这个项目里你认为最有难度的是什么？

**推荐回答**：选“诊断任务在异常情况下的所有权和结果一致性”，对应 S7、S8、A2。难点不是正常情况下能取到任务，而是 Worker 崩溃、网络阻塞或租约过期后，旧 Worker 还可能回来提交。

我的设计把问题分开：用行锁处理领取竞争，用租约支持恢复，用 token 拒绝旧执行者，用事务一起提交证据、报告和完成状态。这样可以接受查询重复执行，但不接受失效 Worker 写入一份看似有效的报告。

**追问准备**：画两条时间线：Worker A 领取 → 租约过期 → Worker B 重领并换 token → A 迟到提交影响 0 行。比背“分布式锁”更容易讲清楚。

### B2 遇到过什么印象深刻的 bug？

**推荐回答**：仓库里修过一个“异常处理又被数据库约束拒绝”的问题。对账某个 Incident 失败时，需要写 `INCIDENT_RECONCILE_FAILED` 审计状态，但数据库旧 CHECK 约束没有允许这个值。尤其首次失败、还没有审计行时，插入失败记录也会报错，失败就难以追踪。

处理时通过 V11 迁移把这个状态加入受控集合，并让单个 Incident 的处理和失败记录隔离，不阻断后续事件。测试分别覆盖首次失败可以插入、已有记录保留原观察状态并写错误码，以及一个事件失败后仍继续处理其他事件。

**注意**：只有能讲清自己的排查过程时才说“我发现并修复了”；否则说“项目里这段回归让我意识到，异常路径也要遵守数据库契约”。本文没有重新复现这个历史问题。

**代码**：`alerts/AlertStateReconciliationWorker.java`、`src/main/resources/db/migration/V11__allow_incident_reconcile_failed_observation.sql`、`src/test/java/com/dduo/aiops/alerts/AlertStateReconciliationPostgresTests.java`

### B3 做过压测吗？能支持多少告警、多少 Worker？

**参考回答**：当前能指向的代码证据是正确性验证，不能据此给生产吞吐结论。比如 PostgreSQL 集成测试用 12 个线程同时提交相同 Webhook，检查最终只有 1 个事件、1 个 Incident 和 1 个初始 Run，这是并发幂等测试，不是告警吞吐压测。

如果要测容量，我会把 Webhook 接收、数据库领取、遥测查询分开：分别记录吞吐、P95、错误率、队列等待和数据库锁等待，再区分冷启动、积压和依赖慢响应。没有实际结果前，不说“支持上千 QPS”或“多 Worker 性能已经验证”。

**代码**：`src/test/java/com/dduo/aiops/work/DiagnosisPipelinePostgresIntegrationTests.java` 的 `concurrentDuplicateWebhooksCreateOneEventIncidentAndRun`

### B4 这个项目怎么验证？Fault Lab 能证明诊断准确率吗？

**参考回答**：验证分几层：

- **单元和 HTTP stub 测试**：检查规则判定、响应上限、遥测失败降级，以及 AI 超时、脱敏和越权输出拒绝。
- **真实 PostgreSQL 测试**：检查重复告警并发入库、租约过期拒绝写入、重新领取、证据不足不晋升主要根因，以及数据库触发器约束。
- **前端测试**：Vitest 检查组件和请求逻辑，Playwright 检查模拟 API 下的页面交互。
- **受控故障实验**：Fault Lab 提供已知故障和 Ground Truth，用来核对告警、证据、报告和恢复链路。

Fault Lab 的预设故障和 AI-chat contract lab 的模拟信号都不是完整生产故障分布。即使受控场景全部通过，也只能说明这些场景闭环，不能推导生产诊断准确率。

**注意**：部分 PostgreSQL 集成测试要设置 `AIDIAG_INTEGRATION_TESTS=true`，Testcontainers 用例无 Docker 时会跳过。本文只核对用例，未执行；模拟页面通过也不代表真实 Cloudflare 登录、真实模型调用通过。

**代码**：`src/test/java/com/dduo/aiops/work/DiagnosisPipelinePostgresIntegrationTests.java`、`src/test/java/com/dduo/aiops/advisory/AiAdvisoryServiceTests.java`、`frontend-console/tests/e2e/`、`fault-lab/GROUND_TRUTH.md`

### B5 项目最大的不足是什么？有什么具体改进方向？

**推荐回答**：可以讲一个当前确实存在的衔接问题：规则报告把 `remediationTemplate` 保存为包含 JSON 的字符串，AI 预览读取动作白名单时却只接受 JSON 数组，所以真实报告可能传不出预期的建议动作。

现有 AI 服务测试手写了数组形式的报告 fixture，单测可以验证自己的输入，但没有验证真实报告生产端和消费端的格式一致。下一步应统一字段结构，并补一个从真实报告生成到 AI Prompt 的契约测试，直接断言动作白名单有内容。

**更大的边界**：当前只覆盖有限的固定规则，不做完整因果分析；模型校验也不验证假设的语义支持。继续扩展前，应先补真实场景评测和跨阶段契约，而不是先宣传“AI 自动定位所有根因”。这个格式问题在本文核对时仍存在，本文没有修改业务代码。

**代码**：`diagnosis/DiagnosisRepository.java` 的 `reportJson`、`advisory/AiAdvisoryService.java` 的 `controlledActions`、`src/test/java/com/dduo/aiops/advisory/AiAdvisoryServiceTests.java`

### B6 为什么 AIDiag 用 Spring MVC，AI-chat 却用 WebFlux？

**参考回答**：两个应用的请求特点不同。AI-chat 有持续的 SSE 输出；AIDiag 主要是短 Webhook、只读查询和后台诊断，没有相同的流式需求。AIDiag 的主链又使用阻塞 JDBC 和 HTTP 客户端，MVC 更容易把事务和异常处理讲清楚。

项目启用了 Java 21 虚拟线程，一些并发查询也使用虚拟线程执行器，适合等待外部 IO 的任务。

**诚实补充**：虚拟线程不会让数据库连接和外部接口变成无限资源。当前 Hikari 最大连接数默认 10，仍要控制任务并发、查询成本和超时，不能说“开虚拟线程就解决性能问题”。

**代码**：`pom.xml`、`src/main/resources/application.yml`、`monitoring/ServiceSignalsService.java`、`advisory/AiAdvisoryService.java`

### B7 项目怎么部署？现在的线上状态能怎么讲？

**参考回答**：仓库采用家庭 Ubuntu 服务器加 Docker Compose 的部署方式，后端和 PostgreSQL、Prometheus、Alertmanager、Loki、Tempo 等组件分别运行。配置和 Secret 通过受控挂载提供，Secret 用 configtree 读取；数据库结构由 Flyway 管理。

发布方案使用不可变 release、产物 SHA-256、候选健康检查、切换前数据库备份和回滚材料。控制台发布到 Cloudflare Pages，Access 模式通过同域 Function 请求后端。FRP 只承担远程 SSH 维护，业务入口走各自的应用访问链路。

**有依据的历史补充**：仓库的 2026-10-01 发布记录写明 Access 改造已发布，后端健康、匿名拒绝和前端产物核对通过；允许账号真实登录、Inbox 读取和退出仍未验收，当次也未验证真实告警 Webhook 闭环。README 中“尚未激活”的说法与这份记录有差异，应带日期引用发布记录，不能把历史结果当成今天的实时状态。

**资料**：`deployment/home/README.md`、`docs/Cloudflare-Access-控制台接入.md`、`docs/deployment-verification/2026-10-01-cloudflare-access-release.md`

### B8 多实例部署会有什么问题？单机断电谁来发现？

**参考回答**：代码层面，多个 Worker 可以竞争同一个数据库任务，领取和提交靠数据库行锁、租约和 token；AI 日预算也在数据库里共享。但这不等于已经验证多实例吞吐和高可用，还需要实际并发领取、故障切换和数据库压力测试。

更现实的问题是当前家庭单机故障域。被监控服务和观测组件都在同一台主机时，断电会让整套链路一起消失，本机无法报告自己的整机故障，需要其他主机上的外部探针发现不可达。

**注意**：容器健康、数据库可连、业务正常是不同层次；多个容器也不是多个独立故障域。

**代码与资料**：`work/DiagnosisRunRepository.java`、`advisory/AiAdvisoryRepository.java`、`deployment/home/README.md`、`AGENTS.md`

### B9 如果告警量涨 100 倍，哪里会先撑不住？

**参考回答**：我会先测，不凭感觉给唯一瓶颈。优先看诊断队列等待、Worker 处理耗时、遥测查询超时和 PostgreSQL 连接池，因为一条任务会查询多个外部系统，任务执行速度不等于 Webhook 接收速度。

先检查 Alertmanager 分组是否合理、重复投递比例和有限的查询窗口，再用测量结果决定增加 Worker 或调整数据库。还要预算 Loki、Tempo、Prometheus 的存储和查询开销，不能只放宽线程数和响应上限。

**已有观察入口**：AIDiag 自监控暴露活动事件、待处理任务、积压、数据库可达性和最近成功采样时间。后续容量结论应来自受控压测，不从这些指标名直接推出来。

**代码**：`catalog/AidiagSelfMonitoring.java`、`infra/prometheus/rules/aidiag-pipeline.yml`

### B10 项目是单体还是微服务？为什么不拆成很多服务？

**参考回答**：AIDiag 是一个按业务包划分的单体后端：`alerts` 负责接入和对账，`work` 负责任务，`diagnosis` 负责取证和报告，`advisory` 负责 AI 建议，`console` 负责读取接口和鉴权，另外有服务目录和监控模块。它用一个 Maven 后端工程打成一个应用部署。

一个人维护、资源有限时，先在一个事务和进程边界内解决一致性，成本更低。拆服务会增加网络调用、跨库一致性和部署维护成本，当前没有足够收益支持这么做。

**注意**：Prometheus、Loki 等独立组件不等于后端拆成了微服务；AI-chat 的 MySQL、Redis、RabbitMQ、Elasticsearch 主要是被观测依赖，也不是 AIDiag 的任务调度组件。不要把两个项目的技术栈混讲。

**代码**：`pom.xml`、`src/main/java/com/dduo/aiops/`

### B11 项目是你一个人做的吗？用了 AI 写代码吗？

**推荐回答**：按实际经历说明分工。我在开发中使用了 AI 编程工具，但方案、边界和验收需要自己负责。比如不能只看正常路径，要检查告警重复投递、租约过期、弱证据被误当主要根因、模型引用不存在的证据等情况。

我判断实现是否可靠，会对照 SQL 约束和状态流转，看测试是否覆盖真实生产端与消费端的契约，而不只看模拟返回是否符合预期。B5 的报告格式衔接就是一个具体例子。

**注意**：是否主要独立开发、哪些设计由你决定、哪些问题亲自复现，都按真实情况说。不要否认使用 AI，也不要把读懂别人或 AI 的代码包装成自己完整完成了测试和上线。

### B12 遇到答不上来的问题怎么办？

**这不是面试题，是给你的应急话术**：

- 完全不知道：“这块我没有深入研究过，我目前的理解是……，之后会对照文档和实验确认。”
- 需要想一下：“我想一下。”然后从正常链路、异常情况、持久化结果三个角度梳理。
- 被问到没做的功能：“目前没有实现。如果要做，我会先解决……，再用……验证。”
- 被问真实数据：“目前只有这些用例或历史记录，不能据此给生产吞吐、准确率或实时状态结论。”
- 被连续追问卡住：回到熟悉的具体例子，比如旧 Worker 的 token 失效、Metric/Log 不足时弃权，避免堆名词。

诚实说明边界，再讲清一个自己掌握的实现，比临时编一个完整方案更可靠。
