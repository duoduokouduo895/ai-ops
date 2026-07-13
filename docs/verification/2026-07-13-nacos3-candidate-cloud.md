# Nacos 3.2.2 隔离候选云端验证记录

日期：2026-07-13

## 结论

隔离 Nacos 3.2.2 候选已经在 GCP `34.150.80.173` 创建并通过基础门禁。它使用独立容器、数据库、数据库用户、Docker volumes 和 Compose Project，仅将端口绑定到 loopback；现有 Nacos 2.1.1 没有被停止、重启或替换。

这项结果证明 Nacos 3 服务端、配置迁移、鉴权和最小权限边界可用，但还不能证明 AI-chat 已完整通过应用级验收。新版 AI-chat 后续启动已能读取 Nacos 配置，随后被独立的 Elasticsearch 主版本兼容门禁阻断并自动回滚，因此服务注册、断线重连和真实 SSE 仍待 Elasticsearch 候选完成后验证。

## 云端隔离边界

- 容器：`nacos-v3-candidate`；
- 镜像：`nacos/nacos-server:v3.2.2`，使用固定 digest；
- 宿主机端口：`127.0.0.1:18848` 和 `127.0.0.1:19848`；
- 独立数据库/用户：`nacos_v3_candidate_clean2`；
- 独立 Compose Project：`nacos3-candidate-clean2`；
- 独立 volumes：`nacos3-candidate-clean2-logs`、`nacos3-candidate-clean2-data`；
- 资源上限：2560 MiB、1.5 CPU、512 PID；只读根文件系统并删除 Linux capabilities；
- `/tmp` 使用 `rw,exec,nosuid,nodev` tmpfs，满足 RocksDB JNI 加载且限制提权与设备访问。

## 验证结果

1. 容器状态为 healthy，restart count 为 0；延迟复验结果保持一致。
2. 迁移后 `config_info=19`、`his_config_info=10`，启动前后行数保持不变。
3. 9 项 AI-chat 必需配置全部非空并逐项记录 SHA-256，不在验证输出中打印配置正文。
4. 仅存在 `DEFAULT_GROUP=18` 与既有 `foo=1`；无内置 Skill 或 AgentSpec group 数据。
5. 空 namespace 行数为 0；关闭 namespace compatible mode 后没有把 public 配置镜像到空 tenant。
6. 匿名配置读取返回 HTTP 403。
7. `aichat-candidate` 仅有配置读取与 naming 读写权限，权限集合与 allowlist 精确一致。
8. 源 Nacos 2.1.1 保持 running，restart count 为 0；源数据库完整备份的 SHA-256 复核一致。
9. AI-chat 候选凭据副本使用宿主机 uid/gid `10001:10001`、mode `0400`，容器内非 root 用户已验证可读；Nacos 源凭据仍保持 root-only `0600`。

## 实施中发现并修复的问题

- Windows 归档中的 `versions.env` CRLF 会使 Linux digest 变量带回车；发布脚本改为 LF fail-fast。
- Nacos 3.2.2 的 RocksDB JNI 需要可执行 `/tmp`；只为独立 tmpfs 开放 `exec`，同时保留 `nosuid,nodev`。
- 官方镜像会导入内置 `skills-data.zip` 与 `agentspec-data.zip`；候选以只读 `/dev/null` 精确屏蔽这两个 bootstrap 文件，避免污染配置表。
- namespace 兼容参数必须作为 JVM system property 传入；使用 `JAVA_TOOL_OPTIONS=-Dnacos.config.namespace.compatible.mode=false`，并把历史空 tenant 规范化为 `public`。
- Docker Compose file Secret 基于 bind mount，`uid/gid/mode` 字段不会在 Docker Compose 中生效；AI-chat 凭据因此改为宿主机预置正确所有权，并增加发布前 fail-closed 校验。

## 回滚与遗留风险

- 两个失败候选已停止并保留证据，没有删除其数据库或 volumes；当前干净候选独立运行。
- 当前候选不替代 Nacos 2.1.1；正式替换需要单独维护窗口、应用级断线重连验证和回滚决策。
- 新版 AI-chat 已通过 Nacos 配置读取，但尚未完成注册、真实登录、SSE 和遥测验收；直接原因是 Elasticsearch 9 client 无法连接现有 8.13.4 server。
- 不应原地升级共享 Elasticsearch。下一步建议创建无共享数据卷、仅私网访问的 Elasticsearch 9 隔离候选，经 Repository、索引和向量检索验证后再继续 AI-chat 门禁。

## 官方兼容性依据

- [Spring Data Elasticsearch 5.5 到 6.0 迁移说明](https://docs.spring.io/spring-data/elasticsearch/reference/migration-guides/migration-guide-5.5-6.0.html)：6.0 起使用 Elasticsearch 9 libraries。
- [Elasticsearch Java client 兼容策略](https://www.elastic.co/docs/reference/elasticsearch/clients/java)：9.x client 支持 9.x server，不支持更早的 8.x server。
- [Elasticsearch 升级准备](https://www.elastic.co/docs/deploy-manage/upgrade/prepare-to-upgrade)：从早期 8.x 升级到 9.x 前应先到 8.19、运行 Upgrade Assistant 并准备快照，因此不对共享 8.13.4 执行原地试错。
- [Docker Compose services / Secrets](https://docs.docker.com/reference/compose-file/services/)：file Secret 的 `uid`、`gid`、`mode` 在 Docker Compose bind mount 实现下不生效。
