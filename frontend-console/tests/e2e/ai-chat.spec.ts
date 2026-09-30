import { expect, test, type Page } from '@playwright/test'

const END = Date.parse('2026-09-30T12:00:00Z') / 1000
const incidentId = '11111111-1111-4111-8111-111111111111'

function wave(count: number, fn: (index: number) => number, gapFrom = -1, gapTo = -1) {
  const points = []
  for (let index = 0; index < count; index++) {
    if (index >= gapFrom && index < gapTo) continue
    points.push({ t: END - (count - 1 - index) * 30, v: Number(fn(index).toFixed(3)) })
  }
  return points
}

function signal(id: string, group: string, title: string, unit: string, series: { label: string; points: { t: number; v: number }[] }[], extra: Record<string, unknown> = {}) {
  const last = series[0]?.points.at(-1)?.v ?? null
  return { id, group, title, unit, description: `${title} 的固定查询说明。`, threshold: null, alertName: null, alertState: null, status: series.length ? 'OK' : 'NO_DATA', reason: null, current: last, series, ...extra }
}

const signals = {
  serviceId: 'ai-chat', displayName: 'AI-chat', environment: 'production', otelServiceName: 'ai-chat-production', range: '1h',
  start: new Date((END - 3600) * 1000).toISOString(), end: new Date(END * 1000).toISOString(), stepSeconds: 30, queriedAt: new Date(END * 1000).toISOString(),
  signals: [
    signal('availability', 'golden', '外部可用性', 'up', [{ label: 'availability', points: wave(121, (i) => (i > 70 && i < 76 ? 0 : 1)) }], { alertName: 'AiChatProcessUnavailable', alertState: 'INACTIVE' }),
    signal('chat-throughput', 'golden', '聊天请求（按结果）', 'req/s', [
      { label: 'success', points: wave(121, (i) => 1.2 + Math.sin(i / 9) * 0.4) },
      { label: 'timeout', points: wave(121, (i) => (i > 90 ? 0.25 : 0.01)) },
    ]),
    signal('ttft-p95', 'golden', '首 Token 延迟 P95', 's', [{ label: 'ttft-p95', points: wave(121, (i) => 0.8 + (i > 88 ? 2.4 : Math.cos(i / 7) * 0.2)) }]),
    signal('model-timeouts', 'golden', '模型超时（5m）', 'count', [{ label: 'model-timeouts', points: wave(121, (i) => (i > 92 ? 4 : 0)) }], { threshold: 1, alertName: 'AiChatModelTimeout', alertState: 'FIRING', status: 'BREACH' }),
    signal('active-streams', 'saturation', '活跃 SSE 流', 'streams', [{ label: 'active-streams', points: wave(121, (i) => 6 + Math.round(Math.sin(i / 6) * 3), 40, 52) }]),
    signal('r2dbc-pending', 'saturation', 'R2DBC 等待获取', 'conn', [{ label: 'r2dbc-pending', points: wave(121, () => 0) }], { threshold: 1, alertName: 'AiChatR2dbcPoolExhaustion', alertState: 'INACTIVE' }),
    signal('document-backlog', 'saturation', '文档 Outbox 积压', 'events', [], { threshold: 10, alertName: 'AiChatDocumentBacklog', alertState: 'INACTIVE', status: 'NO_DATA' }),
    signal('search-errors', 'dependency', '知识检索失败（5m）', 'count', [], { threshold: 3, alertName: 'AiChatSearchUnavailable', alertState: 'UNKNOWN', status: 'QUERY_FAILED', reason: 'QUERY_TIMEOUT' }),
  ],
}

const trend = {
  days: 14, zone: 'UTC',
  items: Array.from({ length: 14 }, (_, index) => ({
    day: new Date(Date.UTC(2026, 8, 17 + index)).toISOString().slice(0, 10),
    opened: [0, 1, 0, 2, 1, 0, 0, 3, 1, 0, 1, 4, 2, 1][index],
    critical: [0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 2, 1, 0][index],
    resolved: [0, 1, 0, 1, 2, 0, 0, 2, 2, 0, 1, 3, 2, 1][index],
  })),
}

const summary = {
  id: incidentId, title: 'AI-chat model stream timed out', primaryService: 'ai-chat-production', alertName: 'AiChatModelTimeout',
  severity: 'WARNING', status: 'DIAGNOSED', startedAt: '2026-09-30T11:47:00Z', lastAlertAt: '2026-09-30T11:58:00Z', resolvedAt: null, hasReport: true, evidenceCount: 3,
}

