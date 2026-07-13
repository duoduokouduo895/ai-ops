# Nacos 3.2.2 隔离候选本地收紧记录

日期：2026-07-13

## 结论

隔离 Nacos 3.2.2 候选的代码、迁移脚本、鉴权初始化、验证和保留数据的停止/恢复流程已完成本地与云端静态检查。当前状态是“可以在明确确认后创建隔离候选”，不是“Nacos 3 已在云端运行”，更不是“现有 Nacos 2.1.1 已升级”。

## 实现边界

- 固定 `nacos/nacos-server:v3.2.2` 及镜像 digest；
- 仅绑定宿主机 `127.0.0.1:18848/19848`，容器通过 `middleware-net` 访问 MySQL、通过 `nacos-net` 服务 AI-chat 候选；
- 2560 MiB 内存、1.5 CPU、512 PID、只读根文件系统、`cap_drop: ALL`、`no-new-privileges` 和日志轮转；
- 完整备份现有 Nacos 数据库，校验固定 commit 的官方 3.2.2 schema SHA-256 后创建独立数据库；
- 只迁移 6 个配置/namespace 白名单表，旧 auth 表不复制；旧版已移除表非空、namespace 冲突、资源不足、端口/数据库/volume 冲突时全部 fail closed；
- 新建独立管理员和 `aichat-candidate` 用户，后者仅有配置读、服务注册/发现读写权限；
- 候选和生产使用不同的 AI-chat Nacos Secret 目录；
- 任一执行错误会停止新候选并保留证据，不删除备份、数据库、Secret 或 volumes；现有 `nacos` 容器不参与停止或重启。

## 验证结果

1. AI-chat 使用 Temurin 21.0.11 执行 `mvn clean verify`：159 项测试通过，0 failure、0 error、0 skipped，可执行 Jar 构建成功。
2. AIDiag 使用 Temurin 21.0.11 执行 `mvnw.cmd clean verify`：44 项测试，34 项通过、10 项 PostgreSQL 集成测试因本地未提供测试数据库而按既有条件跳过；构建与 CycloneDX SBOM 生成成功。
3. 5 个云端 Shell 脚本均通过 `bash -n`。
4. 在 GCP Docker 主机的临时目录执行 `docker compose config --quiet`，确认镜像 digest、loopback 端口、2560 MiB 限额、`cap_drop` 与 `no-new-privileges` 正确渲染；未 pull 镜像、未创建数据库、未启动容器。
5. AI-chat candidate/production Compose 均通过静态渲染：候选固定指向 `nacos-v3-candidate:8848` 且所有业务写任务关闭，生产固定指向 `nacos:8848`；两者 Secret 路径相互隔离。
6. 激活脚本负向测试证明：候选若误配为 `nacos:8848`，在 Docker build/up 前以 exit 69 拒绝执行。
7. 准备脚本在没有显式确认变量时以 exit 64 拒绝云端写入。
8. 两个仓库执行高风险 Secret 模式扫描，无匹配；`git diff --check` 无空白错误。
9. GCP 主机只读前置审计：源 Nacos 2.1.1 running、restart count 0；`config_info=19`、`his_config_info=10`，3 个已移除旧表和 `config_tags_relation` 均为 0，`NULL tenant_id=0`、空/public namespace 冲突为 0；`MemAvailable=7610 MiB`、`/opt` 可用磁盘约 24.0 GiB，18848/19848 端口空闲。
10. 候选数据库、数据库用户、容器和两个 named volume 均不存在；云端流式下载固定 commit 的官方 schema 得到 SHA-256 `00f3148b049cc6c5cbfbbb8c92ffa608076d3ba1b0bc3ef78d4660d3caf0de5b`，与脚本锁定值一致。
11. 从提交 `bfaaf3a` 生成 AIDiag source archive，SHA-256 为 `e2f69d8fad5cbba8fd7d81c99983b06133dbf531dd2c9ad9024aeb4c9e0a77f4`；从 AI-chat 提交 `235c1c3` 生成候选 archive，SHA-256 为 `f5f3e62618e55815f235b324266627bcc1cd16ed5a57cecea68ad0e4d45851a7`。两个最终包在 Linux 临时目录复验 hash、Shell LF/语法、AIDiag 执行位和高风险 Secret 模式，全部通过。

## 对抗性审查修复

- 删除 AI-chat Nacos 公网地址默认值，避免缺少环境变量时静默连接错误服务器；
- 将候选和生产 Nacos 凭据拆成独立目录，避免轮换一方破坏另一方回滚；
- 兼容旧候选的 legacy Compose 回滚路径，防止新鉴权门禁阻断恢复；
- 不克隆 Nacos 2 schema，不复制旧 `users/roles/permissions`，避免把匿名/弱凭据和旧结构带入候选；
- 对 3.2 已移除表、namespace 冲突、配置空值、Secret 日志泄漏、镜像漂移、公共端口、源 Nacos restart count 增加拒绝门禁；
- 上游 Docker 启动脚本启用 `set -x`，wrapper 将 xtrace 重定向到 `/dev/null`，验证脚本还会用 Secret 原值反查候选日志；
- readiness 失败时显式停止候选，避免 `restart: unless-stopped` 留下持续重启的失败容器；
- API 调用增加连接与总超时，避免鉴权初始化无限挂起；
- RBAC 验证不仅检查“非管理员”，还比对数据库中的唯一角色与两条精确 permission allowlist。

## 尚未执行

- 尚未在云端创建 `nacos_v3_candidate` 数据库或数据库用户；
- 尚未下载/启动 Nacos 3.2.2 镜像；
- 尚未初始化真实候选 Secret 或改写 AI-chat runtime candidate 配置；
- 尚未发布新版 AI-chat 候选，因此服务注册、断线重连、真实登录、SSE、Metric/Log/Trace 和候选回滚仍是下一门禁；
- 现有 Nacos 2.1.1、AI-chat 旧候选、Nginx、DNS 和线上业务均未改变。
