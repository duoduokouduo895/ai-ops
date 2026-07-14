# V1 Phase B/C 云端执行记录（2026-07-14）

## 结论

cutover `20260713-v1-mvp-cutover-1` 的 Phase B 已完成。Phase C 的生产启动、Flyway V5、Guest SSE、
Nacos/Elasticsearch、Prometheus/Loki/Tempo 和告警恢复核心链路已通过；后续正式用户、知识库、OSS、
IK/RAG、管理端和模型路由完整业务回归也已完成。后续已完成
Phase D 的 TLS/Nginx 预置和主机外 `--resolve` 验证，但没有修改 DNS。

旧服务器已停止，而公共 DNS 仍指向旧地址 `119.29.109.190`。因此公开 API 当前处于维护不可用状态；GCP
production 仅绑定 `127.0.0.1:18083` 与 `127.0.0.1:19092`，不能把“容器健康”误解为“公网已经恢复”。

## Phase B：停写、备份与批准

- 旧 `spring_dduo-chat.service` 的 JVM PID 为 `670913`。多次 `SIGTERM` 未完成退出；线程证据显示
  `SpringApplicationShutdownHook` 在 RabbitMQ `CachingConnectionFactory.createConnection` 路径等待，
  `SIGTERM handler` 同步等待 shutdown hooks；
- 在用户明确授权后，只对已再次核对的 MainPID 使用 `SIGKILL`。执行后 MainPID 为 0、8080 listener 为 0、
  Java 进程为 0，Java 中间件连接也已消失；systemd `Restart=no`，服务状态为 `failed` 是强制停止后的预期；
- 旧机 root-only 停写证据：
  `/opt/ai-chat-legacy-cutover/20260713-v1-mvp-cutover-1/stopped.env`，SHA-256 为
  `f0a5037057fe15697b349d58ec71d8732dab25df181e4aa4e06bef42e05b81db`；复制到 GCP 的审计副本保持
  `root:root`、mode `600` 且摘要一致；
- V4 数据库备份为
  `/opt/ai-chat/runtime/cutovers/20260713-v1-mvp-cutover-1/ai_agent_db.sql.gz`，SHA-256 为
  `8e81624d92298505604fb0c8413dc6e204445a0aba860f6a3b8db8ff305e660c`；gzip、摘要、所有者和 mode `600`
  均通过门禁；
- candidate 已停止但容器保留；批准证据与 AI-chat r7 manifest
  `d47c4843158fa08b51c9e5c6501d3ff16f22e98edef355796a23249f59091968` 绑定，`--ready` 通过后才激活
  production targets。

## Phase C：生产启动与核心回归

- AI-chat 首次活动 release 为 `20260714-v1-production-cutover-gate-r7`，随后经无结构变更升级至
  `20260714-v1.0.0-rag-model-policy-r12`；最终 Java 21 构建执行 176 项测试，0 failure、0 error、0 skip；
- Flyway 从 V4 升至 V5，`document_processing_outbox` 已创建；production 容器
  `ai-chat-production-ai-chat-backend-1` 为 `healthy/restart 0`，只读根文件系统、非 privileged；
- production 仅绑定 loopback：业务端口 `18083 -> 8080`，management 端口 `19092 -> 9091`；
- Nacos production 最小权限身份只能看到一个健康的 `ai-chat-production` 实例；Elasticsearch 稳定别名
  验证为 9.3.2，IK 版本为 9.3.2；
- 使用临时 Guest 调用一次 production SSE Chat，HTTP 200，收到 metadata/status/message，数据库产生两条
  message；同一 trace `6f5fb8921cba4941a1447cd6f2645710` 可在 Prometheus、Loki、Tempo 关联；唯一
  Prompt marker 未进入应用日志、Docker 日志或 Loki；MySQL、Redis、Elasticsearch 测试派生数据已精确清理；
- AI-chat 当前公开模型 catalog 没有 `deepseek-v4-flash`。本次 smoke 使用 API alias `chat-fast`；运行时
  catalog 显示 `chat-fast`、`chat-reasoning`、`chat-coding` 分别对应 GLM-4.5、DeepSeek V3.2、Qwen3-32B。
  这不等同于 AIDiag Stage 4 的 DeepSeek 配置，发布前必须消除 AI-chat 文档与运行配置漂移；
- AIDiag 活动 release 最终为 `20260714-v1-production-monitoring-gate-r5`。production 七个目标全部 up，
  candidate 七个目标定义保留；candidate 计划停写时只抑制其 application unavailable 告警，production 与
  dependency 告警不受抑制；
- 受控 pause production 触发 `AiChatProcessUnavailable`，生成 Incident
  `de992e16-bda6-470e-af2d-1b2787a2ea18` 和确定性报告，`evaluationGroundTruth=UNSPECIFIED`；unpause
  后 Incident 变为 `RESOLVED`，production 恢复 `healthy/restart 0`，验证了真实
  `firing -> Incident -> report -> resolved` 周期。