async function mockApi(page: Page) {
  await page.route('**/api/v1/console/**', async (route) => {
    const url = new URL(route.request().url())
    const path = url.pathname
    const json = path.endsWith('/session') ? { principal: 'aidiag-admin', idleExpiresAt: '2026-10-01T08:00:00Z', absoluteExpiresAt: '2026-10-02T00:00:00Z', csrfToken: 'csrf' }
      : path.endsWith('/signals') ? { ...signals, range: url.searchParams.get('range') ?? '1h' }
      : path.endsWith('/overview/trend') ? trend
      : path.endsWith('/overview') ? { activeIncidents: 1, pendingRuns: 0, recoveredLast24Hours: 2, failedIncidents: 0, lastEvidenceAt: '2026-09-30T11:49:00Z' }
      : path.endsWith('/pipeline-health') ? { pipelineStatus: 'IDLE', pendingRuns: 0 }
      : path === '/api/v1/console/incidents' ? { items: [summary], nextCursor: null, hasMore: false }
      : path.endsWith('/evidence') ? [{
        id: '22222222-2222-4222-8222-222222222222', diagnosisRunId: '44444444-4444-4444-8444-444444444444', type: 'METRIC', source: 'prometheus', serviceName: 'ai-chat-production',
        startTime: '2026-09-30T11:37:00Z', endTime: '2026-09-30T11:52:00Z', collectedAt: '2026-09-30T11:49:00Z', summary: '固定 PromQL（model timeout requests in 5m）最大值 4.0，阈值 1.0', importance: 30,
        metric: { threshold: 1, maximum: 4, points: Array.from({ length: 31 }, (_, i) => [Date.parse('2026-09-30T11:37:00Z') / 1000 + i * 30, i > 18 ? 4 : 0]) },
      }]
      : path.endsWith('/report') || path.endsWith('/timeline') || path.endsWith('/ai-attempts') ? (path.endsWith('/report') ? null : [])
      : { ...summary, environment: 'production', detectedAt: summary.startedAt, updatedAt: summary.lastAlertAt, diagnosisRunId: null, diagnosisRunStatus: null, attempt: null, failureCode: null, diagnosisStartedAt: null, diagnosisFinishedAt: null }
    if (json === null) return route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ code: 'CONSOLE_RESOURCE_NOT_FOUND', message: 'x', requestId: 'r' }) })
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(json) })
  })
}

test.beforeEach(async ({ page }) => { await mockApi(page) })

test('AI-chat page shows fixed signals, rule state and related incidents', async ({ page }, info) => {
  await page.goto('/ai-chat')
  await expect(page.getByRole('heading', { name: 'AI-chat', level: 1 })).toBeVisible()
  await expect(page.getByRole('heading', { name: '模型超时（5m）' })).toBeVisible()
  await expect(page.getByText('AiChatModelTimeout').first()).toBeVisible()
  await expect(page.getByText('QUERY_TIMEOUT')).toBeVisible()
  await expect(page.getByText('AI-chat model stream timed out')).toBeVisible()
  await page.getByRole('radio', { name: '6h' }).click()
  await expect(page).toHaveURL(/range=6h/)
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
  expect(overflow).toBeLessThanOrEqual(1)
  const chart = page.getByRole('img', { name: /模型超时/ })
  await chart.focus()
  await page.keyboard.press('End')
  await expect(page.locator('.tooltip').first()).toContainText('4 次')
  await page.screenshot({ path: `test-results/screens/ai-chat-${info.project.name}.png`, fullPage: true })
})

test('Inbox shows the 14-day incident trend', async ({ page }, info) => {
  await page.goto('/')
  await expect(page.getByText('Incident trend · 14d')).toBeVisible()
  await expect(page.locator('.trend path.critical').first()).toBeVisible()
  await page.screenshot({ path: `test-results/screens/inbox-${info.project.name}.png`, fullPage: true })
})

test('Casefile draws the metric evidence curve against its threshold', async ({ page }, info) => {
  await page.goto(`/incidents/${incidentId}`)
  await page.getByRole('heading', { name: 'Evidence', exact: true }).click()
  await expect(page.locator('.metric-curve .threshold text')).toContainText('阈值 1')
  await page.locator('.metric-curve').scrollIntoViewIfNeeded()
  await page.screenshot({ path: `test-results/screens/casefile-${info.project.name}.png`, fullPage: true })
})
