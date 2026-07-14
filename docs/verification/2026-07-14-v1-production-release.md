# AIDiag / AI-chat V1 正式发布记录（2026-07-14）

## 发布结论

V1 已完成代码、生产部署、DNS 切换和外部入口验收。30 分钟稳定性观察自
`2026-07-14T08:18:46Z` 至 `2026-07-14T08:48:47Z` 完整通过，AIDiag / AI-chat 个人展示版 V1.0.0
正式发布；V1 tag 指向实际部署 commit。

## 不可变发布身份

| 项目 | 发布身份 |
|---|---|
| AIDiag Git commit | `74a4dd15dcb4bed063042c5ab0757c990c9b9796` |
| AIDiag release | `/opt/aidiag/releases/20260714-v1.0.0-74a4dd1` |
| AIDiag source SHA-256 | `53acdc3d3b014f231bfeb5a4f6cb036737bf3fc5896220418942860c411ad06c` |
| AIDiag image digest | `sha256:89b8d5f4be66f9d3c18ff01cd10307f28dd50f98dacae323a5825e54ad1dc54e` |
| AIDiag CycloneDX SBOM SHA-256 | `810bd6057ab2d57141afbccf90fab30d526da4a2c3ce23441b01d22365e54cbf` |
| AI-chat Git commit | `3e86520c6c4c0487030c744195c146d9c69a2ae3` |
| AI-chat release | `/opt/ai-chat/releases/20260714-v1.0.0-rag-model-policy-r12` |
| AI-chat source SHA-256 | `7682526ab4359ae6738bf84c8bad9919b373979bd51eb34eae65f64ee9bb097f` |
| AI-chat image digest | `sha256:406285ce4b412a57527ad8cce5972d96d031073b7a9e8a8cc473f348241fbbaa` |
| Cutover id | `20260713-v1-mvp-cutover-1` |

## 切流和外部验收

- DNSPod 的 `api.dduo.chat` A 记录已从 `119.29.109.190` 切换到 `34.150.80.173`，TTL 600；
- Google、Cloudflare、阿里公共 DNS 和本机 resolver 最终均返回 `34.150.80.173`；
- 不带 `--resolve` 的公网 `https://api.dduo.chat/healthz` 返回 200，remote IP 为 GCP，TLS verify result 0；
- 未认证的 `/api/ai/models` 返回 401，说明 Nginx upstream 正常且鉴权边界未被放宽；
- `https://app.dduo.chat` 返回 200；其到新 API 的 CORS preflight 返回 200，允许既定 Origin、方法、
  `authorization/content-type`、凭证和刷新 token 响应头；
- 证书 CN 为 `api.dduo.chat`，由 Let’s Encrypt R12 签发，有效至 `2026-08-12T23:21:26Z`。

## 外部监控

- GCP Uptime Check `aichat-production-https-rZXjRQLM0jo` 每 60 秒从 Asia Pacific、Europe、USA Oregon
  对 `/healthz` 执行 HTTPS 和证书验证，超时 10 秒，只接受 2xx；
- Email 通知渠道 `AIDiag V1 production alerts` 已创建并启用；
- 告警策略 `AI-chat production HTTPS unavailable` 已启用：至少两个 checker 连续 120 秒低于 1 才触发，
  恢复时发送 CLOSED，30 分钟无数据后自动关闭；
- 通知地址只保存在 GCP Notification Channel，不写入仓库或本文。

## 发布门禁结果

- AIDiag 本地 Java 21 `verify`：50 项测试，0 failure、0 error；10 项 PostgreSQL 条件集成测试因本地无
  测试数据库而跳过；同一不可变源码在云端 Docker build 中再次执行并通过；
- AI-chat 本地和 r12 云端 Java 21 `verify`：176 项测试，0 failure、0 error、0 skip；
- production monitoring lifecycle Linux 测试通过；production `7-up`，candidate `7-retained`；
- AI-chat 完整业务回归覆盖登录/刷新、Guest/用户 SSE、管理端、知识库上传、OSS、ES9 + IK、向量投影、
  RAG、模型路由和精确清理；客户端请求 `chat-fast` 时，RAG 实际路由到 `chat-reasoning`，SSE metadata
  披露实际模型，答案无 tool/box 协议泄漏，`cleanup_remaining=0`；
- AIDiag 与 AI-chat 在切流后均为 `healthy/restart 0`，Prometheus 活动告警为 0。
- 30 分钟窗口内逐分钟公网探针全部为 HTTP 200、remote IP `34.150.80.173`、TLS verify 0；最终 GCP
  Uptime Check 时间序列为 493 pass、0 fail，容器 ERROR/Exception 计数均为 0，磁盘稳定在 85%。

## 回滚与保留项

- DNS 快速回退值仍为 `119.29.109.190`，但旧 JVM 已停止，不能仅改 DNS；回滚必须先按 root-only 恢复包
  恢复旧应用并完成业务探测，且必须维持单写端；
- 数据库 V4 备份、旧 writer 停写证据、Nginx 激活证据和旧 release 均保留，未删除容器、卷或旧服务器；
- GCP 磁盘在发布后约 85%，仍有约 12 GiB。旧 release/镜像清理需要单独盘点和确认，不在发布窗口执行；
- TLS 自动续期、聊天中暴露过的模型 Key 轮换、Alertmanager 重启后状态对账和真实旧站恢复演练是发布后
  P1；这些限制不影响当前单机个人展示版可用性，但不能被表述为企业级高可用。
