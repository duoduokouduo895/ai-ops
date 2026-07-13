# Elasticsearch 9.3.2 + IK 与 AI-chat 私有候选云端验收

> 日期：2026-07-13  
> Elasticsearch release：`20260713-elasticsearch9-ik-candidate-r2`  
> AI-chat release：`20260713-v1-nacos3-es9-ik-candidate-r4`  
> AI-chat commit：`8d92dd5c88b5d5d3a9fab91b8edbc435c6bf44c7`

## 结论

Elasticsearch 9.3.2 + IK 9.3.2 隔离候选、Nacos 3.2.2 应用级接入和新版 AI-chat 私有候选均通过。候选只使用 loopback 与私有 Docker 网络，没有替换现有 Elasticsearch 8.13.4、Nacos 2.1.1、Nginx 或 DNS，也没有开启 Flyway、RabbitMQ listener、scheduler 或后台索引同步。

这证明当前版本组合可以进入生产切换准备，但不等于已正式发布。历史 Secret 轮换、主机外监控、Nginx/TLS、维护窗口写切换、生产业务回归和最终回滚演练仍是发布门禁。

## 版本与隔离边界

- Elasticsearch server：`9.3.2`，官方 amd64 镜像使用 manifest digest 固定；
- `analysis-ik`：`9.3.2`，下载 ZIP 使用 SHA-256 固定，构建期同时核对插件描述符中的 Elasticsearch、Java 和插件版本；
- AI-chat：Java 21、Spring Boot 4.0.7、Spring Data Elasticsearch 6.0.6、Elasticsearch Java Client 9.2.8、LangChain4j 1.15.1；
- 容器：`elasticsearch-v9-candidate`；宿主机仅监听 `127.0.0.1:19200`；
- 独立 volumes：config 与 data 均不复用 Elasticsearch 8；
- 鉴权：独立 `aichat_candidate` 用户，只允许已评审业务索引并拒绝安全系统索引；
- 资源：1 GiB JVM heap、2.5 GiB 容器内存、1.5 CPU、512 PIDs、只读 root filesystem；可写 keystore 仅位于隔离 config volume。

## 基础设施验收

固定验证脚本两次得到一致结果：

- Elasticsearch version `9.3.2`；
- IK version `9.3.2`；
- `ik_smart_gate=PASS`；
- `least_privilege_gate=PASS`；
- `index_mapping_and_search_gate=PASS`；
- `dense_vector_knn_gate=PASS`；
- 候选和现有 Elasticsearch 8 restart count 均为 0；
- 网络暴露为 `loopback-and-private-only`。

首次启动被只读 root filesystem 拒绝创建 `elasticsearch.keystore.tmp`。修复没有关闭只读根文件系统，而是增加独立 config volume，随后使用保留的数据卷恢复并通过门禁。失败数据和证据未在未确认时删除。

## AI-chat 应用级验收

- Docker 镜像内 `mvn verify`：166 tests，0 failures，0 errors，0 skipped；
- 容器 healthy、restart count 0、运行用户 `10001:10001`、只读 root filesystem、2 GiB memory；
- 启动报告确认 Nacos 与 Elasticsearch 均为 UP，ES endpoint 为私网候选；
- `conversations` 由真实应用创建，Mapping 包含 `ik_max_word` 与 `ik_smart`；
- 临时 Guest SSE 返回 HTTP 200，包含 `metadata`、`status`、`message`，并在清理前持久化 2 条消息；
- 应用日志确认真实会话索引写入和删除成功；
- 成功计数器增加，Prometheus `up{job="ai-chat-candidate"}` 为 1；
- 同一 traceId 在 Tempo 命中，Loki 命中 1 条脱敏运维事件；
- 验收后 Guest、conversation、message、JTI、限流键、ChatMemory 和 ES 派生文档均为 0 条残留。

`knowledge_base_chunks` 由 LangChain4j embedding store 在首次 RAG/向量写入时懒创建，不把“启动后尚未触发”误报为失败。其 IK Mapping 由代码契约测试覆盖，dense_vector KNN 和 IK 中文检索由隔离候选基础设施门禁覆盖；生产切换后仍需执行真实知识库上传/检索回归。

## Nacos 应用级恢复验收

初次查询发现实例注册名为硬编码的 `ai-agent-service`，与部署门禁期望的 `ai-chat-candidate` 不一致。AI-chat Compose 已用 `AICHAT_SERVICE_NAME` 同时驱动 Spring application、AIDiag 与 OTel service name，并增加部署契约测试。

修复后：

- `ai-chat-candidate` 恰有 1 个健康临时实例，旧错误名实例为 0；
- 受控停止隔离 Nacos 3.2.2 约 30 秒期间，AI-chat 依靠本地配置缓存保持 UP 且零重启；
- Nacos 恢复后 AI-chat 自动重新注册，两个候选 restart count 均保持 0；
- 现有 Nacos 2.1.1 未停止或修改。

## 对抗性审查

1. 单独断开 `nacos-net` 不能制造真实断线，因为候选还通过 `middleware-net` 共享可达路径。最终改为受控停止隔离 Nacos 容器，避免把错误实验当成恢复证明。
2. 部署契约测试最初在 Docker build 中看不到根目录 Compose；随后又发现 `.dockerignore` 未放行该文件。两次均在替换容器前失败，旧候选保持运行；最终只把 Compose 复制到 build stage，不进入运行镜像。
3. 验收脚本最初把 AIDiag 的 `deepseek-v4-flash` 诊断模型误当作 AI-chat 业务别名。已改为显式稳定别名，默认 `chat-fast`；没有修改 AI-chat Provider 配置。
4. 失败验收必须删除临时 Token/header 目录。固定脚本现已在成功和失败路径都按精确 ID 清理，并删除临时目录；一次旧的 root-only 临时目录已立即清除。
5. 候选 HTTP TLS 当前只在私有网络内关闭。正式生产若威胁模型不允许私网明文，必须增加 TLS/证书信任，不能把 loopback 验收直接视为公网安全配置。
6. Elasticsearch 8 与 9 同时运行约占用 3 GiB 内存；适合短期候选，不应在未设置保留期和磁盘水位告警的情况下长期双跑。

## 剩余门禁

- 逐项轮换曾进入聊天记录或历史配置的 DeepSeek、Nacos、JWT、数据库、中间件与 OSS Secret，并保留旧实例回滚策略；
- 从 GCP 主机外增加 HTTPS/主机可用性监控；
- 维护窗口内执行生产 Flyway、listener/scheduler 切换和真实知识库/OSS/RAG 回归；
- 预置 Nginx/TLS、DNS 回退清单并完成最终回滚演练；
- 归档 SBOM、镜像 digest、验证证据和 V1 tag 后再声明正式发布。

## 参考

- [Elasticsearch Docker 配置与 keystore](https://www.elastic.co/docs/deploy-manage/deploy/self-managed/install-elasticsearch-docker-configure)
- [Elasticsearch Java client 兼容策略](https://www.elastic.co/docs/reference/elasticsearch/clients/java)
- [Spring Data Elasticsearch 5.5 到 6.0 迁移说明](https://docs.spring.io/spring-data/elasticsearch/reference/migration-guides/migration-guide-5.5-6.0.html)
- [IK 分词插件仓库](https://github.com/infinilabs/analysis-ik)
- [Nacos 3 Client OpenAPI](https://nacos.io/en/docs/latest/open-api/)
