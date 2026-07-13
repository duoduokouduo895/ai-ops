# Stage 5.0 AI-chat 私有候选验证记录

> 日期：2026-07-13  
> 候选 release：`20260713-stage5-candidate-8`  
> AI-chat commit：`08c6c30`

## 结论

AI-chat 最新 backend 已作为被动候选部署到 GCP，健康检查通过。候选只绑定 `127.0.0.1:18082`，未修改 `api.dduo.chat` DNS、Nginx 或旧云服务器，因此不承接公网流量。

Stage 5.0 的本地容器化、干净源码归档和云端私有候选三项完成；凭据/Nacos 安全门禁、TLS/Nginx 切换准备和完整业务验收仍未完成，禁止据此切换生产流量。

## 构建与测试

- 本地使用 Java 21 编译目标 Java 17，执行 `mvn test`：141 tests，0 failures，0 errors，0 skipped。
- 云端多阶段 Docker build 执行 `mvn verify`：141 tests 全部通过。
- 最终运行镜像 `/opt` 下仅包含应用 JAR、OTel Java Agent 和 JRE 文件；没有源码、前端、构建缓存或 Secret。
- 上传归档使用 SHA-256 校验后才解压到独立 release 目录。

## 运行边界

- 容器状态：healthy，restart count 0。
- 运行用户：`10001:10001`；root filesystem 为 read-only。
- 限制：2 GiB memory、2 CPU、512 PIDs；Nacos 快照目录使用 32 MiB tmpfs。
- 网络：`middleware-net`、`nacos-net`、`aidiag-telemetry`。
- 端口：仅 `127.0.0.1:18082 -> 8080`；未公开 management 或 OTLP receiver。

## 被动模式验证

候选显式关闭以下可能改变共享状态或产生后台流量的能力：

- Flyway migration；
- RabbitMQ listener 与动态队列声明；
- Spring scheduling；
- Elasticsearch 后台同步；
- Nacos service discovery；
- 旧健康巡检与模型 warmup。

验证结果：数据库 Flyway 版本仍为 `4`，`document_processing_outbox` 表不存在；document queues 的 consumer count 均为 `0`；Nacos 未注册候选实例。候选启动日志没有残留 `APPLICATION FAILED`、Nacos snapshot 写入错误或重启。

## 对抗性审查与修正

1. 首次候选因 `DiagnosticController` 强制依赖 `AmqpAdmin` 而启动失败。已改为可选依赖，在关闭 RabbitMQ 管理能力时返回明确的 unavailable 结果，并增加回归测试；未通过重新开启 Broker 自动声明规避问题。
2. 只读 root filesystem 阻止 Nacos 写入本地快照。已为 `/opt/aichat/nacos` 增加受限 tmpfs，保留整体只读策略并恢复短时配置缓存能力。
3. 失败的候选始终停留在 loopback，部署脚本健康门禁失败后未修改 DNS/Nginx；生产数据库未迁移。

## 未完成门禁

- Nacos 匿名读取和历史 Secret 轮换方案尚未完成。
- `api.dduo.chat` 的 GCP Nginx virtual host、TLS 证书、DNS 回退清单尚未预置。
- 当前 actuator health 只证明应用上下文和健康组件通过，不等于登录、SSE Chat、知识库、OSS 和模型调用的完整业务验收。
- Stage 5.1 的专用 management port、Prometheus 指标、隐私日志和真实 Trace 验收尚未实施。
