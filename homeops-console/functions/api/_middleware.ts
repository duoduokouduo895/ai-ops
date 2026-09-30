import cloudflareAccessPlugin from '@cloudflare/pages-plugin-cloudflare-access'

export const onRequest: PagesFunction<Env> = async (context) => {
  const domain = context.env.HOMEOPS_ACCESS_TEAM_DOMAIN
  const aud = context.env.HOMEOPS_ACCESS_AUD
  if (!domain || !aud || domain.includes('replace-before-deploy') || aud === 'replace-before-deploy') {
    return Response.json({ error: 'HomeOps Access 尚未配置。' }, { status: 503 })
  }
  const verifyAccess = cloudflareAccessPlugin({ domain, aud })
  return verifyAccess(context)
}
