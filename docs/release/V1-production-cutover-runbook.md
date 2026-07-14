# AIDiag / AI-chat V1 生产切换手册

## 1. 目标与不可越过的边界

本手册把已通过验收的 AI-chat、Nacos 3.2.2、Elasticsearch 9.3.2 + IK 从候选状态切换到
GCP 生产状态。切换前不得修改 `api.dduo.chat` DNS，不得停止旧 Nacos 2.1.1 或 Elasticsearch
8.13.4，不得让新旧 AI-chat 的 RabbitMQ listener、scheduler、Flyway 或索引同步同时写入。

Phase A 盘点时 GCP 约 15 GiB 内存、无 Swap，且磁盘使用率已超过 80%，V1 不再复制第二套 Nacos 3
和 Elasticsearch 9。生产复用已验收候选容器与数据卷，通过新的稳定网络别名和独立 least-privilege
身份接入；旧 2.x/8.x 容器保持不变，供旧应用回滚。

## 2. 当前已验证基线

- Nacos 3.2.2 候选：鉴权开启、匿名访问拒绝、配置读取和 `ai-chat-candidate` 注册通过，restart 0；
- Elasticsearch 9.3.2 + analysis-ik 9.3.2：鉴权、IK 中文检索、业务 Mapping、dense vector KNN 通过，restart 0；
- AI-chat 候选：166 项构建测试、真实 Guest SSE、消息持久化、Nacos 注册、Prometheus/Loki/Tempo 关联通过；
- MySQL `ai_agent_db.flyway_schema_history` 已由 production 首启从 V4 升至 V5，outbox 表存在；
- GCP 的 root-owned、mode 600 `/opt/ai-chat/runtime/production.env` 绑定 cutover
  `20260713-v1-mvp-cutover-1` 且已按批准流程确认；production Secret 和最小权限身份已创建，
  `nacos-v3` / `elasticsearch-v9` 稳定别名已生效；
- Phase A 已通过真实 Guest SSE、Prometheus/Loki/Tempo 关联、隐私与精确清理复验，AI-chat 候选已恢复
  `healthy/restart 0`；补齐旧实例恢复门禁及真实生命周期测试后的 release
  `20260714-v1-production-cutover-gate-r7` 已在 GCP
  通过只读 `--prepare`，manifest SHA-256 为
  `d47c4843158fa08b51c9e5c6501d3ff16f22e98edef355796a23249f59091968`；
- monitoring-aware AIDiag release `20260714-v1-production-monitoring-gate-r5` 已在云端激活：production
  七个目标全部健康，candidate 七个目标定义保留，Prometheus 对 root-owned runtime target 目录使用只读挂载；
- Phase B 的 `CONFIRMED=true`、数据库备份、旧 writer 停止与批准证据、candidate 停止、互斥锁和
  production target 激活均已通过；
- AI-chat production 已从首次启动 release r7 升级到 `20260714-v1.0.0-rag-model-policy-r12`，当前为 `healthy/restart 0`，仅绑定
  loopback。GCP Nginx/TLS 已指向新 AI-chat，DNS 已切到 `34.150.80.173` 并完成主机外真实域名验证。旧线上实例已经停止，
  只能通过 root-only 恢复包回退。
- 旧服务器 `119.29.109.190` 的 ED25519 指纹已由用户确认，公钥登录已通过。线上 Jar SHA-256 为
  `3cbee08e04cf10d04ea62ab5577df1e9f0f55c7c813cc199fcfdb81247440688`；但 systemd 引用的启动脚本、
  环境文件和 PID 文件已经丢失，旧 `ExecStop` 也存在历史失败记录。直接停服将失去可靠的原路启动能力，
  因此已在用户确认后创建 cutover id `20260713-v1-mvp-cutover-1` 的 root-only 恢复包。恢复包 Jar 哈希与
  运行中 Jar 一致，权限、PID 与 8080 归属复核通过。Phase B 中旧 JVM 因 RabbitMQ shutdown hook 阻塞，
  在用户明确确认后使用 `SIGKILL` 停止；PID、8080 和 Java 中间件连接均已消失。恢复包包含进程环境，
  只能保留在旧服务器 root 私有目录，不得下载、打印或提交 Git。
