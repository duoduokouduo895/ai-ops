# Stage 3 云端验收说明

Stage 3 只有在四类受控故障均通过真实链路后才可标记为完成。以下脚本不会删除数据库记录、不会打印 Secret，也不会执行自动修复：

```bash
bash scripts/verify-stage3.sh
```

脚本在每个故障场景中验证完整闭环：

1. 通过 Payment 容器内部、带控制 Token 的固定端点开启故障；
2. Order 请求得到预期失败状态；
3. Prometheus 规则进入 firing，Alertmanager 投递 Webhook；
4. AIDiag 创建 Incident、采集固定 Prometheus/Loki 证据并生成 `SUPPORTED` 报告；
5. 关闭故障后，Order 恢复为 `201`，对应 Incident 进入 `RESOLVED`。

| Fault Lab 故障 | 告警 | 预期 Order HTTP | 指标证据 |
|---|---|---:|---|
| `downstream-timeout` | `FaultLabDownstreamTimeout` | 504 | `faultlab_fault_active{fault="downstream-timeout"}`，仅限受控实验 |
| `database-slow-query` | `FaultLabDatabaseSlowQuery` | 504 | `faultlab_fault_active{fault="database-slow-query"}`，仅限受控实验 |
| `hikari-exhaustion` | `FaultLabHikariPoolExhausted` | 503 | `faultlab_hikari_holders_active >= 4` |
| `thread-pool-exhaustion` | `FaultLabThreadPoolRejected` | 503 | `faultlab_thread_pool_saturated == 1` |

`faultlab_fault_active` 是经过认证的 Fault Lab 开关状态，用于稳定演示下游超时和慢查询；它不是生产服务健康指标，后续接入 AI-chat 时必须替换为真实的延迟、错误率、连接池或 Trace 指标。

若脚本在开始前提示已有未解决 Fault Lab Incident，请先人工检查历史数据和 Alertmanager 状态，不要删除 Incident 记录来绕过验收。