- 完整业务回归创建临时正式用户、知识库和文档，验证登录/刷新、用户 SSE、管理 API、OSS 上传、
  Elasticsearch 9 + IK、向量投影和 RAG 后按唯一标识精确清理。r9 修复 R2DBC 投影构造问题；r10 暴露
  RAG 同时注入上下文和知识库工具导致模型返回 tool call 的问题；r11 禁止 RAG 重复暴露检索工具；r12
  将 RAG 请求路由到契约通过的 `chat-reasoning`，并在 SSE metadata 中披露实际模型。客户端传
  `chat-fast` 的真实候选回归最终返回正确答案、无 tool/box 协议泄漏，`cleanup_remaining=0`。

## 发现并修正的问题

1. candidate 在 Phase B 计划停写后仍被原规则作为生产故障告警。r5 将抑制范围限定为：仅当 production
   target series 存在时抑制 `ai-chat-candidate` 的 application alert；不能抑制 production 或 dependency；
2. r5 激活重建 Alertmanager 后丢失之前 firing 的活动告警状态，导致一个 candidate 假 Incident 没有收到
   resolved。没有直接修改数据库，而是用原始 group key/fingerprint 通过认证 Webhook 写入 resolved，保留
   审计路径。`/alertmanager` 已挂载 named volume，但 Alertmanager 不依靠该卷持久化活动告警集合；后续应
   避免在 firing 期间重建，并增加重启后的 Incident/Prometheus 状态对账，不能把“已有 volume”当作已解决；
3. 本地默认 DNS resolver 曾返回旧地址以外的异常 A 记录；向 Google DNS 与 Cloudflare DNS 交叉查询均只
   返回 `119.29.109.190`。后续 DNS 切换验收必须使用至少两个独立递归解析器，并核对权威记录。

## 2026-07-14 继续任务后的只读复核

- GCP AI-chat production 为 `healthy/restart 0`，loopback 两端口仍监听；
- `cloud-verify-ai-chat-monitoring.sh --dual` 返回 `PASS`：production `7-up`，candidate `7-retained`；
- AIDiag r5 全部核心容器运行，活动 `AiChatProcessUnavailable` 数量为 0；
- 旧服务器 MainPID、8080 listener、Java process 均为 0；停写证据摘要未变化；
- Google DNS 与 Cloudflare DNS 均仍将 `api.dduo.chat` 解析到旧地址。

## 未完成门禁

- Phase D 的正式 TLS、Nginx 原子配置和主机外 `--resolve` HTTPS 验证已完成；DNS 切换、通知渠道和至少
  30 分钟观察未执行；
- 历史 Secret 轮换、外部 uptime monitor、恢复演练、SBOM/镜像 digest/证据归档、V1 tag 尚未完成；
- Alertmanager 重建后的活动告警连续性仍是已知风险；即使 `/alertmanager` 使用 named volume，也不能只依赖
  Alertmanager 内存状态发送 resolved，需增加重启后的状态对账。

## Phase D 入口预置（DNS 未切换）

- 旧服务器证书 SAN 精确包含 `api.dduo.chat`，证书与私钥匹配，有效期至 2026-08-12；通过 SSH 内存 tar
  流迁移，未把私钥写入本地工作区或命令输出。GCP 正式私钥为 `root:root`、mode `600`，临时导入副本已删除；
- 首次激活暴露两个门禁缺陷：Nginx reload 后约 355ms 才切换到新 SNI 证书，原脚本立即探测会误撤回；
  `GET /api/users/guest` 不是合法只读探针并返回 500。两次失败均自动撤回配置，DNS 和 AI-chat 容器未变化；
- control release `20260714-v1-production-nginx-gate-r8` 为 reload 增加 20 秒有界 readiness retry，并改用
  `/healthz=200` 与无认证只读 `/api/ai/models=401` 双门禁，禁止 Guest 数据副作用和宽泛 4xx/5xx 成功判断；
- r8 与 r7 只有激活脚本和契约测试两处差异，Java 21 云端构建 169 项测试全部通过。r8 仅提供控制脚本，
  application release/current 仍是已批准 r7，production 没有重启；
- 最终激活后 `nginx -t` 通过，本机和主机外 `--resolve api.dduo.chat:443:34.150.80.173` 均返回
  `/healthz=200`、`/api/ai/models=401`、TLS verify result 0；原有 `mailhook.series.works=200`，
  `api.series.works=500` 在操作前后未变化；
- 激活证据位于
  `/opt/ai-chat/runtime/cutovers/20260713-v1-mvp-cutover-1/nginx/activation.env`，mode `600`。公开 DNS 仍为
  `119.29.109.190`，因此用户流量尚未进入 GCP；证书续期和旧服务器私钥 `644` 权限是后续安全事项。
