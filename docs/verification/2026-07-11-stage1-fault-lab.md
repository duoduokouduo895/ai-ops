# Stage 1 Fault Lab 验收记录（2026-07-11）

## 部署边界

- Release：`/opt/aidiag/releases/20260711-stage1`。
- 新增 `faultlab-postgres`、独立 `faultlab_db_password`、独立数据卷、内部 `faultlab` 网络与日志卷；未复用 AIDiag 主库。
- 仅 `order-service` 绑定 `127.0.0.1:18081`；`payment-service` 无宿主机端口，控制端点只通过 Compose 内部调用。
- 所有故障开关和参数均为固定集合，未提供任意 SQL、URL、延迟或资源大小输入。
- 后续加固：`faultlab_control_token` 仅挂载给 Payment；未携带请求头的控制请求返回 404，Order 容器不存在该 Secret 文件。

## 构建与运行

- 云端 Java 21 Docker build：Maven Enforcer（Java 21、Maven 3.9）通过；`FaultStateTests` 1/1 通过。
- `faultlab-postgres`、`payment-service`、`order-service` 处于 healthy 状态。
- Prometheus：`faultlab-order` 与 `faultlab-payment` targets 均为 `up=1`。

## 正常链路与关联

- 正常恢复请求：`runId=1417a3e6-f7ca-46c4-8c6d-687405f53380`，HTTP `201 COMPLETED`。
- Order 与 Payment 的结构化日志共享 `traceId=0100a8aeb842a5a23fd02b3822c33991`，但各自保留不同 spanId。
- Loki 以该 runId 查询到 Payment 的 `faultlab_payment_succeeded` 事件；Tempo 查询同一 trace 同时包含 `order-service` 与 `payment-service`。

## 四类故障

| 故障 | 观测结果 | 恢复验证 |
|---|---|---|
| `downstream-timeout` | HTTP `504 PAYMENT_TIMEOUT`；runId `554caf26-e684-4e29-a1f2-317756bf7b8a` | 关闭后 HTTP 201 |
| `database-slow-query` | HTTP `504 PAYMENT_TIMEOUT`；runId `1d3e4c38-ef03-4ffe-9102-99ff5db28896` | 关闭后 HTTP 201 |
| `hikari-exhaustion` | HTTP `503 PAYMENT_UNAVAILABLE`；runId `9b91f184-a552-46a1-8e72-c50f2687629f`；Ground Truth 显示 4 个持有连接 | 关闭后 HTTP 201 |
| `thread-pool-exhaustion` | HTTP `503 PAYMENT_UNAVAILABLE`；runId `de2ac379-0299-416c-9f5e-31d5590dae3d`；Loki 记录 `faultlab_thread_pool_rejected` | 关闭后 HTTP 201 |

Prometheus 历史窗口证据：`max_over_time(faultlab_hikari_holders_active[10m])=4`、`max_over_time(faultlab_thread_pool_active[10m])=2`、`max_over_time(faultlab_thread_pool_queue_depth[10m])=2`，且 `faultlab_thread_pool_rejected_total=1`。

## 验收中修复的确定性问题

1. Hikari 获取连接与 Order HTTP 超时原本同为 1 秒，存在 503/504 竞态；将连接等待固定为 500ms，短于 Order 的 1 秒超时，使连接池故障稳定返回 503。
2. 线程池预占任务闭包读取可变字段，导致开关过程中存在任务入队竞态；改为捕获单个不可变 `CountDownLatch`，使 2 个活动线程和 2 个队列任务稳定建立。

Stage 1 已完成。Alertmanager 触发 Incident、幂等 Webhook、任务租约和诊断报告仍属于 Stage 2/3，尚未宣称完成。
