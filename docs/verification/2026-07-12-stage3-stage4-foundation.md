# AIDiag Stage 3 与 Stage 4 基础能力验收

## 1. 验收范围

- 云端主机：`34.150.80.173`；
- 最终 release：`/opt/aidiag/releases/20260712-stage4-4`；
- 源码包 SHA-256：`14e2fff0bdda3a50fb1a40d3ccdb83ae47b752747468a17fcf6e19387fbaa688`；
- 最终数据库版本：Flyway V7；
- Stage 4 运行模式：`AIDIAG_STAGE4_ENABLED=false`，未配置 Provider、模型 Key 或 Operator Secret。

## 2. 迁移前保护

- 迁移前使用 `pg_dump -Fc` 创建 `/opt/aidiag/backups/aidiag-before-stage4-20260712.dump`；
- 备份 SHA-256：`891ed91b52953150681fb9770b93649515c0d6c89036ad2af3a854b8acf5fd5d`；
- 已在 PostgreSQL 容器内通过 `pg_restore -l` 验证归档目录可读取。

## 3. Flyway V6/V7 验收

- Flyway 历史为 `1,2,3,4,5,6,7`；
- 5 条历史错误主要根因已修正，`history_corrections=5`；
- 不满足 `SUPPORTED + 同 diagnosis_run` 的主要根因数量为 `0`；
- `primary_root_cause_id` 与 `report_json.primaryRootCauseCandidateId` 不一致数量为 `0`；
- `trg_diagnosis_report_primary_root_cause` 与 `trg_root_cause_candidate_protect_primary` 均存在；
- `ai_diagnosis_attempt` 已创建，禁用态审计记录数为 `0`。

## 4. 测试结果

- 本地 Java 21 `mvn verify` 通过并生成 CycloneDX SBOM；
- 最终云端镜像构建执行 25 个测试：16 个单元/组件测试通过，9 个数据库集成测试按环境门禁跳过；
- Compose `test` profile 随后连接真实 PostgreSQL 18.4，9/9 集成测试通过；
- Stage 4 服务级测试覆盖：虚构 evidenceId 拒绝、模型不可用降级、超时中断、禁用态不调用模型、独立 Operator 鉴权和 YAML 占位符解析。

## 5. Stage 3 四场景

`scripts/verify-stage3.sh` 最终通过：

| 故障 | Incident | 结果 |
|---|---|---|
| downstream-timeout | `aa9b43c1-60aa-4bcc-affe-c8b9b368c8ca` | firing、SUPPORTED 报告、恢复、RESOLVED |
| database-slow-query | `8653a326-2700-496e-9cdf-e2aec09c9915` | firing、SUPPORTED 报告、恢复、RESOLVED |
| hikari-exhaustion | `4ae561fd-8c7b-421a-8355-4c37d54f66c2` | firing、SUPPORTED 报告、恢复、RESOLVED |
| thread-pool-exhaustion | `257f6efe-2d2e-4a83-a275-dec887152613` | firing、SUPPORTED 报告、恢复、RESOLVED |

验收后：

- 四类活跃 Fault Lab Incident 数量为 `0`；
- `max(faultlab_fault_active)=0`；
- 正常 Order 请求返回 HTTP `201`；
- 四个验收 Incident 均为 `RESOLVED`，报告均为 `SUPPORTED` 且具有主要根因。

## 6. Stage 4 禁用态

- `POST /api/v1/incidents/{id}/ai-diagnosis-preview` 返回 HTTP `404`；
- 禁用态不会注册 Controller、不会调用模型、不会创建审计记录；
- 模型建议与 `diagnosis_report` 分表保存，现有确定性主要根因不会被 Stage 4 改写；
- 真实 Provider 验收仍等待 Provider、模型、数据出境、费用上限及两个文件型 Secret 的决策。

## 7. 发布过程说明

首次 `stage4-1` 启动暴露了重载构造器未显式注入的问题，`stage4-2` 又暴露了 Operator 密码 YAML 自引用问题。两次均发生在后端启动阶段；Flyway V6/V7 已成功提交，PostgreSQL 与遥测组件保持健康。最终通过增加 Spring Bean 装配测试和真实 `application.yml` 解析测试修复，并以不可变 `stage4-4` release 收敛。最终后端健康，最近日志无 `ERROR` 或 `Application run failed`。

## 8. 尚未关闭的边界

- Stage 3 尚未采集 Tempo Trace 作为诊断 evidence，属于必要 P1；
- Stage 4 尚未选择真实模型 Provider，因此不能宣称真实模型链路完成；
- Mockito/Byte Buddy 在 Java 21 测试中提示未来将禁止动态 Agent，自测构建暂不受影响，但升级较新 JDK 前应改为显式测试 Agent；
- `AlertmanagerWebhookController` 仍有 Spring 7 弃用 API 编译提示，应在后续兼容性整理中消除。