- 旧 Java 停止前仍指向 `34.150.46.227`：Elasticsearch 9200 有连接，而 MySQL、Redis、RabbitMQ、
  Nacos/gRPC 多个连接处于 `SYN-SENT`，`/actuator/health` 超时。因此恢复包只提供启动能力，恢复后仍需
  做业务探测，不能将“进程启动”当作“回滚成功”。
- Phase A 盘点时 GCP 可用内存约 5.2 GiB、可用磁盘约 13.3 GiB（使用率 83%）。`api.dduo.chat` 的
  Nginx server block 与 TLS 已配置，但证书将于 2026-08-12 到期，长期运行前必须补续期流程。
- 当前公共 DNS 仍解析到已停止的旧服务器 `119.29.109.190`，公开 API 处于维护不可用状态。不得因为 GCP
  容器健康而宣称外部服务已恢复，也不得在没有新确认时修改 Nginx 或 DNS。

## 3. 分阶段切换

### Phase A：维护窗口前准备（无业务切换）

1. 清理无引用镜像/发布归档前先做磁盘清单；目标至少保留 8 GiB，删除必须另行确认；
2. 经确认后创建生产 Nacos/Elasticsearch 独立用户，权限与候选相同但凭据必须不同：

   ```bash
   NACOS3_BASE_DIR=/opt/nacos3-candidate-clean2 \
   ELASTICSEARCH9_BASE_DIR=/opt/elasticsearch9-candidate \
   AIDIAG_V1_PRODUCTION_IDENTITIES_CONFIRMED=PROVISION_DISTINCT_PRODUCTION_IDENTITIES \
     ./scripts/cloud-provision-v1-production-identities.sh /opt/aidiag/releases/<release>
   ```

   脚本只新增/验证身份和文件 Secret，不重启容器、不修改网络别名。失败后保留同一组受管 Secret，
   可安全重试；如果目录不是由脚本创建则拒绝接管。`NACOS3_BASE_DIR` / `ELASTICSEARCH9_BASE_DIR`
   必须与当前容器实际挂载的 Secret 根目录一致，否则脚本 fail-closed，防止恢复候选后误用旧运行目录；
3. 停止 AI-chat 候选后，使用带精确确认门禁和失败回滚的脚本重新创建已验收 Nacos 3 / Elasticsearch 9，
   使其同时具有候选别名和 `nacos-v3` / `elasticsearch-v9` 稳定别名：

   ```bash
   NACOS3_BASE_DIR=/opt/nacos3-candidate-clean2 \
   ELASTICSEARCH9_BASE_DIR=/opt/elasticsearch9-candidate \
   AIDIAG_V1_STABLE_ALIASES_CONFIRMED=PROMOTE_VERIFIED_CANDIDATES_TO_STABLE_ALIASES \
     ./scripts/cloud-promote-v1-stable-aliases.sh /opt/aidiag/releases/<release>
   ```

   脚本按 Nacos、Elasticsearch 顺序处理，每一步都等待 health、restart 0 和原候选验收脚本通过；
   任一步失败会使用容器 Compose label 记录的原 release 重新创建已触碰的候选，数据卷不会删除；
4. 安装 root-owned `production.env`，保持 `AICHAT_PRODUCTION_CONFIRMED=false`，填写唯一
   `AICHAT_CUTOVER_ID`；
5. 执行 AI-chat `cloud-preflight-production.sh --prepare`。它只读检查，不构建、不启动、不修改 DNS。
6. 激活包含 production 双目标定义的 AIDiag release。此时不要创建 production target 文件，先在候选仍运行时验证
   candidate 的 1 个 metrics target、1 个应用探针和 5 个真实依赖探针全部健康，且 production target 为 0：

   ```bash
   chmod 700 scripts/*.sh
   ./scripts/cloud-activate-release.sh /opt/aidiag/releases/<release>
   ./scripts/cloud-verify-ai-chat-monitoring.sh --candidate-only
   ```

   candidate 探针必须指向 `nacos-v3-candidate:8848` 和 `elasticsearch-v9-candidate:9200`；若仍指向旧
   `nacos` / `elasticsearch`，不得继续。Dashboard 默认仍选择 candidate，production 只作为可选项出现。

