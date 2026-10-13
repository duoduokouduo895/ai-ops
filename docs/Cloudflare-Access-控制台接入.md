# AIDiag 控制台：Cloudflare Access + Cloudflare 账号

本改造已于 2026-10-01 发布到家庭 AIDiag 后端与现有 Pages 项目 `aidiag-console`。
后端健康、匿名鉴权与前端产物检查通过；真实 Cloudflare 账户登录仍待用户验收。
发布版本、检查结果和回滚点见 [本次发布记录](deployment-verification/2026-10-01-cloudflare-access-release.md)。
后续运行本地构建不会自动修改 Cloudflare 账户、DNS、Tunnel 或家庭服务器，也不会自动发布。

## 访问链路

```text
浏览器 → ops.dduo.me 的 Access 应用 → Cloudflare 账号验证
       → Pages 静态控制台 + /api/v1/console/* Pages Function
       → ops-api.dduo.me 的现有 Tunnel → 家庭 AIDiag
       → 后端验证原始 Access JWT → 只读投影 API
```

浏览器仅请求同域 API，不再在 AIDiag 中输入用户名或密码。Pages Function 只转发已知只读路由，
只向固定的 `https://ops-api.dduo.me` 发请求，不转发浏览器 Cookie、Basic Auth 或邮箱身份头。
后端验证 `Cf-Access-Jwt-Assertion` 的 RS256 签名、issuer、应用 AUD、exp、nbf、iat、type、sub 和 email。
邮箱身份来自已验证 JWT；旧 Session Cookie 和单独的邮箱头不能登录。

Access 模式不创建本地 Session，不依赖 BCrypt 密码或 audit pepper，不需要数据库迁移。
退出登录访问 `/cdn-cgi/access/logout`；它会撤销该 Zero Trust 团队下的 Access 会话，
包括其他 Access 应用，但不会退出用户的整个 Cloudflare 管理账户。

## 本次环境参数（2026-10-01）

| 参数 | 值 |
| --- | --- |
| 现有 Pages 项目名 | `aidiag-console` |
| 控制台域名 | `ops.dduo.me` |
| Zero Trust Team domain | `dduo.cloudflareaccess.com` |
| Access 应用名 | `AIDiag Console` |
| Access 应用 AUD | `1ebdf0d6aeaf41b30dda7430770aecffad2ec6d823f2bb7fa7893b650c64834a` |

参数由用户提供；控制台截图确认现有 Pages 项目绑定 `ops.dduo.me`，
Access 应用关联 `Allow My Cloudflare Account` 策略。该策略允许所选账户成员；
只允许本人时，应在策略中增加本人邮箱 Require 条件。
Team domain 的公开 JWKS 端点已读取到 RSA 公钥，正式后端可访问该端点。
线上 Access 登录挑战及正式后端配置的团队域、AUD 均与上述参数一致；真实登录及令牌签发仍待用户验收。
沿用现有 Pages 项目，不新建重复站点。Team domain 不包含 `https://` 或路径；AUD 不是账户 ID 或 API Token。

不要把 API Token、Access Cookie 或 JWT 写入 Git、文档、终端输出或聊天。

## Cloudflare 配置

- 在 Zero Trust 的 Identity providers 中启用 **Cloudflare**，打开 **Restrict to account members**。
- 创建或更新 HTTP Self-hosted Access 应用，覆盖整个 `ops.dduo.me`，包括 `/api/v1/console/*`。
- 应用只启用 Cloudflare 登录方法；Allow 策略使用当前账户的 **Cloudflare Account Member**，
  若账户有多个成员而只供自己使用，再增加自己的确切邮箱 Require 条件。
  不配置 Everyone Allow、Bypass 或用于人工控制台的 Service Auth 策略。
- 建议应用 Session duration 为 8 小时。记下 Team domain 和此应用的 AUD。
- 对 Pages 的 `pages.dev` 地址及 preview 地址也配置 Access 或限制访问。
  它们不应成为绕过身份验证的入口；后端始终要求相同 AUD 的有效人类身份 JWT。
- 保留现有 `ops-api.dduo.me` Tunnel 到后端 loopback 的链路；此版本由后端验证透传 JWT。
  不要给 origin 主机名另加会拦截 Pages 子请求或覆盖 JWT 的独立交互式 Access 登录应用。
  若 origin 已有 Access 策略，先检查实际路由并设计相容的机器访问方式，再启用本改造。
- 公网 origin 只发布所需的 Console API 路径，后端端口仍绑定 loopback；
  不为浏览器发布 Webhook、Actuator、数据库或机器只读接口。

Cloudflare 账户成员策略由 Access 执行；后端接受的是该应用签发的身份 JWT，
JWT 中不包含 Cloudflare 管理权限。注销或撤销策略后，已复制的 JWT 仍可能在过期前通过本地验签，
因此不要设置过长会话，也不要暴露或保存令牌。

