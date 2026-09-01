import { expect, test, type Page } from '@playwright/test'

const incidentId = '11111111-1111-4111-8111-111111111111'
const evidenceId = '22222222-2222-4222-8222-222222222222'

async function mockApi(page: Page) {
  await page.route('**/api/v1/console/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    const json = path.endsWith('/session') ? { principal:'aidiag-admin', idleExpiresAt:'2026-07-15T08:00:00Z', absoluteExpiresAt:'2026-07-16T00:00:00Z', csrfToken:'csrf' }
      : path.endsWith('/report') ? { id:'33333333-3333-4333-8333-333333333333', generatedAt:'2026-07-15T00:04:00Z', rootCause:'Model provider latency increased', description:'Metric, log and trace evidence align.', affectedService:'ai-chat-production', supportScore:91.5, verificationStatus:'SUPPORTED', supportingEvidence:3, opposingEvidence:0 }
      : path.endsWith('/evidence') ? [{ id:evidenceId, diagnosisRunId:'44444444-4444-4444-8444-444444444444', type:'TRACE', source:'tempo', serviceName:'ai-chat-production', startTime:'2026-07-15T00:00:00Z', endTime:'2026-07-15T00:05:00Z', collectedAt:'2026-07-15T00:03:00Z', summary:'Provider span exceeded the fixed reviewed threshold.', importance:92 }]
      : path.endsWith('/timeline') ? [{ evidenceId, type:'TRACE_CONFIRMATION', occurredAt:'2026-07-15T00:03:00Z', summary:'Redacted evidence collected' }]
      : path.endsWith('/ai-attempts') ? [{ id:'55555555-5555-4555-8555-555555555555', sourceReportId:'33333333-3333-4333-8333-333333333333', status:'COMPLETED', promptVersion:'stage4-v1', modelProvider:'deepseek', modelName:'deepseek-v4-flash', failureCode:null, createdAt:'2026-07-15T00:05:00Z', completedAt:'2026-07-15T00:05:02Z' }]
      : { id:incidentId, title:'AI-chat latency SLO breach', primaryService:'ai-chat-production', alertName:'AiChatLatencyHigh', severity:'CRITICAL', status:'RESOLVED', environment:'production', startedAt:'2026-07-15T00:00:00Z', detectedAt:'2026-07-15T00:01:00Z', lastAlertAt:'2026-07-15T00:05:00Z', resolvedAt:'2026-07-15T00:47:00Z', updatedAt:'2026-07-15T00:47:00Z', diagnosisRunId:'44444444-4444-4444-8444-444444444444', diagnosisRunStatus:'COMPLETED', attempt:1, failureCode:null, diagnosisStartedAt:'2026-07-15T00:01:10Z', diagnosisFinishedAt:'2026-07-15T00:04:00Z' }
    await route.fulfill({ status:200, contentType:'application/json', body:JSON.stringify(json) })
  })
}

test.beforeEach(async ({ page }) => { await mockApi(page) })

test('renders the evidence-led casefile without exposing raw payloads', async ({ page }) => {
  await page.goto(`/incidents/${incidentId}`)
  await expect(page.getByRole('heading', { name:'Incident Casefile' })).toBeVisible()
  await expect(page.getByText('Model provider latency increased')).toBeVisible()
  await expect(page.getByText('Provider span exceeded the fixed reviewed threshold.')).toBeVisible()
  const rawPayloadNotice = page.getByText('raw payload')
  if (!await rawPayloadNotice.isVisible()) await page.getByText('Limitations', { exact:true }).click()
  await expect(rawPayloadNotice).toBeVisible()
  await expect(page.locator('body')).not.toContainText('request_json')
})

test('keeps the casefile within the mobile viewport', async ({ page }) => {
  await page.goto(`/incidents/${incidentId}`)
  await expect(page.getByRole('heading', { name:'Incident Casefile' })).toBeVisible()
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
  expect(overflow).toBeLessThanOrEqual(1)
})

test('keeps the Casefile usable at a 200 percent reflow-equivalent width', async ({ page }) => {
  await page.setViewportSize({ width:640, height:720 })
  await page.goto(`/incidents/${incidentId}`)
  await expect(page.getByRole('heading', { name:'Incident Casefile' })).toBeVisible()
  await expect(page.getByRole('button', { name:/AI advisory/ })).toBeVisible()
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
  expect(overflow).toBeLessThanOrEqual(1)
})

test('returns to login with an explicit message after session expiry', async ({ page }) => {
  await page.route('**/api/v1/console/overview', (route) => route.fulfill({
    status: 401,
    contentType: 'application/json',
    body: JSON.stringify({ code: 'CONSOLE_SESSION_EXPIRED', message: 'expired', requestId: 'test-request' }),
  }))
  await page.goto('/')
  await expect(page).toHaveURL(/\/login\?reason=expired/)
  await expect(page.getByText('会话已过期，请重新登录。未提交的修改不会被重放。')).toBeVisible()
})
