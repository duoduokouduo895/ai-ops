# AIDiag 平台介绍

## 1. AIDiag 是什么

AIDiag 是一个面向 Java 应用的证据驱动型运维与异常诊断平台。它把分散的指标、日志、Trace 和告警组织成 Incident，再生成能说明“发生了什么、证据是什么、哪些结论仍不确定”的诊断报告。

V1 面向个人展示和受控实验，重点证明完整闭环和工程可靠性，不把自己包装成企业级全自动 AIOps。

## 2. 为什么要使用这些组件

初学者可以先记住下面这组关系：

| 组件 | 解决的问题 | 类比 |
|---|---|---|
| Prometheus | 数值是否异常、何时开始、持续多久 | 体温计和趋势图 |
| Loki | 异常发生时程序记录了什么事件 | 病历文字记录 |
| Tempo | 一次请求经过了哪些服务、在哪一步变慢或失败 | 路线轨迹 |
| Alertmanager | 告警如何分组、去重、静默、通知和恢复 | 值班调度员 |
| Blackbox Exporter | 不依赖应用自身，从外部判断端口或 URL 是否可达 | 门外的人敲门 |
| Grafana | 把指标、日志、Trace 和告警放在同一界面 | 运维驾驶舱 |
| PostgreSQL | 保存 Incident、任务租约、证据和报告 | 可审计档案库 |
| Spring AI / DeepSeek | 在固定证据上生成辅助说明 | 受限的分析助手 |

指标负责“发现问题”，日志负责“解释细节”，Trace 负责“还原路径”。单独依赖其中一种信号，很容易误判。

## 3. 平台能回答什么

- AI-chat 当前是否能被 Prometheus 抓取？
- 公网 API、内部 JVM 和中间件端口分别是否可达？
- 最近是否出现模型流超时、R2DBC 获取等待、文档 outbox 积压或搜索失败？
- 同一批告警是否已经聚合为一个 Incident？
- 诊断用了哪些固定查询，哪些查询失败了？
- 主要根因是被 Metric + Log 独立支持，还是只是弱候选？
- Incident 是否恢复，恢复时间是否晚于最新 firing？
- DeepSeek 建议引用了哪些已有证据，是否越过允许的 Runbook action？

## 4. 平台不能承诺什么

- TCP 可达不等于账号、协议、数据和性能都正常；
- 一条告警不等于已经确认根因；
- contract lab 的 20/20 结果不等于真实生产准确率 100%；
- AIDiag 与 AI-chat 同机部署时，不能独立发现整台主机掉电；
- V1 不执行自动修复，也不会自动重放用户聊天请求；
- 未注册的服务或告警会生成证据不足报告，不会让模型自由猜测。

## 5. 主要使用入口

### Grafana 独立故障入口

Dashboard：`AIDiag / AI-chat 独立故障入口`。

它在 AI-chat Admin 无法启动时仍可展示：

- 内部应用 health 探针；
- 公网 HTTPS ingress 探针；
- MySQL、Redis、RabbitMQ、Elasticsearch、Nacos TCP 探针；
- 当前外部告警；
- 最近启动和运行错误；
- 建议的人工排查顺序。

### AI-chat Operations Dashboard

用于服务仍运行时观察模型超时、TTFT、活跃流、R2DBC pool、outbox、搜索结果和脱敏事件。

### Incident 只读 API

- `GET /api/v1/incidents`
- `GET /api/v1/incidents/{incidentId}`
- `GET /api/v1/incidents/{incidentId}/report`
- `GET /api/v1/incidents/shadow-summary`

API 使用独立文件 Secret 的 Basic Auth，浏览器不应直接持有凭据；前端应通过受保护的 Admin BFF 访问。

### Stage 4 人工建议

- `POST /api/v1/incidents/{incidentId}/ai-diagnosis-preview`

该接口使用独立 operator 凭据，只在 Stage 4 显式启用时存在。返回内容永远标记 `automatedRemediation=false`。

## 6. 一次故障的阅读顺序

1. 先看公网 ingress 与内部 application 探针，判断问题在入口层还是 JVM 层；
2. 再看依赖 TCP 探针，判断是否存在明显网络不可达；
3. 查看 Prometheus 告警和相关指标趋势；
4. 从 Incident 打开确定性报告，确认是否为 `SUPPORTED`；
5. 阅读 supporting/opposing evidence 和 limitations；
6. 用 traceId 从 Loki 跳转 Tempo，还原具体请求路径；
7. 只有在报告证据充分且确实需要补充表达时，人工调用 AI 建议；
8. 修复后确认 resolved 通知和探针恢复，而不是只看一次请求成功。

## 7. 安全与隐私

- Secret 使用 Docker file Secret/config tree，不进入环境文件、镜像和 Git；
- Webhook body 有 1 MiB 硬上限和更小的默认配置上限；
- 告警 JSON 持久化前递归脱敏，原文只保留 SHA-256 指纹；
- AI-chat 运维日志不记录 Prompt、回答、userId、conversationId、Authorization 或 Cookie；
- 遥测查询由服务器固定，外部 label/annotation 不参与拼接；
- Stage 4 Prompt 只包含有界摘要和 evidence id；模型输出还要经过二次敏感信息和 allowlist 校验；
- AIDiag、Grafana、Prometheus、management、OTLP 默认不直接公开公网端口。

## 8. 适用场景

适合：

- Java/Spring 项目展示可观测性、可靠任务和 AI 安全边界；
- 小规模服务的受控告警聚合和确定性诊断；
- 学习 Metrics/Logs/Traces/Alertmanager 的完整关系；
- 在不破坏真实业务的隔离实验中验证诊断规则。

不适合直接使用：

- 强 SLA、多地域、高合规生产环境；
- 未经评审的任意命令执行或自动修复；
- 把全量业务日志、聊天内容或个人信息发送给模型；
- 用少量实验样本对外宣传生产准确率。

## 9. 推荐阅读顺序

1. 本文；
2. [`V1-全链路设计与实现.md`](V1-全链路设计与实现.md)；
3. [`AIDiag-核心模块.md`](AIDiag-核心模块.md)；
4. [`AI-chat-接入指南.md`](AI-chat-接入指南.md)；
5. [`其他项目接入指南.md`](其他项目接入指南.md)；
6. [`../AIDiag-V1-实施方案.md`](../AIDiag-V1-实施方案.md) 和 [`verification/`](verification/) 中的逐阶段证据。
