# 家用服务器部署覆盖

这些 Compose 覆盖文件用于 9GB 内存的单机部署，目标是保留 AI-chat 与 AIDiag 的必要闭环，而不是复刻云端的全部历史环境。

保留：MySQL、Redis、RabbitMQ、Elasticsearch 9 + IK、AI-chat、AIDiag 核心观测链。

Nacos 3 暂时保留运行，供上一版 AI-chat release 回滚；新版本的 AI-chat 不再依赖它。
只有新版本完成业务验收、回滚窗口结束并核对实际监控目标后，才能另行停止 Nacos。

现有 AIDiag 镜像可通过 `compose.aidiag.catalog.yaml` 加载外部服务目录，无需重建应用。
将已审核的 `src/main/resources/service-catalog/catalog.yml` 放到
`/opt/aidiag-home/runtime/catalog/catalog.yml`（非密钥文件，目录 `0755`、文件 `0644`），
在 AIDiag 原有 Compose 文件之后追加该覆盖，只重建 `aidiag-backend`。
此目录去掉 AI-chat 的 Nacos 依赖；Prometheus 的运行目标由
`activate-ai-chat-home-monitoring.sh` 同步更新。保留原 Compose 命令和目标文件备份供回滚。

不启动：旧 Nacos 2、旧 Elasticsearch 8、Fault Lab、历史 Prometheus/Loki/Tempo 数据与过期 release。

资源上限合计约 7.7GiB；实际运行量通常更低，但仍需保留约 1.5GiB 给 Ubuntu、Docker 与突发缓存。任何服务持续接近其上限时，应先观察 Grafana，再调高单项上限，不要整体放宽。

这些文件不包含 IP、域名、密码、Token 或生产 `.env`。实际 runtime Secret 必须在 `/opt/ai-chat-home/runtime/.secrets` 与 `/opt/aidiag-home/runtime/.secrets` 中以 `700/600` 权限保存。

## AIDiag release 回滚保留

家庭服务器上的 AIDiag 发布使用独立 release 目录。每次发布可在
`runtime/deployment/home/retention/*.env` 写入回滚 release、数据库备份、镜像和
`KEEP_UNTIL_EPOCH`；`cleanup-aidiag-retention.sh` 由
`aidiag-home-release-cleanup.timer` 每日执行，只删除已过期且不是当前运行 release
的条目。脚本会校验路径位于 `/opt/aidiag-home/releases` 或
`/opt/aidiag-home/backups` 下，并在没有活动 backend 容器时停止清理。

## HomeOps 主机采集

`compose.homeops.yaml` 是独立的只读主机采集覆盖，不属于 AIDiag 的诊断业务：

- `homeops-node-exporter` 读取宿主机 `/proc`、`/sys`、根文件系统统计与受限的 systemd 状态，不挂载 Docker socket，也不发布主机端口。
- `homeops-power-collector` 每 15 秒读取电池、交流供电、CPU 与 NVMe 温度，原子写入 node_exporter textfile 目录。
- 两个容器合计内存上限为 88 MiB，且只加入现有 `aidiag-telemetry` 内部网络。
- 当前根文件系统只读挂载是 node_exporter 获取宿主机磁盘容量的必要前提；容器已 `read_only`、移除 capabilities 并启用 `no-new-privileges`，但它仍是需要重点维护的信任边界。

不要单独启动采集器后长期不更新 Prometheus 配置，也不要先加载 `homeops.yml` 告警规则后再慢慢部署采集器，否则会产生预期内的 `HomeServerMetricsUnavailable` 告警。应在维护窗口中完成配置校验、采集器启动、Prometheus reload 和 target 验证。

共用 Prometheus 配置通过 `/etc/prometheus/targets/home-node.json` 的 `file_sd` 发现家庭主机；只有 home Compose 覆盖会挂载 `home-node-target.json`，云端或演示环境没有该文件时不会凭空生成一个离线 target。

`compose.homeops-email.yaml` 与 `alertmanager.homeops.email.example.yml` 是可选邮件配置模板。示例地址使用保留域名 `example.invalid`，不能直接投入使用；SMTP 密码只能写入 runtime secret 文件，不能进入 YAML、Git 或命令行参数。
