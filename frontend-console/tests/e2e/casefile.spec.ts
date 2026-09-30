import { expect, test, type Page } from '@playwright/test'

const incidentId = '11111111-1111-4111-8111-111111111111'
const evidenceId = '22222222-2222-4222-8222-222222222222'
const logEvidenceId = '66666666-6666-4666-8666-666666666666'

async function mockApi(page: Page) {
  await page.route('**/api/v1/console/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    const incident = {
      id: incidentId, title: 'AI-chat latency SLO breach', primaryService: 'ai-chat-production',
      alertName: 'AiChatLatencyHigh', severity: 'CRITICAL', status: 'RESOLVED', environment: 'production',
      startedAt: '2026-07-15T00:00:00Z', detectedAt: '2026-07-15T00:01:00Z', lastAlertAt: '2026-07-15T00:05:00Z',
      resolvedAt: '2026-07-15T00:47:00Z', updatedAt: '2026-07-15T00:47:00Z',
      diagnosisRunId: '44444444-4444-4444-8444-444444444444', diagnosisRunStatus: 'COMPLETED', attempt: 1,
      failureCode: null, diagnosisStartedAt: '2026-07-15T00:01:10Z', diagnosisFinishedAt: '2026-07-15T00:04:00Z',
    }
    const summary = { ...incident, hasReport: true, evidenceCount: 2 }
    const json = path.endsWith('/session') ? { principal: 'aidiag-admin', idleExpiresAt: '2026-07-15T08:00:00Z', absoluteExpiresAt: '2026-07-16T00:00:00Z', csrfToken: 'csrf' }
      : path.endsWith('/overview') ? { activeIncidents: 1, pendingRuns: 1, recoveredLast24Hours: 4, failedIncidents: 0, lastEvidenceAt: '2026-07-15T00:03:00Z' }
      : path.endsWith('/pipeline-health') ? { queriedAt: '2026-07-15T00:05:00Z', lastSuccessfulCheckAt: '2026-07-15T00:05:00Z', freshness: 'FRESH', reason: 'FRESH', databaseStatus: 'UP', activeIncidents: 1, pendingRuns: 1, waitingRuns: 0, inProgressRuns: 1, oldestPendingAgeSeconds: 20, lastAlertAt: '2026-07-15T00:05:00Z', lastEvidenceAt: '2026-07-15T00:03:00Z', lastReportAt: '2026-07-15T00:04:00Z', pipelineStatus: 'IN_PROGRESS' }
      : path.endsWith('/services') ? { version: '1', compiledAt: '2026-07-15T00:00:00Z', sourceDigest: 'abc', queriedAt: '2026-07-15T00:05:00Z', services: [{ id: 'ai-chat', displayName: 'AI-chat Production', otelServiceName: 'ai-chat-production', environment: 'production', diagnosisProfile: 'ai-chat-v1', status: 'UP', applicationStatus: 'UP', queriedAt: '2026-07-15T00:05:00Z', sampledAt: '2026-07-15T00:04:50Z', freshness: 'FRESH', reason: 'FRESH', dependencies: [{ id: 'postgresql', kind: 'DATABASE', status: 'UP', observedAt: '2026-07-15T00:05:00Z', sampledAt: '2026-07-15T00:04:50Z', freshness: 'FRESH', reason: 'FRESH' }, { id: 'model-provider', kind: 'EXTERNAL', status: 'UNKNOWN', observedAt: '2026-07-15T00:05:00Z', sampledAt: '2026-07-14T00:03:00Z', freshness: 'STALE', reason: 'STALE' }], grafanaUrl: 'http://127.0.0.1:13000' }] }
      : path.endsWith('/report') ? { id: '33333333-3333-4333-8333-333333333333', generatedAt: '2026-07-15T00:04:00Z', rootCause: 'Model provider latency increased', description: 'Metric, log and trace evidence align.', affectedService: 'ai-chat-production', supportScore: 91.5, verificationStatus: 'SUPPORTED', supportingEvidence: 3, opposingEvidence: 0, disposition: 'CONFIRMED_ROOT_CAUSE', candidates: [] }
      : path.endsWith('/evidence') ? [
        { id: evidenceId, diagnosisRunId: '44444444-4444-4444-8444-444444444444', type: 'TRACE', source: 'tempo', serviceName: 'ai-chat-production', startTime: '2026-07-15T00:00:00Z', endTime: '2026-07-15T00:05:00Z', collectedAt: '2026-07-15T00:03:00Z', summary: 'Provider span exceeded the fixed reviewed threshold.', importance: 92 },
        { id: logEvidenceId, diagnosisRunId: '44444444-4444-4444-8444-444444444444', type: 'LOG', source: 'loki', serviceName: 'ai-chat-production', startTime: '2026-07-15T00:00:00Z', endTime: '2026-07-15T00:05:00Z', collectedAt: '2026-07-15T00:02:30Z', summary: 'Timeout summaries aligned with the metric window.', importance: 82 },
      ]
      : path.endsWith('/timeline') ? [
        { evidenceId, type: 'EVIDENCE_TRACE', occurredAt: '2026-07-15T00:03:00Z', summary: 'Redacted trace evidence collected', referenceKind: 'EVIDENCE', referenceId: evidenceId },
        { evidenceId: logEvidenceId, type: 'EVIDENCE_LOG', occurredAt: '2026-07-15T00:02:30Z', summary: 'Redacted log evidence collected', referenceKind: 'EVIDENCE', referenceId: logEvidenceId },
        { evidenceId: '77777777-7777-4777-8777-777777777777', type: 'ALERT_RESOLVED', occurredAt: '2026-07-15T00:47:00Z', summary: 'Alertmanager state received', referenceKind: 'ALERT_EVENT', referenceId: '77777777-7777-4777-8777-777777777777' },
      ]
      : path.endsWith('/ai-attempts') ? [{ id: '55555555-5555-4555-8555-555555555555', sourceReportId: '33333333-3333-4333-8333-333333333333', status: 'COMPLETED', promptVersion: 'stage4-v1', modelProvider: 'deepseek', modelName: 'deepseek-v4-flash', failureCode: null, createdAt: '2026-07-15T00:05:00Z', completedAt: '2026-07-15T00:05:02Z' }]
      : path === '/api/v1/console/incidents' ? { items: [summary], nextCursor: null, hasMore: false }
      : incident
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(json) })
  })
}

