const MAX_RESPONSE_BYTES = 1_000_000
const REQUEST_TIMEOUT_MS = 4_500

export interface VectorSample {
  metric: Record<string, string>
  timestamp: number
  value: number
}

export interface MatrixSeries {
  metric: Record<string, string>
  values: Array<{ timestamp: number; value: number }>
}

export interface PrometheusAlert {
  labels: Record<string, string>
  annotations: Record<string, string>
  activeAt: string | null
  state: string
}

type JsonRecord = Record<string, unknown>

function isRecord(value: unknown): value is JsonRecord {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function toStringRecord(value: unknown): Record<string, string> | null {
  if (!isRecord(value)) return null
  const entries: Array<[string, string]> = []
  for (const [key, item] of Object.entries(value)) {
    if (typeof item !== 'string') return null
    entries.push([key, item])
  }
  return Object.fromEntries(entries)
}

function finiteNumber(value: unknown): number | null {
  const parsed = typeof value === 'number' ? value : typeof value === 'string' ? Number(value) : Number.NaN
  return Number.isFinite(parsed) ? parsed : null
}

async function readBoundedJson(response: Response): Promise<unknown> {
  const declaredLength = Number(response.headers.get('content-length'))
  if (Number.isFinite(declaredLength) && declaredLength > MAX_RESPONSE_BYTES) {
    throw new Error('origin_response_too_large')
  }
  if (!response.body) throw new Error('origin_response_missing_body')

  const reader = response.body.getReader()
  const chunks: Uint8Array[] = []
  let total = 0
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    total += value.byteLength
    if (total > MAX_RESPONSE_BYTES) {
      await reader.cancel('response too large')
      throw new Error('origin_response_too_large')
    }
    chunks.push(value)
  }

  const body = new Uint8Array(total)
  let offset = 0
  for (const chunk of chunks) {
    body.set(chunk, offset)
    offset += chunk.byteLength
  }
  try {
    return JSON.parse(new TextDecoder().decode(body)) as unknown
  } catch {
    throw new Error('origin_response_invalid_json')
  }
}

function requireSuccessEnvelope(payload: unknown): JsonRecord {
  if (!isRecord(payload) || payload.status !== 'success' || !isRecord(payload.data)) {
    throw new Error('origin_response_invalid_envelope')
  }
  return payload.data
}

function parseVector(payload: unknown): VectorSample[] {
  const data = requireSuccessEnvelope(payload)
  if (data.resultType !== 'vector' || !Array.isArray(data.result)) {
    throw new Error('origin_response_not_vector')
  }

  const samples: VectorSample[] = []
  for (const item of data.result) {
    if (!isRecord(item) || !Array.isArray(item.value) || item.value.length !== 2) continue
    const metric = toStringRecord(item.metric)
    const timestamp = finiteNumber(item.value[0])
    const value = finiteNumber(item.value[1])
    if (metric && timestamp !== null && value !== null) samples.push({ metric, timestamp, value })
  }
  return samples
}

function parseMatrix(payload: unknown): MatrixSeries[] {
  const data = requireSuccessEnvelope(payload)
  if (data.resultType !== 'matrix' || !Array.isArray(data.result)) {
    throw new Error('origin_response_not_matrix')
  }

  const series: MatrixSeries[] = []
  for (const item of data.result) {
    if (!isRecord(item) || !Array.isArray(item.values)) continue
    const metric = toStringRecord(item.metric)
    if (!metric) continue
    const values: MatrixSeries['values'] = []
    for (const point of item.values) {
      if (!Array.isArray(point) || point.length !== 2) continue
      const timestamp = finiteNumber(point[0])
      const value = finiteNumber(point[1])
      if (timestamp !== null && value !== null) values.push({ timestamp, value })
    }
    series.push({ metric, values })
  }
  return series
}

