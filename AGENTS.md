# AI-ops 家庭服务器上下文

## 目的

本项目负责家庭服务器上的 AIDiag、观测链和 `deployment/home/` 家庭部署覆盖。涉及服务器状态、Docker、FRP、Cloudflare Tunnel、监控、告警、备份、部署或故障时，先使用本文，不要重新从历史会话或随机文件寻找连接方式和服务布局。

普通本地 Java/Vue 开发、单元测试和文档编辑不需要连接家庭服务器。

## 家庭服务器如何远程访问

家庭服务器位于家庭网络/NAT 后面；阿里云云服务器提供公网可达入口，但公网 IP 并不属于家庭主机。远程维护链路是：

```text
当前电脑 OpenSSH
  -> 本机个人 SSH 私钥
  -> 阿里云云服务器 frps
  -> 家庭服务器主动连接出去的 frpc
  -> 家庭服务器 sshd
```

FRP 只提供 SSH 维护，不承载 AI-chat、AIDiag 或 HomeOps 的用户业务流量。

项目不保存公网 IP、FRP 端口、用户名、IdentityFile、Token 或主机指纹。真实连接参数保存在当前电脑的 `~/.ssh/config` 和受控 `known_hosts` 中。

预期 SSH alias：

- `home-server-frp`：远程经阿里云 FRP 进入家庭服务器；
- `home-server-lan`：同一可信局域网内的恢复入口。

标准连接：

```powershell
ssh home-server-frp
```

只有在同一 Wi-Fi 且 FRP 路径故障时才尝试：

```powershell
ssh home-server-lan
```

如果 alias 不存在、Host key 不匹配或密钥认证失败，停止在远程操作前，报告缺失的本机连接条件。不要从仓库、Shell 历史或聊天记录恢复公网参数，不要读取/输出私钥，不要关闭 Strict Host Key Checking。

## 服务器里有什么

以下是家庭环境的仓库基线和历史只读核验；任何“正在运行”结论都必须由本次 SSH 检查确认。

### 宿主机

- Lenovo XiaoXinAir-14API 2019（81NJ）；
- AMD Ryzen 5 3500U；
- Ubuntu 24.04 系列；
- Linux 可见内存约 9.65 GiB，swap 约 4 GiB；
- Samsung 512 GB NVMe；
- 单机、单盘、单家庭网络，资源和故障域有限，不宣称企业级高可用。

### 宿主机系统服务

- `sshd`：维护登录；
- `docker`：应用、中间件和观测组件运行时；
- `frpc`：连接阿里云 `frps` 的 SSH 反向通道；
- `cloudflared`：家庭环境承担公网应用入口时的 Cloudflare Tunnel connector。

### Docker 服务组

- 应用：AI-chat、AIDiag；
- 数据与中间件：MySQL、PostgreSQL、Redis、RabbitMQ、Nacos、Elasticsearch；
- 遥测与告警：Prometheus、Alertmanager、Grafana、Alloy、Loki、Tempo、Blackbox Exporter；
- HomeOps 候选：只读 node_exporter 与电池/温度采集器。

容器名不是稳定契约。使用 Compose project/service label 或实际 Compose 文件发现容器，拒绝依赖历史 release 名称。

### 目录与配置所有者

- `deployment/home/`：家庭服务器 Compose 覆盖、Host 采集和家庭探针；
- `/opt/ai-chat-home/runtime/`：AI-chat 家庭运行数据、日志和受限 Secret 的预期根目录；
- `/opt/aidiag-home/runtime/`：AIDiag 家庭运行数据和受限 Secret 的预期根目录；
- `infra/prometheus/`、`infra/alertmanager/`、`infra/alloy/`、`infra/loki/`、`infra/tempo/`、`infra/grafana/`：观测配置；
- `homeops-console/`：独立只读家庭状态中心实现；仓库实现不等于本次已部署。

不得读取、复制或打印 `.secrets`、`.env` 的值。检查 Secret 时只验证文件存在、所有者、权限和非空状态，不输出正文。

## 首次连接后的只读基线

先确认目标主机身份，再运行：

