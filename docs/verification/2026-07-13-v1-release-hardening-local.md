# V1 发布收紧本地验证记录（2026-07-13）

## 结论

AI-chat 已完成 V1 发布候选的本地版本迁移、安全收紧和发布结构重构，并执行了云端私有候选验证。验证证明新版镜像可构建，但 Nacos Client 3.1.1 无法从现有 Nacos Server 2.1.1 读取已有配置，因此健康门禁正确拒绝并回滚。当前结论是“代码与镜像通过、Nacos 3 隔离门禁待执行”，不是“已经可以正式发布”。

## 已验证版本

| 范围 | 解析结果 |
|---|---:|
| Java | Temurin 21；Maven compiler release 21 |
| Spring Boot | 4.0.7 |
| Spring Cloud | 2025.1.2 |
| Spring Cloud Alibaba | 2025.1.0.0 |
| Nacos Client | 3.1.1 |
| LangChain4j core | 1.15.1 |
| LangChain4j Spring Boot 4 starter | 1.15.1-beta25 |
| Apache HttpClient 5 | 5.5.2，由 Boot BOM 管理 |

## 本地验证

- Java 21 执行 `mvn verify`：159 tests，0 failures，0 errors，0 skipped；可执行 Jar 构建成功。
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

## 对抗性审查

1. Nacos Client 3.1.1 与当前云端 Nacos Server 2.1.1 的真实候选验证已经失败，不能直接部署为生产；不得继续通过增加本地默认配置掩盖该问题。
2. Nacos 2.1.1 升级到 3.x 会改变数据库结构；必须先备份并使用隔离数据库/端口验证，不能原地试错。
3. Spring Boot 4 默认转向 Jackson 3；当前 RabbitMQ、Redis 和部分 LangChain4j 链路保留 Jackson 2 兼容桥。V1 可使用，但后续 4.x 升级前必须迁移并做消息/缓存兼容测试。
4. LangChain4j Spring starter 仍是 beta 版本；现有 159 项测试只证明本地代码契约，仍需云端真实 Provider、Nacos、Elasticsearch 和 SSE 验收。
5. 候选模式关闭 Flyway、listener、scheduler、索引同步和模型 warmup，只能证明只读启动与遥测；生产验收必须在旧写实例停止后单独执行。

## 未完成门禁

- 隔离 Nacos 3.2.1、鉴权、配置复制与回滚验证；
- 新版 AI-chat 云端候选部署和真实登录/SSE/遥测验收；
- 启动早期日志与主机外可用性监控；
- 历史 Secret 逐项轮换；
- Nginx/TLS、维护窗口、生产 Flyway、业务回归、DNS 切换和最终回滚演练。
