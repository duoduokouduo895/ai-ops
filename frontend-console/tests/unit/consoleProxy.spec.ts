// @vitest-environment node
import { afterEach, describe, expect, it, vi } from 'vitest'
import { webcrypto } from 'node:crypto'
import { proxyConsole } from '../../functions/lib/consoleProxy'

vi.stubGlobal('crypto', webcrypto)
afterEach(() => vi.restoreAllMocks())
const origin = 'https://ops-api.dduo.me'
function request(path = '/api/v1/console/session', init: RequestInit = {}) {
  return new Request('https://ops.dduo.me' + path, { headers: { 'Cf-Access-Jwt-Assertion': 'signed-token' }, ...init })
}

describe('same-origin console proxy', () => {
  it('forwards only the Access token to a fixed origin and streams uncached JSON', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(Response.json({ principal: 'owner@example.com' }, {
      headers: { 'Set-Cookie': 'private-origin-cookie', 'Cf-Access-Jwt-Assertion': 'private-token' },
    }))
    const response = await proxyConsole(request('/api/v1/console/incidents?limit=20', { headers: {
      'Cf-Access-Jwt-Assertion': 'signed-token', 'Cookie': 'do-not-forward',
      'Authorization': 'Basic do-not-forward', 'Cf-Access-Authenticated-User-Email': 'forged@example.com',
    } }), origin, fetcher)
    expect(fetcher).toHaveBeenCalledOnce()
    const [url, options] = fetcher.mock.calls[0]!
    expect(url).toBe(origin + '/api/v1/console/incidents?limit=20')
    const headers = new Headers(options?.headers)
    expect(headers.get('Cf-Access-Jwt-Assertion')).toBe('signed-token')
    expect(headers.has('Cookie')).toBe(false)
    expect(headers.has('Authorization')).toBe(false)
    expect(headers.has('Cf-Access-Authenticated-User-Email')).toBe(false)
    expect(options?.redirect).toBe('manual')
    expect(response.headers.get('Cache-Control')).toBe('no-store')
    expect(response.headers.has('Set-Cookie')).toBe(false)
    expect(response.headers.has('Cf-Access-Jwt-Assertion')).toBe(false)
    expect(await response.json()).toEqual({ principal: 'owner@example.com' })
  })

  it('rejects missing credentials, unsafe routes, writes and unreviewed origins before fetching', async () => {
    const fetcher = vi.fn<typeof fetch>()
    expect((await proxyConsole(request(undefined, { headers: {} }), origin, fetcher)).status).toBe(401)
    expect((await proxyConsole(request('/api/v1/console/other'), origin, fetcher)).status).toBe(404)
    expect((await proxyConsole(request('/actuator/prometheus'), origin, fetcher)).status).toBe(404)
    expect((await proxyConsole(request(undefined, { method: 'POST' }), origin, fetcher)).status).toBe(405)
    expect((await proxyConsole(request(), 'https://evil.example', fetcher)).status).toBe(503)
    expect(fetcher).not.toHaveBeenCalled()
  })

  it('preserves origin 401 and never follows login redirects or returns login HTML as API data', async () => {
    const fetcher = vi.fn<typeof fetch>()
    fetcher.mockResolvedValueOnce(Response.json({ code: 'CONSOLE_ACCESS_REQUIRED' }, { status: 401 }))
    expect((await proxyConsole(request(), origin, fetcher)).status).toBe(401)
    fetcher.mockResolvedValueOnce(new Response(null, { status: 302, headers: { Location: 'https://login.example' } }))
    expect((await proxyConsole(request(), origin, fetcher)).status).toBe(502)
    fetcher.mockResolvedValueOnce(new Response('<html>Login</html>', { headers: { 'Content-Type': 'text/html' } }))
    expect((await proxyConsole(request(), origin, fetcher)).status).toBe(502)
    fetcher.mockRejectedValueOnce(new Error('private connection details'))
    const response = await proxyConsole(request(), origin, fetcher)
    expect(response.status).toBe(502)
    expect(await response.text()).not.toContain('private connection details')
  })
})