```bash
hostnamectl
uptime
free -h
df -hT /
df -ih /
systemctl --failed --no-pager
systemctl is-active ssh docker frpc cloudflared
docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Image}}'
```

只查询必要字段，不输出完整 `docker inspect`、容器环境变量、Compose 渲染正文或 Secret 路径内容。

## 根据服务器内容排错

### 无法 SSH

1. 确认使用的是 `home-server-frp`，不是业务域名；
2. 检查本机 alias、密钥可用性和已固定 Host key；
3. 同一局域网时用 `home-server-lan` 区分“家庭主机/sshd 故障”和“FRP 路径故障”；
4. LAN 可用而 FRP 不可用时，检查家庭机 `frpc` 的配置验证、最近日志和代理注册状态，再检查阿里云 `frps`；
5. `frpc active`、云端端口监听或 TCP 可达都不能单独证明 FRP 控制面成功；
6. 一次受控 `frpc` 重启仍失败时停止重复重启，转到 `frps` 配置、认证、TLS、版本和安全组诊断。

### 公网应用不可用

1. 先从外部确认 DNS、TLS 和 HTTP 现象；
2. 查清当前该域名实际由 `home` 还是 `gcp` 环境承载，不根据 README 或域名猜测；
3. 如果由家庭服务器承担：检查 `cloudflared`、Tunnel route、loopback 服务和应用鉴权；
4. 如果由 GCP 承担：按 GCP/Nginx/loopback production 契约处理，不把家庭 Tunnel 当成当前路径；
5. Pages 前端在线不代表后端 origin 在线；缺少指标也不能显示为零。

### 主机或 Docker 异常

1. 检查最近重启、负载、内存/swap、磁盘/inode、时间同步、failed units 与 OOM；
2. 检查 Docker daemon；
3. 按 Compose service label 查看健康、restart count、OOMKilled 与必要网络；
4. 只读取故障服务有限时间/行数的日志并脱敏；
5. 不先重启数据库、中间件或整个 Compose project。

### AI-chat 异常

按 `宿主机 -> Docker -> AI-chat health -> MySQL/Redis/RabbitMQ/Nacos/Elasticsearch -> 登录/SSE/RAG -> Metrics/Logs/Trace -> 公网入口` 排查。AIDiag 不可用不应阻断聊天主链。

### AIDiag 异常

按 `Alertmanager Webhook -> Incident -> DiagnosisRun -> Evidence -> Report` 排查。Metric/Log 证据不足时应正确弃权；AI 仅生成受限建议，不执行修复，也不能覆盖确定性报告。

### 整机断电或家庭网络中断

Prometheus、Alertmanager、AIDiag、Grafana 和 HomeOps 与被监控服务同机时会一起消失。本机无法自报整机故障，必须依赖阿里云或其他独立节点的外部 sentinel/公网探针确认 `DOWN` 与 `RECOVERED`。

## Agent 可直接进行的动作

- 使用已有 SSH alias 连接并完成只读基线；
- 读取 systemd/Docker 状态和有限日志；
- 运行 `docker compose config --quiet`、固定 Prometheus/Blackbox/health 检查；
- 生成只读诊断快照，区分本次运行态、仓库预期、历史证据和未知项；
- 给出最小命名服务的修复方案、回滚点和验证方式。

未经当前任务明确授权，不重启/停止服务，不部署，不改 Compose、FRP、Cloudflare、DNS、UFW，不执行数据库/Nacos 迁移、恢复、prune 或 Secret 轮换。一次性迁移脚本（如 Nacos endpoint patch）不得作为日常维护动作或定时任务。

## 家庭服务器事实来源

按以下顺序读取：

1. `deployment/home/README.md`；
2. `deployment/home/` 对应 Compose 覆盖与脚本；
3. `docs/AI-chat-接入指南.md`、`docs/AIDiag-平台介绍.md`；
4. 本次 SSH 只读检查；
5. 带日期的历史验证记录。

当 GCP、家庭部署和历史文档互相冲突时，不自行选择“当前生产”。先报告冲突，通过本次只读检查确认目标；仍不确定时标为 `UNKNOWN`，停止在写操作之前。
