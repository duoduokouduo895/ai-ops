import type { HistoryResponse, Overview } from '../shared/homeops'
import { demoHistory, demoOverview } from './demo'

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isOverview(value: unknown): value is Overview {
  return isRecord(value)
    && typeof value.generatedAt === 'string'
    && typeof value.siteLabel === 'string'
    && typeof value.headline === 'string'
    && isRecord(value.freshness)
    && isRecord(value.power)
    && isRecord(value.system)
    && Array.isArray(value.services)
    && Array.isArray(value.alerts)
    && Array.isArray(value.limitations)
}

function isHistory(value: unknown): value is HistoryResponse {
  return isRecord(value)
    && typeof value.generatedAt === 'string'
    && Array.isArray(value.series)
    && value.series.every((item) => isRecord(item) && typeof item.key === 'string' && Array.isArray(item.points))
}

function useDemo(): boolean {
  return import.meta.env.VITE_HOMEOPS_DEMO === 'true' || (import.meta.env.DEV && import.meta.env.VITE_HOMEOPS_DEMO !== 'false')
}

async function getJson(path: string): Promise<unknown> {
  const response = await fetch(path, {
    headers: { Accept: 'application/json' },
    credentials: 'same-origin',
    signal: AbortSignal.timeout(8_000),
  })
  if (!response.ok) throw new Error(`HomeOps API 返回 ${response.status}`)
  return response.json() as Promise<unknown>
}

export async function fetchOverview(): Promise<Overview> {
  if (useDemo()) return { ...demoOverview, generatedAt: new Date().toISOString() }
  const payload = await getJson('/api/overview')
  if (!isOverview(payload)) throw new Error('HomeOps 概览响应格式不正确')
  return payload
}

export async function fetchHistory(range: '6h' | '24h' | '7d' = '24h'): Promise<HistoryResponse> {
  if (useDemo()) return { ...demoHistory, range, generatedAt: new Date().toISOString() }
  const payload = await getJson(`/api/history?range=${range}`)
  if (!isHistory(payload)) throw new Error('HomeOps 趋势响应格式不正确')
  return payload
}
