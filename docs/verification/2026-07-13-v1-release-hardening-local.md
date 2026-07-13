# V1 发布收紧本地验证记录（2026-07-13）

## 结论

AI-chat 已完成 V1 发布候选的本地版本迁移、安全收紧和发布结构重构。后续隔离 Nacos 3.2.2、Elasticsearch 9.3.2 + IK 9.3.2 和新版 AI-chat 私有候选均已通过应用级云端验收；共享 Nacos 2.1.1、Elasticsearch 8.13.4、Nginx 和 DNS 未被替换。当前结论是“私有候选通过、生产切换门禁待完成”，不是“已经正式发布”。

## 已验证版本

| 范围 | 解析结果 |
|---|---:|
| Java | Temurin 21；Maven compiler release 21 |
| Spring Boot | 4.0.7 |
| Spring Cloud | 2025.1.2 |
| Spring Cloud Alibaba | 2025.1.0.0 |
| Nacos Client | 3.1.1 |
| Spring Data Elasticsearch | 6.0.6 |
| Elasticsearch Java Client | 9.2.8 |
| Elasticsearch REST Client | 9.3.1（由 LangChain4j Elasticsearch 引入） |
| 隔离候选 Elasticsearch Server | 9.3.2 + IK 9.3.2；与 9.x client 通过真实验收 |
| 现有共享 Elasticsearch Server | 8.13.4，保持运行，仅作为旧实例回滚链 |
| LangChain4j core | 1.15.1 |
| LangChain4j Spring Boot 4 starter | 1.15.1-beta25 |
| Apache HttpClient 5 | 5.5.2，由 Boot BOM 管理 |

## 本地验证

- Java 21 执行最终 `mvn verify`：166 tests，0 failures，0 errors，0 skipped；可执行 Jar 构建成功。
- `RuntimeLogPrivacyTest` 覆盖源代码中的运行日志调用，禁止用户输入、认证详情、异常原文、文件/OSS 信息、知识库 ID 列表和 Throwable 堆栈进入日志。
- `SearchSecurityTest` 验证未认证请求不能访问测试 seam；生产 `SecurityConfig` 已删除 `/api/test/**` 放行规则。
- Cloud Compose 使用 dummy file secrets 执行静态展开通过；候选和生产模式的端口、网络、写任务和鉴权要求由激活脚本分别校验。
- Maven 依赖树确认 Nacos Client 3.1.1、Spring Cloud Context 5.0.2、LangChain4j 1.15.1/beta25 和 HttpClient 5.5.2 均来自目标 BOM 组合。

## 云端候选验证

- `20260713-v1-rc1` 在构建期发现 `.dockerignore` 未放行 runtime entrypoint；没有创建或替换容器。修复后 entrypoint 以单文件白名单进入 build context。
- `20260713-v1-rc2` 发现 Nacos Client 3 会写 `/opt/aichat/logs/nacos`；只读根文件系统拒绝后健康门禁回滚。修复为 32 MiB、uid/gid 10001、mode 0700 的 tmpfs，没有放宽根文件系统。
- `20260713-v1-rc3` 与 `20260713-v1-rc4` 均能构建 Java 21 镜像，但 9 个 Nacos Data ID 被客户端判定为空，应用因缺失 `spring.r2dbc.username` fail closed；没有使用本地默认凭据绕过。
- 从 GCP 主机直接调用现有 Nacos 2.1.1 HTTP API，9 个 Data ID 均返回 HTTP 200，响应大小为 473～4755 bytes；只记录状态和大小，未读取或归档配置正文。
- 每次失败均未修改 Nginx/DNS、未运行 Flyway、listener 或 scheduler；激活脚本恢复旧 release 并重建旧共享镜像标签。
- 后续 release `20260713-v1-nacos3-es9-ik-candidate-r4` 在隔离 Elasticsearch 9.3.2 + IK 上通过：容器 healthy/零重启、Nacos 同名注册与恢复重连、真实 Guest SSE、MySQL/ES 写入删除、Prometheus/Loki/Tempo 关联和精确清理。
- 完整证据见 [`2026-07-13-elasticsearch9-ik-candidate-cloud.md`](./2026-07-13-elasticsearch9-ik-candidate-cloud.md)。

