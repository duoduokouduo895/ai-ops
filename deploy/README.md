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
- 浏览器 API：`https://ops-api.dduo.me/api/v1/console`；
- 正式网址：`https://ops.dduo.me`；
- 后端发布：禁用。

## 标准操作

所有操作从仓库根目录执行，并使用 `pwsh`：

```powershell
# 构建、检查产物并展示将要发布的动作；不会写入 Cloudflare
pwsh .\deploy\scripts\deploy.ps1 -Target frontend

# 检查线上静态页面和 API CORS preflight；不会写入 Cloudflare
pwsh .\deploy\scripts\check.ps1

# 只有明确要正式发布时才使用
pwsh .\deploy\scripts\deploy.ps1 -Target frontend -ConfirmDeploy

# 先展示回滚说明；不会写入 Cloudflare
pwsh .\deploy\scripts\rollback.ps1 -DeploymentId <成功的生产deployment-id>

# 只有明确要正式回滚时才使用；需要当前进程的 Cloudflare 凭据环境变量
pwsh .\deploy\scripts\rollback.ps1 -DeploymentId <成功的生产deployment-id> -ConfirmRollback
```

正式发布前，入口会：

1. 验证配置只指向 `ops.dduo.me` 和 `ops-api.dduo.me`；
2. 在 `frontend-console` 执行 `npm ci`；
3. 临时设置 `VITE_AIDIAG_API_BASE_URL` 并执行现有 `npm run build`；
4. 检查 `index.html`、`_headers`、`_redirects` 和打包 JavaScript；
5. 确认产物确实包含预期的 API 地址；
6. dry-run 时停在本地，只有 `-ConfirmDeploy` 才调用 Pages Direct Upload。

## Wrangler 版本

`frontend-console` 锁定 `wrangler@4.86.0`。这是根据当前机器的 Node `20.19.5` 选择的兼容版本；以后升级 Wrangler 前先升级并验证 Node.js，再更新依赖锁文件。

## 回滚边界

Pages 回滚只针对成功的生产 deployment，preview deployment 不能作为生产回滚目标。`rollback.ps1` 的真实回滚使用 Cloudflare Pages Rollback API，要求当前进程提供 `CLOUDFLARE_ACCOUNT_ID` 和至少具有 Pages Write 权限的 `CLOUDFLARE_API_TOKEN`；脚本不会打印或持久化 Token。

如果只想人工操作，可直接在 Cloudflare Pages 的 Deployments 页面选择目标 deployment 执行 Rollback；不要删除旧站点或新建同名项目代替回滚。

## 当前尚缺的协助项

这套入口已建立，但要让它真正执行发布，还需要你提供或确认一个非敏感信息：**现有 Cloudflare Pages project name**。不要发送 Token；确认项目名后，我可以继续帮你生成本机 `deploy/deploy.config.json`（不提交 Git），先跑 dry-run，再按你的明确指令正式发布。
