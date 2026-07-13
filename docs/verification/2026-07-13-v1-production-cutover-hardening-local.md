# V1 生产切换门禁收紧记录（2026-07-13）

## 审计结论

发布前审计发现以下 P0：

1. AI-chat production 示例仍指向旧 `nacos:8848`，但 Spring Boot 4 / Nacos Client 3 的生产基线是
   已验收的 Nacos 3.2.2；
2. GCP 尚无 production runtime env、生产 Nacos/Elasticsearch 身份或本机旧 production release；
3. 原激活脚本只要求一个布尔确认，没有绑定旧写端停机、数据库备份或具体 release；
4. `ai_agent_db` 当前 Flyway 为 V4，production 首启将执行 V5，首次失败不能由 GCP 自动恢复外部旧实例；
5. 主机约 15 GiB 内存、无 Swap、磁盘使用率 80%，再复制一套 Nacos 3 + Elasticsearch 9 会明显
   压缩应用和观测栈余量。

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
- 首次生产失败的日志明确提示需要按手册重启外部旧实例，不再误报“已恢复”。

## 验证结果

- AI-chat Java 21 `mvn clean verify`：167 tests，0 failures，0 errors，0 skipped；
- AIDiag Java 21 `mvnw clean verify`：44 tests，0 failures，0 errors，10 个条件式 PostgreSQL 集成测试跳过；
- 5 个新增/修改 shell 脚本在 GCP 执行 `bash -n` 通过；主机未安装 shellcheck；
- 修改后的 Nacos 3 / Elasticsearch 9 Compose 使用云端真实 runtime env 执行 `docker compose config --quiet` 通过；
- 未提供确认字符串时，生产身份脚本以 64 拒绝；缺少 production env 时，生产预检以 67 拒绝；
  前后均不存在生产 Secret 目录，验证过程无状态修改；
- 线上 Nacos 3、Elasticsearch 9、AI-chat 候选、旧 Nacos 2 和旧 Elasticsearch 8 均未重启，Nginx/DNS 未修改。

## 尚未执行

生产身份创建、稳定别名生效需要短暂重新创建两个候选中间件，属于生产安全配置和维护窗口动作，
必须在用户确认后执行。`--ready`、Flyway V5、production 启动、Nginx/TLS、DNS 和外部监控也尚未执行。
