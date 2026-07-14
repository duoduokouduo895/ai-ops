# AIDiag V1 核心模块

## 1. 模块总览

| 模块 | 输入 | 输出 | 关键实现 |
|---|---|---|---|
| 告警接入 | Alertmanager JSON | `alert_event`、Incident、初始 run | `AlertmanagerWebhookController/Service` |
| Incident 查询 | 查询参数、Incident id | 分页、详情、shadow summary | `IncidentQueryController` |
| Stage 2 任务 | `PENDING/RETRY_WAIT` run | `EVIDENCE_READY` 或 retry | `DiagnosisRunWorker/Repository` |
| Stage 3 诊断 | Incident + 固定规则 | evidence、candidate、report | `RuleBasedDiagnosisService` 等 |
| Stage 4 AI 建议 | 最新确定性报告 | 独立 advisory audit | `Stage4AiDiagnosisService` 等 |
| 访问控制 | Basic Auth header | allow / 401 | 三组独立 Authenticator |
| 观测基础设施 | Metrics/Logs/Traces | 告警和 Dashboard | `infra/`、`compose.yaml` |
| 故障实验 | 受控 Token | 可重复 Ground Truth | `fault-lab/` |

## 2. 告警接入模块

### 代码入口

- [`AlertmanagerWebhookController.java`](../src/main/java/com/dduo/aiops/alerts/AlertmanagerWebhookController.java)
- [`AlertmanagerWebhookService.java`](../src/main/java/com/dduo/aiops/alerts/AlertmanagerWebhookService.java)
- [`WebhookAuthenticator.java`](../src/main/java/com/dduo/aiops/alerts/WebhookAuthenticator.java)

### 如何工作

1. Controller 先校验独立 Webhook Basic Auth；
2. 根据 `Content-Length` 预判，并从输入流最多读取 `maxPayloadBytes + 1`；
3. 只接受 JSON object；
4. Service 校验 receiver、groupKey、alerts、状态、时间、severity 和受控身份 label；
5. JSON 在持久化前递归脱敏，raw body 计算 SHA-256；
6. 每个 alert 独立写 `alert_event`；数据库唯一约束负责并发幂等；
7. firing 创建或更新活动 Incident，resolved 只关闭时间顺序安全的 Incident。

### 核心不变量

- 幂等键：`receiver + group_key + fingerprint + alert_status + starts_at`；
- 活动 Incident 唯一键：`correlation_key` 的 partial unique index；
- `resolved` group 不能包含 firing alert；
- 同一 resolved group 若异常地包含多个 environment，必须按各自 correlation key 独立恢复；
- 新告警只能提高 Incident severity，不能把 CRITICAL 降为 WARNING；
- annotations 和额外 labels 不能直接进入查询模板；
- 超大、空 body、非法时间或控制字符均在业务写入前拒绝。

## 3. Incident 聚合与只读 API

### 代码入口

- [`IncidentQueryController.java`](../src/main/java/com/dduo/aiops/alerts/IncidentQueryController.java)
- [`DiagnosisReportController.java`](../src/main/java/com/dduo/aiops/diagnosis/DiagnosisReportController.java)
- [`ReadApiAuthenticationInterceptor.java`](../src/main/java/com/dduo/aiops/security/ReadApiAuthenticationInterceptor.java)

### 聚合键为什么不使用 alert 文本

`correlation_key = SHA-256(environment + receiver + groupKey)`。Alertmanager 的 groupKey 已由受控 `group_by` 规则生成；再次加上 environment 和 receiver，可隔离不同环境与路由。annotation 文本可能变化、过长或含用户数据，不适合作为聚合键。

### API 边界

- page 最大 10000，size 最大 100；
- service 只允许 `[A-Za-z0-9._-]{1,128}`；
- status/severity 使用 enum；
- 报告 API只返回 `report_json`，不返回 `evidence.raw_data` 和原始告警 payload；
- 所有 GET Incident 路径通过 read-only Basic Auth；Stage 4 POST 使用另一组 operator Auth。

## 4. Stage 2：持久化任务与租约恢复

### 代码入口

- [`DiagnosisRunWorker.java`](../src/main/java/com/dduo/aiops/work/DiagnosisRunWorker.java)
- [`DiagnosisRunRepository.java`](../src/main/java/com/dduo/aiops/work/DiagnosisRunRepository.java)

### 使用了什么

- `FOR UPDATE SKIP LOCKED`：多个 Worker 同时轮询时只让一个拿到某条任务；
- `lease_owner`：记录当前 Worker 身份；
- `lease_token`：每次抢占生成随机 fencing token；
- `lease_generation`：记录被回收次数；
- `lease_until/heartbeat_at`：判断 Worker 是否仍存活；
- PostgreSQL transaction：状态更新和 Stage 2 handoff audit 同时成功或回滚。

