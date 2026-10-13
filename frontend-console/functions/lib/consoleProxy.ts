const CONSOLE_ROUTE = /^\/api\/v1\/console\/(?:session|overview(?:\/trend)?|pipeline-health|services(?:\/[A-Za-z0-9._-]{1,128}\/signals)?|incidents(?:\/[a-f0-9-]{36}(?:\/(?:report|evidence|timeline|ai-attempts))?)?)$/i

function error(status: number, code: string, message: string, requestId: string) {
  return Response.json({ code, message, requestId }, { status, headers: {
    'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'X-Request-Id': requestId,
  } })
}

/** Forward a small, read-only route allowlist. The Java origin verifies the Access JWT signature and claims. */
export async function proxyConsole(request: Request, apiOrigin: string, fetcher: typeof fetch = fetch): Promise<Response> {
  const requestId = crypto.randomUUID()
  if (request.method !== 'GET' && request.method !== 'HEAD') {
    const response = error(405, 'CONSOLE_ACCESS_READ_ONLY', '控制台仅支持读取', requestId)
    response.headers.set('Allow', 'GET, HEAD')
    return response
  }
  const url = new URL(request.url)
  if (!CONSOLE_ROUTE.test(url.pathname) || url.search.length > 2048) {
    return error(404, 'CONSOLE_ROUTE_NOT_FOUND', '未找到该接口', requestId)
  }
  if (apiOrigin !== 'https://ops-api.dduo.me') {
    return error(503, 'CONSOLE_ORIGIN_NOT_CONFIGURED', '控制台暂不可用', requestId)
  }
  const token = request.headers.get('Cf-Access-Jwt-Assertion')
  if (!token || token.length > 16384) return error(401, 'CONSOLE_ACCESS_REQUIRED', '请通过 Cloudflare 账号登录', requestId)

  try {
    const upstream = await fetcher(apiOrigin + url.pathname + url.search, {
      method: request.method, redirect: 'manual', signal: AbortSignal.timeout(10_000),
      headers: { 'Cf-Access-Jwt-Assertion': token, 'Accept': 'application/json',
        'Origin': url.origin, 'X-Request-Id': requestId },
    })
    if (upstream.status >= 300 && upstream.status < 400
        || !upstream.headers.get('Content-Type')?.includes('application/json')) {
      await upstream.body?.cancel()
      return error(502, 'CONSOLE_ORIGIN_UNAVAILABLE', '控制台暂不可用，请稍后重试', requestId)
    }
    // Stream the body; never forward cookies, credentials, redirects or arbitrary origin headers.
    return new Response(upstream.body, { status: upstream.status, headers: {
      'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store',
      'X-Content-Type-Options': 'nosniff', 'X-Request-Id': requestId,
    } })
  } catch {
    return error(502, 'CONSOLE_ORIGIN_UNAVAILABLE', '控制台暂不可用，请稍后重试', requestId)
  }
}