async function expand(page: Page, title: string) {
  await page.getByRole('heading', { name: title, exact: true }).click()
}

test.beforeEach(async ({ page }) => { await mockApi(page) })

test('renders the conclusion-first casefile without exposing raw payloads', async ({ page }) => {
  await page.goto(`/incidents/${incidentId}`)
  await expect(page.getByRole('heading', { name: 'Incident Casefile' })).toBeVisible()
  await expect(page.getByText('Model provider latency increased')).toBeVisible()
  await expect(page.getByText('raw payload')).toBeVisible()
  await expect(page.getByText('Provider span exceeded the fixed reviewed threshold.')).toBeHidden()
  await expand(page, 'Evidence')
  await expect(page.getByText('Provider span exceeded the fixed reviewed threshold.')).toBeVisible()
  await expect(page.locator('body')).not.toContainText('request_json')
})

test('keeps the casefile within the mobile viewport', async ({ page }) => {
  await page.goto(`/incidents/${incidentId}`)
  await expect(page.getByRole('heading', { name: 'Incident Casefile' })).toBeVisible()
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
  expect(overflow).toBeLessThanOrEqual(1)
})

test('keeps the Casefile usable at a 200 percent reflow-equivalent width', async ({ page }) => {
  await page.setViewportSize({ width: 640, height: 720 })
  await page.goto(`/incidents/${incidentId}`)
  await expect(page.getByRole('heading', { name: 'Incident Casefile' })).toBeVisible()
  await expand(page, 'Run details 与 AI advisory')
  await expect(page.getByRole('heading', { name: 'AI advisory', exact: true })).toBeVisible()
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
  expect(overflow).toBeLessThanOrEqual(1)
})

test('keeps timeline selection and evidence tabs synchronized', async ({ page }) => {
  await page.goto(`/incidents/${incidentId}`)
  await expand(page, 'Evidence')
  await page.getByRole('button', { name: 'LOG', exact: true }).click()
  await expect(page.getByText('Timeout summaries aligned with the metric window.')).toBeVisible()
  await expand(page, 'Timeline')
  await page.getByRole('button', { name: /EVIDENCE TRACE/ }).click()
  await expect(page.getByRole('button', { name: 'TRACE', exact: true })).toHaveAttribute('aria-pressed', 'true')
  await expect(page.getByText('Provider span exceeded the fixed reviewed threshold.')).toBeVisible()
})

test('does not treat alert timeline rows as evidence selectors', async ({ page }) => {
  await page.goto(`/incidents/${incidentId}`)
  await expand(page, 'Timeline')
  await expect(page.getByText('ALERT RESOLVED')).toBeVisible()
  await expect(page.getByRole('button', { name: /ALERT RESOLVED/ })).toHaveCount(0)
})

test('moves focus to the destination heading after route navigation', async ({ page }) => {
  await page.goto(`/incidents/${incidentId}`)
  await page.getByRole('link', { name: 'Incidents', exact: true }).last().click()
  await expect(page.getByRole('heading', { name: 'Incidents', exact: true })).toBeFocused()
})

test('closes mobile navigation with Escape', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/')
  await page.getByRole('button', { name: 'Menu' }).click()
  await expect(page.getByRole('navigation', { name: '移动端主导航' })).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('navigation', { name: '移动端主导航' })).toBeHidden()
})