### 调用流程

```text
claimNext
  -> PENDING/RETRY_WAIT/expired processing run
  -> LEASED
moveToCollecting
  -> COLLECTING
waitWithHeartbeats
recordStageTwoHandoff
  -> EVIDENCE_READY + tool_call_audit（同一事务）
```

失败时当前 run 进入 `FAILED`。未达到最大次数则新建下一 attempt；达到最大次数时活动 Incident 同步进入 `FAILED`，避免 UI 永久显示 `DIAGNOSING`。

### 容易引入 Bug 的地方

- 任何状态写入都必须继续带 owner、token 和未过期 lease 条件；
- 不能把 `SKIP LOCKED` 去掉，否则多实例会互相等待；
- 不能复用旧 token；
- retry 必须创建新 row，保留 previous run 审计链；
- resolved Incident 不能被迟到的 Worker 重新改成 FAILED 或 DIAGNOSED。

## 5. Stage 3：确定性诊断

### 代码入口

- [`DiagnosisRuleRegistry.java`](../src/main/java/com/dduo/aiops/diagnosis/DiagnosisRuleRegistry.java)
- [`AiChatDiagnosisRuleProvider.java`](../src/main/java/com/dduo/aiops/diagnosis/AiChatDiagnosisRuleProvider.java)
- [`FaultLabDiagnosisRuleProvider.java`](../src/main/java/com/dduo/aiops/diagnosis/FaultLabDiagnosisRuleProvider.java)
- [`TelemetryEvidenceCollector.java`](../src/main/java/com/dduo/aiops/diagnosis/TelemetryEvidenceCollector.java)
- [`RuleBasedDiagnosisService.java`](../src/main/java/com/dduo/aiops/diagnosis/RuleBasedDiagnosisService.java)
- [`Stage3DiagnosisWorker.java`](../src/main/java/com/dduo/aiops/diagnosis/Stage3DiagnosisWorker.java)
- [`Stage3DiagnosisRepository.java`](../src/main/java/com/dduo/aiops/diagnosis/Stage3DiagnosisRepository.java)

### 规则边界

Registry 以精确 `(serviceName, alertName)` 查找规则。每条规则在代码中固定：

- PromQL 与阈值；
- LogQL 与事件 marker；
- TraceQL；
- candidate name、描述和受控 remediation JSON。

未匹配规则时不访问遥测后端，直接生成 `INSUFFICIENT_EVIDENCE`。

### 证据采集边界

- 单工具 HTTP timeout 1～10 秒；
- 单响应最多 128 KiB；
- Loki 最多计 200 行，不保存日志原文；
- Tempo 最多保存 50 个合法 traceId 与 duration 摘要；
- 请求内容仅来自服务器固定规则；
- 每次外部查询前续租，失去 lease 立即停止；
- 采集失败会写工具失败审计，但不伪造“0 表示健康”。

### 判定规则

| Metric | Log | Trace | 结论 |
|---|---|---|---|
| 命中 | 命中 | 任意 | `SUPPORTED`，可成为主要根因 |
| 仅一类或 Trace 命中 | 缺失/未命中 | 任意 | `WEAKLY_SUPPORTED`，不能成为主要根因 |
| 全部无结果/失败 | 全部无结果/失败 | 无 | `INSUFFICIENT_EVIDENCE` |

数据库触发器再次保证 `diagnosis_report.primary_root_cause_id` 只能引用同一 run 下的 `SUPPORTED` candidate。应用 Bug 也不能绕过该约束。

## 6. Stage 4：AI 辅助建议

### 代码入口

- [`Stage4AiDiagnosisService.java`](../src/main/java/com/dduo/aiops/aid/Stage4AiDiagnosisService.java)
- [`SpringAiStage4ModelClient.java`](../src/main/java/com/dduo/aiops/aid/SpringAiStage4ModelClient.java)
- [`AiDiagnosisAdviceValidator.java`](../src/main/java/com/dduo/aiops/aid/AiDiagnosisAdviceValidator.java)
- [`Stage4Repository.java`](../src/main/java/com/dduo/aiops/aid/Stage4Repository.java)
- [`AiDiagnosisPreviewController.java`](../src/main/java/com/dduo/aiops/aid/AiDiagnosisPreviewController.java)

### 安全链

