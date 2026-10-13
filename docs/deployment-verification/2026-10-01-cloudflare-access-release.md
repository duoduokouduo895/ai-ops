# 2026-10-01 Cloudflare Access 发布记录

用户授权发布后，已将本地 Access 改造发布到家庭 AIDiag 后端与现有 Cloudflare Pages 项目。
发布后端健康、匿名保护、Pages 静态文件指纹及 API 拒绝边界通过。真实 Cloudflare 账户登录、Inbox 数据读取和退出尚未验收。

## 版本与范围

| 项目 | 本次版本 |
| --- | --- |
| 核验时间 | `2026-10-01T00:23:49Z`，新加坡时间 08:23:49 |
| 正式控制台 | `https://ops.dduo.me` |
| Pages 项目 | `aidiag-console` |
| Pages Production deployment | `b5fdabda-7668-4f0d-bd62-1393ef285c7d`，分支 `main` |
| 部署固定地址 | `https://b5fdabda.aidiag-console.pages.dev` |
| 后端 release | `/opt/aidiag-home/releases/20261001-access-aec5f743b1d5` |
| 后端镜像 | `aidiag-backend:1.1.0-home-20261001-access-aec5f743b1d5` |
| JAR SHA-256 | `aec5f743b1d5a2c77c1ee1e632c070da616f7be451256b9a2915d11435a04adb` |
| Access Team domain | `dduo.cloudflareaccess.com` |
| Access AUD | `1ebdf0d6aeaf41b30dda7430770aecffad2ec6d823f2bb7fa7893b650c64834a` |
| 浏览器 API | 同域 `/api/v1/console` |

前端通过 `deploy/scripts/deploy.ps1 -Target frontend -ConfirmDeploy` 发布，使用锁定的 Wrangler 4.86.0。
Pages 标记的 Source 为 `b2241b7`，本次产物包含未提交的工作区修改，该标记不代表全部已发布代码。
本次未创建 Git commit，也未执行 Git push。

后端按不可变 release 方式发布，只重建 Compose project `aidiag` 的 `aidiag-backend`。
新镜像沿用已运行的 Java 21 与 OpenTelemetry agent 基础镜像，仅替换 JAR。
使用 `compose.console-access.yaml` 替代旧 `compose.console.yaml`，继续叠加原 Stage4、家庭资源与 catalog 覆盖。
发布配置保存在服务器 release 的 `release-settings.sh`，只包含镜像及公开 Access 参数。
现有 DNS、Tunnel 和其他服务未变更。

## 验证结果

- 复用本轮此前通过的 Maven verify、Vitest 与 Playwright 检查；正式前端入口重新执行 `npm ci`、Vue/Functions 类型检查、构建与产物检查，均通过。
- 镜像内 JAR 与本地 SHA-256 一致；新旧 JAR 的 11 个 Flyway 迁移文件名称、内容摘要一致。
- 候选容器通过 health/readiness 和鉴权拒绝检查后，先备份 PostgreSQL，再切换正式后端。候选容器已停止。
- 正式后端 `healthy`、readiness `UP`、restart count `0`、OOMKilled `false`；内存上限 640 MiB、CPU 上限 0.6 保持原值。
- 正式运行模式为 `access`，Team domain 与 AUD 逐项匹配，未挂载控制台 password/bcrypt/audit pepper Secret。
- 本机 Console session、health、incidents、services 的匿名请求返回 401；伪造邮箱头、旧 Session Cookie、无效 JWT 和匿名密码登录 POST 均返回 401。
- 后端容器能获取团队 JWKS；公网 origin Console session 的匿名和无效 JWT 请求返回 JSON 401。
- `ops.dduo.me` 的 `/`、`/login`、`/api/v1/console/session`、`/api/v1/console/incidents` 返回 302，跳转到正确团队的 Access 登录挑战，挑战中的 AUD 匹配。
- 固定 deployment 与 `aidiag-console.pages.dev` 的 `/login` HTML、`/assets/index-DlaJFvim.js` 与本地构建的 SHA-256 相同，证明新版产物已上线。
- 上述 Pages 地址的匿名 session/incidents 返回 401，未知 API 返回 404，POST 返回 405；无效 JWT 返回 401。
- Flyway 正式启动日志校验 11 个迁移、当前版本 11，并明确记录 `No migration necessary`；数据库最后一条迁移为 `11|true`。
- `/actuator/prometheus` 返回 200，`aidiag_database_reachable=1`、`aidiag_diagnosis_backlog=0`。
- 本次检查 AI-chat 为 running/healthy、0 restart、非 OOM。主机可用内存约 3547 MiB，AIDiag 当时约 408/640 MiB。