## 家庭服务器配置与激活顺序

在正常的不可变 release 流程中使用
`deployment/home/compose.console-access.yaml` **替代** `compose.console.yaml`，
再追加原有家庭资源限制与 catalog 覆盖。不要同时叠加两个控制台鉴权文件。

提供以下公开配置参数，值不需要作为 Secret 保存：

```text
AIDIAG_IMAGE=<已验证的新 AIDiag 镜像>
AIDIAG_CONSOLE_ACCESS_TEAM_DOMAIN=dduo.cloudflareaccess.com
AIDIAG_CONSOLE_ACCESS_AUDIENCE=1ebdf0d6aeaf41b30dda7430770aecffad2ec6d823f2bb7fa7893b650c64834a
```

缺失或格式不合法时后端拒绝启动，不能回退为匿名或账号密码访问。
Webhook 的 Basic Auth、机器读取凭据和 AI 建议操作员鉴权保持独立；
本改造不会修复或改变 Alertmanager Webhook 401。

建议顺序：保存旧部署命令与镜像 → 配好 Access → 校验新后端配置并按原发布流程启动 →
确认匿名 API 被拒绝 → 发布新版前端及 Pages Function → 用真实账户验收。
激活涉及 Cloudflare 与远程生产写操作，需要当前任务的明确发布授权。

## 前端与本地验证

默认构建使用 `access`，浏览器 API 固定为 `/api/v1/console`。
`npm run build` 同时类型检查 Pages Functions，并编译 `dist/_worker.js/index.js`；
这个目录必须随静态文件一起上传。Wrangler 配置中仅保存公开的固定 origin，未新增服务器密码。

```powershell
Set-Location E:\Java\AI-ops
mvn -B -ntp verify
Set-Location frontend-console
npm test
npm run build
npm run test:e2e
```

Vite 开发服务器不运行 Pages Function，也不模拟 Cloudflare 登录；直接打开 `/login` 可预览登录入口。
Playwright 的 API Mock 验证页面、路由、过期提示及退出导航，不能代替真实 Access 登录验收。
后端测试通过真实 RSA 签名验证 JWT 的拒绝边界；它不使用生产凭据。

项目当前锁定的 Wrangler 4.86.0 本地 workerd 最多支持 `2026-05-03`。
本次本地 Pages 运行检查临时传入 `--compatibility-date 2026-05-03`，验证静态深链接和匿名 API 的
401/404/405；仓库生产配置仍为 `2026-10-01`。此模拟不能替代该生产兼容日期下的真实部署验收。

`deploy/deploy.config.example.jsonc` 已填写现有 Pages 项目及同域 API；
本机 `deploy/deploy.config.json` 已切换为 `/api/v1/console`，匿名页面检查接受 Access 的 302，关闭旧跨域登录 CORS 检查。
旧配置保存在 Git 忽略的 `.codex/backups/deploy.config.before-access-20261001-075517.json`；真实部署配置同样不进入 Git。
发布脚本根据 `apiBaseUrl` 选择 Access 或旧 Session 构建。Access 的匿名线上检查只确认
302 指向 Cloudflare Access 登录挑战，不把它视作已登录功能通过，也不抓取受保护的 bundle。

## 真实验收与回滚

- 未登录访问页面与同域 API，应进入 Access 登录挑战；直接访问 origin Console API，应返回 JSON 401。
- 被允许的 Cloudflare 账户成员登录后应直接进入 Inbox，访问事件和指标，无第二次密码登录。
- 未允许的账户不能读取事件；伪造 JWT、邮箱头、错误 AUD、过期 JWT 和旧 Session Cookie 应被拒绝。
- 会话过期后，页面显示重新登录入口；退出应清除应用 Access 会话。
- 确认 Alertmanager 仍通过原机器鉴权路径接入，health/metrics 不受浏览器 Access 模式影响。

回滚后端时恢复旧镜像与 `compose.console.yaml`；前端恢复旧 Pages deployment，
或用 `VITE_CONSOLE_AUTH_MODE=session` 及旧跨域 API 地址构建。
Access 边缘应用仍在时，旧前端会先经过 Access 再进行旧密码登录；
撤销边缘策略是独立的远程操作，不能通过恢复镜像自动完成。

官方参考：
[Cloudflare identity provider](https://developers.cloudflare.com/cloudflare-one/integrations/identity-providers/cloudflare/)、
[JWT 验证](https://developers.cloudflare.com/cloudflare-one/access-controls/applications/http-apps/authorization-cookie/validating-json/)、
[应用 Token](https://developers.cloudflare.com/cloudflare-one/access-controls/applications/http-apps/authorization-cookie/application-token/)。
