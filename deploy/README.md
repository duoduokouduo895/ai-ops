# frontend-console 标准发布入口

这套 `deploy/` 只负责：

```text
E:\Java\AI-ops\frontend-console
  -> npm run build
  -> Cloudflare Pages Direct Upload
  -> https://ops.dduo.me
```

它不会发布 AIDiag 后端，不会连接家庭服务器，也不会修改 DNS、Cloudflare Tunnel、Nginx、Docker 或数据库。

## 第一次配置

仓库不提交真实配置。先复制模板：

```powershell
Copy-Item .\deploy\deploy.config.example.jsonc .\deploy\deploy.config.json
```

然后打开 `deploy/deploy.config.json`：

1. 删除 JSONC 注释，使其成为严格 JSON；
2. 只填写你 Cloudflare 账户中**已经存在**的 Pages project name；
3. 不把 API Token、Cookie、密码或其他 Secret 写进文件。

项目名不能从 `ops.dduo.me` 反推出，请在 Cloudflare Pages 控制台查看。发布入口只会使用配置中的现有 project name，不会自动创建新项目。

当前模板已经固定这些发布契约：

- 前端目录：`frontend-console`；
- 构建：`npm run build`；
- 构建产物：`frontend-console/dist`；
- 浏览器 API：同域 `/api/v1/console`，由 Pages Function 转发到受 JWT 校验保护的 origin；
- 登录：Cloudflare Access + Cloudflare 账号，真实 Team domain/AUD 配置在后端；
- 正式网址：`https://ops.dduo.me`；
- 后端发布：禁用。

## 标准操作

所有操作从仓库根目录执行，并使用 `pwsh`：

```powershell
# 构建、检查产物并展示将要发布的动作；不会写入 Cloudflare
pwsh .\deploy\scripts\deploy.ps1 -Target frontend

# dry-run 构建后，只检查产物及现有 Cloudflare 登录，不重复构建或上传
pwsh .\deploy\scripts\deploy.ps1 -Target frontend -PreflightOnly

# Access 模式检查匿名请求进入 Access 登录挑战；不会写入 Cloudflare
pwsh .\deploy\scripts\check.ps1

# 只有明确要正式发布时才使用
pwsh .\deploy\scripts\deploy.ps1 -Target frontend -ConfirmDeploy

# 先展示回滚说明；不会写入 Cloudflare
pwsh .\deploy\scripts\rollback.ps1 -DeploymentId <成功的生产deployment-id>

# 只有明确要正式回滚时才使用；需要当前进程的 Cloudflare 凭据环境变量
pwsh .\deploy\scripts\rollback.ps1 -DeploymentId <成功的生产deployment-id> -ConfirmRollback
```

正式发布前，入口会：

1. 验证配置只指向 `ops.dduo.me` 和同域 API（旧 Session 模式可用原 `ops-api.dduo.me`）；
2. 在 `frontend-console` 执行 `npm ci`；
3. 根据 API 地址设置 `VITE_CONSOLE_AUTH_MODE`，临时设置 `VITE_AIDIAG_API_BASE_URL` 并执行 `npm run build`；
4. 检查静态文件、JavaScript，Access 模式还必须有 `_routes.json` 与编译后的 `_worker.js`；
5. 确认产物确实包含预期的 API 地址；
6. dry-run 时停在本地，只有 `-ConfirmDeploy` 才调用 Pages Direct Upload。

真实配置不会自动覆盖：使用 Access 时将 `apiBaseUrl` 改为 `/api/v1/console`、
`enableCorsCheck` 改为 `false`，匿名页面期望状态改为 `302`。
匿名检查会验证 Location 指向 Cloudflare Access；它不代表真实账户登录和业务验收通过。
启用前必须先完成 [Access 接入配置](../docs/Cloudflare-Access-控制台接入.md)，并发布支持 Access 的后端。

## Wrangler 版本

`frontend-console` 锁定 `wrangler@4.86.0`。这是根据当前机器的 Node `20.19.5` 选择的兼容版本；以后升级 Wrangler 前先升级并验证 Node.js，再更新依赖锁文件。

## 回滚边界

Pages 回滚只针对成功的生产 deployment，preview deployment 不能作为生产回滚目标。`rollback.ps1` 的真实回滚使用 Cloudflare Pages Rollback API，要求当前进程提供 `CLOUDFLARE_ACCOUNT_ID` 和至少具有 Pages Write 权限的 `CLOUDFLARE_API_TOKEN`；脚本不会打印或持久化 Token。

如果只想人工操作，可直接在 Cloudflare Pages 的 Deployments 页面选择目标 deployment 执行 Rollback；不要删除旧站点或新建同名项目代替回滚。

## 当前环境

用户已确认现有 Pages 项目为 `aidiag-console`。本机忽略的 `deploy/deploy.config.json` 已配置同域 API 与 Access 登录挑战检查。
2026-10-01 已通过本入口发布前端及 Pages Function；发布版本、核验与回滚点见
[本次发布记录](../docs/deployment-verification/2026-10-01-cloudflare-access-release.md)。