test('keeps evidence visible with reduced motion enabled', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.goto(`/incidents/${incidentId}`)
  await expand(page, 'Evidence')
  await expect(page.getByText('Provider span exceeded the fixed reviewed threshold.')).toBeVisible()
  await expand(page, 'Timeline')
  const transitionDurations = await page.getByRole('button', { name: /EVIDENCE TRACE/ }).evaluate((element) =>
    getComputedStyle(element).transitionDuration.split(',').map((value) => {
      const duration = Number.parseFloat(value)
      return value.trim().endsWith('ms') ? duration : duration * 1000
    }),
  )
  expect(Math.max(...transitionDurations)).toBeLessThan(1)
})

test('returns to login with an explicit message after session expiry', async ({ page }) => {
  await page.route('**/api/v1/console/overview', (route) => route.fulfill({
    status: 401,
    contentType: 'application/json',
    body: JSON.stringify({ code: 'CONSOLE_SESSION_EXPIRED', message: 'expired', requestId: 'test-request' }),
  }))
  await page.goto('/')
  await expect(page).toHaveURL(/\/login\?reason=expired/)
  await expect(page.getByText('会话已过期，请重新登录。')).toBeVisible()
  await expect(page.getByText(/会话 Cookie/)).toHaveCount(0)
  await expect(page.getByText('从告警到证据')).toHaveCount(0)
})

test('shows a compact inbox summary without treating pending runs as watching', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'Inbox', exact: true })).toBeVisible()
  await expect(page.getByText('Active incidents')).toBeVisible()
  await expect(page.getByText('Pending runs')).toBeVisible()
  await expect(page.getByText('Resolved · 24h')).toBeVisible()
  await expect(page.getByText('观察中')).toHaveCount(0)
})

test('opens status explanations without leaving the incident list', async ({ page }) => {
  await page.goto('/incidents')
  await expect(page.getByRole('heading', { name: 'Incidents', exact: true })).toBeVisible()
  const status = page.getByRole('button', { name: '查看 RESOLVED 的含义' })
  await status.hover()
  const hint = page.getByRole('tooltip')
  await expect(hint).toContainText('RESOLVED · 已记录恢复')
  await expect(hint).toContainText('这条事件已记录恢复')
  await hint.hover()
  await expect(hint).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('tooltip')).toHaveCount(0)
  await expect(hint).toHaveCount(0)
  await status.click()
  await expect(page.getByText('RESOLVED · 已记录恢复')).toBeVisible()
  await expect(page.getByRole('heading', { name: /页面说明/ })).toHaveCount(0)
  await expect(page).toHaveURL(/\/incidents/)
  await page.keyboard.press('Escape')
  await expect(page.getByText('RESOLVED · 已记录恢复')).toHaveCount(0)
})

test('keeps stale samples visible on Services', async ({ page }) => {
  await page.goto('/services')
  await expect(page.getByRole('heading', { name: 'Services', exact: true })).toBeVisible()
  await page.getByText('查看依赖').click()
  await expect(page.getByText(/上次采样 .* · 已过期/)).toBeVisible()
})

test('keeps insufficient evidence in the casefile conclusion', async ({ page }) => {
  await page.route('**/api/v1/console/incidents/*/report', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: '33333333-3333-4333-8333-333333333333',
        generatedAt: '2026-07-15T00:04:00Z',
        rootCause: null,
        description: null,
        affectedService: 'ai-chat-production',
        supportScore: 12,
        verificationStatus: 'INSUFFICIENT_EVIDENCE',
        supportingEvidence: 0,
        opposingEvidence: 0,
        disposition: 'INSUFFICIENT_EVIDENCE',
        candidates: [],
      }),
    })
  })
  await page.goto(`/incidents/${incidentId}`)
  await expect(page.getByText('报告证据不足，没有受支持根因')).toBeVisible()
  await expect(page.getByRole('tooltip')).toHaveCount(0)
})

test('keeps login and list pages within 1440, 768 and 390 viewports', async ({ page }) => {
  const paths = ['/', '/incidents', `/incidents/${incidentId}`, '/services']
  for (const viewport of [{ width: 1440, height: 900 }, { width: 768, height: 1024 }, { width: 390, height: 844 }]) {
    await page.setViewportSize(viewport)
    for (const path of paths) {
      await page.goto(path)
      await expect(page.getByRole('heading').first()).toBeVisible()
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
      expect(overflow, `${path} at ${viewport.width}`).toBeLessThanOrEqual(1)
    }
  }
})

test('restores incident filters from the URL after opening a casefile', async ({ page }) => {
  await page.goto('/incidents')
  await page.getByLabel('状态', { exact: true }).selectOption('RESOLVED')
  await expect(page).toHaveURL(/status=RESOLVED/)
  await page.getByRole('link', { name: /AI-chat latency SLO breach/ }).click()
  await expect(page.getByRole('heading', { name: 'Incident Casefile' })).toBeVisible()
  await page.getByRole('link', { name: 'Incidents', exact: true }).last().click()
  await expect(page).toHaveURL(/status=RESOLVED/)
  await expect(page.getByLabel('状态', { exact: true })).toHaveValue('RESOLVED')
})
