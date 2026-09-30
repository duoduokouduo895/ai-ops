# HomeOps Console

HomeOps 是家庭服务器的独立只读概览，不是 AIDiag 的新页面，也不会占用 `ops.dduo.me`。它复用现有 Prometheus/Grafana/Cloudflare 基础设施，但使用单独的产品入口、Access 策略和数据契约。

## 已由真实服务器确认的边界

- 宿主机是 Lenovo XiaoXinAir-14API 2019，Ryzen 5 3500U，Ubuntu 24.04.4；Linux 可见内存约 9.65 GiB。
- AI-chat、AIDiag 与现有 telemetry 栈已在同一家庭主机运行，因此不再重复部署第二套 Prometheus、Grafana、Loki 或 Tempo。
- SSH 维护走 `本机 → 阿里云 frps → 家庭服务器 frpc`；应用公网流量走 Cloudflare Tunnel。HomeOps 不改变这两条链路的职责。
- 2026-08-11 在不停止现有服务的情况下，断开交流电采集 180 秒：稳定阶段电池侧功耗均值 6.181 W、中位数 6.182 W、范围 5.997–6.356 W。
- 交流供电且电池不充放电时，内核上报的电池功率是 0 W，这只表示电池没有能量流动，不表示整机零功耗。因此页面只能显示带日期的 6.18 W 实测基线，直到接入插座侧电表。

## 数据链路

```text
Ubuntu /proc、/sys、systemd
        │
        ├─ node_exporter（内部网络，无公开端口）
        └─ 电池/温度 textfile collector
                        │
                        ▼
               现有 Prometheus
                        │
                        ▼
             现有 Grafana datasource proxy
                        │  Viewer Service Account
                        ▼
   Cloudflare Tunnel + Access Service Token
                        │
                        ▼
        Pages Function 固定查询与字段收敛
                        │
                        ▼
            HomeOps Vue 只读概览页
```

浏览器不能提交 PromQL，也接触不到 Grafana token、Access service token、Prometheus lifecycle API、Docker socket 或 SSH。Pages Function 只允许三种固定历史范围（6h、24h、7d），为 origin 请求设置 4.5 秒超时，并将单次 JSON 响应限制在 1 MB。

## 页面内容

- 首页先回答“现在是否值得关注”，再显示 CPU、内存、根磁盘、温度、网络、供电与电池。
- 功耗明确区分 `插座实时`、`电池实时`、`实测基线` 和 `暂无数据`，不会把估算值伪装成实时数据。
- 服务区分主机链路、应用、数据服务和观测系统；AIDiag 仍然只负责自己的 incident/diagnosis 语义。
- Pages 边缘仍在线但家庭主机失联时，页面显示 origin 不可确认，而不是把缺失指标显示成 0。
- V1 只读：没有 Shell、容器重启、系统升级、关机或智能插座开关。

## 本地开发

本机默认 Node 20 不能运行当前 Wrangler 4.120.1；本次验证使用 Codex 工作区自带 Node 24，没有修改全局 Node。日常开发请使用 Node 22 或更高版本。

```powershell
cd E:\Java\AI-ops\homeops-console
npm install
npm run dev
```

开发模式默认使用 `src/demo.ts` 的证据化演示快照。设置 `VITE_HOMEOPS_DEMO=false` 后，Vite 页面会请求真实 `/api`，此时应通过 `wrangler pages dev` 启动 Pages Functions。

生产构建与测试：

```powershell
npm run build
npm test
npm run test:functions
npm run types:check
```

## 部署前必须完成的配置（本次没有执行）

1. 在家庭主机维护窗口启动 `deployment/home/compose.homeops.yaml`，并让 Prometheus 加载 `infra/prometheus/prometheus.yml` 与 `infra/prometheus/rules/homeops.yml`。
2. 在 Grafana 创建只读 Viewer Service Account，并为 token 设置合理有效期；不要使用管理员 token。
3. 为 Grafana datasource proxy 建立一个新的专用 Tunnel hostname，并用 Cloudflare Access Service Auth 保护。不要复用 `ops.dduo.me`，也不要直接暴露 Prometheus。
4. 将 `wrangler.jsonc` 中三个 `replace-before-deploy` 值替换为真实的 datasource proxy URL、Access team domain 与 Application AUD。
5. 将 `HOMEOPS_GRAFANA_TOKEN`、`HOMEOPS_ORIGIN_ACCESS_CLIENT_ID`、`HOMEOPS_ORIGIN_ACCESS_CLIENT_SECRET` 作为 Pages secrets 写入，真实值不得写入 `.dev.vars.example` 或 Git。
6. 为 HomeOps 自身的自定义域配置 Access 登录策略。`/api/*` 还会再次验证 `Cf-Access-Jwt-Assertion`，防止直接访问 `pages.dev` 绕过数据保护。
7. 先用预览部署验证，再决定正式域名。仓库和文档中的域名均不替用户做未经确认的假设。

## 邮件告警

本机 Alertmanager 适合发送“主机仍在线、指标异常”的邮件：断开交流电、温度过高、磁盘/内存不足、采集过期以及 `docker`、`frpc`、`cloudflared` 停止。`send_resolved: true` 会在恢复后补发恢复邮件。

推荐策略：

- `critical`：等待 2–3 分钟后立即发送，并发送恢复通知。
- `warning`：持续 5–15 分钟才发送，避免瞬时抖动；同类告警按主机分组。
- 电池健康度 72.1% 是维护信息而非故障，不单独触发即时邮件；断开交流电才是即时异常。
- SMTP 使用单独的应用专用密码，存放在 `homeops_smtp_password` 文件中并保持 `600` 权限。

本机 Alertmanager 无法报告“整台家庭服务器断电”，因为它会一起消失。必须在阿里云节点或独立外部监控上运行轻量 sentinel，从外部检查至少一个家庭主机上的公开健康端点，并由外部 SMTP/邮件服务发送 `DOWN` 与 `RECOVERED`。这条外部失联链不能依赖家庭主机上的 Prometheus、Grafana、AIDiag 或 HomeOps。

## 后续可扩展项

- 接入支持本地局域网读取的计量插座，新增 `homeops_wall_power_watts` 后页面会自动优先显示插座实时功耗。
- 安装并最小权限运行 SMART exporter，加入 NVMe 磨损、介质错误与备用空间；当前服务器尚未安装 `smartctl`，不能伪造 SMART 健康值。
- 增加备份作业完成时间、最后一次可恢复验证、证书到期和公网 DNS/TLS 外部探测。
- 增加每周摘要：平均功耗、月累计耗电、温度分位数、可用率和告警噪声，而不是继续堆首页卡片。
