# AIDiag / AI-chat V1 生产切换手册

## 1. 目标与不可越过的边界

本手册把已通过验收的 AI-chat、Nacos 3.2.2、Elasticsearch 9.3.2 + IK 从候选状态切换到
GCP 生产状态。切换前不得修改 `api.dduo.chat` DNS，不得停止旧 Nacos 2.1.1 或 Elasticsearch
8.13.4，不得让新旧 AI-chat 的 RabbitMQ listener、scheduler、Flyway 或索引同步同时写入。

GCP 当前约 15 GiB 内存、无 Swap，且磁盘使用率已经达到 80%，V1 不再复制第二套 Nacos 3
和 Elasticsearch 9。生产复用已验收候选容器与数据卷，通过新的稳定网络别名和独立 least-privilege
身份接入；旧 2.x/8.x 容器保持不变，供旧应用回滚。

## 2. 当前已验证基线

- Nacos 3.2.2 候选：鉴权开启、匿名访问拒绝、配置读取和 `ai-chat-candidate` 注册通过，restart 0；
- Elasticsearch 9.3.2 + analysis-ik 9.3.2：鉴权、IK 中文检索、业务 Mapping、dense vector KNN 通过，restart 0；
- AI-chat 候选：166 项构建测试、真实 Guest SSE、消息持久化、Nacos 注册、Prometheus/Loki/Tempo 关联通过；
- MySQL `ai_agent_db.flyway_schema_history` 当前成功版本为 V4，V1 production 首启将执行 V5 outbox 迁移；
- GCP 尚无 `/opt/ai-chat/runtime/production.env`、生产 Secret、生产 Compose Project 或本机旧 production release；
- Nginx 与 DNS 尚未指向新 AI-chat，旧线上实例仍是首次切换失败时的唯一应用回滚链。

## 3. 分阶段切换

### Phase A：维护窗口前准备（无业务切换）

1. 清理无引用镜像/发布归档前先做磁盘清单；目标至少保留 8 GiB，删除必须另行确认；
2. 经确认后创建生产 Nacos/Elasticsearch 独立用户，权限与候选相同但凭据必须不同：

   ```bash
   AIDIAG_V1_PRODUCTION_IDENTITIES_CONFIRMED=PROVISION_DISTINCT_PRODUCTION_IDENTITIES \
     ./scripts/cloud-provision-v1-production-identities.sh /opt/aidiag/releases/<release>
   ```

   脚本只新增/验证身份和文件 Secret，不重启容器、不修改网络别名。失败后保留同一组受管 Secret，
   可安全重试；如果目录不是由脚本创建则拒绝接管；
3. 使用新 Compose release 重新创建已验收 Nacos 3 / Elasticsearch 9，使其同时具有候选别名和
   `nacos-v3` / `elasticsearch-v9` 稳定别名；
4. 安装 root-owned `production.env`，保持 `AICHAT_PRODUCTION_CONFIRMED=false`，填写唯一
   `AICHAT_CUTOVER_ID`；
5. 执行 AI-chat `cloud-preflight-production.sh --prepare`。它只读检查，不构建、不启动、不修改 DNS。

完成边界：生产 Secret、稳定别名、资源、端口、Compose 渲染和版本门禁全部通过；候选与旧线上继续运行。

### Phase B：维护窗口与停写

1. 执行 `cloud-backup-production-database.sh <cutover-id>`，确认 gzip 和 SHA-256 都通过；
2. 在旧 AI-chat 服务器停止应用，确认 RabbitMQ consumer、scheduler 和索引写入不再增长；
3. 停止 GCP `ai-chat-candidate`，但保持 Nacos 3、Elasticsearch 9、MySQL、Redis、RabbitMQ、AIDiag 运行；
4. 执行 `cloud-approve-production-cutover.sh ... --confirm-old-writers-stopped`。该参数是人工断言，
   只有实际完成第 2 步后才允许使用；
5. 将 `AICHAT_PRODUCTION_CONFIRMED` 改为 `true`，执行 `--ready` 门禁。

完成边界：同一时间没有旧生产写端或本地候选写端，数据库可恢复，批准证据绑定当前 release 摘要。

### Phase C：GCP 生产启动与业务回归

1. 执行 `cloud-activate-production.sh`。首次启动会由 Flyway 将应用库从 V4 升到 V5；
2. 验证 actuator、登录、Guest/用户 SSE Chat、消息持久化、管理端、模型调用；
3. 上传一个临时知识库文档，验证 `knowledge_base_chunks` 的 IK Mapping、向量写入及 RAG 命中，随后清理；
4. 对同一 `trace_id` 验证 Prometheus 指标、Loki 脱敏日志、Tempo Trace 和 AIDiag 服务读取；
5. 验证告警、自动建单与诊断报告链路，并观察至少一个完整告警恢复周期。

完成边界：新生产在 loopback 端口工作，真实读写回归通过；此时 Nginx/DNS 仍未改变。

### Phase D：入口切换与外部监控

1. 先配置 Nginx upstream 到 `127.0.0.1:18083`，执行 `nginx -t` 后 reload；
2. 通过固定 Host/header 在 GCP 本机和外部网络验证 HTTPS，再修改 DNS；
3. 将 DNS TTL 临时调低后，把 `api.dduo.chat` A 记录切到 `34.150.80.173`；
4. 从独立于 GCP VM 的监控位置执行 HTTPS 可用性探测，覆盖 DNS、TLS、Nginx 和应用；
5. 观察错误率、P95、RabbitMQ backlog、MySQL/Redis/ES/Nacos、JVM、容器重启和磁盘至少 30 分钟。

完成边界：外部域名、TLS、业务功能和观测链路均通过，旧实例保持停止但暂不删除。

## 4. 回滚矩阵

| 失败时点 | 回滚动作 | 数据注意事项 |
|---|---|---|
| Phase A | 移除新生产身份/稳定别名，候选与旧线上不变 | 无业务写入，无数据回滚 |
| Phase B、生产未启动 | 重新启动外部旧 AI-chat | 新生产未写入，数据库仍为 V4 |
| Phase C、Flyway V5 后 | 停止 GCP production，再启动兼容 V5 的旧应用；若旧应用不兼容，按备份恢复 | 禁止在新旧实例同时写入时恢复数据库 |
| Nginx 已切、DNS 未切 | 恢复 Nginx upstream 并 reload | DNS 无需处理 |
| DNS 已切 | 先恢复 DNS/Nginx，再停止新实例、启动旧实例 | DNS 缓存期间两个入口可能并存，必须保证仅一侧写入 |

首次 GCP 生产没有本机前一 release，`cloud-activate.sh` 无法自动启动外部旧服务器。这个限制必须保留在
操作检查表中，不能把“新容器已删除”误认为“旧服务已恢复”。

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
- 允许创建生产 Nacos/Elasticsearch 身份并短暂重建两个已验收候选容器以增加稳定别名；
- 在实际停止旧写端后确认人工断言；
- 允许修改 Nginx、TLS 和 DNS；
- 提供或授权外部监控所在的 GCP Project/通知渠道。

这些动作之前的脚本、release、dry-run 和回滚证据可以先准备；任何确认都不能隐式扩展为删除旧容器、
旧数据卷或旧服务器。