```text
operator Basic Auth
 -> 读取最新确定性报告
 -> 只取 importance 最高的有界 evidence summary
 -> Secret/邮箱/手机号/URI user-info 脱敏
 -> prompt 字符上限
 -> PostgreSQL 原子每日预算
 -> 虚拟线程 + 应用 timeout + Provider timeout
 -> JSON Object 输出
 -> evidence id / runbook action allowlist
 -> 敏感输出检测
 -> 独立 ai_diagnosis_attempt 审计
```

AI 建议不能修改 `diagnosis_report`，也没有执行命令的代码路径。Provider 异常不会把原始异常正文返回或写入数据库。

## 7. 数据模型

| 表 | 作用 |
|---|---|
| `alert_event` | 脱敏告警事件、raw body hash、接收次数 |
| `incident` | 聚合后的故障生命周期 |
| `incident_alert` | Incident 与事件的多对多关系 |
| `incident_affected_service` | 受影响服务和角色 |
| `diagnosis_run` | attempt、状态、lease、failure 和模型元数据 |
| `tool_call_audit` | 每次受控查询/交接的请求摘要和结果 |
| `evidence` | 指标/日志/Trace 等紧凑证据 |
| `root_cause_candidate` | 最多三条候选及支持分 |
| `candidate_evidence` | 支持或反对关系 |
| `diagnosis_report` | 不可变 JSON/Markdown 报告 |
| `ai_diagnosis_attempt` | Stage 4 请求、结果或 fallback 审计 |
| `ai_model_daily_budget` | 多实例共享的 UTC 日调用额度 |

迁移文件位于 [`../src/main/resources/db/migration/`](../src/main/resources/db/migration/)。禁止手工修改已经发布的迁移；新约束必须新增下一版本迁移。

## 8. 可观测基础设施

- [`prometheus.yml`](../infra/prometheus/prometheus.yml)：抓取 AIDiag、AI-chat、Fault Lab 和 file-SD 生产目标；
- [`aidiag.yml`](../infra/prometheus/rules/aidiag.yml)：固定告警规则；
- [`config.alloy`](../infra/alloy/config.alloy)：文件日志和 OTLP Trace；
- [`alertmanager.yml`](../infra/alertmanager/alertmanager.yml)：分组和 Webhook；
- [`blackbox.yml`](../infra/blackbox/blackbox.yml)：HTTP/TCP 模块；
- [`grafana/dashboards/`](../infra/grafana/dashboards/)：独立故障入口和 Operations Dashboard。

生产目标使用 `file_sd`，由 [`cloud-activate-ai-chat-production-monitoring.sh`](../scripts/cloud-activate-ai-chat-production-monitoring.sh) 在 cutover 证据完整后原子创建。目标文件使用 `0644 root:root`，保证 Prometheus 容器可读；marker 保存 digest，防止无管理来源的文件被静默复用。

## 9. 测试层次

1. 单元测试：校验认证、规则、预算、脱敏、输出 allowlist、租约分支；
2. Spring MVC/Context 测试：校验参数错误为 400、真实 Bean 装配；
3. PostgreSQL 条件集成测试：校验并发重复 Webhook、partial unique index、租约回收、事务回滚和数据库触发器；
4. 配置契约测试：校验 Dashboard、Prometheus 目标、生产脚本和版本台账；
5. Linux lifecycle test：用 mock Docker 验证激活、幂等、失败撤回和回滚；
6. 云端门禁：`promtool`、Alloy、Compose、容器 health、真实 firing/resolved；
7. 隔离故障实验：四类场景的 Metric + Log + Trace 联合证据。

本地没有 Docker 时，PostgreSQL 条件集成测试会明确标记 skipped，不能用单元测试替代其结论。

## 10. 对抗性审查后的已知边界

- V1 只对 Stage 4 建议与模型日预算实施 30 天默认保留清理；`alert_event`、Incident、evidence、report 尚无自动保留策略。删除它们会改变审计链，需先确定保留周期、归档与外键删除顺序，不在本次审计中自动开启。
- 正常 Alertmanager 顺序中，firing 先于 resolved；当延迟的旧 resolved 晚于更新 firing 到达时，`last_alert_at` 门禁会拒绝误关单。但若因 Alertmanager 状态丢失/备份切换导致 resolved 在任何 firing 之前先到，V1 没有 tombstone 或主动对账，之后的旧 firing 仍可能建立活动 Incident。这是 V1.1 需要的恢复对账项。
- 同一活动 Incident 的首次 run 由 `(incident_id, attempt)` 唯一键保护。报告因证据不足回到 `OPEN` 后，新 firing 不会自动建立第二次业务重诊；当前主要依赖新 Incident 或人工调查。
- Loki 在单机 named volume 的初始化边界下仍使用已记录的 root 运行权衡；这不是理想的长期安全基线，迁移至对象存储/独立主机时应改为最小权限 UID。