完成边界：生产 Secret、稳定别名、资源、端口、Compose 渲染和版本门禁全部通过；candidate 七个目标全部健康、
production target 尚未激活，候选与旧线上继续运行。

### Phase B：维护窗口与停写

> 执行状态（2026-07-14）：已完成。停写证据与数据库备份摘要、批准和 production target 激活均通过；
> 具体证据见 [`2026-07-14-v1-phase-b-c-cloud.md`](../verification/2026-07-14-v1-phase-b-c-cloud.md)。

1. 在进入维护窗口前，把同一不可变 AI-chat release 的 `cloud-control-legacy-writer.sh` 安全传到旧服务器，
   执行 `prepare <cutover-id>`。确认恢复包 mode 700、文件 mode 600、Jar SHA-256 与运行中 Jar 一致，且输出
   `service_mutation=none`。本次 cutover 已完成该步骤；正式停写前仍须确认恢复包存在、权限未漂移且
   `prepared.env` 绑定的 MainPID/Jar/端口仍与运行实例一致。恢复包包含进程环境，禁止下载或提交 Git；
   复核未通过不得停止旧服务；
2. 执行 `cloud-backup-production-database.sh <cutover-id>`，确认 gzip 和 SHA-256 都通过；
3. 在明确维护窗口内执行带精确确认值的 `cloud-control-legacy-writer.sh stop <cutover-id>`。不能使用旧的
   `systemctl stop` 作为唯一依据；必须确认旧 MainPID 不存在、prepare 绑定的 8080 不监听、Java 中间件连接
   消失，并把
   root-only `stopped.env` 复制到 GCP 的
   `/opt/ai-chat/runtime/cutovers/<cutover-id>/legacy-old-writer-stopped.env`，保持 `root:root`、mode 600；
4. 再检查 RabbitMQ consumer、scheduler 和索引写入不再增长；因为当前旧中间件连接本身异常，
   “consumer 为 0”只能作为补充证据，不能替代 PID/端口/连接三项停写门禁；
5. 停止 GCP `ai-chat-candidate`，但保持 Nacos 3、Elasticsearch 9、MySQL、Redis、RabbitMQ、AIDiag 运行；
6. 执行 `cloud-approve-production-cutover.sh ... --confirm-old-writers-stopped`。该参数是人工断言，
   只有实际完成第 3～4 步后才允许使用；脚本还会校验旧服务器停写证据的 cutover id、PID/端口断言，
   强制本次旧站端口为 8080，并校验
   SHA-256，缺少证据时 fail closed；
7. 将 `AICHAT_PRODUCTION_CONFIRMED` 改为 `true`，执行 `--ready` 门禁。
8. `--ready` 通过后、启动 production 前，按同一个 cutover id 激活 production 的 1 个 metrics target、1 个应用探针
   和 5 个依赖探针。脚本会再次验证备份、旧 writer 停止/批准证据、candidate 已停止和当前七个 candidate
   监控定义，并通过互斥锁防止激活与回滚并发执行。目标文件不含凭据；激活后应用探针暂时为 0 是预期状态，
   但 Phase C 必须立即开始：

   ```bash
   AIDIAG_PRODUCTION_MONITORING_CONFIRMED=ACTIVATE_AI_CHAT_PRODUCTION_MONITORING \
     ./scripts/cloud-activate-ai-chat-production-monitoring.sh \
     /opt/aidiag/releases/<release> <cutover-id>
   ```

完成边界：同一时间没有旧生产写端或本地候选写端，数据库可恢复，批准证据绑定当前 release 摘要；Prometheus
已经发现七个 production target，应用未启动造成的短暂 down 状态可解释且可审计。

### Phase C：GCP 生产启动与业务回归

