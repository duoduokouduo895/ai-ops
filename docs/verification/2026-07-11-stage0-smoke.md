# Stage 0 遥测 Smoke 验收记录

验收日期：2026-07-11  
环境：`cloud-demo`  
服务：`aidiag`

## 测试批次

- `smokeRunId`：`16243ad9-c9c4-4883-a070-5ba4ab864b3d`
- `traceId`：`3a94ddf3d408eed59081955c826e1612`
- 触发接口：`POST /internal/smoke/telemetry`

## 通过项

- Prometheus 查询到 `aidiag_smoke_telemetry_events_total{scenario="stage0"}`，值为 `1`。
- Loki 以服务名和 `smokeRunId` 查询到唯一结构化日志，日志中的 `traceId` 与测试批次一致。
- Tempo TraceQL 按 `aidiag.smoke.run.id` 查询命中该 Trace；按 `traceId` 读取的 Trace 含相同 `smokeRunId`。
- 随机不存在的 `smokeRunId` 在 Loki 和 Tempo 查询中均无结果。
- 匹配日志和 Trace 的结果不包含 PostgreSQL、Alertmanager、Grafana 三个运行期 Secret。
- Grafana 的 Prometheus、Loki、Tempo 数据源健康检查均为 `OK`。
- 测试完成后，`AIDIAG_SMOKE_ENABLED=false`，接口返回 `404`，Prometheus 的 `aidiag` target 为 `up`。

## 安全说明

本记录未包含 Secret、密码、Cookie、Token、连接串或完整原始遥测负载。Smoke 接口默认不注册，启用时仅可通过云主机回环地址访问，且没有数据库写入或任意参数。
