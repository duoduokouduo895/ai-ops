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
- 任何删除数据库、备份、Secret 或 volumes 的清理动作都需要另行评审与确认。

## 当前用法

家庭服务器以本目录的 `compose.yaml` 为基础，叠加
[`deployment/home/compose.nacos.home.yaml`](../../deployment/home/compose.nacos.home.yaml)，容器名为 `nacos-v3`。
Nacos 3 仅为上一版 AI-chat 的回滚窗口保留，新版 AI-chat 已不依赖它（见
[`deployment/home/README.md`](../../deployment/home/README.md)）。

GCP 时期的准备、验证、启停与恢复脚本已随 GCP 下线移除，需要时可在 Git 历史中查阅。

V1 生产不会把 `nacos` 旧别名改指向 3.x。维护窗口内使用本文件重新创建已验收候选后，
同一容器会额外获得 `nacos-v3` 稳定别名；候选仍使用 `nacos-v3-candidate`。这样生产和候选可以使用
不同身份，同时旧 Nacos 2.1.1 回滚链不会被覆盖。重新创建会造成 Nacos 3 候选短暂不可用，
必须在 AI-chat 候选停止后进行。
