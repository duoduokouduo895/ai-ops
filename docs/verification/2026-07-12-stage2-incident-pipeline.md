# Stage 2 Incident Pipeline 验收记录（2026-07-12）

## 范围

本记录覆盖可靠告警接入、Incident 聚合和持久化诊断任务；不包含自动建单、证据采集、规则诊断、报告生成或 LLM 调用。这些能力仍属于 Stage 3/4。

## 已部署变更

- Flyway V2：为 Incident 增加活动关联键，补充任务 lease token、lease generation、自动重试和 `EVIDENCE_READY` 状态；
- Flyway V3：为所有可处理状态的过期租约建立索引，支持异常退出后重新认领 `COLLECTING` 等中间状态任务；
- Alertmanager Webhook：Basic Auth、请求体上限和 JSON 校验；认证/格式错误不落库，数据库错误返回可重试的服务端错误；
- PostgreSQL：用唯一约束和活动 Incident 的部分唯一索引保证事件幂等、并发聚合；
- Worker：通过 `FOR UPDATE SKIP LOCKED` 认领任务；每次状态推进均以 lease token 及 generation 作为 fencing 条件；当前只审计交接并停在 `EVIDENCE_READY`。

## 验收证据

| 场景 | 结果 |
|---|---|
| 未认证 Webhook | 返回 `401`，未创建事件或 Incident。 |
| 认证后的首个测试事件 | 返回 `202`；创建 1 个 Incident、1 个告警事件和 1 个初始任务。 |
| 10 个并发重复 firing Webhook | 仍为 1 个 Incident、1 个诊断任务；同一告警事件的 `receive_count` 累加到 11。 |
| resolved Webhook | 仅记录 resolved 生命周期事件，不新增诊断任务。 |
| Fault Lab Hikari 连接池真实告警 | Prometheus -> Alertmanager -> Basic Auth Webhook 成功；数据库记录 1 个 firing、1 个 resolved 和 1 个 `EVIDENCE_READY` 任务。 |
| Worker 异常退出恢复 | 测试任务先处于 `COLLECTING`、`lease_generation=1`；后端异常停止并重新启动后，由新实例接管为 generation 2，最终为 `EVIDENCE_READY`，且无有效 lease。 |
| 最终运行态 | 后端 `/actuator/health` 为 `UP`；Prometheus 7 个配置 target 均为 `up`；所有 AIDiag Compose 服务运行正常。 |

## 结论与边界

Stage 2 可以作为可靠事件接入与任务交接层使用。它明确不把“任务已接入”伪装成“已诊断”：没有收集到真实 Prometheus/Loki/Tempo 证据时，任务不会写入报告，也不会将 Incident 标记为 `DIAGNOSED`。

租约恢复实验验证了失联任务可重新认领。当前没有通过并行保留旧 Worker 进程来做进程级 stale-writer 注入；代码层面以每次更新的 lease token 和 generation 条件防止旧持有者写回。Stage 3 应补充该边界的自动化集成测试，并实现证据采集和确定性报告。

## 收紧复验（2026-07-12）

- 交接状态推进与 `stage2-ingestion-handoff` 审计已改为单条 CTE；过期 lease 无法留下成功审计；
- Webhook 字段按数据库列宽验证，越界标签在写库前拒绝；
- Compose PostgreSQL 集成测试覆盖并发幂等、过期 `COLLECTING` 接管、Stage 2/3 stale lease 拒绝写入和报告证据持久化；
- 发布结构改为 `/opt/aidiag/current` 指向不可变 release，`.env.cloud` 与文件 Secret 固定在 `/opt/aidiag/runtime`；容器不再挂载旧 release；
- V4 扩展 Incident 标题并防止重复 Stage 2 handoff 审计；V5 修复 Evidence 与 Candidate 的级联删除关系。