function parseAlerts(payload: unknown): PrometheusAlert[] {
  const data = requireSuccessEnvelope(payload)
  if (!Array.isArray(data.alerts)) throw new Error('origin_response_not_alerts')
  const alerts: PrometheusAlert[] = []
  for (const item of data.alerts) {
    if (!isRecord(item)) continue
    const labels = toStringRecord(item.labels)
    const annotations = toStringRecord(item.annotations)
    if (!labels || !annotations || typeof item.state !== 'string') continue
    alerts.push({
      labels,
      annotations,
      state: item.state,
      activeAt: typeof item.activeAt === 'string' ? item.activeAt : null,
    })
  }
  return alerts
}

function validateBaseUrl(raw: string): URL {
  let url: URL
  try {
    url = new URL(raw)
  } catch {
    throw new Error('grafana_base_url_invalid')
  }
  const local = url.hostname === '127.0.0.1' || url.hostname === 'localhost'
  if (url.protocol !== 'https:' && !(local && url.protocol === 'http:')) {
    throw new Error('grafana_base_url_requires_https')
  }
  if (url.username || url.password) throw new Error('grafana_base_url_must_not_contain_credentials')
  if (!url.pathname.includes('/api/datasources/proxy/uid/')) {
    throw new Error('grafana_base_url_must_use_datasource_proxy')
  }
  url.search = ''
  url.hash = ''
  return url
}

export class PrometheusClient {
  private readonly baseUrl: URL
  private readonly headers: Headers

  constructor(env: Env) {
    this.baseUrl = validateBaseUrl(env.HOMEOPS_GRAFANA_BASE_URL)
    if (!env.HOMEOPS_GRAFANA_TOKEN || env.HOMEOPS_GRAFANA_TOKEN.startsWith('replace-')) {
      throw new Error('grafana_token_missing')
    }

    this.headers = new Headers({
      Accept: 'application/json',
      Authorization: `Bearer ${env.HOMEOPS_GRAFANA_TOKEN}`,
    })

    const clientId = env.HOMEOPS_ORIGIN_ACCESS_CLIENT_ID
    const clientSecret = env.HOMEOPS_ORIGIN_ACCESS_CLIENT_SECRET
    if (Boolean(clientId) !== Boolean(clientSecret)) throw new Error('origin_access_credentials_incomplete')
    if (clientId && clientSecret && !clientId.startsWith('replace-') && !clientSecret.startsWith('replace-')) {
      this.headers.set('CF-Access-Client-Id', clientId)
      this.headers.set('CF-Access-Client-Secret', clientSecret)
    }
  }

  async query(expression: string, time = Date.now() / 1_000): Promise<VectorSample[]> {
    const url = this.endpoint('/api/v1/query')
    url.searchParams.set('query', expression)
    url.searchParams.set('time', String(time))
    const payload = await this.fetchJson(url)
    return parseVector(payload)
  }

  async queryRange(expression: string, start: number, end: number, stepSeconds: number): Promise<MatrixSeries[]> {
    const url = this.endpoint('/api/v1/query_range')
    url.searchParams.set('query', expression)
    url.searchParams.set('start', String(start))
    url.searchParams.set('end', String(end))
    url.searchParams.set('step', String(stepSeconds))
    url.searchParams.set('limit', '16')
    const payload = await this.fetchJson(url)
    return parseMatrix(payload)
  }

  async alerts(): Promise<PrometheusAlert[]> {
    const payload = await this.fetchJson(this.endpoint('/api/v1/alerts'))
    return parseAlerts(payload)
  }

  private endpoint(path: string): URL {
    const base = this.baseUrl.toString().replace(/\/$/, '')
    return new URL(`${base}${path}`)
  }

  private async fetchJson(url: URL): Promise<unknown> {
    const response = await fetch(url, {
      method: 'GET',
      headers: this.headers,
      redirect: 'error',
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    })
    if (!response.ok) throw new Error(`origin_http_${response.status}`)
    return readBoundedJson(response)
  }
}

export const prometheusParsing = { parseVector, parseMatrix, parseAlerts }
