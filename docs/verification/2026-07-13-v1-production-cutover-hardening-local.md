# V1 生产切换门禁收紧记录（2026-07-13）

## 审计结论

发布前审计发现以下 P0：

1. AI-chat production 示例仍指向旧 `nacos:8848`，但 Spring Boot 4 / Nacos Client 3 的生产基线是
   已验收的 Nacos 3.2.2；
2. GCP 尚无 production runtime env、生产 Nacos/Elasticsearch 身份或本机旧 production release；
3. 原激活脚本只要求一个布尔确认，没有绑定旧写端停机、数据库备份或具体 release；
4. `ai_agent_db` 当前 Flyway 为 V4，production 首启将执行 V5，首次失败不能由 GCP 自动恢复外部旧实例；
5. 主机约 15 GiB 内存、无 Swap、磁盘使用率 80%，再复制一套 Nacos 3 + Elasticsearch 9 会明显
   压缩应用和观测栈余量；
6. Nacos 候选曾从默认目录恢复到 `/opt/nacos3-candidate-clean2`，原 Phase A 脚本仍默认读取旧目录；
   如果直接执行会使用与活动容器不一致的数据库和 Secret；
7. AI-chat `--prepare` 没有导出 Compose 必需的 `AICHAT_RELEASE`，会在真正门禁前渲染失败。

因此采用“已验收候选受控晋升”的最小方案：同一 Nacos 3 / Elasticsearch 9 容器保留候选别名，
额外增加稳定生产别名；生产使用独立 least-privilege 身份，旧 Nacos 2 / Elasticsearch 8 保持不变。

## 已实现门禁

- production 固定连接 `nacos-v3:8848` 与 `elasticsearch-v9:9200`；
- `--prepare` 只读校验配置、Secret 隔离、版本、IK、稳定别名、资源、端口和 Compose；
- `--ready` 额外要求 AI-chat 候选停止、旧写端停止证据、`ai_agent_db` gzip/SHA-256 备份、
  人工批准和当前 release 内容摘要一致；
- release 必须 root-owned、不可被 group/world 写入且不含 symlink；
- 数据库备份脚本只接受当前 Flyway V4，使用 `--single-transaction` 并在落盘后做 gzip/SHA-256 校验；
- 生产身份脚本需要精确确认字符串，原子创建文件 Secret，Nacos 与 Elasticsearch 权限均按白名单配置；
- 身份创建与别名晋升会核对配置目录和活动容器的 Secret mount；目录漂移时 fail-closed。新生产密码
  固定为无换行的 64 位十六进制，预检也拒绝 multiline Secret；
- 稳定别名晋升脚本要求生产身份已存在且 AI-chat 候选已停止，按中间件分步重建、逐步复验；失败时
  根据当前容器 Compose label 自动恢复原 release，不删除数据库或 volumes；
- AI-chat `--prepare` 从受校验 release 目录名确定并导出 `AICHAT_RELEASE`，不再依赖操作者临时注入；
- 首次生产失败的日志明确提示需要按手册重启外部旧实例，不再误报“已恢复”。

## 验证结果

- AI-chat Java 21 `mvn clean verify`：167 tests，0 failures，0 errors，0 skipped；
- AIDiag Java 21 `mvnw clean verify`：44 tests，0 failures，0 errors，10 个条件式 PostgreSQL 集成测试跳过；
- 5 个新增/修改 shell 脚本在 GCP 执行 `bash -n` 通过；主机未安装 shellcheck；
- 修改后的 Nacos 3 / Elasticsearch 9 Compose 使用云端真实 runtime env 执行 `docker compose config --quiet` 通过；
- 未提供确认字符串时，生产身份脚本以 64 拒绝；缺少 production env 时，生产预检以 67 拒绝；
  前后均不存在生产 Secret 目录，验证过程无状态修改；
