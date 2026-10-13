# 家用服务器部署覆盖

新版控制台可使用 `compose.console-access.yaml` 替代根目录 `compose.console.yaml`，
接入 Cloudflare Access + Cloudflare 账号登录，不加载控制台密码 Secret。
该改造已于 2026-10-01 在家庭 AIDiag 后端激活，健康检查通过；真实账号登录仍待用户验收。
版本、验证与回滚点见 [发布记录](../../docs/deployment-verification/2026-10-01-cloudflare-access-release.md)。
所需参数、操作顺序、验收与回滚见
[`Cloudflare Access 接入说明`](../../docs/Cloudflare-Access-控制台接入.md)。

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

ES 使用固定 `-Xms1g -Xmx1g` 堆和 `2g` 容器上限，保留一半空间给堆外内存与文件缓存；关闭未使用的 ML 和 Watcher，保留鉴权、全文搜索与 IK。AI-chat（1280 MiB）和 AIDiag（640 MiB）使用 `-XX:MaxRAMPercentage=60.0`，并在 JVM OOM 时退出。

不运行 Nacos 和 Fault Lab 时，本项目当前服务的内存上限合计约 8.03 GiB；2026-09-30 家服另有上限 768 MiB 的 garment-manager，15 个活动容器合计约 8.78 GiB。上限之和不是实际常驻内存，也不保证宿主机不会 OOM；发布前后必须核对 `MemAvailable`、swap、容器占用和 OOM 状态。规划仍应给 Ubuntu、Docker 与突发缓存预留约 1.5 GiB，其他项目也必须纳入预算，不能整体放宽上限。

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
