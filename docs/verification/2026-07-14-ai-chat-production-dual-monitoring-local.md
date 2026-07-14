# AI-chat production 双目标监控本地验证

> 日期：2026-07-14  
> 范围：AIDiag monitoring-aware release 的本地实现与静态门禁；不包含 GCP 云端激活结果。

## 完成范围

- candidate metrics 与 Blackbox 目标保持常驻，依赖地址修正为已验收的
  `nacos-v3-candidate:8848`、`elasticsearch-v9-candidate:9200`；
- production metrics、应用 health 与五个依赖探针使用单一 root-owned Prometheus `file_sd` JSON，
  未激活时不会产生 production 假告警；
- Alloy 独立采集 `production-operations.jsonl`，Prometheus 告警、确定性诊断规则与两个 Grafana Dashboard
  均按 `service_name` 区分 candidate 和 production；
- production 的 `evaluationGroundTruth` 固定为 `UNSPECIFIED`；受控 Ground Truth 只允许用于
  `ai-chat-stage5-lab`；
- 增加绑定 cutover id 的激活、回滚和只读验收脚本。回滚拒绝在 production 容器运行时撤销监控，
  被撤回的 target 与 marker 进入审计目录，不直接删除。

## 本地验证结果

1. 使用 Temurin 21.0.11 执行 `mvnw.cmd clean verify`：构建成功；测试共 49 项，39 项通过、
   10 项 PostgreSQL 集成测试因未设置既有 `AIDIAG_INTEGRATION_TESTS` 门禁而跳过；可执行 Jar 与
   CycloneDX 1.6 SBOM 生成成功。
2. 新增 `ProductionMonitoringContractTests` 4 项全部通过，覆盖动态 production target、candidate
   稳定别名、日志/告警身份、Dashboard selector、激活与回滚确认门禁。
3. Git for Windows `sh -n` 检查全部 `scripts/*.sh` 通过；三个新脚本的缺少确认值/非法参数负向测试均
   按预期返回 exit 64。
4. PyYAML 成功解析 `compose.yaml`、Prometheus 配置和告警规则；Python JSON parser 成功解析两个
   AI-chat Dashboard；全部 Shell 文件不含 CR 字节。
5. Markdown 标题层级、`git diff --check` 和“本次 tracked diff + untracked 新文件”敏感模式扫描通过。

## 云端只读预检

- 当前 Codex 运行环境没有本地 Docker CLI，无法在本机执行 `docker compose config`、Prometheus
  `promtool` 或 Alloy 容器级配置检查；该缺口随后使用 GCP 已固定版本容器完成；
- SSH 使用临时 known-hosts 文件重新握手，服务器 ED25519 指纹与既有确认值一致后才启用公钥认证；
  当前出口 IP 仍为 GCP 防火墙已放行地址，未修改 SSH 或防火墙配置；
- release `20260714-v1-production-monitoring-gate-r1` 已按 SHA-256
  `0471e7ba75b64fb66197ba47c6394cef531d784f3dddb1a938363dd6ddfa281f` 上传并解压到
  `/opt/aidiag/releases/`，但 `/opt/aidiag/current` 仍指向 `20260713-v1-hardening-2`；
- 新 release 的 `docker compose config --quiet`、全部 Shell `bash -n`、Prometheus `promtool check config`
  和 Alloy `validate` 均通过；Prometheus 确认 16 条规则。production file_sd 文件尚不存在的两条 warning
  是未激活设计的预期状态；
- dry-run 后 AI-chat candidate、Nacos 3 与 Elasticsearch 9 + IK 均为 `healthy/restart 0`，production
  Compose Project 不存在，运行中的容器和活动 release 未改变。

## 云端激活与故障修正证据

- 第一次激活在切换活动 release 之前被 Docker build 门禁拦截：构建阶段只复制了 `src/`，导致
  `ProductionMonitoringContractTests` 无法读取其校验的 `compose.yaml`、`infra/` 和 `scripts/` 文件。
  失败后确认 `/opt/aidiag/current` 仍指向 `20260713-v1-hardening-2`，AIDiag、candidate、Nacos 3、
  Elasticsearch 9 均保持健康，production target 仍不存在；
- `Dockerfile` 已只在 build stage 中补充复制契约测试需要的原始部署文件，不把这些文件带入 runtime
  image。修复后 Temurin 21.0.11 `mvnw.cmd clean verify` 再次通过（49 项、0 失败、0 错误、10 项按既有
  PostgreSQL 门禁跳过）；云端 `r2` Docker build 也以相同测试结果通过；
- `r2` 已成功激活且全部 AIDiag 服务通过健康门禁。首次 candidate-only 验收从活动目标 API 确认当前
  7 个目标均健康，但 PromQL 即时查询还返回旧 `elasticsearch:9200` 在 5 分钟 lookback 内的历史序列，
  使按结果条数判断的脚本误报 7 条探针。验收逻辑已收紧为：活动目标集合必须精确匹配，再只对当前
  6 个探针实例校验 `probe_success=1`；没有直接修改 `r2`，而是生成新的不可变 `r3`；
- `20260714-v1-production-monitoring-gate-r3` 发布包 SHA-256 为
  `e9f0825129c409115688bf172451df0b79ea01132f874a40a74e1de36906271c`。云端 Docker build 再次通过
  49 项测试，随后原子激活成功，全部 13 个 AIDiag Compose 服务通过健康等待；
- 激活后 `cloud-verify-ai-chat-monitoring.sh --candidate-only` 返回 PASS：candidate 7/7 目标 up，
  production targets inactive；Prometheus 将 `/opt/aidiag/runtime/prometheus-targets` 只读挂载到容器，
  candidate backend、Prometheus、AIDiag backend 均为 `healthy/restart 0`；
- production target 文件仍不存在，因此本次激活没有提前启动 production 告警或更改 AI-chat 流量。
- Phase B 前进一步收紧激活/回滚门禁：激活必须同时满足 `CONFIRMED=true`、同一 cutover 的数据库备份与
  SHA-256/gzip 校验、旧 writer 停止和批准证据、candidate 已停止、candidate 七个监控定义仍存在；激活与
  回滚通过 root-owned lock file 和 `flock` 互斥。Linux `/tmp` mock 生命周期测试覆盖未确认拒绝、candidate
  运行拒绝、成功激活、幂等重试、production 运行时拒绝回滚和归档回滚，结果 PASS；
- 加固后的 `20260714-v1-production-monitoring-gate-r4` 发布包 SHA-256 为
  `8559ca4274c983b91dd584dcf1b57192621a0f63c7636bd83e563f7bd1bd38df`，已在云端激活。镜像内 49 项
  测试通过，激活后 candidate-only 再次为 7/7 up，三个关键容器均 `healthy/restart 0`；在当前
  `CONFIRMED=false` 状态尝试提前激活按预期以 exit 69 拒绝，真实 production target 仍不存在。

## 后续云端执行状态

- 本文记录的本地与 r4 基线保持为历史证据；后续 Phase B 已完成，production target 已激活，AIDiag r5
  已完成 production 7/7、计划停写 candidate 告警抑制和真实告警恢复周期验收；
- Phase C 的生产启动、Flyway V5、Guest SSE 和遥测核心链路已通过，但完整业务回归、Phase D、主机外
  HTTPS 监控和 V1 发布归档仍未完成；
- 云端结果见 [`2026-07-14-v1-phase-b-c-cloud.md`](./2026-07-14-v1-phase-b-c-cloud.md)，后续仍必须按
  [`V1-production-cutover-runbook.md`](../release/V1-production-cutover-runbook.md) 执行，不能用本地静态验证
  替代云端结果。