- 随后已从已校验 release 安装 root-owned、mode 600 的 `production.env`，固定 cutover id 并保持
  `AICHAT_PRODUCTION_CONFIRMED=false`；`--prepare` 以 68 在首个缺失生产 Secret 处拒绝，未启动容器；
- Phase A 首次身份创建因旧默认 Nacos 运行目录而失败；确认 Nacos/Elasticsearch 均无半成品身份后，
  修复活动 mount 绑定，并仅原子去除两份尚未使用生产密码的末尾换行，随机值本身未更换；
- 生产身份创建与幂等重跑通过：Nacos 仅有 config read、naming read/write 两项权限；Elasticsearch
  对 allowlist 外索引返回 403；
- 停止 AI-chat 候选后，Nacos 3 与 Elasticsearch 9 已增加 `nacos-v3` / `elasticsearch-v9` 稳定别名；
  两者重建后均为 `healthy/restart 0`，旧 Nacos 2 / Elasticsearch 8 restart 仍为 0；
- AI-chat 候选恢复后真实 Guest SSE 返回 200，事件包含 metadata/status/message，持久化 2 条消息；
  Prometheus target 为 1、Tempo trace 返回 200、Loki 命中同一 trace，Prompt/回答未进入遥测，临时
  Guest、conversation、message、Redis/JTI 和 Elasticsearch 派生数据精确清理通过；
- 修复 AI-chat release 变量后，`20260714-v1-production-cutover-gate-r2` 的只读 `--prepare` 通过，
  manifest SHA-256 为 `c9a7e2bdd2873b7a29ae266e7f7cfea65c8fb78bd0f6e3a539cbe6befe515ae9`；
- Phase A 非敏感证据已保存到
  `/opt/aidiag/evidence/v1-phase-a/20260713T221201Z.env`（root:root、mode 600）；Nginx/DNS 未修改，
  production Project 仍不存在，`AICHAT_PRODUCTION_CONFIRMED=false`。
- 2026-07-14 对旧服务器完成只读盘点：线上 Jar SHA-256 为
  `3cbee08e04cf10d04ea62ab5577df1e9f0f55c7c813cc199fcfdb81247440688`，但 systemd 引用的启动脚本、
  环境文件和 PID 文件已经丢失，历史 `ExecStop` 也曾失败；旧 JVM 的 MySQL、Redis、RabbitMQ、Nacos/gRPC
  连接处于 `SYN-SENT`，`/actuator/health` 超时。旧进程存活不能证明业务健康，也不能直接安全停服；
- 新增 `cloud-control-legacy-writer.sh`：`prepare` 原子创建 root-only Jar/命令行/进程环境恢复包且不停止服务，
  `stop` 只对已捕获 MainPID 发 `SIGTERM` 并验证 PID、prepare 绑定的应用端口和 Java 中间件连接消失，
  生产审批仍强制旧端口为 8080；`start` 要求确认 GCP
  production 已停止后用临时 systemd unit 恢复。批准脚本现在必须校验从旧服务器复制来的 mode 600
  `legacy-old-writer-stopped.env` 并记录其 SHA-256；
- Java 21 `clean verify` 共 169 项，0 failures、0 errors、0 skipped；两个新增/修改 Shell 在 GCP
  `bash -n` 通过，Markdown 围栏与两个仓库 `git diff --check` 通过；
- `20260714-v1-production-cutover-gate-r6` 源码包 SHA-256 为
  `fc5266a21e53b790ba8f60c08faf29ec73342408821383f5520a2e88c6fafac2`，已作为新目录上传 GCP，后被 r7
  取代，不作为正式切换候选。
  首次附加检查因 PowerShell 管道 BOM 未正确启用外层 `set -e`，该次状态输出被明确作废；随后使用无 BOM
  编码重新执行 fail-fast 检查：`--prepare`、Compose 渲染、candidate `healthy/restart 0`、candidate 监控
  7/7 全部通过，production Project 不存在，AIDiag 活动 release 仍为 r4，未改变流量或运行服务；