> 执行状态（2026-07-14）：已完成。正式用户登录/刷新、Guest/用户 SSE、管理端、知识库上传、OSS、
> Elasticsearch 9 + IK、向量投影、RAG、模型调用、三信号关联和告警恢复均通过，临时数据已精确清理。

1. 执行 `cloud-activate-production.sh`。首次启动会由 Flyway 将应用库从 V4 升到 V5；
2. production 达到 `healthy/restart 0` 后执行 `./scripts/cloud-verify-ai-chat-monitoring.sh --dual`；该模式要求
   production 七个目标全部健康，并确认 candidate 七个目标定义仍保留，但不要求已停写的 candidate 应用健康；
3. 验证 actuator、登录、Guest/用户 SSE Chat、消息持久化、管理端、模型调用；
4. 上传一个临时知识库文档，验证 `knowledge_base_chunks` 的 IK Mapping、向量写入及 RAG 命中，随后清理；
5. 对同一 `trace_id` 验证 Prometheus 指标、Loki `production-operations.jsonl` 脱敏日志、Tempo Trace 和 AIDiag 服务读取；
6. 验证告警、自动建单与诊断报告链路，并观察至少一个完整告警恢复周期。production 报告的
   `evaluationGroundTruth` 必须是 `UNSPECIFIED`；受控 Ground Truth 只允许出现在 `ai-chat-stage5-lab`。

完成边界：新生产在 loopback 端口工作，真实读写回归通过；此时 Nginx/DNS 仍未改变。

### Phase D：入口切换与外部监控

> 执行状态（2026-07-14）：已完成。DNS 已切到 `34.150.80.173`；Google、Cloudflare、阿里公共 DNS、
> 真实公网 TLS/health/CORS、GCP 三地域 Uptime Check、Email 告警和 30 分钟稳定观察均通过。

1. 为 `api.dduo.chat` 准备至少七天有效且证书/私钥匹配的正式 TLS 文件；自签名证书不能通过门禁；
2. 使用 AI-chat release 中的 `cloud-activate-production-nginx.sh` 原子安装 upstream 到
   `127.0.0.1:18083`。脚本要求 production `healthy/restart 0`、端口仅 loopback、SSE buffering 关闭，
   并在 `nginx -t`、reload 和本机 `--resolve` TLS/upstream 探测任一步失败时撤回新配置；脚本不改 DNS：

   ```bash
   AICHAT_PRODUCTION_NGINX_CONFIRMED=ACTIVATE_AI_CHAT_PRODUCTION_NGINX \
     ./deploy/cloud-activate-production-nginx.sh \
     /opt/ai-chat/releases/<release> <cutover-id>
   ```

3. 通过固定 Host/header 在 GCP 本机和外部网络验证 HTTPS，再修改 DNS；
4. 将 DNS TTL 临时调低后，把 `api.dduo.chat` A 记录切到 `34.150.80.173`；
5. 从独立于 GCP VM 的监控位置对 `https://api.dduo.chat/healthz` 执行 HTTPS 可用性探测，覆盖
   DNS、TLS、Nginx 和 JVM health。GCP VM 默认服务账号缺少读取/创建 Uptime Check 的认证 scope，不能从
   VM 直接创建；需要在 Cloud Shell/Console 使用具备 `roles/monitoring.uptimeCheckConfigEditor`、
   `roles/monitoring.alertPolicyEditor` 和 `roles/monitoring.notificationChannelEditor` 的身份，并至少配置一个
   通知渠道。创建命令基线如下，执行前必须确认 DNS 已切换且 `/healthz` 返回 200：

   ```bash
   gcloud monitoring uptime create aichat-production-https \
     --project=project-18afddc1-7772-4ce6-82b \
     --resource-type=uptime-url \
     --resource-labels=host=api.dduo.chat,project_id=project-18afddc1-7772-4ce6-82b \
     --protocol=https --port=443 --path=/healthz --request-method=get \
     --validate-ssl=true --status-classes=2xx --period=1 --timeout=10 \
     --regions=asia-pacific,europe,usa-oregon
   ```

   Uptime Check 创建后还必须绑定告警策略和通知渠道，不能把“有检查但无人收到告警”视为完成；
