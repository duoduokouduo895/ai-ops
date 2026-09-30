import type { HistoryResponse, HistorySeries } from '../../shared/homeops'
import { PrometheusClient } from '../lib/prometheus'

const RANGE_CONFIG = {
  '6h': { seconds: 6 * 60 * 60, step: 120 },
  '24h': { seconds: 24 * 60 * 60, step: 300 },
  '7d': { seconds: 7 * 24 * 60 * 60, step: 1_800 },
} as const

const HISTORY_QUERIES: Array<{ key: HistorySeries['key']; label: string; unit: HistorySeries['unit']; expression: string }> = [
  { key: 'cpu', label: 'CPU', unit: '%', expression: '100 * (1 - avg(rate(node_cpu_seconds_total{job="home-node",mode="idle"}[5m])))' },
  { key: 'memory', label: '内存', unit: '%', expression: '100 * (1 - node_memory_MemAvailable_bytes{job="home-node"} / node_memory_MemTotal_bytes{job="home-node"})' },
  { key: 'temperature', label: 'CPU 温度', unit: '°C', expression: 'homeops_temperature_celsius{sensor="cpu"}' },
]

export const onRequestGet: PagesFunction<Env> = async ({ request, env }) => {
  const requestedRange = new URL(request.url).searchParams.get('range') ?? '24h'
  if (!(requestedRange in RANGE_CONFIG)) {
    return Response.json({ error: 'range 仅支持 6h、24h 或 7d。' }, { status: 400, headers: { 'Cache-Control': 'no-store' } })
  }
  const range = requestedRange as keyof typeof RANGE_CONFIG
  const config = RANGE_CONFIG[range]
  const end = Date.now() / 1_000
  const start = end - config.seconds

  try {
    const client = new PrometheusClient(env)
    const series = await Promise.all(HISTORY_QUERIES.map(async (definition): Promise<HistorySeries> => {
      const result = await client.queryRange(definition.expression, start, end, config.step)
      return {
        key: definition.key,
        label: definition.label,
        unit: definition.unit,
        points: (result[0]?.values ?? []).map((point) => ({ timestamp: point.timestamp, value: Math.round(point.value * 10) / 10 })),
      }
    }))
    const response: HistoryResponse = { generatedAt: new Date().toISOString(), range, series }
    return Response.json(response, { headers: { 'Cache-Control': 'private, max-age=15', 'X-Content-Type-Options': 'nosniff' } })
  } catch (error) {
    console.error(JSON.stringify({ message: 'homeops_history_failed', error: error instanceof Error ? error.message : 'unknown_error' }))
    return Response.json({ error: '历史趋势暂时不可用。' }, { status: 502, headers: { 'Cache-Control': 'no-store' } })
  }
}