## AIDiag 云端发布验证

- `20260713-v1-hardening-1` 已由发布脚本激活，`/opt/aidiag/current` 指向对应不可变发布目录；12 个 Compose 服务全部通过启动门禁，发布脚本返回 0。
- AIDiag backend、Grafana、order-service 均返回 HTTP 200，Prometheus `/-/ready` 返回 Ready；所有 AIDiag 容器重启计数为 0。
- Grafana `/api/health` 返回 HTTP 200 且 database 为 `ok`，运行时内存限制为 1610612736 bytes（1536 MiB）。
- Prometheus 查询得到 application、Nacos、MySQL、Redis、RabbitMQ、Elasticsearch 共 6 个 `blackbox-ai-chat` 探针，当前值均为 1。
- Alloy 没有挂载 Docker socket；失败候选写入的受控启动日志已由 Alloy 发送到 Loki，查询 `{service_name="ai-chat-startup",event="ai_chat_startup_failure"}` 返回成功并检索到数据。
- 发布后发现 Grafana 13 会尝试后台安装建议插件，且缺失空的 `plugins`、`alerting` provisioning 子目录会产生启动错误。后续收紧已禁用预装插件后台安装、版本/插件更新检查和匿名统计，并补齐合法空配置；使用相同固定镜像的隔离容器验证健康通过且 `level=error` 为 0。
- 收紧归档 `20260713-v1-hardening-2.zip` 的 SHA-256 为 `006f71d1ec550e41ff92d70548223f31706bf03bc1f731a29ae68abbc8359dce`，对应代码提交 `ff09a0d631e76f51c94d0f9ae2ca50043e71b6e9`；云端哈希复核一致并已激活到 `/opt/aidiag/releases/20260713-v1-hardening-2`。
- 激活后 Grafana 的 Prometheus、Loki、Tempo 数据源健康接口均返回 HTTP 200/`OK`；`ai-chat-break-glass` 仪表盘包含 5 个 panel（`stat`、`timeseries`、`table`、`logs`、`text`），启动日志 `level=error` 为 0，所有 AIDiag 容器重启计数为 0。

## 对抗性审查

1. Nacos Client 3.1.1 不再连接共享 2.1.1；隔离 Nacos 3.2.2 已通过。生产切换仍必须保留共享旧实例回滚链，不能原地覆盖。
2. Nacos 2.1.1 升级到 3.x 会改变数据库结构；必须先备份并使用隔离数据库/端口验证，不能原地试错。
3. Spring Boot 4 默认转向 Jackson 3；当前 RabbitMQ、Redis 和部分 LangChain4j 链路保留 Jackson 2 兼容桥。V1 可使用，但后续升级兼容桥前必须迁移并做消息/缓存兼容测试。
4. LangChain4j Spring starter 仍是 beta 版本；166 项测试和云端 SSE/ES 验收证明当前路径可用，但升级 starter 时仍需重复知识库、向量和流式回归。
5. 候选模式关闭 Flyway、listener、scheduler、索引同步和模型 warmup，只能证明私有候选与受控业务主链；生产验收必须在旧写实例停止后单独执行。
6. Spring Data Elasticsearch 6 使用 Elasticsearch 9 libraries；隔离 9.3.2 + IK 已通过，现有 8.13.4 继续只服务旧实例。不得让新旧应用交叉连接错误主版本。

## 未完成门禁

- 主机外可用性监控（启动早期日志采集已完成）；
- 历史 Secret 逐项轮换；
- Nginx/TLS、维护窗口、生产 Flyway、业务回归、DNS 切换和最终回滚演练。