6. 观察错误率、P95、RabbitMQ backlog、MySQL/Redis/ES/Nacos、JVM、容器重启和磁盘至少 30 分钟。

完成边界：外部域名、TLS、业务功能和观测链路均通过，旧实例保持停止但暂不删除。

## 4. 回滚矩阵

| 失败时点 | 回滚动作 | 数据注意事项 |
|---|---|---|
| Phase A | 移除新生产身份/稳定别名，候选与旧线上不变 | 无业务写入，无数据回滚 |
| Phase B、生产未启动 | 确认 GCP production 未运行后，用已校验恢复包启动外部旧 AI-chat | 新生产未写入，数据库仍为 V4；旧依赖原本已部分不可达，恢复后必须做业务探测 |
| production 监控已激活、生产未启动 | 保持旧写端恢复完成后，按确认门禁执行监控回滚脚本 | 只移动受管 file_sd 文件到审计目录，不删除时序数据 |
| Phase C、Flyway V5 后 | 停止 GCP production，再启动兼容 V5 的旧应用；若旧应用不兼容，按备份恢复 | 禁止在新旧实例同时写入时恢复数据库 |
| Nginx 已切、DNS 未切 | 恢复 Nginx upstream 并 reload | DNS 无需处理 |
| DNS 已切 | 先恢复 DNS/Nginx，再停止新实例、启动旧实例 | DNS 缓存期间两个入口可能并存，必须保证仅一侧写入 |

首次 GCP 生产没有本机前一 release，`cloud-activate.sh` 无法自动启动外部旧服务器。旧 systemd 的原启动文件
也已经丢失，回滚必须显式使用 `cloud-control-legacy-writer.sh start <cutover-id>` 的 root-only 恢复包；该命令
要求同时确认 GCP production 已停止。不能把“新容器已删除”或“旧端口监听”误认为业务已经恢复。

只有在 `ai-chat-production` 容器已经停止后，才允许撤回 production 监控：

```bash
AIDIAG_PRODUCTION_MONITORING_ROLLBACK_CONFIRMED=ROLLBACK_AI_CHAT_PRODUCTION_MONITORING \
  ./scripts/cloud-rollback-ai-chat-production-monitoring.sh <cutover-id>
```

脚本拒绝在 production 仍运行时移除监控，并把 target 与激活 marker 移入
`/opt/aidiag/runtime/production-monitoring/<cutover-id>/disabled-*`，不直接删除证据；candidate 目标不会被修改。

## 5. Secret 轮换顺序

V1 不在同一窗口一次性轮换所有凭据。推荐顺序为：

1. 新增生产 Nacos/Elasticsearch 身份并验证，不删除候选/旧身份；
2. 切换 AI-chat production 并验证；
3. 分别轮换 JWT、模型、OSS，再验证登录、Chat、上传；
4. 最后处理 MySQL、Redis、RabbitMQ；这些凭据位于 Nacos 配置，必须支持新旧值重叠或安排独立窗口；
5. 稳定观察后再撤销旧身份。

每次只轮换一个依赖，记录“新凭据生效、旧凭据撤销、业务验证、回滚方式”四项证据。Secret 只写入
root-owned 文件或 Nacos，不进入 Git、命令输出、日志和文档。

## 6. 仍需用户确认的动作

- 选择维护窗口并确认旧 AI-chat 可停止；
- 在实际停止旧写端后确认人工断言；
- 允许修改 Nginx、TLS 和 DNS；
- 提供或授权外部监控所在的 GCP Project/通知渠道。

旧服务器 host key、公钥登录、root-only 恢复包、生产 Nacos/Elasticsearch 身份和稳定别名均已确认完成，
不再列为后续待确认动作。

这些动作之前的脚本、release、dry-run 和回滚证据可以先准备；任何确认都不能隐式扩展为删除旧容器、
旧数据卷或旧服务器。
