# 隔离 Nacos 3.2.2 候选

该目录只用于 Stage 5.5 兼容性门禁，不替换现有 `nacos` 容器，也不修改其数据库。候选使用独立容器、独立数据库、独立 Docker volumes、独立鉴权用户以及仅绑定 loopback 的宿主机端口。

## 安全边界

- 镜像同时锁定 tag 与 digest；
- 使用 Nacos 3.2.2 官方 schema 创建干净数据库，只迁移配置白名单表；
- 将 Nacos 2 的空 namespace 一次性规范化为 Nacos 3 的 `public`，并关闭双向兼容同步；
- 只读遮蔽镜像自带的 `skills-data.zip` 与 `agentspec-data.zip`，避免 V1 未使用的 AI Registry 向 `config_info` 导入内置资源；
- `users`、`roles`、`permissions` 不从 Nacos 2 复制；
- 旧版 `config_info_aggr`、`config_info_beta`、`config_info_tag` 只要存在非空数据就拒绝迁移；
- AI-chat 候选用户仅拥有 `public:DEFAULT_GROUP:config/*` 的读权限，以及 `public:DEFAULT_GROUP:naming/*` 的读写权限；
- AI-chat 专用凭据副本使用 `10001:10001` 和 mode `0400`，因为 Docker Compose 的 file Secret 会保留宿主机所有权，不能依赖 overlay 中的 `uid/gid/mode`；
- 8848/9848 只映射到 `127.0.0.1`，容器同时加入 AI-chat 所需的私有网络；
- Nacos 上游启动脚本会开启 `set -x`，本目录的 wrapper 使用 `BASH_XTRACEFD` 丢弃 xtrace，避免 Secret 进入 Docker 日志；
- 停止脚本不删除数据库、备份、Secret 或 volumes。任何清理动作都需要另行评审与确认。

## 执行顺序

以下脚本必须在不可变 AIDiag release 目录中执行：

```bash
NACOS3_CANDIDATE_CONFIRMED=CREATE_ISOLATED_NACOS3_CANDIDATE \
  ./scripts/cloud-prepare-nacos3-candidate.sh /opt/aidiag/releases/<release>

./scripts/cloud-verify-nacos3-candidate.sh /opt/aidiag/releases/<release>
```

准备脚本会先检查资源、现有容器、端口、网络、数据库冲突和旧表数据，再进行完整逻辑备份。随后下载固定 commit 的官方 schema 并校验 SHA-256，创建独立数据库、迁移白名单数据、启动候选、初始化管理员和 AI-chat 最小权限用户，最后把候选凭据安装到 `/opt/ai-chat/runtime/.secrets/candidate`。验证会再次比较源库与候选库的 `config_info`、`his_config_info` 精确行数，并拒绝空 namespace、`skill_enc.*` 或 `agentspec__enc.*` 数据。

如果保留失败候选并以新数据库、新 volumes 重试，可在执行准备脚本时覆盖 `NACOS3_BASE_DIR`、`NACOS3_DB_NAME`、`NACOS3_DB_USER`、`NACOS3_COMPOSE_PROJECT`、`NACOS3_LOGS_VOLUME` 和 `NACOS3_DATA_VOLUME`。这些名称会固化到候选的 `runtime/candidate.env`，后续 start/stop 会自动沿用，避免误操作旧候选资源。

停止与恢复候选：

```bash
NACOS3_STOP_CONFIRMED=STOP_ISOLATED_NACOS3 \
  ./scripts/cloud-stop-nacos3-candidate.sh /opt/aidiag/releases/<release>

NACOS3_START_CONFIRMED=START_ISOLATED_NACOS3 \
  ./scripts/cloud-start-nacos3-candidate.sh /opt/aidiag/releases/<release>
```

仅当首次准备已经创建数据库和 volumes、但在管理员初始化前失败时，才使用恢复脚本；它会要求认证表仍为空：

```bash
NACOS3_RESUME_CONFIRMED=RESUME_ISOLATED_NACOS3 \
  ./scripts/cloud-resume-nacos3-candidate.sh /opt/aidiag/releases/<release>
```

停止只影响 `nacos-v3-candidate`。当前 `nacos` 2.1.1、AI-chat 旧候选、Nginx 与 DNS 均保持不变。