这些检查不使用生产 Access Cookie 或 JWT，不证明允许账户已成功读取真实事件。

## 数据库备份与回滚

| 回滚材料 | 位置 / 版本 |
| --- | --- |
| 发布前数据库备份 | `/opt/aidiag-home/backups/20261001-pre-access-aec5f743b1d5.sql.gz` |
| 备份检查 | 87,211 bytes，权限 `0600`，`gzip -t` 通过 |
| 旧后端 release | `/opt/aidiag-home/releases/20260913-v1.1.0-home-6c441b7cc285` |
| 旧后端镜像 | `aidiag-backend:1.1.0-home-20260913-6c441b7cc285` |
| 旧 Pages Production deployment | `bbec3f9b-4a6f-411c-92ce-985bb1b039b9` |
| 服务器发布记录 | `/opt/aidiag-home/releases/20261001-access-aec5f743b1d5/DEPLOYMENT_RECORD.txt` |

旧 release、镜像与备份均保留。本次未新增自动删除计划。
新后端 Compose 继续使用旧 release 的 `versions.env`，因此旧 release 仍是运行依赖，不能作为过期回滚目录删除。
现有 cleanup timer 处于 active，但发布前检查发现 cleanup service 权限失败；本次没有扩大范围修复，也未把自动清理声明为正常。

需要回滚时，经该次操作授权后在 PowerShell 执行：

```powershell
# 只恢复原后端镜像和原 Compose 鉴权覆盖，不恢复或迁移数据库
ssh home-server-frp 'bash /opt/aidiag-home/releases/20261001-access-aec5f743b1d5/rollback.sh'

# 先核对旧 Pages deployment；此命令仅 dry-run
pwsh .\deploy\scripts\rollback.ps1 -DeploymentId bbec3f9b-4a6f-411c-92ce-985bb1b039b9
```

Pages 正式回滚可在该项目的 Deployments 页面操作；固定脚本加 `-ConfirmRollback` 时需受控进程中的 Pages Write API 凭据。
本次 Wrangler 使用已有 OAuth 登录完成发布，不读取、打印或保存凭据。
数据库没有版本变更，应用回滚无需恢复备份。旧前端恢复后，若边缘 Access 应用继续保留，仍会先通过 Access 再进行原密码登录。

## 尚未完成的真实验收

1. 用户在自己的浏览器打开 `https://ops.dduo.me`，用被允许的 Cloudflare 账户登录，确认进入 Inbox 并读取事件/指标，无第二次 AIDiag 密码登录。
2. 确认不允许的账户被拒绝，并完成会话过期、退出及重新登录验收。
3. 当前 `pages.dev` 地址仍允许读取静态页面，但 Console API 必须提供有效 JWT。若需这些地址也显示 Access 登录挑战，应另行配置 Pages 的访问保护；本次未修改该策略。

浏览器自动观察登录入口超时，未获得登录页 DOM 或已登录页面证据；不据此判断登录失败。
本次未验证真实告警 Webhook 或 AI 建议业务闭环，也未处理此前独立的 Alertmanager Webhook 401 问题。
