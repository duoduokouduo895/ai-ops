import { onBeforeUnmount, onMounted, ref, type Ref } from 'vue'

export interface ChartSeries {
  label: string
  color: string
  /** [epochSeconds, value] */
  points: [number, number][]
}

/**
 * Categorical slots follow the entity, never its rank: a filter or a missing series must not repaint the rest.
 * Outcomes outside the bounded telemetry contract fold into a neutral "other" key.
 */
const OUTCOME_SLOTS: Record<string, string> = {
  success: 'var(--series-1)',
  timeout: 'var(--series-2)',
  error: 'var(--series-3)',
  cancelled: 'var(--series-4)',
}

export function seriesColor(label: string, single: boolean) {
  if (single) return 'var(--chart-single)'
  return OUTCOME_SLOTS[label] ?? 'var(--text-muted)'
}

const UNIT_LABEL: Record<string, string> = {
  'req/s': 'req/s',
  s: '',
  count: '次',
  streams: '条',
  conn: '个',
  events: '条',
  up: '',
}

export function formatValue(value: number | null | undefined, unit: string) {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—'
  if (unit === 'up') return value >= 0.5 ? '成功' : '失败'
  if (unit === 's') return value < 1 ? `${Math.round(value * 1000)} ms` : `${value.toFixed(2)} s`
  if (unit === 'req/s') return `${value < 10 ? value.toFixed(2) : Math.round(value)} req/s`
  const rounded = Number.isInteger(value) ? value : Number(value.toFixed(1))
  return `${rounded.toLocaleString('zh-CN')}${UNIT_LABEL[unit] ? ` ${UNIT_LABEL[unit]}` : ''}`
}

export function formatTick(value: number, unit: string) {
  if (unit === 's') return value < 1 && value > 0 ? `${Math.round(value * 1000)}ms` : `${Number(value.toFixed(2))}s`
  if (Math.abs(value) >= 1000) return `${Number((value / 1000).toFixed(1))}k`
  return `${Number(value.toFixed(2))}`
}

/** Clean upper bound and 3–5 evenly spaced ticks starting at zero. */
export function niceScale(maxValue: number): { max: number; ticks: number[] } {
  if (!Number.isFinite(maxValue) || maxValue <= 0) return { max: 1, ticks: [0, 0.5, 1] }
  const rough = maxValue / 4
  const magnitude = 10 ** Math.floor(Math.log10(rough))
  const step = [1, 2, 2.5, 5, 10].map((factor) => factor * magnitude).find((candidate) => candidate >= rough) ?? 10 * magnitude
  const max = Math.ceil(maxValue / step) * step
  const ticks: number[] = []
  for (let tick = 0; tick <= max + step / 2; tick += step) ticks.push(Number(tick.toPrecision(6)))
  return { max, ticks }
}

export function formatClock(epochSeconds: number, withDate = false) {
  return new Intl.DateTimeFormat('zh-CN', {
    ...(withDate ? { month: '2-digit', day: '2-digit' } : {}),
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(new Date(epochSeconds * 1000))
}

/** Width of an element, tracked so SVG text is drawn in real pixels instead of being stretched. */
export function useElementWidth(target: Ref<HTMLElement | null>, fallback = 640) {
  const width = ref(fallback)
  let observer: ResizeObserver | null = null
  onMounted(() => {
    if (!target.value) return
    width.value = target.value.clientWidth || fallback
    if (typeof ResizeObserver === 'undefined') return
    observer = new ResizeObserver((entries) => {
      const next = Math.round(entries[0]?.contentRect.width ?? 0)
      if (next > 0) width.value = next
    })
    observer.observe(target.value)
  })
  onBeforeUnmount(() => observer?.disconnect())
  return width
}
