# Elasticsearch 9.3.2 + IK 隔离候选

该目录用于 Stage 5.5 的 Elasticsearch 主版本兼容门禁。候选不会复用、挂载或升级现有 Elasticsearch 8.13.4 的容器与数据卷。

## 固定边界

- Elasticsearch server 与 `analysis-ik` 插件均为 `9.3.2`；
- Elasticsearch 官方 amd64 镜像使用 digest 锁定；IK 下载包使用 SHA-256 锁定，镜像构建时同时核对插件描述符；
- 独立容器、独立 config/data volumes、独立 native realm 用户；config volume 允许官方 entrypoint 原子创建 keystore，其余根文件系统保持只读；
- HTTP 只绑定 loopback，AI-chat 通过既有 `middleware-net` 和专用 alias 访问；
- 开启认证但暂不在候选私有 Docker 网络内启用 HTTP TLS；禁止将该端口开放到公网；
- AI-chat 用户只允许访问 `conversations`、`knowledge_base_chunks`、`knowledge_base_embeddings*` 与迁移期 `kb_*` 索引；
- 停止和失败回滚均保留数据卷与证据，任何删除操作需要另行确认。

## 执行

```bash
ELASTICSEARCH9_CANDIDATE_CONFIRMED=CREATE_ISOLATED_ELASTICSEARCH9_CANDIDATE \
  ./scripts/cloud-prepare-elasticsearch9-candidate.sh /opt/aidiag/releases/<release>

./scripts/cloud-verify-elasticsearch9-candidate.sh /opt/aidiag/releases/<release>
```

首次启动在 native user 初始化前失败且脚本已经停止候选时，使用保留数据的恢复入口；它不会删除或重建 Secret：

```bash
ELASTICSEARCH9_RESUME_CONFIRMED=RESUME_ISOLATED_ELASTICSEARCH9 \
  ./scripts/cloud-resume-elasticsearch9-candidate.sh /opt/aidiag/releases/<release>
```

停止与恢复只影响隔离候选：

```bash
ELASTICSEARCH9_STOP_CONFIRMED=STOP_ISOLATED_ELASTICSEARCH9 \
  ./scripts/cloud-stop-elasticsearch9-candidate.sh /opt/aidiag/releases/<release>

ELASTICSEARCH9_START_CONFIRMED=START_ISOLATED_ELASTICSEARCH9 \
  ./scripts/cloud-start-elasticsearch9-candidate.sh /opt/aidiag/releases/<release>
```