- r6 云端 release manifest SHA-256 为
  `91725f388273482092211153784963069d971cffd5e89e02fd9cf5a124efa5c2`；
- 对恢复脚本继续进行对抗性测试时，GCP 的 8080 已被其他服务占用，测试按预期在创建 mock unit 前拒绝。
  随后将应用端口改为 prepare 时绑定并在 stop/start 间强制一致，production 审批仍固定要求本次旧站端口
  为 8080；生命周期测试自动选择隔离高位端口；
- GCP 真实 systemd 生命周期测试覆盖 MainPID 漂移拒绝、无确认 stop 拒绝、无 GCP 停机确认的 restore
  拒绝、优雅 stop 证据和恢复启动，全部 PASS；临时 unit、端口和目录清理通过；
- `20260714-v1-production-cutover-gate-r7` 源码包 SHA-256 为
  `b37c30428a17f20ab7d47b53401c74b87a84b0de07eefb29cdd9675b872f7288`，云端 release manifest SHA-256
  为 `d47c4843158fa08b51c9e5c6501d3ff16f22e98edef355796a23249f59091968`。r7 在不可变 release 目录中再次
  完成生命周期测试、Compose 渲染、`--prepare`、candidate `healthy/restart 0` 和监控 7/7 验证；
  production Project 与 production target 仍不存在。
- 经用户明确确认，已在旧服务器创建 cutover id `20260713-v1-mvp-cutover-1` 的 root-only 恢复包。
  创建前先核对本地与 GCP r7 的 `cloud-control-legacy-writer.sh` SHA-256 一致；恢复包为
  131877951 bytes，目录为 `root:root`、mode 700，普通文件为 mode 600，恢复 launcher 为 mode 700。
  备份 Jar SHA-256 与运行中 Jar 均为
  `3cbee08e04cf10d04ea62ab5577df1e9f0f55c7c813cc199fcfdb81247440688`；创建时间为
  `2026-07-14T01:12:31Z`。创建前后 `spring_dduo-chat.service` 均为 active、MainPID 均为 670913，
  8080 仍由该 PID 监听，临时上传脚本已删除，且不存在 `stopped.env` / `restored.env`，因此本步骤没有
  停止、重启或切换旧服务；
- 创建恢复包后的独立只读复核再次确认旧服务 active、PID/8080 归属不变；GCP candidate 监控仍为 7/7，
  production target 未激活且 production Compose Project 不存在。

## Phase A 完成边界与尚未执行

Phase A 已完成，旧服务器的 root-only 恢复包也已创建并完成静态校验，但这不代表正式发布。该恢复包没有在
旧服务器实际停止后进行恢复启动验证，因此只能降低、不能消除回滚不确定性。下一步进入维护窗口前必须再次
取得确认，再停止外部旧 AI-chat 写端、生成绑定 cutover id 的停写证据、备份并校验 `ai_agent_db`、设置人工
批准证据并执行 `--ready`。
Flyway V5、production 启动、真实生产登录/SSE/知识库/OSS/RAG 回归、Nginx/TLS、DNS 切换、30 分钟
观察和回滚演练均尚未执行。首次 GCP production 失败仍需要人工恢复外部旧实例。

## 后续 Phase B/C 执行结果

上述“尚未执行”是本文收尾时的历史边界。2026-07-14 后续已完成旧 writer 停写、数据库备份、批准、
production target 激活、Flyway V5、GCP production 启动、Guest SSE、Prometheus/Loki/Tempo 关联和真实告警
恢复闭环。用户登录、知识库上传、OSS/RAG、管理端、Nginx/TLS、DNS、主机外监控和 30 分钟观察仍未完成。
完整执行证据见 [`2026-07-14-v1-phase-b-c-cloud.md`](./2026-07-14-v1-phase-b-c-cloud.md)。
